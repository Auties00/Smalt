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
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks the photosites of CC0 samples of codings no other sample covers against those Adobe DNG Converter 18.0, run
 * as a black box, stores for them ({@code -u}, uncompressed): the SHA-256 of the converter's stored image, little-endian
 * 16-bit words row by row, equals that of the decoded sensor plane over the same window. The codings are the Z-series
 * data stored uncompressed under Nikon's compression code (14-bit packed, 12-bit packed), the uncompressed data of the
 * Coolpix NRW files (12-bit packed in 32-bit words, and 12-bit samples in the high bits of 16-bit words, of which the
 * converter keeps the whole words: they are compared without their four low bits), Sony's lossless compression of
 * four-component tiles, of which the converter stores the window from the top-left corner it crops, and the original
 * ORF of an OM System high-resolution shot.
 */
class ConverterPhotositesTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "NEF_Z6_UNCOMPRESSED_14, 6064, 4040, 0, 080166252bc0b970c539fed331803b710e473d624bb963fab107753255ebe97a",
            "NEF_Z7II_UNCOMPRESSED_12, 8288, 5520, 0, a991222ee5dfdf705181c507c3b61c231745e527dbcb6801671f0ac2e9a1ad46",
            "NRW_B700, 5200, 3902, 0, 5207e0705ff1c5b29a76a94b54cc460eccf97bc77afcc74c879a45d6aa6286d4",
            // the converter's words with their four low bits cleared
            "NRW_P7800, 4032, 3024, 4, 21a6da7fe97f6a5d0decbdb3d96e78b904b4864ce7f1cb22213e3b2c4203e90c",
            "ARW_A1M2_LOSSLESS, 5638, 3768, 0, b48b333b62905bf25a480aa0bdd043722348c2a9c2d1c35468067245e75dea48",
            "ORI_OM5, 5240, 3912, 0, 1e34762df0fdf6c2529e6df7c0a3265436ba211145b858a181ad9400a2a7fda0"
    })
    @DisplayName("the decoded photosites equal those Adobe DNG Converter stores")
    void photosites(RawSamples sample, int width, int height, int shift, String sha256) throws Exception {
        try (var source = MediaSource.of(sample.path())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertNotNull(file.sensor, file.unsupported);
            var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
            file.sensor.reader.read(plane, MediaLimits.DEFAULT);
            var digest = MessageDigest.getInstance("SHA-256");
            var row = ByteBuffer.allocate(width * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (var y = 0; y < height; y++) {
                row.clear();
                for (var x = 0; x < width; x++) {
                    row.putShort((short) (plane.integers[y * plane.width + x] << shift));
                }
                digest.update(row.array());
            }
            assertEquals(sha256, HexFormat.of().formatHex(digest.digest()));
        }
    }
}
