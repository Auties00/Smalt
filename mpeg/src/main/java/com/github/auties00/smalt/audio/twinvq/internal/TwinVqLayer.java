/* Ports the TwinVQ tools of the ISO/IEC 14496-3 reference software (ISO/IEC 14496-5, src_tf: ntt_BitUnPack.c,
   ntt_SclBitUnPack.c, ntt_vex_pn.c, ntt_tf_requantize_spectrum.c, mat_scale_tf_requantize_spectrum.c,
   ntt_tf_proc_spectrum_d.c, ntt_scale_tf_proc_spectrum_d.c, ntt_dec_bark_env.c, ntt_scale_dec_bark_env.c,
   mat_scale_set_shift_para.c, ntt_dec_gain.c, ntt_dec_sq_gain.c, ntt_mulawinv.c, ntt_dec_pit_seq.c, ntt_extend_pitch.c,
   ntt_dec_pgain.c, ntt_lsp_decw.c, ntt_redec.c, ntt_check.c, ntt_lsptowt_int.c, ntt_denormalizer_spectrum.c,
   ntt_post_process.c), developed by Naoki Iwakami, Satoshi Miki, Takehiro Moriya, Akio Jin and Kazuaki
   Chikira (NTT), Takeshi Norimatsu, Mineo Tsushima and Tomokazu Ishikawa (Matsushita Electric Industrial Co Ltd.),
   and edited by them. ISO/IEC gives users of the MPEG-2 NBC/MPEG-4 Audio standards free license to this software
   module or modifications thereof for use in hardware or software products claiming conformance to the MPEG-2
   NBC/MPEG-4 Audio standards. Copyright (c) 1996-1997. Modified for Smalt. */
package com.github.auties00.smalt.audio.twinvq.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import java.util.Arrays;

/**
 * Reconstructs the spectrum of one TwinVQ coding layer: the interleaved vector quantization tool (ISO/IEC 14496-3
 * subclause 4.6.5) and the spectrum normalization tool (subclause 4.6.10), with the state each keeps between frames.
 *
 * <p>The base layer and the scalable layers share the tools and differ in their code books, gain quantizers and
 * active band: the base layer's is fixed by its bitrate, while a scalable layer selects one of four through
 * {@code fb_shift}. Where the text of the standard differs from the reference decoder, as in the polarity of the code
 * vectors, the gain of the periodic peak components or the spacing of the active bands, this class follows the
 * reference decoder, whose output the ISO/IEC 14496-26 waveforms are.
 *
 * <p>Instances are not thread-safe.
 */
final class TwinVqLayer {
    /**
     * The number of windows of an eight short sequence.
     */
    private static final int SHORT_WINDOWS = 8;

    /**
     * The largest number of bits of a shape code index, polarity included ({@code MAXBIT}).
     */
    private static final int MAX_BITS = 6;

    /**
     * The number of bits of the shape code of an index, below the polarity bit ({@code MAXBIT_SHAPE}).
     */
    private static final int SHAPE_BITS = 5;

    /**
     * The number of bits of the LSP indices of a channel: the prediction switch, the first stage and three splits of
     * the second stage.
     */
    private static final int LSP_BITS = 1 + 6 + 3 * 4;

    /**
     * The number of LSP indices of a channel.
     */
    private static final int LSP_INDICES = 5;

    /**
     * The splits of the second stage LSP vector ({@code isp}, Table 4.161).
     */
    private static final int[] LSP_SPLITS = {0, 5, 14, 20};

    /**
     * The smallest gap between two LSP coefficients and the bounds of the first and the last.
     */
    private static final double MIN_GAP = 0.0001;

    private static final double LOW_LIMIT = -10.0;

    private static final double HIGH_LIMIT = 10.0;

    /**
     * The number of divisions of the envelope vector ({@code FW_N_DIV}), the bits of an index, and the bits of the
     * envelope of a channel, prediction switch included.
     */
    private static final int ENVELOPE_DIVISIONS = 7;

    private static final int ENVELOPE_BITS = 6;

    private static final int ENVELOPE_TOTAL_BITS = ENVELOPE_BITS * ENVELOPE_DIVISIONS + 1;

    /**
     * The number of Bark-scale subbands ({@code N_CRB}).
     */
    private static final int BARK_BANDS = 42;

    /**
     * The step of the MA prediction coefficient of the envelope ({@code FW_ALF_STEP}), and the lower limit of a
     * decoded envelope value.
     */
    private static final double ENVELOPE_ALPHA_STEP = 0.5;

    private static final double ENVELOPE_FLOOR = 0.1;

    /**
     * The normalized amplitude of the flattened coefficients ({@code AMP_NM} and {@code SUB_AMP_NM}).
     */
    private static final double AMPLITUDE_NORM = 1024.0;

    /**
     * The factor of the mu-law gain quantizers ({@code MU}).
     */
    private static final double MU = 100.0;

    /**
     * The bits of a subframe gain, the bits of the band limit of a channel and the steps of its higher part.
     */
    private static final int SUB_GAIN_BITS = 4;

    private static final int BAND_LIMIT_BITS = 3;

    private static final int BAND_LIMIT_STEPS = 3;

    /**
     * The smallest upper band limit and the largest lower band limit ({@code CUT_M_H} and {@code CUT_M_L}).
     */
    private static final double CUT_HIGH = 0.7;

    private static final double CUT_LOW = 0.0025;

    /**
     * The elements of the periodic peak components of a channel ({@code N_FR_P}), the divisions of a channel, the bits
     * of a shape code, those of a channel including base frequency and gain, and the quantizer of the gain.
     */
    private static final int PERIODIC_ELEMENTS = 20;

    private static final int PERIODIC_DIVISIONS = 2;

    private static final int PERIODIC_SHAPE_BITS = 6;

    private static final int PERIODIC_BASE_BITS = 8;

    private static final int PERIODIC_GAIN_BITS = 7;

    private static final int PERIODIC_TOTAL_BITS = 28 + PERIODIC_BASE_BITS + PERIODIC_GAIN_BITS;

    private static final double PERIODIC_GAIN_MAX = 20000.0;

    private static final double PERIODIC_GAIN_MU = 200.0;

    private static final double PERIODIC_GAIN_STEP = PERIODIC_GAIN_MAX / ((1 << PERIODIC_GAIN_BITS) - 1);

    /**
     * The tables of the Bark-scale subbands of the base layer: the scalefactor bands of AAC at 16 kHz, up to 16 kHz,
     * and at 24 kHz above; the last band ends at the frame length.
     */
    private static final int[] BARK_16 = {8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 100, 112, 124, 136, 148, 160, 172,
            184, 196, 212, 228, 244, 260, 280, 300, 320, 344, 368, 396, 424, 456, 492, 532, 572, 616, 664, 716, 772, 832,
            896, 1024};

    private static final int[] BARK_24 = {4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 52, 60, 68, 76, 84, 92, 100, 108,
            116, 124, 136, 148, 160, 172, 188, 204, 220, 240, 260, 284, 308, 336, 364, 396, 432, 468, 508, 552, 600, 652,
            1024};

    /**
     * Whether this is a scalable layer rather than the base layer.
     */
    private final boolean scalable;

    private final int channels;

    private final int frameLength;

    /**
     * The sampling frequency truncated to kilohertz ({@code ISAMPF}).
     */
    private final int rateKhz;

    /**
     * The LSP code book of the layer.
     */
    private final double[] lspCode;

    /**
     * The interleaved vector quantization code books of the layer, long and short windows, of conjugate channels 0 and
     * 1, with the elements of a code vector.
     */
    private final double[][] shapeLong;

    private final double[][] shapeShort;

    private final int shapeLongLength;

    private final int shapeShortLength;

    /**
     * The global gain quantizer: its bits, its largest amplitude and its step.
     */
    private final int gainBits;

    private final double gainMax;

    private final double gainStep;

    /**
     * The subframe gain quantizer: its largest amplitude and its step.
     */
    private final double subGainMax;

    private final double subGainStep;

    /**
     * The table of the cosines of {@code pi k / (4 N)}, for k up to {@code 2 N}.
     */
    private final double[] cosines;

    /**
     * The subbands of the Bark-scale envelope of the base layer.
     */
    private final int[] barkBase;

    /**
     * The subbands of the Bark-scale envelope of each channel of a scalable layer in the current frame.
     */
    private final int[][] barkScalable;

    /**
     * The positions of the periodic peak components in {@link #periodic}, in the order of the code vectors.
     */
    private final int[] periodicOrder;

    /**
     * The fields of the current frame.
     */
    private boolean bandLimit;

    private boolean periodicPresent;

    private boolean postProcess;

    private final int[] shift = new int[2];

    private final int[] bandHigh = new int[2];

    private final int[] bandLow = new int[2];

    private final int[] periodicShapes;

    private final int[] periodicBase = new int[2];

    private final int[] periodicGain = new int[2];

    private final int[] gains = new int[2 * (SHORT_WINDOWS + 1)];

    private final int[][] lsp = new int[2][LSP_INDICES];

    private final int[] envelope = new int[2 * ENVELOPE_DIVISIONS];

    private final int[] envelopeAlpha = new int[2];

    private int[] shapes = new int[256];

    private int shapeBits;

    /**
     * The LSP indices and envelope indices of the previous frame, which start at zero.
     */
    private final int[][] previousLsp = new int[2][LSP_INDICES];

    private final int[] previousEnvelope = new int[2 * ENVELOPE_DIVISIONS];

    /**
     * The active band of each channel in the current frame, of the base layer or of the selected {@code fb_shift} of a
     * scalable layer, as ratios of the Nyquist frequency.
     */
    private final double[] bandBottom = new double[2];

    private final double[] bandTop = new double[2];

    /**
     * The active band of a scalable layer for each value of {@code fb_shift}.
     */
    private final double[] activeBottom = new double[4];

    private final double[] activeTop = new double[4];

    /**
     * The working spectra, laid out channel after channel and window after window.
     */
    private final double[] flat;

    private final double[] flatScratch;

    private final double[] spectrum;

    private final double[] bark;

    private final double[] envelopeValues = new double[BARK_BANDS];

    private final double[] envelopePrevious = new double[BARK_BANDS];

    private final double[] gain = new double[2 * SHORT_WINDOWS];

    private final double[] lpc;

    private final double[] lpcBand;

    private final double[] periodicLines;

    private final double[] periodic = new double[2 * PERIODIC_ELEMENTS];

    private final double[] periodicGains = new double[2];

    private final double[] lspValues = new double[TwinVqTables.LSP_ORDER + 1];

    private final double[] lspRaw = new double[TwinVqTables.LSP_ORDER];

    private final double[] lspGain = new double[TwinVqTables.LSP_ORDER];

    private final double[] lspPrediction = new double[TwinVqTables.LSP_ORDER];

    private final double[] lspResult = new double[TwinVqTables.LSP_ORDER + 1];

    private final double[] cosLsp = new double[TwinVqTables.LSP_ORDER];

    /**
     * Creates a layer.
     *
     * @param scalable whether this is a scalable layer rather than the base layer
     * @param channels the number of channels
     * @param frameLength the number of samples of a frame
     * @param sampleRate the sampling frequency
     * @param cosines the table of the cosines of {@code pi k / (4 N)}, for k up to {@code 2 N}
     */
    TwinVqLayer(boolean scalable, int channels, int frameLength, int sampleRate, double[] cosines) {
        this.scalable = scalable;
        this.channels = channels;
        this.frameLength = frameLength;
        this.rateKhz = sampleRate / 1000;
        this.cosines = cosines;
        this.lspCode = scalable ? TwinVqTables.LSP_SCALABLE : TwinVqTables.LSP_CORE;
        this.shapeLong = scalable ? TwinVqTables.SHAPE_SCALABLE_LONG : TwinVqTables.SHAPE_CORE_LONG;
        this.shapeShort = scalable ? TwinVqTables.SHAPE_SCALABLE_SHORT : TwinVqTables.SHAPE_CORE_SHORT;
        this.shapeLongLength = scalable ? 28 : 20;
        this.shapeShortLength = scalable ? 24 : 17;
        this.gainBits = scalable ? 8 : 9;
        this.gainMax = scalable ? 8000.0 : 16000.0;
        this.gainStep = gainMax / ((1 << gainBits) - 1);
        this.subGainMax = scalable ? 6000.0 : 4700.0;
        this.subGainStep = subGainMax / ((1 << SUB_GAIN_BITS) - 1);
        var table = sampleRate <= 16000 ? BARK_16 : BARK_24;
        this.barkBase = Arrays.copyOf(table, table.length);
        barkBase[BARK_BANDS - 1] = frameLength;
        this.barkScalable = new int[2][BARK_BANDS];
        var size = channels * frameLength;
        this.flat = new double[size];
        this.flatScratch = new double[size];
        this.spectrum = new double[size];
        this.bark = new double[size];
        this.lpc = new double[size];
        this.lpcBand = new double[size];
        this.periodicLines = new double[size];
        var divisions = PERIODIC_DIVISIONS * channels;
        this.periodicShapes = new int[2 * divisions];
        this.periodicOrder = new int[PERIODIC_ELEMENTS * channels];
        var length = PERIODIC_ELEMENTS * channels / divisions;
        var serial = 0;
        for (var division = 0; division < divisions; division++) {
            for (var i = 0; i < length; i++) {
                int position;
                if (channels == 2 && i < length - 1) {
                    position = (division + i) % divisions + divisions * i;
                } else {
                    position = division + divisions * i;
                }
                periodicOrder[serial++] = position / channels + position % channels * PERIODIC_ELEMENTS;
            }
        }
    }

    /**
     * Returns the fields a scalable layer reads besides its header and its shape code, or the base layer besides its
     * header, its shape code and its flags.
     *
     * @param eightShort whether the frame is an eight short sequence
     * @return the number of bits
     */
    private int normalizationBits(boolean eightShort) {
        var gainTotal = eightShort ? gainBits + SUB_GAIN_BITS * SHORT_WINDOWS : gainBits;
        return (LSP_BITS + gainTotal + (eightShort ? 0 : ENVELOPE_TOTAL_BITS)) * channels;
    }

    /**
     * Reads the flags at the start of the {@code vq_single_element} of the base layer, and returns the bits of the
     * side information of the element, those of the shape code excluded.
     *
     * @param reader the bit source, at the start of the element
     * @param eightShort whether the frame is an eight short sequence
     * @return the number of bits, the flags included
     */
    int readBaseFlags(AacBitReader reader, boolean eightShort) {
        bandLimit = reader.readFlag();
        periodicPresent = false;
        postProcess = false;
        var bits = normalizationBits(eightShort) + 1;
        if (!eightShort) {
            periodicPresent = reader.readFlag();
            postProcess = reader.readFlag();
            bits += 2;
            if (periodicPresent) {
                bits += PERIODIC_TOTAL_BITS * channels;
            }
        }
        if (bandLimit) {
            bits += BAND_LIMIT_BITS * channels;
        }
        return bits;
    }

    /**
     * {@return the bits of the side information of the {@code vq_single_element} of a scalable layer, those of the
     * shape code excluded}
     *
     * @param eightShort whether the frame is an eight short sequence
     */
    int scalableSideBits(boolean eightShort) {
        return normalizationBits(eightShort) + 2 * channels;
    }

    /**
     * Reads the fields of the base layer after its flags up to the periodic peak components.
     *
     * @param reader the bit source
     */
    void readBaseSide(AacBitReader reader) {
        if (bandLimit) {
            for (var channel = 0; channel < channels; channel++) {
                bandHigh[channel] = reader.read(2);
                bandLow[channel] = reader.read(1);
            }
        }
        if (periodicPresent) {
            var divisions = PERIODIC_DIVISIONS * channels;
            for (var division = 0; division < divisions; division++) {
                periodicShapes[division] = reader.read(PERIODIC_SHAPE_BITS + 1);
                periodicShapes[division + divisions] = reader.read(PERIODIC_SHAPE_BITS + 1);
            }
            for (var channel = 0; channel < channels; channel++) {
                periodicBase[channel] = reader.read(PERIODIC_BASE_BITS);
                periodicGain[channel] = reader.read(PERIODIC_GAIN_BITS);
            }
        }
    }

    /**
     * Reads the {@code fb_shift} fields at the start of the {@code vq_single_element} of a scalable layer.
     *
     * @param reader the bit source
     */
    void readScalableShift(AacBitReader reader) {
        for (var channel = 0; channel < channels; channel++) {
            shift[channel] = reader.read(2);
        }
    }

    /**
     * Reads the shape code indices.
     *
     * @param reader the bit source
     * @param bits the number of bits of the shape code
     * @throws MediaException.Malformed if the frame has too few bits or more indices than spectral lines
     */
    private void readShapes(AacBitReader reader, int bits) {
        if (bits <= 0) {
            throw new MediaException.Malformed("TwinVQ frame with no room for its shape code");
        }
        var divisions = (bits + MAX_BITS * 2 - 1) / (MAX_BITS * 2);
        if (divisions > channels * frameLength) {
            throw new MediaException.Malformed("TwinVQ frame with " + divisions + " interleaved vectors");
        }
        if (shapes.length < 2 * divisions) {
            shapes = new int[2 * divisions];
        }
        for (var division = 0; division < divisions; division++) {
            var total = (bits + divisions - 1 - division) / divisions;
            shapes[division] = reader.read((total + 1) / 2);
            shapes[division + divisions] = reader.read(total / 2);
        }
        shapeBits = bits;
    }

    /**
     * Reads the envelope indices and their prediction switches.
     *
     * @param reader the bit source
     * @param eightShort whether the frame is an eight short sequence, which has no envelope
     */
    private void readEnvelope(AacBitReader reader, boolean eightShort) {
        if (eightShort) {
            return;
        }
        for (var i = 0; i < ENVELOPE_DIVISIONS * channels; i++) {
            envelope[i] = reader.read(ENVELOPE_BITS);
        }
        for (var channel = 0; channel < channels; channel++) {
            envelopeAlpha[channel] = reader.read(1);
        }
    }

    /**
     * Reads the gain indices.
     *
     * @param reader the bit source
     * @param eightShort whether the frame is an eight short sequence, which adds the subframe gains
     */
    private void readGains(AacBitReader reader, boolean eightShort) {
        if (!eightShort) {
            for (var channel = 0; channel < channels; channel++) {
                gains[channel] = reader.read(gainBits);
            }
            return;
        }
        for (var channel = 0; channel < channels; channel++) {
            var top = (SHORT_WINDOWS + 1) * channel;
            gains[top] = reader.read(gainBits);
            for (var window = 0; window < SHORT_WINDOWS; window++) {
                gains[top + window + 1] = reader.read(SUB_GAIN_BITS);
            }
        }
    }

    /**
     * Reads the LSP indices.
     *
     * @param reader the bit source
     */
    private void readLsp(AacBitReader reader) {
        for (var channel = 0; channel < channels; channel++) {
            var indices = lsp[channel];
            indices[0] = reader.read(1);
            indices[1] = reader.read(6);
            for (var split = 0; split < 3; split++) {
                indices[split + 2] = reader.read(4);
            }
        }
    }

    /**
     * Reads the fields of the TwinVQ syntax: the shape code, the envelope, the gains and the LSP indices.
     *
     * @param reader the bit source
     * @param eightShort whether the frame is an eight short sequence
     * @param bits the number of bits of the shape code
     */
    void readPlain(AacBitReader reader, boolean eightShort, int bits) {
        readShapes(reader, bits);
        readEnvelope(reader, eightShort);
        readGains(reader, eightShort);
        readLsp(reader);
    }

    /**
     * Reads the fields of category 1 or 3 of the ER TwinVQ syntax: the gains, the LSP indices and the envelope.
     *
     * @param reader the bit source
     * @param eightShort whether the frame is an eight short sequence
     */
    void readErrorResilientSide(AacBitReader reader, boolean eightShort) {
        readGains(reader, eightShort);
        readLsp(reader);
        readEnvelope(reader, eightShort);
    }

    /**
     * Reads the fields of category 2 or 4 of the ER TwinVQ syntax: the shape code.
     *
     * @param reader the bit source
     * @param bits the number of bits of the shape code
     */
    void readErrorResilientShapes(AacBitReader reader, int bits) {
        readShapes(reader, bits);
    }

    /**
     * Sets the active band of the base layer.
     *
     * @param upper the upper limit of the band, as a ratio of the Nyquist frequency ({@code qsample})
     */
    void setBaseBand(double upper) {
        for (var channel = 0; channel < channels; channel++) {
            bandBottom[channel] = 0;
            bandTop[channel] = upper;
        }
    }

    /**
     * Sets the active bands {@code fb_shift} selects from (subclause 4.6.5.4.4).
     *
     * @param qsample the width of a band, as a ratio of the Nyquist frequency
     * @param bias the distance between two bands
     */
    void setScalableBands(double qsample, double bias) {
        for (var i = 0; i < 4; i++) {
            activeBottom[i] = i * bias;
            activeTop[i] = i * bias + qsample;
        }
    }

    /**
     * {@return the upper limit of the highest active band of a scalable layer}
     */
    double highestTop() {
        return activeTop[3];
    }

    /**
     * Reconstructs the spectrum of the current frame of the base layer.
     *
     * @param eightShort whether the frame is an eight short sequence
     * @param history whether the LSP quantizer predicts from the previous frame
     * @return the spectrum, channel after channel, window after window, overwritten by the next frame
     */
    double[] reconstructBase(boolean eightShort, boolean history) {
        var windows = eightShort ? SHORT_WINDOWS : 1;
        var length = frameLength / windows;
        requantizeBase(eightShort);
        if (periodicPresent) {
            decodePeriodic();
        }
        if (eightShort) {
            Arrays.fill(bark, 1.0);
        } else {
            decodeEnvelope(length);
        }
        decodeGains(windows);
        decodeLpc(length, history);
        var upper = bandTop[0];
        var lines = (int) (((float) upper - (float) bandBottom[0]) * (float) length);
        var scale = (int) ((upper - bandBottom[0]) * 16384.0);
        if (scale <= 0) {
            throw new MediaException.Malformed("TwinVQ frame with an empty active band");
        }
        scale = 16384 * 16384 / scale;
        for (var channel = 0; channel < channels; channel++) {
            var top = channel * length;
            Arrays.fill(lpcBand, top, top + length, 0);
            for (var line = 0; line < lines; line++) {
                lpcBand[line + top] = lpc[Math.min(scale * line / 16384, length - 1) + top];
            }
        }
        denormalize(length, windows, periodicPresent);
        for (var channel = 0; channel < channels; channel++) {
            double high;
            double low;
            if (bandLimit) {
                var ratio = (float) bandHigh[channel] / (float) BAND_LIMIT_STEPS;
                ratio *= (float) (1.0 - CUT_HIGH);
                ratio = (float) (1.0 - ratio);
                ratio *= (float) upper;
                high = ratio;
                low = (float) bandLow[channel] * (float) CUT_LOW + (float) bandBottom[channel];
            } else {
                high = upper;
                low = bandBottom[channel];
            }
            limitBand(channel, length, windows, low, high);
        }
        return spectrum;
    }

    /**
     * Reconstructs the spectrum of the current frame of a scalable layer.
     *
     * @param eightShort whether the frame is an eight short sequence
     * @param history whether the LSP quantizer predicts from the previous frame
     * @return the spectrum, channel after channel, window after window, overwritten by the next frame
     */
    double[] reconstructScalable(boolean eightShort, boolean history) {
        var windows = eightShort ? SHORT_WINDOWS : 1;
        var length = frameLength / windows;
        for (var channel = 0; channel < channels; channel++) {
            bandBottom[channel] = activeBottom[shift[channel]];
            bandTop[channel] = activeTop[shift[channel]];
        }
        requantizeScalable(eightShort, windows, length);
        Arrays.fill(bark, 1.0);
        if (!eightShort) {
            setScalableBark();
            decodeEnvelopeScalable(length);
        }
        decodeGains(windows);
        decodeLpc(length, history);
        for (var channel = 0; channel < channels; channel++) {
            var upper = (int) (bandTop[channel] * 16384.0);
            var lower = (int) (bandBottom[channel] * 16384.0);
            var first = length * lower / 16384;
            var lines = length * (upper - lower) / 16384;
            var top = channel * length;
            Arrays.fill(lpcBand, top, top + length, 0);
            if (upper > lower) {
                var scale = 16384 * 16384 / (upper - lower);
                for (var line = 0; line < lines && line + first < length; line++) {
                    lpcBand[line + top + first] = lpc[Math.min(line * scale / 16384, length - 1) + top];
                }
            }
        }
        denormalize(length, windows, false);
        for (var channel = 0; channel < channels; channel++) {
            limitBand(channel, length, windows, bandBottom[channel], bandTop[channel]);
        }
        return spectrum;
    }

    /**
     * Rebuilds the flattened coefficients of the base layer from the shape code indices (subclauses 4.6.5.4.3 and
     * 4.6.5.4.4).
     *
     * @param eightShort whether the frame is an eight short sequence
     */
    private void requantizeBase(boolean eightShort) {
        var subframes = channels * (eightShort ? SHORT_WINDOWS : 1);
        var length = frameLength * channels / subframes;
        var lines = (int) ((float) bandTop[0] * (float) length);
        var size = lines * subframes;
        Arrays.fill(flat, 0);
        expand(eightShort, subframes, size, flatScratch);
        for (var subframe = 0; subframe < subframes; subframe++) {
            System.arraycopy(flatScratch, lines * subframe, flat, length * subframe, lines);
        }
    }

    /**
     * Rebuilds the flattened coefficients of a scalable layer from the shape code indices, in the active band of each
     * channel.
     *
     * @param eightShort whether the frame is an eight short sequence
     * @param windows the number of windows of the frame
     * @param length the number of lines of a window
     */
    private void requantizeScalable(boolean eightShort, int windows, int length) {
        var subframes = channels * windows;
        var first = (int) (((float) bandTop[0] - (float) bandBottom[0]) * (float) length);
        expand(eightShort, subframes, first * subframes, flatScratch);
        Arrays.fill(flat, 0);
        for (var subframe = 0; subframe < subframes; subframe++) {
            var channel = subframe * length / frameLength;
            var lines = (int) (((float) bandTop[channel] - (float) bandBottom[channel]) * (float) length);
            var bias = (int) ((float) bandBottom[channel] * (float) length);
            var count = Math.min(lines, Math.min(length - bias, flatScratch.length - subframe * lines));
            if (count > 0) {
                System.arraycopy(flatScratch, subframe * lines, flat, subframe * length + bias, count);
            }
        }
    }

    /**
     * Expands the code vectors of the shape code indices into interleaved coefficients.
     *
     * @param eightShort whether the frame is an eight short sequence
     * @param subframes the number of subframes of the frame, over all channels
     * @param size the number of coefficients the indices code
     * @param target receives the coefficients, subframe after subframe
     */
    private void expand(boolean eightShort, int subframes, int size, double[] target) {
        var book0 = eightShort ? shapeShort[0] : shapeLong[0];
        var book1 = eightShort ? shapeShort[1] : shapeLong[1];
        var vectorLength = eightShort ? shapeShortLength : shapeLongLength;
        var divisions = (shapeBits + MAX_BITS * 2 - 1) / (MAX_BITS * 2);
        var mask = (1 << SHAPE_BITS) - 1;
        var firstLength = (size + divisions - 1) / divisions;
        var swapped = (divisions % subframes == 0 && subframes > 1)
                || ((divisions & 1) == 0 && (subframes & 1) == 0);
        var stride = size / subframes;
        for (var division = 0; division < divisions; division++) {
            var index0 = shapes[division];
            var index1 = shapes[division + divisions];
            var base0 = (index0 & mask) * vectorLength;
            var base1 = (index1 & mask) * vectorLength;
            var polarity0 = 1 - 2 * (index0 >> SHAPE_BITS & 1);
            var polarity1 = 1 - 2 * (index1 >> SHAPE_BITS & 1);
            var count = (size + divisions - 1 - division) / divisions;
            for (var element = 0; element < count; element++) {
                var position = element < firstLength - 1 && swapped
                        ? (division + element) % divisions + element * divisions
                        : division + element * divisions;
                var line = position / subframes + position % subframes * stride;
                var value0 = element < vectorLength ? book0[base0 + element] : 0;
                var value1 = element < vectorLength ? book1[base1 + element] : 0;
                target[line] = (polarity0 * value0 + polarity1 * value1) * 0.5;
            }
        }
    }

    /**
     * Decodes the periodic peak components of a long frame into {@link #periodicLines} and {@link #periodicGains}
     * (subclause 4.6.10.3.3).
     */
    private void decodePeriodic() {
        var divisions = PERIODIC_DIVISIONS * channels;
        var length = PERIODIC_ELEMENTS * channels / divisions;
        var mask = (1 << PERIODIC_SHAPE_BITS) - 1;
        var serial = 0;
        for (var division = 0; division < divisions; division++) {
            var index0 = periodicShapes[division];
            var index1 = periodicShapes[division + divisions];
            var base0 = (index0 & mask) * TwinVqTables.PERIODIC_LENGTH;
            var base1 = ((index1 & mask) + (1 << PERIODIC_SHAPE_BITS)) * TwinVqTables.PERIODIC_LENGTH;
            var polarity0 = 1 - 2 * (index0 >> PERIODIC_SHAPE_BITS & 1);
            var polarity1 = 1 - 2 * (index1 >> PERIODIC_SHAPE_BITS & 1);
            for (var i = 0; i < length; i++) {
                periodic[periodicOrder[serial++]] = (polarity0 * TwinVqTables.PERIODIC[base0 + i]
                        + polarity1 * TwinVqTables.PERIODIC[base1 + i]) * 0.5;
            }
        }
        var upper = (int) (bandTop[0] * 16384.0 + 0.1);
        var lowest = (int) ((float) frameLength / (float) rateKhz * 0.2 * 1024.0 + 0.5);
        var bandwidth = 8;
        if (rateKhz == 8) {
            bandwidth = 3;
        }
        if (rateKhz >= 11) {
            bandwidth = 4;
        }
        if (rateKhz >= 22) {
            bandwidth = 8;
        }
        for (var channel = 0; channel < channels; channel++) {
            var value = periodicGain[channel] * PERIODIC_GAIN_STEP + PERIODIC_GAIN_STEP / 2.0;
            periodicGains[channel] = muLawInverse(value, PERIODIC_GAIN_MAX, PERIODIC_GAIN_MU);
            var top = channel * frameLength;
            var source = channel * PERIODIC_ELEMENTS;
            // the products wrap around as the 32-bit integers of the reference decoder do
            var pitch = (int) (StrictMath.pow(1.009792, periodicBase[channel]) * 4096.0 + 0.5);
            pitch *= lowest;
            pitch /= 256;
            int count;
            if (bandwidth * upper < 32768) {
                var scaled = pitch * 16384 / upper;
                scaled = PERIODIC_ELEMENTS * scaled;
                scaled /= frameLength;
                count = scaled / 16384;
            } else {
                var scaled = pitch * bandwidth;
                scaled = PERIODIC_ELEMENTS * scaled;
                scaled /= frameLength;
                count = scaled / 32768;
            }
            Arrays.fill(periodicLines, top, top + frameLength, 0);
            var used = 0;
            for (var i = 0; i < count / 2 && i < frameLength && used < PERIODIC_ELEMENTS; i++) {
                periodicLines[i + top] = periodic[i + source];
                used++;
            }
            for (var harmonic = 0; harmonic < PERIODIC_ELEMENTS && used < PERIODIC_ELEMENTS; harmonic++) {
                var center = (pitch * (harmonic + 1) + 8192) / 16384;
                if (center + (count - 1) / 2 + 1 >= frameLength) {
                    continue;
                }
                for (var offset = -count / 2; offset < (count - 1) / 2 + 1; offset++) {
                    var line = center + offset;
                    if (line >= 0 && line < frameLength) {
                        periodicLines[line + top] = periodic[used + source];
                    }
                    used++;
                    if (used >= PERIODIC_ELEMENTS) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Expands envelope indices into the values of the Bark-scale subbands.
     *
     * @param indices the code book indices
     * @param offset the index of the first division
     * @param target receives the values
     */
    private static void expandEnvelope(int[] indices, int offset, double[] target) {
        for (var division = 0; division < ENVELOPE_DIVISIONS; division++) {
            var base = indices[offset + division] * TwinVqTables.ENVELOPE_LENGTH;
            for (var i = 0; i < TwinVqTables.ENVELOPE_LENGTH; i++) {
                target[i * ENVELOPE_DIVISIONS + division] = TwinVqTables.ENVELOPE[base + i];
            }
        }
    }

    /**
     * Decodes the Bark-scale envelope of the base layer into {@link #bark} (subclause 4.6.10.3.4), and remembers the
     * indices.
     *
     * @param length the number of lines of a window
     */
    private void decodeEnvelope(int length) {
        for (var channel = 0; channel < channels; channel++) {
            var top = channel * length;
            expandEnvelope(previousEnvelope, channel * ENVELOPE_DIVISIONS, envelopePrevious);
            expandEnvelope(envelope, channel * ENVELOPE_DIVISIONS, envelopeValues);
            var alpha = envelopeAlpha[channel] * ENVELOPE_ALPHA_STEP;
            var line = 0;
            for (var band = 0; band < BARK_BANDS; band++) {
                var value = Math.max(envelopeValues[band] + alpha * envelopePrevious[band] + 1.0, ENVELOPE_FLOOR);
                if (postProcess && value < 1.0) {
                    value *= value;
                }
                var end = Math.min(barkBase[band], length);
                for (; line < end; line++) {
                    bark[line + top] = value;
                }
            }
        }
        System.arraycopy(envelope, 0, previousEnvelope, 0, ENVELOPE_DIVISIONS * channels);
    }

    /**
     * Computes the subbands of the Bark-scale envelope of each channel of a scalable layer from its active band
     * (subclause 4.6.10.3.4.2).
     */
    private void setScalableBark() {
        for (var channel = 0; channel < channels; channel++) {
            var low = (int) (bandBottom[channel] * 16384.0);
            var high = (int) (bandTop[channel] * 16384.0);
            var width = frameLength * (high - low);
            var average = width / BARK_BANDS;
            var slope = width / (BARK_BANDS * BARK_BANDS);
            var start = low * frameLength;
            var table = barkScalable[channel];
            table[BARK_BANDS - 1] = start / 16384 + width / 16384;
            for (var band = 0; band < BARK_BANDS - 1; band++) {
                var position = band + 1;
                var value = position * position * slope / 2 + 8192 + position * average / 2;
                table[band] = start / 16384 + value / 16384;
            }
        }
    }

    /**
     * Decodes the Bark-scale envelope of a scalable layer into {@link #bark}, over the active band of each channel, and
     * remembers the indices.
     *
     * @param length the number of lines of a window
     */
    private void decodeEnvelopeScalable(int length) {
        for (var channel = 0; channel < channels; channel++) {
            var top = channel * length;
            expandEnvelope(previousEnvelope, channel * ENVELOPE_DIVISIONS, envelopePrevious);
            expandEnvelope(envelope, channel * ENVELOPE_DIVISIONS, envelopeValues);
            var alpha = envelopeAlpha[channel] * ENVELOPE_ALPHA_STEP;
            var table = barkScalable[channel];
            var line = (int) (length * bandBottom[channel]);
            for (var band = 0; band < BARK_BANDS; band++) {
                var value = Math.max(envelopeValues[band] + alpha * envelopePrevious[band] + 1.0, ENVELOPE_FLOOR);
                var end = Math.min(table[band], length);
                for (; line < end; line++) {
                    bark[line + top] = value;
                }
            }
        }
        System.arraycopy(envelope, 0, previousEnvelope, 0, ENVELOPE_DIVISIONS * channels);
    }

    /**
     * {@return the inverse of the mu-law quantizer}
     *
     * @param value the quantized value
     * @param max the largest amplitude
     * @param mu the factor of the quantizer
     */
    private static double muLawInverse(double value, double max, double mu) {
        var clamped = Math.min(value, max);
        return max * (StrictMath.pow(10.0, clamped * StrictMath.log10(1.0 + mu) / max) - 1.0) / mu;
    }

    /**
     * Decodes the gain of each window of each channel into {@link #gain} (subclause 4.6.10.3.2).
     *
     * @param windows the number of windows of the frame
     */
    private void decodeGains(int windows) {
        if (windows == 1) {
            for (var channel = 0; channel < channels; channel++) {
                gain[channel] = muLawInverse(gains[channel] * gainStep + gainStep / 2.0, gainMax, MU) / AMPLITUDE_NORM;
            }
            return;
        }
        for (var channel = 0; channel < channels; channel++) {
            var top = channel * (windows + 1);
            var global = muLawInverse(gains[top] * gainStep + gainStep / 2.0, gainMax, MU) / AMPLITUDE_NORM;
            for (var window = 0; window < windows; window++) {
                var value = gains[top + window + 1] * subGainStep + subGainStep / 2.0;
                gain[window + channel * windows] = global * muLawInverse(value, subGainMax, MU) / AMPLITUDE_NORM;
            }
        }
    }

    /**
     * Rebuilds the LSP coefficients of one set of indices.
     *
     * @param indices the LSP indices
     * @param values receives the coefficients of the prediction residual, from index 1; used as the working vector
     * @param raw receives the quantized vector before prediction, or {@code null}
     */
    private void rebuildLsp(int[] indices, double[] values, double[] raw) {
        var first = indices[1] * TwinVqTables.LSP_ORDER;
        for (var split = 0; split < 3; split++) {
            var second = (TwinVqTables.LSP_SECOND_STAGE + indices[split + 2]) * TwinVqTables.LSP_ORDER;
            for (var j = LSP_SPLITS[split]; j < LSP_SPLITS[split + 1]; j++) {
                values[j + 1] = lspCode[first + j] + lspCode[second + j];
            }
        }
        var order = TwinVqTables.LSP_ORDER;
        if (values[1] < LOW_LIMIT) {
            values[1] += (LOW_LIMIT - values[1]) * 1.2;
        }
        if (values[order] > HIGH_LIMIT) {
            values[order] -= (values[order] - HIGH_LIMIT) * 1.2;
        }
        for (var j = 1; j < order; j++) {
            if (values[j] > values[j + 1] - MIN_GAP) {
                var difference = values[j] - values[j + 1];
                values[j] -= (difference + MIN_GAP) / 2.0;
                values[j + 1] += (difference + MIN_GAP) / 2.0;
            }
        }
        if (raw != null) {
            System.arraycopy(values, 1, raw, 0, order);
        }
    }

    /**
     * Decodes the LSP coefficients of a channel through the MA prediction into {@link #lspResult} (subclause
     * 4.6.10.3.5).
     *
     * @param channel the channel to decode
     * @param history whether the quantizer predicts from the previous frame
     */
    private void decodeLsp(int channel, boolean history) {
        var order = TwinVqTables.LSP_ORDER;
        var mode = lsp[channel][0];
        var row = (TwinVqTables.LSP_PREDICTION + mode) * order;
        for (var j = 0; j < order; j++) {
            lspGain[j] = history ? 1.0 - lspCode[row + j] : 1.0;
            lspPrediction[j] = 0;
        }
        if (history) {
            rebuildLsp(previousLsp[channel], lspValues, lspRaw);
            for (var j = 0; j < order; j++) {
                lspPrediction[j] = lspCode[row + j] * lspRaw[j];
            }
        }
        rebuildLsp(lsp[channel], lspValues, null);
        for (var j = 0; j < order; j++) {
            lspValues[j + 1] = lspValues[j + 1] * lspGain[j] + lspPrediction[j];
        }
        for (var i = 1; i < order; i++) {
            if (lspValues[i] > lspValues[i + 1] - MIN_GAP) {
                var difference = lspValues[i] - lspValues[i + 1];
                lspValues[i] -= (difference + MIN_GAP) / 2.0;
                lspValues[i + 1] += (difference + MIN_GAP) / 2.0;
            }
        }
        for (var i = 1; i < order; i++) {
            if (lspValues[i] > lspValues[i + 1] - MIN_GAP * 0.95) {
                var difference = lspValues[i] - lspValues[i + 1];
                lspValues[i] -= (difference + MIN_GAP * 0.95) / 2.0;
                lspValues[i + 1] += (difference + MIN_GAP * 0.95) / 2.0;
            }
        }
        for (var pass = 0; pass < order - 1; pass++) {
            var swapped = false;
            for (var j = 1; j < order; j++) {
                if (lspValues[j] > lspValues[j + 1]) {
                    swapped = true;
                    var value = lspValues[j];
                    lspValues[j] = lspValues[j + 1];
                    lspValues[j + 1] = value;
                }
            }
            if (!swapped) {
                break;
            }
        }
        System.arraycopy(lspValues, 0, lspResult, 0, order + 1);
    }

    /**
     * Decodes the LPC envelope of each channel into {@link #lpc}, and remembers the indices.
     *
     * @param length the number of lines of a window
     * @param history whether the LSP quantizer predicts from the previous frame
     */
    private void decodeLpc(int length, boolean history) {
        for (var channel = 0; channel < channels; channel++) {
            decodeLsp(channel, history);
            lpcEnvelope(length, channel * length);
        }
        for (var channel = 0; channel < channels; channel++) {
            System.arraycopy(lsp[channel], 0, previousLsp[channel], 0, LSP_INDICES);
        }
    }

    /**
     * {@return the amplitude of the LPC envelope at a frequency}
     *
     * @param cosine the cosine of the frequency
     */
    private double weight(double cosine) {
        var doubled = 2.0 * cosine;
        var a = 1.0;
        var b = 1.0;
        for (var j = 0; j < TwinVqTables.LSP_ORDER; j += 2) {
            a *= doubled - cosLsp[j];
            b *= doubled - cosLsp[j + 1];
        }
        return 1.0 / (a * a * (1.0 + cosine) + b * b * (1.0 - cosine));
    }

    /**
     * Transforms the LSP coefficients of {@link #lspResult} into the amplitude spectrum of the LPC envelope (subclause
     * 4.6.10.3.5.5): a long window computes it sparsely and interpolates where it is smooth.
     *
     * @param length the number of lines of a window
     * @param base the first line of the channel in {@link #lpc}
     */
    private void lpcEnvelope(int length, int base) {
        var magnify = frameLength / length;
        int low;
        int high;
        if (magnify == 1) {
            low = 4;
            high = 8;
        } else if (magnify > 1 && magnify <= 4) {
            low = 2;
            high = 4;
        } else {
            low = 1;
            high = 1;
        }
        for (var j = 0; j < TwinVqTables.LSP_ORDER; j++) {
            cosLsp[j] = 2.0 * StrictMath.cos(lspResult[j + 1]);
        }
        var w = lpc;
        var half = length / 2;
        int i;
        for (i = 0; i < half; i += low) {
            w[base + i] = weight(cosines[(i * 4 + 2) * magnify]);
        }
        for (i = half; i < length; i += high) {
            w[base + i] = weight(-cosines[frameLength * 4 - (i * 4 + 2) * magnify]);
        }
        if (high == 1) {
            return;
        }
        var endpoint = i - high;
        for (i = low; i < half; i += low) {
            if (w[base + i] * 1.95 > w[base + low + i] + w[base + i - low] && w[base + i - low] > w[base + i + low]) {
                w[base + i - low / 2] = weight(cosines[((i - low / 2) * 4 + 2) * magnify]);
                var step = 1;
                for (var j = i - low + 1; j < i - low / 2; j++) {
                    var delta = (double) (step + 1) / low;
                    w[base + j] = w[base + i - low] * (1.0 - delta) + w[base + i - low / 2] * delta;
                    w[base + j + low / 2] = w[base + i - low / 2] * (1.0 - delta) + w[base + i] * delta;
                    step += 2;
                }
            } else {
                var step = 0;
                for (var j = i - low + 1; j < i; j++) {
                    var delta = (double) (step + 1) / low;
                    w[base + j] = w[base + i - low] * (1.0 - delta) + w[base + i] * delta;
                    step++;
                }
            }
        }
        var step = 0;
        for (var j = half - low + 1; j < half; j++) {
            var delta = (double) (step + 1) / low;
            w[base + j] = w[base + half - low] * (1.0 - delta) + w[base + half] * delta;
            step++;
        }
        for (i = half + high; i < length - high; i += high) {
            if (w[base + i] * 1.95 > w[base + high + i] + w[base + i - high]
                    && w[base + i - high] > w[base + i + high]) {
                w[base + i - low] = weight(-cosines[frameLength * 4 - ((i - low) * 4 + 2) * magnify]);
                step = 1;
                for (var j = i - high + 1; j < i - low; j++) {
                    var delta = (double) (step + 1) / high;
                    w[base + j] = w[base + i - high] * (1.0 - delta) + w[base + i - low] * delta;
                    w[base + j + low] = w[base + i - low] * (1.0 - delta) + w[base + i] * delta;
                    step += 2;
                }
            } else {
                step = 0;
                for (var j = i - high + 1; j < i; j++) {
                    var delta = (double) (step + 1) / high;
                    w[base + j] = w[base + i - high] * (1.0 - delta) + w[base + i] * delta;
                    step++;
                }
            }
        }
        step = 0;
        for (var j = endpoint - high + 1; j < endpoint; j++) {
            var delta = (double) (step + 1) / high;
            w[base + j] = w[base + i - high] * (1.0 - delta) + w[base + i] * delta;
            step++;
        }
        for (i = endpoint + 1; i < length; i++) {
            w[base + i] = w[base + endpoint];
        }
    }

    /**
     * Runs the inverse normalization into {@link #spectrum} (subclause 4.6.10.3.6).
     *
     * @param length the number of lines of a window
     * @param windows the number of windows of the frame
     * @param withPeriodic whether the frame has periodic peak components
     */
    private void denormalize(int length, int windows, boolean withPeriodic) {
        for (var channel = 0; channel < channels; channel++) {
            var top = channel * length * windows;
            var lpcTop = channel * length;
            for (var window = 0; window < windows; window++) {
                var subTop = top + window * length;
                var level = gain[window + channel * windows];
                if (withPeriodic) {
                    var peak = periodicGains[channel];
                    for (var line = 0; line < length; line++) {
                        spectrum[subTop + line] = (level * flat[subTop + line] * bark[subTop + line]
                                + peak * periodicLines[subTop + line]) * lpcBand[line + lpcTop];
                    }
                } else {
                    for (var line = 0; line < length; line++) {
                        spectrum[subTop + line] = level * flat[subTop + line] * bark[subTop + line]
                                * lpcBand[line + lpcTop];
                    }
                }
            }
        }
    }

    /**
     * Clears the lines of each window of a channel of {@link #spectrum} outside a band (subclause 4.6.10.3.7).
     *
     * @param channel the channel to limit
     * @param length the number of lines of a window
     * @param windows the number of windows of the frame
     * @param low the lower limit of the band, as a ratio of the Nyquist frequency
     * @param high the upper limit of the band
     */
    private void limitBand(int channel, int length, int windows, double low, double high) {
        var start = Math.min((int) ((float) low * (float) length), length);
        var width = (int) (((float) high - (float) low) * (float) length);
        var stop = Math.min(start + Math.max(width, 0), length);
        var top = channel * frameLength;
        for (var window = 0; window < windows; window++) {
            var subTop = top + window * length;
            Arrays.fill(spectrum, subTop, subTop + start, 0);
            Arrays.fill(spectrum, subTop + stop, subTop + length, 0);
        }
    }
}
