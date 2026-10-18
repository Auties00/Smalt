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
 * with film grain applied; the caller releases each one.
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
         * has no helper threads; a test hook for the two-pass path.
         */
        FORCED
    }

    /**
     * The largest frame delay {@link #frameDelay(int)} accepts.
     */
    public static final int MAX_FRAME_DELAY = 64;

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
            decode(config, 4, config.length - 4, 0, 0, out);
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
        this.pts = pts;
        this.duration = duration;
        shown.clear();
        var unitStart = outputs.size();
        try {
            var pos = offset;
            var end = offset + length;
            while (pos < end) {
                pos += parseObu(data, pos, end - pos);
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
     * Discards the frames in flight, the frames waiting to be shown, the references and the
     * decoding state.
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
        switch (type) {
            case Obu.OBU_SEQ_HDR -> parseSequenceHeader();
            case Obu.OBU_REDUNDANT_FRAME_HDR -> {
                if (frameHdr == null) {
                    parseFrameHeader(temporalId, spatialId, false);
                }
            }
            case Obu.OBU_FRAME, Obu.OBU_FRAME_HDR -> {
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
                if (frameHdr == null) {
                    throw malformed("tile group without frame header");
                }
                parseTileGroup(data);
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
     * ({@code output_image}).
     *
     * @param picture the shown picture, released by this method when a grained copy is returned
     * @return the picture to output
     */
    private Av1Picture applyGrain(Av1Picture picture) {
        var hdr = picture.frameHdr;
        var fg = hdr.filmGrain;
        if (!hdr.filmGrainPresent || (fg.numYPoints == 0 && fg.numUvPoints[0] == 0 && fg.numUvPoints[1] == 0
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
