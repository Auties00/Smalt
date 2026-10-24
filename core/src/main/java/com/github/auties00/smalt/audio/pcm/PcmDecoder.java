package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes uncompressed PCM packets into planar float samples.
 *
 * <p>Every {@code PCM_*} {@link Codec} is supported: unsigned and signed 8-bit, signed 16, 24
 * (three packed bytes), 32 and 64-bit integers, 32 and 64-bit IEEE floats, each in the byte order
 * the codec names, and ITU-T G.711 A-law and mu-law. A packet holds whole interleaved frames of
 * {@code channels * bytesPerSample} bytes; trailing bytes that do not complete a frame are
 * ignored.
 *
 * <p>Integers of {@code n} bits are scaled by {@code 2^-(n-1)}, so the most negative value maps
 * to exactly {@code -1.0}; unsigned 8-bit samples are first offset by 128, and 64-bit samples are
 * rounded to the nearest float before they are scaled. G.711 codes are
 * expanded to the 16-bit linear values of the standard and scaled like 16-bit samples. Finite
 * float samples pass through unchanged; NaN decodes to zero and infinities to {@code +-1}, so a
 * hostile file cannot poison later processing.
 *
 * <p>Integer formats of up to 32 bits also decode to their integers bit for bit
 * ({@link #decodeIntegers(Packet, Consumer)}): unsigned 8-bit samples offset by 128 as 8-bit
 * samples, and G.711 codes as their 16-bit linear values. Every format also decodes to 64-bit
 * floats ({@link #decodeDoubles(Packet, Consumer)}), scaled as above but at double precision: exact
 * for 64-bit floats and every integer format but 64-bit integers, which are rounded to the nearest
 * double.
 *
 * <p>The track must declare its sample rate and channel layout (or at least a channel count
 * through its layout), because raw PCM carries neither.
 *
 * <p>Instances are not thread-safe.
 */
public final class PcmDecoder implements AudioDecoder {
    /**
     * The scale applied to 8-bit integers.
     */
    private static final float SCALE_8 = 0x1p-7f;

    /**
     * The scale applied to 16-bit integers.
     */
    private static final float SCALE_16 = 0x1p-15f;

    /**
     * The scale applied to 24-bit integers.
     */
    private static final float SCALE_24 = 0x1p-23f;

    /**
     * The scale applied to 32-bit integers.
     */
    private static final float SCALE_32 = 0x1p-31f;

    /**
     * The scale applied to 64-bit integers.
     */
    private static final float SCALE_64 = 0x1p-63f;

    /**
     * The float value of every A-law code, from the G.711 expansion.
     */
    private static final float[] ALAW = buildAlaw();

    /**
     * The float value of every mu-law code, from the G.711 expansion.
     */
    private static final float[] MULAW = buildMulaw();

    /**
     * The coding format decoded.
     */
    private final Codec codec;

    /**
     * The channel layout of the output.
     */
    private final ChannelLayout layout;

    /**
     * The sampling rate of the output.
     */
    private final int sampleRate;

    /**
     * The number of bytes of one coded sample.
     */
    private final int bytesPerSample;

    /**
     * The number of bytes of one interleaved frame.
     */
    private final int frameSize;

    /**
     * The buffer handed to consumers, reallocated when a packet exceeds its capacity.
     */
    private AudioBuffer buffer;

    /**
     * The sample size of the integer output, or 0 for formats without one.
     */
    private final int integerBits;

    /**
     * The integer buffer handed to consumers, or {@code null} before the first integer decode.
     */
    private IntegerAudioBuffer integers;

    /**
     * The double buffer handed to consumers, or {@code null} before the first double decode.
     */
    private DoubleAudioBuffer doubles;

    /**
     * Constructs a decoder for a PCM track.
     *
     * @param track the track to decode
     * @throws IllegalArgumentException if the track's codec is not a PCM codec
     * @throws MediaException.Malformed if the track declares no sample rate or no layout
     */
    public PcmDecoder(AudioTrack track) {
        Objects.requireNonNull(track, "track");
        this.codec = track.codec();
        if (!codec.isPcm()) {
            throw new IllegalArgumentException(codec + " is not a PCM codec");
        }
        if (track.sampleRate() <= 0) {
            throw new MediaException.Malformed("PCM track without a sample rate");
        }
        this.sampleRate = track.sampleRate();
        this.layout = track.layout().orElseThrow(() -> new MediaException.Malformed("PCM track without a channel layout"));
        this.bytesPerSample = bytesPerSample(codec);
        this.frameSize = bytesPerSample * layout.channels();
        this.buffer = new AudioBuffer(layout, sampleRate, 0);
        this.integerBits = switch (codec) {
            case PCM_U8, PCM_S8 -> 8;
            case PCM_ALAW, PCM_MULAW, PCM_S16LE, PCM_S16BE -> 16;
            case PCM_S24LE, PCM_S24BE -> 24;
            case PCM_S32LE, PCM_S32BE -> 32;
            default -> 0;
        };
    }

    /**
     * Returns the size of one coded sample of a PCM codec.
     *
     * @param codec the PCM codec
     * @return the size in bytes
     * @throws IllegalArgumentException if {@code codec} is not a PCM codec
     */
    static int bytesPerSample(Codec codec) {
        return codec.pcmBitsPerSample()
                .orElseThrow(() -> new IllegalArgumentException(codec + " is not a PCM codec")) / 8;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Integer formats of up to 32 bits and G.711 have integer output; 64-bit integers and floats
     * do not.
     */
    @Override
    public boolean supportsIntegerOutput() {
        return integerBits != 0;
    }

    @Override
    public int bitsPerSample() {
        return integerBits;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every PCM format has double output.
     */
    @Override
    public boolean supportsDoubleOutput() {
        return true;
    }

    /**
     * Decodes one packet of interleaved frames into a single block.
     *
     * <p>An empty packet, or one shorter than a frame, produces no block.
     *
     * @param packet the next packet
     * @param output receives the decoded block
     */
    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        var frames = data.length / frameSize;
        if (frames == 0) {
            return;
        }
        if (buffer.capacity() < frames) {
            buffer = new AudioBuffer(layout, sampleRate, frames);
        }
        var planes = buffer.planes();
        var channels = planes.length;
        for (var c = 0; c < channels; c++) {
            convert(data, c * bytesPerSample, frameSize, planes[c], frames);
        }
        buffer.setFrames(frames);
        output.accept(buffer);
    }

    /**
     * Decodes one packet of interleaved frames into a single block of integers.
     *
     * <p>An empty packet, or one shorter than a frame, produces no block.
     *
     * @param packet the next packet
     * @param output receives the decoded block
     * @throws UnsupportedOperationException if the format has no integer output
     */
    @Override
    public void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        if (integerBits == 0) {
            throw new UnsupportedOperationException(codec + " has no integer output");
        }
        var data = packet.data();
        var frames = data.length / frameSize;
        if (frames == 0) {
            return;
        }
        if (integers == null || integers.capacity() < frames) {
            integers = new IntegerAudioBuffer(layout, sampleRate, integerBits, frames);
        }
        var planes = integers.planes();
        for (var c = 0; c < planes.length; c++) {
            var target = planes[c];
            var position = c * bytesPerSample;
            for (var i = 0; i < frames; i++, position += frameSize) {
                target[i] = integerAt(data, position);
            }
        }
        integers.setFrames(frames);
        output.accept(integers);
    }

    /**
     * Does nothing: PCM decoding keeps no state between packets.
     *
     * @param output never called
     * @throws UnsupportedOperationException if the format has no integer output
     */
    @Override
    public void flushIntegers(Consumer<IntegerAudioBuffer> output) {
        if (integerBits == 0) {
            throw new UnsupportedOperationException(codec + " has no integer output");
        }
    }

    /**
     * Decodes one packet of interleaved frames into a single block of doubles.
     *
     * <p>An empty packet, or one shorter than a frame, produces no block.
     *
     * @param packet the next packet
     * @param output receives the decoded block
     */
    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        var frames = data.length / frameSize;
        if (frames == 0) {
            return;
        }
        if (doubles == null || doubles.capacity() < frames) {
            doubles = new DoubleAudioBuffer(layout, sampleRate, frames);
        }
        var planes = doubles.planes();
        for (var c = 0; c < planes.length; c++) {
            convertDoubles(data, c * bytesPerSample, frameSize, planes[c], frames);
        }
        doubles.setFrames(frames);
        output.accept(doubles);
    }

    /**
     * Does nothing: PCM decoding keeps no state between packets.
     *
     * @param output never called
     */
    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
    }

    /**
     * Returns the integer sample of an integer format.
     *
     * @param data the packet
     * @param position the index of the sample's first byte
     * @return the signed sample, unsigned 8-bit samples offset by 128 and G.711 codes expanded
     */
    private int integerAt(byte[] data, int position) {
        return switch (codec) {
            case PCM_U8 -> (data[position] & 0xFF) - 128;
            case PCM_S8 -> data[position];
            case PCM_ALAW -> alawToLinear(data[position] & 0xFF);
            case PCM_MULAW -> mulawToLinear(data[position] & 0xFF);
            case PCM_S16LE -> (short) ByteArrays.u16le(data, position);
            case PCM_S16BE -> (short) ByteArrays.u16be(data, position);
            case PCM_S24LE -> (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8 | data[position + 2] << 16;
            case PCM_S24BE -> data[position] << 16 | (data[position + 1] & 0xFF) << 8 | (data[position + 2] & 0xFF);
            case PCM_S32LE -> ByteArrays.i32le(data, position);
            case PCM_S32BE -> ByteArrays.i32be(data, position);
            default -> throw new IllegalStateException(codec + " has no integer output");
        };
    }

    /**
     * Converts the samples of one channel to doubles.
     *
     * @param data the interleaved packet
     * @param offset the index of the channel's first sample
     * @param stride the distance between consecutive samples of the channel, one frame
     * @param target the channel's plane
     * @param frames the number of samples
     */
    private void convertDoubles(byte[] data, int offset, int stride, double[] target, int frames) {
        var position = offset;
        switch (codec) {
            case PCM_S64LE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = ByteArrays.i64le(data, position) * 0x1p-63;
                }
            }
            case PCM_S64BE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = ByteArrays.i64be(data, position) * 0x1p-63;
                }
            }
            case PCM_F32LE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = sanitize(Float.intBitsToFloat(ByteArrays.i32le(data, position)));
                }
            }
            case PCM_F32BE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = sanitize(Float.intBitsToFloat(ByteArrays.i32be(data, position)));
                }
            }
            case PCM_F64LE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = sanitize(Double.longBitsToDouble(ByteArrays.i64le(data, position)));
                }
            }
            case PCM_F64BE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = sanitize(Double.longBitsToDouble(ByteArrays.i64be(data, position)));
                }
            }
            default -> {
                var scale = Math.scalb(1.0, 1 - integerBits);
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = integerAt(data, position) * scale;
                }
            }
        }
    }

    /**
     * Converts the samples of one channel.
     *
     * @param data the interleaved packet
     * @param offset the index of the channel's first sample
     * @param stride the distance between consecutive samples of the channel, one frame
     * @param target the channel's plane
     * @param frames the number of samples
     */
    private void convert(byte[] data, int offset, int stride, float[] target, int frames) {
        var position = offset;
        switch (codec) {
            case PCM_U8 -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = ((data[position] & 0xFF) - 128) * SCALE_8;
                }
            }
            case PCM_S8 -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = data[position] * SCALE_8;
                }
            }
            case PCM_ALAW -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = ALAW[data[position] & 0xFF];
                }
            }
            case PCM_MULAW -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = MULAW[data[position] & 0xFF];
                }
            }
            case PCM_S16LE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = (short) ByteArrays.u16le(data, position) * SCALE_16;
                }
            }
            case PCM_S16BE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = (short) ByteArrays.u16be(data, position) * SCALE_16;
                }
            }
            case PCM_S24LE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    var value = (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8 | data[position + 2] << 16;
                    target[i] = value * SCALE_24;
                }
            }
            case PCM_S24BE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    var value = data[position] << 16 | (data[position + 1] & 0xFF) << 8 | (data[position + 2] & 0xFF);
                    target[i] = value * SCALE_24;
                }
            }
            case PCM_S32LE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = ByteArrays.i32le(data, position) * SCALE_32;
                }
            }
            case PCM_S32BE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = ByteArrays.i32be(data, position) * SCALE_32;
                }
            }
            case PCM_S64LE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = ByteArrays.i64le(data, position) * SCALE_64;
                }
            }
            case PCM_S64BE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = ByteArrays.i64be(data, position) * SCALE_64;
                }
            }
            case PCM_F32LE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = sanitize(Float.intBitsToFloat(ByteArrays.i32le(data, position)));
                }
            }
            case PCM_F32BE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = sanitize(Float.intBitsToFloat(ByteArrays.i32be(data, position)));
                }
            }
            case PCM_F64LE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = sanitize((float) Double.longBitsToDouble(ByteArrays.i64le(data, position)));
                }
            }
            case PCM_F64BE -> {
                for (var i = 0; i < frames; i++, position += stride) {
                    target[i] = sanitize((float) Double.longBitsToDouble(ByteArrays.i64be(data, position)));
                }
            }
            default -> throw new IllegalStateException(codec + " is not a PCM codec");
        }
    }

    /**
     * Replaces non-finite float samples.
     *
     * @param value the decoded sample
     * @return {@code value} when finite, {@code 0} for NaN, and {@code +-1} for infinities
     */
    private static float sanitize(float value) {
        if (Float.isFinite(value)) {
            return value;
        }
        return Float.isNaN(value) ? 0 : Math.copySign(1.0f, value);
    }

    /**
     * Replaces non-finite double samples.
     *
     * @param value the decoded sample
     * @return {@code value} when finite, {@code 0} for NaN, and {@code +-1} for infinities
     */
    private static double sanitize(double value) {
        if (Double.isFinite(value)) {
            return value;
        }
        return Double.isNaN(value) ? 0 : Math.copySign(1.0, value);
    }

    /**
     * Does nothing: PCM decoding keeps no state between packets.
     *
     * @param output never called
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
    }

    /**
     * Returns the 16-bit linear value of a G.711 A-law code.
     *
     * @param code the code, from 0 to 255
     * @return the linear value, from -32256 to 32256
     */
    static int alawToLinear(int code) {
        var value = code ^ 0x55;
        var magnitude = (value & 0x0F) << 4;
        var segment = (value & 0x70) >> 4;
        magnitude = switch (segment) {
            case 0 -> magnitude + 8;
            case 1 -> magnitude + 0x108;
            default -> (magnitude + 0x108) << (segment - 1);
        };
        return (value & 0x80) != 0 ? magnitude : -magnitude;
    }

    /**
     * Returns the 16-bit linear value of a G.711 mu-law code.
     *
     * @param code the code, from 0 to 255
     * @return the linear value, from -32124 to 32124
     */
    static int mulawToLinear(int code) {
        var value = ~code & 0xFF;
        var magnitude = (((value & 0x0F) << 3) + 0x84) << ((value & 0x70) >> 4);
        return (value & 0x80) != 0 ? 0x84 - magnitude : magnitude - 0x84;
    }

    /**
     * Builds the A-law expansion table.
     *
     * @return the float value of every code
     */
    private static float[] buildAlaw() {
        var table = new float[256];
        for (var code = 0; code < 256; code++) {
            table[code] = alawToLinear(code) * SCALE_16;
        }
        return table;
    }

    /**
     * Builds the mu-law expansion table.
     *
     * @return the float value of every code
     */
    private static float[] buildMulaw() {
        var table = new float[256];
        for (var code = 0; code < 256; code++) {
            table[code] = mulawToLinear(code) * SCALE_16;
        }
        return table;
    }
}
