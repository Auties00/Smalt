package com.github.auties00.smalt.image.behavior;

import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.tiff.TiffCompression;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RegionAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Covers region decoding of damaged files by the decoders that decode only what a region needs (JPEG, JPEG 2000,
 * TIFF, WebP): files truncated at several points and files with a run of corrupted bytes. A damaged file that
 * still decodes whole decodes every region as the crop of that whole decode; one that does not either fails the
 * region decode with a {@code MediaException} or, when the damage lies in data the region does not need, decodes
 * the region.
 */
@DisplayName("region decoding of damaged files")
class RegionDamageTest {
    static RgbaPicture picture(int width, int height, boolean alpha, long seed) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var random = new Random(seed);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 255 / width + random.nextInt(24));
                pixels[at + 1] = (byte) (y * 255 / height + random.nextInt(24));
                pixels[at + 2] = (byte) ((x / 5 ^ y / 3) * 11 + random.nextInt(16));
                pixels[at + 3] = alpha ? (byte) (x * 3 + y * 7) : (byte) 255;
            }
        }
        return picture;
    }

    static Map<String, byte[]> files() {
        var files = new LinkedHashMap<String, byte[]>();
        var rgb = picture(211, 157, false, 1);
        var rgba = picture(181, 133, true, 2);
        files.put("jpeg baseline", JpegEncoder.encode(rgb, JpegEncoderOptions.ofQuality(85)));
        files.put("jpeg restart", JpegEncoder.encode(rgb, JpegEncoderOptions.ofQuality(85).toBuilder()
                .restartInterval(4).build()));
        files.put("jpeg progressive", JpegEncoder.encode(rgb, JpegEncoderOptions.ofProgressive(80)));
        files.put("jpeg arithmetic", JpegEncoder.encode(rgb, JpegEncoderOptions.ofQuality(85).toBuilder()
                .arithmetic(true).build()));
        files.put("jp2 tiles", Jp2Encoder.encode(rgb, Jp2EncoderOptions.lossless().toBuilder().tileSize(48, 48)
                .build()));
        files.put("j2k tiles", Jp2Encoder.encode(rgb, Jp2EncoderOptions.lossy(10).toBuilder()
                .format(Jp2Format.CODESTREAM).tileSize(64, 40).build()));
        files.put("jp2 untiled", Jp2Encoder.encode(rgba, Jp2EncoderOptions.lossy(8).toBuilder().noTiles().build()));
        files.put("tiff tiles", TiffEncoder.encode(rgba, TiffEncoderOptions.builder()
                .compression(TiffCompression.DEFLATE).tiles(32, 32).build()));
        files.put("tiff strips", TiffEncoder.encode(rgb, TiffEncoderOptions.builder()
                .compression(TiffCompression.LZW).strips(9).build()));
        files.put("tiff raw strips", TiffEncoder.encode(rgb, TiffEncoderOptions.builder().strips(13).build()));
        files.put("webp lossy alpha", WebpEncoder.encode(rgba, WebpEncoderOptions.lossy(80)));
        files.put("webp lossless", WebpEncoder.encode(rgb, WebpEncoderOptions.lossless()));
        return files;
    }

    @TestFactory
    @DisplayName("a region of a damaged file equals the crop of its whole decode, or fails cleanly")
    Stream<DynamicTest> damagedFiles() {
        return files().entrySet().stream().flatMap(file -> {
            var info = Images.probe(MediaSource.of(file.getValue()));
            var regions = RegionAsserts.damageRegions(info.width(), info.height());
            return RegionAsserts.damaged(file.getValue()).entrySet().stream().map(variant -> DynamicTest.dynamicTest(
                    file.getKey() + " " + variant.getKey(), () -> RegionAsserts.assertDamaged(file.getKey() + " "
                            + variant.getKey(), o -> Images.decode(MediaSource.of(variant.getValue()), o), regions)));
        });
    }
}
