package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.HeifSequenceBuilder;
import com.github.auties00.smalt.testkit.image.AnimationSeeks;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pairs the color and alpha pictures of HEIC image sequences by sample index: alpha tracks timed faster, slower
 * and irregularly against their color tracks, sync samples placed differently, and a color track with B-frames
 * whose display order differs from the decoding order of an alpha track without them. Every alpha sample is a flat
 * gray level that names its index, so each frame shows which alpha sample it was paired with, and every seek must
 * return the frames a read from the start does ({@link AnimationSeeks}).
 */
@DisplayName("HEIC sequence alpha pairing")
class HeicSequenceAlphaTest {
    /** The frames of every test sequence. */
    private static final int FRAMES = 13;

    /** The color track's duration of every sample, in units of the 1000 Hz timescale. */
    private static final int DELTA = 100;

    /** The samples, sync flags, composition offsets and configuration of the HEVC track of an MP4 file. */
    private record Samples(List<byte[]> data, int[] sync, int[] offsets, byte[] config, int width, int height) {
        static Samples of(byte[] file) {
            var data = new ArrayList<byte[]>();
            var sync = new ArrayList<Integer>();
            var offsets = new ArrayList<Integer>();
            try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.keyframe()) {
                        sync.add(data.size());
                    }
                    data.add(packet.data());
                    // rescale the composition offset to the DELTA units of one frame
                    offsets.add((int) Math.round((double) (packet.pts() - packet.dts()) * DELTA / packet.duration()));
                }
                var minimum = offsets.stream().mapToInt(Integer::intValue).min().orElse(0);
                return new Samples(data, sync.stream().mapToInt(Integer::intValue).toArray(),
                        offsets.stream().mapToInt(offset -> offset - minimum).toArray(),
                        track.codecConfig().orElseThrow(), track.width(), track.height());
            }
        }

        boolean reorders() {
            return Arrays.stream(offsets).anyMatch(offset -> offset != 0);
        }
    }

    /** Encodes an x265 color sequence with a sync sample every {@code interval} frames and up to {@code bframes} B-frames. */
    private static Samples color(int interval, int bframes) {
        return Samples.of(Fixtures.read(Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i",
                "testsrc2=size=64x48:rate=10"), List.of("-frames:v", Integer.toString(FRAMES), "-pix_fmt", "yuv420p",
                "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "keyint=" + interval + ":min-keyint="
                        + interval + ":bframes=" + bframes + ":b-adapt=0:scenecut=0:open-gop=0:log-level=error",
                "-tag:v", "hvc1"))));
    }

    /** Encodes an x265 alpha sequence without B-frames whose frame {@code i} is the flat level {@link #level(int)}. */
    private static Samples alpha(int interval) {
        return Samples.of(Fixtures.read(Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i",
                "nullsrc=size=64x48:rate=10,geq=lum='" + 20 + "+N*" + 16 + "':cb=128:cr=128,format=gray"),
                List.of("-frames:v", Integer.toString(FRAMES), "-pix_fmt", "gray", "-color_range", "pc", "-c:v",
                        "libx265", "-preset", "ultrafast", "-x265-params", "keyint=" + interval + ":min-keyint="
                                + interval + ":bframes=0:scenecut=0:open-gop=0:log-level=error", "-tag:v", "hvc1"))));
    }

    /** {@return the gray level of alpha sample {@code index}} */
    private static int level(int index) {
        return 20 + 16 * index;
    }

    private static byte[] sequence(Samples color, Samples alpha, int[] alphaDurations) {
        var builder = new HeifSequenceBuilder("msf1", 1000, "msf1", "iso8", "mif1");
        var colorTrack = builder.track("hvc1", color.width(), color.height(), DELTA, color.data(),
                HeifBuilder.box("hvcC", color.config()));
        builder.syncSamples(colorTrack, color.sync());
        if (color.reorders()) {
            builder.compositionOffsets(colorTrack, color.offsets());
        }
        var alphaTrack = builder.track("hvc1", alpha.width(), alpha.height(), DELTA, alpha.data(),
                HeifBuilder.box("hvcC", alpha.config()),
                HeifSequenceBuilder.auxi("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));
        builder.reference("auxl", alphaTrack, colorTrack).syncSamples(alphaTrack, alpha.sync())
                .durations(alphaTrack, alphaDurations);
        return builder.build();
    }

    private static int[] constant(int delta) {
        var durations = new int[FRAMES];
        Arrays.fill(durations, delta);
        return durations;
    }

    /** {@return the mean alpha of an RGBA frame} */
    private static double meanAlpha(RgbaPicture picture) {
        var sum = 0L;
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                sum += picture.pixels()[y * picture.stride() + 4 * x + 3] & 0xFF;
            }
        }
        return (double) sum / ((long) picture.width() * picture.height());
    }

    /**
     * {@return the index of the color sample of each frame in display order} The color samples are presented at
     * {@code index * DELTA + offset}.
     */
    private static Integer[] displayed(Samples color) {
        var order = new Integer[color.data().size()];
        for (var index = 0; index < order.length; index++) {
            order[index] = index;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(a * DELTA + color.offsets()[a], b * DELTA + color.offsets()[b]));
        return order;
    }

    /** Checks that every frame read from the start carries the alpha level of the alpha sample of its color sample. */
    private static void assertPairedByIndex(byte[] file, Samples color) {
        var order = displayed(color);
        try (AnimationReader reader = HeicDecoder.openAnimation(MediaSource.of(file))) {
            for (var frame = 0; frame < FRAMES; frame++) {
                var read = reader.read();
                assertNotNull(read, "frame " + frame);
                var sample = order[frame];
                var mean = meanAlpha((RgbaPicture) read.picture());
                // the level as coded, or expanded from the limited range; neighbouring levels lie 16 apart
                var expanded = (level(sample) - 16) * 255.0 / 219;
                assertTrue(Math.abs(mean - level(sample)) <= 3 || Math.abs(mean - expanded) <= 3,
                        "alpha " + mean + " of frame " + frame + " is not the level of alpha sample " + sample);
            }
        }
    }

    @ParameterizedTest(name = "alpha samples of {0} ms")
    @ValueSource(ints = {50, 100, 170})
    @DisplayName("an alpha track timed unlike its color track pairs by index and seeks as it reads")
    void timedUnlike(int alphaDelta) {
        var color = color(4, 0);
        var alpha = alpha(5);
        assertTrue(color.sync().length > 2 && alpha.sync().length > 2, "several sync samples");
        var file = sequence(color, alpha, constant(alphaDelta));
        assertPairedByIndex(file, color);
        AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(MediaSource.of(file)), alphaDelta);
    }

    @Test
    @DisplayName("an alpha track of irregular sample durations pairs by index and seeks as it reads")
    void irregular() {
        var color = color(3, 0);
        var alpha = alpha(4);
        var durations = new int[FRAMES];
        for (var index = 0; index < FRAMES; index++) {
            durations[index] = 1 + index * 37 % 230;
        }
        var file = sequence(color, alpha, durations);
        assertPairedByIndex(file, color);
        AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(MediaSource.of(file)), 17);
    }

    @ParameterizedTest(name = "alpha samples of {0} ms")
    @ValueSource(ints = {40, 100, 230})
    @DisplayName("a color track with B-frames pairs each picture with the alpha sample of its own index")
    void reordered(int alphaDelta) {
        var color = color(6, 2);
        assertTrue(color.reorders(), "the color track has B-frames");
        var alpha = alpha(4);
        var file = sequence(color, alpha, constant(alphaDelta));
        assertPairedByIndex(file, color);
        AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(MediaSource.of(file)), alphaDelta + 1);
    }

    @Test
    @DisplayName("a color track presented after the start of a faster alpha track seeks back to the alpha track's start")
    void delayedColor() {
        var color = color(4, 0);
        var alpha = alpha(4);
        var builder = new HeifSequenceBuilder("msf1", 1000, "msf1", "iso8", "mif1");
        var colorTrack = builder.track("hvc1", color.width(), color.height(), DELTA, color.data(),
                HeifBuilder.box("hvcC", color.config()));
        var offsets = new int[FRAMES];
        Arrays.fill(offsets, 250);
        builder.syncSamples(colorTrack, color.sync()).compositionOffsets(colorTrack, offsets);
        var alphaTrack = builder.track("hvc1", alpha.width(), alpha.height(), 30, alpha.data(),
                HeifBuilder.box("hvcC", alpha.config()),
                HeifSequenceBuilder.auxi("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));
        builder.reference("auxl", alphaTrack, colorTrack).syncSamples(alphaTrack, alpha.sync());
        var file = builder.build();
        // the color samples are presented in decoding order, so the display order is the identity
        assertPairedByIndex(file, new Samples(color.data(), color.sync(), new int[FRAMES], color.config(),
                color.width(), color.height()));
        AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(MediaSource.of(file)), 29);
        try (var reader = HeicDecoder.openAnimation(MediaSource.of(file))) {
            for (var frame = FRAMES - 1; frame >= 0; frame--) {
                reader.seek(frame * DELTA * 1000L);
                var read = reader.read();
                assertNotNull(read, "frame " + frame);
                var mean = meanAlpha((RgbaPicture) read.picture());
                var expanded = (level(frame) - 16) * 255.0 / 219;
                assertTrue(Math.abs(mean - level(frame)) <= 3 || Math.abs(mean - expanded) <= 3,
                        "alpha " + mean + " of frame " + frame + " after a seek is not the level of alpha sample "
                                + frame);
            }
        }
    }

    @Test
    @DisplayName("an alpha track with fewer samples than the color track fails the frame that has none")
    void missingAlpha() {
        var color = color(4, 0);
        var alpha = alpha(5);
        var shorter = new Samples(alpha.data().subList(0, 9), Arrays.stream(alpha.sync()).filter(i -> i < 9).toArray(),
                Arrays.copyOf(alpha.offsets(), 9), alpha.config(), alpha.width(), alpha.height());
        var builder = new HeifSequenceBuilder("msf1", 1000, "msf1", "iso8", "mif1");
        var colorTrack = builder.track("hvc1", color.width(), color.height(), DELTA, color.data(),
                HeifBuilder.box("hvcC", color.config()));
        builder.syncSamples(colorTrack, color.sync());
        var alphaTrack = builder.track("hvc1", shorter.width(), shorter.height(), 60, shorter.data(),
                HeifBuilder.box("hvcC", shorter.config()),
                HeifSequenceBuilder.auxi("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));
        builder.reference("auxl", alphaTrack, colorTrack).syncSamples(alphaTrack, shorter.sync());
        var file = builder.build();
        try (var reader = HeicDecoder.openAnimation(MediaSource.of(file))) {
            for (var frame = 0; frame < 9; frame++) {
                assertNotNull(reader.read(), "frame " + frame);
            }
            assertThrows(MediaException.Malformed.class, reader::read);
        }
        try (var reader = HeicDecoder.openAnimation(MediaSource.of(file))) {
            reader.seek(4 * DELTA * 1000L);
            assertNotNull(reader.read());
            assertThrows(MediaException.Malformed.class, () -> reader.seek(10 * DELTA * 1000L));
        }
    }
}
