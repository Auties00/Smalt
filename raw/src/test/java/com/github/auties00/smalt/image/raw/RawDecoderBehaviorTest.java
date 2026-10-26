package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.image.ImageDecoderAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Runs the DNG and camera raw decoders through the I/O behavior harness ({@link ImageDecoderAsserts}), with
 * the sensor data of {@link RawDecoder#decodeRaw} as an extra decode, over DNG files made by
 * {@link TinyDng} in every sample layout and by {@link DngSpecSamples} in the layouts and profile features of DNG
 * 1.2 to 1.7, synthetic Sigma X3F files ({@link X3fWriter}) and Canon CR3 files ({@link Cr3Writer}), a smoke-tier DNG
 * and full-tier files of every camera raw format (three for CR3: a lossless file, a version 2 C-RAW file and a
 * version 1 C-RAW file of two tiles),
 * comparing each with its golden dump under {@code src/test/resources/dumps}.
 */
@DisplayName("raw decoder I/O behaviors")
class RawDecoderBehaviorTest {
    static String sensor(RawImage image) {
        var crc = new CRC32();
        var samples = image.samples();
        var bytes = new byte[samples.length * 2];
        for (var i = 0; i < samples.length; i++) {
            bytes[2 * i] = (byte) samples[i];
            bytes[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        crc.update(bytes);
        return "sensor " + image.width() + "x" + image.height() + " channels=" + image.channels() + " cfa="
                + image.cfaPattern().map(Object::toString).orElse("none") + " neutral="
                + Arrays.toString(image.cameraNeutral()) + " crc=" + "%08x".formatted(crc.getValue());
    }

    static ImageDecoderAsserts.Builder harness(String name, byte[] data) {
        return ImageDecoderAsserts.of(name, data).decoder("raw sensor data",
                source -> sensor(RawDecoder.decodeRaw(source)));
    }

    static TinyDng layout(String layout) {
        var dng = TinyDng.ramp(64, 48);
        dng.white = 65535;
        switch (layout) {
            case "uncompressed" -> {
            }
            case "lossless-jpeg" -> dng.compression = 7;
            case "lossless-jpeg-two-components" -> {
                dng.compression = 7;
                dng.jpegComponents = 2;
            }
            case "deflate-predictor" -> {
                dng.compression = 8;
                dng.predictor = 34892;
            }
            case "packed-12bit" -> {
                for (var i = 0; i < dng.samples.length; i++) {
                    dng.samples[i] &= 0xFFF;
                }
                dng.bits = 12;
                dng.white = 4095;
            }
            case "float-predictor" -> {
                dng.floats = new float[64 * 48];
                for (var i = 0; i < dng.floats.length; i++) {
                    dng.floats[i] = (i % 97) / 96f;
                }
                dng.compression = 8;
                dng.predictor = 3;
            }
            case "linear" -> {
                dng.samplesPerPixel = 3;
                dng.samples = new int[64 * 48 * 3];
                for (var i = 0; i < dng.samples.length; i++) {
                    dng.samples[i] = (i * 131) & 0xFFFF;
                }
            }
            case "cropped-rotated" -> {
                dng.crop = new int[]{4, 2, 56, 40};
                dng.orientation = 6;
            }
            default -> throw new IllegalArgumentException(layout);
        }
        return dng;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"uncompressed", "lossless-jpeg", "lossless-jpeg-two-components", "deflate-predictor",
            "packed-12bit", "float-predictor", "linear", "cropped-rotated"})
    @DisplayName("tiny DNG layouts: identical pictures and sensor data under every source behavior and clean faults")
    void tiny(String layout) {
        harness("tinydng/" + layout, layout(layout).write()).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"wide-24bit", "planar", "sub-tiles-interleaved", "staggered", "enhanced",
            "linear-raw-preview", "gain-table-map", "rgb-tables", "extra-profile", "four-color"})
    @DisplayName("DNG 1.2 to 1.7 layouts and profile features: identical pictures and sensor data under every source "
            + "behavior and clean faults")
    void spec(String name) {
        harness("dngspec/" + name, DngSpecSamples.of(name)).run();
    }

    static X3fWriter x3fFile(String name) {
        var x3f = new X3fWriter();
        x3f.preview = false;
        switch (name) {
            case "quattro" -> {
                x3f.type = 1;
                x3f.format = 35;
                x3f.camfType = 5;
                x3f.layers(501, 0, 3000);
                x3f.defaultEntries();
                x3f.entries.add(X3fWriter.matrix("BadPixelsLumaF23", 6, new int[]{4}, 6, 9, 1, 0));
                x3f.entries.add(X3fWriter.matrix("BadPixelsChromaF23", 6, new int[]{4}, 4, 5, 1, 0));
                x3f.entries.add(X3fWriter.floats("SpatialGain", new int[]{2, 2, 3}, 1, 1.1, 1.2, 1.3, 1.4, 1.5, 1, 1,
                        1, 1, 1, 1));
            }
            case "uncompressed" -> {
                x3f.format = 6;
                x3f.rowSize = 168;
                x3f.layers(502, -60, 450);
            }
            case "x530" -> {
                x3f.format = 5;
                x3f.preTrue = true;
                x3f.layers(503, -60, 450);
            }
            case "thumbnails" -> {
                x3f.layers(504, 0, 3000);
                x3f.thumbnail(11, 30, 18, 505);
                x3f.thumbnail(3, 20, 12, 506);
            }
            default -> throw new IllegalArgumentException(name);
        }
        return x3f;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"quattro", "uncompressed", "x530", "thumbnails"})
    @DisplayName("synthetic Sigma X3F files of the codings no small sample shows: identical pictures and sensor data "
            + "under every source behavior and clean faults")
    void x3f(String name) {
        harness("x3f/" + name, x3fFile(name).write()).run();
    }

    static Cr3Writer cr3File(String name) {
        var cr3 = new Cr3Writer();
        cr3.width = 64;
        cr3.height = 48;
        switch (name) {
            case "lossless" -> {
            }
            case "lossless-two-tiles" -> cr3.tiles = 2;
            case "lossy-version-1" -> cr3.levels = 3;
            case "lossy-version-2" -> {
                cr3.levels = 3;
                cr3.version = 0x200;
            }
            default -> throw new IllegalArgumentException(name);
        }
        return cr3;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lossless", "lossless-two-tiles", "lossy-version-1", "lossy-version-2"})
    @DisplayName("synthetic Canon CR3 files of both CRX codings and codec versions: identical pictures and sensor "
            + "data under every source behavior and clean faults")
    void cr3(String name) {
        harness("cr3/" + name, cr3File(name).write()).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rawpixls-sony-nex-6-12bit-12bit-compressed-3-2.arw",
            "rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2", "rawpixls-canon-eos-5d-mark-iii.cr2",
            "rawpixls-canon-eos-d30-raw-3-2.crw", "rawpixls-canon-eos-r6-3-2.cr3",
            "rawpixls-canon-eos-r6-mark-iii-raw-3-2.cr3", "rawpixls-canon-eos-90d-3-2.cr3",
            "rawpixls-minolta-dimage-5-4-3.mrw", "rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef",
            "rawpixls-olympus-e-10-16bit-4-3.orf", "rawpixls-pentax-ist-dl-12bit-3-2.pef",
            "rawpixls-fujifilm-x-a1-12bit-12bit-uncompressed-3-2.raf", "rawpixls-panasonic-dmc-lx3-1-1.rw2",
            "rawpixls-sigma-dp1-3-2-1116.x3f"})
    @DisplayName("camera raw files of the full tier, at least one per format: identical pictures and sensor data under every "
            + "source behavior and clean faults")
    void camera(String name) throws IOException {
        harness("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name)))
                .noScaled()
                .maxFaultReads(4)
                .timeout(Duration.ofMinutes(5))
                .run();
    }

    @Test
    @DisplayName("a smoke-tier DNG: identical pictures and sensor data under every source behavior and clean faults")
    void vector() throws IOException {
        var name = "rawpixls-canon-eos-5d-mark-iii-14bit-14bit-2.3471882640587.dng";
        harness("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name)))
                .noScaled()
                .maxFaultReads(6)
                .timeout(Duration.ofMinutes(5))
                .run();
    }
}
