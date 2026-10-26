/* Ports the ISO/IEC 23003-3:2019 reference software MPEG Surround decoder (sac_bitdec.c SpatialDecDecodeHeader,
   SpatialDecParseFrame, SpatialDecDecodeFrame, mapIndexData, factorFunct, factorCLD, createMapping, mapFrequency,
   decodeAndMapFrameOtt, decodeAndMapFrameTtt, decodeAndMapFrameSmg, decodeAndMapFrameArbdmx, parseArbitraryDownmixData,
   parseResidualData and SpatialDecParseExtensionFrame; sac_resdecode.c decodeIcs and decodeCpe), with the
   SpatialFrame() syntax of ISO/IEC 23003-1 subclause 5.2 that the reference software trims to the 2-1-2 tree.
   Copyright Agere Systems, Coding Technologies, Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software
   licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ChannelStream;
import com.github.auties00.smalt.audio.aac.internal.SamplingTables;

import java.util.Arrays;

/**
 * Reads the {@code SpatialFrame()} of the MPEG Surround trees of ISO/IEC 23003-1 and maps its parameters onto
 * dequantized values per parameter set and band.
 *
 * <p>The mapped values ({@link #cld}, {@link #icc}, the TTT and arbitrary downmix parameters, the smoothing and
 * temporal shaping controls and the residual spectra) stay valid until the next frame. Instances keep the
 * differential coding history of one stream; they are not thread-safe.
 */
final class MpsParameters {
    static final int MAX_SETS = MpsLossless.MAX_SETS;
    static final int MAX_BANDS = MpsLossless.MAX_BANDS;
    private static final int MAX_PARAMETERS = 5 + 4 + 6;
    private static final int[] STRIDES = {1, 2, 5, 28};
    private static final int[] SMOOTHING_TIMES = {64, 128, 256, 512};
    private static final int[][] TEMPORAL_SHAPING_CHANNELS = {{5, 5, 4, 6, 6, 4, 4}, {5, 5, 5, 7, 7, 4, 4}};
    private static final int[] SAMPLING_FREQUENCIES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000,
            12000, 11025, 8000, 7350};
    private static final float[] DEQUANT_CLD = {-150.0f, -45.0f, -40.0f, -35.0f, -30.0f, -25.0f, -22.0f, -19.0f,
            -16.0f, -13.0f, -10.0f, -8.0f, -6.0f, -4.0f, -2.0f, 0.0f, 2.0f, 4.0f, 6.0f, 8.0f, 10.0f, 13.0f, 16.0f,
            19.0f, 22.0f, 25.0f, 30.0f, 35.0f, 40.0f, 45.0f, 150.0f};
    private static final float[] DEQUANT_CPC = {-2.0f, -1.9f, -1.8f, -1.7f, -1.6f, -1.5f, -1.4f, -1.3f, -1.2f, -1.1f,
            -1.0f, -0.9f, -0.8f, -0.7f, -0.6f, -0.5f, -0.4f, -0.3f, -0.2f, -0.1f, 0.0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f,
            0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f, 1.8f, 1.9f, 2.0f, 2.1f, 2.2f,
            2.3f, 2.4f, 2.5f, 2.6f, 2.7f, 2.8f, 2.9f, 3.0f};
    private static final float[] DEQUANT_ICC = {1.0000f, 0.9370f, 0.84118f, 0.60092f, 0.36764f, 0.0f, -0.5890f,
            -0.9900f};

    final MpsConfig config;
    final int tree;
    final int slots;
    final int bands;
    final int ottCount;
    final int tttCount;
    final int inputs;
    final int outputs;
    final int[] ottBands = new int[5];
    final boolean[] ottLfe = new boolean[5];
    final int[] ottCldDefault = new int[5];
    final int[] tttMode = new int[2];
    final int[] tttStart = new int[2];
    final int[] tttStop = new int[2];
    final boolean[] tttDecorrelated = new boolean[2];
    final int[] residualBands = new int[10];
    final int residualFrames;
    final int residualUpdate;
    final int downmixResidualBands;
    final int downmixResidualFrames;
    final int downmixResidualUpdate;

    int sets = 1;
    int setsPrevious = 1;
    final int[] parameterSlot = new int[MAX_SETS + 1];
    boolean extendFrame;
    private boolean independent;

    private final MpsLossless lossless = new MpsLossless();
    private final MpsLossless.Modes[] cldModes = modes();
    private final MpsLossless.Modes[] iccModes = modes();
    private final MpsLossless.Modes[] cpcModes = modes();

    private final int[][][] cldCoded = new int[5][MAX_SETS + 1][MAX_BANDS];
    private final int[][] cldCodedPrevious = new int[5][MAX_BANDS];
    private final int[][][] cldIndex = new int[5][MAX_SETS + 1][MAX_BANDS];
    private final int[][] cldIndexPrevious = new int[5][MAX_BANDS];
    final float[][][] cld = new float[5][MAX_SETS + 1][MAX_BANDS];

    private final int[][][] iccCoded = new int[5][MAX_SETS + 1][MAX_BANDS];
    private final int[][] iccCodedPrevious = new int[5][MAX_BANDS];
    private final int[][][] iccIndex = new int[5][MAX_SETS + 1][MAX_BANDS];
    private final int[][] iccIndexPrevious = new int[5][MAX_BANDS];
    private final int[][][] iccDifference = new int[5][MAX_SETS + 1][MAX_BANDS];
    private final boolean[][] iccDifferencePresent = new boolean[5][MAX_SETS];
    final float[][][] icc = new float[5][MAX_SETS + 1][MAX_BANDS];

    private final int[][][] tttCoded = new int[5][MAX_SETS + 1][MAX_BANDS];
    private final int[][] tttCodedPrevious = new int[5][MAX_BANDS];
    private final int[][][] tttIndex = new int[5][MAX_SETS + 1][MAX_BANDS];
    private final int[][] tttIndexPrevious = new int[5][MAX_BANDS];
    final float[][] cpc1 = new float[MAX_SETS + 1][MAX_BANDS];
    final float[][] cpc2 = new float[MAX_SETS + 1][MAX_BANDS];
    final float[][] tttIcc = new float[MAX_SETS + 1][MAX_BANDS];
    final float[][] tttCld1 = new float[MAX_SETS + 1][MAX_BANDS];
    final float[][] tttCld2 = new float[MAX_SETS + 1][MAX_BANDS];

    private final int[][][] gainCoded = new int[6][MAX_SETS + 1][MAX_BANDS];
    private final int[][] gainCodedPrevious = new int[6][MAX_BANDS];
    private final int[][][] gainIndex = new int[6][MAX_SETS + 1][MAX_BANDS];
    private final int[][] gainIndexPrevious = new int[6][MAX_BANDS];
    final float[][][] downmixGain = new float[6][MAX_SETS + 1][MAX_BANDS];
    final boolean[] downmixResidualAbs = new boolean[6];
    final boolean[] downmixAlphaUpdate = new boolean[6];

    private final int[] smoothMode = new int[MAX_SETS];
    private final int[] smoothTime = new int[MAX_SETS];
    private final int[] smoothStride = new int[MAX_SETS];
    private final int[][] smoothFlags = new int[MAX_SETS][MAX_BANDS];
    final int[] smoothingTime = new int[MAX_SETS + 1];
    final int[][] smoothingData = new int[MAX_SETS + 1][MAX_BANDS];
    private int previousSmoothingTime;
    private final int[] previousSmoothingData = new int[MAX_BANDS];

    final boolean[] stpEnabled = new boolean[8];
    final boolean[] gesEnabled = new boolean[8];
    final float[][] envelopeShape;
    private final int[] envelopeCodes;

    final float[][][] residualSpectra = new float[10][4][2048];
    boolean residualFresh;
    final int[][] residualWindows = new int[10][4];
    private final ChannelStream residualStream;
    private final ChannelStream downmixResidualStream;

    private final float[][] totalDb = new float[MAX_SETS + 1][MAX_BANDS];
    private final float[][][] partialDb = new float[7][MAX_SETS + 1][MAX_BANDS];
    private final int[] mapping = new int[MAX_BANDS + 1];
    private final int[] interpolate = new int[MAX_SETS + 1];
    private final int[] parameterSets = new int[MAX_SETS + 1];
    private final int[] widths = new int[MAX_BANDS + 1];

    /**
     * Derives the frame layout of a configuration ({@code SpatialDecDecodeHeader}).
     *
     * @param config the configuration
     * @param qmfBands the number of QMF subbands of the decoder
     * @throws MediaException.Malformed if the residual frames do not divide the spatial frame
     * @throws MediaException.Unsupported if a residual frame needs an MDCT longer than two AAC frames
     */
    MpsParameters(MpsConfig config, int qmfBands) {
        this.config = config;
        this.tree = config.treeConfig;
        this.slots = config.frameLength;
        this.bands = MpsConfig.FREQUENCY_RESOLUTIONS[config.frequencyResolution];
        var properties = MpsConfig.TREE_PROPERTIES[tree];
        this.inputs = properties[0];
        this.outputs = properties[1];
        this.ottCount = properties[2];
        this.tttCount = properties[3];
        for (var i = 0; i < ottCount; i++) {
            ottLfe[i] = config.ottLfe[i];
            ottBands[i] = config.ottBands[i];
        }
        if (tttCount > 0) {
            tttMode[0] = config.tttModeLow;
            tttMode[1] = config.tttModeHigh;
            tttStart[0] = 0;
            tttStop[1] = bands;
            if (config.tttDualMode) {
                tttStop[0] = config.tttBandsLow;
                tttStart[1] = config.tttBandsLow;
            } else {
                tttStop[0] = bands;
                tttStart[1] = bands;
            }
            for (var i = 0; i < 2; i++) {
                tttDecorrelated[i] = tttMode[i] == 0;
            }
        }
        if (config.residualCoding) {
            for (var i = 0; i < ottCount + tttCount; i++) {
                residualBands[i] = config.residualPresent[i] ? config.residualBands[i] : 0;
            }
        }
        this.residualFrames = config.residualFrames;
        this.residualUpdate = residualFrames > 0 ? slots / residualFrames : 0;
        this.downmixResidualBands = config.arbitraryDownmix == 2 ? config.downmixResidualBands : 0;
        this.downmixResidualFrames = config.downmixResidualFrames;
        this.downmixResidualUpdate = downmixResidualFrames > 0 ? slots / downmixResidualFrames : 0;
        if (config.residualCoding) {
            checkResidualFrames(residualFrames, residualUpdate, qmfBands);
        }
        if (config.arbitraryDownmix == 2) {
            checkResidualFrames(downmixResidualFrames, downmixResidualUpdate, qmfBands);
        }
        switch (tree) {
            case MpsConfig.TREE_5151 -> {
                ottCldDefault[0] = 15;
                ottCldDefault[1] = 15;
                ottCldDefault[4] = 15;
            }
            case MpsConfig.TREE_5152 -> {
                ottCldDefault[0] = 15;
                ottCldDefault[2] = 15;
                ottCldDefault[3] = 15;
                ottCldDefault[4] = 15;
            }
            case MpsConfig.TREE_525 -> Arrays.fill(ottCldDefault, 0, 3, 15);
            case MpsConfig.TREE_7271, MpsConfig.TREE_7272 -> Arrays.fill(ottCldDefault, 15);
            default -> {
                ottCldDefault[0] = 15;
                ottCldDefault[1] = 15;
            }
        }
        this.envelopeShape = new float[8][slots];
        this.envelopeCodes = new int[slots];
        this.residualStream = config.residualCoding ? residualStream(config.residualSamplingIndex) : null;
        this.downmixResidualStream = config.arbitraryDownmix == 2
                ? residualStream(config.downmixResidualSamplingIndex) : null;
    }

    private void checkResidualFrames(int frames, int update, int qmfBands) {
        if (slots % frames != 0 || !MpsMdctToQmf.supports(update)) {
            throw new MediaException.Malformed("MPEG Surround residual frames do not divide a spatial frame of "
                    + slots + " slots");
        }
        if (update * qmfBands > 2048) {
            throw new MediaException.Unsupported("mpeg surround residual at " + qmfBands + " qmf bands",
                    "MPEG Surround residual frames of " + update + " slots of " + qmfBands
                            + " QMF bands are not supported");
        }
    }

    private static ChannelStream residualStream(int samplingIndex) {
        if (samplingIndex >= SAMPLING_FREQUENCIES.length) {
            throw new MediaException.Malformed("MPEG Surround residual sampling frequency index " + samplingIndex
                    + " is reserved");
        }
        var tableIndex = SamplingTables.tableIndexFor(SAMPLING_FREQUENCIES[samplingIndex]);
        return new ChannelStream(1024, tableIndex, AudioSpecificConfig.AOT_LC, AacKernels.INSTANCE);
    }

    private static MpsLossless.Modes[] modes() {
        var modes = new MpsLossless.Modes[MAX_PARAMETERS];
        for (var i = 0; i < modes.length; i++) {
            modes[i] = new MpsLossless.Modes();
        }
        return modes;
    }

    /**
     * Reads the framing information of a {@code SpatialFrame()} up to its {@code bsIndependencyFlag}.
     *
     * @param reader the bit source, positioned at the frame; it is left after the flag
     * @param slots the number of slots of a spatial frame
     * @return the {@code bsIndependencyFlag}
     */
    static boolean independent(AacBitReader reader, int slots) {
        var framing = reader.readFlag();
        var sets = reader.read(3) + 1;
        if (framing) {
            var previous = -1;
            for (var i = 0; i < sets; i++) {
                var bits = 0;
                while ((1 << bits) < slots - sets + i - previous) {
                    bits++;
                }
                previous += 1 + (bits > 0 ? reader.read(bits) : 0);
            }
        }
        return reader.readFlag();
    }

    /**
     * Reads one {@code SpatialFrame()} ({@code SpatialDecParseFrame}) and maps its parameters
     * ({@code SpatialDecDecodeFrame}).
     *
     * <p>Byte alignment is relative to the first bit of the frame.
     *
     * @param reader the bit source, positioned at the frame
     * @throws MediaException.Malformed if the frame is invalid
     */
    void read(AacBitReader reader) {
        var start = reader.bitPosition();
        setsPrevious = sets;
        var framing = reader.readFlag();
        sets = reader.read(3) + 1;
        if (sets > slots) {
            throw new MediaException.Malformed("MPEG Surround frame has more parameter sets than slots");
        }
        for (var i = 0; i < sets; i++) {
            if (framing) {
                var bits = 0;
                var previous = i > 0 ? parameterSlot[i - 1] : -1;
                while ((1 << bits) < slots - sets + i - previous) {
                    bits++;
                }
                parameterSlot[i] = previous + 1 + (bits > 0 ? reader.read(bits) : 0);
                if (parameterSlot[i] > slots - sets + i) {
                    throw new MediaException.Malformed("MPEG Surround parameter slot is past the frame");
                }
            } else {
                parameterSlot[i] = (int) Math.ceil((float) slots * (float) (i + 1) / (float) sets) - 1;
            }
        }
        independent = reader.readFlag();
        for (var i = 0; i < ottCount; i++) {
            lossless.read(reader, cldModes[i], cldCoded[i], cldCodedPrevious[i], MpsLossless.CLD, 0, ottBands[i],
                    ottCldDefault[i], sets, false, independent);
        }
        if (config.oneIcc) {
            lossless.read(reader, iccModes[0], iccCoded[0], iccCodedPrevious[0], MpsLossless.ICC, 0, bands, 0, sets,
                    false, independent);
        } else {
            for (var i = 0; i < ottCount; i++) {
                if (!ottLfe[i]) {
                    lossless.read(reader, iccModes[i], iccCoded[i], iccCodedPrevious[i], MpsLossless.ICC, 0,
                            ottBands[i], 0, sets, false, independent);
                }
            }
        }
        for (var j = 0; j < 2 && tttCount > 0; j++) {
            if (j == 1 && tttStart[1] >= tttStop[1]) {
                break;
            }
            var offset = ottCount + 2 * j;
            if (tttMode[j] < 2) {
                lossless.read(reader, cpcModes[offset], tttCoded[0], tttCodedPrevious[0], MpsLossless.CPC,
                        tttStart[j], tttStop[j], 10, sets, false, independent);
                lossless.read(reader, cpcModes[offset + 1], tttCoded[1], tttCodedPrevious[1], MpsLossless.CPC,
                        tttStart[j], tttStop[j], 10, sets, false, independent);
                lossless.read(reader, iccModes[offset], tttCoded[2], tttCodedPrevious[2], MpsLossless.ICC,
                        tttStart[j], tttStop[j], 0, sets, false, independent);
            } else {
                lossless.read(reader, cldModes[offset], tttCoded[3], tttCodedPrevious[3], MpsLossless.CLD,
                        tttStart[j], tttStop[j], 15, sets, false, independent);
                lossless.read(reader, cldModes[offset + 1], tttCoded[4], tttCodedPrevious[4], MpsLossless.CLD,
                        tttStart[j], tttStop[j], 0, sets, false, independent);
            }
        }
        for (var ps = 0; ps < sets; ps++) {
            smoothMode[ps] = reader.read(2);
            if (smoothMode[ps] >= 2) {
                smoothTime[ps] = reader.read(2);
            }
            if (smoothMode[ps] == 3) {
                smoothStride[ps] = reader.read(2);
                var groups = (bands - 1) / STRIDES[smoothStride[ps]] + 1;
                for (var pg = 0; pg < groups; pg++) {
                    smoothFlags[ps][pg] = reader.read(1);
                }
            }
        }
        Arrays.fill(stpEnabled, false);
        Arrays.fill(gesEnabled, false);
        if (config.temporalShaping != 0 && reader.readFlag()) {
            var channels = TEMPORAL_SHAPING_CHANNELS[config.temporalShaping - 1][tree];
            if (config.temporalShaping == 1) {
                for (var i = 0; i < channels; i++) {
                    stpEnabled[i] = reader.readFlag();
                }
            } else {
                for (var i = 0; i < channels; i++) {
                    gesEnabled[i] = reader.readFlag();
                }
                for (var i = 0; i < channels; i++) {
                    if (gesEnabled[i]) {
                        lossless.readReshape(reader, envelopeCodes, slots);
                        for (var ts = 0; ts < slots; ts++) {
                            envelopeShape[i][ts] = (float) StrictMath.pow(2,
                                    (float) envelopeCodes[ts] / (config.envelopeQuantization + 2) - 1);
                        }
                    }
                }
            }
        }
        if (config.arbitraryDownmix != 0) {
            var offset = ottCount + 4 * tttCount;
            for (var ch = 0; ch < inputs; ch++) {
                lossless.read(reader, cldModes[offset + ch], gainCoded[ch], gainCodedPrevious[ch], MpsLossless.CLD,
                        0, bands, 0, sets, false, independent);
            }
        }
        var misaligned = (int) ((reader.bitPosition() - start) & 7);
        if (misaligned != 0) {
            reader.read(8 - misaligned);
        }
        readExtensions(reader);
        for (var i = 0; i < ottCount; i++) {
            for (var ps = 0; ps < sets; ps++) {
                if (!iccDifferencePresent[i][ps]) {
                    Arrays.fill(iccDifference[i][ps], 0, bands, 0);
                }
            }
        }
        reader.checkNotOverrun();
        residualFresh = true;
        decode();
    }

    /**
     * Keeps the mapped parameters of the last frame for the next one: its last parameter set becomes the previous
     * one, and the matrices of the next frame equal it.
     */
    void repeat() {
        setsPrevious = sets;
        residualFresh = false;
    }

    private void readExtensions(AacBitReader reader) {
        for (var type : config.extensionTypes) {
            if (type >= 12) {
                continue;
            }
            var length = reader.read(8);
            if (length == 255) {
                length += reader.read(16);
            }
            var begin = reader.bitPosition();
            switch (type) {
                case MpsConfig.EXTENSION_RESIDUAL -> readResidual(reader);
                case MpsConfig.EXTENSION_ARBITRARY_DOWNMIX -> readDownmixResidual(reader);
                default -> {
                }
            }
            var fill = 8L * length - (reader.bitPosition() - begin);
            if (fill < 0) {
                throw new MediaException.Malformed("MPEG Surround extension frame overruns its length");
            }
            reader.skip(fill);
        }
    }

    private void readResidual(AacBitReader reader) {
        for (var box = 0; box < ottCount + tttCount; box++) {
            if (config.residualBands[box] <= 0 || !config.residualPresent[box]) {
                continue;
            }
            if (box < ottCount) {
                for (var ps = 0; ps < sets; ps++) {
                    iccDifferencePresent[box][ps] = reader.readFlag();
                    if (iccDifferencePresent[box][ps]) {
                        for (var pb = 0; pb < config.residualBands[box]; pb++) {
                            var value = 0;
                            var count = 0;
                            while (!reader.readFlag() && count++ < 7) {
                                value++;
                            }
                            iccDifference[box][ps][pb] = value;
                        }
                    }
                }
            }
            for (var frame = 0; frame < residualFrames; frame++) {
                var window = readIcs(reader, residualStream, residualSpectra[box][frame], 0);
                residualWindows[box][frame] = window;
                if (window == 2 && (residualUpdate == 18 || residualUpdate == 24 || residualUpdate == 30)) {
                    if (readIcs(reader, residualStream, residualSpectra[box][frame], 1024) != window) {
                        throw new MediaException.Malformed("MPEG Surround residual halves differ in window sequence");
                    }
                }
            }
        }
    }

    private void readDownmixResidual(AacBitReader reader) {
        var offset = ottCount + tttCount;
        for (var ch = 0; ch < inputs; ) {
            var group = inputs == 1 ? 1 : 2;
            downmixResidualAbs[ch] = reader.readFlag();
            downmixAlphaUpdate[ch] = reader.readFlag();
            if (group == 1) {
                for (var frame = 0; frame < downmixResidualFrames; frame++) {
                    var window = readIcs(reader, downmixResidualStream, residualSpectra[offset + ch][frame], 0);
                    residualWindows[offset + ch][frame] = window;
                    if (window == 2 && shortPair(downmixResidualUpdate)) {
                        readIcs(reader, downmixResidualStream, residualSpectra[offset + ch][frame], 1024);
                    }
                }
            } else {
                downmixResidualAbs[ch + 1] = downmixResidualAbs[ch];
                downmixAlphaUpdate[ch + 1] = downmixAlphaUpdate[ch];
                for (var frame = 0; frame < downmixResidualFrames; frame++) {
                    var window = readPair(reader, offset + ch, frame, 0);
                    residualWindows[offset + ch][frame] = window;
                    residualWindows[offset + ch + 1][frame] = window;
                    if (window == 2 && shortPair(downmixResidualUpdate)) {
                        readPair(reader, offset + ch, frame, 1024);
                    }
                }
            }
            ch += group;
        }
    }

    private static boolean shortPair(int update) {
        return update == 18 || update == 24 || update == 30;
    }

    private int readPair(AacBitReader reader, int channel, int frame, int position) {
        reader.read(4);
        if (reader.readFlag()) {
            throw new MediaException.Malformed("MPEG Surround downmix residual uses a common window");
        }
        var first = readIcs(reader, downmixResidualStream, residualSpectra[channel][frame], position);
        var second = readIcs(reader, downmixResidualStream, residualSpectra[channel + 1][frame], position);
        if (first != second) {
            throw new MediaException.Malformed("MPEG Surround downmix residual channels differ in window sequence");
        }
        return first;
    }

    private static int readIcs(AacBitReader reader, ChannelStream stream, float[] spectrum, int position) {
        stream.parse(reader, false, false);
        stream.applyTns();
        System.arraycopy(stream.spectrum(), 0, spectrum, position, 1024);
        return stream.windowSequence();
    }

    private void decode() {
        extendFrame = parameterSlot[sets - 1] != slots - 1;
        decodeOtt();
        decodeTtt();
        decodeSmoothing();
        if (config.arbitraryDownmix != 0) {
            var offset = ottCount + 4 * tttCount;
            for (var ch = 0; ch < inputs; ch++) {
                map(cldModes[offset + ch], downmixGain[ch], gainIndex[ch], gainCoded[ch], null, gainIndexPrevious[ch],
                        MpsLossless.CLD, 0, bands, 0, 0, null, null, null);
            }
        }
        if (extendFrame) {
            sets++;
            parameterSlot[sets - 1] = slots - 1;
        }
    }

    private void decodeOtt() {
        for (var ps = 0; ps < sets; ps++) {
            Arrays.fill(totalDb[ps], 0, bands, 0.0f);
        }
        var quantMode = config.quantMode;
        switch (tree) {
            case MpsConfig.TREE_5151 -> {
                mapCld(0, quantMode, totalDb, partialDb[0], partialDb[1]);
                mapCld(1, quantMode, partialDb[0], partialDb[2], partialDb[3]);
                mapCld(2, quantMode, partialDb[1], partialDb[5], partialDb[6]);
                mapCld(3, quantMode, partialDb[2], partialDb[5], partialDb[6]);
                mapCld(4, quantMode, totalDb, partialDb[5], partialDb[6]);
            }
            case MpsConfig.TREE_5152 -> {
                mapCld(0, quantMode, totalDb, partialDb[0], partialDb[1]);
                mapCld(1, quantMode, partialDb[0], partialDb[2], partialDb[3]);
                mapCld(2, quantMode, totalDb, partialDb[5], partialDb[6]);
                mapCld(3, quantMode, partialDb[2], partialDb[5], partialDb[6]);
                mapCld(4, quantMode, partialDb[3], partialDb[5], partialDb[6]);
            }
            default -> {
                for (var i = 0; i < ottCount; i++) {
                    mapCld(i, 0, null, null, null);
                }
            }
        }
        if (config.oneIcc) {
            var count = extendFrame ? sets + 1 : sets;
            for (var i = 1; i < ottCount; i++) {
                if (!ottLfe[i]) {
                    for (var ps = 0; ps < count; ps++) {
                        System.arraycopy(iccCoded[0][ps], 0, iccCoded[i][ps], 0, bands);
                    }
                }
            }
        }
        var shared = iccModes[0].quantCoarsePrevious;
        for (var i = 0; i < ottCount; i++) {
            if (!ottLfe[i]) {
                if (config.oneIcc) {
                    iccModes[0].quantCoarsePrevious = shared;
                }
                map(iccModes[config.oneIcc ? 0 : i], icc[i], iccIndex[i], iccCoded[i], iccDifference[i],
                        iccIndexPrevious[i], MpsLossless.ICC, 0, ottBands[i], 0, 0, null, null, null);
            }
        }
    }

    private void mapCld(int box, int quantMode, float[][] total, float[][] first, float[][] second) {
        map(cldModes[box], cld[box], cldIndex[box], cldCoded[box], null, cldIndexPrevious[box], MpsLossless.CLD, 0,
                ottBands[box], ottCldDefault[box], quantMode, total, first, second);
    }

    private void decodeTtt() {
        for (var j = 0; j < 2 && tttCount > 0 && tttStart[j] < tttStop[j]; j++) {
            var offset = ottCount + 2 * j;
            if (tttMode[j] < 2) {
                map(cpcModes[offset], cpc1, tttIndex[0], tttCoded[0], null, tttIndexPrevious[0], MpsLossless.CPC,
                        tttStart[j], tttStop[j], 10, 0, null, null, null);
                map(cpcModes[offset + 1], cpc2, tttIndex[1], tttCoded[1], null, tttIndexPrevious[1],
                        MpsLossless.CPC, tttStart[j], tttStop[j], 10, 0, null, null, null);
                map(iccModes[offset], tttIcc, tttIndex[2], tttCoded[2], null, tttIndexPrevious[2], MpsLossless.ICC,
                        tttStart[j], tttStop[j], 0, 0, null, null, null);
            } else {
                map(cldModes[offset], tttCld1, tttIndex[3], tttCoded[3], null, tttIndexPrevious[3], MpsLossless.CLD,
                        tttStart[j], tttStop[j], 15, 0, null, null, null);
                map(cldModes[offset + 1], tttCld2, tttIndex[4], tttCoded[4], null, tttIndexPrevious[4],
                        MpsLossless.CLD, tttStart[j], tttStop[j], 0, 0, null, null, null);
            }
        }
    }

    /**
     * Maps the coded sets of one parameter onto every parameter set ({@code mapIndexData}).
     */
    private void map(MpsLossless.Modes modes, float[][] output, int[][] outputIndex, int[][] coded,
                     int[][] difference, int[] previous, int type, int startBand, int stopBand, int defaultValue,
                     int quantMode, float[][] totalIn, float[][] total1, float[][] total2) {
        var codedSets = 0;
        for (var i = 0; i < sets; i++) {
            if (modes.dataMode[i] == 3) {
                parameterSets[codedSets++] = i;
            }
        }
        var setIndex = 0;
        for (var i = 0; i < sets; i++) {
            var mode = modes.dataMode[i];
            if (mode == 0) {
                modes.uncompressedCoarse[i] = 0;
                for (var band = startBand; band < stopBand; band++) {
                    outputIndex[i][band] = defaultValue;
                    previous[band] = defaultValue;
                }
                modes.quantCoarsePrevious = 0;
            }
            if (mode == 1 || mode == 2) {
                System.arraycopy(previous, startBand, outputIndex[i], startBand, stopBand - startBand);
                modes.uncompressedCoarse[i] = modes.quantCoarsePrevious;
            }
            interpolate[i] = mode == 2 ? 1 : 0;
            if (mode == 3) {
                var slot = parameterSets[setIndex];
                var stride = STRIDES[modes.freqResStride[setIndex]];
                var dataBands = (stopBand - startBand - 1) / stride + 1;
                createMapping(mapping, startBand, stopBand, stride);
                var first = mapping[0];
                for (var j = 0; j < dataBands; j++) {
                    var value = coded[setIndex][j + first];
                    for (var band = mapping[j]; band < mapping[j + 1]; band++) {
                        outputIndex[slot][band] = value;
                    }
                }
                System.arraycopy(outputIndex[slot], startBand, previous, startBand, stopBand - startBand);
                modes.quantCoarsePrevious = modes.quantCoarse[setIndex];
                modes.uncompressedCoarse[i] = modes.quantCoarse[setIndex];
                setIndex++;
            }
            if (difference != null) {
                for (var band = startBand; band < stopBand; band++) {
                    outputIndex[i][band] += difference[i][band];
                }
            }
        }
        for (var i = 0; i < sets; i++) {
            if (modes.uncompressedCoarse[i] == 1) {
                MpsLossless.coarseToFine(outputIndex[i], type, startBand, stopBand - startBand);
                modes.uncompressedCoarse[i] = 0;
            }
        }
        var i1 = -1;
        for (var i = 0; i < sets; i++) {
            if (interpolate[i] != 1) {
                i1 = i;
            }
            var i2 = i;
            while (interpolate[i2] == 1) {
                i2++;
                if (i2 >= sets) {
                    throw new MediaException.Malformed("MPEG Surround interpolates past the last parameter set");
                }
            }
            int x1;
            if (i1 == -1) {
                x1 = 0;
                i1 = 0;
            } else {
                x1 = parameterSlot[i1];
            }
            var first = i1;
            if (interpolate[i] == 1) {
                var xi = parameterSlot[i];
                var x2 = parameterSlot[i2];
                for (var band = startBand; band < stopBand; band++) {
                    var y1 = outputIndex[first][band];
                    var y2 = outputIndex[i2][band];
                    outputIndex[i][band] = y1 + (xi - x1) * (y2 - y1) / (x2 - x1);
                }
            }
        }
        for (var ps = 0; ps < sets; ps++) {
            if (quantMode != 0 && type == MpsLossless.CLD) {
                for (var pb = startBand; pb < stopBand; pb++) {
                    factorCld(outputIndex[ps], pb, totalIn[ps][pb], total1[ps], total2[ps], quantMode);
                }
            }
            for (var band = startBand; band < stopBand; band++) {
                output[ps][band] = dequantize(outputIndex[ps][band], type);
            }
        }
        if (extendFrame) {
            System.arraycopy(output[sets - 1], startBand, output[sets], startBand, stopBand - startBand);
        }
    }

    private static float dequantize(int value, int type) {
        switch (type) {
            case MpsLossless.CLD -> {
                if (value < -15 || value > 15) {
                    throw new MediaException.Malformed("MPEG Surround CLD index " + value + " is out of range");
                }
                return DEQUANT_CLD[value + 15];
            }
            case MpsLossless.ICC -> {
                if (value < 0 || value > 7) {
                    throw new MediaException.Malformed("MPEG Surround ICC index " + value + " is out of range");
                }
                return DEQUANT_ICC[value];
            }
            default -> {
                if (value < -20 || value > 30) {
                    throw new MediaException.Malformed("MPEG Surround CPC index " + value + " is out of range");
                }
                return DEQUANT_CPC[value + 20];
            }
        }
    }

    private static float factor(float ottVsTotalDb, int quantMode) {
        if (quantMode == 0) {
            return 1;
        }
        float addFactor;
        float xFactor;
        float maxFactor;
        if (quantMode == 1) {
            addFactor = 5;
            xFactor = 21;
            maxFactor = 5;
        } else {
            addFactor = 8;
            xFactor = 25;
            maxFactor = 8;
        }
        var difference = -ottVsTotalDb;
        var xLinear = 1.0f;
        var dy = (addFactor - 1) / (xFactor - xLinear);
        var factor = difference > xLinear ? (difference - xLinear) * dy + 1 : 1.0f;
        return Math.min(maxFactor, factor);
    }

    private static void factorCld(int[] index, int pb, float ottVsTotalDb, float[] first, float[] second,
                                  int quantMode) {
        var dbe = 10 / (float) StrictMath.log(10);
        var factor = factor(ottVsTotalDb, quantMode);
        var value = (int) (index[pb] * factor + 15.5) - 15;
        value = Math.max(Math.min(value, 15), -15);
        index[pb] = value;
        var cld = DEQUANT_CLD[value + 15];
        var energy = (float) StrictMath.exp(ottVsTotalDb / dbe);
        var c1 = (float) StrictMath.exp(cld / dbe) / (1 + (float) StrictMath.exp(cld / dbe));
        var c2 = 1 / (1 + (float) StrictMath.exp(cld / dbe));
        first[pb] = dbe * (float) StrictMath.log(c1 * energy);
        second[pb] = dbe * (float) StrictMath.log(c2 * energy);
    }

    /**
     * Builds the band borders of a coded parameter set ({@code createMapping}).
     */
    private void createMapping(int[] map, int startBand, int stopBand, int stride) {
        var inBands = stopBand - startBand;
        var outBands = Math.max(1, (inBands - 1) / stride + 1);
        var diff = inBands - outBands * stride;
        Arrays.fill(widths, 0, outBands, stride);
        int increment;
        int k;
        if (diff > 0) {
            increment = -1;
            k = outBands - 1;
        } else {
            increment = 1;
            k = 0;
        }
        while (diff != 0) {
            widths[k] -= increment;
            k += increment;
            diff += increment;
            if (k >= outBands) {
                if (diff > 0) {
                    k = outBands - 1;
                } else if (diff < 0) {
                    k = 0;
                }
            }
        }
        map[0] = startBand;
        for (var i = 0; i < outBands; i++) {
            map[i + 1] = map[i] + widths[i];
        }
    }

    private void decodeSmoothing() {
        for (var ps = 0; ps < sets; ps++) {
            switch (smoothMode[ps]) {
                case 0 -> {
                    smoothingTime[ps] = 256;
                    Arrays.fill(smoothingData[ps], 0, bands, 0);
                }
                case 1 -> {
                    smoothingTime[ps] = ps > 0 ? smoothingTime[ps - 1] : previousSmoothingTime;
                    System.arraycopy(ps > 0 ? smoothingData[ps - 1] : previousSmoothingData, 0, smoothingData[ps], 0,
                            bands);
                }
                case 2 -> {
                    smoothingTime[ps] = SMOOTHING_TIMES[smoothTime[ps]];
                    Arrays.fill(smoothingData[ps], 0, bands, 1);
                }
                default -> {
                    smoothingTime[ps] = SMOOTHING_TIMES[smoothTime[ps]];
                    var stride = STRIDES[smoothStride[ps]];
                    var groups = (bands - 1) / stride + 1;
                    createMapping(mapping, 0, bands, stride);
                    for (var pg = 0; pg < groups; pg++) {
                        for (var pb = mapping[pg]; pb < mapping[pg + 1]; pb++) {
                            smoothingData[ps][pb] = smoothFlags[ps][pg];
                        }
                    }
                }
            }
        }
        previousSmoothingTime = smoothingTime[sets - 1];
        System.arraycopy(smoothingData[sets - 1], 0, previousSmoothingData, 0, bands);
        if (extendFrame) {
            smoothingTime[sets] = smoothingTime[sets - 1];
            System.arraycopy(smoothingData[sets - 1], 0, smoothingData[sets], 0, bands);
        }
    }
}
