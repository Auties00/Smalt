package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
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
 * Pairs the color and alpha samples of AVIF image sequences by sample index: alpha tracks timed faster, slower and
 * irregularly against their color tracks, with sync samples placed differently. Every alpha sample is a flat gray
 * level that names its index, so each frame shows which alpha sample it was paired with, and every seek must return
 * the frames a read from the start does ({@link AnimationSeeks}).
 */
@DisplayName("AVIF sequence alpha pairing")
class AvifSequenceAlphaTest {
    /** The frames of every test sequence. */
    static final int FRAMES = 12;

    /** The color track's duration of every sample, in units of the 1000 Hz timescale. */
    static final int DELTA = 100;

    /** The samples, sync flags and configuration of the AV1 track of a sequence. */
    record Samples(List<byte[]> data, int[] sync, byte[] config, int width, int height) {
        static Samples of(byte[] file) {
            var data = new ArrayList<byte[]>();
            var sync = new ArrayList<Integer>();
            try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() != track.id()) {
                        continue;
                    }
                    if (packet.keyframe()) {
                        sync.add(data.size());
                    }
                    data.add(packet.data());
                }
                return new Samples(data, sync.stream().mapToInt(Integer::intValue).toArray(),
                        track.codecConfig().orElseThrow(), track.width(), track.height());
            }
        }
    }

    /** Encodes a libaom color sequence with a key frame every {@code interval} frames. */
    static Samples color(int interval) {
        return Samples.of(Fixtures.read(Fixtures.ffmpeg("avif", List.of("-f", "lavfi", "-i",
                "testsrc2=size=48x32:rate=10"), List.of("-frames:v", Integer.toString(FRAMES), "-pix_fmt", "yuv420p",
                "-c:v", "libaom-av1", "-cpu-used", "8", "-g", Integer.toString(interval), "-keyint_min",
                Integer.toString(interval)))));
    }

    /** Encodes a libaom monochrome alpha sequence whose frame {@code i} is the flat level {@link #level(int)}. */
    static Samples alpha(int interval) {
        return Samples.of(Fixtures.read(Fixtures.ffmpeg("avif", List.of("-f", "lavfi", "-i",
                "nullsrc=size=48x32:rate=10,geq=lum='20+N*16':cb=128:cr=128,format=gray"),
                List.of("-frames:v", Integer.toString(FRAMES), "-pix_fmt", "gray", "-color_range", "pc",
                        "-c:v", "libaom-av1", "-cpu-used", "8", "-crf", "8", "-g", Integer.toString(interval),
                        "-keyint_min", Integer.toString(interval)))));
    }

    /** {@return the gray level of alpha sample {@code index}} */
    static int level(int index) {
        return 20 + 16 * index;
    }

    /** {@return a sequence of a color track and an alpha track whose samples last {@code alphaDurations}} */
    static byte[] sequence(Samples color, Samples alpha, int[] alphaDurations) {
        var builder = new HeifSequenceBuilder("avis", 1000, "avis", "msf1", "iso8", "mif1", "miaf");
        var colorTrack = builder.track("av01", color.width(), color.height(), DELTA, color.data(),
                HeifBuilder.av1C(color.config()));
        var alphaTrack = builder.track("av01", alpha.width(), alpha.height(), DELTA, alpha.data(),
                HeifBuilder.av1C(alpha.config()), HeifSequenceBuilder.auxi("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));
        builder.reference("auxl", alphaTrack, colorTrack).syncSamples(colorTrack, color.sync())
                .syncSamples(alphaTrack, alpha.sync());
        if (alphaDurations != null) {
            builder.durations(alphaTrack, alphaDurations);
        }
        return builder.build();
    }

    static int[] constant(int delta) {
        var durations = new int[FRAMES];
        Arrays.fill(durations, delta);
        return durations;
    }

    /** {@return the mean alpha of an RGBA frame} */
    static double meanAlpha(RgbaPicture picture) {
        var sum = 0L;
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                sum += picture.pixels()[y * picture.stride() + 4 * x + 3] & 0xFF;
            }
        }
        return (double) sum / ((long) picture.width() * picture.height());
    }

    /** Checks that the alpha of a frame is the level of alpha sample {@code sample}, coded or range-expanded. */
    static void assertLevel(RgbaPicture picture, int sample, String what) {
        var mean = meanAlpha(picture);
        // neighbouring levels lie 16 apart
        var expanded = (level(sample) - 16) * 255.0 / 219;
        assertTrue(Math.abs(mean - level(sample)) <= 3 || Math.abs(mean - expanded) <= 3,
                "alpha " + mean + " of " + what + " is not the level of alpha sample " + sample);
    }

    private static void assertPairedByIndex(byte[] file) {
        try (var reader = AvifDecoder.openAnimation(MediaSource.of(file))) {
            for (var frame = 0; frame < FRAMES; frame++) {
                var read = reader.read();
                assertNotNull(read, "frame " + frame);
                assertLevel((RgbaPicture) read.picture(), frame, "frame " + frame);
            }
        }
    }

    @ParameterizedTest(name = "alpha samples of {0} ms")
    @ValueSource(ints = {40, 100, 230})
    @DisplayName("an alpha track timed unlike its color track pairs by index and seeks as it reads")
    void timedUnlike(int alphaDelta) {
        var color = color(3);
        var alpha = alpha(5);
        assertTrue(color.sync().length > 2 && alpha.sync().length > 1, "several sync samples");
        var file = sequence(color, alpha, constant(alphaDelta));
        assertPairedByIndex(file);
        AnimationSeeks.assertMatchesSequential(() -> AvifDecoder.openAnimation(MediaSource.of(file)), alphaDelta);
    }

    @Test
    @DisplayName("a color track presented after the start of a faster alpha track seeks back to the alpha track's start")
    void delayedColor() {
        var color = color(3);
        var alpha = alpha(4);
        var builder = new HeifSequenceBuilder("avis", 1000, "avis", "msf1", "iso8", "mif1", "miaf");
        var colorTrack = builder.track("av01", color.width(), color.height(), DELTA, color.data(),
                HeifBuilder.av1C(color.config()));
        var alphaTrack = builder.track("av01", alpha.width(), alpha.height(), 30, alpha.data(),
                HeifBuilder.av1C(alpha.config()), HeifSequenceBuilder.auxi("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));
        var offsets = new int[FRAMES];
        Arrays.fill(offsets, 250);
        builder.reference("auxl", alphaTrack, colorTrack).syncSamples(colorTrack, color.sync())
                .syncSamples(alphaTrack, alpha.sync()).compositionOffsets(colorTrack, offsets);
        var file = builder.build();
        assertPairedByIndex(file);
        AnimationSeeks.assertMatchesSequential(() -> AvifDecoder.openAnimation(MediaSource.of(file)), 29);
    }

    @Test
    @DisplayName("an alpha track of irregular sample durations pairs by index and seeks as it reads")
    void irregular() {
        var color = color(4);
        var alpha = alpha(3);
        var durations = new int[FRAMES];
        for (var index = 0; index < FRAMES; index++) {
            durations[index] = 1 + index * 53 % 260;
        }
        var file = sequence(color, alpha, durations);
        assertPairedByIndex(file);
        AnimationSeeks.assertMatchesSequential(() -> AvifDecoder.openAnimation(MediaSource.of(file)), 23);
    }

    @Test
    @DisplayName("seeks to every frame of an alpha track timed faster, from the last to the first")
    void fasterAlphaSeeksEachFrame() {
        var color = color(3);
        var alpha = alpha(3);
        var file = sequence(color, alpha, constant(25));
        try (var reader = AvifDecoder.openAnimation(MediaSource.of(file))) {
            for (var frame = FRAMES - 1; frame >= 0; frame--) {
                reader.seek(frame * DELTA * 1000L);
                var read = reader.read();
                assertNotNull(read, "frame " + frame);
                assertLevel((RgbaPicture) read.picture(), frame, "frame " + frame + " after a seek");
            }
        }
    }

    @Test
    @DisplayName("an alpha track with fewer samples than the color track fails the frame that has none")
    void missingAlpha() {
        var color = color(4);
        var alpha = alpha(3);
        var shorter = new Samples(alpha.data().subList(0, 8), Arrays.stream(alpha.sync()).filter(i -> i < 8).toArray(),
                alpha.config(), alpha.width(), alpha.height());
        var builder = new HeifSequenceBuilder("avis", 1000, "avis", "msf1", "iso8", "mif1", "miaf");
        var colorTrack = builder.track("av01", color.width(), color.height(), DELTA, color.data(),
                HeifBuilder.av1C(color.config()));
        var alphaTrack = builder.track("av01", shorter.width(), shorter.height(), 60, shorter.data(),
                HeifBuilder.av1C(shorter.config()), HeifSequenceBuilder.auxi("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));
        builder.reference("auxl", alphaTrack, colorTrack).syncSamples(colorTrack, color.sync())
                .syncSamples(alphaTrack, shorter.sync());
        var file = builder.build();
        try (var reader = AvifDecoder.openAnimation(MediaSource.of(file))) {
            for (var frame = 0; frame < 8; frame++) {
                assertNotNull(reader.read(), "frame " + frame);
            }
            assertThrows(MediaException.Malformed.class, reader::read);
        }
        try (var reader = AvifDecoder.openAnimation(MediaSource.of(file))) {
            reader.seek(5 * DELTA * 1000L);
            assertLevel((RgbaPicture) reader.read().picture(), 5, "frame 5 after a seek");
            assertThrows(MediaException.Malformed.class, () -> reader.seek(9 * DELTA * 1000L));
        }
    }
}
