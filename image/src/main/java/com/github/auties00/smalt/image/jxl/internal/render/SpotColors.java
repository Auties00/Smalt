/* Ports libjxl 0.11.2 (lib/jxl/render_pipeline/stage_spot.cc, and the place of the spot color stage in
   lib/jxl/dec_cache.cc and lib/jxl/blending.cc). Copyright the JPEG XL Project Authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.render;

import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Composites the spot color extra channels of an image into its color channels, as a renderer that shows spot
 * colors displays them (ISO/IEC 18181-1 section D.4.4, {@code spot_color}).
 *
 * <p>Each spot color channel, in file order, blends its color over the three color channels with the weight
 * {@code solidity * s}, {@code s} being the channel's sample: {@code c = w * spot + (1 - w) * c}. A gray image
 * blends the red component of the spot color into its one channel, as libjxl, which carries a gray image as three
 * equal channels and writes gray output from the first, renders it. The blend happens in linear light for an XYB
 * frame that is neither blended onto the canvas nor kept as a reference after its color transform, and in the
 * output color space otherwise.
 */
public final class SpotColors {
    /**
     * Prevents instantiation.
     */
    private SpotColors() {
        throw new AssertionError();
    }

    /**
     * {@return whether an image has a spot color channel}
     *
     * @param image the image header
     */
    public static boolean present(ImageHeader image) {
        for (var info : image.extraChannels()) {
            if (info.type() == ExtraChannelInfo.TYPE_SPOT_COLOR && info.spotColor() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return whether the spot colors of a displayed frame are blended in linear light, before the transfer
     * function of the output color space}
     *
     * @param image the image header
     * @param frame the header of the frame that completes the displayed canvas
     */
    public static boolean linear(ImageHeader image, FrameHeader frame) {
        return image.xybEncoded() && !needsBlending(frame) && !(frame.canReference() && !frame.saveBeforeCt);
    }

    /**
     * {@return whether a frame is blended onto the canvas rather than replacing it whole}
     *
     * @param frame the frame header
     */
    static boolean needsBlending(FrameHeader frame) {
        if (!frame.isNormalFrame()) {
            return false;
        }
        var replaceAll = frame.blending.mode() == FrameHeader.BLEND_REPLACE;
        for (var info : frame.ecBlending) {
            replaceAll &= info.mode() == FrameHeader.BLEND_REPLACE;
        }
        return !replaceAll || frame.haveCrop;
    }

    /**
     * Blends the spot color channels of an image into its color planes, in place, the extra channels following
     * the color planes.
     *
     * @param planes the planes: the color planes followed by the extra channels in file order, every one
     * {@code length} samples
     * @param colorChannels the number of color planes, 1 or 3; nothing is blended for any other count
     * @param length the number of samples per plane
     * @param image the image header
     */
    public static void mix(float[][] planes, int colorChannels, int length, ImageHeader image) {
        mix(planes, colorChannels, colorChannels, length, image);
    }

    /**
     * Blends the spot color channels of an image into its color planes, in place: three RGB planes, or one gray
     * plane that takes the red component of each spot color.
     *
     * @param planes the planes, every one {@code length} samples, holding the color planes first and the extra
     * channels in file order from {@code firstExtra}
     * @param colorChannels the number of color planes to blend into, 1 or 3; nothing is blended for any other
     * count
     * @param firstExtra the index of the first extra channel in {@code planes}, at least {@code colorChannels}
     * @param length the number of samples per plane
     * @param image the image header
     */
    public static void mix(float[][] planes, int colorChannels, int firstExtra, int length, ImageHeader image) {
        if (colorChannels != 1 && colorChannels != 3) {
            return;
        }
        var infos = image.extraChannels();
        var count = Math.min(infos.size(), planes.length - firstExtra);
        var chunks = Math.max(1, length >> 14);
        var bands = ParallelBands.bands(chunks, (long) length * colorChannels * count);
        ParallelBands.run(bands, band -> {
            var from = (int) ((long) length * ParallelBands.start(band, bands, chunks) / chunks);
            var to = (int) ((long) length * ParallelBands.start(band + 1, bands, chunks) / chunks);
            for (var e = 0; e < count; e++) {
                var info = infos.get(e);
                var spot = info.spotColor();
                if (info.type() != ExtraChannelInfo.TYPE_SPOT_COLOR || spot == null) {
                    continue;
                }
                var weights = planes[firstExtra + e];
                var solidity = spot[3];
                for (var c = 0; c < colorChannels; c++) {
                    var plane = planes[c];
                    var color = spot[c];
                    for (var i = from; i < to; i++) {
                        var mix = solidity * weights[i];
                        plane[i] = mix * color + (1.0f - mix) * plane[i];
                    }
                }
            }
        });
    }
}
