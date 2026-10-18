package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.vp8.Vp8Decoder;
import com.github.auties00.smalt.video.vp8.Vp8EncoderOptions;
import com.github.auties00.smalt.video.vp8.Vp8EncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the reference-frame policy of the real-time VP8 engine: the periodic golden refresh of
 * mostly static scenes, its absence in error-resilient mode, and the temporal-layer patterns,
 * whose lower layers must decode on their own.
 */
@DisplayName("real-time VP8 reference policy")
class RtEncoderTest {
    static final long TICK = RtEncoder.TICKS_PER_SECOND / 30;

    static RtEncoder encoder(int width, int height, int bitrate, int layers, int speed, boolean errorResilient) {
        var options = Vp8EncoderOptions.builder().bitrate(bitrate).temporalLayers(layers)
                .speed(Vp8EncoderSpeed.of(speed)).errorResilient(errorResilient).noPeriodicKeyframes()
                .bufferSize(Duration.ofMillis(1000)).initialBufferLevel(Duration.ofMillis(500))
                .optimalBufferLevel(Duration.ofMillis(600)).build();
        var encoder = new RtEncoder(width, height, options);
        encoder.configure(options);
        return encoder;
    }

    /** Returns a textured still background crossed by a small moving square. */
    static YuvPicture scene(int width, int height, int t) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = 80 + ((x * 7 + y * 13) % 41) + ((x / 8 + y / 8) % 2) * 30;
                var dx = x - (20 + t * 3) % width;
                var dy = y - height / 2;
                if (dx >= 0 && dx < 16 && dy >= 0 && dy < 16) {
                    value = 230;
                }
                picture.y()[y * picture.yStride() + x] = (byte) value;
            }
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            for (var x = 0; x < picture.chromaWidth(); x++) {
                picture.u()[y * picture.uvStride() + x] = (byte) (110 + (x + y) % 9);
                picture.v()[y * picture.uvStride() + x] = (byte) (140 - (x * y) % 7);
            }
        }
        return picture;
    }

    static boolean samePlanes(YuvPicture a, YuvPicture b) {
        for (var y = 0; y < a.height(); y++) {
            if (!Arrays.equals(a.y(), y * a.yStride(), y * a.yStride() + a.width(), b.y(), y * b.yStride(),
                    y * b.yStride() + a.width())) {
                return false;
            }
        }
        for (var y = 0; y < a.chromaHeight(); y++) {
            var w = a.chromaWidth();
            if (!Arrays.equals(a.u(), y * a.uvStride(), y * a.uvStride() + w, b.u(), y * b.uvStride(), y * b.uvStride() + w)
                    || !Arrays.equals(a.v(), y * a.uvStride(), y * a.uvStride() + w, b.v(), y * b.uvStride(), y * b.uvStride() + w)) {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("a mostly static scene refreshes the golden frame about every 40 frames, only without error resilience")
    void goldenRefresh() {
        for (var errorResilient : new boolean[]{false, true}) {
            var encoder = encoder(176, 144, 300_000, 1, 6, errorResilient);
            var refreshes = new ArrayList<Integer>();
            for (var i = 0; i < 130; i++) {
                encoder.encode(scene(176, 144, i), i * TICK, (i + 1) * TICK, false);
                if (!encoder.lastKeyFrame() && encoder.refreshGolden) {
                    refreshes.add(i);
                }
            }
            if (errorResilient) {
                assertTrue(refreshes.isEmpty(), "golden refreshed at " + refreshes);
            } else {
                assertTrue(refreshes.size() >= 2, "golden refreshed at " + refreshes);
                for (var k = 1; k < refreshes.size(); k++) {
                    assertTrue(refreshes.get(k) - refreshes.get(k - 1) >= 20, "golden refreshed at " + refreshes);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} layers")
    @ValueSource(ints = {2, 3})
    @DisplayName("frames follow the temporal-layer pattern, and every lower-layer substream decodes on its own")
    void temporalLayers(int layers) {
        var encoder = encoder(160, 96, 250_000, layers, 6, true);
        encoder.keepReconstruction(true);
        var pattern = layers == 2 ? new int[]{0, 1} : new int[]{0, 2, 1, 2};
        var packets = new ArrayList<byte[]>();
        var frameLayers = new ArrayList<Integer>();
        var recons = new ArrayList<YuvPicture>();
        for (var i = 0; i < 48; i++) {
            var data = encoder.encode(scene(160, 96, i), i * TICK, (i + 1) * TICK, i == 21);
            var recon = YuvPicture.allocate(160, 96, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            encoder.copyReconstruction(recon);
            packets.add(data);
            frameLayers.add(encoder.lastLayer());
            recons.add(recon);
            if (i < 21) {
                assertEquals(pattern[i % pattern.length], encoder.lastLayer(), "frame " + i);
            } else {
                assertEquals(pattern[(i - 21) % pattern.length], encoder.lastLayer(), "frame " + i);
            }
            if (encoder.lastLayer() == layers - 1 && layers == 3) {
                assertFalse(encoder.refreshLast || encoder.refreshGolden || encoder.refreshAlt, "frame " + i);
            }
        }
        for (var top = 0; top < layers; top++) {
            var decoded = new int[1];
            var count = 0;
            try (var decoder = new Vp8Decoder(VideoTrack.builder().id(1).codec(Codec.VP8)
                    .timeBase(new Rational(1, 30))
                    .width(160)
                    .height(96)
                    .build())) {
                for (var i = 0; i < packets.size(); i++) {
                    if (frameLayers.get(i) > top) {
                        continue;
                    }
                    count++;
                    var expected = recons.get(i);
                    var label = "layers up to " + top + ", frame " + i;
                    decoder.decode(new Packet(1, packets.get(i), i, i, 1, false), frame -> {
                        decoded[0]++;
                        assertTrue(samePlanes(expected, (YuvPicture) frame.picture()), label);
                    });
                }
            }
            assertEquals(count, decoded[0]);
        }
    }

    @Test
    @DisplayName("the reconstruction is bit-exact with the decoder for every speed")
    void speeds() {
        for (var speed = 4; speed <= 16; speed++) {
            var encoder = encoder(96, 64, 150_000, 1, speed, false);
            encoder.keepReconstruction(true);
            try (var decoder = new Vp8Decoder(VideoTrack.builder().id(1).codec(Codec.VP8)
                    .timeBase(new Rational(1, 30))
                    .width(96)
                    .height(64)
                    .build())) {
                var recon = YuvPicture.allocate(96, 64, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                List<YuvPicture> out = new ArrayList<>();
                for (var i = 0; i < 30; i++) {
                    var data = encoder.encode(scene(96, 64, i), i * TICK, (i + 1) * TICK, false);
                    encoder.copyReconstruction(recon);
                    out.clear();
                    decoder.decode(new Packet(1, data, i, i, 1, false), frame -> out.add((YuvPicture) frame.picture()));
                    assertEquals(1, out.size());
                    assertTrue(samePlanes(recon, out.getFirst()), "speed " + speed + ", frame " + i);
                }
            }
        }
    }
}
