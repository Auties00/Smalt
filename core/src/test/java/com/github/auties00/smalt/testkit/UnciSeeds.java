package com.github.auties00.smalt.testkit;

import com.github.auties00.smalt.testkit.UnciWriter.Component;
import com.github.auties00.smalt.testkit.UnciWriter.Image;
import com.github.auties00.smalt.testkit.UnciWriter.Layout;
import com.github.auties00.smalt.testkit.UnciWriter.PaletteComponent;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.github.auties00.smalt.testkit.UnciWriter.A;
import static com.github.auties00.smalt.testkit.UnciWriter.B;
import static com.github.auties00.smalt.testkit.UnciWriter.BLACK;
import static com.github.auties00.smalt.testkit.UnciWriter.CB;
import static com.github.auties00.smalt.testkit.UnciWriter.COMPLEX;
import static com.github.auties00.smalt.testkit.UnciWriter.CR;
import static com.github.auties00.smalt.testkit.UnciWriter.CYAN;
import static com.github.auties00.smalt.testkit.UnciWriter.FILTER_ARRAY;
import static com.github.auties00.smalt.testkit.UnciWriter.FLOAT;
import static com.github.auties00.smalt.testkit.UnciWriter.G;
import static com.github.auties00.smalt.testkit.UnciWriter.MAGENTA;
import static com.github.auties00.smalt.testkit.UnciWriter.MONO;
import static com.github.auties00.smalt.testkit.UnciWriter.PALETTE;
import static com.github.auties00.smalt.testkit.UnciWriter.R;
import static com.github.auties00.smalt.testkit.UnciWriter.SIGNED;
import static com.github.auties00.smalt.testkit.UnciWriter.UNSIGNED;
import static com.github.auties00.smalt.testkit.UnciWriter.Y;
import static com.github.auties00.smalt.testkit.UnciWriter.YELLOW;

/**
 * The small uncompressed HEIF files of the ISO/IEC 23001-17 features beyond the basic layouts, built with
 * {@link UnciWriter} from seeded random values: the fuzz seeds of the HEIC target and the inputs of its I/O behavior
 * harness.
 */
public enum UnciSeeds {
    /** A 4-bit palette of 10-bit RGB and 8-bit alpha entries. */
    PALETTE_RGBA("valid-unci-palette.heic"),
    /** 32-bit floating-point gray. */
    FLOAT_GRAY("valid-unci-float.heic"),
    /** Signed 24-bit red, unsigned 40-bit green and complex 64-bit blue in reversed little-endian 16-byte blocks. */
    WIDE_BLOCKS("valid-unci-wide-blocks.heic"),
    /** 8-bit CMYK, pixel interleaving. */
    CMYK("valid-unci-cmyk.heic"),
    /** A 12-bit Bayer filter array in little-endian two-byte words, with its pattern. */
    BAYER("valid-unci-bayer.heic"),
    /** 8-bit RGB compressed with Brotli in units of rows indexed by icef. */
    BROTLI_UNITS("valid-unci-brot-units.heic"),
    /** 10-bit Y'CbCr 4:2:0, row interleaving, of odd size. */
    ROWS_420("valid-unci-420-rows.heic"),
    /** 8-bit Y'CbCr 4:2:0, multi-Y interleaving, of odd size. */
    MULTI_Y_420("valid-unci-420-multiy.heic");

    private final String fileName;

    UnciSeeds(String fileName) {
        this.fileName = fileName;
    }

    /** {@return the seed's file name} */
    public String fileName() {
        return fileName;
    }

    /** {@return the HEIF file} */
    public byte[] file() {
        var random = new Random(ordinal() + 23L);
        return switch (this) {
            case PALETTE_RGBA -> {
                var components = List.of(Component.of(PALETTE, 4));
                var definition = UnciWriter.definition(components, R, G, B, A);
                var planes = UnciWriter.planes();
                planes.put(PALETTE, random(random, 12 * 8, 4, UNSIGNED, 16));
                var entries = new BigInteger[16][];
                for (var e = 0; e < 16; e++) {
                    entries[e] = new BigInteger[]{BigInteger.valueOf(e * 68), BigInteger.valueOf(1023 - e * 60),
                            BigInteger.valueOf(random.nextInt(1024)), BigInteger.valueOf(e < 12 ? 255 : e * 10)};
                }
                var image = new Image(12, 8, components, Layout.of(0, 0), planes);
                yield file(image, definition, UnciWriter.data(image), UnciWriter.cpal(List.of(
                        new PaletteComponent(R, 10, UNSIGNED), new PaletteComponent(G, 10, UNSIGNED),
                        new PaletteComponent(B, 10, UNSIGNED), new PaletteComponent(A, 8, UNSIGNED)), definition,
                        entries));
            }
            case FLOAT_GRAY -> {
                var components = List.of(new Component(MONO, 32, FLOAT, 0));
                var values = new BigInteger[12 * 8];
                for (var i = 0; i < values.length; i++) {
                    var v = (i % 12) / 11f * 1.2f - 0.1f;
                    values[i] = BigInteger.valueOf(Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
                }
                var planes = UnciWriter.planes();
                planes.put(MONO, values);
                var image = new Image(12, 8, components, Layout.of(0, 0), planes);
                yield file(image, UnciWriter.definition(components), UnciWriter.data(image));
            }
            case WIDE_BLOCKS -> {
                var components = List.of(new Component(R, 24, SIGNED, 0), Component.of(G, 40),
                        new Component(B, 64, COMPLEX, 0));
                var planes = UnciWriter.planes();
                planes.put(R, random(random, 8 * 6, 24, SIGNED, 0));
                planes.put(G, random(random, 8 * 6, 40, UNSIGNED, 0));
                var blue = new BigInteger[8 * 6];
                for (var i = 0; i < blue.length; i++) {
                    var real = Float.floatToRawIntBits(random.nextFloat()) & 0xFFFFFFFFL;
                    var imaginary = Float.floatToRawIntBits(random.nextFloat() - 0.5f) & 0xFFFFFFFFL;
                    blue[i] = BigInteger.valueOf(real).shiftLeft(32).or(BigInteger.valueOf(imaginary));
                }
                planes.put(B, blue);
                var image = new Image(8, 6, components, Layout.of(0, 1).blocks(16, false, true, true), planes);
                yield file(image, UnciWriter.definition(components), UnciWriter.data(image));
            }
            case CMYK -> {
                var components = List.of(Component.of(CYAN, 8), Component.of(MAGENTA, 8), Component.of(YELLOW, 8),
                        Component.of(BLACK, 8));
                var planes = UnciWriter.planes();
                for (var type : new int[]{CYAN, MAGENTA, YELLOW, BLACK}) {
                    planes.put(type, random(random, 8 * 8, 8, UNSIGNED, 0));
                }
                var image = new Image(8, 8, components, Layout.of(0, 1), planes);
                yield file(image, UnciWriter.definition(components), UnciWriter.data(image));
            }
            case BAYER -> {
                var components = List.of(new Component(FILTER_ARRAY, 12, UNSIGNED, 2));
                var definition = UnciWriter.definition(components, R, G, B);
                var planes = UnciWriter.planes();
                planes.put(FILTER_ARRAY, random(random, 8 * 8, 12, UNSIGNED, 0));
                var image = new Image(8, 8, components, Layout.of(0, 0).littleEndian(true), planes);
                yield file(image, definition, UnciWriter.data(image), UnciWriter.cpat(2, 2, new int[]{R, G, G, B},
                        new float[]{1.8f, 1f, 1f, 1.4f}, definition));
            }
            case BROTLI_UNITS -> {
                var components = List.of(Component.of(R, 8), Component.of(G, 8), Component.of(B, 8));
                var planes = UnciWriter.planes();
                for (var type : new int[]{R, G, B}) {
                    planes.put(type, random(random, 8 * 6, 8, UNSIGNED, 0));
                }
                var image = new Image(8, 6, components, Layout.of(0, 1), planes);
                var data = UnciWriter.data(image);
                var payload = new ByteArrayOutputStream();
                var offsets = new long[6];
                var sizes = new long[6];
                for (var row = 0; row < 6; row++) {
                    var unit = UnciWriter.storedBrotli(Arrays.copyOfRange(data, row * 24, row * 24 + 24));
                    offsets[row] = payload.size();
                    sizes[row] = unit.length;
                    payload.writeBytes(unit);
                }
                yield file(image, UnciWriter.definition(components), payload.toByteArray(),
                        UnciWriter.cmpC("brot", 3), UnciWriter.icef(1, 0, offsets, sizes));
            }
            case ROWS_420 -> {
                var components = List.of(Component.of(Y, 10), Component.of(CB, 10), Component.of(CR, 10));
                var planes = UnciWriter.planes();
                planes.put(Y, random(random, 9 * 7, 10, UNSIGNED, 0));
                planes.put(CB, random(random, 5 * 4, 10, UNSIGNED, 0));
                planes.put(CR, random(random, 5 * 4, 10, UNSIGNED, 0));
                var image = new Image(9, 7, components, Layout.of(2, 3).rowAlign(4), planes);
                yield file(image, UnciWriter.definition(components), UnciWriter.data(image));
            }
            case MULTI_Y_420 -> {
                var components = List.of(Component.of(CB, 8), Component.of(Y, 8), Component.of(Y, 8),
                        Component.of(CR, 8), Component.of(Y, 8), Component.of(Y, 8));
                var planes = UnciWriter.planes();
                planes.put(Y, random(random, 9 * 7, 8, UNSIGNED, 0));
                planes.put(CB, random(random, 5 * 4, 8, UNSIGNED, 0));
                planes.put(CR, random(random, 5 * 4, 8, UNSIGNED, 0));
                var image = new Image(9, 7, components, Layout.of(2, 5), planes);
                yield file(image, UnciWriter.definition(components), UnciWriter.data(image));
            }
        };
    }

    private static BigInteger[] random(Random random, int count, int depth, int format, int bound) {
        var out = new BigInteger[count];
        for (var i = 0; i < count; i++) {
            out[i] = bound != 0 ? BigInteger.valueOf(random.nextInt(bound)) : new BigInteger(depth, random);
        }
        return out;
    }

    private static byte[] file(Image image, int[] definition, byte[] data, byte[]... extra) {
        var properties = new ArrayList<byte[]>();
        properties.add(UnciWriter.uncC(image.components(), image.layout(), definition));
        properties.add(UnciWriter.cmpd(definition));
        properties.addAll(List.of(extra));
        return UnciWriter.file(image.width(), image.height(), data, properties.toArray(byte[][]::new));
    }
}
