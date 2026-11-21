package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.opus.internal.OpusStreamDecoder;
import com.github.auties00.smalt.testkit.audio.OpusCompare;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the RFC 6716 / RFC 8251 decoder conformance procedure ({@code tests/run_vectors.sh}) on
 * the twelve official test vectors: every vector is decoded like {@code opus_demo -d <rate>
 * <channels>} (int16 output through the soft clipper, lost-packet concealment for empty packets),
 * the final range of every packet must equal the encoder's (proving bit-exact entropy decoding),
 * and the output must pass the ported {@code opus_compare} against either the stereo or the mono
 * reference, at 48, 24, 16, 12 and 8 kHz, mono and stereo.
 */
class OpusConformanceTest {
    private static final int[] RATES = {48000, 24000, 16000, 12000, 8000};

    static Stream<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        for (var vector = 1; vector <= 12; vector++) {
            for (var channels = 1; channels <= 2; channels++) {
                cases.add(Arguments.of(vector, channels));
            }
        }
        return cases.stream();
    }

    /**
     * Decodes a vector like {@code opus_demo -d}, checking final ranges; returns int16 output.
     * Like {@code opus_demo}, empty (lost) packets are concealed when the next packet arrives, so
     * trailing lost packets produce no output.
     */
    static short[] decode(List<OpusVectors.BitPacket> packets, int rate, int channels) {
        var decoder = new OpusStreamDecoder(rate, channels);
        var maxFrame = 48000 * 2;
        var pcm = new float[maxFrame * channels];
        var sink = new OpusVectors.FloatSink();
        var lostPrevious = true;
        var end = packets.size();
        while (end > 0 && packets.get(end - 1).data().length == 0) {
            end--;
        }
        for (var index = 0; index < end; index++) {
            var packet = packets.get(index);
            var lost = packet.data().length == 0;
            int samples;
            if (lost) {
                samples = decoder.decodeNative(null, 0, 0, pcm, 0, decoder.lastPacketDuration(), false, false, null, true);
            } else {
                samples = decoder.decodeNative(packet.data(), 0, packet.data().length, pcm, 0, maxFrame, false, false, null, true);
            }
            assertTrue(samples >= 0, "packet " + index + " failed with " + samples);
            sink.append(pcm, 0, samples * channels);
            if (!lost && !lostPrevious) {
                assertEquals(packet.finalRange(), decoder.finalRange(),
                        "final range of packet " + index + " (toc " + (packet.data()[0] & 0xFF) + ")");
            }
            lostPrevious = lost;
        }
        return OpusVectors.toPcm16(sink.data(), sink.size());
    }

    @ParameterizedTest(name = "testvector{0} channels {1}")
    @MethodSource("cases")
    @DisplayName("decodes the RFC 8251 vectors at every rate with matching final ranges and passes opus_compare")
    void vector(int vector, int channels) throws Exception {
        var directory = OpusVectors.directory();
        var name = String.format("testvector%02d", vector);
        var packets = OpusVectors.readBit(directory.resolve(name + ".bit"));
        try (var executor = Executors.newFixedThreadPool(Math.min(4, Runtime.getRuntime().availableProcessors()))) {
            var stereo = executor.submit(() -> OpusCompare.prepare(OpusVectors.readPcm16(directory.resolve(name + ".dec")), channels));
            var mono = executor.submit(() -> OpusCompare.prepare(OpusVectors.readPcm16(directory.resolve(name + "m.dec")), channels));
            var results = new ArrayList<Future<String>>();
            for (var rate : RATES) {
                results.add(executor.submit(() -> {
                    var decoded = decode(packets, rate, channels);
                    var first = OpusCompare.compare(stereo.get(), decoded, rate);
                    var second = OpusCompare.compare(mono.get(), decoded, rate);
                    System.err.printf("[opus-conformance] %s %d ch %d Hz: quality %.1f %% / %.1f %%%n", name, channels, rate,
                            first.quality(), second.quality());
                    if (!first.passes() && !second.passes()) {
                        return name + " fails at " + rate + " Hz: quality " + first.quality() + " / " + second.quality();
                    }
                    return null;
                }));
            }
            var failures = new ArrayList<String>();
            for (var result : results) {
                try {
                    var failure = result.get();
                    if (failure != null) {
                        failures.add(failure);
                    }
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof AssertionError error) {
                        throw error;
                    }
                    throw exception;
                }
            }
            assertTrue(failures.isEmpty(), String.join("; ", failures));
        }
    }

    @ParameterizedTest(name = "testvector{0}")
    @MethodSource("vectorIds")
    @DisplayName("the ported opus_compare accepts each reference against itself")
    void compareSelf(int vector) {
        var directory = OpusVectors.directory();
        var reference = OpusVectors.readPcm16(directory.resolve(String.format("testvector%02d.dec", vector)));
        var result = OpusCompare.compare(reference, reference, 2, 48000);
        assertTrue(result.passes());
        assertEquals(100.0, result.quality(), 1e-6);
    }

    static Stream<Integer> vectorIds() {
        return Stream.of(1, 7, 12);
    }
}
