package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.internal.SyntheticVendorTiff.Nikon;
import com.github.auties00.smalt.aot.internal.SyntheticVendorTiff.Panasonic;
import com.github.auties00.smalt.aot.internal.SyntheticVendorTiff.RawSample;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks that the TIFF-structured vendor raw files of the training workloads are decodable files of their formats
 * whose sensor images are the photosites their coders simulated, which also checks the NEF, CR2, PEF, ARW, ORF and
 * RW2 decoders against independent coders of their streams.
 */
@DisplayName("synthetic TIFF-structured vendor raw files of the training workloads")
class SyntheticVendorTiffTest {
    private static RgbaPicture picture(int width, int height) {
        return SyntheticImages.rgba(SyntheticVideo.natural(width, height, 1).getFirst(), false);
    }

    static Stream<Arguments> files() {
        return Stream.of(
                Arguments.of("NEF lossless 14-bit", RawFormat.NEF,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.nef(picture(96, 64), Nikon.LOSSLESS_14)),
                Arguments.of("NEF lossless 12-bit", RawFormat.NEF,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.nef(picture(96, 64), Nikon.LOSSLESS_12)),
                Arguments.of("NEF lossy 12-bit", RawFormat.NEF,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.nef(picture(96, 64), Nikon.LOSSY_12)),
                Arguments.of("NEF uncompressed", RawFormat.NEF,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.nef(picture(96, 64), Nikon.UNCOMPRESSED)),
                Arguments.of("CR2", RawFormat.CR2,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.cr2(picture(128, 64))),
                Arguments.of("PEF 14-bit", RawFormat.PEF,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.pef(picture(96, 64), 14)),
                Arguments.of("PEF 12-bit", RawFormat.PEF,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.pef(picture(96, 64), 12)),
                Arguments.of("ARW cRAW", RawFormat.ARW,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.arw(picture(160, 96), true)),
                Arguments.of("ARW uncompressed", RawFormat.ARW,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.arw(picture(160, 96), false)),
                Arguments.of("ARW variable-length", RawFormat.ARW,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.arwVariableLength(picture(160, 96))),
                Arguments.of("ORF compressed", RawFormat.ORF,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.orf(picture(96, 64), true)),
                Arguments.of("ORF uncompressed", RawFormat.ORF,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.orf(picture(96, 64), false)),
                Arguments.of("RW2 version 4", RawFormat.RW2,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.rw2(picture(112, 64), Panasonic.V4)),
                Arguments.of("RW2 version 5", RawFormat.RW2,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.rw2(picture(90, 64), Panasonic.V5)),
                Arguments.of("RW2 version 6", RawFormat.RW2,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.rw2(picture(88, 64), Panasonic.V6)),
                Arguments.of("RW2 version 8", RawFormat.RW2,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.rw2(picture(96, 64), Panasonic.V8)),
                Arguments.of("ERF", RawFormat.ERF,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.erf(picture(100, 64))),
                Arguments.of("MEF", RawFormat.MEF,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.mef(picture(96, 64))),
                Arguments.of("SRW packed", RawFormat.SRW,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.srw(picture(96, 64), true)),
                Arguments.of("SRW words", RawFormat.SRW,
                        (Supplier<RawSample>) () -> SyntheticVendorTiff.srw(picture(96, 64), false)));
    }

    static Stream<Arguments> uncheckedLevels() {
        // each writer's mosaic parameters and the image area's origin in the stored photosites
        return Stream.of(
                Arguments.of("Kodak DCS", RawFormat.DCR, (Supplier<byte[]>) () -> SyntheticVendorTiff.kodak(
                        picture(96, 64)), SyntheticVendorRaw.mosaic(picture(96, 64), 100, 3995), 96, 4, 2, 88, 60),
                Arguments.of("Leaf MOS compressed", RawFormat.MOS, (Supplier<byte[]>) () -> SyntheticVendorTiff.leaf(
                        picture(96, 64), true), SyntheticVendorRaw.mosaic(picture(96, 64), 0, 15000), 96, 0, 0, 96, 64),
                Arguments.of("Leaf MOS uncompressed", RawFormat.MOS,
                        (Supplier<byte[]>) () -> SyntheticVendorTiff.leaf(picture(96, 64), false),
                        SyntheticVendorRaw.mosaic(picture(96, 64), 0, 15000), 96, 0, 0, 96, 64),
                Arguments.of("Hasselblad 3FR", RawFormat.FFF,
                        (Supplier<byte[]>) () -> SyntheticVendorTiff.hasselblad(picture(96, 64)),
                        SyntheticVendorRaw.mosaic(picture(96, 64), 2048, 60000), 96, 4, 2, 88, 60));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uncheckedLevels")
    @DisplayName("a file whose levels come from model tables decodes to an order-preserving map of its photosites")
    void decodable(String name, RawFormat format, Supplier<byte[]> factory, int[] stored, int storedWidth, int left,
                   int top, int width, int height) {
        var file = factory.get();
        var info = RawDecoder.info(MediaSource.of(file));
        assertEquals(format, info.format(), name);
        assertTrue(info.isDecodable(), info.unsupportedFeature().orElse(""));
        assertEquals(width, info.width(), name);
        assertEquals(height, info.height(), name);
        var samples = RawDecoder.decodeRaw(MediaSource.of(file)).samples();
        assertEquals(width * height, samples.length, name);
        var distinct = new java.util.HashSet<Integer>();
        // the levels are unknown here, but every level, curve and black offset the decoders apply is the same
        // nondecreasing function of the stored value at each mosaic position, so a coder or decoder that misplaces
        // or miscodes a photosite breaks the order
        var pairs = new java.util.ArrayList<java.util.List<long[]>>();
        for (var position = 0; position < 4; position++) {
            pairs.add(new java.util.ArrayList<>());
        }
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = stored[(y + top) * storedWidth + x + left];
                var actual = samples[y * width + x] & 0xFFFF;
                pairs.get(((y + top) & 1) * 2 + ((x + left) & 1)).add(new long[]{value, actual});
                distinct.add(actual);
            }
        }
        for (var position = 0; position < 4; position++) {
            var list = pairs.get(position);
            list.sort(java.util.Comparator.<long[]>comparingLong(pair -> pair[0]).thenComparingLong(pair -> pair[1]));
            for (var i = 1; i < list.size(); i++) {
                var previous = list.get(i - 1);
                var current = list.get(i);
                if (current[1] < previous[1] || (current[0] == previous[0] && current[1] != previous[1])) {
                    fail(name + ": stored " + previous[0] + " decodes to " + previous[1] + " but stored "
                            + current[0] + " to " + current[1]);
                }
            }
        }
        assertTrue(distinct.size() > 64, name + " decodes to " + distinct.size() + " distinct values");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("decodes to the photosites its coder simulated, scaled by its levels")
    void decodes(String name, RawFormat format, Supplier<RawSample> factory) {
        var sample = factory.get();
        var info = RawDecoder.info(MediaSource.of(sample.file()));
        assertEquals(format, info.format(), name);
        assertTrue(info.isDecodable(), info.unsupportedFeature().orElse(""));
        assertEquals(sample.width(), info.width(), name);
        assertEquals(sample.height(), info.height(), name);
        var image = RawDecoder.decodeRaw(MediaSource.of(sample.file()));
        assertEquals(sample.width(), image.width(), name);
        assertEquals(sample.height(), image.height(), name);
        var samples = image.samples();
        assertEquals(sample.stored().length, samples.length, name);
        var distinct = new java.util.HashSet<Integer>();
        for (var y = 0; y < sample.height(); y++) {
            for (var x = 0; x < sample.width(); x++) {
                var index = y * sample.width() + x;
                var black = (float) sample.black()[(y & 1) * 2 + (x & 1)];
                var range = (float) sample.white() - black;
                // the decoder's linearization in float: the stored value less black, over the range, to 16 bits
                var expected = Math.round(Math.clamp((sample.stored()[index] - black) * (1 / range), 0f, 1f) * 65535f);
                var actual = samples[index] & 0xFFFF;
                if (Math.abs(expected - actual) > 1) {
                    fail(name + ": photosite " + x + "," + y + " stored " + sample.stored()[index] + " decodes to "
                            + actual + ", expected " + expected);
                }
                distinct.add(actual);
            }
        }
        // the picture's content survives: a flat or saturated decode would pass the comparison of a broken coder
        assertTrue(distinct.size() > 64, name + " decodes to " + distinct.size() + " distinct values");
        assertTrue(RawDecoder.decode(MediaSource.of(sample.file())).picture().width() > 0, name);
    }
}
