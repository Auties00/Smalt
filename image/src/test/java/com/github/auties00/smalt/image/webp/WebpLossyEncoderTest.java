package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers lossy encoding: container layout with and without alpha, decodability by dwebp, the
 * rate-distortion trade-off against cwebp at equal size, the target-size search and Y'CbCr
 * input. Every test needs the VP8 encoder and is skipped until the VP8 workstream is merged.
 */
class WebpLossyEncoderTest {
    static RgbaPicture opaque() {
        return WebpLosslessEncoderTest.Source.OPAQUE.picture();
    }

    static byte[] rgbOnly(byte[] rgba) {
        var rgb = new byte[rgba.length / 4 * 3];
        for (int i = 0, j = 0; i < rgba.length; i += 4, j += 3) {
            rgb[j] = rgba[i];
            rgb[j + 1] = rgba[i + 1];
            rgb[j + 2] = rgba[i + 2];
        }
        return rgb;
    }

    static byte[] cwebp(WebpLosslessEncoderTest.Source source, String name, List<String> flags) {
        Oracle.assume(Oracle.Tool.CWEBP);
        var out = WebpTestSupport.scratchPath(name);
        var arguments = new java.util.ArrayList<>(List.of("-quiet"));
        arguments.addAll(flags);
        arguments.addAll(List.of(source.png().toString(), "-o", out.toString()));
        Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(1), arguments);
        return WebpTestSupport.readFile(out);
    }

    @ParameterizedTest(name = "quality {0}")
    @ValueSource(ints = {0, 50, 75, 100})
    @DisplayName("writes a simple VP8 file matching cwebp's size and quality at equal settings")
    void opaqueFile(int quality) {
        var picture = opaque();
        var file = WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture, WebpEncoderOptions.lossy(quality)));
        var chunks = WebpStructure.validate(file);
        assertEquals(List.of("VP8 "), chunks.stream().map(WebpStructure.RawChunk::tag).toList());
        assertEquals(0, chunks.getFirst().size() & 1, "VP8 payload padded to even length like libwebp");
        var theirs = cwebp(WebpLosslessEncoderTest.Source.OPAQUE, "lossy-cwebp-q" + quality + ".webp",
                List.of("-q", Integer.toString(quality)));
        var source = rgbOnly(WebpTestSupport.tightRgba(picture));
        var path = WebpTestSupport.scratch("lossy-q" + quality + ".webp", file);
        Oracle.assertDecodesCleanly(path);
        var ours = Quality.psnr(source, rgbOnly(WebpTestSupport.dwebpRgba(path)));
        var reference = Quality.psnr(source, rgbOnly(WebpTestSupport.dwebpRgba(WebpTestSupport.scratch("lossy-ref-q" + quality + ".webp", theirs))));
        assertTrue(file.length <= theirs.length * 1.10, "ours " + file.length + " vs cwebp " + theirs.length);
        assertTrue(ours >= reference - 0.5, "ours " + ours + " dB vs cwebp " + reference + " dB");
    }

    @Test
    @DisplayName("writes VP8X, ALPH and VP8 for transparent pictures, with cwebp's ALPH chunk")
    void alphaFile() {
        var picture = WebpLosslessEncoderTest.Source.ALPHA.picture();
        var file = WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture, WebpEncoderOptions.lossy(80)));
        var chunks = WebpStructure.validate(file);
        assertEquals(List.of("VP8X", "ALPH", "VP8 "), chunks.stream().map(WebpStructure.RawChunk::tag).toList());
        assertEquals(0x10, file[20] & 0xFF);
        var theirs = cwebp(WebpLosslessEncoderTest.Source.ALPHA, "lossy-alpha-cwebp.webp", List.of("-q", "80"));
        assertArrayEquals(WebpAlphaTest.chunk(theirs, "ALPH"), WebpAlphaTest.chunk(file, "ALPH"));
        var decoded = WebpTestSupport.dwebpRgba(WebpTestSupport.scratch("lossy-alpha.webp", file));
        var source = WebpTestSupport.tightRgba(picture);
        for (var i = 3; i < source.length; i += 4) {
            assertEquals(source[i], decoded[i], "alpha is lossless at alpha quality 100");
        }
    }

    @Test
    @DisplayName("stays within 1 dB of cwebp at equal size")
    void rateDistortion() {
        Oracle.assume(Oracle.Tool.CWEBP);
        var picture = opaque();
        var ours = WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture, WebpEncoderOptions.lossy(75)));
        var oursPath = WebpTestSupport.scratch("rd-ours.webp", ours);
        var theirsPath = WebpTestSupport.scratchPath("rd-cwebp.webp");
        Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(1), List.of("-quiet", "-size", Integer.toString(ours.length),
                WebpLosslessEncoderTest.Source.OPAQUE.png().toString(), "-o", theirsPath.toString()));
        var source = rgbOnly(WebpTestSupport.tightRgba(picture));
        var oursPsnr = Quality.psnr(source, rgbOnly(WebpTestSupport.dwebpRgba(oursPath)));
        var theirsPsnr = Quality.psnr(source, rgbOnly(WebpTestSupport.dwebpRgba(theirsPath)));
        var theirsSize = WebpTestSupport.readFile(theirsPath).length;
        assertTrue(oursPsnr >= theirsPsnr - 1.0 || ours.length < theirsSize * 0.9,
                "ours " + ours.length + " B " + oursPsnr + " dB vs cwebp " + theirsSize + " B " + theirsPsnr + " dB");
    }

    @ParameterizedTest(name = "target {0} bytes")
    @ValueSource(ints = {1500, 3000, 6000})
    @DisplayName("fits the target size when a quality reaches it")
    void targetSize(int target) {
        var picture = opaque();
        var file = WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture, WebpEncoderOptions.lossy(95).toBuilder()
                .targetSize(target).build()));
        var floor = WebpEncoder.encode(picture, WebpEncoderOptions.lossy(0));
        if (floor.length <= target) {
            assertTrue(file.length <= target, file.length + " > " + target);
        } else {
            assertTrue(file.length <= floor.length);
        }
    }

    @ParameterizedTest(name = "target {0} bytes")
    @ValueSource(ints = {2000, 3000, 6000})
    @DisplayName("counts the embedded color profile against the target size")
    void targetSizeWithProfile(int target) {
        var picture = opaque();
        var options = WebpEncoderOptions.lossy(95).toBuilder().colorProfile(ColorProfile.displayP3()).build();
        var file = WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture, options.toBuilder().targetSize(target).build()));
        var floor = WebpEncoder.encode(picture, options.toBuilder().quality(0).build());
        assertTrue(WebpContainer.iccProfile(file).isPresent());
        if (floor.length <= target) {
            assertTrue(file.length <= target, file.length + " > " + target);
        } else {
            assertTrue(file.length <= floor.length);
        }
    }

    @Test
    @DisplayName("returns the smallest attempt when no quality fits the target")
    void unreachableTarget() {
        var picture = opaque();
        var file = WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture, WebpEncoderOptions.lossy(80).toBuilder()
                .targetSize(10).build()));
        var floor = WebpEncoder.encode(picture, WebpEncoderOptions.lossy(0));
        assertTrue(file.length <= floor.length);
    }

    @Test
    @DisplayName("codes limited-range BT.601 4:2:0 pictures directly")
    void yuvInput() {
        var picture = YuvPicture.allocate(48, 32, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var y = 0; y < 32; y++) {
            for (var x = 0; x < 48; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) (16 + x * 4);
            }
        }
        java.util.Arrays.fill(picture.u(), (byte) 100);
        java.util.Arrays.fill(picture.v(), (byte) 150);
        var file = WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture, WebpEncoderOptions.lossy(90).toBuilder()
                .extendedFormat(true).build()));
        var chunks = WebpStructure.validate(file);
        assertEquals(List.of("VP8X", "VP8 "), chunks.stream().map(WebpStructure.RawChunk::tag).toList());
        WebpTestSupport.dwebpRgba(WebpTestSupport.scratch("lossy-yuv.webp", file));
    }

    @Test
    @DisplayName("codes other Y'CbCr layouts as the RGBA they convert to")
    void otherYuvLayout() {
        var picture = YuvPicture.allocate(40, 24, ChromaFormat.YUV444, false, ColorInfo.BT709_LIMITED);
        for (var y = 0; y < 24; y++) {
            for (var x = 0; x < 40; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) (16 + x * 5);
                picture.u()[y * picture.uvStride() + x] = (byte) (64 + y * 4);
                picture.v()[y * picture.uvStride() + x] = (byte) (200 - x * 2);
            }
        }
        var options = WebpEncoderOptions.lossy(80);
        var file = WebpTestSupport.withVp8(() -> WebpEncoder.encode(picture, options));
        assertArrayEquals(WebpTestSupport.withVp8(() -> WebpEncoder.encode(ImageOps.toRgba(picture), options)), file);
    }
}
