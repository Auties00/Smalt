package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.audio.flac.FlacConformanceCorpus;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures ALAC decoding of real music that ffmpeg's encoder wrote to M4A: 60 s of 48 kHz 16-bit
 * stereo (IETF CELLAR FLAC {@code subset/53}) and 8 s of 96 kHz 24-bit stereo ({@code subset/30});
 * the workload of {@code ffmpeg -threads 1 -benchmark -i clip.m4a -f null -}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AlacDecodeBenchmark {
    @Param({"music16", "music24"})
    public String clip;

    private AudioTrack track;
    private List<Packet> packets;

    @Setup
    public void setup() {
        var source = clip.equals("music16") ? FlacConformanceCorpus.vector("subset", "53") : FlacConformanceCorpus.vector("subset", "30");
        var format = clip.equals("music16") ? List.of("-c:a", "alac", "-sample_fmt", "s16p")
                : List.of("-c:a", "alac", "-sample_fmt", "s32p", "-bits_per_raw_sample", "24");
        var file = Fixtures.ffmpeg("m4a", List.of("-i", source.toString()), format);
        try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
            // the source's chapters become a text track: keep the audio track's packets only
            track = (AudioTrack) demuxer.tracks().stream().filter(AudioTrack.class::isInstance).findFirst().orElseThrow();
            packets = new ArrayList<>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
        }
    }

    /** Decodes every packet to planar floats. */
    @Benchmark
    public long decode() {
        var frames = new long[1];
        var decoder = new AlacDecoder(track);
        for (var packet : packets) {
            decoder.decode(packet, buffer -> frames[0] += buffer.frames());
        }
        return frames[0];
    }
}
