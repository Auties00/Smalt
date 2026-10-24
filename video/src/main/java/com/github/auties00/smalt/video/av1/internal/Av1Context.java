/* Ports dav1d (src/obu.c, src/decode.c, src/lib.c). Copyright (c) 2018-2025, VideoLAN and dav1d authors; BSD 2-Clause
   License. The tile list decoding follows libaom 3.13.1 (av1/decoder/obu.c, read_and_decode_one_tile_list).
   Copyright (c) 2016, Alliance for Open Media; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Decodes a stream of AV1 OBUs: parses sequence and frame headers, collects tile groups, decodes
 * frames, maintains the eight reference slots and collects the frames to show.
 *
 * <p>The decoder follows operating point 0 unless {@link #operatingPoint(int)} selects another
 * and, for scalable streams, shows only the highest spatial layer of each temporal unit unless
 * {@link #allLayers(boolean)} asks for every layer. Shown frames are returned as {@link Av1Picture}s
 * with film grain applied; the caller releases each one. Temporal units are OBUs in the low-overhead
 * format unless {@link #annexB(boolean)} selects the length-delimited format of Annex B.
 *
 * <p>While {@link #anchorFrames(Av1Picture[])} holds anchor frames, the decoder runs the large scale
 * tile decoding process (AV1 specification 7.3): a frame header OBU or frame OBU becomes the camera
 * frame header, whose tile data is skipped, tile group OBUs are skipped, and every tile list OBU
 * decodes its tiles from the anchor frames into one output frame, returned with a {@code null}
 * {@link Av1Picture#frameHdr} and without film grain. The reference slots are not updated in this
 * mode. Without anchor frames, tile list OBUs are skipped.
 *
 * <p>With a frame delay of one (the default), every frame is decoded before the call that submits
 * it returns, and each call returns the frames its temporal unit shows. With a larger frame delay
 * ({@link #frameDelay(int)}), up to that many frames are decoded at once on the module's shared
 * pool, each frame's reconstruction overlapping with the frames before it (see {@link FrameJob});
 * a shown frame is then returned by a later call once the frames decoded before it are done, or by
 * {@link #flush(List)}, and the failure of a corrupt frame is raised by the call that would return
 * it. The frames, and the calls that return them, never depend on the number of threads. Instances
 * are not thread-safe.
 *
 * @implNote This implementation ports dav1d's OBU dispatcher {@code dav1d_parse_obus}
 * ({@code src/obu.c}), {@code dav1d_submit_frame} and {@code dav1d_decode_frame}
 * ({@code src/decode.c}) and the output selection of {@code src/lib.c}
 * ({@code output_picture_ready}, {@code output_image}), with dav1d's default settings
 * except that, unless every layer is requested, every spatial layer but the highest of a
 * temporal unit is dropped, as libaom does, and that the frames in flight retire in a
 * fixed order (the oldest whenever the frame delay is reached) rather than as they
 * finish, so that the output of every call is deterministic.
 */
public final class Av1Context {
    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * The picture pool of decoded frames.
     */
    private final Av1PicturePool pool;

    /**
     * The picture pool of frames with film grain applied.
     */
    private final Av1PicturePool grainPool;

    /**
     * The OBU reader.
     */
    private final BitReader gb = new BitReader();

    /**
     * The mastering display of the last {@code METADATA_TYPE_HDR_MDCV} metadata OBU, or
     * {@code null} before the first.
     */
    private MasteringDisplay masteringDisplay;

    /**
     * The light level of the last {@code METADATA_TYPE_HDR_CLL} metadata OBU, or {@code null}
     * before the first.
     */
    private ContentLightLevel contentLightLevel;

    /**
     * The active sequence header, or {@code null} before the first one.
     */
    private SequenceHeader seqHdr;

    /**
     * The layer mask of the selected operating point.
     */
    private int operatingPointIdc;

    /**
     * The highest spatial layer of the selected operating point.
     */
    private int maxSpatialId;

    /**
     * The operating point requested with {@link #operatingPoint(int)}.
     */
    private int operatingPoint;

    /**
     * Whether every spatial layer of a temporal unit is shown, set with
     * {@link #allLayers(boolean)}.
     */
    private boolean allLayers;

    /**
     * Whether film grain is synthesized on the shown frames.
     */
    private boolean filmGrain = true;

    /**
     * Whether temporal units are in the length-delimited format of Annex B.
     */
    private boolean annexB;

    /**
     * The end of the last size field {@link #annexBSize} read.
     */
    private int sizeEnd;

    /**
     * Holds no anchor frames.
     */
    private static final Av1Picture[] NO_ANCHORS = new Av1Picture[0];

    /**
     * The largest number of anchor frames ({@code anchor_frame_idx} is at most 127).
     */
    public static final int MAX_ANCHOR_FRAMES = 128;

    /**
     * The anchor frames of large scale tile decoding, owned by the context; empty for the general
     * decoding process.
     */
    private Av1Picture[] anchors = NO_ANCHORS;

    /**
     * The camera frame header the tiles of tile list OBUs are decoded with, or {@code null}.
     */
    private FrameHeader cameraHdr;

    /**
     * The sequence header {@link #cameraHdr} was parsed with.
     */
    private SequenceHeader cameraSeq;

    /**
     * The decoding states of the parallel bands of camera tiles, created as tile lists need them and
     * dropped with the anchor frames.
     */
    private final List<CameraBand> cameraBands = new ArrayList<>();

    /**
     * Whether each reference of the camera frame predicts from the frame of its slot rather than
     * from the anchor frame of the tile.
     */
    private final boolean[] slotUsable = new boolean[7];

    /**
     * The anchor frame index of each entry of the tile list being decoded.
     */
    private final int[] entryAnchor = new int[512];

    /**
     * The tile row of each entry of the tile list being decoded.
     */
    private final int[] entryRow = new int[512];

    /**
     * The tile column of each entry of the tile list being decoded.
     */
    private final int[] entryCol = new int[512];

    /**
     * The first byte of the coded tile of each entry of the tile list being decoded.
     */
    private final int[] entryOffset = new int[512];

    /**
     * The size of the coded tile of each entry of the tile list being decoded.
     */
    private final int[] entrySize = new int[512];

    /**
     * The failure of each entry of the tile list being decoded, or {@code null}.
     */
    private final RuntimeException[] entryFailure = new RuntimeException[512];

    /**
     * The decoding state of one band of the camera tiles of a tile list: its frame state, the
     * picture its tiles are reconstructed into, and the motion and segmentation buffers no reference
     * slot keeps.
     */
    private static final class CameraBand {
        /**
         * The frame and task state.
         */
        final FrameJob job = new FrameJob();

        /**
         * The picture of the camera frame size the tiles are reconstructed into, owned by the band
         * and kept between tile lists, or {@code null} before the first one.
         */
        Av1Picture cur;

        /**
         * The temporal motion buffer.
         */
        RefMvs.Temporal mvs;

        /**
         * The segmentation map.
         */
        byte[] segmap;
    }

    /**
     * The picture of each reference slot.
     */
    private final Av1Picture[] refPic = new Av1Picture[8];

    /**
     * The frame header of each reference slot.
     */
    private final FrameHeader[] refHdr = new FrameHeader[8];

    /**
     * The segmentation map of each reference slot.
     */
    private final byte[][] refSegmap = new byte[8][];

    /**
     * The temporal motion of each reference slot.
     */
    private final RefMvs.Temporal[] refMvs = new RefMvs.Temporal[8];

    /**
     * The order hints of the references of the frame in each reference slot.
     */
    private final int[][] refRefpoc = new int[8][7];

    /**
     * The CDFs stored with each reference slot.
     */
    private final char[][] refCdf = new char[8][];

    /**
     * The default CDFs of each quantizer category, created on demand.
     */
    private final char[][] defaultCdf = new char[4][];

    /**
     * The most recycled buffers kept per kind: one per reference slot plus the frame being decoded.
     */
    private static final int MAX_RECYCLED = 9;

    /**
     * The CDF buffers no reference slot holds any more and no frame in flight reads.
     */
    private final ArrayDeque<char[]> freeCdf = new ArrayDeque<>();

    /**
     * The temporal motion buffers no reference slot holds any more and no frame in flight reads.
     */
    private final ArrayDeque<RefMvs.Temporal> freeMvs = new ArrayDeque<>();

    /**
     * The segmentation maps no reference slot holds any more and no frame in flight reads.
     */
    private final ArrayDeque<byte[]> freeSegmap = new ArrayDeque<>();

    /**
     * The buffers no reference slot holds any more but a frame in flight may still read, each
     * with the number of the last frame submitted when it was dropped, in drop order.
     */
    private final ArrayDeque<Object> dropped = new ArrayDeque<>();

    /**
     * The number of the last frame submitted when each buffer of {@link #dropped} was dropped.
     */
    private final ArrayDeque<Long> droppedAfter = new ArrayDeque<>();

    /**
     * The frame header being assembled, or {@code null}.
     */
    private FrameHeader frameHdr;

    /**
     * The number of tiles collected for {@link #frameHdr}.
     */
    private int nTiles;

    /**
     * The tile groups collected for {@link #frameHdr}; only its tile data fields are used.
     */
    private final FrameContext collected = new FrameContext();

    /**
     * How frames are decoded; every mode produces the same pictures.
     */
    public enum Threading {
        /**
         * Two passes on the module's shared pool when the frame is large enough and a helper thread
         * of the module is free when the frame starts, one pass on the calling thread otherwise.
         */
        AUTO,

        /**
         * One pass on the calling thread, interleaving decoding and filtering; the frame delay is
         * ignored.
         */
        OFF,

        /**
         * Two passes for every frame, whose tasks run on the calling thread alone when the module
         * has no helper threads, and the tiles of tile lists in at least two bands; a test hook for
         * the two-pass and banded paths.
         */
        FORCED
    }

    /**
     * The largest frame delay {@link #frameDelay(int)} accepts.
     */
    public static final int MAX_FRAME_DELAY = 64;

    /**
     * The approximate number of operations per sample of a camera tile, which weighs the decoding
     * of a tile list against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long CAMERA_TILE_WORK = 16;

    /**
     * The smallest frame, in luma samples, decoded in two passes under {@link Threading#AUTO}.
     */
    private static final int MIN_THREADED_SAMPLES = 1 << 16;

    /**
     * The threading mode.
     */
    private Threading threading = Threading.AUTO;

    /**
     * The most frames in flight, set with {@link #frameDelay(int)}.
     */
    private int frameDelay = 1;

    /**
     * The failure of a frame retired while later frames were submitted, raised once the current
     * call has submitted the frames of its temporal unit; {@code null} when none.
     */
    private MediaException deferred;

    /**
     * The scheduler of the frames decoded in two passes.
     */
    private final FrameScheduler scheduler = new FrameScheduler();

    /**
     * The frames submitted and not yet retired, oldest first.
     */
    private final ArrayDeque<FrameJob> inFlight = new ArrayDeque<>();

    /**
     * The jobs of retired frames, kept for reuse with their buffers.
     */
    private final ArrayDeque<FrameJob> idle = new ArrayDeque<>();

    /**
     * The number of frames submitted so far.
     */
    private long submitted;

    /**
     * The number of the last frame retired.
     */
    private long retired;

    /**
     * The frames to show, in showing order, each waiting for the frame that decodes its picture to
     * retire.
     */
    private final ArrayDeque<Output> outputs = new ArrayDeque<>();

    /**
     * The entries of {@link #outputs} no longer in use, kept for reuse.
     */
    private final ArrayDeque<Output> freeOutputs = new ArrayDeque<>();

    /**
     * The frames shown by the current call, in order.
     */
    private final List<Av1Picture> shown = new ArrayList<>();

    /**
     * The order hints of the references of the frame being set up.
     */
    private final int[] refpoc = new int[7];

    /**
     * The film grain tables.
     */
    private final FilmGrainBuffers grain = new FilmGrainBuffers();

    /**
     * The timestamp of the packet being decoded.
     */
    private long pts;

    /**
     * The duration of the packet being decoded.
     */
    private long duration;

    /**
     * One frame to show.
     */
    private static final class Output {
        /**
         * The picture, or {@code null} once dropped.
         */
        Av1Picture picture;

        /**
         * The number of the frame whose retirement makes the picture final.
         */
        long after;

        /**
         * The timestamp of the packet that showed the frame.
         */
        long pts;

        /**
         * The duration of the packet that showed the frame.
         */
        long duration;
    }

    /**
     * Creates a decoder.
     *
     * @param limits the resource limits
     */
    public Av1Context(MediaLimits limits) {
        this.limits = limits;
        this.pool = new Av1PicturePool(limits);
        this.grainPool = new Av1PicturePool(limits);
    }

    /**
     * Selects how frames are decoded, from the next frame on.
     *
     * @param mode the threading mode
     */
    public void threading(Threading mode) {
        this.threading = Objects.requireNonNull(mode, "mode");
    }

    /**
     * Selects how many frames may be in flight at once, as dav1d's {@code max_frame_delay} does:
     * with one, every frame is decoded by the call that submits it; with more, frames are decoded
     * concurrently and returned later (see the class documentation). It takes effect from the next
     * frame.
     *
     * @param delay the most frames in flight, from 1 to {@link #MAX_FRAME_DELAY}
     * @throws IllegalArgumentException if the delay is outside 1 to {@link #MAX_FRAME_DELAY}
     */
    public void frameDelay(int delay) {
        if (delay < 1 || delay > MAX_FRAME_DELAY) {
            throw new IllegalArgumentException("frame delay " + delay + " is outside 1.." + MAX_FRAME_DELAY);
        }
        this.frameDelay = delay;
    }

    /**
     * Returns whether the frame being set up decodes in two passes.
     *
     * @param hdr the frame header
     * @param async whether the frame is decoded concurrently with others
     * @return {@code true} for two-pass decoding
     */
    private boolean threaded(FrameHeader hdr, boolean async) {
        if (async) {
            return true;
        }
        return switch (threading) {
            case OFF -> false;
            case FORCED -> true;
            case AUTO -> ParallelBands.enabled() && (long) hdr.upscaledWidth * hdr.height >= MIN_THREADED_SAMPLES
                    && ParallelBands.available() > 0;
        };
    }

    /**
     * Returns whether the next frame is decoded concurrently with others.
     *
     * @return {@code true} when frames may be in flight
     */
    private boolean asynchronous() {
        return frameDelay > 1 && threading != Threading.OFF && (maxSpatialId == 0 || allLayers);
    }

    /**
     * Selects the operating point to decode, as dav1d's {@code operating_point} setting does: an
     * index the sequence header does not declare selects operating point 0. It takes effect from
     * the next sequence header.
     *
     * @param index the operating point index, from 0 to 31
     * @throws IllegalArgumentException if the index is outside 0 to 31
     */
    public void operatingPoint(int index) {
        if (index < 0 || index > 31) {
            throw new IllegalArgumentException("operating point " + index + " is outside 0..31");
        }
        this.operatingPoint = index;
    }

    /**
     * Selects whether every spatial layer of a temporal unit is shown, as dav1d's
     * {@code all_layers} setting does, rather than only the highest layer of the operating point.
     *
     * @param allLayers {@code true} to show every layer
     */
    public void allLayers(boolean allLayers) {
        this.allLayers = allLayers;
    }

    /**
     * Selects whether film grain is synthesized on the shown frames, as dav1d's {@code apply_grain} setting does;
     * without it a frame is output as reconstructed, its grain parameters ignored.
     *
     * @param filmGrain {@code true}, the default, to synthesize film grain
     */
    public void filmGrain(boolean filmGrain) {
        this.filmGrain = filmGrain;
    }

    /**
     * Selects whether the temporal units passed to {@link #decode} are in the length-delimited
     * format of Annex B ({@code temporal_unit(temporal_unit_size)}, size field included) rather than
     * a sequence of low-overhead OBUs; codec configuration records are always low-overhead.
     *
     * @param annexB {@code true} for Annex B
     */
    public void annexB(boolean annexB) {
        this.annexB = annexB;
    }

    /**
     * Replaces the anchor frames of large scale tile decoding, taking ownership of the pictures and
     * releasing the previous ones; an empty array returns to the general decoding process and
     * forgets the camera frame header. Each picture needs only its planes, size, layout and bit
     * depth; its headers are ignored.
     *
     * @param frames the anchor frames, at most {@link #MAX_ANCHOR_FRAMES}, indexed by
     * {@code anchor_frame_idx}
     * @throws IllegalArgumentException if there are more than {@link #MAX_ANCHOR_FRAMES} frames
     */
    public void anchorFrames(Av1Picture[] frames) {
        if (frames.length > MAX_ANCHOR_FRAMES) {
            throw new IllegalArgumentException(frames.length + " anchor frames exceed " + MAX_ANCHOR_FRAMES);
        }
        for (var anchor : anchors) {
            anchor.release();
        }
        anchors = frames.length == 0 ? NO_ANCHORS : frames.clone();
        if (anchors.length == 0) {
            cameraHdr = null;
            cameraSeq = null;
            cameraBands.clear();
        }
        frameHdr = null;
        nTiles = 0;
        collected.nTileData = 0;
    }

    /**
     * {@return the number of anchor frames, 0 for the general decoding process}
     */
    public int anchorFrameCount() {
        return anchors.length;
    }


    /**
     * Decodes the OBUs of a codec configuration record ({@code av1C}), whose configuration OBUs
     * usually carry the sequence header.
     *
     * @param config the record
     * @throws MediaException.Malformed if the configuration OBUs are corrupt
     */
    public void decodeConfig(byte[] config) {
        if (config.length <= 4 || (config[0] & 0x80) == 0) {
            return;
        }
        var out = new ArrayList<Av1Picture>();
        try {
            decodeUnit(config, 4, config.length - 4, 0, 0, out, false);
            flush(out);
        } finally {
            for (var picture : out) {
                picture.release();
            }
        }
    }

    /**
     * Decodes the OBUs of one temporal unit and appends the frames that became due: with a frame
     * delay of one, the frames the unit shows.
     *
     * @param data the buffer
     * @param offset the first byte
     * @param length the number of bytes
     * @param pts the presentation timestamp of the unit
     * @param duration the duration of the unit
     * @param out receives the shown pictures, which the caller releases
     * @throws MediaException.Malformed if the data is corrupt, or a frame submitted earlier that
     * would be returned now is
     * @throws MediaException.LimitExceeded if a frame exceeds the limits
     */
    public void decode(byte[] data, int offset, int length, long pts, long duration, List<Av1Picture> out) {
        decodeUnit(data, offset, length, pts, duration, out, annexB);
    }

    /**
     * Decodes the OBUs of one temporal unit in either format, as {@link #decode} describes.
     *
     * @param data the buffer
     * @param offset the first byte
     * @param length the number of bytes
     * @param pts the presentation timestamp of the unit
     * @param duration the duration of the unit
     * @param out receives the shown pictures, which the caller releases
     * @param lengthDelimited whether the unit is in the length-delimited format of Annex B
     */
    private void decodeUnit(byte[] data, int offset, int length, long pts, long duration, List<Av1Picture> out,
                            boolean lengthDelimited) {
        this.pts = pts;
        this.duration = duration;
        shown.clear();
        var unitStart = outputs.size();
        try {
            var pos = offset;
            var end = offset + length;
            if (lengthDelimited) {
                parseAnnexB(data, pos, end);
            } else {
                while (pos < end) {
                    pos += parseObu(data, pos, end - pos);
                }
            }
        } catch (RuntimeException exception) {
            frameHdr = null;
            nTiles = 0;
            collected.nTileData = 0;
            deferred = null;
            releaseShown();
            throw exception;
        }
        retainPendingTiles();
        if (maxSpatialId != 0 && !allLayers) {
            dropLowerLayers(unitStart);
        }
        collectOutputs();
        raiseDeferred();
        deliver(out);
    }

    /**
     * Raises the failure of a frame retired by the current call, if any, dropping the frames the
     * call would have returned.
     *
     * @throws MediaException the failure
     */
    private void raiseDeferred() {
        var failure = deferred;
        if (failure != null) {
            deferred = null;
            releaseShown();
            throw failure;
        }
    }

    /**
     * Retires every frame in flight and appends the frames still to show.
     *
     * @param out receives the shown pictures, which the caller releases
     * @throws MediaException.Malformed if a frame in flight is corrupt
     */
    public void flush(List<Av1Picture> out) {
        shown.clear();
        while (!inFlight.isEmpty()) {
            retireDeferring();
        }
        collectOutputs();
        raiseDeferred();
        deliver(out);
    }

    /**
     * Keeps only the last frame a temporal unit shows, dropping its lower spatial layers.
     *
     * @param unitStart the number of entries {@link #outputs} held before the unit
     */
    private void dropLowerLayers(int unitStart) {
        var last = outputs.size() - 1;
        var i = 0;
        for (var entry : outputs) {
            if (i >= unitStart && i < last && entry.picture != null) {
                entry.picture.release();
                entry.picture = null;
            }
            i++;
        }
    }

    /**
     * Moves the frames whose pictures are final to the frames shown by the current call, in
     * showing order, skipping the frames of corrupt pictures.
     */
    private void collectOutputs() {
        while (!outputs.isEmpty() && outputs.peekFirst().after <= retired) {
            var entry = outputs.pollFirst();
            var picture = entry.picture;
            entry.picture = null;
            freeOutputs.add(entry);
            if (picture == null) {
                continue;
            }
            if (picture.progress.failed()) {
                picture.release();
                continue;
            }
            picture.pts = entry.pts;
            picture.duration = entry.duration;
            shown.add(picture);
        }
    }

    /**
     * Applies film grain to the frames shown by the current call and appends them.
     *
     * @param out receives the shown pictures
     */
    private void deliver(List<Av1Picture> out) {
        try {
            for (var i = 0; i < shown.size(); i++) {
                var picture = shown.get(i);
                shown.set(i, null);
                out.add(applyGrain(picture));
            }
        } finally {
            for (var picture : shown) {
                if (picture != null) {
                    picture.release();
                }
            }
            shown.clear();
        }
    }

    /**
     * Queues a frame to show.
     *
     * @param picture the picture, retained for the output
     * @param after the number of the frame whose retirement makes the picture final
     */
    private void queueOutput(Av1Picture picture, long after) {
        var entry = freeOutputs.poll();
        if (entry == null) {
            entry = new Output();
        }
        entry.picture = picture;
        entry.after = after;
        entry.pts = pts;
        entry.duration = duration;
        outputs.addLast(entry);
    }

    /**
     * Copies the tile data of a frame whose tile groups continue in a later packet, so that the
     * packet's array is not retained.
     */
    private void retainPendingTiles() {
        var c = collected;
        for (var i = 0; i < c.nTileData; i++) {
            var copy = Arrays.copyOfRange(c.tileData[i], c.tileOffset[i], c.tileOffset[i] + c.tileSize[i]);
            c.tileData[i] = copy;
            c.tileOffset[i] = 0;
        }
    }

    /**
     * Releases the pictures collected for the call.
     */
    private void releaseShown() {
        for (var picture : shown) {
            if (picture != null) {
                picture.release();
            }
        }
        shown.clear();
    }

    /**
     * Discards the frames in flight, the frames waiting to be shown, the references, the anchor
     * frames and the decoding state.
     */
    public void reset() {
        while (!inFlight.isEmpty()) {
            var job = inFlight.pollFirst();
            scheduler.await(job);
            retire(job);
        }
        for (var entry : outputs) {
            if (entry.picture != null) {
                entry.picture.release();
                entry.picture = null;
            }
        }
        outputs.clear();
        deferred = null;
        for (var i = 0; i < 8; i++) {
            clearSlot(i);
        }
        frameHdr = null;
        nTiles = 0;
        collected.nTileData = 0;
        seqHdr = null;
        anchorFrames(NO_ANCHORS);
    }

    /**
     * Empties one reference slot.
     *
     * @param i the slot
     */
    private void clearSlot(int i) {
        if (refPic[i] != null) {
            refPic[i].release();
            refPic[i] = null;
        }
        refHdr[i] = null;
        var segmap = refSegmap[i];
        var mvs = refMvs[i];
        var cdf = refCdf[i];
        refSegmap[i] = null;
        refMvs[i] = null;
        refCdf[i] = null;
        recycle(cdf, mvs, segmap);
    }

    /**
     * Drops the buffers a reference slot no longer holds: once no slot holds a buffer, it becomes
     * free for reuse as soon as every frame submitted so far, which may read it, has retired.
     *
     * @param cdf the dropped CDFs, or {@code null}
     * @param mvs the dropped temporal motion, or {@code null}
     * @param segmap the dropped segmentation map, or {@code null}
     */
    private void recycle(char[] cdf, RefMvs.Temporal mvs, byte[] segmap) {
        var cdfHeld = cdf == null;
        var mvsHeld = mvs == null;
        var segmapHeld = segmap == null;
        for (var i = 0; i < 8; i++) {
            cdfHeld |= refCdf[i] == cdf;
            mvsHeld |= refMvs[i] == mvs;
            segmapHeld |= refSegmap[i] == segmap;
        }
        for (var defaults : defaultCdf) {
            cdfHeld |= defaults == cdf;
        }
        if (!cdfHeld) {
            drop(cdf);
        }
        if (!mvsHeld) {
            drop(mvs);
        }
        if (!segmapHeld) {
            drop(segmap);
        }
        releaseDropped();
    }

    /**
     * Records a buffer no slot holds any more.
     *
     * @param buffer the dropped CDF, motion or segmentation buffer
     */
    private void drop(Object buffer) {
        for (var held : dropped) {
            if (held == buffer) {
                return;
            }
        }
        dropped.addLast(buffer);
        droppedAfter.addLast(submitted);
    }

    /**
     * Frees the dropped buffers that no frame in flight may read any more.
     */
    private void releaseDropped() {
        while (!dropped.isEmpty() && droppedAfter.peekFirst() <= retired) {
            var buffer = dropped.pollFirst();
            droppedAfter.pollFirst();
            switch (buffer) {
                case char[] cdf when freeCdf.size() < MAX_RECYCLED -> freeCdf.push(cdf);
                case RefMvs.Temporal mvs when freeMvs.size() < MAX_RECYCLED -> freeMvs.push(mvs);
                case byte[] segmap when freeSegmap.size() < MAX_RECYCLED -> freeSegmap.push(segmap);
                default -> {
                }
            }
        }
    }

    /**
     * Assigns the state of a decoded or shown frame to a reference slot, recycling what the slot
     * held before.
     *
     * @param i the slot
     * @param cdf the CDFs to store
     * @param segmap the segmentation map to store
     * @param mvs the temporal motion to store
     */
    private void assignSlot(int i, char[] cdf, byte[] segmap, RefMvs.Temporal mvs) {
        var oldCdf = refCdf[i];
        var oldSegmap = refSegmap[i];
        var oldMvs = refMvs[i];
        refCdf[i] = cdf;
        refSegmap[i] = segmap;
        refMvs[i] = mvs;
        recycle(oldCdf, oldMvs, oldSegmap);
    }

    /**
     * Returns a malformed-stream exception.
     *
     * @param message the description
     * @return the exception
     */
    private static MediaException.Malformed malformed(String message) {
        return new MediaException.Malformed("av1: " + message);
    }

    /**
     * Parses a temporal unit in the length-delimited format (AV1 specification, Annex B): its
     * {@code temporal_unit_size}, then frame units of OBUs, each preceded by its size. An OBU that
     * also carries {@code obu_size} must agree with its {@code obu_length}; an empty buffer holds no
     * unit.
     *
     * @param data the buffer
     * @param offset the first byte of the {@code temporal_unit_size} field
     * @param end the end of the unit's bytes
     */
    private void parseAnnexB(byte[] data, int offset, int end) {
        if (offset == end) {
            return;
        }
        var unitSize = annexBSize(data, offset, end);
        var pos = sizeEnd;
        if (unitSize != end - pos) {
            throw malformed(unitSize > end - pos ? "temporal unit exceeds the packet" : "bytes after the temporal unit");
        }
        while (pos < end) {
            var frameSize = annexBSize(data, pos, end);
            pos = sizeEnd;
            if (frameSize > end - pos) {
                throw malformed("frame unit exceeds the temporal unit");
            }
            var frameEnd = pos + (int) frameSize;
            while (pos < frameEnd) {
                var obuLength = annexBSize(data, pos, frameEnd);
                pos = sizeEnd;
                if (obuLength > frameEnd - pos) {
                    throw malformed("OBU exceeds the frame unit");
                }
                if (parseObu(data, pos, (int) obuLength) != obuLength) {
                    throw malformed("obu_size disagrees with obu_length");
                }
                pos += (int) obuLength;
            }
        }
    }

    /**
     * Reads one size field of the length-delimited format ({@code leb128()}), leaving the index
     * after it in {@link #sizeEnd}.
     *
     * @param data the buffer
     * @param offset the first byte of the field
     * @param end the end of the readable bytes
     * @return the value, at most {@code 2^32 - 1}
     */
    private long annexBSize(byte[] data, int offset, int end) {
        long value = 0;
        for (var i = 0; i < 8; i++) {
            if (offset + i >= end) {
                throw malformed("truncated size field");
            }
            var b = data[offset + i] & 0xFF;
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                if (value > 0xFFFFFFFFL) {
                    break;
                }
                sizeEnd = offset + i + 1;
                return value;
            }
        }
        throw malformed("invalid size field");
    }

    /**
     * Parses one OBU and acts on it ({@code dav1d_parse_obus}).
     *
     * @param data the buffer
     * @param offset the first byte of the OBU
     * @param length the bytes left in the unit
     * @return the size of the OBU
     */
    private int parseObu(byte[] data, int offset, int length) {
        gb.init(data, offset, length);
        gb.bit();
        var type = gb.bits(4);
        var hasExtension = gb.bit() != 0;
        var hasLengthField = gb.bit() != 0;
        gb.bit();
        var temporalId = 0;
        var spatialId = 0;
        if (hasExtension) {
            temporalId = gb.bits(3);
            spatialId = gb.bits(2);
            gb.bits(3);
        }
        if (hasLengthField) {
            var len = gb.uleb128();
            if (len > gb.end() - gb.position()) {
                throw malformed("OBU size exceeds the packet");
            }
            gb.setEnd(gb.position() + (int) len);
        }
        if (gb.error()) {
            throw malformed("truncated OBU header");
        }
        var obuEnd = gb.end();
        var consumed = obuEnd - offset;
        if (type != Obu.OBU_SEQ_HDR && type != Obu.OBU_TD && hasExtension && operatingPointIdc != 0) {
            var inTemporalLayer = ((operatingPointIdc >> temporalId) & 1) != 0;
            var inSpatialLayer = ((operatingPointIdc >> (spatialId + 8)) & 1) != 0;
            if (!inTemporalLayer || !inSpatialLayer) {
                return consumed;
            }
        }
        var largeScale = anchors.length != 0;
        switch (type) {
            case Obu.OBU_SEQ_HDR -> parseSequenceHeader();
            case Obu.OBU_REDUNDANT_FRAME_HDR -> {
                if (frameHdr == null && !largeScale) {
                    parseFrameHeader(temporalId, spatialId, false);
                }
            }
            case Obu.OBU_FRAME, Obu.OBU_FRAME_HDR -> {
                if (largeScale) {
                    parseCameraHeader(temporalId, spatialId, type == Obu.OBU_FRAME);
                    return consumed;
                }
                parseFrameHeader(temporalId, spatialId, type == Obu.OBU_FRAME);
                if (type == Obu.OBU_FRAME) {
                    if (frameHdr.showExistingFrame) {
                        frameHdr = null;
                        throw malformed("frame OBU with show_existing_frame");
                    }
                    gb.byteAlign();
                    parseTileGroup(data);
                }
            }
            case Obu.OBU_TILE_GRP -> {
                if (largeScale) {
                    return consumed;
                }
                if (frameHdr == null) {
                    throw malformed("tile group without frame header");
                }
                parseTileGroup(data);
            }
            case Obu.OBU_TILE_LIST -> {
                if (largeScale) {
                    decodeTileList(data);
                }
            }
            case Obu.OBU_METADATA -> parseMetadata();
            default -> {
            }
        }
        if (seqHdr != null && frameHdr != null) {
            if (frameHdr.showExistingFrame) {
                showExisting();
                frameHdr = null;
            } else if (nTiles == frameHdr.tilingCols * frameHdr.tilingRows) {
                if (collected.nTileData == 0) {
                    throw malformed("frame without tile data");
                }
                var hdr = frameHdr;
                frameHdr = null;
                nTiles = 0;
                submitFrame(hdr);
            }
        }
        return consumed;
    }

    /**
     * Parses a metadata OBU, keeping the high-dynamic-range metadata ({@code METADATA_TYPE_HDR_CLL}
     * and {@code METADATA_TYPE_HDR_MDCV}, AV1 specification 5.8.3 and 5.8.4) and ignoring every
     * other type; a truncated or out-of-range payload is ignored.
     *
     * <p>The metadata persists until another metadata OBU of the same type replaces it, as in
     * dav1d, so every later frame carries it.
     */
    private void parseMetadata() {
        var type = gb.uleb128();
        if (type == 1) {
            var maxCll = gb.bits(16);
            var maxFall = gb.bits(16);
            if (!gb.error()) {
                contentLightLevel = new ContentLightLevel(maxCll, maxFall);
            }
        } else if (type == 2) {
            var primaries = new double[6];
            for (var i = 0; i < 6; i++) {
                primaries[i] = gb.bits(16) / 65536.0;
            }
            var whiteX = gb.bits(16) / 65536.0;
            var whiteY = gb.bits(16) / 65536.0;
            var max = gb.bits32() / 256.0;
            var min = gb.bits32() / 16384.0;
            if (!gb.error()) {
                masteringDisplay = new MasteringDisplay(new Chromaticity(primaries[0], primaries[1]),
                        new Chromaticity(primaries[2], primaries[3]), new Chromaticity(primaries[4], primaries[5]),
                        new Chromaticity(whiteX, whiteY), max, min);
            }
        }
    }

    /**
     * Returns the mastering display of the last {@code METADATA_TYPE_HDR_MDCV} metadata OBU.
     *
     * @return the mastering display, or {@code null} when none was seen
     */
    public MasteringDisplay masteringDisplay() {
        return masteringDisplay;
    }

    /**
     * Returns the light level of the last {@code METADATA_TYPE_HDR_CLL} metadata OBU.
     *
     * @return the light level, or {@code null} when none was seen
     */
    public ContentLightLevel contentLightLevel() {
        return contentLightLevel;
    }

    /**
     * Parses a sequence header OBU and resets the references when a new sequence starts.
     */
    private void parseSequenceHeader() {
        var seq = new SequenceHeader();
        if (!seq.parse(gb, false)) {
            throw malformed("invalid sequence header");
        }
        var opIdx = operatingPoint < seq.numOperatingPoints ? operatingPoint : 0;
        operatingPointIdc = seq.operatingPointIdc[opIdx];
        var spatialMask = operatingPointIdc >> 8;
        maxSpatialId = spatialMask != 0 ? 31 - Integer.numberOfLeadingZeros(spatialMask) : 0;
        if (seqHdr == null) {
            frameHdr = null;
        } else if (!seq.sameSequence(seqHdr)) {
            frameHdr = null;
            cameraHdr = null;
            cameraSeq = null;
            for (var i = 0; i < 8; i++) {
                clearSlot(i);
            }
        }
        seqHdr = seq;
    }

    /**
     * Parses a frame header and starts collecting the frame's tiles.
     *
     * @param temporalId the temporal layer of the OBU
     * @param spatialId the spatial layer of the OBU
     * @param frameObu whether the header is the start of a frame OBU
     */
    private void parseFrameHeader(int temporalId, int spatialId, boolean frameObu) {
        if (seqHdr == null) {
            throw malformed("frame header before sequence header");
        }
        var hdr = new FrameHeader();
        hdr.temporalId = temporalId;
        hdr.spatialId = spatialId;
        frameHdr = null;
        if (!hdr.parse(seqHdr, gb, refHdr, false)) {
            throw malformed("invalid frame header");
        }
        collected.nTileData = 0;
        nTiles = 0;
        if (!frameObu && !Obu.checkTrailingBits(gb, false)) {
            throw malformed("truncated frame header");
        }
        if (!hdr.showExistingFrame) {
            LimitChecks.checkPicture(limits, hdr.upscaledWidth, hdr.height);
        }
        frameHdr = hdr;
    }

    /**
     * Parses the camera frame header of large scale tile decoding, which the tiles of later tile
     * list OBUs are decoded with, and checks the constraints the large scale tile decoding process
     * needs (AV1 specification 7.3.1): an inter frame, without super-resolution or reference frame
     * motion vectors, whose tiles are all of one size.
     *
     * @param temporalId the temporal layer of the OBU
     * @param spatialId the spatial layer of the OBU
     * @param frameObu whether the header starts a frame OBU, whose tile data is skipped
     */
    private void parseCameraHeader(int temporalId, int spatialId, boolean frameObu) {
        if (seqHdr == null) {
            throw malformed("frame header before sequence header");
        }
        cameraHdr = null;
        cameraSeq = null;
        var hdr = new FrameHeader();
        hdr.temporalId = temporalId;
        hdr.spatialId = spatialId;
        if (!hdr.parse(seqHdr, gb, refHdr, false)) {
            throw malformed("invalid frame header");
        }
        if (!frameObu && !Obu.checkTrailingBits(gb, false)) {
            throw malformed("truncated frame header");
        }
        if (hdr.showExistingFrame || hdr.frameType != FrameHeader.INTER) {
            throw malformed("camera frame header is not an inter frame");
        }
        if (hdr.codedWidth != hdr.upscaledWidth || hdr.useRefFrameMvs) {
            throw malformed("camera frame header uses super-resolution or reference frame motion vectors");
        }
        var sbLog2 = seqHdr.sb128 ? 7 : 6;
        var miWidth = ((hdr.upscaledWidth + 7) >> 3) << 3;
        var miHeight = ((hdr.height + 7) >> 3) << 3;
        var tileWidth = tileSpan(hdr.colStartSb, 0, sbLog2, miWidth);
        var tileHeight = tileSpan(hdr.rowStartSb, 0, sbLog2, miHeight);
        for (var col = 1; col < hdr.tilingCols; col++) {
            if (tileSpan(hdr.colStartSb, col, sbLog2, miWidth) != tileWidth) {
                throw malformed("camera frame header tiles differ in width");
            }
        }
        for (var row = 1; row < hdr.tilingRows; row++) {
            if (tileSpan(hdr.rowStartSb, row, sbLog2, miHeight) != tileHeight) {
                throw malformed("camera frame header tiles differ in height");
            }
        }
        LimitChecks.checkPicture(limits, hdr.upscaledWidth, hdr.height);
        cameraHdr = hdr;
        cameraSeq = seqHdr;
    }

    /**
     * Returns the size of a tile in samples ({@code TileWidth} or {@code TileHeight}).
     *
     * @param startSb the first superblock of each tile column or row, then the superblock count
     * @param index the tile column or row
     * @param sbLog2 the binary logarithm of the superblock size
     * @param limit the frame size rounded up to 8 samples ({@code MiCols * MI_SIZE})
     * @return the size of the tile
     */
    private static int tileSpan(int[] startSb, int index, int sbLog2, int limit) {
        return Math.min(startSb[index + 1] << sbLog2, limit) - (startSb[index] << sbLog2);
    }

    /**
     * Decodes a tile list OBU ({@code tile_list_obu}, large scale tile decoding process, AV1
     * specification 7.3): decodes each listed tile with the camera frame header, predicting from the
     * listed anchor frame in place of the frame of {@code ref_frame_idx[0]}, and writes the tiles in
     * raster order into a new output frame whose other samples are 0, queued to be shown. Every
     * entry is checked before any tile is decoded; the tiles are then decoded in parallel bands, each
     * with its own frame state, unless threading is off (and in at least two bands when it is
     * forced). The output does not depend on the bands, and the failure of the first tile that fails
     * to decode is raised.
     *
     * @param data the buffer of the packet, the reader being positioned at the OBU payload
     */
    private void decodeTileList(byte[] data) {
        var hdr = cameraHdr;
        if (hdr == null) {
            throw malformed("tile list without a camera frame header");
        }
        var seq = cameraSeq;
        var widthInTiles = gb.bits(8) + 1;
        var heightInTiles = gb.bits(8) + 1;
        var count = gb.bits(16) + 1;
        if (gb.error()) {
            throw malformed("truncated tile list");
        }
        if (count > entryAnchor.length) {
            throw malformed("tile list holds " + count + " tiles, more than " + entryAnchor.length);
        }
        if (count > widthInTiles * heightInTiles) {
            throw malformed("tile list holds more tiles than its output frame");
        }
        var bpc = 8 + 2 * seq.hbd;
        for (var tile = 0; tile < count; tile++) {
            var anchorIdx = gb.bits(8);
            var row = gb.bits(8);
            var col = gb.bits(8);
            var size = gb.bits(16) + 1;
            if (gb.error() || size > gb.end() - gb.position()) {
                throw malformed("truncated tile list entry");
            }
            if (anchorIdx >= anchors.length) {
                throw malformed("tile list entry names anchor frame " + anchorIdx + " of " + anchors.length);
            }
            if (row >= hdr.tilingRows || col >= hdr.tilingCols) {
                throw malformed("tile list entry names a tile outside the camera frame");
            }
            var anchor = anchors[anchorIdx];
            if (anchor.w != hdr.upscaledWidth || anchor.h != hdr.height || anchor.layout != seq.layout
                    || anchor.bpc != bpc) {
                throw malformed("anchor frame " + anchorIdx + " is " + anchor.w + "x" + anchor.h + " at "
                        + anchor.bpc + " bits, the camera frame header codes " + hdr.upscaledWidth + "x"
                        + hdr.height + " at " + bpc + " bits in its sequence's chroma format");
            }
            var last = hdr.refidx[0];
            anchor.frameHdr = refHdr[last] != null ? refHdr[last] : hdr;
            anchor.seqHdr = seq;
            entryAnchor[tile] = anchorIdx;
            entryRow[tile] = row;
            entryCol[tile] = col;
            entryOffset[tile] = gb.position();
            entrySize[tile] = size;
            gb.seek(gb.position() + size);
        }
        while (!inFlight.isEmpty()) {
            retireDeferring();
        }
        var sbLog2 = seq.sb128 ? 7 : 6;
        var tileWidth = tileSpan(hdr.colStartSb, 0, sbLog2, ((hdr.upscaledWidth + 7) >> 3) << 3);
        var tileHeight = tileSpan(hdr.rowStartSb, 0, sbLog2, ((hdr.height + 7) >> 3) << 3);
        var bands = switch (threading) {
            case OFF -> 1;
            case FORCED -> Math.min(count, Math.max(2, ParallelBands.THREADS));
            case AUTO -> ParallelBands.bands(count, (long) count * tileWidth * tileHeight * CAMERA_TILE_WORK);
        };
        while (cameraBands.size() < bands) {
            cameraBands.add(new CameraBand());
        }
        var output = pool.obtain(widthInTiles * tileWidth, heightInTiles * tileHeight, seq.layout, bpc);
        try {
            for (var plane : output.data) {
                if (plane != null) {
                    Arrays.fill(plane, (short) 0);
                }
            }
            output.progress.start();
            output.progress.finish();
            output.seqHdr = seq;
            for (var band = 0; band < bands; band++) {
                var camera = cameraBands.get(band);
                camera.cur = cameraPicture(camera.cur, hdr.upscaledWidth, hdr.height, seq.layout, bpc);
                setupCameraFrame(camera, hdr, seq, bpc);
            }
            Arrays.fill(entryFailure, 0, count, null);
            ParallelBands.run(bands, band -> {
                var camera = cameraBands.get(band);
                var end = ParallelBands.start(band + 1, bands, count);
                for (var tile = ParallelBands.start(band, bands, count); tile < end; tile++) {
                    try {
                        useAnchor(camera.job.f, hdr, entryAnchor[tile]);
                        decodeCameraTile(camera.job, entryRow[tile], entryCol[tile], data, entryOffset[tile],
                                entrySize[tile]);
                        copyTile(camera.cur, hdr.colStartSb[entryCol[tile]] << sbLog2,
                                hdr.rowStartSb[entryRow[tile]] << sbLog2, tileWidth, tileHeight, output,
                                tileWidth * (tile % widthInTiles), tileHeight * (tile / widthInTiles));
                    } catch (RuntimeException exception) {
                        entryFailure[tile] = exception;
                        return;
                    }
                }
            });
            for (var tile = 0; tile < count; tile++) {
                var failure = entryFailure[tile];
                if (failure != null) {
                    entryFailure[tile] = null;
                    throw failure instanceof MediaException media ? media
                            : new MediaException.Malformed("av1: corrupt camera tile data", failure);
                }
            }
        } catch (RuntimeException exception) {
            output.release();
            throw exception;
        } finally {
            Arrays.fill(entryFailure, 0, count, null);
            for (var band = 0; band < bands; band++) {
                var camera = cameraBands.get(band);
                var f = camera.job.f;
                Arrays.fill(f.refp, null);
                f.cur = null;
                f.srCur = null;
            }
        }
        queueOutput(output, submitted);
    }

    /**
     * Returns the picture a band of camera tiles reconstructs into, reusing the band's picture when
     * its planes fit the camera frame.
     *
     * @param cur the band's picture, or {@code null}
     * @param w the camera frame width
     * @param h the camera frame height
     * @param layout the pixel layout
     * @param bpc the bits per sample
     * @return the picture, with unspecified samples
     */
    private Av1Picture cameraPicture(Av1Picture cur, int w, int h, int layout, int bpc) {
        if (cur != null && cur.fits(w, h, layout)) {
            cur.reset(w, h, bpc);
            return cur;
        }
        LimitChecks.checkAllocation(limits, Av1Picture.samples(w, h, layout) * 2);
        return Av1Picture.allocate(w, h, layout, bpc);
    }

    /**
     * Prepares the frame state of one band of camera tiles that every tile of a tile list OBU is
     * decoded with: the camera frame header, its geometry, CDFs, motion and segmentation buffers,
     * and the references of the slots that {@code ref_frame_idx[0]} does not name.
     *
     * @param camera the band, with the picture its tiles are reconstructed into
     * @param hdr the camera frame header
     * @param seq the sequence header of the camera frame header
     * @param bpc the bits per sample
     */
    private void setupCameraFrame(CameraBand camera, FrameHeader hdr, SequenceHeader seq, int bpc) {
        var f = camera.job.f;
        var cur = camera.cur;
        f.seqHdr = seq;
        f.frameHdr = hdr;
        cur.frameHdr = hdr;
        cur.seqHdr = seq;
        cur.progress.start();
        cur.progress.finish();
        f.cur = cur;
        f.srCur = cur;
        var last = hdr.refidx[0];
        for (var i = 0; i < 7; i++) {
            var slot = hdr.refidx[i];
            var ref = refPic[slot];
            slotUsable[i] = slot != last && ref != null && ref.w == hdr.upscaledWidth && ref.h == hdr.height
                    && ref.layout == seq.layout && ref.bpc == bpc;
            f.refp[i] = slotUsable[i] ? ref : cur;
            f.refMvs[i] = null;
            f.svcScale[i * 2] = 0;
            f.svcScale[i * 2 + 1] = 0;
            var g = hdr.gmv[i];
            f.gmvWarpAllowed[i] = g.type > WarpedMotionParams.TRANSLATION && !hdr.forceIntegerMv
                    && !WarpMv.getShearParams(g);
            f.refpoc[i] = refHdr[slot] != null ? refHdr[slot].frameOffset : 0;
        }
        var primary = hdr.primaryRefFrame == FrameHeader.PRIMARY_REF_NONE ? null
                : refCdf[hdr.refidx[hdr.primaryRefFrame]];
        f.inCdf = primary != null ? primary : defaults(hdr.quantYac);
        f.outCdf = null;
        setupGeometry(f, hdr, seq, cur, cur, false);
        f.haveTt = false;
        f.twoPass = false;
        f.frameThreaded = false;
        var mvsSize = f.sb128h * 16 * (f.b4Stride >> 1);
        if (camera.mvs == null || camera.mvs.size() != mvsSize) {
            LimitChecks.checkAllocation(limits, mvsSize * 5L);
            camera.mvs = new RefMvs.Temporal(mvsSize);
        }
        f.mvs = camera.mvs;
        f.curSegmap = null;
        f.prevSegmap = null;
        if (hdr.segmentationEnabled) {
            if (hdr.segmentationTemporal || !hdr.segmentationUpdateMap) {
                var slot = hdr.refidx[hdr.primaryRefFrame == FrameHeader.PRIMARY_REF_NONE ? 0 : hdr.primaryRefFrame];
                var ref = refHdr[slot];
                if (ref != null && refSegmap[slot] != null && ((ref.codedWidth + 7) >> 3) << 1 == f.bw
                        && ((ref.height + 7) >> 3) << 1 == f.bh) {
                    f.prevSegmap = refSegmap[slot];
                }
            }
            if (!hdr.segmentationUpdateMap && f.prevSegmap != null) {
                f.curSegmap = f.prevSegmap;
            } else {
                var size = f.b4Stride * 32 * f.sb128h;
                if (camera.segmap == null || camera.segmap.length != size) {
                    LimitChecks.checkAllocation(limits, size);
                    camera.segmap = new byte[size];
                }
                Arrays.fill(camera.segmap, (byte) 0);
                f.curSegmap = camera.segmap;
            }
        }
        FrameDecoder.init(f, limits);
    }

    /**
     * Makes an anchor frame the reference of every slot {@code ref_frame_idx[0]} names, and the
     * stand-in for the slots whose frames the camera frame cannot predict from, which a conforming
     * stream never predicts from; the anchor frame was checked against the camera frame.
     *
     * @param f the frame state of the band of camera tiles
     * @param hdr the camera frame header
     * @param index the anchor frame index
     */
    private void useAnchor(FrameContext f, FrameHeader hdr, int index) {
        var anchor = anchors[index];
        for (var i = 0; i < 7; i++) {
            f.refp[i] = slotUsable[i] ? refPic[hdr.refidx[i]] : anchor;
        }
    }

    /**
     * Decodes one camera tile into the picture of the camera frame (decode camera tile process, AV1
     * specification 7.3.2), without the in-loop filters and without CDF adaptation.
     *
     * @param job the decoding state of camera tiles, its frame prepared
     * @param row the tile row
     * @param col the tile column
     * @param data the buffer holding the coded tile
     * @param offset the first byte of the coded tile
     * @param size the size of the coded tile
     */
    private static void decodeCameraTile(FrameJob job, int row, int col, byte[] data, int offset, int size) {
        var f = job.f;
        var t = job.t;
        var hdr = f.frameHdr;
        for (var n = 0; n < f.sb128w * hdr.tilingRows; n++) {
            f.a[n].reset(false);
        }
        var ts = f.ts[row * hdr.tilingCols + col];
        TileDecoder.setupTile(ts, f, data, offset, size, row, col);
        // camera tiles never adapt their CDFs, whatever disable_cdf_update says, as libaom decodes them
        ts.msac.init(data, offset, size, true);
        t.f = f;
        t.pass = TaskContext.PASS_SINGLE;
        t.ts = ts;
        var end = Math.min(hdr.rowStartSb[row + 1], f.sbh);
        for (var sby = hdr.rowStartSb[row]; sby < end; sby++) {
            t.by = sby << f.sbShift;
            if (!TileDecoder.decodeTileSbrow(t)) {
                throw malformed("corrupt camera tile data");
            }
        }
    }

    /**
     * Copies the samples of a decoded tile into the output frame of a tile list.
     *
     * @param src the picture of the camera frame
     * @param x0 the left column of the tile in luma samples
     * @param y0 the top row of the tile in luma samples
     * @param w the tile width in luma samples
     * @param h the tile height in luma samples
     * @param dst the output frame
     * @param dx the left column of the destination in luma samples
     * @param dy the top row of the destination in luma samples
     */
    private static void copyTile(Av1Picture src, int x0, int y0, int w, int h, Av1Picture dst, int dx, int dy) {
        for (var y = 0; y < h; y++) {
            System.arraycopy(src.data[0], (y0 + y) * src.stride[0] + x0, dst.data[0], (dy + y) * dst.stride[0] + dx, w);
        }
        if (src.layout == SequenceHeader.LAYOUT_I400) {
            return;
        }
        var ssHor = src.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var ssVer = src.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var cw = w >> ssHor;
        var ch = h >> ssVer;
        for (var plane = 1; plane < 3; plane++) {
            for (var y = 0; y < ch; y++) {
                System.arraycopy(src.data[plane], ((y0 >> ssVer) + y) * src.stride[1] + (x0 >> ssHor), dst.data[plane],
                        ((dy >> ssVer) + y) * dst.stride[1] + (dx >> ssHor), cw);
            }
        }
    }

    /**
     * Parses a tile group header and collects its tile data.
     *
     * @param data the buffer of the packet
     */
    private void parseTileGroup(byte[] data) {
        var hdr = frameHdr;
        var c = collected;
        var n = hdr.tilingCols * hdr.tilingRows;
        var haveTilePos = n > 1 && gb.bit() != 0;
        int start;
        int end;
        if (haveTilePos) {
            var bits = hdr.tilingLog2Cols + hdr.tilingLog2Rows;
            start = gb.bits(bits);
            end = gb.bits(bits);
        } else {
            start = 0;
            end = n - 1;
        }
        gb.byteAlign();
        if (gb.error()) {
            throw malformed("truncated tile group header");
        }
        if (start > end || start != nTiles || end >= n) {
            c.nTileData = 0;
            nTiles = 0;
            throw malformed("tile groups out of order");
        }
        var i = c.nTileData;
        if (i == c.tileData.length) {
            growTileGroups(c, i * 2);
        }
        c.tileData[i] = data;
        c.tileOffset[i] = gb.position();
        c.tileSize[i] = gb.end() - gb.position();
        c.tileStart[i] = start;
        c.tileEnd[i] = end;
        c.nTileData = i + 1;
        nTiles += 1 + end - start;
    }

    /**
     * Grows the tile group arrays of a frame.
     *
     * @param f the frame
     * @param capacity the new capacity
     */
    private static void growTileGroups(FrameContext f, int capacity) {
        f.tileData = Arrays.copyOf(f.tileData, capacity);
        f.tileOffset = Arrays.copyOf(f.tileOffset, capacity);
        f.tileSize = Arrays.copyOf(f.tileSize, capacity);
        f.tileStart = Arrays.copyOf(f.tileStart, capacity);
        f.tileEnd = Arrays.copyOf(f.tileEnd, capacity);
    }

    /**
     * Moves the collected tile groups to the frame that decodes them.
     *
     * @param f the frame
     */
    private void takeTileGroups(FrameContext f) {
        var c = collected;
        var n = c.nTileData;
        if (f.tileData.length < n) {
            growTileGroups(f, c.tileData.length);
        }
        for (var i = 0; i < n; i++) {
            f.tileData[i] = c.tileData[i];
            f.tileOffset[i] = c.tileOffset[i];
            f.tileSize[i] = c.tileSize[i];
            f.tileStart[i] = c.tileStart[i];
            f.tileEnd[i] = c.tileEnd[i];
            c.tileData[i] = null;
        }
        f.nTileData = n;
        c.nTileData = 0;
    }

    /**
     * Shows a previously decoded frame ({@code show_existing_frame}).
     */
    private void showExisting() {
        var idx = frameHdr.existingFrameIdx;
        var picture = refPic[idx];
        if (picture == null) {
            throw malformed("show_existing_frame of an empty slot");
        }
        queueOutput(picture.retain(), picture.frame);
        if (refHdr[idx].frameType == FrameHeader.KEY) {
            for (var i = 0; i < 8; i++) {
                if (i == idx) {
                    continue;
                }
                if (refPic[i] != null) {
                    refPic[i].release();
                }
                refPic[i] = picture.retain();
                refHdr[i] = refHdr[idx];
                assignSlot(i, refCdf[idx], refSegmap[idx], null);
            }
        }
    }

    /**
     * Returns the default CDFs of a quantizer index.
     *
     * @param qidx the base quantizer index
     * @return the shared default context
     */
    private char[] defaults(int qidx) {
        var qcat = Cdf.qcat(qidx);
        var cdf = defaultCdf[qcat];
        if (cdf == null) {
            cdf = new char[Cdf.SIZE];
            Cdf.loadDefaults(cdf, qcat);
            defaultCdf[qcat] = cdf;
        }
        return cdf;
    }

    /**
     * Returns the coded width a reference slot's frame had.
     *
     * @param slot the reference slot, from 0 to 7
     * @return the width
     */
    private int refCodedWidth(int slot) {
        return refHdr[slot].codedWidth;
    }

    /**
     * Sets up a frame whose tiles were all collected ({@code dav1d_submit_frame}), updates the
     * reference slots and decodes it, before returning or concurrently with later frames.
     *
     * @param hdr the frame header
     */
    private void submitFrame(FrameHeader hdr) {
        var async = asynchronous();
        if (!async) {
            while (!inFlight.isEmpty()) {
                retireDeferring();
            }
        }
        var job = idle.poll();
        if (job == null) {
            job = new FrameJob();
        }
        job.frame = ++submitted;
        var f = job.f;
        var seq = seqHdr;
        var bpc = 8 + 2 * seq.hbd;
        f.seqHdr = seq;
        f.frameHdr = hdr;
        Av1Picture srCur = null;
        Av1Picture cur = null;
        var retained = 0;
        var obtained = false;
        try {
            for (var i = 0; i < 7; i++) {
                f.refp[i] = null;
                f.refMvs[i] = null;
            }
            if (hdr.isInterOrSwitch()) {
                if (hdr.primaryRefFrame != FrameHeader.PRIMARY_REF_NONE && refPic[hdr.refidx[hdr.primaryRefFrame]] == null) {
                    throw malformed("missing primary reference");
                }
                for (var i = 0; i < 7; i++) {
                    var refidx = hdr.refidx[i];
                    var ref = refPic[refidx];
                    if (ref == null || hdr.codedWidth * 2 < ref.w || hdr.height * 2 < ref.h
                            || hdr.codedWidth > ref.w * 16 || hdr.height > ref.h * 16 || seq.layout != ref.layout
                            || bpc != ref.bpc) {
                        throw malformed("missing or incompatible reference");
                    }
                    f.refp[i] = ref.retain();
                    retained = i + 1;
                    if (hdr.codedWidth != ref.w || hdr.height != ref.h) {
                        f.svcScale[i * 2] = scaleFac(ref.w, hdr.codedWidth);
                        f.svcScale[i * 2 + 1] = scaleFac(ref.h, hdr.height);
                        f.svcStep[i * 2] = (f.svcScale[i * 2] + 8) >> 4;
                        f.svcStep[i * 2 + 1] = (f.svcScale[i * 2 + 1] + 8) >> 4;
                    } else {
                        f.svcScale[i * 2] = 0;
                        f.svcScale[i * 2 + 1] = 0;
                    }
                    var g = hdr.gmv[i];
                    f.gmvWarpAllowed[i] = g.type > WarpedMotionParams.TRANSLATION && !hdr.forceIntegerMv
                            && !WarpMv.getShearParams(g) && f.svcScale[i * 2] == 0;
                }
            }
            if (hdr.primaryRefFrame == FrameHeader.PRIMARY_REF_NONE) {
                f.inCdf = defaults(hdr.quantYac);
            } else {
                var cdf = refCdf[hdr.refidx[hdr.primaryRefFrame]];
                f.inCdf = cdf != null ? cdf : defaults(hdr.quantYac);
            }
            if (hdr.refreshContext) {
                var outCdf = freeCdf.poll();
                f.outCdf = outCdf != null ? outCdf : new char[Cdf.SIZE];
            } else {
                f.outCdf = null;
            }
            srCur = pool.obtain(hdr.upscaledWidth, hdr.height, seq.layout, bpc);
            cur = hdr.codedWidth != hdr.upscaledWidth ? pool.obtain(hdr.codedWidth, hdr.height, seq.layout, bpc) : srCur;
            obtained = true;
            srCur.progress.start();
            srCur.frame = job.frame;
            srCur.frameHdr = hdr;
            srCur.seqHdr = seq;
            srCur.pts = pts;
            srCur.duration = duration;
            cur.frameHdr = hdr;
            cur.seqHdr = seq;
            f.cur = cur;
            f.srCur = srCur;
            setupGeometry(f, hdr, seq, cur, srCur, async);
            setupMotion(f, hdr);
            setupSegmap(f, hdr);
            FrameDecoder.init(f, limits);
            takeTileGroups(f);
        } catch (RuntimeException exception) {
            for (var i = 0; i < retained; i++) {
                f.refp[i].release();
                f.refp[i] = null;
            }
            if (cur != null && cur != srCur) {
                cur.release();
            }
            if (srCur != null) {
                srCur.progress.fail();
                srCur.progress.finish();
                srCur.release();
            }
            f.cur = null;
            f.srCur = null;
            f.nTileData = 0;
            collected.nTileData = 0;
            submitted--;
            idle.add(job);
            if (obtained) {
                for (var i = 0; i < 8; i++) {
                    if ((hdr.refreshFrameFlags & (1 << i)) != 0) {
                        clearSlot(i);
                    }
                }
            }
            throw exception;
        }
        var refresh = hdr.refreshFrameFlags;
        for (var i = 0; i < 8; i++) {
            if ((refresh & (1 << i)) != 0) {
                if (refPic[i] != null) {
                    refPic[i].release();
                }
                refPic[i] = srCur.retain();
                refHdr[i] = hdr;
                assignSlot(i, hdr.refreshContext ? f.outCdf : f.inCdf, f.curSegmap, hdr.allowIntrabc ? null : f.mvs);
                System.arraycopy(refpoc, 0, refRefpoc[i], 0, 7);
            }
        }
        if (hdr.showFrame) {
            queueOutput(srCur.retain(), job.frame);
        }
        f.frameThreaded = async;
        inFlight.addLast(job);
        if (!f.twoPass) {
            job.decodeSinglePass();
            retireOldest();
            return;
        }
        job.resetSchedule();
        if (hdr.isInterOrSwitch()) {
            if (hdr.primaryRefFrame != FrameHeader.PRIMARY_REF_NONE) {
                job.cdfDep = f.refp[hdr.primaryRefFrame].frame;
            }
            for (var n = 0; n < f.rf.nMfmvs; n++) {
                job.addMotionDep(f.refp[f.rf.mfmvRef[n]].frame);
            }
        }
        if (async) {
            job.copyTiles();
        }
        scheduler.submit(job);
        if (!async) {
            retireOldest();
            return;
        }
        while (inFlight.size() >= frameDelay) {
            retireDeferring();
        }
    }

    /**
     * Retires the oldest frame in flight as {@link #retireOldest()} does, keeping its failure for
     * the end of the call ({@link #deferred}) so that the call still submits the rest of its
     * temporal unit.
     */
    private void retireDeferring() {
        try {
            retireOldest();
        } catch (MediaException failure) {
            if (deferred == null) {
                deferred = failure;
            }
        }
    }

    /**
     * Waits for the oldest frame in flight, helping with the decoding of the frames in flight
     * meanwhile, and retires it.
     *
     * @throws MediaException.Malformed if the frame is corrupt or predicts from a corrupt frame
     */
    private void retireOldest() {
        var job = inFlight.pollFirst();
        scheduler.await(job);
        MediaException error = job.error;
        var srCur = job.f.srCur;
        if (error == null && job.f.frameHdr.isInterOrSwitch()) {
            for (var i = 0; i < 7; i++) {
                if (job.f.refp[i].progress.failed()) {
                    error = malformed("reference frame is corrupt");
                    srCur.progress.fail();
                    break;
                }
            }
        }
        if (error != null) {
            for (var i = 0; i < 8; i++) {
                if (refPic[i] == srCur) {
                    clearSlot(i);
                }
            }
        }
        retire(job);
        if (error != null) {
            throw error;
        }
    }

    /**
     * Releases what a decoded frame held and makes its job reusable.
     *
     * @param job the retired frame
     */
    private void retire(FrameJob job) {
        var f = job.f;
        for (var i = 0; i < 7; i++) {
            if (f.refp[i] != null) {
                f.refp[i].release();
                f.refp[i] = null;
            }
        }
        if (f.cur != f.srCur) {
            f.cur.release();
        }
        f.srCur.release();
        f.cur = null;
        f.srCur = null;
        f.nTileData = 0;
        if (job.frame > retired) {
            retired = job.frame;
        }
        releaseDropped();
        idle.add(job);
    }

    /**
     * Returns a reference scale factor in 1/16384 units.
     *
     * @param refSz the reference size
     * @param thisSz the current size
     * @return the factor
     */
    private static int scaleFac(int refSz, int thisSz) {
        return (int) ((((long) refSz << 14) + (thisSz >> 1)) / thisSz);
    }

    /**
     * Returns the initial super-resolution position ({@code get_upscale_x0}).
     *
     * @param inW the coded width
     * @param outW the upscaled width
     * @param step the position step
     * @return the initial position
     */
    private static int upscaleX0(int inW, int outW, int step) {
        var err = outW * step - (inW << 14);
        var x0 = (-((outW - inW) << 13) + (outW >> 1)) / outW + 128 - (err / 2);
        return x0 & 0x3fff;
    }

    /**
     * Derives the block grid geometry, super-resolution parameters and decoding mode of the frame.
     *
     * @param f the frame
     * @param hdr the frame header
     * @param seq the sequence header
     * @param cur the coded picture
     * @param srCur the upscaled picture
     * @param async whether the frame is decoded concurrently with others
     */
    private void setupGeometry(FrameContext f, FrameHeader hdr, SequenceHeader seq, Av1Picture cur, Av1Picture srCur,
                               boolean async) {
        if (hdr.codedWidth != hdr.upscaledWidth) {
            f.resizeStep[0] = scaleFac(cur.w, srCur.w);
            var ssHor = cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            var inCw = (cur.w + ssHor) >> ssHor;
            var outCw = (srCur.w + ssHor) >> ssHor;
            f.resizeStep[1] = scaleFac(inCw, outCw);
            f.resizeStart[0] = upscaleX0(cur.w, srCur.w, f.resizeStep[0]);
            f.resizeStart[1] = upscaleX0(inCw, outCw, f.resizeStep[1]);
        }
        var sb128 = seq.sb128 ? 1 : 0;
        f.w4 = (hdr.codedWidth + 3) >> 2;
        f.h4 = (hdr.height + 3) >> 2;
        f.bw = ((hdr.codedWidth + 7) >> 3) << 1;
        f.bh = ((hdr.height + 7) >> 3) << 1;
        f.sb128w = (f.bw + 31) >> 5;
        f.sb128h = (f.bh + 31) >> 5;
        f.sbShift = 4 + sb128;
        f.sbStep = 16 << sb128;
        f.sbh = (f.bh + f.sbStep - 1) >> f.sbShift;
        f.b4Stride = (f.bw + 31) & ~31;
        f.bitdepthMax = (1 << cur.bpc) - 1;
        f.haveTt = threaded(hdr, async);
        f.twoPass = f.haveTt;
    }

    /**
     * Sets up the temporal motion state of the frame.
     *
     * @param f the frame
     * @param hdr the frame header
     */
    private void setupMotion(FrameContext f, FrameHeader hdr) {
        if (hdr.isInterOrSwitch() || hdr.allowIntrabc) {
            var size = f.sb128h * 16 * (f.b4Stride >> 1);
            f.mvs = takeMvs(size);
            if (!hdr.allowIntrabc) {
                for (var i = 0; i < 7; i++) {
                    refpoc[i] = f.refp[i].frameHdr.frameOffset;
                }
            } else {
                Arrays.fill(refpoc, 0);
            }
            System.arraycopy(refpoc, 0, f.refpoc, 0, 7);
            if (hdr.useRefFrameMvs) {
                for (var i = 0; i < 7; i++) {
                    var refidx = hdr.refidx[i];
                    var refW = ((refCodedWidth(refidx) + 7) >> 3) << 1;
                    var refH = ((f.refp[i].h + 7) >> 3) << 1;
                    f.refMvs[i] = refMvs[refidx] != null && refW == f.bw && refH == f.bh ? refMvs[refidx] : null;
                    System.arraycopy(refRefpoc[refidx], 0, f.refrefpoc[i], 0, 7);
                }
            }
        } else {
            f.mvs = null;
            Arrays.fill(refpoc, 0);
        }
    }

    /**
     * Sets up the segmentation maps of the frame.
     *
     * @param f the frame
     * @param hdr the frame header
     */
    private void setupSegmap(FrameContext f, FrameHeader hdr) {
        if (!hdr.segmentationEnabled) {
            f.curSegmap = null;
            f.prevSegmap = null;
            return;
        }
        f.prevSegmap = null;
        if (hdr.segmentationTemporal || !hdr.segmentationUpdateMap) {
            var priRef = hdr.primaryRefFrame;
            var refW = ((refCodedWidth(hdr.refidx[priRef]) + 7) >> 3) << 1;
            var refH = ((f.refp[priRef].h + 7) >> 3) << 1;
            if (refW == f.bw && refH == f.bh) {
                f.prevSegmap = refSegmap[hdr.refidx[priRef]];
            }
        }
        var size = f.b4Stride * 32 * f.sb128h;
        if (!hdr.segmentationUpdateMap && f.prevSegmap != null) {
            f.curSegmap = f.prevSegmap;
        } else {
            f.curSegmap = takeSegmap(size);
        }
    }

    /**
     * Returns a zeroed temporal motion buffer of the given size, recycled when possible.
     *
     * @param size the number of 8x8 blocks
     * @return the buffer
     */
    private RefMvs.Temporal takeMvs(int size) {
        for (var it = freeMvs.iterator(); it.hasNext(); ) {
            var mvs = it.next();
            if (mvs.size() == size) {
                it.remove();
                Arrays.fill(mvs.mv, 0);
                Arrays.fill(mvs.ref, (byte) 0);
                return mvs;
            }
        }
        freeMvs.clear();
        LimitChecks.checkAllocation(limits, size * 5L);
        return new RefMvs.Temporal(size);
    }

    /**
     * Returns a zeroed segmentation map of the given size, recycled when possible.
     *
     * @param size the number of 4x4 blocks
     * @return the map
     */
    private byte[] takeSegmap(int size) {
        for (var it = freeSegmap.iterator(); it.hasNext(); ) {
            var segmap = it.next();
            if (segmap.length == size) {
                it.remove();
                Arrays.fill(segmap, (byte) 0);
                return segmap;
            }
        }
        freeSegmap.clear();
        LimitChecks.checkAllocation(limits, size);
        return new byte[size];
    }

    /**
     * Applies film grain to a shown picture when its frame carries grain parameters
     * ({@code output_image}); the output frames of tile lists never get any.
     *
     * @param picture the shown picture, released by this method when a grained copy is returned
     * @return the picture to output
     */
    private Av1Picture applyGrain(Av1Picture picture) {
        var hdr = picture.frameHdr;
        if (hdr == null) {
            return picture;
        }
        var fg = hdr.filmGrain;
        if (!filmGrain || !hdr.filmGrainPresent || (fg.numYPoints == 0 && fg.numUvPoints[0] == 0 && fg.numUvPoints[1] == 0
                && !(fg.clipToRestrictedRange && fg.chromaScalingFromLuma))) {
            return picture;
        }
        var out = grainPool.obtain(picture.w, picture.h, picture.layout, picture.bpc);
        try {
            out.frameHdr = hdr;
            out.seqHdr = picture.seqHdr;
            out.pts = picture.pts;
            out.duration = picture.duration;
            grain.apply(picture, out);
        } catch (RuntimeException exception) {
            out.release();
            throw exception;
        } finally {
            picture.release();
        }
        return out;
    }
}
