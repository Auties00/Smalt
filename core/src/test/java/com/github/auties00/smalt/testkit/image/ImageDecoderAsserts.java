package com.github.auties00.smalt.testkit.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Dumps;
import com.github.auties00.smalt.testkit.io.Behaviors;
import com.github.auties00.smalt.testkit.io.IoVariant;
import com.github.auties00.smalt.testkit.io.SimulatedSource;
import com.github.auties00.smalt.testkit.io.SimulatedStream;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.MultipleFailuresError;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs the image decoders over one input under every I/O behavior a valid {@link MediaSource} may show,
 * checks that every behavior gives the reference result or a clean failure, and compares a readable dump
 * with its golden file.
 *
 * <p>The decoding flow is the same for every behavior: {@link Images#detect(MediaSource)},
 * {@link Images#probe(MediaSource)}, {@link Images#decode(MediaSource, ImageDecoderOptions)}, a reduced-size
 * decode with {@link ImageDecoderOptions#ofMinimumSize(int, int)} (a quarter of each dimension, which
 * selects the scaled decoding paths of the formats that have them), every frame of
 * {@link Images#openAnimation(MediaSource, ImageDecoderOptions)} for an animated image, every page of
 * {@link Images#openPages(MediaSource, ImageDecoderOptions)} for a multi-image format, and every extra
 * decode added with {@link Builder#decoder(String, Function)} (for example a region decoder). The
 * flow's text, made by {@link PictureDump}, names every picture by the CRC of its samples.
 *
 * <p>{@link Builder#run()} performs:
 * <ol>
 *   <li>the reference flow over {@link MediaSource#of(byte[])}, compared with the golden file
 *       {@code dumps/<name>.dump};</li>
 *   <li>the flow over every {@link IoVariant}, which must give the reference text exactly; over a source
 *       that reads its input once, the steps after the first decode read a new such source each, and a
 *       {@link MediaException.Unsupported} refusal is accepted and recorded in the dump;</li>
 *   <li>the flow twice over one source, which must give the reference text both times without closing
 *       the source;</li>
 *   <li>the flow over sources that fail once at read index {@code k}, for a bounded, evenly spread sample of
 *       the reference read indices, and over sources that fail from {@code k} on, both with whole reads and
 *       with reads cut to random sizes, and, when the flow passes over a stream read once, over such a
 *       stream that fails from a read on: each run gives the reference text or throws
 *       {@link MediaException.Io} caused by the injected fault.</li>
 * </ol>
 * Every flow is bounded by a timeout. Failures of all behaviors are collected and reported together.
 */
public final class ImageDecoderAsserts {
    private ImageDecoderAsserts() {
        throw new AssertionError();
    }

    /**
     * Returns a run of the harness over one input.
     *
     * @param name the dump name, for example {@code "png/basn0g01.png"}; see {@link Dumps#golden(String)}
     * @param data the input
     * @return the builder of the run
     */
    public static Builder of(String name, byte[] data) {
        return new Builder(name, data);
    }

    /**
     * Configures and runs the harness over one input.
     */
    public static final class Builder {
        /**
         * The seed of the chunk sizes of the sources that fail during cut reads.
         */
        private static final long FAULT_SEED = 7;

        /**
         * The largest number of read indices at which a stream read once fails.
         */
        private static final int MAX_STREAM_FAULTS = 12;

        private final String name;
        private final byte[] data;
        private ImageDecoderOptions options = ImageDecoderOptions.defaults();
        private int maxFaultReads = 32;
        private Duration timeout = Duration.ofSeconds(120);
        private boolean dump = true;
        private boolean scaled = true;
        private boolean pages = true;
        private boolean animation = true;
        private final Map<String, Function<MediaSource, ?>> decoders = new LinkedHashMap<>();

        private Builder(String name, byte[] data) {
            Dumps.golden(name);
            this.name = name;
            this.data = Objects.requireNonNull(data, "data");
        }

        /**
         * Sets the decoder options of every decode but the reduced-size one; {@link ImageDecoderOptions#defaults()}
         * by default.
         *
         * @param options the options
         * @return this builder
         */
        public Builder options(ImageDecoderOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Adds a decode to the flow, such as a region decoder; its result is dumped as a picture when it is a
         * {@link com.github.auties00.smalt.image.Picture} or a
         * {@link com.github.auties00.smalt.image.DecodedImage}, and by {@link String#valueOf(Object)}
         * otherwise.
         *
         * @param label the label of the decode in the dump
         * @param decoder decodes a source without closing it
         * @return this builder
         */
        public Builder decoder(String label, Function<MediaSource, ?> decoder) {
            decoders.put(Objects.requireNonNull(label, "label"), Objects.requireNonNull(decoder, "decoder"));
            return this;
        }

        /**
         * Leaves the reduced-size decode out of the flow.
         *
         * @return this builder
         */
        public Builder noScaled() {
            this.scaled = false;
            return this;
        }

        /**
         * Leaves the page reader out of the flow.
         *
         * @return this builder
         */
        public Builder noPages() {
            this.pages = false;
            return this;
        }

        /**
         * Leaves the animation reader out of the flow.
         *
         * @return this builder
         */
        public Builder noAnimation() {
            this.animation = false;
            return this;
        }

        /**
         * Sets the largest number of read indices at which a fault is injected, for each way of delivering
         * reads (whole, cut to random sizes, and a stream read once, which is also capped lower); 32 by default.
         *
         * @param maxFaultReads the bound, at least 0
         * @return this builder
         */
        public Builder maxFaultReads(int maxFaultReads) {
            if (maxFaultReads < 0) {
                throw new IllegalArgumentException("maxFaultReads " + maxFaultReads);
            }
            this.maxFaultReads = maxFaultReads;
            return this;
        }

        /**
         * Sets the time limit of each flow; 120 seconds by default.
         *
         * @param timeout the limit
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Skips the golden dump comparison, for inputs that are not pinned.
         *
         * @return this builder
         */
        public Builder noDump() {
            this.dump = false;
            return this;
        }

        /**
         * Runs every behavior and fails with all the failures found.
         *
         * @throws MultipleFailuresError if any behavior fails
         * @throws AssertionFailedError if the reference flow fails or the golden dump differs
         */
        public void run() {
            var failures = new ArrayList<Throwable>();
            var counting = SimulatedSource.of(data);
            String reference;
            try {
                reference = flow(counting);
            } catch (RuntimeException failure) {
                throw new AssertionFailedError("the reference decode of " + name + " failed: " + failure, failure);
            }
            var reads = counting.reads();
            check(failures, "memory source", () -> same("memory source", reference, flow(MediaSource.of(data))));
            var notes = new StringBuilder();
            var readOnce = new boolean[1];
            for (var variant : IoVariant.alternatives()) {
                check(failures, variant.name(), () -> {
                    try (var source = variant.open(data)) {
                        if (!variant.isReadOnce()) {
                            same(variant.name(), reference, flow(source));
                            return;
                        }
                        try {
                            same(variant.name(), reference, flow(source, () -> variant.open(data)));
                            readOnce[0] |= variant == IoVariant.READ_ONCE;
                        } catch (MediaException.Unsupported refused) {
                            notes.append("refused over ").append(variant).append(": ").append(refused.getMessage())
                                    .append('\n');
                        }
                    }
                });
            }
            check(failures, "re-open", () -> {
                var source = SimulatedSource.of(data, true, SimulatedSource.Chunking.RANDOM, 5);
                same("first decode", reference, flow(source));
                same("second decode over the same source", reference, flow(source));
                if (source.closes() > 0) {
                    throw new AssertionFailedError("decoding closed the source");
                }
            });
            check(failures, "injected faults", () -> faults(failures, reference, reads, readOnce[0]));
            if (dump) {
                check(failures, "golden dump", () -> Dumps.assertMatches(name, "# image dump of " + name
                        + "; regenerate with -Dsmalt.test.updateDumps=true\n" + reference + notes));
            }
            if (!failures.isEmpty()) {
                throw new MultipleFailuresError(name + ": " + failures.size() + " behavior(s) failed", failures);
            }
        }

        /**
         * Runs the flow over sources that fail at a spread of read indices: with whole reads, with reads cut
         * to random sizes (which spread the read indices over the decode of a decoder that buffers its
         * input), and, when the flow passes over a stream read once, over such a stream that fails from a
         * read on.
         */
        private void faults(List<Throwable> failures, String reference, long reads, boolean readOnce) {
            faults(failures, reference, "whole reads", reads,
                    (index, permanent) -> SimulatedSource.failing(data, index, permanent));
            var chunked = SimulatedSource.of(data, true, SimulatedSource.Chunking.RANDOM, FAULT_SEED);
            if (reference.equals(flow(chunked))) {
                faults(failures, reference, "random chunks", chunked.reads(), (index, permanent) -> SimulatedSource
                        .failing(data, SimulatedSource.Chunking.RANDOM, FAULT_SEED, index, permanent));
            }
            if (!readOnce || maxFaultReads == 0) {
                return;
            }
            var stream = SimulatedStream.of(data);
            try (var source = stream.source(true)) {
                flow(source, () -> IoVariant.READ_ONCE.open(data));
            }
            var reported = 0;
            for (var index : Behaviors.faultIndices(stream.reads(), Math.min(maxFaultReads, MAX_STREAM_FAULTS))) {
                try (var source = SimulatedStream.failing(data, index).source(true)) {
                    var problem = fault("stream read once failing from read " + index, reference,
                            () -> flow(source, () -> IoVariant.READ_ONCE.open(data)));
                    if (problem != null && reported++ < 4) {
                        failures.add(problem);
                    }
                }
            }
        }

        private void faults(List<Throwable> failures, String reference, String delivery, long reads,
                            FaultySource factory) {
            var reported = 0;
            for (var index : Behaviors.faultIndices(reads, maxFaultReads)) {
                var source = factory.open(index, false);
                var problem = fault("transient fault at read " + index + " (" + delivery + ")", reference,
                        () -> flow(source));
                if (problem != null && reported++ < 4) {
                    failures.add(problem);
                }
            }
            if (maxFaultReads == 0) {
                return;
            }
            for (var index : new LinkedHashSet<>(List.of(0L, reads / 2, Math.max(0, reads - 1)))) {
                var source = factory.open(index, true);
                var problem = fault("permanent fault from read " + index + " (" + delivery + ")", reference,
                        () -> flow(source));
                if (problem != null) {
                    failures.add(problem);
                }
            }
        }

        private AssertionFailedError fault(String behavior, String reference, Supplier<String> flow) {
            try {
                var text = flow.get();
                if (!text.equals(reference)) {
                    return new AssertionFailedError(behavior + ": the fault was swallowed and the result differs "
                            + Dumps.firstDifference(reference, text));
                }
                return null;
            } catch (MediaException failure) {
                if (Behaviors.isCleanFault(failure)) {
                    return null;
                }
                return new AssertionFailedError(behavior + ": expected MediaException.Io caused by the injected fault"
                        + " but got " + failure, failure);
            } catch (RuntimeException failure) {
                return new AssertionFailedError(behavior + ": " + failure, failure);
            }
        }

        /**
         * Creates a source that fails at a read index.
         */
        @FunctionalInterface
        private interface FaultySource {
            SimulatedSource open(long index, boolean permanent);
        }

        private static void check(List<Throwable> failures, String behavior, Runnable body) {
            try {
                body.run();
            } catch (AssertionFailedError | MultipleFailuresError failure) {
                failures.add(failure);
            } catch (RuntimeException | StackOverflowError failure) {
                failures.add(new AssertionFailedError(behavior + ": " + failure, failure));
            }
        }

        private static void same(String behavior, String reference, String actual) {
            if (!reference.equals(actual)) {
                throw new AssertionFailedError(behavior + ": result differs " + Dumps.firstDifference(reference, actual),
                        reference, actual);
            }
        }

        /**
         * Runs the decoding flow over a source and returns its text.
         */
        private String flow(MediaSource source) {
            return flow(source, null);
        }

        /**
         * Runs the decoding flow and returns its text: detection, probing and the decode share
         * {@code source}, and every later step reads {@code source} again or, when {@code fresh} is not
         * {@code null}, a new source it supplies, as a stream that is read once cannot be read a second
         * time.
         */
        private String flow(MediaSource source, Supplier<MediaSource> fresh) {
            var opened = new ArrayList<MediaSource>();
            Supplier<MediaSource> next = fresh == null ? () -> source : () -> {
                var created = fresh.get();
                opened.add(created);
                return created;
            };
            try {
                return Behaviors.withTimeout(timeout, name, () -> steps(source, next));
            } finally {
                opened.forEach(MediaSource::close);
            }
        }

        private String steps(MediaSource source, Supplier<MediaSource> next) {
            var text = new StringBuilder();
            var format = Images.detect(source);
            text.append("format ").append(format.map(ImageFormat::name).orElse("none")).append('\n');
            var info = Images.probe(source);
            text.append("info ").append(info).append('\n');
            text.append("decode\n").append(PictureDump.image(Images.decode(source, options)));
            if (scaled && info.width() >= 8 && info.height() >= 8) {
                var width = Math.max(1, info.width() / 4);
                var height = Math.max(1, info.height() / 4);
                text.append("scaled ").append(width).append('x').append(height).append('\n')
                        .append(PictureDump.image(Images.decode(next.get(), options.toBuilder()
                                .minSize(width, height).build())));
            }
            if (animation && info.animated()) {
                try (var reader = Images.openAnimation(next.get(), options)) {
                    text.append("animation ").append(reader.width()).append('x').append(reader.height())
                            .append(" loops=").append(reader.loopCount()).append(" frames=")
                            .append(reader.frameCount()).append('\n');
                    var index = 0;
                    for (var frame = reader.read(); frame != null; frame = reader.read()) {
                        text.append("frame ").append(index++).append(" ts=").append(frame.timestampMicros())
                                .append(" dur=").append(frame.durationMicros()).append(' ')
                                .append(PictureDump.picture(frame.picture())).append('\n');
                    }
                }
            }
            if (pages && !info.animated() && (info.format() == ImageFormat.TIFF || info.format() == ImageFormat.ICO
                    || info.frameCount() > 1)) {
                try (var reader = Images.openPages(next.get(), options)) {
                    text.append("pages ").append(reader.pageCount()).append('\n');
                    for (var page = 0; page < reader.pageCount(); page++) {
                        text.append("page ").append(page).append(' ').append(reader.probe(page)).append('\n')
                                .append(PictureDump.image(reader.read(page)));
                    }
                }
            }
            for (var entry : decoders.entrySet()) {
                text.append("decoder ").append(entry.getKey()).append('\n');
                var result = entry.getValue().apply(next.get());
                switch (result) {
                    case DecodedImage image -> text.append(PictureDump.image(image));
                    case Picture picture -> text.append("picture ")
                            .append(PictureDump.picture(picture)).append('\n');
                    case null, default -> text.append(result).append('\n');
                }
            }
            return text.toString();
        }
    }
}
