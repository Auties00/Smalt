package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.audio.ChannelLayout;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Times resampling 60 s of 44.1 kHz stereo repeatedly and prints the minimum and median wall time,
 * which is robust against a busy machine, and the process CPU time per run (after the first
 * round the JIT is idle, so this is the resampling thread's CPU time), which excludes the time
 * the thread spends preempted. Arguments: runs, block size in frames.
 */
public final class ResamplerTiming {
    private static final int RATE = 44100;
    private static final int FRAMES = 60 * RATE;

    private ResamplerTiming() {
    }

    public static void main(String[] args) {
        var runs = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        var block = args.length > 1 ? Integer.parseInt(args[1]) : 4096;
        var random = new SplittableRandom(1);
        var input = new float[2][FRAMES];
        for (var i = 0; i < FRAMES; i++) {
            input[0][i] = (float) random.nextDouble(-0.5, 0.5);
            input[1][i] = (float) random.nextDouble(-0.5, 0.5);
        }
        var mixer = new ChannelMixer(ChannelLayout.STEREO, ChannelLayout.MONO);
        var mono = new Resampler(RATE, 16000, 1, Resampler.Quality.STANDARD);
        var stereo = new Resampler(RATE, 48000, 2, Resampler.Quality.STANDARD);
        var monoOut = new float[1][mono.maxOutputFrames(block) + 64];
        var stereoOut = new float[2][stereo.maxOutputFrames(block) + 64];
        var slice = new float[2][block];
        var mixed = new float[1][block];
        for (var round = 0; round < 2; round++) {
            report("calibration: 1e8 scalar dependent adds (1 cycle each)", runs / 4, ResamplerTiming::calibrate);
            report("mix+16k mono (block " + block + ")", runs, () -> {
                long total = 0;
                for (var offset = 0; offset < FRAMES; offset += block) {
                    var count = Math.min(block, FRAMES - offset);
                    System.arraycopy(input[0], offset, slice[0], 0, count);
                    System.arraycopy(input[1], offset, slice[1], 0, count);
                    mixer.process(slice, mixed, count);
                    total += mono.process(mixed, count, monoOut, 0);
                }
                return total + mono.flush(monoOut, 0);
            });
            report("48k stereo (block " + block + ")", runs, () -> {
                long total = 0;
                for (var offset = 0; offset < FRAMES; offset += block) {
                    var count = Math.min(block, FRAMES - offset);
                    System.arraycopy(input[0], offset, slice[0], 0, count);
                    System.arraycopy(input[1], offset, slice[1], 0, count);
                    total += stereo.process(slice, count, stereoOut, 0);
                }
                return total + stereo.flush(stereoOut, 0);
            });
        }
    }

    private static long calibrate() {
        // a dependent chain of integer additions retires at one per cycle on any x86 core, so its
        // time gives the effective clock under the current machine load
        long value = System.nanoTime() | 1;
        for (var i = 0; i < 100_000_000; i++) {
            value += i ^ (value >>> 7);
        }
        return value;
    }

    private static void report(String name, int runs, java.util.function.LongSupplier operation) {
        var times = new double[runs];
        var cpuStart = ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
        for (var i = 0; i < runs; i++) {
            var start = System.nanoTime();
            operation.getAsLong();
            times[i] = (System.nanoTime() - start) / 1e6;
        }
        var cpu = (ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos() - cpuStart) / 1e6 / runs;
        Arrays.sort(times);
        System.out.printf("%s: min %.2f ms, median %.2f ms, cpu %.2f ms%n", name, times[0], times[runs / 2], cpu);
    }
}
