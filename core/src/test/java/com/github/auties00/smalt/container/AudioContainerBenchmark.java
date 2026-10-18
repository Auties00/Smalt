package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.pcm.PcmDecoder;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.container.ogg.OggMuxerOptions;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures the containers of the audio workstream on 60 s inputs: demuxing an Ogg Opus file
 * (compare with {@code ffmpeg -threads 1 -benchmark -i in.opus -c copy -f null -}), remuxing its
 * packets with {@link OggMuxer} (compare with {@code -c copy -f ogg out.opus}), and demuxing plus
 * decoding a 16-bit stereo WAVE file (compare with {@code -i in.wav -f null -}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AudioContainerBenchmark {
    private byte[] opus;
    private byte[] wav;
    private AudioTrack opusTrack;
    private List<Packet> opusPackets;

    @Setup
    public void setup() {
        opus = Fixtures.read(Fixtures.tone("opus", 440, 48000, 2, 60, List.of("-c:a", "libopus", "-b:a", "64k")));
        wav = Fixtures.read(Fixtures.tone("wav", 440, 44100, 2, 60, List.of("-c:a", "pcm_s16le")));
        try (var demuxer = new OggDemuxer(MediaSource.of(opus))) {
            opusTrack = (AudioTrack) demuxer.tracks().getFirst();
            opusPackets = new ArrayList<>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                opusPackets.add(packet);
            }
        }
    }

    @Benchmark
    public void demuxOpus(Blackhole blackhole) {
        try (var demuxer = new OggDemuxer(MediaSource.of(opus))) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                blackhole.consume(packet);
            }
        }
    }

    @Benchmark
    public int muxOpus() {
        var output = new ByteArrayOutputStream(opus.length);
        try (var muxer = new OggMuxer(MediaSink.of(output), OggMuxerOptions.defaults())) {
            muxer.addTrack(opusTrack);
            for (var packet : opusPackets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return output.size();
    }

    @Benchmark
    public void decodeWav(Blackhole blackhole) {
        try (var demuxer = new WavDemuxer(MediaSource.of(wav));
             var decoder = new PcmDecoder((AudioTrack) demuxer.tracks().getFirst())) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                decoder.decode(packet, blackhole::consume);
            }
        }
    }
}
