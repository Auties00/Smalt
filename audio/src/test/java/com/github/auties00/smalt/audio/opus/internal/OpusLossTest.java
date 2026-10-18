package com.github.auties00.smalt.audio.opus.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the loss paths of the stream decoder that files never trigger: in-band FEC recovery
 * from the next packet and packet-loss concealment in every mode, checking durations, finite
 * output and that decoding resumes normally afterwards.
 */
class OpusLossTest {
    @ParameterizedTest(name = "{0} Hz, {1} bps, app {2}, mode {3}")
    @CsvSource({"16000,24000,2048,1000", "48000,32000,2049,1001", "48000,64000,2049,1002", "8000,12000,2048,1000"})
    @DisplayName("recovers lost frames with FEC or PLC and resumes decoding")
    void loss(int rate, int bitrate, int application, int mode) {
        var encoder = new OpusStreamEncoder(rate, 1, application);
        encoder.setBitrate(bitrate);
        encoder.setInbandFec(1);
        encoder.setPacketLossPerc(20);
        encoder.setForceMode(mode);
        var frame = rate / 50;
        var packets = new ArrayList<byte[]>();
        var pcm = new float[frame];
        var out = new byte[1500];
        for (var n = 0; n < 60; n++) {
            for (var i = 0; i < frame; i++) {
                var t = n * frame + i;
                pcm[i] = (float) (0.3 * StrictMath.sin(2 * Math.PI * 220 * t / rate) * (1 + StrictMath.sin(t * 0.001)));
            }
            var length = encoder.encodeFloat(pcm, 0, frame, out, 0, out.length);
            assertTrue(length > 0);
            packets.add(java.util.Arrays.copyOf(out, length));
        }
        var decoder = new OpusStreamDecoder(48000, 1);
        var buffer = new float[5760];
        for (var n = 0; n < packets.size(); n++) {
            int samples;
            if (n % 5 == 3) {
                var next = packets.get(n + 1);
                samples = decoder.decodeNative(next, 0, next.length, buffer, 0, 960, true, false, null, false);
            } else if (n % 7 == 5) {
                samples = decoder.decodeNative(null, 0, 0, buffer, 0, 960, false, false, null, false);
            } else {
                var packet = packets.get(n);
                samples = decoder.decodeNative(packet, 0, packet.length, buffer, 0, 5760, false, false, null, false);
            }
            assertEquals(960, samples, "packet " + n);
            for (var i = 0; i < samples; i++) {
                assertTrue(Float.isFinite(buffer[i]) && Math.abs(buffer[i]) < 4, "sample " + i + " of packet " + n);
            }
        }
    }
}
