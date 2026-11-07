package com.github.auties00.smalt.audio.voice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs a voice processor and the WebRTC APM oracle ({@link WasmApmOracle}) side by side on the same
 * streams: the scalar processor without flushing tiny values, which must equal the oracle bit for
 * bit, and the processor with flushing, as the public constructor configures it. The mobile echo
 * canceller gets a 40 ms stream delay before every frame, the other configurations a single 0 ms
 * report at the start; a configuration that needs the microphone volume gets the volume the
 * oracle recommended after the previous frame, starting from 128.
 */
final class OracleHarness {
    static final int MOBILE_DELAY_MS = 40;
    static final int INITIAL_ANALOG_LEVEL = 128;

    private OracleHarness() {
    }

    /**
     * The comparison of a run.
     */
    record Result(double maxDifference, long identical, long samples, String javaDigest, String oracleDigest,
                  double flushedMaxDifference, int levelMismatches, int firstMismatchFrame) {
    }

    static boolean needsAnalogLevel(VoiceProcessorOptions options) {
        return options.gainControl() == GainControl.ADAPTIVE_ANALOG
                || options.gainControl() == GainControl.MANAGED_ANALOG
                || options.levelControl().map(LevelControlOptions::inputVolumeController).orElse(false);
    }

    static Result run(VoiceProcessorOptions options, RateScenarios.Streams streams) {
        var mobile = options.echoCancellation() == EchoCancellation.MOBILE;
        var analog = needsAnalogLevel(options);
        var delay = mobile ? MOBILE_DELAY_MS : 0;
        var java = new VoiceProcessor(options, false, false);
        java.setStreamDelayMs(delay);
        var flushed = new VoiceProcessor(options, false, true);
        flushed.setStreamDelayMs(delay);
        var flushedMaxDifference = 0.0;
        var javaDigest = sha256();
        var oracleDigest = sha256();
        var maxDifference = 0.0;
        long identical = 0;
        var levelMismatches = 0;
        var firstMismatch = -1;
        var n = streams.frameSamples();
        var renderLength = n * streams.renderChannels();
        var captureLength = n * streams.captureChannels();
        try (var oracle = WasmApmOracle.of(options)) {
            oracle.setStreamDelayMs(delay);
            var level = INITIAL_ANALOG_LEVEL;
            var flushedLevel = INITIAL_ANALOG_LEVEL;
            var render = new float[renderLength];
            var javaFrame = new float[captureLength];
            var oracleFrame = new float[captureLength];
            var flushedFrame = new float[captureLength];
            var bytes = new byte[4 * captureLength];
            for (var f = 0; f < streams.frames(); f++) {
                for (var i = 0; i < renderLength; i++) {
                    render[i] = streams.render()[f * renderLength + i] / 32768f;
                }
                for (var i = 0; i < captureLength; i++) {
                    javaFrame[i] = streams.capture()[f * captureLength + i] / 32768f;
                    oracleFrame[i] = javaFrame[i];
                    flushedFrame[i] = javaFrame[i];
                }
                java.processRender(render, 0);
                flushed.processRender(render, 0);
                oracle.processRender(render, 0);
                if (mobile) {
                    oracle.setStreamDelayMs(delay);
                }
                if (analog) {
                    java.setAnalogLevel(level);
                    flushed.setAnalogLevel(flushedLevel);
                    oracle.setAnalogLevel(level);
                }
                java.processCapture(javaFrame, 0, javaFrame, 0);
                flushed.processCapture(flushedFrame, 0, flushedFrame, 0);
                oracle.processCapture(oracleFrame, 0);
                if (analog) {
                    level = oracle.recommendedAnalogLevel();
                    if (java.recommendedAnalogLevel() != level) {
                        levelMismatches++;
                    }
                    flushedLevel = flushed.recommendedAnalogLevel();
                }
                for (var i = 0; i < captureLength; i++) {
                    var difference = Math.abs((double) javaFrame[i] - oracleFrame[i]);
                    maxDifference = Math.max(maxDifference, difference);
                    flushedMaxDifference = Math.max(flushedMaxDifference,
                            Math.abs((double) flushedFrame[i] - oracleFrame[i]));
                    if (Float.floatToRawIntBits(javaFrame[i]) == Float.floatToRawIntBits(oracleFrame[i])) {
                        identical++;
                    } else if (firstMismatch < 0) {
                        firstMismatch = f;
                    }
                }
                javaDigest.update(littleEndian(javaFrame, bytes));
                oracleDigest.update(littleEndian(oracleFrame, bytes));
            }
        }
        return new Result(maxDifference, identical, (long) streams.frames() * captureLength,
                HexFormat.of().formatHex(javaDigest.digest()), HexFormat.of().formatHex(oracleDigest.digest()),
                flushedMaxDifference, levelMismatches, firstMismatch);
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static byte[] littleEndian(float[] frame, byte[] bytes) {
        for (var i = 0; i < frame.length; i++) {
            var bits = Float.floatToRawIntBits(frame[i]);
            bytes[4 * i] = (byte) bits;
            bytes[4 * i + 1] = (byte) (bits >>> 8);
            bytes[4 * i + 2] = (byte) (bits >>> 16);
            bytes[4 * i + 3] = (byte) (bits >>> 24);
        }
        return bytes;
    }

    /**
     * Reads a golden digest file: one {@code key digest} pair per line, {@code #} comments.
     */
    static Map<String, String> loadGoldens(String resource) {
        var goldens = new LinkedHashMap<String, String>();
        try (var in = OracleHarness.class.getResourceAsStream(resource)) {
            if (in == null) {
                return goldens;
            }
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var separator = line.lastIndexOf(' ');
                goldens.put(line.substring(0, separator), line.substring(separator + 1));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return goldens;
    }

    /**
     * Describes the options a case runs with, the stable part of its golden key.
     */
    static String describe(VoiceProcessorOptions options) {
        var echo = switch (options.echoCancellation()) {
            case OFF -> "noaec";
            case STANDARD -> "aec3";
            case MOBILE -> "aecm-" + options.mobileEchoRoute() + (options.comfortNoise() ? "-cng" : "");
        };
        var description = echo + "|" + options.noiseSuppression();
        if (options.gainControl() == GainControl.MANAGED_ANALOG) {
            var analog = options.analogGainController();
            description += "|agc-MANAGED_ANALOG-" + analog.clippedLevelMin() + "-" + analog.clippedLevelStep() + "-"
                    + analog.clippedRatioThreshold() + "-" + analog.clippedWaitFrames() + "-"
                    + (analog.digitalAdaptive() ? "digital" : "nodigital");
            if (analog.clippingPrediction() != ClippingPrediction.OFF) {
                description += "-" + analog.clippingPrediction() + "-" + analog.predictionWindowFrames() + "-"
                        + analog.referenceWindowFrames() + "-" + analog.referenceWindowDelayFrames() + "-"
                        + analog.clippingThresholdDbfs() + "-" + analog.crestFactorMarginDb() + "-"
                        + (analog.predictedStep() ? "step" : "nostep");
            }
        } else if (options.gainControl() != GainControl.OFF) {
            description += "|agc-" + options.gainControl() + "-" + options.targetLevelDbfs() + "-"
                    + options.compressionGainDb() + "-" + (options.limiter() ? "limiter" : "nolimiter");
        }
        if (options.levelControl().isPresent()) {
            var level = options.levelControl().get();
            description += "|agc2-" + level.fixedGainDb() + "-" + (level.adaptiveDigital() ? "adaptive-"
                    + level.headroomDb() + "-" + level.maxGainDb() + "-" + level.initialGainDb() + "-"
                    + level.maxGainChangeDbPerSecond() + "-" + level.maxOutputNoiseLevelDbfs() : "fixed")
                    + (level.inputVolumeController() ? "-ivc" : "");
        }
        if (options.sampleRate() != 16000 || options.captureChannels() != 1 || options.renderChannels() != 1) {
            description += "|" + options.sampleRate() + "Hz-" + options.captureChannels() + "x"
                    + options.renderChannels() + (options.multichannelCapture() ? "-mccapture" : "")
                    + (options.multichannelRender() ? "-mcrender" : "");
        }
        return description;
    }

    static String key(VoiceProcessorOptions options, RateScenarios.Streams streams) {
        return describe(options) + "|" + streams.name() + "|" + streams.frames();
    }
}
