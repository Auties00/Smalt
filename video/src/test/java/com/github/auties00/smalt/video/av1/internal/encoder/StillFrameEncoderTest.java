package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the intra-only still encoder: every stream it writes decodes with the module's decoder
 * to exactly the encoder's reconstruction, across quantizers, speeds, picture formats and sizes.
 */
class StillFrameEncoderTest {
    private static void roundTrip(StillTesting.Planes planes, StillSettings settings, double minPsnr) {
        var holder = new StillFrameEncoder[1];
        var obus = StillTesting.encode(planes, settings, holder);
        try (var encoder = holder[0]) {
            var decoded = StillTesting.decode(obus, planes.width(), planes.height());
            assertNull(StillTesting.firstDifference(decoded, encoder.reconstruction(), planes));
            var psnr = StillTesting.psnr(planes, decoded);
            System.out.printf("%dx%d layout %d %d-bit q%d s%d: %d bytes, %.2f dB%n", planes.width(), planes.height(),
                    planes.layout(), planes.bitDepth(), settings.qindex, settings.speed, obus.length, psnr);
            assertTrue(psnr >= minPsnr, "psnr " + psnr);
        }
    }

    @ParameterizedTest(name = "foreman q{0} speed {1}")
    @DisplayName("an 8-bit 4:2:0 picture decodes to the reconstruction")
    @CsvSource({"40, 6, 38", "120, 6, 30", "200, 6, 24", "120, 0, 30", "120, 9, 28"})
    void foreman(int q, int speed, double minPsnr) {
        roundTrip(StillTesting.foreman(0), StillSettings.of(q, speed).threads(1).reducedHeader(true), minPsnr);
    }

    @ParameterizedTest(name = "layout {0} depth {1}")
    @DisplayName("every chroma format and bit depth decodes to the reconstruction")
    @CsvSource({"0, 8", "1, 10", "1, 12", "2, 8", "2, 10", "3, 8", "3, 10", "3, 12", "0, 10"})
    void formats(int layout, int depth) {
        var planes = StillTesting.convert(StillTesting.foreman(0), layout, depth);
        roundTrip(planes, StillSettings.of(100, 6).threads(1).reducedHeader(layout == SequenceHeader.LAYOUT_I420), 30);
    }

    @ParameterizedTest(name = "{0}x{1}")
    @DisplayName("odd sizes decode to the reconstruction")
    @CsvSource({"1, 1", "7, 5", "97, 61", "130, 66", "64, 64", "200, 9"})
    void sizes(int width, int height) {
        var planes = StillTesting.synthetic(width, height, SequenceHeader.LAYOUT_I420, 8, width * 31L + height);
        roundTrip(planes, StillSettings.of(90, 6).threads(1).reducedHeader(true), 20);
    }

    @ParameterizedTest(name = "layout {0} depth {1} q{2}")
    @DisplayName("screen content with palettes decodes to the reconstruction")
    @CsvSource({"1, 8, 60", "1, 8, 160", "3, 8, 100", "1, 10, 100", "0, 8, 100", "2, 8, 100", "1, 8, 0"})
    void screen(int layout, int depth, int q) {
        var planes = StillTesting.screen(200, 136, layout, depth, 11);
        roundTrip(planes, StillSettings.of(q, 6).screenContent(true).threads(1).reducedHeader(true), q == 0 ? 99 : 30);
    }

    @ParameterizedTest(name = "layout {0} depth {1} speed {2}")
    @DisplayName("loop restoration decodes to the reconstruction")
    @CsvSource({"1, 8, 0", "1, 8, 3", "0, 8, 2", "2, 10, 2", "3, 8, 4", "3, 12, 1", "1, 10, 4"})
    void restoration(int layout, int depth, int speed) {
        var planes = StillTesting.convert(StillTesting.foreman(0), layout, depth);
        roundTrip(planes, StillSettings.of(140, speed).threads(1).reducedHeader(true), 28);
    }

    @ParameterizedTest(name = "{0}x{1} tiles speed {2}")
    @DisplayName("tiled frames coded in parallel decode to the reconstruction")
    @CsvSource({"1, 1, 4", "2, 0, 6", "0, 2, 9"})
    void tiles(int colsLog2, int rowsLog2, int speed) {
        var planes = StillTesting.foreman(0);
        roundTrip(planes, StillSettings.of(100, speed).tiles(colsLog2, rowsLog2).reducedHeader(true), 30);
    }

    @ParameterizedTest(name = "layout {0}")
    @DisplayName("lossless pictures decode exactly to the source")
    @CsvSource({"1", "3", "0"})
    void lossless(int layout) {
        var planes = StillTesting.convert(StillTesting.foreman(0), layout, 8);
        roundTrip(planes, StillSettings.of(0, 6).threads(1).reducedHeader(true), 99);
    }
}
