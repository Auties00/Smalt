package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.util.Objects;

/**
 * Converts between Smalt's planar float samples ({@link AudioBuffer}) and the interleaved PCM
 * bytes of a Java Sound {@link AudioFormat}.
 *
 * <p>The supported formats are {@link AudioFormat.Encoding#PCM_SIGNED} and
 * {@link AudioFormat.Encoding#PCM_UNSIGNED} at 8, 16, 24 or 32 bits and
 * {@link AudioFormat.Encoding#PCM_FLOAT} at 32 or 64 bits, in either byte order, with any channel
 * count and a frame size of {@code channels * bits / 8} bytes.
 *
 * <p>Integer samples scale as Smalt's PCM decoder scales them: a {@code b}-bit sample {@code v}
 * is the float {@code v / 2^(b - 1)}, and a float {@code x} is the sample
 * {@code round(x * 2^(b - 1))}, clamped to the sample range, with NaN as {@code 0}. Converting
 * integer samples of up to 24 bits to floats and back is therefore lossless; 32-bit samples keep
 * the 24 significant bits of a {@code float}. Float samples are copied as they are (64-bit samples
 * are rounded to {@code float}).
 *
 * <p>This class is thread-safe.
 */
public final class SampledAudio {
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
            sizeSupported = bits == 8 || bits == 16 || bits == 24 || bits == 32;
        } else if (AudioFormat.Encoding.PCM_FLOAT.equals(encoding)) {
            sizeSupported = bits == 32 || bits == 64;
        } else {
            return false;
        }
        var channels = format.getChannels();
        return sizeSupported && channels > 0
                && (format.getFrameSize() == AudioSystem.NOT_SPECIFIED || format.getFrameSize() == channels * bits / 8);
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
     * Returns a PCM format.
     *
     * @param encoding {@link AudioFormat.Encoding#PCM_SIGNED},
     * {@link AudioFormat.Encoding#PCM_UNSIGNED} or
     * {@link AudioFormat.Encoding#PCM_FLOAT}
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
        var channels = layout.channels();
        var format = new AudioFormat(encoding, sampleRate, bits, channels, channels * bits / 8, sampleRate, bigEndian);
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
     * @return {@code channels * bits / 8}
     * @throws IllegalArgumentException if the format is not supported
     */
    public static int frameSize(AudioFormat format) {
        checkSupported(format);
        return format.getChannels() * format.getSampleSizeInBits() / 8;
    }

    /**
     * Writes the frames of a buffer as interleaved PCM bytes.
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
     * Writes planar float samples as interleaved PCM bytes.
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
        var bytes = format.getSampleSizeInBits() / 8;
        var frameSize = channels * bytes;
        Objects.checkFromIndexSize(offset, frames * frameSize, target.length);
        for (var plane : planes) {
            Objects.checkFromIndexSize(fromFrame, frames, plane.length);
        }
        var encoding = format.getEncoding();
        var bigEndian = format.isBigEndian();
        var floating = AudioFormat.Encoding.PCM_FLOAT.equals(encoding);
        var unsigned = AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding);
        var bits = bytes * 8;
        for (var c = 0; c < channels; c++) {
            var plane = planes[c];
            var at = offset + c * bytes;
            for (var i = 0; i < frames; i++, at += frameSize) {
                var sample = plane[fromFrame + i];
                long raw;
                if (floating) {
                    raw = bits == 32 ? Float.floatToRawIntBits(sample) & 0xFFFFFFFFL : Double.doubleToRawLongBits(sample);
                } else {
                    raw = quantize(sample, bits);
                    if (unsigned) {
                        raw += 1L << (bits - 1);
                    }
                }
                store(raw, target, at, bytes, bigEndian);
            }
        }
        return frames * frameSize;
    }

    /**
     * Reads interleaved PCM bytes into a new buffer.
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
     * Reads interleaved PCM bytes into planar float samples.
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
        var bytes = format.getSampleSizeInBits() / 8;
        var frameSize = channels * bytes;
        Objects.checkFromIndexSize(offset, frames * frameSize, data.length);
        for (var plane : planes) {
            Objects.checkFromIndexSize(planeOffset, frames, plane.length);
        }
        var encoding = format.getEncoding();
        var bigEndian = format.isBigEndian();
        var floating = AudioFormat.Encoding.PCM_FLOAT.equals(encoding);
        var unsigned = AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding);
        var bits = bytes * 8;
        var scale = (float) Math.scalb(1.0, -(bits - 1));
        for (var c = 0; c < channels; c++) {
            var plane = planes[c];
            var at = offset + c * bytes;
            for (var i = 0; i < frames; i++, at += frameSize) {
                var raw = load(data, at, bytes, bigEndian);
                float sample;
                if (floating) {
                    sample = bits == 32 ? Float.intBitsToFloat((int) raw) : (float) Double.longBitsToDouble(raw);
                } else {
                    var value = unsigned ? raw - (1L << (bits - 1)) : (raw << (64 - bits)) >> (64 - bits);
                    sample = value * scale;
                }
                plane[planeOffset + i] = sample;
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
}
