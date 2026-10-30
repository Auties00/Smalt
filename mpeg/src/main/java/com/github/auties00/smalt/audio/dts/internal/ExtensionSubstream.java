package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.Arrays;

/**
 * Parses a DTS-HD extension substream frame (ETSI TS 102 114 clause 7.5) and decodes the components of its first
 * audio asset: the XBR, XXCH and X96 extensions of a core, and the XLL lossless and LBR low bit rate codings.
 *
 * <p>Instances are not thread-safe.
 */
public final class ExtensionSubstream {
    /**
     * The core component of the extension substream.
     */
    static final int CORE = 0x010;

    /**
     * The XBR component.
     */
    static final int XBR = 0x020;

    /**
     * The XXCH component.
     */
    static final int XXCH = 0x040;

    /**
     * The X96 component.
     */
    static final int X96 = 0x080;

    /**
     * The LBR component.
     */
    static final int LBR = 0x100;

    /**
     * The XLL component.
     */
    static final int XLL = 0x200;

    /**
     * The sample rates by {@code nuMaxSampleRate} (Table 7-9).
     */
    static final int[] SAMPLE_RATES = {8000, 16000, 32000, 64000, 128000, 22050, 44100, 88200, 176400, 352800,
            12000, 24000, 48000, 96000, 192000, 384000};

    /**
     * The reader of the substream.
     */
    private final MsbBitReader reader = new MsbBitReader(new byte[0]);

    /**
     * The decoder of the XBR extension.
     */
    private final XbrDecoder xbr = new XbrDecoder();

    /**
     * The decoder of the XXCH extension.
     */
    private final XxchDecoder xxch = new XxchDecoder();

    /**
     * The decoder of the X96 extension.
     */
    private final X96Decoder x96 = new X96Decoder();

    /**
     * The decoder of the XLL extension.
     */
    private final XllDecoder xll = new XllDecoder();

    /**
     * The decoder of the LBR header.
     */
    private final LbrDecoder lbr = new LbrDecoder();

    /**
     * The offset of each extension component of the asset.
     */
    private final int[] componentOffsets = new int[12];

    /**
     * The size of each extension component of the asset.
     */
    private final int[] componentSizes = new int[12];

    /**
     * The frame duration in samples at the reference clock ({@code nuExSSFrameDurationCode}), or {@code 0}.
     */
    int frameDuration;

    /**
     * The coding mode of the asset ({@code nuCodingMode}).
     */
    int codingMode;

    /**
     * The components of the asset ({@code nuCoreExtensionMask}, or the mode's single component).
     */
    int components;

    /**
     * The bit resolution of the asset.
     */
    int bitResolution;

    /**
     * The sample rate of the asset.
     */
    int maxSampleRate;

    /**
     * The total channel count of the asset.
     */
    int totalChannels;

    /**
     * The speaker activity mask of the asset (Table 7-10), or {@code 0}.
     */
    int speakerMask;

    /**
     * Whether each channel of the asset feeds one loudspeaker ({@code bOne2OneMapChannels2Speakers}).
     */
    boolean oneToOne;

    /**
     * The dialogue normalization gain of the asset in dB ({@code -nuDialNormCode}), or {@code 0} when the asset
     * signals none.
     */
    int dialogueNormalization;

    /**
     * Whether the last decoded frame produced output of its own (XLL or LBR) rather than modifying the core.
     */
    private boolean ownOutput;

    /**
     * The active substream mask of each presentation ({@code nuActiveExSSMask}).
     */
    private final int[] activeSubstreams = new int[8];

    /**
     * The size of each asset in bytes ({@code nuAssetFsize}).
     */
    private final int[] assetSizes = new int[8];

    /**
     * The channel count of each mixing output configuration.
     */
    private final int[] mixOutChannels = new int[4];

    /**
     * The speaker layout of each speaker remapping set ({@code nuSpkrMaskRemapSet}).
     */
    private final int[] remapLayouts = new int[7];

    /**
     * The channel count of the asset and of each embedded downmix, in the order the mixing metadata lists them.
     */
    private final int[] decodedChannels = new int[3];

    /**
     * Returns the size of the extension substream frame at a position, or {@code -1} if the header is invalid.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the number of bytes available
     * @return the frame size in bytes, or {@code -1}
     */
    public int frameSize(byte[] data, int offset, int length) {
        if (length < 12) {
            return -1;
        }
        reader.reset(data, offset, Math.min(length, 16));
        if (reader.read(32) != DtsFraming.EXSS_SYNC) {
            return -1;
        }
        reader.skip(8 + 2);
        var longSizes = reader.readFlag();
        var headerSize = reader.read(longSizes ? 12 : 8) + 1;
        var frameSize = reader.read(longSizes ? 20 : 16) + 1;
        if (headerSize < 16 || frameSize < headerSize) {
            return -1;
        }
        if (headerSize <= length && !DtsCrc.check(data, offset + 5, headerSize - 5)) {
            return -1;
        }
        return frameSize;
    }

    /**
     * Decodes an extension substream frame that follows a core frame, applying its components to the core.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the frame size
     * @param core the core decoder, its frame decoded
     * @throws MediaException.Malformed if the frame breaks the syntax
     * @throws MediaException.Unsupported if the frame carries an XLL extension, which a stream with a core does
     *         not decode yet; the extensions before it are applied to the core
     */
    public void decode(byte[] data, int offset, int length, CoreDecoder core) {
        parse(data, offset, length);
        ownOutput = false;
        if ((components & XXCH) != 0) {
            xxch.decode(data, componentOffsets[6], componentSizes[6], core, reader);
        }
        if ((components & X96) != 0) {
            x96.decodeSubstream(data, componentOffsets[7], componentSizes[7], core);
        }
        if ((components & XBR) != 0) {
            xbr.decode(data, componentOffsets[5], componentSizes[5], core);
        }
        if ((components & XLL) != 0) {
            // TODO: decode the lossless channels coded against a core. Their residuals add to the reference
            // decoder's fixed-point core, which the specification does not define; Smalt's float core leaves them
            // within about 100 least significant bits, but an LFE channel coded against the core and the downmixes
            // a hierarchical channel set embeds into the core come out wrong (the FATE xll_51_24_48_768 and
            // xll_71_24_48_768 streams), so the core alone is output until those match.
            throw new MediaException.Unsupported("DTS", "the XLL extension of a stream with a core");
        }
    }

    /**
     * Decodes an extension substream frame that carries no core, as DTS-HD Master Audio without core and DTS
     * Express do.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the frame size
     * @return whether the frame produced output
     * @throws MediaException.Malformed if the frame breaks the syntax
     */
    public boolean decodeAlone(byte[] data, int offset, int length) {
        parse(data, offset, length);
        ownOutput = false;
        if ((components & XLL) != 0) {
            xll.decode(data, componentOffsets[9], componentSizes[9], this, null);
            ownOutput = true;
        } else if ((components & LBR) != 0) {
            lbr.decode(data, componentOffsets[8], componentSizes[8], this);
            ownOutput = true;
        }
        return ownOutput;
    }

    /**
     * {@return the dialogue normalization gain in dB the asset of the last frame signals, from -31 to 0}
     */
    public int dialogueNormalization() {
        return dialogueNormalization;
    }

    /**
     * {@return whether the last frame decoded by {@link #decode} replaced the core output with its own}
     */
    public boolean ownOutput() {
        return ownOutput;
    }

    /**
     * {@return the number of output samples per channel of the last frame with output of its own}
     */
    public int outputSamples() {
        return (components & XLL) != 0 ? xll.outputSamples() : lbr.outputSamples();
    }

    /**
     * {@return the output sample rate of the last frame with output of its own}
     */
    public int outputRate() {
        return (components & XLL) != 0 ? xll.outputRate() : lbr.outputRate();
    }

    /**
     * {@return the DTS speaker bit of each output channel of the last frame with output of its own}
     */
    public int[] masks() {
        return (components & XLL) != 0 ? xll.masks() : lbr.masks();
    }

    /**
     * {@return the samples of one output channel of the last frame with output of its own}
     *
     * @param ch the channel
     */
    public float[] output(int ch) {
        return (components & XLL) != 0 ? xll.output(ch) : lbr.output(ch);
    }

    /**
     * Clears the decoder state, as at the start of a stream.
     */
    public void reset() {
        xll.reset();
        lbr.reset();
    }

    /**
     * Parses the header and the descriptor of the first asset, locating its components.
     */
    private void parse(byte[] data, int offset, int length) {
        reader.reset(data, offset, length);
        reader.skip(32 + 8);
        var substreamIndex = reader.read(2);
        var longSizes = reader.readFlag();
        var sizeBits = longSizes ? 20 : 16;
        var headerSize = reader.read(longSizes ? 12 : 8) + 1;
        reader.skip(sizeBits);
        var staticFields = reader.readFlag();
        var presentations = 1;
        var assets = 1;
        var mixMetadata = false;
        var mixOutConfigs = 0;
        frameDuration = 0;
        if (staticFields) {
            reader.skip(2);
            frameDuration = 512 * (reader.read(3) + 1);
            if (reader.readFlag()) {
                reader.skip(36);
            }
            presentations = reader.read(3) + 1;
            assets = reader.read(3) + 1;
            for (var p = 0; p < presentations; p++) {
                activeSubstreams[p] = reader.read(substreamIndex + 1);
            }
            for (var p = 0; p < presentations; p++) {
                for (var s = 0; s < substreamIndex + 1; s++) {
                    if ((activeSubstreams[p] >> s & 1) != 0) {
                        reader.skip(8);
                    }
                }
            }
            mixMetadata = reader.readFlag();
            if (mixMetadata) {
                reader.skip(2);
                var maskBits = (reader.read(2) + 1) << 2;
                mixOutConfigs = reader.read(2) + 1;
                for (var c = 0; c < mixOutConfigs; c++) {
                    mixOutChannels[c] = Integer.bitCount(Speakers.expand(reader.read(maskBits)));
                }
            }
        }
        for (var a = 0; a < assets; a++) {
            assetSizes[a] = reader.read(sizeBits) + 1;
        }
        readAssetDescriptor(staticFields, mixMetadata, mixOutConfigs, sizeBits);
        var position = offset + headerSize;
        for (var bit = 0; bit < 12; bit++) {
            componentOffsets[bit] = position;
            if ((components & 1 << bit) != 0) {
                position += componentSizes[bit];
            }
        }
        if (position > offset + headerSize + assetSizes[0] || position > offset + length) {
            throw new MediaException.Malformed("DTS-HD asset components overrun the asset");
        }
    }

    /**
     * Reads the descriptor of the first asset (Tables 7-5 to 7-7).
     */
    private void readAssetDescriptor(boolean staticFields, boolean mixMetadataEnabled, int mixOutConfigs,
                                     int sizeBits) {
        var start = reader.bitPosition();
        var descriptorSize = reader.read(9) + 1;
        reader.skip(3);
        var embeddedStereo = false;
        var embeddedSix = false;
        oneToOne = true;
        speakerMask = 0;
        if (staticFields) {
            if (reader.readFlag()) {
                reader.skip(4);
            }
            if (reader.readFlag()) {
                reader.skip(24);
            }
            if (reader.readFlag()) {
                var bytes = reader.read(10) + 1;
                reader.skip(bytes * 8L);
            }
            bitResolution = reader.read(5) + 1;
            maxSampleRate = SAMPLE_RATES[reader.read(4)];
            totalChannels = reader.read(8) + 1;
            oneToOne = reader.readFlag();
            if (oneToOne) {
                embeddedStereo = totalChannels > 2 && reader.readFlag();
                embeddedSix = totalChannels > 6 && reader.readFlag();
                var maskBits = 0;
                if (reader.readFlag()) {
                    maskBits = (reader.read(2) + 1) << 2;
                    speakerMask = reader.read(maskBits);
                }
                var remapSets = reader.read(3);
                for (var s = 0; s < remapSets; s++) {
                    remapLayouts[s] = reader.read(maskBits);
                }
                for (var s = 0; s < remapSets; s++) {
                    var speakers = Integer.bitCount(Speakers.expand(remapLayouts[s]));
                    var decoded = reader.read(5) + 1;
                    for (var ch = 0; ch < speakers; ch++) {
                        var mask = reader.read(decoded);
                        reader.skip(5L * Integer.bitCount(mask));
                    }
                }
            } else {
                reader.skip(3);
            }
        }
        var drc = reader.readFlag();
        if (drc) {
            reader.skip(8);
        }
        dialogueNormalization = reader.readFlag() ? -reader.read(5) : 0;
        if (drc && embeddedStereo) {
            reader.skip(8);
        }
        var mixMetadataPresent = mixMetadataEnabled && reader.readFlag();
        if (mixMetadataPresent) {
            reader.skip(1 + 6);
            var control = reader.read(2);
            reader.skip(control < 3 ? 3 : 8);
            var perChannel = reader.readFlag();
            for (var c = 0; c < mixOutConfigs; c++) {
                reader.skip(6L * (perChannel ? mixOutChannels[c] : 1));
            }
            var embedded = 0;
            decodedChannels[embedded++] = totalChannels;
            if (embeddedSix) {
                decodedChannels[embedded++] = 6;
            }
            if (embeddedStereo) {
                decodedChannels[embedded++] = 2;
            }
            for (var c = 0; c < mixOutConfigs; c++) {
                for (var e = 0; e < embedded; e++) {
                    for (var ch = 0; ch < decodedChannels[e]; ch++) {
                        var map = reader.read(mixOutChannels[c]);
                        reader.skip(6L * Integer.bitCount(map));
                    }
                }
            }
        }
        Arrays.fill(componentSizes, 0);
        codingMode = reader.read(2);
        switch (codingMode) {
            case 0 -> {
                components = reader.read(12);
                if ((components & CORE) != 0) {
                    componentSizes[4] = reader.read(14) + 1;
                    if (reader.readFlag()) {
                        reader.skip(2);
                    }
                }
                if ((components & XBR) != 0) {
                    componentSizes[5] = reader.read(14) + 1;
                }
                if ((components & XXCH) != 0) {
                    componentSizes[6] = reader.read(14) + 1;
                }
                if ((components & X96) != 0) {
                    componentSizes[7] = reader.read(12) + 1;
                }
                if ((components & LBR) != 0) {
                    componentSizes[8] = reader.read(14) + 1;
                    if (reader.readFlag()) {
                        reader.skip(2);
                    }
                }
                if ((components & XLL) != 0) {
                    readXllSize(sizeBits);
                }
                if ((components & 0x400) != 0) {
                    reader.skip(16);
                }
                if ((components & 0x800) != 0) {
                    reader.skip(16);
                }
            }
            case 1 -> {
                components = XLL;
                readXllSize(sizeBits);
            }
            case 2 -> {
                components = LBR;
                componentSizes[8] = reader.read(14) + 1;
                if (reader.readFlag()) {
                    reader.skip(2);
                }
            }
            default -> components = 0;
        }
        reader.checkNotOverrun();
        if (reader.bitPosition() - start > descriptorSize * 8L) {
            throw new MediaException.Malformed("DTS-HD asset descriptor overruns its size");
        }
        reader.seek(start + descriptorSize * 8L);
    }

    /**
     * Reads the XLL component size and its synchronization fields.
     */
    private void readXllSize(int sizeBits) {
        componentSizes[9] = reader.read(sizeBits) + 1;
        if (reader.readFlag()) {
            reader.skip(4);
            var delayBits = reader.read(5) + 1;
            reader.skip(delayBits);
            reader.skip(sizeBits);
        }
    }
}
