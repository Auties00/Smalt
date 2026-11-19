package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.amr.AmrDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Signals, codec drivers and ffmpeg round trips shared by the AMR tests. ffmpeg is used only as a
 * black box: {@code libopencore_amrnb} and {@code libopencore_amrwb} are the C builds of the code
 * the Smalt codecs port, so their output must match bit for bit; {@code libvo_amrwbenc} departs
 * from TS 26.173, which Smalt's AMR-WB encoder follows, so it serves as a quality reference.
 */
final class AmrTestSupport {
    private AmrTestSupport() {
    }

    static int rate(Codec codec) {
        return codec == Codec.AMR_WB ? 16_000 : 8_000;
    }

    static int frameSamples(Codec codec) {
        return codec == Codec.AMR_WB ? 320 : 160;
    }

    /**
     * Returns three seconds of test signal: a harmonic glide with vibrato, low-level noise, then
     * digital silence, so that speech-like coding and the voice activity detector both run. The
     * signal is the same on every platform: {@code StrictMath} rounds alike everywhere, while
     * {@code Math.sin} may differ by an ulp between x86 and arm64.
     */
    static short[] signal(int sampleRate) {
        var seconds = 3;
        var samples = new short[sampleRate * seconds];
        var random = new Random(26_073);
        var phase = 0.0;
        for (var i = 0; i < sampleRate; i++) {
            var t = (double) i / sampleRate;
            var f0 = 140 + 60 * t + 8 * StrictMath.sin(2 * Math.PI * 5 * t);
            phase += 2 * Math.PI * f0 / sampleRate;
            var value = 0.0;
            for (var harmonic = 1; harmonic <= 12; harmonic++) {
                if (harmonic * f0 < sampleRate / 2.0) {
                    value += StrictMath.sin(harmonic * phase) / harmonic;
                }
            }
            var envelope = 0.5 - 0.5 * StrictMath.cos(2 * Math.PI * Math.min(1, t * 1.2));
            samples[i] = (short) Math.round(6000 * envelope * value);
        }
        for (var i = sampleRate; i < 2 * sampleRate; i++) {
            samples[i] = (short) Math.round(random.nextGaussian() * 120);
        }
        return samples;
    }

    /**
     * Encodes 16-bit samples with the public encoder, returning one storage-format frame per
     * packet, the flush included.
     */
    static List<Packet> encode(AmrEncoderOptions options, short[] pcm) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new AmrEncoder(options)) {
            var block = 997;
            var buffer = new AudioBuffer(ChannelLayout.MONO, options.sampleRate(), block);
            for (var offset = 0; offset < pcm.length; offset += block) {
                var count = Math.min(block, pcm.length - offset);
                var plane = buffer.plane(0);
                for (var i = 0; i < count; i++) {
                    plane[i] = pcm[offset + i] / 32768f;
                }
                buffer.setFrames(count);
                encoder.encode(buffer, packets::add);
            }
            encoder.flush(packets::add);
        }
        return packets;
    }

    /**
     * Decodes packets with the public decoder to 16-bit samples.
     */
    static short[] decode(Codec codec, List<Packet> packets) {
        var track = track(codec);
        var output = new ArrayList<short[]>();
        try (var decoder = new AmrDecoder(track)) {
            for (var packet : packets) {
                decoder.decode(packet, buffer -> {
                    var samples = new short[buffer.frames()];
                    var plane = buffer.plane(0);
                    for (var i = 0; i < samples.length; i++) {
                        samples[i] = (short) Math.round(plane[i] * 32768f);
                    }
                    output.add(samples);
                });
            }
        }
        var total = output.stream().mapToInt(samples -> samples.length).sum();
        var result = new short[total];
        var position = 0;
        for (var samples : output) {
            System.arraycopy(samples, 0, result, position, samples.length);
            position += samples.length;
        }
        return result;
    }

    static AudioTrack track(Codec codec) {
        return AudioTrack.builder()
                .id(0)
                .codec(codec)
                .timeBase(Rational.timeBaseOf(rate(codec)))
                .duration(-1)
                .sampleRate(rate(codec))
                .layout(ChannelLayout.MONO)
                .build();
    }

    /**
     * Builds an {@code .amr} storage file from packets.
     */
    static byte[] storage(Codec codec, List<Packet> packets) {
        var magic = codec == Codec.AMR_WB ? "#!AMR-WB\n" : "#!AMR\n";
        var size = magic.length() + packets.stream().mapToInt(Packet::size).sum();
        var file = new byte[size];
        for (var i = 0; i < magic.length(); i++) {
            file[i] = (byte) magic.charAt(i);
        }
        var position = magic.length();
        for (var packet : packets) {
            System.arraycopy(packet.data(), 0, file, position, packet.size());
            position += packet.size();
        }
        return file;
    }

    /**
     * Reads the packets of an {@code .amr} storage file.
     */
    static List<Packet> frames(byte[] file) {
        var packets = new ArrayList<Packet>();
        try (Demuxer demuxer = new AmrDemuxer(MediaSource.of(file))) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        return packets;
    }

    /**
     * Encodes 16-bit samples with ffmpeg's build of the reference encoder.
     */
    static byte[] ffmpegEncode(AmrMode mode, boolean dtx, short[] pcm, Path directory) {
        var input = write(directory, "input.raw", bytes(pcm));
        var output = directory.resolve("ffmpeg-" + mode + (dtx ? "-dtx" : "") + ".amr");
        var arguments = new ArrayList<>(List.of("-v", "error", "-y", "-f", "s16le", "-ar", Integer.toString(mode.sampleRate()),
                "-ac", "1", "-i", input.toString(), "-c:a", mode.isWideband() ? "libvo_amrwbenc" : "libopencore_amrnb",
                "-b:a", Integer.toString(mode.bitrate())));
        if (dtx) {
            arguments.addAll(List.of("-dtx", "1"));
        }
        arguments.addAll(List.of("-f", "amr", output.toString()));
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        return read(output);
    }

    /**
     * Decodes an {@code .amr} file with ffmpeg's build of the reference decoder.
     */
    static short[] ffmpegDecode(Codec codec, byte[] file, Path directory) {
        var input = write(directory, "decode-" + System.nanoTime() + ".amr", file);
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error",
                "-c:a", codec == Codec.AMR_WB ? "libopencore_amrwb" : "libopencore_amrnb", "-i", input.toString(),
                "-f", "s16le", "-"));
        return words(raw);
    }

    /**
     * Decodes every frame of a file with ffmpeg's build of the reference decoder, failing on any
     * error. ffmpeg's native AMR decoders do not implement comfort noise, so they cannot check
     * streams coded with discontinuous transmission.
     */
    static void assertDecodesCleanly(Codec codec, Path file) {
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-xerror",
                "-c:a", codec == Codec.AMR_WB ? "libopencore_amrwb" : "libopencore_amrnb", "-i", file.toString(),
                "-f", "null", "-"));
    }

    static short[] words(byte[] bytes) {
        var words = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(words);
        return words;
    }

    static byte[] bytes(short[] words) {
        var buffer = ByteBuffer.allocate(words.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asShortBuffer().put(words);
        return buffer.array();
    }

    static Path write(Path directory, String name, byte[] data) {
        try {
            return Files.write(directory.resolve(name), data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns the spectral SNR in decibels of a decoded signal against its input, after removing
     * the codec delay: the energy of the input's short-time magnitude spectra (256-sample Hann
     * windows, half overlapping) over the energy of the magnitude differences. Comparing
     * magnitudes ignores the phase response of the codecs' pre- and post-processing filters,
     * which dominates a waveform SNR at the low frequencies of voiced sound.
     */
    static double spectralSnr(short[] input, short[] decoded, int delay, int from, int to) {
        var size = 256;
        var window = new double[size];
        for (var i = 0; i < size; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / size);
        }
        double signal = 0;
        double noise = 0;
        for (var start = from; start + size <= to && start + delay + size <= decoded.length; start += size / 2) {
            for (var bin = 1; bin < size / 2; bin++) {
                double xr = 0, xi = 0, yr = 0, yi = 0;
                for (var i = 0; i < size; i++) {
                    var angle = 2 * Math.PI * bin * i / size;
                    var cos = Math.cos(angle) * window[i];
                    var sin = Math.sin(angle) * window[i];
                    xr += input[start + i] * cos;
                    xi -= input[start + i] * sin;
                    yr += decoded[start + delay + i] * cos;
                    yi -= decoded[start + delay + i] * sin;
                }
                var x = Math.hypot(xr, xi);
                var y = Math.hypot(yr, yi);
                signal += x * x;
                noise += (x - y) * (x - y);
            }
        }
        return noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise);
    }
}
