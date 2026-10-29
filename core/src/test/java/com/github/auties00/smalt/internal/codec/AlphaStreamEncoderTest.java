package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AlphaStreamEncoder} over a fake engine whose packets hold the luma plane of the picture they code,
 * so that every attached alpha frame shows which alpha plane was coded, and its alpha extraction from every picture
 * type.
 */
@DisplayName("AlphaStreamEncoder")
class AlphaStreamEncoderTest {
    private static final int WIDTH = 5;
    private static final int HEIGHT = 3;

    /** Codes a picture as a copy of its luma plane, recording the frames and key frame requests it receives. */
    private static final class LumaEngine implements VideoEncoder {
        final List<VideoFrame> frames = new ArrayList<>();
        final List<Boolean> keyRequested = new ArrayList<>();
        boolean pendingKey;
        boolean dropAll;
        long shift;
        boolean closed;

        @Override
        public Codec codec() {
            return Codec.VP8;
        }

        @Override
        public int width() {
            return WIDTH;
        }

        @Override
        public int height() {
            return HEIGHT;
        }

        @Override
        public Rational timeBase() {
            return Rational.MICROSECONDS;
        }

        @Override
        public Optional<byte[]> codecConfig() {
            return Optional.empty();
        }

        @Override
        public void encode(VideoFrame frame, Consumer<Packet> output) {
            var yuv = (YuvPicture) frame.picture();
            assertEquals(ChromaFormat.YUV420, yuv.chromaFormat());
            assertEquals(ColorInfo.BT601_LIMITED, yuv.color());
            for (var sample = 0; sample < yuv.chromaWidth() * yuv.chromaHeight(); sample++) {
                assertEquals(128, yuv.u()[sample] & 0xFF);
                assertEquals(128, yuv.v()[sample] & 0xFF);
            }
            frames.add(frame);
            keyRequested.add(pendingKey);
            pendingKey = false;
            if (dropAll) {
                return;
            }
            var data = new byte[WIDTH * HEIGHT];
            for (var row = 0; row < HEIGHT; row++) {
                System.arraycopy(yuv.y(), row * yuv.yStride(), data, row * WIDTH, WIDTH);
            }
            output.accept(new Packet(0, new byte[]{1}, frame.pts(), frame.pts(), 0, Set.of(PacketFlag.DISCARD),
                    PacketSideData.NONE));
            output.accept(new Packet(0, data, frame.pts() + shift, frame.pts() + shift, frame.duration(), false));
        }

        @Override
        public void flush(Consumer<Packet> output) {
        }

        @Override
        public void requestKeyframe() {
            pendingKey = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static YuvPicture withAlpha(int seed) {
        var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, true, ColorInfo.BT709_LIMITED);
        for (var index = 0; index < WIDTH * HEIGHT; index++) {
            picture.alpha()[index] = (byte) (seed * 17 + index * 3);
        }
        return picture;
    }

    private static byte[] alphaOf(YuvPicture picture) {
        return Arrays.copyOf(picture.alpha(), WIDTH * HEIGHT);
    }

    private static Packet packet(long pts, boolean key) {
        return new Packet(3, new byte[]{9, 9}, pts, pts, 10, key);
    }

    @Nested
    @DisplayName("pairing")
    class Pairing {
        @Test
        @DisplayName("each shown packet carries the coded alpha plane of the picture pushed at its timestamp")
        void attaches() {
            var engine = new LumaEngine();
            var stream = new AlphaStreamEncoder(engine, ColorInfo.BT601_LIMITED);
            var pictures = new ArrayList<YuvPicture>();
            for (var index = 0; index < 4; index++) {
                pictures.add(withAlpha(index));
                stream.push(pictures.getLast(), index * 10, 10);
            }
            for (var index = 0; index < 4; index++) {
                var packet = packet(index * 10, index == 0);
                var attached = stream.attach(packet);
                assertArrayEquals(alphaOf(pictures.get(index)), attached.sideData().alphaFrame().orElseThrow());
                assertArrayEquals(packet.data(), attached.data());
                assertEquals(packet.pts(), attached.pts());
                assertEquals(index * 10L, engine.frames.get(index).pts());
                assertEquals(10, engine.frames.get(index).duration());
            }
            assertEquals(List.of(true, false, false, false), engine.keyRequested);
        }

        @Test
        @DisplayName("pictures whose packets never come (dropped frames) are skipped")
        void dropped() {
            var engine = new LumaEngine();
            var stream = new AlphaStreamEncoder(engine, ColorInfo.BT601_LIMITED);
            for (var index = 0; index < 5; index++) {
                stream.push(withAlpha(index), index, 1);
            }
            assertArrayEquals(alphaOf(withAlpha(1)), stream.attach(packet(1, true)).sideData().alphaFrame().orElseThrow());
            assertArrayEquals(alphaOf(withAlpha(4)), stream.attach(packet(4, false)).sideData().alphaFrame().orElseThrow());
            assertEquals(2, engine.frames.size());
        }

        @Test
        @DisplayName("hidden packets, packets without a pushed picture and frames the engine drops get no alpha frame")
        void untouched() {
            var engine = new LumaEngine();
            var stream = new AlphaStreamEncoder(engine, ColorInfo.BT601_LIMITED);
            stream.push(withAlpha(1), 5, 1);
            var hidden = new Packet(0, new byte[]{7}, 5, 5, 0, Set.of(PacketFlag.DISCARD), PacketSideData.NONE);
            assertSame(hidden, stream.attach(hidden));
            var missing = packet(3, false);
            assertSame(missing, stream.attach(missing));
            assertTrue(stream.attach(packet(5, false)).sideData().alphaFrame().isPresent());
            var late = packet(6, false);
            assertSame(late, stream.attach(late));
            engine.dropAll = true;
            stream.push(withAlpha(2), 7, 1);
            var dropped = packet(7, false);
            assertSame(dropped, stream.attach(dropped));
            stream.close();
            assertTrue(engine.closed);
        }

        @Test
        @DisplayName("an engine packet of another picture's timestamp is never attached")
        void otherTimestamp() {
            var engine = new LumaEngine();
            engine.shift = -1;
            var stream = new AlphaStreamEncoder(engine, ColorInfo.BT601_LIMITED);
            stream.push(withAlpha(1), 4, 1);
            var packet = packet(4, true);
            assertSame(packet, stream.attach(packet));
            assertEquals(1, engine.frames.size());
        }

        @Test
        @DisplayName("keeps the other side data of the packet")
        void keepsSideData() {
            var stream = new AlphaStreamEncoder(new LumaEngine(), ColorInfo.BT601_LIMITED);
            stream.push(withAlpha(3), 0, 1);
            var side = PacketSideData.builder().hdr10Plus(new byte[]{1, 2}).discard(1, 2).build();
            var attached = stream.attach(new Packet(0, new byte[]{1}, 0, 0, 5, Set.of(PacketFlag.KEY), side));
            assertArrayEquals(new byte[]{1, 2}, attached.sideData().hdr10Plus().orElseThrow());
            assertEquals(1, attached.sideData().discardStart());
            assertEquals(2, attached.sideData().discardEnd());
            assertTrue(attached.keyframe());
        }

        @Test
        @DisplayName("rejects a picture of another size")
        void size() {
            var stream = new AlphaStreamEncoder(new LumaEngine(), ColorInfo.BT601_LIMITED);
            assertThrows(IllegalArgumentException.class, () -> stream.push(RgbaPicture.allocate(4, 3, true), 0, 1));
        }
    }

    @Nested
    @DisplayName("alpha extraction")
    class Extraction {
        private final byte[] plane = new byte[WIDTH * HEIGHT];

        private int expected(int x, int y) {
            return (x * 50 + y * 20) & 0xFF;
        }

        private void assertPlane(int tolerance) {
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var got = plane[y * WIDTH + x] & 0xFF;
                    assertTrue(Math.abs(got - expected(x, y)) <= tolerance, "(" + x + "," + y + ") " + got);
                }
            }
        }

        @Test
        @DisplayName("an opaque picture gives 255")
        void opaque() {
            AlphaStreamEncoder.extract(YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false,
                    ColorInfo.BT601_LIMITED), plane, WIDTH, HEIGHT);
            for (var value : plane) {
                assertEquals(255, value & 0xFF);
            }
        }

        @Test
        @DisplayName("an 8-bit planar picture with a padded alpha stride")
        void yuv() {
            var stride = WIDTH + 3;
            var alpha = new byte[stride * HEIGHT];
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    alpha[y * stride + x] = (byte) expected(x, y);
                }
            }
            var base = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV444, false, ColorInfo.JPEG);
            AlphaStreamEncoder.extract(base.toBuilder().alpha(alpha, stride).build(), plane, WIDTH, HEIGHT);
            assertPlane(0);
        }

        @Test
        @DisplayName("a 10-bit planar picture, rounded to 8 bits")
        void yuv16() {
            var picture = Yuv16Picture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, 10, true, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    picture.alpha()[y * picture.alphaStride() + x] = (short) Math.round(expected(x, y) * 1023 / 255.0);
                }
            }
            AlphaStreamEncoder.extract(picture, plane, WIDTH, HEIGHT);
            assertPlane(0);
        }

        @Test
        @DisplayName("8-bit and 16-bit RGBA pictures")
        void rgba() {
            var eight = RgbaPicture.allocate(WIDTH, HEIGHT, true);
            var sixteen = Rgba16Picture.allocate(WIDTH, HEIGHT, true);
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    eight.pixels()[y * eight.stride() + 4 * x + 3] = (byte) expected(x, y);
                    sixteen.pixels()[y * sixteen.stride() + 4 * x + 3] = (short) (expected(x, y) * 257);
                }
            }
            AlphaStreamEncoder.extract(eight, plane, WIDTH, HEIGHT);
            assertPlane(0);
            Arrays.fill(plane, (byte) 0);
            AlphaStreamEncoder.extract(sixteen, plane, WIDTH, HEIGHT);
            assertPlane(0);
        }

        @Test
        @DisplayName("channel pictures of every sample type, alpha found by the layout")
        void channels() {
            var layout = PixelLayout.GRAY_ALPHA;
            var eight = Channel8Picture.allocate(WIDTH, HEIGHT, layout);
            var sixteen = Channel16Picture.allocate(WIDTH, HEIGHT, layout);
            var floating = ChannelFloatPicture.allocate(WIDTH, HEIGHT, layout);
            var alpha = layout.alphaIndex();
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    eight.pixels()[y * eight.stride() + 2 * x + alpha] = (byte) expected(x, y);
                    sixteen.pixels()[y * sixteen.stride() + 2 * x + alpha] = (short) (expected(x, y) * 257);
                    floating.pixels()[y * floating.stride() + 2 * x + alpha] = expected(x, y) / 255.0f;
                }
            }
            for (var picture : List.of(eight, sixteen, floating)) {
                Arrays.fill(plane, (byte) 0);
                AlphaStreamEncoder.extract(picture, plane, WIDTH, HEIGHT);
                assertPlane(0);
            }
            assertFalse(PixelLayout.GRAY.hasAlpha());
        }
    }
}
