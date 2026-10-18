package com.github.auties00.smalt.audio.opus.internal.celt;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Measures CELT encoding and decoding of 3 seconds of the deterministic test signal (48 kHz
 * stereo, 20 ms frames), the transforms, and the PVQ search.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class CeltBenchmark {
    @Param({"128000"})
    public int bitrate;

    private float[] pcm;
    private byte[][] packets;
    private float[] out;
    private float[] mdctIn;
    private float[] mdctOut;
    private float[] f;
    private float[] f2;

    @Setup
    public void setup() {
        pcm = CeltEncodeHarness.signal(48000, 2, 3.0);
        var config = new CeltEncodeHarness.Config(48000, 2, 960, bitrate, 1, 1, 10, 2, 0, 24, 0, 0, 0, 0, 0, 2, 0);
        var bit = CeltEncodeHarness.encode(config, pcm);
        var parsed = CeltOnlyHarness.parseBit(bit);
        packets = new byte[parsed.size()][];
        for (var i = 0; i < packets.length; i++) {
            packets[i] = parsed.get(i).data();
        }
        out = new float[960 * 2];
        mdctIn = new float[2048];
        for (var i = 0; i < mdctIn.length; i++) {
            mdctIn[i] = (float) Math.sin(i * 0.37) * 1000;
        }
        mdctOut = new float[2048];
        f = new float[960];
        f2 = new float[960];
    }

    @Benchmark
    public int encode() {
        var st = new CeltEncoder(48000, 2);
        st.setSignaling(0);
        st.setComplexity(10);
        st.setBitrate(bitrate);
        st.setVbr(1);
        var buf = new byte[1275];
        var total = 0;
        for (var off = 0; off + 1920 <= pcm.length; off += 1920) {
            total += st.encodeWithEc(pcm, off, 960, buf, 0, 1275, null);
        }
        return total;
    }

    @Benchmark
    public float decode() {
        var st = new CeltDecoder(48000, 2);
        var sum = 0f;
        for (var packet : packets) {
            st.decodeWithEc(packet, 0, packet.length, out, 0, 960, null, 0);
            sum += out[100];
        }
        return sum;
    }

    @Benchmark
    public float mdctForward() {
        var mode = CeltMode.MODE_48000_960;
        Mdct.cltMdctForward(mode.mdct, mdctIn, 0, mdctOut, 0, mode.window, 120, 0, 1, f, f2);
        return mdctOut[5];
    }

    @Benchmark
    public float mdctBackward() {
        var mode = CeltMode.MODE_48000_960;
        Mdct.cltMdctBackward(mode.mdct, mdctIn, 0, mdctOut, 0, mode.window, 120, 0, 1, f);
        return mdctOut[5];
    }
}
