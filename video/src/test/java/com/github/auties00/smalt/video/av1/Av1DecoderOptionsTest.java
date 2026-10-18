package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the options convention on {@link Av1DecoderOptions}, and that the decoder's threading
 * and limits come from it.
 */
@DisplayName("Av1DecoderOptions")
class Av1DecoderOptionsTest {
    @Test
    @DisplayName("defaults, builders and equality follow the options convention")
    void convention() {
        var defaults = Av1DecoderOptions.defaults();
        assertEquals(MediaLimits.DEFAULT, defaults.limits());
        assertEquals(0, defaults.threads());
        assertEquals(1, defaults.maxFrameDelay());
        assertFalse(defaults.highBitDepth());
        var small = MediaLimits.builder().maxDimension(64).build();
        var changed = defaults.toBuilder().limits(small).threads(1).maxFrameDelay(3).highBitDepth(true).build();
        assertEquals(changed, Av1DecoderOptions.builder().limits(small).threads(1).maxFrameDelay(3).highBitDepth(true)
                .build());
        assertEquals(3, changed.maxFrameDelay());
        assertNotEquals(defaults, defaults.toBuilder().maxFrameDelay(2).build());
        assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().maxFrameDelay(0).build());
        assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().maxFrameDelay(65).build());
        assertEquals(changed, changed.toBuilder().build());
        assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
        assertNotEquals(defaults, changed);
        assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().threads(-1).build());
        assertThrows(NullPointerException.class, () -> defaults.toBuilder().limits(null).build());
        assertThrows(NullPointerException.class, () -> new Av1Decoder(track(64, 64), null));
    }

    @Test
    @DisplayName("decoding on the calling thread gives the same pictures as the shared pool, within the limits")
    void threadingAndLimits() {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Av1Encoder(320, 256, Av1EncoderOptions.forRealtime().toBuilder().dropFrames(false)
                .build())) {
            for (var i = 0; i < 3; i++) {
                encoder.encode(new VideoFrame(picture(320, 256, i), i * 3000L, 3000, false), packets::add);
            }
        }
        var pooled = decode(packets, Av1DecoderOptions.defaults());
        var calling = decode(packets, Av1DecoderOptions.builder().threads(1).build());
        assertEquals(3, pooled.size());
        for (var i = 0; i < pooled.size(); i++) {
            assertArrayEquals(pooled.get(i), calling.get(i), "frame " + i);
        }
        assertThrows(MediaException.LimitExceeded.class, () -> decode(packets,
                Av1DecoderOptions.builder().limits(MediaLimits.builder().maxDimension(256).build()).build()));
    }

    @Test
    @DisplayName("a frame delay returns the same frames with their timestamps, each up to that many packets minus one later")
    void frameDelay() {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Av1Encoder(320, 256, Av1EncoderOptions.forRealtime().toBuilder().dropFrames(false)
                .build())) {
            for (var i = 0; i < 10; i++) {
                encoder.encode(new VideoFrame(picture(320, 256, i), i * 3000L, 3000, false), packets::add);
            }
        }
        var direct = decode(packets, Av1DecoderOptions.defaults());
        assertEquals(packets.size(), direct.size());
        for (var delay : new int[]{2, 4}) {
            for (var threads : new int[]{0, 1}) {
                var options = Av1DecoderOptions.builder().maxFrameDelay(delay).threads(threads).build();
                var effective = threads == 1 ? 1 : delay;
                var frames = new ArrayList<byte[]>();
                var timestamps = new ArrayList<Long>();
                var calls = new ArrayList<Integer>();
                try (var decoder = new Av1Decoder(track(320, 256), options)) {
                    for (var i = 0; i < packets.size(); i++) {
                        var call = i;
                        decoder.decode(packets.get(i), frame -> {
                            var picture = (YuvPicture) frame.picture();
                            frames.add(Arrays.copyOf(picture.y(), picture.y().length));
                            timestamps.add(frame.pts());
                            calls.add(call);
                        });
                    }
                    decoder.flush(frame -> {
                        var picture = (YuvPicture) frame.picture();
                        frames.add(Arrays.copyOf(picture.y(), picture.y().length));
                        timestamps.add(frame.pts());
                        calls.add(packets.size());
                    });
                }
                assertEquals(direct.size(), frames.size(), "frames with delay " + delay);
                for (var i = 0; i < frames.size(); i++) {
                    assertArrayEquals(direct.get(i), frames.get(i), "frame " + i + " with delay " + delay);
                    assertEquals(packets.get(i).pts(), timestamps.get(i), "timestamp " + i);
                    var expected = i + effective - 1 < packets.size() ? i + effective - 1 : packets.size();
                    assertEquals(expected, calls.get(i), "call returning frame " + i + " with delay " + delay);
                }
            }
        }
    }

    @Test
    @DisplayName("frames of an alternate reference structure decode to the same pictures at every thread count and frame delay")
    void referenceStructure() {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Av1Encoder(192, 128, Av1EncoderOptions.ofQuality(55))) {
            for (var i = 0; i < 24; i++) {
                encoder.encode(new VideoFrame(picture(192, 128, i), i * 3000L, 3000, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        var direct = decodeAll(packets, Av1DecoderOptions.builder().threads(1).build(), 192, 128);
        assertEquals(24, direct.size());
        for (var threads : new int[]{0, 2, 8}) {
            for (var delay : new int[]{1, 3, 8}) {
                var frames = decodeAll(packets, Av1DecoderOptions.builder().threads(threads)
                        .maxFrameDelay(delay).build(), 192, 128);
                assertEquals(direct.size(), frames.size(), "threads " + threads + " delay " + delay);
                for (var i = 0; i < frames.size(); i++) {
                    assertArrayEquals(direct.get(i), frames.get(i), "frame " + i + " threads " + threads
                            + " delay " + delay);
                }
            }
        }
    }

    @Test
    @DisplayName("closing a decoder with frames in flight drops them")
    void closeInFlight() {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Av1Encoder(320, 256, Av1EncoderOptions.forRealtime().toBuilder().dropFrames(false)
                .build())) {
            for (var i = 0; i < 6; i++) {
                encoder.encode(new VideoFrame(picture(320, 256, i), i * 3000L, 3000, false), packets::add);
            }
        }
        var count = new int[1];
        var decoder = new Av1Decoder(track(320, 256), Av1DecoderOptions.builder().maxFrameDelay(8).build());
        for (var packet : packets) {
            decoder.decode(packet, frame -> count[0]++);
        }
        decoder.close();
        assertEquals(0, count[0]);
        assertThrows(IllegalStateException.class, () -> decoder.flush(frame -> count[0]++));
    }

    private static List<byte[]> decodeAll(List<Packet> packets, Av1DecoderOptions options, int width, int height) {
        var pictures = new ArrayList<byte[]>();
        try (var decoder = new Av1Decoder(track(width, height), options)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> {
                    var picture = (YuvPicture) frame.picture();
                    pictures.add(Arrays.copyOf(picture.y(), picture.y().length));
                });
            }
            decoder.flush(frame -> {
                var picture = (YuvPicture) frame.picture();
                pictures.add(Arrays.copyOf(picture.y(), picture.y().length));
            });
        }
        return pictures;
    }

    private static List<byte[]> decode(List<Packet> packets, Av1DecoderOptions options) {
        var pictures = new ArrayList<byte[]>();
        try (var decoder = new Av1Decoder(track(320, 256), options)) {
            assertEquals(options, decoder.options());
            for (var packet : packets) {
                decoder.decode(packet, frame -> {
                    var picture = (YuvPicture) frame.picture();
                    pictures.add(Arrays.copyOf(picture.y(), picture.y().length));
                });
            }
        }
        return pictures;
    }

    private static VideoTrack track(int width, int height) {
        return VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(new Rational(1, 90_000)).width(width)
                .height(height).build();
    }

    private static YuvPicture picture(int width, int height, int index) {
        var picture = YuvPicture.allocate(width, height, com.github.auties00.smalt.image.ChromaFormat.YUV420, false,
                com.github.auties00.smalt.image.ColorInfo.BT601_LIMITED);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) ((x * 3 + y * 2 + index * 5) ^ (x * y >> 4));
            }
        }
        Arrays.fill(picture.u(), (byte) 110);
        Arrays.fill(picture.v(), (byte) 140);
        return picture;
    }
}
