package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;
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
 * Measures the SILK decoder on the 3-second golden streams (wideband mono and stereo 20 ms,
 * mediumband stereo 60 ms), output at 48 kHz, with no allocation in the timed loop beyond the
 * range decoder reuse.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SilkDecoderBenchmark {
    @Param({"wb_mono_20:48000:1", "wb_stereo_20:48000:2", "mb_stereo_60_fec:48000:2", "wb_mono_20:16000:1"})
    public String config;

    private final List<byte[]> frameData = new ArrayList<>();
    private final List<int[]> frameInfo = new ArrayList<>();
    private int apiRate;
    private int apiChannels;
    private final short[] pcm = new short[48 * 60 * 2];
    private final int[] n = new int[1];
    private final RangeDecoder dec = new RangeDecoder();

    @Setup
    public void setup() {
        var parts = config.split(":");
        apiRate = Integer.parseInt(parts[1]);
        apiChannels = Integer.parseInt(parts[2]);
        for (var packet : SilkReferenceDriver.parseBit(SilkDecoderGoldenTest.readBytes(parts[0] + ".bit"))) {
            var p = packet.data();
            var toc = p[0] & 0xFF;
            var ms = new int[]{10, 20, 40, 60}[(toc >> 3) & 3];
            var rate = (toc >> 3) < 4 ? 8000 : (toc >> 3) < 8 ? 12000 : 16000;
            for (var f : SilkReferenceDriver.frames(p)) {
                var data = new byte[f[1]];
                System.arraycopy(p, f[0], data, 0, f[1]);
                frameData.add(data);
                frameInfo.add(new int[]{ms, rate, ((toc >> 2) & 1) != 0 ? 2 : 1});
            }
        }
    }

    @Benchmark
    public int decodeStream() {
        var decoder = new SilkDecoder();
        var control = new SilkDecControl();
        control.nChannelsAPI = apiChannels;
        control.API_sampleRate = apiRate;
        var checksum = 0;
        for (var i = 0; i < frameData.size(); i++) {
            var info = frameInfo.get(i);
            var data = frameData.get(i);
            control.payloadSize_ms = info[0];
            control.internalSampleRate = info[1];
            control.nChannelsInternal = info[2];
            dec.init(data, 0, data.length);
            var calls = info[0] == 10 ? 1 : info[0] / 20;
            for (var c = 0; c < calls; c++) {
                decoder.decode(control, 0, c == 0 ? 1 : 0, dec, pcm, 0, n);
                checksum += pcm[n[0] - 1];
            }
        }
        return checksum;
    }
}
