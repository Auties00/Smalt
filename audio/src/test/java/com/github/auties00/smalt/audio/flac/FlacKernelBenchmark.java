package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.internal.header.Crc16;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures the FLAC decoding kernels in isolation: CRC-16 over 1 MiB, the LPC restore of a
 * 4096-sample block per order, and Rice decoding of 4096 residuals with parameter 12.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class FlacKernelBenchmark {
    /** The LPC order under test, a separate state so only {@link #lpc} is parameterized. */
    @State(Scope.Benchmark)
    public static class Order {
        @Param({"3", "8", "12", "32"})
        public int value;
    }

    private byte[] bytes;
    private int[] residual;
    private int[] work;
    private int[] coefficients;
    private byte[] rice;
    private FlacBitReader reader;

    @Setup
    public void setup() {
        var random = new SplittableRandom(1);
        bytes = new byte[1 << 20];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) random.nextInt(256);
        }
        residual = new int[4096];
        for (var i = 0; i < residual.length; i++) {
            residual[i] = random.nextInt(-4096, 4096);
        }
        work = new int[4096];
        coefficients = new int[32];
        for (var j = 0; j < 32; j++) {
            coefficients[j] = random.nextInt(-1000, 1000);
        }
        coefficients[0] = 30000;
        var writer = new MsbBitWriter();
        for (var value : residual) {
            var folded = value >= 0 ? (long) value << 1 : ((-(long) value) << 1) - 1;
            writer.write(1, (int) (folded >>> 12) + 1);
            writer.write((int) (folded & 0xFFF), 12);
        }
        rice = writer.toByteArray();
        reader = new FlacBitReader();
    }

    @Benchmark
    public int crc16() {
        return Crc16.crc16(0, bytes, 0, bytes.length);
    }

    @Benchmark
    public int lpc(Order order) {
        System.arraycopy(residual, 0, work, 0, work.length);
        FlacLpc.restore(work, work.length, coefficients, order.value, 15);
        return work[work.length - 1];
    }

    @Benchmark
    public int rice() {
        reader.reset(rice, 0, rice.length);
        reader.readRice(work, 0, 4096, 12);
        return work[4095];
    }
}
