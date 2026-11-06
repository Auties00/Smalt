package com.github.auties00.smalt.audio.voice;

import com.dylibso.chicory.compiler.InterpreterFallback;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Function;

/**
 * The bit-exact reference of the mobile echo canceller: the legacy {@code WebRtcAecm_*} API of
 * webrtc-audio-processing 2.1, driven directly through the mobile echo canceller exports of the
 * oracle module ({@code apm.wasm}, see {@link WasmApmOracle}) at 16 kHz.
 */
public final class WasmAecmOracle implements AutoCloseable {
    public static final int FRAME = 160;

    /**
     * One scenario of {@link VoiceScenarios} as 16-bit render and capture streams.
     */
    public record Stream(String name, short[] render, short[] capture) {
        public int frames() {
            return capture.length / FRAME;
        }
    }

    /** Returns the {@link VoiceScenarios#suite(int)} streams, for tests outside this package. */
    public static List<Stream> scenarios(int seconds) {
        return VoiceScenarios.suite(seconds).stream()
                .map(scenario -> new Stream(scenario.name(), scenario.render(), scenario.capture()))
                .toList();
    }

    private static final class Compiled {
        static final WasmModule MODULE;
        static final Function<Instance, Machine> MACHINE;

        static {
            try (var in = WasmAecmOracle.class.getResourceAsStream("apm.wasm")) {
                if (in == null) {
                    throw new IllegalStateException("apm.wasm not found next to WasmAecmOracle");
                }
                MODULE = Parser.parse(in);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            MACHINE = MachineFactoryCompiler.builder(MODULE).withInterpreterFallback(InterpreterFallback.FAIL).compile();
        }
    }

    private final WasiPreview1 wasi;
    private final Memory memory;
    private final ExportFunction bufferFarend;
    private final ExportFunction process;
    private final ExportFunction destroy;
    private final int handle;
    private final int farendPointer;
    private final int noisyPointer;
    private final int cleanPointer;
    private final int outPointer;

    /**
     * Creates the reference canceller: {@code WebRtcAecm_Create}, {@code WebRtcAecm_Init(16000)}
     * and {@code WebRtcAecm_set_config(echoMode, comfortNoise)}.
     */
    public WasmAecmOracle(int echoMode, boolean comfortNoise) {
        this.wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder().withStdout(OutputStream.nullOutputStream())
                        .withStderr(OutputStream.nullOutputStream()).build())
                .build();
        var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
        var instance = Instance.builder(Compiled.MODULE).withImportValues(imports).withMachineFactory(Compiled.MACHINE).build();
        this.memory = instance.memory();
        instance.export("_initialize").apply();
        var malloc = instance.export("malloc");
        this.farendPointer = (int) malloc.apply(FRAME * 2)[0];
        this.noisyPointer = (int) malloc.apply(FRAME * 2)[0];
        this.cleanPointer = (int) malloc.apply(FRAME * 2)[0];
        this.outPointer = (int) malloc.apply(FRAME * 2)[0];
        this.handle = (int) instance.export("smalt_aecm_create").apply(echoMode, comfortNoise ? 1 : 0)[0];
        if (handle == 0) {
            throw new IllegalStateException("smalt_aecm_create returned NULL");
        }
        this.bufferFarend = instance.export("smalt_aecm_buffer_farend");
        this.process = instance.export("smalt_aecm_process");
        this.destroy = instance.export("smalt_aecm_destroy");
    }

    public int bufferFarend(short[] farend, int offset) {
        write(farendPointer, farend, offset);
        return (int) bufferFarend.apply(handle, farendPointer)[0];
    }

    /**
     * Runs {@code WebRtcAecm_Process}; with {@code inPlace} the output buffer is the noisy input
     * buffer, as the APM calls it.
     */
    public int process(short[] noisy, int noisyOffset, short[] clean, int cleanOffset, short[] out, int outOffset,
                       int delayMs, boolean inPlace) {
        write(noisyPointer, noisy, noisyOffset);
        var cleanArgument = 0;
        if (clean != null) {
            write(cleanPointer, clean, cleanOffset);
            cleanArgument = cleanPointer;
        }
        var target = inPlace ? noisyPointer : outPointer;
        var result = (int) process.apply(handle, noisyPointer, cleanArgument, target, delayMs)[0];
        for (var i = 0; i < FRAME; i++) {
            out[outOffset + i] = memory.readShort(target + 2 * i);
        }
        return result;
    }

    private void write(int pointer, short[] samples, int offset) {
        for (var i = 0; i < FRAME; i++) {
            memory.writeShort(pointer + 2 * i, samples[offset + i]);
        }
    }

    @Override
    public void close() {
        destroy.apply(handle);
        wasi.close();
    }
}
