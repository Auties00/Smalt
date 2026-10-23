package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Dumps;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts.SeekPrecision;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts;
import com.github.auties00.smalt.testkit.io.Behaviors;
import com.github.auties00.smalt.testkit.io.IoVariant;
import com.github.auties00.smalt.testkit.io.SimulatedSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.MultipleFailuresError;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the I/O behavior harness itself: a correct demuxer of a toy container passes every behavior,
 * and each deliberately broken variant of it fails the behavior it breaks.
 */
@DisplayName("the demuxer behavior harness")
class DemuxerAssertsTest {
    /**
     * The ways the toy demuxer can be broken.
     */
    enum Bug {
        /** Assumes every read delivers what it asks for. */
        ASSUMES_FULL_READS,
        /** Stops at the size the source reports, so a source of unknown size yields no packet. */
        NEEDS_SIZE,
        /** Turns a read failure into the end of the stream. */
        SWALLOWS_IO,
        /** Reports a read failure as a corrupt container. */
        IO_AS_MALFORMED,
        /** Closes its source. */
        CLOSES_SOURCE,
        /** Lands on the first key frame after the seek target. */
        SEEKS_LATE,
        /** Returns the last key frame for a target past the end. */
        SEEKS_PAST_END,
        /** Moves before the reads of a seek, so a failed seek leaves it elsewhere. */
        MOVES_ON_FAILED_SEEK,
        /** Keeps state across instances, so a second demuxer over the same input differs. */
        SHARED_STATE,
        /** Loops forever on a short read. */
        LOOPS_ON_SHORT_READ,
        /** Lands on the packet at the target, key frame or not. */
        SEEKS_TO_NON_KEY,
        /**
         * Reads through a buffer refilled on demand and turns a read failure into the end of the stream, so
         * only a fault that lands after the first whole read reveals it.
         */
        BUFFERS_AND_SWALLOWS_IO
    }

    private static final int PACKETS = 40;

    /** Builds the toy container: magic, packet count, then length-prefixed payloads. */
    static byte[] toy() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'T', 'O', 'Y', '1', 0, 0, 0, PACKETS});
        for (var i = 0; i < PACKETS; i++) {
            var length = 20 + i * 7 % 50;
            out.writeBytes(new byte[]{0, 0, 0, (byte) length});
            for (var j = 0; j < length; j++) {
                out.write(i * 31 + j);
            }
        }
        return out.toByteArray();
    }

    static final Map<MediaSource, Boolean> SHARED = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Demuxes the toy container: packet {@code i} has pts {@code 10 * i} in a 1/100 time base, duration
     * 10, and every third packet is a key frame.
     */
    static class ToyDemuxer implements Demuxer {
        private final MediaSource source;
        private final Bug bug;
        private final List<long[]> index = new ArrayList<>();
        private int next;

        ToyDemuxer(MediaSource source, Bug bug) {
            this.source = source;
            this.bug = bug;
            var header = readExactly(0, 8);
            if (header[0] != 'T' || header[3] != '1') {
                throw new MediaException.Malformed("not a toy file");
            }
            var count = header[7];
            var position = 8L;
            for (var i = 0; i < count; i++) {
                var length = readExactly(position, 4)[3] & 0xFF;
                index.add(new long[]{position + 4, length});
                position += 4 + length;
            }
            if (bug == Bug.SHARED_STATE && SHARED.put(source, true) != null) {
                next = 1;
            }
        }

        private byte[] readExactly(long position, int length) {
            var data = new byte[length];
            if (bug == Bug.ASSUMES_FULL_READS) {
                if (source.read(position, data, 0, length) < 0) {
                    throw new MediaException.Malformed("truncated");
                }
                return data;
            }
            if (bug == Bug.LOOPS_ON_SHORT_READ) {
                while (!Thread.currentThread().isInterrupted()) {
                    var read = source.read(position, data, 0, length);
                    if (read == length || read < 0) {
                        return data;
                    }
                }
                throw new MediaException.Malformed("interrupted");
            }
            if (bug == Bug.NEEDS_SIZE && source.size() < 0) {
                throw new MediaException.Malformed("no size");
            }
            source.readFully(position, data, 0, length);
            return data;
        }

        @Override
        public ContainerFormat format() {
            return ContainerFormat.IVF;
        }

        @Override
        public List<Track> tracks() {
            return List.of(DataTrack.builder().id(1).codec(Codec.SUBRIP).timeBase(new Rational(1, 100))
                    .duration(PACKETS * 10L).build());
        }

        @Override
        public Map<String, String> metadata() {
            return Map.of("title", "toy");
        }

        @Override
        public long durationMicros() {
            return PACKETS * 100_000L;
        }

        @Override
        public Packet read() {
            if (next >= index.size()) {
                return null;
            }
            var entry = index.get(next);
            byte[] data;
            try {
                data = readExactly(entry[0], (int) entry[1]);
            } catch (MediaException.Io failure) {
                if (bug == Bug.SWALLOWS_IO) {
                    return null;
                }
                if (bug == Bug.IO_AS_MALFORMED) {
                    throw new MediaException.Malformed("corrupt packet", failure);
                }
                throw failure;
            }
            var key = next % 3 == 0;
            var packet = new Packet(1, data, next * 10L, next * 10L, 10, key);
            next++;
            return packet;
        }

        @Override
        public boolean isSeekable() {
            return true;
        }

        @Override
        public void seek(long micros) {
            var ticks = Math.floorDiv(micros, 10_000);
            if (bug == Bug.MOVES_ON_FAILED_SEEK) {
                next = 0;
            }
            readExactly(0, 4);
            if (ticks >= PACKETS * 10L && bug != Bug.SEEKS_PAST_END) {
                next = PACKETS;
                return;
            }
            var target = (int) Math.clamp(ticks / 10, 0, PACKETS - 1);
            var key = target - target % 3;
            if (bug == Bug.SEEKS_LATE && key < target) {
                key = Math.min(PACKETS - 1, key + 3);
            }
            if (bug == Bug.SEEKS_TO_NON_KEY) {
                key = target;
            }
            next = key;
        }

        @Override
        public void close() {
            if (bug == Bug.CLOSES_SOURCE) {
                source.close();
            }
        }
    }

    /**
     * Demuxes the toy container as {@link ToyDemuxer} does, through a forward buffer that one source read
     * refills when it runs out, as a buffered reader does, and without an index until the first seek; a
     * source that delivers whole reads hands it the file in the read of its header.
     */
    static final class BufferedToyDemuxer implements Demuxer {
        private final MediaSource source;
        private final boolean swallows;
        private final int count;
        private byte[] cache = new byte[0];
        private int cached;
        private long position = 8;
        private int next;

        BufferedToyDemuxer(MediaSource source, boolean swallows) {
            this.source = source;
            this.swallows = swallows;
            var header = bytes(0, 8);
            if (header[0] != 'T' || header[3] != '1') {
                throw new MediaException.Malformed("not a toy file");
            }
            count = header[7];
        }

        private byte[] bytes(long at, int length) {
            while (cached < at + length) {
                if (cached == cache.length) {
                    cache = Arrays.copyOf(cache, Math.max(4096, cache.length * 2));
                }
                var read = source.read(cached, cache, cached, cache.length - cached);
                if (read < 0) {
                    throw new MediaException.Malformed("truncated");
                }
                cached += read;
            }
            return Arrays.copyOfRange(cache, (int) at, (int) at + length);
        }

        @Override
        public ContainerFormat format() {
            return ContainerFormat.IVF;
        }

        @Override
        public List<Track> tracks() {
            return List.of(DataTrack.builder().id(1).codec(Codec.SUBRIP).timeBase(new Rational(1, 100))
                    .duration(PACKETS * 10L).build());
        }

        @Override
        public Map<String, String> metadata() {
            return Map.of("title", "toy");
        }

        @Override
        public long durationMicros() {
            return PACKETS * 100_000L;
        }

        @Override
        public Packet read() {
            if (next >= count) {
                return null;
            }
            byte[] data;
            try {
                var length = bytes(position, 4)[3] & 0xFF;
                data = bytes(position + 4, length);
            } catch (MediaException.Io failure) {
                if (swallows) {
                    return null;
                }
                throw failure;
            }
            var packet = new Packet(1, data, next * 10L, next * 10L, 10, next % 3 == 0);
            position += 4 + data.length;
            next++;
            return packet;
        }

        @Override
        public boolean isSeekable() {
            return true;
        }

        @Override
        public void seek(long micros) {
            var ticks = Math.floorDiv(micros, 10_000);
            if (ticks >= PACKETS * 10L) {
                bytes(0, 8);
                next = count;
                return;
            }
            var target = (int) Math.clamp(ticks / 10, 0, PACKETS - 1);
            var key = target - target % 3;
            var at = 8L;
            for (var i = 0; i < key; i++) {
                at += 4 + (bytes(at, 4)[3] & 0xFF);
            }
            position = at;
            next = key;
        }

        @Override
        public void close() {
        }
    }

    static DemuxerAsserts.Builder harness(Bug bug) {
        return DemuxerAsserts.of("toy", toy())
                .factory(source -> bug == Bug.BUFFERS_AND_SWALLOWS_IO ? new BufferedToyDemuxer(source, true)
                        : new ToyDemuxer(source, bug))
                .noDump()
                .timeout(Duration.ofSeconds(bug == Bug.LOOPS_ON_SHORT_READ ? 1 : 30));
    }

    static String failureText(Throwable failure) {
        var text = new StringBuilder(String.valueOf(failure.getMessage()));
        if (failure instanceof MultipleFailuresError multiple) {
            for (var each : multiple.getFailures()) {
                text.append('\n').append(each.getMessage());
            }
        }
        return text.toString();
    }

    @Test
    @DisplayName("passes correct demuxers, one reading the source directly and one through a buffer")
    void correct() {
        harness(null).run();
        DemuxerAsserts.of("toy", toy()).factory(source -> new BufferedToyDemuxer(source, false)).noDump().run();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Bug.class)
    @DisplayName("fails every deliberately broken demuxer, naming the broken behavior")
    void broken(Bug bug) {
        SHARED.clear();
        var failure = assertThrows(AssertionError.class, () -> harness(bug).run());
        var text = failureText(failure);
        var expected = switch (bug) {
            case ASSUMES_FULL_READS -> "ONE_BYTE";
            case NEEDS_SIZE -> "UNKNOWN_LENGTH";
            case SWALLOWS_IO -> "the fault was swallowed";
            case IO_AS_MALFORMED -> "instead of MediaException.Io";
            case CLOSES_SOURCE -> "closed its source";
            case SEEKS_LATE -> "is the last sync packet at or before the target";
            case SEEKS_PAST_END -> "at or past the end of every track";
            case MOVES_ON_FAILED_SEEK -> "the demuxer must stay where it was";
            case SHARED_STATE -> "second open";
            case LOOPS_ON_SHORT_READ -> "no result within";
            case SEEKS_TO_NON_KEY -> "not a sync point";
            case BUFFERS_AND_SWALLOWS_IO -> "(random chunks): the fault was swallowed";
        };
        assertTrue(text.contains(expected), () -> bug + ": expected a failure mentioning <" + expected + "> in\n" + text);
    }

    @Test
    @DisplayName("accepts a demuxer whose seek lands on an earlier key frame only with AT_OR_BEFORE precision")
    void precision() {
        var data = toy();
        Function<MediaSource, Demuxer> early = source -> new ToyDemuxer(source, null) {
            @Override
            public void seek(long micros) {
                super.seek(micros);
                var landed = read();
                super.seek(landed == null ? micros : Math.max(-1, landed.pts() * 10_000 - 30_000));
            }
        };
        assertThrows(AssertionError.class, () -> DemuxerAsserts.of("toy", data).factory(early).noDump().run());
        DemuxerAsserts.of("toy", data).factory(early).noDump().seekPrecision(SeekPrecision.AT_OR_BEFORE).run();
    }

    @Nested
    @DisplayName("simulated sources")
    class Sources {
        @ParameterizedTest(name = "{0}")
        @EnumSource(IoVariant.class)
        @DisplayName("deliver the same bytes as the array, within the source contract")
        void variants(IoVariant variant) {
            var data = toy();
            try (var source = variant.open(data)) {
                assertEquals(variant.isSized() ? data.length : -1, source.size());
                var copy = new byte[data.length];
                var position = 0;
                var shortReads = 0;
                while (position < data.length) {
                    var read = source.read(position, copy, position, data.length - position);
                    assertTrue(read > 0, "a read before the end stores a byte");
                    if (read < data.length - position) {
                        shortReads++;
                    }
                    position += read;
                }
                assertEquals(-1, source.read(data.length, new byte[1], 0, 1));
                assertEquals(0, source.read(data.length, new byte[1], 0, 0));
                assertArrayEquals(data, copy);
                if (variant == IoVariant.ONE_BYTE || variant == IoVariant.UNKNOWN_LENGTH_ONE_BYTE) {
                    assertEquals(data.length - 1, shortReads);
                }
            }
        }

        @Test
        @DisplayName("deliver random chunk sizes that depend on position and length only")
        void deterministicChunks() {
            var data = toy();
            var first = SimulatedSource.of(data, true, SimulatedSource.Chunking.RANDOM, 9);
            var second = SimulatedSource.of(data, true, SimulatedSource.Chunking.RANDOM, 9);
            var sizes = new HashSet<Integer>();
            for (var position = 0; position < 200; position++) {
                var a = first.read(position, new byte[100], 0, 100);
                var b = second.read(199 - position, new byte[100], 0, 100);
                sizes.add(a);
                assertEquals(a, first.read(position, new byte[100], 0, 100));
                assertTrue(b >= 1 && b <= 100);
            }
            assertTrue(sizes.size() > 10, "chunk sizes vary: " + sizes);
            assertTrue(sizes.contains(1), "one-byte deliveries occur");
        }

        @Test
        @DisplayName("fail once at the chosen read with MediaException.Io caused by the injected fault")
        void transientFault() {
            var source = SimulatedSource.failing(toy(), 2, false);
            source.read(0, new byte[4], 0, 4);
            source.read(4, new byte[4], 0, 4);
            var failure = assertThrows(MediaException.Io.class, () -> source.read(8, new byte[4], 0, 4));
            assertInstanceOf(SimulatedSource.InjectedFault.class, failure.getCause());
            assertEquals(2, ((SimulatedSource.InjectedFault) failure.getCause()).readIndex());
            assertTrue(Behaviors.isCleanFault(failure));
            assertEquals(4, source.read(8, new byte[4], 0, 4));
            assertEquals(1, source.faults());
            assertEquals(0, source.read(8, new byte[4], 0, 0), "a zero-length read is not counted");
            assertEquals(4, source.reads());
        }

        @Test
        @DisplayName("fail every read from the chosen one when permanent")
        void permanentFault() {
            var source = SimulatedSource.failing(toy(), 1, true);
            source.read(0, new byte[4], 0, 4);
            for (var i = 0; i < 3; i++) {
                assertThrows(MediaException.Io.class, () -> source.read(0, new byte[4], 0, 4));
            }
            assertEquals(3, source.faults());
        }

        @Test
        @DisplayName("count closes and reads after close")
        void closes() {
            var source = SimulatedSource.of(toy());
            source.close();
            source.read(0, new byte[1], 0, 1);
            assertEquals(1, source.closes());
            assertEquals(1, source.readsAfterClose());
        }

        @Test
        @DisplayName("spread bounded fault indices over the whole read count")
        void faultIndices() {
            assertEquals(List.of(0L, 1L, 2L), Behaviors.faultIndices(3, 10));
            var spread = Behaviors.faultIndices(1000, 10);
            assertEquals(10, spread.size());
            assertEquals(List.of(0L, 1L, 2L, 3L, 4L), spread.subList(0, 5));
            assertEquals(999L, spread.getLast());
            assertEquals(Set.copyOf(spread).size(), spread.size());
        }
    }

    @Nested
    @DisplayName("golden dumps")
    class Goldens {
        @Test
        @DisplayName("fail when missing, pass when equal, name the first differing line, and update on request")
        void lifecycle(@TempDir Path directory) throws Exception {
            var previous = System.getProperty("smalt.test.dumps");
            var update = System.getProperty("smalt.test.updateDumps");
            System.setProperty("smalt.test.dumps", directory.toString());
            System.clearProperty("smalt.test.updateDumps");
            try {
                var missing = assertThrows(AssertionFailedError.class, () -> Dumps.assertMatches("a/b", "x\n"));
                assertTrue(missing.getMessage().contains("missing golden dump"));
                System.setProperty("smalt.test.updateDumps", "true");
                Dumps.assertMatches("a/b", "one\ntwo");
                assertEquals("one\ntwo\n", Files.readString(directory.resolve("a/b.dump")));
                System.clearProperty("smalt.test.updateDumps");
                Dumps.assertMatches("a/b", "one\ntwo\n");
                Files.writeString(directory.resolve("a/b.dump"), "one\r\ntwo\r\n");
                Dumps.assertMatches("a/b", "one\ntwo\n");
                var differs = assertThrows(AssertionFailedError.class, () -> Dumps.assertMatches("a/b", "one\nthree\n"));
                assertTrue(differs.getMessage().contains("at line 2: expected <two> but was <three>"),
                        differs.getMessage());
                assertThrows(IllegalArgumentException.class, () -> Dumps.golden("../escape"));
            } finally {
                restore("smalt.test.dumps", previous);
                restore("smalt.test.updateDumps", update);
            }
        }

        @Test
        @DisplayName("the harness compares its dump with the golden file")
        void harnessDump(@TempDir Path directory) throws Exception {
            var previous = System.getProperty("smalt.test.dumps");
            var update = System.getProperty("smalt.test.updateDumps");
            System.setProperty("smalt.test.dumps", directory.toString());
            try {
                System.setProperty("smalt.test.updateDumps", "true");
                DemuxerAsserts.of("toy", toy()).factory(source -> new ToyDemuxer(source, null)).run();
                var text = Files.readString(directory.resolve("toy.dump"));
                assertTrue(text.contains("packets 40\n0 t1 pts=0 dts=0 dur=10 flags=KEY size=20 crc="), text);
                assertTrue(text.contains("seek 1333333: t1@12\n"), text);
                System.clearProperty("smalt.test.updateDumps");
                Files.writeString(directory.resolve("toy.dump"), text.replace("seek 1333333: t1@12", "seek 1333333: t1@9"));
                var failure = assertThrows(AssertionError.class,
                        () -> DemuxerAsserts.of("toy", toy()).factory(source -> new ToyDemuxer(source, null)).run());
                assertTrue(failureText(failure).contains("expected <seek 1333333: t1@9> but was <seek 1333333: t1@12>"),
                        failureText(failure));
            } finally {
                restore("smalt.test.dumps", previous);
                restore("smalt.test.updateDumps", update);
            }
        }

        private static void restore(String key, String value) {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        }
    }
}
