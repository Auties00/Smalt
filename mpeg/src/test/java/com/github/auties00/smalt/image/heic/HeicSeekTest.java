package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.image.ImageDecoderOptions;
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
 * Seeks HEIC image sequences to frame starts, frame ends, random times and times outside the
 * sequence, and checks every frame read after a seek against a read from the start
 * ({@link AnimationSeeks}): the Nokia conformance sequences (inter prediction, reordering and an
 * edit list that hides the first picture), x265 sequences with a sync sample every few frames and
 * an alpha track whose sync samples differ from the color track's; still images seek as one-frame
 * animations.
 */
@DisplayName("HEIC seeking")
class HeicSeekTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"C027.heic", "C028.heic", "C041.heic"})
    @DisplayName("Nokia sequences: frames read after a seek equal the frames read from the start")
    void nokia(String name) throws IOException {
        var data = Files.readAllBytes(HeicVectors.nokia(name));
        AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(MediaSource.of(data)), name.hashCode());
    }

    /** The samples, sync flags and configuration of the HEVC track of an MP4 file. */
    private record Samples(List<byte[]> data, int[] sync, byte[] config, int width, int height) {
        static Samples of(byte[] file) {
            var data = new ArrayList<byte[]>();
            var sync = new ArrayList<Integer>();
            try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
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

    /** Encodes an x265 sequence without B-frames and with a sync sample every {@code interval} frames. */
    private static Samples x265(int frames, int interval, String pixelFormat) {
        return Samples.of(Fixtures.read(Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i",
                "testsrc2=size=64x48:rate=10"), List.of("-frames:v", Integer.toString(frames), "-pix_fmt", pixelFormat,
                "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "keyint=" + interval + ":min-keyint="
                        + interval + ":bframes=0:scenecut=0:open-gop=0:log-level=error", "-tag:v", "hvc1"))));
    }

    private static byte[] sequence(Samples color, Samples alpha) {
        var builder = new HeifSequenceBuilder("msf1", 1000, "msf1", "iso8", "mif1");
        var colorTrack = builder.track("hvc1", color.width(), color.height(), 100, color.data(),
                HeifBuilder.box("hvcC", color.config()));
        builder.syncSamples(colorTrack, color.sync());
        if (alpha != null) {
            var alphaTrack = builder.track("hvc1", alpha.width(), alpha.height(), 100, alpha.data(),
                    HeifBuilder.box("hvcC", alpha.config()),
                    HeifSequenceBuilder.auxi("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));
            builder.reference("auxl", alphaTrack, colorTrack).syncSamples(alphaTrack, alpha.sync());
        }
        return builder.build();
    }

    @Test
    @DisplayName("x265 sequences restart at their sync samples, with and without alpha")
    void x265() {
        var color = x265(13, 4, "yuv420p");
        assertTrue(color.sync().length > 2, "several sync samples");
        var plain = sequence(color, null);
        AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(MediaSource.of(plain)), 5);
        var withAlpha = sequence(color, x265(13, 5, "gray"));
        AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(MediaSource.of(withAlpha)), 6);
        var deep = sequence(x265(9, 3, "yuv420p10le"), null);
        AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(MediaSource.of(deep),
                ImageDecoderOptions.builder().highBitDepth(true).build()), 7);
    }

    @Test
    @DisplayName("a seek restarts at the sync sample before the target, so corrupt samples before it are never decoded")
    void restartsAtSyncSample() {
        // a seek on a fresh reader decodes the first picture to find the time origin, and the frame-threaded engine
        // feeds up to about one sample per thread before it outputs that picture, so the first group of pictures
        // stays intact and is longer than that; the second group is corrupt
        var threads = Math.max(1, Integer.getInteger("smalt.threads", Runtime.getRuntime().availableProcessors()));
        var interval = 2 * threads + 16;
        var color = x265(3 * interval + 1, interval, "yuv420p");
        assertTrue(color.sync().length > 3 && color.sync()[0] == 0, "several sync samples");
        var damaged = new ArrayList<>(color.data());
        for (var i = color.sync()[1] + 1; i < color.sync()[2]; i++) {
            // one NAL unit whose length prefix runs past the end of the sample
            damaged.set(i, new byte[]{0, 0, 0, 0x40, 0x02, 0x01, (byte) 0xD0});
        }
        var intact = sequence(color, null);
        var corrupt = sequence(new Samples(damaged, color.sync(), color.config(), color.width(), color.height()), null);
        AnimationSeeks.assertSeeksPastCorruptSamples(() -> HeicDecoder.openAnimation(MediaSource.of(intact)),
                () -> HeicDecoder.openAnimation(MediaSource.of(corrupt)), color.sync()[2] + 1);
    }

    @Test
    @DisplayName("a still image seeks as one frame shown for zero microseconds")
    void still() {
        var builder = HeicStructureTest.heic();
        builder.primary(HeicStructureTest.still(builder, HeicStructureTest.tile(0, 0, "yuv420p")));
        var data = builder.build();
        try (var reader = HeicDecoder.openAnimation(MediaSource.of(data))) {
            assertTrue(reader.isSeekable());
            var expected = (byte[]) AnimationSeeks.pixels(reader.read().picture());
            assertNull(reader.read());
            reader.seek(5);
            assertNull(reader.read());
            reader.seek(-1);
            var again = reader.read();
            assertNotNull(again);
            assertEquals(0, again.timestampMicros());
            assertArrayEquals(expected, (byte[]) AnimationSeeks.pixels(again.picture()));
            assertNull(reader.read());
        }
    }
}
