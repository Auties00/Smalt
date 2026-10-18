/* Ports jxl-oxide 0.12.6 (crates/jxl-render: blend_single) and libjxl (lib/jxl/render_pipeline). Copyright
   Wonwoo Choi and the jxl-oxide contributors, and the JPEG XL Project Authors; MIT License and BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.render;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.frame.Patches;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;

/**
 * Blends frames onto the canvas and patches onto frames (ISO/IEC 18181-1 sections F.2 and
 * G.1.4): replace, add, multiply, alpha blending (straight or premultiplied, above or below) and
 * alpha-weighted addition, channel by channel.
 *
 * <p>Every mode also mixes the alpha channel itself, and a missing canvas alpha counts as zero.
 */
public final class Blending {
    /**
     * Copies the new samples.
     */
    private static final int REPLACE = 0;

    /**
     * Adds the new samples.
     */
    private static final int ADD = 1;

    /**
     * Multiplies by the new samples.
     */
    private static final int MUL = 2;

    /**
     * Alpha-blends the new samples.
     */
    private static final int BLEND = 3;

    /**
     * Adds the new samples weighted by alpha.
     */
    private static final int MULADD = 4;

    /**
     * Mixes two alpha channels.
     */
    private static final int MIX_ALPHA = 5;

    /**
     * Leaves the canvas unchanged.
     */
    private static final int SKIP = 6;

    /**
     * Prevents instantiation.
     */
    private Blending() {
        throw new AssertionError();
    }

    /**
     * Blends a rendered frame onto the canvas its blending information names.
     *
     * @param frameImage the rendered frame
     * @param header the frame header
     * @param image the image header
     * @param references the reference slots
     * @return the new canvas, the size of the image
     */
    public static FrameImage blendFrame(FrameImage frameImage, FrameHeader header, ImageHeader image,
                                        FrameImage[] references) {
        var width = image.width();
        var height = image.height();
        var colorChannels = frameImage.colorChannels;
        var numEc = frameImage.extraChannels();
        var out = new float[colorChannels + numEc][];
        var hasExtra = numEc > 0;
        for (var idx = 0; idx < colorChannels + numEc; idx++) {
            var info = idx < colorChannels ? header.blending : header.ecBlending[idx - colorChannels];
            var base = references[info.source()];
            var alphaIdx = info.usesAlpha() && hasExtra ? info.alphaChannel() : -1;
            var target = new float[width * height];
            if (base != null) {
                copyRegion(base, idx, target, width, height);
            }
            var mode = switch (info.mode()) {
                case FrameHeader.BLEND_REPLACE -> REPLACE;
                case FrameHeader.BLEND_ADD -> ADD;
                case FrameHeader.BLEND_MUL -> MUL;
                case FrameHeader.BLEND_BLEND -> alphaIdx < 0 ? REPLACE : alphaIdx + colorChannels == idx ? MIX_ALPHA : BLEND;
                default -> alphaIdx < 0 ? ADD : alphaIdx + colorChannels == idx ? SKIP : MULADD;
            };
            float[] baseAlpha = null;
            var baseAlphaStride = width;
            if (alphaIdx >= 0 && base != null) {
                baseAlpha = new float[width * height];
                copyRegion(base, colorChannels + alphaIdx, baseAlpha, width, height);
            }
            var newAlpha = alphaIdx >= 0 ? frameImage.planes[colorChannels + alphaIdx] : null;
            var premultiplied = alphaIdx >= 0 && image.extraChannels().get(alphaIdx).alphaAssociated();
            var x0 = Math.max(header.x0, 0);
            var y0 = Math.max(header.y0, 0);
            var x1 = (int) Math.min((long) header.x0 + frameImage.width, width);
            var y1 = (int) Math.min((long) header.y0 + frameImage.height, height);
            if (x1 > x0 && y1 > y0) {
                blend(mode, info.clamp(), false, premultiplied, target, baseAlpha, width, x0, y0,
                        frameImage.planes[idx], newAlpha, frameImage.width, x0 - header.x0, y0 - header.y0, x1 - x0,
                        y1 - y0, baseAlphaStride);
            }
            out[idx] = target;
        }
        var canvas = new FrameImage(out, colorChannels, width, height);
        canvas.colorTransformed = frameImage.colorTransformed;
        return canvas;
    }

    /**
     * Copies a channel of a reference into a canvas-sized plane, clipping or zero-filling it.
     *
     * @param source the reference
     * @param index the channel index
     * @param target the canvas plane
     * @param width the canvas width
     * @param height the canvas height
     */
    private static void copyRegion(FrameImage source, int index, float[] target, int width, int height) {
        if (index >= source.planes.length) {
            return;
        }
        var w = Math.min(width, source.width);
        var h = Math.min(height, source.height);
        var plane = source.planes[index];
        for (var y = 0; y < h; y++) {
            System.arraycopy(plane, y * source.width, target, y * width, w);
        }
    }

    /**
     * Applies the patches of a frame.
     *
     * @param frame the frame planes, modified
     * @param patches the patch dictionary of the frame
     * @param references the reference slots
     * @param image the image header
     * @throws MediaException.Malformed if a patch references an empty slot or a slot of another
     * channel layout
     */
    public static void applyPatches(FrameImage frame, Patches patches, FrameImage[] references, ImageHeader image) {
        var colorChannels = frame.colorChannels;
        for (var ref : patches.refs()) {
            var source = references[ref.refIndex()];
            if (source == null) {
                throw new MediaException.Malformed("patch from an empty reference slot " + ref.refIndex());
            }
            if (source.colorChannels != colorChannels || source.planes.length != frame.planes.length) {
                throw new MediaException.Malformed("patch reference with a different channel layout");
            }
            for (var target : ref.targets()) {
                for (var idx = 0; idx < frame.planes.length; idx++) {
                    var info = idx < colorChannels ? target.blending()[0] : target.blending()[1 + idx - colorChannels];
                    if (info.mode() == Patches.NONE) {
                        continue;
                    }
                    var bx0 = Math.max(target.x(), 0);
                    var by0 = Math.max(target.y(), 0);
                    var bx1 = (int) Math.min((long) target.x() + ref.width(), frame.width);
                    var by1 = (int) Math.min((long) target.y() + ref.height(), frame.height);
                    if (bx1 <= bx0 || by1 <= by0) {
                        continue;
                    }
                    var left = bx0 - target.x();
                    var top = by0 - target.y();
                    var rx0 = Math.max((long) ref.x0() + left, 0);
                    var ry0 = Math.max((long) ref.y0() + top, 0);
                    var rx1 = Math.min((long) ref.x0() + left + (bx1 - bx0), source.width);
                    var ry1 = Math.min((long) ref.y0() + top + (by1 - by0), source.height);
                    if (rx1 <= rx0 || ry1 <= ry0) {
                        continue;
                    }
                    var w = (int) (rx1 - rx0);
                    var h = (int) (ry1 - ry0);
                    var usesAlpha = info.mode() >= Patches.BLEND_ABOVE;
                    var alphaIdx = usesAlpha ? info.alphaChannel() : -1;
                    var swapped = info.mode() == Patches.BLEND_BELOW || info.mode() == Patches.MULADD_BELOW;
                    int mode;
                    switch (info.mode()) {
                        case Patches.REPLACE -> mode = REPLACE;
                        case Patches.ADD -> mode = ADD;
                        case Patches.MUL -> mode = MUL;
                        case Patches.BLEND_ABOVE, Patches.BLEND_BELOW ->
                                mode = alphaIdx + colorChannels == idx ? MIX_ALPHA : BLEND;
                        default -> mode = alphaIdx + colorChannels == idx ? (swapped ? REPLACE : SKIP) : MULADD;
                    }
                    float[] baseAlpha = null;
                    float[] newAlpha = null;
                    var premultiplied = false;
                    if (usesAlpha && alphaIdx + colorChannels != idx) {
                        baseAlpha = frame.planes[colorChannels + alphaIdx];
                        newAlpha = source.planes[colorChannels + alphaIdx];
                        premultiplied = image.extraChannels().get(alphaIdx).alphaAssociated();
                    }
                    blend(mode, info.clamp(), swapped, premultiplied, frame.planes[idx], baseAlpha, frame.width, bx0, by0,
                            source.planes[idx], newAlpha, source.width, (int) rx0, (int) ry0, w, h, frame.width);
                }
            }
        }
    }

    /**
     * Blends a rectangle of new samples onto base samples.
     *
     * @param mode the blending mode, one of the constants of this class
     * @param clamp whether alpha (and multiplied samples) are clamped
     * @param swapped whether the new samples go below the base ones
     * @param premultiplied whether the color is premultiplied by alpha
     * @param base the base samples, modified
     * @param baseAlpha the base alpha, or {@code null}
     * @param baseStride the width of the base plane
     * @param baseX the left edge of the rectangle in the base
     * @param baseY the top edge of the rectangle in the base
     * @param fresh the new samples
     * @param freshAlpha the new alpha, or {@code null}
     * @param freshStride the width of the new plane
     * @param freshX the left edge of the rectangle in the new plane
     * @param freshY the top edge of the rectangle in the new plane
     * @param width the rectangle width
     * @param height the rectangle height
     * @param baseAlphaStride the width of the base alpha plane
     */
    private static void blend(int mode, boolean clamp, boolean swapped, boolean premultiplied, float[] base,
                              float[] baseAlpha, int baseStride, int baseX, int baseY, float[] fresh,
                              float[] freshAlpha, int freshStride, int freshX, int freshY, int width, int height,
                              int baseAlphaStride) {
        if ((mode == BLEND && freshAlpha == null)) {
            mode = REPLACE;
        }
        if (mode == MULADD && freshAlpha == null) {
            mode = ADD;
        }
        for (var dy = 0; dy < height; dy++) {
            var b = (baseY + dy) * baseStride + baseX;
            var ba = (baseY + dy) * baseAlphaStride + baseX;
            var f = (freshY + dy) * freshStride + freshX;
            for (var dx = 0; dx < width; dx++) {
                var bi = b + dx;
                var fi = f + dx;
                switch (mode) {
                    case REPLACE -> base[bi] = fresh[fi];
                    case ADD -> base[bi] += fresh[fi];
                    case MUL -> {
                        var value = fresh[fi];
                        if (clamp) {
                            value = Math.clamp(value, 0.0f, 1.0f);
                        }
                        base[bi] *= value;
                    }
                    case BLEND -> {
                        float baseSample;
                        float newSample;
                        float alphaBase;
                        float alphaNew;
                        if (swapped) {
                            baseSample = fresh[fi];
                            newSample = base[bi];
                            alphaBase = freshAlpha[fi];
                            alphaNew = baseAlpha == null ? 0.0f : baseAlpha[ba + dx];
                        } else {
                            baseSample = base[bi];
                            newSample = fresh[fi];
                            alphaBase = baseAlpha == null ? 0.0f : baseAlpha[ba + dx];
                            alphaNew = freshAlpha[fi];
                        }
                        if (clamp) {
                            alphaNew = Math.clamp(alphaNew, 0.0f, 1.0f);
                        }
                        if (premultiplied) {
                            base[bi] = newSample + baseSample * (1.0f - alphaNew);
                        } else {
                            var newRev = 1.0f - alphaNew;
                            var mixed = 1.0f - newRev * (1.0f - alphaBase);
                            var recip = mixed > 0 ? 1.0f / mixed : 0.0f;
                            base[bi] = (alphaNew * newSample + alphaBase * baseSample * newRev) * recip;
                        }
                    }
                    case MULADD -> {
                        float baseSample;
                        float newSample;
                        float alphaNew;
                        if (swapped) {
                            baseSample = fresh[fi];
                            newSample = base[bi];
                            alphaNew = baseAlpha == null ? 0.0f : baseAlpha[ba + dx];
                        } else {
                            baseSample = base[bi];
                            newSample = fresh[fi];
                            alphaNew = freshAlpha[fi];
                        }
                        if (clamp) {
                            alphaNew = Math.clamp(alphaNew, 0.0f, 1.0f);
                        }
                        base[bi] = baseSample + alphaNew * newSample;
                    }
                    case MIX_ALPHA -> {
                        var a = base[bi];
                        var n = fresh[fi];
                        if (swapped) {
                            var t = a;
                            a = n;
                            n = t;
                        }
                        if (clamp) {
                            n = Math.clamp(n, 0.0f, 1.0f);
                        }
                        base[bi] = a + n * (1.0f - a);
                    }
                    default -> {
                    }
                }
            }
        }
    }
}
