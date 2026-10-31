package com.github.auties00.smalt.video.mpeg4.internal.encoder;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.video.mpeg4.internal.Frame;

import java.util.ArrayDeque;

/**
 * Copies 8-bit 4:2:0 pictures into the edge-extended planes the encoders code from, and keeps the planes for reuse.
 *
 * <p>Instances are not thread-safe.
 */
public final class SourceFrames {
    private final int width;
    private final int height;
    private final int mbWidth;
    private final int mbHeight;

    /**
     * The planes no picture holds.
     */
    private final ArrayDeque<Frame> free = new ArrayDeque<>();

    /**
     * Constructs a pool for a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     */
    public SourceFrames(int width, int height) {
        this.width = width;
        this.height = height;
        this.mbWidth = (width + 15) >> 4;
        this.mbHeight = (height + 15) >> 4;
    }

    /**
     * Copies a picture into planes of the pool and extends its edges over the coded area and the border.
     *
     * @param picture the picture, 8-bit 4:2:0 of the pool's size
     * @return the planes, owned by the caller until {@link #release(Frame)}
     */
    public Frame copy(YuvPicture picture) {
        var frame = acquire();
        var stride = frame.lumaStride;
        for (var row = 0; row < height; row++) {
            System.arraycopy(picture.y(), row * picture.yStride(), frame.y, frame.lumaOrigin + row * stride, width);
        }
        var chromaWidth = (width + 1) >> 1;
        var chromaHeight = (height + 1) >> 1;
        var chromaStride = frame.chromaStride;
        for (var row = 0; row < chromaHeight; row++) {
            System.arraycopy(picture.u(), row * picture.uvStride(), frame.u, frame.chromaOrigin + row * chromaStride,
                    chromaWidth);
            System.arraycopy(picture.v(), row * picture.uvStride(), frame.v, frame.chromaOrigin + row * chromaStride,
                    chromaWidth);
        }
        frame.extendEdges(width, height);
        return frame;
    }

    /**
     * Returns planes to the pool.
     *
     * @param frame planes from {@link #copy(YuvPicture)} or {@link #acquire()}
     */
    public void release(Frame frame) {
        free.push(frame);
    }

    /**
     * Returns planes of the pool's size, whose content is unspecified.
     *
     * @return the planes
     */
    public Frame acquire() {
        var frame = free.poll();
        return frame != null ? frame : new Frame(mbWidth, mbHeight);
    }
}
