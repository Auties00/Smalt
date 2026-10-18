package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the AAC default-options encoder constructor, the decoder's signaling accessors, and the
 * decoder's handling of an exception thrown by the caller's consumer.
 */
@DisplayName("AAC API additions")
class AacApiAdditionsTest {
    private static List<Packet> encode(AudioEncoder encoder) {
        var packets = new ArrayList<Packet>();
        var buffer = new AudioBuffer(ChannelLayout.STEREO, 48_000, 1500);
        for (var block = 0; block < 8; block++) {
            for (var channel = 0; channel < 2; channel++) {
                var plane = buffer.plane(channel);
                for (var i = 0; i < 1500; i++) {
                    var t = (block * 1500 + i) / 48_000.0;
                    plane[i] = (float) (0.4 * Math.sin(2 * Math.PI * (440 + 220 * channel) * t));
                }
            }
            buffer.setFrames(1500);
            encoder.encode(buffer, packets::add);
        }
        encoder.flush(packets::add);
        return packets;
    }

    @Test
    @DisplayName("AacEncoder() encodes as AacEncoder(AacEncoderOptions.defaults())")
    void defaultEncoder() {
        try (var plain = new AacEncoder(); var explicit = new AacEncoder(AacEncoderOptions.defaults())) {
            assertEquals(AacEncoderOptions.defaults(), plain.options());
            assertArrayEquals(explicit.codecConfig().orElseThrow(), plain.codecConfig().orElseThrow());
            var expected = encode(explicit);
            var actual = encode(plain);
            assertEquals(expected.size(), actual.size());
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i).data(), actual.get(i).data());
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // name, configuration fields as (value, bits) pairs, expected SBR and PS signaling
            "LC stereo, 2 5 3 4 2 4 0 3, false, false",
            "explicit HE-AAC stereo, 5 5 6 4 2 4 3 4 2 5 0 3, true, false",
            "explicit HE-AAC v2 mono, 29 5 6 4 1 4 3 4 2 5 0 3, true, true",
            "sync extension without SBR, 2 5 6 4 2 4 0 3 695 11 5 5 0 1, false, false",
            "sync extension with SBR and PS, 2 5 6 4 1 4 0 3 695 11 5 5 1 1 3 4 1352 11 1 1, true, true"
    })
    @DisplayName("the signaling accessors report the extensions the configuration signals")
    void signaling(String what, String fields, boolean sbr, boolean ps) {
        var values = Arrays.stream(fields.split(" ")).mapToInt(Integer::parseInt).toArray();
        var decoder = new AacDecoder(HeAacSignalingTest.track(HeAacSignalingTest.bits(values), 0));
        assertEquals(sbr, decoder.spectralBandReplicationSignaled(), what);
        assertEquals(ps, decoder.parametricStereoSignaled(), what);
    }

    @Test
    @DisplayName("decodesSpectralBandReplication() is false when the options turn the extension off")
    void extensionOff() {
        var config = HeAacSignalingTest.bits(5, 5, 6, 4, 2, 4, 3, 4, 2, 5, 0, 3);
        var on = new AacDecoder(HeAacSignalingTest.track(config, 0));
        var off = new AacDecoder(HeAacSignalingTest.track(config, 0),
                AacDecoderOptions.builder().spectralBandReplication(false).build());
        assertEquals(true, on.decodesSpectralBandReplication());
        assertEquals(false, off.decodesSpectralBandReplication());
        assertEquals(true, off.spectralBandReplicationSignaled());
    }

    @Test
    @DisplayName("an exception thrown by the consumer reaches the caller unchanged")
    void consumerFailure() {
        try (var encoder = new AacEncoder()) {
            var packets = encode(encoder);
            var decoder = new AacDecoder(HeAacSignalingTest.track(encoder.codecConfig().orElseThrow(), 48_000));
            var failure = new IllegalStateException("consumer");
            var thrown = assertThrows(IllegalStateException.class,
                    () -> decoder.decode(packets.get(2), buffer -> {
                        throw failure;
                    }));
            assertSame(failure, thrown);
        }
    }
}
