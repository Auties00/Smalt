package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
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
