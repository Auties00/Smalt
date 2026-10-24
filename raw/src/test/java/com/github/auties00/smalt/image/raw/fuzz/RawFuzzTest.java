package com.github.auties00.smalt.image.raw.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.raw.RawColorSpace;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawDemosaic;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.RawHighlights;
import com.github.auties00.smalt.image.raw.RawToneCurve;
import com.github.auties00.smalt.image.raw.RawWhiteBalance;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;

/**
 * Fuzz targets of the raw decoders, one per raw format. Each input is described, decoded to its sensor image,
 * rendered with the default options, with every option off its default and as a thumbnail (one of the three
 * while fuzzing, see {@code FuzzRun.sampled}), and its embedded preview is extracted and decoded.
 *
 * <p>An input that {@link RawDecoder#info} describes as another format is skipped; one it cannot describe is
 * still decoded, so that damaged headers reach the decoders.
 */
class RawFuzzTest {
    /** The rendering that differs most from the default: every option off its default. */
    private static final RawDecoderOptions RENDERED = RawDecoderOptions.builder()
            .limits(Fuzz.LIMITS)
            .demosaic(RawDemosaic.MENON)
            .whiteBalance(RawWhiteBalance.temperature(4000))
            .highlights(RawHighlights.BLEND)
            .toneCurve(RawToneCurve.LINEAR)
            .colorSpace(RawColorSpace.PROPHOTO_RGB)
            .exposure(0.5)
            .highBitDepth(true)
            .opcodes(false)
            .defaultCrop(false)
            .enhancedImage(true)
            .userCrop(true)
            .autoBlack(true)
            .build();

    /**
     * The rendering of the standard tone curve with the options that need it or the default crop: the automatic
     * black subtraction, the user crop and the enhanced image.
     */
    private static final RawDecoderOptions PROFILED = RawDecoderOptions.builder()
            .limits(Fuzz.LIMITS)
            .highlights(RawHighlights.BLEND)
            .enhancedImage(true)
            .userCrop(true)
            .autoBlack(true)
            .build();

    @FuzzTest
    void dng(byte[] data) {
        decode(RawFormat.DNG, data);
    }

    @FuzzTest
    void cr2(byte[] data) {
        decode(RawFormat.CR2, data);
    }

    @FuzzTest
    void cr3(byte[] data) {
        decode(RawFormat.CR3, data);
    }

    @FuzzTest
    void crw(byte[] data) {
        decode(RawFormat.CRW, data);
    }

    @FuzzTest
    void nef(byte[] data) {
        decode(RawFormat.NEF, data);
    }

    @FuzzTest
    void arw(byte[] data) {
        decode(RawFormat.ARW, data);
    }

    @FuzzTest
    void raf(byte[] data) {
        decode(RawFormat.RAF, data);
    }

    @FuzzTest
    void orf(byte[] data) {
        decode(RawFormat.ORF, data);
    }

    @FuzzTest
    void rw2(byte[] data) {
        decode(RawFormat.RW2, data);
    }

    @FuzzTest
    void pef(byte[] data) {
        decode(RawFormat.PEF, data);
    }

    @FuzzTest
    void srw(byte[] data) {
        decode(RawFormat.SRW, data);
    }

    @FuzzTest
    void mrw(byte[] data) {
        decode(RawFormat.MRW, data);
    }

    @FuzzTest
    void x3f(byte[] data) {
        decode(RawFormat.X3F, data);
    }

    private static void decode(RawFormat format, byte[] data) {
        var run = Fuzz.start(format.name());
        var info = run.value("info", () -> RawDecoder.info(MediaSource.of(data), Fuzz.LIMITS));
        if (info.isPresent() && info.get().format() != format) {
            run.skip("described as " + info.get().format());
            return;
        }
        var plain = RawDecoderOptions.builder().limits(Fuzz.LIMITS).build();
        run.value("sensor", () -> RawDecoder.decodeRaw(MediaSource.of(data), plain)).ifPresent(raw -> {
            if (raw.samples().length < (long) raw.width() * raw.height() * raw.channels()) {
                throw new AssertionError("the sensor image holds fewer samples than its size");
            }
            run.output();
        });
        if (run.sampled(data, 0, 3)) {
            run.value("render", () -> RawDecoder.decode(MediaSource.of(data), plain)).ifPresent(image -> {
                ImageOps.toRgba(image.picture());
                run.output();
            });
        }
        if (run.sampled(data, 1, 3)) {
            run.step("render options", () -> RawDecoder.decode(MediaSource.of(data), RENDERED));
            run.step("render profile options", () -> RawDecoder.decode(MediaSource.of(data), PROFILED));
        }
        if (run.sampled(data, 2, 3)) {
            run.step("thumbnail", () -> RawDecoder.decode(MediaSource.of(data), plain.toBuilder()
                    .minSize(32, 32)
                    .embeddedPreview(true)
                    .build()));
        }
        run.value("preview", () -> RawDecoder.extractPreview(MediaSource.of(data), Fuzz.LIMITS).orElse(null))
                .ifPresent(preview -> run.step("decode preview", () -> Images.decode(MediaSource.of(preview),
                        ImageDecoderOptions.builder().limits(Fuzz.LIMITS).build())));
    }
}
