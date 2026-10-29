package com.github.auties00.smalt.audio.aac.internal;

import java.util.Arrays;

/**
 * Applies the dynamic range control of an AAC stream: the {@code dynamic_range_info} of ISO/IEC
 * 14496-3 subclause 4.5.2.7, the heavy compression value of the MPEG-4 ancillary data of ETSI TS
 * 101 154 Annex C.5, and the program reference level normalization.
 *
 * <p>The gains of a frame scale its spectral lines before the synthesis filterbank, band by band,
 * or, when spectral band replication is decoded, the QMF subband samples before the QMF synthesis
 * as subclause 4.5.2.7.5 specifies: the band borders are mapped onto the 32 lower subbands, the
 * highest band extends over the replicated ones, and long frames move from the previous frame's
 * gains to the new ones by the interpolation scheme the stream selects. Channel information
 * persists until the stream overwrites it; a {@code dynamic_range_info} resets the information of
 * every channel it does not exclude before applying its own.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation applies the QMF-domain gains of low-delay spectral band
 * replication with the six-slot offset of the 1024-sample framing, which the specification
 * defines only for the SBR object type.
 */
public final class DynamicRangeControl {
    /**
     * The extension type of a fill element payload holding dynamic range information.
     */
    public static final int EXT_DYNAMIC_RANGE = 11;

    /**
     * The largest number of bands of a {@code dynamic_range_info}.
     */
    private static final int MAX_BANDS = 16;

    /**
     * The number of channels whose information is tracked before the first growth.
     */
    private static final int INITIAL_CHANNELS = 8;

    /**
     * The sync byte of the MPEG-4 ancillary data of ETSI TS 101 154.
     */
    private static final int ANCILLARY_SYNC = 0xBC;

    /**
     * The slot from which each step interpolation scheme takes the new gains, 1024-sample framing
     * (ISO/IEC 14496-3 subclause 4.5.2.7.5).
     */
    private static final int[] STEP_OFFSETS_1024 = {0, 4, 8, 12, 16, 20, 24, 28};

    /**
     * The slot from which each step interpolation scheme takes the new gains, 960-sample framing.
     */
    private static final int[] STEP_OFFSETS_960 = {0, 4, 8, 11, 15, 19, 23, 26};

    /**
     * Whether the {@code dynamic_range_info} gains are applied.
     */
    private final boolean light;

    /**
     * Whether the heavy compression gain is applied while the stream switches it on.
     */
    private final boolean heavy;

    /**
     * The fraction of the attenuations applied.
     */
    private final double cut;

    /**
     * The fraction of the boosts applied.
     */
    private final double boost;

    /**
     * The target level in quarter dB below full scale, or -1 for no normalization.
     */
    private final int targetLevel;

    /**
     * The number of spectral lines per frame.
     */
    private final int frameLength;

    /**
     * The number of QMF slots per frame.
     */
    private final int slots;

    /**
     * The row of the QMF gain matrix that the first output slot of a frame uses.
     */
    private final int qmfOffset;

    /**
     * The step interpolation offsets of the framing.
     */
    private final int[] stepOffsets;

    /**
     * The program reference level in quarter dB below full scale.
     */
    private int programLevel;

    /**
     * The number of bands of each channel.
     */
    private int[] bandCounts;

    /**
     * The last spectral line of each band, divided by four, by channel and band.
     */
    private int[][] bandTops;

    /**
     * The gain of each band in quarter dB (negative attenuates), by channel and band.
     */
    private int[][] gains;

    /**
     * The interpolation scheme of each channel.
     */
    private int[] schemes;

    /**
     * The QMF gain matrix of each channel, two frames of rows of 64 subbands, or {@code null}
     * before its first use.
     */
    private double[][][] qmfGains;

    /**
     * The QMF gains of each channel's current output slots, 64 per slot, with six slots of
     * look-ahead, or {@code null} before their first use.
     */
    private float[][] slotGains;

    /**
     * Whether the stream switched the heavy compression on.
     */
    private boolean compressionOn;

    /**
     * The heavy compression gain, linear.
     */
    private double compressionGain = 1;

    /**
     * The gains of the bands of the channel being applied, linear.
     */
    private final double[] bandGains = new double[MAX_BANDS];

    /**
     * The previous frame's last row of the QMF gain matrix being updated.
     */
    private final double[] previousRow = new double[64];

    /**
     * The exclusion mask of the {@code dynamic_range_info} being parsed.
     */
    private boolean[] excluded = new boolean[INITIAL_CHANNELS];

    /**
     * The band tops of the {@code dynamic_range_info} being parsed.
     */
    private final int[] parsedTops = new int[MAX_BANDS];

    /**
     * The gains of the {@code dynamic_range_info} being parsed.
     */
    private final int[] parsedGains = new int[MAX_BANDS];

    /**
     * Creates the control of one stream.
     *
     * @param light whether the {@code dynamic_range_info} gains are applied
     * @param heavy whether the heavy compression gain is applied while the stream switches it on
     * @param cut the fraction of the attenuations applied, from 0 to 1
     * @param boost the fraction of the boosts applied, from 0 to 1
     * @param targetLevel the target level in quarter dB below full scale, from 0 to 127, or -1
     * for no normalization
     * @param frameLength the number of spectral lines per frame
     * @param lowDelay whether the stream uses low-delay spectral band replication
     */
    public DynamicRangeControl(boolean light, boolean heavy, double cut, double boost, int targetLevel,
                               int frameLength, boolean lowDelay) {
        this.light = light;
        this.heavy = heavy;
        this.cut = cut;
        this.boost = boost;
        this.targetLevel = targetLevel;
        this.frameLength = frameLength;
        this.slots = frameLength / 32;
        this.qmfOffset = lowDelay || frameLength != 960 ? 6 : 5;
        this.stepOffsets = frameLength % 120 == 0 ? STEP_OFFSETS_960 : STEP_OFFSETS_1024;
        this.programLevel = Math.max(targetLevel, 0);
        allocate(INITIAL_CHANNELS);
    }

    /**
     * Sizes the per-channel state for at least a number of channels; new channels start from the
     * default information: one band covering the frame, no gain.
     *
     * @param channels the number of channels
     */
    public void ensureChannels(int channels) {
        if (channels > bandCounts.length) {
            allocate(Math.max(channels, 2 * bandCounts.length));
        }
    }

    /**
     * Grows the per-channel state.
     *
     * @param channels the new capacity
     */
    private void allocate(int channels) {
        var old = bandCounts == null ? 0 : bandCounts.length;
        bandCounts = bandCounts == null ? new int[channels] : Arrays.copyOf(bandCounts, channels);
        bandTops = bandTops == null ? new int[channels][] : Arrays.copyOf(bandTops, channels);
        gains = gains == null ? new int[channels][] : Arrays.copyOf(gains, channels);
        schemes = schemes == null ? new int[channels] : Arrays.copyOf(schemes, channels);
        qmfGains = qmfGains == null ? new double[channels][][] : Arrays.copyOf(qmfGains, channels);
        slotGains = slotGains == null ? new float[channels][] : Arrays.copyOf(slotGains, channels);
        for (var channel = old; channel < channels; channel++) {
            bandTops[channel] = new int[MAX_BANDS];
            gains[channel] = new int[MAX_BANDS];
            reset(channel);
        }
    }

    /**
     * Restores the default information of a channel.
     *
     * @param channel the index of the channel, in stream order
     */
    private void reset(int channel) {
        bandCounts[channel] = 1;
        bandTops[channel][0] = frameLength / 4 - 1;
        gains[channel][0] = 0;
        schemes[channel] = 0;
    }

    /**
     * Returns whether the control changes any sample, given its settings alone.
     *
     * @return {@code true} unless every gain source is off
     */
    public boolean active() {
        return light || heavy || targetLevel >= 0;
    }

    /**
     * Reads a {@code dynamic_range_info} (ISO/IEC 14496-3 Table 4.58) after its extension type
     * and applies it, unless it runs past the bits available.
     *
     * @param reader the bit source, after the extension type
     * @param available the number of bits of the enclosing payload after the extension type
     * @return the number of bytes of the extension payload, extension type included
     */
    public int read(AacBitReader reader, long available) {
        var start = reader.bitPosition();
        var bytes = 1;
        if (reader.readFlag()) {
            reader.read(8);
            bytes++;
        }
        var maskLength = 0;
        if (reader.readFlag()) {
            do {
                for (var i = 0; i < 7; i++) {
                    if (maskLength == excluded.length) {
                        excluded = Arrays.copyOf(excluded, 2 * maskLength);
                    }
                    excluded[maskLength++] = reader.readFlag();
                }
                bytes++;
            } while (reader.readFlag() && reader.bitPosition() - start < available);
        }
        var bands = 1;
        var scheme = 0;
        if (reader.readFlag()) {
            bands += reader.read(4);
            scheme = reader.read(4);
            bytes++;
            for (var band = 0; band < bands; band++) {
                parsedTops[band] = reader.read(8);
                bytes++;
            }
        } else {
            parsedTops[0] = frameLength / 4 - 1;
        }
        var level = -1;
        if (reader.readFlag()) {
            level = reader.read(7);
            reader.read(1);
            bytes++;
        }
        for (var band = 0; band < bands; band++) {
            var negative = reader.readFlag();
            var magnitude = reader.read(7);
            parsedGains[band] = negative ? -magnitude : magnitude;
            bytes++;
        }
        if (reader.bitPosition() - start > available) {
            return bytes;
        }
        if (level >= 0) {
            programLevel = level;
        }
        ensureChannels(maskLength);
        for (var channel = 0; channel < bandCounts.length; channel++) {
            if (channel < maskLength && excluded[channel]) {
                continue;
            }
            reset(channel);
            bandCounts[channel] = bands;
            System.arraycopy(parsedTops, 0, bandTops[channel], 0, bands);
            System.arraycopy(parsedGains, 0, gains[channel], 0, bands);
            schemes[channel] = scheme;
        }
        return bytes;
    }

    /**
     * Reads the payload of a data stream element and takes the heavy compression value from it
     * when it holds the MPEG-4 ancillary data of ETSI TS 101 154 (Table C.22); other payloads
     * are skipped.
     *
     * @param reader the bit source, at the first data byte
     * @param count the number of data bytes
     */
    public void readAncillary(AacBitReader reader, int count) {
        var used = 0;
        if (count >= 3 && reader.read(8) == ANCILLARY_SYNC) {
            reader.read(8);
            var status = reader.read(8);
            used = 3;
            if ((status & 0x10) != 0 && used < count) {
                reader.read(8);
                used++;
            }
            if ((status & 0x04) != 0 && used + 2 <= count) {
                var mode = reader.read(8);
                var value = reader.read(8);
                used += 2;
                compressionOn = (mode & 1) != 0;
                compressionGain = StrictMath.pow(10, (48.164 - 6.0206 * (value >> 4) - 0.4014 * (value & 15)) / 20);
            }
        } else if (count >= 3) {
            used = 1;
        }
        reader.skip(8L * (count - used));
    }

    /**
     * Returns whether the heavy compression gain replaces the {@code dynamic_range_info} gains in
     * the current frame.
     *
     * @return {@code true} when heavy compression is selected and switched on
     */
    private boolean heavyOn() {
        return heavy && compressionOn;
    }

    /**
     * Fills {@link #bandGains} with the linear gains of a channel's bands and returns their
     * number.
     *
     * @param channel the index of the channel, in stream order
     * @return the number of bands
     */
    private int computeGains(int channel) {
        var normalization = targetLevel < 0 ? 1 : StrictMath.pow(0.5, (targetLevel - programLevel) / 24.0);
        if (heavyOn()) {
            bandGains[0] = compressionGain * normalization;
            return 1;
        }
        var bands = bandCounts[channel];
        for (var band = 0; band < bands; band++) {
            var gain = gains[channel][band];
            var factor = !light ? 1 : StrictMath.pow(2, (gain < 0 ? cut : boost) * gain / 24.0);
            bandGains[band] = factor * normalization;
        }
        return bands;
    }

    /**
     * Returns the last spectral line plus one of a channel's band.
     *
     * @param channel the index of the channel, in stream order
     * @param band the index of the band within the channel's bands
     * @return the line, limited to the frame
     */
    private int top(int channel, int band) {
        if (heavyOn()) {
            return frameLength;
        }
        return Math.min(4 * (bandTops[channel][band] + 1), frameLength);
    }

    /**
     * Scales a channel's spectral lines by the gains of its bands, ISO/IEC 14496-3 subclause
     * 4.5.2.7.2; with eight short windows the lines are the eight windows one after the other.
     *
     * @param channel the channel, in the order of the stream's channel elements, the
     * independently switched coupling channels last
     * @param spectrum the spectral lines, scaled in place
     */
    public void applySpectrum(int channel, float[] spectrum) {
        ensureChannels(channel + 1);
        var bands = computeGains(channel);
        var bottom = 0;
        for (var band = 0; band < bands; band++) {
            var top = top(channel, band);
            var gain = (float) bandGains[band];
            if (gain != 1) {
                for (var line = bottom; line < top; line++) {
                    spectrum[line] *= gain;
                }
            }
            bottom = Math.max(bottom, top);
        }
    }

    /**
     * Advances a channel's QMF gains by one frame, ISO/IEC 14496-3 subclause 4.5.2.7.5, and
     * returns the gains of the frame's output slots.
     *
     * @param channel the index of the channel, in stream order
     * @param shortWindows whether the channel's frame uses eight short windows
     * @return the gain of each subband of each output slot, 64 per slot, the frame's slots
     * followed by six slots of look-ahead; owned by this control and valid until the next call
     * for the channel
     */
    public float[] advanceQmf(int channel, boolean shortWindows) {
        ensureChannels(channel + 1);
        var matrix = qmfGains[channel];
        if (matrix == null) {
            matrix = new double[2 * slots][64];
            for (var row : matrix) {
                Arrays.fill(row, 1);
            }
            qmfGains[channel] = matrix;
            slotGains[channel] = new float[(slots + 6) * 64];
        }
        for (var row = 0; row < slots; row++) {
            System.arraycopy(matrix[slots + row], 0, matrix[row], 0, 64);
        }
        System.arraycopy(matrix[slots - 1], 0, previousRow, 0, 64);
        var bands = computeGains(channel);
        var scheme = heavyOn() ? 0 : schemes[channel];
        var half = slots / 2;
        var bottom = 0;
        for (var band = 0; band < bands; band++) {
            var top = top(channel, band);
            var gain = bandGains[band];
            if (!shortWindows) {
                var bottomQmf = slots * (bottom / slots) * 32 / frameLength;
                for (var j = -half; j < slots; j++) {
                    double alpha;
                    if (j + half >= slots) {
                        alpha = 1;
                    } else if (scheme == 0 || scheme > 8) {
                        alpha = (j + half) / (double) slots;
                    } else {
                        alpha = j + half >= stepOffsets[scheme - 1] ? 1 : 0;
                    }
                    var row = matrix[slots + j];
                    for (var k = bottomQmf; k < 64; k++) {
                        row[k] = alpha * gain + (1 - alpha) * previousRow[k];
                    }
                }
            } else {
                var windowLength = frameLength / 8;
                var startSample = bottom / windowLength * slots / 8;
                var stopSample = Math.min(slots, (top + windowLength - 1) / windowLength * slots / 8);
                var bottomQmf = bottom % windowLength * 32 / windowLength;
                for (var j = startSample; j < stopSample; j++) {
                    if (j > startSample && j % 4 == 0) {
                        bottomQmf = 0;
                    }
                    Arrays.fill(matrix[slots + j], bottomQmf, 64, gain);
                }
            }
            bottom = Math.max(bottom, top);
        }
        var out = slotGains[channel];
        for (var slot = 0; slot < slots + 6; slot++) {
            var row = matrix[qmfOffset + slot];
            for (var k = 0; k < 64; k++) {
                out[slot * 64 + k] = (float) row[k];
            }
        }
        return out;
    }
}
