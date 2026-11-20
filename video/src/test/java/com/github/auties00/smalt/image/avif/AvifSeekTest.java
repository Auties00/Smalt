package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.HeifSequenceBuilder;
import com.github.auties00.smalt.testkit.image.AnimationSeeks;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeks AVIF image sequences to frame starts, frame ends, random times and times outside the
 * sequence, and checks every frame read after a seek against a read from the start
 * ({@link AnimationSeeks}): libaom sequences with a key frame every few frames, at frame durations
 * that are and are not whole microseconds, an alpha track whose sync samples differ from the color
 * track's, and the Netflix test sequence with alpha; still images seek as one-frame animations.
 */
@DisplayName("AVIF seeking")
class AvifSeekTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    /** Encodes a libaom sequence with a key frame every {@code interval} frames. */
    private static byte[] sequence(int rate, int frames, int interval, String pixelFormat) {
        return Fixtures.read(Fixtures.ffmpeg("avif", List.of("-f", "lavfi", "-i",
                        "testsrc2=size=48x32:rate=" + rate), List.of("-frames:v", Integer.toString(frames),
                "-pix_fmt", pixelFormat, "-c:v", "libaom-av1", "-cpu-used", "8", "-g", Integer.toString(interval),
                "-keyint_min", Integer.toString(interval))));
    }

    @ParameterizedTest(name = "{0} fps")
    @ValueSource(ints = {10, 30})
    @DisplayName("frames read after a seek equal the frames read from the start")
    void libaom(int rate) {
        var data = sequence(rate, 14, 4, "yuv420p");
        AnimationSeeks.assertMatchesSequential(() -> AvifDecoder.openAnimation(MediaSource.of(data)), rate);
        var deep = sequence(rate, 8, 3, "yuv420p10le");
        AnimationSeeks.assertMatchesSequential(() -> AvifDecoder.openAnimation(MediaSource.of(deep), WIDE), rate + 1);
    }

    @Test
    @DisplayName("Netflix's alpha_video.avif seeks through its color and alpha tracks")
    void netflix() throws IOException {
        var data = Files.readAllBytes(Conformance.fetchListed("avif-Netflix-avis-alpha_video.avif"));
        AnimationSeeks.assertMatchesSequential(() -> AvifDecoder.openAnimation(MediaSource.of(data)), 3);
    }

    /** The samples, sync flags and configuration of the AV1 track of a sequence. */
    private record Samples(List<byte[]> data, int[] sync, byte[] config, int width, int height) {
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

    @Test
    @DisplayName("an alpha track whose sync samples differ from the color track's pairs each frame with its alpha")
    void alphaSync() {
        var color = Samples.of(sequence(10, 12, 3, "yuv420p"));
        var alpha = Samples.of(Fixtures.read(Fixtures.ffmpeg("avif", List.of("-f", "lavfi", "-i",
                "testsrc2=size=48x32:rate=10"), List.of("-frames:v", "12", "-vf", "format=gray", "-pix_fmt", "gray",
                "-c:v", "libaom-av1", "-cpu-used", "8", "-g", "5", "-keyint_min", "5"))));
        assertTrue(color.sync().length > 2 && alpha.sync().length > 1);
        var builder = new HeifSequenceBuilder("avis", 1000, "avis", "msf1", "iso8", "mif1", "miaf");
        var colorTrack = builder.track("av01", color.width(), color.height(), 100, color.data(),
                HeifBuilder.av1C(color.config()));
        var alphaTrack = builder.track("av01", alpha.width(), alpha.height(), 100, alpha.data(),
                HeifBuilder.av1C(alpha.config()), HeifSequenceBuilder.auxi("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));
        builder.reference("auxl", alphaTrack, colorTrack).syncSamples(colorTrack, color.sync())
                .syncSamples(alphaTrack, alpha.sync());
        var data = builder.build();
        AnimationSeeks.assertMatchesSequential(() -> AvifDecoder.openAnimation(MediaSource.of(data)), 11);
    }

    @Test
    @DisplayName("a seek restarts at the sync sample before the target, so corrupt samples before it are never decoded")
    void restartsAtSyncSample() {
        var color = Samples.of(sequence(10, 12, 4, "yuv420p"));
        assertTrue(color.sync().length > 2 && color.sync()[0] == 0, "several sync samples");
        var damagedSamples = new ArrayList<>(color.data());
        for (var i = 1; i < color.sync()[1]; i++) {
            // a temporal delimiter, then a sequence header OBU that declares 11 bytes and holds none
            damagedSamples.set(i, new byte[]{0x12, 0, 0x0A, 0x0B});
        }
        var intact = single(color, color.data());
        var damaged = single(color, damagedSamples);
        AnimationSeeks.assertSeeksPastCorruptSamples(() -> AvifDecoder.openAnimation(MediaSource.of(intact)),
                () -> AvifDecoder.openAnimation(MediaSource.of(damaged)), color.sync()[1] + 1);
    }

    /** Builds a sequence of one color track holding samples of {@code color}, with its sync samples. */
    private static byte[] single(Samples color, List<byte[]> samples) {
        var builder = new HeifSequenceBuilder("avis", 1000, "avis", "msf1", "iso8", "mif1", "miaf");
        var track = builder.track("av01", color.width(), color.height(), 100, samples, HeifBuilder.av1C(color.config()));
        builder.syncSamples(track, color.sync());
        return builder.build();
    }

    @Test
    @DisplayName("a still image seeks as one frame shown for zero microseconds")
    void still() {
        var builder = new HeifBuilder();
        builder.primary(AvifStructureTest.still(builder, AvifStructureTest.tile(0, 0, "yuv420p")));
        var data = builder.build();
        try (var reader = AvifDecoder.openAnimation(MediaSource.of(data))) {
            assertTrue(reader.isSeekable());
            var expected = (byte[]) AnimationSeeks.pixels(reader.read().picture());
            assertNull(reader.read());
            reader.seek(0);
            assertNull(reader.read());
            reader.seek(-1);
            var again = reader.read();
            assertNotNull(again);
            assertEquals(0, again.timestampMicros());
            assertEquals(0, again.durationMicros());
            assertArrayEquals(expected, (byte[]) AnimationSeeks.pixels(again.picture()));
            assertNull(reader.read());
        }
    }
}
