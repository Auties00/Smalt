/* Ports the ISO/IEC 23003-3:2019 reference software MPEG Surround decoder (sac_bitdec.c
   SpatialDecParseSpecificConfig and SpatialDecParseExtensionConfig, with the SpatialSpecificConfig() syntax of
   ISO/IEC 23003-1 subclause 5.1 that the reference software trims to the 2-1-2 tree). Copyright Agere Systems, Coding
   Technologies, Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software licence (use in products
   conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import java.util.Arrays;

/**
 * Holds an MPEG Surround {@code SpatialSpecificConfig} (ISO/IEC 23003-1 subclause 5.1) with its extensions.
 *
 * <p>The tree configurations 0 to 6 of ISO/IEC 23003-1 are accepted; the 2-1-2 tree of USAC has its own decoder.
 * Instances are immutable after parsing.
 */
public final class MpsConfig {
    /**
     * The 5-1-5 tree whose first box splits front and surround ({@code TREE_5151}).
     */
    public static final int TREE_5151 = 0;

    /**
     * The 5-1-5 tree whose first box splits center and the rest ({@code TREE_5152}).
     */
    public static final int TREE_5152 = 1;

    /**
     * The 5-2-5 tree of a stereo downmix ({@code TREE_525}).
     */
    public static final int TREE_525 = 2;

    /**
     * The 7-2-7 tree that adds the left and right center channels ({@code TREE_7271}).
     */
    public static final int TREE_7271 = 3;

    /**
     * The 7-2-7 tree that adds the left and right rear surround channels ({@code TREE_7272}).
     */
    public static final int TREE_7272 = 4;

    /**
     * The 7-5-7 tree that adds the left and right center channels to a 5.1 downmix ({@code TREE_7571}).
     */
    public static final int TREE_7571 = 5;

    /**
     * The 7-5-7 tree that adds the left and right rear surround channels to a 5.1 downmix ({@code TREE_7572}).
     */
    public static final int TREE_7572 = 6;

    /**
     * The extension type of residual coding ({@code bsSacExtType} 0).
     */
    public static final int EXTENSION_RESIDUAL = 0;

    /**
     * The extension type of arbitrary downmix residual coding ({@code bsSacExtType} 1).
     */
    public static final int EXTENSION_ARBITRARY_DOWNMIX = 1;

    /**
     * The extension type of the arbitrary tree ({@code bsSacExtType} 2).
     */
    public static final int EXTENSION_ARBITRARY_TREE = 2;

    static final int[] FREQUENCY_RESOLUTIONS = {0, 28, 20, 14, 10, 7, 5, 4};
    private static final int MAX_EXTENSIONS = 8;
    private static final int[] SAMPLING_FREQUENCIES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000,
            12000, 11025, 8000, 7350};

    /**
     * The number of input channels, output channels, OTT boxes and TTT boxes of each tree, and the OTT boxes that
     * split off the LFE channel ({@code treePropertyTable}).
     */
    static final int[][] TREE_PROPERTIES = {
            {1, 6, 5, 0, 0, 0, 0, 0, 1},
            {1, 6, 5, 0, 0, 0, 1, 0, 0},
            {2, 6, 3, 1, 1, 0, 0, 0, 0},
            {2, 8, 5, 1, 1, 0, 0, 0, 0},
            {2, 8, 5, 1, 1, 0, 0, 0, 0},
            {6, 8, 2, 0, 0, 0, 0, 0, 0},
            {6, 8, 2, 0, 0, 0, 0, 0, 0}
    };

    final int samplingFrequency;
    final int frameLength;
    final int frequencyResolution;
    final int treeConfig;
    final int quantMode;
    final boolean oneIcc;
    final int arbitraryDownmix;
    final int fixedGainSurround;
    final int fixedGainLfe;
    final int fixedGainDownmix;
    final boolean matrixMode;
    final int temporalShaping;
    final int decorrelator;
    final boolean threeDimensional;
    final int[] ottBands = new int[5];
    final boolean[] ottLfe = new boolean[5];
    final boolean tttDualMode;
    final int tttModeLow;
    final int tttModeHigh;
    final int tttBandsLow;
    final int envelopeQuantization;
    final int[] extensionTypes;
    final boolean residualCoding;
    final int residualSamplingIndex;
    final int residualFrames;
    final boolean[] residualPresent = new boolean[6];
    final int[] residualBands = new int[6];
    final int downmixResidualSamplingIndex;
    final int downmixResidualFrames;
    final int downmixResidualBands;
    final boolean arbitraryTree;

    private MpsConfig(Builder builder) {
        this.samplingFrequency = builder.samplingFrequency;
        this.frameLength = builder.frameLength;
        this.frequencyResolution = builder.frequencyResolution;
        this.treeConfig = builder.treeConfig;
        this.quantMode = builder.quantMode;
        this.oneIcc = builder.oneIcc;
        this.arbitraryDownmix = builder.arbitraryDownmix;
        this.fixedGainSurround = builder.fixedGainSurround;
        this.fixedGainLfe = builder.fixedGainLfe;
        this.fixedGainDownmix = builder.fixedGainDownmix;
        this.matrixMode = builder.matrixMode;
        this.temporalShaping = builder.temporalShaping;
        this.decorrelator = builder.decorrelator;
        this.threeDimensional = builder.threeDimensional;
        System.arraycopy(builder.ottBands, 0, ottBands, 0, 5);
        System.arraycopy(builder.ottLfe, 0, ottLfe, 0, 5);
        this.tttDualMode = builder.tttDualMode;
        this.tttModeLow = builder.tttModeLow;
        this.tttModeHigh = builder.tttModeHigh;
        this.tttBandsLow = builder.tttBandsLow;
        this.envelopeQuantization = builder.envelopeQuantization;
        this.extensionTypes = Arrays.copyOf(builder.extensionTypes, builder.extensionCount);
        this.residualCoding = builder.residualCoding;
        this.residualSamplingIndex = builder.residualSamplingIndex;
        this.residualFrames = builder.residualFrames;
        System.arraycopy(builder.residualPresent, 0, residualPresent, 0, 6);
        System.arraycopy(builder.residualBands, 0, residualBands, 0, 6);
        this.downmixResidualSamplingIndex = builder.downmixResidualSamplingIndex;
        this.downmixResidualFrames = builder.downmixResidualFrames;
        this.downmixResidualBands = builder.downmixResidualBands;
        this.arbitraryTree = builder.arbitraryTree;
    }

    /**
     * Collects the fields while a configuration is parsed.
     */
    private static final class Builder {
        int samplingFrequency;
        int frameLength;
        int frequencyResolution;
        int treeConfig;
        int quantMode;
        boolean oneIcc;
        int arbitraryDownmix;
        int fixedGainSurround;
        int fixedGainLfe;
        int fixedGainDownmix;
        boolean matrixMode;
        int temporalShaping;
        int decorrelator;
        boolean threeDimensional;
        final int[] ottBands = new int[5];
        final boolean[] ottLfe = new boolean[5];
        boolean tttDualMode;
        int tttModeLow;
        int tttModeHigh;
        int tttBandsLow;
        int envelopeQuantization;
        final int[] extensionTypes = new int[MAX_EXTENSIONS];
        int extensionCount;
        boolean residualCoding;
        int residualSamplingIndex;
        int residualFrames;
        final boolean[] residualPresent = new boolean[6];
        final int[] residualBands = new int[6];
        int downmixResidualSamplingIndex;
        int downmixResidualFrames;
        int downmixResidualBands;
        boolean arbitraryTree;
    }

    /**
     * Parses a {@code SpatialSpecificConfig} ({@code SpatialDecParseSpecificConfig}).
     *
     * <p>The configuration starts at the reader's position and spans {@code bits} bits, which bound the extension
     * configurations at its end; byte alignment is relative to its first bit.
     *
     * @param reader the bit source
     * @param bits the length of the configuration in bits
     * @return the configuration
     * @throws MediaException.Malformed if the configuration is truncated or uses reserved values
     * @throws MediaException.Unsupported if it signals a tree outside 0 to 6, or the 3D stereo downmix
     */
    public static MpsConfig read(AacBitReader reader, long bits) {
        var start = reader.bitPosition();
        var b = new Builder();
        var samplingIndex = reader.read(4);
        if (samplingIndex == 15) {
            b.samplingFrequency = reader.read(24);
        } else if (samplingIndex < SAMPLING_FREQUENCIES.length) {
            b.samplingFrequency = SAMPLING_FREQUENCIES[samplingIndex];
        } else {
            throw new MediaException.Malformed("MPEG Surround sampling frequency index " + samplingIndex
                    + " is reserved");
        }
        b.frameLength = reader.read(7) + 1;
        b.frequencyResolution = reader.read(3);
        if (b.frequencyResolution == 0) {
            throw new MediaException.Malformed("MPEG Surround frequency resolution 0 is reserved");
        }
        b.treeConfig = reader.read(4);
        if (b.treeConfig > TREE_7572) {
            throw new MediaException.Unsupported("mpeg surround tree " + b.treeConfig,
                    "MPEG Surround tree configuration " + b.treeConfig + " is not supported");
        }
        var properties = TREE_PROPERTIES[b.treeConfig];
        b.quantMode = reader.read(2);
        if (b.quantMode == 3) {
            throw new MediaException.Malformed("MPEG Surround quantization mode 3 is reserved");
        }
        b.oneIcc = reader.readFlag();
        b.arbitraryDownmix = reader.read(1);
        b.fixedGainSurround = reader.read(3);
        b.fixedGainLfe = reader.read(3);
        if (b.fixedGainSurround > 4 || b.fixedGainLfe > 4) {
            throw new MediaException.Malformed("MPEG Surround fixed gain index is reserved");
        }
        b.fixedGainDownmix = reader.read(3);
        b.matrixMode = reader.readFlag();
        b.temporalShaping = reader.read(2);
        if (b.temporalShaping == 3) {
            throw new MediaException.Malformed("MPEG Surround temporal shaping configuration 3 is reserved");
        }
        b.decorrelator = reader.read(2);
        if (b.decorrelator == 3) {
            throw new MediaException.Malformed("MPEG Surround decorrelator configuration 3 is reserved");
        }
        b.threeDimensional = reader.readFlag();
        var parameterBands = FREQUENCY_RESOLUTIONS[b.frequencyResolution];
        for (var i = 0; i < properties[2]; i++) {
            b.ottLfe[i] = properties[4 + i] != 0;
            if (b.ottLfe[i]) {
                b.ottBands[i] = reader.read(5);
                if (b.ottBands[i] > parameterBands) {
                    throw new MediaException.Malformed("MPEG Surround LFE box codes " + b.ottBands[i] + " of "
                            + parameterBands + " parameter bands");
                }
            } else {
                b.ottBands[i] = parameterBands;
            }
        }
        if (properties[3] != 0) {
            b.tttDualMode = reader.readFlag();
            b.tttModeLow = reader.read(3);
            if (b.tttDualMode) {
                b.tttModeHigh = reader.read(3);
                b.tttBandsLow = reader.read(5);
                if (b.tttBandsLow > parameterBands) {
                    throw new MediaException.Malformed("MPEG Surround TTT box splits at band " + b.tttBandsLow
                            + " of " + parameterBands);
                }
            }
            if (b.tttModeLow > 5 || (b.tttDualMode && b.tttModeHigh > 5)) {
                throw new MediaException.Malformed("MPEG Surround TTT mode is reserved");
            }
        }
        if (b.temporalShaping == 2) {
            b.envelopeQuantization = reader.read(1);
        }
        if (b.threeDimensional) {
            // TODO: a 3D stereo downmix (bs3DaudioMode of ISO/IEC 23003-1) needs its HRTF parameters
            //  (bs3DaudioHRTFset) and the 3D upmix of the reference decoder's binaural processing.
            throw new MediaException.Unsupported("mpeg surround 3d downmix",
                    "MPEG Surround with a 3D stereo downmix (bs3DaudioMode) is not supported");
        }
        var aligned = (int) ((reader.bitPosition() - start) & 7);
        if (aligned != 0) {
            reader.read(8 - aligned);
        }
        readExtensions(reader, b, properties, parameterBands, bits - (reader.bitPosition() - start));
        reader.checkNotOverrun();
        return new MpsConfig(b);
    }

    /**
     * Holds the {@code AudioSpecificConfig} of an MPEG Surround elementary stream (object type 30).
     *
     * @param config the {@code SpatialSpecificConfig}
     * @param embedded whether the spatial frames come embedded in the downmix stream
     * ({@code sacPayloadEmbedding} 1) rather than one per access unit of this stream
     */
    public record Stream(MpsConfig config, boolean embedded) {
    }

    /**
     * The audio object type of MPEG Surround.
     */
    public static final int AOT_MPEG_SURROUND = 30;

    /**
     * Parses the {@code AudioSpecificConfig} of an MPEG Surround elementary stream (ISO/IEC 14496-3 subclause
     * 1.6.2.1, object type 30).
     *
     * @param data the configuration bytes
     * @return the stream's configuration
     * @throws MediaException.Malformed if the configuration is truncated, invalid or of another object type
     * @throws MediaException.Unsupported if the {@code SpatialSpecificConfig} uses an unsupported tool
     */
    public static Stream readAudioSpecificConfig(byte[] data) {
        var reader = new AacBitReader(data);
        var embedded = readPayloadEmbedding(reader);
        var config = read(reader, Math.max(0, reader.bitsLeft()));
        return new Stream(config, embedded);
    }

    /**
     * Returns whether the {@code AudioSpecificConfig} of an MPEG Surround elementary stream embeds the spatial
     * frames in the downmix stream ({@code sacPayloadEmbedding}), without reading its {@code SpatialSpecificConfig}.
     *
     * @param data the configuration bytes
     * @return {@code true} when the frames come in the fill elements of the downmix stream
     * @throws MediaException.Malformed if the configuration is truncated or of another object type
     */
    public static boolean embeddedPayload(byte[] data) {
        var reader = new AacBitReader(data);
        var embedded = readPayloadEmbedding(reader);
        reader.checkNotOverrun();
        return embedded;
    }

    private static boolean readPayloadEmbedding(AacBitReader reader) {
        var type = reader.read(5);
        if (type == 31) {
            type = 32 + reader.read(6);
        }
        if (type != AOT_MPEG_SURROUND) {
            throw new MediaException.Malformed("AudioSpecificConfig of object type " + type + " is not MPEG Surround");
        }
        if (reader.read(4) == 15) {
            reader.read(24);
        }
        reader.read(4);
        return reader.readFlag();
    }

    private static void readExtensions(AacBitReader reader, Builder b, int[] properties, int parameterBands,
                                       long available) {
        while (available >= 8) {
            var type = reader.read(4);
            var length = reader.read(4);
            available -= 8;
            if (length == 15) {
                length += reader.read(8);
                available -= 8;
                if (length == 15 + 255) {
                    length += reader.read(16);
                    available -= 16;
                }
            }
            if (length <= 0) {
                break;
            }
            if (b.extensionCount == MAX_EXTENSIONS) {
                throw new MediaException.Malformed("MPEG Surround configuration has more than " + MAX_EXTENSIONS
                        + " extensions");
            }
            b.extensionTypes[b.extensionCount++] = type;
            var begin = reader.bitPosition();
            switch (type) {
                case EXTENSION_RESIDUAL -> {
                    b.residualCoding = true;
                    b.residualSamplingIndex = reader.read(4);
                    b.residualFrames = reader.read(2) + 1;
                    for (var i = 0; i < properties[2] + properties[3]; i++) {
                        b.residualPresent[i] = reader.readFlag();
                        if (b.residualPresent[i]) {
                            b.residualBands[i] = reader.read(5);
                            if (b.residualBands[i] > parameterBands) {
                                throw new MediaException.Malformed("MPEG Surround residual codes "
                                        + b.residualBands[i] + " of " + parameterBands + " parameter bands");
                            }
                        }
                    }
                }
                case EXTENSION_ARBITRARY_DOWNMIX -> {
                    b.arbitraryDownmix = 2;
                    b.downmixResidualSamplingIndex = reader.read(4);
                    b.downmixResidualFrames = reader.read(2) + 1;
                    b.downmixResidualBands = reader.read(5);
                    if (b.downmixResidualBands > parameterBands) {
                        throw new MediaException.Malformed("MPEG Surround downmix residual codes "
                                + b.downmixResidualBands + " of " + parameterBands + " parameter bands");
                    }
                }
                // TODO: the arbitrary tree extension adds output channels through boxes of its own
                //  (bsOttBoxPresentAT, bsOutputChannelPosAT); the upmix decodes the tree without them.
                case EXTENSION_ARBITRARY_TREE -> b.arbitraryTree = true;
                default -> {
                }
            }
            var consumed = reader.bitPosition() - begin;
            var fill = 8L * length - consumed;
            if (fill < 0) {
                throw new MediaException.Malformed("MPEG Surround extension configuration overruns its length");
            }
            reader.skip(fill);
            available -= 8L * length;
        }
    }

    /**
     * {@return the sampling frequency the configuration declares, in hertz}
     */
    public int samplingFrequency() {
        return samplingFrequency;
    }

    /**
     * {@return the number of QMF slots of a spatial frame}
     */
    public int frameLength() {
        return frameLength;
    }

    /**
     * {@return the tree configuration, one of the {@code TREE_*} constants}
     */
    public int treeConfig() {
        return treeConfig;
    }

    /**
     * {@return the number of downmix channels the tree takes}
     */
    public int inputChannels() {
        return TREE_PROPERTIES[treeConfig][0];
    }

    /**
     * {@return the number of channels the tree outputs}
     */
    public int outputChannels() {
        return TREE_PROPERTIES[treeConfig][1];
    }
}
