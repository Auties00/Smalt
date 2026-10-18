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
import java.util.Arrays;

/**
 * Splits a JPEG XL file into its codestream and its metadata boxes (ISO/IEC 18181-2).
 *
 * <p>A file is either a bare codestream, starting {@code FF 0A}, or an ISO-BMFF container
 * starting with the twelve-byte {@code JXL } signature box. In a container the codestream is one
 * {@code jxlc} box or a sequence of {@code jxlp} boxes, whose four-byte indices must count up from
 * zero with the top bit set on the last one; the {@code Exif}, {@code xml } (XMP) and {@code jbrd}
 * (JPEG bitstream reconstruction) boxes are kept, an {@code Exif} or {@code xml } box compressed
 * into a {@code brob} box is decompressed, and every other box is skipped.
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

    private JxlContainer(byte[] codestream, int codestreamOffset, int codestreamLength, byte[] exif, byte[] exifBox,
                         byte[] xmp, byte[] jbrd) {
        this.codestream = codestream;
        this.codestreamOffset = codestreamOffset;
        this.codestreamLength = codestreamLength;
        this.exif = exif;
        this.exifBox = exifBox;
        this.xmp = xmp;
        this.jbrd = jbrd;
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
            return new JxlContainer(data, 0, data.length, null, null, null, null);
        }
        if (!isJxl(data, data.length)) {
            throw new MediaException.Malformed("not a JPEG XL file");
        }
        var offset = (long) CONTAINER_SIGNATURE.length;
        byte[] exif = null;
        byte[] exifBox = null;
        byte[] xmp = null;
        byte[] jbrd = null;
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
                case "Exif" -> {
                    if (exifBox == null) {
                        exifBox = exifPayload(data, (int) payloadStart, payloadLength);
                    }
                }
                case "xml " -> {
                    if (xmp == null) {
                        xmp = Arrays.copyOfRange(data, (int) payloadStart, (int) payloadEnd);
                    }
                }
                case "jbrd" -> {
                    if (jbrd == null) {
                        jbrd = Arrays.copyOfRange(data, (int) payloadStart, (int) payloadEnd);
                    }
                }
                case "brob" -> {
                    if (payloadLength >= 4) {
                        var inner = new String(data, (int) payloadStart, 4, StandardCharsets.ISO_8859_1);
                        if ((inner.equals("Exif") && exifBox == null) || (inner.equals("xml ") && xmp == null)) {
                            var decompressed = decompressBox(data, (int) payloadStart + 4, payloadLength - 4, limits);
                            if (decompressed != null && inner.equals("Exif")) {
                                exifBox = exifPayload(decompressed, 0, decompressed.length);
                            } else if (decompressed != null) {
                                xmp = decompressed;
                            }
                        }
                    }
                }
                default -> {
                }
            }
            offset += boxSize;
        }
        if (exifBox != null) {
            var tiffOffset = ByteArrays.u32be(exifBox, 0);
            if (tiffOffset < exifBox.length - 4) {
                exif = Arrays.copyOfRange(exifBox, 4 + (int) tiffOffset, exifBox.length);
            }
            exifBox = Arrays.copyOfRange(exifBox, 4, exifBox.length);
        }
        if (jxlcOffset >= 0) {
            return new JxlContainer(data, jxlcOffset, jxlcLength, exif, exifBox, xmp, jbrd);
        }
        if (partial == null) {
            throw new MediaException.Malformed("JPEG XL container without a codestream");
        }
        var joined = partial.toByteArray();
        return new JxlContainer(joined, 0, joined.length, exif, exifBox, xmp, jbrd);
    }

    /**
     * Copies the payload of an {@code Exif} box when it can hold its TIFF offset field.
     *
     * @param data the array holding the box
     * @param start the start of the payload
     * @param length the payload length
     * @return the payload, still with its four-byte offset field, or {@code null} when too short
     */
    private static byte[] exifPayload(byte[] data, int start, int length) {
        return length >= 4 ? Arrays.copyOfRange(data, start, start + length) : null;
    }

    /**
     * Decompresses the Brotli stream of a {@code brob} box.
     *
     * @param data the array holding the box
     * @param start the start of the stream
     * @param length the stream length
     * @param limits the resource bounds, whose byte limit caps the output
     * @return the decompressed payload, or {@code null} when the stream is corrupt, since metadata
     * never prevents decoding the image
     */
    private static byte[] decompressBox(byte[] data, int start, int length, MediaLimits limits) {
        try {
            return BrotliDecoder.decompress(data, start, length, limits.maxBytes());
        } catch (MediaException.Malformed exception) {
            return null;
        }
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
}
