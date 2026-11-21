package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.io.MediaSource;

import javax.sound.sampled.AudioFileFormat;

/**
 * Tells the WAVE, AIFF and AU files the JDK's own Java Sound readers read apart from the variants they
 * decline, from the files' headers.
 *
 * <p>The JDK reads a RIFF WAVE file of integer PCM of up to 32 bits, IEEE float PCM of 32 or 64 bits, or
 * G.711 A-law or mu-law, also when its format chunk is {@code WAVE_FORMAT_EXTENSIBLE} with a PCM or float
 * subformat; an AIFF file of integer PCM, or an AIFF-C file of the compression types {@code NONE} and
 * {@code ulaw}; and a big-endian AU file of 8 to 32-bit integer PCM, 32 or 64-bit float PCM, A-law or
 * mu-law. Every other WAVE (RF64 and BW64 included), AIFF or AU file is a variant only Smalt reads.
 */
final class JdkSampledFiles {
    /**
     * The WAVE format tag of integer PCM.
     */
    private static final int WAVE_PCM = 1;

    /**
     * The WAVE format tag of IEEE float PCM.
     */
    private static final int WAVE_FLOAT = 3;

    /**
     * The WAVE format tag of G.711 A-law.
     */
    private static final int WAVE_ALAW = 6;

    /**
     * The WAVE format tag of G.711 mu-law.
     */
    private static final int WAVE_MULAW = 7;

    /**
     * The WAVE format tag whose subformat GUID names the coding.
     */
    private static final int WAVE_EXTENSIBLE = 0xFFFE;

    /**
     * The most chunks walked to find the one a decision needs.
     */
    private static final int MAX_CHUNKS = 64;

    /**
     * Prevents instantiation.
     */
    private JdkSampledFiles() {
        throw new AssertionError();
    }

    /**
     * Returns whether the JDK's readers read a file.
     *
     * @param source the file
     * @param container the detected container format
     * @return {@code true} for the WAVE, AIFF and AU files the class documents as read by the JDK, and
     * {@code false} for every other file, including those of other containers
     */
    static boolean readsNatively(MediaSource source, ContainerFormat container) {
        return switch (container) {
            case WAV -> readsWave(source);
            case AIFF -> readsAiff(source);
            case AU -> readsAu(source);
            default -> false;
        };
    }

    /**
     * Returns the JDK file type of an AIFF file: AIFF-C for the {@code AIFC} form type.
     *
     * @param source the file
     * @return {@link AudioFileFormat.Type#AIFC} or {@link AudioFileFormat.Type#AIFF}
     */
    static AudioFileFormat.Type aiffType(MediaSource source) {
        var header = new byte[12];
        return read(source, 0, header) && tag(header, 8, "AIFC") ? AudioFileFormat.Type.AIFC
                : AudioFileFormat.Type.AIFF;
    }

    /**
     * Returns whether the JDK reads a WAVE file.
     *
     * @param source the file
     * @return whether its {@code fmt } chunk names a coding the JDK reads
     */
    private static boolean readsWave(MediaSource source) {
        var header = new byte[12];
        if (!read(source, 0, header) || !tag(header, 0, "RIFF") || !tag(header, 8, "WAVE")) {
            return false;
        }
        var chunk = new byte[8];
        var position = 12L;
        for (var i = 0; i < MAX_CHUNKS && read(source, position, chunk); i++) {
            var size = le32(chunk, 4);
            if (tag(chunk, 0, "fmt ")) {
                var format = new byte[(int) Math.min(size, 40)];
                if (format.length < 16 || !read(source, position + 8, format)) {
                    return false;
                }
                var tag = le16(format, 0);
                var bits = le16(format, 14);
                if (tag == WAVE_EXTENSIBLE) {
                    if (format.length < 26) {
                        return false;
                    }
                    tag = le16(format, 24);
                    if (tag != WAVE_PCM && tag != WAVE_FLOAT) {
                        return false;
                    }
                }
                return switch (tag) {
                    case WAVE_PCM -> bits >= 1 && bits <= 32;
                    case WAVE_FLOAT -> bits == 32 || bits == 64;
                    case WAVE_ALAW, WAVE_MULAW -> true;
                    default -> false;
                };
            }
            position += 8 + size + (size & 1);
        }
        return false;
    }

    /**
     * Returns whether the JDK reads an AIFF or AIFF-C file.
     *
     * @param source the file
     * @return {@code true} for an AIFF file of up to 32-bit samples, or an AIFF-C file whose {@code COMM}
     * chunk names the compression type {@code NONE} or {@code ulaw}
     */
    private static boolean readsAiff(MediaSource source) {
        var header = new byte[12];
        if (!read(source, 0, header) || !tag(header, 0, "FORM")) {
            return false;
        }
        var compressed = tag(header, 8, "AIFC");
        if (!compressed && !tag(header, 8, "AIFF")) {
            return false;
        }
        var chunk = new byte[8];
        var position = 12L;
        for (var i = 0; i < MAX_CHUNKS && read(source, position, chunk); i++) {
            var size = be32(chunk, 4);
            if (tag(chunk, 0, "COMM")) {
                var common = new byte[compressed ? 22 : 18];
                if (size < common.length || !read(source, position + 8, common)) {
                    return false;
                }
                var bits = (common[6] & 0xFF) << 8 | common[7] & 0xFF;
                if (!compressed) {
                    return bits >= 1 && bits <= 32;
                }
                return tag(common, 18, "NONE") && bits >= 1 && bits <= 32 || tag(common, 18, "ulaw");
            }
            position += 8 + size + (size & 1);
        }
        return false;
    }

    /**
     * Returns whether the JDK reads an AU file.
     *
     * @param source the file
     * @return {@code true} for a big-endian file of an encoding the class documents
     */
    private static boolean readsAu(MediaSource source) {
        var header = new byte[16];
        if (!read(source, 0, header) || !tag(header, 0, ".snd")) {
            return false;
        }
        return switch ((int) be32(header, 12)) {
            // mu-law, 8, 16, 24 and 32-bit linear, float, double, A-law
            case 1, 2, 3, 4, 5, 6, 7, 27 -> true;
            default -> false;
        };
    }

    /**
     * Reads bytes of a file.
     *
     * @param source the file
     * @param position the offset of the first byte
     * @param target receives the bytes
     * @return {@code true} when every byte was read
     */
    private static boolean read(MediaSource source, long position, byte[] target) {
        var done = 0;
        while (done < target.length) {
            var read = source.read(position + done, target, done, target.length - done);
            if (read <= 0) {
                return false;
            }
            done += read;
        }
        return true;
    }

    /**
     * Returns whether four bytes spell an ASCII tag.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param tag the four-character tag
     * @return {@code true} when the bytes equal the tag
     */
    private static boolean tag(byte[] data, int offset, String tag) {
        for (var i = 0; i < 4; i++) {
            if (data[offset + i] != tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads an unsigned little-endian 16-bit value.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return the value
     */
    private static int le16(byte[] data, int offset) {
        return data[offset] & 0xFF | (data[offset + 1] & 0xFF) << 8;
    }

    /**
     * Reads an unsigned little-endian 32-bit value.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return the value
     */
    private static long le32(byte[] data, int offset) {
        return (le16(data, offset) | (long) le16(data, offset + 2) << 16) & 0xFFFFFFFFL;
    }

    /**
     * Reads an unsigned big-endian 32-bit value.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return the value
     */
    private static long be32(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF) & 0xFFFFFFFFL;
    }
}
