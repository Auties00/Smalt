package com.github.auties00.smalt.video.ffv1;

import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the sample aspect ratio the FFV1 encoder declares in its version 3 slice headers from
 * {@link VideoEncoderOptions#sampleAspectRatio()}, read back by Smalt's decoder and by ffprobe from an AVI file
 * whose track declares no ratio.
 */
@DisplayName("FFV1 sample aspect ratio from the codec-independent options")
class Ffv1SampleAspectTest {
    @ParameterizedTest(name = "{0}:{1}")
    @CsvSource({"0, 0, 0, 0", "4, 3, 4, 3", "40, 33, 40, 33", "1, 1, 1, 1", "128, 96, 4, 3"})
    @DisplayName("declares the ratio in lowest terms, or none without one")
    void declared(long numerator, long denominator, long expectedNumerator, long expectedDenominator)
            throws IOException {
        var options = VideoEncoderOptions.of(32, 16, new Rational(25, 1), 1_000_000).toBuilder().threads(1);
        if (numerator > 0) {
            options.sampleAspectRatio(new Rational(numerator, denominator));
        }
        var picture = YuvPicture.allocate(32, 16, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var i = 0; i < picture.y().length; i++) {
            picture.y()[i] = (byte) (16 + i % 200);
        }
        var packets = new ArrayList<Packet>();
        VideoTrack track;
        try (var encoder = VideoEncoders.create(Codec.FFV1, options.build())) {
            encoder.encode(new VideoFrame(picture, 0, 1, true), packets::add);
            encoder.flush(packets::add);
            track = VideoTrack.builder().id(1).codec(Codec.FFV1).timeBase(encoder.timeBase()).width(32).height(16)
                    .frameRate(new Rational(25, 1)).codecConfig(encoder.codecConfig().orElse(null)).build();
        }
        var expected = expectedNumerator == 0 ? Optional.<Rational>empty()
                : Optional.of(new Rational(expectedNumerator, expectedDenominator));
        var decoder = new Ffv1Decoder(track);
        decoder.decode(packets.getFirst(), frame -> { });
        assertEquals(expected, decoder.sampleAspectRatio());
        if (expected.isEmpty() || Oracle.find(Oracle.Tool.FFPROBE).isEmpty()) {
            return;
        }
        var sink = MediaSink.memory();
        try (var muxer = new AviMuxer(sink)) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(new Packet(1, packet.data(), packet.pts(), packet.dts(), packet.duration(),
                        packet.flags(), packet.sideData()));
            }
            muxer.finish();
        }
        var file = Scratch.directory("ffv1-sample-aspect").resolve(numerator + "x" + denominator + ".avi");
        Files.write(file, sink.toByteArray());
        // An AVI without a video properties header declares no ratio, so ffmpeg takes the one its FFV1 decoder reads
        // from the slice header; Matroska implies square samples from the track's display size, which ffmpeg prefers.
        var probed = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "v:0", "-read_intervals", "%+#1", "-show_entries", "frame=sample_aspect_ratio",
                "-of", "default=noprint_wrappers=1:nokey=1", file.toString())), StandardCharsets.UTF_8)
                .strip().lines().findFirst().orElse("").strip();
        assertEquals(expectedNumerator + ":" + expectedDenominator, probed);
    }
}
