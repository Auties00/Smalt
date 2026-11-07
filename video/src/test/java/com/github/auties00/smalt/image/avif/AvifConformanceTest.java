package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the AOM AVIF conformance files (Microsoft, Netflix, Xiph, Apple and Link-U sets) and
 * compares them with independent references: ffmpeg's AV1 decoder on the item payloads (every
 * sample exact, at the source bit depth), the lossless Netflix originals, and the reference PNGs
 * of the transformation and layered sets.
 */
@DisplayName("AVIF conformance files")
class AvifConformanceTest {
    /**
     * The lossy coding of the Link-U files against the PNG of the picture they were coded from, after the transforms
     * their properties declare: at least 30 dB.
     */
    private static final Tolerance DISPLAYED = Tolerance.psnr(30);

    /** The layer a Xiph file shows against the PNG of that layer: at least 35 dB. */
    private static final Tolerance LAYER = Tolerance.psnr(35);

    private static final String LINK_U = "Link-U/";

    @ParameterizedTest(name = "{0}")
    @DisplayName("decode to the size, layout, depth, alpha and orientation the sets document")
    @CsvSource({
            "Microsoft/Irvine_CA.avif, 480, 640, YUV420, 8, false, NORMAL",
            "Microsoft/kids_720p.avif, 1280, 720, YUV420, 8, false, NORMAL",
            "Microsoft/Mexico.avif, 1920, 1080, YUV420, 8, false, NORMAL",
            "Microsoft/Mexico_YUV444.avif, 960, 540, YUV444, 8, false, NORMAL",
            "Microsoft/bbb_4k.avif, 3840, 2160, YUV420, 8, false, NORMAL",
            "Microsoft/Summer_Nature_4k.avif, 3840, 2160, YUV420, 8, false, NORMAL",
            "Microsoft/Ronda_rotate90.avif, 1920, 1080, YUV420, 8, false, ROTATE_90",
            "Microsoft/Chimera_8bit_cropped_480x256.avif, 480, 256, YUV444, 8, false, NORMAL",
            "Microsoft/Summer_in_Tomsk_720p_5x4_grid.avif, 6400, 2880, YUV420, 8, false, NORMAL",
            "Microsoft/Chimera_10bit_cropped_to_1920x1008.avif, 1920, 1008, YUV420, 10, false, NORMAL",
            "Microsoft/Chimera_10bit_cropped_to_1920x1008_with_HDR_metadata.avif, 1920, 1008, YUV420, 10, false, NORMAL",
            "Microsoft/still_picture.avif, 1280, 720, YUV420, 8, false, NORMAL",
            "Microsoft/reduced_still_picture_header.avif, 1280, 720, YUV420, 8, false, NORMAL",
            "Microsoft/Monochrome.avif, 1280, 720, MONOCHROME, 8, false, NORMAL",
            "Microsoft/Tomsk_with_thumbnails.avif, 1280, 720, YUV420, 8, false, NORMAL",
            "Microsoft/bbb_alpha_inverted.avif, 3840, 2160, YUV420, 8, true, NORMAL",
            "Link-U/fox.profile0.10bpc.yuv420.odd-width.odd-height.avif, 1203, 799, YUV420, 10, false, NORMAL",
            "Link-U/fox.profile1.8bpc.yuv444.odd-height.avif, 1204, 799, YUV444, 8, false, NORMAL",
            "Link-U/fox.profile2.12bpc.yuv422.odd-width.avif, 1203, 800, YUV422, 12, false, NORMAL",
            "Link-U/fox.profile2.12bpc.yuv444.monochrome.avif, 1204, 800, MONOCHROME, 12, false, NORMAL",
            "Link-U/kimono.rotate90.avif, 1024, 722, YUV420, 8, false, ROTATE_90",
            "Link-U/kimono.rotate270.avif, 1024, 722, YUV420, 8, false, ROTATE_270",
            "Link-U/kimono.mirror-horizontal.avif, 722, 1024, YUV420, 8, false, FLIP_HORIZONTAL",
            "Link-U/kimono.mirror-vertical.avif, 722, 1024, YUV420, 8, false, FLIP_VERTICAL",
            "Link-U/kimono.mirror-vertical.rotate270.avif, 1024, 722, YUV420, 8, false, TRANSPOSE",
            "Link-U/kimono.crop.avif, 385, 330, YUV444, 8, false, NORMAL",
            "Link-U/kimono.mirror-vertical.rotate270.crop.avif, 330, 385, YUV444, 8, false, TRANSPOSE",
            "Xiph/quebec_3layer_op2.avif, 360, 182, YUV420, 8, false, NORMAL",
            "Xiph/tiger_3layer_3res.avif, 1216, 832, YUV420, 8, false, NORMAL",
            "Xiph/fruits_2layer_thumbsize.avif, 1296, 864, YUV420, 8, false, NORMAL",
            "Apple/multilayer_examples/animals_00_multilayer_grid_lsel.avif, 2048, 1536, YUV420, 8, false, NORMAL",
            "Apple/edge_case_testing/unknown_properties/free_property.avif, 2048, 1536, YUV420, 8, false, NORMAL",
            "Netflix/avis/alpha_video.avif, 640, 480, YUV420, 8, true, NORMAL",
            "Netflix/avis/Chimera-AV1-10bit-480x270.avif, 480, 270, YUV420, 10, false, NORMAL",
    })
    void summary(String path, int width, int height, ChromaFormat format, int depth, boolean alpha,
                 Orientation orientation) {
        var data = AvifVectors.read(path);
        var info = Images.probe(MediaSource.of(data));
        assertEquals(width, info.width());
        assertEquals(height, info.height());
        assertEquals(alpha, info.hasAlpha());
        assertEquals(orientation, info.orientation());
        var wide = AvifDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().highBitDepth(true).build());
        assertEquals(width, wide.picture().width());
        assertEquals(height, wide.picture().height());
        assertEquals(depth, wide.picture().bitDepth());
        assertEquals(alpha, wide.picture().hasAlpha());
        assertEquals(orientation, wide.orientation());
        var formatOf = wide.picture() instanceof Yuv16Picture deep ? deep.chromaFormat() : ((YuvPicture) wide
                .picture()).chromaFormat();
        assertEquals(format, formatOf);
        var narrow = Images.decode(MediaSource.of(data));
        var expected = wide.picture() instanceof Yuv16Picture deep ? ImageOps.toEightBit(deep) : wide.picture();
        assertPlanesEqual(AvifReference.Planes.of(expected), AvifReference.Planes.of(narrow.picture()), path + " (8-bit)");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("match ffmpeg's AV1 decoding of every item sample for sample")
    @ValueSource(strings = {
            "Microsoft/Irvine_CA.avif",
            "Microsoft/kids_720p.avif",
            "Microsoft/Mexico_YUV444.avif",
            "Microsoft/Ronda_rotate90.avif",
            "Microsoft/Chimera_8bit_cropped_480x256.avif",
            "Microsoft/Chimera_10bit_cropped_to_1920x1008.avif",
            "Microsoft/Chimera_10bit_cropped_to_1920x1008_with_HDR_metadata.avif",
            "Microsoft/Summer_in_Tomsk_720p_5x4_grid.avif",
            "Microsoft/still_picture.avif",
            "Microsoft/reduced_still_picture_header.avif",
            "Microsoft/Monochrome.avif",
            "Microsoft/Tomsk_with_thumbnails.avif",
            "Microsoft/bbb_alpha_inverted.avif",
            "Link-U/fox.profile0.8bpc.yuv420.avif",
            "Link-U/fox.profile0.8bpc.yuv420.odd-width.odd-height.avif",
            "Link-U/fox.profile0.8bpc.yuv420.monochrome.odd-width.avif",
            "Link-U/fox.profile0.10bpc.yuv420.odd-height.avif",
            "Link-U/fox.profile0.10bpc.yuv420.monochrome.odd-width.odd-height.avif",
            "Link-U/fox.profile1.8bpc.yuv444.odd-width.avif",
            "Link-U/fox.profile1.10bpc.yuv444.odd-width.odd-height.avif",
            "Link-U/fox.profile2.8bpc.yuv422.odd-height.avif",
            "Link-U/fox.profile2.10bpc.yuv422.odd-width.odd-height.avif",
            "Link-U/fox.profile2.10bpc.yuv422.monochrome.avif",
            "Link-U/fox.profile2.12bpc.yuv420.odd-width.odd-height.avif",
            "Link-U/fox.profile2.12bpc.yuv422.avif",
            "Link-U/fox.profile2.12bpc.yuv444.odd-height.avif",
            "Link-U/fox.profile2.12bpc.yuv444.monochrome.odd-width.avif",
            "Link-U/hato.profile2.12bpc.yuv422.no-cdef.no-restoration.avif",
            "Link-U/kimono.crop.avif",
            "Link-U/kimono.mirror-vertical.rotate270.crop.avif",
            "Netflix/avif/hdr_cosmos01000_cicp9-16-9_yuv420_limited_qp20.avif",
            "Netflix/avif/hdr_cosmos12920_cicp9-16-9_yuv444_full_qp40.avif",
            "Netflix/avif/sdr_cosmos07296_cicp1-13-6_yuv444_full_qp10.avif",
            "Xiph/tiger_3layer_1res.avif",
            "Xiph/tiger_3layer_3res.avif",
            "Xiph/fruits_2layer_thumbsize.avif",
            "Xiph/abandoned_filmgrain.avif",
            "Apple/multilayer_examples/animals_00_singlelayer.avif",
            "Apple/multilayer_examples/animals_00_multilayer_a1lx.avif",
            "Apple/multilayer_examples/animals_00_multilayer_a1op.avif",
            "Apple/multilayer_examples/animals_00_multilayer_a1op_lsel.avif",
            "Apple/multilayer_examples/animals_00_multilayer_lsel.avif",
            "Apple/multilayer_examples/animals_00_multilayer_grid_a1lx.avif",
            "Apple/multilayer_examples/animals_00_multilayer_grid_lsel.avif",
            "Apple/edge_case_testing/unknown_properties/free_property.avif",
            "Apple/edge_case_testing/unknown_properties/unknown_nonessential_property.avif",
            "Apple/edge_case_testing/non_compliant/truncated_elementary_stream.avif",
    })
    void matchesFfmpeg(String path) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var data = AvifVectors.read(path);
        var expected = AvifReference.primary(data);
        var image = AvifDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().highBitDepth(true).build());
        assertPlanesEqual(expected, AvifReference.Planes.of(image.picture()), path);
    }

    @Nested
    @DisplayName("Link-U transformation set")
    class Transforms {
        @ParameterizedTest(name = "{0}")
        @DisplayName("display upright like the original once oriented")
        @ValueSource(strings = {"kimono.avif", "kimono.rotate90.avif", "kimono.rotate270.avif",
                "kimono.mirror-horizontal.avif", "kimono.mirror-vertical.avif",
                "kimono.mirror-vertical.rotate270.avif"})
        void upright(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var reference = Oracle.decodeVideo(AvifVectors.fetch(LINK_U + "kimono.png"), "rgba", 1);
            assertDisplays(name, 722, 1024, reference);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("display the documented crop")
        @ValueSource(strings = {"kimono.crop.avif", "kimono.mirror-vertical.rotate270.crop.avif"})
        void cropped(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var reference = Oracle.decodeVideo(AvifVectors.fetch(LINK_U + "kimono.crop.png"), "rgba", 1);
            assertDisplays(name, 385, 330, reference);
        }

        private void assertDisplays(String name, int width, int height, byte[] reference) {
            var image = AvifDecoder.decode(MediaSource.of(AvifVectors.read(LINK_U + name)));
            var shown = ImageOps.toRgba(ImageOps.orient(image.picture(), image.orientation()));
            assertEquals(width, shown.width());
            assertEquals(height, shown.height());
            var pixels = Arrays.copyOf(shown.pixels(), width * height * 4);
            DISPLAYED.assertWithin(name + " against the original", reference, pixels);
        }
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("reproduce the Netflix lossless originals exactly (identity matrix, 4:4:4)")
    @ValueSource(strings = {"sdr_cosmos01000_cicp1-13-0_lossless", "sdr_cosmos07296_cicp1-13-0_lossless"})
    void lossless(String name) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var image = AvifDecoder.decode(MediaSource.of(AvifVectors.read("Netflix/avif/" + name + ".avif")));
        var picture = assertInstanceOf(YuvPicture.class, image.picture());
        assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
        var rgb = Oracle.decodeVideo(AvifVectors.fetch("Netflix/avif/" + name + ".png"), "rgb24", 1);
        assertEquals(picture.width() * picture.height() * 3, rgb.length);
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                var at = (y * picture.width() + x) * 3;
                // Identity matrix (H.273 0): Y carries G, Cb carries B, Cr carries R.
                assertEquals(rgb[at + 1], picture.y()[y * picture.yStride() + x], "G at " + x + "," + y);
                assertEquals(rgb[at + 2], picture.u()[y * picture.uvStride() + x], "B at " + x + "," + y);
                assertEquals(rgb[at], picture.v()[y * picture.uvStride() + x], "R at " + x + "," + y);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("show the layer the Xiph references document")
    @CsvSource({
            "quebec_3layer_op2, quebec_3layer_op2_layer0",
            "tiger_3layer_1res, tiger_3layer_1res_layer2",
            "tiger_3layer_3res, tiger_3layer_3res_layer2",
            "fruits_2layer_thumbsize, fruits_2layer_thumbsize_layer1",
            "abandoned_filmgrain, abandoned_filmgrain_layer1",
    })
    void layers(String name, String reference) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var image = AvifDecoder.decode(MediaSource.of(AvifVectors.read("Xiph/" + name + ".avif")));
        var rgba = ImageOps.toRgba(image.picture());
        var expected = Oracle.decodeVideo(AvifVectors.fetch("Xiph/" + reference + ".png"), "rgba", 1);
        assertEquals(expected.length, rgba.width() * rgba.height() * 4);
        LAYER.assertWithin(name + " against " + reference, expected, Arrays.copyOf(rgba.pixels(), expected.length));
    }

    @Test
    @DisplayName("select the base layer with lsel or a1op exactly as decoding the base layer alone")
    void layerSelection() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var data = AvifVectors.read("Apple/multilayer_examples/animals_00_multilayer_a1lx.avif");
        var source = MediaSource.of(data);
        var file = HeifFile.parse(source, MediaLimits.DEFAULT);
        var item = file.primaryItem();
        var payload = file.readItem(source, item, MediaLimits.DEFAULT);
        var config = item.property(HeifProperty.Av1Configuration.class).record();
        var baseSize = (int) item.property(HeifProperty.LayeredImageIndexing.class).layerSizes()[0];
        var base = Arrays.copyOf(payload, baseSize);
        var expected = AvifReference.decode(base, 1024, 768, ChromaFormat.YUV420, 8);
        for (var selector : List.of(
                HeifBuilder.box("lsel", new byte[]{0, 0}),
                HeifBuilder.box("a1op", new byte[]{1}))) {
            var builder = new HeifBuilder();
            builder.primary(builder.item("av01", payload,
                    HeifBuilder.plain(HeifBuilder.ispe(1024, 768)),
                    HeifBuilder.essential(HeifBuilder.av1C(config)),
                    HeifBuilder.essential(selector)));
            var picture = AvifDecoder.decode(MediaSource.of(builder.build())).picture();
            assertPlanesEqual(expected, AvifReference.Planes.of(picture), new String(selector, 4, 4, StandardCharsets.US_ASCII));
        }
    }

    @Test
    @DisplayName("return the EXIF of the Microsoft set and keep HDR metadata")
    void metadata() {
        var image = AvifDecoder.decode(MediaSource.of(AvifVectors.read("Microsoft/Chimera_10bit_cropped_to_1920x1008_with_HDR_metadata.avif")),
                ImageDecoderOptions.builder().highBitDepth(true).build());
        var exif = image.exif().orElseThrow();
        assertTrue(exif[0] == 'M' || exif[0] == 'I');
        var color = ((Yuv16Picture) image.picture()).color();
        assertTrue(color.contentLightLevel().isPresent() || color.masteringDisplay().isPresent());
        var plain = AvifDecoder.decode(MediaSource.of(AvifVectors.read("Link-U/kimono.avif")));
        assertTrue(plain.exif().isEmpty());
        assertTrue(plain.iccProfile().isEmpty());
    }

    @Nested
    @DisplayName("Netflix image sequences")
    class Sequences {
        @Test
        @DisplayName("play every frame of the color and alpha tracks with their timing")
        void alphaVideo() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var path = AvifVectors.fetch("Netflix/avis/alpha_video.avif");
            var data = AvifVectors.read("Netflix/avis/alpha_video.avif");
            var expectedColor = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of(
                    "-v", "error", "-i", path.toString(), "-map", "0:v:2", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
            var expectedAlpha = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of(
                    "-v", "error", "-i", path.toString(), "-map", "0:v:3", "-f", "rawvideo", "-pix_fmt", "gray", "-"));
            var limitedAlpha = Oracle.probe(path).contains("\"color_range\": \"tv\"");
            try (var reader = AvifDecoder.openAnimation(MediaSource.of(data))) {
                assertEquals(640, reader.width());
                assertEquals(480, reader.height());
                assertEquals(48, reader.frameCount());
                var frames = 0;
                var lumaSize = 640 * 480;
                var frameSize = lumaSize * 3 / 2;
                var previous = -1L;
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    assertTrue(frame.timestampMicros() > previous);
                    previous = frame.timestampMicros();
                    var pixels = ((com.github.auties00.smalt.image.RgbaPicture) frame.picture()).pixels();
                    for (var i = 0; i < lumaSize; i++) {
                        var alpha = expectedAlpha[frames * lumaSize + i] & 0xFF;
                        if (limitedAlpha) {
                            alpha = AvifReference.limitedToFull(alpha, 8);
                        }
                        assertEquals(alpha, pixels[i * 4 + 3] & 0xFF, "alpha of frame " + frames);
                    }
                    var color = YuvPicture.builder().size(640, 480).chromaFormat(ChromaFormat.YUV420)
                            .color(colorOf(data)).luma(Arrays.copyOfRange(expectedColor, frames * frameSize, frames * frameSize + lumaSize), 640)
                            .chroma(Arrays.copyOfRange(expectedColor, frames * frameSize + lumaSize, frames * frameSize + lumaSize * 5 / 4),
                                    Arrays.copyOfRange(expectedColor, frames * frameSize + lumaSize * 5 / 4, (frames + 1) * frameSize), 320)
                            .build();
                    var rgba = ImageOps.toRgba(color).pixels();
                    for (var i = 0; i < lumaSize; i++) {
                        assertEquals(rgba[i * 4], pixels[i * 4], "red of frame " + frames);
                    }
                    frames++;
                }
                assertEquals(48, frames);
            }
        }

        @Test
        @DisplayName("decode the 10-bit sequence frame by frame like ffmpeg")
        void tenBit() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var path = AvifVectors.fetch("Netflix/avis/Chimera-AV1-10bit-480x270.avif");
            var data = AvifVectors.read("Netflix/avis/Chimera-AV1-10bit-480x270.avif");
            var first = AvifDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().highBitDepth(true)
                    .build());
            var reference = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of(
                    "-v", "error", "-i", path.toString(), "-map", "0:v:1", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt",
                    "yuv420p10le", "-"));
            var planes = AvifReference.Planes.of(first.picture());
            for (var i = 0; i < planes.planes()[0].length; i++) {
                assertEquals((reference[2 * i] & 0xFF) | ((reference[2 * i + 1] & 0xFF) << 8), planes.planes()[0][i], "luma " + i);
            }
            try (var reader = AvifDecoder.openAnimation(MediaSource.of(data))) {
                var frames = 0;
                while (reader.read() != null) {
                    frames++;
                }
                assertEquals(reader.frameCount(), frames);
                assertEquals(0, reader.loopCount());
            }
        }

        private static ColorInfo colorOf(byte[] data) {
            return ((YuvPicture) AvifDecoder.decode(MediaSource.of(data)).picture()).color();
        }
    }

    static void assertPlanesEqual(AvifReference.Planes expected, AvifReference.Planes actual, String what) {
        assertEquals(expected.width(), actual.width(), what + " width");
        assertEquals(expected.height(), actual.height(), what + " height");
        assertEquals(expected.format(), actual.format(), what + " format");
        assertEquals(expected.depth(), actual.depth(), what + " depth");
        String[] names = {"luma", "cb", "cr", "alpha"};
        for (var plane = 0; plane < 4; plane++) {
            if (expected.planes()[plane] == null) {
                assertNull(actual.planes()[plane], what + " has an unexpected " + names[plane] + " plane");
                continue;
            }
            assertNotNull(actual.planes()[plane], what + " lacks its " + names[plane] + " plane");
            if (!Arrays.equals(expected.planes()[plane], actual.planes()[plane])) {
                var e = expected.planes()[plane];
                var a = actual.planes()[plane];
                var first = Arrays.mismatch(e, a);
                var count = 0;
                for (var i = 0; i < e.length; i++) {
                    if (e[i] != a[i]) {
                        count++;
                    }
                }
                assertArrayEquals(e, a, what + ": " + count + " " + names[plane] + " samples differ, first at " + first);
            }
        }
    }
}
