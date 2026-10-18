package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures decoding 60 s of 320 kbit/s stereo 44.1 kHz MP3 (demuxing excluded); compare with
 * {@code ffmpeg -threads 1 -benchmark -i <fixture> -f null -}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class Mp3DecoderBenchmark {
    private AudioTrack track;
    private List<Packet> packets;

    @Setup
    public void setup() {
        var file = Mp3Fixtures.lame(44100, 2, 60, "-b:a", "320k");
        System.out.println("fixture: " + file.toAbsolutePath());
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(Fixtures.read(file)))) {
            track = AudioPipeline.firstAudio(demuxer);
            packets = AudioPipeline.packets(demuxer, 0);
        }
    }

    @Benchmark
    public double decode() {
        var sum = new double[1];
        try (var decoder = new Mp3Decoder(track)) {
            for (var packet : packets) {
                decoder.decode(packet, buffer -> sum[0] += buffer.plane(0)[100]);
            }
        }
        return sum[0];
    }
}
