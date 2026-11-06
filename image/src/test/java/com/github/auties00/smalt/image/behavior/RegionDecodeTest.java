package com.github.auties00.smalt.image.behavior;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.bmp.BmpDecoder;
import com.github.auties00.smalt.image.bmp.BmpEncoder;
import com.github.auties00.smalt.image.gif.GifDecoder;
import com.github.auties00.smalt.image.gif.GifEncoder;
import com.github.auties00.smalt.image.ico.AniDecoder;
import com.github.auties00.smalt.image.ico.IcoDecoder;
import com.github.auties00.smalt.image.ico.IcoEncoder;
import com.github.auties00.smalt.image.ico.IcoEncoderOptions;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.webp.WebpDecoder;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.PictureDump;
import com.github.auties00.smalt.testkit.image.RegionAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers region decoding of the WebP decoder, which decodes the rows down to the region's last one, and of the
 * decoders that decode the whole image and crop it (PNG, GIF, BMP, ICO, ANI, JPEG XL): a region decode equals the
 * crop of the whole decode, through each decoder and through {@link Images}; a lossless WebP region equals
 * {@code dwebp -crop}; and the provider of every format declares whether it decodes regions itself.
 */
@DisplayName("region decoding of WebP and the cropping decoders")
class RegionDecodeTest {
    static RgbaPicture picture(int width, int height, boolean alpha, long seed) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var random = new Random(seed);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 255 / width + random.nextInt(20));
                pixels[at + 1] = (byte) (y * 255 / height + random.nextInt(20));
                pixels[at + 2] = (byte) ((x ^ y) * 3);
                pixels[at + 3] = alpha ? (byte) (x * 5 + y) : (byte) 255;
            }
        }
        return picture;
    }

    static Map<String, byte[]> webp() {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("lossy", WebpEncoder.encode(picture(203, 147, false, 1), WebpEncoderOptions.lossy(80)));
        files.put("lossy alpha", WebpEncoder.encode(picture(170, 130, true, 2), WebpEncoderOptions.lossy(75)));
        files.put("lossless", WebpEncoder.encode(picture(203, 147, false, 3), WebpEncoderOptions.lossless()));
        files.put("lossless alpha", WebpEncoder.encode(picture(150, 99, true, 4), WebpEncoderOptions.lossless()));
        files.put("lossy large", WebpEncoder.encode(picture(1200, 900, false, 5), WebpEncoderOptions.lossy(90)));
        files.put("lossy alpha large", WebpEncoder.encode(picture(1000, 800, true, 6), WebpEncoderOptions.lossy(90)));
        files.put("lossless large", WebpEncoder.encode(picture(700, 500, true, 7), WebpEncoderOptions.lossless()));
        return files;
    }

    static Map<String, byte[]> samples(String directory) {
        var files = new LinkedHashMap<String, byte[]>();
        try (var list = Files.list(Path.of("src", "test", "resources", "samples", directory))) {
            for (var file : list.sorted().toList()) {
                files.put(directory + "/" + file.getFileName(), Files.readAllBytes(file));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return files;
    }

    static Stream<DynamicTest> cases(Map<String, byte[]> files,
                                     BiFunction<MediaSource, ImageDecoderOptions, DecodedImage> decoder,
                                     List<ImageDecoderOptions> optionSets) {
        return files.entrySet().stream().flatMap(entry -> optionSets.stream().map(options -> DynamicTest.dynamicTest(
                entry.getKey() + " " + options, () -> {
                    var data = entry.getValue();
                    var whole = decoder.apply(MediaSource.of(data), options);
                    var regions = RegionAsserts.regions(whole.sourceWidth(), whole.sourceHeight(), 8,
                            entry.getKey().hashCode());
                    RegionAsserts.assertRegions(entry.getKey(), o -> decoder.apply(MediaSource.of(data), o), options,
                            regions);
                    RegionAsserts.assertRegions(entry.getKey() + " through Images",
                            o -> Images.decode(MediaSource.of(data), o), options, regions.subList(0, 4));
                    RegionAsserts.assertOutsideRejected(o -> decoder.apply(MediaSource.of(data), o), options,
                            whole.sourceWidth(), whole.sourceHeight());
                })));
    }

    static List<ImageDecoderOptions> optionSets() {
        return List.of(ImageDecoderOptions.defaults(), ImageDecoderOptions.builder().convertToSrgb(true).build(),
                ImageDecoderOptions.builder().highBitDepth(true).minSize(20, 20).build());
    }

    @TestFactory
    @DisplayName("a region of a WebP image equals the crop of the whole image")
    Stream<DynamicTest> webpRegions() {
        var files = webp();
        files.putAll(samples("webp"));
        return cases(files, WebpDecoder::decode, optionSets());
    }

    @TestFactory
    @DisplayName("a region of a lossless WebP image equals dwebp -crop")
    Stream<DynamicTest> dwebpCrop() {
        var files = webp();
        return Stream.of("lossless", "lossless alpha", "lossless large").map(name -> DynamicTest.dynamicTest(name,
                () -> {
                    Oracle.assume("dwebp");
                    var input = Scratch.directory("webp-region").resolve(name.replace(' ', '-') + ".webp");
                    Files.write(input, files.get(name));
                    var whole = WebpDecoder.decode(MediaSource.of(files.get(name)));
                    for (var region : RegionAsserts.regions(whole.sourceWidth(), whole.sourceHeight(), 3, 5)
                            .subList(1, 8)) {
                        var output = Scratch.directory("webp-region").resolve(name.replace(' ', '-') + ".pam");
                        Oracle.run(Oracle.Tool.DWEBP, Duration.ofMinutes(1), List.of(input.toAbsolutePath()
                                .toString(), "-crop", String.valueOf(region.x()), String.valueOf(region.y()),
                                String.valueOf(region.width()), String.valueOf(region.height()), "-pam", "-o",
                                output.toAbsolutePath().toString()));
                        var pam = Files.readAllBytes(output);
                        var expected = java.util.Arrays.copyOfRange(pam, pam.length - region.width() * region.height()
                                * 4, pam.length);
                        var decoded = (RgbaPicture) WebpDecoder.decode(MediaSource.of(files.get(name)),
                                ImageDecoderOptions.builder().region(region).build()).picture();
                        var actual = new byte[region.width() * region.height() * 4];
                        for (var y = 0; y < region.height(); y++) {
                            System.arraycopy(decoded.pixels(), y * decoded.stride(), actual, y * region.width() * 4,
                                    region.width() * 4);
                        }
                        Tolerance.exact().assertWithin(name + " " + region, expected, actual);
                    }
                }));
    }

    @Test
    @DisplayName("a WebP region decodes only down to its last row, under limits that bound those rows")
    void webpRows() {
        var data = WebpEncoder.encode(picture(800, 1200, false, 8), WebpEncoderOptions.lossy(70));
        var options = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(800 * 300).build())
                .build();
        var region = new Region(100, 50, 200, 100);
        var decoded = WebpDecoder.decode(MediaSource.of(data), options.toBuilder().region(region).build());
        var whole = WebpDecoder.decode(MediaSource.of(data));
        assertEquals(PictureDump.picture(ImageOps.crop(whole.picture(), region)),
                PictureDump.picture(decoded.picture()));
        assertThrows(com.github.auties00.smalt.MediaException.LimitExceeded.class,
                () -> WebpDecoder.decode(MediaSource.of(data), options));
    }

    @TestFactory
    @DisplayName("a region of a PNG, GIF, BMP, ICO, ANI or JPEG XL image equals the crop of the whole image")
    Stream<DynamicTest> cropping() {
        var rgba = picture(77, 51, true, 9);
        var png = new LinkedHashMap<String, byte[]>(samples("png"));
        png.put("rgba", PngEncoder.encode(rgba));
        var gif = new LinkedHashMap<String, byte[]>(samples("gif"));
        gif.put("still", GifEncoder.encode(picture(60, 40, false, 10)));
        var bmp = new LinkedHashMap<String, byte[]>(samples("bmp"));
        bmp.put("rgba", BmpEncoder.encode(rgba));
        var ico = new LinkedHashMap<String, byte[]>();
        ico.put("icon", samples("ico").get("ico/icon.ico"));
        ico.put("sizes", IcoEncoder.encode(List.of(picture(48, 48, true, 11), picture(16, 16, true, 12)),
                IcoEncoderOptions.defaults()));
        var ani = new LinkedHashMap<String, byte[]>();
        ani.put("animated", samples("ico").get("ico/animated.ani"));
        return Stream.of(cases(png, PngDecoder::decode, optionSets()), cases(gif, GifDecoder::decode, optionSets()),
                cases(bmp, BmpDecoder::decode, optionSets()), cases(ico, IcoDecoder::decode, optionSets()),
                cases(ani, AniDecoder::decode, optionSets()), cases(jxl(), JxlDecoder::decode, optionSets()))
                .flatMap(stream -> stream);
    }

    static Map<String, byte[]> jxl() {
        var files = new LinkedHashMap<String, byte[]>();
        var directory = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "image", "jxl",
                "features");
        try (var list = Files.list(directory)) {
            for (var file : list.filter(path -> path.toString().endsWith(".jxl")).sorted().limit(4).toList()) {
                files.put("jxl/" + file.getFileName(), Files.readAllBytes(file));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return files;
    }

    @Test
    @DisplayName("every page of an icon decodes the region")
    void iconPages() {
        var data = IcoEncoder.encode(List.of(picture(48, 48, true, 13), picture(32, 32, true, 14)),
                IcoEncoderOptions.defaults());
        var region = new Region(3, 5, 10, 9);
        try (var whole = IcoDecoder.openPages(MediaSource.of(data), ImageDecoderOptions.defaults());
             var pages = IcoDecoder.openPages(MediaSource.of(data), ImageDecoderOptions.builder().region(region)
                     .build());
             var dispatched = Images.openPages(MediaSource.of(data), ImageDecoderOptions.builder().region(region)
                     .build())) {
            for (var index = 0; index < 2; index++) {
                var expected = PictureDump.picture(ImageOps.crop(whole.read(index).picture(), region));
                assertEquals(expected, PictureDump.picture(pages.read(index).picture()));
                assertEquals(expected, PictureDump.picture(dispatched.read(index).picture()));
            }
        }
    }

    @Test
    @DisplayName("the providers that decode regions natively declare it, and the cropping ones do not")
    void providers() {
        var natives = List.of(ImageFormat.TIFF, ImageFormat.JPEG, ImageFormat.JP2, ImageFormat.WEBP);
        for (var provider : ServiceLoader.load(ImageCodecProvider.class)) {
            for (var format : ImageFormat.values()) {
                if (provider.decodes(format)) {
                    assertEquals(natives.contains(format), provider.decodesRegions(format), provider + " " + format);
                }
            }
        }
    }
}
