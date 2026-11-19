package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.auties00.smalt.image.avif.AvifStructureTest.alphaPlane;
import static com.github.auties00.smalt.image.avif.AvifStructureTest.encode;
import static com.github.auties00.smalt.image.avif.AvifStructureTest.still;
import static com.github.auties00.smalt.image.avif.AvifStructureTest.tile;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds AVIF files whose primary item is a derived image (identity derivations, overlays and grids
 * of derived images, ISO/IEC 23008-12 clause 6.6.2) around AV1 stills encoded by ffmpeg (libaom),
 * and checks each reconstruction against the same composition done by hand from the inputs decoded
 * on their own.
 */
@DisplayName("AVIF derived images")
class AvifDerivedTest {
    static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    static DecodedImage decode(byte[] data) {
        return AvifDecoder.decode(MediaSource.of(data));
    }

    static Picture standalone(AvifStructureTest.Coded coded, ImageDecoderOptions options,
                              HeifBuilder.Property... extra) {
        var builder = new HeifBuilder();
        builder.primary(still(builder, coded, extra));
        return AvifDecoder.decode(MediaSource.of(builder.build()), options).picture();
    }

    static int hidden(HeifBuilder builder, AvifStructureTest.Coded coded, HeifBuilder.Property... extra) {
        var id = still(builder, coded, extra);
        builder.hidden(id);
        return id;
    }

    static int identity(HeifBuilder builder, int input, HeifBuilder.Property... properties) {
        var id = builder.idatItem("iden", new byte[0], properties);
        builder.reference("dimg", id, input);
        return id;
    }

    @Nested
    @DisplayName("identity derivations")
    class Identity {
        @Test
        @DisplayName("show their input exactly as the input decodes on its own")
        void plainInput() {
            var coded = tile(0, 0, "yuv420p10le");
            var builder = new HeifBuilder();
            builder.primary(identity(builder, hidden(builder, coded), plain(HeifBuilder.ispe(64, 64))));
            var data = builder.build();
            for (var options : List.of(ImageDecoderOptions.defaults(), WIDE)) {
                var actual = AvifDecoder.decode(MediaSource.of(data), options).picture();
                AvifConformanceTest.assertPlanesEqual(AvifReference.Planes.of(standalone(coded, options)),
                        AvifReference.Planes.of(actual), "identity of " + options.highBitDepth());
            }
            var info = AvifDecoder.probe(MediaSource.of(data));
            assertEquals(64, info.width());
            assertFalse(info.hasAlpha());
        }

        @Test
        @DisplayName("apply the transformative properties of their input, and report their own orientation")
        void transformedInput() {
            var coded = encode("crop=96:64:0:0", 96, 64, "yuv420p");
            var builder = new HeifBuilder();
            var input = hidden(builder, coded, essential(HeifBuilder.irot(1)));
            builder.primary(identity(builder, input, plain(HeifBuilder.ispe(64, 96)), essential(HeifBuilder.imir(0))));
            var decoded = decode(builder.build());
            var single = new HeifBuilder();
            single.primary(still(single, coded, essential(HeifBuilder.irot(1))));
            var alone = decode(single.build());
            var upright = ImageOps.toRgba(ImageOps.orient(alone.picture(), alone.orientation()));
            assertEquals(64, decoded.picture().width());
            assertEquals(96, decoded.picture().height());
            assertArrayEquals(packed(upright), packed(ImageOps.toRgba(decoded.picture())));
            var mirrored = new HeifBuilder();
            var probe = mirrored.item("av01", coded.payload(), plain(HeifBuilder.ispe(64, 96)),
                    essential(HeifBuilder.av1C(coded.config())), essential(HeifBuilder.imir(0)));
            mirrored.primary(probe);
            assertEquals(AvifDecoder.probe(MediaSource.of(mirrored.build())).orientation(), decoded.orientation());
        }

        @Test
        @DisplayName("take the EXIF block of their input when they have none")
        void inheritedExif() {
            var builder = new HeifBuilder();
            var input = hidden(builder, tile(0, 0, "yuv420p"));
            var exif = builder.item("Exif", new byte[]{0, 0, 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8});
            builder.reference("cdsc", exif, input);
            builder.primary(identity(builder, input, plain(HeifBuilder.ispe(64, 64))));
            var decoded = decode(builder.build());
            assertArrayEquals(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8}, decoded.exif().orElseThrow());
        }

        @Test
        @DisplayName("reject a missing input, two inputs, a cycle and a chain deeper than eight")
        void malformed() {
            var coded = tile(0, 0, "yuv420p");
            var missing = new HeifBuilder();
            var lone = missing.idatItem("iden", new byte[0], plain(HeifBuilder.ispe(64, 64)));
            missing.reference("dimg", lone, 9).primary(lone);
            assertThrows(MediaException.Malformed.class, () -> decode(missing.build()));

            var two = new HeifBuilder();
            var a = hidden(two, coded);
            var b = hidden(two, coded);
            var both = two.idatItem("iden", new byte[0], plain(HeifBuilder.ispe(64, 64)));
            two.reference("dimg", both, a, b).primary(both);
            assertThrows(MediaException.Malformed.class, () -> decode(two.build()));

            var cycle = new HeifBuilder();
            var first = cycle.idatItem("iden", new byte[0], plain(HeifBuilder.ispe(64, 64)));
            var second = cycle.idatItem("iden", new byte[0], plain(HeifBuilder.ispe(64, 64)));
            cycle.reference("dimg", first, second).reference("dimg", second, first).primary(first);
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                assertThrows(MediaException.Malformed.class, () -> decode(cycle.build()));
                assertThrows(MediaException.Malformed.class, () -> AvifDecoder.probe(MediaSource.of(cycle.build())));
            });

            for (var depth : new int[]{8, 9}) {
                var chain = new HeifBuilder();
                var current = hidden(chain, coded);
                for (var i = 0; i < depth; i++) {
                    current = identity(chain, current, plain(HeifBuilder.ispe(64, 64)));
                    if (i < depth - 1) {
                        chain.hidden(current);
                    }
                }
                chain.primary(current);
                if (depth == 8) {
                    assertEquals(64, decode(chain.build()).picture().width());
                } else {
                    assertThrows(MediaException.Malformed.class, () -> decode(chain.build()));
                }
            }
        }

        @Test
        @DisplayName("reject an input coded with another codec as unsupported")
        void otherCodec() {
            var builder = new HeifBuilder();
            var hevc = builder.item("hvc1", new byte[]{0, 0, 0, 1, 0x40}, plain(HeifBuilder.ispe(64, 64)));
            builder.hidden(hevc).primary(identity(builder, hevc, plain(HeifBuilder.ispe(64, 64))));
            assertThrows(MediaException.Unsupported.class, () -> decode(builder.build()));
            assertThrows(MediaException.Unsupported.class, () -> AvifDecoder.probe(MediaSource.of(builder.build())));
        }
    }

    @Nested
    @DisplayName("image overlays")
    class Overlays {
        @Test
        @DisplayName("place opaque inputs over the fill color, clipped to the canvas, at negative offsets too")
        void opaque() {
            var tiles = List.of(tile(0, 0, "yuv420p"), tile(64, 64, "yuv444p"));
            var fill = new int[]{0x1234, 0x8000, 0xFFFF, 0xC000};
            int[][] offsets = {{-10, -6}, {40, 30}};
            var builder = new HeifBuilder();
            var inputs = new int[]{hidden(builder, tiles.get(0)), hidden(builder, tiles.get(1))};
            var overlay = builder.idatItem("iovl", HeifBuilder.overlay(false, fill, 90, 80, offsets),
                    plain(HeifBuilder.ispe(90, 80)));
            builder.reference("dimg", overlay, inputs).primary(overlay);
            var data = builder.build();
            var info = AvifDecoder.probe(MediaSource.of(data));
            assertEquals(90, info.width());
            assertEquals(80, info.height());
            assertTrue(info.hasAlpha());
            var actual = assertInstanceOf(RgbaPicture.class, decode(data).picture());
            var expected = new byte[4 * 90 * 80];
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (byte) ((fill[i & 3] * 255 + 32767) / 65535);
            }
            for (var t = 0; t < tiles.size(); t++) {
                var rgba = ImageOps.toRgba(standalone(tiles.get(t), ImageDecoderOptions.defaults()));
                for (var y = 0; y < 64; y++) {
                    for (var x = 0; x < 64; x++) {
                        var cx = offsets[t][0] + x;
                        var cy = offsets[t][1] + y;
                        if (cx >= 0 && cy >= 0 && cx < 90 && cy < 80) {
                            System.arraycopy(rgba.pixels(), y * rgba.stride() + 4 * x, expected, 4 * (cy * 90 + cx), 3);
                            expected[4 * (cy * 90 + cx) + 3] = (byte) 255;
                        }
                    }
                }
            }
            assertArrayEquals(expected, packed(actual));
        }

        @Test
        @DisplayName("blend an input with alpha over the inputs before it, in straight alpha")
        void blended() {
            var below = tile(0, 0, "yuv420p");
            var above = tile(64, 0, "yuv420p");
            var alpha = alphaPlane(64, 64);
            var builder = new HeifBuilder();
            var first = hidden(builder, below);
            var second = hidden(builder, above);
            var aux = builder.item("av01", alpha.payload(), plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.av1C(alpha.config())), essential(HeifBuilder.alpha()));
            builder.reference("auxl", aux, second).hidden(aux);
            var overlay = builder.idatItem("iovl", HeifBuilder.overlay(true, new int[]{0, 0, 0, 0}, 64, 64,
                    new int[]{0, 0}, new int[]{0, 0}), plain(HeifBuilder.ispe(64, 64)));
            builder.reference("dimg", overlay, first, second).primary(overlay);
            var actual = packed(assertInstanceOf(RgbaPicture.class, decode(builder.build()).picture()));
            var under = ImageOps.toRgba(standalone(below, ImageDecoderOptions.defaults()));
            var withAlpha = new HeifBuilder();
            var color = still(withAlpha, above);
            var alphaItem = withAlpha.item("av01", alpha.payload(), plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.av1C(alpha.config())), essential(HeifBuilder.alpha()));
            withAlpha.reference("auxl", alphaItem, color).primary(color);
            var over = ImageOps.toRgba(decode(withAlpha.build()).picture());
            for (var i = 0; i < 64 * 64; i++) {
                var a = over.pixels()[4 * i + 3] & 0xFF;
                for (var c = 0; c < 4; c++) {
                    int expected;
                    if (a == 0) {
                        expected = c == 3 ? 255 : under.pixels()[4 * i + c] & 0xFF;
                    } else if (a == 255) {
                        expected = c == 3 ? 255 : over.pixels()[4 * i + c] & 0xFF;
                    } else {
                        var coverage = a * 255 + 255 * (255 - a);
                        expected = c == 3 ? (coverage + 127) / 255
                                : ((over.pixels()[4 * i + c] & 0xFF) * a * 255
                                + (under.pixels()[4 * i + c] & 0xFF) * 255 * (255 - a) + coverage / 2) / coverage;
                    }
                    assertEquals(expected, actual[4 * i + c] & 0xFF, "pixel " + i + " component " + c);
                }
            }
        }

        @Test
        @DisplayName("compose 10-bit inputs at 16 bits with the exact fill when the depth is kept")
        void deep() {
            var coded = tile(0, 0, "yuv420p10le");
            var fill = new int[]{0x1234, 0x5678, 0x9ABC, 0xFFFF};
            var builder = new HeifBuilder();
            var input = hidden(builder, coded);
            var overlay = builder.idatItem("iovl", HeifBuilder.overlay(false, fill, 70, 64, new int[]{6, 0}),
                    plain(HeifBuilder.ispe(70, 64)));
            builder.reference("dimg", overlay, input).primary(overlay);
            var data = builder.build();
            var deep = assertInstanceOf(Rgba16Picture.class, AvifDecoder.decode(MediaSource.of(data), WIDE).picture());
            var rgba = ImageOps.toRgba16(standalone(coded, WIDE));
            for (var y = 0; y < 64; y++) {
                for (var x = 0; x < 70; x++) {
                    for (var c = 0; c < 4; c++) {
                        var expected = x < 6 ? fill[c] : c == 3 ? 0xFFFF : rgba.pixels()[y * rgba.stride() + 4 * (x - 6) + c]
                                & 0xFFFF;
                        assertEquals(expected, deep.pixels()[y * deep.stride() + 4 * x + c] & 0xFFFF);
                    }
                }
            }
            assertInstanceOf(RgbaPicture.class, decode(data).picture());
        }

        @Test
        @DisplayName("reject an overlay without inputs, with a payload of the wrong size or an empty canvas")
        void malformed() {
            var coded = tile(0, 0, "yuv420p");
            var none = new HeifBuilder();
            var empty = none.idatItem("iovl", HeifBuilder.overlay(false, new int[4], 8, 8),
                    plain(HeifBuilder.ispe(8, 8)));
            none.primary(empty);
            assertThrows(MediaException.Malformed.class, () -> decode(none.build()));
            var wrong = new HeifBuilder();
            var input = hidden(wrong, coded);
            var overlay = wrong.idatItem("iovl", HeifBuilder.overlay(false, new int[4], 8, 8, new int[]{0, 0},
                    new int[]{0, 0}), plain(HeifBuilder.ispe(8, 8)));
            wrong.reference("dimg", overlay, input).primary(overlay);
            assertThrows(MediaException.Malformed.class, () -> decode(wrong.build()));
            var zero = new HeifBuilder();
            var zeroInput = hidden(zero, coded);
            var zeroCanvas = zero.idatItem("iovl", HeifBuilder.overlay(false, new int[4], 0, 8, new int[]{0, 0}),
                    plain(HeifBuilder.ispe(8, 8)));
            zero.reference("dimg", zeroCanvas, zeroInput).primary(zeroCanvas);
            assertThrows(MediaException.Malformed.class, () -> decode(zero.build()));
        }

        @Test
        @DisplayName("stop an overlay that decodes its input over and over at four times the pixel limit")
        void budget() {
            var coded = tile(0, 0, "yuv420p");
            var builder = new HeifBuilder();
            var input = hidden(builder, coded);
            var inner = builder.idatItem("iovl", HeifBuilder.overlay(false, new int[4], 64, 64, new int[]{0, 0}),
                    plain(HeifBuilder.ispe(64, 64)));
            builder.reference("dimg", inner, input).hidden(inner);
            var refs = new int[40];
            var offsets = new int[40][];
            for (var i = 0; i < refs.length; i++) {
                refs[i] = identity(builder, inner, plain(HeifBuilder.ispe(64, 64)));
                builder.hidden(refs[i]);
                offsets[i] = new int[]{0, 0};
            }
            var outer = builder.idatItem("iovl", HeifBuilder.overlay(false, new int[4], 64, 64, offsets),
                    plain(HeifBuilder.ispe(64, 64)));
            builder.reference("dimg", outer, refs).primary(outer);
            var limits = MediaLimits.builder().maxPixels(64 * 64 * 16).build();
            assertThrows(MediaException.LimitExceeded.class, () -> AvifDecoder.decode(MediaSource.of(builder.build()),
                    ImageDecoderOptions.builder().limits(limits).build()));
        }
    }

    @Nested
    @DisplayName("grids of derived images")
    class Grids {
        @Test
        @DisplayName("stitch identity derivations of coded tiles exactly as the grid of the tiles themselves")
        void identities() {
            var tiles = List.of(tile(0, 0, "yuv420p"), tile(64, 0, "yuv420p"), tile(0, 64, "yuv420p"),
                    tile(64, 64, "yuv420p"));
            var derived = new HeifBuilder();
            var coded = new HeifBuilder();
            var derivedIds = new int[4];
            var codedIds = new int[4];
            for (var i = 0; i < 4; i++) {
                derivedIds[i] = identity(derived, hidden(derived, tiles.get(i)), plain(HeifBuilder.ispe(64, 64)));
                derived.hidden(derivedIds[i]);
                codedIds[i] = hidden(coded, tiles.get(i));
            }
            for (var pair : List.of(new Object[]{derived, derivedIds}, new Object[]{coded, codedIds})) {
                var builder = (HeifBuilder) pair[0];
                var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 120, 100), plain(HeifBuilder.ispe(120, 100)));
                builder.reference("dimg", grid, (int[]) pair[1]).primary(grid);
            }
            for (var options : List.of(ImageDecoderOptions.defaults(), WIDE)) {
                AvifConformanceTest.assertPlanesEqual(
                        AvifReference.Planes.of(AvifDecoder.decode(MediaSource.of(coded.build()), options).picture()),
                        AvifReference.Planes.of(AvifDecoder.decode(MediaSource.of(derived.build()), options).picture()),
                        "grid of identities");
            }
            assertEquals(120, AvifDecoder.probe(MediaSource.of(derived.build())).width());
        }

        @Test
        @DisplayName("turn packed RGBA when an input is an overlay, converting the planar inputs on their own")
        void packedInput() {
            var tiles = List.of(tile(0, 0, "yuv420p"), tile(64, 0, "yuv420p"));
            var builder = new HeifBuilder();
            var plainInput = identity(builder, hidden(builder, tiles.get(0)), plain(HeifBuilder.ispe(64, 64)));
            builder.hidden(plainInput);
            var overlaid = hidden(builder, tiles.get(1));
            var overlay = builder.idatItem("iovl", HeifBuilder.overlay(false, new int[]{0, 0, 0, 0xFFFF}, 64, 64,
                    new int[]{0, 0}), plain(HeifBuilder.ispe(64, 64)));
            builder.reference("dimg", overlay, overlaid).hidden(overlay);
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, 2, 128, 64), plain(HeifBuilder.ispe(128, 64)));
            builder.reference("dimg", grid, plainInput, overlay).primary(grid);
            var actual = packed(assertInstanceOf(RgbaPicture.class, decode(builder.build()).picture()));
            for (var t = 0; t < 2; t++) {
                var rgba = ImageOps.toRgba(standalone(tiles.get(t), ImageDecoderOptions.defaults()));
                for (var y = 0; y < 64; y++) {
                    for (var x = 0; x < 64; x++) {
                        for (var c = 0; c < 3; c++) {
                            assertEquals(rgba.pixels()[y * rgba.stride() + 4 * x + c],
                                    actual[4 * (y * 128 + 64 * t + x) + c], "tile " + t);
                        }
                        assertEquals((byte) 255, actual[4 * (y * 128 + 64 * t + x) + 3]);
                    }
                }
            }
        }
    }

    /** Copies the pixels of an RGBA picture without row padding. */
    static byte[] packed(RgbaPicture picture) {
        var out = new byte[4 * picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), out, 4 * y * picture.width(), 4 * picture.width());
        }
        return out;
    }

}
