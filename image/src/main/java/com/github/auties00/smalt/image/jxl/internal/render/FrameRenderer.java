/* Ports jxl-oxide 0.12.6 (crates/jxl-render: render_frame) and libjxl (lib/jxl/render_pipeline). Copyright
   Wonwoo Choi and the jxl-oxide contributors, and the JPEG XL Project Authors; MIT License and BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.render;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.color.ColorConverter;
import com.github.auties00.smalt.image.jxl.internal.frame.DecodedFrame;
import com.github.auties00.smalt.image.jxl.internal.header.BitDepth;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;
import java.util.List;

/**
 * Renders a decoded frame into planes at the frame's full resolution (ISO/IEC 18181-1 annexes J
 * and K): chroma upsampling, the restoration filters, extra channel upsampling, patches, splines,
 * upsampling, noise, and the color transform to the output space.
 *
 * <p>The color transform is skipped when the frame is saved as a reference before it, so that
 * later frames can copy its patches in the frame's own color space.
 *
 * @implNote This implementation follows the rendering order of libjxl's render pipeline; noise is
 * added after upsampling, at the resolution its random field is defined at.
 */
public final class FrameRenderer {
    /**
     * Prevents instantiation.
     */
    private FrameRenderer() {
        throw new AssertionError();
    }

    /**
     * Holds what rendering a frame needs from the frames before it.
     *
     * @param references the four reference slots, entries {@code null} when empty
     * @param visibleFrames the number of displayed frames so far, this one included when displayed
     * @param invisibleFrames the number of frames since the last displayed one
     * @param spotsLinear whether the color transform of this frame blends the spot colors in linear light, as
     * {@link SpotColors#linear} decides for a displayed frame whose spot colors are rendered
     */
    public record Context(FrameImage[] references, long visibleFrames, long invisibleFrames, boolean spotsLinear) {
    }

    /**
     * Renders a frame.
     *
     * @param decoded the decoded frame
     * @param image the image header
     * @param context the references and frame counts
     * @param limits the resource bounds
     * @return the frame planes, at the frame's full resolution
     * @throws MediaException.Malformed if a patch references a missing frame
     * @throws MediaException.LimitExceeded if the planes exceed the limits
     */
    public static FrameImage render(DecodedFrame decoded, ImageHeader image, Context context, MediaLimits limits) {
        var frame = decoded.header();
        var colorWidth = frame.colorWidth();
        var colorHeight = frame.colorHeight();
        var numEc = image.extraChannels().size();
        LimitChecks.checkAllocation(limits, (long) frame.width * frame.height * 4L * (3 + numEc));
        float[][] color;
        var modular = decoded.modular();
        var modularIndex = 0;
        var xybModular = false;
        if (decoded.varDct() != null) {
            var vd = decoded.varDct();
            color = new float[3][];
            for (var c = 0; c < 3; c++) {
                var plane = vd.planes()[c];
                var pw = vd.planeWidth(c);
                if (vd.hshift(c) != 0 || vd.vshift(c) != 0 || frame.doYCbCr) {
                    color[c] = Upsampler.upsampleChroma(plane, pw, vd.hshift(c), vd.vshift(c), colorWidth, colorHeight);
                } else {
                    color[c] = crop(plane, pw, colorWidth, colorHeight);
                }
            }
        } else {
            var count = frame.doYCbCr ? 3 : frame.encodedColorChannels;
            color = new float[count][];
            if (image.xybEncoded() && count == 3) {
                xybModular = true;
                color = modularXyb(modular, decoded.lfGlobal().lfDequant(), colorWidth, colorHeight);
            } else {
                for (var c = 0; c < count; c++) {
                    var channel = modular.get(c);
                    var plane = toFloat(channel, image.bitDepth());
                    if (frame.doYCbCr) {
                        color[c] = Upsampler.upsampleChroma(plane, channel.width, channel.hshift, channel.vshift,
                                colorWidth, colorHeight);
                    } else {
                        color[c] = plane;
                    }
                }
            }
            modularIndex = count;
        }
        var extra = new float[numEc][];
        var extraShift = new int[numEc];
        var extraWidth = new int[numEc];
        var extraHeight = new int[numEc];
        for (var i = 0; i < numEc; i++) {
            var channel = modular.get(modularIndex + i);
            extra[i] = toFloat(channel, image.extraChannels().get(i).bitDepth());
            extraShift[i] = Math.max(channel.hshift, 0);
            extraWidth[i] = channel.width;
            extraHeight[i] = channel.height;
        }
        var colorCount = color.length;
        if (frame.gaborWeights != null || frame.epf != null) {
            var planes = colorCount == 3 ? color : new float[][]{color[0], color[0].clone(), color[0].clone()};
            if (frame.gaborWeights != null) {
                RestorationFilters.gaborish(planes, colorWidth, colorHeight, frame.gaborWeights);
            }
            if (frame.epf != null) {
                var sigma = epfSigma(decoded, frame, colorWidth, colorHeight);
                var sigmaWidth = (colorWidth + 7) / 8;
                RestorationFilters.epf(planes, colorWidth, colorHeight, sigma, sigmaWidth, frame.epf);
            }
            color = colorCount == 3 ? planes : new float[][]{planes[0]};
        }
        for (var i = 0; i < numEc; i++) {
            if (extraShift[i] > 0) {
                var up = Upsampler.upsample(extra[i], extraWidth[i], extraHeight[i], extraShift[i], image);
                extra[i] = crop(up, extraWidth[i] << extraShift[i], colorWidth, colorHeight);
            }
        }
        var all = new float[colorCount + numEc][];
        System.arraycopy(color, 0, all, 0, colorCount);
        System.arraycopy(extra, 0, all, colorCount, numEc);
        var result = new FrameImage(all, colorCount, colorWidth, colorHeight);
        var lfGlobal = decoded.lfGlobal();
        if (lfGlobal.patches() != null) {
            Blending.applyPatches(result, lfGlobal.patches(), context.references(), image);
        }
        var corrX = lfGlobal.varDct() == null ? 0.0f : lfGlobal.varDct().baseCorrelationX();
        var corrB = lfGlobal.varDct() == null ? 1.0f : lfGlobal.varDct().baseCorrelationB();
        if (lfGlobal.splines() != null && colorCount == 3) {
            lfGlobal.splines().render(all[0], all[1], all[2], colorWidth, colorHeight, corrX, corrB);
        }
        var targetWidth = frame.sampleWidth(1);
        var targetHeight = frame.sampleHeight(1);
        if (frame.upsampling > 1) {
            var shift = Integer.numberOfTrailingZeros(frame.upsampling);
            var upsampled = new float[all.length][];
            for (var i = 0; i < all.length; i++) {
                var up = Upsampler.upsample(all[i], colorWidth, colorHeight, shift, image);
                upsampled[i] = crop(up, colorWidth << shift, targetWidth, targetHeight);
            }
            result = new FrameImage(upsampled, colorCount, targetWidth, targetHeight);
            all = upsampled;
        } else if (colorWidth != targetWidth || colorHeight != targetHeight) {
            var cropped = new float[all.length][];
            for (var i = 0; i < all.length; i++) {
                cropped[i] = crop(all[i], colorWidth, targetWidth, targetHeight);
            }
            result = new FrameImage(cropped, colorCount, targetWidth, targetHeight);
            all = cropped;
        }
        if (lfGlobal.noise() != null && colorCount == 3) {
            Noise.apply(all[0], all[1], all[2], result.width, result.height, frame.groupDim(), context.visibleFrames(),
                    context.invisibleFrames(), lfGlobal.noise(), corrX, corrB);
        }
        if (!frame.saveBeforeCt || frame.isLast) {
            result = transformColor(result, image, frame, context.spotsLinear());
        }
        return result;
    }

    /**
     * Converts the color channels of a rendered frame to the output color space.
     *
     * @param result the frame planes, converted in place when the channel count is kept
     * @param image the image header
     * @param frame the frame header
     * @return the converted image: {@code result}, or a new image with one color channel when the
     * output is gray
     */
    public static FrameImage transformColor(FrameImage result, ImageHeader image, FrameHeader frame) {
        return transformColor(result, image, frame, false);
    }

    /**
     * Converts the color channels of a rendered frame to the output color space, optionally blending the spot
     * colors in linear light on the way.
     *
     * @param result the frame planes, converted in place when the channel count is kept
     * @param image the image header
     * @param frame the frame header
     * @param spotsLinear whether to blend the spot color channels into an XYB frame's linear RGB planes (its linear
     * luminance for a gray image) before its transfer function, as {@link SpotColors#mix} blends them
     * @return the converted image: {@code result}, or a new image with one color channel when the
     * output is gray
     */
    public static FrameImage transformColor(FrameImage result, ImageHeader image, FrameHeader frame,
                                            boolean spotsLinear) {
        if (result.colorTransformed) {
            return result;
        }
        var length = result.width * result.height;
        var out = result;
        if (frame.doYCbCr) {
            ColorConverter.ycbcrToRgb(result.planes[0], result.planes[1], result.planes[2], length);
            if (image.isGray()) {
                out = reduceToGray(result, 1);
            }
        } else if (image.xybEncoded() && result.colorChannels == 3) {
            var target = ColorConverter.xybTarget(image);
            var gray = target.colorSpace() == ColorEncoding.GRAY;
            Runnable spots = spotsLinear ? () -> SpotColors.mix(result.planes, gray ? 1 : 3, 3, length, image) : null;
            var channels = ColorConverter.xybToTarget(result.planes, length, image, target, spots);
            if (channels == 1) {
                out = reduceToGray(result, 0);
            }
        }
        out.colorTransformed = true;
        return out;
    }

    /**
     * Keeps one of the three color planes of an image.
     *
     * @param image the image with three color planes
     * @param keep the color plane to keep
     * @return an image with one color channel and the same extra channels
     */
    private static FrameImage reduceToGray(FrameImage image, int keep) {
        var reduced = new float[image.planes.length - 2][];
        reduced[0] = image.planes[keep];
        System.arraycopy(image.planes, 3, reduced, 1, image.planes.length - 3);
        return new FrameImage(reduced, 1, image.width, image.height);
    }

    /**
     * Converts the XYB channels of a modular frame to floating-point X, Y and B planes.
     *
     * @param modular the modular channels: Y, X and B minus Y
     * @param lfDequant the LF dequantization weights
     * @param width the plane width
     * @param height the plane height
     * @return the X, Y and B planes
     */
    private static float[][] modularXyb(List<ModularChannel> modular, float[] lfDequant, int width, int height) {
        var yc = modular.get(0).data;
        var xc = modular.get(1).data;
        var bc = modular.get(2).data;
        var length = width * height;
        var x = new float[length];
        var y = new float[length];
        var b = new float[length];
        var mx = lfDequant[0] / 128.0f;
        var my = lfDequant[1] / 128.0f;
        var mb = lfDequant[2] / 128.0f;
        for (var i = 0; i < length; i++) {
            var luma = yc[i];
            var blue = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) bc[i] + luma));
            x[i] = xc[i] * mx;
            y[i] = luma * my;
            b[i] = blue * mb;
        }
        return new float[][]{x, y, b};
    }

    /**
     * Converts an integer channel to nominal floating-point values.
     *
     * @param channel the integer samples
     * @param depth the sample format
     * @return the plane
     */
    private static float[] toFloat(ModularChannel channel, BitDepth depth) {
        var data = channel.data;
        var out = new float[channel.width * channel.height];
        if (!depth.floatSample()) {
            var scale = depth.scale();
            for (var i = 0; i < out.length; i++) {
                out[i] = data[i] * scale;
            }
        } else {
            for (var i = 0; i < out.length; i++) {
                out[i] = depth.toFloat(data[i]);
            }
        }
        return out;
    }

    /**
     * Copies the top-left part of a plane.
     *
     * @param plane the source samples
     * @param stride its width
     * @param width the width to keep
     * @param height the height to keep
     * @return the cropped plane
     */
    static float[] crop(float[] plane, int stride, int width, int height) {
        if (stride == width && plane.length == width * height) {
            return plane;
        }
        var out = new float[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, y * stride, out, y * width, width);
        }
        return out;
    }

    /**
     * Builds the edge-preserving filter sigma of every 8x8 block of the frame.
     *
     * @param decoded the decoded frame
     * @param frame the frame header
     * @param width the color width
     * @param height the color height
     * @return the block sigmas, {@code ceil(width / 8)} per row
     */
    private static float[] epfSigma(DecodedFrame decoded, FrameHeader frame, int width, int height) {
        var bw = (width + 7) / 8;
        var bh = (height + 7) / 8;
        var sigma = new float[bw * bh];
        Arrays.fill(sigma, frame.epf.sigmaForModular());
        var vd = decoded.varDct();
        if (vd == null) {
            return sigma;
        }
        var perRow = frame.lfGroupsPerRow();
        var lfBlocks = frame.groupDim();
        for (var g = 0; g < frame.numLfGroups(); g++) {
            var meta = vd.hfMetadata(g);
            if (meta == null || meta.epfSigma() == null) {
                continue;
            }
            var x0 = (g % perRow) * lfBlocks;
            var y0 = (g / perRow) * lfBlocks;
            var gw = Math.min(meta.blockWidth(), bw - x0);
            var gh = Math.min(meta.blockHeight(), bh - y0);
            for (var y = 0; y < gh; y++) {
                System.arraycopy(meta.epfSigma(), y * meta.blockWidth(), sigma, (y0 + y) * bw + x0, gw);
            }
        }
        return sigma;
    }
}
