package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.Mpeg4ElementaryStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the H.263 decoder constructor taking the generic {@link VideoDecoderOptions}. The stream is
 * written by ffmpeg.
 */
@DisplayName("H.263 API additions")
class H263ApiAdditionsTest {
    private static final VideoTrack TRACK = VideoTrack.builder().id(1).codec(Codec.H263)
            .timeBase(new Rational(1, 90_000)).build();

    private static byte[] decode(Function<VideoTrack, VideoDecoder> factory) {
        var file = Fixtures.ffmpeg("h263", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=15"),
                List.of("-t", "0.4", "-c:v", "h263", "-q:v", "5", "-g", "3", "-f", "h263"));
        var raw = new ByteArrayOutputStream();
        Consumer<VideoFrame> sink = frame -> {
            var picture = (YuvPicture) frame.picture();
            for (var row = 0; row < picture.height(); row++) {
                raw.write(picture.y(), row * picture.yStride(), picture.width());
            }
        };
        try (var source = MediaSource.of(file); var stream = Mpeg4ElementaryStream.open(source);
             var decoder = factory.apply(stream.track())) {
            for (var packet = stream.read(); packet != null; packet = stream.read()) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return raw.toByteArray();
    }

    @Test
    @DisplayName("carries the limits and the thread count over")
    void conversion() {
        var limits = MediaLimits.builder().maxDimension(2048).build();
        var generic = VideoDecoderOptions.builder().limits(limits).threads(1).build();
        try (var decoder = new H263Decoder(TRACK, generic)) {
            assertEquals(limits, decoder.options().limits());
            assertEquals(1, decoder.options().threads());
        }
    }

    @Test
    @DisplayName("decodes as the decoder built with the default options")
    void sameOutput() {
        var expected = decode(H263Decoder::new);
        var actual = decode(track -> new H263Decoder(track, VideoDecoderOptions.defaults()));
        assertTrue(expected.length > 0);
        assertArrayEquals(expected, actual);
    }

    @Test
    @DisplayName("rejects a track of another codec and null options")
    void rejects() {
        var mpeg4 = VideoTrack.builder().id(1).codec(Codec.MPEG4_PART2).timeBase(new Rational(1, 90_000)).build();
        assertThrows(IllegalArgumentException.class, () -> new H263Decoder(mpeg4, VideoDecoderOptions.defaults()));
        assertThrows(NullPointerException.class, () -> new H263Decoder(TRACK, (VideoDecoderOptions) null));
    }
}
