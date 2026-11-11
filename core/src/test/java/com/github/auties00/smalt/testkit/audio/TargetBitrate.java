package com.github.auties00.smalt.testkit.audio;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures how an {@link AudioEncoder} follows {@link AudioEncoder#setTargetBitrate(long)} changes while it runs:
 * the input is fed in 20 ms chunks, the target changes at fixed points of the input, and the bitrate of the packets
 * coded after each change, once the encoder had a number of frames to settle, must be within a tolerance of the
 * target.
 */
public final class TargetBitrate {
    private TargetBitrate() {
    }

    /**
     * One phase of a schedule.
     *
     * @param seconds the duration of the phase
     * @param bitsPerSecond the target set at the start of the phase, or 0 to keep the encoder's own
     */
    public record Phase(double seconds, long bitsPerSecond) {
    }

    /**
     * Encodes a signal through a schedule of targets and asserts the bitrate of every phase.
     *
     * @param encoder the encoder
     * @param planes the input samples, at least as long as the schedule
     * @param settleSeconds the duration at the start of each phase excluded from the measurement
     * @param tolerance the largest relative deviation of the measured bitrate from the target
     * @param phases the schedule
     * @return the measured bitrate of each phase, in bits per second
     */
    public static double[] assertFollows(AudioEncoder encoder, float[][] planes, double settleSeconds, double tolerance,
                                         Phase... phases) {
        var rate = encoder.sampleRate();
        var chunk = rate / 50;
        var buffer = new AudioBuffer(encoder.layout(), rate, chunk);
        var packets = new ArrayList<Packet>();
        var boundaries = new long[phases.length + 1];
        var offset = 0;
        for (var p = 0; p < phases.length; p++) {
            if (phases[p].bitsPerSecond() > 0) {
                encoder.setTargetBitrate(phases[p].bitsPerSecond());
            }
            boundaries[p] = offset;
            var end = offset + (long) (phases[p].seconds() * rate);
            while (offset < end) {
                var frames = (int) Math.min(chunk, end - offset);
                for (var c = 0; c < planes.length; c++) {
                    System.arraycopy(planes[c], offset, buffer.plane(c), 0, frames);
                }
                buffer.setFrames(frames);
                encoder.encode(buffer, packets::add);
                offset += frames;
            }
        }
        boundaries[phases.length] = offset;
        encoder.flush(packets::add);
        var measured = new double[phases.length];
        var report = new StringBuilder();
        for (var p = 0; p < phases.length; p++) {
            var from = boundaries[p] + (long) (settleSeconds * rate) + encoder.delay();
            var to = boundaries[p + 1];
            measured[p] = bitrate(packets, from, to, rate);
            report.append(String.format(java.util.Locale.ROOT, "%n  phase %d: target %d, measured %.0f", p,
                    phases[p].bitsPerSecond(), measured[p]));
        }
        for (var p = 0; p < phases.length; p++) {
            var target = phases[p].bitsPerSecond();
            if (target > 0) {
                assertTrue(Math.abs(measured[p] - target) <= tolerance * target, "phase " + p
                        + " misses its target by more than " + tolerance * 100 + "%" + report);
            }
        }
        return measured;
    }

    /**
     * Returns the bitrate of the packets whose timestamps fall in a span of the input.
     *
     * @param packets the packets, with timestamps in samples
     * @param from the first input sample
     * @param to the index after the last input sample
     * @param rate the sampling rate
     * @return the bitrate in bits per second
     */
    public static double bitrate(List<Packet> packets, long from, long to, int rate) {
        var bits = 0L;
        var samples = 0L;
        for (var packet : packets) {
            if (packet.pts() >= from && packet.pts() + packet.duration() <= to) {
                bits += 8L * packet.size();
                samples += packet.duration();
            }
        }
        return samples == 0 ? 0 : bits * (double) rate / samples;
    }
}
