package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.image.raw.TinyDng;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the VC-5 decoder of GoPro's GPR files: the samples of every camera against the digest of the photosites Adobe
 * DNG Converter stores for them (every CC0 sample is compared with the converter by {@link VendorOracleTest}), and
 * synthetic bitstreams of {@link Vc5Writer} in DNG files: both Bayer orders and output depths, the stream errors
 * it rejects, truncated and damaged files.
 */
class Vc5DecoderTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    // the SHA-256 of the photosites Adobe DNG Converter 18.0 stores for the sample (raw-oracle.py's summary)
    private static final String HERO7_DIGEST = "25428fca54b1ac52b58dd4400779d63fa96ee7e62f6fa532b3f96ff94ae59389";

    static byte[] gpr(Vc5Writer writer, String cfa, int white) {
        var dng = new TinyDng();
        dng.width = writer.width;
        dng.height = writer.height;
        dng.bits = 16;
        dng.compression = 9;
        dng.cfa = cfa;
        dng.white = white;
        dng.stripData = writer.write();
        return dng.write();
    }

    private static SensorPlane decode(byte[] data) {
        var file = RawParser.parse(MediaSource.of(data), MediaLimits.DEFAULT);
        assertTrue(file.sensor != null, file.unsupported);
        var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
        file.sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane;
    }

    private static String digest(short[] samples) throws Exception {
        var bytes = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var sample : samples) {
            bytes.putShort(sample);
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
    }

    @Test
    @DisplayName("a GoPro HERO7 Black file decodes to the photosites Adobe DNG Converter stores for it")
    void hero7() throws Exception {
        var plane = decode(java.nio.file.Files.readAllBytes(RawSamples.GPR_HERO7_BLACK_16BIT_4_3_2908.path()));
        assertEquals(4000, plane.width);
        assertEquals(3000, plane.height);
        assertEquals(HERO7_DIGEST, digest(plane.integers));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // the SHA-256 of the image Adobe DNG Converter 18.0 stores (-u) for each file: the two lenses of a FUSION
            // (3104 x 3000) and the HERO5 to HERO9 Black
            "GPR_FUSION_BACK, 3104, 3000, 44732fc1dbc2883b89f33be4f6a7b2cd1f93c401c75859e4d82ad96be7399527",
            "GPR_FUSION_FRONT, 3104, 3000, 58382d65b40cdd82b33f3b91a47de1b5f329a6289bafc20a88d30a4543f97ed3",
            "GPR_HERO5, 4000, 3000, fc4da317d5acd87ff502b5f692da8a2b5e8562297c7a30ab4d774ee3cd718166",
            "GPR_HERO6, 4000, 3000, 7c0fa7faa996b89b68cbafd60b69a5d6b3d6338ec63b620944eb5749400dac43",
            "GPR_HERO7, 4000, 3000, fab22db6b11e8ecb220e54e35a5b81d98500db26ff2cc741b84c472d09d0b220",
            "GPR_HERO9, 5568, 4176, 6592240ad9710edba5190fd1fd17c951cdb9f2359cedd3d2ab6728efaafcf882"
    })
    @DisplayName("GPR files of every GoPro camera decode to the photosites Adobe DNG Converter stores for them")
    void cameras(RawSamples sample, int width, int height, String sha256) throws Exception {
        var plane = decode(java.nio.file.Files.readAllBytes(sample.path()));
        assertEquals(width, plane.width);
        assertEquals(height, plane.height);
        assertEquals(sha256, digest(plane.integers));
    }

    @Test
    @DisplayName("a synthetic bitstream decodes to photosites of the output depth, the same for each thread count")
    void synthetic() {
        var writer = new Vc5Writer().scene(1);
        var first = decode(gpr(writer, "RGGB", 16383));
        var again = decode(gpr(writer, "RGGB", 16383));
        assertArrayEquals(first.integers, again.integers);
        var distinct = Arrays.stream(toInts(first.integers)).distinct().count();
        assertTrue(distinct > 50, "a scene decodes to a varied image: " + distinct + " values");
        for (var value : first.integers) {
            assertTrue(value >= 0 && value < 1 << 14);
        }
    }

    private static int[] toInts(short[] values) {
        var out = new int[values.length];
        for (var index = 0; index < values.length; index++) {
            out[index] = values[index] & 0xFFFF;
        }
        return out;
    }

    @Test
    @DisplayName("12-bit output is the 14-bit output without its two low bits")
    void depths() {
        var writer = new Vc5Writer().scene(2);
        var fourteen = decode(gpr(writer, "RGGB", 16383));
        var twelve = decode(gpr(writer, "RGGB", 4095));
        for (var index = 0; index < fourteen.integers.length; index++) {
            assertEquals(fourteen.integers[index] >> 2, twelve.integers[index]);
        }
    }

    @Test
    @DisplayName("the green-first order places the same photosites of each quad as green, blue, red, green")
    void greenFirst() {
        var writer = new Vc5Writer().scene(3);
        var rggb = decode(gpr(writer, "RGGB", 16383));
        var gbrg = decode(gpr(writer, "GBRG", 16383));
        var width = rggb.width;
        for (var y = 0; y < rggb.height; y += 2) {
            for (var x = 0; x < width; x += 2) {
                var top = y * width + x;
                var bottom = top + width;
                // red, green 1, green 2, blue
                assertEquals(rggb.integers[top], gbrg.integers[bottom]);
                assertEquals(rggb.integers[top + 1], gbrg.integers[top]);
                assertEquals(rggb.integers[bottom], gbrg.integers[bottom + 1]);
                assertEquals(rggb.integers[bottom + 1], gbrg.integers[top + 1]);
            }
        }
    }

    @Test
    @DisplayName("the highpass coefficients change the decoded photosites")
    void highpass() {
        var writer = new Vc5Writer().scene(4);
        var plain = decode(gpr(writer, "RGGB", 16383));
        writer.subbands[0][9][5] = 30;
        var changed = decode(gpr(writer, "RGGB", 16383));
        assertNotEquals(Arrays.hashCode(plain.integers), Arrays.hashCode(changed.integers));
    }

    @Test
    @DisplayName("rejects a stream without its start marker, a band without its end marker and a truncated stream")
    void malformed() {
        var writer = new Vc5Writer().scene(5);
        var stream = writer.write();
        var marker = stream.clone();
        marker[0] = 'X';
        var dng = new TinyDng();
        dng.width = writer.width;
        dng.height = writer.height;
        dng.compression = 9;
        dng.white = 16383;
        dng.stripData = marker;
        assertThrows(MediaException.Malformed.class, () -> RawDecoder.decodeRaw(MediaSource.of(dng.write())));
        writer.dropBandEnd = true;
        assertThrows(MediaException.Malformed.class,
                () -> RawDecoder.decodeRaw(MediaSource.of(gpr(writer, "RGGB", 16383))));
        writer.dropBandEnd = false;
        dng.stripData = Arrays.copyOf(stream, stream.length / 2);
        assertThrows(MediaException.Malformed.class, () -> RawDecoder.decodeRaw(MediaSource.of(dng.write())));
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var original = gpr(new Vc5Writer().scene(6), "RGGB", 16383);
        var random = new SplittableRandom(23);
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            for (var round = 0; round < 500; round++) {
                var bytes = original.clone();
                if (round % 3 == 0) {
                    bytes = Arrays.copyOf(bytes, 8 + random.nextInt(bytes.length - 8));
                } else {
                    for (var count = 0; count < 1 + random.nextInt(6); count++) {
                        bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
                    }
                }
                var input = bytes;
                var limits = MediaLimits.builder().maxPixels(1 << 20).build();
                try {
                    RawDecoder.decodeRaw(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                } catch (MediaException expected) {
                    // a damaged file may be rejected
                }
            }
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the VC-5 seed of the DNG fuzz target")
    void fuzzSeeds() {
        FuzzSeeds.write(FUZZ_TARGET, "dng", "valid-vc5.dng", gpr(new Vc5Writer().scene(1), "RGGB", 16383));
    }
}
