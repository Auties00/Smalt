package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks the variable-length coded samples of the Sony DSLR-A100 to A390 against the values of the uncompressed DNG
 * files Adobe DNG Converter 18.0 writes for them with {@code -u}, run as a black box: the converter's image (a window
 * of the decoded sensor image) by the SHA-256 of its little-endian 16-bit words, row by row, its black level, its
 * white balance and its image area. The digests were computed once from those files, so the test needs only the sample
 * files, which are CC0 except those of the DSLR-A300 and A330 (CC BY-NC-SA, {@code tools/testdata/licences.tsv}).
 */
@DisplayName("Sony variable-length coding against the converter")
class SonyDeltaOracleTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "ARW_A100_DELTA_12, 3880, 2600, 0, ec36ce4ecbc62249ca0cecf949006a1cb6109647d5b287e5823daef7069cfaf6, 0, 0.638404, 0.445993, GRBG",
            "ARW_A200_COMPRESSED_12, 3878, 2600, 1, 4d330f51c3a2f31a1a41dd0f150bb26295bd9fc982ac89acdcb65f0767b0f2aa, 4, 0.742029, 0.320000, RGGB",
            "ARW_A230_DELTA_12, 3878, 2600, 1, a303acc8e8a9c0dea3fb221af8651de110daed159bbb0575aef905eae15923a3, 0, 0.577878, 0.619855, RGGB",
            "ARW_A290_DELTA_12, 4599, 3064, 0, e652994373cdfcb380cd671913a2be12ed2ef3ab47189efcb816e58c3944399a, 0, 0.349727, 0.705234, RGGB",
            "ARW_A300_DELTA_12, 3878, 2600, 1, 71f5d008c69adb710920c38d4080eb13f13d0a12f377bd8cfdff4460ca2ad508, 0, 0.519270, 0.627451, RGGB",
            "ARW_A330_DELTA_12, 3878, 2600, 1, 1c206fe06cef492bc239681458b812d998bf7cefea21c695f692881ff6d38f7b, 0, 0.568889, 0.646465, RGGB",
            "ARW_A350_DELTA_12, 4599, 3064, 0, d856d8a2845e578ba47b7aca80b8bd217321082d358e4011b773d80c17f1940d, 0, 0.371553, 0.699454, RGGB",
            "ARW_A380_DELTA_12, 4599, 3064, 0, 4e71be3d3da27a7edf7eb385569e1ab503a4d589b64814d923e9f30c85eb0200, 0, 0.404423, 0.606635, RGGB",
            "ARW_A390_DELTA_12, 4599, 3064, 0, a6fa214d909846e6bcb4587d25b659c8efb754d33f7de28160b737eb6a9b1299, 0, 0.388467, 0.633663, RGGB"
    })
    @DisplayName("the photosites, black level, white balance and image area equal the converter's")
    void sample(RawSamples sample, int width, int height, int left, String digest, int black, double neutralRed,
                double neutralBlue, String pattern) throws NoSuchAlgorithmException {
        var path = sample.fetch();
        try (var source = MediaSource.of(path)) {
            var limits = MediaLimits.builder().maxBytes(1L << 30).build();
            var file = RawParser.parse(source, limits);
            assertNotNull(file.sensor, file.unsupported);
            var sensor = file.sensor;
            assertEquals("Sony variable-length", sensor.compression);
            assertEquals(12, sensor.bitsPerSample);
            assertEquals(pattern, sensor.cfa.toString());
            var plane = SensorPlane.of(sensor, limits);
            sensor.reader.read(plane, limits);
            var words = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    words.putShort(plane.integers[y * plane.width + left + x]);
                }
            }
            assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(words.array())),
                    "the photosites");
            for (var level : sensor.black) {
                assertEquals(black, level, "the black level");
            }
            assertArrayEquals(new double[]{4095}, sensor.white, "the white level");
            assertArrayEquals(new double[]{neutralRed, 1, neutralBlue}, file.color.asShotNeutral, 1e-6,
                    "the white balance");
            var crop = sensor.defaultCrop;
            assertNotNull(crop);
            var wide = plane.width > 4000;
            assertArrayEquals(new double[]{4, 4, wide ? 4592 : 3872, wide ? 3056 : 2592}, crop, "the image area");
        }
    }
}
