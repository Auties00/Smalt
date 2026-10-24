package com.github.auties00.smalt.testkit.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the seek contract of {@link AnimationReader}: after {@code seek(t)} the next {@code read()} returns the
 * frame a read from the start shows at {@code t} (the last one whose timestamp is at or before it, the first one
 * for a time before zero, none at or past the end), with the same timestamp, duration and pixels, and the reads
 * after it continue the animation from there.
 */
public final class AnimationSeeks {
    /** One frame read from the start: its timing and a copy of its pixels. */
    public record Frame(long timestamp, long duration, Object pixels) {
    }

    private AnimationSeeks() {
        throw new AssertionError();
    }

    /** {@return every frame of a reader, read from the start, with copies of their pixels} */
    public static List<Frame> sequential(AnimationReader reader) {
        var frames = new ArrayList<Frame>();
        for (var frame = reader.read(); frame != null; frame = reader.read()) {
            frames.add(copy(frame));
        }
        return frames;
    }

    private static Frame copy(AnimationFrame frame) {
        return new Frame(frame.timestampMicros(), frame.durationMicros(), pixels(frame.picture()));
    }

    /** {@return a copy of the pixels of an RGBA picture without row padding: a byte or short array} */
    public static Object pixels(Picture picture) {
        return switch (picture) {
            case RgbaPicture rgba -> {
                var out = new byte[4 * rgba.width() * rgba.height()];
                for (var y = 0; y < rgba.height(); y++) {
                    System.arraycopy(rgba.pixels(), y * rgba.stride(), out, 4 * y * rgba.width(), 4 * rgba.width());
                }
                yield out;
            }
            case Rgba16Picture rgba -> {
                var out = new short[4 * rgba.width() * rgba.height()];
                for (var y = 0; y < rgba.height(); y++) {
                    System.arraycopy(rgba.pixels(), y * rgba.stride(), out, 4 * y * rgba.width(), 4 * rgba.width());
                }
                yield out;
            }
            default -> throw new IllegalArgumentException("not an RGBA frame: " + picture);
        };
    }

    /**
     * Seeks a reader to every frame start, the middle and the last microsecond of every frame, times before zero
     * and at or past the end, and random times in random order, each followed by up to two reads, comparing every
     * frame with a read from the start. A second read from the start must return the same frames, so that a
     * decoder whose output depends on thread scheduling fails here rather than as a seek mismatch.
     *
     * @param opener opens the animation from its start
     * @param seed the seed of the random times and order
     */
    public static void assertMatchesSequential(Supplier<AnimationReader> opener, long seed) {
        List<Frame> expected;
        try (var reader = opener.get()) {
            expected = sequential(reader);
        }
        assertTrue(expected.size() > 1, "the animation has several frames");
        try (var reader = opener.get()) {
            var again = sequential(reader);
            assertEquals(expected.size(), again.size(), "frames of a second read from the start");
            for (var i = 0; i < expected.size(); i++) {
                if (!samePixels(expected.get(i).pixels(), again.get(i).pixels())) {
                    fail("frame " + i + " of a second read from the start differs from the first: "
                            + difference(expected.get(i).pixels(), again.get(i).pixels(), expected));
                }
            }
        }
        var last = expected.getLast();
        var end = last.timestamp() + last.duration();
        var times = new ArrayList<Long>(List.of(-1_000_000L, -1L, end, end + 1, end + 1_000_000L));
        for (var frame : expected) {
            times.add(frame.timestamp());
            times.add(frame.timestamp() + frame.duration() / 2);
            if (frame.duration() > 0) {
                times.add(frame.timestamp() + frame.duration() - 1);
            }
        }
        var random = new Random(seed);
        for (var i = 0; i < 40; i++) {
            times.add(random.nextLong(-end / 4 - 1, end + end / 4 + 2));
        }
        Collections.shuffle(times, random);
        try (var reader = opener.get()) {
            assertTrue(reader.isSeekable(), "the reader seeks");
            for (var time : times) {
                reader.seek(time);
                var index = shownAt(expected, time);
                var reads = 1 + random.nextInt(2);
                for (var read = 0; read < reads; read++, index++) {
                    var frame = reader.read();
                    if (index < 0 || index >= expected.size()) {
                        assertNull(frame, "no frame after a seek to " + time + " us");
                        break;
                    }
                    assertNotNull(frame, "frame " + index + " after a seek to " + time + " us");
                    var actual = copy(frame);
                    var want = expected.get(index);
                    assertEquals(want.timestamp(), actual.timestamp(), "timestamp of frame " + index + " at " + time);
                    assertEquals(want.duration(), actual.duration(), "duration of frame " + index + " at " + time);
                    if (!samePixels(want.pixels(), actual.pixels())) {
                        fail("frame " + index + " after a seek to " + time + " us differs from a read from the start: "
                                + difference(want.pixels(), actual.pixels(), expected));
                    }
                }
            }
        }
    }

    /**
     * Checks that a seek restarts the decoding at a sync sample rather than at the start: the damaged animation, some
     * of whose samples before the sync sample of frame {@code target} are corrupt, fails a read from the start, yet a
     * seek into frame {@code target} returns frame {@code target} and the frames after it exactly as the intact
     * animation does, both on a reader that has read nothing and again on the same reader after it read to the end.
     * The first seek comes before any read because a decoder that holds pictures back may already have consumed the
     * corrupt samples when it returns the first frame; the samples a reader decodes to find the time of its first
     * frame stay intact.
     *
     * @param intact opens the intact animation
     * @param damaged opens the damaged animation
     * @param target the index of a frame at or after the sync sample that follows the corrupt samples
     */
    public static void assertSeeksPastCorruptSamples(Supplier<AnimationReader> intact, Supplier<AnimationReader> damaged,
                                                     int target) {
        List<Frame> expected;
        try (var reader = intact.get()) {
            expected = sequential(reader);
        }
        assertTrue(target > 0 && target < expected.size(), "the target frame exists");
        assertThrows(MediaException.class, () -> {
            try (var reader = damaged.get()) {
                sequential(reader);
            }
        }, "a read from the start decodes the corrupt samples");
        try (var reader = damaged.get()) {
            var want = expected.get(target);
            for (var pass = 0; pass < 2; pass++) {
                reader.seek(want.timestamp() + want.duration() / 2);
                for (var index = target; index < expected.size(); index++) {
                    var frame = reader.read();
                    assertNotNull(frame, "frame " + index + " after seek " + pass);
                    var actual = copy(frame);
                    assertEquals(expected.get(index).timestamp(), actual.timestamp(), "timestamp of frame " + index);
                    if (!samePixels(expected.get(index).pixels(), actual.pixels())) {
                        fail("frame " + index + " after seek " + pass + " differs from the intact animation: "
                                + difference(expected.get(index).pixels(), actual.pixels(), expected));
                    }
                }
                assertNull(reader.read());
            }
        }
    }

    /** {@return the index of the frame shown at a time, or -1 at or past the end} */
    private static int shownAt(List<Frame> frames, long time) {
        if (time < 0) {
            return 0;
        }
        for (var i = 0; i < frames.size(); i++) {
            var frame = frames.get(i);
            if (frame.timestamp() + frame.duration() > time) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Describes how a frame differs from the one expected: the number of differing samples, the first of them with
     * its pixel, channel and both values, and the frames read from the start whose pixels the frame has instead.
     */
    private static String difference(Object expected, Object actual, List<Frame> frames) {
        var want = samples(expected);
        var got = samples(actual);
        if (want.length != got.length) {
            return got.length + " samples instead of " + want.length;
        }
        var count = 0;
        var first = -1;
        for (var i = 0; i < want.length; i++) {
            if (want[i] != got[i]) {
                count++;
                if (first < 0) {
                    first = i;
                }
            }
        }
        var matches = new ArrayList<Integer>();
        for (var i = 0; i < frames.size(); i++) {
            if (samePixels(frames.get(i).pixels(), actual)) {
                matches.add(i);
            }
        }
        return count + " of " + want.length + " samples differ, the first at pixel " + first / 4 + " channel "
                + "RGBA".charAt(first % 4) + " (" + want[first] + " expected, " + got[first] + " read); the frame equals "
                + (matches.isEmpty() ? "no frame" : "frames " + matches) + " of the read from the start";
    }

    private static int[] samples(Object pixels) {
        return switch (pixels) {
            case byte[] bytes -> {
                var out = new int[bytes.length];
                for (var i = 0; i < bytes.length; i++) {
                    out[i] = bytes[i] & 0xFF;
                }
                yield out;
            }
            case short[] shorts -> {
                var out = new int[shorts.length];
                for (var i = 0; i < shorts.length; i++) {
                    out[i] = shorts[i] & 0xFFFF;
                }
                yield out;
            }
            default -> throw new IllegalArgumentException("not a sample array: " + pixels);
        };
    }

    private static boolean samePixels(Object expected, Object actual) {
        return expected instanceof byte[] a && actual instanceof byte[] b ? Arrays.equals(a, b)
                : expected instanceof short[] c && actual instanceof short[] d && Arrays.equals(c, d);
    }
}
