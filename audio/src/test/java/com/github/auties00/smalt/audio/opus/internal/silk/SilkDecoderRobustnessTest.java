package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds the SILK decoder hostile input: random payloads with every control combination the Opus
 * layer can produce, and bit-flipped or truncated versions of the golden fixtures. Every call
 * must return without throwing, produce the expected number of samples, and finish in bounded
 * time.
 */
class SilkDecoderRobustnessTest {
    private static final int[] API_RATES = {8000, 12000, 16000, 24000, 48000};
    private static final int[] INTERNAL_RATES = {8000, 12000, 16000};
    private static final int[] PAYLOADS = {10, 20, 40, 60};

    @Test
    @DisplayName("decodes random payloads under every control combination without failing")
    void randomPayloads() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            var random = new Random(0xC0BA17L);
            var out = new short[48 * 60 * 2 + 64];
            var n = new int[1];
            for (var stream = 0; stream < 400; stream++) {
                var decoder = new SilkDecoder();
                var control = new SilkDecControl();
                control.nChannelsAPI = 1 + random.nextInt(2);
                control.API_sampleRate = API_RATES[random.nextInt(API_RATES.length)];
                for (var packet = 0; packet < 25; packet++) {
                    var roll = random.nextInt(10);
                    // the Opus layer never runs the SILK PLC before a packet was decoded
                    var lostFlag = packet == 0 ? 0 : roll < 7 ? 0 : roll < 9 ? 1 : 2;
                    if (lostFlag == 1) {
                        control.payloadSize_ms = random.nextBoolean() ? 10 : 20;
                    } else {
                        control.payloadSize_ms = PAYLOADS[random.nextInt(PAYLOADS.length)];
                        control.internalSampleRate = INTERNAL_RATES[random.nextInt(INTERNAL_RATES.length)];
                        control.nChannelsInternal = 1 + random.nextInt(2);
                    }
                    var data = new byte[random.nextInt(random.nextBoolean() ? 8 : 400)];
                    random.nextBytes(data);
                    if (random.nextInt(4) == 0) {
                        java.util.Arrays.fill(data, (byte) (random.nextBoolean() ? 0 : 0xFF));
                    }
                    var dec = new RangeDecoder(data, 0, data.length);
                    var frameMs = control.payloadSize_ms == 10 ? 10 : 20;
                    var calls = lostFlag == 1 ? 1 : control.payloadSize_ms / frameMs;
                    for (var call = 0; call < calls; call++) {
                        var first = call == 0 ? 1 : 0;
                        var ret = assertDoesNotThrow(() -> decoder.decode(control, lostFlag, first, dec, out, 0, n));
                        assertEquals(0, ret);
                        assertEquals(control.API_sampleRate / 1000 * frameMs, n[0]);
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("decodes bit-flipped and truncated golden streams without failing")
    void mutatedFixtures() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            var random = new Random(0xF00DL);
            String[] streams = {"wb_mono_20", "wb_stereo_20", "mb_stereo_60_fec", "wb_mono_40", "wb_dtx", "stereo_switch"};
            var variants = 0;
            for (var name : streams) {
                var packets = SilkReferenceDriver.parseBit(SilkDecoderGoldenTest.readBytes(name + ".bit"));
                for (var variant = 0; variant < 40; variant++) {
                    var mutated = new ArrayList<SilkReferenceDriver.Packet>();
                    for (var packet : packets) {
                        var data = packet.data().clone();
                        if (data.length > 1 && random.nextInt(4) == 0) {
                            switch (random.nextInt(3)) {
                                case 0 -> data[1 + random.nextInt(data.length - 1)] ^= (byte) (1 << random.nextInt(8));
                                case 1 -> data = java.util.Arrays.copyOf(data, 1 + random.nextInt(data.length));
                                default -> {
                                    for (var i = 1; i < data.length; i++) {
                                        data[i] = (byte) random.nextInt(256);
                                    }
                                }
                            }
                        }
                        if (data.length > 0 && isValidSilkFraming(data)) {
                            mutated.add(new SilkReferenceDriver.Packet(data, packet.finalRange()));
                        }
                    }
                    var rate = API_RATES[random.nextInt(API_RATES.length)];
                    var driver = new SilkReferenceDriver(rate, 1 + random.nextInt(2));
                    var pcm = assertDoesNotThrow(() -> driver.decodeAll(mutated, random.nextInt(6), random.nextBoolean()));
                    assertTrue(pcm.length > 0);
                    variants++;
                }
            }
            assertEquals(240, variants);
        });
    }

    private static boolean isValidSilkFraming(byte[] data) {
        try {
            var frames = SilkReferenceDriver.frames(data);
            for (var f : frames) {
                if (f[0] < 0 || f[1] < 0 || f[0] + f[1] > data.length) {
                    return false;
                }
            }
            return frames.length > 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
