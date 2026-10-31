/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/vad/voice_activity_detector.cc, standalone_vad.cc,
   vad_audio_proc.cc, pitch_based_vad.cc, pitch_internal.cc, pole_zero_filter.cc, gmm.cc, vad_circular_buffer.cc,
   noise_gmm_tables.h, voice_gmm_tables.h, vad_audio_proc_internal.h). Copyright (c) 2014, The WebRTC project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc;

import com.github.auties00.smalt.audio.voice.internal.DoubleMath;
import com.github.auties00.smalt.audio.voice.internal.OouraRealFft;

/**
 * Estimates the voice probability and the level of 16 kHz audio for the analog gain controller,
 * combining WebRTC's standalone voice activity detector with a pitch-based one.
 *
 * <p>Frames of 10 ms go in one at a time; every third frame completes a 30 ms chunk, for which
 * {@link #frames()} reports three 10 ms results with their RMS level ({@link #rms(int)}) and
 * voice probability ({@link #probability(int)}). Silent chunks get a low probability without
 * running the detectors. Instances are not thread-safe.
 *
 * @implNote This implementation ports {@code VoiceActivityDetector} at 16 kHz, where it does not
 * resample.
 */
final class VoiceDetector {
    private static final int LENGTH_10MS = 160;
    private static final int MAX_STANDALONE_FRAMES = 3;
    private static final int NUM_PAST_SIGNAL_SAMPLES = 80;
    private static final int NUM_SUBFRAMES = 3;
    private static final int NUM_SUBFRAME_SAMPLES = 160;
    private static final int NUM_SAMPLES_TO_PROCESS = NUM_SUBFRAMES * NUM_SUBFRAME_SAMPLES;
    private static final int BUFFER_LENGTH = NUM_PAST_SIGNAL_SAMPLES + NUM_SAMPLES_TO_PROCESS;
    private static final int LPC_ORDER = 16;
    private static final int DFT_SIZE = 512;
    private static final float FREQUENCY_RESOLUTION = 16000 / (float) DFT_SIZE;
    private static final int SILENCE_RMS = 5;
    private static final double NEUTRAL_PROBABILITY = 0.5;
    private static final double LOW_PROBABILITY = 0.01;
    private static final int POSTERIOR_HISTORY_SIZE = 500;
    private static final double INITIAL_PRIOR_PROBABILITY = 0.3;
    private static final int TRANSIENT_WIDTH_THRESHOLD = 7;
    private static final double LOW_PROBABILITY_THRESHOLD = 0.2;
    private static final int GMM_MIXTURES = 12;
    private static final int GMM_DIM = 3;

    private static final double[] CORR_WEIGHT = {1.000000, 0.985000, 0.970225, 0.955672, 0.941337, 0.927217,
            0.913308, 0.899609, 0.886115, 0.872823, 0.859730, 0.846834, 0.834132, 0.821620, 0.809296, 0.797156,
            0.785199};
    private static final double[] LPC_ANALYSIS_WINDOW = lpcAnalysisWindow();
    private static final float[] HPF_NUMERATOR = {0.974827f, -1.949650f, 0.974827f};
    private static final float[] HPF_DENOMINATOR = {1.0f, -1.971999f, 0.972457f};

    private static final double[] NOISE_COVAR_INVERSE = {
            7.36219567592941e+00, 4.83060785179861e-03, 1.23335151497610e-02,
            4.83060785179861e-03, 1.65289507047817e-04, -2.41490588169997e-04,
            1.23335151497610e-02, -2.41490588169997e-04, 6.59472060689382e-03,
            8.70265239309140e+00, -5.30636201431086e-04, 5.44014966585347e-03,
            -5.30636201431086e-04, 3.11095453521008e-04, -1.86287206836035e-04,
            5.44014966585347e-03, -1.86287206836035e-04, 6.29493388790744e-04,
            4.53467851955055e+00, -3.92977536695197e-03, -2.46521420693317e-03,
            -3.92977536695197e-03, 4.94650752632750e-05, -1.08587438501826e-05,
            -2.46521420693317e-03, -1.08587438501826e-05, 9.28793975422261e-05,
            9.26817997114275e-01, -4.03976069276753e-04, -3.56441427392165e-03,
            -4.03976069276753e-04, 2.51976251631430e-06, 1.46914206734572e-07,
            -3.56441427392165e-03, 1.46914206734572e-07, 8.19914567685373e-05,
            7.61715986787441e+00, -1.54889041216888e-04, 2.41756280071656e-02,
            -1.54889041216888e-04, 3.50282550461672e-07, -6.27251196972490e-06,
            2.41756280071656e-02, -6.27251196972490e-06, 1.45061847649872e-02,
            8.31193642663158e+00, -3.84070508164323e-04, -3.09750630821876e-02,
            -3.84070508164323e-04, 3.80433432277336e-07, -1.14321142836636e-06,
            -3.09750630821876e-02, -1.14321142836636e-06, 8.35091486289997e-04,
            9.67283151270894e-01, 5.82465812445039e-05, -3.18350798617053e-03,
            5.82465812445039e-05, 2.23762672000318e-07, -7.74196587408623e-07,
            -3.18350798617053e-03, -7.74196587408623e-07, 3.85120938338325e-04,
            8.28066236985388e+00, 5.87634508319763e-05, 6.99303090891743e-03,
            5.87634508319763e-05, 2.93746018618058e-07, 3.40843332882272e-07,
            6.99303090891743e-03, 3.40843332882272e-07, 1.99379171190344e-04,
            6.07488998675646e+00, -1.11494526618473e-02, 5.10013111123381e-03,
            -1.11494526618473e-02, 6.99238879921751e-04, 5.36718550370870e-05,
            5.10013111123381e-03, 5.36718550370870e-05, 5.26909853276753e-04,
            6.90492021419175e+00, 4.20639355257863e-04, -2.38612752336481e-03,
            4.20639355257863e-04, 3.31246767338153e-06, -2.42052288150859e-08,
            -2.38612752336481e-03, -2.42052288150859e-08, 4.46608368363412e-04,
            1.31069150869715e+01, -1.73718583865670e-04, -1.97591814508578e-02,
            -1.73718583865670e-04, 2.80451716300124e-07, 9.96570755379865e-07,
            -1.97591814508578e-02, 9.96570755379865e-07, 2.41361900868847e-03,
            4.69566344239814e+00, -2.61077567563690e-04, 5.26359000761433e-03,
            -2.61077567563690e-04, 1.82420859823767e-06, -7.83645887541601e-07,
            5.26359000761433e-03, -7.83645887541601e-07, 1.33586288288802e-02
    };
    private static final double[] NOISE_MEAN = {
            -2.01386094766163e+00, 1.69702162045397e+02, 7.41715804872181e+01,
            -1.94684591777290e+00, 1.42398396732668e+02, 1.64186321157831e+02,
            -2.29319297562437e+00, 3.86415425589868e+02, 2.13452215267125e+02,
            -3.25487177070268e+00, 1.08668712553616e+03, 2.33119949467419e+02,
            -2.13159632447467e+00, 4.83821702557717e+03, 6.86786166673740e+01,
            -2.26171410780526e+00, 4.79420193982422e+03, 1.53222513286450e+02,
            -3.32166740703185e+00, 4.35161135834358e+03, 1.33206448431316e+02,
            -2.19290322814343e+00, 3.98325506609408e+03, 2.13249167359934e+02,
            -2.02898459255404e+00, 7.37039893155007e+03, 1.12518527491926e+02,
            -2.26150236399500e+00, 1.54896745196145e+03, 1.49717357868579e+02,
            -2.00417668301790e+00, 3.82434760310304e+03, 1.07438913004312e+02,
            -2.30193040814533e+00, 1.43953696546439e+03, 7.04085275122649e+01
    };
    private static final double[] NOISE_WEIGHTS = {
            -1.09422832086193e+01, -1.10847897513425e+01, -1.36767587732187e+01,
            -1.79789356118641e+01, -1.42830169160894e+01, -1.56500228061379e+01,
            -1.83124990950113e+01, -1.69979436177477e+01, -1.12329424387828e+01,
            -1.41311785780639e+01, -1.47171861448585e+01, -1.35963362781839e+01
    };
    private static final double[] VOICE_COVAR_INVERSE = {
            1.83673825579513e+00, -8.09791637570095e-04, 4.60106414365986e-03,
            -8.09791637570095e-04, 8.89351738394608e-04, -9.80188953277734e-04,
            4.60106414365986e-03, -9.80188953277734e-04, 1.38706060206582e-03,
            6.76228912850703e+01, -1.98893120119660e-02, -3.53548357253551e-03,
            -1.98893120119660e-02, 3.96216858500530e-05, -4.08492938394097e-05,
            -3.53548357253551e-03, -4.08492938394097e-05, 9.31864352856416e-04,
            9.98612435944558e+00, -5.27880954316893e-03, -6.30342541619017e-03,
            -5.27880954316893e-03, 4.54359480225226e-05, 6.30804591626044e-05,
            -6.30342541619017e-03, 6.30804591626044e-05, 5.36466441382942e-04,
            3.39917474216349e+01, -1.56213579433191e-03, -4.01459014990225e-02,
            -1.56213579433191e-03, 6.40415424897724e-05, 6.20076342427833e-05,
            -4.01459014990225e-02, 6.20076342427833e-05, 3.51199070103063e-03,
            1.34545062271428e+01, -7.94513610147144e-03, -5.34401019341728e-02,
            -7.94513610147144e-03, 1.16511820098649e-04, 4.66063702069293e-05,
            -5.34401019341728e-02, 4.66063702069293e-05, 2.72354323774163e-03,
            1.08557844314806e+02, -1.54885805673668e-02, -1.88029692674851e-02,
            -1.54885805673668e-02, 1.16404042786406e-04, 6.45579292702802e-06,
            -1.88029692674851e-02, 6.45579292702802e-06, 4.32330478391416e-04,
            8.22940066541450e+01, -1.15903110231303e-02, -4.92166764865343e-02,
            -1.15903110231303e-02, 7.42510742165261e-05, 3.73007314191290e-06,
            -4.92166764865343e-02, 3.73007314191290e-06, 3.64005221593244e-03,
            2.31133605685660e+00, -7.83261568950254e-04, 7.45744012346313e-04,
            -7.83261568950254e-04, 1.29460648214142e-05, -2.22774455093730e-06,
            7.45744012346313e-04, -2.22774455093730e-06, 1.05117294093010e-04,
            3.78767849189611e+02, 1.57759761011568e-03, -2.08551217988774e-02,
            1.57759761011568e-03, 4.76066236886865e-05, -2.33977412299324e-05,
            -2.08551217988774e-02, -2.33977412299324e-05, 5.24261005371196e-04,
            6.98580096506135e-01, -5.13850255217378e-04, -4.01124551717056e-04,
            -5.13850255217378e-04, 1.40501021984840e-06, -2.09496928716569e-06,
            -4.01124551717056e-04, -2.09496928716569e-06, 2.82879357740037e-04,
            2.62770945162399e+00, -2.31825753241430e-03, -5.30447217466318e-03,
            -2.31825753241430e-03, 4.59108572227649e-05, 7.67631886355405e-05,
            -5.30447217466318e-03, 7.67631886355405e-05, 2.28521601674098e-03,
            1.89940391362152e+02, -4.23280856852379e-03, -2.70608873541399e-02,
            -4.23280856852379e-03, 6.77547582742563e-05, 2.69154203800467e-05,
            -2.70608873541399e-02, 2.69154203800467e-05, 3.88574543373470e-03
    };
    private static final double[] VOICE_MEAN = {
            -2.15020241646536e+00, 4.97079062999877e+02, 4.77078119504505e+02,
            -8.92097680029190e-01, 5.92064964199921e+02, 1.81045145941059e+02,
            -1.29435784144398e+00, 4.98450293410611e+02, 1.71991263804064e+02,
            -1.03925228397884e+00, 4.99511274321571e+02, 1.05838336539105e+02,
            -1.29229047206129e+00, 4.15026762566707e+02, 1.12861119017125e+02,
            -7.88748114599810e-01, 4.48739336688113e+02, 1.89784216956337e+02,
            -8.77777402332642e-01, 4.86620285054533e+02, 1.13477708016491e+02,
            -2.06465957063057e+00, 6.33385049870607e+02, 2.32758546796149e+02,
            -6.98893789231685e-01, 5.93622051503385e+02, 1.92536982473203e+02,
            -2.55901217508894e+00, 1.55914919756205e+03, 1.39769980835570e+02,
            -1.92070024165837e+00, 4.87983940444185e+02, 1.02745468128289e+02,
            -7.29187507662854e-01, 5.22717685022855e+02, 1.16377942283991e+02
    };
    private static final double[] VOICE_WEIGHTS = {
            -1.39789694361035e+01, -1.19527720202104e+01, -1.32396317929055e+01,
            -1.09436815209238e+01, -1.13440027478149e+01, -1.12200721834504e+01,
            -1.02537324043693e+01, -1.60789861938302e+01, -1.03394494048344e+01,
            -1.83207938586818e+01, -1.31186044948288e+01, -9.52479998673554e+00
    };

    private final WebRtcVad standalone = new WebRtcVad();
    private final short[] standaloneBuffer = new short[MAX_STANDALONE_FRAMES * LENGTH_10MS];
    private int standaloneIndex;

    private final float[] audioBuffer = new float[BUFFER_LENGTH];
    private int numBufferSamples = NUM_PAST_SIGNAL_SAMPLES;
    private double logOldGain = -2;
    private double oldLag = 50;
    private final short[] pastInput = new short[2];
    private final float[] pastOutput = new float[2];
    private final OouraRealFft fft = new OouraRealFft(DFT_SIZE);
    private final IsacPitch pitch = new IsacPitch();

    private double priorProbability = INITIAL_PRIOR_PROBABILITY;
    private final double[] posteriors = new double[POSTERIOR_HISTORY_SIZE];
    private boolean posteriorsFull;
    private int posteriorIndex;
    private double posteriorSum;

    private int frames;
    private final double[] rms = new double[NUM_SUBFRAMES];
    private final double[] probabilities = new double[NUM_SUBFRAMES];
    private final double[] logPitchGain = new double[NUM_SUBFRAMES];
    private final double[] pitchLagHz = new double[NUM_SUBFRAMES];
    private final double[] spectralPeak = new double[NUM_SUBFRAMES];
    private float lastVoiceProbability = 1.0f;

    private final double[] lags = new double[4];
    private final double[] gains = new double[4];
    private final double[] lpc = new double[NUM_SUBFRAMES * (LPC_ORDER + 1)];
    private final double[] corr = new double[LPC_ORDER + 1];
    private final double[] reflection = new double[LPC_ORDER];
    private final double[] coefficients = new double[LPC_ORDER + 1];
    private final double[] windowed = new double[NUM_SUBFRAME_SAMPLES + NUM_PAST_SIGNAL_SAMPLES];
    private final float[] dft = new float[DFT_SIZE];
    private final double[] gmmFeatures = new double[GMM_DIM];
    private final double[] centered = new double[GMM_DIM];

    private static double[] lpcAnalysisWindow() {
        var half = new double[]{
                0.00000000, 0.01314436, 0.02628645, 0.03942400, 0.05255473, 0.06567639,
                0.07878670, 0.09188339, 0.10496421, 0.11802689, 0.13106918, 0.14408883,
                0.15708358, 0.17005118, 0.18298941, 0.19589602, 0.20876878, 0.22160547,
                0.23440387, 0.24716177, 0.25987696, 0.27254725, 0.28517045, 0.29774438,
                0.31026687, 0.32273574, 0.33514885, 0.34750406, 0.35979922, 0.37203222,
                0.38420093, 0.39630327, 0.40833713, 0.42030043, 0.43219112, 0.44400713,
                0.45574642, 0.46740697, 0.47898676, 0.49048379, 0.50189608, 0.51322164,
                0.52445853, 0.53560481, 0.54665854, 0.55761782, 0.56848075, 0.57924546,
                0.58991008, 0.60047278, 0.61093173, 0.62128512, 0.63153117, 0.64166810,
                0.65169416, 0.66160761, 0.67140676, 0.68108990, 0.69065536, 0.70010148,
                0.70942664, 0.71862923, 0.72770765, 0.73666033, 0.74548573, 0.75418233,
                0.76274862, 0.77118312, 0.77948437, 0.78765094, 0.79568142, 0.80357442,
                0.81132858, 0.81894256, 0.82641504, 0.83374472, 0.84093036, 0.84797069,
                0.85486451, 0.86161063, 0.86820787, 0.87465511, 0.88095122, 0.88709512,
                0.89308574, 0.89892206, 0.90460306, 0.91012776, 0.91549520, 0.92070447,
                0.92575465, 0.93064488, 0.93537432, 0.93994213, 0.94434755, 0.94858979,
                0.95266814, 0.95658189, 0.96033035, 0.96391289, 0.96732888, 0.97057773,
                0.97365889, 0.97657181, 0.97931600, 0.98189099, 0.98429632, 0.98653158,
                0.98859639, 0.99049038, 0.99221324, 0.99376466, 0.99514438, 0.99635215,
                0.99738778, 0.99825107, 0.99894188, 0.99946010, 0.99980562, 0.99997840};
        var window = new double[2 * half.length];
        for (var i = 0; i < half.length; i++) {
            window[i] = half[i];
            window[window.length - 1 - i] = half[i];
        }
        return window;
    }

    /**
     * Processes a 10 ms frame ({@code VoiceActivityDetector::ProcessChunk}).
     *
     * @param audio the 160 samples
     */
    void process(short[] audio) {
        if (standaloneIndex + LENGTH_10MS > LENGTH_10MS * MAX_STANDALONE_FRAMES) {
            standaloneIndex = 0;
        }
        System.arraycopy(audio, 0, standaloneBuffer, standaloneIndex, LENGTH_10MS);
        standaloneIndex += LENGTH_10MS;
        var silence = extractFeatures(audio);
        if (frames > 0) {
            if (silence) {
                java.util.Arrays.fill(probabilities, 0, frames, LOW_PROBABILITY);
            } else {
                java.util.Arrays.fill(probabilities, 0, frames, NEUTRAL_PROBABILITY);
                standaloneActivity();
                voicingProbability();
            }
            lastVoiceProbability = (float) probabilities[frames - 1];
        }
    }

    /**
     * {@return the number of 10 ms results of the last frame, 0 or 3}
     */
    int frames() {
        return frames;
    }

    /**
     * Returns the RMS level of a 10 ms result.
     *
     * @param i the result
     * @return the level, in the 16-bit range
     */
    double rms(int i) {
        return rms[i];
    }

    /**
     * Returns the voice probability of a 10 ms result.
     *
     * @param i the result
     * @return the probability
     */
    double probability(int i) {
        return probabilities[i];
    }

    /**
     * {@return the voice probability of the last 10 ms result}
     */
    float lastVoiceProbability() {
        return lastVoiceProbability;
    }

    private void standaloneActivity() {
        if (standaloneIndex == 0) {
            return;
        }
        var numFrames = standaloneIndex / LENGTH_10MS;
        if (numFrames > frames) {
            return;
        }
        var activity = standalone.process(standaloneBuffer, standaloneIndex);
        probabilities[0] = activity == 0 ? 0.01 : 0.5;
        for (var n = 1; n < numFrames; n++) {
            probabilities[n] = probabilities[0];
        }
        standaloneIndex = 0;
    }

    private boolean extractFeatures(short[] frame) {
        frames = 0;
        highPassFilter(frame, audioBuffer, numBufferSamples);
        numBufferSamples += NUM_SUBFRAME_SAMPLES;
        if (numBufferSamples < BUFFER_LENGTH) {
            return false;
        }
        frames = NUM_SUBFRAMES;
        computeRms();
        for (var i = 0; i < NUM_SUBFRAMES; ++i) {
            if (rms[i] < SILENCE_RMS) {
                resetBuffer();
                return true;
            }
        }
        pitchAnalysis();
        findFirstSpectralPeaks();
        resetBuffer();
        return false;
    }

    private void resetBuffer() {
        System.arraycopy(audioBuffer, NUM_SAMPLES_TO_PROCESS, audioBuffer, 0, NUM_PAST_SIGNAL_SAMPLES);
        numBufferSamples = NUM_PAST_SIGNAL_SAMPLES;
    }

    private void highPassFilter(short[] in, float[] output, int offset) {
        var num = HPF_NUMERATOR;
        var den = HPF_DENOMINATOR;
        for (var n = 0; n < 2; n++) {
            var o = in[n] * num[0];
            o += n == 0 ? num[1] * pastInput[1] + num[2] * pastInput[0] : num[1] * in[0] + num[2] * pastInput[1];
            o -= n == 0 ? den[1] * pastOutput[1] + den[2] * pastOutput[0]
                    : den[1] * output[offset] + den[2] * pastOutput[1];
            output[offset + n] = o;
        }
        for (var n = 2; n < NUM_SUBFRAME_SAMPLES; n++) {
            var o = in[n] * num[0];
            var sum = 0.0f;
            sum += num[1] * in[n - 1];
            sum += num[2] * in[n - 2];
            o += sum;
            sum = 0.0f;
            sum += den[1] * output[offset + n - 1];
            sum += den[2] * output[offset + n - 2];
            o -= sum;
            output[offset + n] = o;
        }
        pastInput[0] = in[NUM_SUBFRAME_SAMPLES - 2];
        pastInput[1] = in[NUM_SUBFRAME_SAMPLES - 1];
        pastOutput[0] = output[offset + NUM_SUBFRAME_SAMPLES - 2];
        pastOutput[1] = output[offset + NUM_SUBFRAME_SAMPLES - 1];
    }

    private void computeRms() {
        var offset = NUM_PAST_SIGNAL_SAMPLES;
        for (var i = 0; i < NUM_SUBFRAMES; i++) {
            var sum = 0.0;
            for (var n = 0; n < NUM_SUBFRAME_SAMPLES; n++, offset++) {
                sum += audioBuffer[offset] * audioBuffer[offset];
            }
            rms[i] = Math.sqrt(sum / NUM_SUBFRAME_SAMPLES);
        }
    }

    private void pitchAnalysis() {
        pitch.analyze(audioBuffer, NUM_PAST_SIGNAL_SAMPLES, lags, gains);
        for (var n = 0; n < 4; n++) {
            gains[n] = DoubleMath.log(gains[n] + 1e-12);
        }
        interpolate(logOldGain, gains, logPitchGain);
        logOldGain = gains[3];
        interpolate(oldLag, lags, pitchLagHz);
        oldLag = lags[3];
        for (var n = 0; n < NUM_SUBFRAMES; n++) {
            pitchLagHz[n] = 8000 / pitchLagHz[n];
        }
    }

    private static void interpolate(double old, double[] in, double[] out) {
        out[0] = 1. / 6. * old + 5. / 6. * in[0];
        out[1] = 5. / 6. * in[1] + 1. / 6. * in[2];
        out[2] = 0.5 * in[2] + 0.5 * in[3];
    }

    private void findFirstSpectralPeaks() {
        for (int i = 0, offset = 0; i < NUM_SUBFRAMES; i++, offset += LPC_ORDER + 1) {
            var bufferIndex = i * NUM_SUBFRAME_SAMPLES;
            for (var n = 0; n < NUM_SUBFRAME_SAMPLES + NUM_PAST_SIGNAL_SAMPLES; n++) {
                windowed[n] = audioBuffer[bufferIndex++] * LPC_ANALYSIS_WINDOW[n];
            }
            IsacPitch.autoCorr(corr, windowed, NUM_SUBFRAME_SAMPLES + NUM_PAST_SIGNAL_SAMPLES, LPC_ORDER);
            corr[0] *= 1.0001;
            for (var k = 0; k < LPC_ORDER + 1; k++) {
                corr[k] *= CORR_WEIGHT[k];
            }
            IsacPitch.levinsonDurbin(coefficients, reflection, corr, LPC_ORDER);
            System.arraycopy(coefficients, 0, lpc, offset, LPC_ORDER + 1);
        }
        var numDftCoefficients = DFT_SIZE / 2 + 1;
        var data = dft;
        for (var i = 0; i < NUM_SUBFRAMES; i++) {
            java.util.Arrays.fill(data, 0.0f);
            for (var n = 0; n < LPC_ORDER + 1; n++) {
                data[n] = (float) lpc[i * (LPC_ORDER + 1) + n];
            }
            fft.forward(data);
            var indexPeak = 0;
            var prev = data[0] * data[0];
            var curr = data[2] * data[2] + data[3] * data[3];
            var next = 0.0f;
            var foundPeak = false;
            for (var n = 2; n < numDftCoefficients - 1; n++) {
                next = data[2 * n] * data[2 * n] + data[2 * n + 1] * data[2 * n + 1];
                if (curr < prev && curr < next) {
                    foundPeak = true;
                    indexPeak = n - 1;
                    break;
                }
                prev = curr;
                curr = next;
            }
            var fractionalIndex = 0.0f;
            if (!foundPeak) {
                next = data[1] * data[1];
                if (curr < prev && curr < next) {
                    indexPeak = numDftCoefficients - 1;
                }
            } else {
                var nextValue = 1.0f / next;
                var prevValue = 1.0f / prev;
                var currValue = 1.0f / curr;
                fractionalIndex = -(nextValue - prevValue) * 0.5f / (nextValue + prevValue - 2.f * currValue);
            }
            spectralPeak[i] = (indexPeak + fractionalIndex) * FREQUENCY_RESOLUTION;
        }
    }

    private void voicingProbability() {
        for (var n = 0; n < frames; n++) {
            gmmFeatures[0] = logPitchGain[n];
            gmmFeatures[1] = spectralPeak[n];
            gmmFeatures[2] = pitchLagHz[n];
            var voice = evaluateGmm(gmmFeatures, VOICE_MEAN, VOICE_COVAR_INVERSE, VOICE_WEIGHTS);
            var noise = evaluateGmm(gmmFeatures, NOISE_MEAN, NOISE_COVAR_INVERSE, NOISE_WEIGHTS);
            if (spectralPeak[n] < 200 || spectralPeak[n] > 2000 || logPitchGain[n] < -2.0) {
                voice = 1e-12 * noise;
            } else if (logPitchGain[n] > -0.9) {
                noise = 1e-12 * voice;
            }
            var p = priorProbability * voice / (voice * priorProbability + noise * (1 - priorProbability));
            p = limitProbability(p);
            var prodActive = p * probabilities[n];
            var prodInactive = (1 - p) * (1 - probabilities[n]);
            probabilities[n] = prodActive / (prodActive + prodInactive);
            updatePrior(probabilities[n]);
            priorProbability = limitProbability(priorProbability);
        }
    }

    private static double limitProbability(double p) {
        if (p > 0.99) {
            return 0.99;
        } else if (p < 0.01) {
            return 0.01;
        }
        return p;
    }

    private double evaluateGmm(double[] x, double[] mean, double[] covarInverse, double[] weight) {
        var f = 0.0;
        for (var n = 0; n < GMM_MIXTURES; n++) {
            for (var i = 0; i < GMM_DIM; ++i) {
                centered[i] = x[i] - mean[n * GMM_DIM + i];
            }
            var q = 0.0;
            var c = n * GMM_DIM * GMM_DIM;
            for (var i = 0; i < GMM_DIM; ++i) {
                var v = 0.0;
                for (var j = 0; j < GMM_DIM; j++) {
                    v += covarInverse[c++] * centered[j];
                }
                q += v * centered[i];
            }
            q *= -0.5;
            f += DoubleMath.exp(q + weight[n]);
        }
        return f;
    }

    private void updatePrior(double p) {
        if (posteriorsFull) {
            posteriorSum -= posteriors[posteriorIndex];
        }
        posteriorSum += p;
        posteriors[posteriorIndex] = p;
        posteriorIndex++;
        if (posteriorIndex >= POSTERIOR_HISTORY_SIZE) {
            posteriorsFull = true;
            posteriorIndex = 0;
        }
        removeTransient();
        if (posteriorsFull) {
            priorProbability = posteriorSum / POSTERIOR_HISTORY_SIZE;
        } else {
            priorProbability = posteriorIndex > 0 ? posteriorSum / posteriorIndex : 0;
        }
    }

    private int linearIndex(int index) {
        if (index < 0 || index >= POSTERIOR_HISTORY_SIZE) {
            return -1;
        }
        if (!posteriorsFull && index >= posteriorIndex) {
            return -1;
        }
        index = posteriorIndex - 1 - index;
        if (index < 0) {
            index += POSTERIOR_HISTORY_SIZE;
        }
        return index;
    }

    private void setPosterior(int index, double value) {
        var linear = linearIndex(index);
        if (linear < 0) {
            return;
        }
        posteriorSum -= posteriors[linear];
        posteriors[linear] = value;
        posteriorSum += value;
    }

    private void removeTransient() {
        if (!posteriorsFull && posteriorIndex < TRANSIENT_WIDTH_THRESHOLD + 2) {
            return;
        }
        var index2 = TRANSIENT_WIDTH_THRESHOLD + 1;
        if (posteriors[linearIndex(0)] < LOW_PROBABILITY_THRESHOLD) {
            setPosterior(0, 0);
            int index;
            for (index = index2; index > 0; index--) {
                if (posteriors[linearIndex(index)] < LOW_PROBABILITY_THRESHOLD) {
                    break;
                }
            }
            for (; index > 0; index--) {
                setPosterior(index, 0.0);
            }
        }
    }
}
