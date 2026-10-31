package com.github.auties00.smalt.image.jxs.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;

import java.util.Arrays;

/**
 * Reads the box structure of a JXS file (ISO/IEC 21122-3) or recognizes a raw JPEG XS codestream.
 *
 * <p>A JXS file starts with the 12-byte signature box ({@code JXS }), and holds a file type box, a JP2 header box
 * with the image header and color specification boxes, an optional video support box and the contiguous codestream
 * box ({@code jp2c}). Instances are immutable.
 *
 * @param codestreamStart the position of the codestream
 * @param codestreamEnd the end of the codestream (exclusive)
 * @param color the color description of a parameterized or enumerated color specification, or {@code null}
 * @param icc the ICC profile of a color specification, or {@code null}
 * @param boxed whether the input is a JXS file rather than a raw codestream
 */
public record JxsFile(int codestreamStart, int codestreamEnd, ColorInfo color, byte[] icc, boolean boxed) {
    /**
     * The JXS signature box.
     */
    private static final byte[] SIGNATURE = {0, 0, 0, 12, 'J', 'X', 'S', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};

    /**
     * Returns whether {@code data} starts with the JXS signature box.
     *
     * @param data the bytes
     * @param length the number of valid bytes
     * @return whether the bytes are a JXS file
     */
    public static boolean isFile(byte[] data, int length) {
        return length >= SIGNATURE.length && Arrays.equals(data, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length);
    }

    /**
     * Locates the codestream of a JXS file or a raw codestream.
     *
     * @param data the bytes
     * @param length the number of valid bytes
     * @return the structure
     * @throws MediaException.Malformed if the input is neither a JXS file nor a JPEG XS codestream, or its boxes are
     * invalid
     */
    public static JxsFile parse(byte[] data, int length) {
        if (JxsHeader.isCodestream(data, 0, length)) {
            return new JxsFile(0, length, null, null, false);
        }
        if (!isFile(data, length)) {
            throw new MediaException.Malformed("the input is neither a JXS file nor a JPEG XS codestream");
        }
        var position = SIGNATURE.length;
        ColorInfo color = null;
        byte[] icc = null;
        while (position + 8 <= length) {
            long size = u32(data, position);
            var type = u32(data, position + 4);
            var header = 8;
            if (size == 1) {
                if (position + 16 > length) {
                    throw new MediaException.Malformed("a JXS box header is truncated");
                }
                size = (u32(data, position + 8) << 32) | u32(data, position + 12);
                header = 16;
            } else if (size == 0) {
                size = length - position;
            }
            if (size < header || size > length - position) {
                if (type == JP2C && size > length - position) {
                    size = length - position;
                } else {
                    throw new MediaException.Malformed("a JXS box has an invalid length");
                }
            }
            var content = position + header;
            var end = (int) (position + size);
            if (type == JP2H) {
                var inner = content;
                while (inner + 8 <= end) {
                    var innerSize = u32(data, inner);
                    var innerType = u32(data, inner + 4);
                    if (innerSize < 8 || innerSize > end - inner) {
                        throw new MediaException.Malformed("a box of the JXS header box has an invalid length");
                    }
                    if (innerType == COLR && color == null && icc == null) {
                        var colr = colorSpecification(data, inner + 8, (int) (inner + innerSize));
                        if (colr instanceof ColorInfo info) {
                            color = info;
                        } else if (colr instanceof byte[] profile) {
                            icc = profile;
                        }
                    }
                    inner += (int) innerSize;
                }
            } else if (type == JP2C) {
                if (!JxsHeader.isCodestream(data, content, end - content)) {
                    throw new MediaException.Malformed("the JXS codestream box does not hold a JPEG XS codestream");
                }
                return new JxsFile(content, end, color, icc, true);
            }
            position = end;
        }
        throw new MediaException.Malformed("the JXS file has no codestream box");
    }

    private static final long JP2H = 0x6A703268L;
    private static final long JP2C = 0x6A703263L;
    private static final long COLR = 0x636F6C72L;

    /**
     * Reads a color specification: a {@link ColorInfo} for the enumerated sRGB, gray and sYCC spaces and for a
     * parameterized one, the profile bytes for an ICC one, or {@code null} for anything else.
     */
    private static Object colorSpecification(byte[] data, int start, int end) {
        if (end - start < 3) {
            return null;
        }
        var method = data[start] & 0xFF;
        switch (method) {
            case 1 -> {
                if (end - start < 7) {
                    return null;
                }
                var space = u32(data, start + 3);
                if (space == 16) {
                    return new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT709,
                            TransferCharacteristics.SRGB);
                }
                if (space == 17) {
                    return new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.FULL, ColorPrimaries.BT709,
                            TransferCharacteristics.SRGB);
                }
                if (space == 18) {
                    return ColorInfo.JPEG;
                }
                return null;
            }
            case 2, 3 -> {
                return Arrays.copyOfRange(data, start + 3, end);
            }
            case 5 -> {
                if (end - start < 10) {
                    return null;
                }
                var primaries = (data[start + 3] & 0xFF) << 8 | data[start + 4] & 0xFF;
                var transfer = (data[start + 5] & 0xFF) << 8 | data[start + 6] & 0xFF;
                var matrix = (data[start + 7] & 0xFF) << 8 | data[start + 8] & 0xFF;
                var full = (data[start + 9] & 0x80) != 0;
                return new ColorInfo(MatrixCoefficients.of(matrix), full ? ColorRange.FULL : ColorRange.LIMITED,
                        ColorPrimaries.of(primaries), TransferCharacteristics.of(transfer));
            }
            default -> {
                return null;
            }
        }
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }
}
