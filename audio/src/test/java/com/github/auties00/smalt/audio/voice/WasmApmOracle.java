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
import java.util.function.Function;

/**
 * The bit-exact reference of the voice processor: webrtc-audio-processing 2.1 behind an APM shim
 * (with a create call that sets every knob and the analog level calls), compiled to wasm32-wasip1
 * with wasi-sdk 34 at -O2 (no SIMD, so every float operation runs in source order) and run by
 * Chicory, compiled to JVM bytecode. The module ({@code apm.wasm}) is a test resource next to this
 * class; {@code tools/testdata/apm-oracle} holds the shim and the script that builds it. The
 * export names keep the module's {@code cobalt_webrtc_} prefix until it is rebuilt.
 */
final class WasmApmOracle implements AutoCloseable {
    static final int AEC_OFF = 0;
    static final int AEC_MOBILE = 1;
    static final int AEC_AEC3 = 2;
    static final int FRAME = 160;

    private static final class Compiled {
        static final WasmModule MODULE;
        static final Function<Instance, Machine> MACHINE;

        static {
            try (var in = WasmApmOracle.class.getResourceAsStream("apm.wasm")) {
                if (in == null) {
                    throw new IllegalStateException("apm.wasm not found next to WasmApmOracle");
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
    private final ExportFunction processReverse;
    private final ExportFunction process;
    private final ExportFunction setDelay;
    private final ExportFunction setAnalogLevel;
    private final ExportFunction recommendedAnalogLevel;
    private final ExportFunction destroy;
    private final int handle;
    private final int renderPointer;
    private final int capturePointer;

    /**
     * Creates an APM through the shim's {@code cobalt_webrtc_apm_create_ex}: the echo canceller
     * ({@link #AEC_OFF}, {@link #AEC_MOBILE} or {@link #AEC_AEC3}), the noise suppression level (-1
     * off, 0 kLow to 3 kVeryHigh) and the gain controller 1 without its analog controller (-1 off,
     * 0 kAdaptiveAnalog, 1 kAdaptiveDigital, 2 kFixedDigital).
     */
    WasmApmOracle(int aecMode, int nsLevel, int agcMode, int targetLevelDbfs, int compressionGainDb, boolean limiter) {
        this.wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder().withStdout(OutputStream.nullOutputStream())
                        .withStderr(OutputStream.nullOutputStream()).build())
                .build();
        var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
        var instance = Instance.builder(Compiled.MODULE).withImportValues(imports).withMachineFactory(Compiled.MACHINE).build();
        this.memory = instance.memory();
        instance.export("_initialize").apply();
        var malloc = instance.export("malloc");
        this.renderPointer = (int) malloc.apply(FRAME * 4)[0];
        this.capturePointer = (int) malloc.apply(FRAME * 4)[0];
        this.handle = (int) instance.export("cobalt_webrtc_apm_create_ex")
                .apply(aecMode, nsLevel, agcMode, targetLevelDbfs, compressionGainDb, limiter ? 1 : 0)[0];
        if (handle == 0) {
            throw new IllegalStateException("cobalt_webrtc_apm_create_ex returned NULL");
        }
        this.processReverse = instance.export("cobalt_webrtc_apm_process_reverse");
        this.process = instance.export("cobalt_webrtc_apm_process");
        this.setDelay = instance.export("cobalt_webrtc_apm_set_stream_delay_ms");
        this.setAnalogLevel = instance.export("cobalt_webrtc_apm_set_stream_analog_level");
        this.recommendedAnalogLevel = instance.export("cobalt_webrtc_apm_recommended_stream_analog_level");
        this.destroy = instance.export("cobalt_webrtc_apm_destroy");
    }

    /** Creates the oracle matching voice processor options. */
    static WasmApmOracle of(VoiceProcessorOptions options) {
        var aec = switch (options.echoCancellation()) {
            case OFF -> AEC_OFF;
            case STANDARD -> AEC_AEC3;
            case MOBILE -> AEC_MOBILE;
        };
        var agc = switch (options.gainControl()) {
            case OFF -> -1;
            case ADAPTIVE_ANALOG -> 0;
            case ADAPTIVE_DIGITAL -> 1;
            case FIXED_DIGITAL -> 2;
        };
        return new WasmApmOracle(aec, options.noiseSuppression().level(), agc, options.targetLevelDbfs(),
                options.compressionGainDb(), options.limiter());
    }

    void setAnalogLevel(int level) {
        setAnalogLevel.apply(handle, level);
    }

    int recommendedAnalogLevel() {
        return (int) recommendedAnalogLevel.apply(handle)[0];
    }

    void setStreamDelayMs(int delayMs) {
        setDelay.apply(handle, delayMs);
    }

    void processRender(float[] frame, int offset) {
        for (var i = 0; i < FRAME; i++) {
            memory.writeF32(renderPointer + 4 * i, frame[offset + i]);
        }
        if (processReverse.apply(handle, renderPointer)[0] != 0) {
            throw new IllegalStateException("process_reverse failed");
        }
    }

    void processCapture(float[] frame, int offset) {
        for (var i = 0; i < FRAME; i++) {
            memory.writeF32(capturePointer + 4 * i, frame[offset + i]);
        }
        if (process.apply(handle, capturePointer)[0] != 0) {
            throw new IllegalStateException("process failed");
        }
        for (var i = 0; i < FRAME; i++) {
            frame[offset + i] = memory.readFloat(capturePointer + 4 * i);
        }
    }

    @Override
    public void close() {
        destroy.apply(handle);
        wasi.close();
    }
}
