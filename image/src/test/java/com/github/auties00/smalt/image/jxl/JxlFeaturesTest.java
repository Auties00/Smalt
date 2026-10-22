package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jxl.internal.JxlCodestream;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.color.IccStream;
import com.github.auties00.smalt.image.jxl.internal.color.IccSynthesizer;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.AnimationSeeks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the spot color rendering, the conversion of gray XYB images to their embedded gray profile, the preview
 * frame, the reduced-size decodes of progressive files, the gain map and metadata boxes and the {@code jxli} frame
 * index, on the vectors of {@code tools/testdata/jxl-features/make-vectors.py}: cjxl encodes, spliced where libjxl
 * cannot write the feature, with djxl's decodes as references.
 */
class JxlFeaturesTest {
    /** Lossy files go through float transforms that libjxl evaluates with its own approximations. */
    static final Tolerance LOSSY = Tolerance.offByOne();

    /** Lossless files reconstruct the samples exactly. */
    static final Tolerance LOSSLESS = Tolerance.exact();

    static final String DIRECTORY = "/com/github/auties00/smalt/image/jxl/features/";

    static final ImageDecoderOptions DEEP = ImageDecoderOptions.builder().highBitDepth(true).build();

    static Path path(String name) {
        try {
            return Path.of(JxlFeaturesTest.class.getResource(DIRECTORY + name).toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static byte[] resource(String name) {
        try {
            return Files.readAllBytes(path(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static DecodedImage decode(String name, ImageDecoderOptions options) {
        return JxlDecoder.decode(MediaSource.of(resource(name)), options);
    }

    static byte[] rgba(DecodedImage image) {
        return JxlDifferentialTest.tight((RgbaPicture) image.picture());
    }

    static Rgba16Picture png16(String name) {
        return ImageOps.toRgba16(Images.decode(MediaSource.of(resource(name)), DEEP).picture());
    }

    static int to8(double sixteen) {
        return (int) Math.round(Math.min(Math.max(sixteen, 0), 65535) * 255 / 65535);
    }

    /** The RGBA bytes of a 16-bit reference rounded to 8 bits. */
    static byte[] eightBit(Rgba16Picture picture) {
        return boxed(picture, 1);
    }

    /** The RGBA bytes of a 16-bit reference averaged over boxes of {@code factor} samples, rounded to 8 bits. */
    static byte[] boxed(Rgba16Picture picture, int factor) {
        var width = (picture.width() + factor - 1) / factor;
        var height = (picture.height() + factor - 1) / factor;
        var out = new byte[width * height * 4];
        for (var oy = 0; oy < height; oy++) {
            for (var ox = 0; ox < width; ox++) {
                for (var c = 0; c < 4; c++) {
                    var sum = 0.0;
                    var count = 0;
                    for (var y = oy * factor; y < Math.min(oy * factor + factor, picture.height()); y++) {
                        for (var x = ox * factor; x < Math.min(ox * factor + factor, picture.width()); x++) {
                            sum += picture.pixels()[y * picture.stride() + x * 4 + c] & 0xFFFF;
                            count++;
                        }
                    }
                    out[(oy * width + ox) * 4 + c] = (byte) to8(sum / count);
                }
            }
        }
        return out;
    }

    /** The RGBA bytes of the first frame of a djxl float32 decode, clamped and rounded to 8 bits. */
    static byte[] npy(String name) {
        var reference = JxlVectors.Reference.read(path(name));
        var out = new byte[reference.width() * reference.height() * 4];
        for (var y = 0; y < reference.height(); y++) {
            for (var x = 0; x < reference.width(); x++) {
                for (var c = 0; c < 4; c++) {
                    var source = reference.channels() == 1 ? 0 : c;
                    var value = c < 3 || reference.channels() == 4 ? reference.sample(0, x, y, Math.min(source,
                            reference.channels() - 1)) : 1.0f;
                    out[(y * reference.width() + x) * 4 + c] = (byte) to8(value * 65535.0);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("the vectors are the files features.sha256 pins")
    void pinned() throws Exception {
        var lines = Files.readAllLines(path("features.sha256"), StandardCharsets.US_ASCII);
        assertFalse(lines.isEmpty());
        for (var line : lines) {
            var digest = line.substring(0, 64);
            var name = line.substring(66);
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource(name)));
            assertEquals(digest, actual, name);
        }
    }

    @Nested
    @DisplayName("spot colors")
    class Spot {
        @Test
        @DisplayName("an XYB image composites its spot color in linear light as djxl renders it")
        void xybLinear() {
            var expected = eightBit(png16("spot-xyb.png"));
            var actual = rgba(decode("spot-xyb.jxl", ImageDecoderOptions.defaults()));
            LOSSY.assertWithin("spot-xyb", expected, actual);
            assertThrows(AssertionError.class, () -> LOSSY.assertWithin("spot colors hidden",
                    eightBit(png16("spot-xyb.nospot.png")), actual), "the spot color changes the picture");
        }

        @Test
        @DisplayName("blending in the output color space would not match djxl, so the vector checks the linear blend")
        void encodedBlendDiffers() {
            var channels = (Channel8Picture) decode("spot-xyb.jxl", ImageDecoderOptions.builder().keepChannels(true)
                    .build()).picture();
            var count = channels.layout().count();
            var pixels = channels.pixels();
            var mixed = new byte[channels.width() * channels.height() * 4];
            for (var i = 0; i < channels.width() * channels.height(); i++) {
                var weight = 0.75f * (pixels[i * count + 3] & 0xFF) / 255.0f;
                var spot = new float[]{0.2f, 0.6f, 0.9f};
                for (var c = 0; c < 3; c++) {
                    var value = (pixels[i * count + c] & 0xFF) / 255.0f;
                    mixed[i * 4 + c] = (byte) Math.round(255 * (weight * spot[c] + (1 - weight) * value));
                }
                mixed[i * 4 + 3] = (byte) 255;
            }
            assertThrows(AssertionError.class, () -> Tolerance.maxAbs(3).assertWithin("encoded-space blend",
                    eightBit(png16("spot-xyb.png")), mixed));
        }

        @Test
        @DisplayName("channel pictures keep the spot channel apart, the color uncomposited")
        void channelsUncomposited() {
            var image = decode("spot-xyb.jxl", ImageDecoderOptions.builder().keepChannels(true).build());
            var picture = (Channel8Picture) image.picture();
            assertEquals(4, picture.layout().count());
            assertEquals(ChannelType.SPOT, picture.layout().channels().get(3).type());
            assertEquals("spot", picture.layout().channels().get(3).name());
            var reference = eightBit(png16("spot-xyb.nospot.png"));
            var color = new byte[reference.length];
            var pixels = picture.pixels();
            for (var i = 0; i < reference.length / 4; i++) {
                color[i * 4] = pixels[i * 4];
                color[i * 4 + 1] = pixels[i * 4 + 1];
                color[i * 4 + 2] = pixels[i * 4 + 2];
                color[i * 4 + 3] = (byte) 255;
            }
            LOSSY.assertWithin("spot-xyb without spot colors", reference, color);
        }

        @Test
        @DisplayName("decodeFloat keeps the spot channel apart")
        void floatUncomposited() {
            var image = JxlDecoder.decodeFloat(MediaSource.of(resource("spot-xyb.jxl")));
            assertEquals(List.of(JxlChannelType.SPOT_COLOR), image.extraChannelTypes());
            var reference = eightBit(png16("spot-xyb.nospot.png"));
            var color = new byte[reference.length];
            for (var i = 0; i < reference.length / 4; i++) {
                for (var c = 0; c < 3; c++) {
                    color[i * 4 + c] = (byte) to8(image.planes()[c][i] * 65535.0);
                }
                color[i * 4 + 3] = (byte) 255;
            }
            LOSSY.assertWithin("float planes without spot colors", reference, color);
        }

        @Test
        @DisplayName("the animation reader composites spot colors as decode does")
        void animation() {
            try (var reader = JxlDecoder.openAnimation(MediaSource.of(resource("spot-xyb.jxl")))) {
                var frame = reader.read();
                assertArrayEquals(rgba(decode("spot-xyb.jxl", ImageDecoderOptions.defaults())),
                        JxlDifferentialTest.tight((RgbaPicture) frame.picture()));
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"spot-gray-xyb", "spot-gray"})
        @DisplayName("a gray image composites the red component of its spot color as djxl renders it")
        void gray(String name) {
            var codestream = JxlCodestream.open(resource(name + ".jxl"), MediaLimits.DEFAULT);
            assertTrue(codestream.header().isGray());
            assertEquals(name.endsWith("xyb"), codestream.header().xybEncoded());
            var actual = rgba(decode(name + ".jxl", ImageDecoderOptions.defaults()));
            LOSSY.assertWithin(name, eightBit(png16(name + ".png")), actual);
            assertThrows(AssertionError.class, () -> LOSSY.assertWithin("spot colors hidden",
                    eightBit(png16(name + ".nospot.png")), actual), "the spot color changes the picture");
            for (var i = 0; i < actual.length; i += 4) {
                assertEquals(actual[i], actual[i + 1], "gray output at " + i / 4);
                assertEquals(actual[i], actual[i + 2], "gray output at " + i / 4);
            }
            var channels = (Channel8Picture) decode(name + ".jxl", ImageDecoderOptions.builder().keepChannels(true)
                    .build()).picture();
            assertEquals(2, channels.layout().count());
            assertEquals(ChannelType.SPOT, channels.layout().channels().get(1).type());
            var reference = eightBit(png16(name + ".nospot.png"));
            var color = new byte[reference.length];
            for (var i = 0; i < reference.length / 4; i++) {
                color[i * 4] = color[i * 4 + 1] = color[i * 4 + 2] = channels.pixels()[i * 2];
                color[i * 4 + 3] = (byte) 255;
            }
            LOSSY.assertWithin(name + " without spot colors", reference, color);
        }
    }

    @Nested
    @DisplayName("gray XYB images with an ICC profile")
    class GrayIcc {
        @Test
        @DisplayName("decode to their embedded gray profile, matching djxl's linear output through the curve")
        void parametric() {
            var image = decode("gray-icc.jxl", ImageDecoderOptions.defaults());
            var codestream = JxlCodestream.open(resource("gray-icc.jxl"), MediaLimits.DEFAULT);
            assertTrue(codestream.header().xybEncoded() && codestream.header().isGray());
            assertArrayEquals(codestream.icc(), image.iccProfile().orElseThrow());
            assertEquals(ProfileColorSpace.GRAY, image.colorProfile().orElseThrow().colorSpace());
            var linear = png16("gray-icc.linear.png");
            var expected = new byte[linear.width() * linear.height() * 4];
            double g = 1.8, a = 1 / 1.1, b = 0.1 / 1.1, c = 1 / 6.0, d = 0.06;
            var threshold = Math.pow(a * d + b, g);
            for (var i = 0; i < linear.width() * linear.height(); i++) {
                var y = (linear.pixels()[i * 4] & 0xFFFF) / 65535.0;
                var encoded = y >= threshold ? (Math.pow(y, 1 / g) - b) / a : y / c;
                var value = (byte) to8(encoded * 65535);
                expected[i * 4] = expected[i * 4 + 1] = expected[i * 4 + 2] = value;
                expected[i * 4 + 3] = (byte) 255;
            }
            LOSSY.assertWithin("gray ICC", expected, rgba(image));
        }

        @Test
        @DisplayName("the conformance grayscale case, whose profile has a sampled curve, matches the linear reference")
        void sampledCurve() {
            var test = JxlVectors.find("grayscale");
            var data = JxlVectors.read(test.input());
            var image = JxlDecoder.decode(MediaSource.of(data), DEEP);
            var icc = JxlCodestream.open(data, MediaLimits.DEFAULT).icc();
            assertArrayEquals(icc, image.iccProfile().orElseThrow());
            var curve = grayCurve(icc);
            var reference = test.reference();
            var picture = ImageOps.toRgba16(image.picture());
            var expected = new byte[picture.width() * picture.height()];
            var actual = new byte[expected.length];
            for (var y = 0; y < picture.height(); y++) {
                for (var x = 0; x < picture.width(); x++) {
                    var encoded = invert(curve, Math.min(Math.max(reference.sample(0, x, y, 0), 0), 1));
                    expected[y * picture.width() + x] = (byte) to8(encoded * 65535);
                    actual[y * picture.width() + x] = (byte) to8(picture.pixels()[y * picture.stride() + x * 4]
                            & 0xFFFF);
                }
            }
            Tolerance.maxAbs(2).assertWithin("grayscale conformance", expected, actual);
        }

        /** The entries of the {@code kTRC} {@code curv} table of a gray profile, as values from 0 to 1. */
        static double[] grayCurve(byte[] icc) {
            var count = ((icc[128] & 0xFF) << 24) | ((icc[129] & 0xFF) << 16) | ((icc[130] & 0xFF) << 8)
                    | (icc[131] & 0xFF);
            for (var i = 0; i < count; i++) {
                var entry = 132 + 12 * i;
                if (new String(icc, entry, 4, StandardCharsets.US_ASCII).equals("kTRC")) {
                    var offset = u32(icc, entry + 4);
                    assertEquals("curv", new String(icc, offset, 4, StandardCharsets.US_ASCII));
                    var n = u32(icc, offset + 8);
                    var curve = new double[n];
                    for (var k = 0; k < n; k++) {
                        curve[k] = (((icc[offset + 12 + 2 * k] & 0xFF) << 8) | (icc[offset + 13 + 2 * k] & 0xFF))
                                / 65535.0;
                    }
                    return curve;
                }
            }
            throw new AssertionError("no kTRC tag");
        }

        static int u32(byte[] data, int offset) {
            return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8)
                    | (data[offset + 3] & 0xFF);
        }

        /** Finds the input of a monotonic sampled curve that gives an output, interpolating linearly. */
        static double invert(double[] curve, double y) {
            if (y <= curve[0]) {
                return 0;
            }
            for (var k = 1; k < curve.length; k++) {
                if (y <= curve[k]) {
                    var t = curve[k] == curve[k - 1] ? 0 : (y - curve[k - 1]) / (curve[k] - curve[k - 1]);
                    return (k - 1 + t) / (curve.length - 1);
                }
            }
            return 1;
        }
    }

    @Nested
    @DisplayName("preview frames")
    class Preview {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"preview-vardct, 160, 120, 40, 30", "preview-modular, 120, 90, 30, 23"})
        @DisplayName("decode as djxl decodes them, the image after them unchanged")
        void preview(String name, int width, int height, int previewWidth, int previewHeight) {
            var tolerance = name.endsWith("modular") ? LOSSLESS : LOSSY;
            var preview = JxlDecoder.decodePreview(MediaSource.of(resource(name + ".jxl"))).orElseThrow();
            assertEquals(previewWidth, preview.picture().width());
            assertEquals(previewHeight, preview.picture().height());
            assertEquals(width, preview.sourceWidth());
            assertEquals(height, preview.sourceHeight());
            assertEquals(Orientation.NORMAL, preview.orientation());
            tolerance.assertWithin(name + " preview", npy(name + ".preview.npy"), rgba(preview));
            var full = decode(name + ".jxl", ImageDecoderOptions.defaults());
            assertEquals(width, full.picture().width());
            tolerance.assertWithin(name + " image", eightBit(png16(name + ".full.png")), rgba(full));
        }

        @Test
        @DisplayName("is what decode returns under a size hint it satisfies")
        void sizeHint() {
            var preview = rgba(JxlDecoder.decodePreview(MediaSource.of(resource("preview-vardct.jxl"))).orElseThrow());
            var hinted = decode("preview-vardct.jxl", ImageDecoderOptions.builder().minSize(40, 30).build());
            assertEquals(40, hinted.picture().width());
            assertEquals(160, hinted.sourceWidth());
            assertArrayEquals(preview, rgba(hinted));
            var smaller = decode("preview-vardct.jxl", ImageDecoderOptions.builder().minSize(10, 10).build());
            assertEquals(20, smaller.picture().width(), "the LF image is smaller than the preview");
        }

        @Test
        @DisplayName("is passed over when it is smaller than the hint")
        void tooSmall() {
            var image = decode("preview-modular.jxl", ImageDecoderOptions.builder().minSize(31, 23).build());
            assertEquals(120, image.picture().width(), "no smaller rendition of a single-pass modular image");
        }

        @Test
        @DisplayName("is empty for a file without one")
        void absent() {
            assertTrue(JxlDecoder.decodePreview(MediaSource.of(resource("progressive.jxl"))).isEmpty());
        }

        @Test
        @DisplayName("does not change the probe")
        void probe() {
            var info = JxlDecoder.probe(MediaSource.of(resource("preview-vardct.jxl")));
            assertEquals(160, info.width());
            assertEquals(1, info.frameCount());
        }
    }

    @Nested
    @DisplayName("reduced-size decodes")
    class Reduced {
        @ParameterizedTest(name = "hint {0}x{1}")
        @CsvSource({"80, 60, 2", "40, 30, 4", "41, 30, 2", "79, 59, 2"})
        @DisplayName("of a progressive VarDCT file use its first pass, as djxl -s 2 renders it")
        void passes(int minWidth, int minHeight, int factor) {
            var image = decode("progressive.jxl", ImageDecoderOptions.builder().minSize(minWidth, minHeight).build());
            assertEquals(160 / factor, image.picture().width());
            assertEquals(120 / factor, image.picture().height());
            assertEquals(160, image.sourceWidth());
            LOSSY.assertWithin("progressive / " + factor, boxed(png16("progressive.s2.png"), factor), rgba(image));
        }

        @Test
        @DisplayName("by 8 of a VarDCT file is its LF image")
        void lf() {
            var image = decode("progressive.jxl", ImageDecoderOptions.builder().minSize(20, 15).build());
            assertEquals(20, image.picture().width());
            assertEquals(15, image.picture().height());
            // The LF image is the XYB average of each block; the box average of the rendered image differs where the
            // fractal's contrast makes the transfer functions bend inside a block.
            Tolerance.psnr(27).assertWithin("LF image", boxed(png16("progressive.png"), 8), rgba(image));
            Tolerance.psnr(25).assertWithin("LF image vs djxl -s 8", boxed(png16("progressive.s8.png"), 8),
                    rgba(image));
        }

        @ParameterizedTest(name = "hint {0}x{1}")
        @CsvSource({"80, 60, 2", "40, 30, 4", "20, 15, 8"})
        @DisplayName("of a squeezed lossless file average the unsqueezed channels like a box filter")
        void squeezed(int minWidth, int minHeight, int factor) {
            var image = decode("progressive-lossless.jxl", ImageDecoderOptions.builder()
                    .minSize(minWidth, minHeight).build());
            assertEquals(160 / factor, image.picture().width());
            LOSSY.assertWithin("lossless / " + factor, boxed(png16("progressive-lossless.png"), factor), rgba(image));
        }

        @Test
        @DisplayName("fall back to the full image when an extra channel arrives only in the last pass")
        void incompleteExtraChannel() {
            var full = rgba(decode("alpha-unsqueezed.jxl", ImageDecoderOptions.defaults()));
            var hinted = decode("alpha-unsqueezed.jxl", ImageDecoderOptions.builder().minSize(40, 12).build());
            assertEquals(320, hinted.picture().width());
            assertArrayEquals(full, rgba(hinted));
        }

        @Test
        @DisplayName("reduce an image whose squeezed extra channel is complete without the passes")
        void squeezedExtraChannel() {
            var hinted = decode("spot-xyb.jxl", ImageDecoderOptions.builder().minSize(12, 8).build());
            assertEquals(12, hinted.picture().width());
            // decoded without the HF passes: close to the box average of the full image, as the LF image is
            Tolerance.psnr(32).assertWithin("spot-xyb / 8", boxed(png16("spot-xyb.png"), 8), rgba(hinted));
        }

        @Test
        @DisplayName("of an animation reduce its first frame")
        void animation() {
            var image = decode("indexed.jxl", ImageDecoderOptions.builder().minSize(12, 8).build());
            assertEquals(12, image.picture().width());
            assertEquals(8, image.picture().height());
        }

        @Test
        @DisplayName("keep channel pictures reduced")
        void channels() {
            var image = decode("progressive.jxl", ImageDecoderOptions.builder().minSize(80, 60).keepChannels(true)
                    .highBitDepth(true).build());
            assertEquals(80, image.picture().width());
        }
    }

    @Nested
    @DisplayName("gain maps and metadata boxes")
    class Boxes {
        static final byte[] METADATA = "Smalt test gain map metadata, opaque to the decoder"
                .getBytes(StandardCharsets.US_ASCII);

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"gainmap-plain.jxl", "gainmap-brob.jxl"})
        @DisplayName("the jhgm box decodes to its metadata, color encoding and gain map image")
        void gainMap(String name) {
            var gainMap = JxlDecoder.decodeGainMap(MediaSource.of(resource(name))).orElseThrow();
            assertEquals(0, gainMap.version());
            assertArrayEquals(METADATA, gainMap.metadata());
            var linearSrgb = new ColorEncoding(false, ColorEncoding.RGB, ColorEncoding.WHITE_D65, null,
                    ColorEncoding.PRIMARIES_SRGB, null, ColorEncoding.TF_LINEAR, 0, 1);
            assertArrayEquals(IccSynthesizer.synthesize(linearSrgb), gainMap.alternateIcc().orElseThrow());
            assertArrayEquals(resource("gainmap.jxl"), gainMap.codestream());
            assertEquals(64, gainMap.image().picture().width());
            LOSSY.assertWithin("gain map", eightBit(png16("gainmap.png")), rgba(gainMap.image()));
            assertEquals(160, decode(name, ImageDecoderOptions.defaults()).picture().width());
        }

        @Test
        @DisplayName("a stored alternate ICC profile is decompressed")
        void alternateIcc() {
            var donor = resource("gray-icc.jxl");
            var stream = iccStream(donor);
            var icc = JxlCodestream.open(donor, MediaLimits.DEFAULT).icc();
            var gain = resource("gainmap.jxl");
            var content = new ByteArrayOutputStream();
            content.write(1);
            content.writeBytes(new byte[]{0, 3, 'a', 'b', 'c', 0});
            content.writeBytes(new byte[]{(byte) (stream.length >>> 24), (byte) (stream.length >>> 16),
                    (byte) (stream.length >>> 8), (byte) stream.length});
            content.writeBytes(stream);
            content.writeBytes(gain);
            var file = JxlBoxes.container(resource("progressive.jxl"), JxlBoxes.box("jhgm", content.toByteArray()));
            var gainMap = JxlDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            assertEquals(1, gainMap.version());
            assertArrayEquals(new byte[]{'a', 'b', 'c'}, gainMap.metadata());
            assertArrayEquals(icc, gainMap.alternateIcc().orElseThrow());
            var stored = JxlDecoder.decodeGainMap(MediaSource.of(JxlBoxes.container(resource("progressive.jxl"),
                    JxlBoxes.brob("jhgm", content.toByteArray())))).orElseThrow();
            assertArrayEquals(icc, stored.alternateIcc().orElseThrow());
        }

        /** The ICC stream of a codestream, byte aligned as a jhgm box stores it. */
        static byte[] iccStream(byte[] codestream) {
            var reader = new JxlBitReader(codestream, 0, codestream.length);
            ImageHeader.read(reader);
            var start = reader.bitPosition();
            IccStream.read(reader, Long.MAX_VALUE);
            var end = reader.bitPosition();
            var out = new byte[(int) ((end - start + 7) / 8)];
            for (var bit = start; bit < end; bit++) {
                if ((codestream[(int) (bit >>> 3)] >>> (bit & 7) & 1) != 0) {
                    out[(int) ((bit - start) >>> 3)] |= (byte) (1 << ((bit - start) & 7));
                }
            }
            return out;
        }

        @Test
        @DisplayName("files without a jhgm box have no gain map")
        void absent() {
            assertTrue(JxlDecoder.decodeGainMap(MediaSource.of(resource("progressive.jxl"))).isEmpty());
            assertTrue(JxlDecoder.decodeGainMap(MediaSource.of(resource("indexed.jxl"))).isEmpty());
        }

        @Test
        @DisplayName("a truncated jhgm box or a gain map that is not a bare codestream is malformed")
        void malformed() {
            var base = resource("progressive.jxl");
            for (var content : List.of(new byte[]{0, 0, 10, 1, 2}, new byte[]{0, 0, 0, 0, 0, 0, 0, 0},
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3}, new byte[]{0, 0, 0, 2, 0x50, (byte) 0xB4})) {
                var file = JxlBoxes.container(base, JxlBoxes.box("jhgm", content));
                assertThrows(MediaException.Malformed.class, () -> JxlDecoder.decodeGainMap(MediaSource.of(file)));
                assertEquals(160, JxlDecoder.decode(MediaSource.of(file)).picture().width(),
                        "a broken gain map never prevents decoding the image");
            }
        }

        @Test
        @DisplayName("readBoxes lists the boxes in file order, brob ones decompressed")
        void readBoxes() {
            var plain = JxlDecoder.readBoxes(MediaSource.of(resource("gainmap-plain.jxl")));
            assertEquals(List.of("jhgm", "jumb"), plain.stream().map(JxlBox::type).toList());
            assertFalse(plain.getFirst().compressed());
            var compressed = JxlDecoder.readBoxes(MediaSource.of(resource("gainmap-brob.jxl")));
            assertEquals(List.of("jumb", "jhgm"), compressed.stream().map(JxlBox::type).toList());
            assertTrue(compressed.getFirst().compressed());
            assertArrayEquals(plain.get(1).content(), compressed.get(0).content());
            assertArrayEquals(plain.get(0).content(), compressed.get(1).content());
            assertTrue(JxlDecoder.readBoxes(MediaSource.of(resource("progressive.jxl"))).isEmpty());
        }

        @Test
        @DisplayName("brob boxes of any type are decompressed, those of codestream boxes left out")
        void brob() {
            var exif = new byte[]{0, 0, 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            var file = JxlBoxes.container(resource("progressive.jxl"), JxlBoxes.brob("Exif", exif),
                    JxlBoxes.brob("jxlc", new byte[]{1, 2}), JxlBoxes.brob("brob", new byte[]{1}),
                    JxlBoxes.brob("abcd", new byte[70_000]));
            var boxes = JxlDecoder.readBoxes(MediaSource.of(file));
            assertEquals(List.of("Exif", "abcd"), boxes.stream().map(JxlBox::type).toList());
            assertArrayEquals(exif, boxes.getFirst().content());
            assertArrayEquals(new byte[70_000], boxes.get(1).content());
            var image = JxlDecoder.decode(MediaSource.of(file));
            assertArrayEquals(java.util.Arrays.copyOfRange(exif, 4, exif.length), image.exif().orElseThrow());
        }

        @Test
        @DisplayName("a brob box that inflates past the byte limit fails readBoxes and decodeGainMap, not decode")
        void brobBomb() throws IOException {
            byte[] stream;
            try (var input = JxlFeaturesTest.class.getResourceAsStream(
                    "/com/github/auties00/smalt/image/jxl/brotli/runs.bin.q9-w22.br")) {
                stream = input.readAllBytes();
            }
            // 275 bytes of Brotli that inflate to 13048: the file fits the limit, the inflated box does not
            var limited = ImageDecoderOptions.builder().limits(MediaLimits.DEFAULT.toBuilder().maxBytes(10_000).build())
                    .build();
            for (var type : List.of("jumb", "jhgm")) {
                var payload = new ByteArrayOutputStream();
                payload.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
                payload.writeBytes(stream);
                var file = JxlBoxes.container(resource("progressive.jxl"), JxlBoxes.box("brob", payload.toByteArray()));
                assertTrue(file.length < 10_000);
                assertThrows(MediaException.LimitExceeded.class, () -> JxlDecoder.readBoxes(MediaSource.of(file),
                        limited), type);
                if (type.equals("jhgm")) {
                    assertThrows(MediaException.LimitExceeded.class, () -> JxlDecoder.decodeGainMap(
                            MediaSource.of(file), limited));
                }
                assertEquals(13048, JxlDecoder.readBoxes(MediaSource.of(file)).getFirst().content().length);
                assertEquals(160, JxlDecoder.decode(MediaSource.of(file)).picture().width());
            }
        }

        @Test
        @DisplayName("a corrupt brob box fails readBoxes but not the image decode")
        void corruptBrob() {
            var file = JxlBoxes.container(resource("progressive.jxl"), JxlBoxes.box("brob",
                    new byte[]{'j', 'u', 'm', 'b', (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
            assertThrows(MediaException.Malformed.class, () -> JxlDecoder.readBoxes(MediaSource.of(file)));
            assertEquals(160, JxlDecoder.decode(MediaSource.of(file)).picture().width());
        }

        @Test
        @DisplayName("JxlBox and JxlGainMap compare their bytes")
        void values() {
            var box = new JxlBox("jumb", new byte[]{1, 2}, true);
            assertEquals(box, new JxlBox("jumb", new byte[]{1, 2}, true));
            assertNotEquals(box, new JxlBox("jumb", new byte[]{1, 2}, false));
            assertEquals(box.hashCode(), new JxlBox("jumb", new byte[]{1, 2}, true).hashCode());
            assertThrows(IllegalArgumentException.class, () -> new JxlBox("abc", new byte[0], false));
            var gainMap = JxlDecoder.decodeGainMap(MediaSource.of(resource("gainmap-plain.jxl"))).orElseThrow();
            var same = new JxlGainMap(gainMap.version(), gainMap.metadata().clone(),
                    gainMap.alternateIcc().map(byte[]::clone), gainMap.codestream().clone(), gainMap.image());
            assertEquals(gainMap, same);
            assertEquals(gainMap.hashCode(), same.hashCode());
            assertThrows(IllegalArgumentException.class, () -> new JxlGainMap(256, new byte[0],
                    java.util.Optional.empty(), new byte[0], gainMap.image()));
        }
    }

    @Nested
    @DisplayName("seeking")
    class Seek {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"indexed.jxl", "indexed-cropped.jxl"})
        @DisplayName("returns after a seek the frames a linear read returns")
        void matchesLinear(String name) {
            var data = resource(name);
            AnimationSeeks.assertMatchesSequential(() -> JxlDecoder.openAnimation(MediaSource.of(data)), name.length());
        }

        @Test
        @DisplayName("the frames match the source animation")
        void source() {
            try (var source = Images.openAnimation(MediaSource.of(resource("indexed.apng")));
                 var decoded = JxlDecoder.openAnimation(MediaSource.of(resource("indexed.jxl")))) {
                assertEquals(8, decoded.frameCount());
                for (var index = 0; index < 8; index++) {
                    var expected = source.read();
                    var actual = decoded.read();
                    assertEquals(expected.timestampMicros(), actual.timestampMicros());
                    Tolerance.psnr(27).assertWithin("frame " + index,
                            JxlDifferentialTest.tight((RgbaPicture) expected.picture()),
                            JxlDifferentialTest.tight((RgbaPicture) actual.picture()));
                }
            }
        }
    }
}
