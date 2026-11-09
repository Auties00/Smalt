package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Locates the fields of a Motion-JPEG frame, converts QuickTime Motion-JPEG format B frames to
 * interchange JPEG streams, and weaves two decoded fields into one frame.
 *
 * <p>A frame holds one JPEG image, or two field images one after the other: the OpenDML
 * convention marks each field with an {@code APP0 AVI1} segment carrying its polarity and size,
 * QuickTime Motion-JPEG format A with an {@code APP1 mjpg} segment carrying the offset of the next
 * field. Format B carries the same information in a header that replaces the markers: its frames
 * hold no marker codes and no stuffed bytes, and {@link #toInterchange} rebuilds the marker stream
 * from the header's offsets.
 *
 * <p>This class is thread-safe. Arguments are not checked; values outside the documented ranges
 * give unspecified results.
 */
public final class MotionJpegFields {
    /**
     * The tag of the QuickTime Motion-JPEG headers, {@code 'mjpg'}.
     */
    private static final int MJPG = 0x6D6A7067;

    /**
     * The size of the fields of a format B header, ten 32-bit integers.
     */
    private static final int FORMAT_B_HEADER = 40;

    /**
     * The marker codes of the segments a format B header points to, in the order of its offsets.
     */
    private static final int[] FORMAT_B_MARKERS = {0xDB, 0xC4, 0xC0, 0xDA};

    /**
     * Prevents instantiation.
     */
    private MotionJpegFields() {
        throw new AssertionError();
    }

    /**
     * Returns whether a frame is coded in QuickTime Motion-JPEG format B: it starts with the
     * marker-less header, whose second word is the tag {@code 'mjpg'}, rather than with SOI.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame
     * @param length the frame length
     * @return {@code true} for a format B frame
     */
    public static boolean isFormatB(byte[] data, int offset, int length) {
        return length >= FORMAT_B_HEADER && !(data[offset] == (byte) 0xFF && data[offset + 1] == (byte) 0xD8)
                && u32(data, offset + 4) == MJPG;
    }

    /**
     * Converts a QuickTime Motion-JPEG format B frame into interchange JPEG streams, one per field,
     * concatenated in their stored order.
     *
     * <p>Each field starts with a header of 32-bit big-endian integers: a reserved word, the tag
     * {@code 'mjpg'}, the field size, the padded field size, and the offsets, from the start of the
     * field, of the next field (0 for the last), of the quantization tables, of the Huffman tables
     * (0 for the tables of T.81 Annex K), of the frame header, of the scan header and of the
     * entropy-coded data. The tables and headers are marker segments without their marker codes,
     * and the entropy-coded data has no stuffed zero bytes; each field becomes SOI, DQT, DHT (when
     * present), SOF0, SOS, the data with its {@code 0xFF} bytes stuffed, and EOI. The data runs to
     * the end of the field: its size, or the start of the next field, or the end of the frame,
     * whichever comes first. At most two fields are converted.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame
     * @param length the frame length
     * @param limits the resource bounds checked before the allocation
     * @return the interchange streams
     * @throws MediaException.Malformed if a header or a segment it points to is invalid
     * @throws MediaException.Unsupported if a field takes its quantization tables from the sample
     * description, which this conversion does not read
     * @throws MediaException.LimitExceeded if the converted frame exceeds the limits
     */
    public static byte[] toInterchange(byte[] data, int offset, int length, MediaLimits limits) {
        var end = offset + length;
        var starts = new int[2];
        var ends = new int[2];
        var count = 0;
        var start = offset;
        while (count < 2) {
            if (start + FORMAT_B_HEADER > end || u32(data, start + 4) != MJPG) {
                throw new MediaException.Malformed("Motion-JPEG format B field without its 'mjpg' header");
            }
            var next = u32(data, start + 16);
            var limit = end;
            if (next > 0 && next < end - start) {
                limit = start + (int) next;
            }
            var size = u32(data, start + 8);
            if (size >= FORMAT_B_HEADER && size < limit - start) {
                limit = start + (int) size;
            }
            starts[count] = start;
            ends[count] = limit;
            count++;
            if (next <= 0 || next >= end - start) {
                break;
            }
            start += (int) next;
        }
        var total = 0L;
        for (var field = 0; field < count; field++) {
            total += interchangeLength(data, starts[field], ends[field]);
        }
        LimitChecks.checkAllocation(limits, total);
        var out = new byte[(int) total];
        var at = 0;
        for (var field = 0; field < count; field++) {
            at = writeInterchange(data, starts[field], ends[field], out, at);
        }
        return out;
    }

    /**
     * Returns the length of the interchange stream of one format B field.
     *
     * @param data the frame
     * @param start the index of the field header
     * @param end the index after the field
     * @return the stream length
     */
    private static long interchangeLength(byte[] data, int start, int end) {
        var length = 4L;
        for (var slot = 0; slot < 4; slot++) {
            var segment = segment(data, start, end, slot);
            if (segment >= 0) {
                length += 2 + u16(data, segment);
            }
        }
        var entropy = entropyStart(data, start, end);
        for (var i = entropy; i < end; i++) {
            length += data[i] == (byte) 0xFF ? 2 : 1;
        }
        return length;
    }

    /**
     * Writes the interchange stream of one format B field.
     *
     * @param data the frame
     * @param start the index of the field header
     * @param end the index after the field
     * @param out the destination
     * @param at the index in {@code out} to write at
     * @return the index after the stream
     */
    private static int writeInterchange(byte[] data, int start, int end, byte[] out, int at) {
        out[at++] = (byte) 0xFF;
        out[at++] = (byte) 0xD8;
        for (var slot = 0; slot < 4; slot++) {
            var segment = segment(data, start, end, slot);
            if (segment >= 0) {
                var size = u16(data, segment);
                out[at++] = (byte) 0xFF;
                out[at++] = (byte) FORMAT_B_MARKERS[slot];
                System.arraycopy(data, segment, out, at, size);
                at += size;
            }
        }
        for (var i = entropyStart(data, start, end); i < end; i++) {
            out[at++] = data[i];
            if (data[i] == (byte) 0xFF) {
                out[at++] = 0;
            }
        }
        out[at++] = (byte) 0xFF;
        out[at++] = (byte) 0xD9;
        return at;
    }

    /**
     * Returns the index of a marker segment a format B header points to, at its length field.
     *
     * @param data the frame
     * @param start the index of the field header
     * @param end the index after the field
     * @param slot 0 for the quantization tables, 1 for the Huffman tables, 2 for the frame header
     * and 3 for the scan header
     * @return the index, or {@code -1} for Huffman tables the field does not carry
     * @throws MediaException.Malformed if the offset or the segment is out of the field
     * @throws MediaException.Unsupported if the field carries no quantization tables
     */
    private static int segment(byte[] data, int start, int end, int slot) {
        var offset = u32(data, start + 20 + 4 * slot);
        if (offset == 0) {
            // TODO: read the quantization tables of the sample description's mjqt box, whose layout the QuickTime
            //  File Format does not give and of which no sample is at hand
            return switch (slot) {
                case 0 -> throw new MediaException.Unsupported("mjpeg format b",
                        "the Motion-JPEG format B field takes its quantization tables from the sample description");
                case 1 -> -1;
                default -> throw new MediaException.Malformed("Motion-JPEG format B field without "
                        + (slot == 2 ? "a frame" : "a scan") + " header");
            };
        }
        if (offset < FORMAT_B_HEADER || offset > end - start - 2) {
            throw new MediaException.Malformed("Motion-JPEG format B header points outside its field");
        }
        var at = start + (int) offset;
        var size = u16(data, at);
        if (size < 2 || size > end - at) {
            throw new MediaException.Malformed("truncated Motion-JPEG format B segment");
        }
        return at;
    }

    /**
     * Returns the index of the entropy-coded data of a format B field: the start of data offset,
     * or the end of the scan header when the offset is zero.
     *
     * @param data the frame
     * @param start the index of the field header
     * @param end the index after the field
     * @return the index of the first entropy-coded byte
     * @throws MediaException.Malformed if the offset is out of the field
     */
    private static int entropyStart(byte[] data, int start, int end) {
        var offset = u32(data, start + 36);
        if (offset == 0) {
            var scan = segment(data, start, end, 3);
            return scan + u16(data, scan);
        }
        if (offset < FORMAT_B_HEADER || offset > end - start) {
            throw new MediaException.Malformed("Motion-JPEG format B data offset points outside its field");
        }
        return start + (int) offset;
    }

    /**
     * Locates the fields of an interchange Motion-JPEG frame.
     *
     * <p>The second field starts where the first field's {@code APP1 mjpg} segment or
     * {@code APP0 AVI1} segment says, when that position holds SOI; otherwise after the first
     * field's EOI and any padding of {@code 0x00} or {@code 0xFF} bytes. A frame with anything else
     * after its first image has one field.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's SOI marker
     * @param length the frame length
     * @param bounds receives the start and end index of the first field and, for a two-field frame,
     * of the second field; at least four elements
     * @return the number of fields, 1 or 2
     */
    public static int split(byte[] data, int offset, int length, int[] bounds) {
        var end = offset + length;
        var firstEnd = endOfImage(data, offset, end);
        bounds[0] = offset;
        bounds[1] = firstEnd;
        var hinted = hintedNextField(data, offset, end);
        int second;
        if (hinted > offset + 2 && isSoi(data, hinted, end)) {
            second = hinted;
            bounds[1] = Math.min(firstEnd, hinted);
        } else {
            second = firstEnd;
            while (second < end && (data[second] == 0 || (data[second] == (byte) 0xFF && !isSoi(data, second, end)))) {
                second++;
            }
            if (!isSoi(data, second, end)) {
                return 1;
            }
        }
        bounds[2] = second;
        bounds[3] = endOfImage(data, second, end);
        return 2;
    }

    /**
     * Returns the polarity an {@code APP0 AVI1} segment right after SOI declares for a field.
     *
     * @param data the array holding the field
     * @param offset the index of the field's SOI marker
     * @param end the index after the field
     * @return 0 for a progressive frame, 1 for the first field, 2 for the second, another byte
     * value as stored, or {@code -1} when the field has no such segment
     */
    public static int polarity(byte[] data, int offset, int end) {
        var at = offset + 2;
        if (at + 9 > end || data[at] != (byte) 0xFF || data[at + 1] != (byte) 0xE0 || u16(data, at + 2) < 7
                || u32(data, at + 4) != 0x41564931) {
            return -1;
        }
        return data[at + 8] & 0xFF;
    }

    /**
     * Returns where the field after the one at {@code offset} starts according to its
     * {@code APP1 mjpg} or {@code APP0 AVI1} segment.
     *
     * @param data the frame
     * @param offset the index of the field's SOI marker
     * @param end the index after the frame
     * @return the index of the next field, or {@code -1} when no segment gives it
     */
    private static int hintedNextField(byte[] data, int offset, int end) {
        var at = offset + 2;
        while (at + 4 <= end && data[at] == (byte) 0xFF) {
            var marker = data[at + 1] & 0xFF;
            if (marker < 0xE0 || marker > 0xEF) {
                return -1;
            }
            var size = u16(data, at + 2);
            if (size < 2 || at + 2 + size > end) {
                return -1;
            }
            if (marker == 0xE1 && size >= 22 && u32(data, at + 8) == MJPG) {
                var next = u32(data, at + 20);
                return next > 0 && next < end - offset ? offset + (int) next : -1;
            }
            if (marker == 0xE0 && size >= 12 && u32(data, at + 4) == 0x41564931) {
                var fieldSize = u32(data, at + 10);
                return fieldSize > 0 && fieldSize < end - offset ? offset + (int) fieldSize : -1;
            }
            at += 2 + size;
        }
        return -1;
    }

    /**
     * Returns the index after the EOI marker of the image at {@code offset}, walking its segments
     * and skipping the entropy-coded data of its scans.
     *
     * @param data the frame
     * @param offset the index of the image's SOI marker
     * @param end the index after the frame
     * @return the index after EOI, or {@code end} when the image has none
     */
    private static int endOfImage(byte[] data, int offset, int end) {
        var at = offset + 2;
        while (at + 1 < end) {
            if (data[at] != (byte) 0xFF) {
                at++;
                continue;
            }
            var marker = data[at + 1] & 0xFF;
            if (marker == 0xFF) {
                at++;
                continue;
            }
            if (marker == 0xD9) {
                return at + 2;
            }
            if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                at += 2;
                continue;
            }
            if (at + 4 > end) {
                return end;
            }
            var size = u16(data, at + 2);
            if (size < 2 || at + 2 + size > end) {
                return end;
            }
            at += 2 + size;
            if (marker == 0xDA) {
                while (at + 1 < end) {
                    if (data[at] == (byte) 0xFF) {
                        var next = data[at + 1] & 0xFF;
                        if (next != 0x00 && next != 0xFF && (next < 0xD0 || next > 0xD7)) {
                            break;
                        }
                        at += next == 0xFF ? 1 : 2;
                    } else {
                        at++;
                    }
                }
            }
        }
        return end;
    }

    /**
     * Weaves two decoded fields into one interlaced frame: the rows of {@code top} become the
     * frame's rows 0, 2, 4 and so on, those of {@code bottom} rows 1, 3, 5 and so on, and the chroma
     * rows alternate the same way.
     *
     * @param top the top field, a {@link YuvPicture}, a {@link Yuv16Picture}, an {@link RgbaPicture} or an
     * {@link Rgba16Picture}
     * @param bottom the bottom field, of the same type, width, chroma format, depth, color and alpha
     * as {@code top}, and as many rows or one fewer
     * @param height the frame height, at most the sum of the field heights
     * @param limits the resource bounds checked before the allocation
     * @return the frame, with tight strides
     * @throws MediaException.Malformed if the fields do not {@linkplain #pairs pair}
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public static Picture weave(Picture top, Picture bottom, int height, MediaLimits limits) {
        if (!pairs(top, bottom)) {
            throw new MediaException.Malformed("Motion-JPEG fields of " + top.width() + "x" + top.height() + " and "
                    + bottom.width() + "x" + bottom.height() + " do not form one frame");
        }
        LimitChecks.checkPicture(limits, top.width(), height);
        return switch (top) {
            case YuvPicture first -> weaveBytes(first, (YuvPicture) bottom, height);
            case Yuv16Picture first -> weaveShorts(first, (Yuv16Picture) bottom, height);
            case Rgba16Picture first -> weaveWidePixels(first, (Rgba16Picture) bottom, height);
            default -> weavePixels((RgbaPicture) top, (RgbaPicture) bottom, height);
        };
    }

    /**
     * Returns whether two decoded fields form one frame: both are {@link YuvPicture} or both
     * {@link Yuv16Picture} of the same width, chroma format, depth and color, or both
     * {@link RgbaPicture} or both {@link Rgba16Picture} of the same width and alpha, and the top field
     * has as many rows as the bottom one or one more.
     *
     * @param top the top field
     * @param bottom the bottom field
     * @return {@code true} when {@link #weave} accepts the fields
     */
    public static boolean pairs(Picture top, Picture bottom) {
        if (top.width() != bottom.width() || (top.height() != bottom.height() && top.height() != bottom.height() + 1)) {
            return false;
        }
        return switch (top) {
            case YuvPicture first when bottom instanceof YuvPicture second ->
                    first.chromaFormat() == second.chromaFormat() && first.color().equals(second.color());
            case Yuv16Picture first when bottom instanceof Yuv16Picture second ->
                    first.chromaFormat() == second.chromaFormat() && first.bitDepth() == second.bitDepth()
                            && first.color().equals(second.color());
            case RgbaPicture first when bottom instanceof RgbaPicture second -> first.hasAlpha() == second.hasAlpha();
            case Rgba16Picture first when bottom instanceof Rgba16Picture second ->
                    first.hasAlpha() == second.hasAlpha();
            default -> false;
        };
    }

    /**
     * Weaves two 8-bit fields.
     *
     * @param top the top field
     * @param bottom the bottom field
     * @param height the frame height
     * @return the frame
     */
    private static YuvPicture weaveBytes(YuvPicture top, YuvPicture bottom, int height) {
        var format = top.chromaFormat();
        var frame = YuvPicture.allocate(top.width(), height, format, false, top.color());
        weavePlane(top.y(), top.yStride(), top.height(), bottom.y(), bottom.yStride(), bottom.height(), frame.y(),
                frame.yStride(), top.width(), height);
        if (format.hasChroma()) {
            weavePlane(top.u(), top.uvStride(), top.chromaHeight(), bottom.u(), bottom.uvStride(),
                    bottom.chromaHeight(), frame.u(), frame.uvStride(), frame.chromaWidth(), frame.chromaHeight());
            weavePlane(top.v(), top.uvStride(), top.chromaHeight(), bottom.v(), bottom.uvStride(),
                    bottom.chromaHeight(), frame.v(), frame.uvStride(), frame.chromaWidth(), frame.chromaHeight());
        }
        return frame;
    }

    /**
     * Weaves two fields of more than 8 bits.
     *
     * @param top the top field
     * @param bottom the bottom field
     * @param height the frame height
     * @return the frame
     */
    private static Yuv16Picture weaveShorts(Yuv16Picture top, Yuv16Picture bottom, int height) {
        var format = top.chromaFormat();
        var frame = Yuv16Picture.allocate(top.width(), height, format, top.bitDepth(), false, top.color());
        weavePlane(top.y(), top.yStride(), top.height(), bottom.y(), bottom.yStride(), bottom.height(), frame.y(),
                frame.yStride(), top.width(), height);
        if (format.hasChroma()) {
            weavePlane(top.u(), top.uvStride(), top.chromaHeight(), bottom.u(), bottom.uvStride(),
                    bottom.chromaHeight(), frame.u(), frame.uvStride(), frame.chromaWidth(), frame.chromaHeight());
            weavePlane(top.v(), top.uvStride(), top.chromaHeight(), bottom.v(), bottom.uvStride(),
                    bottom.chromaHeight(), frame.v(), frame.uvStride(), frame.chromaWidth(), frame.chromaHeight());
        }
        return frame;
    }

    /**
     * Weaves two RGBA fields.
     *
     * @param top the top field
     * @param bottom the bottom field
     * @param height the frame height
     * @return the frame
     */
    private static RgbaPicture weavePixels(RgbaPicture top, RgbaPicture bottom, int height) {
        var frame = RgbaPicture.allocate(top.width(), height, top.hasAlpha());
        weavePlane(top.pixels(), top.stride(), top.height(), bottom.pixels(), bottom.stride(), bottom.height(),
                frame.pixels(), frame.stride(), 4 * top.width(), height);
        return frame;
    }

    /**
     * Weaves two 16-bit RGBA fields.
     *
     * @param top the top field
     * @param bottom the bottom field
     * @param height the frame height
     * @return the frame
     */
    private static Rgba16Picture weaveWidePixels(Rgba16Picture top, Rgba16Picture bottom, int height) {
        var stride = 4 * top.width();
        var pixels = new short[stride * height];
        weavePlane(top.pixels(), top.stride(), top.height(), bottom.pixels(), bottom.stride(), bottom.height(),
                pixels, stride, stride, height);
        return Rgba16Picture.of(top.width(), height, stride, pixels, top.hasAlpha());
    }

    /**
     * Interleaves the rows of two planes; a field that runs out of rows repeats its last one.
     *
     * @param top the top field's plane, an array of {@code byte} or {@code short}
     * @param topStride its stride
     * @param topRows its row count
     * @param bottom the bottom field's plane, of the same element type
     * @param bottomStride its stride
     * @param bottomRows its row count
     * @param out the frame's plane, of the same element type
     * @param outStride its stride
     * @param width the samples per row
     * @param rows the frame's row count
     */
    private static void weavePlane(Object top, int topStride, int topRows, Object bottom, int bottomStride,
                                   int bottomRows, Object out, int outStride, int width, int rows) {
        for (var y = 0; y < rows; y++) {
            var fromTop = (y & 1) == 0;
            var row = Math.min(y >> 1, (fromTop ? topRows : bottomRows) - 1);
            System.arraycopy(fromTop ? top : bottom, row * (fromTop ? topStride : bottomStride), out, y * outStride,
                    width);
        }
    }

    /**
     * Returns whether an SOI marker is at an index.
     *
     * @param data the bytes
     * @param at the index
     * @param end the index after the valid bytes
     * @return {@code true} when {@code FF D8} is at {@code at}
     */
    private static boolean isSoi(byte[] data, int at, int end) {
        return at + 1 < end && data[at] == (byte) 0xFF && data[at + 1] == (byte) 0xD8;
    }

    /**
     * Reads a big-endian 16-bit value.
     *
     * @param data the bytes
     * @param at the index
     * @return the value
     */
    private static int u16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    /**
     * Reads a big-endian 32-bit value.
     *
     * @param data the bytes
     * @param at the index
     * @return the unsigned value
     */
    private static long u32(byte[] data, int at) {
        return ((long) u16(data, at) << 16) | u16(data, at + 2);
    }
}
