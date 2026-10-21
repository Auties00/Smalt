package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes planar float samples into uncompressed PCM packets.
 *
 * <p>Every {@code PCM_*} {@link Codec} is produced: unsigned and signed 8-bit, signed 16, 24
 * (three packed bytes), 32 and 64-bit integers, 32 and 64-bit IEEE floats, each in the byte order
 * the codec names, and ITU-T G.711 A-law and mu-law. A packet holds
 * {@link PcmEncoderOptions#framesPerPacket()} interleaved frames, except the last one emitted by
 * {@link #flush(Consumer)}, which holds the frames left over and is not padded: PCM has no frame
 * structure, so padding would only lengthen the output.
 *
 * <p>Samples are clipped to {@code [-1, 1]} and NaN encodes as zero. An integer format of
 * {@code n} bits scales by {@code 2^(n-1)} and rounds to nearest (ties up), so {@code -1.0} maps
 * to the most negative code and {@code +1.0} to the most positive one; unsigned 8-bit samples are
 * offset by 128. This scaling is the inverse of {@link PcmDecoder}'s, so samples decoded from an
 * integer format encode back to the same codes. G.711 samples are first quantized to 16 bits.
 * No dither is applied.
 *
 * <p>Packets are timestamped in samples ({@code 1 / sampleRate()}), start at zero, are all key
 * frames, and carry their exact duration. There is no codec delay and no codec configuration.
 *
 * <p>Instances are not thread-safe.
 */
public final class PcmEncoder implements AudioEncoder {
    /**
     * The scale applied to 8-bit integers.
     */
    private static final float SCALE_8 = 128f;

    /**
     * The scale applied to 16-bit integers.
     */
    private static final float SCALE_16 = 32768f;

    /**
     * The scale applied to 24-bit integers.
     */
    private static final float SCALE_24 = 8388608f;

    /**
     * The scale applied to 32-bit integers.
     */
    private static final double SCALE_32 = 2147483648.0;

    /**
     * The scale applied to 64-bit integers.
     */
    private static final double SCALE_64 = 0x1p63;

    /**
     * The bias added to a mu-law magnitude before its segment is found, ITU-T G.711.
     */
    private static final int MULAW_BIAS = 0x84;

    /**
     * The largest magnitude mu-law encodes before clipping.
     */
    private static final int MULAW_CLIP = 32635;

    /**
     * The upper bound of each A-law segment of a 13-bit magnitude, ITU-T G.711.
     */
    private static final int[] ALAW_SEGMENT_END = {0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF};

    /**
     * The configuration.
     */
    private final PcmEncoderOptions options;

    /**
     * The sample format produced.
     */
    private final Codec codec;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The number of bytes of one coded sample.
     */
    private final int bytesPerSample;

    /**
     * The number of bytes of one interleaved frame.
     */
    private final int frameBytes;

    /**
     * The packet being filled, or {@code null} before the first sample.
     */
    private byte[] pending;

    /**
     * The number of frames in {@link #pending}.
     */
    private int pendingFrames;

    /**
     * The number of frames emitted in packets so far.
     */
    private long emitted;

    /**
     * Constructs an encoder producing signed 16-bit little-endian samples from 48 kHz stereo input.
     */
    public PcmEncoder() {
        this(PcmEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder with the given settings.
     *
     * @param options the sample format, input format and packet size
     */
    public PcmEncoder(PcmEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.codec = options.codec();
        this.channels = options.layout().channels();
        this.bytesPerSample = PcmDecoder.bytesPerSample(codec);
        this.frameBytes = bytesPerSample * channels;
    }

    /**
     * {@return the options this encoder was created with}
     */
    public PcmEncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return codec;
    }

    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    @Override
    public ChannelLayout layout() {
        return options.layout();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link PcmEncoderOptions#framesPerPacket()}
     */
    @Override
    public int frameSize() {
        return options.framesPerPacket();
    }

    /**
     * {@inheritDoc}
     *
     * @return zero: PCM has no codec delay
     */
    @Override
    public int delay() {
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * @return an empty optional: PCM needs no configuration
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * {@return the size of one coded sample in bits: 8 for the 8-bit and G.711 formats, otherwise
     * 16, 24, 32 or 64}
     */
    public int bitsPerSample() {
        return bytesPerSample * 8;
    }

    @Override
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (input.sampleRate() != options.sampleRate() || !input.layout().equals(options.layout())) {
            throw new IllegalArgumentException("input " + input.sampleRate() + " Hz " + input.layout()
                    + " differs from the encoder's " + options.sampleRate() + " Hz " + options.layout());
        }
        var planes = input.planes();
        var frames = input.frames();
        var consumed = 0;
        var packetFrames = options.framesPerPacket();
        while (consumed < frames) {
            if (pending == null) {
                pending = new byte[packetFrames * frameBytes];
            }
            var count = Math.min(frames - consumed, packetFrames - pendingFrames);
            for (var c = 0; c < channels; c++) {
                convert(planes[c], consumed, pending, pendingFrames * frameBytes + c * bytesPerSample, frameBytes, count);
            }
            pendingFrames += count;
            consumed += count;
            if (pendingFrames == packetFrames) {
                emit(pending, output);
            }
        }
    }

    /**
     * Emits the frames left over in a final packet that is not padded.
     *
     * @param output receives zero or one packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (pendingFrames == 0) {
            return;
        }
        emit(Arrays.copyOf(pending, pendingFrames * frameBytes), output);
    }

    /**
     * Emits the pending frames as one packet and starts a new one.
     *
     * @param data the packet payload, exactly {@link #pendingFrames} frames long
     * @param output the consumer
     */
    private void emit(byte[] data, Consumer<Packet> output) {
        var frames = pendingFrames;
        var pts = emitted;
        pending = null;
        pendingFrames = 0;
        emitted += frames;
        output.accept(new Packet(0, data, pts, pts, frames, true));
    }

    /**
     * Converts samples of one channel into their coded form.
     *
     * @param source the channel's plane
     * @param from the index of the first sample
     * @param target the packet
     * @param offset the index of the first coded sample in {@code target}
     * @param stride the distance between consecutive coded samples, one frame
     * @param count the number of samples
     */
    private void convert(float[] source, int from, byte[] target, int offset, int stride, int count) {
        var position = offset;
        var end = from + count;
        switch (codec) {
            case PCM_U8 -> {
                for (var i = from; i < end; i++, position += stride) {
                    target[position] = (byte) (quantize(source[i], SCALE_8, -128, 127) + 128);
                }
            }
            case PCM_S8 -> {
                for (var i = from; i < end; i++, position += stride) {
                    target[position] = (byte) quantize(source[i], SCALE_8, -128, 127);
                }
            }
            case PCM_ALAW -> {
                for (var i = from; i < end; i++, position += stride) {
                    target[position] = (byte) alaw(quantize(source[i], SCALE_16, -32768, 32767));
                }
            }
            case PCM_MULAW -> {
                for (var i = from; i < end; i++, position += stride) {
                    target[position] = (byte) mulaw(quantize(source[i], SCALE_16, -32768, 32767));
                }
            }
            case PCM_S16LE -> {
                for (var i = from; i < end; i++, position += stride) {
                    ByteArrays.put16le(target, position, quantize(source[i], SCALE_16, -32768, 32767));
                }
            }
            case PCM_S16BE -> {
                for (var i = from; i < end; i++, position += stride) {
                    ByteArrays.put16be(target, position, quantize(source[i], SCALE_16, -32768, 32767));
                }
            }
            case PCM_S24LE -> {
                for (var i = from; i < end; i++, position += stride) {
                    var value = quantize(source[i], SCALE_24, -8388608, 8388607);
                    target[position] = (byte) value;
                    target[position + 1] = (byte) (value >> 8);
                    target[position + 2] = (byte) (value >> 16);
                }
            }
            case PCM_S24BE -> {
                for (var i = from; i < end; i++, position += stride) {
                    var value = quantize(source[i], SCALE_24, -8388608, 8388607);
                    target[position] = (byte) (value >> 16);
                    target[position + 1] = (byte) (value >> 8);
                    target[position + 2] = (byte) value;
                }
            }
            case PCM_S32LE -> {
                for (var i = from; i < end; i++, position += stride) {
                    ByteArrays.put32le(target, position, quantize32(source[i]));
                }
            }
            case PCM_S32BE -> {
                for (var i = from; i < end; i++, position += stride) {
                    ByteArrays.put32be(target, position, quantize32(source[i]));
                }
            }
            case PCM_S64LE -> {
                for (var i = from; i < end; i++, position += stride) {
                    ByteArrays.put64le(target, position, quantize64(source[i]));
                }
            }
            case PCM_S64BE -> {
                for (var i = from; i < end; i++, position += stride) {
                    ByteArrays.put64be(target, position, quantize64(source[i]));
                }
            }
            case PCM_F32LE -> {
                for (var i = from; i < end; i++, position += stride) {
                    ByteArrays.put32le(target, position, Float.floatToRawIntBits(clip(source[i])));
                }
            }
            case PCM_F32BE -> {
                for (var i = from; i < end; i++, position += stride) {
                    ByteArrays.put32be(target, position, Float.floatToRawIntBits(clip(source[i])));
                }
            }
            case PCM_F64LE -> {
                for (var i = from; i < end; i++, position += stride) {
                    ByteArrays.put64le(target, position, Double.doubleToRawLongBits(clip(source[i])));
                }
            }
            case PCM_F64BE -> {
                for (var i = from; i < end; i++, position += stride) {
                    ByteArrays.put64be(target, position, Double.doubleToRawLongBits(clip(source[i])));
                }
            }
            default -> throw new IllegalStateException(codec + " is not a PCM codec");
        }
    }

    /**
     * Scales a sample to an integer code, rounding to nearest and clipping to a range.
     *
     * @param sample the sample, nominally in {@code [-1, 1]}
     * @param scale the scale, {@code 2^(n-1)} for {@code n}-bit codes
     * @param min the smallest code
     * @param max the largest code
     * @return the code; zero for NaN
     */
    private static int quantize(float sample, float scale, int min, int max) {
        return Math.clamp(Math.round(sample * scale), min, max);
    }

    /**
     * Scales a sample to a signed 32-bit code, rounding to nearest and clipping.
     *
     * @param sample the sample, nominally in {@code [-1, 1]}
     * @return the code; zero for NaN
     */
    private static int quantize32(float sample) {
        return Math.clamp(Math.round(sample * SCALE_32), Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Scales a sample to a signed 64-bit code, rounding to nearest and clipping.
     *
     * @param sample the sample, nominally in {@code [-1, 1]}
     * @return the code; zero for NaN
     */
    private static long quantize64(float sample) {
        return Math.round(sample * SCALE_64);
    }

    /**
     * Clips a sample to {@code [-1, 1]}, mapping NaN to zero.
     *
     * @param sample the sample, of any value
     * @return the clipped sample
     */
    private static float clip(float sample) {
        return Float.isNaN(sample) ? 0f : Math.clamp(sample, -1f, 1f);
    }

    /**
     * Encodes a 16-bit linear sample as G.711 A-law.
     *
     * @implNote This implementation follows the segment encoding of ITU-T G.711: the sample is
     * reduced to 13 bits, its segment is the first whose upper bound holds the magnitude, and the
     * code (sign, segment, four mantissa bits) is XORed with {@code 0x55}.
     *
     * @param pcm the sample, in {@code [-32768, 32767]}
     * @return the A-law code, in {@code [0, 255]}
     */
    static int alaw(int pcm) {
        var value = pcm >> 3;
        int mask;
        if (value >= 0) {
            mask = 0xD5;
        } else {
            mask = 0x55;
            value = -value - 1;
        }
        var segment = 0;
        while (segment < 8 && value > ALAW_SEGMENT_END[segment]) {
            segment++;
        }
        if (segment == 8) {
            return 0x7F ^ mask;
        }
        var code = segment << 4;
        code |= segment < 2 ? (value >> 1) & 0x0F : (value >> segment) & 0x0F;
        return code ^ mask;
    }

    /**
     * Encodes a 16-bit linear sample as G.711 mu-law.
     *
     * @implNote This implementation follows the biased segment encoding of ITU-T G.711: the
     * magnitude is clipped to {@value #MULAW_CLIP}, biased by {@code 0x84}, its segment is the
     * position of its highest set bit above bit 7, and the code (sign, segment, four mantissa
     * bits) is complemented.
     *
     * @param pcm the sample, in {@code [-32768, 32767]}
     * @return the mu-law code, in {@code [0, 255]}
     */
    static int mulaw(int pcm) {
        var sign = 0;
        var value = pcm;
        if (value < 0) {
            sign = 0x80;
            value = -value;
        }
        value = Math.min(value, MULAW_CLIP) + MULAW_BIAS;
        var exponent = 31 - Integer.numberOfLeadingZeros(value >> 7);
        var mantissa = (value >> (exponent + 3)) & 0x0F;
        return ~(sign | (exponent << 4) | mantissa) & 0xFF;
    }
}
