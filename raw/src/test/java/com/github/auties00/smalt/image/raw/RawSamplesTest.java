package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the CC0 sample files of every supported format and checks each description, the size of
 * the rendering, and the rendering against the camera's own embedded preview (its image area, when it
 * letterboxes a wider image), as stored or both turned upright by their orientations: their luminance on a
 * coarse grid must correlate, which catches wrong CFA layouts, bit packings, crops, levels and orientations.
 */
class RawSamplesTest {
    private static final int GRID_WIDTH = 48;

    private static final int GRID_HEIGHT = 32;

    @ParameterizedTest(name = "{0}")
    @DisplayName("describes, renders and recognizes the sample files")
    @CsvSource({
            "ADC_5D3_UNCOMPRESSED, DNG, 5760, 3840, RGGB, 16, true, Canon EOS 5D Mark III, 200",
            "ADC_5D3_LOSSLESS, DNG, 5760, 3840, RGGB, 16, true, Canon EOS 5D Mark III, 200",
            "ADC_5D3_LOSSY, DNG, 5760, 3840, -, 8, true, Canon EOS 5D Mark III, 200",
            "ADC_5D3_LINEAR_LOSSLESS, DNG, 3960, 2640, -, 16, true, Canon EOS 5D Mark III, 200",
            "ADC_5D3_LINEAR_LOSSY, DNG, 3960, 2640, -, 8, true, Canon EOS 5D Mark III, 200",
            "DNG_5D3_14BIT_SMALL, DNG, 1920, 818, RGGB, 14, true, Canon EOS 5D Mark III, 200",
            "DNG_5D3_16BIT_SMALL, DNG, 1904, 1064, RGGB, 16, true, Canon EOS 5D Mark III, 100",
            "DNG_7D2_JPEG_XL, DNG, 5472, 3648, RGGB, 16, true, Canon EOS 7D Mark II, 4000",
            "CR2_5D3, CR2, 5760, 3840, RGGB, 14, true, Canon EOS 5D Mark III, 200",
            "DNG_PIXEL_2_XL, DNG, 4016, 3008, BGGR, 16, true, Pixel 2 XL, 51",
            "DNG_LEICA_M9, DNG, 5212, 3468, RGGB, 8, true, M9 Digital Camera, 640",
            "DNG_X100S, DNG, 4896, 3264, GGBGGR/GGRGGB/RBGBRG/GGRGGB/GGBGGR/BRGRBG, 16, true, X100S, 200",
            "DNG_PENTAX_KX, DNG, 4288, 2848, BGGR, 12, true, PENTAX K-x, 2000",
            "PEF_PENTAX_KX, PEF, 4288, 2848, BGGR, 12, true, PENTAX K-x, 2000",
            "DNG_IPHONE_7_PLUS, DNG, 4032, 3024, RGGB, 16, true, iPhone 7 Plus, 20",
            "NEF_D7000_LOSSLESS_14, NEF, 4928, 3264, RGGB, 14, true, NIKON D7000, 100",
            "NEF_D7000_LOSSY_12, NEF, 4928, 3264, RGGB, 12, true, NIKON D7000, 100",
            "NEF_D5100_UNCOMPRESSED_14, NEF, 4928, 3264, RGGB, 14, true, NIKON D5100, 500",
            "NEF_D70S_LOSSY_12, NEF, 3008, 2000, BGGR, 12, true, NIKON D70s, 0",
            "NEF_D300S_LOSSLESS_12, NEF, 4288, 2848, RGGB, 12, true, NIKON D300S, 0",
            "NEF_D300S_LOSSLESS_14, NEF, 4288, 2848, RGGB, 14, true, NIKON D300S, 0",
            "NEF_D5600_LOSSY_12_SPLIT, NEF, 6000, 4000, RGGB, 12, true, NIKON D5600, 12800",
            "NEF_D7000_LOSSY_14_SPLIT, NEF, 4928, 3264, RGGB, 14, true, NIKON D7000, 0",
            "NEF_E5700, NEF, 2576, 1924, GMYC, 12, false, E5700, 0",
            "NEF_E8400, NEF, 3280, 2454, BGGR, 12, true, E8400, 0",
            "NEF_E8800, NEF, 3280, 2454, BGGR, 12, true, E8800, 0",
            "ARW_NEX6_CRAW_12, ARW, 4912, 3264, RGGB, 14, true, NEX-6, 200",
            "ARW_A7M2_UNCOMPRESSED_14, ARW, 6000, 4000, RGGB, 14, true, ILCE-7M2, 100",
            "SRW_NX1000, SRW, 5472, 3648, GRBG, 12, true, NX1000, 400",
            "ERF_R_D1, ERF, 3008, 2000, RGGB, 12, true, R-D1, 400",
            "GPR_HERO7_BLACK_16BIT_4_3_2908, DNG, 4000, 3000, RGGB, 14, true, HERO7 Black, 100",
            "RW2_DMC_FZ8_4_3, RW2, 3072, 2304, BGGR, 12, true, DMC-FZ8, 100",
            "RW2_DMC_LX1_16_9, RW2, 3840, 2160, RGGB, 12, true, DMC-LX1, 100",
            "FFF_H3D_16BIT_4_3, FFF, 7216, 5412, RGGB, 16, true, Hasselblad H3D, 50",
            "FFF_H4D_40_16BIT_4_3, FFF, 7304, 5478, RGGB, 16, true, Hasselblad H4D-40, 100",
            "MOS_APTUS_22_16BIT_4_3_3196, MOS, 4008, 5344, RGGB, 16, true, Aptus 22, 0",
            "MOS_AFI_II_12_16BIT_4_3, MOS, 10320, 7752, RGGB, 16, true, AFi-II 12, 0",
            "DCR_EASYSHARE_Z981, DCR, 4288, 3216, GRBG, 12, true, KODAK EasyShare Z981 Digital Camera, 100",
            "DCR_EASYSHARE_Z990_NAN, DCR, 4000, 3000, GRBG, 12, true, KODAK EasyShare Z990 Digital Camera, 125",
            "DCR_P880_NAN, DCR, 3264, 2448, GRBG, 12, true, KODAK P880 ZOOM DIGITAL CAMERA, 50",
            "DCR_DCS520C_12BIT_3_2, DCR, 1728, 1152, GRBG, 12, true, DCS520C, 0",
            "DCR_DCS760C_12BIT_3_2, DCR, 3032, 2008, GRBG, 12, true, DCS760C, 0",
            "DCR_DC120_8BIT_4_3, DCR, 0, 0, -, 0, false, Kodak DC120 ZOOM Digital Camera, 0",
            "DNG_RICOH_GR, DNG, 4928, 3264, RGGB, 12, true, GR, 100",
            "DNG_SAMSUNG_GX20, DNG, 4672, 3104, BGGR, 12, true, SAMSUNG GX20, 400",
            "ORF_EM10, ORF, 4608, 3456, RGGB, 12, true, E-M10, 200",
            "ORF_E410, ORF, 3648, 2736, RGGB, 12, true, E-410, 400",
            "ORF_E1, ORF, 2560, 1920, GRBG, 12, true, E-1, 100",
            "ORF_XZ2, ORF, 3968, 2976, RGGB, 12, true, XZ-2, 100",
            "RW2_GF6, RW2, 4592, 3448, GRBG, 12, true, DMC-GF6, 160",
            "RW2_GH5S, RW2, 3680, 2760, RGGB, 14, true, DC-GH5S, 800",
            "RW2_S1, RW2, 6000, 4000, RGGB, 14, true, DC-S1, 800",
            "RW2_S5M2, RW2, 6000, 4000, RGGB, 14, true, DC-S5M2, 100",
            "RW2_G9M2, RW2, 5776, 4336, RGGB, 16, true, DC-G9M2, 100",
            "PEF_IST_D, PEF, 3008, 2008, RGGB, 12, true, PENTAX *ist D, 200",
            "PEF_IST_DL, PEF, 3008, 2000, RGGB, 12, true, PENTAX *ist DL, 200",
            "PEF_K5, PEF, 4928, 3264, BGGR, 14, true, PENTAX K-5, 160",
            "PEF_KS1, PEF, 5472, 3648, RGGB, 12, true, PENTAX K-S1, 100",
            "PEF_K3III_MONOCHROME, PEF, 6192, 4128, -, 14, true, PENTAX K-3 Mark III Monochrome, 200",
            "CR2_7D_SRAW, CR2, 2592, 1728, -, 16, true, Canon EOS 7D, 1250",
            "CR2_7D_MRAW, CR2, 3888, 2592, -, 16, true, Canon EOS 7D, 1250",
            "CR2_5D3_SRAW, CR2, 2880, 1920, -, 16, true, Canon EOS 5D Mark III, 200",
            "CR2_40D_SRAW, CR2, 1944, 1296, -, 16, true, Canon EOS 40D, 100",
            "RAF_XA1, RAF, 4896, 3264, RGGB, 12, true, X-A1, 1000",
            "RAF_XE2, RAF, 4896, 3264, GGBGGR/GGRGGB/RBGBRG/GGRGGB/GGBGGR/BRGRBG, 14, true, X-E2, 200",
            "RAF_XT2_COMPRESSED, RAF, 6000, 4000, GRGGBG/GBGGRG/RGRBGB/GBGGRG/GRGGBG/BGBRGR, 14, true, X-T2, 400",
            "RAF_XT4_LOSSLESS, RAF, 6240, 4160, GGBGGR/BRGRBG/GGBGGR/GGRGGB/RBGBRG/GGRGGB, 14, true, X-T4, 160",
            "RAF_GFX50SII_LOSSLESS, RAF, 8256, 6192, RGGB, 14, true, GFX50S II, 100",
            "RAF_XE5_LOSSY, RAF, 7728, 5152, GGBGGR/GGRGGB/RBGBRG/GGRGGB/GGBGGR/BRGRBG, 14, true, X-E5, 250",
            "RAF_GFX50SII_LOSSY, RAF, 8256, 6192, RGGB, 14, true, GFX50S II, 100",
            "RAF_S3PRO, RAF, 4256, 2848, RGBG/BGRG, 14, true, FinePix S3Pro, 100",
            "RAF_S5500, RAF, 2272, 1704, RGGB, 14, true, FinePix S5500, 100",
            "RAF_S6000FD, RAF, 4048, 3036, BGRG/RGBG, 14, true, FinePix S6000fd, 3200",
            "RAF_S100FS, RAF, 5440, 4080, RGBG/BGRG, 14, true, FinePix S100FS, 100",
            "RAF_S200EXR, RAF, 2808, 2108, RGGB, 14, true, FinePix S200EXR, 100",
            "RAF_S200EXR_HR, RAF, 5616, 4216, RRGG/GGBB, 14, true, FinePix S200EXR, 100",
            "RAF_S2PRO, RAF, 2848, 4256, RGBG/BGRG, 12, true, FinePixS2Pro, 100",
            "RAF_S5PRO, RAF, 4256, 2848, RGBG/BGRG, 14, true, FinePix S5Pro, 100",
            "RAF_S5000, RAF, 2120, 2816, RGBG/BGRG, 14, true, FinePix S5000, 200",
            "RAF_S5200, RAF, 2736, 3648, RGBG/BGRG, 14, true, FinePix S5200, 64",
            "RAF_S6500FD, RAF, 4048, 3036, BGRG/RGBG, 14, true, FinePix S6500fd, 100",
            "RAF_S7000, RAF, 3040, 4048, RGBG/BGRG, 14, true, FinePix S7000, 200",
            "RAF_S9500, RAF, 3648, 4864, RGBG/BGRG, 14, true, FinePix S9500, 80",
            "RAF_S9600, RAF, 3648, 4864, RGBG/BGRG, 14, true, FinePix S9600, 200",
            "RAF_F770EXR, RAF, 3248, 2436, RGGB, 12, true, FinePix F770EXR, 100",
            "RAF_X10, RAF, 5632, 4232, BBGG/GGRR, 12, true, X10, 200",
            "RAF_X10_SN, RAF, 2816, 2116, BGGR, 12, true, X10, 100",
            "RAF_S1, RAF, 4624, 3470, RGGB, 12, true, FinePix S1, 200",
            "RAF_SL1000, RAF, 4624, 3470, RGGB, 12, true, FinePix SL1000, 100",
            "RAF_DBP_GX680, RAF, 5408, 7648, RGBG/BGRG, 12, true, DBP for GX680, 100",
            "RAF_F700, RAF, 2128, 2832, RGBG/BGRG, 14, true, FinePix F700, 200",
            "RAF_E550, RAF, 3040, 4048, RGBG/BGRG, 14, true, FinePix E550, 200",
            "RAF_E900, RAF, 3648, 4864, RGBG/BGRG, 14, true, FinePix E900, 80",
            "RAF_S5600, RAF, 2736, 3648, RGBG/BGRG, 14, true, FinePix S5600, 64",
            "RAF_HS10, RAF, 3664, 2742, BGGR, 12, true, FinePix HS10 HS11, 200",
            "RAF_F600EXR, RAF, 3248, 2436, RGGB, 12, true, FinePix F600EXR, 100",
            "RAF_XT4_LOSSY, RAF, 6240, 4160, GGBGGR/BRGRBG/GGBGGR/GGRGGB/RBGBRG/GGRGGB, 14, true, X-T4, 160",
            "RAF_XE5_LOSSLESS, RAF, 7728, 5152, GGBGGR/GGRGGB/RBGBRG/GGRGGB/GGBGGR/BRGRBG, 14, true, X-E5, 250",
            "RAF_GFX50S_COMPRESSED, RAF, 8256, 6192, RGGB, 14, true, GFX 50S, 100",
            "CR3_250D, CR3, 6000, 4000, RGGB, 14, true, Canon EOS 250D, 100",
            "CR3_EOS_250D_C_RAW, CR3, 6000, 4000, RGGB, 14, true, Canon EOS 250D, 100",
            "CR3_EOS_90D, CR3, 6960, 4640, RGGB, 14, true, Canon EOS 90D, 100",
            "CR3_EOS_R, CR3, 4176, 2784, RGGB, 14, true, Canon EOS R, 100",
            "CR3_EOS_RP, CR3, 3888, 2592, RGGB, 14, true, Canon EOS RP, 100",
            "CR3_EOS_R5, CR3, 5088, 3392, RGGB, 14, true, Canon EOS R5, 100",
            "CR3_EOS_1D_X_MARK_III, CR3, 5472, 3648, RGGB, 14, true, Canon EOS-1D X Mark III, 100",
            "CR3_EOS_R5_RAW_1_6_CROP_DUAL_PIXEL, CR3, 5088, 3392, RGGB, 14, true, Canon EOS R5, 100",
            "CR3_EOS_R6_MARK_III_RAW, CR3, 4320, 2880, RGGB, 14, true, Canon EOS R6 Mark III, 100",
            "CR3_POWERSHOT_V1_CRAW, CR3, 5760, 3840, RGGB, 14, true, Canon PowerShot V1, 100",
            "MRW_ALPHA_7D, MRW, 3008, 2000, RGGB, 12, true, ALPHA-7 DIGITAL, 200",
            "MRW_ALPHA_7D_2025, MRW, 3008, 2000, RGGB, 12, true, ALPHA-7 DIGITAL, 100",
            "MRW_ALPHA_SWEET, MRW, 3008, 2000, RGGB, 12, true, ALPHA SWEET DIGITAL, 400",
            "MRW_DIMAGE_5, MRW, 2048, 1544, RGGB, 12, true, DiMAGE 5, 100",
            "MRW_DIMAGE_A2, MRW, 3264, 2448, RGGB, 12, true, DiMAGE A2, 64",
            "MRW_DYNAX_5D, MRW, 3008, 2000, RGGB, 12, true, DYNAX 5D, 100",
            "MRW_DYNAX_7D, MRW, 3008, 2000, RGGB, 12, true, DYNAX 7D, 100",
            "MRW_MAXXUM_7D, MRW, 3008, 2000, RGGB, 12, true, MAXXUM 7D, 200",
            "CRW_D30, CRW, 2160, 1440, RGGB, 12, true, Canon EOS D30, 1600",
            "CRW_D60, CRW, 3072, 2048, RGGB, 12, true, Canon EOS D60, 100",
            "CRW_10D, CRW, 3072, 2048, RGGB, 12, true, Canon EOS 10D, 200",
            "CRW_10D_3164, CRW, 3072, 2048, RGGB, 12, true, Canon EOS 10D, 100",
            "CRW_10D_3165, CRW, 3072, 2048, RGGB, 12, true, Canon EOS 10D, 200",
            "CRW_300D, CRW, 3072, 2048, RGGB, 12, true, Canon EOS 300D DIGITAL, 100",
            "CRW_300D_3162, CRW, 3072, 2048, RGGB, 12, true, Canon EOS 300D DIGITAL, 100",
            "CRW_DIGITAL_REBEL, CRW, 3072, 2048, RGGB, 12, true, Canon EOS DIGITAL REBEL, 1600",
            "CRW_G1, CRW, 2048, 1536, YCGM, 10, false, Canon PowerShot G1, 50",
            "CRW_G5, CRW, 2592, 1944, RGGB, 12, true, Canon PowerShot G5, 100",
            "CRW_G6, CRW, 3072, 2304, RGGB, 12, true, Canon PowerShot G6, 100",
            "X3F_DP1, X3F, 2640, 1760, -, 12, true, SIGMA DP1, 400",
            "X3F_SD14, X3F, 2640, 1760, -, 12, true, SIGMA SD14, 100",
            "X3F_SD15, X3F, 2640, 1760, -, 12, true, SIGMA SD15, 50",
            "X3F_SD1_MERRILL, X3F, 4704, 3136, -, 12, true, SIGMA SD1 Merrill, 100",
            "X3F_DP2_MERRILL, X3F, 4704, 3136, -, 12, true, SIGMA DP2 Merrill, 100",
            "X3F_DP2_QUATTRO, X3F, 5424, 3616, -, 14, true, SIGMA dp2 Quattro, 100"
    })
    void sample(RawSamples sample, RawFormat format, int width, int height, String cfa, int bits, boolean decodable,
                String model, long iso) {
        try (var source = MediaSource.of(sample.fetch())) {
            var info = RawDecoder.info(source);
            assertEquals(format, info.format());
            assertEquals(width, info.width());
            assertEquals(height, info.height());
            assertEquals(cfa, info.cfaPattern().map(Object::toString).orElse("-"));
            assertEquals(bits, info.bitsPerSample());
            assertEquals(decodable, info.isDecodable());
            assertEquals(model, info.model().orElseThrow());
            assertEquals(iso, info.iso().orElse(0));
            var expected = format == RawFormat.DNG ? ImageFormat.DNG : ImageFormat.RAW;
            assertEquals(expected, Images.detect(source).orElse(null));
            var media = Media.probe(source);
            assertEquals(expected, media.imageFormat().orElseThrow());
            assertEquals(model, media.tags().get("model"), media.tags().toString());
            if (!info.previews().isEmpty()) {
                var hinted = Images.decode(source, ImageDecoderOptions.builder().minSize(200, 130).build());
                assertTrue(hinted.picture().width() >= 200 || hinted.picture().height() >= 130);
            }
            if (!decodable) {
                assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decode(source));
                return;
            }
            var image = RawDecoder.decode(source);
            assertEquals(width, image.picture().width());
            assertEquals(height, image.picture().height());
            assertEquals(width, Images.probe(source).width());
            var preview = RawDecoder.extractPreview(source);
            if (preview.isPresent()) {
                assertArrayEquals(preview.get(), RawDecoder.extractPreview(source, MediaLimits.DEFAULT).orElseThrow());
                var tight = MediaLimits.builder().maxBytes(preview.get().length - 1).build();
                assertThrows(MediaException.LimitExceeded.class, () -> RawDecoder.extractPreview(source, tight));
                // a preview without an orientation of its own may be stored as the sensor is, while a file stored
                // transposed or turned renders in its stored orientation: one of the two pairings must match
                var decoded = Images.decode(MediaSource.of(preview.get()));
                var stored = correlation(grid(image.picture()), grid(letterboxed(decoded.picture(), width, height)));
                var rendered = ImageOps.orient(image.picture(), image.orientation());
                var camera = letterboxed(ImageOps.orient(decoded.picture(), decoded.orientation()), rendered.width(),
                        rendered.height());
                var upright = correlation(grid(rendered), grid(camera));
                assertTrue(Math.max(stored, upright) > 0.9,
                        "luminance correlation with the preview " + stored + " as stored, " + upright + " upright");
            }
        }
    }

    // the image area of a preview letterboxed to show a wider image across its whole width
    static Picture letterboxed(Picture preview, int width, int height) {
        var shown = (int) ((long) preview.width() * height / width);
        if (shown > preview.height() - 2) {
            return preview;
        }
        return ImageOps.crop(preview, 0, (preview.height() - shown) / 2, preview.width(), shown);
    }

    static double[] grid(Picture picture) {
        var small = ImageOps.toRgba(ImageOps.resize(picture, GRID_WIDTH, GRID_HEIGHT,
                com.github.auties00.smalt.image.ops.ResizeFilter.AREA));
        var values = new double[GRID_WIDTH * GRID_HEIGHT];
        var pixels = small.pixels();
        for (var y = 0; y < GRID_HEIGHT; y++) {
            for (var x = 0; x < GRID_WIDTH; x++) {
                var at = y * small.stride() + x * 4;
                values[y * GRID_WIDTH + x] = 0.2126 * (pixels[at] & 0xFF) + 0.7152 * (pixels[at + 1] & 0xFF)
                        + 0.0722 * (pixels[at + 2] & 0xFF);
            }
        }
        return values;
    }

    static double correlation(double[] a, double[] b) {
        var meanA = 0.0;
        var meanB = 0.0;
        for (var i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= a.length;
        meanB /= b.length;
        var covariance = 0.0;
        var varianceA = 0.0;
        var varianceB = 0.0;
        for (var i = 0; i < a.length; i++) {
            covariance += (a[i] - meanA) * (b[i] - meanB);
            varianceA += (a[i] - meanA) * (a[i] - meanA);
            varianceB += (b[i] - meanB) * (b[i] - meanB);
        }
        return covariance / Math.sqrt(varianceA * varianceB);
    }
}
