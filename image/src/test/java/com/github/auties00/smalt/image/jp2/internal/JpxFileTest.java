package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.ImageDecoderAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.box;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.cdef;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.colr;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.concat;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.flst;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.ftyp;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.ihdr;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.signature;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the JPX file format of ISO/IEC 15444-2 annex M on files assembled box by box: codestreams in fragment tables,
 * several codestreams and compositing layers with their header boxes, opacity boxes, codestream registration, cross
 * references, and the composition rendered as an animation.
 */
@DisplayName("JPX files")
class JpxFileTest {
    /** Codes planes losslessly into a Part 1 codestream. */
    static byte[] codestream(int width, int height, int precision, long[]... planes) {
        var writer = new Part2Writer(width, height, planes.length, precision, false);
        writer.rsiz = 0;
        writer.levels = 2;
        var shifts = new long[planes.length];
        Arrays.fill(shifts, 1L << (precision - 1));
        return writer.write(Jp2Part2Test.forward(writer, planes, shifts, Part2Forward.Tree.dyadic(2), null));
    }

    static long[] solid(int count, long value) {
        var plane = new long[count];
        Arrays.fill(plane, value);
        return plane;
    }

    static Jp2Image channels(byte[] file, int layer) {
        return Jp2Decoder.decodeChannels(MediaSource.of(file), Jp2DecoderOptions.builder().compositingLayer(layer)
                .build());
    }

    static byte[] header(int width, int height, int components, int space) {
        return box("jp2h", ihdr(width, height, components, 8), colr(space));
    }

    @Nested
    @DisplayName("fragment tables")
    class Fragments {
        @Test
        @DisplayName("a codestream split into fragments in media data boxes, out of order, decodes like the whole")
        void fragmented() {
            var random = new Random(1);
            var planes = new long[][]{Jp2Part2Test.smooth(random, 33, 27, 0, 255),
                    Jp2Part2Test.smooth(random, 33, 27, 0, 255), Jp2Part2Test.smooth(random, 33, 27, 0, 255)};
            var stream = codestream(33, 27, 8, planes);
            var a = Arrays.copyOfRange(stream, 0, 100);
            var b = Arrays.copyOfRange(stream, 100, 300);
            var c = Arrays.copyOfRange(stream, 300, stream.length);
            var layout = new JpxBuilder.Layout();
            layout.add(signature()).add(ftyp()).add(header(33, 27, 3, 16));
            var placeholder = box("ftbl", flst(new long[]{0, 0, 0}, new long[]{0, 0, 0}, new long[]{0, 0, 0}));
            layout.add(placeholder);
            layout.media(b);
            layout.media(concat(a, c));
            var offsets = layout.offsets();
            layout.boxes.set(3, box("ftbl", flst(new long[]{offsets[1], a.length, 0}, new long[]{offsets[0], b.length, 0},
                    new long[]{offsets[1] + a.length, c.length, 0})));
            var file = layout.build();
            var image = channels(file, 0);
            for (var i = 0; i < 3; i++) {
                assertArrayEquals(Arrays.stream(planes[i]).mapToInt(v -> (int) v).toArray(),
                        image.channels().get(i).samples(), "channel " + i);
            }
            assertEquals(Jp2ColorSpace.SRGB, image.colorSpace());
        }

        @Test
        @DisplayName("a fragment in another file is unsupported and one past the end of the file is malformed")
        void invalid() {
            var stream = codestream(8, 8, 8, solid(64, 9));
            var external = concat(signature(), ftyp(), header(8, 8, 1, 17),
                    box("dtbl", new byte[]{0, 1}, box("url ", new byte[]{0, 0, 0, 0}, "file:other.jpx\0".getBytes())),
                    box("ftbl", flst(new long[]{0, stream.length, 1})));
            assertThrows(MediaException.Unsupported.class, () -> channels(external, 0));
            var outside = concat(signature(), ftyp(), header(8, 8, 1, 17),
                    box("ftbl", flst(new long[]{1L << 40, stream.length, 0})));
            assertThrows(MediaException.Malformed.class, () -> channels(outside, 0));
            // an offset whose sum with the length overflows a signed 64-bit value
            var overflowing = concat(signature(), ftyp(), header(8, 8, 1, 17),
                    box("ftbl", flst(new long[]{Long.MAX_VALUE - 4, stream.length, 0})));
            var failure = assertThrows(MediaException.Malformed.class, () -> channels(overflowing, 0));
            assertTrue(failure.getMessage().contains("fragment outside the file"), failure.getMessage());
            var truncated = concat(signature(), ftyp(), header(8, 8, 1, 17),
                    box("ftbl", box("flst", new byte[]{0, 2, 0, 0, 0, 0})));
            assertThrows(MediaException.Malformed.class, () -> channels(truncated, 0));
            var empty = concat(signature(), ftyp(), header(8, 8, 1, 17), box("ftbl", box("free")));
            assertThrows(MediaException.Malformed.class, () -> channels(empty, 0));
        }
    }

    @Nested
    @DisplayName("several codestreams and compositing layers")
    class Layers {
        @Test
        @DisplayName("each codestream is a layer without layer headers, and pages decode each")
        void codestreamLayers() {
            var first = codestream(16, 12, 8, solid(192, 10), solid(192, 20), solid(192, 30));
            var second = codestream(9, 7, 8, solid(63, 200));
            var file = concat(signature(), ftyp(), header(16, 12, 3, 16), box("jp2c", first),
                    box("jpch", ihdr(9, 7, 1, 8)), box("jpch", ihdr(9, 7, 1, 8)), box("jp2c", second));
            assertEquals(3, channels(file, 0).channels().size());
            var layer = channels(file, 1);
            assertEquals(9, layer.width());
            assertEquals(200, layer.channels().getFirst().sample(3, 3));
            var info = Jp2Decoder.probe(MediaSource.of(file));
            assertEquals(2, info.frameCount());
            try (var pages = Jp2Decoder.openPages(MediaSource.of(file))) {
                assertEquals(2, pages.pageCount());
                assertEquals(9, pages.probe(1).width());
                var page = (RgbaPicture) pages.read(1).picture();
                assertEquals(9, page.width());
                assertEquals((byte) 200, page.pixels()[0]);
                var front = (RgbaPicture) pages.read(0).picture();
                assertEquals((byte) 30, front.pixels()[2]);
            }
            assertThrows(IndexOutOfBoundsException.class, () -> channels(file, 2));
            // the page flow under every I/O behavior of a source
            ImageDecoderAsserts.of("jp2/pages.jpx", file).noDump().run();
        }

        @Test
        @DisplayName("layer header boxes override the JP2 header's color, with precedence among color groups")
        void colors() {
            var stream = codestream(8, 8, 8, solid(64, 50), solid(64, 60), solid(64, 70));
            var file = concat(signature(), ftyp(), header(8, 8, 3, 16), box("jp2c", stream),
                    box("jplh", box("cgrp", colr(17, 1, null), colr(16, 5, null), JpxBuilder.colrUnknown(9))),
                    box("jplh"));
            assertEquals(Jp2ColorSpace.SRGB, channels(file, 0).colorSpace());
            // the second layer has no color group: the JP2 header's specification applies
            var layered = concat(signature(), ftyp(), header(8, 8, 3, 16), box("jp2c", stream), box("jp2c", stream),
                    box("jplh", box("cgrp", colr(17))), box("jplh"));
            assertEquals(Jp2ColorSpace.GRAYSCALE, channels(layered, 0).colorSpace());
            assertEquals(Jp2ColorSpace.SRGB, channels(layered, 1).colorSpace());
        }

        @Test
        @DisplayName("an opacity box makes the last channel opacity, or adds a chroma-key opacity channel")
        void opacity() {
            var random = new Random(3);
            var red = Jp2Part2Test.image(random, 64, 0, 255);
            red[5] = 7;
            var green = Jp2Part2Test.image(random, 64, 0, 255);
            green[5] = 8;
            var blue = Jp2Part2Test.image(random, 64, 0, 255);
            blue[5] = 9;
            var stream = codestream(8, 8, 8, red, green, blue, solid(64, 128));
            var alpha = concat(signature(), ftyp(), header(8, 8, 4, 16), box("jp2c", stream),
                    box("jplh", JpxBuilder.opct(1)));
            var channels = channels(alpha, 0).channels();
            assertEquals(Jp2ChannelType.PREMULTIPLIED_OPACITY, channels.get(3).type());
            assertEquals(3, channels.get(2).association());
            var rgb = codestream(8, 8, 8, red, green, blue);
            var keyed = concat(signature(), ftyp(), header(8, 8, 3, 16), box("jp2c", rgb),
                    box("jplh", JpxBuilder.opctKey(new byte[]{7, 8, 9}, 3)));
            var image = channels(keyed, 0);
            assertEquals(4, image.channels().size());
            var mask = image.channels().get(3);
            assertEquals(Jp2ChannelType.OPACITY, mask.type());
            assertEquals(0, mask.sample(5, 0));
            for (var i = 0; i < 64; i++) {
                var keyedSample = red[i] == 7 && green[i] == 8 && blue[i] == 9;
                assertEquals(keyedSample ? 0 : 1, mask.sample(i % 8, i / 8), "sample " + i);
            }
            var picture = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(keyed)).picture();
            assertEquals(0, picture.pixels()[5 * 4 + 3]);
            assertTrue(picture.hasAlpha());
        }

        @Test
        @DisplayName("codestream registration combines codestreams, scaling an opacity codestream of half size")
        void registration() {
            var random = new Random(4);
            var rgb = codestream(16, 10, 8, Jp2Part2Test.smooth(random, 16, 10, 0, 255),
                    Jp2Part2Test.smooth(random, 16, 10, 0, 255), Jp2Part2Test.smooth(random, 16, 10, 0, 255));
            var alphaPlane = Jp2Part2Test.image(random, 40, 0, 255);
            var alpha = codestream(8, 5, 8, alphaPlane);
            var file = concat(signature(), ftyp(), header(16, 10, 3, 16), box("jp2c", rgb), box("jp2c", alpha),
                    box("jplh", JpxBuilder.creg(4, 4, new int[]{0, 4, 4, 0, 0}, new int[]{1, 8, 8, 0, 0}),
                            cdef(new int[]{0, 0, 1}, new int[]{1, 0, 2}, new int[]{2, 0, 3}, new int[]{3, 1, 0})));
            var image = channels(file, 0);
            assertEquals(4, image.channels().size());
            var opacity = image.channels().get(3);
            assertEquals(2, opacity.subsamplingX());
            assertEquals(Jp2ChannelType.OPACITY, opacity.type());
            assertEquals(16, image.width());
            assertEquals(1, Jp2Decoder.probe(MediaSource.of(file)).frameCount());
            var picture = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(file)).picture();
            for (var y = 0; y < 10; y++) {
                for (var x = 0; x < 16; x++) {
                    assertEquals((byte) alphaPlane[(y / 2) * 8 + x / 2], picture.pixels()[(y * 16 + x) * 4 + 3]);
                }
            }
        }

        @Test
        @DisplayName("a fractional registration resamples onto the layer grid")
        void fractional() {
            var plane = new long[12 * 6];
            for (var i = 0; i < plane.length; i++) {
                plane[i] = i;
            }
            var base = codestream(12, 6, 8, plane);
            // codestream 1 on its own grid of 2/3 the spacing, offset by 1/3 of a layer point
            var file = concat(signature(), ftyp(), header(12, 6, 1, 17), box("jp2c", base), box("jp2c", base),
                    box("jplh", JpxBuilder.creg(3, 3, new int[]{0, 3, 3, 0, 0}, new int[]{1, 2, 2, 1, 1})));
            var image = channels(file, 0);
            // codestream 0 covers [0, 12) x [0, 6); codestream 1 covers [1/3, 1/3 + 8) x [1/3, 1/3 + 4), grown to
            // [0, 9) x [0, 5)
            assertEquals(9, image.width());
            assertEquals(5, image.height());
            var second = image.channels().get(1);
            for (var v = 0; v < 5; v++) {
                for (var u = 0; u < 9; u++) {
                    var column = Math.clamp(Math.floorDiv(u * 3 - 1, 2), 0, 11);
                    var row = Math.clamp(Math.floorDiv(v * 3 - 1, 2), 0, 5);
                    assertEquals(plane[row * 12 + column], second.sample(u, v), u + "," + v);
                }
            }
        }

        @Test
        @DisplayName("a cross-reference box stands for the palette it points to")
        void crossReference() {
            var indices = new long[]{0, 1, 2, 1, 0, 2, 2, 2, 0};
            var stream = codestream(3, 3, 8, indices);
            var palette = JpxBuilder.pclr(3, 255, 0, 0, 0, 255, 0, 0, 0, 255);
            var contents = Arrays.copyOfRange(palette, 8, palette.length);
            var layout = new JpxBuilder.Layout();
            layout.add(signature()).add(ftyp()).add(header(3, 3, 3, 16));
            layout.add(box("jpch", JpxBuilder.cref("pclr", flst(new long[]{0, 0, 0})),
                    JpxBuilder.cmap(new int[]{0, 1, 0}, new int[]{0, 1, 1}, new int[]{0, 1, 2})));
            layout.add(box("jp2c", stream));
            layout.media(contents);
            var offset = layout.offsets()[0];
            layout.boxes.set(3, box("jpch", JpxBuilder.cref("pclr", flst(new long[]{offset, contents.length, 0})),
                    JpxBuilder.cmap(new int[]{0, 1, 0}, new int[]{0, 1, 1}, new int[]{0, 1, 2})));
            var image = channels(layout.build(), 0);
            assertEquals(3, image.channels().size());
            assertEquals(255, image.channels().get(0).sample(0, 0));
            assertEquals(255, image.channels().get(1).sample(1, 0));
            assertEquals(255, image.channels().get(2).sample(2, 0));
            assertEquals(0, image.channels().get(2).sample(1, 0));
        }

        @Test
        @DisplayName("palette columns of up to 38 bits, signed or not, map into wide samples")
        void deepPalette() {
            var stream = codestream(2, 2, 8, new long[]{0, 1, 1, 0});
            // two entries of a 38-bit unsigned and a 33-bit signed column, five bytes each; the padding of the
            // first signed value repeats its sign bit, as I.5.3.4 allows
            var palette = box("pclr", new byte[]{0, 2, 2, 37, (byte) (0x80 | 32),
                    0x3F, -1, -1, -1, -1, -1, -1, -1, -1, -5,
                    0x25, 0, 0, 0, 1, 0, -1, -1, -1, -1});
            var file = concat(signature(), ftyp(), box("jp2h", ihdr(2, 2, 1, 8), colr(17), palette,
                    JpxBuilder.cmap(new int[]{0, 1, 0}, new int[]{0, 1, 1})), box("jp2c", stream));
            var image = channels(file, 0);
            var deep = image.channels().get(0);
            var wide = image.channels().get(1);
            assertTrue(deep.wide());
            assertEquals(38, deep.precision());
            assertEquals((1L << 38) - 1, deep.sample(0, 0));
            assertEquals(0x25_0000_0001L, deep.sample(1, 0));
            assertTrue(wide.wide());
            assertEquals(33, wide.precision());
            assertTrue(wide.signed());
            assertEquals(-5, wide.sample(0, 0));
            assertEquals(0xFFFF_FFFFL, wide.sample(1, 0));
            assertEquals(-5, wide.sample(1, 1));
        }
    }

    @Nested
    @DisplayName("compositions")
    class Compositions {
        private byte[] file(byte[] composition, byte[]... streams) {
            var parts = new java.util.ArrayList<byte[]>();
            parts.add(signature());
            parts.add(ftyp());
            parts.add(header(4, 4, 3, 16));
            for (var stream : streams) {
                parts.add(box("jp2c", stream));
            }
            parts.add(composition);
            return concat(parts.toArray(byte[][]::new));
        }

        private int[] pixel(RgbaPicture picture, int x, int y) {
            var at = y * picture.stride() + 4 * x;
            var pixels = picture.pixels();
            return new int[]{pixels[at] & 0xFF, pixels[at + 1] & 0xFF, pixels[at + 2] & 0xFF, pixels[at + 3] & 0xFF};
        }

        @Test
        @DisplayName("instructions composite, persist, reuse layers and time frames")
        void animation() {
            var red = codestream(4, 4, 8, solid(16, 255), solid(16, 0), solid(16, 0));
            var green = codestream(2, 2, 8, solid(4, 0), solid(4, 255), solid(4, 0));
            var blue = codestream(2, 2, 8, solid(4, 0), solid(4, 0), solid(4, 255));
            // 1: red at 0,0, life 0 (frame composition); 2: green at 1,1 -> frame 1 (10 ticks)
            // 3: blue at 2,2 without persisting -> frame 2 (5 ticks), reused 1 instruction later
            // 4: blue (reused) at 0,2 -> frame 3: blue of frame 2 is gone
            var composition = box("comp", JpxBuilder.copt(5, 4, 2), JpxBuilder.inst(9, 0, 100,
                    JpxBuilder.Instruction.at(0, 0, true, 0, 0),
                    JpxBuilder.Instruction.at(1, 1, true, 10, 0),
                    JpxBuilder.Instruction.at(2, 2, false, 5, 1),
                    JpxBuilder.Instruction.at(0, 2, true, 7, 0)));
            var data = file(composition, red, green, blue);
            try (var animation = Jp2Decoder.openAnimation(MediaSource.of(data))) {
                assertEquals(5, animation.width());
                assertEquals(4, animation.height());
                assertEquals(3, animation.loopCount());
                assertEquals(3, animation.frameCount());
                var first = animation.read();
                assertEquals(0, first.timestampMicros());
                assertEquals(1_000_000, first.durationMicros());
                var picture = (RgbaPicture) first.picture();
                assertArrayEquals(new int[]{255, 0, 0, 255}, pixel(picture, 0, 0));
                assertArrayEquals(new int[]{0, 255, 0, 255}, pixel(picture, 2, 2));
                assertArrayEquals(new int[]{0, 0, 0, 0}, pixel(picture, 4, 0));
                var second = animation.read();
                assertEquals(1_000_000, second.timestampMicros());
                assertEquals(500_000, second.durationMicros());
                assertArrayEquals(new int[]{0, 0, 255, 255}, pixel((RgbaPicture) second.picture(), 3, 3));
                var third = animation.read();
                var last = (RgbaPicture) third.picture();
                assertArrayEquals(new int[]{255, 0, 0, 255}, pixel(last, 3, 3));
                assertArrayEquals(new int[]{0, 0, 255, 255}, pixel(last, 0, 2));
                assertArrayEquals(new int[]{0, 255, 0, 255}, pixel(last, 2, 1));
                assertNull(animation.read());
            }
            var info = Jp2Decoder.probe(MediaSource.of(data));
            assertTrue(info.animated());
            assertEquals(3, info.frameCount());
            var still = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(data)).picture();
            assertArrayEquals(new int[]{0, 255, 0, 255}, pixel(still, 2, 2));
        }

        @Test
        @DisplayName("an instruction crops, rotates and scales its layer")
        void geometry() {
            var plane = new long[16];
            for (var i = 0; i < 16; i++) {
                plane[i] = i * 10;
            }
            var stream = codestream(4, 4, 8, plane, plane, plane);
            // crop the 2x2 block at (1, 1), rotate 90 degrees clockwise, scale to 4x4, place at (1, 0)
            var instruction = new JpxBuilder.Instruction(1, 0, 4, 4, true, 1, 0, 1, 1, 2, 2, 2);
            var data = file(box("comp", JpxBuilder.copt(5, 4, 0), JpxBuilder.inst(0x6B, 0, 1, instruction)),
                    stream);
            try (var animation = Jp2Decoder.openAnimation(MediaSource.of(data))) {
                var picture = (RgbaPicture) animation.read().picture();
                // the crop is {50, 60; 90, 100}; rotated clockwise it is {90, 50; 100, 60}, each sample doubled
                var expected = new int[][]{{90, 90, 50, 50}, {90, 90, 50, 50}, {100, 100, 60, 60},
                        {100, 100, 60, 60}};
                for (var y = 0; y < 4; y++) {
                    for (var x = 0; x < 4; x++) {
                        assertEquals(expected[y][x], pixel(picture, x + 1, y)[0], x + "," + y);
                    }
                }
                assertEquals(0, pixel(picture, 0, 0)[3]);
            }
        }

        @Test
        @DisplayName("a set repeating indefinitely ends when no layer is left, and a file without composition is one frame")
        void ending() {
            var a = codestream(2, 2, 8, solid(4, 1), solid(4, 2), solid(4, 3));
            var data = file(box("comp", JpxBuilder.copt(2, 2, 255), JpxBuilder.inst(9, 65535, 1,
                    JpxBuilder.Instruction.at(0, 0, true, 1, 0))), a, a, a);
            try (var animation = Jp2Decoder.openAnimation(MediaSource.of(data))) {
                assertEquals(0, animation.loopCount());
                assertEquals(3, animation.frameCount());
                var frames = 0;
                while (animation.read() != null) {
                    frames++;
                }
                assertEquals(3, frames);
            }
            var plain = concat(signature(), ftyp(), header(2, 2, 3, 16), box("jp2c", a));
            try (var animation = Jp2Decoder.openAnimation(MediaSource.of(plain))) {
                assertEquals(1, animation.frameCount());
                assertFalse(animation.read() == null);
                assertNull(animation.read());
            }
            assertFalse(Jp2Decoder.probe(MediaSource.of(plain)).animated());
        }

        @Test
        @DisplayName("a composition that shows no layer decodes, probes and animates as the first compositing layer")
        void empty() {
            var a = codestream(3, 2, 8, solid(6, 40), solid(6, 50), solid(6, 60));
            var b = codestream(3, 2, 8, solid(6, 90), solid(6, 90), solid(6, 90));
            // an instruction set of no instructions (Ityp 0), and one whose only instruction neither persists nor
            // lives (M.5.3.2.1: no action)
            var none = box("comp", JpxBuilder.copt(7, 5, 0), JpxBuilder.inst(0, 0, 0));
            var skipped = box("comp", JpxBuilder.copt(7, 5, 0), JpxBuilder.inst(8, 0, 1,
                    JpxBuilder.Instruction.at(0, 0, false, 0, 0)));
            for (var composition : new byte[][]{none, skipped}) {
                var data = file(composition, a, b);
                var info = Jp2Decoder.probe(MediaSource.of(data));
                assertFalse(info.animated());
                assertEquals(3, info.width());
                assertEquals(2, info.frameCount());
                var still = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(data)).picture();
                assertEquals(3, still.width());
                assertArrayEquals(new int[]{40, 50, 60, 255}, pixel(still, 2, 1));
                try (var animation = Jp2Decoder.openAnimation(MediaSource.of(data))) {
                    assertEquals(1, animation.frameCount());
                    assertEquals(3, animation.width());
                    var frame = (RgbaPicture) animation.read().picture();
                    assertArrayEquals(new int[]{40, 50, 60, 255}, pixel(frame, 0, 0));
                    assertNull(animation.read());
                }
            }
        }

        @Test
        @DisplayName("a layer reused after an instruction that skips it is decoded once and rendered again")
        void skippedReuse() {
            var red = codestream(2, 2, 8, solid(4, 255), solid(4, 0), solid(4, 0));
            var green = codestream(2, 2, 8, solid(4, 0), solid(4, 255), solid(4, 0));
            // 1: red skipped (no persistence, no life) but reserved for instruction 2; 2: red at 2,0 -> frame 1;
            // 3: green, the next unused layer, at 0,0 -> frame 2
            var composition = box("comp", JpxBuilder.copt(4, 2, 0), JpxBuilder.inst(9, 0, 1,
                    JpxBuilder.Instruction.at(0, 0, false, 0, 1),
                    JpxBuilder.Instruction.at(2, 0, true, 1, 0),
                    JpxBuilder.Instruction.at(0, 0, true, 1, 0)));
            var data = file(composition, red, green);
            try (var animation = Jp2Decoder.openAnimation(MediaSource.of(data))) {
                assertEquals(2, animation.frameCount());
                var first = (RgbaPicture) animation.read().picture();
                assertArrayEquals(new int[]{255, 0, 0, 255}, pixel(first, 2, 0));
                assertArrayEquals(new int[]{0, 0, 0, 0}, pixel(first, 0, 0));
                var second = (RgbaPicture) animation.read().picture();
                assertArrayEquals(new int[]{0, 255, 0, 255}, pixel(second, 1, 1));
                assertArrayEquals(new int[]{255, 0, 0, 255}, pixel(second, 3, 1));
                assertNull(animation.read());
            }
        }

        @Test
        @DisplayName("frames composite layers with opacity over the render area")
        void blending() {
            var opaque = codestream(2, 2, 8, solid(4, 200), solid(4, 100), solid(4, 0));
            var translucent = codestream(2, 2, 8, solid(4, 0), solid(4, 0), solid(4, 255), solid(4, 128));
            var parts = concat(signature(), ftyp(), header(2, 2, 3, 16), box("jp2c", opaque), box("jp2c", translucent),
                    box("jplh"), box("jplh", JpxBuilder.opct(0)),
                    box("comp", JpxBuilder.copt(2, 2, 0), JpxBuilder.inst(9, 0, 1,
                            JpxBuilder.Instruction.at(0, 0, true, 0, 0), JpxBuilder.Instruction.at(0, 0, true, 1, 0))));
            var picture = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(parts), ImageDecoderOptions.defaults())
                    .picture();
            var alpha = 128 / 255.0;
            var expected = new int[]{(int) Math.round(200 * (1 - alpha)), (int) Math.round(100 * (1 - alpha)),
                    (int) Math.round(255 * alpha), 255};
            var actual = pixel(picture, 1, 1);
            for (var c = 0; c < 4; c++) {
                assertEquals(expected[c], actual[c], 1, "channel " + c);
            }
        }
    }

    @Nested
    @DisplayName("hostile files")
    class Hostile {
        @Test
        @DisplayName("an indefinitely repeating set that reuses its layer ends instead of looping forever")
        void reuse() {
            var a = codestream(2, 2, 8, solid(4, 1), solid(4, 2), solid(4, 3));
            var data = concat(signature(), ftyp(), header(2, 2, 3, 16), box("jp2c", a),
                    box("comp", JpxBuilder.copt(2, 2, 255), JpxBuilder.inst(9, 65535, 1,
                            JpxBuilder.Instruction.at(0, 0, true, 1, 1))));
            try (var animation = Jp2Decoder.openAnimation(MediaSource.of(data))) {
                var frames = 0;
                while (animation.read() != null) {
                    frames++;
                }
                assertEquals(animation.frameCount(), frames);
                assertTrue(frames >= 1 && frames <= 2, "frames " + frames);
            }
        }

        @Test
        @DisplayName("a render area beyond the limits and deeply nested codestream boxes are rejected")
        void limits() {
            var a = codestream(2, 2, 8, solid(4, 1), solid(4, 2), solid(4, 3));
            var huge = concat(signature(), ftyp(), header(2, 2, 3, 16), box("jp2c", a),
                    box("comp", JpxBuilder.copt(1L << 31, 1L << 30, 0), JpxBuilder.inst(9, 0, 1,
                            JpxBuilder.Instruction.at(0, 0, true, 1, 0))));
            assertThrows(MediaException.LimitExceeded.class, () -> Jp2Decoder.openAnimation(MediaSource.of(huge)));
            assertThrows(MediaException.LimitExceeded.class, () -> Jp2Decoder.probe(MediaSource.of(huge)));
            // probing reads the render area without allocating it
            var large = concat(signature(), ftyp(), header(2, 2, 3, 16), box("jp2c", a),
                    box("comp", JpxBuilder.copt(60000, 50000, 0), JpxBuilder.inst(9, 0, 1,
                            JpxBuilder.Instruction.at(0, 0, true, 1, 0))));
            var info = Jp2Decoder.probe(MediaSource.of(large));
            assertEquals(60000, info.width());
            assertEquals(50000, info.height());
            assertThrows(MediaException.LimitExceeded.class, () -> Jp2Decoder.decode(MediaSource.of(large)));
            var nested = box("jp2c", a);
            for (var i = 0; i < 40; i++) {
                nested = box("j2cx", nested);
            }
            var deep = concat(signature(), ftyp(), header(2, 2, 3, 16), nested);
            assertThrows(MediaException.Malformed.class, () -> channels(deep, 0));
        }
    }
}
