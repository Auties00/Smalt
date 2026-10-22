package com.github.auties00.smalt.audio.aac.internal;

import java.util.Arrays;

/**
 * Runs the AAC SSR synthesis: four band inverse MDCTs, the gain control tool and the inverse
 * polyphase quadrature filter (ISO/IEC 14496-3 subclause 4.6.12).
 *
 * <p>The 1024 spectral lines of a frame are four PQF bands of 256 lines (32 per band and window in
 * eight-short-window frames). Each band's lines are inverse-transformed with windows of 512 and 64
 * samples, the lines of the odd bands in reverse order; the windowed blocks of bands 1 to 3 are
 * divided by the gain control function of the frame's gain control data, overlapped into 256 band
 * samples per frame, and the four band signals are upsampled by four and combined by the 96-tap
 * IPQF into 1024 output samples, scaled like {@link Synthesis}'s. The windows of a frame sit in its
 * 512-sample band block as the windows of a 1024-line frame sit in its 2048-sample block, short
 * windows starting at sample 112.
 *
 * <p>An instance holds scratch memory and is not thread-safe; the memory of each channel is a
 * {@link State}.
 *
 * @implNote This implementation overlaps an eight-short-window frame with only the first 144
 * samples of the previous frame's overlap. For the window sequence transitions ISO/IEC 14496-3
 * allows, this equals the band signals of subclause 4.6.12.3.3, whose overlap holds no more; for the
 * others it reproduces the reference waveform of the ISO/IEC 14496-26 stream as17, which uses every
 * transition.
 */
public final class SsrSynthesis {
    /**
     * The number of PQF bands.
     */
    public static final int BANDS = 4;

    /**
     * The number of spectral lines of a band in a long window, and of band samples per frame.
     */
    private static final int LINES = 256;

    /**
     * The number of spectral lines of a band in a short window.
     */
    private static final int SHORT_LINES = 32;

    /**
     * The position of the first short window in a band block.
     */
    private static final int SHORT_START = 112;

    /**
     * The number of overlap samples an eight-short-window frame adds.
     */
    private static final int SHORT_OVERLAP = SHORT_START + SHORT_LINES;

    /**
     * The inverse of the 16-bit full scale.
     */
    private static final double OUTPUT_SCALE = 1.0 / 32768.0;

    /**
     * The number of IPQF taps.
     */
    private static final int TAPS = 96;

    /**
     * The number of IPQF taps each output sample uses per band.
     */
    private static final int PHASE_TAPS = TAPS / BANDS;

    /**
     * The number of past band samples the IPQF needs.
     */
    private static final int HISTORY = PHASE_TAPS - 1;

    /**
     * The number of floats a {@link State} holds, for allocation limits.
     */
    public static final int STATE_FLOATS = BANDS * (3 * LINES + HISTORY);

    /**
     * The first 48 IPQF prototype coefficients {@code Q(j)} (ISO/IEC 14496-3 Table 4.164); the other
     * 48 mirror them.
     */
    private static final double[] PROTOTYPE = {
            9.7655291007575512E-05, 1.3809589379038567E-04, 9.8400749256623534E-05, -8.6671544782335723E-05,
            -4.6217998911921346E-04, -1.0211814095158174E-03, -1.6772149340010668E-03, -2.2533338951411081E-03,
            -2.4987888343213967E-03, -2.1390815966761882E-03, -9.5595397454597772E-04, 1.1172111530118943E-03,
            3.9091309127348584E-03, 6.9635703420118673E-03, 9.5595442159478339E-03, 1.0815766540021360E-02,
            9.8770514991715300E-03, 6.1562567291327357E-03, -4.1793946063629710E-04, -9.2128743097707640E-03,
            -1.8830775873369020E-02, -2.7226498457701823E-02, -3.2022840857588906E-02, -3.0996332527754609E-02,
            -2.2656858741499447E-02, -6.8031113858963354E-03, 1.5085400948280744E-02, 3.9750993388272739E-02,
            6.2445363629436743E-02, 7.7622327748721326E-02, 7.9968338496132926E-02, 6.5615493068475583E-02,
            3.3313658300882690E-02, -1.4691563058190206E-02, -7.2307890475334147E-02, -1.2993222541703875E-01,
            -1.7551641029040532E-01, -1.9626543957670528E-01, -1.8073330670215029E-01, -1.2097653136035738E-01,
            -1.4377370758549035E-02, 1.3522730742860303E-01, 3.1737852699301633E-01, 5.1590021798482233E-01,
            7.1080020379761377E-01, 8.8090632488444798E-01, 1.0068321641150089E+00, 1.0737914947736096E+00
    };

    /**
     * The IPQF coefficients by output phase: {@code PHASE_FILTERS[p][band * PHASE_TAPS + i]} is
     * {@code Q_band(p + 4 i)}.
     */
    private static final float[][] PHASE_FILTERS = phaseFilters();

    /**
     * {@code 2^(k / 8)} for {@code k} from -32 to 88, the interpolated gain control levels.
     */
    private static final float[] LEVELS = levels();

    /**
     * The index of {@code 2^0} in {@link #LEVELS}.
     */
    private static final int LEVEL_ZERO = 32;

    /**
     * The long-window band DCT-IV.
     */
    private final Dct4 longDct;

    /**
     * The short-window band DCT-IV.
     */
    private final Dct4 shortDct;

    /**
     * The 512-sample windows, indexed by shape.
     */
    private final float[][] longWindows;

    /**
     * The 64-sample windows, indexed by shape.
     */
    private final float[][] shortWindows;

    /**
     * The composite long-sequence windows, indexed {@code sequence * 4 + previous * 2 + current}
     * and built on first use.
     */
    private final float[][] composites = new float[16][];

    /**
     * The lines of one band and window.
     */
    private final float[] lines = new float[LINES];

    /**
     * The DCT-IV output of one band and window.
     */
    private final float[] transformed = new float[LINES];

    /**
     * The windowed blocks of one band ({@code U}, then {@code T} once gain-controlled): 512 samples,
     * or eight 64-sample blocks.
     */
    private final float[] blocks = new float[2 * LINES];

    /**
     * The band block of one band, the short blocks overlapped in place.
     */
    private final float[] band = new float[2 * LINES];

    /**
     * The fragment modification functions of one band, 256 per window.
     */
    private final float[] fragments = new float[8 * LINES];

    /**
     * The level exponent before the first gain change of every window of one band
     * ({@code log2 ALEV(0)}).
     */
    private final int[] firstLevels = new int[8];

    /**
     * The memory one channel keeps between SSR frames.
     *
     * <p>Instances are not thread-safe.
     */
    public static final class State {
        /**
         * The window shape of the previous frame.
         */
        private int previousShape;

        /**
         * The second half of the previous band block of each band.
         */
        private final float[][] overlap = new float[BANDS][LINES];

        /**
         * The fragment modification function of the previous frame of each band ({@code PFMD}).
         */
        private final float[][] previousFragments = new float[BANDS][LINES];

        /**
         * The band signal of each band: the 23 samples the IPQF used last, then the frame's 256.
         */
        private final float[][] signals = new float[BANDS][HISTORY + LINES];

        /**
         * Creates the memory of a channel that has not decoded any frame.
         */
        public State() {
            for (var fragment : previousFragments) {
                Arrays.fill(fragment, 1);
            }
        }
    }

    /**
     * Prepares the SSR filterbank.
     *
     * @param kernel the kernel running the transforms
     */
    public SsrSynthesis(AacKernel kernel) {
        this.longDct = new Dct4(LINES, OUTPUT_SCALE / LINES, kernel);
        this.shortDct = new Dct4(SHORT_LINES, OUTPUT_SCALE / SHORT_LINES, kernel);
        this.longWindows = new float[][]{Windows.sine(2 * LINES), Windows.kbd(2 * LINES, Windows.LONG_KBD_ALPHA)};
        this.shortWindows = new float[][]{Windows.sine(2 * SHORT_LINES), Windows.kbd(2 * SHORT_LINES, Windows.SHORT_KBD_ALPHA)};
    }

    /**
     * Synthesizes one frame of one channel.
     *
     * @param stream the channel stream of the frame, with TNS applied
     * @param state the channel's memory
     * @param out the output samples, 1024 from {@code offset}
     * @param offset the index of the first output sample
     */
    public void synthesize(ChannelStream stream, State state, float[] out, int offset) {
        var spectrum = stream.spectrum();
        var sequence = stream.windowSequence();
        var shape = stream.windowShape();
        for (var index = 0; index < BANDS; index++) {
            transformBand(spectrum, index, sequence, state.previousShape, shape);
            if (index > 0) {
                controlGain(stream, state.previousFragments[index], index, sequence);
            }
            overlapBand(state, index, sequence);
        }
        combine(state, out, offset);
        state.previousShape = shape;
    }

    /**
     * Inverse-transforms and windows one band into {@link #blocks}.
     *
     * @param spectrum the frame's spectrum
     * @param index the PQF band
     * @param sequence the window sequence
     * @param previousShape the window shape of the previous frame
     * @param shape the window shape of this frame
     */
    private void transformBand(float[] spectrum, int index, int sequence, int previousShape, int shape) {
        if (sequence != ChannelStream.EIGHT_SHORT_SEQUENCE) {
            gather(spectrum, index * LINES, LINES, index);
            longDct.transform(lines, 0, transformed, 0);
            var window = composite(sequence, previousShape, shape);
            unfold(LINES, window, window, 0);
            return;
        }
        for (var window = 0; window < 8; window++) {
            gather(spectrum, window * BANDS * SHORT_LINES + index * SHORT_LINES, SHORT_LINES, index);
            shortDct.transform(lines, 0, transformed, 0);
            unfold(SHORT_LINES, shortWindows[window == 0 ? previousShape : shape], shortWindows[shape],
                    window * 2 * SHORT_LINES);
        }
    }

    /**
     * Copies the lines of one band and window into {@link #lines}, reversed in the odd bands.
     *
     * @param spectrum the frame's spectrum
     * @param start the band's first line
     * @param count the number of lines
     * @param index the PQF band
     */
    private void gather(float[] spectrum, int start, int count, int index) {
        if ((index & 1) == 0) {
            System.arraycopy(spectrum, start, lines, 0, count);
            return;
        }
        for (var i = 0; i < count; i++) {
            lines[i] = spectrum[start + count - 1 - i];
        }
    }

    /**
     * Unfolds the DCT-IV output of {@code m} lines into the {@code 2m}-sample inverse MDCT output,
     * windowed, at {@code blocks[base]}.
     *
     * @param m the number of lines
     * @param rising the window whose first {@code m} samples weight the first half
     * @param falling the window whose last {@code m} samples weight the second half
     * @param base the index of the first output sample in {@link #blocks}
     */
    private void unfold(int m, float[] rising, float[] falling, int base) {
        var h = m >> 1;
        var u = transformed;
        for (var n = 0; n < h; n++) {
            blocks[base + n] = rising[n] * u[h + n];
        }
        for (var n = h; n < m; n++) {
            blocks[base + n] = -rising[n] * u[m + h - 1 - n];
        }
        for (var n = m; n < m + h; n++) {
            blocks[base + n] = -falling[n] * u[m + h - 1 - n];
        }
        for (var n = m + h; n < 2 * m; n++) {
            blocks[base + n] = -falling[n] * u[n - m - h];
        }
    }

    /**
     * Divides the windowed blocks of one band by its gain control function {@code GMF} and updates
     * the band's previous fragment modification function (ISO/IEC 14496-3 subclause 4.6.12.3.2).
     *
     * <p>A previous function shorter than 256 samples is followed by ones.
     *
     * @param stream the channel stream holding the gain control data
     * @param previous the band's previous fragment modification function, updated in place
     * @param index the PQF band, from 1 to 3
     * @param sequence the window sequence
     */
    private void controlGain(ChannelStream stream, float[] previous, int index, int sequence) {
        switch (sequence) {
            case ChannelStream.ONLY_LONG_SEQUENCE -> {
                fragment(stream, index, 0, 256);
                var first = level(firstLevels[0]);
                for (var j = 0; j < 256; j++) {
                    blocks[j] /= first * previous[j];
                    blocks[256 + j] /= fragments[j];
                }
                System.arraycopy(fragments, 0, previous, 0, 256);
            }
            case ChannelStream.LONG_START_SEQUENCE -> {
                fragment(stream, index, 0, 112);
                fragment(stream, index, 1, 32);
                var first0 = level(firstLevels[0]);
                var first1 = level(firstLevels[1]);
                for (var j = 0; j < 256; j++) {
                    blocks[j] /= first0 * first1 * previous[j];
                }
                for (var j = 0; j < 112; j++) {
                    blocks[256 + j] /= first1 * fragments[j];
                }
                for (var j = 0; j < 32; j++) {
                    blocks[368 + j] /= fragments[LINES + j];
                }
                System.arraycopy(fragments, LINES, previous, 0, 32);
                Arrays.fill(previous, 32, 256, 1);
            }
            case ChannelStream.EIGHT_SHORT_SEQUENCE -> {
                for (var window = 0; window < 8; window++) {
                    fragment(stream, index, window, 32);
                }
                for (var window = 0; window < 8; window++) {
                    var base = window * 64;
                    var first = level(firstLevels[window]);
                    for (var j = 0; j < 32; j++) {
                        var before = window == 0 ? previous[j] : fragments[(window - 1) * LINES + j];
                        blocks[base + j] /= first * before;
                        blocks[base + 32 + j] /= fragments[window * LINES + j];
                    }
                }
                System.arraycopy(fragments, 7 * LINES, previous, 0, 32);
                Arrays.fill(previous, 32, 256, 1);
            }
            default -> {
                fragment(stream, index, 0, 112);
                fragment(stream, index, 1, 256);
                var first0 = level(firstLevels[0]);
                var first1 = level(firstLevels[1]);
                for (var j = 0; j < 32; j++) {
                    blocks[112 + j] /= first0 * first1 * previous[j];
                }
                for (var j = 0; j < 112; j++) {
                    blocks[144 + j] /= first1 * fragments[j];
                }
                for (var j = 0; j < 256; j++) {
                    blocks[256 + j] /= fragments[LINES + j];
                }
                System.arraycopy(fragments, LINES, previous, 0, 256);
            }
        }
    }

    /**
     * Computes the fragment modification function {@code FMD} of one band and window into
     * {@link #fragments} at {@code window * 256}, and the window's first level into
     * {@link #firstLevels}.
     *
     * @param stream the channel stream holding the gain control data
     * @param index the PQF band, from 1 to 3
     * @param window the window index
     * @param length the window's length in band samples
     */
    private void fragment(ChannelStream stream, int index, int window, int length) {
        var changes = stream.gainChanges(index, window);
        var base = window * LINES;
        firstLevels[window] = changes == 0 ? 0 : stream.gainLevel(index, window, 0);
        var m = 0;
        var location = 0;
        var level = firstLevels[window];
        var nextLevel = firstLevels[window];
        var nextLocation = changes == 0 ? length : stream.gainLocation(index, window, 0);
        for (var j = 0; j < length; j++) {
            while (j >= nextLocation) {
                m++;
                location = nextLocation;
                level = nextLevel;
                nextLevel = m < changes ? stream.gainLevel(index, window, m) : 0;
                nextLocation = m < changes ? stream.gainLocation(index, window, m) : length;
            }
            var distance = j - location;
            fragments[base + j] = distance <= 7
                    ? LEVELS[LEVEL_ZERO + (8 - distance) * level + distance * nextLevel]
                    : LEVELS[LEVEL_ZERO + 8 * nextLevel];
        }
    }

    /**
     * Returns {@code 2^exponent}.
     *
     * @param exponent the level exponent, from -4 to 11
     * @return the level
     */
    private static float level(int exponent) {
        return LEVELS[LEVEL_ZERO + 8 * exponent];
    }

    /**
     * Places the gain-controlled blocks of one band in its band block and overlaps the block with
     * the previous one into the frame's 256 band samples.
     *
     * @param state the channel's memory
     * @param index the PQF band
     * @param sequence the window sequence
     */
    private void overlapBand(State state, int index, int sequence) {
        var previous = state.overlap[index];
        var signal = state.signals[index];
        var limit = LINES;
        if (sequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            Arrays.fill(band, 0);
            for (var window = 0; window < 8; window++) {
                var target = SHORT_START + window * SHORT_LINES;
                for (var j = 0; j < 2 * SHORT_LINES; j++) {
                    band[target + j] += blocks[window * 2 * SHORT_LINES + j];
                }
            }
            limit = SHORT_OVERLAP;
        } else {
            System.arraycopy(blocks, 0, band, 0, 2 * LINES);
        }
        for (var j = 0; j < limit; j++) {
            signal[HISTORY + j] = previous[j] + band[j];
        }
        System.arraycopy(band, limit, signal, HISTORY + limit, LINES - limit);
        System.arraycopy(band, LINES, previous, 0, LINES);
    }

    /**
     * Runs the IPQF over the frame's band samples and keeps the last 23 of each band.
     *
     * @param state the channel's memory
     * @param out the output samples
     * @param offset the index of the first output sample
     */
    private static void combine(State state, float[] out, int offset) {
        var s0 = state.signals[0];
        var s1 = state.signals[1];
        var s2 = state.signals[2];
        var s3 = state.signals[3];
        for (var phase = 0; phase < BANDS; phase++) {
            var filter = PHASE_FILTERS[phase];
            for (var m = 0; m < LINES; m++) {
                var newest = HISTORY + m;
                var sum = 0.0f;
                for (var i = 0; i < PHASE_TAPS; i++) {
                    sum += filter[i] * s0[newest - i]
                            + filter[PHASE_TAPS + i] * s1[newest - i]
                            + filter[2 * PHASE_TAPS + i] * s2[newest - i]
                            + filter[3 * PHASE_TAPS + i] * s3[newest - i];
                }
                out[offset + 4 * m + phase] = sum;
            }
        }
        for (var signal : state.signals) {
            System.arraycopy(signal, LINES, signal, 0, HISTORY);
        }
    }

    /**
     * Returns the composite window of a long window sequence, building it on first use.
     *
     * @param sequence the window sequence: only long, long start or long stop
     * @param previousShape the shape of the rising half
     * @param shape the shape of the falling half
     * @return the window, 512 samples
     */
    private float[] composite(int sequence, int previousShape, int shape) {
        var index = sequence * 4 + previousShape * 2 + shape;
        var window = composites[index];
        if (window != null) {
            return window;
        }
        window = new float[2 * LINES];
        var rising = longWindows[previousShape];
        var falling = longWindows[shape];
        switch (sequence) {
            case ChannelStream.LONG_START_SEQUENCE -> {
                System.arraycopy(rising, 0, window, 0, LINES);
                Arrays.fill(window, LINES, LINES + SHORT_START, 1);
                System.arraycopy(shortWindows[shape], SHORT_LINES, window, LINES + SHORT_START, SHORT_LINES);
            }
            case ChannelStream.LONG_STOP_SEQUENCE -> {
                System.arraycopy(shortWindows[previousShape], 0, window, SHORT_START, SHORT_LINES);
                Arrays.fill(window, SHORT_OVERLAP, LINES, 1);
                System.arraycopy(falling, LINES, window, LINES, LINES);
            }
            default -> {
                System.arraycopy(rising, 0, window, 0, LINES);
                System.arraycopy(falling, LINES, window, LINES, LINES);
            }
        }
        composites[index] = window;
        return window;
    }

    /**
     * Builds the IPQF coefficients {@code Q_B(j) = Q(j) cos((2B + 1)(2j - 3) pi / 16)} by phase.
     *
     * @return the coefficients
     */
    private static float[][] phaseFilters() {
        var filters = new float[BANDS][BANDS * PHASE_TAPS];
        for (var j = 0; j < TAPS; j++) {
            var prototype = j < TAPS / 2 ? PROTOTYPE[j] : PROTOTYPE[TAPS - 1 - j];
            for (var index = 0; index < BANDS; index++) {
                var value = prototype * StrictMath.cos((2 * index + 1) * (2 * j - 3) * Math.PI / 16);
                filters[j % BANDS][index * PHASE_TAPS + j / BANDS] = (float) value;
            }
        }
        return filters;
    }

    /**
     * Builds {@link #LEVELS}.
     *
     * @return the levels
     */
    private static float[] levels() {
        var table = new float[121];
        for (var k = 0; k < table.length; k++) {
            table[k] = (float) StrictMath.pow(2, (k - LEVEL_ZERO) / 8.0);
        }
        return table;
    }
}
