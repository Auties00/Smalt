package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.internal.heif.DecodedTile;
import com.github.auties00.smalt.internal.heif.HeifCanvas;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;

/**
 * Presents a decoded HEVC picture, cropped by its conformance window, to a {@link HeifCanvas},
 * and allocates the canvases decoded pictures are written into.
 *
 * <p>Color samples reach the canvas as the HEVC decoder of this module hands out its output:
 * copied at the source depth, or reduced to 8 bits by rounding to nearest. A tile is a view of
 * the engine's picture, valid while the engine keeps it.
 */
final class HevcTile implements DecodedTile {
    /**
     * The format name of the canvases, for messages.
     */
    private static final String FORMAT = "heic";

    /**
     * The decoded picture.
     */
    private final HevcFrame frame;

    /**
     * Wraps a decoded picture.
     *
     * @param frame the decoded picture
     */
    private HevcTile(HevcFrame frame) {
        this.frame = frame;
    }

    /**
     * {@return a view of a decoded picture}
     *
     * @param frame the decoded picture
     */
    static HevcTile of(HevcFrame frame) {
        return new HevcTile(frame);
    }

    /**
     * {@return the chroma layout of a decoded picture}
     *
     * @param frame the decoded picture
     */
    static ChromaFormat formatOf(HevcFrame frame) {
        return switch (frame.sps.chromaFormatIdc) {
            case 0 -> ChromaFormat.MONOCHROME;
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV420;
        };
    }

    /**
     * {@return the width of a decoded picture after its conformance window}
     *
     * @param frame the decoded picture
     */
    static int widthOf(HevcFrame frame) {
        return frame.sps.width - frame.sps.cropLeft - frame.sps.cropRight;
    }

    /**
     * {@return the height of a decoded picture after its conformance window}
     *
     * @param frame the decoded picture
     */
    static int heightOf(HevcFrame frame) {
        return frame.sps.height - frame.sps.cropTop - frame.sps.cropBottom;
    }

    /**
     * Allocates a canvas of the size of one decoded picture.
     *
     * @param limits the bounds of the canvas
     * @param frame the decoded picture whose layout and depth the canvas takes
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param hasAlpha whether to allocate an alpha plane
     * @return the canvas
     * @throws MediaException.Unsupported if a deep picture has different luma and chroma depths
     * @throws MediaException.LimitExceeded if the canvas exceeds the limits
     */
    static HeifCanvas canvas(MediaLimits limits, HevcFrame frame, boolean wide, boolean hasAlpha) {
        return canvas(limits, frame, widthOf(frame), heightOf(frame), wide, hasAlpha);
    }

    /**
     * Allocates a canvas in the layout and depth of a decoded picture.
     *
     * @param limits the bounds of the canvas
     * @param frame the decoded picture whose layout and depth the canvas takes
     * @param width the canvas width
     * @param height the canvas height
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param hasAlpha whether to allocate an alpha plane
     * @return the canvas
     * @throws MediaException.Unsupported if a deep picture has different luma and chroma depths
     * @throws MediaException.LimitExceeded if the canvas exceeds the limits
     */
    static HeifCanvas canvas(MediaLimits limits, HevcFrame frame, int width, int height, boolean wide,
                             boolean hasAlpha) {
        var sps = frame.sps;
        var depth = sps.pictureBitDepth();
        var keep = wide && depth > 8;
        if (keep && sps.mixedBitDepth()) {
            throw new MediaException.Unsupported(FORMAT, "luma of " + sps.bitDepthLuma + " bits with chroma of "
                    + sps.bitDepthChroma + " bits");
        }
        return new HeifCanvas(FORMAT, limits, width, height, formatOf(frame), depth, keep, hasAlpha);
    }

    /**
     * {@return whether a decoded picture has the chroma layout and the bit depth of a canvas}
     *
     * @param canvas the canvas to write into
     * @param frame the decoded picture
     */
    static boolean fits(HeifCanvas canvas, HevcFrame frame) {
        return formatOf(frame) == canvas.format() && frame.sps.pictureBitDepth() == canvas.bitDepth();
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
