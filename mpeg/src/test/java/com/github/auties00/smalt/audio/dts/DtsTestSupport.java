package com.github.auties00.smalt.audio.dts;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.container.dts.DtsDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoding drivers and ffmpeg black-box decodes shared by the DTS tests. ffmpeg decodes with {@code -cpuflags 0}:
 * its x86 SIMD 128x LFE interpolation applies the D.8 taps in another order than its C code, so only the C code is
 * the same oracle on every platform.
 */
final class DtsTestSupport {
    private DtsTestSupport() {
    }

    /**
     * A decoded stream: interleaved samples in the order of the output layout.
     *
     * @param samples the interleaved samples
     * @param channels the channel count
     * @param sampleRate the sample rate
     */
    record Decoded(float[] samples, int channels, int sampleRate) {
    }

    /**
     * Decodes a file through {@link DtsDemuxer} and {@link DtsDecoder}.
     */
    static Decoded decodeFile(Path file, DtsDecoderOptions options) {
        try (var source = MediaSource.of(file); var demuxer = new DtsDemuxer(source)) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var decoder = new DtsDecoder(track, options);
            var buffers = new ArrayList<AudioBuffer>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                decoder.decode(packet, buffer -> buffers.add(buffer.copy()));
            }
            return interleave(buffers);
        }
    }

    /**
     * Decodes raw access units, one per packet.
     */
    static Decoded decodeUnits(List<byte[]> units, DtsDecoderOptions options) {
        var track = AudioTrack.builder().codec(Codec.DTS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                .build();
        var decoder = new DtsDecoder(track, options);
        var buffers = new ArrayList<AudioBuffer>();
        for (var unit : units) {
            decoder.decode(new Packet(0, unit, 0, 0, 0, true), buffer -> buffers.add(buffer.copy()));
        }
        return interleave(buffers);
    }

    static Decoded interleave(List<AudioBuffer> buffers) {
        if (buffers.isEmpty()) {
            return new Decoded(new float[0], 0, 0);
        }
        var channels = buffers.getFirst().channels();
        var total = 0;
        for (var buffer : buffers) {
            total += buffer.frames();
        }
        var samples = new float[total * channels];
        var position = 0;
        for (var buffer : buffers) {
            for (var i = 0; i < buffer.frames(); i++) {
                for (var ch = 0; ch < channels; ch++) {
                    samples[position++] = buffer.plane(ch)[i];
                }
            }
        }
        return new Decoded(samples, channels, buffers.getFirst().sampleRate());
    }

    /**
     * Decodes a file with ffmpeg to interleaved float samples in ffmpeg's channel order.
     */
    static float[] ffmpegFloats(Path file, List<String> inputArguments) {
        var arguments = new ArrayList<>(List.of("-cpuflags", "0", "-v", "error"));
        arguments.addAll(inputArguments);
        arguments.addAll(List.of("-i", file.toString(), "-f", "f32le", "-"));
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    /**
     * Decodes a file with ffmpeg to interleaved signed 32-bit samples, the lossless output of DTS-HD Master Audio.
     */
    static int[] ffmpegIntegers(Path file) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-cpuflags", "0", "-v", "error", "-i", file.toString(),
                "-f", "s32le", "-"));
        var samples = new int[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(samples);
        return samples;
    }

    /**
     * Prints, per channel, the least squares gain of the decoded samples against the reference and the SNR left after
     * it, the first frame of 512 samples where they part, to locate a mismatch from a CI log.
     */
    static void report(String name, float[] reference, float[] decoded, int channels) {
        if (channels <= 0 || reference.length != decoded.length) {
            return;
        }
        var frames = reference.length / channels;
        for (var ch = 0; ch < channels; ch++) {
            double rd = 0;
            double dd = 0;
            double rr = 0;
            var first = -1;
            for (var i = 0; i < frames; i++) {
                double r = reference[i * channels + ch];
                double d = decoded[i * channels + ch];
                rd += r * d;
                dd += d * d;
                rr += r * r;
                if (first < 0 && Math.abs(r - d) > 1e-4 * Math.max(1e-3, Math.abs(r))) {
                    first = i;
                }
            }
            var gain = dd == 0 ? 0 : rd / dd;
            var error = rr - 2 * gain * rd + gain * gain * dd;
            System.out.printf("DTS-REPORT %s ch %d gain %.6f snr-after-gain %.2f dB energy %.3g first-frame %d%n",
                    name, ch, gain, 10 * Math.log10(rr / Math.max(error, 1e-30)), rr, first < 0 ? -1 : first / 512);
        }
    }

    /**
     * Converts samples left-aligned in 32 bits to full-scale floats.
     */
    static float[] toFloat(int[] samples) {
        var out = new float[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = samples[i] * 0x1p-31f;
        }
        return out;
    }

    /**
     * Converts full-scale float samples to clipped 24-bit integers, left-aligned in 32 bits as ffmpeg outputs them.
     */
    static int[] toInt32(float[] samples) {
        var out = new int[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = Math.clamp(Math.round(samples[i] * (1 << 23)), -(1 << 23), (1 << 23) - 1) << 8;
        }
        return out;
    }
}
