package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.io.MediaSource;
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
import java.util.concurrent.TimeUnit;

/**
 * Measures FLAC encoding of real music from memory: 60 s of 48 kHz 16-bit stereo (IETF CELLAR
 * {@code subset/53}) and 8 s of 96 kHz 24-bit stereo ({@code subset/30}), at compression levels
 * 0, 5 and 8, with the production kernel or the scalar one; the workload of
 * {@code flac -<level> --no-padding --no-seektable} on the same samples.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class FlacEncodeBenchmark {
    @Param({"music16", "music24"})
    public String clip;

    @Param({"5"})
    public int level;

    @Param({"production", "scalar"})
    public String kernel;

    private int[][] samples;
    private FlacEncoderOptions options;
    private FlacEncoderKernel implementation;

    @Setup
    public void setup() {
        var file = clip.equals("music16") ? FlacConformanceCorpus.vector("subset", "53") : FlacConformanceCorpus.vector("subset", "30");
        var chunks = new ArrayList<int[][]>();
        var total = 0;
        int bits;
        int rate;
        try (var demuxer = new FlacDemuxer(MediaSource.of(file));
             var decoder = new FlacDecoder((AudioTrack) demuxer.tracks().getFirst())) {
            rate = decoder.sampleRate();
            for (Packet packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                decoder.decode(packet, buffer -> {
                });
                var size = decoder.lastBlockSize();
                chunks.add(new int[][]{java.util.Arrays.copyOf(decoder.lastSamples()[0], size),
                        java.util.Arrays.copyOf(decoder.lastSamples()[1], size)});
                total += size;
            }
            bits = decoder.lastBitsPerSample();
        }
        samples = new int[2][total];
        var position = 0;
        for (var chunk : chunks) {
            System.arraycopy(chunk[0], 0, samples[0], position, chunk[0].length);
            System.arraycopy(chunk[1], 0, samples[1], position, chunk[1].length);
            position += chunk[0].length;
        }
        options = FlacEncoderOptions.ofCompressionLevel(level).toBuilder().format(rate, ChannelLayout.STEREO)
                .bitsPerSample(bits).noSeekTable().padding(0).build();
        implementation = kernel.equals("scalar") ? new ScalarFlacEncoderKernel() : FlacEncoderKernel.INSTANCE;
    }

    /** Encodes the whole clip, MD5 signature included, in 4096-sample chunks. */
    @Benchmark
    public long encode() {
        var bytes = new long[1];
        var encoder = new FlacEncoder(options, implementation);
        var length = samples[0].length;
        for (var position = 0; position < length; position += 4096) {
            encoder.encode(samples, position, Math.min(4096, length - position), packet -> bytes[0] += packet.size());
        }
        encoder.flush(packet -> bytes[0] += packet.size());
        return bytes[0];
    }
}
