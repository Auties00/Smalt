package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the MPEG-4 Part 2 decoder constructor taking the generic {@link VideoDecoderOptions} and
 * {@link Mpeg4ElementaryStream#open(MediaSource, DemuxerOptions)}. The streams are written by ffmpeg.
 */
@DisplayName("MPEG-4 Part 2 API additions")
class Mpeg4ApiAdditionsTest {
    private static final VideoTrack TRACK = VideoTrack.builder().id(1).codec(Codec.MPEG4_PART2)
            .timeBase(new Rational(1, 90_000)).build();

    private static Path stream() {
        return Fixtures.ffmpeg("m4v", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25"),
                List.of("-t", "0.4", "-c:v", "mpeg4", "-q:v", "5", "-bf", "1", "-f", "m4v"));
    }

    private static byte[] decode(Path file, Function<VideoTrack, VideoDecoder> factory) {
        var raw = new ByteArrayOutputStream();
        try (var source = MediaSource.of(file); var stream = Mpeg4ElementaryStream.open(source);
             var decoder = factory.apply(stream.track())) {
            for (var packet = stream.read(); packet != null; packet = stream.read()) {
                decoder.decode(packet, frame -> raw.writeBytes(Mpeg4TestStreams.raw(
                        (YuvPicture) frame.picture())));
            }
            decoder.flush(frame -> raw.writeBytes(Mpeg4TestStreams.raw(
                    (YuvPicture) frame.picture())));
        }
        return raw.toByteArray();
    }

    @Nested
    @DisplayName("Mpeg4Decoder(VideoTrack, VideoDecoderOptions)")
    class Constructor {
        @ParameterizedTest(name = "threads {0}")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("carries the limits and the thread count over")
        void conversion(int threads) {
            var limits = MediaLimits.builder().maxDimension(4096).build();
            var generic = VideoDecoderOptions.builder().limits(limits).threads(threads).highBitDepth(true).build();
            try (var decoder = new Mpeg4Decoder(TRACK, generic)) {
                assertEquals(limits, decoder.options().limits());
                assertEquals(threads, decoder.options().threads());
            }
        }

        @Test
        @DisplayName("decodes on one thread as the decoder built with the default options")
        void sameOutput() {
            var file = stream();
            var expected = decode(file, Mpeg4Decoder::new);
            var actual = decode(file, track -> new Mpeg4Decoder(track,
                    VideoDecoderOptions.builder().threads(1).build()));
            assertTrue(expected.length > 0);
            assertArrayEquals(expected, actual);
        }

        @ParameterizedTest(name = "threads {0}")
        @ValueSource(ints = {1, 2})
        @DisplayName("passes an exception of the frame consumer through unchanged")
        void consumerFailure(int threads) {
            var failure = new IllegalStateException("consumer");
            var thrown = assertThrows(IllegalStateException.class, () -> {
                try (var source = MediaSource.of(stream()); var elementary = Mpeg4ElementaryStream.open(source);
                     var decoder = new Mpeg4Decoder(elementary.track(),
                             VideoDecoderOptions.builder().threads(threads).build())) {
                    for (var packet = elementary.read(); packet != null; packet = elementary.read()) {
                        decoder.decode(packet, frame -> {
                            throw failure;
                        });
                    }
                    decoder.flush(frame -> {
                        throw failure;
                    });
                }
            });
            assertSame(failure, thrown);
        }

        @Test
        @DisplayName("rejects a track of another codec and null options")
        void rejects() {
            var h263 = VideoTrack.builder().id(1).codec(Codec.H263).timeBase(new Rational(1, 90_000)).build();
            assertThrows(IllegalArgumentException.class, () -> new Mpeg4Decoder(h263, VideoDecoderOptions.defaults()));
            assertThrows(NullPointerException.class, () -> new Mpeg4Decoder(TRACK, (VideoDecoderOptions) null));
        }
    }

    @Nested
    @DisplayName("Mpeg4ElementaryStream.open(MediaSource, DemuxerOptions)")
    class Open {
        private static Object split(Path file, Function<MediaSource, Mpeg4ElementaryStream> opener) {
            var packets = new ArrayList<String>();
            try (var source = MediaSource.of(file); var stream = opener.apply(source)) {
                for (Packet packet = stream.read(); packet != null; packet = stream.read()) {
                    packets.add(Arrays.toString(packet.data()) + packet.keyframe() + packet.pts());
                }
            } catch (MediaException exception) {
                return exception.getClass();
            }
            return packets;
        }

        @Test
        @DisplayName("splits the stream as open(MediaSource) and applies the options' limits")
        void sameSplit() {
            var file = stream();
            var expected = split(file, Mpeg4ElementaryStream::open);
            assertTrue(expected instanceof List<?> packets && !packets.isEmpty());
            assertEquals(expected, split(file, source -> Mpeg4ElementaryStream.open(source,
                    DemuxerOptions.defaults())));
            var tight = MediaLimits.builder().maxBytes(4096).build();
            assertEquals(MediaException.LimitExceeded.class, split(file, source -> Mpeg4ElementaryStream.open(source,
                    DemuxerOptions.builder().limits(tight).build())));
        }

        @Test
        @DisplayName("rejects null options and content that is not an elementary stream")
        void rejects() {
            assertThrows(NullPointerException.class,
                    () -> Mpeg4ElementaryStream.open(MediaSource.of(new byte[8]), (DemuxerOptions) null));
            assertThrows(MediaException.Unsupported.class, () -> Mpeg4ElementaryStream.open(
                    MediaSource.of(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0}), DemuxerOptions.defaults()));
        }
    }
}
