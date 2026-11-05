/* Ports jxrlib 1.1 (image/decode/strdec.c: ImageStrDecInit, ImageStrDecDecode, outputMBRow, outputMBRowAlpha,
   outputNChannel, interpolateUV and their sample conversions). Copyright (c) Microsoft Corporation; BSD 2-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxr.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Decodes a JPEG XR codestream into the samples of its output format ({@code OUTPUT_CLR_FMT} and
 * {@code OUTPUT_BITDEPTH} of its image header), as jxrlib writes them into a pixel buffer of the file's pixel
 * format before any pixel format conversion.
 *
 * <p>The samples are those of the coded image, before its orientation is applied: integers of the output bit depth
 * (two's complement for the signed fixed-point depths), the bits of half or single precision floats for the
 * floating-point depths, the four bytes of each pixel for RGBE, and 0 or 1 for black and white.
 */
public final class JxrCodestream {
    /** The color format of RGB output, of three channels. */
    public static final int RGB = 0;

    /** The color format of gray output, of one channel. */
    public static final int GRAY = 1;

    /** The color format of CMYK output, of four channels. */
    public static final int CMYK = 2;

    /** The color format of independent channels. */
    public static final int CHANNELS = 3;

    /** The color format of RGB with a shared exponent, of four bytes. */
    public static final int RGBE = 4;

    /** The color format of raw YUV 4:4:4, of three channels. */
    public static final int YUV444 = 5;

    /** The color format of raw YUV 4:2:2, its chroma at half width. */
    public static final int YUV422 = 6;

    /** The color format of raw YUV 4:2:0, its chroma at half width and height. */
    public static final int YUV420 = 7;

    private JxrCodestream() {
        throw new AssertionError();
    }

    /**
     * Holds the decoded samples of a codestream.
     *
     * @param header the image header
     * @param format the color format of the samples, one of the constants of this class
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels the samples of each channel, row-major: the color channels, then alpha when the codestream
     * holds an alpha plane; the chroma channels of {@link #YUV422} and {@link #YUV420} at their reduced size
     * @param alpha whether the last channel is alpha
     */
    public record Samples(JxrHeader header, int format, int width, int height, int[][] channels, boolean alpha) {
    }

    /**
     * Decodes a codestream.
     *
     * @param data the bytes
     * @param start the index of the codestream's signature
     * @param end the index one past the codestream
     * @param limits the limits bounding the image size and allocations
     * @return the samples
     * @throws MediaException.Malformed if the codestream is invalid
     * @throws MediaException.Unsupported if the codestream uses a combination of formats jxrlib does not decode
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static Samples decode(byte[] data, int start, int end, MediaLimits limits) {
        var header = JxrHeader.parse(data, start, end);
        LimitChecks.checkPicture(limits, header.width, header.height);
        var plane = header.plane;
        var switches = new JxrPlaneDecoder.Switches();
        var position = header.headerEnd;
        JxrHeader.Plane alphaPlane = null;
        if (header.alpha) {
            var bits = new JxrBits(data, position, end);
            alphaPlane = JxrHeader.readPlane(bits, header.outputBitDepth);
            position = (int) bits.bytePosition();
        }
        var fullWidth = (long) header.width + header.extraLeft + header.extraRight;
        var fullHeight = (long) header.height + header.extraTop + header.extraBottom;
        var padded = ((fullWidth + 15) & ~15L) * ((fullHeight + 15) & ~15L);
        LimitChecks.checkAllocation(limits, padded * 4 * 2 * (plane.channels + (header.alpha ? 1 : 0)));
        // the alpha plane's band selection is set last, and applies to both planes, as in jxrlib
        var subband = (alphaPlane != null ? alphaPlane : plane).subband;
        switches.skipFlexbits = subband == JxrHeader.SB_NO_FLEXBITS;
        switches.decodeHp = subband == JxrHeader.SB_ALL || subband == JxrHeader.SB_NO_FLEXBITS;
        switches.decodeLp = subband != JxrHeader.SB_DC_ONLY;
        var primary = new JxrPlaneDecoder(data, end, header, plane, false, switches);
        if (alphaPlane != null) {
            primary.next = new JxrPlaneDecoder(data, end, header, alphaPlane, true, switches);
        }
        primary.attachStreams(position);
        if (primary.next != null) {
            primary.next.shareStreams(primary);
        }
        primary.decode();
        return JxrOutput.convert(header, primary);
    }
}
