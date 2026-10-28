/* Ports the processing chain of the ISO/IEC 23003-4 reference software DRC tool decoder (drcToolDecoder.c) for
   time domain audio. Copyright Apple Inc., Fraunhofer IIS and ISO/IEC 2014; ISO/IEC reference software licence (use
   in products conforming to ISO/IEC 23003-4). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import static com.github.auties00.smalt.audio.aac.internal.drc.DrcModel.*;

/**
 * Applies MPEG-D DRC (ISO/IEC 23003-4) to decoded audio: the selection of the DRC sets, equalizer and downmix that
 * match the playback request, the DRC gains before and after the downmix, the loudness normalization gain and the
 * peak limiter, in the order of the reference decoder (ISO/IEC 23003-4 Annex E).
 *
 * <p>The output is delayed by {@link #delay()} samples. Instances are mutable and not thread-safe.
 */
public final class MpegDrcDecoder {
    private static final int[] DECODER_DOWNMIX_ID_LIST = {0, 4};

    private final int sampleRate;
    private final int frameSize;
    private final int inputChannels;
    private final int gainDelaySamples;
    private final int delayMode;
    private final DrcParser parser;
    private final UniDrcConfig config = new UniDrcConfig();
    private final LoudnessInfoSet loudnessInfoSet = new LoudnessInfoSet();
    private final UniDrcGain gain = new UniDrcGain();
    private final AacBitReader gainReader = new AacBitReader(new byte[0]);
    private final DrcSelection.Params params;
    private final boolean configPresent;
    private final boolean gainsPresent;
    private boolean peakLimiterPresent;
    private DrcSelection selection;
    private final DrcGainDecoder[] decoders = new DrcGainDecoder[2];
    private DrcLimiter limiter;
    private int outputChannels;
    private int flushingSamples;
    private float loudnessNormalizationGain = 1.0f;
    private float[] downmixed = new float[0];

    /**
     * Constructs a decoder for a stream whose configuration and loudness information are known up front, as in
     * ISO/IEC 23003-3 ({@code uniDrcConfig()} in the configuration of the {@code uniDrc} extension element and
     * {@code loudnessInfoSet()} in a configuration extension).
     *
     * @param sampleRate the sampling rate of the audio
     * @param channels the number of channels of the audio
     * @param frameSize the number of samples per frame, which is the DRC frame size
     * @param gainDelaySamples the delay of the audio relative to the gains, from 0 to 4500
     * @param uniDrcConfig the {@code uniDrcConfig()} payload, or {@code null} without DRC sets
     * @param loudnessInfo the {@code loudnessInfoSet()} payload, or {@code null} without loudness information
     * @param targetLoudness the target loudness in LKFS, or {@code NaN} to leave loudness normalization off
     * @param effectRequest the requested effect types, the desired one in the least significant 4 bits followed by
     * its fall-backs ({@code 1} night, {@code 2} noisy, {@code 3} limited, {@code 4} low level, {@code 5} dialog,
     * {@code 6} general compression, {@code 7} expanded, {@code 8} artistic; {@code 0} requests sets without
     * compression)
     * @param boost the scale of the gain boosts, from 0 to 1
     * @param compress the scale of the gain attenuations, from 0 to 1
     * @param peakLimiter whether the peak limiter runs
     * @throws MediaException.Malformed if a payload is invalid or the parameters are out of range
     */
    public MpegDrcDecoder(int sampleRate, int channels, int frameSize, int gainDelaySamples, byte[] uniDrcConfig,
                          byte[] loudnessInfo, float targetLoudness, long effectRequest, float boost, float compress,
                          boolean peakLimiter) {
        this(sampleRate, channels, frameSize, gainDelaySamples, DELAY_MODE_REGULAR_DELAY, uniDrcConfig != null,
                uniDrcConfig != null);
        if (uniDrcConfig != null) {
            parser.parseUniDrcConfig(new AacBitReader(uniDrcConfig), config);
        } else {
            DrcParser.generateDrcInstructionsForDrcOff(config);
            config.channelLayout.baseChannelCount = -1;
        }
        if (loudnessInfo != null) {
            parser.parseLoudnessInfoSet(new AacBitReader(loudnessInfo), loudnessInfoSet);
        }
        if (!Float.isNaN(targetLoudness)) {
            params.loudnessNormalizationOn = 1;
            params.targetLoudness = targetLoudness;
        }
        params.requestEffects(effectRequest);
        params.boost = boost;
        params.compress = compress;
        if (peakLimiter) {
            params.peakLimiterPresent = 1;
            peakLimiterPresent = true;
        }
    }

    private MpegDrcDecoder(int sampleRate, int channels, int frameSize, int gainDelaySamples, int delayMode,
                           boolean configPresent, boolean gainsPresent) {
        if (channels < 1 || channels > CHANNEL_COUNT_MAX) {
            throw new MediaException.Malformed("MPEG-D DRC does not support " + channels + " channels");
        }
        this.sampleRate = sampleRate;
        this.frameSize = frameSize;
        this.inputChannels = channels;
        this.gainDelaySamples = gainDelaySamples;
        this.delayMode = delayMode;
        this.configPresent = configPresent;
        this.gainsPresent = gainsPresent;
        this.parser = new DrcParser(sampleRate, frameSize, delayMode);
        this.params = new DrcSelection.Params();
    }

    /**
     * Constructs a decoder for a stream that carries its configuration in band, in {@code uniDrc()} payloads, with
     * the playback request of a {@code uniDrcInterface()} payload (ISO/IEC 23003-4 clauses 7.2 and 7.6).
     *
     * @param sampleRate the sampling rate of the audio
     * @param channels the number of channels of the audio
     * @param frameSize the number of samples per frame
     * @param delayMode {@link DrcModel#DELAY_MODE_REGULAR_DELAY} or {@link DrcModel#DELAY_MODE_LOW_DELAY}
     * @param uniDrcInterface the {@code uniDrcInterface()} payload, or {@code null} for the default request
     * @return the decoder
     * @throws MediaException.Malformed if the interface payload is invalid or the parameters are out of range
     */
    static MpegDrcDecoder inBand(int sampleRate, int channels, int frameSize, int delayMode, byte[] uniDrcInterface) {
        var decoder = new MpegDrcDecoder(sampleRate, channels, frameSize, 0, delayMode, false, true);
        decoder.applyInterface(uniDrcInterface);
        return decoder;
    }

    /**
     * Constructs a decoder for a stream that carries its configuration, loudness information and gains in separate
     * payloads, with the playback request of a {@code uniDrcInterface()} payload.
     *
     * @param sampleRate the sampling rate of the audio
     * @param channels the number of channels of the audio
     * @param frameSize the number of samples per frame
     * @param delayMode {@link DrcModel#DELAY_MODE_REGULAR_DELAY} or {@link DrcModel#DELAY_MODE_LOW_DELAY}
     * @param uniDrcConfig the {@code uniDrcConfig()} payload, or {@code null}
     * @param loudnessInfo the {@code loudnessInfoSet()} payload, or {@code null}
     * @param gainsPresent whether the stream carries {@code uniDrcGain()} payloads
     * @param uniDrcInterface the {@code uniDrcInterface()} payload, or {@code null} for the default request
     * @return the decoder
     * @throws MediaException.Malformed if a payload is invalid or the parameters are out of range
     */
    static MpegDrcDecoder split(int sampleRate, int channels, int frameSize, int delayMode, byte[] uniDrcConfig,
                                byte[] loudnessInfo, boolean gainsPresent, byte[] uniDrcInterface) {
        var decoder = new MpegDrcDecoder(sampleRate, channels, frameSize, 0, delayMode, uniDrcConfig != null,
                gainsPresent);
        if (uniDrcConfig != null) {
            decoder.parser.parseUniDrcConfig(new AacBitReader(uniDrcConfig), decoder.config);
        } else {
            DrcParser.generateDrcInstructionsForDrcOff(decoder.config);
            decoder.config.channelLayout.baseChannelCount = -1;
        }
        if (loudnessInfo != null) {
            decoder.parser.parseLoudnessInfoSet(new AacBitReader(loudnessInfo), decoder.loudnessInfoSet);
        }
        decoder.applyInterface(uniDrcInterface);
        return decoder;
    }

    private void applyInterface(byte[] uniDrcInterface) {
        if (uniDrcInterface != null) {
            peakLimiterPresent = DrcInterface.apply(new AacBitReader(uniDrcInterface), params);
        }
    }

    /**
     * Processes a frame whose {@code uniDrcGain()} payload a bit stream holds, leaving the stream after the payload.
     *
     * @param audio the channels
     * @param reader the bit stream, or {@code null} to repeat the previous gains
     * @throws MediaException.Malformed if the payload is invalid
     */
    void processGain(float[][] audio, AacBitReader reader) {
        initialize();
        if (gainsPresent && reader != null) {
            parser.parseUniDrcGain(reader, config, gain);
        }
        apply(audio);
    }

    /**
     * {@return the delay of the output in samples, valid once the first frame is processed}
     */
    public int delay() {
        return flushingSamples;
    }

    /**
     * {@return the number of output channels, valid once the first frame is processed}
     */
    public int outputChannels() {
        return selection == null ? inputChannels : outputChannels;
    }

    /**
     * Processes a frame whose gains come in a {@code uniDrcGain()} payload ({@code
     * processUniDrcBitstreamDec_uniDrcGain}
     * and the processing of the DRC tool decoder).
     *
     * @param audio the channels, at least {@link #outputChannels()} and the input channel count, each of at least the
     * frame size; the output replaces the input
     * @param payload the {@code uniDrcGain()} payload of the frame, or {@code null} to repeat the previous gains
     * @param offset the offset of the payload
     * @param length the length of the payload
     * @throws MediaException.Malformed if the payload is invalid
     */
    public void process(float[][] audio, byte[] payload, int offset, int length) {
        initialize();
        if (gainsPresent && payload != null) {
            gainReader.reset(payload, offset, length);
            parser.parseUniDrcGain(gainReader, config, gain);
        }
        apply(audio);
    }

    /**
     * Processes a frame of a stream with in-band configuration, reading its {@code uniDrc()} and {@code uniDrcGain()}
     * payloads from a bit stream without byte alignment between frames.
     *
     * @param audio the channels
     * @param reader the bit stream, or {@code null} once it is exhausted, which repeats the previous gains
     * @throws MediaException.Malformed if the payload is invalid
     */
    void processInBand(float[][] audio, AacBitReader reader) {
        if (reader != null && reader.bitsLeft() > 0) {
            if (DrcParser.bits(reader, 1) != 0) {
                if (DrcParser.bits(reader, 1) != 0) {
                    parser.parseUniDrcConfig(reader, config);
                } else {
                    DrcParser.generateDrcInstructionsForDrcOff(config);
                    config.channelLayout.baseChannelCount = -1;
                }
                loudnessInfoSet.loudnessInfoAlbumCount = 0;
                loudnessInfoSet.loudnessInfoCount = 0;
                parser.parseLoudnessInfoSet(reader, loudnessInfoSet);
            }
        }
        initialize();
        if (reader != null && reader.bitsLeft() > 0) {
            parser.parseUniDrcGain(reader, config, gain);
        }
        apply(audio);
    }

    private void initialize() {
        if (selection != null) {
            return;
        }
        if (config.channelLayout.baseChannelCount < 0 || !configPresent) {
            config.channelLayout.baseChannelCount = inputChannels;
        }
        selection = new DrcSelection(params, DrcSelection.SUBBAND_DOMAIN_MODE_OFF);
        selection.process(config, loudnessInfoSet);
        var output = selection.output;
        if (inputChannels != output.baseChannelCount) {
            throw new MediaException.Malformed("MPEG-D DRC base layout has " + output.baseChannelCount
                    + " channels, not " + inputChannels);
        }
        var delay = 0;
        for (var i = 0; i < decoders.length; i++) {
            var ids = new int[SUB_DRC_COUNT];
            var downmixIds = new int[SUB_DRC_COUNT];
            var count = 0;
            for (var j = 0; j < output.numSelectedDrcSets; j++) {
                if (downmixIdMatches(output.selectedDownmixIds[j], DECODER_DOWNMIX_ID_LIST[i])) {
                    ids[count] = output.selectedDrcSetIds[j];
                    downmixIds[count] = output.selectedDownmixIds[j];
                    count++;
                }
            }
            int channelCount;
            if (i == 0) {
                channelCount = inputChannels;
            } else {
                outputChannels = output.targetChannelCount;
                channelCount = outputChannels;
            }
            var decoder = new DrcGainDecoder(frameSize, sampleRate, gainDelaySamples, delayMode);
            decoder.initAfterConfig(channelCount, ids, downmixIds, count, output.selectedEqSetIds[i], config,
                    loudnessInfoSet);
            decoders[i] = decoder;
            delay += decoder.parametricDrcDelay() + decoder.eqDelay();
        }
        if (outputChannels < 1 || outputChannels > inputChannels) {
            throw new MediaException.Malformed("MPEG-D DRC downmix has " + outputChannels + " channels");
        }
        if (output.loudnessNormalizationGainDb != 0.0f) {
            loudnessNormalizationGain = (float) StrictMath.pow(10.0, output.loudnessNormalizationGainDb / 20.0);
        }
        if (peakLimiterPresent) {
            limiter = new DrcLimiter(DrcLimiter.ATTACK_DEFAULT_MS, DrcLimiter.RELEASE_DEFAULT_MS,
                    DrcLimiter.DEFAULT_THRESHOLD, outputChannels, sampleRate);
            delay += limiter.delay();
        }
        flushingSamples = delay;
        downmixed = new float[inputChannels];
    }

    private static boolean downmixIdMatches(int downmixId, int decoderDownmixId) {
        return switch (decoderDownmixId) {
            case 0 -> downmixId == 0;
            case 1 -> downmixId == 0 || downmixId == ID_FOR_ANY_DOWNMIX;
            case 2 -> downmixId == ID_FOR_ANY_DOWNMIX;
            case 3 -> downmixId != 0 && downmixId != ID_FOR_ANY_DOWNMIX;
            default -> downmixId != 0;
        };
    }

    private void apply(float[][] audio) {
        var output = selection.output;
        decoders[0].process(audio, config, gain, output.loudnessNormalizationGainDb, output.boost, output.compress);
        if (output.downmixMatrixPresent != 0) {
            var base = output.baseChannelCount;
            var target = output.targetChannelCount;
            for (var n = 0; n < frameSize; n++) {
                for (var oc = 0; oc < target; oc++) {
                    var sum = 0.0f;
                    for (var ic = 0; ic < base; ic++) {
                        sum += audio[ic][n] * output.downmixMatrix[ic][oc];
                    }
                    downmixed[oc] = sum;
                }
                for (var oc = 0; oc < target; oc++) {
                    audio[oc][n] = downmixed[oc];
                }
                for (var oc = target; oc < base; oc++) {
                    audio[oc][n] = 0.0f;
                }
            }
        }
        decoders[1].process(audio, config, gain, output.loudnessNormalizationGainDb, output.boost, output.compress);
        if (output.loudnessNormalizationGainDb != 0.0f) {
            for (var c = 0; c < outputChannels; c++) {
                var channel = audio[c];
                for (var i = 0; i < frameSize; i++) {
                    channel[i] *= loudnessNormalizationGain;
                }
            }
        }
        if (limiter != null) {
            limiter.apply(audio, frameSize);
        }
    }
}
