package com.github.auties00.smalt.image.jxl.internal.render;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.JxlCodestream;
import com.github.auties00.smalt.image.jxl.internal.header.BitDepth;
import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the spot color blend: the formula, the order of several spot channels, the channels left alone, and
 * where the blend happens for the frames of the vectors.
 */
class SpotColorsTest {
    static ImageHeader header(String name) throws IOException {
        try (var input = SpotColorsTest.class.getResourceAsStream("/com/github/auties00/smalt/image/jxl/features/"
                + name)) {
            return JxlCodestream.open(input.readAllBytes(), MediaLimits.DEFAULT).header();
        }
    }

    static ImageHeader withChannels(ImageHeader h, List<ExtraChannelInfo> channels) {
        return new ImageHeader(h.width(), h.height(), h.orientation(), h.intrinsicWidth(), h.intrinsicHeight(),
                h.previewWidth(), h.previewHeight(), h.animation(), h.bitDepth(), h.modular16Bit(), channels,
                h.xybEncoded(), h.colorEncoding(), h.toneMapping(), h.opsinInverse(), h.upsampling2(),
                h.upsampling4(), h.upsampling8());
    }

    static ExtraChannelInfo spot(float r, float g, float b, float solidity) {
        return new ExtraChannelInfo(ExtraChannelInfo.TYPE_SPOT_COLOR, BitDepth.DEFAULT, 0, "", false,
                new float[]{r, g, b, solidity}, 0);
    }

    static ExtraChannelInfo depth() {
        return new ExtraChannelInfo(ExtraChannelInfo.TYPE_DEPTH, BitDepth.DEFAULT, 0, "", false, null, 0);
    }

    @Test
    @DisplayName("blends each spot channel in file order with the weight solidity times sample")
    void formula() throws IOException {
        var header = withChannels(header("spot-xyb.jxl"), List.of(spot(1, 0, 0.5f, 0.5f), depth(),
                spot(0, 1, 0, 1)));
        var planes = new float[][]{{0.2f, 0.8f}, {0.4f, 0.6f}, {0.6f, 0.4f}, {1, 0.5f}, {0.9f, 0.9f}, {0.25f, 0}};
        SpotColors.mix(planes, 3, 2, header);
        float[][] color = {{0.2f, 0.8f}, {0.4f, 0.6f}, {0.6f, 0.4f}};
        float[][] spots = {{1, 0, 0.5f, 0.5f}, {0, 1, 0, 1}};
        float[][] weights = {{1, 0.5f}, {0.25f, 0}};
        for (var i = 0; i < 2; i++) {
            for (var s = 0; s < 2; s++) {
                var mix = spots[s][3] * weights[s][i];
                for (var c = 0; c < 3; c++) {
                    color[c][i] = mix * spots[s][c] + (1 - mix) * color[c][i];
                }
            }
        }
        for (var c = 0; c < 3; c++) {
            assertArrayEquals(color[c], planes[c], 1e-6f);
        }
        assertArrayEquals(new float[]{0.9f, 0.9f}, planes[4], "the depth channel is left alone");
    }

    @Test
    @DisplayName("blends the red component of the spot color into a gray image's one channel")
    void gray() throws IOException {
        var header = withChannels(header("spot-xyb.jxl"), List.of(spot(0.5f, 0, 1, 0.5f)));
        var planes = new float[][]{{0.25f, 0.75f}, {0.5f, 1}};
        SpotColors.mix(planes, 1, 2, header);
        assertArrayEquals(new float[]{0.25f * 0.5f + 0.75f * 0.25f, 0.5f * 0.5f + 0.5f * 0.75f}, planes[0], 1e-6f);
        // the linear luminance of an XYB gray image sits in plane 0, ahead of the two other converted planes
        var linear = new float[][]{{0.25f, 0.75f}, {0.1f, 0.1f}, {0.2f, 0.2f}, {0.5f, 1}};
        SpotColors.mix(linear, 1, 3, 2, header);
        assertArrayEquals(planes[0], linear[0], 1e-6f);
        assertArrayEquals(new float[]{0.1f, 0.1f}, linear[1]);
        assertArrayEquals(new float[]{0.2f, 0.2f}, linear[2]);
    }

    @Test
    @DisplayName("leaves images of two or four color channels unchanged")
    void otherCounts() throws IOException {
        var header = withChannels(header("spot-xyb.jxl"), List.of(spot(1, 1, 1, 1)));
        var planes = new float[][]{{0.25f}, {0.5f}, {1}};
        SpotColors.mix(planes, 2, 1, header);
        assertArrayEquals(new float[]{0.25f}, planes[0]);
        assertArrayEquals(new float[]{0.5f}, planes[1]);
    }

    @Test
    @DisplayName("an image without spot channels has none to blend")
    void absent() throws IOException {
        assertFalse(SpotColors.present(header("progressive.jxl")));
        assertTrue(SpotColors.present(header("spot-xyb.jxl")));
    }
}
