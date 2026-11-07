package com.github.auties00.smalt.testkit.audio;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives an {@link AudioDecoder} through packet loss traces, the way a real-time receiver uses
 * {@link AudioDecoder#conceal(int, java.util.function.Consumer)}, and measures what the concealment did.
 *
 * <p>A trace decodes every packet once without loss (the reference) and once with a set of packets lost: each run of
 * consecutive lost packets is replaced by one {@code conceal} call for the samples the reference decode produced for
 * them, as a receiver derives the span from the timestamp gap. {@link #assertConceals} then checks the codec-neutral
 * contract on the two decodes: the output keeps its length, no sample step around a loss exceeds the steps of the
 * reference by more than a factor (no click), the concealed span is neither louder than the signal around it by more
 * than a bound nor, when the codec repeats the signal, silent after a signal that was not, the decode after the loss
 * converges back to the reference, and the concealment is deterministic.
 */
public final class PacketLoss {
    private PacketLoss() {
    }

    /**
     * An encoded stream.
     *
     * @param track the track a decoder is created from
     * @param packets the packets in decoding order
     */
    public record Encoded(AudioTrack track, List<Packet> packets) {
    }

    /**
     * One run of lost packets of a lossy decode.
     *
     * @param firstPacket the index of the first lost packet
     * @param lastPacket the index of the last lost packet
     * @param from the index of the first concealed output sample
     * @param to the index after the last concealed output sample
     * @param requested the number of samples asked of {@code conceal}
     * @param returned the number of samples {@code conceal} reported
     */
    public record Run(int firstPacket, int lastPacket, int from, int to, int requested, int returned) {
    }

    /**
     * A decode of a trace.
     *
     * @param planes the decoded samples of every channel
     * @param starts the index of the first output sample of each packet, the concealed span starting at a run's first
     * packet
     * @param lengths the number of output samples of each packet, the whole concealed span counted on a run's first
     * packet
     * @param runs the runs of lost packets
     * @param layout the layout of the last block
     * @param sampleRate the sampling rate of the last block
     */
    public record Decoded(float[][] planes, int[] starts, int[] lengths, List<Run> runs, ChannelLayout layout,
                          int sampleRate) {
        /** {@return the number of samples per channel} */
        public int length() {
            return planes[0].length;
        }
    }

    /**
     * The bounds a concealment must meet.
     *
     * @param exactLength whether every {@code conceal} call must produce exactly the span it was asked for
     * @param stepFactor the largest ratio of a sample step from just before a loss to the end of the recovery to the
     * largest step of the reference decode
     * @param maxGainDb the largest level of a concealed span over the louder of the reference's level over the same
     * span and over as many samples before it, in decibels
     * @param audibleSamples the number of samples at the start of each concealed span that must not be silent (at
     * most {@code minGainDb} below the reference's level over as many samples before the loss, when those are not
     * silent), or 0 to skip the check
     * @param minGainDb the largest attenuation of the start of a concealed span, in decibels, with
     * {@code audibleSamples}
     * @param recoveryPackets the number of packets after a loss after which the decode must have converged
     * @param recoverySnr the smallest SNR of the converged decode against the reference, in decibels
     */
    public record Bounds(boolean exactLength, double stepFactor, double maxGainDb, int audibleSamples,
                         double minGainDb, int recoveryPackets, double recoverySnr) {
    }

    /**
     * Returns a deterministic test signal: a voiced-like harmonic tone (fundamental near 140 Hz with a slow
     * vibrato, harmonics falling 4 dB each up to a quarter of the rate) under a syllable-rate envelope, with noise
     * 30 dB below it; each channel has its own phase and level.
     *
     * @param rate the sampling rate in hertz
     * @param channels the number of channels
     * @param seconds the duration
     * @return the samples of every channel, peaking near 0.5
     */
    public static float[][] signal(int rate, int channels, double seconds) {
        var frames = (int) (rate * seconds);
        var planes = new float[channels][frames];
        var random = new Random(0x5EED);
        var harmonics = (int) Math.max(1, rate / 4.0 / 140);
        for (var c = 0; c < channels; c++) {
            var phase = 0.0;
            for (var i = 0; i < frames; i++) {
                var t = (double) i / rate;
                var f0 = 140 * (1 + 0.03 * StrictMath.sin(2 * Math.PI * 3 * t));
                phase += 2 * Math.PI * f0 / rate;
                var tone = 0.0;
                for (var h = 1; h <= harmonics; h++) {
                    tone += StrictMath.pow(10, -4.0 * (h - 1) / 20) * StrictMath.sin(h * phase + c + h);
                }
                var envelope = 0.6 + 0.4 * StrictMath.sin(2 * Math.PI * 4 * t + c);
                var noise = (random.nextDouble() - 0.5) * 0.03;
                planes[c][i] = (float) ((0.2 * envelope * tone + noise) * (1 - 0.1 * c));
            }
        }
        return planes;
    }

    /**
     * Encodes planar samples with an encoder in chunks of 960 samples and flushes it.
     *
     * @param encoder the encoder, of the signal's layout and rate
     * @param planes the samples of every channel
     * @return the track and packets
     */
    public static Encoded encode(AudioEncoder encoder, float[][] planes) {
        var packets = new ArrayList<Packet>();
        var rate = encoder.sampleRate();
        var layout = encoder.layout();
        var chunk = new AudioBuffer(layout, rate, 960);
        for (var offset = 0; offset < planes[0].length; offset += 960) {
            var frames = Math.min(960, planes[0].length - offset);
            for (var c = 0; c < planes.length; c++) {
                System.arraycopy(planes[c], offset, chunk.plane(c), 0, frames);
            }
            chunk.setFrames(frames);
            encoder.encode(chunk, packets::add);
        }
        encoder.flush(packets::add);
        var track = AudioTrack.builder().id(1).codec(encoder.codec()).timeBase(Rational.timeBaseOf(rate))
                .sampleRate(rate).layout(layout);
        encoder.codecConfig().ifPresent(track::codecConfig);
        return new Encoded(track.build(), List.copyOf(packets));
    }

    /**
     * Decodes every packet of a stream.
     *
     * @param decoder the decoder
     * @param packets the packets
     * @return the decode
     */
    public static Decoded decode(AudioDecoder decoder, List<Packet> packets) {
        return decode(decoder, packets, Set.of(), null);
    }

    /**
     * Decodes a stream with some packets lost, concealing each run of lost packets with one call for the samples
     * the reference decode produced for them.
     *
     * @param decoder the decoder
     * @param packets the packets
     * @param lost the indices of the lost packets
     * @param reference the decode without loss, whose packet lengths size the concealed spans, or {@code null} when
     * {@code lost} is empty
     * @return the decode
     */
    public static Decoded decode(AudioDecoder decoder, List<Packet> packets, Set<Integer> lost, Decoded reference) {
        var collected = new ArrayList<float[][]>();
        var starts = new int[packets.size()];
        var lengths = new int[packets.size()];
        var runs = new ArrayList<Run>();
        var position = new int[1];
        var format = new Object[2];
        java.util.function.Consumer<AudioBuffer> sink = buffer -> {
            var frames = buffer.frames();
            var copy = new float[buffer.channels()][];
            for (var c = 0; c < copy.length; c++) {
                copy[c] = Arrays.copyOf(buffer.plane(c), frames);
            }
            collected.add(copy);
            position[0] += frames;
            format[0] = buffer.layout();
            format[1] = buffer.sampleRate();
        };
        for (var i = 0; i < packets.size(); i++) {
            starts[i] = position[0];
            if (!lost.contains(i)) {
                decoder.decode(packets.get(i), sink);
                lengths[i] = position[0] - starts[i];
                continue;
            }
            var last = i;
            while (last + 1 < packets.size() && lost.contains(last + 1)) {
                last++;
            }
            var requested = 0;
            for (var k = i; k <= last; k++) {
                requested += reference.lengths()[k];
            }
            var from = position[0];
            var returned = requested > 0 ? decoder.conceal(requested, sink) : 0;
            lengths[i] = position[0] - from;
            for (var k = i + 1; k <= last; k++) {
                starts[k] = position[0];
            }
            runs.add(new Run(i, last, from, position[0], requested, returned));
            i = last;
        }
        decoder.flush(sink);
        var channels = collected.isEmpty() ? decoder.layout().channels() : collected.getFirst().length;
        var planes = new float[channels][position[0]];
        var at = 0;
        for (var block : collected) {
            for (var c = 0; c < channels && c < block.length; c++) {
                System.arraycopy(block[c], 0, planes[c], at, block[c].length);
            }
            at += block[0].length;
        }
        var layout = format[0] != null ? (ChannelLayout) format[0] : decoder.layout();
        var rate = format[1] != null ? (int) format[1] : decoder.sampleRate();
        return new Decoded(planes, starts, lengths, List.copyOf(runs), layout, rate);
    }

    /**
     * Returns a loss trace of bursts.
     *
     * @param spec pairs of the first lost packet and the burst length
     * @return the indices of the lost packets
     */
    public static Set<Integer> bursts(int... spec) {
        var lost = new TreeSet<Integer>();
        for (var i = 0; i + 1 < spec.length; i += 2) {
            for (var k = 0; k < spec[i + 1]; k++) {
                lost.add(spec[i] + k);
            }
        }
        return lost;
    }

    /**
     * Returns a seeded random loss trace of a two-state (Gilbert) model, which loses packets in bursts.
     *
     * @param seed the seed
     * @param packets the number of packets
     * @param percent the long-run percentage of lost packets
     * @param meanBurst the mean burst length in packets
     * @param guard the number of packets at the start and the end that are never lost
     * @param gap the smallest number of received packets between two bursts
     * @return the indices of the lost packets
     */
    public static Set<Integer> random(long seed, int packets, int percent, double meanBurst, int guard, int gap) {
        var random = new Random(seed);
        var lost = new TreeSet<Integer>();
        var stay = 1 - 1 / meanBurst;
        var enter = percent / 100.0 / meanBurst / (1 - percent / 100.0);
        var losing = false;
        var received = gap;
        for (var i = guard; i < packets - guard; i++) {
            losing = losing ? random.nextDouble() < stay : received >= gap && random.nextDouble() < enter;
            if (losing) {
                lost.add(i);
                received = 0;
            } else {
                received++;
            }
        }
        return lost;
    }

    /**
     * Splits interleaved samples into planes.
     *
     * @param interleaved the samples, channel by channel within each frame
     * @param channels the number of channels
     * @return one plane per channel
     */
    public static float[][] deinterleave(float[] interleaved, int channels) {
        var frames = interleaved.length / channels;
        var planes = new float[channels][frames];
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < channels; c++) {
                planes[c][i] = interleaved[i * channels + c];
            }
        }
        return planes;
    }

    /**
     * Returns the largest step between consecutive samples of any channel within a span.
     *
     * @param planes the samples
     * @param from the first sample
     * @param to the index after the last sample
     * @return the largest absolute difference
     */
    public static double maxStep(float[][] planes, int from, int to) {
        var largest = 0.0;
        for (var plane : planes) {
            for (var i = Math.max(1, from); i < Math.min(to, plane.length); i++) {
                largest = Math.max(largest, Math.abs((double) plane[i] - plane[i - 1]));
            }
        }
        return largest;
    }

    /**
     * Returns the mean energy per sample of a span, over every channel.
     *
     * @param planes the samples
     * @param from the first sample
     * @param to the index after the last sample
     * @return the mean of the squared samples
     */
    public static double energy(float[][] planes, int from, int to) {
        var sum = 0.0;
        var count = 0L;
        for (var plane : planes) {
            for (var i = Math.max(0, from); i < Math.min(to, plane.length); i++) {
                sum += (double) plane[i] * plane[i];
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    /**
     * Returns the SNR of a decode against a reference over a span.
     *
     * @param reference the reference samples
     * @param test the samples compared
     * @param from the first sample
     * @param to the index after the last sample
     * @return the SNR in decibels, positive infinity for equal spans
     */
    public static double snr(float[][] reference, float[][] test, int from, int to) {
        var signal = 0.0;
        var noise = 0.0;
        for (var c = 0; c < reference.length; c++) {
            for (var i = Math.max(0, from); i < Math.min(to, Math.min(reference[c].length, test[c].length)); i++) {
                var d = (double) reference[c][i] - test[c][i];
                signal += (double) reference[c][i] * reference[c][i];
                noise += d * d;
            }
        }
        return noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise);
    }

    /**
     * Decodes a stream without and with a loss trace and asserts the concealment contract.
     *
     * @param decoders creates a fresh decoder for each decode
     * @param packets the packets
     * @param lost the indices of the lost packets
     * @param bounds the bounds to meet
     * @return the lossy decode
     */
    public static Decoded assertConceals(Supplier<AudioDecoder> decoders, List<Packet> packets, Set<Integer> lost,
                                         Bounds bounds) {
        Decoded reference;
        try (var decoder = decoders.get()) {
            assertTrue(decoder.supportsConcealment(), "the decoder supports concealment");
            reference = decode(decoder, packets);
        }
        Decoded lossy;
        try (var decoder = decoders.get()) {
            lossy = decode(decoder, packets, lost, reference);
        }
        Decoded again;
        try (var decoder = decoders.get()) {
            again = decode(decoder, packets, lost, reference);
        }
        for (var c = 0; c < lossy.planes().length; c++) {
            assertArrayEquals(lossy.planes()[c], again.planes()[c], "the concealment is deterministic, channel " + c);
        }
        assertEquals(reference.planes().length, lossy.planes().length, "channel count");
        var cleanStep = maxStep(reference.planes(), 0, reference.length());
        assertTrue(cleanStep > 0, "the reference decode is not silent");
        var runs = lossy.runs();
        var worstStep = 0.0;
        var worstGain = Double.NEGATIVE_INFINITY;
        var worstStart = Double.POSITIVE_INFINITY;
        var worstSnr = Double.POSITIVE_INFINITY;
        var report = new StringBuilder();
        for (var r = 0; r < runs.size(); r++) {
            var run = runs.get(r);
            var label = "loss of packets " + run.firstPacket() + " to " + run.lastPacket();
            if (bounds.exactLength()) {
                assertEquals(run.requested(), run.returned(), label + ": samples reported");
            }
            assertEquals(run.returned(), run.to() - run.from(), label + ": samples handed over");
            var recoveryEnd = lossy.starts()[Math.min(packets.size() - 1, run.lastPacket() + 1 + bounds.recoveryPackets())];
            var step = maxStep(lossy.planes(), Math.max(1, run.from() - 1), recoveryEnd) / cleanStep;
            var referenceFrom = reference.starts()[run.firstPacket()];
            var length = run.to() - run.from();
            var concealed = energy(lossy.planes(), run.from(), run.to());
            var expected = Math.max(energy(reference.planes(), referenceFrom, referenceFrom + length),
                    energy(reference.planes(), referenceFrom - length, referenceFrom));
            var gain = 10 * Math.log10(Math.max(concealed, 1e-30) / Math.max(expected, 1e-30));
            var startGain = Double.NaN;
            if (bounds.audibleSamples() > 0 && run.firstPacket() > 0) {
                var span = Math.min(bounds.audibleSamples(), length);
                var start = energy(lossy.planes(), run.from(), run.from() + span);
                var history = energy(reference.planes(), referenceFrom - span, referenceFrom);
                if (history > 1e-8) {
                    startGain = 10 * Math.log10(Math.max(start, 1e-30) / history);
                    worstStart = Math.min(worstStart, startGain);
                }
            }
            var snr = Double.NaN;
            var settled = run.lastPacket() + 1 + bounds.recoveryPackets();
            var next = r + 1 < runs.size() ? runs.get(r + 1).firstPacket() : packets.size() - 1;
            if (settled < next) {
                var from = lossy.starts()[settled];
                var to = lossy.starts()[next];
                var shifted = shift(reference.planes(), from - reference.starts()[settled]);
                snr = snr(shifted, lossy.planes(), from, to);
                worstSnr = Math.min(worstSnr, snr);
            }
            worstStep = Math.max(worstStep, step);
            if (expected > 1e-12) {
                worstGain = Math.max(worstGain, gain);
            }
            report.append(String.format(java.util.Locale.ROOT,
                    "%n  %s: step x%.2f, level %+.1f dB, start %+.1f dB, recovery %.1f dB", label, step, gain,
                    startGain, snr));
        }
        var summary = String.format(java.util.Locale.ROOT,
                "worst step x%.2f (bound %.2f), level %+.1f dB (bound %+.1f), start %+.1f dB (bound %+.1f), "
                        + "recovery %.1f dB (bound %.1f)", worstStep, bounds.stepFactor(), worstGain,
                bounds.maxGainDb(), worstStart, -bounds.minGainDb(), worstSnr, bounds.recoverySnr()) + report;
        assertTrue(worstStep <= bounds.stepFactor(), "a step around a loss is too large: " + summary);
        assertTrue(worstGain <= bounds.maxGainDb(), "a concealed span is too loud: " + summary);
        assertTrue(bounds.audibleSamples() == 0 || worstStart >= -bounds.minGainDb(),
                "a concealed span starts too quietly: " + summary);
        assertTrue(worstSnr >= bounds.recoverySnr(), "the decode does not converge after a loss: " + summary);
        if (bounds.exactLength()) {
            assertEquals(reference.length(), lossy.length(), "the output keeps its length");
        }
        return lossy;
    }

    /**
     * Returns the samples delayed by a number of samples, padded with zeros, so that a reference lines up with a
     * lossy decode whose concealed span differs in length.
     *
     * @param planes the samples
     * @param delay the delay, negative to advance
     * @return the shifted samples
     */
    private static float[][] shift(float[][] planes, int delay) {
        if (delay == 0) {
            return planes;
        }
        var out = new float[planes.length][];
        for (var c = 0; c < planes.length; c++) {
            out[c] = new float[planes[c].length + Math.max(0, delay)];
            for (var i = 0; i < planes[c].length; i++) {
                var j = i + delay;
                if (j >= 0 && j < out[c].length) {
                    out[c][j] = planes[c][i];
                }
            }
        }
        return out;
    }
}
