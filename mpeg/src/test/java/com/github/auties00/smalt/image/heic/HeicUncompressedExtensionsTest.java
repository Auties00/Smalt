package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.UnciWriter;
import com.github.auties00.smalt.testkit.UnciWriter.Component;
import com.github.auties00.smalt.testkit.UnciWriter.Image;
import com.github.auties00.smalt.testkit.UnciWriter.Layout;
import com.github.auties00.smalt.testkit.UnciWriter.PaletteComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.github.auties00.smalt.testkit.UnciWriter.A;
import static com.github.auties00.smalt.testkit.UnciWriter.B;
import static com.github.auties00.smalt.testkit.UnciWriter.BLACK;
import static com.github.auties00.smalt.testkit.UnciWriter.CYAN;
import static com.github.auties00.smalt.testkit.UnciWriter.FILTER_ARRAY;
import static com.github.auties00.smalt.testkit.UnciWriter.FLOAT;
import static com.github.auties00.smalt.testkit.UnciWriter.G;
import static com.github.auties00.smalt.testkit.UnciWriter.MAGENTA;
import static com.github.auties00.smalt.testkit.UnciWriter.MONO;
import static com.github.auties00.smalt.testkit.UnciWriter.PALETTE;
import static com.github.auties00.smalt.testkit.UnciWriter.R;
import static com.github.auties00.smalt.testkit.UnciWriter.SIGNED;
import static com.github.auties00.smalt.testkit.UnciWriter.YELLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the uncompressed items of ISO/IEC 23001-17 that need its later properties through {@link HeicDecoder}, with
 * those properties marked essential: palettes ({@code cpal}), filter arrays ({@code cpat}), CMYK, floating-point
 * and signed components, and Brotli compression in units ({@code cmpC}, {@code icef}). The layouts themselves are
 * covered by core's {@code HeifUncompressedFormatsTest}.
 */
@DisplayName("HEIF with uncompressed images of ISO/IEC 23001-17 extensions")
class HeicUncompressedExtensionsTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    private static byte[] file(Image image, int[] definition, byte[] data, byte[]... extra) {
        var properties = new ArrayList<byte[]>();
        properties.add(UnciWriter.uncC(image.components(), image.layout(), definition));
        properties.add(UnciWriter.cmpd(definition));
        properties.addAll(List.of(extra));
        return UnciWriter.file(image.width(), image.height(), data, properties.toArray(byte[][]::new));
    }

    private static BigInteger[] values(int count, java.util.function.IntUnaryOperator value) {
        var out = new BigInteger[count];
        for (var i = 0; i < count; i++) {
            out[i] = BigInteger.valueOf(value.applyAsInt(i));
        }
        return out;
    }

    @Test
    @DisplayName("a palette of RGBA entries decodes to the entries of its indexes, alpha included")
    void palette() {
        var components = List.of(Component.of(PALETTE, 2));
        var definition = UnciWriter.definition(components, R, G, B, A);
        var planes = UnciWriter.planes();
        planes.put(PALETTE, values(12, i -> i % 4));
        var image = new Image(4, 3, components, Layout.of(0, 0), planes);
        var entries = new BigInteger[4][4];
        for (var e = 0; e < 4; e++) {
            entries[e] = new BigInteger[]{BigInteger.valueOf(e * 60), BigInteger.valueOf(255 - e * 50),
                    BigInteger.valueOf(e * 10), BigInteger.valueOf(e == 3 ? 0 : 255)};
        }
        var cpal = UnciWriter.cpal(List.of(new PaletteComponent(R, 8, 0), new PaletteComponent(G, 8, 0),
                new PaletteComponent(B, 8, 0), new PaletteComponent(A, 8, 0)), definition, entries);
        var bytes = file(image, definition, UnciWriter.data(image), cpal);
        assertTrue(HeicDecoder.probe(MediaSource.of(bytes)).hasAlpha());
        var rgba = ImageOps.toRgba(HeicDecoder.decode(MediaSource.of(bytes)).picture());
        for (var i = 0; i < 12; i++) {
            var at = (i / 4) * rgba.stride() + 4 * (i % 4);
            var e = i % 4;
            assertEquals(e * 60, rgba.pixels()[at] & 0xFF, "red " + i);
            assertEquals(255 - e * 50, rgba.pixels()[at + 1] & 0xFF, "green " + i);
            assertEquals(e * 10, rgba.pixels()[at + 2] & 0xFF, "blue " + i);
            assertEquals(e == 3 ? 0 : 255, rgba.pixels()[at + 3] & 0xFF, "alpha " + i);
        }
    }

    @Test
    @DisplayName("a Bayer filter array decodes to its linear 12-bit mosaic")
    void bayer() {
        var components = List.of(new Component(FILTER_ARRAY, 12, 0, 2));
        var definition = UnciWriter.definition(components, R, G, B);
        var planes = UnciWriter.planes();
        planes.put(FILTER_ARRAY, values(8 * 4, i -> (i * 131) % 4096));
        var image = new Image(8, 4, components, Layout.of(0, 0).littleEndian(true), planes);
        var bytes = file(image, definition, UnciWriter.data(image), UnciWriter.cpat(2, 2, new int[]{R, G, G, B},
                new float[]{2f, 1f, 1f, 1.5f}, definition));
        var picture = assertInstanceOf(Yuv16Picture.class, HeicDecoder.decode(MediaSource.of(bytes), WIDE).picture());
        assertEquals(ChromaFormat.MONOCHROME, picture.chromaFormat());
        assertEquals(TransferCharacteristics.LINEAR, picture.color().transfer());
        for (var i = 0; i < 32; i++) {
            assertEquals((i * 131) % 4096, picture.y()[(i / 8) * picture.yStride() + i % 8], "sample " + i);
        }
    }

    @Test
    @DisplayName("a CMYK item decodes to RGB without color management")
    void cmyk() {
        var components = List.of(Component.of(CYAN, 8), Component.of(MAGENTA, 8), Component.of(YELLOW, 8),
                Component.of(BLACK, 8));
        var planes = UnciWriter.planes();
        planes.put(CYAN, values(4, i -> i * 80));
        planes.put(MAGENTA, values(4, i -> 255 - i * 80));
        planes.put(YELLOW, values(4, i -> 30));
        planes.put(BLACK, values(4, i -> i * 20));
        var image = new Image(2, 2, components, Layout.of(0, 1), planes);
        var definition = UnciWriter.definition(components);
        var rgba = ImageOps.toRgba(HeicDecoder.decode(MediaSource.of(file(image, definition,
                UnciWriter.data(image)))).picture());
        for (var i = 0; i < 4; i++) {
            var k = i * 20;
            var at = (i / 2) * rgba.stride() + 4 * (i % 2);
            assertEquals(((255 - i * 80) * (255 - k) + 127) / 255, rgba.pixels()[at] & 0xFF, "red " + i);
            assertEquals(((i * 80) * (255 - k) + 127) / 255, rgba.pixels()[at + 1] & 0xFF, "green " + i);
            assertEquals((225 * (255 - k) + 127) / 255, rgba.pixels()[at + 2] & 0xFF, "blue " + i);
        }
    }

    @Test
    @DisplayName("floating-point gray and signed RGB decode to 16-bit pictures")
    void floatAndSigned() {
        var gray = List.of(new Component(MONO, 32, FLOAT, 0));
        var floats = new float[]{0f, 0.25f, 1f, 2f};
        var planes = UnciWriter.planes();
        planes.put(MONO, values(4, i -> Float.floatToRawIntBits(floats[i])));
        planes.put(MONO, java.util.Arrays.stream(planes.get(MONO)).map(v -> v.and(BigInteger.valueOf(0xFFFFFFFFL)))
                .toArray(BigInteger[]::new));
        var image = new Image(4, 1, gray, Layout.of(0, 0), planes);
        var picture = assertInstanceOf(Yuv16Picture.class, HeicDecoder.decode(MediaSource.of(file(image,
                UnciWriter.definition(gray), UnciWriter.data(image))), WIDE).picture());
        assertEquals(16, picture.bitDepth());
        assertEquals(0, picture.y()[0]);
        assertEquals(16384, picture.y()[1] & 0xFFFF);
        assertEquals(65535, picture.y()[2] & 0xFFFF);
        assertEquals(65535, picture.y()[3] & 0xFFFF);
        var rgb = List.of(new Component(R, 8, SIGNED, 0), new Component(G, 8, SIGNED, 0), new Component(B, 8, SIGNED, 0));
        var signed = UnciWriter.planes();
        signed.put(R, values(1, i -> 0x80));
        signed.put(G, values(1, i -> 0));
        signed.put(B, values(1, i -> 0x7F));
        var signedImage = new Image(1, 1, rgb, Layout.of(0, 1), signed);
        var narrow = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(file(signedImage,
                UnciWriter.definition(rgb), UnciWriter.data(signedImage)))).picture());
        assertEquals(128, narrow.y()[0] & 0xFF);
        assertEquals(255, narrow.u()[0] & 0xFF);
        assertEquals(0, narrow.v()[0] & 0xFF);
    }

    @Test
    @DisplayName("Brotli compression in units of rows indexed by an essential icef decodes")
    void brotliUnits() {
        var components = List.of(Component.of(R, 8), Component.of(G, 8), Component.of(B, 8));
        var planes = UnciWriter.planes();
        planes.put(R, values(12, i -> i * 20));
        planes.put(G, values(12, i -> 255 - i * 20));
        planes.put(B, values(12, i -> 7 * i));
        var image = new Image(4, 3, components, Layout.of(0, 1), planes);
        var data = UnciWriter.data(image);
        var payload = new java.io.ByteArrayOutputStream();
        var offsets = new long[3];
        var sizes = new long[3];
        for (var row = 0; row < 3; row++) {
            var unit = UnciWriter.storedBrotli(java.util.Arrays.copyOfRange(data, row * 12, row * 12 + 12));
            offsets[row] = payload.size();
            sizes[row] = unit.length;
            payload.writeBytes(unit);
        }
        var bytes = file(image, UnciWriter.definition(components), payload.toByteArray(),
                UnciWriter.cmpC("brot", 3), UnciWriter.icef(1, 0, offsets, sizes));
        var rgba = ImageOps.toRgba(HeicDecoder.decode(MediaSource.of(bytes)).picture());
        for (var i = 0; i < 12; i++) {
            var at = (i / 4) * rgba.stride() + 4 * (i % 4);
            assertEquals(i * 20, rgba.pixels()[at] & 0xFF, "red " + i);
            assertEquals(7 * i, rgba.pixels()[at + 2] & 0xFF, "blue " + i);
        }
    }

    @Test
    @DisplayName("a palette item without cpal is malformed and is not mistaken for an image with alpha")
    void paletteWithoutCpal() {
        var components = List.of(Component.of(PALETTE, 8));
        var definition = UnciWriter.definition(components, R, G, B);
        var planes = UnciWriter.planes();
        planes.put(PALETTE, values(4, i -> 0));
        var image = new Image(2, 2, components, Layout.of(0, 0), planes);
        var bytes = file(image, definition, UnciWriter.data(image));
        assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(bytes)));
        assertEquals(false, HeicDecoder.probe(MediaSource.of(bytes)).hasAlpha());
    }
}
