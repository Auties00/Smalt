package com.github.auties00.smalt.testkit.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Dumps;
import com.github.auties00.smalt.testkit.io.Behaviors;
import com.github.auties00.smalt.testkit.io.IoVariant;
import com.github.auties00.smalt.testkit.io.SimulatedSource;
import com.github.auties00.smalt.testkit.io.SimulatedStream;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.MultipleFailuresError;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Runs a demuxer over one input under every I/O behavior a valid {@link MediaSource} may show, checks the
 * results against each other and against the {@link Demuxer} contract, and compares a readable dump with
 * its golden file.
 *
 * <p>{@link Builder#run()} performs, in order:
 * <ol>
 *   <li>the reference demux over {@link MediaSource#of(byte[])}, dumped by {@link DemuxDump} and compared
 *       with the golden file {@code dumps/<name>.dump} through {@link Dumps#assertMatches(String, String)};</li>
 *   <li>container recognition over every {@link IoVariant}, which must give the reference format (only
 *       with the default factory, {@link Containers#openDemuxer(MediaSource)});</li>
 *   <li>a full demux over every {@link IoVariant}: identical packets, and an identical header except
 *       that a source of unknown length may change durations, in which case the golden dump records
 *       the other header;</li>
 *   <li>re-opening: a second demuxer over the same source gives the same result, and closing a demuxer
 *       never closes its source;</li>
 *   <li>injected faults: a demux over a source that fails once at read index {@code k}, for every
 *       {@code k} below the reference read count (a bounded, evenly spread sample of them when there are
 *       more than {@link Builder#maxFaultReads(int)}), and over sources that fail from {@code k} on, both
 *       with whole reads and with reads cut to random sizes (which spread the read indices over the packet
 *       reads of a demuxer that buffers its input), and over a stream read once that fails from a read on;
 *       each run either gives the reference result or ends with a {@link MediaException.Io} caused by the
 *       injected fault, after a prefix of the reference packets;</li>
 *   <li>for a seekable demuxer, seeks to 0, a third, two thirds and the end of the presentation, a time
 *       before zero, and a chained sequence of seeks on one demuxer; after each, the packets read must be
 *       the reference packets from the {@linkplain SeekPrecision sync point the contract names}, in
 *       reference order, and a seek past the end of every track must end the stream. The same seeks
 *       over the one-byte, unknown-length and read-once variants must give the same packets, except
 *       that a read-once source may refuse a seek with {@link MediaException.Unsupported} and must then
 *       stay positioned where it was;</li>
 *   <li>a seek whose first read fails must either succeed or throw {@link MediaException.Io} and leave
 *       the demuxer positioned where it was, as {@link Demuxer#seek(long)} requires.</li>
 * </ol>
 * Every run is bounded by a timeout, so a demuxer that loops fails. Failures of all behaviors are
 * collected and reported together.
 *
 * <p>The golden dump holds the header, every packet, and for each seek the per-track index of the first
 * packet read after it ({@code seek 333333: t1@12 t2@40}), so a change in seek behavior is a one-line
 * diff.
 */
public final class DemuxerAsserts {
    /**
     * How exactly a demuxer must land after a seek.
     */
    public enum SeekPrecision {
        /**
         * Each track restarts at its last sync packet whose presentation time is at or before the target
         * (or at its first sync packet for a target before it), as {@link Demuxer#seek(long)} specifies.
         */
        EXACT,
        /**
         * Each track restarts at a sync packet whose presentation time is at or before the target, not
         * necessarily the last one; for formats whose index is coarser than their packets.
         */
        AT_OR_BEFORE,
        /**
         * Each track restarts at a sync packet near the target; the position is estimated (for example
         * from a constant bit rate) so the time is not checked.
         */
        ESTIMATED
    }

    private DemuxerAsserts() {
        throw new AssertionError();
    }

    /**
     * Returns a run of the harness over one input.
     *
     * @param name the dump name, for example {@code "mp4/faststart"}; see {@link Dumps#golden(String)}
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
        private final String name;
        private final byte[] data;
        private Function<MediaSource, Demuxer> factory = Containers::openDemuxer;
        private boolean defaultFactory = true;
        private SeekPrecision precision = SeekPrecision.EXACT;
        private int maxFaultReads = 48;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean dump = true;

        private Builder(String name, byte[] data) {
            Dumps.golden(name);
            this.name = name;
            this.data = Objects.requireNonNull(data, "data");
        }

        /**
         * Sets how the demuxer is created; {@link Containers#openDemuxer(MediaSource)} by default. With
         * another factory, recognition is not checked.
         *
         * @param factory creates a demuxer over a source, without closing it
         * @return this builder
         */
        public Builder factory(Function<MediaSource, Demuxer> factory) {
            this.factory = Objects.requireNonNull(factory, "factory");
            this.defaultFactory = false;
            return this;
        }

        /**
         * Sets how exactly the demuxer must land after a seek; {@link SeekPrecision#EXACT} by default.
         *
         * @param precision the precision
         * @return this builder
         */
        public Builder seekPrecision(SeekPrecision precision) {
            this.precision = Objects.requireNonNull(precision, "precision");
            return this;
        }

        /**
         * Sets the largest number of read indices at which a fault is injected; 48 by default.
         *
         * @param maxFaultReads the bound, at least 1
         * @return this builder
         */
        public Builder maxFaultReads(int maxFaultReads) {
            if (maxFaultReads < 1) {
                throw new IllegalArgumentException("maxFaultReads " + maxFaultReads);
            }
            this.maxFaultReads = maxFaultReads;
            return this;
        }

        /**
         * Sets the time limit of each demux; 60 seconds by default.
         *
         * @param timeout the limit
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Skips the golden dump comparison, for inputs that are not pinned (generated at test time by a
         * tool whose output may change between versions).
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
         * @throws AssertionFailedError if the golden dump differs or the reference demux fails
         */
        public void run() {
            new Run(this).execute();
        }
    }

    /**
     * The result of one demux.
     *
     * @param header the header text, or {@code null} when opening failed
     * @param packets the packets read before the end or the failure
     * @param failure the failure that ended the demux, or {@code null}
     */
    record Outcome(String header, List<Packet> packets, Throwable failure) {
    }

    private static final class Run {
        /**
         * The seed of the chunk sizes of the sources that fail during cut reads.
         */
        private static final long FAULT_SEED = 7;

        /**
         * The largest number of read indices at which a stream read once fails.
         */
        private static final int MAX_STREAM_FAULTS = 16;

        private final Builder options;
        private final List<Throwable> failures = new ArrayList<>();
        private String referenceHeader;
        private List<Packet> reference;
        private List<String> referenceLines;
        private final Map<Integer, List<Integer>> trackIndices = new HashMap<>();
        private final Map<Integer, Track> tracks = new HashMap<>();
        private final Map<Integer, TrackInfo> trackInfos = new HashMap<>();
        private final Map<String, List<IoVariant>> headerNotes = new LinkedHashMap<>();
        private final Set<IoVariant> refused = EnumSet.noneOf(IoVariant.class);
        private final Map<IoVariant, String> variantHeaders = new EnumMap<>(IoVariant.class);
        private long[] perTrackPosition;
        private final StringBuilder dump = new StringBuilder();
        private final StringBuilder notes = new StringBuilder();

        Run(Builder options) {
            this.options = options;
        }

        void execute() {
            var counting = SimulatedSource.of(options.data);
            var outcome = demux(counting);
            if (outcome.failure() != null) {
                throw new AssertionFailedError("the reference demux of " + options.name + " failed", outcome.failure());
            }
            referenceHeader = outcome.header();
            reference = outcome.packets();
            referenceLines = DemuxDump.lines(reference);
            var referenceReads = counting.reads();
            indexReference();
            dump.append("# demux dump of ").append(options.name).append("; regenerate with -Dsmalt.test.updateDumps=true\n");
            dump.append(referenceHeader);
            dump.append(DemuxDump.packets(reference));
            check("memory source", () -> compare("memory source", demux(MediaSource.of(options.data)), true));
            if (options.defaultFactory) {
                checkRecognition();
            }
            for (var variant : IoVariant.alternatives()) {
                check(variant.name(), () -> checkVariant(variant));
            }
            check("re-open", this::checkReopen);
            check("injected faults", () -> checkFaults(referenceReads));
            boolean seekable;
            try (var demuxer = open(MediaSource.of(options.data))) {
                seekable = demuxer.isSeekable();
            }
            if (seekable) {
                checkSeeks();
            }
            for (var entry : headerNotes.entrySet()) {
                dump.append("header over ").append(String.join(", ", entry.getValue().stream().map(Enum::name)
                        .toList())).append(":\n").append(indent(entry.getKey()));
            }
            dump.append(notes);
            if (options.dump) {
                check("golden dump", () -> Dumps.assertMatches(options.name, dump.toString()));
            }
            if (!failures.isEmpty()) {
                throw new MultipleFailuresError(options.name + ": " + failures.size() + " behavior(s) failed", failures);
            }
        }

        private void indexReference() {
            try (var demuxer = open(MediaSource.of(options.data))) {
                for (var track : demuxer.tracks()) {
                    tracks.put(track.id(), track);
                }
            }
            for (var i = 0; i < reference.size(); i++) {
                trackIndices.computeIfAbsent(reference.get(i).trackId(), _ -> new ArrayList<>()).add(i);
            }
            perTrackPosition = new long[reference.size()];
            var counters = new HashMap<Integer, Integer>();
            for (var i = 0; i < reference.size(); i++) {
                perTrackPosition[i] = counters.merge(reference.get(i).trackId(), 1, Integer::sum) - 1;
            }
        }

        private Demuxer open(MediaSource source) {
            return Behaviors.withTimeout(options.timeout, options.name, () -> options.factory.apply(source));
        }

        private void seek(Demuxer demuxer, long micros) {
            Behaviors.withTimeout(options.timeout, options.name, () -> {
                demuxer.seek(micros);
                return null;
            });
        }

        private void check(String behavior, Runnable body) {
            try {
                body.run();
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError error && !(error instanceof StackOverflowError)) {
                    throw error;
                }
                failures.add(failure instanceof AssertionFailedError || failure instanceof MultipleFailuresError
                        ? failure : new AssertionFailedError(behavior + ": " + failure, failure));
            }
        }

        private Outcome demux(MediaSource source) {
            return Behaviors.withTimeout(options.timeout, options.name, () -> {
                String header = null;
                var packets = new ArrayList<Packet>();
                try (var demuxer = open(source)) {
                    header = DemuxDump.header(demuxer);
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        packets.add(packet);
                    }
                } catch (RuntimeException | StackOverflowError failure) {
                    return new Outcome(header, packets, failure);
                }
                return new Outcome(header, packets, null);
            });
        }

        private void compare(String behavior, Outcome outcome, boolean sameHeader) {
            if (outcome.failure() != null) {
                throw new AssertionFailedError(behavior + ": demux failed after " + outcome.packets().size()
                        + " packets: " + outcome.failure(), outcome.failure());
            }
            if (sameHeader && !referenceHeader.equals(outcome.header())) {
                throw new AssertionFailedError(behavior + ": header differs "
                        + Dumps.firstDifference(referenceHeader, outcome.header()), referenceHeader, outcome.header());
            }
            comparePackets(behavior, referenceLines, DemuxDump.lines(outcome.packets()));
        }

        private static void comparePackets(String behavior, List<String> expected, List<String> actual) {
            if (expected.equals(actual)) {
                return;
            }
            var count = Math.min(expected.size(), actual.size());
            for (var i = 0; i < count; i++) {
                if (!expected.get(i).equals(actual.get(i))) {
                    throw new AssertionFailedError(behavior + ": packet " + i + " differs", expected.get(i), actual.get(i));
                }
            }
            throw new AssertionFailedError(behavior + ": " + actual.size() + " packets instead of " + expected.size()
                    + (actual.size() < expected.size() ? ", first missing " + expected.get(count)
                    : ", first extra " + actual.get(count)));
        }

        private void checkRecognition() {
            var expected = Containers.detect(MediaSource.of(options.data));
            for (var variant : IoVariant.alternatives()) {
                check(variant + " recognition", () -> {
                    Optional<ContainerFormat> actual;
                    try (var source = variant.open(options.data)) {
                        actual = Behaviors.withTimeout(options.timeout, options.name, () -> Containers.detect(source));
                    }
                    if (!expected.equals(actual)) {
                        throw new AssertionFailedError(variant + ": recognized " + actual, expected, actual);
                    }
                });
            }
        }

        private void checkVariant(IoVariant variant) {
            try (var source = variant.open(options.data)) {
                var outcome = demux(source);
                var exact = variant.isSized() && !variant.isReadOnce();
                if (variant.isReadOnce() && outcome.failure() instanceof MediaException.Unsupported refused) {
                    var lines = DemuxDump.lines(outcome.packets());
                    this.refused.add(variant);
                    comparePackets(variant + " before the refusal " + refused.getMessage(),
                            referenceLines.subList(0, Math.min(lines.size(), referenceLines.size())), lines);
                    note("refused over " + variant + " after " + lines.size() + " packets\n");
                    return;
                }
                compare(variant.name(), outcome, exact);
                variantHeaders.put(variant, outcome.header());
                if (!exact && !referenceHeader.equals(outcome.header())) {
                    headerNotes.computeIfAbsent(outcome.header(), _ -> new ArrayList<>()).add(variant);
                }
                if (source instanceof SimulatedSource simulated && simulated.closes() > 0) {
                    throw new AssertionFailedError(variant + ": closing the demuxer closed its source");
                }
            }
        }

        private static String indent(String text) {
            var out = new StringBuilder();
            for (var line : text.split("\n")) {
                out.append("  ").append(line).append('\n');
            }
            return out.toString();
        }

        private void checkReopen() {
            for (var variant : List.of(IoVariant.MEMORY, IoVariant.RANDOM_CHUNKS)) {
                try (var source = variant.open(options.data)) {
                    compare(variant + " first open", demux(source), true);
                    compare(variant + " second open", demux(source), true);
                }
            }
        }

        private void checkFaults(long referenceReads) {
            faults("whole reads", referenceReads, (index, permanent) -> SimulatedSource.failing(options.data, index,
                    permanent), referenceHeader);
            var chunked = SimulatedSource.of(options.data, true, SimulatedSource.Chunking.RANDOM, FAULT_SEED);
            if (demux(chunked).failure() == null) {
                faults("random chunks", chunked.reads(), (index, permanent) -> SimulatedSource.failing(options.data,
                        SimulatedSource.Chunking.RANDOM, FAULT_SEED, index, permanent), referenceHeader);
            }
            if (!refused.contains(IoVariant.READ_ONCE) && variantHeaders.containsKey(IoVariant.READ_ONCE)) {
                var stream = SimulatedStream.of(options.data);
                try (var source = stream.source(true)) {
                    demux(source);
                }
                var reported = 0;
                for (var index : Behaviors.faultIndices(stream.reads(), Math.min(options.maxFaultReads,
                        MAX_STREAM_FAULTS))) {
                    Outcome outcome;
                    try (var source = SimulatedStream.failing(options.data, index).source(true)) {
                        outcome = demux(source);
                    }
                    var problem = faultProblem("stream read once failing from read " + index, outcome,
                            variantHeaders.get(IoVariant.READ_ONCE));
                    if (problem != null && reported++ < 4) {
                        failures.add(problem);
                    }
                }
            }
        }

        /**
         * Demuxes over sources that fail once at a spread of read indices and over sources that fail from a
         * read on, reporting at most four of the transient failures.
         */
        private void faults(String delivery, long reads, FaultySource factory, String expectedHeader) {
            var reported = 0;
            for (var index : Behaviors.faultIndices(reads, options.maxFaultReads)) {
                var outcome = demux(factory.open(index, false));
                var problem = faultProblem("transient fault at read " + index + " (" + delivery + ")", outcome,
                        expectedHeader);
                if (problem != null && reported++ < 4) {
                    failures.add(problem);
                }
            }
            for (var index : new LinkedHashSet<>(List.of(0L, reads / 2, Math.max(0, reads - 1)))) {
                var outcome = demux(factory.open(index, true));
                var problem = faultProblem("permanent fault from read " + index + " (" + delivery + ")", outcome,
                        expectedHeader);
                if (problem != null) {
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

        private AssertionFailedError faultProblem(String behavior, Outcome outcome, String expectedHeader) {
            var lines = DemuxDump.lines(outcome.packets());
            if (outcome.failure() == null) {
                if (outcome.header().equals(expectedHeader) && lines.equals(referenceLines)) {
                    return null;
                }
                return new AssertionFailedError(behavior + ": the fault was swallowed and the result differs ("
                        + outcome.packets().size() + " packets instead of " + reference.size() + ")");
            }
            if (!Behaviors.causedByInjectedFault(outcome.failure())) {
                return new AssertionFailedError(behavior + ": expected MediaException.Io caused by the injected fault"
                        + " but got " + outcome.failure(), outcome.failure());
            }
            if (!(outcome.failure() instanceof MediaException.Io)) {
                return new AssertionFailedError(behavior + ": the injected fault surfaced as " + outcome.failure()
                        + " instead of MediaException.Io", outcome.failure());
            }
            if (outcome.header() != null && !outcome.header().equals(expectedHeader)) {
                return new AssertionFailedError(behavior + ": header differs before the failure "
                        + Dumps.firstDifference(expectedHeader, outcome.header()));
            }
            if (lines.size() > referenceLines.size() || !referenceLines.subList(0, lines.size()).equals(lines)) {
                return new AssertionFailedError(behavior + ": the packets read before the failure are not a prefix of "
                        + "the reference");
            }
            return null;
        }

        private TrackInfo info(int trackId) {
            return trackInfos.computeIfAbsent(trackId, id -> new TrackInfo(tracks.get(id), trackIndices.get(id)));
        }

        /**
         * The facts about one track of the reference that the seek checks use.
         */
        private final class TrackInfo {
            final Track track;
            final List<Integer> indices;
            final int firstSync;
            final long endTicks;
            final long bytesPerTick;

            TrackInfo(Track track, List<Integer> indices) {
                this.track = track;
                this.indices = indices;
                var first = -1;
                for (var i = 0; i < indices.size(); i++) {
                    if (packet(i).flags().contains(PacketFlag.KEY)) {
                        first = i;
                        break;
                    }
                }
                this.firstSync = first;
                var timestamps = new ArrayList<Long>();
                var end = Long.MIN_VALUE;
                for (var i = 0; i < indices.size(); i++) {
                    var packet = packet(i);
                    if (packet.pts() == Packet.NO_TIMESTAMP) {
                        continue;
                    }
                    timestamps.add(packet.pts());
                    if (packet.duration() != Packet.UNKNOWN_DURATION) {
                        end = Math.max(end, packet.pts() + packet.duration());
                    }
                }
                timestamps.sort(null);
                if (!timestamps.isEmpty()) {
                    var last = timestamps.getLast();
                    var step = 0L;
                    for (var i = timestamps.size() - 1; i > 0 && step == 0; i--) {
                        step = timestamps.get(i) - timestamps.get(i - 1);
                    }
                    var lastPacket = lastWithPts(last);
                    end = Math.max(end, last + (lastPacket.duration() != Packet.UNKNOWN_DURATION
                            ? lastPacket.duration() : step));
                }
                this.endTicks = end;
                var rate = -1L;
                for (var i = 0; i < indices.size(); i++) {
                    var packet = packet(i);
                    if (!packet.flags().contains(PacketFlag.KEY) || packet.duration() <= 0
                            || packet.size() % packet.duration() != 0 || packet.pts() == Packet.NO_TIMESTAMP) {
                        rate = -1;
                        break;
                    }
                    var packetRate = packet.size() / packet.duration();
                    if (rate >= 0 && rate != packetRate) {
                        rate = -1;
                        break;
                    }
                    rate = packetRate;
                }
                this.bytesPerTick = rate > 0 ? rate : -1;
            }

            Packet packet(int trackIndex) {
                return reference.get(indices.get(trackIndex));
            }

            String line(int trackIndex) {
                return referenceLines.get(indices.get(trackIndex));
            }

            private Packet lastWithPts(long pts) {
                for (var i = indices.size() - 1; i >= 0; i--) {
                    if (packet(i).pts() == pts) {
                        return packet(i);
                    }
                }
                throw new IllegalStateException();
            }

            boolean hasSync() {
                return firstSync >= 0;
            }

            /**
             * {@return the end of the track in microseconds, rounded down, or {@code -1} when no packet has
             * a timestamp}
             */
            long endMicrosFloor() {
                return endTicks == Long.MIN_VALUE ? -1 : floorMicros(track.timeBase(), endTicks);
            }

            long endMicrosCeil() {
                return endTicks == Long.MIN_VALUE ? -1 : ceilMicros(track.timeBase(), endTicks);
            }

            boolean endedBy(long target) {
                var end = endMicrosFloor();
                return end >= 0 && target >= end;
            }

            /**
             * {@return the index of the last sync packet at or before the target, or the first sync packet
             * when none is}
             */
            int lastSyncAtOrBefore(long target) {
                var found = firstSync;
                for (var i = firstSync; i >= 0 && i < indices.size(); i++) {
                    var packet = packet(i);
                    if (packet.flags().contains(PacketFlag.KEY) && packet.pts() != Packet.NO_TIMESTAMP
                            && atOrBefore(packet.pts() + 1, track.timeBase(), target)) {
                        found = i;
                    }
                }
                return found;
            }

            boolean endsAtOrBefore(int trackIndex, long target) {
                var packet = packet(trackIndex);
                return packet.pts() != Packet.NO_TIMESTAMP && packet.duration() != Packet.UNKNOWN_DURATION
                        && atOrBefore(packet.pts() + packet.duration() + 1, track.timeBase(), target);
            }
        }

        private long presentationEnd() {
            var end = 0L;
            for (var trackId : trackIndices.keySet()) {
                end = Math.max(end, info(trackId).endMicrosCeil());
            }
            return end;
        }

        private void checkSeeks() {
            long duration;
            try (var demuxer = open(MediaSource.of(options.data))) {
                duration = demuxer.durationMicros();
            }
            var end = presentationEnd();
            var span = duration > 0 ? duration : end;
            var targets = new LinkedHashSet<Long>();
            targets.add(0L);
            targets.add(span / 3);
            targets.add(span * 2 / 3);
            targets.add(end);
            if (duration > 0 && duration != end) {
                targets.add(duration);
            }
            targets.add(-1_000_000L);
            var expected = new HashMap<Long, List<String>>();
            for (var target : targets) {
                check("seek to " + target, () -> {
                    try (var demuxer = open(MediaSource.of(options.data))) {
                        seek(demuxer, target);
                        var lines = DemuxDump.lines(readAll(demuxer));
                        var summary = validateSeek(target, lines, end);
                        expected.put(target, lines);
                        dump.append("seek ").append(target).append(": ").append(summary).append('\n');
                    }
                });
            }
            if (expected.size() != targets.size()) {
                return;
            }
            check("chained seeks", () -> chainedSeeks(new ArrayList<>(targets), expected));
            for (var variant : List.of(IoVariant.ONE_BYTE, IoVariant.UNKNOWN_LENGTH, IoVariant.READ_ONCE,
                    IoVariant.READ_ONCE_UNKNOWN_LENGTH)) {
                check(variant + " seeks", () -> variantSeeks(variant, new ArrayList<>(targets), expected));
            }
            check("seek with a failing read", () -> failingSeek(List.of(0L, span / 2, end)));
        }

        private List<Packet> readAll(Demuxer demuxer) {
            return Behaviors.withTimeout(options.timeout, options.name, () -> {
                var packets = new ArrayList<Packet>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    packets.add(packet);
                }
                return packets;
            });
        }

        private List<Packet> read(Demuxer demuxer, int count) {
            return Behaviors.withTimeout(options.timeout, options.name, () -> {
                var packets = new ArrayList<Packet>();
                for (var i = 0; i < count; i++) {
                    var packet = demuxer.read();
                    if (packet == null) {
                        break;
                    }
                    packets.add(packet);
                }
                return packets;
            });
        }

        private void chainedSeeks(List<Long> targets, Map<Long, List<String>> expected) {
            try (var demuxer = open(MediaSource.of(options.data))) {
                var half = DemuxDump.lines(read(demuxer, reference.size() / 2));
                comparePackets("chained seeks, first half", referenceLines.subList(0, half.size()), half);
                for (var i = targets.size() - 1; i >= 0; i--) {
                    var target = targets.get(i);
                    seek(demuxer, target);
                    var want = expected.get(target);
                    var got = DemuxDump.lines(read(demuxer, Math.max(1, want.size() / 2)));
                    comparePackets("chained seek to " + target + " (after other seeks)",
                            want.subList(0, Math.min(want.size(), got.size())), got);
                }
                seek(demuxer, 0);
                comparePackets("chained seeks, final seek to 0", expected.get(0L), DemuxDump.lines(readAll(demuxer)));
            }
        }

        private void note(String note) {
            if (notes.indexOf(note) < 0) {
                notes.append(note);
            }
        }

        private void variantSeeks(IoVariant variant, List<Long> targets, Map<Long, List<String>> expected) {
            if (refused.contains(variant)) {
                return;
            }
            for (var target : targets) {
                try (var source = variant.open(options.data); var demuxer = open(source)) {
                    var lead = DemuxDump.lines(read(demuxer, reference.size() / 3));
                    var seekable = demuxer.isSeekable();
                    try {
                        seek(demuxer, target);
                    } catch (MediaException.Unsupported refused) {
                        if (seekable && !variant.isReadOnce()) {
                            throw new AssertionFailedError(variant + ": isSeekable() is true but seek(" + target
                                    + ") failed: " + refused, refused);
                        }
                        var all = new ArrayList<>(lead);
                        all.addAll(DemuxDump.lines(readAll(demuxer)));
                        comparePackets(variant + " after a refused seek to " + target
                                + " (the demuxer must stay where it was)", referenceLines, all);
                        note(seekable ? "seek refused over " + variant + ": " + target + "\n"
                                : "not seekable over " + variant + "\n");
                        continue;
                    }
                    if (!seekable) {
                        note("seeks forward only over " + variant + "\n");
                    }
                    comparePackets(variant + " seek to " + target, expected.get(target),
                            DemuxDump.lines(readAll(demuxer)));
                }
            }
        }

        private void failingSeek(List<Long> targets) {
            for (var target : targets) {
                var source = SimulatedSource.of(options.data);
                try (var demuxer = open(source)) {
                    var lead = DemuxDump.lines(read(demuxer, reference.size() / 3));
                    source.failNextRead();
                    try {
                        seek(demuxer, target);
                    } catch (MediaException.Io failure) {
                        if (!Behaviors.causedByInjectedFault(failure)) {
                            throw failure;
                        }
                        var all = new ArrayList<>(lead);
                        all.addAll(DemuxDump.lines(readAll(demuxer)));
                        comparePackets("after a failed seek to " + target + " (the demuxer must stay where it was)",
                                referenceLines, all);
                        continue;
                    } finally {
                        source.disarm();
                    }
                    validateSeek(target, DemuxDump.lines(readAll(demuxer)), presentationEnd());
                }
            }
        }

        /**
         * Checks the packets read after a seek and returns the per-track summary for the dump.
         */
        private String validateSeek(long target, List<String> lines, long end) {
            if (target >= end && !lines.isEmpty()) {
                throw new AssertionFailedError("seek to " + target + ", at or past the end of every track (" + end
                        + " us): read " + lines.size() + " packets instead of none, first " + lines.getFirst());
            }
            var outByTrack = new HashMap<Integer, List<String>>();
            for (var line : lines) {
                var trackId = Integer.parseInt(line.substring(1, line.indexOf(' ')));
                if (!trackIndices.containsKey(trackId)) {
                    throw new AssertionFailedError("seek to " + target + ": packets of unknown track " + trackId);
                }
                outByTrack.computeIfAbsent(trackId, _ -> new ArrayList<>()).add(line);
            }
            var starts = new HashMap<Integer, Integer>();
            var summaries = new HashMap<Integer, String>();
            for (var entry : outByTrack.entrySet()) {
                var info = info(entry.getKey());
                var start = info.indices.size() - entry.getValue().size();
                var want = start < 0 ? List.<String>of() : referenceLines(info, start);
                if (start >= 0 && want.equals(entry.getValue())) {
                    checkStart(target, info, start);
                    starts.put(entry.getKey(), start);
                    summaries.put(entry.getKey(), "@" + start);
                } else if (info.bytesPerTick > 0) {
                    summaries.put(entry.getKey(), "@pts=" + checkRepacketized(target, info, entry.getValue()));
                } else {
                    var expectedFrom = info.lastSyncAtOrBefore(target);
                    comparePackets("seek to " + target + ", track " + entry.getKey()
                                    + " (must be the reference packets from a sync point to the end)",
                            expectedFrom < 0 ? List.of() : referenceLines(info, expectedFrom), entry.getValue());
                }
            }
            var filtered = new ArrayList<String>();
            var actual = new ArrayList<String>();
            for (var i = 0; i < reference.size(); i++) {
                var start = starts.get(reference.get(i).trackId());
                if (start != null && perTrackPosition[i] >= start) {
                    filtered.add(referenceLines.get(i));
                }
            }
            for (var line : lines) {
                if (starts.containsKey(Integer.parseInt(line.substring(1, line.indexOf(' '))))) {
                    actual.add(line);
                }
            }
            comparePackets("seek to " + target + " (packets of all tracks must keep the reference order)", filtered,
                    actual);
            if (lines.isEmpty()) {
                for (var trackId : trackIndices.keySet()) {
                    requireEnded(target, info(trackId));
                }
                return "end";
            }
            var summary = new StringBuilder();
            for (var trackId : trackIndices.keySet().stream().sorted().toList()) {
                var part = summaries.get(trackId);
                if (part == null) {
                    requireEnded(target, info(trackId));
                    part = "@end";
                }
                summary.append(summary.isEmpty() ? "" : " ").append('t').append(trackId).append(part);
            }
            return summary.toString();
        }

        private List<String> referenceLines(TrackInfo info, int from) {
            var lines = new ArrayList<String>();
            for (var i = from; i < info.indices.size(); i++) {
                lines.add(info.line(i));
            }
            return lines;
        }

        private void requireEnded(long target, TrackInfo info) {
            if (info.hasSync() && !info.endedBy(target) && options.precision != SeekPrecision.ESTIMATED) {
                throw new AssertionFailedError("seek to " + target + ": no packet of track " + info.track.id()
                        + " read although it lasts until " + info.endMicrosFloor() + " us");
            }
        }

        private void checkStart(long target, TrackInfo info, int start) {
            if (!info.hasSync()) {
                return;
            }
            var trackId = info.track.id();
            var first = info.packet(start);
            if (!first.flags().contains(PacketFlag.KEY)) {
                var syncPacket = info.packet(info.firstSync);
                var beforeFirstSync = syncPacket.pts() == Packet.NO_TIMESTAMP
                        || !atOrBefore(syncPacket.pts() + 1, info.track.timeBase(), target);
                if (start == 0 && beforeFirstSync) {
                    return;
                }
                throw new AssertionFailedError("seek to " + target + ": track " + trackId + " restarts at packet "
                        + start + ", which is not a sync point: " + info.line(start));
            }
            if (options.precision == SeekPrecision.ESTIMATED || first.pts() == Packet.NO_TIMESTAMP) {
                return;
            }
            var expected = info.lastSyncAtOrBefore(target);
            if (start == expected) {
                return;
            }
            if (start > expected) {
                for (var i = expected; i < start; i++) {
                    if (!info.endsAtOrBefore(i, target)) {
                        throw new AssertionFailedError("seek to " + target + ": track " + trackId
                                + " restarts at packet " + start + " (pts " + first.pts() + ") but packet " + expected
                                + " is the last sync packet at or before the target: " + info.line(expected));
                    }
                }
                return;
            }
            if (options.precision == SeekPrecision.EXACT) {
                throw new AssertionFailedError("seek to " + target + ": track " + trackId + " restarts at packet "
                        + start + " (pts " + first.pts() + ") but sync packet " + expected + " (pts "
                        + info.packet(expected).pts() + ") is the last one at or before the target");
            }
        }

        /**
         * Checks a constant-rate track whose packets the demuxer cut afresh at the seek target: the payload
         * must continue the reference bytes from the first timestamp read, and that timestamp must be the
         * target's.
         */
        private long checkRepacketized(long target, TrackInfo info, List<String> lines) {
            var packets = new ArrayList<Packet>();
            try (var demuxer = open(MediaSource.of(options.data))) {
                seek(demuxer, target);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() == info.track.id()) {
                        packets.add(packet);
                    }
                }
            }
            if (!DemuxDump.lines(packets).equals(lines)) {
                throw new AssertionFailedError("seek to " + target + ": track " + info.track.id()
                        + " gives other packets on a second seek");
            }
            var referenceBytes = new ByteArrayOutputStream();
            for (var i = 0; i < info.indices.size(); i++) {
                referenceBytes.writeBytes(info.packet(i).data());
            }
            var all = referenceBytes.toByteArray();
            var firstPts = info.packet(0).pts();
            var pts = packets.getFirst().pts();
            var offset = (pts - firstPts) * info.bytesPerTick;
            if (pts < firstPts || offset > all.length) {
                throw new AssertionFailedError("seek to " + target + ": track " + info.track.id() + " restarts at pts "
                        + pts + ", outside the stream");
            }
            var expectedPts = pts;
            var position = (int) offset;
            for (var packet : packets) {
                if (packet.pts() != expectedPts || packet.duration() <= 0
                        || packet.size() != packet.duration() * info.bytesPerTick
                        || !Arrays.equals(all, position, position + packet.size(), packet.data(), 0,
                        packet.size())) {
                    throw new AssertionFailedError("seek to " + target + ": track " + info.track.id()
                            + " does not continue the reference samples at " + DemuxDump.packet(packet));
                }
                expectedPts += packet.duration();
                position += packet.size();
            }
            if (position != all.length) {
                throw new AssertionFailedError("seek to " + target + ": track " + info.track.id() + " ends after "
                        + position + " of " + all.length + " bytes");
            }
            var timeBase = info.track.timeBase();
            var beforeStart = !atOrBefore(firstPts + 1, timeBase, target);
            if (beforeStart ? pts != firstPts : !atOrBefore(pts, timeBase, target)) {
                throw new AssertionFailedError("seek to " + target + ": track " + info.track.id() + " restarts at pts "
                        + pts + ", after the target");
            }
            if (options.precision == SeekPrecision.EXACT && !beforeStart && atOrBefore(pts + 2, timeBase, target)) {
                throw new AssertionFailedError("seek to " + target + ": track " + info.track.id() + " restarts at pts "
                        + pts + ", more than one sample before the target");
            }
            return pts;
        }

        /**
         * Returns whether {@code ticks - 1} time base units, one unit of rounding slack, are at or
         * before {@code micros}.
         */
        private static boolean atOrBefore(long ticks, Rational timeBase, long micros) {
            var left = BigInteger.valueOf(ticks - 1).multiply(BigInteger.valueOf(timeBase.numerator()))
                    .multiply(BigInteger.valueOf(1_000_000));
            var right = BigInteger.valueOf(micros).multiply(BigInteger.valueOf(timeBase.denominator()));
            return left.compareTo(right) <= 0;
        }

        private static long ceilMicros(Rational timeBase, long ticks) {
            var value = BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(timeBase.numerator()))
                    .multiply(BigInteger.valueOf(1_000_000));
            var division = value.divideAndRemainder(BigInteger.valueOf(timeBase.denominator()));
            return division[0].longValue() + (division[1].signum() > 0 ? 1 : 0);
        }

        private static long floorMicros(Rational timeBase, long ticks) {
            var value = BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(timeBase.numerator()))
                    .multiply(BigInteger.valueOf(1_000_000));
            var division = value.divideAndRemainder(BigInteger.valueOf(timeBase.denominator()));
            return division[0].longValue() - (division[1].signum() < 0 ? 1 : 0);
        }
    }
}
