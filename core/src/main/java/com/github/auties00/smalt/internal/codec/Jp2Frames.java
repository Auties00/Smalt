package com.github.auties00.smalt.internal.codec;

import java.io.ByteArrayOutputStream;

/**
 * Reads and rewrites the forms of a Motion JPEG 2000 frame ({@link com.github.auties00.smalt.track.Codec#JPEG2000}):
 * a JP2 file, a raw codestream, or the box sequence of an ISO/IEC 15444-3 sample (one or two Contiguous Codestream
 * boxes), and builds the JP2 Header box ({@code jp2h}) that a track's configuration and an {@code mjp2} sample entry
 * hold.
 *
 * <p>Every method treats its input as untrusted: a box or marker segment that does not fit makes it return
 * {@code null}, {@code 0} or {@link #UNKNOWN} rather than throw.
 */
public final class Jp2Frames {
    /**
     * A frame in no known form.
     */
    public static final int UNKNOWN = 0;

    /**
     * A raw codestream, starting with {@code SOC} and {@code SIZ}.
     */
    public static final int CODESTREAM = 1;

    /**
     * A JP2 (or JPX, JPH) file, starting with the signature box.
     */
    public static final int FILE = 2;

    /**
     * A sequence of boxes holding a Contiguous Codestream box, as a Motion JPEG 2000 sample stores a frame.
     */
    public static final int BOXES = 3;

    /**
     * The JPEG 2000 signature box.
     */
    private static final byte[] SIGNATURE = {0, 0, 0, 0x0C, 'j', 'P', ' ', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};

    /**
     * The box type of the Contiguous Codestream box ({@code jp2c}).
     */
    public static final int JP2C = 0x6A703263;

    /**
     * The box type of the JP2 Header box ({@code jp2h}).
     */
    public static final int JP2H = 0x6A703268;

    /**
     * The box type of the Image Header box ({@code ihdr}).
     */
    private static final int IHDR = 0x69686472;

    /**
     * The box type of the Bits Per Component box ({@code bpcc}).
     */
    private static final int BPCC = 0x62706363;

    /**
     * The box type of the Colour Specification box ({@code colr}).
     */
    private static final int COLR = 0x636F6C72;

    /**
     * The box type of the File Type box ({@code ftyp}).
     */
    private static final int FTYP = 0x66747970;

    /**
     * The JP2 brand ({@code jp2 }).
     */
    private static final int JP2_BRAND = 0x6A703220;

    /**
     * The JPH brand ({@code jph }) of a file holding a high-throughput codestream.
     */
    private static final int JPH_BRAND = 0x6A706820;

    /**
     * The {@code Rsiz} flag of a codestream that needs ISO/IEC 15444-15 (bit 14).
     */
    private static final int RSIZ_PART15 = 0x4000;

    /**
     * The enumerated color space sRGB (ISO/IEC 15444-1 Table I.10).
     */
    private static final int SRGB = 16;

    /**
     * The enumerated color space greyscale.
     */
    private static final int GRAYSCALE = 17;

    /**
     * The enumerated color space sYCC.
     */
    private static final int SYCC = 18;

    /**
     * The largest number of boxes examined in one frame.
     */
    private static final int MAX_BOXES = 256;

    /**
     * Prevents instantiation.
     */
    private Jp2Frames() {
        throw new AssertionError();
    }

    /**
     * Holds the fields of a codestream's {@code SIZ} segment and the component transform flag of its main
     * {@code COD} segment (ISO/IEC 15444-1 A.5.1 and A.6.1).
     *
     * @param profile the capabilities field {@code Rsiz}
     * @param width the image width, {@code Xsiz - XOsiz}
     * @param height the image height, {@code Ysiz - YOsiz}
     * @param depths the precision of each component, from 1 to 38
     * @param signed whether each component is signed
     * @param subsamplingX the horizontal subsampling of each component, from 1 to 255
     * @param subsamplingY the vertical subsampling of each component, from 1 to 255
     * @param componentTransform whether the main header applies the multiple component transform
     */
    public record Siz(int profile, long width, long height, int[] depths, boolean[] signed, int[] subsamplingX,
                      int[] subsamplingY, boolean componentTransform) {
        /**
         * {@return the number of components}
         */
        public int components() {
            return depths.length;
        }

        /**
         * {@return the largest component precision}
         */
        public int maxDepth() {
            var max = 0;
            for (var depth : depths) {
                max = Math.max(max, depth);
            }
            return max;
        }

        /**
         * Returns whether the image holds Y'CbCr samples by its layout: three or more components, no component
         * transform, the luma component not subsampled and the two chroma components subsampled alike, by more
         * than 1 in at least one direction.
         *
         * @return whether the layout is a subsampled Y'CbCr one
         */
        public boolean subsampledChroma() {
            return depths.length >= 3 && !componentTransform && subsamplingX[0] == 1 && subsamplingY[0] == 1
                    && subsamplingX[1] == subsamplingX[2] && subsamplingY[1] == subsamplingY[2]
                    && (subsamplingX[1] > 1 || subsamplingY[1] > 1);
        }
    }

    /**
     * Classifies a frame.
     *
     * @param data the array holding the frame
     * @param offset the index of its first byte
     * @param length its length
     * @return {@link #CODESTREAM}, {@link #FILE}, {@link #BOXES} or {@link #UNKNOWN}
     */
    public static int form(byte[] data, int offset, int length) {
        if (length >= 4 && (data[offset] & 0xFF) == 0xFF && (data[offset + 1] & 0xFF) == 0x4F
                && (data[offset + 2] & 0xFF) == 0xFF && (data[offset + 3] & 0xFF) == 0x51) {
            return CODESTREAM;
        }
        if (isFile(data, offset, length)) {
            return FILE;
        }
        return length >= 8 && find(data, offset, offset + length, JP2C, 0, null) > 0 ? BOXES : UNKNOWN;
    }

    /**
     * Returns whether bytes start with the JPEG 2000 signature box.
     *
     * @param data the array holding the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return whether they start a JP2 family file
     */
    public static boolean isFile(byte[] data, int offset, int length) {
        if (length < SIGNATURE.length) {
            return false;
        }
        for (var i = 0; i < SIGNATURE.length; i++) {
            if (data[offset + i] != SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Locates the codestreams of a frame: the whole frame for a raw codestream, otherwise the contents of the
     * Contiguous Codestream boxes at its top level (after the signature box of a file), at most two.
     *
     * @param data the array holding the frame
     * @param offset the index of its first byte
     * @param length its length
     * @param bounds receives the start and end (exclusive) of each codestream: {@code bounds[2k]} and
     * {@code bounds[2k + 1]} for the {@code k}-th; at least 4 entries
     * @return the number of codestreams found, 0 to 2
     */
    public static int codestreams(byte[] data, int offset, int length, int[] bounds) {
        return switch (form(data, offset, length)) {
            case CODESTREAM -> {
                bounds[0] = offset;
                bounds[1] = offset + length;
                yield 1;
            }
            case FILE -> find(data, offset + SIGNATURE.length, offset + length, JP2C, 2, bounds);
            case BOXES -> find(data, offset, offset + length, JP2C, 2, bounds);
            default -> 0;
        };
    }

    /**
     * Returns the JP2 Header box of a JP2 file or box sequence, header included.
     *
     * @param data the array holding the frame
     * @param offset the index of its first byte
     * @param length its length
     * @return a copy of the box, or {@code null} when the frame has none
     */
    public static byte[] header(byte[] data, int offset, int length) {
        var bounds = new int[4];
        if (!findHeader(data, offset, length, bounds)) {
            return null;
        }
        return java.util.Arrays.copyOfRange(data, bounds[0], bounds[1]);
    }

    /**
     * Locates the JP2 Header box of a JP2 file or box sequence, without copying it.
     *
     * @param data the array holding the frame
     * @param offset the index of its first byte
     * @param length its length
     * @param bounds receives the start of the box (its header included) in {@code bounds[0]} and its end
     * (exclusive) in {@code bounds[1]}; at least 4 entries
     * @return whether the frame holds a JP2 Header box at its top level
     */
    public static boolean findHeader(byte[] data, int offset, int length, int[] bounds) {
        var form = form(data, offset, length);
        if (form != FILE && form != BOXES) {
            return false;
        }
        var start = form == FILE ? offset + SIGNATURE.length : offset;
        if (find(data, start, offset + length, JP2H, 1, bounds) != 1) {
            return false;
        }
        bounds[0] -= bounds[2];
        return true;
    }

    /**
     * Returns whether bytes are a JP2 Header box holding an Image Header box first, as ISO/IEC 15444-1 I.5.3
     * requires.
     *
     * @param box the candidate box, header included
     * @return whether it is a JP2 Header box
     */
    public static boolean isHeader(byte[] box) {
        return box != null && isHeader(box, 0, box.length);
    }

    /**
     * Returns whether part of an array is a JP2 Header box holding an Image Header box first, as ISO/IEC 15444-1
     * I.5.3 requires.
     *
     * @param data the array holding the candidate box
     * @param start the index of the box, its header included
     * @param end the end of the box (exclusive)
     * @return whether it is a JP2 Header box
     */
    public static boolean isHeader(byte[] data, int start, int end) {
        if (end - start < 8 + 22 || u32(data, start + 4) != JP2H || u32(data, start) != end - start) {
            return false;
        }
        return u32(data, start + 8) >= 22 && u32(data, start + 12) == IHDR;
    }

    /**
     * Returns the image size an Image Header box of a JP2 Header box declares.
     *
     * @param header the JP2 Header box, header included
     * @return the width and height, or {@code null} when {@code header} is not a JP2 Header box
     */
    public static long[] headerSize(byte[] header) {
        if (!isHeader(header)) {
            return null;
        }
        return new long[]{u32(header, 20), u32(header, 16)};
    }

    /**
     * Returns the largest component precision an Image Header box (and its Bits Per Component box) declares.
     *
     * @param header the JP2 Header box, header included
     * @return the precision, from 1 to 38, or 0 when {@code header} is not a JP2 Header box
     */
    public static int headerDepth(byte[] header) {
        if (!isHeader(header)) {
            return 0;
        }
        var bpc = header[26] & 0xFF;
        if (bpc != 0xFF) {
            return (bpc & 0x7F) + 1;
        }
        var bounds = new int[4];
        if (find(header, 8, header.length, BPCC, 1, bounds) != 1) {
            return 0;
        }
        var max = 0;
        for (var i = bounds[0]; i < bounds[1]; i++) {
            max = Math.max(max, (header[i] & 0x7F) + 1);
        }
        return max;
    }

    /**
     * Returns the enumerated color space of the first Colour Specification box of a JP2 Header box.
     *
     * @param header the JP2 Header box, header included
     * @return the {@code EnumCS} value, or -1 when the header declares none by enumeration
     */
    public static long headerColorSpace(byte[] header) {
        if (!isHeader(header)) {
            return -1;
        }
        var bounds = new int[4];
        if (find(header, 8, header.length, COLR, 1, bounds) != 1 || bounds[1] - bounds[0] < 7
                || header[bounds[0]] != 1) {
            return -1;
        }
        return u32(header, bounds[0] + 3);
    }

    /**
     * Parses the {@code SIZ} segment of a codestream and the component transform flag of the {@code COD} segment of
     * its main header.
     *
     * @param data the array holding the codestream
     * @param start the index of its {@code SOC} marker
     * @param end the end of the codestream (exclusive)
     * @return the fields, or {@code null} when the main header is not valid
     */
    public static Siz siz(byte[] data, int start, int end) {
        if (end - start < 4 + 2 + 38 || u16(data, start) != 0xFF4F || u16(data, start + 2) != 0xFF51) {
            return null;
        }
        var at = start + 4;
        var segment = u16(data, at);
        if (segment < 41 || at + segment > end) {
            return null;
        }
        var components = u16(data, at + 36);
        if (components < 1 || components > 16384 || 38 + 3 * components != segment) {
            return null;
        }
        var width = u32(data, at + 4) - u32(data, at + 12);
        var height = u32(data, at + 8) - u32(data, at + 16);
        if (width <= 0 || height <= 0) {
            return null;
        }
        var depths = new int[components];
        var signed = new boolean[components];
        var dx = new int[components];
        var dy = new int[components];
        for (var c = 0; c < components; c++) {
            var ssiz = data[at + 38 + 3 * c] & 0xFF;
            depths[c] = (ssiz & 0x7F) + 1;
            signed[c] = (ssiz & 0x80) != 0;
            dx[c] = data[at + 39 + 3 * c] & 0xFF;
            dy[c] = data[at + 40 + 3 * c] & 0xFF;
            if (depths[c] > 38 || dx[c] == 0 || dy[c] == 0) {
                return null;
            }
        }
        var transform = false;
        at += segment;
        for (var markers = 0; markers < MAX_BOXES && at + 4 <= end; markers++) {
            var marker = u16(data, at);
            if (marker == 0xFF90 || (marker & 0xFF00) != 0xFF00) {
                break;
            }
            var size = u16(data, at + 2);
            if (size < 2 || at + 2 + size > end) {
                break;
            }
            if (marker == 0xFF52 && size >= 7) {
                transform = (data[at + 8] & 0xFF) != 0;
                break;
            }
            at += 2 + size;
        }
        return new Siz(u16(data, start + 6), width, height, depths, signed, dx, dy, transform);
    }

    /**
     * Parses the main header of the first codestream of a frame, which may be only the leading bytes of the frame:
     * a Contiguous Codestream box that extends past the bytes given is read as far as they go.
     *
     * @param frame the frame, or its leading bytes
     * @return the fields, or {@code null} when the bytes hold no valid main header
     */
    public static Siz firstSiz(byte[] frame) {
        var form = form(frame, 0, frame.length);
        if (form == CODESTREAM) {
            return siz(frame, 0, frame.length);
        }
        var start = form == FILE ? SIGNATURE.length : 0;
        var at = start;
        for (var boxes = 0; boxes < MAX_BOXES && at + 8 <= frame.length; boxes++) {
            var size = u32(frame, at);
            var header = size == 1 ? 16 : 8;
            if (size == 1) {
                if (at + 16 > frame.length || u32(frame, at + 8) != 0) {
                    return null;
                }
                size = u32(frame, at + 12);
            }
            if (u32(frame, at + 4) == JP2C) {
                return siz(frame, at + header, frame.length);
            }
            if (size < header || size > frame.length - at) {
                return null;
            }
            at += (int) size;
        }
        return null;
    }

    /**
     * Builds the JP2 Header box describing a codestream: an Image Header box (with a Bits Per Component box when the
     * components differ in precision or signedness) and an enumerated Colour Specification box declaring grayscale
     * for one or two components, sYCC for a subsampled Y'CbCr layout ({@link Siz#subsampledChroma()}) and sRGB
     * otherwise.
     *
     * @param siz the codestream's main header fields
     * @return the box, header included, or {@code null} when the image is larger than the Image Header box records
     */
    public static byte[] synthesizeHeader(Siz siz) {
        var colorSpace = siz.components() < 3 ? GRAYSCALE : siz.subsampledChroma() ? SYCC : SRGB;
        return buildHeader(siz.width(), siz.height(), siz.depths(), siz.signed(), colorSpace);
    }

    /**
     * Builds a JP2 Header box: an Image Header box (with a Bits Per Component box when the components differ in
     * precision or signedness) and an enumerated Colour Specification box.
     *
     * @param width the image width
     * @param height the image height
     * @param depths the precision of each component, from 1 to 38
     * @param signed whether each component is signed
     * @param colorSpace the enumerated color space ({@code EnumCS}), for example 16 for sRGB, 17 for greyscale and
     * 18 for sYCC
     * @return the box, header included, or {@code null} when the image is larger than the Image Header box records
     */
    public static byte[] buildHeader(long width, long height, int[] depths, boolean[] signed, int colorSpace) {
        if (width > 0xFFFFFFFFL || height > 0xFFFFFFFFL) {
            return null;
        }
        var count = depths.length;
        var uniform = true;
        for (var c = 1; c < count; c++) {
            uniform &= depths[c] == depths[0] && signed[c] == signed[0];
        }
        var out = new ByteArrayOutputStream();
        var ihdr = 8 + 14;
        var bpcc = uniform ? 0 : 8 + count;
        var colr = 8 + 7;
        writeU32(out, 8 + ihdr + bpcc + colr);
        writeU32(out, JP2H);
        writeU32(out, ihdr);
        writeU32(out, IHDR);
        writeU32(out, height);
        writeU32(out, width);
        out.write(count >>> 8);
        out.write(count);
        out.write(uniform ? bitsPerComponent(depths[0], signed[0]) : 0xFF);
        out.write(7);
        out.write(0);
        out.write(0);
        if (!uniform) {
            writeU32(out, bpcc);
            writeU32(out, BPCC);
            for (var c = 0; c < count; c++) {
                out.write(bitsPerComponent(depths[c], signed[c]));
            }
        }
        writeU32(out, colr);
        writeU32(out, COLR);
        out.write(1);
        out.write(0);
        out.write(0);
        writeU32(out, colorSpace);
        return out.toByteArray();
    }

    /**
     * Wraps one codestream into a JP2 file: the signature box, a File Type box, a JP2 Header box and a Contiguous
     * Codestream box. The brand is JP2 ({@code jp2 }), or JPH ({@code jph }, ISO/IEC 15444-15 annex D) for a
     * codestream whose {@code Rsiz} declares the high-throughput block coder.
     *
     * @param header the JP2 Header box, header included
     * @param data the array holding the codestream
     * @param start the index of its first byte
     * @param end the end of the codestream (exclusive)
     * @return the file
     */
    public static byte[] toFile(byte[] header, byte[] data, int start, int end) {
        var length = end - start;
        var brand = length >= 8 && (u16(data, start + 6) & RSIZ_PART15) != 0 ? JPH_BRAND : JP2_BRAND;
        var file = new byte[SIGNATURE.length + 20 + header.length + 8 + length];
        System.arraycopy(SIGNATURE, 0, file, 0, SIGNATURE.length);
        var at = SIGNATURE.length;
        at = putU32(file, at, 20);
        at = putU32(file, at, FTYP);
        at = putU32(file, at, brand);
        at = putU32(file, at, 0);
        at = putU32(file, at, brand);
        System.arraycopy(header, 0, file, at, header.length);
        at += header.length;
        at = putU32(file, at, 8L + length);
        at = putU32(file, at, JP2C);
        System.arraycopy(data, start, file, at, length);
        return file;
    }

    /**
     * Rewrites a frame that relies on its track's JP2 Header box so that it carries that header, for containers
     * that store no configuration with a JPEG 2000 track: a raw codestream or a single Contiguous Codestream box
     * becomes a JP2 file, and the two Contiguous Codestream boxes of an interlaced frame follow a copy of the
     * header box.
     *
     * @param frame the frame in any form
     * @param header the track's configuration, or {@code null} for none
     * @return the rewritten frame when {@code header} is a JP2 Header box and the frame holds a codestream but no
     * JP2 Header box of its own, otherwise the frame itself
     */
    public static byte[] selfContained(byte[] frame, byte[] header) {
        if (!isHeader(header)) {
            return frame;
        }
        var form = form(frame, 0, frame.length);
        if (form != CODESTREAM && form != BOXES) {
            return frame;
        }
        var bounds = new int[4];
        if (form == BOXES && find(frame, 0, frame.length, JP2H, 1, bounds) == 1) {
            return frame;
        }
        var count = codestreams(frame, 0, frame.length, bounds);
        if (count == 1) {
            return toFile(header, frame, bounds[0], bounds[1]);
        }
        if (count != 2) {
            return frame;
        }
        var sample = toSample(frame);
        var boxes = new byte[header.length + sample.length];
        System.arraycopy(header, 0, boxes, 0, header.length);
        System.arraycopy(sample, 0, boxes, header.length, sample.length);
        return boxes;
    }

    /**
     * Rewrites a frame as a Motion JPEG 2000 sample: one Contiguous Codestream box per codestream, at most two.
     *
     * @param frame the frame in any form
     * @return the sample, the frame itself when it already is a sequence of Contiguous Codestream boxes only, or
     * {@code null} when it holds no codestream
     */
    public static byte[] toSample(byte[] frame) {
        var bounds = new int[4];
        var count = codestreams(frame, 0, frame.length, bounds);
        if (count == 0) {
            return null;
        }
        var length = 0L;
        for (var k = 0; k < count; k++) {
            length += 8 + bounds[2 * k + 1] - bounds[2 * k];
        }
        if (length == frame.length && form(frame, 0, frame.length) == BOXES) {
            return frame;
        }
        var sample = new byte[Math.toIntExact(length)];
        var at = 0;
        for (var k = 0; k < count; k++) {
            var size = bounds[2 * k + 1] - bounds[2 * k];
            at = putU32(sample, at, 8L + size);
            at = putU32(sample, at, JP2C);
            System.arraycopy(frame, bounds[2 * k], sample, at, size);
            at += size;
        }
        return sample;
    }

    /**
     * Returns the {@code BPC} byte of a component.
     *
     * @param depth the component precision
     * @param signed whether the component is signed
     * @return the precision minus one, with bit 7 set for a signed component
     */
    private static int bitsPerComponent(int depth, boolean signed) {
        return (depth - 1) | (signed ? 0x80 : 0);
    }

    /**
     * Finds boxes of one type among a sequence of boxes, stopping at the first box that does not fit.
     *
     * @param data the array holding the boxes
     * @param start the index of the first box
     * @param end the end of the sequence (exclusive)
     * @param type the box type wanted
     * @param wanted how many boxes to report in {@code bounds}, or 0 to only count up to one
     * @param bounds receives the content start and end of each box found, and for a single wanted box its header
     * length in {@code bounds[2]}; ignored when {@code wanted} is 0
     * @return the number of boxes found, at most {@code max(wanted, 1)}
     */
    private static int find(byte[] data, int start, int end, int type, int wanted, int[] bounds) {
        var found = 0;
        var at = start;
        for (var boxes = 0; boxes < MAX_BOXES && at + 8 <= end; boxes++) {
            var size = u32(data, at);
            var header = 8;
            if (size == 1) {
                if (at + 16 > end) {
                    break;
                }
                var high = u32(data, at + 8);
                size = high != 0 ? -1 : u32(data, at + 12);
                header = 16;
            } else if (size == 0) {
                size = end - at;
            }
            if (size < header) {
                break;
            }
            if (wanted == 0 && u32(data, at + 4) == type) {
                return 1;
            }
            if (size > end - at) {
                break;
            }
            if (u32(data, at + 4) == type) {
                bounds[2 * found] = at + header;
                bounds[2 * found + 1] = (int) (at + size);
                if (wanted == 1) {
                    bounds[2] = header;
                }
                if (++found == wanted) {
                    break;
                }
            }
            at += (int) size;
        }
        return found;
    }

    /**
     * Reads a big-endian 16-bit value.
     *
     * @param data the array
     * @param at the index of the first byte
     * @return the value
     */
    private static int u16(byte[] data, int at) {
        return (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
    }

    /**
     * Reads a big-endian unsigned 32-bit value.
     *
     * @param data the array
     * @param at the index of the first byte
     * @return the value
     */
    private static long u32(byte[] data, int at) {
        return (long) (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8
                | data[at + 3] & 0xFF;
    }

    /**
     * Writes a big-endian 32-bit value into an array.
     *
     * @param data the array
     * @param at the index of the first byte
     * @param value the 32-bit value, its higher bits ignored
     * @return the index after it
     */
    private static int putU32(byte[] data, int at, long value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
        return at + 4;
    }

    /**
     * Writes a big-endian 32-bit value into a stream.
     *
     * @param out the stream
     * @param value the 32-bit value, its higher bits ignored
     */
    private static void writeU32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }
}
