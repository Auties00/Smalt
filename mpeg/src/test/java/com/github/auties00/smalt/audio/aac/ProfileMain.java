package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Packet;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loops the decoder or the encoder over the benchmark input for profiling with JFR; not a test.
 */
public final class ProfileMain {
    private ProfileMain() {
    }

    public static void main(String[] args) throws Exception {
        var mode = args[0];
        var rounds = Integer.parseInt(args[1]);
        if (mode.equals("decode")) {
            var stream = AacTestSupport.parseAdts(Files.readAllBytes(Path.of("target/aac-bench/ffmpeg-128k.aac")));
            var packets = stream.frames().stream().map(frame -> new Packet(1, frame, 0, 0, 0, true)).toList();
            var sink = new float[1];
            var best = Double.MAX_VALUE;
            for (var round = 0; round < rounds; round++) {
                var start = System.nanoTime();
                var decoder = new AacDecoder(stream.track());
                for (var packet : packets) {
                    decoder.decode(packet, buffer -> sink[0] += buffer.plane(0)[5]);
                }
                best = Math.min(best, (System.nanoTime() - start) / 1e6);
                if (round % 20 == 19) {
                    System.out.printf("decode after %d rounds: best %.1f ms%n", round + 1, best);
                }
            }
        } else if (mode.equals("wav")) {
            Files.createDirectories(Path.of("target/aac-bench"));
            Files.write(Path.of("target/aac-bench/input.wav"), TestSignals.wav(TestSignals.generate(TestSignals.Kind.MUSIC, 48000, 2, 60), 48000));
        } else {
            var input = TestSignals.generate(TestSignals.Kind.MUSIC, 48000, 2, 60);
            var bitrate = Integer.parseInt(args[2]);
            var best = Double.MAX_VALUE;
            for (var round = 0; round < rounds; round++) {
                var start = System.nanoTime();
                var encoder = new AacEncoder(AacEncoderOptions.of(48000, ChannelLayout.STEREO, bitrate));
                var bytes = new long[1];
                encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, input, input[0].length), p -> bytes[0] += p.size());
                encoder.flush(p -> bytes[0] += p.size());
                best = Math.min(best, (System.nanoTime() - start) / 1e6);
                System.out.printf("encode round %d: %.1f ms, best %.1f ms (%d bytes)%n", round, (System.nanoTime() - start) / 1e6, best, bytes[0]);
            }
        }
    }
}
