package com.github.auties00.smalt.audio.opus.internal.celt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feeds the CELT decoder arbitrary and mutated frames and the CELT encoder extreme input, and
 * checks the argument validation of both: every call must return a sample count or a negative
 * Opus error code, never throw, and produce finite output.
 */
class CeltRobustnessTest {
    private static void assertFinite(float[] pcm, int n) {
        for (var i = 0; i < n; i++) {
            assertTrue(Float.isFinite(pcm[i]), "non-finite sample at " + i);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("decodes random bytes of every length, frame size and band configuration")
    void randomFrames(int seed) {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var random = new Random(seed);
            var pcm = new float[960 * 2];
            for (var trial = 0; trial < 3000; trial++) {
                var rates = new int[]{8000, 12000, 16000, 24000, 48000};
                var rate = rates[random.nextInt(rates.length)];
                var channels = 1 + random.nextInt(2);
                var decoder = new CeltDecoder(rate, channels);
                decoder.setChannels(1 + random.nextInt(2));
                var hybrid = random.nextBoolean();
                decoder.setStartBand(hybrid ? 17 : 0);
                decoder.setEndBand(hybrid ? 19 + 2 * random.nextInt(2) : new int[]{13, 17, 19, 21}[random.nextInt(4)]);
                for (var frame = 0; frame < 4; frame++) {
                    var frameSize = (120 << random.nextInt(4)) * rate / 48000;
                    var len = random.nextInt(8) == 0 ? random.nextInt(3) : random.nextInt(1276);
                    var data = new byte[len];
                    random.nextBytes(data);
                    var ret = decoder.decodeWithEc(len == 0 ? null : data, 0, len, pcm, 0, frameSize, null, 0);
                    assertTrue(ret == frameSize || ret == Celt.OPUS_INTERNAL_ERROR, "returned " + ret);
                    assertFinite(pcm, frameSize * channels);
                }
            }
        });
    }

    @Test
    @DisplayName("decodes truncated and bit-flipped versions of real frames")
    void mutatedFrames() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var config = new CeltEncodeHarness.Config(48000, 2, 960, 96000, 1, 1, 10, 2, 0, 24, 0, 0, 0, 0, 0, 2, 0);
            var packets = CeltOnlyHarness.parseBit(CeltEncodeHarness.encode(config,
                    CeltEncodeHarness.signal(48000, 2, 1.0)));
            var random = new Random(5);
            var decoder = new CeltDecoder(48000, 2);
            var pcm = new float[1920];
            for (var round = 0; round < 6; round++) {
                for (var packet : packets) {
                    var data = packet.data().clone();
                    var len = data.length;
                    switch (round % 3) {
                        case 0 -> len = random.nextInt(len + 1);
                        case 1 -> {
                            for (var f = 0; f < 1 + random.nextInt(8); f++) {
                                data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                            }
                        }
                        default -> data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                    }
                    var ret = decoder.decodeWithEc(data, 0, len, pcm, 0, 960, null, 0);
                    assertTrue(ret == 960 || ret == Celt.OPUS_INTERNAL_ERROR, "returned " + ret);
                    assertFinite(pcm, 1920);
                }
            }
        });
    }

    @Test
    @DisplayName("rejects invalid decoder arguments with Opus error codes")
    void decoderArguments() {
        assertThrows(IllegalArgumentException.class, () -> new CeltDecoder(44100, 2));
        assertThrows(IllegalArgumentException.class, () -> new CeltDecoder(48000, 3));
        var decoder = new CeltDecoder(48000, 1);
        var pcm = new float[960];
        assertEquals(Celt.OPUS_BAD_ARG, decoder.decodeWithEc(new byte[10], 0, 10, pcm, 0, 100, null, 0));
        assertEquals(Celt.OPUS_BAD_ARG, decoder.decodeWithEc(new byte[1300], 0, 1276, pcm, 0, 960, null, 0));
        assertEquals(Celt.OPUS_BAD_ARG, decoder.setStartBand(21));
        assertEquals(Celt.OPUS_BAD_ARG, decoder.setEndBand(0));
        assertEquals(Celt.OPUS_BAD_ARG, decoder.setChannels(3));
        decoder.setStartBand(17);
        decoder.setEndBand(13);
        assertEquals(Celt.OPUS_BAD_ARG, decoder.decodeWithEc(new byte[10], 0, 10, pcm, 0, 960, null, 0));
    }

    @Test
    @DisplayName("encodes extreme, silent and non-finite input without failing")
    void extremeInput() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var random = new Random(8);
            var buf = new byte[1275];
            for (var trial = 0; trial < 200; trial++) {
                var channels = 1 + random.nextInt(2);
                var encoder = new CeltEncoder(48000, channels);
                encoder.setSignaling(0);
                encoder.setComplexity(random.nextInt(11));
                encoder.setBitrate(6000 + random.nextInt(500000));
                encoder.setVbr(random.nextInt(2));
                var pcm = new float[960 * channels];
                for (var frame = 0; frame < 5; frame++) {
                    for (var i = 0; i < pcm.length; i++) {
                        pcm[i] = switch (trial % 6) {
                            case 0 -> (float) random.nextGaussian() * 1e6f;
                            case 1 -> 0f;
                            case 2 -> random.nextBoolean() ? Float.MAX_VALUE : -Float.MAX_VALUE;
                            case 3 -> random.nextInt(50) == 0 ? Float.NaN : (float) random.nextGaussian();
                            case 4 -> random.nextInt(50) == 0 ? Float.POSITIVE_INFINITY : 1e-38f;
                            default -> i % 2 == 0 ? 1f : -1f;
                        };
                    }
                    var ret = encoder.encodeWithEc(pcm, 0, 960, buf, 0, 2 + random.nextInt(1274), null);
                    assertTrue(ret >= 2 && ret <= 1275, "returned " + ret);
                }
            }
        });
    }

    @Test
    @DisplayName("rejects invalid encoder arguments with Opus error codes")
    void encoderArguments() {
        assertThrows(IllegalArgumentException.class, () -> new CeltEncoder(44100, 1));
        assertThrows(IllegalArgumentException.class, () -> new CeltEncoder(48000, 0));
        var encoder = new CeltEncoder(48000, 1);
        var buf = new byte[100];
        assertEquals(Celt.OPUS_BAD_ARG, encoder.encodeWithEc(new float[960], 0, 960, buf, 0, 1, null));
        assertEquals(Celt.OPUS_BAD_ARG, encoder.encodeWithEc(new float[960], 0, 100, buf, 0, 100, null));
        assertEquals(Celt.OPUS_BAD_ARG, encoder.setBitrate(400));
        assertEquals(Celt.OPUS_BAD_ARG, encoder.setPrediction(3));
        assertEquals(Celt.OPUS_BAD_ARG, encoder.setLsbDepth(7));
        assertEquals(Celt.OPUS_BAD_ARG, encoder.setPacketLossPerc(101));
    }
}
