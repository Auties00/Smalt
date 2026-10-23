package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.video.hevc.internal.HevcDecoderEngine;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;
import com.github.auties00.smalt.video.hevc.internal.HevcNal;
import com.github.auties00.smalt.video.hevc.internal.HevcVps;

/**
 * Supplies the decoded picture of another coded item as the external base layer of a layered HEVC
 * item (an {@code lhv1} item with an {@code exbl} reference): the picture of an AVC-coded item,
 * handed to the first access unit of the layered item as an IRAP picture (H.265 F.8.1.8).
 *
 * <p>The visible picture of the item is placed at the origin of the conformance window of the base
 * layer's representation format; samples the representation format has outside the visible
 * picture repeat the nearest edge sample. The chroma format and the bit depths must be the
 * representation format's.
 */
final class CodedBaseLayer implements HevcDecoderEngine.ExternalBaseLayer {
    /**
     * The decoded base item.
     */
    private final CodedFrame base;

    /**
     * The bounds of the picture allocated for the engine.
     */
    private final MediaLimits limits;

    /**
     * Whether the picture was handed to the engine.
     */
    private boolean supplied;

    /**
     * Constructs the base layer of a layered item.
     *
     * @param base the decoded base item, valid while the layered item decodes
     * @param limits the bounds of the picture allocated for the engine
     */
    CodedBaseLayer(CodedFrame base, MediaLimits limits) {
        this.base = base;
        this.limits = limits;
    }

    /**
     * Returns the base layer picture of the first access unit, then none.
     *
     * @param format the representation format of the base layer
     * @param pocLsb the {@code slice_pic_order_cnt_lsb} of the access unit, which an image item's
     * single access unit does not need
     * @return the picture, or {@code null} after the first access unit
     * @throws MediaException.Malformed if the item's chroma format or bit depths differ from the
     * representation format, or its picture is larger than the representation format's
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    @Override
    public Picture next(HevcVps.RepFormat format, int pocLsb) {
        if (supplied) {
            return null;
        }
        supplied = true;
        var chroma = chromaFormatIdc(base.format());
        if (chroma != format.chromaFormatIdc() || format.separateColorPlane()) {
            throw new MediaException.Malformed("heic: the external base layer item is coded as " + base.format()
                    + ", not in the chroma format of its layered item");
        }
        if (base.depth(0) != format.bitDepthLuma() || chroma != 0 && base.depth(1) != format.bitDepthChroma()) {
            throw new MediaException.Malformed("heic: the external base layer item has other bit depths than its"
                    + " layered item gives it");
        }
        var originX = format.cropLeft();
        var originY = format.cropTop();
        if (originX + base.width() > format.width() || originY + base.height() > format.height()) {
            throw new MediaException.Malformed("heic: the external base layer item is " + base.width() + "x"
                    + base.height() + ", larger than its layered item's base layer of " + format.width() + "x"
                    + format.height());
        }
        LimitChecks.checkPicture(limits, format.width(), format.height());
        LimitChecks.checkAllocation(limits, (long) format.width() * format.height() * 2 * 2);
        var eightBit = format.bitDepthLuma() == 8 && format.bitDepthChroma() == 8;
        var frame = new HevcFrame(format.width(), format.height(), chroma, eightBit);
        copy(0, frame.width, frame.height, originX, originY, eightBit ? frame.y8 : null, eightBit ? null : frame.y);
        if (chroma != 0) {
            var shiftX = chroma == 3 ? 0 : 1;
            var shiftY = chroma == 1 ? 1 : 0;
            copy(1, frame.chromaWidth, frame.chromaHeight, originX >> shiftX, originY >> shiftY,
                    eightBit ? frame.cb8 : null, eightBit ? null : frame.cb);
            copy(2, frame.chromaWidth, frame.chromaHeight, originX >> shiftX, originY >> shiftY,
                    eightBit ? frame.cr8 : null, eightBit ? null : frame.cr);
        }
        return new Picture(frame, true, HevcNal.IDR_W_RADL);
    }

    /**
     * Copies one plane of the base item into a plane of the picture, repeating its edge samples
     * over the rest of the plane.
     *
     * @param plane the plane index: 0 luma, 1 Cb, 2 Cr
     * @param width the width of the destination plane
     * @param height the height of the destination plane
     * @param originX the column the item's plane starts at
     * @param originY the row the item's plane starts at
     * @param out8 the 8-bit destination, or {@code null}
     * @param out16 the 16-bit destination, or {@code null}
     */
    private void copy(int plane, int width, int height, int originX, int originY, byte[] out8, short[] out16) {
        var format = base.format();
        var sourceWidth = plane == 0 ? base.width() : (base.width() + (1 << format.shiftX()) - 1) >> format.shiftX();
        var sourceHeight = plane == 0 ? base.height()
                : (base.height() + (1 << format.shiftY()) - 1) >> format.shiftY();
        var stride = base.stride(plane);
        var offset = base.offset(plane);
        var eightBitSource = base.eightBit();
        var in8 = eightBitSource ? base.plane8(plane) : null;
        var in16 = eightBitSource ? null : base.plane16(plane);
        for (var y = 0; y < height; y++) {
            var row = offset + Math.clamp(y - originY, 0, sourceHeight - 1) * stride;
            var out = y * width;
            for (var x = 0; x < width; x++) {
                var at = row + Math.clamp(x - originX, 0, sourceWidth - 1);
                var sample = eightBitSource ? in8[at] & 0xFF : in16[at];
                if (out8 != null) {
                    out8[out + x] = (byte) sample;
                } else {
                    out16[out + x] = (short) sample;
                }
            }
        }
    }

    /**
     * {@return the {@code chroma_format_idc} of a chroma format}
     *
     * @param format the chroma format
     */
    private static int chromaFormatIdc(ChromaFormat format) {
        return switch (format) {
            case MONOCHROME -> 0;
            case YUV422 -> 2;
            case YUV444 -> 3;
            default -> 1;
        };
    }
}
