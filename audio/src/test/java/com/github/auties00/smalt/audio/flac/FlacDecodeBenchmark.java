package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.container.flac.FlacDemuxer;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures FLAC decoding of 60 s of 24-bit stereo 48 kHz noise-plus-tone audio (compression level
 * 5), the workload of {@code ffmpeg -threads 1 -benchmark -i bench.flac -f null -}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class FlacDecodeBenchmark {
    /** The generating filter graph: two partly correlated pink-noise-plus-tone channels. */
    static final String GRAPH = "anoisesrc=c=pink:a=0.25:d=60:r=48000:seed=1[a];anoisesrc=c=pink:a=0.25:d=60:r=48000:seed=2[b];"
            + "sine=f=440:r=48000:d=60,volume=0.3,asplit[s1][s2];[a][s1]amix=inputs=2:normalize=0[l];"
            + "[b][s2]amix=inputs=2:normalize=0[r];[l][r]amerge=inputs=2";

    private byte[] file;
    private AudioTrack track;
    private List<Packet> packets;

    @Setup
    public void setup() {
        var path = Fixtures.ffmpeg("flac", List.of("-filter_complex", GRAPH),
                List.of("-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24", "-compression_level", "5"));
        file = Fixtures.read(path);
        try (var demuxer = new FlacDemuxer(MediaSource.of(file))) {
            track = (AudioTrack) demuxer.tracks().getFirst();
            packets = new ArrayList<>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
            }
        }
    }

    /** Demuxes and decodes the whole file from memory. */
    @Benchmark
    public long demuxAndDecode() {
        var sum = new long[1];
        try (var demuxer = new FlacDemuxer(MediaSource.of(file));
             var decoder = new FlacDecoder((AudioTrack) demuxer.tracks().getFirst())) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                decoder.decode(packet, buffer -> sum[0] += buffer.frames());
            }
        }
        return sum[0];
    }

    /** Decodes pre-demuxed packets. */
    @Benchmark
    public long decode() {
        var sum = new long[1];
        try (var decoder = new FlacDecoder(track)) {
            for (var packet : packets) {
                decoder.decode(packet, buffer -> sum[0] += buffer.frames());
            }
        }
        return sum[0];
    }

    /** Demuxes the whole file without decoding. */
    @Benchmark
    public long demux() {
        var sum = 0L;
        try (var demuxer = new FlacDemuxer(MediaSource.of(file))) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                sum += packet.size();
            }
        }
        return sum;
    }
}
