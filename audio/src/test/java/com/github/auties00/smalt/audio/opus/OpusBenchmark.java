package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures encoding and decoding of 60 s of 16 kHz mono speech with
 * {@link OpusEncoderOptions#forSpeech(int)} at 16 kb/s and of 60 s of 48 kHz stereo music at 128 kb/s.
 *
 * <p>Run with {@code org.openjdk.jmh.Main OpusBenchmark}. The setup writes the same inputs as raw
 * float PCM next to the JVM's temporary directory ({@code opus-bench-speech.f32},
 * {@code opus-bench-music.f32}) so the ffmpeg CLI can be timed on identical data.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class OpusBenchmark {
    private static final int SECONDS = 60;

    private float[][] speech;
    private float[][] music;
    private List<byte[]> speechPackets;
    private List<byte[]> musicPackets;
    private byte[] speechHead;
    private byte[] musicHead;
    private OpusEncoderOptions musicConfig;

    @Setup
    public void setup() throws Exception {
        var mp3 = Conformance.fetch("librivox-art_of_war_03-04_64kb.mp3",
                "https://archive.org/download/art_of_war_librivox/art_of_war_03-04_sun_tzu_64kb.mp3",
                "57e879fe7822578fd22010f1280185799a119a3482e025d9a71f49878ff45ba7");
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-t", Integer.toString(SECONDS),
                "-i", mp3.toString(), "-ac", "1", "-ar", "16000", "-f", "f32le", "-"));
        var temp = Path.of(System.getProperty("java.io.tmpdir"));
        Files.write(temp.resolve("opus-bench-speech.f32"), raw);
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        speech = new float[][]{samples};
        var vector = OpusVectors.readPcm16(OpusVectors.directory().resolve("testvector07.dec"));
        var frames = SECONDS * 48000;
        music = new float[2][frames];
        var interleaved = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < frames; i++) {
            var j = i % (vector.length / 2);
            music[0][i] = vector[2 * j] * (1.0f / 32768);
            music[1][i] = vector[2 * j + 1] * (1.0f / 32768);
            interleaved.putFloat(music[0][i]).putFloat(music[1][i]);
        }
        Files.write(temp.resolve("opus-bench-music.f32"), interleaved.array());
        musicConfig = OpusEncoderOptions.defaults().toBuilder().bitrate(128000).build();
        speechPackets = new ArrayList<>();
        speechHead = encode(OpusEncoderOptions.forSpeech(16000), speech, speechPackets);
        musicPackets = new ArrayList<>();
        musicHead = encode(musicConfig, music, musicPackets);
    }

    private static byte[] encode(OpusEncoderOptions config, float[][] planes, List<byte[]> out) {
        var encoder = new OpusEncoder(config);
        var total = planes[0].length;
        var chunk = config.sampleRate() / 50;
        for (var position = 0; position < total; position += chunk) {
            var count = Math.min(chunk, total - position);
            var block = new float[planes.length][];
            for (var c = 0; c < planes.length; c++) {
                block[c] = java.util.Arrays.copyOfRange(planes[c], position, position + count);
            }
            encoder.encode(new AudioBuffer(encoder.layout(), config.sampleRate(), block, count), packet -> {
                if (out != null) {
                    out.add(packet.data());
                }
            });
        }
        encoder.flush(packet -> {
            if (out != null) {
                out.add(packet.data());
            }
        });
        return encoder.codecConfig().orElseThrow();
    }

    private static long decode(byte[] head, List<byte[]> packets) {
        var track = AudioTrack.builder().id(1).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).codecConfig(head)
                .sampleRate(48000)
                .build();
        var total = new long[1];
        try (var decoder = new OpusDecoder(track)) {
            for (var data : packets) {
                decoder.decode(new Packet(1, data, 0, 0, 0, true), buffer -> total[0] += buffer.frames());
            }
        }
        return total[0];
    }

    @Benchmark
    public byte[] encodeVoiceNote60s() {
        return encode(OpusEncoderOptions.forSpeech(16000), speech, null);
    }

    @Benchmark
    public byte[] encodeMusic128k60s() {
        return encode(musicConfig, music, null);
    }

    @Benchmark
    public long decodeVoiceNote60s() {
        return decode(speechHead, speechPackets);
    }

    @Benchmark
    public long decodeMusic128k60s() {
        return decode(musicHead, musicPackets);
    }
}
