package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers 4:4:0 pictures and settings in the AV1 and AVIF encoders: AV1 cannot code 4:4:0, so the options reject it,
 * and a 4:4:0 picture coded with the automatic chroma format codes as its 4:4:4 widening.
 */
@DisplayName("AV1 encoders and 4:4:0")
class AvifYuv440Test {
    @Test
    @DisplayName("a 4:4:0 coded chroma format is rejected")
    void rejected() {
        assertThrows(IllegalArgumentException.class, () -> AvifEncoderOptions.lossy(80).toBuilder()
                .chromaFormat(ChromaFormat.YUV440).build());
        assertThrows(IllegalArgumentException.class, () -> Av1EncoderOptions.forIntraOnly(80).toBuilder()
                .pictureFormat(ChromaFormat.YUV440, 8).build());
    }

    @Test
    @DisplayName("a 4:4:0 picture codes in 4:4:4, as its 4:4:4 conversion does")
    void automatic() {
        var picture = YuvPicture.allocate(40, 26, ChromaFormat.YUV440, false, ColorInfo.BT709_LIMITED);
        for (var i = 0; i < picture.y().length; i++) {
            picture.y()[i] = (byte) (16 + (i * 13) % 200);
        }
        for (var i = 0; i < picture.u().length; i++) {
            picture.u()[i] = (byte) (90 + (i * 5) % 70);
            picture.v()[i] = (byte) (150 - (i * 3) % 60);
        }
        var options = AvifEncoderOptions.lossy(85);
        var widened = ImageOps.toYuv(picture, ChromaFormat.YUV444, picture.color());
        var file = AvifEncoder.encode(picture, options);
        assertArrayEquals(AvifEncoder.encode(widened, options), file);
        var decoded = (YuvPicture) AvifDecoder.decode(MediaSource.of(file)).picture();
        assertEquals(ChromaFormat.YUV444, decoded.chromaFormat());
    }

    @Test
    @DisplayName("the AV1 encoder takes a 4:4:0 picture, converting it to its coded format")
    void encoderInput() {
        var picture = YuvPicture.allocate(64, 48, ChromaFormat.YUV440, false, ColorInfo.BT709_LIMITED);
        java.util.Arrays.fill(picture.y(), (byte) 100);
        java.util.Arrays.fill(picture.u(), (byte) 120);
        java.util.Arrays.fill(picture.v(), (byte) 140);
        var packets = new java.util.ArrayList<com.github.auties00.smalt.track.Packet>();
        try (var encoder = new Av1Encoder(64, 48, Av1EncoderOptions.forRealtime().toBuilder().dropFrames(false).build())) {
            encoder.encode(new com.github.auties00.smalt.video.VideoFrame(picture, 0, 3000, true), packets::add);
            encoder.flush(packets::add);
        }
        assertEquals(1, packets.size());
    }
}
