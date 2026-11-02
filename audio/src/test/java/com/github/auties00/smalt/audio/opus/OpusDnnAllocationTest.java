package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamEncoder;
import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the neural paths allocate nothing per packet once warm: the DRED encoder (latents, payload coding and
 * the padding extension), and a decoder of complexity 7 that decodes, conceals with the deep PLC, enhances with
 * NoLACE and rebuilds from DRED. Like {@code OpusEncoderAllocationTest} it runs on the scalar leg only, since a vector
 * kernel allocates until the JIT compiles it.
 */
class OpusDnnAllocationTest {
    private static Object threads;
    private static Method allocated;

    private static void counter() {
        Skip.unless(!Simd.ENABLED, "simd:enabled", "vector kernels allocate until compiled; run with -Dsmalt.test.simd=false");
        try {
            threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null);
            allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw Skip.now("env:allocation-counter", "thread allocation counter unavailable: " + exception);
        }
    }

    private static long measure(Runnable work) throws Exception {
        var overhead = Long.MAX_VALUE;
        for (var i = 0; i < 10; i++) {
            var before = (long) allocated.invoke(threads);
            overhead = Math.min(overhead, (long) allocated.invoke(threads) - before);
        }
        long bytes = 0;
        for (var pass = 0; pass < 40; pass++) {
            var before = (long) allocated.invoke(threads);
            work.run();
            bytes = (long) allocated.invoke(threads) - before - overhead;
            if (pass >= 2 && bytes == 0) {
                break;
            }
        }
        return bytes;
    }

    @ParameterizedTest(name = "{0} {1} us")
    @CsvSource({"vbr, 20000", "cbr, 20000", "cvbr, 40000", "vbr, 60000"})
    @DisplayName("the DRED encoder allocates nothing per packet in steady state")
    void encoder(String mode, int micros) throws Exception {
        counter();
        var input = OpusEncoderGoldenTest.input("speech48k");
        var encoder = new OpusStreamEncoder(48000, 1, OpusDefines.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(32000);
        encoder.setVbr(!mode.equals("cbr"));
        encoder.setVbrConstraint(mode.equals("cvbr"));
        encoder.setPacketLossPerc(20);
        encoder.setDredDuration(100);
        var frame = 48 * micros / 1000;
        var pcm = new float[input.length];
        for (var i = 0; i < pcm.length; i++) {
            pcm[i] = input[i] / 32768f;
        }
        var frames = pcm.length / frame;
        var packet = new byte[1276 * 6];
        var position = new int[1];
        var carried = new int[1];
        Runnable eight = () -> {
            for (var k = 0; k < 8; k++) {
                var length = encoder.encodeFloat(pcm, (position[0]++ % frames) * frame, frame, packet, 0,
                        packet.length);
                if (length <= 0) {
                    throw new AssertionError("encode failed: " + length);
                }
                if (OpusPackets.hasDred(packet, 0, length)) {
                    carried[0]++;
                }
            }
        };
        for (var k = 0; k < 8; k++) {
            eight.run();
        }
        // hasDred allocates its parse scratch; count DRED packets outside the measured span.
        carried[0] = 0;
        Runnable measured = () -> {
            for (var k = 0; k < 8; k++) {
                if (encoder.encodeFloat(pcm, (position[0]++ % frames) * frame, frame, packet, 0, packet.length) <= 0) {
                    throw new AssertionError("encode failed");
                }
            }
        };
        assertEquals(0, measure(measured), "bytes allocated over 8 packets");
        eight.run();
        assertTrue(carried[0] > 0, "the measured configuration codes DRED");
    }

    @ParameterizedTest(name = "{0} at complexity {1}")
    @CsvSource({"decode, 0", "conceal, 0", "decode, 7", "conceal, 7", "parse, 7", "recover, 7"})
    @DisplayName("a decoder allocates nothing per packet while decoding, concealing and recovering")
    void decoder(String operation, int complexity) throws Exception {
        counter();
        List<byte[]> packets = OpusDnnGoldenTest.packets("e_dred48");
        var decoder = new OpusDecoder(OpusDecoderOptions.of(48000, 1).toBuilder().complexity(complexity).build());
        var dred = new OpusDred();
        var pcm = new float[5760];
        var position = new int[1];
        var concealing = operation.equals("conceal");
        var recovering = operation.equals("recover");
        var parsing = operation.equals("parse");
        Runnable prepare = () -> {
            var packet = packets.get(100 + position[0] % (packets.size() - 100));
            if (recovering) {
                dred.parse(packet, 0, packet.length, 1920, 48000);
            }
        };
        Runnable work = () -> {
            for (var k = 0; k < 12; k++) {
                var packet = packets.get(100 + position[0]++ % (packets.size() - 100));
                if (k % 3 == 1) {
                    if (concealing) {
                        decoder.conceal(pcm, 0, 960);
                    } else if (parsing) {
                        dred.parse(packet, 0, packet.length, 1920, 48000);
                    } else if (recovering) {
                        decoder.recover(dred, 1920, pcm, 0, 960);
                        decoder.recover(dred, 960, pcm, 0, 960);
                    }
                }
                decoder.decode(packet, 0, packet.length, pcm, 0, 5760);
            }
        };
        prepare.run();
        for (var k = 0; k < 6; k++) {
            work.run();
        }
        assertEquals(0, measure(work), "bytes allocated over 12 packets");
    }
}
