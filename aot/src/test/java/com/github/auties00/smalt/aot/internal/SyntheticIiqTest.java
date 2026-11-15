package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the synthetic Phase One IIQ files of the {@code raw-decode-iiq} workload decode, so the workload trains
 * the row decoder and the calibration rather than the path of an unsupported file.
 */
@DisplayName("synthetic Phase One IIQ files of the training workload")
class SyntheticIiqTest {
    @ParameterizedTest(name = "compression {0}")
    @ValueSource(ints = {0, 3, 5, 8})
    @DisplayName("decode to the picture's size and follow its content")
    void decodes(int compression) {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(96, 64, 1).getFirst(), false);
        var file = SyntheticVendorTiff.iiq(picture, compression);
        var info = RawDecoder.info(MediaSource.of(file));
        assertEquals(RawFormat.IIQ, info.format());
        assertTrue(info.isDecodable(), info.toString());
        var raw = RawDecoder.decodeRaw(MediaSource.of(file));
        assertEquals(96 * 64, raw.samples().length);
        var lowest = Integer.MAX_VALUE;
        var highest = Integer.MIN_VALUE;
        for (var sample : raw.samples()) {
            lowest = Math.min(lowest, sample & 0xFFFF);
            highest = Math.max(highest, sample & 0xFFFF);
        }
        assertTrue(highest - lowest > 500, "the photosites span " + lowest + " to " + highest);
        assertEquals(96, RawDecoder.decode(MediaSource.of(file), RawDecoderOptions.defaults()).picture().width());
    }
}
