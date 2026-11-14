/* Ports the measurements of hvxc_fcomp, hvxc_ini_phase and segsnr of the ISO/IEC 14496-5 HVXC conformance tools.
 * Copyright Sony Corporation; ISO/IEC 14496-3 reference software licence. Modified for Smalt. */
package com.github.auties00.smalt.audio.hvxc;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.hvxc.internal.HvxcObject;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reads the HVXC conformance streams of ISO/IEC 14496-26 through Smalt's MP4 demuxer, decodes them, and measures the
 * criteria of the ISO/IEC 14496-4 HVXC procedures: the differential spectrum bounds of the all voiced streams, the
 * peak to RMS statistics of the harmonic phase initialization and the segmental SNR.
 */
final class HvxcTestSupport {
    private HvxcTestSupport() {
    }

    /**
     * The tracks of a stream and the access units of each, in file order.
     *
     * @param tracks the HVXC tracks
     * @param units the access units of each track
     */
    record Stream(List<AudioTrack> tracks, List<List<Packet>> units) {
        int frames() {
            return units.getFirst().size();
        }

        List<Packet> frame(int frame) {
            var packets = new ArrayList<Packet>();
            for (var track : units) {
                if (frame >= track.size()) {
                    break;
                }
                packets.add(track.get(frame));
            }
            return packets;
        }

        List<byte[]> configs() {
            return tracks.stream().map(track -> track.codecConfig().orElseThrow()).toList();
        }
    }

    static Stream stream(String name) {
        return stream(Fixtures.read(Conformance.fetchListed("mpeg4audio-" + name + ".mp4")));
    }

    static Stream stream(byte[] file) {
        var units = new LinkedHashMap<Integer, List<Packet>>();
        var tracks = new LinkedHashMap<Integer, AudioTrack>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            for (var track : demuxer.tracks()) {
                if (track instanceof AudioTrack audio && audio.codec() == Codec.HVXC) {
                    tracks.put(track.id(), audio);
                    units.put(track.id(), new ArrayList<>());
                }
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var list = units.get(packet.trackId());
                if (list != null) {
                    list.add(packet);
                }
            }
        }
        return new Stream(List.copyOf(tracks.values()), List.copyOf(units.values()));
    }

    /**
     * Decodes a stream through the public decoder and drops the decoder's delay, as the conformance references do.
     */
    static float[] decode(Stream stream, HvxcDecoderOptions options) {
        var out = new Samples();
        try (var decoder = new HvxcDecoder(stream.tracks(), options)) {
            for (var frame = 0; frame < stream.frames(); frame++) {
                decoder.decode(stream.frame(frame), buffer -> out.add(buffer.plane(0), buffer.frames()));
            }
            var all = out.toArray();
            return Arrays.copyOfRange(all, decoder.delay(), all.length);
        }
    }

    /**
     * Decodes a stream with the test mode of the conformance procedure and drops the decoder's delay.
     *
     * @param testMode the {@code test_mode} bits
     * @param lowDelay whether to decode in the low delay mode
     * @param speed the speed change factor
     * @param pitch the pitch change factor
     * @param maxLayer the highest layer, -1 for every layer
     */
    static float[] decode(Stream stream, int testMode, boolean lowDelay, float speed, float pitch, int maxLayer) {
        var object = new HvxcObject(stream.configs(), maxLayer, lowDelay, testMode, speed, pitch,
                MediaLimits.defaults());
        var out = new Samples();
        var buffer = new float[object.maxSamples()];
        var data = new byte[stream.units().size()][];
        var lengths = new int[data.length];
        for (var frame = 0; frame < stream.frames(); frame++) {
            var packets = stream.frame(frame);
            for (var i = 0; i < packets.size(); i++) {
                data[i] = packets.get(i).data();
                lengths[i] = data[i].length;
            }
            var written = object.decode(data, lengths, packets.size(), buffer);
            out.add(buffer, written);
        }
        var all = out.toArray();
        return Arrays.copyOfRange(all, object.delay(), all.length);
    }

    /**
     * A growing array of samples.
     */
    static final class Samples {
        private float[] data = new float[1 << 16];

        private int length;

        void add(float[] samples, int count) {
            if (data.length < length + count) {
                data = Arrays.copyOf(data, Math.max(2 * data.length, length + count));
            }
            System.arraycopy(samples, 0, data, length, count);
            length += count;
        }

        float[] toArray() {
            return Arrays.copyOf(data, length);
        }
    }

    static float[] reference(String name) {
        return readWav(Fixtures.read(Conformance.fetchListed("mpeg4audio-" + name + ".wav")));
    }

    /**
     * Reads a mono 16-bit PCM WAV file, full scale 1.
     */
    static float[] readWav(byte[] data) {
        var position = 12;
        while (position + 8 <= data.length) {
            var id = new String(data, position, 4, StandardCharsets.ISO_8859_1);
            var size = ByteBuffer.wrap(data, position + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (id.equals("data")) {
                var frames = Math.min(size, data.length - position - 8) / 2;
                var samples = new float[frames];
                for (var i = 0; i < frames; i++) {
                    var p = position + 8 + 2 * i;
                    samples[i] = (short) ((data[p] & 0xFF) | (data[p + 1] << 8)) / 32768f;
                }
                return samples;
            }
            position += 8 + size + (size & 1);
        }
        throw new IllegalArgumentException("no data chunk");
    }

    /**
     * {@return the frames whose differential spectrum leaves the bounds of the all voiced test ({@code hvxc_fcomp})}
     *
     * <p>Each frame is a Hanning windowed 256-sample block every 160 samples from sample 112; the magnitude spectrum
     * of the test signal minus that of the reference, averaged over 7 bins, must stay in dB between the lower and
     * the upper bound of the bit rate in every bin below 128. The average covers the bin and the 6 below it, as the
     * tool computes it.
     *
     * @param test the decoded signal, full scale 1
     * @param reference the reference without the noise component, full scale 1
     * @param fourKbit whether the bounds of 4 kbit/s apply
     */
    static List<Integer> spectralFailures(float[] test, float[] reference, boolean fourKbit) {
        var length = 256;
        var upper = new float[128];
        var lower = new float[128];
        var knee = fourKbit ? 85 : 70;
        var floor = fourKbit ? 100 : 90;
        for (var i = 0; i < 128; i++) {
            if (i < knee) {
                upper[i] = -30.0f;
            } else if (i < knee + 10) {
                upper[i] = (float) (2.6 * (double) (i - knee) - 30.0);
            } else if (i < 122) {
                upper[i] = -4.0f;
            } else {
                upper[i] = (float) (-1.4 * (double) (i - 122) - 4.0);
            }
            if (i < floor) {
                lower[i] = -200.0f;
            } else if (i < floor + 10) {
                lower[i] = (float) (45.0 / 10.0 * (double) (i - floor - 10) - 35.0);
            } else if (i < 118) {
                lower[i] = -35.0f;
            } else {
                lower[i] = (float) (-38.0 / 9.0 * (double) (i - 118) - 35.0);
            }
        }
        var window = new float[length];
        var inverse = 1.0f / ((float) length - 1.0f);
        for (var i = 0; i < length; i++) {
            window[i] = (float) (0.5f * (1.0f - StrictMath.cos(2.0f * Math.PI * i * inverse)));
        }
        var failures = new ArrayList<Integer>();
        var xa = new double[length];
        var ya = new double[length];
        var xb = new double[length];
        var yb = new double[length];
        var difference = new double[129];
        var smoothed = new double[129];
        for (int offset = 112, frame = 0; offset + length <= test.length && offset + length <= reference.length;
             offset += 160, frame++) {
            for (var i = 0; i < length; i++) {
                xa[i] = test[offset + i] * window[i];
                xb[i] = reference[offset + i] * window[i];
                ya[i] = 0;
                yb[i] = 0;
            }
            fft(xa, ya);
            fft(xb, yb);
            for (var i = 0; i <= 128; i++) {
                difference[i] = Math.abs(Math.hypot(xa[i], ya[i]) - Math.hypot(xb[i], yb[i]));
            }
            for (var i = 0; i <= 128; i++) {
                // The tool's average filter pads its input with 3 zeros and then centres the 7 taps on the padded
                // index, so bin i averages bins i - 6 to i.
                var sum = 0.0;
                for (var k = i - 6; k <= i; k++) {
                    if (k >= 0) {
                        sum += difference[k];
                    }
                }
                smoothed[i] = sum / 7;
            }
            for (var i = 0; i < 128; i++) {
                var level = 20.0 * Math.log10(smoothed[i]);
                if (level > upper[i] || level < lower[i]) {
                    failures.add(frame);
                    break;
                }
            }
        }
        return failures;
    }

    /**
     * Transforms in place, radix 2.
     */
    private static void fft(double[] re, double[] im) {
        var n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            var bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                var t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (var size = 2; size <= n; size <<= 1) {
            var angle = -2 * Math.PI / size;
            for (var start = 0; start < n; start += size) {
                for (var k = 0; k < size / 2; k++) {
                    var c = StrictMath.cos(angle * k);
                    var s = StrictMath.sin(angle * k);
                    var a = start + k;
                    var b = a + size / 2;
                    var tr = re[b] * c - im[b] * s;
                    var ti = re[b] * s + im[b] * c;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }

    /**
     * {@return the average and the deviation of the peak to RMS ratio of the voiced segments of the harmonic phase
     * initialization stream ({@code hvxc_ini_phase})}
     *
     * <p>The stream alternates two unvoiced and two voiced frames; the segments are 80 samples centred every 80
     * samples, and those at positions 5 to 7 of every 8 are measured. Samples before the start read as zero.
     *
     * @param test the decoded signal, full scale 1
     */
    static double[] peakToRms(float[] test) {
        var ratios = new ArrayList<Double>();
        for (var frame = 0; ; frame++) {
            var start = frame * 80 - 40;
            if (start + 80 > test.length) {
                break;
            }
            var energy = 0.0f;
            var peak = 0.0f;
            for (var i = 0; i < 80; i++) {
                var value = start + i < 0 ? 0.0f : test[start + i] * 32768.0f;
                energy += value * value;
                peak = Math.max(peak, value);
            }
            var rms = (float) Math.sqrt(energy / 80.0f);
            if (frame % 8 >= 5) {
                ratios.add((double) (peak / rms));
            }
        }
        var average = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        var deviation = Math.sqrt(ratios.stream().mapToDouble(r -> (r - average) * (r - average)).average()
                .orElse(Double.NaN));
        return new double[]{average, deviation};
    }
}
