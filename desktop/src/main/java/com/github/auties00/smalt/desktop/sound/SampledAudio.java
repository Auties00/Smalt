package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.util.Objects;

/**
 * Converts between Smalt's planar float samples ({@link AudioBuffer}) and the interleaved sample
 * bytes of a Java Sound {@link AudioFormat}.
 *
 * <p>The supported formats, in either byte order, with any channel count and a frame size of
 * {@code channels * ceil(bits / 8)} bytes, are:
 * <ul>
 * <li>{@link AudioFormat.Encoding#PCM_SIGNED} and {@link AudioFormat.Encoding#PCM_UNSIGNED} at 8 to 32
 * bits;</li>
 * <li>{@link AudioFormat.Encoding#PCM_FLOAT} at 16 (IEEE 754 half precision), 32 or 64 bits;</li>
 * <li>{@link AudioFormat.Encoding#ULAW} and {@link AudioFormat.Encoding#ALAW} at 8 bits, the
 * companded codes of ITU-T G.711.</li>
 * </ul>
 *
 * <p>Integer samples scale as Smalt's PCM decoder scales them: a {@code b}-bit sample {@code v}
 * is the float {@code v / 2^(b - 1)}, and a float {@code x} is the sample
 * {@code round(x * 2^(b - 1))}, clamped to the sample range, with NaN as {@code 0}. A sample whose
 * size is not a multiple of 8 bits fills the most significant bits of its {@code ceil(b / 8)}
 * bytes, as WAVE and AIFF files store it: the low bits are ignored when reading and written as
 * zero. Converting integer samples of up to 24 bits to floats and back is therefore lossless;
 * samples of more bits keep the 24 significant bits of a {@code float}. Float samples are copied
 * as they are, 64-bit samples rounded to {@code float} and half-precision samples rounded to
 * nearest even, out-of-range values becoming infinities.
 *
 * <p>A G.711 code decodes to the linear value of the G.711 tables, a 14-bit (mu-law) or 13-bit
 * (A-law) value read as a 16-bit sample, and a float encodes as the code of its 16-bit sample
 * {@code round(x * 32768)} reduced to its 14 or 13 most significant bits, a negative sample {@code v}
 * coded as the magnitude {@code -v - 1} as the ITU-T G.191 tools code it. Decoding a code and encoding
 * the sample again gives the same code,
 * except that the mu-law negative zero {@code 0x7F} encodes again as the positive zero
 * {@code 0xFF}.
 *
 * <p>This class is thread-safe.
 */
public final class SampledAudio {
    /**
     * The linear value of each mu-law code, as a 16-bit sample.
     */
    private static final short[] MU_LAW = new short[256];

    /**
     * The linear value of each A-law code, as a 16-bit sample.
     */
    private static final short[] A_LAW = new short[256];

    /**
     * The value added to the magnitude of a 16-bit sample before mu-law segmentation ({@code 33 << 2}).
     */
    private static final int MU_LAW_BIAS = 0x84;

    /**
     * The largest magnitude mu-law codes, {@code 8159 << 2} less the bias.
     */
    private static final int MU_LAW_CLIP = 32635;

    static {
        for (var code = 0; code < 256; code++) {
            MU_LAW[code] = (short) decodeMuLaw(code);
            A_LAW[code] = (short) decodeALaw(code);
        }
    }

    /**
     * Prevents instantiation.
     */
    private SampledAudio() {
        throw new AssertionError();
    }

    /**
     * Returns whether a format's samples can be converted.
     *
     * @param format the Java Sound format to check
     * @return {@code true} for the encodings, sizes and frame layout the class documents; the
     * sample rate is not checked
     */
    public static boolean isSupported(AudioFormat format) {
        Objects.requireNonNull(format, "format");
        var bits = format.getSampleSizeInBits();
        var encoding = format.getEncoding();
        boolean sizeSupported;
        if (AudioFormat.Encoding.PCM_SIGNED.equals(encoding) || AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding)) {
            sizeSupported = bits >= 8 && bits <= 32;
        } else if (AudioFormat.Encoding.PCM_FLOAT.equals(encoding)) {
            sizeSupported = bits == 16 || bits == 32 || bits == 64;
        } else if (AudioFormat.Encoding.ULAW.equals(encoding) || AudioFormat.Encoding.ALAW.equals(encoding)) {
            sizeSupported = bits == 8;
        } else {
            return false;
        }
        var channels = format.getChannels();
        return sizeSupported && channels > 0 && (format.getFrameSize() == AudioSystem.NOT_SPECIFIED
                || format.getFrameSize() == channels * containerBytes(bits));
    }

    /**
     * Returns the 16-bit signed little-endian PCM format of a sample rate and channel layout, the
     * format every Java Sound output line accepts.
     *
     * @param sampleRate the sampling rate in hertz
     * @param layout the channel layout
     * @return the format
     * @throws IllegalArgumentException if {@code sampleRate} is not positive
     */
    public static AudioFormat pcm16(int sampleRate, ChannelLayout layout) {
        return format(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, layout, false);
    }

    /**
     * Returns a supported format of an encoding, sample rate, sample size and channel layout, with a
     * frame of {@code channels * ceil(bits / 8)} bytes.
     *
     * @param encoding one of the encodings the class documents
     * @param sampleRate the sampling rate in hertz
     * @param bits the sample size in bits
     * @param layout the channel layout
     * @param bigEndian whether multi-byte samples are big-endian
     * @return the format
     * @throws IllegalArgumentException if {@code sampleRate} is not positive or the format is not
     * supported
     */
    public static AudioFormat format(AudioFormat.Encoding encoding, int sampleRate, int bits, ChannelLayout layout,
                                     boolean bigEndian) {
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(layout, "layout");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("invalid sample rate " + sampleRate);
        }
        if (bits <= 0) {
            throw new IllegalArgumentException("unsupported sample size " + bits);
        }
        var channels = layout.channels();
        var format = new AudioFormat(encoding, sampleRate, bits, channels, channels * containerBytes(bits), sampleRate,
                bigEndian);
        if (!isSupported(format)) {
            throw new IllegalArgumentException("unsupported PCM format " + format);
        }
        return format;
    }

    /**
     * Returns the channel layout Smalt assumes for a format's channel count.
     *
     * @param format the Java Sound format whose channel count is used
     * @return {@link ChannelLayout#defaultFor(int)} of the channel count
     * @throws IllegalArgumentException if the channel count is not positive
     */
    public static ChannelLayout layoutOf(AudioFormat format) {
        return ChannelLayout.defaultFor(Objects.requireNonNull(format, "format").getChannels());
    }

    /**
     * Returns the size of one frame of a supported format.
     *
     * @param format a supported Java Sound format
     * @return {@code channels * ceil(bits / 8)}
     * @throws IllegalArgumentException if the format is not supported
     */
    public static int frameSize(AudioFormat format) {
        checkSupported(format);
        return format.getChannels() * containerBytes(format.getSampleSizeInBits());
    }

    /**
     * Writes the frames of a buffer as interleaved sample bytes.
     *
     * @param buffer the samples; its channel count must match the format's
     * @param format the format of the bytes
     * @param target the destination array, with room for {@code buffer.frames() * frameSize(format)}
     * bytes from {@code offset}
     * @param offset the index in {@code target} of the first byte written
     * @return the number of bytes written
     * @throws IllegalArgumentException if the format is not supported or its channel count differs
     * from the buffer's
     * @throws IndexOutOfBoundsException if {@code target} is too short
     */
    public static int write(AudioBuffer buffer, AudioFormat format, byte[] target, int offset) {
        Objects.requireNonNull(buffer, "buffer");
        return write(buffer.planes(), 0, buffer.frames(), format, target, offset);
    }

    /**
     * Writes planar float samples as interleaved sample bytes.
     *
     * @param planes the samples, one plane per channel; the plane count must match the format's
     * channel count
     * @param fromFrame the index in each plane of the first frame written
     * @param frames the number of frames to write
     * @param format the format of the bytes
     * @param target the destination array
     * @param offset the index in {@code target} of the first byte written
     * @return the number of bytes written, {@code frames * frameSize(format)}
     * @throws IllegalArgumentException if the format is not supported or its channel count differs
     * from the plane count
     * @throws IndexOutOfBoundsException if a plane or {@code target} is too short
     */
    public static int write(float[][] planes, int fromFrame, int frames, AudioFormat format, byte[] target, int offset) {
        Objects.requireNonNull(planes, "planes");
        Objects.requireNonNull(target, "target");
        checkSupported(format);
        var channels = format.getChannels();
        if (planes.length != channels) {
            throw new IllegalArgumentException(planes.length + " planes for a " + channels + "-channel format");
        }
        var bits = format.getSampleSizeInBits();
        var bytes = containerBytes(bits);
        var frameSize = channels * bytes;
        Objects.checkFromIndexSize(offset, frames * frameSize, target.length);
        for (var plane : planes) {
            Objects.checkFromIndexSize(fromFrame, frames, plane.length);
        }
        var encoding = encodingOf(format);
        var bigEndian = format.isBigEndian();
        var padding = bytes * 8 - bits;
        for (var c = 0; c < channels; c++) {
            var plane = planes[c];
            var at = offset + c * bytes;
            for (var i = 0; i < frames; i++, at += frameSize) {
                var sample = plane[fromFrame + i];
                switch (encoding) {
                    case FLOAT -> {
                        long raw = switch (bits) {
                            case 16 -> Float.floatToFloat16(sample) & 0xFFFFL;
                            case 32 -> Float.floatToRawIntBits(sample) & 0xFFFFFFFFL;
                            default -> Double.doubleToRawLongBits(sample);
                        };
                        store(raw, target, at, bytes, bigEndian);
                    }
                    case MU_LAW -> target[at] = (byte) encodeMuLaw((int) quantize(sample, 16));
                    case A_LAW -> target[at] = (byte) encodeALaw((int) quantize(sample, 16));
                    case SIGNED, UNSIGNED -> {
                        var raw = quantize(sample, bits);
                        if (encoding == Kind.UNSIGNED) {
                            raw += 1L << (bits - 1);
                        }
                        store(raw << padding, target, at, bytes, bigEndian);
                    }
                }
            }
        }
        return frames * frameSize;
    }

    /**
     * Reads interleaved sample bytes into a new buffer.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes; trailing bytes that do not fill a frame are ignored
     * @param format the format of the bytes; its sample rate must be positive
     * @return a new buffer with {@code length / frameSize(format)} frames
     * @throws IllegalArgumentException if the format is not supported or its sample rate is not
     * positive
     * @throws IndexOutOfBoundsException if the range is outside {@code data}
     */
    public static AudioBuffer read(byte[] data, int offset, int length, AudioFormat format) {
        Objects.requireNonNull(data, "data");
        checkSupported(format);
        Objects.checkFromIndexSize(offset, length, data.length);
        var rate = Math.round(format.getSampleRate());
        if (rate <= 0) {
            throw new IllegalArgumentException("invalid sample rate " + format.getSampleRate());
        }
        var frames = length / frameSize(format);
        var buffer = new AudioBuffer(layoutOf(format), rate, frames);
        read(data, offset, frames, format, buffer.planes(), 0);
        buffer.setFrames(frames);
        return buffer;
    }

    /**
     * Reads interleaved sample bytes into planar float samples.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param frames the number of frames to read
     * @param format the format of the bytes
     * @param planes the destination, one plane per channel; the plane count must match the
     * format's channel count
     * @param planeOffset the index in each plane of the first frame written
     * @throws IllegalArgumentException if the format is not supported or its channel count differs
     * from the plane count
     * @throws IndexOutOfBoundsException if {@code data} or a plane is too short
     */
    public static void read(byte[] data, int offset, int frames, AudioFormat format, float[][] planes, int planeOffset) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(planes, "planes");
        checkSupported(format);
        var channels = format.getChannels();
        if (planes.length != channels) {
            throw new IllegalArgumentException(planes.length + " planes for a " + channels + "-channel format");
        }
        var bits = format.getSampleSizeInBits();
        var bytes = containerBytes(bits);
        var frameSize = channels * bytes;
        Objects.checkFromIndexSize(offset, frames * frameSize, data.length);
        for (var plane : planes) {
            Objects.checkFromIndexSize(planeOffset, frames, plane.length);
        }
        var encoding = encodingOf(format);
        var bigEndian = format.isBigEndian();
        var padding = bytes * 8 - bits;
        var scale = (float) Math.scalb(1.0, -(bits - 1));
        for (var c = 0; c < channels; c++) {
            var plane = planes[c];
            var at = offset + c * bytes;
            for (var i = 0; i < frames; i++, at += frameSize) {
                plane[planeOffset + i] = switch (encoding) {
                    case FLOAT -> {
                        var raw = load(data, at, bytes, bigEndian);
                        yield switch (bits) {
                            case 16 -> Float.float16ToFloat((short) raw);
                            case 32 -> Float.intBitsToFloat((int) raw);
                            default -> (float) Double.longBitsToDouble(raw);
                        };
                    }
                    case MU_LAW -> MU_LAW[data[at] & 0xFF] * 0x1p-15f;
                    case A_LAW -> A_LAW[data[at] & 0xFF] * 0x1p-15f;
                    case SIGNED -> {
                        var raw = load(data, at, bytes, bigEndian) >>> padding;
                        yield ((raw << (64 - bits)) >> (64 - bits)) * scale;
                    }
                    case UNSIGNED -> {
                        var raw = load(data, at, bytes, bigEndian) >>> padding;
                        yield (raw - (1L << (bits - 1))) * scale;
                    }
                };
            }
        }
    }

    /**
     * Reads interleaved integer PCM bytes into planar signed integers of the format's sample size,
     * unsigned samples offset to signed ones.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param frames the number of frames to read
     * @param format a supported {@link AudioFormat.Encoding#PCM_SIGNED} or
     * {@link AudioFormat.Encoding#PCM_UNSIGNED} format
     * @param planes the destination, one plane per channel
     * @param planeOffset the index in each plane of the first frame written
     */
    static void readIntegers(byte[] data, int offset, int frames, AudioFormat format, int[][] planes,
                             int planeOffset) {
        var channels = format.getChannels();
        var bits = format.getSampleSizeInBits();
        var bytes = containerBytes(bits);
        var frameSize = channels * bytes;
        Objects.checkFromIndexSize(offset, frames * frameSize, data.length);
        var unsigned = AudioFormat.Encoding.PCM_UNSIGNED.equals(format.getEncoding());
        var bigEndian = format.isBigEndian();
        var padding = bytes * 8 - bits;
        for (var c = 0; c < channels; c++) {
            var plane = planes[c];
            var at = offset + c * bytes;
            for (var i = 0; i < frames; i++, at += frameSize) {
                var raw = load(data, at, bytes, bigEndian) >>> padding;
                plane[planeOffset + i] = (int) (unsigned ? raw - (1L << (bits - 1)) : (raw << (64 - bits)) >> (64 - bits));
            }
        }
    }

    /**
     * Converts a float sample to an integer sample.
     *
     * @param sample the sample, nominally in {@code [-1, 1]}
     * @param bits the sample size
     * @return the signed integer sample, clamped to the range of {@code bits} bits
     */
    static long quantize(float sample, int bits) {
        var limit = 1L << (bits - 1);
        var scaled = Math.round((double) sample * limit);
        return Math.clamp(scaled, -limit, limit - 1);
    }

    /**
     * Returns the G.711 mu-law code of a 16-bit sample.
     *
     * @param sample the sample, from {@code -32768} to {@code 32767}
     * @return the code, from 0 to 255
     */
    static int encodeMuLaw(int sample) {
        var sign = sample < 0 ? 0x80 : 0;
        // A negative sample codes its ones' complement, as ITU-T G.191 does for both laws
        var magnitude = Math.min(sample < 0 ? ~sample : sample, MU_LAW_CLIP) + MU_LAW_BIAS;
        // The segment is the position of the highest set bit above bit 7 (the bias makes bit 7 the lowest)
        var segment = 31 - Integer.numberOfLeadingZeros(magnitude >> 7);
        var mantissa = (magnitude >> (segment + 3)) & 0x0F;
        return ~(sign | segment << 4 | mantissa) & 0xFF;
    }

    /**
     * Returns the 16-bit sample of a G.711 mu-law code.
     *
     * @param code the code, from 0 to 255
     * @return the sample, from {@code -32124} to {@code 32124}
     */
    static int decodeMuLaw(int code) {
        var complement = ~code & 0xFF;
        var magnitude = (((complement & 0x0F) << 3) + MU_LAW_BIAS) << ((complement & 0x70) >> 4);
        return (complement & 0x80) != 0 ? MU_LAW_BIAS - magnitude : magnitude - MU_LAW_BIAS;
    }

    /**
     * Returns the G.711 A-law code of a 16-bit sample, coded from its 13 most significant bits.
     *
     * @param sample the sample, from {@code -32768} to {@code 32767}
     * @return the code, from 0 to 255, with the even bits inverted as G.711 transmits them
     */
    static int encodeALaw(int sample) {
        var value = sample >> 3;
        int mask;
        if (value >= 0) {
            mask = 0xD5;
        } else {
            mask = 0x55;
            value = -value - 1;
        }
        // Segment s holds the magnitudes below 32 << s; segments 0 and 1 share a step of 2
        var segment = Math.max(0, 31 - Integer.numberOfLeadingZeros(value >> 4));
        if (segment >= 8) {
            return 0x7F ^ mask;
        }
        var mantissa = segment < 2 ? (value >> 1) & 0x0F : (value >> segment) & 0x0F;
        return (segment << 4 | mantissa) ^ mask;
    }

    /**
     * Returns the 16-bit sample of a G.711 A-law code.
     *
     * @param code the code, from 0 to 255
     * @return the sample, from {@code -32256} to {@code 32256}
     */
    static int decodeALaw(int code) {
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
     * Returns the number of bytes a sample of a size occupies.
     *
     * @param bits the sample size in bits, positive
     * @return {@code ceil(bits / 8)}
     */
    static int containerBytes(int bits) {
        return (bits + 7) / 8;
    }

    /**
     * Returns the sample kind of a supported format.
     *
     * @param format a supported format
     * @return the kind of its samples
     */
    private static Kind encodingOf(AudioFormat format) {
        var encoding = format.getEncoding();
        if (AudioFormat.Encoding.PCM_FLOAT.equals(encoding)) {
            return Kind.FLOAT;
        }
        if (AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding)) {
            return Kind.UNSIGNED;
        }
        if (AudioFormat.Encoding.ULAW.equals(encoding)) {
            return Kind.MU_LAW;
        }
        if (AudioFormat.Encoding.ALAW.equals(encoding)) {
            return Kind.A_LAW;
        }
        return Kind.SIGNED;
    }

    /**
     * Stores the low bytes of a value.
     *
     * @param value the sample bits to store
     * @param target the destination
     * @param offset the index of the first byte
     * @param bytes the number of bytes
     * @param bigEndian whether the most significant byte comes first
     */
    private static void store(long value, byte[] target, int offset, int bytes, boolean bigEndian) {
        for (var i = 0; i < bytes; i++) {
            var shift = 8 * (bigEndian ? bytes - 1 - i : i);
            target[offset + i] = (byte) (value >>> shift);
        }
    }

    /**
     * Loads an unsigned value from bytes.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param bytes the number of bytes
     * @param bigEndian whether the most significant byte comes first
     * @return the value, zero-extended
     */
    private static long load(byte[] data, int offset, int bytes, boolean bigEndian) {
        var value = 0L;
        for (var i = 0; i < bytes; i++) {
            var shift = 8 * (bigEndian ? bytes - 1 - i : i);
            value |= (data[offset + i] & 0xFFL) << shift;
        }
        return value;
    }

    /**
     * Checks that a format is supported.
     *
     * @param format the Java Sound format to check
     * @throws IllegalArgumentException if the format is not supported
     */
    private static void checkSupported(AudioFormat format) {
        if (!isSupported(format)) {
            throw new IllegalArgumentException("unsupported PCM format " + format);
        }
    }

    /**
     * The kinds of samples the supported formats hold.
     */
    private enum Kind {
        /**
         * Two's complement integers.
         */
        SIGNED,
        /**
         * Offset binary integers.
         */
        UNSIGNED,
        /**
         * IEEE 754 floats.
         */
        FLOAT,
        /**
         * G.711 mu-law codes.
         */
        MU_LAW,
        /**
         * G.711 A-law codes.
         */
        A_LAW
    }
}
