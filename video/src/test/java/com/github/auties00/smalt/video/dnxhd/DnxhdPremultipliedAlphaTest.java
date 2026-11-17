package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks premultiplied alpha (the {@code PMA} flag of ST 2019-1 Amendment 1:2023): frames whose
 * video is premultiplied, by a floating-point reference in the test or by the encoder, decode to
 * straight alpha that matches the straight source within the coding error the division amplifies,
 * the decoder divides exactly as the definition says, and frames without the flag are left as
 * coded. No other encoder that sets the flag was found, so the frames come from Smalt's encoder,
 * with the flag set by the encoder or patched into frames coded from premultiplied samples.
 */
@DisplayName("DNxHD premultiplied alpha")
class DnxhdPremultipliedAlphaTest {
    /** Encodes one picture. */
    static byte[] encode(DnxhdEncoderOptions options, int width, int height, Picture picture) {
        return new DnxhdEncoder(width, height, options).encodeFrame(new VideoFrame(picture, 0, 3600, true)).data();
    }

    /** Returns a copy of a frame with the PMA flag of every coding unit set or cleared. */
    static byte[] withPma(byte[] frame, boolean set) {
        var copy = frame.clone();
        var units = (frame[5] & 3) == 2 ? 2 : 1;
        for (var unit = 0; unit < units; unit++) {
            var at = unit * frame.length / units + 7;
            copy[at] = (byte) (set ? copy[at] | 4 : copy[at] & ~4);
        }
        return copy;
    }

    static Picture decode(byte[] frame, boolean highBitDepth) {
        return new DnxhdDecoder(DnxhdDecoderOptions.builder().highBitDepth(highBitDepth).build())
                .decodeFrame(frame, 0, frame.length);
    }

    static Stream<Arguments> yuvCases() {
        return Stream.of(
                Arguments.of("DNxHR HQ 4:2:2", 256, 128, ChromaFormat.YUV422, 8,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ), 34.0),
                Arguments.of("DNxHR HQ 4:2:0", 256, 128, ChromaFormat.YUV420, 8,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ).toBuilder().chromaFormat(ChromaFormat.YUV420)
                                .build(), 34.0),
                Arguments.of("DNxHR HQX 10-bit", 256, 128, ChromaFormat.YUV422, 10,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX), 40.0),
                Arguments.of("DNxHR HQX 12-bit", 256, 128, ChromaFormat.YUV422, 12,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX).toBuilder().bitDepth(12).build(), 40.0),
                Arguments.of("DNxHR 444 10-bit lossless alpha", 256, 128, ChromaFormat.YUV444, 10,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder().alpha(true).losslessAlpha(true).build(), 40.0),
                Arguments.of("DNxHR 444 12-bit", 256, 128, ChromaFormat.YUV444, 12,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder().bitDepth(12).build(), 40.0),
                Arguments.of("DNxHD 1080i two fields", 1920, 1080, ChromaFormat.YUV422, 8,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder().fieldOrder(FieldOrder.TOP_FIRST)
                                .build(), 34.0),
                Arguments.of("DNxHD 720p 10-bit", 1280, 720, ChromaFormat.YUV422, 10,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder().bitDepth(10).build(), 40.0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yuvCases")
    @DisplayName("divides the video of frames coded from premultiplied samples exactly as the definition says")
    void patchedFlag(String label, int width, int height, ChromaFormat format, int depth, DnxhdEncoderOptions options,
                     double minimumPsnr) {
        var source = AlphaPlanes.synthetic(width, height, format, depth);
        var premultiplied = source.premultiplied();
        var coded = encode(options.toBuilder().alpha(true).build(), width, height, premultiplied.toPicture());
        assertEquals(0, coded[7] & 4, "PMA clear as coded");
        var asCoded = AlphaPlanes.of(decode(coded, true));
        var straight = AlphaPlanes.of(decode(withPma(coded, true), true));
        // the flag changes the video only, and exactly as the reference divides the decoded samples
        AlphaPlanes.assertSamplesEqual(asCoded.straightened(), straight, label);
        var codingPsnr = asCoded.psnr(premultiplied, 0.5);
        var straightPsnr = straight.psnr(source, 0.5);
        System.out.printf("%s: premultiplied %.2f dB, straightened %.2f dB%n", label, codingPsnr, straightPsnr);
        // where alpha is at least one half the division at most doubles the coding error (6 dB)
        assertTrue(straightPsnr >= codingPsnr - 6.5, label + ": " + straightPsnr + " against " + codingPsnr);
        assertTrue(straightPsnr >= minimumPsnr, label + ": " + straightPsnr);
        for (var i = 0; i < straight.a().length; i++) {
            if (straight.a()[i] == 0) {
                assertEquals(straight.black(), straight.y()[i], "transparent luma straightens to black");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yuvCases")
    @DisplayName("round-trips straight pictures through the encoder's premultiplication")
    void encoderPremultiplies(String label, int width, int height, ChromaFormat format, int depth,
                              DnxhdEncoderOptions options, double minimumPsnr) {
        var source = AlphaPlanes.synthetic(width, height, format, depth);
        var frame = encode(options.toBuilder().alpha(true).premultipliedAlpha(true).build(), width, height,
                source.toPicture());
        var units = (frame[5] & 3) == 2 ? 2 : 1;
        for (var unit = 0; unit < units; unit++) {
            assertEquals(5, frame[unit * frame.length / units + 7] & 5, label + ": ALP and PMA set in unit " + unit);
        }
        var straight = AlphaPlanes.of(decode(frame, true));
        var asCoded = AlphaPlanes.of(decode(withPma(frame, false), true));
        var premultiplyPsnr = asCoded.psnr(source.premultiplied(), 0.5);
        var straightPsnr = straight.psnr(source, 0.5);
        System.out.printf("%s: coded %.2f dB against the reference premultiplication, straight %.2f dB%n", label,
                premultiplyPsnr, straightPsnr);
        assertTrue(premultiplyPsnr >= minimumPsnr, label + ": " + premultiplyPsnr);
        assertTrue(straightPsnr >= minimumPsnr, label + ": " + straightPsnr);
        var plain = AlphaPlanes.of(decode(encode(options.toBuilder().alpha(true).build(), width, height,
                source.toPicture()), true));
        assertTrue(straightPsnr >= plain.psnr(source, 0.5) - 6.5, label + ": no worse than coding straight samples");
    }

    @Test
    @DisplayName("leaves the video of frames without the flag as coded")
    void flagClear() {
        var source = AlphaPlanes.synthetic(256, 128, ChromaFormat.YUV444, 10);
        var frame = encode(DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder().alpha(true).losslessAlpha(true)
                .build(), 256, 128, source.toPicture());
        var decoded = AlphaPlanes.of(decode(frame, true));
        assertEquals(source.a().length, decoded.a().length);
        var transparent = 0;
        for (var i = 0; i < source.a().length; i++) {
            if (source.a()[i] == 0) {
                transparent++;
                assertEquals(0, decoded.a()[i], "lossless alpha");
                assertTrue(Math.abs(decoded.y()[i] - source.y()[i]) <= 24, "straight luma kept under zero alpha at " + i);
            }
        }
        assertTrue(transparent > 1000, "the source has transparent samples");
        assertTrue(decoded.psnr(source, 0) > 40, "straight video decodes as coded");
    }

    @Test
    @DisplayName("divides 8-bit output of a deep stream in 8 bits when high bit depth is off")
    void eightBitOutput() {
        var source = AlphaPlanes.synthetic(256, 128, ChromaFormat.YUV422, 10);
        var frame = encode(DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX).toBuilder().alpha(true)
                .premultipliedAlpha(true).build(), 256, 128, source.toPicture());
        var asCoded = AlphaPlanes.of(decode(withPma(frame, false), false));
        var straight = AlphaPlanes.of(decode(frame, false));
        assertEquals(8, straight.depth());
        AlphaPlanes.assertSamplesEqual(asCoded.straightened(), straight, "8-bit");
    }

    static Stream<Arguments> rgbCases() {
        return Stream.of(
                Arguments.of("DNxHR 444 RGB lossless alpha", 256, 128, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444)
                        .toBuilder().rgb(true).alpha(true).losslessAlpha(true).build()),
                Arguments.of("DNxHR 444 RGB 12-bit, RGB macroblocks", 256, 128,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder().rgb(true).bitDepth(12)
                                .alternateColorSpace(false).build()),
                Arguments.of("DNxHD 1256 RGB", 1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder()
                        .rgb(true).alpha(true).losslessAlpha(true).build()));
    }

    /** Returns a 16-bit RGBA picture of smooth color over the alpha of {@link AlphaPlanes#synthetic}. */
    static Rgba16Picture rgba(int width, int height) {
        var alpha = AlphaPlanes.synthetic(width, height, ChromaFormat.YUV444, 16).a();
        var picture = Rgba16Picture.allocate(width, height, true);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = (y * width + x) * 4;
                pixels[at] = (short) (32768 + 30000 * Math.sin(x / 15.0));
                pixels[at + 1] = (short) (32768 + 30000 * Math.cos(y / 9.0));
                pixels[at + 2] = (short) (20000 + 400 * ((x + y) % 64));
                pixels[at + 3] = (short) alpha[y * width + x];
            }
        }
        return picture;
    }

    /** Returns the reference premultiplication of 16-bit RGBA. */
    static Rgba16Picture premultiply(Rgba16Picture picture) {
        var result = Rgba16Picture.allocate(picture.width(), picture.height(), true);
        var from = picture.pixels();
        var to = result.pixels();
        for (var i = 0; i < from.length; i += 4) {
            var alpha = from[i + 3] & 0xFFFF;
            for (var c = 0; c < 3; c++) {
                to[i + c] = (short) AlphaPlanes.round((from[i + c] & 0xFFFF) * (double) alpha / 65535);
            }
            to[i + 3] = (short) alpha;
        }
        return result;
    }

    /** Returns the PSNR of the color channels of two 16-bit RGBA buffers over pixels of at least half alpha. */
    static double psnr(short[] expected, short[] actual) {
        var sum = 0.0;
        var count = 0;
        for (var i = 0; i < expected.length; i += 4) {
            if ((expected[i + 3] & 0xFFFF) >= 32768) {
                for (var c = 0; c < 3; c++) {
                    var d = (expected[i + c] & 0xFFFF) - (actual[i + c] & 0xFFFF);
                    sum += (double) d * d;
                    count++;
                }
            }
        }
        return 10 * Math.log10(65535.0 * 65535.0 * count / sum);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rgbCases")
    @DisplayName("divides RGB frames in full range, in 16 bits and in 8")
    void rgb(String label, int width, int height, DnxhdEncoderOptions options) {
        var source = rgba(width, height);
        var coded = encode(options.toBuilder().alpha(true).build(), width, height, premultiply(source));
        var asCoded = assertInstanceOf(Rgba16Picture.class, decode(coded, true)).pixels();
        var flagged = withPma(coded, true);
        var straight = assertInstanceOf(Rgba16Picture.class, decode(flagged, true)).pixels();
        var narrow = assertInstanceOf(RgbaPicture.class, decode(flagged, false)).pixels();
        var narrowAsCoded = assertInstanceOf(RgbaPicture.class, decode(coded, false)).pixels();
        for (var i = 0; i < asCoded.length; i += 4) {
            var alpha = asCoded[i + 3] & 0xFFFF;
            assertEquals(alpha, straight[i + 3] & 0xFFFF, label + ": alpha unchanged");
            for (var c = 0; c < 3; c++) {
                var expected = alpha == 0 ? 0 : (int) Math.min(65535,
                        AlphaPlanes.round((asCoded[i + c] & 0xFFFF) * 65535.0 / alpha));
                assertEquals(expected, straight[i + c] & 0xFFFF, label + ": component " + c + " of pixel " + i / 4);
                assertEquals((expected * 255 + 32767) / 65535, narrow[i + c] & 0xFF, label + ": 8-bit component");
            }
            assertEquals(narrowAsCoded[i + 3], narrow[i + 3], label + ": 8-bit alpha as without the flag");
        }
        var straightPsnr = psnr(source.pixels(), straight);
        var codingPsnr = psnr(premultiply(source).pixels(), asCoded);
        System.out.printf("%s: premultiplied %.2f dB, straightened %.2f dB%n", label, codingPsnr, straightPsnr);
        assertTrue(straightPsnr >= codingPsnr - 6.5 && straightPsnr >= 40, label + ": " + straightPsnr);
        var encoded = assertInstanceOf(Rgba16Picture.class, decode(encode(options.toBuilder().alpha(true)
                .premultipliedAlpha(true).build(), width, height, source), true)).pixels();
        assertTrue(psnr(source.pixels(), encoded) >= 40, label + ": the encoder's premultiplication round-trips");
    }

    @Test
    @DisplayName("codes premultiplied video that ffmpeg, which ignores alpha, decodes as the premultiplied samples")
    void ffmpegSeesPremultipliedVideo(@TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = AlphaPlanes.synthetic(256, 128, ChromaFormat.YUV422, 8);
        var encoder = new DnxhdEncoder(256, 128, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ).toBuilder()
                .alpha(true).premultipliedAlpha(true).frameRate(DnxhdEncoderTest.FRAME_RATE).build());
        var packets = DnxhdEncoderTest.encode(List.of(source.toPicture()), encoder);
        var file = DnxhdEncoderTest.mux(packets, encoder, directory.resolve("premultiplied.mov"));
        Oracle.assertDecodesCleanly(file);
        var ffmpeg = IntraTesting.ffmpegDecode(file, "yuv422p", 1);
        var frame = packets.getFirst().data();
        var ours = IntraTesting.raw(decode(withPma(frame, false), true), false);
        var psnr = IntraTesting.psnr(ours, ffmpeg, false, 255);
        assertTrue(psnr > 40, "ffmpeg against Smalt's premultiplied decode " + psnr);
    }

    @Test
    @DisplayName("rejects premultiplied alpha without alpha stored in band")
    void options() {
        assertThrows(IllegalArgumentException.class, () -> DnxhdEncoderOptions.builder().premultipliedAlpha(true)
                .build());
        assertThrows(IllegalArgumentException.class, () -> DnxhdEncoderOptions.builder().alpha(true)
                .premultipliedAlpha(true).alphaStorage(DnxhdEncoderOptions.AlphaStorage.AVID_UNCOMPRESSED).build());
        var options = DnxhdEncoderOptions.builder().alpha(true).premultipliedAlpha(true).build();
        assertTrue(options.premultipliedAlpha());
        assertEquals(options, options.toBuilder().build());
        assertTrue(options.toString().contains("premultipliedAlpha=true"));
    }
}
