package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Selects the E-AC-3 coding tools a {@link FrameEncoder} uses beyond those of AC-3: spectral
 * extension, the adaptive hybrid transform and transient pre-noise processing, and the substream
 * the frames belong to.
 *
 * @param spxStartCode the spectral extension start copy frequency code {@code spxstrtf}, from
 * 0 to 3, or {@code -1} without spectral extension
 * @param spxBeginCode the spectral extension begin frequency code {@code spxbegf}, from 0 to 7
 * @param spxEndCode the spectral extension end frequency code {@code spxendf}, from 0 to 7
 * @param hybridTransform whether stationary channels of six-block frames may use the adaptive
 * hybrid transform
 * @param preNoise whether the frames carry transient pre-noise processing data
 * @param channelMap the custom channel map {@code chanmap} of a dependent substream, or
 * {@code -1} for an independent substream
 */
public record EncoderTools(int spxStartCode, int spxBeginCode, int spxEndCode, boolean hybridTransform,
                           boolean preNoise, int channelMap) {
    /**
     * The tools of AC-3 alone: an independent substream without any E-AC-3 tool.
     */
    public static final EncoderTools NONE = new EncoderTools(-1, 0, 0, false, false, -1);

    /**
     * The first coefficient of the extension region at 48 kHz of the three spectral extension
     * tiers: about 8.0, 10.2 and 12.5 kHz.
     */
    private static final int[] TIER_BEGIN = {85, 109, 133};

    /**
     * One past the last coefficient of the extension region at 48 kHz of the three spectral
     * extension tiers: about 14.7, 17.0 and 17.0 kHz.
     */
    private static final int[] TIER_END = {157, 181, 181};

    /**
     * The lowest bit rate per full-bandwidth channel of each spectral extension tier, and the rate
     * from which spectral extension is off.
     */
    private static final int[] TIER_RATE = {44_000, 56_000, 72_000, 92_000};

    /**
     * Returns the tools an E-AC-3 substream uses by default: spectral extension in the tier of
     * its bit rate per full-bandwidth channel, at 32, 44.1 and 48 kHz.
     *
     * <p>From 44 to 56 kbit/s per channel the extension region spans about 8.0 to 14.7 kHz, from
     * 56 to 72 kbit/s about 10.2 to 17.0 kHz and from 72 to 92 kbit/s about 12.5 to 17.0 kHz,
     * copied from 2.3 kHz up; the regions scale to the coefficient grid of 32 and 44.1 kHz and end
     * below 0.45 times the sampling rate. Below 44 and from 92 kbit/s per channel, and at the
     * reduced sampling rates, spectral extension is off.
     *
     * @implNote This implementation sets the tiers by measuring music and film sound against coding
     * without spectral extension: within them the waveform-coded band gains SNR from the bits the
     * extension frees without a drop of segmental SNR, while below them the side information costs
     * more than it frees and above them the segmental SNR drops.
     *
     * @param sampleRate the sampling rate
     * @param bitrate the bit rate of the substream in bits per second
     * @param channels the number of full-bandwidth channels of the substream
     * @return the tools
     */
    public static EncoderTools defaults(int sampleRate, int bitrate, int channels) {
        if (sampleRate < 32_000) {
            return NONE;
        }
        var perChannel = bitrate / channels;
        var tier = -1;
        for (var t = 0; t < 3; t++) {
            if (perChannel >= TIER_RATE[t] && perChannel < TIER_RATE[t + 1]) {
                tier = t;
            }
        }
        if (tier < 0) {
            return NONE;
        }
        var scale = 48_000.0 / sampleRate;
        var limit = 0.45 * 512;
        var beginCode = 0;
        var beginDistance = Double.MAX_VALUE;
        for (var code = 0; code < 8; code++) {
            var bin = 25 + 12 * (code < 6 ? code + 2 : code * 2 - 3);
            var distance = Math.abs(bin - TIER_BEGIN[tier] * scale);
            if (distance < beginDistance && bin < limit) {
                beginDistance = distance;
                beginCode = code;
            }
        }
        var beginBin = 25 + 12 * (beginCode < 6 ? beginCode + 2 : beginCode * 2 - 3);
        var endCode = -1;
        var endDistance = Double.MAX_VALUE;
        for (var code = 0; code < 8; code++) {
            var bin = 25 + 12 * (code < 3 ? code + 5 : code * 2 + 3);
            var distance = Math.abs(bin - TIER_END[tier] * scale);
            if (distance < endDistance && bin > beginBin && bin <= limit) {
                endDistance = distance;
                endCode = code;
            }
        }
        return endCode < 0 ? NONE : NONE.withSpectralExtension(0, beginCode, endCode);
    }

    /**
     * Returns whether spectral extension is used.
     *
     * @return {@code true} when {@link #spxStartCode()} is not negative
     */
    public boolean spectralExtension() {
        return spxStartCode >= 0;
    }

    /**
     * Returns these tools with spectral extension over the given codes.
     *
     * @param startCode the start copy frequency code, from 0 to 3, or {@code -1} for none
     * @param beginCode the begin frequency code, from 0 to 7
     * @param endCode the end frequency code, from 0 to 7
     * @return the new tools
     */
    public EncoderTools withSpectralExtension(int startCode, int beginCode, int endCode) {
        return new EncoderTools(startCode, beginCode, endCode, hybridTransform, preNoise, channelMap);
    }

    /**
     * Returns these tools with the adaptive hybrid transform allowed or not.
     *
     * @param hybridTransform whether the transform may be used
     * @return the new tools
     */
    public EncoderTools withHybridTransform(boolean hybridTransform) {
        return new EncoderTools(spxStartCode, spxBeginCode, spxEndCode, hybridTransform, preNoise, channelMap);
    }

    /**
     * Returns these tools with transient pre-noise processing data on or off.
     *
     * @param preNoise whether the frames carry the data
     * @return the new tools
     */
    public EncoderTools withPreNoise(boolean preNoise) {
        return new EncoderTools(spxStartCode, spxBeginCode, spxEndCode, hybridTransform, preNoise, channelMap);
    }

    /**
     * Returns these tools for a dependent substream with a custom channel map.
     *
     * @param channelMap the channel map, or {@code -1} for an independent substream
     * @return the new tools
     */
    public EncoderTools withChannelMap(int channelMap) {
        return new EncoderTools(spxStartCode, spxBeginCode, spxEndCode, hybridTransform, preNoise, channelMap);
    }
}
