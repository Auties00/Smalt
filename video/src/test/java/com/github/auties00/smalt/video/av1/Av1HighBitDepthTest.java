package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Covers the opt-in high bit depth output of {@link Av1Decoder} on the libaom
 * conformance vectors: 10-bit frames keep their samples, and reducing them gives the default 8-bit
 * output exactly.
 */
class Av1HighBitDepthTest {
    @Test
    @DisplayName("hands out 10-bit frames whose reduction equals the default 8-bit output")
    void tenBit() {
        var stream = Av1RobustnessTest.vector("av1-1-b10-00-quantizer-10.ivf");
        var count = 0;
        try (var narrow = new Av1Decoder(stream.track());
             var wide = new Av1Decoder(stream.track(), Av1DecoderOptions.builder().highBitDepth(true).build())) {
            for (var packet : stream.packets().subList(0, Math.min(6, stream.packets().size()))) {
                var expected = new ArrayList<byte[]>();
                narrow.decode(packet, frame -> expected.add(raw((YuvPicture) frame.picture())));
                var actual = new ArrayList<byte[]>();
                wide.decode(packet, frame -> {
                    var picture = assertInstanceOf(Yuv16Picture.class, frame.picture());
                    assertEquals(10, picture.bitDepth());
                    actual.add(raw(ImageOps.toEightBit(picture)));
                });
                assertEquals(expected.size(), actual.size());
                for (var index = 0; index < expected.size(); index++) {
                    assertArrayEquals(expected.get(index), actual.get(index), "frame " + count);
                    count++;
                }
            }
        }
        assertFalse(count == 0, "no frame decoded");
    }

    @Test
    @DisplayName("keeps 8-bit streams in 8-bit pictures")
    void eightBit() {
        var stream = Av1RobustnessTest.vector("av1-1-b8-01-size-66x66.ivf");
        var frames = new ArrayList<VideoFrame>();
        try (var decoder = new Av1Decoder(stream.track(), Av1DecoderOptions.builder().highBitDepth(true).build())) {
            decoder.decode(stream.packets().getFirst(), frames::add);
        }
        assertInstanceOf(YuvPicture.class, frames.getFirst().picture());
    }

    private static byte[] raw(YuvPicture picture) {
        var out = new java.io.ByteArrayOutputStream();
        for (var y = 0; y < picture.height(); y++) {
            out.write(picture.y(), y * picture.yStride(), picture.width());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            out.write(picture.u(), y * picture.uvStride(), picture.chromaWidth());
            out.write(picture.v(), y * picture.uvStride(), picture.chromaWidth());
        }
        return out.toByteArray();
    }
}
