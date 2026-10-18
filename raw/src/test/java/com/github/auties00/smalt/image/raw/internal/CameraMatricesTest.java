package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Checks the per-model color matrices of {@link CameraMatrices}: that {@link CameraMatrixDerivation}
 * re-derives every one from the pinned rawtoaces-data archive, how models are recognized, and that
 * vendor files of a known model get them while DNG files keep their own.
 */
class CameraMatricesTest {
    @Test
    @DisplayName("every matrix is re-derived from the rawtoaces-data spectral sensitivities")
    void derivation() throws Exception {
        var archive = Conformance.fetch("rawtoaces-data-e9b8503cf8a0641f40e5345d9757bde60c15f423.tar.gz",
                "https://codeload.github.com/AcademySoftwareFoundation/rawtoaces-data/tar.gz/e9b8503cf8a0641f40e5345d9757bde60c15f423",
                "583eb822932be044a38c9c19bc950a4db1bab409c979bcfd1419afde9029ffa6");
        var cameras = CameraMatrixDerivation.derive(archive);
        assertEquals(52, cameras.size());
        for (var camera : cameras) {
            var entry = CameraMatrices.find(camera.make(), camera.model());
            assertNotNull(entry, camera.make() + " " + camera.model());
            assertArrayEquals(camera.a(), entry.a(), 1e-9, camera.model() + " under illuminant A");
            assertArrayEquals(camera.d65(), entry.d65(), 1e-9, camera.model() + " under D65");
        }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({
            "NIKON CORPORATION, NIKON D7000, Nikon, D7000",
            "NIKON CORPORATION, NIKON Z f, Nikon, Z f",
            "Canon, Canon EOS 5D Mark III, Canon, EOS 5D Mark III",
            "SONY, ILCE-7M3, Sony, ILCE-7M3",
            "FUJIFILM, X-T3, Fujifilm, X-T3",
            "Canon, Canon EOS DIGITAL REBEL XTi, Canon, Digital Rebel XTi",
            "Canon, Canon EOS 400D DIGITAL, Canon, Digital Rebel XTi",
            "Canon, Canon EOS REBEL SL1, Canon, EOS 100D",
            "Canon, Canon EOS Kiss X9, Canon, EOS 200D",
            "Canon, Canon EOS 250D, Canon, EOS 200D II",
            "Canon, Canon EOS REBEL T3i, Canon, EOS 600D",
            "Canon, Canon EOS R5m2, Canon, EOS R5m2",
            "Canon, Canon EOS R5 Mark II, Canon, EOS R5m2",
            "Canon, Canon EOS R6m2, Canon, EOS R6m2"
    })
    @DisplayName("recognizes a model from its EXIF make and model")
    void recognizes(String make, String model, String dataMake, String dataModel) {
        assertSame(CameraMatrices.find(dataMake, dataModel), CameraMatrices.find(make, model));
        assertNotNull(CameraMatrices.find(make, model));
    }

    @Test
    @DisplayName("an unknown model has no matrices")
    void unknown() {
        assertNull(CameraMatrices.find("NIKON CORPORATION", "NIKON D70s"));
    }

    @Test
    @DisplayName("a NEF file of a known model gets its matrices, a DNG file keeps its own")
    void complete() {
        try (var source = MediaSource.of(RawSamples.NEF_D7000_LOSSY_12.fetch())) {
            var color = RawParser.parse(source, MediaLimits.DEFAULT).color;
            var entry = CameraMatrices.find("Nikon", "D7000");
            assertArrayEquals(entry.a(), color.colorMatrix[0]);
            assertArrayEquals(entry.d65(), color.colorMatrix[1]);
            assertEquals(17, color.illuminant[0]);
            assertEquals(21, color.illuminant[1]);
        }
        try (var source = MediaSource.of(RawSamples.ADC_5D3_LOSSLESS.fetch())) {
            var color = RawParser.parse(source, MediaLimits.DEFAULT).color;
            var entry = CameraMatrices.find("Canon", "EOS 5D Mark III");
            assertNotNull(color.colorMatrix[0]);
            assertFalse(Arrays.equals(entry.a(), color.colorMatrix[0]));
        }
    }
}
