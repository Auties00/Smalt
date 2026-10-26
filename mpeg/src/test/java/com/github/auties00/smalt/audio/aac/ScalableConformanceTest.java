package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the scalable AAC conformance streams of ISO/IEC 14496-26 whose layers are all AAC: {@code ac01} (eight mono
 * layers), {@code ac02} (four mono and four stereo layers, joined by the frequency selective switch), {@code ac03}
 * (eight stereo layers) and {@code ac22} (four stereo layers with intensity stereo), all of object type 6 with long
 * term prediction, and the error resilient {@code er_ac111}, {@code er_ac118}, {@code er_ac119}, {@code er_ac121}
 * and {@code er_ac123} of object type 20 (virtual codebooks, RVLC and codeword reordering per layer, intensity stereo,
 * noise substitution), with {@code epConfig} 0 and 1. Each stream is decoded from the access units of all its tracks
 * at the layer each reference names ({@code _layN}, through {@link AacDecoderOptions#maxLayer()}).
 *
 * <p>The references hold more than 16 bits, so the decoded samples are compared unrounded under the criterion of
 * the standard: an RMS error of at most {@code 2^-15 / sqrt(12)} and a peak error of at most {@code 2^-14} of full
 * scale. The noise substitution of {@code er_ac123} cannot match sample by sample; its band energies must match
 * within half a decibel on average.
 *
 * <p>The smoke tier decodes {@code er_ac121_ep0} at its base and top layers, {@code er_ac111_ep1} (category instances
 * in separate tracks, a mono and a stereo layer) at its top layer and {@code ac01} at its base layer, and checks that
 * a CELP core ({@code er_ac211_ep0}) is rejected.
 */
@DisplayName("Scalable AAC conformance (ISO/IEC 14496-26)")
class ScalableConformanceTest {
    /**
     * The layers of the references of each stream of the full tier, and whether it uses noise substitution.
     */
    private static final String REFERENCES = """
            ac01 0 1 2 3 4 5 6 7
            ac02 0 1 2 3 4 5 6 7
            ac03 0 1 2 3 4 5 6 7
            ac22 0 1 2 3
            er_ac111_ep0 0 1
            er_ac111_ep1 0 1
            er_ac118_ep0 0 1 2 3
            er_ac118_ep1 0 1 2 3
            er_ac119_ep0 0 1 2 3
            er_ac119_ep1 0 1 2 3
            er_ac121_ep0 0 1 2 3
            er_ac123_ep0 0 1 noise
            er_ac123_ep1 0 1 noise
            """;

    private static final double RMS_LIMIT = Math.pow(2, -15) / Math.sqrt(12);

    private static final double PEAK_LIMIT = Math.pow(2, -14);

    @TestFactory
    @DisplayName("the smoke streams match their references")
    Stream<DynamicTest> smoke() {
        return Stream.of(
                DynamicTest.dynamicTest("er_ac121_ep0", () -> check("er_ac121_ep0", List.of(0, 3), false)),
                DynamicTest.dynamicTest("er_ac111_ep1", () -> check("er_ac111_ep1", List.of(1), false)),
                DynamicTest.dynamicTest("ac01", () -> check("ac01", List.of(0), false)));
    }

    @TestFactory
    @DisplayName("every stream matches its references at every layer")
    Stream<DynamicTest> full() {
        var tests = new ArrayList<DynamicTest>();
        for (var line : REFERENCES.lines().map(String::trim).filter(line -> !line.isEmpty()).toList()) {
            var parts = new ArrayList<>(List.of(line.split(" ")));
            var stream = parts.removeFirst();
            var noise = parts.remove("noise");
            var layers = parts.stream().map(Integer::parseInt).toList();
            tests.add(DynamicTest.dynamicTest(stream, () -> check(stream, layers, noise)));
        }
        return tests.stream();
    }

    @Test
    @DisplayName("rejects a CELP core layer")
    void celpCore() {
        var tracks = tracks("er_ac211_ep0");
        var exception = assertThrows(MediaException.Unsupported.class,
                () -> new AacDecoder(tracks.stream().map(AacTestSupport.Stream::track).toList()));
        assertEquals("aac scalable core", exception.format());
    }

    @Test
    @DisplayName("decodes the base layer from the first track alone")
    void baseLayerTrack() {
        var tracks = tracks("ac01");
        AacTestSupport.Decoded decoded;
        try (var decoder = new AacDecoder(tracks.getFirst().track())) {
            decoded = AacTestSupport.decodeAll(decoder, tracks.getFirst().frames());
        }
        compare("ac01_lay0", reference("ac01_lay0")[0], decoded.planes()[0]);
    }

    @Test
    @DisplayName("keeps the stereo output of a frame that lacks its stereo layers, with the mono layers in both channels")
    void missingLayers() {
        var tracks = tracks("ac02");
        var mono = reference("ac02_lay3")[0];
        var collected = new ArrayList<float[][]>();
        try (var decoder = new AacDecoder(tracks.stream().map(AacTestSupport.Stream::track).toList())) {
            assertEquals(ChannelLayout.STEREO, decoder.layout());
            for (var block = 0; block < tracks.getFirst().frames().size(); block++) {
                var units = new ArrayList<Packet>();
                for (var layer = 0; layer < 4; layer++) {
                    units.add(new Packet(1, tracks.get(layer).frames().get(block), Packet.NO_TIMESTAMP,
                            Packet.NO_TIMESTAMP, 0, true));
                }
                decoder.decode(units, buffer -> {
                    assertEquals(2, buffer.channels());
                    collected.add(new float[][]{
                            Arrays.copyOf(buffer.plane(0), buffer.frames()),
                            Arrays.copyOf(buffer.plane(1), buffer.frames())});
                });
            }
        }
        var left = new float[collected.size() * 1024];
        var right = new float[left.length];
        for (var i = 0; i < collected.size(); i++) {
            System.arraycopy(collected.get(i)[0], 0, left, i * 1024, 1024);
            System.arraycopy(collected.get(i)[1], 0, right, i * 1024, 1024);
        }
        assertArrayEquals(left, right);
        compare("ac02_lay3 left", mono, left);
    }

    @TestFactory
    @DisplayName("decodes damaged frames or rejects them as malformed, and recovers on the next frame")
    Stream<DynamicTest> damaged() {
        return Stream.of("er_ac121_ep0", "er_ac111_ep1", "ac01").map(name -> DynamicTest.dynamicTest(name, () -> {
            var tracks = tracks(name);
            var random = new java.util.Random(name.hashCode());
            var blocks = Math.min(tracks.getFirst().frames().size(), 60);
            var rejected = 0;
            var decodedAfterDamage = 0;
            try (var decoder = new AacDecoder(tracks.stream().map(AacTestSupport.Stream::track).toList())) {
                for (var block = 0; block < blocks; block++) {
                    var units = new ArrayList<Packet>();
                    for (var track : tracks) {
                        var data = track.frames().get(block).clone();
                        if (block % 3 == 1 && data.length > 0) {
                            switch (random.nextInt(4)) {
                                case 0 -> data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                                case 1 -> data = Arrays.copyOf(data, random.nextInt(data.length));
                                case 2 -> Arrays.fill(data, (byte) 0xFF);
                                default -> data = new byte[0];
                            }
                        }
                        units.add(new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true));
                    }
                    var damagedBlock = block % 3 == 1;
                    try {
                        decoder.decode(units, buffer -> {
                            for (var c = 0; c < buffer.channels(); c++) {
                                for (var i = 0; i < buffer.frames(); i++) {
                                    assertTrue(Float.isFinite(buffer.plane(c)[i]), name + " finite output");
                                }
                            }
                        });
                        if (!damagedBlock && block > 1) {
                            decodedAfterDamage++;
                        }
                    } catch (MediaException.Malformed exception) {
                        assertTrue(damagedBlock, name + " rejects undamaged block " + block + ": " + exception);
                        rejected++;
                    }
                }
            }
            assertTrue(decodedAfterDamage > 0, name + " recovers after damaged blocks (" + rejected + " rejected)");
        }));
    }

    /**
     * Returns the tracks of a conformance stream.
     *
     * @param stream the stream name
     * @return the tracks, in file order
     */
    private static List<AacTestSupport.Stream> tracks(String stream) {
        return AacTestSupport.parseMp4Tracks(Fixtures.read(Conformance.fetchListed("mpeg4audio-" + stream + ".mp4")));
    }

    /**
     * Returns a reference waveform.
     *
     * @param name the reference name, without its extension
     * @return the planes
     */
    private static float[][] reference(String name) {
        return AacTestSupport.readWav(Fixtures.read(Conformance.fetchListed("mpeg4audio-" + name + ".wav")));
    }

    /**
     * Decodes a stream at each layer and compares it with the reference of that layer.
     *
     * @param stream the stream name, with its {@code _epN} suffix for the error resilient streams
     * @param layers the layers of its references
     * @param noise whether the stream uses noise substitution, so that only band energies can match
     */
    private static void check(String stream, List<Integer> layers, boolean noise) {
        var tracks = tracks(stream);
        var testCase = stream.contains("_ep") ? stream.substring(0, stream.lastIndexOf('_')) : stream;
        for (var layer : layers) {
            var decoded = AacTestSupport.decodeInstances(tracks, AacDecoderOptions.builder().maxLayer(layer).build());
            var name = testCase + "_lay" + layer;
            var expected = reference(name);
            assertEquals(expected.length, decoded.planes().length, name + " channels");
            for (var c = 0; c < expected.length; c++) {
                var actual = decoded.planes()[c];
                if (noise) {
                    var deviation = PnsEnergy.meanBandDeviation(expected[c], actual, decoded.sampleRate());
                    assertTrue(deviation < 0.5, name + " channel " + c + ": mean band energy deviation " + deviation
                            + " dB");
                    assertTrue(error(expected[c], actual)[0] > RMS_LIMIT, name + " channel " + c
                            + " matches sample by sample, so it holds no noise substitution");
                } else {
                    compare(name + " channel " + c, expected[c], actual);
                }
            }
        }
    }

    /**
     * Compares a decoded channel with its reference under the criterion of the standard.
     *
     * @param name the channel name for messages
     * @param reference the reference samples
     * @param actual the decoded samples
     */
    private static void compare(String name, float[] reference, float[] actual) {
        assertTrue(actual.length >= reference.length, name + " length " + actual.length + " < " + reference.length);
        var error = error(reference, actual);
        assertTrue(error[0] <= RMS_LIMIT && error[1] <= PEAK_LIMIT, name + ": RMS " + error[0] * 32768 + " and peak "
                + error[1] * 32768 + " LSB16");
    }

    /**
     * Returns the RMS and peak errors of a decoded channel against its reference.
     *
     * @param reference the reference samples
     * @param actual the decoded samples
     * @return the RMS error and the peak error, of full scale
     */
    private static double[] error(float[] reference, float[] actual) {
        double squared = 0;
        double peak = 0;
        for (var i = 0; i < reference.length; i++) {
            var value = i < actual.length ? Math.clamp(actual[i], -1.0, 32767 / 32768.0) : 0;
            var difference = Math.abs(reference[i] - value);
            squared += difference * difference;
            peak = Math.max(peak, difference);
        }
        return new double[]{Math.sqrt(squared / reference.length), peak};
    }
}
