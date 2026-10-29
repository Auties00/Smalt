package com.github.auties00.smalt.video.vc1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the streams {@link Vc1Synthetic} writes, which need no fetched vector: against ffmpeg, and for their frame
 * order, timestamps and keyframes, plus the decoder's argument, state and limit checks.
 */
@DisplayName("VC-1 synthetic streams")
class Vc1SyntheticTest {
    static final Tolerance EXACT = Tolerance.exact();

    @ParameterizedTest(name = "{1}x{2} {3} loop filter {4}")
    @CsvSource({"1, 64, 48, IPBSPB, true", "2, 50, 34, IBPI, false", "3, 16, 16, ISSPB, true",
            "4, 176, 144, IPPBBP, true", "5, 34, 66, IPI, false"})
    @DisplayName("decodes advanced profile streams as ffmpeg does")
    void advancedAgainstFfmpeg(long seed, int width, int height, String pictures, boolean loopFilter,
                               @TempDir Path directory) throws IOException {
        var bytes = Vc1Synthetic.advanced(seed, width, height, pictures, loopFilter);
        var file = directory.resolve("synthetic.vc1");
        Files.write(file, bytes);
        var decoded = Vc1TestStreams.decode(Vc1TestStreams.elementary(bytes));
        assertTrue(decoded.failures().isEmpty(), decoded.failures().toString());
        var expected = Oracle.decodeVideo(file, List.of("-f", "vc1"), "yuv420p", Integer.MAX_VALUE);
        EXACT.assertFrames("advanced " + pictures, expected, decoded.raw(), frameSize(width, height));
    }

    @ParameterizedTest(name = "{1}x{2} {3}")
    // ffmpeg rejects the one-byte packets of skipped frames, so the streams compared with it have none
    @CsvSource({"6, 48, 32, IPIP", "7, 16, 16, IPPP", "8, 80, 48, IPPI"})
    @DisplayName("decodes simple profile RCV streams as ffmpeg does")
    void simpleAgainstFfmpeg(long seed, int width, int height, String pictures, @TempDir Path directory)
            throws IOException {
        var stream = Vc1Synthetic.simple(seed, width, height, pictures);
        var file = directory.resolve("synthetic.rcv");
        Files.write(file, Vc1Synthetic.rcv(stream));
        var decoded = Vc1TestStreams.decode(stream);
        assertTrue(decoded.failures().isEmpty(), decoded.failures().toString());
        var expected = Oracle.decodeVideo(file, "yuv420p", Integer.MAX_VALUE);
        EXACT.assertFrames("simple " + pictures, expected, decoded.raw(), frameSize(width, height));
    }

    private static int frameSize(int width, int height) {
        return width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2);
    }

    @Nested
    @DisplayName("output")
    class Output {
        @Test
        @DisplayName("hands frames out in display order, a B frame before the anchor that follows it")
        void displayOrder() {
            var decoded = Vc1TestStreams.decode(Vc1TestStreams.elementary(
                    Vc1Synthetic.advanced(11, 32, 32, "IPB", true)));
            assertEquals(3, decoded.count());
            var keys = decoded.frames().stream().map(Vc1TestStreams.Frame::keyframe).toList();
            assertEquals(List.of(true, false, false), keys);
            // the P picture repeats the I picture, so the B picture between them does too
            assertArrayEquals(decoded.frames().get(0).raw(), decoded.frames().get(1).raw());
            assertArrayEquals(decoded.frames().get(0).raw(), decoded.frames().get(2).raw());
        }

        @Test
        @DisplayName("gives the simple profile frames the RCV timestamps and outputs skipped frames")
        void timestamps() {
            var decoded = Vc1TestStreams.decode(Vc1Synthetic.simple(12, 32, 32, "ISPS"));
            assertEquals(List.of(0L, 40L, 80L, 120L), decoded.frames().stream().map(Vc1TestStreams.Frame::pts)
                    .toList());
            assertEquals(32, decoded.frames().getFirst().width());
            assertEquals(32, decoded.frames().getFirst().height());
            assertArrayEquals(decoded.frames().get(0).raw(), decoded.frames().get(1).raw());
            assertArrayEquals(decoded.frames().get(2).raw(), decoded.frames().get(3).raw());
        }

        @Test
        @DisplayName("decodes the same stream to the same samples twice")
        void deterministic() {
            var bytes = Vc1Synthetic.advanced(13, 96, 64, "IPBBPI", true);
            var first = Vc1TestStreams.decode(Vc1TestStreams.elementary(bytes)).raw();
            var second = Vc1TestStreams.decode(Vc1TestStreams.elementary(bytes)).raw();
            assertArrayEquals(first, second);
        }

        @Test
        @DisplayName("drops the references on flush, so a P picture after it produces no frame")
        void flushDropsReferences() {
            var stream = Vc1TestStreams.elementary(Vc1Synthetic.advanced(14, 32, 32, "IP", true));
            var frames = new ArrayList<VideoFrame>();
            Consumer<VideoFrame> sink = frames::add;
            try (var decoder = new Vc1Decoder(Vc1TestStreams.track(stream))) {
                var packets = stream.packets();
                decoder.decode(packets.get(0), sink);
                decoder.decode(packets.get(1), sink);
                decoder.flush(sink);
                assertEquals(2, frames.size());
                assertThrows(MediaException.Malformed.class, () -> decoder.decode(packets.get(1), sink));
                decoder.flush(sink);
                assertEquals(2, frames.size());
            }
        }
    }

    @Nested
    @DisplayName("checks")
    class Checks {
        @Test
        @DisplayName("rejects a track of another codec")
        void otherCodec() {
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 1000)).build();
            assertThrows(IllegalArgumentException.class, () -> new Vc1Decoder(track));
        }

        @Test
        @DisplayName("rejects a simple profile track without a picture size")
        void simpleWithoutSize() {
            var stream = Vc1Synthetic.simple(15, 32, 32, "I");
            var track = VideoTrack.builder().id(1).codec(Codec.VC1).timeBase(new Rational(1, 1000))
                    .codecConfig(stream.config()).build();
            assertThrows(MediaException.Malformed.class, () -> new Vc1Decoder(track));
        }

        @Test
        @DisplayName("refuses a sequence header whose picture size exceeds the limits")
        void limits() {
            var stream = Vc1TestStreams.elementary(Vc1Synthetic.advanced(16, 64, 64, "I", false));
            var options = VideoDecoderOptions.builder()
                    .limits(MediaLimits.builder().maxDimension(32).build())
                    .build();
            try (var decoder = new Vc1Decoder(Vc1TestStreams.track(stream), options)) {
                assertThrows(MediaException.LimitExceeded.class,
                        () -> decoder.decode(stream.packets().getFirst(), frame -> {
                        }));
            }
        }

        @Test
        @DisplayName("refuses to decode after close")
        void closed() {
            var stream = Vc1TestStreams.elementary(Vc1Synthetic.advanced(17, 32, 32, "I", false));
            var decoder = new Vc1Decoder(Vc1TestStreams.track(stream));
            decoder.close();
            decoder.close();
            assertThrows(IllegalStateException.class, () -> decoder.decode(stream.packets().getFirst(), frame -> {
            }));
        }

        @Test
        @DisplayName("passes the exception of the consumer through and stays usable")
        void consumerFailure() {
            var stream = Vc1TestStreams.elementary(Vc1Synthetic.advanced(18, 32, 32, "IPP", false));
            try (var decoder = new Vc1Decoder(Vc1TestStreams.track(stream))) {
                var failure = new IllegalStateException("consumer");
                var packets = stream.packets();
                decoder.decode(packets.get(0), frame -> {
                });
                var thrown = assertThrows(RuntimeException.class, () -> decoder.decode(packets.get(1), frame -> {
                    throw failure;
                }));
                assertTrue(thrown == failure || thrown.getCause() == failure, thrown.toString());
                var frames = new ArrayList<VideoFrame>();
                decoder.decode(packets.get(2), frames::add);
                decoder.flush(frames::add);
                assertFalse(frames.isEmpty());
            }
        }
    }
}
