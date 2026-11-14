package com.github.auties00.smalt.image.dds;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the formats DirectXTex cannot decode against the formats that share their texels: legacy {@code DDPF_YUV}
 * mask formats against {@code AYUV}, {@code Y410} and {@code Y416}, the typeless depth formats against their typed
 * twins, the planar formats against {@code V408} with replicated chroma and {@code P208} against {@code YUY2}; the
 * palette and 1-bit formats against their definitions; and the mipmap layout of planar surfaces, the validation of
 * the new headers and their robustness to mutations.
 */
@DisplayName("DDS video, depth and palette formats")
class DdsVideoFormatsTest {
    private static final ImageDecoderOptions NATIVE = ImageDecoderOptions.builder().highBitDepth(true)
            .keepChannels(true).build();

    private static int[] decode(byte[] file) {
        return DdsDirectXTexDifferentialTest.samples(DdsDecoder.decode(MediaSource.of(file), NATIVE).picture());
    }

    private static byte[] surface(byte[] file, int headerBytes) {
        return Arrays.copyOfRange(file, headerBytes, file.length);
    }

    private static byte[] fixture(String name) {
        return DdsDirectXTexDifferentialTest.files().get(name);
    }

    @Nested
    @DisplayName("legacy YUV masks")
    class LegacyYuv {
        @Test
        @DisplayName("decode as AYUV and Y410 decode the same texels")
        void sameAsDxgi() {
            var ayuv = fixture("ayuv");
            var masks = DdsFiles.legacy(13, 7, 1, DdsFiles.DDPF_YUV | DdsFiles.DDPF_ALPHAPIXELS, 0, 32,
                    new long[]{0xFF0000, 0xFF00, 0xFF, 0xFF000000L}, 0, 1, surface(ayuv, 148));
            assertArrayEquals(decode(ayuv), decode(masks));
            var y410 = fixture("y410");
            var tenBit = DdsFiles.legacy(13, 7, 1, DdsFiles.DDPF_YUV | DdsFiles.DDPF_ALPHAPIXELS, 0, 32,
                    new long[]{0xFFC00, 0x3FF, 0x3FF00000, 0xC0000000L}, 0, 1, surface(y410, 148));
            assertArrayEquals(decode(y410), decode(tenBit));
        }

        @Test
        @DisplayName("decode without alpha as opaque")
        void withoutAlpha() {
            var ayuv = fixture("ayuv");
            var masks = DdsFiles.legacy(13, 7, 1, DdsFiles.DDPF_YUV, 0, 32, new long[]{0xFF0000, 0xFF00, 0xFF, 0}, 0,
                    1, surface(ayuv, 148));
            var expected = decode(ayuv);
            for (var index = 3; index < expected.length; index += 4) {
                expected[index] = 255;
            }
            assertArrayEquals(expected, decode(masks));
        }

        @Test
        @DisplayName("convert other widths with the 16-bit coefficients after aligning them to 16 bits")
        void otherWidths() {
            // 9-bit Y, U and V in bits 0-8, 9-17 and 18-26; Y416 holds U, Y, V and alpha words
            var random = new Random(41);
            var count = 6 * 5;
            var legacy = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
            var y416 = ByteBuffer.allocate(count * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (var index = 0; index < count; index++) {
                var y = random.nextInt(512);
                var u = random.nextInt(512);
                var v = random.nextInt(512);
                legacy.putInt(y | u << 9 | v << 18 | random.nextInt(32) << 27);
                y416.putShort((short) (u << 7)).putShort((short) (y << 7)).putShort((short) (v << 7))
                        .putShort((short) 0xFFFF);
            }
            var file = DdsFiles.legacy(6, 5, 1, DdsFiles.DDPF_YUV, 0, 32, new long[]{0x1FF, 0x1FF << 9, 0x1FF << 18, 0},
                    0, 1, legacy.array());
            assertArrayEquals(decode(DdsFiles.dx10(6, 5, 102, y416.array())), decode(file));
        }

        @Test
        @DisplayName("reject masks of different or too narrow widths, missing masks and masks past the texel")
        void rejects() {
            var data = new byte[64];
            assertThrows(MediaException.Unsupported.class, () -> DdsDecoder.decode(MediaSource.of(DdsFiles.legacy(4,
                    4, 1, DdsFiles.DDPF_YUV, 0, 32, new long[]{0xFF, 0x3FF00, 0xFF000000L, 0}, 0, 1, data))));
            assertThrows(MediaException.Unsupported.class, () -> DdsDecoder.decode(MediaSource.of(DdsFiles.legacy(4,
                    4, 1, DdsFiles.DDPF_YUV, 0, 16, new long[]{0x1F, 0x3E0, 0x7C00, 0}, 0, 1, data))));
            assertThrows(MediaException.Malformed.class, () -> DdsDecoder.decode(MediaSource.of(DdsFiles.legacy(4,
                    4, 1, DdsFiles.DDPF_YUV, 0, 32, new long[]{0xFF, 0, 0xFF0000, 0}, 0, 1, data))));
            assertThrows(MediaException.Malformed.class, () -> DdsDecoder.decode(MediaSource.of(DdsFiles.legacy(4,
                    4, 1, DdsFiles.DDPF_YUV, 0, 16, new long[]{0xFF, 0xFF00, 0xFF0000, 0}, 0, 1, data))));
        }
    }

    @Nested
    @DisplayName("depth formats")
    class Depth {
        @ParameterizedTest
        @CsvSource({"d24-unorm-s8-uint,44", "d32-float-s8x24-uint,19"})
        @DisplayName("decode the typeless formats as their typed twins")
        void typeless(String name, int dxgi) {
            var typed = fixture(name);
            var typeless = typed.clone();
            ByteBuffer.wrap(typeless).order(ByteOrder.LITTLE_ENDIAN).putInt(128, dxgi);
            assertArrayEquals(decode(typed), decode(typeless));
        }

        @Test
        @DisplayName("decode depth to red and the stencil to green, from their definitions")
        void definition() {
            // depth 0x800000 of 0xFFFFFF, stencil 51
            var texel = new byte[]{0, 0, (byte) 0x80, 51};
            var floats = decode(DdsFiles.dx10(1, 1, 45, texel));
            assertEquals((float) (0x800000 / 16777215.0), Float.intBitsToFloat(floats[0]));
            assertEquals(0.2f, Float.intBitsToFloat(floats[1]));
            assertEquals(0f, Float.intBitsToFloat(floats[2]));
            assertArrayEquals(new byte[]{0, 51, 0, -1}, RasterTesting.tight(DdsDecoder.decode(MediaSource.of(
                    DdsFiles.dx10(1, 1, 47, texel))).picture()));
            var d16 = DdsDecoder.decode(MediaSource.of(DdsFiles.dx10(1, 1, 55, new byte[]{0x34, 0x12})), NATIVE);
            assertArrayEquals(new int[]{0x1234, 0x1234, 0x1234, 0xFFFF},
                    DdsDirectXTexDifferentialTest.samples(d16.picture()));
            assertFalse(DdsDecoder.probe(MediaSource.of(DdsFiles.dx10(1, 1, 45, texel))).hasAlpha());
        }
    }

    @Nested
    @DisplayName("palette and 1-bit formats")
    class Indexed {
        @Test
        @DisplayName("decode the DXGI palette formats' indices as gray and their alpha")
        void dxgiPalettes() {
            assertArrayEquals(new byte[]{77, 77, 77, -1}, rgba(DdsFiles.dx10(1, 1, 113, new byte[]{77})));
            assertArrayEquals(new byte[]{77, 77, 77, 9}, rgba(DdsFiles.dx10(1, 1, 114, new byte[]{77, 9})));
            // AI44: alpha in the high nibble; IA44: index in the high nibble; 4-bit values scaled by 17
            assertArrayEquals(new byte[]{85, 85, 85, (byte) 170}, rgba(DdsFiles.dx10(1, 1, 111,
                    new byte[]{(byte) 0xA5})));
            assertArrayEquals(new byte[]{(byte) 170, (byte) 170, (byte) 170, 85}, rgba(DdsFiles.dx10(1, 1, 112,
                    new byte[]{(byte) 0xA5})));
        }

        @Test
        @DisplayName("treat a legacy palette whose alpha bytes are all zero as opaque")
        void opaquePalette() {
            var palette = new byte[1024];
            for (var index = 0; index < 256; index++) {
                palette[4 * index] = (byte) index;
                palette[4 * index + 1] = (byte) (255 - index);
                palette[4 * index + 2] = 7;
            }
            var data = new byte[1024 + 2];
            System.arraycopy(palette, 0, data, 0, 1024);
            data[1024] = 3;
            data[1025] = (byte) 200;
            var file = DdsFiles.legacy(2, 1, 1, DdsFiles.DDPF_PALETTEINDEXED8, 0, 8, new long[4], 0, 1, data);
            assertArrayEquals(new byte[]{3, (byte) 252, 7, -1, (byte) 200, 55, 7, -1}, rgba(file));
            assertFalse(DdsDecoder.probe(MediaSource.of(file)).hasAlpha());
            var translucent = file.clone();
            translucent[128 + 4 * 9 + 3] = 1;
            assertTrue(DdsDecoder.probe(MediaSource.of(translucent)).hasAlpha());
            var alpha = DdsFiles.legacy(1, 1, 1, DdsFiles.DDPF_PALETTEINDEXED8 | DdsFiles.DDPF_ALPHAPIXELS, 0, 16,
                    new long[]{0, 0, 0, 0xFF00}, 0, 1, Arrays.copyOf(palette, 1026));
            alpha[alpha.length - 2] = 5;
            alpha[alpha.length - 1] = 66;
            assertArrayEquals(new byte[]{5, (byte) 250, 7, 66}, rgba(alpha));
        }

        @Test
        @DisplayName("reject a truncated palette and a palette of the wrong bit count")
        void rejects() {
            assertThrows(MediaException.Malformed.class, () -> DdsDecoder.decode(MediaSource.of(DdsFiles.legacy(1,
                    1, 1, DdsFiles.DDPF_PALETTEINDEXED8, 0, 8, new long[4], 0, 1, new byte[1000]))));
            assertThrows(MediaException.Malformed.class, () -> DdsDecoder.decode(MediaSource.of(DdsFiles.legacy(1,
                    1, 1, DdsFiles.DDPF_PALETTEINDEXED8, 0, 16, new long[4], 0, 1, new byte[1026]))));
        }

        @Test
        @DisplayName("decode R1_UNORM most significant bit first, rows padded to bytes")
        void oneBit() {
            var file = DdsFiles.dx10(9, 2, 66, new byte[]{(byte) 0b10110000, (byte) 0b10000000, 0x01, 0x7F});
            assertArrayEquals(new byte[]{-1, 0, -1, -1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0, -1, 0}, gray(rgba(file)));
            assertFalse(DdsDecoder.probe(MediaSource.of(file)).hasAlpha());
        }

        private static byte[] gray(byte[] rgba) {
            var out = new byte[rgba.length / 4];
            for (var index = 0; index < out.length; index++) {
                out[index] = rgba[4 * index];
            }
            return out;
        }

        private static byte[] rgba(byte[] file) {
            return RasterTesting.tight(DdsDecoder.decode(MediaSource.of(file)).picture());
        }
    }

    @Nested
    @DisplayName("planar and packed YUV")
    class Planar {
        /**
         * {@return a V408 file of random samples; the U and V planes of {@code chroma} are those of the format being
         * compared, replicated to every texel}
         */
        private static byte[] v408(int width, int height, byte[] luma, byte[] u, byte[] v, int chromaWidth,
                                   int chromaHeight) {
            var data = new byte[width * height * 3];
            var chromaColumns = (width + chromaWidth - 1) / chromaWidth;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var texel = y * width + x;
                    data[texel] = luma[texel];
                    var chroma = (y / chromaHeight) * chromaColumns + x / chromaWidth;
                    data[width * height + texel] = u[chroma];
                    data[2 * width * height + texel] = v[chroma];
                }
            }
            return DdsFiles.dx10(width, height, 132, data);
        }

        @ParameterizedTest
        @CsvSource({"103,2,2,13,7", "103,2,2,14,8", "110,4,1,13,5", "110,4,1,12,3", "130,2,1,13,6",
                "131,1,2,13,7", "131,1,2,6,4"})
        @DisplayName("replicate each chroma sample over the texels it covers")
        void chroma(int dxgi, int chromaWidth, int chromaHeight, int width, int height) {
            var random = new Random(dxgi * 31L + width);
            var columns = (width + chromaWidth - 1) / chromaWidth;
            var rows = (height + chromaHeight - 1) / chromaHeight;
            var luma = DdsFiles.random(width * height, random.nextLong());
            var u = DdsFiles.random(columns * rows, random.nextLong());
            var v = DdsFiles.random(columns * rows, random.nextLong());
            var pitch = ((width + chromaWidth - 1) / chromaWidth) * chromaWidth;
            var out = new ByteArrayOutputStream();
            for (var y = 0; y < height; y++) {
                out.write(luma, y * width, width);
                out.writeBytes(new byte[pitch - width]);
            }
            var interleaved = dxgi != 131;
            if (interleaved) {
                var rowBytes = columns * 2;
                for (var y = 0; y < rows; y++) {
                    for (var x = 0; x < columns; x++) {
                        out.write(u[y * columns + x]);
                        out.write(v[y * columns + x]);
                    }
                    // NV11 rows are half the pitch; NV12 and P208 rows fill it
                    out.writeBytes(new byte[dxgi == 110 ? 0 : pitch - rowBytes]);
                }
                if (dxgi == 110) {
                    out.writeBytes(new byte[pitch * height - rows * rowBytes]);
                }
            } else {
                out.write(u, 0, u.length);
                out.write(v, 0, v.length);
            }
            var file = DdsFiles.dx10(width, height, dxgi, out.toByteArray());
            assertArrayEquals(decode(v408(width, height, luma, u, v, chromaWidth, chromaHeight)), decode(file));
            // one byte less than the surface is truncated
            var truncated = Arrays.copyOf(file, file.length - 1);
            assertThrows(MediaException.Malformed.class, () -> DdsDecoder.decode(MediaSource.of(truncated)));
        }

        @ParameterizedTest
        @CsvSource({"ayuv,100,255", "y416,102,65535"})
        @DisplayName("divide premultiplied alpha out of the converted colors")
        void premultiplied(String name, int dxgi, int max) {
            var straight = fixture(name);
            var premultiplied = DdsFiles.dx10(13, 7, 1, dxgi, 3, 0, 1, 2, 1, surface(straight, 148));
            var colors = decode(straight);
            var divided = decode(premultiplied);
            assertEquals(colors.length, divided.length);
            var changed = 0;
            for (var index = 0; index < colors.length; index += 4) {
                var opacity = colors[index + 3];
                assertEquals(opacity, divided[index + 3]);
                for (var channel = 0; channel < 3; channel++) {
                    var expected = opacity == 0 ? 0
                            : (int) Math.min(max, ((long) colors[index + channel] * max + opacity / 2) / opacity);
                    assertEquals(expected, divided[index + channel], "sample " + (index + channel));
                    changed += expected != colors[index + channel] ? 1 : 0;
                }
            }
            assertTrue(changed > 0);
        }

        @Test
        @DisplayName("decode V408 as AYUV decodes the same samples")
        void v408AsAyuv() {
            var ayuv = fixture("ayuv");
            var texels = surface(ayuv, 148);
            var count = 13 * 7;
            var planes = new byte[count * 3];
            for (var index = 0; index < count; index++) {
                planes[index] = texels[4 * index + 2];
                planes[count + index] = texels[4 * index + 1];
                planes[2 * count + index] = texels[4 * index];
                texels[4 * index + 3] = (byte) 255;
            }
            assertArrayEquals(decode(DdsFiles.dx10(13, 7, 100, texels)), decode(DdsFiles.dx10(13, 7, 132, planes)));
        }

        @Test
        @DisplayName("decode P208 as YUY2 decodes the same samples")
        void p208AsYuy2() {
            var yuy2 = fixture("yuy2");
            var texels = surface(yuy2, 148);
            var pairs = 7;
            var luma = new byte[14 * 7];
            var chroma = new byte[14 * 7];
            for (var y = 0; y < 7; y++) {
                for (var pair = 0; pair < pairs; pair++) {
                    var at = (y * pairs + pair) * 4;
                    luma[y * 14 + 2 * pair] = texels[at];
                    luma[y * 14 + 2 * pair + 1] = texels[at + 2];
                    chroma[y * 14 + 2 * pair] = texels[at + 1];
                    chroma[y * 14 + 2 * pair + 1] = texels[at + 3];
                }
            }
            var data = new byte[luma.length + chroma.length];
            System.arraycopy(luma, 0, data, 0, luma.length);
            System.arraycopy(chroma, 0, data, luma.length, chroma.length);
            assertArrayEquals(decode(yuy2), decode(DdsFiles.dx10(13, 7, 130, data)));
        }

        @Test
        @DisplayName("lay out the mipmap levels of a planar surface one after another")
        void mipmaps() {
            // NV12 levels 14x8, 7x4 and 3x2: pitches 14, 8 and 4 bytes, 12, 6 and 3 rows
            var sizes = new int[]{14 * 12, 8 * 6, 4 * 3};
            var levels = new byte[3][];
            var out = new ByteArrayOutputStream();
            for (var level = 0; level < 3; level++) {
                levels[level] = DdsFiles.random(sizes[level], 70 + level);
                out.writeBytes(levels[level]);
            }
            var file = DdsFiles.dx10(14, 8, 3, 103, 3, 0, 1, 0, 1, out.toByteArray());
            try (var pages = DdsDecoder.openPages(MediaSource.of(file), NATIVE)) {
                assertEquals(3, pages.pageCount());
                var widths = new int[]{14, 7, 3};
                var heights = new int[]{8, 4, 2};
                for (var level = 0; level < 3; level++) {
                    assertArrayEquals(decode(DdsFiles.dx10(widths[level], heights[level], 103, levels[level])),
                            DdsDirectXTexDifferentialTest.samples(pages.read(level).picture()), "level " + level);
                }
            }
        }
    }

    @Test
    @DisplayName("still rejects the opaque 4:2:0 format")
    void opaque() {
        assertThrows(MediaException.Unsupported.class, () -> DdsDecoder.decode(MediaSource.of(
                DdsFiles.dx10(4, 4, 106, new byte[64]))));
    }

    @Test
    @DisplayName("survives mutations of the new formats with only media exceptions")
    void mutations() {
        var seeds = List.of(fixture("nv12"), fixture("p010"), fixture("nv11"), fixture("y410"), fixture("y216"),
                fixture("legacy-a8p8"), fixture("legacy-uyvy"), fixture("d24-unorm-s8-uint"), fixture("r1-unorm"),
                DdsFiles.legacy(5, 3, 1, DdsFiles.DDPF_YUV | DdsFiles.DDPF_ALPHAPIXELS, 0, 32,
                        new long[]{0xFF0000, 0xFF00, 0xFF, 0xFF000000L}, 0, 1, DdsFiles.random(60, 8)),
                DdsFiles.dx10(5, 3, 131, DdsFiles.random(5 * 7, 9)));
        var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(300), 23,
                source -> DdsDecoder.decode(source));
        assertTrue(stats.decoded() > 0);
    }
}
