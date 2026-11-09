/* Ports jxrlib 1.1 (jxrgluelib/JXRGlueJxr.c: ReadContainer, ParsePFD, ParsePFDEntry). Copyright (c) Microsoft
   Corporation; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxr.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.tiff.internal.TiffExif;
import com.github.auties00.smalt.image.tiff.internal.TiffFile;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;
import java.util.Optional;

/**
 * Reads the container of a JPEG XR file (ITU-T T.832 Annex A): a TIFF-like little-endian directory naming the pixel
 * format, the image codestream, an optional planar alpha codestream and the metadata. A bare codestream, starting
 * with its {@code "WMPHOTO"} signature, is read as an image without a container.
 *
 * @param pixelFormat the last byte of the pixel format GUID of the common family, or -1 for another or no GUID
 * @param premultiplied whether the pixel format holds premultiplied alpha
 * @param imageOffset the index of the image codestream
 * @param imageEnd the index one past the image codestream
 * @param alphaOffset the index of the planar alpha codestream, or -1
 * @param alphaEnd the index one past the planar alpha codestream
 * @param orientation the orientation the container sets, 0 to 7, or -1 to take the codestream's
 * @param icc the embedded ICC profile
 * @param exif the EXIF metadata: a stand-alone EXIF block (a little-endian TIFF structure whose first directory
 * points to copies of the EXIF and GPS directories), as JPEG {@code APP1} carries it
 * @param xmp the XMP packet
 * @param dotsPerInch the horizontal and vertical resolution, or {@code null}
 */
public record JxrContainer(int pixelFormat, boolean premultiplied, int imageOffset, int imageEnd, int alphaOffset,
                           int alphaEnd, int orientation, Optional<byte[]> icc, Optional<byte[]> exif,
                           Optional<byte[]> xmp, float[] dotsPerInch) {
    /**
     * The first 15 bytes of the pixel format GUIDs of the common family, {@code 24C3DD6F-034E-FE4B-B185-3D77768DC9}.
     */
    private static final byte[] COMMON_GUID = {0x24, (byte) 0xC3, (byte) 0xDD, 0x6F, 0x03, 0x4E, (byte) 0xFE, 0x4B,
            (byte) 0xB1, (byte) 0x85, 0x3D, 0x77, 0x76, (byte) 0x8D, (byte) 0xC9};

    /**
     * The GUID of {@code 32bppRGBA}, outside the common family.
     */
    private static final byte[] RGBA32 = guid(0xf5c7ad2d, 0x6a8d, 0x43dd, 0xa7, 0xa8, 0xa2, 0x99, 0x35, 0x26, 0x1a,
            0xe9);

    /**
     * The GUID of {@code 32bppPRGBA}, outside the common family.
     */
    private static final byte[] PRGBA32 = guid(0x3cc4a650, 0xa527, 0x4d37, 0xa9, 0x16, 0x31, 0x42, 0xc7, 0xeb, 0xed,
            0xba);

    /**
     * The GUID of {@code 32bppRGB}, outside the common family.
     */
    private static final byte[] RGB32 = guid(0xd98c6b95, 0x3efe, 0x47d6, 0xbb, 0x25, 0xeb, 0x17, 0x48, 0xab, 0x0c,
            0xf1);

    /**
     * The GUID of {@code 96bppRGBFloat}, outside the common family.
     */
    private static final byte[] RGB96_FLOAT = guid(0xe3fed78f, 0xe8db, 0x4acf, 0x84, 0xc1, 0xe9, 0x7f, 0x61, 0x36,
            0xb3, 0x27);

    /**
     * The pixel formats with alpha, by the last byte of their GUID in the common family.
     */
    private static final int[] ALPHA_FORMATS = {0x0F, 0x10, 0x16, 0x17, 0x19, 0x1A, 0x1D, 0x1E, 0x2C, 0x2D, 0x2E,
            0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x4C, 0x4D};

    /**
     * The pixel formats with premultiplied alpha, by the last byte of their GUID in the common family.
     */
    private static final int[] PREMULTIPLIED_FORMATS = {0x10, 0x17, 0x1A};

    /**
     * The pixel format byte this reader gives {@code 32bppRGBA}.
     */
    public static final int FORMAT_RGBA32 = 0x100;

    /**
     * The pixel format byte this reader gives {@code 32bppPRGBA}.
     */
    public static final int FORMAT_PRGBA32 = 0x101;

    /**
     * The pixel format byte this reader gives {@code 32bppRGB}.
     */
    public static final int FORMAT_RGB32 = 0x102;

    /**
     * The pixel format byte this reader gives {@code 96bppRGBFloat}.
     */
    public static final int FORMAT_RGB96_FLOAT = 0x103;

    /**
     * Builds the little-endian bytes of a GUID.
     */
    private static byte[] guid(int data1, int data2, int data3, int... data4) {
        var bytes = new byte[16];
        bytes[0] = (byte) data1;
        bytes[1] = (byte) (data1 >> 8);
        bytes[2] = (byte) (data1 >> 16);
        bytes[3] = (byte) (data1 >> 24);
        bytes[4] = (byte) data2;
        bytes[5] = (byte) (data2 >> 8);
        bytes[6] = (byte) data3;
        bytes[7] = (byte) (data3 >> 8);
        for (var index = 0; index < 8; index++) {
            bytes[8 + index] = (byte) data4[index];
        }
        return bytes;
    }

    /**
     * {@return whether a pixel format carries alpha}
     */
    public boolean formatHasAlpha() {
        if (pixelFormat == FORMAT_RGBA32 || pixelFormat == FORMAT_PRGBA32) {
            return true;
        }
        for (var format : ALPHA_FORMATS) {
            if (format == pixelFormat) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the container.
     *
     * @param data the file
     * @return the container
     * @throws MediaException.Malformed if the container is invalid
     * @throws MediaException.Unsupported if the container is of a version other than 0 and 1
     */
    public static JxrContainer read(byte[] data) {
        if (data.length >= 8 && data[0] == 'W' && data[1] == 'M' && data[2] == 'P' && data[3] == 'H') {
            return new JxrContainer(-1, false, 0, data.length, -1, -1, -1, Optional.empty(), Optional.empty(),
                    Optional.empty(), null);
        }
        if (data.length < 8 || data[0] != 'I' || data[1] != 'I' || (data[2] & 0xFF) != 0xBC) {
            throw new MediaException.Malformed("not a JPEG XR file");
        }
        if (data[3] != 0 && data[3] != 1) {
            throw new MediaException.Unsupported("jxr", "container version " + data[3]);
        }
        var directory = u32(data, 4);
        if (directory < 8 || directory > data.length - 2L) {
            throw new MediaException.Malformed("the JPEG XR directory lies past the file");
        }
        var count = u16(data, (int) directory);
        if (count == 0 || count == 0xFFFF || directory + 2 + 12L * count > data.length) {
            throw new MediaException.Malformed("invalid JPEG XR directory of " + count + " entries");
        }
        var format = -1;
        var premultiplied = false;
        long imageOffset = -1;
        long imageCount = -1;
        long alphaOffset = 0;
        long alphaCount = 0;
        var orientation = -1;
        byte[] icc = null;
        var exifPointer = false;
        byte[] xmp = null;
        var resolution = new float[2];
        for (var index = 0; index < count; index++) {
            var at = (int) directory + 2 + 12 * index;
            var tag = u16(data, at);
            var values = u32(data, at + 4);
            var value = u32(data, at + 8);
            switch (tag) {
                case 0xBC01 -> {
                    if (value + 16 > data.length) {
                        throw new MediaException.Malformed("the JPEG XR pixel format lies past the file");
                    }
                    var guid = Arrays.copyOfRange(data, (int) value, (int) value + 16);
                    if (Arrays.equals(guid, 0, 15, COMMON_GUID, 0, 15)) {
                        format = guid[15] & 0xFF;
                        for (var candidate : PREMULTIPLIED_FORMATS) {
                            premultiplied |= candidate == format;
                        }
                    } else if (Arrays.equals(guid, RGBA32)) {
                        format = FORMAT_RGBA32;
                    } else if (Arrays.equals(guid, PRGBA32)) {
                        format = FORMAT_PRGBA32;
                        premultiplied = true;
                    } else if (Arrays.equals(guid, RGB32)) {
                        format = FORMAT_RGB32;
                    } else if (Arrays.equals(guid, RGB96_FLOAT)) {
                        format = FORMAT_RGB96_FLOAT;
                    }
                }
                case 0xBC02 -> {
                    if (values != 1 || (value & 0xFFFF) > 7) {
                        throw new MediaException.Malformed("invalid JPEG XR transformation");
                    }
                    orientation = (int) (value & 0xFFFF);
                }
                case 0xBCC0 -> imageOffset = single(values, value);
                case 0xBCC1 -> imageCount = single(values, value);
                case 0xBCC2 -> alphaOffset = single(values, value);
                case 0xBCC3 -> alphaCount = single(values, value);
                case 0x8773 -> icc = block(data, value, values);
                case 0x02BC -> xmp = block(data, value, values);
                case 0x8769, 0x8825 -> exifPointer = true;
                case 0xBC82 -> resolution[0] = Float.intBitsToFloat((int) value);
                case 0xBC83 -> resolution[1] = Float.intBitsToFloat((int) value);
                default -> {
                }
            }
        }
        if (imageOffset < 0 || imageOffset >= data.length) {
            throw new MediaException.Malformed("the JPEG XR file names no image codestream");
        }
        var imageEnd = imageCount <= 0 ? data.length : Math.min(data.length, imageOffset + imageCount);
        var exif = exifPointer ? exifBlock(data) : null;
        var density = resolution[0] > 0 && resolution[1] > 0 && Float.isFinite(resolution[0])
                && Float.isFinite(resolution[1]) ? resolution : null;
        var container = new JxrContainer(format, premultiplied, (int) imageOffset, (int) imageEnd, -1, -1,
                orientation, Optional.ofNullable(icc), Optional.ofNullable(exif), Optional.ofNullable(xmp), density);
        if (container.formatHasAlpha() && alphaOffset != 0 && alphaCount != 0) {
            if (alphaOffset >= data.length) {
                throw new MediaException.Malformed("the JPEG XR alpha codestream lies past the file");
            }
            var alphaEnd = Math.min(data.length, alphaOffset + alphaCount);
            container = new JxrContainer(format, premultiplied, (int) imageOffset, (int) imageEnd,
                    (int) alphaOffset, (int) alphaEnd, orientation, container.icc, container.exif, container.xmp,
                    density);
        }
        return container;
    }

    /**
     * {@return the value of a directory entry of one value}
     */
    private static long single(long count, long value) {
        if (count != 1) {
            throw new MediaException.Malformed("a JPEG XR directory entry of " + count + " values");
        }
        return value;
    }

    /**
     * {@return the bytes of a metadata block, or {@code null} when it lies past the file}
     */
    private static byte[] block(byte[] data, long offset, long count) {
        if (count <= 0 || offset < 0 || offset + count > data.length) {
            return null;
        }
        if (count <= 4) {
            return null;
        }
        return Arrays.copyOfRange(data, (int) offset, (int) (offset + count));
    }

    /**
     * {@return the EXIF and GPS directories the container points to, copied into a stand-alone EXIF block, or
     * {@code null} when they cannot be read or do not fit one}
     *
     * <p>The container is read as the TIFF file it is laid out as, its directory being the first one.
     */
    private static byte[] exifBlock(byte[] data) {
        var tiff = data.clone();
        tiff[2] = 42;
        tiff[3] = 0;
        try {
            var file = TiffFile.open(MediaSource.of(tiff), MediaLimits.DEFAULT);
            return TiffExif.extract(file.pages().getFirst());
        } catch (MediaException exception) {
            return null;
        }
    }

    /**
     * {@return a little-endian 16-bit value}
     */
    private static int u16(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
    }

    /**
     * {@return a little-endian 32-bit value}
     */
    private static long u32(byte[] data, int at) {
        return ((data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16
                | (long) (data[at + 3] & 0xFF) << 24);
    }
}
