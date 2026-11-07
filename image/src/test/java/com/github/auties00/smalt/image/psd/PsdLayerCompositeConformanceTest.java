package com.github.auties00.smalt.image.psd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the composites Smalt renders from the layers of Photoshop documents with Photoshop's own: the psd-tools
 * test files (MIT, made with Photoshop, pinned in {@code tools/testdata/vectors.tsv}) are decoded as saved and again
 * with their version information resource saying they have no merged image; the documents Photoshop saved without
 * a merged image are compared with psd-tools 1.26.0's {@code composite()} (black-box run, the RGBA files under
 * {@code psd-tools} in this package's test resources), flattened onto white where the document declares no
 * transparency. The saved RGB and gray composites of transparent documents are matted on white, so the rendered
 * colors are matted the same way before the comparison.
 */
@DisplayName("Photoshop layer composite against Photoshop's saved composites")
class PsdLayerCompositeConformanceTest {
    private static final ImageDecoderOptions CHANNELS = ImageDecoderOptions.builder().keepChannels(true)
            .highBitDepth(true).build();

    /** Photoshop composites the dissolving layers of these rectangles (left, top, right, bottom) with its own noise. */
    private static final List<int[]> BLEND_MODE_DISSOLVE = List.of(new int[]{195, 128, 253, 192},
            new int[]{464, 127, 531, 193});

    private static int colors(byte[] file) {
        var mode = (file[24] & 0xFF) << 8 | (file[25] & 0xFF);
        return mode == 3 ? 3 : mode == 4 ? 4 : 1;
    }

    /**
     * Returns the 8-bit color and transparency samples of a decode, {@code colors + 1} per pixel (opaque when the
     * decode has no transparency), the colors matted on white (no ink, for CMYK) when {@code matte} is set.
     */
    static byte[] samples(DecodedImage image, int colors, boolean matte) {
        var output = PsdLayerCompositeTest.Output.of(image, colors);
        var alphaIndex = output.types().indexOf(ChannelType.ALPHA);
        var count = image.picture().width() * image.picture().height();
        var kept = colors + 1;
        var samples = new byte[count * kept];
        for (var pixel = 0; pixel < count; pixel++) {
            var alpha = alphaIndex < 0 ? 1 : Math.clamp(output.at(pixel, alphaIndex), 0, 1);
            for (var channel = 0; channel < colors; channel++) {
                var value = Math.clamp(output.at(pixel, channel), 0, 1);
                if (matte) {
                    value = colors == 4 ? value * alpha : value * alpha + 1 - alpha;
                }
                samples[pixel * kept + channel] = (byte) Math.round(value * 255);
            }
            samples[pixel * kept + colors] = (byte) Math.round(alpha * 255);
        }
        return samples;
    }

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Conformance.fetchListed("psdtools-" + name));
    }

    /**
     * The documents whose saved composite the rendered one is held to, with the tolerance each meets. Photoshop
     * rounds its 8-bit blends down where Smalt rounds once to nearest, so a sample is about a unit away; the
     * gradient fills Photoshop renders again when it saves differ by a unit more, and the non-separable and
     * dissolving blends of the blend mode sheets reach further.
     */
    static Stream<Arguments> saved() {
        var unit = Tolerance.psnr(50).and(Tolerance.maxAbs(1));
        var blends = Tolerance.psnr(50).and(Tolerance.maxAbs(3));
        return Stream.of(
                Arguments.of("16bit5x5.psd", Tolerance.exact()),
                Arguments.of("1layer.psd", Tolerance.exact()),
                Arguments.of("2layers.psd", unit),
                Arguments.of("2layers.psb", Tolerance.exact()),
                Arguments.of("32bit5x5.psd", Tolerance.exact()),
                Arguments.of("background-red-opacity-80.psd", Tolerance.psnr(80).and(Tolerance.maxAbs(2))),
                Arguments.of("blend-modes-color-burn.psd", blends),
                Arguments.of("blend-modes-color-dodge.psd", blends),
                Arguments.of("blend-modes-color.psd", blends),
                Arguments.of("blend-modes-darken.psd", blends),
                Arguments.of("blend-modes-darker-color.psd", blends),
                Arguments.of("blend-modes-difference.psd", blends),
                Arguments.of("blend-modes-divide.psd", blends),
                Arguments.of("blend-modes-exclusion.psd", blends),
                Arguments.of("blend-modes-group-divider-blend-mode.psd", Tolerance.exact()),
                Arguments.of("blend-modes-hard-light.psd", blends),
                Arguments.of("blend-modes-hard-mix.psd", blends),
                Arguments.of("blend-modes-hue.psd", blends),
                Arguments.of("blend-modes-lighten.psd", blends),
                Arguments.of("blend-modes-lighter-color.psd", blends),
                Arguments.of("blend-modes-linear-burn.psd", blends),
                Arguments.of("blend-modes-linear-dodge.psd", blends),
                Arguments.of("blend-modes-linear-light.psd", blends),
                Arguments.of("blend-modes-luminosity.psd", blends),
                Arguments.of("blend-modes-multiply.psd", blends),
                Arguments.of("blend-modes-normal.psd", blends),
                Arguments.of("blend-modes-overlay.psd", blends),
                Arguments.of("blend-modes-pass-through.psd", blends),
                Arguments.of("blend-modes-pin-light.psd", blends),
                Arguments.of("blend-modes-saturation.psd", blends),
                Arguments.of("blend-modes-screen.psd", blends),
                Arguments.of("blend-modes-soft-light.psd", blends),
                Arguments.of("blend-modes-subtract.psd", blends),
                Arguments.of("blend-modes-vivid-light.psd", blends),
                Arguments.of("blend-modes-rgb-blend-modes.psd", Tolerance.psnr(45)),
                Arguments.of("blend-modes-gray-blend-modes.psd", Tolerance.psnr(45)),
                Arguments.of("blend-modes-cmyk-blend-modes.psd", Tolerance.psnr(44)),
                Arguments.of("broken-groups.psd", unit),
                Arguments.of("clipping-mask.psd", Tolerance.psnr(60)),
                Arguments.of("clipping-mask.psb", Tolerance.psnr(60)),
                Arguments.of("clipping-mask3.psd", Tolerance.exact()),
                Arguments.of("clipping-mask4.psd", unit),
                Arguments.of("clipping-mask5.psd", unit),
                Arguments.of("colormodes-4x4_8bit_cmyk.psd", unit),
                Arguments.of("colormodes-4x4_8bit_duotone.psd", unit),
                Arguments.of("colormodes-4x4_8bit_grayscale.psd", unit),
                Arguments.of("colormodes-4x4_8bit_rgb.psd", Tolerance.exact()),
                Arguments.of("colormodes-4x4_8bit_rgba.psd", unit),
                Arguments.of("empty-group.psd", Tolerance.exact()),
                Arguments.of("empty-layer.psd", Tolerance.exact()),
                Arguments.of("gray0.psd", unit),
                Arguments.of("gray1.psd", Tolerance.exact()),
                Arguments.of("group-clipping-group-clipping.psd", Tolerance.exact()),
                Arguments.of("group.psd", Tolerance.exact()),
                Arguments.of("group.psb", Tolerance.exact()),
                Arguments.of("hidden-groups.psd", Tolerance.exact()),
                Arguments.of("hidden-layer.psd", Tolerance.exact()),
                Arguments.of("layers-gradient-fill.psd", unit),
                Arguments.of("layers-group.psd", Tolerance.exact()),
                Arguments.of("layers-pattern-fill.psd", Tolerance.exact()),
                Arguments.of("layers-pixel-layer.psd", unit),
                Arguments.of("layers-shape-layer.psd", unit),
                Arguments.of("layers-smartobject-layer.psd", unit),
                Arguments.of("layers-solid-color-fill.psd", Tolerance.exact()),
                Arguments.of("layers-type-layer.psd", unit),
                Arguments.of("mask-density-layermask.psd", unit),
                Arguments.of("mask-density-vectormask.psd", Tolerance.exact()),
                Arguments.of("mask-disabled.psd", Tolerance.exact()),
                Arguments.of("mask.psd", Tolerance.exact()),
                Arguments.of("masks3.psd", unit),
                Arguments.of("opacity-fill.psd", unit),
                Arguments.of("passthrough_blendmodes1.psd", unit),
                Arguments.of("passthrough_blendmodes2.psd", blends),
                Arguments.of("passthrough_blendmodes3.psd", blends),
                Arguments.of("passthrough_clipping_mask_blendmode.psd", blends),
                Arguments.of("passthrough_fill_blendmode.psd", Tolerance.psnr(35)),
                Arguments.of("semi-transparent-layers.psd", Tolerance.exact()),
                Arguments.of("semi-transparent-layers.psb", Tolerance.exact()),
                Arguments.of("transparency-clip-opacity.psd", unit),
                Arguments.of("transparency-knockout-none-cyanbg.psd", Tolerance.exact()),
                Arguments.of("transparency-knockout-none-nested.psd", Tolerance.exact()),
                Arguments.of("transparency-knockout-none-normal.psd", Tolerance.exact()),
                Arguments.of("transparency-knockout-none-passthrough.psd", Tolerance.exact()),
                Arguments.of("transparency-transparency-group.psd", Tolerance.psnr(60).and(Tolerance.maxAbs(2))),
                Arguments.of("transparentbg-gimp.psd", Tolerance.exact()),
                Arguments.of("vector-mask3.psd", blends));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("saved")
    @DisplayName("renders the composite Photoshop saved")
    void againstSaved(String name, Tolerance tolerance) throws IOException {
        var file = fixture(name);
        var colors = colors(file);
        var reference = PsdDecoder.decode(MediaSource.of(file), CHANNELS);
        var rendered = PsdDecoder.decode(MediaSource.of(PsdFiles.withoutComposite(file)), CHANNELS);
        var referenceAlpha = PsdLayerCompositeTest.Output.of(reference, colors).types().contains(ChannelType.ALPHA);
        var expected = samples(reference, colors, false);
        var actual = samples(rendered, colors, true);
        var width = reference.picture().width();
        var kept = colors + 1;
        for (var pixel = 0; pixel < expected.length / kept; pixel++) {
            var x = pixel % width;
            var y = pixel / width;
            boolean excluded = name.contains("-blend-modes.psd") && BLEND_MODE_DISSOLVE.stream()
                    .anyMatch(rect -> x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3]);
            if (!referenceAlpha) {
                actual[pixel * kept + colors] = expected[pixel * kept + colors];
            }
            if (expected[pixel * kept + colors] == 0 && actual[pixel * kept + colors] == 0) {
                excluded = true;
            }
            if (excluded) {
                System.arraycopy(expected, pixel * kept, actual, pixel * kept, kept);
            }
        }
        tolerance.assertWithin(name, expected, actual);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "advanced-blending.psd|layer effects",
            "blend-and-clipping.psd|layer effects",
            "clipping-mask2.psd|layer effects",
            "layer_effects.psd|layer effects",
            "effects-blend-modes.psd|layer effects",
            "effects-effects-enabled.psd|layer effects",
            "transparency-fill-opacity.psd|layer effects",
            "transparency-knockout-deep-normal.psd|knockout",
            "transparency-knockout-shallow-nested.psd|knockout",
            "transparency-knockout-isolated-groups.psd|knockout",
            "layers-curves.psd|adjustment layers",
            "layers-levels.psd|adjustment layers",
            "layers-hue-saturation.psd|adjustment layers",
            "passthrough_opacity.psd|adjustment layers",
            "2layer_8ele_tblocks.psd|adjustment layers",
            "mask_parameters.psd|feathered masks",
            "mask-parameters-no-real-channel.psd|feathered masks",
            "mask-density-layervectormask.psd|mask densities",
            "vector-mask.psd|without their pixels",
            "vector-mask-disabled.psd|without their pixels",
            "vector-mask2.psd|without their pixels",
            "layers-minimal-gradient-fill.psd|without their pixels",
            "layers-minimal-pattern-fill.psd|without their pixels",
            "layers-minimal-solid-color-fill.psd|without their pixels",
            "32bit.psd|without their pixels",
            "transparentbg.psd|without their pixels",
            "colormodes-4x4_16bit_rgb.psd|without their pixels",
            "colormodes-4x4_16bit_cmyk.psd|without their pixels",
            "colormodes-4x4_32bit_grayscale.psd|without their pixels",
            "note.psd|without their pixels",
            "unicode_pathname.psd|without their pixels",
            "multichannel-pattern-fill.psd|without their pixels",
            "colormodes-4x4_8bit_lab.psd|color mode 9",
            "colormodes-4x4_16bit_multichannel.psd|color mode 7",
            "colormodes-4x4_8bit_index_color.psd|color mode 2",
            "colormodes-4x4_1bit_bitmap.psd|color mode 0"})
    @DisplayName("refuses the documents whose layers need a feature it does not composite")
    void refused(String name, String feature) throws IOException {
        var file = PsdFiles.withoutComposite(fixture(name));
        var exception = assertThrows(MediaException.Unsupported.class,
                () -> PsdDecoder.decode(MediaSource.of(file), CHANNELS));
        assertTrue(exception.getMessage().contains(feature), exception.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"layer-name-emoji.psd", "layers-minimal-pixel-layer.psd", "layers-minimal-type-layer.psd",
            "layers-minimal-smartobject-layer.psd"})
    @DisplayName("renders the documents Photoshop saved without a composite as psd-tools does")
    void againstPsdTools(String name) throws IOException {
        var file = fixture(name);
        var image = PsdDecoder.decode(MediaSource.of(file));
        var actual = RasterTesting.tight(image.picture());
        byte[] expected;
        try (InputStream in = getClass().getResourceAsStream("psd-tools/" + name + ".rgba")) {
            assertNotNull(in, name);
            expected = in.readAllBytes();
        }
        assertEquals(expected.length, actual.length);
        var flattened = !image.picture().hasAlpha();
        for (var at = 0; at < expected.length; at += 4) {
            if (flattened) {
                var alpha = expected[at + 3] & 0xFF;
                for (var channel = 0; channel < 3; channel++) {
                    expected[at + channel] = (byte) Math.round(((expected[at + channel] & 0xFF) * alpha
                            + 255 * (255 - alpha)) / 255.0);
                }
                expected[at + 3] = (byte) 255;
            }
            if (expected[at + 3] == 0 && actual[at + 3] == 0) {
                java.util.Arrays.fill(expected, at, at + 3, (byte) 0);
                java.util.Arrays.fill(actual, at, at + 3, (byte) 0);
            }
        }
        Tolerance.offByOne().assertWithin(name, expected, actual);
    }

    @Test
    @DisplayName("dissolves as much of each layer as Photoshop does")
    void dissolve() throws IOException {
        var file = fixture("blend-modes-dissolve.psd");
        var expected = samples(PsdDecoder.decode(MediaSource.of(file), CHANNELS), 3, false);
        var actual = samples(PsdDecoder.decode(MediaSource.of(PsdFiles.withoutComposite(file)), CHANNELS), 3, true);
        for (var channel = 0; channel < 4; channel++) {
            var want = 0.0;
            var got = 0.0;
            for (var at = channel; at < expected.length; at += 4) {
                want += expected[at] & 0xFF;
                got += actual[at] & 0xFF;
            }
            var pixels = expected.length / 4.0;
            assertEquals(want / pixels, got / pixels, 4, "mean of channel " + channel);
        }
    }
}
