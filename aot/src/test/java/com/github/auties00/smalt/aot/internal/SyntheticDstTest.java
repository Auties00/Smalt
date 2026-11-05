package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.audio.dst.DstDecoder;
import com.github.auties00.smalt.track.AudioTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the DST frames of the training workload decode to the DSD they code, bit for bit, which also checks
 * the DST decoder against an independent coder of its arithmetic code, prediction filters and Rice-coded headers.
 */
@DisplayName("synthetic DST frames of the training workloads")
class SyntheticDstTest {
    @ParameterizedTest(name = "{0} channels at {1} bit/s")
    @CsvSource({"2, 2822400, 3", "1, 5644800, 2", "6, 2822400, 1"})
    @DisplayName("every frame decodes to its DSD and the frames are smaller than the DSD")
    void roundTrip(int channels, int rate, int frames) {
        var frameBytes = rate / 600;
        var dsd = SyntheticDst.dsd(channels, rate, frameBytes * frames);
        var stream = AudioWorkloads.dstStream(channels, rate, frames);
        var decoder = new DstDecoder((AudioTrack) stream.track());
        var output = new byte[frameBytes * channels];
        var coded = 0L;
        for (var index = 0; index < frames; index++) {
            var packet = stream.packets().get(index);
            coded += packet.size();
            decoder.decodeDsd(packet, output, 0);
            var start = index * frameBytes * channels;
            assertArrayEquals(Arrays.copyOfRange(dsd, start, start + output.length), output, "frame " + index);
        }
        assertTrue(coded < dsd.length, coded + " coded bytes for " + dsd.length + " bytes of DSD");
    }
}
