package com.github.auties00.smalt.testkit.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Dumps;
import com.github.auties00.smalt.testkit.image.PictureDump;
import com.github.auties00.smalt.testkit.io.Behaviors;
import com.github.auties00.smalt.testkit.io.IoVariant;
import com.github.auties00.smalt.testkit.io.SimulatedSource;
import com.github.auties00.smalt.testkit.io.SimulatedStream;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.MultipleFailuresError;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Decodes the first video or audio track of one input with {@link VideoReader} or {@link AudioReader}
 * under every I/O behavior a valid {@link MediaSource} may show, and compares a per-frame CRC dump, in the
 * spirit of FFmpeg's {@code framecrc}, with its golden file {@code dumps/<name>.video.dump} or
 * {@code dumps/<name>.audio.dump}, so that one input can have both.
 *
 * <p>{@link Builder#run()} decodes over {@link MediaSource#of(byte[])} as the reference, then over every
 * {@link IoVariant}, twice over one source, and over sources that fail once or from a read index on (a
 * bounded, evenly spread sample of the reference read indices); every decode must give the reference
 * frames exactly or, for an injected fault, end with {@link MediaException.Io} caused by it after a prefix
 * of the reference frames. Faults are injected into whole reads, into reads cut to random sizes (which
 * spread the read indices over the decode of a demuxer that buffers its input) and into a stream read once.
 * A seekable reader then seeks to 0, a third, two thirds and the end of the presentation and before zero, as
 * {@link VideoReader#seek(long)} and {@link AudioReader#seek(long)} specify, and the dump records where each
 * seek restarts ({@code seek 666666: @10}).
 *
 * <p>A video frame's line holds its timestamps, key flag and the CRC of every plane ({@link PictureDump}).
 * An audio block's line holds its frame count and the CRC of its samples rounded to 16 bits, so that a
 * golden dump stays valid across platforms whose floating-point library functions round differently; the
 * comparisons between behaviors use the CRC of the exact sample bits.
 */
public final class ReaderAsserts {
    private ReaderAsserts() {
        throw new AssertionError();
    }

    /**
     * Returns a run of the harness over the first video track of one input.
     *
     * @param name the dump name; see {@link Dumps#golden(String)}
     * @param data the input
     * @return the builder of the run
     */
    public static Builder video(String name, byte[] data) {
        return new Builder(name, data, true);
    }

    /**
     * Returns a run of the harness over the first audio track of one input.
     *
     * @param name the dump name; see {@link Dumps#golden(String)}
     * @param data the input
     * @return the builder of the run
     */
    public static Builder audio(String name, byte[] data) {
        return new Builder(name, data, false);
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
        private final String golden;
        private final byte[] data;
        private final boolean video;
        private int maxFaultReads = 24;
        private Duration timeout = Duration.ofSeconds(120);
        private boolean dump = true;
        private boolean seeks = true;

        private Builder(String name, byte[] data, boolean video) {
            this.golden = name + (video ? ".video" : ".audio");
            Dumps.golden(golden);
            this.name = name;
            this.data = Objects.requireNonNull(data, "data");
            this.video = video;
        }

        /**
         * Sets the largest number of read indices at which a fault is injected, for each way of delivering
         * reads (whole, cut to random sizes, and a stream read once, which is also capped lower); 24 by default.
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
         * Sets the time limit of each decode; 120 seconds by default.
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
         * Leaves the seek checks out, for readers whose seeking is checked elsewhere.
         *
         * @return this builder
         */
        public Builder noSeeks() {
            this.seeks = false;
            return this;
        }

        /**
         * Runs every behavior and fails with all the failures found.
         *
         * @throws MultipleFailuresError if any behavior fails
         * @throws AssertionFailedError if the reference decode fails or the golden dump differs
         */
        public void run() {
            var failures = new ArrayList<Throwable>();
            var counting = SimulatedSource.of(data);
            var reference = decode(counting);
            if (reference.failure() != null) {
                throw new AssertionFailedError("the reference decode of " + name + " failed: " + reference.failure(),
                        reference.failure());
            }
            var reads = counting.reads();
            check(failures, "memory source", () -> same("memory source", reference, decode(MediaSource.of(data)), true));
            var headers = new LinkedHashMap<String, List<String>>();
            var notes = new StringBuilder();
            var readOnceHeader = new String[1];
            for (var variant : IoVariant.alternatives()) {
                check(failures, variant.name(), () -> {
                    try (var source = variant.open(data)) {
                        var decoded = decode(source);
                        if (variant.isReadOnce() && decoded.failure() instanceof MediaException.Unsupported) {
                            var frames = decoded.exact();
                            if (frames.size() > reference.exact().size()
                                    || !reference.exact().subList(0, frames.size()).equals(frames)) {
                                throw new AssertionFailedError(variant + ": the frames decoded before the refusal "
                                        + decoded.failure().getMessage() + " are not a prefix of the reference");
                            }
                            notes.append("refused over ").append(variant).append(" after ").append(frames.size())
                                    .append(" frames\n");
                            return;
                        }
                        var exactHeader = variant.isSized() && !variant.isReadOnce();
                        same(variant.name(), reference, decoded, exactHeader);
                        if (variant == IoVariant.READ_ONCE) {
                            readOnceHeader[0] = decoded.header();
                        }
                        if (!exactHeader && !decoded.header().equals(reference.header())) {
                            headers.computeIfAbsent(decoded.header(), _ -> new ArrayList<>()).add(variant.name());
                        }
                    }
                });
            }
            for (var entry : headers.entrySet()) {
                notes.append("header over ").append(String.join(", ", entry.getValue())).append(":\n");
                for (var line : entry.getKey().split("\n")) {
                    notes.append("  ").append(line).append('\n');
                }
            }
            check(failures, "re-open", () -> {
                var source = SimulatedSource.of(data, true, SimulatedSource.Chunking.RANDOM, 3);
                same("first decode", reference, decode(source), true);
                same("second decode over the same source", reference, decode(source), true);
                if (source.closes() > 0) {
                    throw new AssertionFailedError("decoding closed the source");
                }
            });
            check(failures, "injected faults", () -> {
                faults(failures, reference, reference.header(), reads,
                        (index, permanent) -> SimulatedSource.failing(data, index, permanent));
                var chunked = SimulatedSource.of(data, true, SimulatedSource.Chunking.RANDOM, FAULT_SEED);
                if (decode(chunked).failure() == null) {
                    faults(failures, reference, reference.header(), chunked.reads(), (index, permanent) ->
                            SimulatedSource.failing(data, SimulatedSource.Chunking.RANDOM, FAULT_SEED, index,
                                    permanent));
                }
                if (readOnceHeader[0] != null && maxFaultReads > 0) {
                    var stream = SimulatedStream.of(data);
                    try (var source = stream.source(true)) {
                        decode(source);
                    }
                    var reported = 0;
                    var bound = Math.min(maxFaultReads, MAX_STREAM_FAULTS);
                    for (var index : Behaviors.faultIndices(stream.reads(), bound)) {
                        Decoded decoded;
                        try (var source = SimulatedStream.failing(data, index).source(true)) {
                            decoded = decode(source);
                        }
                        var problem = fault("stream read once failing from read " + index, reference,
                                readOnceHeader[0], decoded);
                        if (problem != null && reported++ < 4) {
                            failures.add(problem);
                        }
                    }
                }
            });
            var seekLines = new StringBuilder();
            if (seeks) {
                check(failures, "seeks", () -> seekLines.append(Behaviors.withTimeout(timeout, name,
                        () -> video ? new VideoSeeks().run() : new AudioSeeks().run())));
            }
            if (dump) {
                check(failures, "golden dump", () -> Dumps.assertMatches(golden, "# " + (video ? "video" : "audio")
                        + " decode dump of " + name + "; regenerate with -Dsmalt.test.updateDumps=true\n"
                        + reference.header() + String.join("\n", reference.golden()) + "\n" + seekLines + notes));
            }
            if (!failures.isEmpty()) {
                throw new MultipleFailuresError(name + ": " + failures.size() + " behavior(s) failed", failures);
            }
        }

        /**
         * The codecs whose output after a seek {@link AudioReader#seek(long)} does not make sample-exact.
         */
        private static final Set<Codec> APPROXIMATE_SEEKS = EnumSet.of(Codec.OPUS, Codec.AAC, Codec.AMR_NB,
                Codec.AMR_WB, Codec.AC3, Codec.EAC3, Codec.ADPCM_G722, Codec.ADPCM_G726);

        private static String frameLine(VideoFrame frame) {
            return "pts=" + frame.pts() + " dur=" + frame.duration() + " key=" + frame.keyframe() + " "
                    + PictureDump.picture(frame.picture());
        }

        /**
         * Returns the seek targets: 0, a third, two thirds and the end of the presentation, and a time
         * before zero.
         */
        private static List<Long> targets(long span) {
            return List.copyOf(new LinkedHashSet<>(List.of(0L, span / 3, span * 2 / 3, span, -1_000_000L)));
        }

        /**
         * {@return whether {@code ticks} time base units end after {@code micros}, compared exactly}
         */
        private static boolean after(long ticks, Rational timeBase, long micros) {
            var left = BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(timeBase.numerator()))
                    .multiply(BigInteger.valueOf(1_000_000));
            return left.compareTo(BigInteger.valueOf(micros).multiply(BigInteger.valueOf(timeBase.denominator()))) > 0;
        }

        /**
         * Checks {@link VideoReader#seek(long)}: after a seek to each target, the frames read must be the
         * reference frames from the one displayed at the target (a frame is displayed until its duration ends
         * or the next frame starts, with one microsecond of rounding slack either way), none for a target at or
         * past the end and all for a target before zero; and the same seeks
         * chained on one reader, latest first, must start with the same frames.
         */
        private final class VideoSeeks {
            String run() {
                var lines = new ArrayList<String>();
                var ends = new ArrayList<Long>();
                var clipped = new ArrayList<Long>();
                Rational timeBase;
                long duration;
                try (var reader = VideoReader.open(MediaSource.of(data))) {
                    if (!reader.isSeekable()) {
                        return "not seekable\n";
                    }
                    timeBase = reader.track().timeBase();
                    duration = reader.durationMicros();
                    var timestamps = new ArrayList<long[]>();
                    for (var frame = reader.read(); frame != null; frame = reader.read()) {
                        lines.add(frameLine(frame));
                        timestamps.add(new long[]{frame.pts(), frame.duration()});
                    }
                    for (var i = 0; i < timestamps.size(); i++) {
                        var pts = timestamps.get(i)[0];
                        var length = timestamps.get(i)[1];
                        var known = length > 0 && length != Packet.UNKNOWN_DURATION;
                        long last;
                        if (duration > 0) {
                            last = Math.max(pts + 1, microsToTicks(timeBase, duration));
                        } else {
                            last = pts + Math.max(1, i > 0 ? pts - timestamps.get(i - 1)[0] : 1);
                        }
                        var next = i + 1 < timestamps.size() ? timestamps.get(i + 1)[0] : last;
                        ends.add(known ? pts + length : next);
                        clipped.add(known ? Math.min(pts + length, next) : next);
                    }
                }
                if (lines.isEmpty()) {
                    return "";
                }
                var span = duration > 0 ? duration : ticksToMicros(timeBase, ends.getLast());
                var text = new StringBuilder();
                var expected = new LinkedHashMap<Long, List<String>>();
                for (var target : targets(span)) {
                    var got = new ArrayList<String>();
                    try (var reader = VideoReader.open(MediaSource.of(data))) {
                        reader.seek(target);
                        for (var frame = reader.read(); frame != null; frame = reader.read()) {
                            got.add(frameLine(frame));
                        }
                    }
                    var start = lines.size() - got.size();
                    if (start < 0 || !lines.subList(start, lines.size()).equals(got)) {
                        throw new AssertionFailedError("seek to " + target + ": the frames read are not the reference"
                                + " frames from one on (" + got.size() + " frames"
                                + (got.isEmpty() ? "" : ", first " + got.getFirst()) + ")");
                    }
                    var earliest = firstEndingAfter(ends, timeBase, target - 1);
                    var latest = firstEndingAfter(clipped, timeBase, target + 1);
                    if (target < 0 ? start != 0 : start < earliest || start > latest) {
                        throw new AssertionFailedError("seek to " + target + ": restarts at frame " + start + " ("
                                + (start < lines.size() ? lines.get(start) : "end") + ") instead of frame " + earliest
                                + ", the one displayed at the target");
                    }
                    expected.put(target, got);
                    text.append("seek ").append(target).append(": ").append(got.isEmpty() ? "end" : "@" + start)
                            .append('\n');
                }
                try (var reader = VideoReader.open(MediaSource.of(data))) {
                    var order = new ArrayList<>(expected.keySet());
                    Collections.reverse(order);
                    for (var target : order) {
                        reader.seek(target);
                        var want = expected.get(target);
                        for (var i = 0; i < Math.min(3, want.size()); i++) {
                            var frame = reader.read();
                            var line = frame == null ? "end" : frameLine(frame);
                            if (!want.get(i).equals(line)) {
                                throw new AssertionFailedError("chained seek to " + target + ": frame " + i
                                        + " differs from a seek on a fresh reader", want.get(i), line);
                            }
                        }
                    }
                }
                return text.toString();
            }

            private static int firstEndingAfter(List<Long> ends, Rational timeBase, long micros) {
                for (var i = 0; i < ends.size(); i++) {
                    if (after(ends.get(i), timeBase, micros)) {
                        return i;
                    }
                }
                return ends.size();
            }

            private static long microsToTicks(Rational timeBase, long micros) {
                var numerator = BigInteger.valueOf(micros).multiply(BigInteger.valueOf(timeBase.denominator()));
                var denominator = BigInteger.valueOf(timeBase.numerator()).multiply(BigInteger.valueOf(1_000_000));
                return numerator.add(denominator.shiftRight(1)).divide(denominator).longValue();
            }

            private static long ticksToMicros(Rational timeBase, long ticks) {
                return BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(timeBase.numerator()))
                        .multiply(BigInteger.valueOf(1_000_000)).divide(BigInteger.valueOf(timeBase.denominator()))
                        .longValue();
            }
        }

        /**
         * Checks {@link AudioReader#seek(long)}: after a seek to each target, the output must start at the
         * sample presented at the target (one sample of rounding slack either way), at the first sample for
         * a target before zero, and end at once for a target at or past the end. It must then equal the
         * reference samples from there bit for bit, except for Opus, AMR, AAC, AC-3, E-AC-3, G.722 and G.726, whose
         * output after a seek the contract does not make sample-exact: from 100 ms on, their difference from the
         * reference must stay below a quarter of the reference's energy, which a misplaced restart exceeds.
         */
        private final class AudioSeeks {
            String run() {
                float[][] reference;
                int rate;
                long duration;
                boolean exact;
                try (var reader = AudioReader.open(MediaSource.of(data))) {
                    if (!reader.isSeekable()) {
                        return "not seekable\n";
                    }
                    rate = reader.sampleRate();
                    duration = reader.durationMicros();
                    var codec = reader.track().codec();
                    exact = !APPROXIMATE_SEEKS.contains(codec);
                    reference = readAll(reader);
                }
                var total = reference.length == 0 ? 0 : reference[0].length;
                if (total == 0) {
                    return "";
                }
                var end = (long) Math.ceil(total * 1_000_000.0 / rate);
                var span = duration > 0 ? duration : end;
                var text = new StringBuilder();
                for (var target : targets(span)) {
                    float[][] got;
                    try (var reader = AudioReader.open(MediaSource.of(data))) {
                        reader.seek(target);
                        got = readAll(reader);
                    }
                    var count = got.length == 0 ? 0 : got[0].length;
                    var start = total - count;
                    var position = target <= 0 ? 0.0 : target * (double) rate / 1_000_000.0;
                    var misplaced = target >= end ? count != 0
                            : start < Math.floor(position) - 1 || start > Math.ceil(position) + 1;
                    if (misplaced) {
                        throw new AssertionFailedError("seek to " + target + ": the output starts at sample " + start
                                + " instead of " + (long) Math.floor(position) + " (" + count + " of " + total
                                + " samples)");
                    }
                    if (!exact) {
                        var error = 0.0;
                        var energy = 0.0;
                        for (var channel = 0; channel < got.length; channel++) {
                            for (var i = rate / 10; i < count; i++) {
                                var difference = (double) got[channel][i] - reference[channel][start + i];
                                error += difference * difference;
                                energy += (double) reference[channel][start + i] * reference[channel][start + i];
                            }
                        }
                        if (error > energy / 4) {
                            throw new AssertionFailedError("seek to " + target + ": the output after the first 100 ms"
                                    + " differs from a decode from the start by " + error / energy
                                    + " of its energy, more than the 0.25 a converging decoder stays within");
                        }
                    } else {
                        for (var channel = 0; channel < got.length; channel++) {
                            for (var i = 0; i < count; i++) {
                                if (Float.floatToRawIntBits(got[channel][i])
                                        != Float.floatToRawIntBits(reference[channel][start + i])) {
                                    throw new AssertionFailedError("seek to " + target + ": sample " + (start + i)
                                            + " of channel " + channel + " differs from a decode from the start: "
                                            + got[channel][i] + " instead of " + reference[channel][start + i]);
                                }
                            }
                        }
                    }
                    text.append("seek ").append(target).append(": ").append(count == 0 ? "end" : "@sample " + start)
                            .append('\n');
                }
                return text.toString();
            }

            private static float[][] readAll(AudioReader reader) {
                var channels = reader.layout().speakers().size();
                var planes = new float[channels][1 << 12];
                var count = 0;
                for (var block = reader.read(); block != null; block = reader.read()) {
                    var frames = block.frames();
                    if (count + frames > planes[0].length) {
                        for (var channel = 0; channel < channels; channel++) {
                            planes[channel] = Arrays.copyOf(planes[channel], Math.max(planes[channel].length * 2,
                                    count + frames));
                        }
                    }
                    for (var channel = 0; channel < channels; channel++) {
                        System.arraycopy(block.plane(channel), 0, planes[channel], count, frames);
                    }
                    count += frames;
                }
                for (var channel = 0; channel < channels; channel++) {
                    planes[channel] = Arrays.copyOf(planes[channel], count);
                }
                return planes;
            }
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

        private static void same(String behavior, Decoded reference, Decoded actual, boolean exactHeader) {
            if (actual.failure() != null) {
                throw new AssertionFailedError(behavior + ": decode failed after " + actual.exact().size() + " frames: "
                        + actual.failure(), actual.failure());
            }
            if (exactHeader && !reference.header().equals(actual.header())) {
                throw new AssertionFailedError(behavior + ": header differs", reference.header(), actual.header());
            }
            if (!reference.exact().equals(actual.exact())) {
                var count = Math.min(reference.exact().size(), actual.exact().size());
                for (var i = 0; i < count; i++) {
                    if (!reference.exact().get(i).equals(actual.exact().get(i))) {
                        throw new AssertionFailedError(behavior + ": frame " + i + " differs", reference.exact().get(i),
                                actual.exact().get(i));
                    }
                }
                throw new AssertionFailedError(behavior + ": " + actual.exact().size() + " frames instead of "
                        + reference.exact().size());
            }
        }

        /**
         * Decodes over sources that fail once at a spread of read indices and over sources that fail from a
         * read on, reporting at most four of the failures.
         */
        private void faults(List<Throwable> failures, Decoded reference, String header, long reads,
                            FaultySource factory) {
            var sources = new ArrayList<SimulatedSource>();
            for (var index : Behaviors.faultIndices(reads, maxFaultReads)) {
                sources.add(factory.open(index, false));
            }
            if (maxFaultReads > 0) {
                for (var index : new LinkedHashSet<>(List.of(0L, reads / 2, Math.max(0, reads - 1)))) {
                    sources.add(factory.open(index, true));
                }
            }
            var reported = 0;
            for (var source : sources) {
                var problem = fault(source.toString(), reference, header, decode(source));
                if (problem != null && reported++ < 4) {
                    failures.add(problem);
                }
            }
        }

        /**
         * Creates a source that fails at a read index.
         */
        @FunctionalInterface
        private interface FaultySource {
            SimulatedSource open(long index, boolean permanent);
        }

        private static AssertionFailedError fault(String behavior, Decoded reference, String header, Decoded actual) {
            if (actual.failure() == null) {
                return reference.exact().equals(actual.exact()) && header.equals(actual.header()) ? null
                        : new AssertionFailedError(behavior + ": the fault was swallowed and the frames differ");
            }
            if (!Behaviors.isCleanFault(actual.failure())) {
                return new AssertionFailedError(behavior + ": expected MediaException.Io caused by the injected fault"
                        + " but got " + actual.failure(), actual.failure());
            }
            var frames = actual.exact();
            if (frames.size() > reference.exact().size() || !reference.exact().subList(0, frames.size()).equals(frames)) {
                return new AssertionFailedError(behavior + ": the frames decoded before the failure are not a prefix "
                        + "of the reference");
            }
            return null;
        }

        /**
         * The result of one decode.
         *
         * @param header the reader description, or an empty string when opening failed
         * @param exact one line per frame with the exact sample CRC
         * @param golden one line per frame as the golden dump holds it
         * @param failure the failure that ended the decode, or {@code null}
         */
        private record Decoded(String header, List<String> exact, List<String> golden, Throwable failure) {
        }

        private Decoded decode(MediaSource source) {
            return Behaviors.withTimeout(timeout, name, () -> video ? decodeVideo(source) : decodeAudio(source));
        }

        private Decoded decodeVideo(MediaSource source) {
            var lines = new ArrayList<String>();
            var header = "";
            try (var reader = VideoReader.open(source)) {
                var track = reader.track();
                header = "format " + reader.format() + "\nduration " + reader.durationMicros() + "\nseekable "
                        + reader.isSeekable() + "\ntrack " + track.id() + " " + track.codec() + " " + track.width()
                        + "x" + track.height() + " timebase=" + track.timeBase().numerator() + "/"
                        + track.timeBase().denominator() + "\n";
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    lines.add(lines.size() + " " + frameLine(frame));
                }
            } catch (RuntimeException | StackOverflowError failure) {
                return new Decoded(header, lines, lines, failure);
            }
            return new Decoded(header, lines, lines, null);
        }

        private Decoded decodeAudio(MediaSource source) {
            var exact = new ArrayList<String>();
            var golden = new ArrayList<String>();
            var header = "";
            try (var reader = AudioReader.open(source)) {
                var track = reader.track();
                header = "format " + reader.format() + "\nduration " + reader.durationMicros() + "\nseekable "
                        + reader.isSeekable() + "\ntrack " + track.id() + " " + track.codec() + " rate="
                        + reader.sampleRate() + " layout=" + reader.layout().speakers()
                        + reader.layout().ambisonics().map(ambisonics -> " ambisonics=" + ambisonics.normalization()
                        + ambisonics.components()).orElse("") + "\n";
                for (var block = reader.read(); block != null; block = reader.read()) {
                    var bits = new CRC32();
                    var rounded = new CRC32();
                    var frames = block.frames();
                    var word = new byte[4];
                    for (var channel = 0; channel < block.channels(); channel++) {
                        var plane = block.plane(channel);
                        for (var i = 0; i < frames; i++) {
                            var value = Float.floatToRawIntBits(plane[i]);
                            word[0] = (byte) value;
                            word[1] = (byte) (value >> 8);
                            word[2] = (byte) (value >> 16);
                            word[3] = (byte) (value >> 24);
                            bits.update(word, 0, 4);
                            var sample = (int) Math.round(Math.clamp(plane[i], -1.0f, 1.0f) * 32767.0);
                            word[0] = (byte) sample;
                            word[1] = (byte) (sample >> 8);
                            rounded.update(word, 0, 2);
                        }
                    }
                    var index = exact.size();
                    exact.add(index + " frames=" + frames + " crc=" + "%08x".formatted(bits.getValue()));
                    golden.add(index + " frames=" + frames + " crc16=" + "%08x".formatted(rounded.getValue()));
                }
            } catch (RuntimeException | StackOverflowError failure) {
                return new Decoded(header, exact, golden, failure);
            }
            return new Decoded(header, exact, golden, null);
        }
    }
}
