package com.github.auties00.smalt.audio.voice;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Measures the call voice processor (high-pass filter, AEC3, noise suppression HIGH) per 10 ms
 * render and capture pair, on 20 s of far-end speech echoed at 250 ms, of double talk, and of talk
 * spurts separated by digital silence, with and without the Vector API kernels and with and
 * without flushing tiny values ({@code exact} is the bit-exact reference arithmetic: scalar
 * kernels, no flushing).
 *
 * <p>Run with {@code org.openjdk.jmh.Main VoiceProcessorBenchmark} from the sandbox directory. One
 * processor lives for the whole trial, as in a call; every invocation processes the next frame of
 * the looped scenario.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class VoiceProcessorBenchmark {
    @Param({"far-end 250 ms", "double talk", "render pauses"})
    public String scenario;

    @Param({"vector", "scalar", "exact"})
    public String kernels;

    private VoiceProcessor processor;
    private float[] render;
    private float[] capture;
    private float[] frame;
    private int frames;
    private int next;

    @Setup(Level.Trial)
    public void setUp() {
        var source = switch (scenario) {
            case "far-end 250 ms" -> VoiceScenarios.farEndOnly(20, 250);
            case "double talk" -> VoiceScenarios.doubleTalk(20, 60);
            case "render pauses" -> VoiceScenarios.pauses(20);
            default -> throw new IllegalArgumentException(scenario);
        };
        render = new float[source.render().length];
        capture = new float[source.capture().length];
        for (var i = 0; i < render.length; i++) {
            render[i] = source.render()[i] / 32768f;
            capture[i] = source.capture()[i] / 32768f;
        }
        frames = source.frames();
        frame = new float[VoiceScenarios.FRAME];
        processor = switch (kernels) {
            case "vector" -> new VoiceProcessor(VoiceProcessorOptions.forVoip(), true, true);
            case "scalar" -> new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, true);
            case "exact" -> new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, false);
            default -> throw new IllegalArgumentException(kernels);
        };
        processor.setStreamDelayMs(0);
    }

    @Benchmark
    public float frame() {
        var offset = next * VoiceScenarios.FRAME;
        next = next + 1 == frames ? 0 : next + 1;
        System.arraycopy(capture, offset, frame, 0, VoiceScenarios.FRAME);
        processor.processRender(render, offset);
        processor.processCapture(frame, 0, frame, 0);
        return frame[17];
    }
}
