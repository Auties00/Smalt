package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.io.MediaSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Times whole decodes of camera raw files held in memory: the sensor data alone
 * ({@link RawDecoder#decodeRaw}), and the full rendering with each demosaicing algorithm. The
 * thread count comes from the {@code smalt.threads} system property of the fork.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RawBenchmark {
    @Param({"ADC_5D3_LOSSLESS", "CR2_5D3", "NEF_D7000_LOSSLESS_14", "ARW_A7M2_UNCOMPRESSED_14", "MRW_DYNAX_7D", "CRW_10D", "X3F_SD15", "X3F_DP2_MERRILL"})
    public String sample;

    private byte[] bytes;

    @Setup
    public void load() throws IOException {
        bytes = Files.readAllBytes(RawSamples.valueOf(sample).fetch());
    }

    @Benchmark
    public Object sensor() {
        return RawDecoder.decodeRaw(MediaSource.of(bytes));
    }

    @Benchmark
    public Object bilinear() {
        return RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.fast());
    }

    @Benchmark
    public Object menon() {
        return RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.defaults());
    }

    @Benchmark
    public Object preview() {
        return RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.builder().minSize(320, 213).build());
    }
}
