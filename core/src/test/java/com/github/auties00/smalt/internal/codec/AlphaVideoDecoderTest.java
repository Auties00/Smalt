package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AlphaVideoDecoder} over fake codec decoders whose pictures carry the packet bytes as their luma
 * plane, so that the expected alpha plane of every frame is the alpha frame's payload.
 */
@DisplayName("AlphaVideoDecoder")
class AlphaVideoDecoderTest {
    private static final int WIDTH = 4;
    private static final int HEIGHT = 2;

    /**
     * Decodes a packet into a picture whose luma is the first {@code width * height} payload bytes; a payload
     * starting with 0xEE is corrupt, one starting with 0xEF unsupported and one starting with 0xED over the
     * limits. Pictures are recycled, as codec decoders do, and the output can lag the
     * input by a number of packets to stand for a decoder that reorders or delays frames.
     */
    private static final class LumaDecoder implements VideoDecoder {
        private final int width;
        private final int height;
        private final int depth;
        private final int delay;
        private final ArrayDeque<Packet> held = new ArrayDeque<>();
        private final Picture[] pool = new Picture[2];
        private int next;
        private boolean closed;

        LumaDecoder(int width, int height, int depth, int delay) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.delay = delay;
        }

        @Override
        public void decode(Packet packet, Consumer<VideoFrame> output) {
            if ((packet.data()[0] & 0xFF) == 0xEE) {
                throw new MediaException.Malformed("corrupt test packet");
            }
            if ((packet.data()[0] & 0xFF) == 0xEF) {
                throw new MediaException.Unsupported("test", "unsupported test packet");
            }
            if ((packet.data()[0] & 0xFF) == 0xED) {
                throw new MediaException.LimitExceeded("test packet over the limits");
            }
            held.add(packet);
            while (held.size() > delay) {
                emit(held.removeFirst(), output);
            }
        }

        @Override
        public void flush(Consumer<VideoFrame> output) {
            while (!held.isEmpty()) {
                emit(held.removeFirst(), output);
            }
        }

        private void emit(Packet packet, Consumer<VideoFrame> output) {
            var index = next++ & 1;
            if (depth == 8) {
                var picture = pool[index] instanceof YuvPicture yuv ? yuv
                        : YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                pool[index] = picture;
                System.arraycopy(packet.data(), 0, picture.y(), 0, width * height);
            } else {
                var picture = pool[index] instanceof Yuv16Picture yuv ? yuv
                        : Yuv16Picture.allocate(width, height, ChromaFormat.YUV420, depth, false, ColorInfo.BT709_LIMITED);
                pool[index] = picture;
                for (var sample = 0; sample < width * height; sample++) {
                    picture.y()[sample] = (short) (((packet.data()[2 * sample] & 0xFF) << 8)
                            | (packet.data()[2 * sample + 1] & 0xFF));
                }
            }
            output.accept(new VideoFrame(pool[index], packet.pts(), packet.duration(), packet.keyframe()));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static byte[] plane(int seed) {
        var data = new byte[WIDTH * HEIGHT];
        for (var index = 0; index < data.length; index++) {
            data[index] = (byte) (seed * 31 + index * 7);
        }
        data[0] = (byte) (seed & 0x7F);
        return data;
    }

    private static Packet packet(long pts, byte[] color, byte[] alpha) {
        var side = alpha == null ? PacketSideData.NONE : PacketSideData.builder().alphaFrame(alpha).build();
        return new Packet(1, color, pts, pts, 1, Set.of(), side);
    }

    /** Collects a copy of every frame's alpha plane, or {@code null} for a frame without one. */
    private static final class Frames implements Consumer<VideoFrame> {
        final List<Long> pts = new ArrayList<>();
        final List<byte[]> alpha = new ArrayList<>();
        final List<byte[]> luma = new ArrayList<>();
        final List<short[]> deepAlpha = new ArrayList<>();
        final List<Picture> pictures = new ArrayList<>();

        @Override
        public void accept(VideoFrame frame) {
            pts.add(frame.pts());
            pictures.add(frame.picture());
            switch (frame.picture()) {
                case YuvPicture yuv -> {
                    assertTrue(yuv.hasAlpha());
                    var copy = new byte[yuv.width() * yuv.height()];
                    for (var row = 0; row < yuv.height(); row++) {
                        System.arraycopy(yuv.alpha(), row * yuv.alphaStride(), copy, row * yuv.width(), yuv.width());
                    }
                    alpha.add(copy);
                    luma.add(Arrays.copyOf(yuv.y(), yuv.width() * yuv.height()));
                }
                case Yuv16Picture yuv -> {
                    assertTrue(yuv.hasAlpha());
                    deepAlpha.add(Arrays.copyOf(yuv.alpha(), yuv.width() * yuv.height()));
                }
                default -> alpha.add(null);
            }
        }
    }

    private static byte[] opaque() {
        var data = new byte[WIDTH * HEIGHT];
        Arrays.fill(data, (byte) 255);
        return data;
    }

    @Nested
    @DisplayName("8-bit streams")
    class EightBit {
        @Test
        @DisplayName("the alpha plane of each frame is the luma of the alpha frame its packet carries")
        void paired() {
            var decoder = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 8, 0), new LumaDecoder(WIDTH, HEIGHT, 8, 0));
            var frames = new Frames();
            for (var index = 0; index < 5; index++) {
                decoder.decode(packet(index * 10, plane(index), plane(100 + index)), frames);
            }
            decoder.flush(frames);
            assertEquals(List.of(0L, 10L, 20L, 30L, 40L), frames.pts);
            for (var index = 0; index < 5; index++) {
                assertArrayEquals(plane(100 + index), frames.alpha.get(index), "frame " + index);
                assertArrayEquals(plane(index), frames.luma.get(index), "frame " + index);
            }
        }

        @Test
        @DisplayName("a packet without an alpha frame, or with a corrupt one, shows an opaque frame")
        void opaqueFallbacks() {
            var decoder = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 8, 0), new LumaDecoder(WIDTH, HEIGHT, 8, 0));
            var frames = new Frames();
            decoder.decode(packet(0, plane(1), plane(2)), frames);
            decoder.decode(packet(1, plane(3), null), frames);
            var corrupt = plane(4);
            corrupt[0] = (byte) 0xEE;
            decoder.decode(packet(2, plane(5), corrupt), frames);
            decoder.decode(packet(3, plane(6), plane(7)), frames);
            assertArrayEquals(plane(2), frames.alpha.get(0));
            assertArrayEquals(opaque(), frames.alpha.get(1));
            assertArrayEquals(opaque(), frames.alpha.get(2));
            assertArrayEquals(plane(7), frames.alpha.get(3));
        }

        @Test
        @DisplayName("an alpha frame the alpha decoder does not support or refuses over the limits shows an opaque frame")
        void rejectedAlpha() {
            var decoder = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 8, 0), new LumaDecoder(WIDTH, HEIGHT, 8, 0));
            var frames = new Frames();
            var unsupported = plane(4);
            unsupported[0] = (byte) 0xEF;
            var huge = plane(5);
            huge[0] = (byte) 0xED;
            decoder.decode(packet(0, plane(1), unsupported), frames);
            decoder.decode(packet(1, plane(2), huge), frames);
            decoder.decode(packet(2, plane(3), plane(6)), frames);
            assertArrayEquals(opaque(), frames.alpha.get(0));
            assertArrayEquals(opaque(), frames.alpha.get(1));
            assertArrayEquals(plane(6), frames.alpha.get(2));
        }

        @Test
        @DisplayName("the alpha planes of frames the color decoder never shows are discarded, not paired later")
        void staleAlpha() {
            var decoder = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 8, 0), new LumaDecoder(WIDTH, HEIGHT, 8, 0));
            var frames = new Frames();
            var corrupt = plane(1);
            corrupt[0] = (byte) 0xEE;
            for (var index = 0; index < 40; index++) {
                var pts = index;
                assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(pts, corrupt, plane(pts)),
                        frame -> {
                        }));
            }
            decoder.decode(packet(40, plane(2), null), frames);
            decoder.decode(packet(39, plane(3), null), frames);
            assertArrayEquals(opaque(), frames.alpha.get(0));
            assertArrayEquals(opaque(), frames.alpha.get(1), "the alpha plane of 39 was discarded at 40");
        }

        @Test
        @DisplayName("an alpha frame of another size is ignored")
        void otherSize() {
            var decoder = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 8, 0), new LumaDecoder(2, 2, 8, 0));
            var frames = new Frames();
            decoder.decode(packet(0, plane(1), plane(2)), frames);
            assertArrayEquals(opaque(), frames.alpha.getFirst());
        }

        @Test
        @DisplayName("frames a decoder delays are paired by timestamp, also when the alpha decoder delays less")
        void delayed() {
            var decoder = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 8, 2), new LumaDecoder(WIDTH, HEIGHT, 8, 1));
            var frames = new Frames();
            for (var index = 0; index < 6; index++) {
                decoder.decode(packet(index, plane(index), plane(50 + index)), frames);
            }
            assertEquals(4, frames.pts.size());
            decoder.flush(frames);
            assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L), frames.pts);
            for (var index = 0; index < 6; index++) {
                assertArrayEquals(plane(50 + index), frames.alpha.get(index), "frame " + index);
            }
        }

        @Test
        @DisplayName("a corrupt color packet fails as the codec decoder does")
        void corruptColor() {
            var decoder = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 8, 0), new LumaDecoder(WIDTH, HEIGHT, 8, 0));
            var corrupt = plane(1);
            corrupt[0] = (byte) 0xEE;
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(0, corrupt, plane(2)), frame -> {
            }));
            var frames = new Frames();
            decoder.decode(packet(1, plane(3), plane(4)), frames);
            assertArrayEquals(plane(4), frames.alpha.getFirst());
        }

        @Test
        @DisplayName("reuses the wrapped picture of a recycled decoder picture")
        void reusesPictures() {
            var decoder = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 8, 0), new LumaDecoder(WIDTH, HEIGHT, 8, 0));
            var frames = new Frames();
            for (var index = 0; index < 8; index++) {
                decoder.decode(packet(index, plane(index), index % 2 == 0 ? plane(index + 9) : null), frames);
            }
            assertSame(frames.pictures.get(2), frames.pictures.get(6));
            assertArrayEquals(plane(6 + 9), frames.alpha.get(6));
        }

        @Test
        @DisplayName("closes both decoders")
        void closes() {
            var color = new LumaDecoder(WIDTH, HEIGHT, 8, 0);
            var alpha = new LumaDecoder(WIDTH, HEIGHT, 8, 0);
            new AlphaVideoDecoder(color, alpha).close();
            assertTrue(color.closed);
            assertTrue(alpha.closed);
        }
    }

    @Nested
    @DisplayName("bit depths")
    class Depths {
        private static byte[] deep(int... samples) {
            var data = new byte[samples.length * 2];
            for (var index = 0; index < samples.length; index++) {
                data[2 * index] = (byte) (samples[index] >> 8);
                data[2 * index + 1] = (byte) samples[index];
            }
            return data;
        }

        @Test
        @DisplayName("8-bit alpha on a 10-bit frame is rescaled to 10 bits, rounding to nearest")
        void eightOnTen() {
            var decoder = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 10, 0), new LumaDecoder(WIDTH, HEIGHT, 8, 0));
            var frames = new Frames();
            var alpha = new byte[]{0, 1, (byte) 127, (byte) 128, (byte) 200, (byte) 254, (byte) 255, 64};
            decoder.decode(packet(0, deep(0, 1, 2, 3, 4, 5, 6, 7), alpha), frames);
            var expected = new short[alpha.length];
            for (var index = 0; index < alpha.length; index++) {
                expected[index] = (short) Math.round((alpha[index] & 0xFF) * 1023 / 255.0);
            }
            assertArrayEquals(expected, frames.deepAlpha.getFirst());
            assertInstanceOf(Yuv16Picture.class, frames.pictures.getFirst());
        }

        @Test
        @DisplayName("10-bit alpha on an 8-bit frame and on a 12-bit frame is rescaled")
        void tenOnOthers() {
            var samples = new int[]{0, 1, 2, 511, 512, 1021, 1022, 1023};
            var narrow = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 8, 0), new LumaDecoder(WIDTH, HEIGHT, 10, 0));
            var frames = new Frames();
            narrow.decode(packet(0, plane(1), deep(samples)), frames);
            var wide = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 12, 0), new LumaDecoder(WIDTH, HEIGHT, 10, 0));
            wide.decode(packet(0, deep(0, 0, 0, 0, 0, 0, 0, 0), deep(samples)), frames);
            for (var index = 0; index < samples.length; index++) {
                assertEquals(Math.round(samples[index] * 255 / 1023.0), frames.alpha.getFirst()[index] & 0xFF);
                assertEquals(Math.round(samples[index] * 4095 / 1023.0), frames.deepAlpha.getFirst()[index]);
            }
        }

        @Test
        @DisplayName("10-bit alpha on a 10-bit frame is passed unchanged, and an opaque 10-bit frame is all 1023")
        void same() {
            var decoder = new AlphaVideoDecoder(new LumaDecoder(WIDTH, HEIGHT, 10, 0), new LumaDecoder(WIDTH, HEIGHT, 10, 0));
            var frames = new Frames();
            var samples = new int[]{0, 5, 9, 300, 600, 900, 1000, 1023};
            decoder.decode(packet(0, deep(0, 0, 0, 0, 0, 0, 0, 0), deep(samples)), frames);
            decoder.decode(packet(1, deep(0, 0, 0, 0, 0, 0, 0, 0), null), frames);
            for (var index = 0; index < samples.length; index++) {
                assertEquals(samples[index], frames.deepAlpha.get(0)[index]);
                assertEquals(1023, frames.deepAlpha.get(1)[index]);
            }
        }
    }

    @Test
    @DisplayName("a frame of another picture type is passed on unchanged")
    void otherPictures() {
        var rgba = RgbaPicture.allocate(WIDTH, HEIGHT, false);
        VideoDecoder color = new VideoDecoder() {
            @Override
            public void decode(Packet packet, Consumer<VideoFrame> output) {
                output.accept(new VideoFrame(rgba, packet.pts(), 1, true));
            }

            @Override
            public void flush(Consumer<VideoFrame> output) {
            }
        };
        var decoder = new AlphaVideoDecoder(color, new LumaDecoder(WIDTH, HEIGHT, 8, 0));
        var frames = new Frames();
        decoder.decode(packet(0, plane(1), plane(2)), frames);
        assertSame(rgba, frames.pictures.getFirst());
        assertFalse(frames.pictures.getFirst().hasAlpha());
    }
}
