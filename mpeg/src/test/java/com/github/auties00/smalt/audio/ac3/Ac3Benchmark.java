package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the AC-3 and E-AC-3 decoder and encoders on 60 seconds of music-like 5.1 at 48 kHz,
 * the E-AC-3 encoder also with its real-time options, with each of its optional tools and on a 7.1
 * program, and the decoder on the pinned 7.1 Atmos stream looped to 60 seconds.
 *
 * <p>The setup writes the input as {@code target/ac3-bench/input.wav} and the ffmpeg-coded streams
 * next to it ({@code ac3-448k.ac3}, {@code eac3-384k.eac3}), so the same work can be timed with
 * {@code ffmpeg -threads 1 -benchmark -i <stream> -f null -} and
 * {@code ffmpeg -threads 1 -benchmark -i input.wav -c:a ac3 -b:a 448k -f null -}; the 7.1 stream
 * is decoded 12 times ({@code -stream_loop 11}).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xmx2g", "--add-modules", "jdk.incubator.vector"})
public class Ac3Benchmark {
    /**
     * The input and the coded streams.
     */
    @State(Scope.Benchmark)
    public static class Media {
        AudioBuffer input;
        AudioBuffer input71;
        List<byte[]> ac3;
        List<byte[]> eac3;
        List<byte[]> atmos;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            input = music(48000, 60);
            var planes = new float[8][];
            for (var ch = 0; ch < 6; ch++) {
                planes[ch] = input.plane(ch);
            }
            planes[6] = input.plane(4).clone();
            planes[7] = input.plane(5).clone();
            for (var i = 0; i < input.frames(); i++) {
                planes[6][i] = 0.6f * planes[6][(i + 4801) % input.frames()];
                planes[7][i] = 0.6f * planes[7][(i + 9601) % input.frames()];
            }
            input71 = new AudioBuffer(ChannelLayout.SURROUND_7_1, 48000, planes, input.frames());
            var directory = Path.of("target", "ac3-bench");
            Files.createDirectories(directory);
            var wav = directory.resolve("input.wav");
            Files.write(wav, wav(input));
            ac3 = code(directory, wav, "ac3", "448k", "ac3-448k.ac3");
            eac3 = code(directory, wav, "eac3", "384k", "eac3-384k.eac3");
            var stream = Ac3TestSupport.read(Ac3TestSupport.SAMPLES.get(7).fetch());
            var units = Ac3TestSupport.units(stream);
            atmos = new ArrayList<>();
            for (var loop = 0; loop < 12; loop++) {
                atmos.addAll(units);
            }
        }

        private static List<byte[]> code(Path directory, Path wav, String codec, String bitrate, String name) throws Exception {
            var file = Fixtures.ffmpeg(codec, List.of("-i", wav.toString()), List.of("-c:a", codec, "-b:a", bitrate));
            Files.copy(file, directory.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Ac3TestSupport.units(Files.readAllBytes(file));
        }
    }

    /**
     * Returns a deterministic music-like 5.1 signal: chords with decaying notes, percussive noise
     * bursts and a bass line on the LFE.
     */
    static AudioBuffer music(int sampleRate, int seconds) {
        var layout = ChannelLayout.SURROUND_5_1;
        var frames = sampleRate * seconds;
        var planes = new float[6][frames];
        var random = new Random(5);
        var notes = new double[]{220, 261.63, 329.63, 392, 440, 523.25, 659.25, 783.99};
        for (var ch = 0; ch < 6; ch++) {
            var lfe = ch == 3;
            var plane = planes[ch];
            for (var beat = 0; beat < seconds * 4; beat++) {
                var start = beat * sampleRate / 4;
                var frequency = lfe ? 55 * (1 + beat % 3) : notes[(beat * 3 + ch) % notes.length] * (1 + ch % 2);
                for (var i = 0; i < sampleRate / 4 && start + i < frames; i++) {
                    var t = (double) i / sampleRate;
                    var envelope = Math.exp(-t * 6);
                    var value = 0.2 * envelope * (Math.sin(2 * Math.PI * frequency * t)
                            + 0.4 * Math.sin(4 * Math.PI * frequency * t) + 0.2 * Math.sin(6 * Math.PI * frequency * t));
                    if (!lfe && beat % 2 == 0 && i < 2000) {
                        value += 0.15 * Math.exp(-i / 300.0) * random.nextGaussian();
                    }
                    plane[start + i] += (float) value;
                }
            }
        }
        return new AudioBuffer(layout, sampleRate, planes, frames);
    }

    static byte[] wav(AudioBuffer buffer) {
        var channels = buffer.channels();
        var data = ByteBuffer.allocate(buffer.frames() * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < buffer.frames(); i++) {
            for (var ch = 0; ch < channels; ch++) {
                data.putShort((short) Math.round(Math.max(-1, Math.min(1, buffer.plane(ch)[i])) * 32767));
            }
        }
        var output = new ByteArrayOutputStream();
        var header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + data.capacity()).put("WAVE".getBytes()).put("fmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) channels).putInt(buffer.sampleRate())
                .putInt(buffer.sampleRate() * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                .put("data".getBytes()).putInt(data.capacity());
        output.writeBytes(header.array());
        output.writeBytes(data.array());
        return output.toByteArray();
    }

    private static void decode(List<byte[]> units, Codec codec, Blackhole blackhole) {
        try (var decoder = new Ac3Decoder(Ac3TestSupport.track(codec))) {
            var timestamp = 0L;
            for (var unit : units) {
                decoder.decode(new Packet(0, unit, timestamp, timestamp, 1536, true), blackhole::consume);
                timestamp += 1536;
            }
        }
    }

    @Benchmark
    public void decodeAc3Surround448k(Media media, Blackhole blackhole) {
        decode(media.ac3, Codec.AC3, blackhole);
    }

    @Benchmark
    public void decodeEac3Surround384k(Media media, Blackhole blackhole) {
        decode(media.eac3, Codec.EAC3, blackhole);
    }

    @Benchmark
    public void decodeEac3Atmos71(Media media, Blackhole blackhole) {
        decode(media.atmos, Codec.EAC3, blackhole);
    }

    @Benchmark
    public void encodeAc3Surround448k(Media media, Blackhole blackhole) {
        try (var encoder = new Ac3Encoder(Ac3EncoderOptions.of(48000, ChannelLayout.SURROUND_5_1).toBuilder().bitrate(448000).build())) {
            encoder.encode(media.input, blackhole::consume);
            encoder.flush(blackhole::consume);
        }
    }

    @Benchmark
    public void encodeEac3Surround448k(Media media, Blackhole blackhole) {
        try (var encoder = new Eac3Encoder(Eac3EncoderOptions.of(48000, ChannelLayout.SURROUND_5_1).toBuilder().bitrate(448000).build())) {
            encoder.encode(media.input, blackhole::consume);
            encoder.flush(blackhole::consume);
        }
    }

    @Benchmark
    public void encodeEac3Surround448kRealtime(Media media, Blackhole blackhole) {
        var options = Eac3EncoderOptions.forRealtime().toBuilder().format(48000, ChannelLayout.SURROUND_5_1).bitrate(448000).build();
        try (var encoder = new Eac3Encoder(options)) {
            encoder.encode(media.input, blackhole::consume);
            encoder.flush(blackhole::consume);
        }
    }

    private static void encode(Eac3EncoderOptions options, AudioBuffer input, Blackhole blackhole) {
        try (var encoder = new Eac3Encoder(options)) {
            encoder.encode(input, blackhole::consume);
            encoder.flush(blackhole::consume);
        }
    }

    private static Eac3EncoderOptions surround(int bitrate) {
        return Eac3EncoderOptions.of(48000, ChannelLayout.SURROUND_5_1).toBuilder().bitrate(bitrate).build();
    }

    @Benchmark
    public void encodeEac3Surround448kNoSpectralExtension(Media media, Blackhole blackhole) {
        encode(surround(448000).toBuilder().spectralExtension(false).build(), media.input, blackhole);
    }

    @Benchmark
    public void encodeEac3Surround448kHybridTransform(Media media, Blackhole blackhole) {
        encode(surround(448000).toBuilder().hybridTransform(true).build(), media.input, blackhole);
    }

    @Benchmark
    public void encodeEac3Surround448kPreNoise(Media media, Blackhole blackhole) {
        encode(surround(448000).toBuilder().preNoiseProcessing(true).build(), media.input, blackhole);
    }

    @Benchmark
    public void encodeEac3Surround448kRealtimeNoSpectralExtension(Media media, Blackhole blackhole) {
        encode(Eac3EncoderOptions.forRealtime().toBuilder().format(48000, ChannelLayout.SURROUND_5_1).bitrate(448000)
                .spectralExtension(false).build(), media.input, blackhole);
    }

    @Benchmark
    public void encodeEac3Surround71(Media media, Blackhole blackhole) {
        encode(Eac3EncoderOptions.of(48000, ChannelLayout.SURROUND_7_1), media.input71, blackhole);
    }
}
