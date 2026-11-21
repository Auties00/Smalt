package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corrupts single error sensitivity category instances of ISO/IEC 14496-26 streams stored with {@code epConfig} 1
 * (one track per instance) and checks that the decoder conceals the RVLC codewords and the reordered spectral data
 * instead of failing: the output of the corrupt block stays bounded and close to the clean decode, and every block
 * the corrupt one does not overlap decodes bit for bit as without the corruption. The streams have no long term
 * prediction, so an error cannot reach later blocks through the predictor.
 */
class AacErrorConcealmentTest {
    /** The number of blocks decoded before and after the corrupt one. */
    private static final int MARGIN = 4;

    static List<AacTestSupport.Stream> tracks(String name) {
        return AacTestSupport.parseMp4Tracks(Fixtures.read(ErrorResilientVectors.fetch(name + ".mp4")));
    }

    static List<float[][]> decodeWindow(List<AacTestSupport.Stream> tracks, int first, int last, int corruptBlock,
                                        int corruptInstance, byte[] replacement) {
        var blocks = new ArrayList<float[][]>();
        try (var decoder = new AacDecoder(tracks.getFirst().track())) {
            for (var block = first; block <= last; block++) {
                var instances = new ArrayList<Packet>();
                for (var t = 0; t < tracks.size(); t++) {
                    var data = block == corruptBlock && t == corruptInstance ? replacement
                            : tracks.get(t).frames().get(block);
                    instances.add(new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true));
                }
                decoder.decode(instances, buffer -> blocks.add(AacTestSupport.copy(buffer)));
            }
        }
        return blocks;
    }

    @ParameterizedTest(name = "{0} instance {1}")
    @CsvSource({
            "er_al18_48_ep1, 1, RVLC codewords, 12",
            "er_al18_48_ep1, 3, reordered spectral data, 18",
            "er_al26_48_ep1, 3, reordered spectral data, 14",
            "er_al26_48_ep1, 6, reordered spectral data, 24"
    })
    @DisplayName("conceals bit errors in one instance, confined to the blocks the corrupt one overlaps")
    void bitErrors(String name, int instance, String content, double minimumSnr) {
        var tracks = tracks(name);
        var random = new Random(name.hashCode() * 31 + instance);
        double signal = 0;
        double noise = 0;
        var corrupted = 0;
        for (var trial = 0; trial < 60; trial++) {
            var block = 20 + random.nextInt(tracks.getFirst().frames().size() - 40);
            var clean = tracks.get(instance).frames().get(block);
            if (clean.length == 0) {
                continue;
            }
            var data = clean.clone();
            for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
            }
            corrupted++;
            var first = block - MARGIN;
            var last = block + MARGIN;
            var expected = decodeWindow(tracks, first, last, -1, -1, null);
            var actual = decodeWindow(tracks, first, last, block, instance, data);
            for (var i = 0; i < expected.size(); i++) {
                var at = first + i;
                for (var c = 0; c < expected.get(i).length; c++) {
                    var reference = expected.get(i)[c];
                    var output = actual.get(i)[c];
                    if (at < block || at > block + 1) {
                        assertArrayEquals(reference, output, name + " block " + at + " after an error in block " + block);
                        continue;
                    }
                    double peak = 0;
                    for (var value : reference) {
                        peak = Math.max(peak, Math.abs(value));
                    }
                    for (var s = 0; s < output.length; s++) {
                        assertTrue(Float.isFinite(output[s]), content + " concealment output is finite");
                        assertTrue(Math.abs(output[s]) <= 4 * peak + 0.05,
                                content + " concealment of block " + block + " stays bounded: " + output[s]);
                        signal += (double) reference[s] * reference[s];
                        noise += (double) (output[s] - reference[s]) * (output[s] - reference[s]);
                    }
                }
            }
        }
        assertTrue(corrupted >= 30, "enough non-empty instances");
        var snr = 10 * Math.log10(signal / Math.max(noise, 1e-30));
        assertTrue(snr > minimumSnr, content + ": the concealed blocks stay within " + snr + " dB of the clean decode");
    }
}
