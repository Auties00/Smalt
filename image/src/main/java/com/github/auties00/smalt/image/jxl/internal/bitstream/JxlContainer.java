/* Ports jxl-oxide 0.12.6 (crates/jxl-bitstream: the container parser). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.bitstream;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.brotli.BrotliDecoder;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits a JPEG XL file into its codestream and its other boxes (ISO/IEC 18181-2).
 *
 * <p>A file is either a bare codestream, starting {@code FF 0A}, or an ISO-BMFF container
 * starting with the twelve-byte {@code JXL } signature box. In a container the codestream is one
 * {@code jxlc} box or a sequence of {@code jxlp} boxes, whose four-byte indices must count up from
 * zero with the top bit set on the last one. Every other box except {@code ftyp} and {@code jxll} is listed in
 * file order by {@link #boxes()}; a {@code brob} box is listed under the type of the box it compresses and
 * decompressed when read, and one that compresses a {@code jxl*}, {@code jbrd} or {@code brob} box is skipped.
 * The first {@code Exif} and {@code xml } (XMP) boxes, compressed or not, are decompressed while parsing; one
 * whose Brotli stream is corrupt is left out, since metadata never prevents decoding the image. The
 * {@code jxli} frame index is parsed, and left out when it is invalid, since it only speeds up seeking.
 *
 * <p>A {@code jxlc} box excludes {@code jxlp} boxes, out-of-order {@code jxlp} indices are
 * rejected, and a box whose size is zero extends to the end of the file. Instances are immutable
 * after parsing.
 */
public final class JxlContainer {
    /**
     * The container signature box, {@code 00 00 00 0C 'J' 'X' 'L' ' ' 0D 0A 87 0A}.
     */
    private static final byte[] CONTAINER_SIGNATURE = {0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};

    /**
     * The array holding the codestream.
     */
    private final byte[] codestream;

    /**
     * The index of the first codestream byte in {@link #codestream}.
     */
    private final int codestreamOffset;

    /**
     * The number of codestream bytes.
     */
    private final int codestreamLength;

    /**
     * The payload of the first {@code Exif} box, without its four-byte TIFF header offset, or
     * {@code null}.
     */
    private final byte[] exif;

    /**
     * The payload of the first {@code Exif} box after its four-byte TIFF header offset, or
     * {@code null}.
     */
    private final byte[] exifBox;

    /**
     * The payload of the first {@code xml } box, or {@code null}.
     */
    private final byte[] xmp;

    /**
     * The payload of the {@code jbrd} box, or {@code null}.
     */
    private final byte[] jbrd;

    /**
     * The boxes other than the codestream, in file order.
     */
    private final List<Box> boxes;

    /**
     * The parsed {@code jxli} box, or {@code null}.
     */
    private final FrameIndex frameIndex;

    /**
     * Holds a box of the container other than the codestream.
     *
     * @param type the four-character box type; for a {@code brob} box, the type of the box it compresses
     * @param data the array holding the file
     * @param offset the index of the stored payload in {@code data}, after the inner type of a {@code brob} box
     * @param length the stored payload length in bytes
     * @param compressed whether the payload is a Brotli stream from a {@code brob} box
     */
    public record Box(String type, byte[] data, int offset, int length, boolean compressed) {
        /**
         * Returns the payload, decompressed when the box is a {@code brob} box.
         *
         * @param limits the resource bounds, whose byte limit caps the decompressed size
         * @return a new array holding the payload
         * @throws MediaException.Malformed if the Brotli stream is corrupt
         * @throws MediaException.LimitExceeded if the decompressed payload exceeds the byte limit
         */
        public byte[] content(MediaLimits limits) {
            if (!compressed) {
                LimitChecks.checkAllocation(limits, length);
                return Arrays.copyOfRange(data, offset, offset + length);
            }
            return BrotliDecoder.decompress(data, offset, length, limits.maxBytes());
        }
    }

    /**
     * Holds the {@code jxli} frame index: the codestream offsets of frames a decoder can start from (ISO/IEC
     * 18181-2 section 9.3).
     *
     * @param ticksNumerator the numerator of the duration of a tick in seconds, positive
     * @param ticksDenominator the denominator of the duration of a tick in seconds, positive
     * @param entries the indexed frames, in codestream order, the first one the first frame
     */
    public record FrameIndex(long ticksNumerator, long ticksDenominator, List<Entry> entries) {
        /**
         * Holds one indexed frame.
         *
         * @param offset the byte offset of the frame from the start of the codestream
         * @param ticks the time from the start of this frame to the start of the next indexed one, in ticks
         * @param frames the number of displayed frames from this frame to the next indexed one
         */
        public record Entry(long offset, long ticks, long frames) {
        }
    }

    private JxlContainer(byte[] codestream, int codestreamOffset, int codestreamLength, byte[] exif, byte[] exifBox,
                         byte[] xmp, byte[] jbrd, List<Box> boxes, FrameIndex frameIndex) {
        this.codestream = codestream;
        this.codestreamOffset = codestreamOffset;
        this.codestreamLength = codestreamLength;
        this.exif = exif;
        this.exifBox = exifBox;
        this.xmp = xmp;
        this.jbrd = jbrd;
        this.boxes = boxes;
        this.frameIndex = frameIndex;
    }

    /**
     * {@return whether bytes start a JPEG XL file, as a bare codestream or a container}
     *
     * @param header the leading bytes
     * @param length the number of valid bytes
     */
    private static boolean isJxl(byte[] header, int length) {
        if (length >= 2 && (header[0] & 0xFF) == 0xFF && header[1] == 0x0A) {
            return true;
        }
        if (length < CONTAINER_SIGNATURE.length) {
            return false;
        }
        for (var i = 0; i < CONTAINER_SIGNATURE.length; i++) {
            if (header[i] != CONTAINER_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a whole file.
     *
     * @param data the file
     * @param limits the resource bounds applied to concatenated partial codestreams
     * @return the parsed view; its codestream may alias {@code data}
     * @throws MediaException.Malformed if the signature or the box structure is invalid
     * @throws MediaException.LimitExceeded if the codestream exceeds the limits
     */
    public static JxlContainer parse(byte[] data, MediaLimits limits) {
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && data[1] == 0x0A) {
            return new JxlContainer(data, 0, data.length, null, null, null, null, List.of(), null);
        }
        if (!isJxl(data, data.length)) {
            throw new MediaException.Malformed("not a JPEG XL file");
        }
        var offset = (long) CONTAINER_SIGNATURE.length;
        byte[] exif = null;
        byte[] exifBox = null;
        byte[] xmp = null;
        byte[] jbrd = null;
        FrameIndex frameIndex = null;
        var boxes = new ArrayList<Box>();
        var jxlcOffset = -1;
        var jxlcLength = 0;
        ByteArrayOutputStream partial = null;
        var nextPartialIndex = 0;
        var partialFinished = false;
        while (offset < data.length) {
            if (data.length - offset < 8) {
                throw new MediaException.Malformed("truncated JPEG XL box header");
            }
            var size = ByteArrays.u32be(data, (int) offset);
            var type = new String(data, (int) offset + 4, 4, StandardCharsets.ISO_8859_1);
            long headerSize = 8;
            long boxSize;
            if (size == 1) {
                if (data.length - offset < 16) {
                    throw new MediaException.Malformed("truncated JPEG XL box header");
                }
                boxSize = ((long) ByteArrays.u32be(data, (int) offset + 8) << 32) | ByteArrays.u32be(data, (int) offset + 12);
                headerSize = 16;
                if (boxSize < 16) {
                    throw new MediaException.Malformed("invalid JPEG XL box size " + boxSize);
                }
            } else if (size == 0) {
                boxSize = data.length - offset;
            } else {
                if (size < 8) {
                    throw new MediaException.Malformed("invalid JPEG XL box size " + size);
                }
                boxSize = size;
            }
            var payloadStart = offset + headerSize;
            var payloadEnd = Math.min(offset + boxSize, data.length);
            if (payloadStart > payloadEnd) {
                throw new MediaException.Malformed("truncated JPEG XL box " + type);
            }
            var payloadLength = (int) (payloadEnd - payloadStart);
            switch (type) {
                case "jxlc" -> {
                    if (jxlcOffset >= 0 || partial != null) {
                        throw new MediaException.Malformed("a JPEG XL container holds more than one codestream");
                    }
                    jxlcOffset = (int) payloadStart;
                    jxlcLength = payloadLength;
                }
                case "jxlp" -> {
                    if (jxlcOffset >= 0) {
                        throw new MediaException.Malformed("jxlp box after a jxlc box");
                    }
                    if (partialFinished) {
                        throw new MediaException.Malformed("jxlp box after the last partial codestream");
                    }
                    if (payloadLength < 4) {
                        throw new MediaException.Malformed("truncated jxlp box");
                    }
                    var index = ByteArrays.u32be(data, (int) payloadStart);
                    if ((index & 0x7FFF_FFFFL) != nextPartialIndex) {
                        throw new MediaException.Malformed("out-of-order jxlp box " + (index & 0x7FFF_FFFFL));
                    }
                    nextPartialIndex++;
                    partialFinished = (index & 0x8000_0000L) != 0;
                    if (partial == null) {
                        partial = new ByteArrayOutputStream();
                    }
                    LimitChecks.checkAllocation(limits, (long) partial.size() + payloadLength - 4);
                    partial.write(data, (int) payloadStart + 4, payloadLength - 4);
                }
                case "ftyp", "jxll" -> {
                }
                case "brob" -> {
                    if (payloadLength >= 4) {
                        var inner = new String(data, (int) payloadStart, 4, StandardCharsets.ISO_8859_1);
                        if (!inner.startsWith("jxl") && !inner.equals("jbrd") && !inner.equals("brob")) {
                            boxes.add(new Box(inner, data, (int) payloadStart + 4, payloadLength - 4, true));
                        }
                    }
                }
                default -> boxes.add(new Box(type, data, (int) payloadStart, payloadLength, false));
            }
            offset += boxSize;
        }
        for (var box : boxes) {
            switch (box.type()) {
                case "Exif" -> {
                    if (exifBox == null) {
                        var payload = metadataContent(box, limits);
                        exifBox = payload != null && payload.length >= 4 ? payload : null;
                    }
                }
                case "xml " -> {
                    if (xmp == null) {
                        xmp = metadataContent(box, limits);
                    }
                }
                case "jbrd" -> {
                    if (jbrd == null) {
                        jbrd = box.content(limits);
                    }
                }
                case "jxli" -> {
                    if (frameIndex == null && !box.compressed()) {
                        frameIndex = readFrameIndex(box.data(), box.offset(), box.length());
                    }
                }
                default -> {
                }
            }
        }
        if (exifBox != null) {
            var tiffOffset = ByteArrays.u32be(exifBox, 0);
            if (tiffOffset < exifBox.length - 4) {
                exif = Arrays.copyOfRange(exifBox, 4 + (int) tiffOffset, exifBox.length);
            }
            exifBox = Arrays.copyOfRange(exifBox, 4, exifBox.length);
        }
        var listed = List.copyOf(boxes);
        if (jxlcOffset >= 0) {
            return new JxlContainer(data, jxlcOffset, jxlcLength, exif, exifBox, xmp, jbrd, listed, frameIndex);
        }
        if (partial == null) {
            throw new MediaException.Malformed("JPEG XL container without a codestream");
        }
        var joined = partial.toByteArray();
        return new JxlContainer(joined, 0, joined.length, exif, exifBox, xmp, jbrd, listed, frameIndex);
    }

    /**
     * Reads the payload of an {@code Exif} or {@code xml } box.
     *
     * @param box an {@code Exif} or {@code xml } box, compressed or not
     * @param limits the resource bounds, whose byte limit caps the output
     * @return the payload, or {@code null} when its Brotli stream is corrupt
     */
    private static byte[] metadataContent(Box box, MediaLimits limits) {
        try {
            return box.content(limits);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Parses a {@code jxli} box: a variable-length frame count, the tick duration as two big-endian 32-bit
     * integers, and for every indexed frame its offset from the previous one, its duration in ticks and its
     * frame count, as variable-length integers of seven bits per byte, low bits first.
     *
     * @param data the array holding the box
     * @param offset the start of the payload
     * @param length the payload length
     * @return the index, or {@code null} when the payload is invalid
     */
    static FrameIndex readFrameIndex(byte[] data, int offset, int length) {
        var cursor = new int[]{offset};
        var end = offset + length;
        var count = readVarint(data, cursor, end);
        if (count < 1 || count > length || end - cursor[0] < 8) {
            return null;
        }
        var numerator = ByteArrays.u32be(data, cursor[0]) & 0xFFFF_FFFFL;
        var denominator = ByteArrays.u32be(data, cursor[0] + 4) & 0xFFFF_FFFFL;
        cursor[0] += 8;
        if (numerator == 0 || denominator == 0) {
            return null;
        }
        var entries = new ArrayList<FrameIndex.Entry>((int) count);
        var position = 0L;
        for (var i = 0; i < count; i++) {
            var delta = readVarint(data, cursor, end);
            var ticks = readVarint(data, cursor, end);
            var frames = readVarint(data, cursor, end);
            if (delta < 0 || ticks < 0 || frames < 0 || (i > 0 && delta == 0)) {
                return null;
            }
            position += delta;
            if (position < 0) {
                return null;
            }
            entries.add(new FrameIndex.Entry(position, ticks, frames));
        }
        return new FrameIndex(numerator, denominator, List.copyOf(entries));
    }

    /**
     * Reads a variable-length integer of at most 63 bits.
     *
     * @param data the array
     * @param cursor the read position, advanced past the integer
     * @param end the end of the readable bytes
     * @return the value, or -1 when it is truncated or longer than 63 bits
     */
    private static long readVarint(byte[] data, int[] cursor, int end) {
        var value = 0L;
        for (var shift = 0; shift < 63; shift += 7) {
            if (cursor[0] >= end) {
                return -1;
            }
            var b = data[cursor[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value < 0 ? -1 : value;
            }
        }
        return -1;
    }

    /**
     * {@return the array holding the codestream, shared}
     */
    public byte[] codestream() {
        return codestream;
    }

    /**
     * {@return the index of the first codestream byte in {@link #codestream()}}
     */
    public int codestreamOffset() {
        return codestreamOffset;
    }

    /**
     * {@return the number of codestream bytes}
     */
    public int codestreamLength() {
        return codestreamLength;
    }

    /**
     * {@return the EXIF block, a TIFF structure starting with its byte-order mark, or {@code null}
     * when the file has none}
     */
    public byte[] exif() {
        return exif;
    }

    /**
     * {@return the payload of the {@code Exif} box after its four-byte TIFF header offset field, or
     * {@code null} when the file has none}
     *
     * <p>This is the form JPEG reconstruction stores in an {@code APP1} segment.
     */
    public byte[] exifBox() {
        return exifBox;
    }

    /**
     * {@return the XMP packet, or {@code null} when the file has none}
     */
    public byte[] xmp() {
        return xmp;
    }

    /**
     * {@return the JPEG bitstream reconstruction data ({@code jbrd} payload), or {@code null} when
     * the file has none}
     */
    public byte[] jbrd() {
        return jbrd;
    }

    /**
     * {@return the boxes other than the codestream, the signature, {@code ftyp} and {@code jxll}, in file order}
     */
    public List<Box> boxes() {
        return boxes;
    }

    /**
     * {@return the first box of a type, compressed or not, or {@code null} when there is none}
     *
     * @param type the four-character box type
     */
    public Box box(String type) {
        for (var box : boxes) {
            if (box.type().equals(type)) {
                return box;
            }
        }
        return null;
    }

    /**
     * {@return the frame index of the {@code jxli} box, or {@code null} when the file has none or it is invalid}
     */
    public FrameIndex frameIndex() {
        return frameIndex;
    }
}
