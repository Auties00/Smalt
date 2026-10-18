package com.github.auties00.smalt.video.dnxhd.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.intra.internal.IntraBands;
import com.github.auties00.smalt.video.intra.internal.IntraBitReader;
import com.github.auties00.smalt.video.intra.internal.IntraFramePipeline;

import java.util.Arrays;

/**
 * Decodes VC-3 (DNxHD and DNxHR) frames, SMPTE ST 2019-1, into pictures: parses the one or two
 * coding units of a frame, then decodes their macroblock scan lines, which the scan indices of the
 * header make independent, in parallel bands when the frame is large enough, with one
 * {@link Vc3RowDecoder} per band.
 *
 * <p>Y'CbCr frames come out as {@link Yuv16Picture}s of their 10 or 12 bits, or as
 * {@link YuvPicture}s for 8-bit frames and when high bit depth is off; the samples are computed
 * directly at the output depth. RGB frames (the color format flag {@code CLF} set, ST 2019-1
 * clause 7.2.5) come out as full-range {@link Rgba16Picture}s, or {@link RgbaPicture}s when high
 * bit depth is off, whichever color space their macroblocks are coded in: clause 6 lets an RGB
 * encoder code each macroblock as R, G and B or as Y'CbCr, and the decoder returns RGB only. A
 * field-encoded interlaced frame's two fields are woven into one frame, the first coding unit
 * holding the top field. Output planes are recycled from frame to frame while the layout stays the
 * same.
 *
 * <p>A frame carries alpha in one of two ways, and its picture then has an alpha plane (or a
 * meaningful alpha channel), full range at the output depth:
 * <ul>
 * <li>in band, as ST 2019-1 (with Amendment 1:2023) defines it: the coding unit sets {@code ALP} and
 * every macroblock scan line holds its alpha macroblocks after the video ones, DCT-coded or, with
 * {@code LLA}, losslessly run-length coded;</li>
 * <li>out of band, as Avid's QuickTime encoders store it, outside the standard: the frame is the
 * coding units of the video, then the alpha, then the 4-byte big-endian size of the video part.
 * The alpha is either a coding unit of the same compression ID whose luma holds the alpha codes,
 * or a plane of 8-bit samples, one row of the picture's width after the other, which may be
 * followed by unused bytes.</li>
 * </ul>
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation reconstructs 12-bit samples as four times the inverse transform,
 * since ST 2019-1 Table 14 confines 12-bit coefficients, like 10-bit ones, to 13 bits. It follows
 * the standard where other decoders depart from it; the class body lists those divergences.
 */
public final class Vc3FrameDecoder implements IntraFramePipeline.FrameDecoder {
    /*
     * Divergences from ffmpeg's dnxhd decoder, which follows the standard less closely:
     * - inverse quantization follows equation 8.1 and the weight tables of Annex D exactly, where
     *   ffmpeg reconstructs AC levels about a quarter of a quantizer step further from zero and uses
     *   the weights of compression ID 1241 for ID 1271, so its samples differ from these by up to a
     *   few code values on coarsely quantized streams;
     * - a set CLF means RGB (clause 7.2.5), with the channels of a macroblock in the order red,
     *   green, blue (clause 6.3, Table 6) or, when its alternate color space flag is set, Y'CbCr
     *   converted with Recommendation ITU-R BT.709 (clause 8.1.1); a clear CLF on a 4:4:4
     *   compression ID means Y'CbCr 4:4:4. ffmpeg reads the flag the other way round and orders RGB
     *   channels green, blue, red, so it returns the Y'CbCr samples of a set flag unconverted, and
     *   this decoder returns the planes of ffmpeg's RGB streams as Y'CbCr;
     * - RGB samples sit at the BT.709 levels of Table 2 (64 to 940 at 10 bits) unless the color
     *   volume is out of band, and are stretched to full range, clipping the foot and head room;
     *   ffmpeg treats them as full-range codes.
     * The conversion of alternate color space macroblocks uses the BT.709 matrix that clauses
     * 7.3.1.1 and 8.1.1 name, also for BT.2020 color volumes. The layout of Avid's out-of-band
     * alpha was established from files of Avid encoders attached to public ffmpeg bug reports
     * (tickets 3707 and 8427): an 8-bit 4:2:2 DNxHR SQ file and a 10-bit interlaced DNxHD file with
     * raw 8-bit alpha (whose alpha area is sized for 10-bit samples), and an 8-bit DNxHD file whose
     * alpha is a second coding unit.
     */

    /**
     * The resource bounds every frame is checked against.
     */
    private final MediaLimits limits;

    /**
     * The thread count: {@code 1} for the calling thread only, otherwise the shared pool.
     */
    private final int threads;

    /**
     * Whether pictures keep their 10 or 12 bits.
     */
    private final boolean highBitDepth;

    /**
     * The row decoder of every parallel band, created on first use.
     */
    private Vc3RowDecoder[] rowDecoders = new Vc3RowDecoder[1];

    /**
     * The recycled 16-bit output planes, or {@code null}.
     */
    private short[][] planes16;

    /**
     * The recycled 8-bit output planes, or {@code null}.
     */
    private byte[][] planes8;

    /**
     * The recycled 16-bit alpha plane, or {@code null}.
     */
    private short[] alpha16;

    /**
     * The recycled 8-bit alpha plane, or {@code null}.
     */
    private byte[] alpha8;

    /**
     * The decoder of alpha coding units stored out of band, created on first use.
     */
    private Vc3FrameDecoder alphaDecoder;

    /**
     * Constructs a frame decoder.
     *
     * @param limits the resource bounds
     * @param threads {@code 1} for the calling thread only, otherwise the shared pool with at
     * most this many threads ({@code 0} for no cap)
     * @param highBitDepth whether pictures keep their 10 or 12 bits
     */
    public Vc3FrameDecoder(MediaLimits limits, int threads, boolean highBitDepth) {
        this.limits = limits;
        this.threads = threads;
        this.highBitDepth = highBitDepth;
    }

    /**
     * Decodes one frame.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return the picture, whose planes the next call may overwrite
     * @throws MediaException.Malformed if the frame is corrupt
     * @throws MediaException.Unsupported if the frame uses an unknown compression ID
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    @Override
    public Picture decode(byte[] data, int offset, int length) {
        if (data.length >= IntraBitReader.MAX_BYTES) {
            throw new MediaException.LimitExceeded("VC-3 frame of " + data.length + " bytes is too large");
        }
        var first = Vc3Header.parse(data, offset, length);
        var fields = first.fieldCount() == 2 ? 2 : 1;
        var widthInMb = (first.width() + 15) >>> 4;
        var videoLength = first.alpha() ? length : videoLength(data, offset, length, first, fields, widthInMb);
        var headers = new Vc3Header[fields];
        var starts = new int[fields];
        var ends = new int[fields];
        headers[0] = first;
        starts[0] = offset;
        if (fields == 2) {
            var unit = first.compression().codingUnitSize(widthInMb, first.rows(), first.alpha());
            if (unit < Vc3Header.BASE_HEADER_SIZE || unit >= videoLength) {
                unit = videoLength / 2;
            }
            ends[0] = offset + unit;
            starts[1] = offset + unit;
            ends[1] = offset + videoLength;
            headers[1] = Vc3Header.parse(data, starts[1], videoLength - unit);
            var second = headers[1];
            if (second.fieldCount() != 3 || second.width() != first.width() || second.lines() != first.lines()
                    || second.compression() != first.compression() || second.depth() != first.depth()
                    || second.subsampling() != first.subsampling() || second.colorFormat() != first.colorFormat()
                    || second.alpha() != first.alpha() || second.losslessAlpha() != first.losslessAlpha()) {
                throw new MediaException.Malformed("the second field of a VC-3 frame does not match the first");
            }
        } else {
            ends[0] = offset + videoLength;
        }
        var width = first.width();
        var height = first.lines() * fields;
        LimitChecks.checkPicture(limits, width, height);
        var layout = Vc3Layout.of(first.subsampling());
        var rgb = first.rgb();
        var depth = first.depth();
        var outputDepth = rgb ? depth : highBitDepth ? depth : 8;
        var wide = outputDepth > 8;
        var stride = widthInMb * 16;
        var rows = first.rows() * 16 * fields;
        var strides = new int[]{stride, stride >> layout.chromaShiftX, stride >> layout.chromaShiftX};
        var sizes = new int[]{stride * rows, strides[1] * (rows >> layout.chromaShiftY), strides[2] * (rows >> layout.chromaShiftY)};
        var outOfBandAlpha = videoLength < length;
        var alpha = first.alpha() || outOfBandAlpha;
        allocate(sizes, wide, alpha);
        var transformScale = depth == 12 ? 4f : 1f;
        var scale = transformScale * (float) Math.pow(2, outputDepth - depth);
        var levelShift = 1 << (outputDepth - 1);
        var targets = new Vc3Target[fields];
        for (var f = 0; f < fields; f++) {
            targets[f] = new Vc3Target(wide, wide ? planes16 : null, wide ? null : planes8,
                    alpha && wide ? alpha16 : null, alpha && !wide ? alpha8 : null, strides, f, fields, scale,
                    levelShift + 0.5f, (1 << outputDepth) - 0.5f, depth - outputDepth);
        }
        var totalRows = first.rows() * fields;
        var bands = IntraBands.count(threads, totalRows, (long) width * height);
        if (rowDecoders.length < bands) {
            rowDecoders = Arrays.copyOf(rowDecoders, bands);
        }
        var decoders = rowDecoders;
        var bandCount = bands;
        ParallelBands.run(bandCount, band -> {
            var decoder = decoders[band];
            if (decoder == null) {
                decoder = new Vc3RowDecoder();
                decoders[band] = decoder;
            }
            for (var unit = ParallelBands.start(band, bandCount, totalRows); unit < ParallelBands.start(band + 1, bandCount, totalRows); unit++) {
                var field = unit / first.rows();
                var row = unit % first.rows();
                var header = headers[field];
                var payload = starts[field] + header.headerSize();
                var rowStart = payload + header.rowOffsets()[row];
                if (header.rowOffsets()[row] < 0 || rowStart >= ends[field]) {
                    throw new MediaException.Malformed("VC-3 macroblock scan index " + row + " is out of range");
                }
                decoder.decode(data, rowStart, ends[field], header, targets[field], row, widthInMb);
                ParallelBands.checkpoint();
            }
        });
        if (outOfBandAlpha) {
            outOfBandAlpha(data, offset + videoLength, length - videoLength - 4, width, height, stride, wide,
                    outputDepth);
        }
        return picture(first, width, height, stride, strides, wide, outputDepth, layout, alpha);
    }

    /**
     * Returns the size of the coding units of a frame, which is shorter than the frame when Avid's
     * out-of-band alpha follows them.
     *
     * <p>The frame holds out-of-band alpha when its last four bytes, big-endian, give the size of
     * its coding units: the constant size of the compression ID for a constant-rate stream, or a
     * size ending with the end-of-frame signature for a variable-rate one, leaving at least one
     * byte of alpha.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @param first the first coding unit's header
     * @param fields the number of coding units
     * @param widthInMb the width in macroblocks
     * @return the size of the coding units: {@code length} without out-of-band alpha
     */
    private static int videoLength(byte[] data, int offset, int length, Vc3Header first, int fields, int widthInMb) {
        if (length < Vc3Header.BASE_HEADER_SIZE + 9) {
            return length;
        }
        var declared = ByteArrays.i32be(data, offset + length - 4);
        if (declared <= first.headerSize() + 4 || declared > length - 5) {
            return length;
        }
        if (first.variableRate()) {
            return ByteArrays.i32be(data, offset + declared - 4) == Vc3Header.SIGNATURE ? declared : length;
        }
        var expected = (long) first.compression().codingUnitSize(widthInMb, first.rows(), false) * fields;
        return declared == expected ? declared : length;
    }

    /**
     * Decodes Avid's out-of-band alpha into the alpha plane: a coding unit whose luma holds the
     * alpha codes, or 8-bit samples row after row. Alpha that is neither leaves the plane opaque.
     *
     * @param data the array holding the frame
     * @param start the index of the alpha's first byte
     * @param length the number of bytes of alpha
     * @param width the picture width
     * @param height the picture height
     * @param stride the alpha plane's row stride
     * @param wide whether the alpha plane is 16-bit
     * @param outputDepth the output bit depth
     * @throws MediaException.Malformed if the alpha coding unit is corrupt or does not match the
     * picture
     */
    private void outOfBandAlpha(byte[] data, int start, int length, int width, int height, int stride, boolean wide,
                                int outputDepth) {
        var max = (1 << outputDepth) - 1;
        if (length >= Vc3Header.BASE_HEADER_SIZE && data[start] == 0 && data[start + 1] == 0
                && data[start + 4] >= 1 && data[start + 4] <= 4) {
            if (alphaDecoder == null) {
                alphaDecoder = new Vc3FrameDecoder(limits, threads, highBitDepth);
            }
            var picture = alphaDecoder.decode(data, start, length);
            if (picture.width() != width || picture.height() != height) {
                throw new MediaException.Malformed("the VC-3 alpha coding unit does not match the picture");
            }
            switch (picture) {
                case YuvPicture luma -> copyAlpha(luma.y(), null, 0, luma.yStride(), 8, width, height, stride, wide, max);
                case Yuv16Picture luma -> copyAlpha(null, luma.y(), 0, luma.yStride(), luma.bitDepth(), width, height,
                        stride, wide, max);
                default -> throw new MediaException.Malformed("the VC-3 alpha coding unit is not Y'CbCr");
            }
            return;
        }
        if (length >= (long) width * height) {
            copyAlpha(data, null, start, width, 8, width, height, stride, wide, max);
            return;
        }
        if (wide) {
            Arrays.fill(alpha16, (short) max);
        } else {
            Arrays.fill(alpha8, (byte) max);
        }
    }

    /**
     * Copies alpha codes into the alpha plane, rescaling them to the output depth.
     *
     * @param narrow the 8-bit codes, or {@code null}
     * @param shorts the wider codes, used when {@code narrow} is {@code null}
     * @param sourceOffset the index of the first code
     * @param sourceStride the row stride of the codes
     * @param sourceDepth the bit depth of the codes
     * @param width the picture width
     * @param height the picture height
     * @param stride the alpha plane's row stride
     * @param wide whether the alpha plane is 16-bit
     * @param max the largest output code
     */
    private void copyAlpha(byte[] narrow, short[] shorts, int sourceOffset, int sourceStride, int sourceDepth, int width,
                           int height, int stride, boolean wide, int max) {
        var sourceMax = (1 << sourceDepth) - 1;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = sourceOffset + y * sourceStride + x;
                var code = narrow != null ? narrow[at] & 0xFF : shorts[at] & 0xFFFF;
                var value = sourceMax == max ? Math.min(code, max) : (int) ((Math.min(code, sourceMax) * (long) max
                        + sourceMax / 2) / sourceMax);
                if (wide) {
                    alpha16[y * stride + x] = (short) value;
                } else {
                    alpha8[y * stride + x] = (byte) value;
                }
            }
        }
    }

    /**
     * Ensures the recycled planes have the needed sizes.
     *
     * @param sizes the sample count of each plane
     * @param wide whether 16-bit planes are needed
     * @param alpha whether an alpha plane of the first plane's size is needed
     */
    private void allocate(int[] sizes, boolean wide, boolean alpha) {
        LimitChecks.checkAllocation(limits, ((long) sizes[0] * (alpha ? 2 : 1) + sizes[1] + sizes[2]) * (wide ? 2 : 1));
        if (wide) {
            if (planes16 == null || planes16[0].length != sizes[0] || planes16[1].length != sizes[1]) {
                planes16 = new short[][]{new short[sizes[0]], new short[sizes[1]], new short[sizes[2]]};
            }
            if (alpha && (alpha16 == null || alpha16.length != sizes[0])) {
                alpha16 = new short[sizes[0]];
            }
        } else {
            if (planes8 == null || planes8[0].length != sizes[0] || planes8[1].length != sizes[1]) {
                planes8 = new byte[][]{new byte[sizes[0]], new byte[sizes[1]], new byte[sizes[2]]};
            }
            if (alpha && (alpha8 == null || alpha8.length != sizes[0])) {
                alpha8 = new byte[sizes[0]];
            }
        }
    }

    /**
     * Wraps the decoded planes into the frame's picture.
     *
     * @param header the first coding unit's header
     * @param width the frame width
     * @param height the frame height
     * @param stride the luma row stride
     * @param strides the stride of every plane
     * @param wide whether the planes are 16-bit
     * @param depth the output bit depth
     * @param layout the macroblock layout
     * @param alpha whether the alpha plane holds the frame's alpha
     * @return the picture
     */
    private Picture picture(Vc3Header header, int width, int height, int stride, int[] strides, boolean wide,
                            int depth, Vc3Layout layout, boolean alpha) {
        if (header.rgb()) {
            return rgb(width, height, stride, depth, header.colorVolume() == Vc3Header.OUT_OF_BAND_VOLUME, alpha);
        }
        var format = layout == Vc3Layout.YUV444 ? ChromaFormat.YUV444
                : layout == Vc3Layout.YUV420 ? ChromaFormat.YUV420 : ChromaFormat.YUV422;
        var color = switch (header.colorVolume()) {
            case 1, 2 -> new ColorInfo(header.colorVolume() == 1 ? MatrixCoefficients.BT2020_NCL
                    : MatrixCoefficients.BT2020_CL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.UNSPECIFIED);
            case 3 -> new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.LIMITED, ColorPrimaries.UNSPECIFIED,
                    TransferCharacteristics.UNSPECIFIED);
            default -> ColorInfo.BT709_LIMITED;
        };
        // TODO: return straight alpha for pre-multiplied streams (PMA set), whose video channels the
        // pictures, which carry straight alpha, currently hold multiplied by alpha
        if (wide) {
            return Yuv16Picture.builder().size(width, height).chromaFormat(format).bitDepth(depth).color(color)
                    .luma(planes16[0], stride).chroma(planes16[1], planes16[2], strides[1])
                    .alpha(alpha ? alpha16 : null, stride).build();
        }
        return YuvPicture.builder().size(width, height).chromaFormat(format).color(color)
                .luma(planes8[0], stride).chroma(planes8[1], planes8[2], strides[1])
                .alpha(alpha ? alpha8 : null, stride).build();
    }

    /**
     * Packs decoded R, G and B planes and the alpha plane into a full-range RGBA picture, at 16
     * bits when high bit depth is on and 8 bits otherwise.
     *
     * @param width the frame width
     * @param height the frame height
     * @param stride the plane row stride
     * @param depth the sample bit depth of the planes
     * @param outOfBand whether the color volume is described out of band, so that the codes span
     * the full range rather than the BT.709 levels
     * @param alpha whether the alpha plane holds the frame's alpha; the picture is opaque
     * otherwise
     * @return the picture
     */
    private Picture rgb(int width, int height, int stride, int depth, boolean outOfBand, boolean alpha) {
        var r = planes16[0];
        var g = planes16[1];
        var b = planes16[2];
        var a = alpha ? alpha16 : null;
        if (highBitDepth) {
            var pixels = new short[width * height * 4];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var from = y * stride + x;
                    var to = (y * width + x) * 4;
                    pixels[to] = (short) Vc3Rgb.expand(r[from], depth, outOfBand, 65535);
                    pixels[to + 1] = (short) Vc3Rgb.expand(g[from], depth, outOfBand, 65535);
                    pixels[to + 2] = (short) Vc3Rgb.expand(b[from], depth, outOfBand, 65535);
                    pixels[to + 3] = a == null ? (short) 0xFFFF : (short) Vc3Rgb.expand(a[from], depth, true, 65535);
                }
            }
            return Rgba16Picture.of(width, height, width * 4, pixels, alpha);
        }
        var pixels = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var from = y * stride + x;
                var to = (y * width + x) * 4;
                pixels[to] = (byte) Vc3Rgb.expand(r[from], depth, outOfBand, 255);
                pixels[to + 1] = (byte) Vc3Rgb.expand(g[from], depth, outOfBand, 255);
                pixels[to + 2] = (byte) Vc3Rgb.expand(b[from], depth, outOfBand, 255);
                pixels[to + 3] = a == null ? (byte) 0xFF : (byte) Vc3Rgb.expand(a[from], depth, true, 255);
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }
}
