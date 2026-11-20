package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.internal.H264Sps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Covers the sample aspect ratio of {@link H264StreamInfo}, from x264 streams that signal it in
 * the VUI by table index and as {@code Extended_SAR}, against the probe of the same record.
 */
@DisplayName("H.264 VUI sample aspect ratio")
class H264AspectTest {
    static byte[] avcC(String sar) {
        var file = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.2"),
                List.of("-vf", "setsar=" + sar, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-x264-params", "threads=1"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(file)))) {
            return demuxer.tracks().getFirst().codecConfig().orElseThrow();
        }
    }

    @ParameterizedTest(name = "setsar {0}")
    @CsvSource({"64/45, 64, 45", "12/11, 12, 11", "40/33, 40, 33", "7/5, 7, 5", "1/1, 1, 1", "300/200, 3, 2"})
    @DisplayName("reports the ratio of the sequence parameter set in lowest terms")
    void sampleAspect(String sar, long width, long height) {
        var config = avcC(sar);
        var expected = new Rational(width, height);
        var info = H264StreamInfo.probe(config);
        assertEquals(Optional.of(expected), info.sampleAspectRatio());
        assertEquals(expected, VideoConfigs.describe(Codec.H264, config).sampleAspect().reduce());
        assertEquals(info, H264StreamInfo.probe(config));
        assertNotEquals(info, H264StreamInfo.probe(avcC(sar.equals("1/1") ? "7/5" : "1/1")));
    }

    /**
     * A Baseline sequence parameter set of 16x16 samples, with a VUI holding only the given aspect
     * ratio information when {@code vui} is set.
     */
    static byte[] sps(boolean vui, int idc, int width, int height) {
        var writer = new H264BitWriter();
        writer.bits(66, 8).bits(0, 8).bits(30, 8).ue(0);
        writer.ue(0).ue(2).ue(1).bit(0).ue(0).ue(0).bit(1).bit(1).bit(0).bit(vui ? 1 : 0);
        if (vui) {
            writer.bit(idc >= 0 ? 1 : 0);
            if (idc >= 0) {
                writer.bits(idc, 8);
                if (idc == 255) {
                    writer.bits(width, 16).bits(height, 16);
                }
            }
            // overscan, video signal type, chroma location, timing, NAL and VCL HRD, pic_struct, restriction
            writer.bit(0).bit(0).bit(0).bit(0).bit(0).bit(0).bit(0).bit(0);
        }
        return H264BitWriter.nal(3, 7, writer.trailing());
    }

    @ParameterizedTest(name = "vui {0} idc {1} {2}x{3}")
    @CsvSource({"false, -1, 0, 0, 0, 0", "true, -1, 0, 0, 0, 0", "true, 0, 0, 0, 0, 0", "true, 17, 0, 0, 0, 0",
            "true, 254, 0, 0, 0, 0", "true, 255, 0, 9, 0, 0", "true, 255, 9, 0, 0, 0", "true, 255, 9, 6, 3, 2",
            "true, 13, 0, 0, 160, 99", "true, 14, 0, 0, 4, 3"})
    @DisplayName("reads aspect_ratio_idc and Extended_SAR, and no ratio when the VUI leaves it unspecified")
    void syntax(boolean vui, int idc, int width, int height, long numerator, long denominator) {
        var expected = numerator == 0 ? Optional.<Rational>empty() : Optional.of(new Rational(numerator, denominator));
        assertEquals(expected, H264StreamInfo.probe(sps(vui, idc, width, height)).sampleAspectRatio());
    }

    @Test
    @DisplayName("reports the square samples the encoder's own streams signal")
    void encoder() {
        var encoder = new com.github.auties00.smalt.video.h264.encoder.H264Encoder(32, 32,
                com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions.forFile());
        try (encoder) {
            assertEquals(Optional.of(new Rational(1, 1)), H264StreamInfo.probe(encoder.codecConfig().orElseThrow())
                    .sampleAspectRatio());
        }
    }

    @Test
    @DisplayName("shares Table E-1 with HEVC through the index accessors")
    void table() {
        assertEquals(12, H264Sps.aspectWidthOf(2));
        assertEquals(11, H264Sps.aspectHeightOf(2));
        assertEquals(2, H264Sps.aspectWidthOf(16));
        assertEquals(1, H264Sps.aspectHeightOf(16));
        for (var idc : new int[]{0, 17, 254, 255, -1}) {
            assertEquals(0, H264Sps.aspectWidthOf(idc));
            assertEquals(0, H264Sps.aspectHeightOf(idc));
        }
    }
}
