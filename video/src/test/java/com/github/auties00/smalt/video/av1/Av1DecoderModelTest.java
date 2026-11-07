package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.internal.encoder.AnnexEChecker;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks encoded streams that signal the decoder model against an independent implementation of
 * the AV1 specification's Annex E ({@link AnnexEChecker}): constant and variable bitrate and
 * constant quality streams at several levels, chosen by picture size, frame rate and bitrate, must
 * break no requirement of the model, neither as a whole nor when decoding starts at any key frame,
 * and must still decode.
 */
class Av1DecoderModelTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    private static final int FRAMES = 36;

    static Stream<Arguments> streams() {
        return Stream.of(
                // usage, width, height, frames per second, bitrate (0: constant quality), level
                Arguments.of(Av1EncoderUsage.REALTIME, 96, 64, 30, 400_000L, 0),
                Arguments.of(Av1EncoderUsage.REALTIME, 160, 96, 30, 2_500_000L, 4),
                Arguments.of(Av1EncoderUsage.REALTIME, 96, 64, 200, 600_000L, 8),
                Arguments.of(Av1EncoderUsage.REALTIME, 480, 272, 30, 1_200_000L, 1),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, 96, 64, 30, 300_000L, 0),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, 96, 64, 30, 0L, 0),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, 96, 64, 150, 0L, 8),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, 128, 80, 60, 5_000_000L, 5));
    }

    static Av1EncoderOptions options(Av1EncoderUsage usage, int fps, long bitrate) {
        Av1EncoderOptions.Builder base;
        if (usage == Av1EncoderUsage.REALTIME) {
            base = Av1EncoderOptions.forRealtime().toBuilder().bitrate(bitrate).dropFrames(false);
        } else if (bitrate > 0) {
            base = Av1EncoderOptions.ofBitrate(bitrate).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(16);
        } else {
            base = Av1EncoderOptions.ofQuality(70).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(16);
        }
        return base.keyframeInterval(12).timeBase(TIME_BASE).frameRate(new Rational(fps, 1))
                .timingInfo(Av1TimingInfo.DECODER_MODEL).build();
    }

    @ParameterizedTest(name = "{0} {1}x{2} {3} fps {4} bps")
    @MethodSource("streams")
    @DisplayName("codes streams that conform to the Annex E decoder model of their level")
    void conforms(Av1EncoderUsage usage, int width, int height, int fps, long bitrate, int level) {
        var sources = new ArrayList<Picture>(Vp9TestVideo.synthesize(width, height, FRAMES));
        var packets = new ArrayList<Packet>();
        var frameTicks = 90_000L / fps;
        try (var encoder = new Av1Encoder(width, height, options(usage, fps, bitrate))) {
            for (var i = 0; i < sources.size(); i++) {
                encoder.encode(new VideoFrame(sources.get(i), i * frameTicks, frameTicks, false), packets::add);
            }
            encoder.flush(packets::add);
            assertEquals(FRAMES, Av1EncoderPictureFormatTest.decode(encoder, packets).size(), "decoded frames");
        }
        var units = new ArrayList<byte[]>();
        for (var p : packets) {
            units.add(p.data());
        }
        var runs = AnnexEChecker.runs(units);
        assertEquals(1, runs.size(), "one sequence");
        var seq = runs.getFirst().seq();
        assertEquals(level, (seq.majorLevel[0] - 2) * 4 + seq.minorLevel[0], "level");
        var keys = runs.getFirst().frames().stream().filter(f -> !f.showExisting() && f.key()).count();
        assertTrue(keys >= 3, "key frames " + keys);
        List<String> errors = AnnexEChecker.checkStream(units);
        assertTrue(errors.isEmpty(), () -> String.join("\n", errors));
    }
}
