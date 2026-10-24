package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes uncompressed image items ({@code unci}, ISO/IEC 23001-17) through {@link HeicDecoder}:
 * RGB, RGBA and Y'CbCr items, grids of them, uncompressed alpha items and their color
 * properties. The layouts themselves are covered by core's {@code HeifUncompressedTest}.
 */
@DisplayName("HEIF with uncompressed images")
class HeicUncompressedTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    static byte[] profile(String fourCc) {
        return HeifBuilder.fullBox("uncC", 1, 0, fourCc.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** A planar uncC of components of one depth, aligned to two bytes when deeper than 8 bits. */
    static byte[] planar(int sampling, int depth, int count) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u32(out, 0);
        HeifBuilder.u32(out, count);
        for (var i = 0; i < count; i++) {
            HeifBuilder.u16(out, i);
            out.write(depth - 1);
            out.write(0);
            out.write(depth > 8 ? 2 : 0);
        }
        out.write(sampling);
        out.writeBytes(new byte[3]);
        out.writeBytes(new byte[20]);
        return HeifBuilder.fullBox("uncC", 0, 0, out.toByteArray());
    }

    static byte[] cmpd(int... types) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u32(out, types.length);
        for (var type : types) {
            HeifBuilder.u16(out, type);
        }
        return HeifBuilder.box("cmpd", out.toByteArray());
    }

    static byte[] rgba(Random random, int width, int height) {
        var data = new byte[width * height * 4];
        random.nextBytes(data);
        return data;
    }

    static HeifBuilder unif() {
        return new HeifBuilder().brands("mif1", "mif1", "miaf");
    }

    @Nested
    @DisplayName("still images")
    class Stills {
        @Test
        @DisplayName("an RGB item decodes to identity-matrix 4:4:4 planes holding its samples")
        void rgb() {
            var random = new Random(1);
            var data = new byte[24 * 10 * 3];
            random.nextBytes(data);
            var builder = unif();
            builder.primary(builder.item("unci", data, plain(HeifBuilder.ispe(24, 10)), essential(profile("rgb3"))));
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            var rgba = ImageOps.toRgba(picture);
            for (var i = 0; i < 24 * 10; i++) {
                var at = (i / 24) * rgba.stride() + 4 * (i % 24);
                assertEquals(data[3 * i], rgba.pixels()[at], "red " + i);
                assertEquals(data[3 * i + 1], rgba.pixels()[at + 1], "green " + i);
                assertEquals(data[3 * i + 2], rgba.pixels()[at + 2], "blue " + i);
            }
        }

        @Test
        @DisplayName("an RGBA item carries its own alpha plane, and its color properties keep the identity matrix")
        void rgba() {
            var data = HeicUncompressedTest.rgba(new Random(2), 16, 8);
            var builder = unif();
            builder.primary(builder.item("unci", data, plain(HeifBuilder.ispe(16, 8)), essential(profile("rgba")),
                    plain(HeifBuilder.nclx(1, 13, 6, false))));
            var bytes = builder.build();
            assertTrue(HeicDecoder.probe(MediaSource.of(bytes)).hasAlpha());
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(bytes)).picture());
            assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
            assertEquals(ColorRange.FULL, picture.color().range());
            for (var i = 0; i < 16 * 8; i++) {
                assertEquals(data[4 * i + 3], picture.alpha()[(i / 16) * picture.alphaStride() + i % 16], "alpha " + i);
                assertEquals(data[4 * i + 1], picture.y()[(i / 16) * picture.yStride() + i % 16], "green " + i);
            }
        }

        @Test
        @DisplayName("a 10-bit 4:2:0 item keeps its depth, and reduces to the 8-bit decode")
        void deep() {
            var random = new Random(3);
            var width = 12;
            var height = 6;
            var luma = new int[width * height];
            var cb = new int[6 * 3];
            var cr = new int[6 * 3];
            var out = new ByteArrayOutputStream();
            for (var plane : new int[][]{luma, cb, cr}) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = random.nextInt(1024);
                    out.write(plane[i] >> 8);
                    out.write(plane[i]);
                }
            }
            var builder = unif();
            builder.primary(builder.item("unci", out.toByteArray(), plain(HeifBuilder.ispe(width, height)),
                    essential(planar(2, 10, 3)), essential(cmpd(1, 2, 3)), plain(HeifBuilder.nclx(1, 1, 1, false))));
            var bytes = builder.build();
            var wide = assertInstanceOf(Yuv16Picture.class, HeicDecoder.decode(MediaSource.of(bytes), WIDE).picture());
            assertEquals(10, wide.bitDepth());
            assertEquals(ChromaFormat.YUV420, wide.chromaFormat());
            assertEquals(MatrixCoefficients.BT709, wide.color().matrix());
            assertEquals(ColorRange.LIMITED, wide.color().range());
            for (var i = 0; i < luma.length; i++) {
                assertEquals(luma[i], wide.y()[(i / width) * wide.yStride() + i % width]);
            }
            for (var i = 0; i < cb.length; i++) {
                assertEquals(cb[i], wide.u()[(i / 6) * wide.uvStride() + i % 6]);
                assertEquals(cr[i], wide.v()[(i / 6) * wide.uvStride() + i % 6]);
            }
            var narrow = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(bytes)).picture());
            HeicStructureTest.assertPlanes(HeicReference.Planes.of(ImageOps.toEightBit(wide)),
                    HeicReference.Planes.of(narrow));
        }

        @Test
        @DisplayName("a grid of RGBA tiles carries the alpha of each tile")
        void grid() {
            var random = new Random(4);
            var builder = unif();
            var ids = new int[4];
            var tiles = new byte[4][];
            for (var i = 0; i < 4; i++) {
                tiles[i] = HeicUncompressedTest.rgba(random, 8, 8);
                ids[i] = builder.item("unci", tiles[i], plain(HeifBuilder.ispe(8, 8)), essential(profile("rgba")));
                builder.hidden(ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 14, 12), plain(HeifBuilder.ispe(14, 12)));
            builder.reference("dimg", grid, ids).primary(grid);
            var bytes = builder.build();
            assertTrue(HeicDecoder.probe(MediaSource.of(bytes)).hasAlpha());
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(bytes)).picture());
            assertEquals(14, picture.width());
            for (var y = 0; y < 12; y++) {
                for (var x = 0; x < 14; x++) {
                    var tile = tiles[(y / 8) * 2 + x / 8];
                    var at = 4 * ((y % 8) * 8 + x % 8);
                    assertEquals(tile[at + 3], picture.alpha()[y * picture.alphaStride() + x], "alpha " + x + "," + y);
                    assertEquals(tile[at + 1], picture.y()[y * picture.yStride() + x], "green " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("a grid mixing RGBA and RGB tiles is opaque over the RGB tiles")
        void mixedGrid() {
            var random = new Random(6);
            var builder = unif();
            var ids = new int[4];
            var tiles = new byte[4][];
            for (var i = 0; i < 4; i++) {
                var withAlpha = i == 0 || i == 3;
                tiles[i] = new byte[8 * 8 * (withAlpha ? 4 : 3)];
                random.nextBytes(tiles[i]);
                ids[i] = builder.item("unci", tiles[i], plain(HeifBuilder.ispe(8, 8)),
                        essential(profile(withAlpha ? "rgba" : "rgb3")));
                builder.hidden(ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 16, 16), plain(HeifBuilder.ispe(16, 16)));
            builder.reference("dimg", grid, ids).primary(grid);
            var bytes = builder.build();
            assertTrue(HeicDecoder.probe(MediaSource.of(bytes)).hasAlpha());
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(bytes)).picture());
            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 16; x++) {
                    var index = (y / 8) * 2 + x / 8;
                    var size = tiles[index].length / 64;
                    var at = size * ((y % 8) * 8 + x % 8);
                    var alpha = size == 4 ? tiles[index][at + 3] : (byte) 0xFF;
                    assertEquals(alpha, picture.alpha()[y * picture.alphaStride() + x], "alpha " + x + "," + y);
                    assertEquals(tiles[index][at + 1], picture.y()[y * picture.yStride() + x], "green " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("an uncompressed monochrome alpha item gives the alpha plane of an RGB item")
        void alphaItem() {
            var random = new Random(5);
            var color = new byte[8 * 4 * 3];
            random.nextBytes(color);
            var alpha = new byte[8 * 4];
            random.nextBytes(alpha);
            var builder = unif();
            var image = builder.item("unci", color, plain(HeifBuilder.ispe(8, 4)), essential(profile("rgb3")));
            var aux = builder.item("unci", alpha, plain(HeifBuilder.ispe(8, 4)), essential(planar(0, 8, 1)),
                    essential(cmpd(0)), essential(HeifBuilder.auxC("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha")));
            builder.reference("auxl", aux, image).hidden(aux).primary(image);
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            for (var i = 0; i < alpha.length; i++) {
                assertEquals(alpha[i], picture.alpha()[(i / 8) * picture.alphaStride() + i % 8]);
            }
            builder.reference("prem", image, aux);
            var divided = assertInstanceOf(RgbaPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            for (var i = 0; i < alpha.length; i++) {
                var a = alpha[i] & 0xFF;
                var at = (i / 8) * divided.stride() + 4 * (i % 8);
                assertEquals(a, divided.pixels()[at + 3] & 0xFF);
                for (var c = 0; c < 3; c++) {
                    var stored = color[3 * i + c] & 0xFF;
                    var want = a == 0 ? 0 : a == 255 ? stored : Math.min(255, (stored * 510 + a) / (2 * a));
                    assertEquals(want, divided.pixels()[at + c] & 0xFF, "sample " + c + " of pixel " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("invalid and unsupported items")
    class Invalid {
        @Test
        @DisplayName("an item without ispe or uncC, or with too little data, is malformed")
        void malformed() {
            var noExtent = unif();
            noExtent.primary(noExtent.item("unci", new byte[12], essential(profile("rgb3"))));
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(noExtent.build())));
            var noConfig = unif();
            noConfig.primary(noConfig.item("unci", new byte[12], plain(HeifBuilder.ispe(2, 2))));
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(noConfig.build())));
            var shortData = unif();
            shortData.primary(shortData.item("unci", new byte[11], plain(HeifBuilder.ispe(2, 2)),
                    essential(profile("rgb3"))));
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(shortData.build())));
        }

        @Test
        @DisplayName("a palette item is unsupported and is not mistaken for an image without alpha")
        void unsupported() {
            var builder = unif();
            builder.primary(builder.item("unci", new byte[4], plain(HeifBuilder.ispe(2, 2)), essential(planar(0, 8, 1)),
                    essential(cmpd(10))));
            var bytes = builder.build();
            assertThrows(MediaException.Unsupported.class, () -> HeicDecoder.decode(MediaSource.of(bytes)));
            assertFalse(HeicDecoder.probe(MediaSource.of(bytes)).hasAlpha());
        }
    }
}
