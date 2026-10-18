package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Decodes one coded VOP on the module's shared helper threads while the decoder thread moves on to
 * the next packet: parses its macroblocks, waits for the pictures it predicts from, reconstructs it
 * in parallel bands of macroblock rows, extends its borders and makes the cropped copy that the
 * decoder hands to its consumer.
 *
 * <p>The job owns everything it reads from the packet: a private {@link BitReader} over the VOP's
 * bytes, a private copy of the {@link Vop} header and a private {@link VopParser}. It reads the
 * frames it predicts from only after they are published: the macroblock side information of the
 * future picture of a B-VOP (direct mode) once that picture is parsed, the samples of every
 * reference once it is final. Jobs are started in decoding order, so a job only ever waits for
 * jobs that were started before it.
 *
 * <p>A job always publishes its frame as final, also when it fails, so that no job or decoder
 * thread waits for it forever; the failure is kept for {@link #failure()}. The cropped copy is
 * made after the frame is published, so that the pictures that predict from the frame do not wait
 * for it.
 */
final class VopJob implements Runnable {
    /**
     * The resources the jobs of one decoder share: the parsers, bit readers and reconstructors a
     * job takes while it runs and gives back when it finishes.
     */
    static final class Pools {
        /**
         * The idle parsers.
         */
        final ConcurrentLinkedQueue<VopParser> parsers = new ConcurrentLinkedQueue<>();

        /**
         * The idle bit readers.
         */
        final ConcurrentLinkedQueue<BitReader> readers = new ConcurrentLinkedQueue<>();

        /**
         * The idle reconstructors.
         */
        final ConcurrentLinkedQueue<Reconstructor> reconstructors = new ConcurrentLinkedQueue<>();
    }

    /**
     * The frame the VOP is decoded into.
     */
    private final Frame frame;

    /**
     * The frame the VOP predicts forward from, or {@code null}.
     */
    private final Frame forward;

    /**
     * The frame the VOP predicts backward from, or {@code null}.
     */
    private final Frame backward;

    /**
     * The VOP header.
     */
    private final Vop vop;

    /**
     * The layer.
     */
    private final VideoObjectLayer layer;

    /**
     * The temporal distance from the past to the B-VOP.
     */
    private final long trb;

    /**
     * The temporal distance from the past to the future picture.
     */
    private final long trd;

    /**
     * The frame period of the stream.
     */
    private final long framePeriod;

    /**
     * The cap on the number of bands the reconstruction is split into.
     */
    private final int threads;

    /**
     * The shared resources.
     */
    private final Pools pools;

    /**
     * The bytes of the VOP, loaded when the job is created.
     */
    private BitReader reader;

    /**
     * The handle of the started job.
     */
    private ParallelBands.Pending<Void> pending;

    /**
     * The failure of the job, or {@code null}.
     */
    private volatile Throwable failure;

    /**
     * Whether the job ran to its end.
     */
    private volatile boolean complete;

    /**
     * Creates a job and takes the references it reads: the user counts of the frames rise by one
     * for each use and fall when the job finishes.
     *
     * @param frame the frame to decode into
     * @param forward the forward reference, or {@code null}
     * @param backward the backward reference, or {@code null}
     * @param vop the VOP header, copied
     * @param layer the layer of the VOP
     * @param data the array holding the VOP
     * @param offset the index of the VOP's first byte
     * @param length the length of the VOP in bytes
     * @param trb the temporal distance from the past to the B-VOP
     * @param trd the temporal distance from the past to the future picture
     * @param framePeriod the frame period
     * @param threads the cap on the reconstruction bands
     * @param pools the shared resources
     */
    VopJob(Frame frame, Frame forward, Frame backward, Vop vop, VideoObjectLayer layer, byte[] data, int offset,
           int length, long trb, long trd, long framePeriod, int threads, Pools pools) {
        this.frame = frame;
        this.forward = forward;
        this.backward = backward;
        this.vop = vop.copy();
        this.layer = layer;
        this.trb = trb;
        this.trd = trd;
        this.framePeriod = framePeriod;
        this.threads = threads;
        this.pools = pools;
        var bits = pools.readers.poll();
        if (bits == null) {
            bits = new BitReader();
        }
        bits.load(data, offset, length);
        bits.seek(vop.dataPosition);
        this.reader = bits;
        frame.users().incrementAndGet();
        if (forward != null) {
            forward.users().incrementAndGet();
        }
        if (backward != null) {
            backward.users().incrementAndGet();
        }
        frame.beginDecode();
    }

    /**
     * Starts the job on a helper thread.
     */
    void start() {
        pending = ParallelBands.start(this);
    }

    /**
     * Waits for the job to finish, running it on the calling thread if no helper has taken it.
     */
    void join() {
        pending.join();
    }

    /**
     * Returns whether the job finished.
     *
     * @return {@code true} once the frame is final and its cropped copy is made
     */
    boolean finished() {
        return complete;
    }

    /**
     * Returns the failure of the finished job.
     *
     * @return the failure, or {@code null}
     */
    Throwable failure() {
        return failure;
    }

    @Override
    public void run() {
        VopParser parser = null;
        try {
            parser = pools.parsers.poll();
            if (parser == null) {
                parser = new VopParser();
            }
            parser.configure(layer);
            if (backward != null) {
                backward.awaitParsed();
            }
            var corrupt = parser.parse(reader, vop, frame, backward, trb, trd, framePeriod);
            frame.damaged = corrupt;
            frame.publishParsed();
            if (forward != null) {
                forward.awaitDone();
            }
            if (backward != null) {
                backward.awaitDone();
            }
            reconstruct(parser);
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            if (parser != null && failure == null) {
                pools.parsers.add(parser);
            }
            pools.readers.add(reader);
            reader = null;
            frame.publishDone();
            if (forward != null) {
                forward.users().decrementAndGet();
            }
            if (backward != null) {
                backward.users().decrementAndGet();
            }
        }
        try {
            if (failure == null) {
                frame.picture = DecoderCore.render(frame, layer, frame.picture);
            }
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            frame.users().decrementAndGet();
            complete = true;
        }
    }

    /**
     * Reconstructs the macroblocks the parser produced, in bands of rows.
     *
     * @param parser the parser that holds the macroblocks
     */
    private void reconstruct(VopParser parser) {
        var dsp = Mpeg4Dsp.select();
        var blocks = parser.blocks();
        var gmc = vop.codingType == Vop.S ? parser.globalMotion() : null;
        var rows = frame.mbHeight;
        var bands = Reconstructor.bandCount(threads, rows, (long) rows * 16 * frame.mbWidth * 16);
        var rounding = vop.roundingType;
        if (bands <= 1) {
            reconstructBand(blocks, dsp, gmc, rounding, 0, rows);
            return;
        }
        ParallelBands.run(bands, band -> reconstructBand(blocks, dsp, gmc, rounding,
                ParallelBands.start(band, bands, rows), ParallelBands.start(band + 1, bands, rows)));
    }

    /**
     * Reconstructs a band of macroblock rows with a reconstructor taken from the pool and, for a
     * VOP that other VOPs predict from, extends the borders of the band's rows.
     *
     * @param blocks the macroblocks
     * @param dsp the kernels
     * @param gmc the global motion of an S-VOP, or {@code null}
     * @param rounding the rounding type of the VOP
     * @param first the first row
     * @param end the row after the last
     */
    private void reconstructBand(Macroblocks blocks, Mpeg4Dsp dsp, GlobalMotion gmc, int rounding, int first,
                                 int end) {
        var worker = pools.reconstructors.poll();
        if (worker == null) {
            worker = new Reconstructor();
        }
        try {
            worker.reconstruct(blocks, dsp, frame, forward, backward, gmc, layer, rounding, first, end);
        } finally {
            pools.reconstructors.add(worker);
        }
        if (vop.codingType != Vop.B) {
            frame.extendEdges(layer.mbWidth() * 16, layer.mbHeight() * 16, first, end);
        }
    }
}
