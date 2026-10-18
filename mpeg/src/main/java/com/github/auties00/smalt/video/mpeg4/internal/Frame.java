package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds one decoded picture: its three 8-bit planes with a replicated border, and the per
 * macroblock side information that later pictures read (the macroblock types and motion vectors
 * B-VOP direct mode and skipped macroblocks depend on).
 *
 * <p>The planes cover the macroblock-aligned coded area plus a border of {@link #LUMA_BORDER}
 * luminance and {@link #CHROMA_BORDER} chrominance samples on every side. After a reference
 * picture is reconstructed, {@link #extendEdges(int, int)} fills the border and the coded area
 * past the displayed size with the nearest displayed sample, which is the sample a motion vector
 * pointing outside the picture reads (ISO/IEC 14496-2 7.6.4, ITU-T H.263 D.1).
 *
 * <p>Instances are recycled by one decoder and are not thread-safe; reconstruction writes
 * disjoint rows of macroblocks from several threads. A decoder that decodes pictures
 * concurrently publishes the progress of each picture ({@link #publishParsed()},
 * {@link #publishDone()}) for the pictures that read it to wait for ({@link #awaitParsed()},
 * {@link #awaitDone()}), and counts the pictures and pending outputs that still use a frame
 * ({@link #users()}).
 */
public final class Frame {
    /**
     * The width of the luminance border.
     */
    public static final int LUMA_BORDER = 32;

    /**
     * The width of the chrominance border.
     */
    public static final int CHROMA_BORDER = 16;

    /**
     * The macroblock type of an intra macroblock.
     */
    public static final byte MB_INTRA = 0;

    /**
     * The macroblock type of an inter macroblock with one motion vector.
     */
    public static final byte MB_INTER = 1;

    /**
     * The macroblock type of an inter macroblock with four motion vectors.
     */
    public static final byte MB_INTER4V = 2;

    /**
     * The macroblock type of a skipped (not coded) macroblock of a P-VOP.
     */
    public static final byte MB_SKIPPED = 3;

    /**
     * The macroblock type of a macroblock predicted by global motion compensation.
     */
    public static final byte MB_GMC = 4;

    /**
     * The macroblock type of an inter macroblock with field motion vectors.
     */
    public static final byte MB_FIELD = 5;

    /**
     * The number of macroblock columns.
     */
    public final int mbWidth;

    /**
     * The number of macroblock rows.
     */
    public final int mbHeight;

    /**
     * The luminance row stride.
     */
    public final int lumaStride;

    /**
     * The chrominance row stride.
     */
    public final int chromaStride;

    /**
     * The index of the top-left coded luminance sample in {@link #y}.
     */
    public final int lumaOrigin;

    /**
     * The index of the top-left coded chrominance sample in {@link #u} and {@link #v}.
     */
    public final int chromaOrigin;

    /**
     * The luminance plane.
     */
    public final byte[] y;

    /**
     * The blue-difference plane.
     */
    public final byte[] u;

    /**
     * The red-difference plane.
     */
    public final byte[] v;

    /**
     * The type of every macroblock, one of the {@code MB_*} constants.
     */
    public final byte[] mbType;

    /**
     * The forward luminance motion vectors of every 8x8 block after vector padding, eight values
     * per macroblock ({@code x, y} for blocks 0 to 3), in the units of the layer (half samples, or
     * quarter samples when the layer uses quarter sample motion).
     */
    public final int[] mv;

    /**
     * The field motion vectors of field-predicted macroblocks, four values per macroblock: the top
     * field vector and the bottom field vector, each {@code x, y}.
     */
    public final int[] fieldMv;

    /**
     * The field references of field-predicted macroblocks: bit 0 is the reference field of the top
     * field, bit 1 the reference field of the bottom field (0 for top, 1 for bottom).
     */
    public final byte[] fieldRef;

    /**
     * The coding type of the picture, a {@code Vop} coding type.
     */
    public int codingType;

    /**
     * The display time of the picture in ticks of the layer's time resolution.
     */
    public long time;

    /**
     * Whether the picture carried coded data.
     */
    public boolean coded;

    /**
     * Whether the picture is a random access point (an intra picture).
     */
    public boolean keyframe;

    /**
     * The presentation timestamp associated with the picture, or {@code Long.MIN_VALUE}.
     */
    public long pts = Long.MIN_VALUE;

    /**
     * The duration associated with the picture, or {@code 0}.
     */
    public long duration;

    /**
     * Whether decoding the picture met corrupt data that was concealed.
     */
    public boolean damaged;

    /**
     * The lock of the progress published by a concurrent decoder.
     */
    private final ReentrantLock progressLock = new ReentrantLock();

    /**
     * Signaled whenever the progress changes.
     */
    private final Condition progressChanged = progressLock.newCondition();

    /**
     * Whether the macroblock side information ({@link #mbType}, {@link #mv}, {@link #fieldMv},
     * {@link #fieldRef}) is final.
     */
    private volatile boolean parsed = true;

    /**
     * Whether every sample of the picture, border included, is final.
     */
    private volatile boolean done = true;

    /**
     * The number of pictures in flight and pending outputs that use this frame.
     */
    private final AtomicInteger users = new AtomicInteger();

    /**
     * The job that decodes or decoded the frame, or {@code null} for a frame decoded by its
     * decoder.
     */
    VopJob job;

    /**
     * The cropped copy of the picture a job made for its decoder to hand out, or {@code null}.
     */
    YuvPicture picture;

    /**
     * Allocates a picture.
     *
     * @param mbWidth the number of macroblock columns
     * @param mbHeight the number of macroblock rows
     */
    public Frame(int mbWidth, int mbHeight) {
        this.mbWidth = mbWidth;
        this.mbHeight = mbHeight;
        this.lumaStride = mbWidth * 16 + 2 * LUMA_BORDER;
        this.chromaStride = mbWidth * 8 + 2 * CHROMA_BORDER;
        this.lumaOrigin = LUMA_BORDER * lumaStride + LUMA_BORDER;
        this.chromaOrigin = CHROMA_BORDER * chromaStride + CHROMA_BORDER;
        this.y = new byte[lumaStride * (mbHeight * 16 + 2 * LUMA_BORDER)];
        this.u = new byte[chromaStride * (mbHeight * 8 + 2 * CHROMA_BORDER)];
        this.v = new byte[u.length];
        var count = mbWidth * mbHeight;
        this.mbType = new byte[count];
        this.mv = new int[count * 8];
        this.fieldMv = new int[count * 4];
        this.fieldRef = new byte[count];
    }

    /**
     * Marks the frame as the target of a picture about to be decoded concurrently: neither its
     * side information nor its samples are final until the picture publishes them.
     */
    public void beginDecode() {
        parsed = false;
        done = false;
    }

    /**
     * Publishes that the side information of the frame is final.
     */
    public void publishParsed() {
        progressLock.lock();
        try {
            parsed = true;
            progressChanged.signalAll();
        } finally {
            progressLock.unlock();
        }
    }

    /**
     * Publishes that the frame is final, border included.
     */
    public void publishDone() {
        progressLock.lock();
        try {
            parsed = true;
            done = true;
            progressChanged.signalAll();
        } finally {
            progressLock.unlock();
        }
    }

    /**
     * Returns whether the frame is final.
     *
     * @return {@code true} once the picture decoded into it has published completion
     */
    public boolean isDone() {
        return done;
    }

    /**
     * Waits until the side information of the frame is final.
     */
    public void awaitParsed() {
        if (parsed) {
            return;
        }
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (!parsed) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Waits until the frame is final.
     */
    public void awaitDone() {
        if (done) {
            return;
        }
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (!done) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Returns the counter of the pictures in flight and pending outputs that use this frame.
     *
     * @return the counter, zero when nothing uses the frame
     */
    public AtomicInteger users() {
        return users;
    }

    /**
     * Returns the number of bytes a picture of a size occupies, for the allocation check.
     *
     * @param mbWidth the number of macroblock columns
     * @param mbHeight the number of macroblock rows
     * @return the size in bytes
     */
    public static long bytes(int mbWidth, int mbHeight) {
        long luma = (long) (mbWidth * 16 + 2 * LUMA_BORDER) * (mbHeight * 16 + 2 * LUMA_BORDER);
        long chroma = (long) (mbWidth * 8 + 2 * CHROMA_BORDER) * (mbHeight * 8 + 2 * CHROMA_BORDER);
        return luma + 2 * chroma + (long) mbWidth * mbHeight * 50;
    }

    /**
     * Fills the planes with mid-gray and resets the side information, as for a missing reference.
     */
    public void clear() {
        Arrays.fill(y, (byte) 128);
        Arrays.fill(u, (byte) 128);
        Arrays.fill(v, (byte) 128);
        Arrays.fill(mbType, MB_INTRA);
        Arrays.fill(mv, 0);
        Arrays.fill(fieldMv, 0);
        Arrays.fill(fieldRef, (byte) 0);
    }

    /**
     * Copies the planes and side information of another picture of the same size.
     *
     * @param other the source picture
     */
    public void copyFrom(Frame other) {
        System.arraycopy(other.y, 0, y, 0, y.length);
        System.arraycopy(other.u, 0, u, 0, u.length);
        System.arraycopy(other.v, 0, v, 0, v.length);
        System.arraycopy(other.mbType, 0, mbType, 0, mbType.length);
        System.arraycopy(other.mv, 0, mv, 0, mv.length);
        System.arraycopy(other.fieldMv, 0, fieldMv, 0, fieldMv.length);
        System.arraycopy(other.fieldRef, 0, fieldRef, 0, fieldRef.length);
    }

    /**
     * Replicates the samples at the edges of the displayed area into the rest of the coded area
     * and into the border, for the luminance rows of a band of macroblock rows.
     *
     * @param width the displayed luminance width
     * @param height the displayed luminance height
     * @param firstRow the first macroblock row of the band
     * @param endRow the macroblock row after the band
     */
    public void extendEdges(int width, int height, int firstRow, int endRow) {
        extendPlane(y, lumaStride, lumaOrigin, width, height, mbWidth * 16, mbHeight * 16, LUMA_BORDER,
                firstRow * 16, endRow * 16);
        var chromaWidth = (width + 1) >> 1;
        var chromaHeight = (height + 1) >> 1;
        extendPlane(u, chromaStride, chromaOrigin, chromaWidth, chromaHeight, mbWidth * 8, mbHeight * 8,
                CHROMA_BORDER, firstRow * 8, endRow * 8);
        extendPlane(v, chromaStride, chromaOrigin, chromaWidth, chromaHeight, mbWidth * 8, mbHeight * 8,
                CHROMA_BORDER, firstRow * 8, endRow * 8);
    }

    /**
     * Replicates the samples at the edges of the displayed area of all rows.
     *
     * @param width the displayed luminance width
     * @param height the displayed luminance height
     */
    public void extendEdges(int width, int height) {
        extendEdges(width, height, 0, mbHeight);
    }

    /**
     * Replicates the edge samples of one plane for a band of coded rows: each row is extended to
     * the right from its last displayed sample and to the left from its first, the rows below the
     * displayed height repeat the last displayed row, and the band that touches the top or bottom
     * of the coded area also fills the border above or below.
     *
     * @param plane the samples of one component, edited in place
     * @param stride the row stride
     * @param origin the index of the top-left coded sample
     * @param width the displayed width
     * @param height the displayed height
     * @param codedWidth the coded width
     * @param codedRows the coded height
     * @param border the border width
     * @param firstRow the first coded row of the band
     * @param endRow the coded row after the band
     */
    private static void extendPlane(byte[] plane, int stride, int origin, int width, int height, int codedWidth,
                                    int codedRows, int border, int firstRow, int endRow) {
        Planes.extendSides(plane, origin + firstRow * stride, stride, width, Math.min(endRow, height) - firstRow,
                border, codedWidth - width + border);
        var bottomSource = origin + (height - 1) * stride - border;
        var rowLength = codedWidth + 2 * border;
        var fillEnd = endRow >= codedRows ? codedRows + border : endRow;
        for (var row = Math.max(firstRow, height); row < fillEnd; row++) {
            System.arraycopy(plane, bottomSource, plane, origin + row * stride - border, rowLength);
        }
        if (firstRow == 0) {
            Planes.extendTop(plane, origin - border, stride, rowLength, border);
        }
    }
}
