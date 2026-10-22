package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;

/**
 * Presents a decoded HEVC picture, cropped by its conformance window, as a {@link CodedFrame}.
 *
 * <p>Color samples reach the canvas as the HEVC decoder of this module hands out its output:
 * copied at the source depth, the planes of a shallower component shifted left to the depth of
 * the deeper one, or reduced to 8 bits by rounding to nearest. A tile is a view of
 * the engine's picture, valid while the engine keeps it.
 */
final class HevcTile implements CodedFrame {
    /**
     * The decoded picture.
     */
    private final HevcFrame frame;

    /**
     * Wraps a decoded picture.
     *
     * @param frame the decoded picture
     */
    HevcTile(HevcFrame frame) {
        this.frame = frame;
    }

    /**
     * {@return the decoded picture}
     */
    HevcFrame frame() {
        return frame;
    }

    @Override
    public int width() {
        return frame.sps.width - frame.sps.cropLeft - frame.sps.cropRight;
    }

    @Override
    public int height() {
        return frame.sps.height - frame.sps.cropTop - frame.sps.cropBottom;
    }

    @Override
    public ChromaFormat format() {
        return switch (frame.sps.chromaFormatIdc) {
            case 0 -> ChromaFormat.MONOCHROME;
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV420;
        };
    }

    @Override
    public int bitDepth() {
        return frame.sps.pictureBitDepth();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each H.273 code point the VUI signals is kept, and the full range flag when the VUI
     * carries a video signal type; the chroma siting and the mastering display and content light
     * level SEI messages come from the stream too.
     */
    @Override
    public ColorInfo color() {
        var sps = frame.sps;
        return Colors.ofStream(sps.colorPrimaries, sps.transferCharacteristics, sps.matrixCoefficients,
                sps.videoSignalTypePresent, sps.fullRange, frame.color);
    }

    @Override
    public boolean limitedAlpha(HeifProperty.ColorNclx nclx) {
        if (nclx != null) {
            return !nclx.fullRange();
        }
        return frame.sps.videoSignalTypePresent && !frame.sps.fullRange;
    }

    @Override
    public Picture lumaPicture() {
        var sps = frame.sps;
        var offset = sps.cropTop * frame.width + sps.cropLeft;
        if (frame.eightBit()) {
            return HeifPictures.lumaPicture(frame.y8, offset, frame.width, width(), height());
        }
        return HeifPictures.lumaPicture(frame.y, offset, frame.width, width(), height(), sps.bitDepthLuma);
    }

    @Override
    public long pts() {
        return frame.pts;
    }

    @Override
    public long duration() {
        return frame.duration;
    }

    @Override
    public boolean eightBit() {
        return frame.eightBit();
    }

    @Override
    public byte[] plane8(int plane) {
        return switch (plane) {
            case 0 -> frame.y8;
            case 1 -> frame.cb8;
            default -> frame.cr8;
        };
    }

    @Override
    public short[] plane16(int plane) {
        return switch (plane) {
            case 0 -> frame.y;
            case 1 -> frame.cb;
            default -> frame.cr;
        };
    }

    @Override
    public int offset(int plane) {
        var sps = frame.sps;
        if (plane == 0) {
            return sps.cropTop * frame.width + sps.cropLeft;
        }
        return (sps.cropTop >> sps.chromaShiftY) * frame.chromaWidth + (sps.cropLeft >> sps.chromaShiftX);
    }

    @Override
    public int stride(int plane) {
        return plane == 0 ? frame.width : frame.chromaWidth;
    }

    @Override
    public int depth(int plane) {
        return plane == 0 ? frame.sps.bitDepthLuma : frame.sps.bitDepthChroma;
    }
}
