package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;

/**
 * Tells camera raw files from other images: the signatures of the vendor formats that do not
 * start like a TIFF file, and the directories that set a TIFF-structured raw file (DNG, NEF,
 * ARW, PEF and the others) apart from a TIFF image.
 *
 * <p>A TIFF-structured file is a {@link ImageFormat#DNG} when its first directory holds the
 * {@code DNGVersion} tag, a {@link ImageFormat#RAW} when it is a Canon CR2 file ({@code CR} at
 * offset 8) or a Phase One IIQ file ({@code IIIICwaR} at offset 8), when its first directory carries a
 * tag only camera raw files carry, or when one of its directories (the main chain and the
 * {@code SubIFDs} they point to) describes a color filter array or linear raw image or uses a vendor
 * raw compression, and a {@link ImageFormat#TIFF} otherwise. The walk is bounded in the directories
 * and entries it reads, so it terminates on every input; a directory it cannot read ends the walk,
 * and the file is then classified from what was read so far.
 */
final class RawDetection {
    /**
     * The tag holding the DNG version, which every DNG file's first directory has.
     */
    private static final int DNG_VERSION = 50706;

    /**
     * The tag holding the photometric interpretation.
     */
    private static final int PHOTOMETRIC = 262;

    /**
     * The tag holding the compression scheme.
     */
    private static final int COMPRESSION = 259;

    /**
     * The tag holding the offsets of the child directories.
     */
    private static final int SUB_IFDS = 330;

    /**
     * The photometric interpretation of a color filter array image (TIFF/EP).
     */
    private static final int PHOTOMETRIC_CFA = 32803;

    /**
     * The photometric interpretation of a linear raw image (DNG).
     */
    private static final int PHOTOMETRIC_LINEAR_RAW = 34892;

    /**
     * The compression codes only camera raw images use: Nikon NEF (34713), Sony ARW (32767),
     * Panasonic (34316), Kodak (65000, 262) and Pentax (65535) raw compressions, and the codes
     * 32769 and 32770 of older Nikon and Sony files.
     */
    private static final int[] RAW_COMPRESSIONS = {262, 32767, 32769, 32770, 34316, 34713, 65000, 65535};

    /**
     * The tags only camera raw files carry: Kodak's directory of the DCS files (33424), Leaf's metadata packets
     * of the MOS files (34310) and Kodak's directory of the KDC files (65024).
     */
    private static final int[] RAW_TAGS = {33424, 34310, 65024};

    /**
     * The most directories examined.
     */
    private static final int MAX_DIRECTORIES = 16;

    /**
     * The most entries of one directory examined.
     */
    private static final int MAX_ENTRIES = 1024;

    /**
     * Prevents instantiation.
     */
    private RawDetection() {
        throw new AssertionError();
    }

    /**
     * Reads bytes at absolute positions of an input.
     */
    interface Input {
        /**
         * Reads up to {@code length} bytes at {@code position}.
         *
         * @param position the absolute offset
         * @param buffer the destination
         * @param offset the first index written
         * @param length the most bytes read
         * @return the number of bytes read, {@code -1} at the end of the input
         */
        int read(long position, byte[] buffer, int offset, int length);
    }

    /**
     * Exposes the first {@code length} bytes of an array as an input.
     *
     * @param header the bytes
     * @param length the number of valid bytes
     * @return the input, which ends after {@code length} bytes
     */
    static Input over(byte[] header, int length) {
        return (position, buffer, offset, count) -> {
            if (position >= length) {
                return -1;
            }
            var available = (int) Math.min(count, length - position);
            System.arraycopy(header, (int) position, buffer, offset, available);
            return available;
        };
    }

    /**
     * Exposes a source as an input.
     *
     * @param source the file to read, positioned by each read
     * @return the input
     */
    static Input over(MediaSource source) {
        return source::read;
    }

    /**
     * Returns whether the leading bytes carry the signature of a raw format that does not start
     * like a TIFF file: Olympus ORF, Panasonic RW2 and RWL, Fujifilm RAF, Canon CRW, Minolta MRW
     * and Sigma X3F.
     *
     * @param header the leading bytes
     * @param length the number of valid bytes
     * @return whether the bytes start such a raw file
     */
    static boolean hasRawSignature(byte[] header, int length) {
        if (length >= 4 && header[0] == 'I' && header[1] == 'I'
                && ((header[2] == 'R' && (header[3] == 'O' || header[3] == 'S')) || (header[2] == 'U' && header[3] == 0))) {
            return true;
        }
        if (length >= 4 && header[0] == 'M' && header[1] == 'M' && header[2] == 'O' && header[3] == 'R') {
            return true;
        }
        if (length >= 15 && startsWith(header, 0, "FUJIFILMCCD-RAW")) {
            return true;
        }
        if (length >= 14 && header[0] == 'I' && header[1] == 'I' && header[2] == 0x1A && startsWith(header, 6, "HEAPCCDR")) {
            return true;
        }
        if (length >= 4 && header[0] == 0 && header[1] == 'M' && header[2] == 'R' && header[3] == 'M') {
            return true;
        }
        return length >= 4 && startsWith(header, 0, "FOVb");
    }

    /**
     * Classifies a file that starts with a TIFF or BigTIFF signature.
     *
     * @param input the file
     * @return {@link ImageFormat#DNG}, {@link ImageFormat#RAW} or {@link ImageFormat#TIFF}
     * @throws MediaException.Io if reading the input fails
     */
    static ImageFormat classifyTiff(Input input) {
        try {
            return walk(input);
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return ImageFormat.TIFF;
        }
    }

    /**
     * Walks the directories of a classic TIFF file.
     *
     * @param input the file
     * @return the classification
     */
    private static ImageFormat walk(Input input) {
        var header = new byte[16];
        if (readFully(input, 0, header, 16) < 8) {
            return ImageFormat.TIFF;
        }
        var little = header[0] == 'I';
        if (ByteArrays.u16(header, 2, little) != 42) {
            return ImageFormat.TIFF;
        }
        if ((header[8] == 'C' && header[9] == 'R') || startsWith(header, 8, "IIIICwaR")) {
            return ImageFormat.RAW;
        }
        var pending = new long[MAX_DIRECTORIES];
        var visited = new long[MAX_DIRECTORIES];
        var count = 0;
        var visitedCount = 0;
        pending[count++] = ByteArrays.u32(header, 4, little);
        var raw = false;
        var entry = new byte[12];
        var first = true;
        while (count > 0 && visitedCount < MAX_DIRECTORIES) {
            var offset = pending[--count];
            if (offset < 8 || contains(visited, visitedCount, offset)) {
                continue;
            }
            visited[visitedCount++] = offset;
            var countBytes = new byte[2];
            if (readFully(input, offset, countBytes, 2) < 2) {
                continue;
            }
            var entries = Math.min(ByteArrays.u16(countBytes, 0, little), MAX_ENTRIES);
            for (var index = 0; index < entries; index++) {
                if (readFully(input, offset + 2 + 12L * index, entry, 12) < 12) {
                    break;
                }
                var tag = ByteArrays.u16(entry, 0, little);
                var type = ByteArrays.u16(entry, 2, little);
                var values = ByteArrays.u32(entry, 4, little);
                var value = type == 3 ? ByteArrays.u16(entry, 8, little) : ByteArrays.u32(entry, 8, little);
                switch (tag) {
                    case DNG_VERSION -> {
                        if (first) {
                            return ImageFormat.DNG;
                        }
                    }
                    case PHOTOMETRIC -> raw |= value == PHOTOMETRIC_CFA || value == PHOTOMETRIC_LINEAR_RAW;
                    case COMPRESSION -> raw |= Arrays.binarySearch(RAW_COMPRESSIONS, (int) value) >= 0;
                    case SUB_IFDS -> {
                        if (type == 4 || type == 13) {
                            var children = (int) Math.min(values, MAX_DIRECTORIES);
                            if (children == 1) {
                                count = push(pending, count, value);
                            } else {
                                var pointers = new byte[4 * children];
                                var read = readFully(input, value, pointers, pointers.length);
                                for (var child = 0; child + 4 <= read; child += 4) {
                                    count = push(pending, count, ByteArrays.u32(pointers, child, little));
                                }
                            }
                        }
                    }
                    default -> raw |= first && Arrays.binarySearch(RAW_TAGS, tag) >= 0;
                }
            }
            if (entries > 0) {
                var next = new byte[4];
                if (readFully(input, offset + 2 + 12L * entries, next, 4) == 4) {
                    count = push(pending, count, ByteArrays.u32(next, 0, little));
                }
            }
            first = false;
        }
        return raw ? ImageFormat.RAW : ImageFormat.TIFF;
    }

    /**
     * Pushes a directory offset onto the pending stack, when there is room.
     *
     * @param pending the stack
     * @param count the number of offsets on it
     * @param offset the file offset of the directory, ignored when not positive
     * @return the new number of offsets
     */
    private static int push(long[] pending, int count, long offset) {
        if (offset <= 0 || count >= pending.length) {
            return count;
        }
        pending[count] = offset;
        return count + 1;
    }

    /**
     * Returns whether an offset is among the first entries of an array.
     *
     * @param offsets the directory offsets visited so far
     * @param count the number of valid entries
     * @param offset the offset to look for
     * @return whether it is present
     */
    private static boolean contains(long[] offsets, int count, long offset) {
        for (var index = 0; index < count; index++) {
            if (offsets[index] == offset) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads as many bytes as the input holds, up to a count.
     *
     * @param input the file to read
     * @param position the absolute offset
     * @param buffer the destination
     * @param length the most bytes to read
     * @return the number of bytes read
     */
    private static int readFully(Input input, long position, byte[] buffer, int length) {
        var done = 0;
        while (done < length) {
            var read = input.read(position + done, buffer, done, length - done);
            if (read <= 0) {
                break;
            }
            done += read;
        }
        return done;
    }

    /**
     * Returns whether bytes at an offset spell an ASCII string.
     *
     * @param data the bytes
     * @param offset the first index compared
     * @param text the string
     * @return whether they match
     */
    private static boolean startsWith(byte[] data, int offset, String text) {
        for (var index = 0; index < text.length(); index++) {
            if (data[offset + index] != text.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
