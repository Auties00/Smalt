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
     * The reference clocks in Hz by {@code nuRefClockCode}, {@code 0} where reserved (Table 7-3).
     */
    private static final int[] REFERENCE_CLOCKS = {32000, 44100, 48000, 0};

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
     * The reference clock of {@link #frameDuration} in Hz ({@code nuRefClockCode}), or {@code 0}.
     */
    private int referenceClock;

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
     * Whether the XLL component of the asset signals a sync point ({@code bExSSXLLSyncPresent}).
     */
    private boolean xllSyncPresent;

    /**
     * The peak bit rate smoothing buffer size of the XLL component of the asset ({@code nuPeakBRCntrlBuffSzkB}), in
     * units of 16 kB, 0 for none.
     */
    private int xllPeakBuffer;

    /**
     * The number of frames to wait after a sync point before the first XLL frame is decoded
     * ({@code nuInitLLDecDlyFrames}).
     */
    private int xllDelay;

    /**
     * The offset of the first XLL frame in the XLL component of the asset, in bytes ({@code nuExSSXLLSyncOffset}).
     */
    private int xllSyncOffset;

    /**
     * The XLL data received and not yet decoded while a peak bit rate smoothing buffer is in use, from index 0.
     */
    private byte[] smoothing = new byte[0];

    /**
     * The number of bytes held in {@link #smoothing}.
     */
    private int smoothingLength;

    /**
     * The most bytes {@link #smoothing} may hold, from the buffer size of the sync point.
     */
    private int smoothingLimit;

    /**
     * The number of frames still to wait before the first XLL frame of the buffer is decoded.
     */
    private int smoothingDelay;

    /**
     * Whether the XLL data goes through {@link #smoothing}: from a sync point that uses it to a reset or an error.
     */
    private boolean smoothingActive;

    /**
     * The scratch copy of the buffered XLL data and the component of the frame, which {@link #losslessRatio} reads
     * without consuming them.
     */
    private byte[] peek = new byte[0];

    /**
     * The bytes holding the XLL frame located by {@link #locateXll}.
     */
    private byte[] xllData;

    /**
     * The index of the XLL frame located by {@link #locateXll}.
     */
    private int xllOffset;

    /**
     * The number of bytes of the XLL frame located by {@link #locateXll}.
     */
    private int xllLength;

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
     * Decodes an extension substream frame that follows a core frame, applying its components to the core. An XLL
     * component replaces the core output with the lossless one ({@link #ownOutput()}), for which the core frame must
     * have been decoded with {@link CoreDecoder#setFixedPoint} on ({@link #losslessRatio} tells with which ratio); that
     * reconstruction leaves an X96 component out.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the frame size
     * @param core the core decoder, its frame decoded
     * @throws MediaException.Malformed if the frame breaks the syntax
     * @throws MediaException.Unsupported if a component needs a feature this decoder does not implement; the
     *         extensions before it are applied to the core
     */
    public void decode(byte[] data, int offset, int length, CoreDecoder core) {
        parse(data, offset, length);
        ownOutput = false;
        if ((components & XXCH) != 0) {
            xxch.decode(data, componentOffsets[6], componentSizes[6], core, reader);
        }
        if ((components & X96) != 0 && !core.fixedPoint()) {
            x96.decodeSubstream(data, componentOffsets[7], componentSizes[7], core);
        }
        if ((components & XBR) != 0) {
            xbr.decode(data, componentOffsets[5], componentSizes[5], core);
        }
        if ((components & XLL) != 0 && locateXll(data, true)) {
            decodeXll(core);
            ownOutput = true;
        }
    }

    /**
     * {@return the ratio of the rate of band 0 of the XLL channels coded against a core to the rate of that core, 1 or
     * 2, when this decoder can add them to the core, or {@code 0}}
     *
     * <p>A frame that breaks the syntax codes none this decoder can add, nor does a frame that decodes no XLL frame
     * while it waits for the delay of a peak bit rate smoothing buffer. The data of the frame is not consumed: the
     * {@link #decode} of the same frame follows.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the frame size
     * @param coreRate the sample rate of the core
     */
    public int losslessRatio(byte[] data, int offset, int length, int coreRate) {
        try {
            parse(data, offset, length);
            if ((components & XLL) == 0 || !locateXll(data, false)) {
                return 0;
            }
            return xll.coreRatio(xllData, xllOffset, xllLength, this, coreRate);
        } catch (MediaException.Malformed | MediaException.Unsupported unusable) {
            return 0;
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
            if (locateXll(data, true)) {
                decodeXll(null);
                ownOutput = true;
            }
        } else if ((components & LBR) != 0) {
            lbr.decode(data, componentOffsets[8], componentSizes[8], this);
            ownOutput = true;
        }
        return ownOutput;
    }

    /**
     * {@return whether the last frame decoded by {@link #decode} or {@link #decodeAlone} has an XLL component but
     * decoded no XLL frame, because the delay of the sync point of its peak bit rate smoothing buffer has not run out
     * or no sync point was found yet}
     */
    public boolean waitingForLossless() {
        return (components & XLL) != 0 && !ownOutput;
    }

    /**
     * {@return the number of samples per channel of the muted output of a frame that waits for its lossless data
     * without a core (clause 7.5.2, {@code nuInitLLDecDlyFrames}): the frame duration at the rate of the asset, or
     * {@code 0} when the substream header does not give it}
     */
    public int mutedSamples() {
        if (referenceClock == 0 || maxSampleRate == 0) {
            return 0;
        }
        var samples = (long) frameDuration * maxSampleRate;
        return samples % referenceClock == 0 ? (int) (samples / referenceClock) : 0;
    }

    /**
     * {@return the sample rate of the muted output of a frame that waits for its lossless data without a core: the
     * rate of the asset}
     */
    public int mutedRate() {
        return maxSampleRate;
    }

    /**
     * {@return the DTS speaker bit of each channel of the muted output of a frame that waits for its lossless data
     * without a core, from the speaker activity mask of the asset, or an empty array when the asset gives no mask for
     * every channel}
     */
    public int[] mutedMasks() {
        var expanded = Speakers.expand(speakerMask);
        if (speakerMask == 0 || Integer.bitCount(expanded) != totalChannels) {
            return new int[0];
        }
        var masks = new int[totalChannels];
        var ch = 0;
        for (var bit = 0; bit < 32; bit++) {
            if ((expanded >> bit & 1) != 0) {
                masks[ch++] = 1 << bit;
            }
        }
        return masks;
    }

    /**
     * Locates the XLL frame the current extension substream frame decodes into {@link #xllData}, {@link #xllOffset}
     * and {@link #xllLength}.
     *
     * <p>An asset without a peak bit rate smoothing buffer carries one whole XLL frame in each component. One with a
     * buffer (a sync point with a buffer size, a decoding delay or an offset to the first XLL sync word) sends the XLL
     * frames as a byte stream cut at any point and ahead of their time (clause 7.5.2): from a sync point on, the data
     * after its offset fills the buffer, the first XLL frame is decoded once the delay of the sync point has run out,
     * and every frame after that decodes the next one, which the buffer must then hold whole. A frame that waits
     * decodes no XLL frame. Before the first such sync point a frame is read as one whole XLL frame, unless it has no
     * sync point and does not start with a whole XLL frame: a decoder that joins a buffered stream between its sync
     * points cannot synchronize there (clause 7.5.2, {@code bExSSXLLSyncPresent}), so the frame decodes no XLL frame.
     *
     * @param data the bytes of the extension substream frame, parsed
     * @param commit whether to consume the data of the frame: append it to the buffer and count the delay down;
     *               without, the buffer is left as it was and the frame located in {@link #peek}
     * @return whether the frame decodes an XLL frame
     * @throws MediaException.Malformed if the sync point or the buffer breaks its limits: an offset past the component,
     *         a delay or more data than the buffer holds, or a buffer without the whole next XLL frame; a consumed
     *         frame then empties the buffer, so that decoding resumes at the next sync point
     */
    private boolean locateXll(byte[] data, boolean commit) {
        var component = componentOffsets[9];
        var size = componentSizes[9];
        var startsBuffer = !smoothingActive && xllSyncPresent
                && (xllPeakBuffer != 0 || xllDelay != 0 || xllSyncOffset != 0);
        if (!smoothingActive && !startsBuffer) {
            if (!xllSyncPresent) {
                var frame = XllDecoder.frameSize(data, component, size);
                if (frame < 16 || frame > size) {
                    return false;
                }
            }
            xllData = data;
            xllOffset = component;
            xllLength = size;
            return true;
        }
        var held = smoothingActive ? smoothingLength : 0;
        var from = smoothingActive ? 0 : xllSyncOffset;
        var limit = smoothingActive ? smoothingLimit : smoothingLimit(xllPeakBuffer);
        var delay = smoothingActive ? smoothingDelay : xllDelay;
        if (from > size) {
            throw failSmoothing(commit, "DTS XLL sync offset " + from + " past a component of " + size + " bytes");
        }
        if (delay > limit) {
            throw failSmoothing(commit, "DTS XLL decoding delay of " + delay + " frames");
        }
        var total = held + size - from;
        if (total > limit) {
            throw failSmoothing(commit, "DTS XLL data of " + total + " bytes overflows a smoothing buffer of "
                    + limit);
        }
        var target = commit ? smoothing : peek;
        if (target.length < total) {
            target = Arrays.copyOf(target, Math.min(limit, Math.max(total, 2 * target.length)));
            if (commit) {
                smoothing = target;
            } else {
                peek = target;
            }
        }
        if (!commit) {
            System.arraycopy(smoothing, 0, target, 0, held);
        }
        System.arraycopy(data, component + from, target, held, size - from);
        if (commit) {
            smoothingActive = true;
            smoothingLength = total;
            smoothingLimit = limit;
            smoothingDelay = Math.max(delay - 1, 0);
        }
        if (delay > 0) {
            return false;
        }
        var frame = XllDecoder.frameSize(target, 0, total);
        if (frame < 16 || frame > total) {
            throw failSmoothing(commit, "DTS XLL smoothing buffer of " + total + " bytes holds no whole XLL frame");
        }
        xllData = target;
        xllOffset = 0;
        xllLength = frame;
        return true;
    }

    /**
     * Decodes the XLL frame located by {@link #locateXll} and, when it came from the smoothing buffer, removes it from
     * there; a frame of the buffer that breaks the syntax empties the buffer.
     *
     * @param core the core decoder with its frame decoded, or {@code null}
     */
    private void decodeXll(CoreDecoder core) {
        if (xllData != smoothing) {
            xll.decode(xllData, xllOffset, xllLength, this, core);
            return;
        }
        try {
            xll.decode(xllData, xllOffset, xllLength, this, core);
        } catch (MediaException failure) {
            dropSmoothing();
            throw failure;
        }
        smoothingLength -= xllLength;
        System.arraycopy(smoothing, xllLength, smoothing, 0, smoothingLength);
    }

    /**
     * {@return the error for a smoothing buffer that breaks its limits, after emptying the buffer when the frame is
     * being consumed}
     *
     * @param commit whether the frame is being consumed
     * @param message the description of the broken limit
     */
    private MediaException.Malformed failSmoothing(boolean commit, String message) {
        if (commit) {
            dropSmoothing();
        }
        return new MediaException.Malformed(message);
    }

    /**
     * Empties the smoothing buffer and leaves its synchronization.
     */
    private void dropSmoothing() {
        smoothingActive = false;
        smoothingLength = 0;
        smoothingDelay = 0;
    }

    /**
     * {@return the most bytes a peak bit rate smoothing buffer holds: its size, 16 kB per unit of the 4-bit code, or
     * the largest size, 240 kB, for code 0}
     *
     * @param code the {@code nuPeakBRCntrlBuffSzkB} code
     */
    private static int smoothingLimit(int code) {
        return (code == 0 ? 15 : code) * 16 * 1024;
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
        dropSmoothing();
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
        referenceClock = 0;
        if (staticFields) {
            referenceClock = REFERENCE_CLOCKS[reader.read(2)];
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
        xllPeakBuffer = 0;
        xllDelay = 0;
        xllSyncOffset = 0;
        xllSyncPresent = reader.readFlag();
        if (xllSyncPresent) {
            xllPeakBuffer = reader.read(4);
            var delayBits = reader.read(5) + 1;
            xllDelay = (int) Math.min(reader.read(delayBits) & 0xFFFFFFFFL, Integer.MAX_VALUE);
            xllSyncOffset = reader.read(sizeBits);
        }
    }
}
