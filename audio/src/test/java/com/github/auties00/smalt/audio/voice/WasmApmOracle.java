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
 * (a create call that takes every setting as two parameter arrays, the process and analog level
 * calls, and some building blocks of the APM), compiled to wasm32-wasip1 with wasi-sdk 34 at -O2
 * (no SIMD, so every float operation runs in source order) and run by Chicory, compiled to JVM
 * bytecode. The module ({@code apm.wasm}) is a test resource next to this class;
 * {@code tools/testdata/apm-oracle} holds the shim and the script that builds it. Frames are
 * exchanged interleaved, as the voice processor takes them, and converted to the shim's planar
 * layout here.
 */
public final class WasmApmOracle implements AutoCloseable {
    static final int AEC_OFF = 0;
    static final int AEC_MOBILE = 1;
    static final int AEC_AEC3 = 2;

    private static final int I_RATE = 0;
    private static final int I_CAPTURE_CHANNELS = 1;
    private static final int I_RENDER_CHANNELS = 2;
    private static final int I_MC_CAPTURE = 3;
    private static final int I_MC_RENDER = 4;
    private static final int I_AEC = 6;
    private static final int I_NS = 7;
    private static final int I_AGC1_MODE = 8;
    private static final int I_AGC1_TARGET = 9;
    private static final int I_AGC1_COMPRESSION = 10;
    private static final int I_AGC1_LIMITER = 11;
    private static final int I_ANALOG = 12;
    private static final int I_ANALOG_CLIPPED_MIN = 14;
    private static final int I_ANALOG_DIGITAL = 15;
    private static final int I_ANALOG_CLIPPED_STEP = 16;
    private static final int I_ANALOG_CLIPPED_WAIT = 17;
    private static final int I_CP_ENABLED = 18;
    private static final int I_CP_MODE = 19;
    private static final int I_CP_WINDOW = 20;
    private static final int I_CP_REF_WINDOW = 21;
    private static final int I_CP_REF_DELAY = 22;
    private static final int I_CP_USE_STEP = 23;
    private static final int I_AGC2 = 24;
    private static final int I_AGC2_ADAPTIVE = 25;
    private static final int I_AGC2_IVC = 26;
    private static final int I_COUNT = 28;

    private static final int F_CLIPPED_RATIO = 0;
    private static final int F_CP_THRESHOLD = 1;
    private static final int F_CP_CREST = 2;
    private static final int F_AGC2_FIXED_GAIN = 3;
    private static final int F_AGC2_HEADROOM = 4;
    private static final int F_AGC2_MAX_GAIN = 5;
    private static final int F_AGC2_INITIAL_GAIN = 6;
    private static final int F_AGC2_MAX_CHANGE = 7;
    private static final int F_AGC2_MAX_NOISE = 8;
    private static final int F_COUNT = 9;

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

    /**
     * A fresh instance of the oracle module, with its memory and exports.
     */
    static final class Module implements AutoCloseable {
        private final WasiPreview1 wasi;
        final Instance instance;
        final Memory memory;
        private final ExportFunction malloc;

        Module() {
            this.wasi = WasiPreview1.builder()
                    .withOptions(WasiOptions.builder().withStdout(OutputStream.nullOutputStream())
                            .withStderr(OutputStream.nullOutputStream()).build())
                    .build();
            var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
            this.instance = Instance.builder(Compiled.MODULE).withImportValues(imports)
                    .withMachineFactory(Compiled.MACHINE).build();
            this.memory = instance.memory();
            instance.export("_initialize").apply();
            this.malloc = instance.export("malloc");
        }

        int malloc(int bytes) {
            return (int) malloc.apply(bytes)[0];
        }

        ExportFunction export(String name) {
            return instance.export(name);
        }

        void writeFloats(int pointer, float[] values, int offset, int length) {
            for (var i = 0; i < length; i++) {
                memory.writeF32(pointer + 4 * i, values[offset + i]);
            }
        }

        void readFloats(int pointer, float[] values, int offset, int length) {
            for (var i = 0; i < length; i++) {
                values[offset + i] = memory.readFloat(pointer + 4 * i);
            }
        }

        @Override
        public void close() {
            wasi.close();
        }
    }

    private final Module module;
    private final ExportFunction processReverse;
    private final ExportFunction process;
    private final ExportFunction setDelay;
    private final ExportFunction setAnalogLevel;
    private final ExportFunction recommendedAnalogLevel;
    private final ExportFunction destroy;
    private final int handle;
    private final int frames;
    private final int captureChannels;
    private final int renderChannels;
    private final int renderPointer;
    private final int capturePointer;
    private final float[] planar;

    /**
     * Creates an APM through the shim's {@code smalt_apm_create} with the given parameter arrays.
     */
    WasmApmOracle(int[] ints, float[] floats) {
        this.module = new Module();
        this.frames = ints[I_RATE] / 100;
        this.captureChannels = ints[I_CAPTURE_CHANNELS];
        this.renderChannels = ints[I_RENDER_CHANNELS];
        this.renderPointer = module.malloc(frames * renderChannels * 4);
        this.capturePointer = module.malloc(frames * captureChannels * 4);
        this.planar = new float[frames * Math.max(captureChannels, renderChannels)];
        var intsPointer = module.malloc(4 * I_COUNT);
        var floatsPointer = module.malloc(4 * F_COUNT);
        for (var i = 0; i < I_COUNT; i++) {
            module.memory.writeI32(intsPointer + 4 * i, ints[i]);
        }
        module.writeFloats(floatsPointer, floats, 0, F_COUNT);
        this.handle = (int) module.export("smalt_apm_create").apply(intsPointer, floatsPointer)[0];
        if (handle == 0) {
            throw new IllegalStateException("smalt_apm_create returned NULL");
        }
        this.processReverse = module.export("smalt_apm_process_reverse");
        this.process = module.export("smalt_apm_process");
        this.setDelay = module.export("smalt_apm_set_stream_delay_ms");
        this.setAnalogLevel = module.export("smalt_apm_set_stream_analog_level");
        this.recommendedAnalogLevel = module.export("smalt_apm_recommended_stream_analog_level");
        this.destroy = module.export("smalt_apm_destroy");
    }

    /** Creates the oracle matching voice processor options. */
    static WasmApmOracle of(VoiceProcessorOptions options) {
        var ints = new int[I_COUNT];
        var floats = new float[F_COUNT];
        ints[I_RATE] = options.sampleRate();
        ints[I_CAPTURE_CHANNELS] = options.captureChannels();
        ints[I_RENDER_CHANNELS] = options.renderChannels();
        ints[I_MC_CAPTURE] = options.multichannelCapture() ? 1 : 0;
        ints[I_MC_RENDER] = options.multichannelRender() ? 1 : 0;
        ints[I_AEC] = switch (options.echoCancellation()) {
            case OFF -> AEC_OFF;
            case STANDARD -> AEC_AEC3;
            case MOBILE -> AEC_MOBILE;
        };
        ints[I_NS] = options.noiseSuppression().level();
        ints[I_AGC1_MODE] = switch (options.gainControl()) {
            case OFF -> -1;
            case ADAPTIVE_ANALOG, MANAGED_ANALOG -> 0;
            case ADAPTIVE_DIGITAL -> 1;
            case FIXED_DIGITAL -> 2;
        };
        ints[I_AGC1_TARGET] = options.targetLevelDbfs();
        ints[I_AGC1_COMPRESSION] = options.compressionGainDb();
        ints[I_AGC1_LIMITER] = options.limiter() ? 1 : 0;
        var analog = options.analogGainController();
        ints[I_ANALOG] = options.gainControl() == GainControl.MANAGED_ANALOG ? 1 : 0;
        ints[I_ANALOG_CLIPPED_MIN] = analog.clippedLevelMin();
        ints[I_ANALOG_DIGITAL] = analog.digitalAdaptive() ? 1 : 0;
        ints[I_ANALOG_CLIPPED_STEP] = analog.clippedLevelStep();
        ints[I_ANALOG_CLIPPED_WAIT] = analog.clippedWaitFrames();
        floats[F_CLIPPED_RATIO] = analog.clippedRatioThreshold();
        ints[I_CP_ENABLED] = analog.clippingPrediction() != ClippingPrediction.OFF ? 1 : 0;
        ints[I_CP_MODE] = switch (analog.clippingPrediction()) {
            case OFF, CLIPPING_EVENT -> 0;
            case ADAPTIVE_STEP_PEAK -> 1;
            case FIXED_STEP_PEAK -> 2;
        };
        ints[I_CP_WINDOW] = analog.predictionWindowFrames();
        ints[I_CP_REF_WINDOW] = analog.referenceWindowFrames();
        ints[I_CP_REF_DELAY] = analog.referenceWindowDelayFrames();
        ints[I_CP_USE_STEP] = analog.predictedStep() ? 1 : 0;
        floats[F_CP_THRESHOLD] = analog.clippingThresholdDbfs();
        floats[F_CP_CREST] = analog.crestFactorMarginDb();
        var level = options.levelControl();
        ints[I_AGC2] = level.isPresent() ? 1 : 0;
        var agc2 = level.orElse(LevelControlOptions.defaults());
        ints[I_AGC2_ADAPTIVE] = level.isPresent() && agc2.adaptiveDigital() ? 1 : 0;
        ints[I_AGC2_IVC] = level.isPresent() && agc2.inputVolumeController() ? 1 : 0;
        floats[F_AGC2_FIXED_GAIN] = agc2.fixedGainDb();
        floats[F_AGC2_HEADROOM] = agc2.headroomDb();
        floats[F_AGC2_MAX_GAIN] = agc2.maxGainDb();
        floats[F_AGC2_INITIAL_GAIN] = agc2.initialGainDb();
        floats[F_AGC2_MAX_CHANGE] = agc2.maxGainChangeDbPerSecond();
        floats[F_AGC2_MAX_NOISE] = agc2.maxOutputNoiseLevelDbfs();
        return new WasmApmOracle(ints, floats);
    }

    int frameSamples() {
        return frames;
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

    /** Feeds one interleaved render frame. */
    void processRender(float[] frame, int offset) {
        deinterleave(frame, offset, renderChannels);
        module.writeFloats(renderPointer, planar, 0, frames * renderChannels);
        if (processReverse.apply(handle, renderPointer)[0] != 0) {
            throw new IllegalStateException("process_reverse failed");
        }
    }

    /** Conditions one interleaved capture frame in place. */
    void processCapture(float[] frame, int offset) {
        deinterleave(frame, offset, captureChannels);
        module.writeFloats(capturePointer, planar, 0, frames * captureChannels);
        if (process.apply(handle, capturePointer)[0] != 0) {
            throw new IllegalStateException("process failed");
        }
        module.readFloats(capturePointer, planar, 0, frames * captureChannels);
        for (var ch = 0; ch < captureChannels; ch++) {
            for (var i = 0; i < frames; i++) {
                frame[offset + i * captureChannels + ch] = planar[ch * frames + i];
            }
        }
    }

    private void deinterleave(float[] frame, int offset, int channels) {
        for (var ch = 0; ch < channels; ch++) {
            for (var i = 0; i < frames; i++) {
                planar[ch * frames + i] = frame[offset + i * channels + ch];
            }
        }
    }

    @Override
    public void close() {
        destroy.apply(handle);
        module.close();
    }

    /**
     * The reference push sinc resampler.
     */
    static final class Resampler implements AutoCloseable {
        private final Module module = new Module();
        private final int handle;
        private final int in;
        private final int out;
        private final int sourceFrames;
        private final int destinationFrames;

        Resampler(int sourceFrames, int destinationFrames) {
            this.sourceFrames = sourceFrames;
            this.destinationFrames = destinationFrames;
            this.handle = (int) module.export("smalt_resampler_create").apply(sourceFrames, destinationFrames)[0];
            this.in = module.malloc(4 * sourceFrames);
            this.out = module.malloc(4 * destinationFrames);
        }

        void resample(float[] source, float[] destination) {
            module.writeFloats(in, source, 0, sourceFrames);
            module.export("smalt_resampler_resample").apply(handle, in, out);
            module.readFloats(out, destination, 0, destinationFrames);
        }

        @Override
        public void close() {
            module.export("smalt_resampler_destroy").apply(handle);
            module.close();
        }
    }

    /**
     * The reference band splitting filter of one channel.
     */
    static final class Splitter implements AutoCloseable {
        private final Module module = new Module();
        private final int handle;
        private final int data;
        private final int bands;
        private final int frames;

        Splitter(int numBands) {
            this.frames = 160 * numBands;
            this.handle = (int) module.export("smalt_split_create").apply(numBands, frames)[0];
            this.data = module.malloc(4 * frames);
            this.bands = module.malloc(4 * frames);
        }

        void analysis(float[] input, float[] output) {
            module.writeFloats(data, input, 0, frames);
            module.export("smalt_split_analysis").apply(handle, data, bands);
            module.readFloats(bands, output, 0, frames);
        }

        void synthesis(float[] input, float[] output) {
            module.writeFloats(bands, input, 0, frames);
            module.export("smalt_split_synthesis").apply(handle, bands, data);
            module.readFloats(data, output, 0, frames);
        }

        @Override
        public void close() {
            module.export("smalt_split_destroy").apply(handle);
            module.close();
        }
    }

    /**
     * The reference libm, the musl functions the oracle links.
     */
    public static final class Libm implements AutoCloseable {
        public static final int EXP = 0;
        public static final int LOG = 1;
        public static final int POW = 2;
        public static final int LOG10 = 3;
        public static final int SIN = 4;

        private final Module module = new Module();
        private final ExportFunction function = module.export("smalt_libm");

        public double apply(int which, double x, double y) {
            var result = function.apply(which, Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y));
            return Double.longBitsToDouble(result[0]);
        }

        @Override
        public void close() {
            module.close();
        }
    }

    /**
     * The reference voice activity detector of the analog gain controller.
     */
    public static final class AgcVad implements AutoCloseable {
        private final Module module = new Module();
        private final int handle;
        private final int audio;
        private final int out;
        private final ExportFunction process;

        public AgcVad() {
            this.handle = (int) module.export("smalt_avad_create").apply()[0];
            this.audio = module.malloc(2 * 160);
            this.out = module.malloc(8 * 7);
            this.process = module.export("smalt_avad_process");
        }

        /**
         * Processes 160 samples; returns the number of results and fills {@code result} with the
         * three RMS levels, the three probabilities and the last probability.
         */
        public int process(short[] samples, double[] result) {
            for (var i = 0; i < 160; i++) {
                module.memory.writeShort(audio + 2 * i, samples[i]);
            }
            var frames = (int) process.apply(handle, audio, out)[0];
            for (var i = 0; i < 7; i++) {
                result[i] = Double.longBitsToDouble(module.memory.readLong(out + 8 * i));
            }
            return frames;
        }

        @Override
        public void close() {
            module.export("smalt_avad_destroy").apply(handle);
            module.close();
        }
    }

    /**
     * The reference voice activity detector of the level controller.
     */
    static final class Vad implements AutoCloseable {
        private final Module module = new Module();
        private final int handle;
        private final int frame;
        private final int frames;

        Vad(int sampleRate) {
            this.frames = sampleRate / 100;
            this.handle = (int) module.export("smalt_vad_create").apply(sampleRate)[0];
            this.frame = module.malloc(4 * frames);
        }

        float analyze(float[] samples, int offset) {
            module.writeFloats(frame, samples, offset, frames);
            return Float.intBitsToFloat((int) module.export("smalt_vad_analyze").apply(handle, frame)[0]);
        }

        @Override
        public void close() {
            module.export("smalt_vad_destroy").apply(handle);
            module.close();
        }
    }
}
