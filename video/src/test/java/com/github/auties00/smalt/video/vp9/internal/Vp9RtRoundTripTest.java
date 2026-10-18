package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks that every stream of the real-time VP9 encoder decodes, with the module's decoder, to
 * exactly the encoder's own reconstruction.
 */
@DisplayName("VP9 real-time encoder round trip")
class Vp9RtRoundTripTest {
    static final long TICK = Vp9RtEncoder.TICKS_PER_SECOND / 30;

    static Vp9RtEncoder encoder(int width, int height, int bitrate, int speed, boolean errorResilient,
                                boolean cyclicRefresh) {
        var enc = new Vp9RtEncoder(width, height, 1, 6, 1, MediaLimits.DEFAULT);
        enc.configure(Vp9TestVideo.rtOptions(bitrate, speed).errorResilient(errorResilient)
                .adaptiveQuantization(cyclicRefresh).build());
        return enc;
    }

    static List<byte[]> roundTrip(Vp9RtEncoder enc, List<YuvPicture> pictures, int forceKeyAt) {
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        var packets = new ArrayList<byte[]>();
        var recon = YuvPicture.allocate(enc.width, enc.height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var i = 0; i < pictures.size(); i++) {
            var data = enc.encode(pictures.get(i), i * TICK, (i + 1) * TICK, i == forceKeyAt);
            if (data == null) {
                continue;
            }
            packets.add(data);
            enc.copyReconstruction(recon);
            try {
                decoder.decodePacket(data, 0, data.length);
            } catch (RuntimeException exception) {
                throw new AssertionError("frame " + i + " failed to decode", exception);
            }
            var frame = decoder.shownFrame();
            assertNotNull(frame, "frame " + i);
            compare(recon, frame, i);
        }
        return packets;
    }

    static void compare(YuvPicture recon, Vp9Frame frame, int index) {
        for (var plane = 0; plane < 3; plane++) {
            var expected = plane == 0 ? recon.y() : plane == 1 ? recon.u() : recon.v();
            var stride = plane == 0 ? recon.yStride() : recon.uvStride();
            var w = frame.cropWidths[plane];
            var h = frame.cropHeights[plane];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var e = expected[y * stride + x] & 0xFF;
                    var a = frame.planes8[plane][y * frame.strides[plane] + x] & 0xFF;
                    if (e != a) {
                        fail("frame " + index + " plane " + plane + " differs at " + x + "," + y + ": encoder " + e
                                + ", decoder " + a);
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}x{1} speed {2}")
    @CsvSource({"64, 64, 6", "352, 288, 6", "176, 144, 5", "200, 150, 7", "352, 288, 8", "352, 288, 9"})
    @DisplayName("synthetic video decodes to the reconstruction")
    void synthetic(int width, int height, int speed) {
        var enc = encoder(width, height, 300_000, speed, true, true);
        var packets = roundTrip(enc, Vp9TestVideo.synthesize(width, height, 30), -1);
        assertEquals(30, packets.size());
    }

    @ParameterizedTest(name = "{0} layers")
    @CsvSource({"2", "3"})
    @DisplayName("temporal layers decode whole and without their upper layers")
    void temporalLayers(int layers) {
        var width = 320;
        var height = 240;
        var enc = new Vp9RtEncoder(width, height, layers, 0, 1, MediaLimits.DEFAULT);
        enc.configure(Vp9TestVideo.rtOptions(400_000, 6).errorResilient(true).adaptiveQuantization(true).build());
        var pictures = Vp9TestVideo.synthesize(width, height, 24);
        var full = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        var base = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        var recon = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        var expectedLayer = layers == 2 ? new int[]{0, 1} : new int[]{0, 2, 1, 2};
        for (var i = 0; i < pictures.size(); i++) {
            var data = enc.encode(pictures.get(i), i * TICK, (i + 1) * TICK, false);
            assertNotNull(data);
            assertEquals(expectedLayer[i % expectedLayer.length], enc.lastLayer(), "layer of frame " + i);
            enc.copyReconstruction(recon);
            full.decodePacket(data, 0, data.length);
            compare(recon, full.shownFrame(), i);
            if (enc.lastLayer() < layers - 1) {
                base.decodePacket(data, 0, data.length);
                compare(recon, base.shownFrame(), i);
            }
        }
    }

    @Test
    @DisplayName("non error resilient streams use the previous frame's vectors and still decode")
    void nonResilient() {
        var enc = encoder(320, 240, 400_000, 6, false, true);
        roundTrip(enc, Vp9TestVideo.synthesize(320, 240, 30), 12);
    }
}
