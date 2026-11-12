/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/gain_controller2.cc and agc2/vad_wrapper.cc,
   speech_level_estimator.cc, noise_level_estimator.cc, saturation_protector.cc, saturation_protector_buffer.cc,
   adaptive_digital_gain_controller.cc, gain_applier.cc, limiter.cc, fixed_digital_level_estimator.cc,
   interpolated_gain_curve.cc). Copyright (c) 2017, The WebRTC project authors; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc2;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;
import com.github.auties00.smalt.audio.voice.internal.PushSincResampler;

/**
 * Controls the level of the full-band capture signal of one or more channels, WebRTC's gain
 * controller 2: an optional adaptive digital gain driven by a speech level estimate over the
 * frames a neural-network voice activity detector classifies as speech, kept below loud peaks by a
 * saturation protector and below the background noise ceiling; a fixed gain; and a limiter. An
 * optional input volume controller recommends a microphone volume.
 *
 * <p>Samples are in the 16-bit float range; the detector and the level estimates look at the first
 * channel, the gains apply to every channel. Instances are not thread-safe.
 *
 * @implNote This implementation ports {@code GainController2} of WebRTC's
 * {@code modules/audio_processing/gain_controller2.cc} with its internal voice activity detector and
 * the scalar code paths of every component.
 */
public final class LevelController {
    private static final float MIN_LEVEL_DBFS = -90.31f;
    private static final float MIN_DBFS = -90.30899869919436f;
    private static final float VAD_CONFIDENCE_THRESHOLD = 0.95f;
    private static final int ADJACENT_SPEECH_FRAMES_THRESHOLD = 12;
    private static final int LEVEL_ESTIMATOR_TIME_TO_CONFIDENCE_MS = 400;
    private static final float LEVEL_ESTIMATOR_LEAK_FACTOR = 1.0f - 1.0f / 400.0f;
    private static final float SATURATION_PROTECTOR_INITIAL_HEADROOM_DB = 20.0f;
    private static final float LIMITER_THRESHOLD_FOR_AGC_GAIN_DBFS = -1.0f;
    private static final int VAD_RESET_PERIOD_FRAMES = 1500 / 10;
    private static final int SUB_FRAMES_IN_FRAME = 20;
    private static final float MAX_FLOAT_S16 = 32767.0f;
    private static final float MIN_FLOAT_S16 = -32768.0f;
    private static final float MAX_INPUT_LEVEL_LINEAR = (float) 36766.300710566735;
    private static final int KNEE_POINTS = 22;

    private static final float[] APPROXIMATION_X = {
            30057.296875f, 30148.986328125f, 30240.67578125f, 30424.052734375f,
            30607.4296875f, 30790.806640625f, 30974.18359375f, 31157.560546875f,
            31340.939453125f, 31524.31640625f, 31707.693359375f, 31891.0703125f,
            32074.447265625f, 32257.82421875f, 32441.201171875f, 32624.580078125f,
            32807.95703125f, 32991.33203125f, 33174.7109375f, 33358.08984375f,
            33541.46484375f, 33724.84375f, 33819.53515625f, 34009.5390625f,
            34200.05859375f, 34389.81640625f, 34674.48828125f, 35054.375f,
            35434.86328125f, 35814.81640625f, 36195.16796875f, 36575.03125f};

    private static final float[] APPROXIMATION_M = {
            -3.515235675877192989e-07f, -1.050251626111275982e-06f,
            -2.085213736791047268e-06f, -3.443004743530764244e-06f,
            -4.773849468620028347e-06f, -6.077375928725814447e-06f,
            -7.353257842623861507e-06f, -8.601219633419532329e-06f,
            -9.821013009059242904e-06f, -1.101243378798244521e-05f,
            -1.217532644659513608e-05f, -1.330956911260727793e-05f,
            -1.441507538402220234e-05f, -1.549179251014720649e-05f,
            -1.653970684856176376e-05f, -1.755882840370759368e-05f,
            -1.854918446042574942e-05f, -1.951086778717581183e-05f,
            -2.044398024736437947e-05f, -2.1348627342376858e-05f,
            -2.222496914328075945e-05f, -2.265374678245279938e-05f,
            -2.242570917587727308e-05f, -2.220122041762806475e-05f,
            -2.19802095671184361e-05f, -2.176260204578284174e-05f,
            -2.133731686626560986e-05f, -2.092481918225530535e-05f,
            -2.052459603874012828e-05f, -2.013615448959171772e-05f,
            -1.975903069251216948e-05f, -1.939277899509761482e-05f};

    private static final float[] APPROXIMATION_Q = {
            1.010565876960754395f, 1.031631827354431152f, 1.062929749488830566f,
            1.104239225387573242f, 1.144973039627075195f, 1.185109615325927734f,
            1.224629044532775879f, 1.263512492179870605f, 1.301741957664489746f,
            1.339300632476806641f, 1.376173257827758789f, 1.412345528602600098f,
            1.447803974151611328f, 1.482536554336547852f, 1.516532182693481445f,
            1.549780607223510742f, 1.582272171974182129f, 1.613999366760253906f,
            1.644955039024353027f, 1.675132393836975098f, 1.704526185989379883f,
            1.718986630439758301f, 1.711274504661560059f, 1.703639745712280273f,
            1.696081161499023438f, 1.688597679138183594f, 1.673851132392883301f,
            1.659391283988952637f, 1.645209431648254395f, 1.631297469139099121f,
            1.617647409439086914f, 1.604251742362976074f};

    private final int samplesPerChannel;
    private final boolean adaptiveDigital;
    private final boolean speechAnalysis;
    private final float headroomDb;
    private final float maxGainDb;
    private final float maxOutputNoiseLevelDbfs;
    private final float maxGainChangeDbPer10ms;

    private final RnnVad vad;
    private final PushSincResampler vadResampler;
    private final float[] vadFrame = new float[RnnVad.FRAME_SIZE_10MS];
    private int timeToVadReset = VAD_RESET_PERIOD_FRAMES;

    private final float initialSpeechLevelDbfs;
    private final SpeechLevelState preliminaryLevel = new SpeechLevelState();
    private final SpeechLevelState reliableLevel = new SpeechLevelState();
    private float speechLevelDbfs;
    private boolean speechLevelConfident;
    private int levelAdjacentSpeechFrames;

    private int noiseSampleRate = -1;
    private float minNoiseEnergy;
    private boolean noiseFirstPeriod;
    private boolean preliminaryNoiseEnergySet;
    private float preliminaryNoiseEnergy;
    private float noiseEnergy;
    private int noiseCounter;

    private final SaturationState preliminarySaturation = new SaturationState();
    private final SaturationState reliableSaturation = new SaturationState();
    private float saturationHeadroomDb;
    private int saturationAdjacentSpeechFrames;

    private final GainApplier adaptiveGainApplier;
    private int framesToGainIncreaseAllowed = ADJACENT_SPEECH_FRAMES_THRESHOLD;
    private float lastGainDb;

    private final GainApplier fixedGainApplier;

    private final int samplesInSubFrame;
    private float limiterFilterStateLevel;
    private final float[] envelope = new float[SUB_FRAMES_IN_FRAME];
    private final float[] scalingFactors = new float[SUB_FRAMES_IN_FRAME + 1];
    private final float[] perSampleScalingFactors;
    private float lastScalingFactor = 1.f;

    private final InputVolumeController inputVolumeController;
    private int recommendedInputVolume = -1;

    /**
     * Creates a level controller.
     *
     * @param sampleRate the full-band rate, 16, 32 or 48 kHz
     * @param numChannels the number of channels
     * @param fixedGainDb the fixed gain, in dB
     * @param adaptiveDigital whether the adaptive digital gain runs
     * @param headroomDb the headroom of the adaptive digital gain, in dB
     * @param maxGainDb the largest adaptive digital gain, in dB
     * @param initialGainDb the initial adaptive digital gain, in dB
     * @param maxGainChangeDbPerSecond the fastest adaptive digital gain change, in dB per second
     * @param maxOutputNoiseLevelDbfs the highest output noise level, in dBFS
     * @param inputVolumeController whether a microphone volume is recommended
     */
    public LevelController(int sampleRate, int numChannels, float fixedGainDb, boolean adaptiveDigital, float headroomDb,
                           float maxGainDb, float initialGainDb, float maxGainChangeDbPerSecond,
                           float maxOutputNoiseLevelDbfs, boolean inputVolumeController) {
        this.samplesPerChannel = sampleRate / 100;
        this.adaptiveDigital = adaptiveDigital;
        this.speechAnalysis = adaptiveDigital || inputVolumeController;
        this.headroomDb = headroomDb;
        this.maxGainDb = maxGainDb;
        this.maxOutputNoiseLevelDbfs = maxOutputNoiseLevelDbfs;
        this.maxGainChangeDbPer10ms = maxGainChangeDbPerSecond * 10 / 1000.0f;
        this.fixedGainApplier = new GainApplier(dbToRatio(fixedGainDb));
        this.samplesInSubFrame = samplesPerChannel / SUB_FRAMES_IN_FRAME;
        this.perSampleScalingFactors = new float[samplesPerChannel];
        if (speechAnalysis) {
            this.vad = new RnnVad();
            this.vadResampler = samplesPerChannel != RnnVad.FRAME_SIZE_10MS
                    ? new PushSincResampler(samplesPerChannel, RnnVad.FRAME_SIZE_10MS) : null;
            vad.resetNetwork();
        } else {
            this.vad = null;
            this.vadResampler = null;
        }
        this.initialSpeechLevelDbfs = clampLevel(-SATURATION_PROTECTOR_INITIAL_HEADROOM_DB - initialGainDb - headroomDb);
        resetSpeechLevel();
        this.adaptiveGainApplier = new GainApplier(dbToRatio(initialGainDb));
        this.lastGainDb = initialGainDb;
        resetSaturationProtector();
        initializeNoise(48000);
        this.inputVolumeController = inputVolumeController ? new InputVolumeController(numChannels) : null;
    }

    /**
     * Analyzes a full-band frame before the other processing stages for the input volume
     * controller ({@code GainController2::Analyze}).
     *
     * @param appliedInputVolume the microphone volume in effect
     * @param channels the full-band samples of every channel
     * @param numChannels the number of channels
     */
    public void analyze(int appliedInputVolume, float[][] channels, int numChannels) {
        recommendedInputVolume = -1;
        if (inputVolumeController != null) {
            inputVolumeController.analyzeInputAudio(appliedInputVolume, channels, numChannels, samplesPerChannel);
        }
    }

    /**
     * Processes a full-band frame in place ({@code GainController2::Process}).
     *
     * @param channels the full-band samples of every channel, in the 16-bit range
     * @param numChannels the number of channels
     * @param inputVolumeChanged whether the microphone volume changed since the previous frame
     */
    public void process(float[][] channels, int numChannels, boolean inputVolumeChanged) {
        recommendedInputVolume = -1;
        if (inputVolumeChanged && speechAnalysis) {
            resetSpeechLevel();
            if (adaptiveDigital) {
                resetSaturationProtector();
            }
        }
        var speechProbability = 0.f;
        if (speechAnalysis) {
            speechProbability = analyzeVad(channels[0]);
        }
        var first = channels[0];
        var peak = 0.0f;
        var rms = 0.0f;
        for (var i = 0; i < samplesPerChannel; i++) {
            var x = first[i];
            peak = FloatMath.max(Math.abs(x), peak);
            rms += x * x;
        }
        var peakDbfs = floatS16ToDbfs(peak);
        var rmsDbfs = floatS16ToDbfs((float) Math.sqrt(rms / samplesPerChannel));
        var noiseRmsDbfs = 0.f;
        if (adaptiveDigital) {
            noiseRmsDbfs = analyzeNoise(channels, numChannels);
        }
        if (speechAnalysis) {
            updateSpeechLevel(rmsDbfs, speechProbability);
        }
        if (inputVolumeController != null) {
            recommendedInputVolume = inputVolumeController.recommendInputVolume(speechProbability,
                    speechLevelConfident, speechLevelDbfs);
        }
        if (adaptiveDigital) {
            analyzeSaturation(speechProbability, peakDbfs, speechLevelDbfs);
            var headroom = saturationHeadroomDb;
            var limiterEnvelopeDbfs = floatS16ToDbfs(limiterFilterStateLevel);
            processAdaptiveDigital(speechProbability, speechLevelDbfs, speechLevelConfident, noiseRmsDbfs, headroom,
                    limiterEnvelopeDbfs, channels, numChannels);
        }
        fixedGainApplier.applyGain(channels, numChannels, samplesPerChannel);
        limit(channels, numChannels);
    }

    /**
     * Reports whether the capture output is used ({@code SetCaptureOutputUsed}).
     *
     * @param used whether the output is used
     */
    public void setCaptureOutputUsed(boolean used) {
        if (inputVolumeController != null) {
            inputVolumeController.handleCaptureOutputUsedChange(used);
        }
    }

    /**
     * {@return the microphone volume recommended by the last frame, or {@code -1} for none}
     */
    public int recommendedInputVolume() {
        return recommendedInputVolume;
    }

    private float analyzeVad(float[] frame) {
        timeToVadReset--;
        if (timeToVadReset <= 0) {
            vad.resetNetwork();
            timeToVadReset = VAD_RESET_PERIOD_FRAMES;
        }
        if (vadResampler != null) {
            vadResampler.resample(frame, 0, vadFrame, 0);
        } else {
            System.arraycopy(frame, 0, vadFrame, 0, RnnVad.FRAME_SIZE_10MS);
        }
        return vad.analyze(vadFrame, 0);
    }

    static float dbToRatio(float v) {
        return FloatMath.powf(10.0f, v / 20.0f);
    }

    static float floatS16ToDbfs(float v) {
        if (v <= 1.0f) {
            return MIN_DBFS;
        }
        return 20.0f * FloatMath.log10f(v) + MIN_DBFS;
    }

    private static float clampLevel(float levelDbfs) {
        return levelDbfs <= -90.0f ? -90.0f : (levelDbfs >= 30.0f ? 30.0f : levelDbfs);
    }

    private void resetSpeechLevel() {
        preliminaryLevel.reset(initialSpeechLevelDbfs);
        reliableLevel.reset(initialSpeechLevelDbfs);
        speechLevelDbfs = initialSpeechLevelDbfs;
        levelAdjacentSpeechFrames = 0;
    }

    private void updateSpeechLevel(float rmsDbfs, float speechProbability) {
        if (speechProbability < VAD_CONFIDENCE_THRESHOLD) {
            if (levelAdjacentSpeechFrames >= ADJACENT_SPEECH_FRAMES_THRESHOLD) {
                reliableLevel.copyFrom(preliminaryLevel);
            } else if (levelAdjacentSpeechFrames > 0) {
                preliminaryLevel.copyFrom(reliableLevel);
            }
            levelAdjacentSpeechFrames = 0;
        } else {
            levelAdjacentSpeechFrames++;
            var bufferIsFull = preliminaryLevel.timeToConfidenceMs == 0;
            if (!bufferIsFull) {
                preliminaryLevel.timeToConfidenceMs -= 10;
            }
            var leakFactor = bufferIsFull ? LEVEL_ESTIMATOR_LEAK_FACTOR : 1.0f;
            preliminaryLevel.numerator = preliminaryLevel.numerator * leakFactor + rmsDbfs * speechProbability;
            preliminaryLevel.denominator = preliminaryLevel.denominator * leakFactor + speechProbability;
            var levelDbfs = preliminaryLevel.numerator / preliminaryLevel.denominator;
            if (levelAdjacentSpeechFrames >= ADJACENT_SPEECH_FRAMES_THRESHOLD) {
                speechLevelDbfs = clampLevel(levelDbfs);
            }
        }
        speechLevelConfident = reliableLevel.timeToConfidenceMs == 0
                || (levelAdjacentSpeechFrames >= ADJACENT_SPEECH_FRAMES_THRESHOLD
                && preliminaryLevel.timeToConfidenceMs == 0);
    }

    private void initializeNoise(int sampleRate) {
        noiseSampleRate = sampleRate;
        noiseFirstPeriod = true;
        preliminaryNoiseEnergySet = false;
        minNoiseEnergy = sampleRate * 2.0f * 2.0f / 100;
        preliminaryNoiseEnergy = minNoiseEnergy;
        noiseEnergy = minNoiseEnergy;
        noiseCounter = 500;
    }

    private static float energyToDbfs(float signalEnergy, int numSamples) {
        var rmsSquare = signalEnergy / numSamples;
        if (rmsSquare <= 1.0f) {
            return MIN_DBFS;
        }
        return 10.0f * FloatMath.log10f(rmsSquare) + MIN_DBFS;
    }

    private float analyzeNoise(float[][] channels, int numChannels) {
        var sampleRate = samplesPerChannel * 100;
        if (sampleRate != noiseSampleRate) {
            initializeNoise(sampleRate);
        }
        var frameEnergy = 0.0f;
        for (var k = 0; k < numChannels; ++k) {
            var ch = channels[k];
            var channelEnergy = 0.0f;
            for (var i = 0; i < samplesPerChannel; i++) {
                channelEnergy = channelEnergy + ch[i] * ch[i];
            }
            frameEnergy = FloatMath.max(channelEnergy, frameEnergy);
        }
        if (frameEnergy <= minNoiseEnergy) {
            return energyToDbfs(noiseEnergy, samplesPerChannel);
        }
        if (preliminaryNoiseEnergySet) {
            preliminaryNoiseEnergy = FloatMath.min(preliminaryNoiseEnergy, frameEnergy);
        } else {
            preliminaryNoiseEnergy = frameEnergy;
            preliminaryNoiseEnergySet = true;
        }
        if (noiseCounter == 0) {
            noiseFirstPeriod = false;
            var attack = 0.5f;
            noiseEnergy = noiseEnergy < preliminaryNoiseEnergy
                    ? attack * preliminaryNoiseEnergy + (1.0f - attack) * noiseEnergy
                    : preliminaryNoiseEnergy;
            noiseCounter = 500;
            preliminaryNoiseEnergySet = false;
        } else if (noiseFirstPeriod) {
            noiseEnergy = preliminaryNoiseEnergy;
            noiseCounter--;
        } else {
            noiseEnergy = FloatMath.min(noiseEnergy, preliminaryNoiseEnergy);
            noiseCounter--;
        }
        return energyToDbfs(noiseEnergy, samplesPerChannel);
    }

    private void resetSaturationProtector() {
        saturationAdjacentSpeechFrames = 0;
        saturationHeadroomDb = SATURATION_PROTECTOR_INITIAL_HEADROOM_DB;
        preliminarySaturation.reset(SATURATION_PROTECTOR_INITIAL_HEADROOM_DB);
        reliableSaturation.reset(SATURATION_PROTECTOR_INITIAL_HEADROOM_DB);
    }

    private void analyzeSaturation(float speechProbability, float peakDbfs, float speechLevel) {
        if (speechProbability < VAD_CONFIDENCE_THRESHOLD) {
            if (saturationAdjacentSpeechFrames >= ADJACENT_SPEECH_FRAMES_THRESHOLD) {
                reliableSaturation.copyFrom(preliminarySaturation);
            } else if (saturationAdjacentSpeechFrames > 0) {
                preliminarySaturation.copyFrom(reliableSaturation);
            }
            saturationAdjacentSpeechFrames = 0;
        } else {
            saturationAdjacentSpeechFrames++;
            preliminarySaturation.update(peakDbfs, speechLevel);
            if (saturationAdjacentSpeechFrames >= ADJACENT_SPEECH_FRAMES_THRESHOLD) {
                saturationHeadroomDb = preliminarySaturation.headroomDb;
            }
        }
    }

    private void processAdaptiveDigital(float speechProbability, float speechLevel, boolean speechLevelReliable,
                                        float noiseRmsDbfs, float headroom, float limiterEnvelopeDbfs,
                                        float[][] channels, int numChannels) {
        var inputLevelDbfs = speechLevel + headroom;
        float gainDb;
        if (inputLevelDbfs < -(headroomDb + maxGainDb)) {
            gainDb = maxGainDb;
        } else if (inputLevelDbfs < -headroomDb) {
            gainDb = -headroomDb - inputLevelDbfs;
        } else {
            gainDb = 0.0f;
        }
        var maxAllowedGainDb = maxOutputNoiseLevelDbfs - noiseRmsDbfs;
        gainDb = FloatMath.min(gainDb, FloatMath.max(maxAllowedGainDb, 0.0f));
        if (!(speechLevelReliable || limiterEnvelopeDbfs <= LIMITER_THRESHOLD_FOR_AGC_GAIN_DBFS)) {
            var limiterLevelDbfsBeforeGain = limiterEnvelopeDbfs - lastGainDb;
            var newTargetGainDb = FloatMath.max(LIMITER_THRESHOLD_FOR_AGC_GAIN_DBFS - limiterLevelDbfsBeforeGain, 0.0f);
            gainDb = FloatMath.min(newTargetGainDb, gainDb);
        }
        var targetGainDb = gainDb;
        var firstConfidentSpeechFrame = false;
        if (speechProbability < VAD_CONFIDENCE_THRESHOLD) {
            framesToGainIncreaseAllowed = ADJACENT_SPEECH_FRAMES_THRESHOLD;
        } else if (framesToGainIncreaseAllowed > 0) {
            framesToGainIncreaseAllowed--;
            firstConfidentSpeechFrame = framesToGainIncreaseAllowed == 0;
        }
        var gainIncreaseAllowed = framesToGainIncreaseAllowed == 0;
        var maxGainIncreaseDb = maxGainChangeDbPer10ms;
        if (firstConfidentSpeechFrame) {
            maxGainIncreaseDb *= ADJACENT_SPEECH_FRAMES_THRESHOLD;
        }
        var difference = targetGainDb - lastGainDb;
        if (!gainIncreaseAllowed) {
            difference = FloatMath.min(difference, 0.0f);
        }
        var low = -maxGainChangeDbPer10ms;
        var change = difference <= low ? low : (difference >= maxGainIncreaseDb ? maxGainIncreaseDb : difference);
        if (change != 0.f) {
            adaptiveGainApplier.setGainFactor(dbToRatio(lastGainDb + change));
        }
        adaptiveGainApplier.applyGain(channels, numChannels, samplesPerChannel);
        lastGainDb = lastGainDb + change;
    }

    private void limit(float[][] channels, int numChannels) {
        var env = envelope;
        java.util.Arrays.fill(env, 0.f);
        for (var c = 0; c < numChannels; ++c) {
            var channel = channels[c];
            for (var subFrame = 0; subFrame < SUB_FRAMES_IN_FRAME; ++subFrame) {
                for (var s = 0; s < samplesInSubFrame; ++s) {
                    env[subFrame] = FloatMath.max(env[subFrame], Math.abs(channel[subFrame * samplesInSubFrame + s]));
                }
            }
        }
        for (var subFrame = 0; subFrame < SUB_FRAMES_IN_FRAME - 1; ++subFrame) {
            if (env[subFrame] < env[subFrame + 1]) {
                env[subFrame] = env[subFrame + 1];
            }
        }
        for (var subFrame = 0; subFrame < SUB_FRAMES_IN_FRAME; ++subFrame) {
            var value = env[subFrame];
            if (value > limiterFilterStateLevel) {
                env[subFrame] = value * (1 - 0.0f) + limiterFilterStateLevel * 0.0f;
            } else {
                env[subFrame] = value * (1 - 0.9971259f) + limiterFilterStateLevel * 0.9971259f;
            }
            limiterFilterStateLevel = env[subFrame];
        }
        scalingFactors[0] = lastScalingFactor;
        for (var i = 0; i < SUB_FRAMES_IN_FRAME; i++) {
            scalingFactors[i + 1] = lookUpGainToApply(env[i]);
        }
        var subframeSize = samplesPerChannel / SUB_FRAMES_IN_FRAME;
        var isAttack = scalingFactors[0] > scalingFactors[1];
        if (isAttack) {
            var last = scalingFactors[0];
            var current = scalingFactors[1];
            for (var i = 0; i < subframeSize; ++i) {
                perSampleScalingFactors[i] = FloatMath.powf(1.f - i / subframeSize, 8.0f) * (last - current) + current;
            }
        }
        for (var i = isAttack ? 1 : 0; i < SUB_FRAMES_IN_FRAME; ++i) {
            var subframeStart = i * subframeSize;
            var scalingStart = scalingFactors[i];
            var scalingEnd = scalingFactors[i + 1];
            var scalingDiff = (scalingEnd - scalingStart) / subframeSize;
            for (var j = 0; j < subframeSize; ++j) {
                perSampleScalingFactors[subframeStart + j] = scalingStart + scalingDiff * j;
            }
        }
        for (var c = 0; c < numChannels; ++c) {
            var channel = channels[c];
            for (var j = 0; j < samplesPerChannel; ++j) {
                var v = channel[j] * perSampleScalingFactors[j];
                channel[j] = v <= MIN_FLOAT_S16 ? MIN_FLOAT_S16 : (v >= MAX_FLOAT_S16 ? MAX_FLOAT_S16 : v);
            }
        }
        lastScalingFactor = scalingFactors[SUB_FRAMES_IN_FRAME];
    }

    private static float lookUpGainToApply(float inputLevel) {
        if (inputLevel <= APPROXIMATION_X[0]) {
            return 1.0f;
        }
        if (inputLevel >= MAX_INPUT_LEVEL_LINEAR) {
            return 32768.f / inputLevel;
        }
        var lo = 0;
        var hi = APPROXIMATION_X.length;
        while (lo < hi) {
            var mid = (lo + hi) >>> 1;
            if (APPROXIMATION_X[mid] < inputLevel) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        var index = lo - 1;
        return APPROXIMATION_M[index] * inputLevel + APPROXIMATION_Q[index];
    }

    /**
     * The state of the speech level estimator.
     */
    private static final class SpeechLevelState {
        int timeToConfidenceMs;
        float numerator;
        float denominator;

        void reset(float initialLevelDbfs) {
            timeToConfidenceMs = LEVEL_ESTIMATOR_TIME_TO_CONFIDENCE_MS;
            numerator = initialLevelDbfs;
            denominator = 1.0f;
        }

        void copyFrom(SpeechLevelState other) {
            timeToConfidenceMs = other.timeToConfidenceMs;
            numerator = other.numerator;
            denominator = other.denominator;
        }
    }

    /**
     * The state of the saturation protector.
     */
    private static final class SaturationState {
        private static final float ATTACK = 0.9988493699365052f;
        private static final float DECAY = 0.9997697679981565f;
        float headroomDb;
        final float[] peakDelayBuffer = new float[4];
        int next;
        int size;
        float maxPeaksDbfs;
        int timeSincePushMs;

        void reset(float initialHeadroomDb) {
            headroomDb = initialHeadroomDb;
            next = 0;
            size = 0;
            maxPeaksDbfs = MIN_LEVEL_DBFS;
            timeSincePushMs = 0;
        }

        void copyFrom(SaturationState other) {
            headroomDb = other.headroomDb;
            System.arraycopy(other.peakDelayBuffer, 0, peakDelayBuffer, 0, 4);
            next = other.next;
            size = other.size;
            maxPeaksDbfs = other.maxPeaksDbfs;
            timeSincePushMs = other.timeSincePushMs;
        }

        void update(float peakDbfs, float speechLevelDbfs) {
            maxPeaksDbfs = FloatMath.max(maxPeaksDbfs, peakDbfs);
            timeSincePushMs += 10;
            if (timeSincePushMs > 400) {
                peakDelayBuffer[next++] = maxPeaksDbfs;
                if (next == 4) {
                    next = 0;
                }
                if (size < 4) {
                    size++;
                }
                maxPeaksDbfs = MIN_LEVEL_DBFS;
                timeSincePushMs = 0;
            }
            var delayedPeakDbfs = size == 0 ? maxPeaksDbfs : peakDelayBuffer[size == 4 ? next : 0];
            var differenceDb = delayedPeakDbfs - speechLevelDbfs;
            if (differenceDb > headroomDb) {
                headroomDb = headroomDb * ATTACK + differenceDb * (1.0f - ATTACK);
            } else {
                headroomDb = headroomDb * DECAY + differenceDb * (1.0f - DECAY);
            }
            headroomDb = headroomDb <= 12.0f ? 12.0f : (headroomDb >= 25.0f ? 25.0f : headroomDb);
        }
    }

    /**
     * Applies a gain that ramps linearly over a frame from the previous gain ({@code GainApplier}
     * without hard clipping).
     */
    private static final class GainApplier {
        private float lastGainFactor;
        private float currentGainFactor;

        GainApplier(float initialGainFactor) {
            this.lastGainFactor = initialGainFactor;
            this.currentGainFactor = initialGainFactor;
        }

        void setGainFactor(float gainFactor) {
            currentGainFactor = gainFactor;
        }

        void applyGain(float[][] channels, int numChannels, int samplesPerChannel) {
            var inverse = 1.f / samplesPerChannel;
            var last = lastGainFactor;
            var end = currentGainFactor;
            if (!(last == end && 1.f - 1.f / MAX_FLOAT_S16 <= end && end <= 1.f + 1.f / MAX_FLOAT_S16)) {
                if (last == end) {
                    for (var k = 0; k < numChannels; ++k) {
                        var ch = channels[k];
                        for (var i = 0; i < samplesPerChannel; i++) {
                            ch[i] *= end;
                        }
                    }
                } else {
                    var increment = (end - last) * inverse;
                    for (var k = 0; k < numChannels; ++k) {
                        var ch = channels[k];
                        var gain = last;
                        for (var i = 0; i < samplesPerChannel; i++) {
                            ch[i] *= gain;
                            gain += increment;
                        }
                    }
                }
            }
            lastGainFactor = currentGainFactor;
        }
    }
}
