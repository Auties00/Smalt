package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link PresentationOrder}: presentation times from order counts and durations, decoding times from the
 * presentation times of earlier places, the decoder output model that discards the pictures waiting at a picture
 * with {@code no_output_of_prior_pics_flag}, and resumption from an index.
 */
@DisplayName("Presentation ordering of elementary streams")
class PresentationOrderTest {
    /** A picture fed to the ordering: order count, period, duration, whether it is output and discards. */
    private record Picture(long poc, long period, int duration, boolean output, boolean discardPrior) {
        static Picture of(long poc, long period, int duration) {
            return new Picture(poc, period, duration, true, false);
        }
    }

    /** A polled picture: its index in decoding order, presentation and decoding time. */
    private record Timed(int index, long pts, long dts) {
    }

    private static List<Timed> run(PresentationOrder order, List<Picture> pictures) {
        var result = new ArrayList<Timed>();
        var next = 0;
        while (true) {
            var end = next == pictures.size();
            var entry = order.poll(end);
            if (entry != null) {
                result.add(new Timed((Integer) entry.payload, entry.pts(), order.dts(entry)));
                continue;
            }
            if (end) {
                return result;
            }
            var picture = pictures.get(next);
            order.add(new PresentationOrder.Entry(picture.poc(), picture.period(), picture.output(),
                    picture.duration(), picture.discardPrior(), next));
            next++;
        }
    }

    private static void assertValid(List<Picture> pictures, List<Timed> timed) {
        assertEquals(pictures.size(), timed.size());
        for (var i = 0; i < timed.size(); i++) {
            assertEquals(i, timed.get(i).index(), "pictures leave in decoding order");
            var current = timed.get(i);
            if (current.pts() != Packet.NO_TIMESTAMP) {
                assertTrue(current.dts() <= current.pts(), "decoding time " + current.dts() + " after presentation "
                        + current.pts() + " at " + i);
            }
            if (i > 0) {
                assertTrue(current.dts() >= timed.get(i - 1).dts(), "decreasing decoding time at " + i);
            }
        }
        var shown = timed.stream().filter(entry -> entry.pts() != Packet.NO_TIMESTAMP)
                .sorted(Comparator.comparingLong(Timed::pts)).toList();
        for (var i = 1; i < shown.size(); i++) {
            var previous = shown.get(i - 1);
            assertEquals(previous.pts() + pictures.get(previous.index()).duration(), shown.get(i).pts(),
                    "each picture starts when the previous one in presentation order ends");
        }
    }

    @Test
    @DisplayName("times a reordered period at constant and repeated-field durations")
    void reordered() {
        // I0 P3 B1 B2, then P6 B4 B5: decoding order with two pictures of reordering
        var constant = List.of(Picture.of(0, 0, 2), Picture.of(6, 0, 2), Picture.of(2, 0, 2), Picture.of(4, 0, 2),
                Picture.of(12, 0, 2), Picture.of(8, 0, 2), Picture.of(10, 0, 2));
        var timed = run(new PresentationOrder(2, 2, 0, null), constant);
        assertValid(constant, timed);
        assertEquals(List.of(0L, 6L, 2L, 4L, 12L, 8L, 10L), timed.stream().map(Timed::pts).toList());
        assertEquals(List.of(-4L, -2L, 0L, 2L, 4L, 6L, 8L), timed.stream().map(Timed::dts).toList());
        // 3:2 pulldown: the frames alternate three and two fields
        var pulldown = List.of(Picture.of(0, 0, 3), Picture.of(6, 0, 2), Picture.of(2, 0, 2), Picture.of(4, 0, 3),
                Picture.of(12, 0, 3), Picture.of(8, 0, 2), Picture.of(10, 0, 2));
        var repeated = run(new PresentationOrder(2, 2, 0, null), pulldown);
        assertValid(pulldown, repeated);
        assertEquals(List.of(0L, 8L, 3L, 5L, 14L, 10L, 12L), repeated.stream().map(Timed::pts).toList());
    }

    @Test
    @DisplayName("drops the pictures still waiting for output at a picture that discards them, and only then")
    void discardsPrior() {
        // reorder 2: after I0 P4 B2 the decoder outputs I0 and holds B2 and P4
        var pictures = new ArrayList<>(List.of(Picture.of(0, 0, 2), Picture.of(4, 0, 2), Picture.of(2, 0, 2),
                new Picture(0, 1, 2, true, true), Picture.of(2, 1, 2)));
        var timed = run(new PresentationOrder(2, 2, 0, null), pictures);
        assertValid(pictures, timed);
        assertEquals(List.of(0L, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 2L, 4L),
                timed.stream().map(Timed::pts).toList());
        pictures.set(3, Picture.of(0, 1, 2));
        var kept = run(new PresentationOrder(2, 2, 0, null), pictures);
        assertValid(pictures, kept);
        assertEquals(List.of(0L, 4L, 2L, 6L, 8L), kept.stream().map(Timed::pts).toList());
        // with a reorder bound of 0 nothing waits, so nothing is discarded
        var ordered = List.of(Picture.of(0, 0, 2), Picture.of(2, 0, 2), new Picture(0, 1, 2, true, true));
        assertFalse(run(new PresentationOrder(0, 0, 0, null), ordered).stream()
                .anyMatch(entry -> entry.pts() == Packet.NO_TIMESTAMP));
    }

    @Test
    @DisplayName("outputs a waiting picture once it reaches the latency limit")
    void latency() {
        // reorder 4 keeps I0, P8 and B4 waiting; with a latency of 1, B4 coming before P8 makes P8 late, which
        // outputs the waiting pictures up to it
        var pictures = List.of(Picture.of(0, 0, 2), Picture.of(8, 0, 2), Picture.of(4, 0, 2),
                new Picture(0, 1, 2, true, true));
        var limited = run(new PresentationOrder(4, 4, 1, null), pictures);
        var unlimited = run(new PresentationOrder(4, 4, 0, null), pictures);
        assertValid(pictures, limited);
        assertValid(pictures, unlimited);
        assertEquals(0, limited.stream().filter(entry -> entry.pts() == Packet.NO_TIMESTAMP).count());
        assertEquals(3, unlimited.stream().filter(entry -> entry.pts() == Packet.NO_TIMESTAMP).count());
    }

    @Test
    @DisplayName("outputs pictures early when the decoded picture buffer is full of references")
    void bufferFullness() {
        // I0 P2 P4 P6 with four pictures of reordering, then an IDR picture that discards what still waits
        for (var keep : new String[]{"none", "all", "previous"}) {
            var order = new PresentationOrder(4, 4, 0, null);
            var discarded = 0;
            var timed = new ArrayList<PresentationOrder.Entry>();
            for (var i = 0; i < 5; i++) {
                var poc = i < 4 ? 2L * i : 0;
                var period = i < 4 ? 0 : 1;
                java.util.function.LongPredicate references = switch (keep) {
                    case "all" -> other -> true;
                    case "previous" -> other -> other == poc - 2;
                    default -> null;
                };
                var entry = new PresentationOrder.Entry(poc, period, true, 2, i == 4, 4, 0,
                        references == null ? 0 : 3, references, true, false, i);
                order.add(entry);
                timed.add(entry);
            }
            while (order.poll(true) != null) {
                // drain
            }
            for (var entry : timed) {
                discarded += entry.pts() == Packet.NO_TIMESTAMP ? 1 : 0;
            }
            var expected = switch (keep) {
                case "all" -> 1;
                case "previous" -> 3;
                default -> 4;
            };
            assertEquals(expected, discarded, "pictures discarded keeping " + keep + " references");
        }
    }

    @Test
    @DisplayName("outputs a non-reference picture at once when the buffer is full and it comes first")
    void outputWhenFull() {
        // a buffer of 2 holding reference frames I0 and P4: B2 (not a reference) is output without being stored,
        // so the IDR picture finds nothing waiting but P4
        var order = new PresentationOrder(2, 2, 0, null);
        var entries = new ArrayList<PresentationOrder.Entry>();
        long[][] pictures = {{0, 0, 1}, {4, 0, 1}, {2, 0, 0}, {0, 1, 1}};
        for (var i = 0; i < pictures.length; i++) {
            var picture = pictures[i];
            var entry = new PresentationOrder.Entry(picture[0], picture[1], true, 2, i == 3, 2, 0, 2,
                    other -> true, picture[2] == 1, true, i);
            order.add(entry);
            entries.add(entry);
        }
        while (order.poll(true) != null) {
            // drain
        }
        assertEquals(List.of(0L, Packet.NO_TIMESTAMP, 2L, 4L),
                entries.stream().map(PresentationOrder.Entry::pts).toList());
    }

    @Test
    @DisplayName("gives pictures that are not output no presentation time and no decoding time of their own")
    void notOutput() {
        var pictures = List.of(Picture.of(0, 0, 2), new Picture(2, 0, 2, false, false), Picture.of(4, 0, 2));
        var timed = run(new PresentationOrder(1, 1, 0, null), pictures);
        assertValid(pictures, timed);
        assertEquals(List.of(0L, Packet.NO_TIMESTAMP, 2L), timed.stream().map(Timed::pts).toList());
        assertEquals(timed.get(1).dts(), timed.get(2).dts());
    }

    @RepeatedTest(40)
    @DisplayName("keeps every random stream valid, and resumes at each period exactly as a linear read")
    void random(RepetitionInfo repetition) {
        var random = new Random(repetition.getCurrentRepetition());
        var reorder = random.nextInt(5);
        var pictures = new ArrayList<Picture>();
        var starts = new ArrayList<Integer>();
        for (var period = 0; period < 6; period++) {
            starts.add(pictures.size());
            var count = 1 + random.nextInt(12);
            // a decoding order that moves each picture at most `reorder` places before its output place
            var order = new ArrayList<Integer>();
            for (var i = 0; i < count; i++) {
                order.add(i);
            }
            for (var i = 0; i < count; i++) {
                var j = Math.min(count - 1, i + random.nextInt(reorder + 1));
                if (j != i && order.get(i) == i) {
                    var moved = order.remove(j);
                    order.add(i, moved);
                }
            }
            for (var i = 0; i < count; i++) {
                var output = random.nextInt(10) != 0;
                pictures.add(new Picture(2L * order.get(i), period, 1 + random.nextInt(6), output,
                        i == 0 && period > 0 && random.nextBoolean()));
            }
        }
        var latency = random.nextBoolean() ? 0 : 2;
        var times = new PresentationOrder.Times(0);
        var linearOrder = new PresentationOrder(reorder, reorder, latency, times);
        var linear = run(linearOrder, pictures);
        assertValid(pictures, linear);
        var firstDuration = Math.max(0, linearOrder.firstDuration());
        // resuming needs the counts the full ordering had at the first picture of each period
        for (var start : starts.subList(1, starts.size())) {
            var outputs = 0L;
            var clock = 0L;
            for (var i = 0; i < start; i++) {
                if (pictures.get(i).output()) {
                    outputs++;
                }
                if (linear.get(i).pts() != Packet.NO_TIMESTAMP) {
                    clock = Math.max(clock, linear.get(i).pts() + pictures.get(i).duration());
                }
            }
            var resumed = run(new PresentationOrder(reorder, reorder, latency, outputs, clock, times, firstDuration),
                    pictures.subList(start, pictures.size()));
            for (var i = 0; i < resumed.size(); i++) {
                var expected = linear.get(start + i);
                if (expected.pts() == Packet.NO_TIMESTAMP || resumed.get(i).pts() == Packet.NO_TIMESTAMP) {
                    continue;
                }
                assertEquals(expected.pts(), resumed.get(i).pts(), "presentation time resumed at " + start);
                assertEquals(expected.dts(), resumed.get(i).dts(), "decoding time resumed at " + start);
            }
        }
    }

    @Test
    @DisplayName("returns pictures that are not output without holding the whole stream for a first output picture")
    void untimedRun() {
        var order = new PresentationOrder(2, 2, 0, null);
        var pictures = new ArrayList<Picture>();
        for (var i = 0; i < 2000; i++) {
            pictures.add(new Picture(i, 0, 2, false, false));
        }
        for (var i = 0; i < 20; i++) {
            pictures.add(Picture.of(2000 + i, 0, 2));
        }
        var result = new ArrayList<Timed>();
        var largest = 0;
        for (var next = 0; next <= pictures.size(); ) {
            var end = next == pictures.size();
            var entry = order.poll(end);
            if (entry != null) {
                result.add(new Timed((Integer) entry.payload, entry.pts(), order.dts(entry)));
                continue;
            }
            if (end) {
                break;
            }
            var picture = pictures.get(next);
            order.add(new PresentationOrder.Entry(picture.poc(), picture.period(), picture.output(),
                    picture.duration(), picture.discardPrior(), next));
            largest = Math.max(largest, order.size());
            next++;
        }
        assertTrue(largest <= 300, "the ordering held " + largest + " pictures");
        assertValid(pictures, result);
        assertEquals(1, order.firstDuration());
    }
}
