package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Reads the images of a GIF datastream one at a time, composited onto the canvas by
 * {@link GifCanvas}.
 *
 * <p>Each frame's duration is its graphic control delay times 10 milliseconds, as stored: a zero
 * delay stays zero. The animation ends at the trailer, at the end of the input, after a truncated
 * image, or at bytes that introduce no known block. A failure while reading or decompressing an
 * image is reported by the call that reaches that image, after the images before it, as reading
 * them one at a time would. Instances are not thread-safe.
 *
 * @implNote This implementation reads images ahead in groups when the canvas is large enough and
 * decompresses the LZW data of the next group on the shared threads while the current group is
 * composited in order. The read-ahead holds at most two groups of {@link #MAX_GROUP} images and
 * {@link #GROUP_BYTES} color indices each, so memory does not depend on the number of frames.
 */
final class GifAnimationReader implements AnimationReader {
    /**
     * The operation the calls of this reader form, whose computing time decides how long a call keeps
     * its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The largest number of images read ahead and decompressed together.
     */
    private static final int MAX_GROUP = 8;

    /**
     * The most color-index bytes the images of one group may hold together, which bounds the
     * read-ahead on large canvases.
     */
    private static final long GROUP_BYTES = 8L << 20;

    /**
     * The block reader, positioned after the header.
     */
    private final GifReader reader;

    /**
     * The images of the current group, with their decompressed indices.
     */
    private Slot[] slots;

    /**
     * The images of the next group, read ahead and being decompressed, or of the group after the
     * current one once that is consumed.
     */
    private Slot[] spare;

    /**
     * The number of images of the next group.
     */
    private int spareCount;

    /**
     * The decompression of the next group, or {@code null} when no group is pending.
     */
    private ParallelBands.Pending<Void> pending;

    /**
     * The compositing canvas.
     */
    private final GifCanvas canvas;

    /**
     * The limits the frame count is checked against.
     */
    private final MediaLimits limits;

    /**
     * The summary of the datastream.
     */
    private final GifSummary summary;

    /**
     * The number of images of the current group.
     */
    private int count;

    /**
     * The group position of the next image to composite.
     */
    private int position;

    /**
     * The number of frames rendered.
     */
    private int index;

    /**
     * The display time of the current frame, in milliseconds.
     */
    private int duration;

    /**
     * The sum of the durations of the frames returned so far, in microseconds.
     */
    private long elapsedMicros;

    /**
     * Whether the animation has ended or failed.
     */
    private boolean ended;

    /**
     * Constructs a reader over a datastream whose header has been read.
     *
     * @param reader the block reader, positioned after the header
     * @param summary the summary produced by {@link GifSummary#scan}
     * @param limits the limits checked while reading
     */
    GifAnimationReader(GifReader reader, GifSummary summary, MediaLimits limits) {
        this.reader = reader;
        this.summary = summary;
        this.limits = limits;
        this.canvas = new GifCanvas(summary.width(), summary.height(), summary.hasAlpha(), limits);
        var area = (long) summary.width() * summary.height();
        var ahead = (int) Math.min(Math.min(MAX_GROUP, summary.frames()), GROUP_BYTES / Math.max(1, area));
        var group = ParallelBands.bands(ahead, area * ahead);
        this.slots = slots(Math.max(1, group));
        this.spare = group > 1 ? slots(group) : null;
    }

    /**
     * Creates the empty slots of a group.
     *
     * @param size the number of slots
     * @return the slots
     */
    private static Slot[] slots(int size) {
        var result = new Slot[size];
        for (var s = 0; s < size; s++) {
            result[s] = new Slot();
        }
        return result;
    }

    @Override
    public int width() {
        return summary.width();
    }

    @Override
    public int height() {
        return summary.height();
    }

    /**
     * Returns how many times the animation plays in total.
     *
     * <p>The {@code NETSCAPE2.0} or {@code ANIMEXTS1.0} extension stores a repeat count: a stored
     * {@code n > 0} plays the animation {@code n + 1} times and a stored {@code 0} plays it forever. A
     * datastream without the extension plays once.
     *
     * @return the number of plays, {@code 0} meaning forever
     */
    @Override
    public int loopCount() {
        var repeats = summary.loopCount();
        if (repeats < 0) {
            return 1;
        }
        return repeats == 0 ? 0 : repeats + 1;
    }

    @Override
    public int frameCount() {
        return summary.frames();
    }

    @Override
    public AnimationFrame read() {
        if (!advance()) {
            return null;
        }
        var micros = duration * 1000L;
        var frame = new AnimationFrame(canvas.picture(), elapsedMicros, micros);
        elapsedMicros += micros;
        return frame;
    }

    /**
     * Composites the next frame onto the canvas.
     *
     * @return {@code true} when a frame was rendered, {@code false} at the end of the animation
     */
    private boolean advance() {
        try (var _ = ParallelBands.operation(operation)) {
            if (ended) {
                return false;
            }
            try {
                if (slots.length == 1) {
                    return nextAlone();
                }
                if (position == count && !readGroup()) {
                    ended = true;
                    return false;
                }
                var slot = slots[position++];
                if (slot.readFailure != null) {
                    throw slot.readFailure;
                }
                index++;
                LimitChecks.checkFrames(limits, index);
                var frame = slot.frame;
                canvas.begin(frame);
                if (slot.decodeFailure != null) {
                    throw slot.decodeFailure;
                }
                if (slot.decoded > 0) {
                    canvas.draw(frame, slot.indices, slot.decoded);
                }
                duration = frame.delay * 10;
                return true;
            } catch (RuntimeException exception) {
                ended = true;
                throw Guards.translate("GIF frame", exception);
            }
        }
    }

    /**
     * Reads, decompresses and composites the next image on the calling thread.
     *
     * @return {@code false} when the animation has ended
     */
    private boolean nextAlone() {
        var frame = slots[0].frame;
        if (!reader.nextFrame(frame, true)) {
            ended = true;
            return false;
        }
        index++;
        LimitChecks.checkFrames(limits, index);
        canvas.draw(frame);
        duration = frame.delay * 10;
        return true;
    }

    /**
     * Makes the next group of images current: waits for the group read ahead, or reads and
     * decompresses one when none is pending, then reads the group after it and starts
     * decompressing that one on the helper threads.
     *
     * <p>Reading stops at the end of the animation or at the first image that fails to read,
     * whose failure is kept in its slot.
     *
     * @return {@code false} when no image is left
     */
    private boolean readGroup() {
        if (pending == null) {
            count = read(slots);
            if (count == 0) {
                return false;
            }
            var images = count;
            var group = slots;
            ParallelBands.run(images, band -> group[band].decode(limits));
        } else {
            var handle = pending;
            pending = null;
            handle.join();
            var current = slots;
            slots = spare;
            spare = current;
            count = spareCount;
            if (count == 0) {
                return false;
            }
        }
        position = 0;
        if (slots[count - 1].readFailure == null && count == slots.length) {
            spareCount = read(spare);
            if (spareCount > 0) {
                var images = spareCount;
                var group = spare;
                pending = ParallelBands.start(() -> ParallelBands.run(images, band -> group[band].decode(limits)));
            } else {
                pending = null;
            }
        }
        return true;
    }

    /**
     * Reads the images of a group into its slots, stopping at the end of the animation or at the
     * first image that fails to read.
     *
     * @param group the slots
     * @return the number of slots filled, counting one whose image failed to read
     */
    private int read(Slot[] group) {
        var filled = 0;
        for (var s = 0; s < group.length; s++) {
            var slot = group[s];
            slot.readFailure = null;
            slot.decodeFailure = null;
            slot.decoded = 0;
            try {
                if (!reader.nextFrame(slot.frame, true)) {
                    break;
                }
            } catch (RuntimeException exception) {
                slot.readFailure = exception;
                return s + 1;
            }
            filled = s + 1;
        }
        return filled;
    }

    /**
     * Ends the animation, first canceling the decompression of a group read ahead, or waiting for
     * it when a helper thread has already started it; the source is not closed.
     */
    @Override
    public void close() {
        ended = true;
        if (pending != null) {
            var handle = pending;
            pending = null;
            handle.cancel();
        }
    }

    /**
     * One image of a group read ahead, with its decompressed color indices.
     */
    private static final class Slot {
        /**
         * The image.
         */
        private final GifFrame frame;

        /**
         * The decompressor, owned by the slot so that slots decompress concurrently.
         */
        private final GifLzwDecoder lzw;

        /**
         * The color indices, grown as needed.
         */
        private byte[] indices;

        /**
         * The number of indices the LZW data produced.
         */
        private int decoded;

        /**
         * The failure that stopped reading this image, or {@code null}.
         */
        private RuntimeException readFailure;

        /**
         * The failure that stopped decompressing this image, or {@code null}.
         */
        private RuntimeException decodeFailure;

        /**
         * Constructs an empty slot.
         */
        Slot() {
            this.frame = new GifFrame();
            this.lzw = new GifLzwDecoder();
            this.indices = new byte[0];
        }

        /**
         * Decompresses the image's LZW data, keeping a failure instead of throwing it.
         *
         * @param limits the limits the index buffer is checked against
         */
        void decode(MediaLimits limits) {
            if (readFailure != null) {
                return;
            }
            try {
                var total = (long) frame.width * frame.height;
                if (total == 0) {
                    return;
                }
                if (indices.length < total) {
                    LimitChecks.checkAllocation(limits, total);
                    indices = new byte[(int) total];
                }
                decoded = lzw.decode(frame.data, frame.dataLength, frame.minimumCodeSize, indices, (int) total);
            } catch (RuntimeException exception) {
                decodeFailure = exception;
            }
        }
    }
}
