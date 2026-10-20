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
 * {@link TinyDng} in every sample layout, a smoke-tier DNG and one full-tier file of each camera raw format,
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
    @ValueSource(strings = {"rawpixls-sony-nex-6-12bit-12bit-compressed-3-2.arw",
            "rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2", "rawpixls-canon-eos-5d-mark-iii.cr2",
            "rawpixls-canon-eos-d30-raw-3-2.crw",
            "rawpixls-minolta-dimage-5-4-3.mrw", "rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef",
            "rawpixls-olympus-e-10-16bit-4-3.orf", "rawpixls-pentax-ist-dl-12bit-3-2.pef",
            "rawpixls-fujifilm-x-a1-12bit-12bit-uncompressed-3-2.raf", "rawpixls-panasonic-dmc-lx3-1-1.rw2",
            "rawpixls-sigma-dp1-3-2-1116.x3f"})
    @DisplayName("camera raw files of the full tier, one per format: identical pictures and sensor data under every "
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
