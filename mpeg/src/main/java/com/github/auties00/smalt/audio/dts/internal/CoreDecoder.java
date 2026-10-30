package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.Arrays;

/**
 * Decodes the DTS core (ETSI TS 102 114 clause 5) and the extensions carried in the core frame, XCh (6.4), XXCH
 * (6.5) and X96 (6.2), to subband samples, and synthesizes them to PCM.
 *
 * <p>A frame is decoded in two steps: {@link #decode} reads the core and its extensions into the subband buffer, and
 * {@link #synthesize} turns every channel into PCM; the extensions of the extension substream modify the subband
 * samples in between. Output samples are scaled so that the 24-bit full scale maps to 1.0.
 *
 * <p>Instances are not thread-safe.
 */
public final class CoreDecoder {
    /**
     * The XCh sync word.
     */
    static final int XCH_SYNC = 0x5A5A5A5A;

    /**
     * The X96 sync word.
     */
    static final int X96_SYNC = 0x1D95F262;

    /**
     * The XXCH sync word.
     */
    static final int XXCH_SYNC = 0x47004A03;

    /**
     * The DTS speaker mask bits of the channels of each AMODE up to 9, in coding order.
     */
    private static final int[][] AMODE_MASKS = {
            {Speakers.C}, {Speakers.L, Speakers.R}, {Speakers.L, Speakers.R}, {Speakers.L, Speakers.R},
            {Speakers.L, Speakers.R}, {Speakers.C, Speakers.L, Speakers.R}, {Speakers.L, Speakers.R, Speakers.CS},
            {Speakers.C, Speakers.L, Speakers.R, Speakers.CS}, {Speakers.L, Speakers.R, Speakers.LS, Speakers.RS},
            {Speakers.C, Speakers.L, Speakers.R, Speakers.LS, Speakers.RS}};

    /**
     * The output scale: 24-bit full scale to 1.0.
     */
    private static final float OUTPUT_SCALE = 1.0f / (1 << 23);

    /**
     * The output scale of the subband synthesis: the 24-bit full scale to 1.0 times the gain of the synthesis
     * output stage ({@code rScale} of C.3.6, which the specification leaves unstated), 2^7.5, as measured against
     * the deployed decoders.
     */
    private static final float SYNTHESIS_SCALE = (float) (StrictMath.sqrt(2) * 128 / (1 << 23));

    /**
     * The output scale of the 64-band synthesis of X96: twice {@link #SYNTHESIS_SCALE}, as measured against the
     * deployed decoders (the 1024-tap prototype of D.9 has half the passband gain of the 512-tap ones).
     */
    private static final float X96_SYNTHESIS_SCALE = 2 * SYNTHESIS_SCALE;

    /**
     * The target of a downmix operation that stands for the LFE channel.
     */
    private static final int LFE_PLANE = -2;

    /**
     * The attenuation the encoder applies when it mixes an extension channel into the core (-3 dB).
     */
    private static final float MINUS_3DB = 0.70710678f;

    /**
     * The header of the current frame.
     */
    private final CoreHeader header = new CoreHeader();

    /**
     * The subband samples of the current frame, with their history.
     */
    private final SubbandBuffer buffer = new SubbandBuffer();

    /**
     * The primary channel set.
     */
    private final ChannelSet primary = new ChannelSet();

    /**
     * The XCh channel set.
     */
    private final ChannelSet xch = new ChannelSet();

    /**
     * The decoder of the XXCH extension in the core frame.
     */
    private final XxchDecoder xxch = new XxchDecoder();

    /**
     * The decoder of the X96 extension in the core frame.
     */
    private final X96Decoder x96 = new X96Decoder();

    /**
     * The 32-band synthesis filter bank by channel.
     */
    private final Qmf32[] qmf32 = new Qmf32[SubbandBuffer.MAX_CHANNELS];

    /**
     * The 64-band synthesis filter bank by channel.
     */
    private final Qmf64[] qmf64 = new Qmf64[SubbandBuffer.MAX_CHANNELS];

    /**
     * The LFE interpolation filter.
     */
    private final LfeInterpolator lfeInterpolator = new LfeInterpolator();

    /**
     * The reader of the frame.
     */
    private final MsbBitReader reader = new MsbBitReader(new byte[0]);

    /**
     * The number of subsubframes by subframe.
     */
    private final int[] subsubframeCounts = new int[16];

    /**
     * The index of the first subband sample of each subframe, then the sample count.
     */
    private final int[] subframeOffsets = new int[17];

    /**
     * The decimated LFE samples of the frame.
     */
    private final float[] lfeDecimated = new float[64];

    /**
     * The DTS speaker bit by channel.
     */
    private final int[] masks = new int[SubbandBuffer.MAX_CHANNELS];

    /**
     * The LFE codes of the subframe being read.
     */
    private final int[] lfeCodes = new int[16];

    /**
     * The transient mode by subframe, channel and subband, for the XBR extension.
     */
    private final int[][][] transients = new int[16][SubbandBuffer.MAX_CHANNELS][32];

    /**
     * The scale factor code book by channel, for the XBR extension.
     */
    private final int[] scaleBooks = new int[SubbandBuffer.MAX_CHANNELS];

    /**
     * The source channel of each recorded downmix operation, {@code -1} for a scaling.
     */
    private final int[] operationSources = new int[256];

    /**
     * The target channel of each recorded downmix operation.
     */
    private final int[] operationTargets = new int[256];

    /**
     * The coefficient or scale of each recorded downmix operation.
     */
    private final float[] operationGains = new float[256];

    /**
     * The number of recorded downmix reversals of the frame.
     */
    private int operations;

    /**
     * Whether the frame was synthesized, which a frame is once.
     */
    private boolean synthesized;

    /**
     * The LFE samples at the core rate, before the 2x interpolation of X96.
     */
    private final float[] lfeX96 = new float[8192];

    /**
     * The last two LFE samples at the core rate of the previous frame.
     */
    private final float[] lfeHistory = new float[2];

    /**
     * The synthesized samples by channel, the LFE channel last.
     */
    private final float[][] pcm = new float[SubbandBuffer.MAX_CHANNELS + 1][8192];

    /**
     * The number of subframes of the frame.
     */
    private int subframes;

    /**
     * The number of subband samples per subband of the frame.
     */
    private int samples;

    /**
     * The number of decimated LFE samples of the frame.
     */
    private int lfeSamples;

    /**
     * The number of channels decoded so far, LFE excluded.
     */
    private int channels;

    /**
     * Whether the frame decoded X96 data, which doubles the output rate.
     */
    private boolean x96Present;

    /**
     * Whether the previous frame was decoded with X96, whose synthesis history differs.
     */
    private boolean previousX96;

    /**
     * The step size table of the frame.
     */
    private int[] steps;

    /**
     * Constructs a decoder.
     */
    public CoreDecoder() {
        for (var i = 0; i < qmf32.length; i++) {
            qmf32[i] = new Qmf32();
            qmf64[i] = new Qmf64();
        }
        xch.planeBands = primary.planeBands;
    }

    /**
     * Decodes a core frame and the extensions it carries to subband samples.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the number of bytes of the core frame
     * @param extensions whether to decode the XCh, XXCH and X96 extensions
     * @throws MediaException.Malformed if the frame breaks the syntax
     * @throws MediaException.Unsupported if the frame uses a channel arrangement this decoder cannot output
     */
    public void decode(byte[] data, int offset, int length, boolean extensions) {
        reader.reset(data, offset, length);
        header.read(reader);
        if (header.frameSize > length) {
            throw new MediaException.Malformed("DTS frame of " + header.frameSize + " bytes cut to " + length);
        }
        if (header.amode > 9) {
            // TODO: AMODE 10 to 15 (more than five channels in the core without XXCH) has no defined coding order.
            throw new MediaException.Unsupported("DTS", "channel arrangement " + header.amode + " is not supported");
        }
        steps = header.rate == 0x1F ? DtsCoreTables.STEP_LOSSLESS : DtsCoreTables.STEP_LOSSY;
        subframes = reader.read(4) + 1;
        var primaryChannels = reader.read(3) + 1;
        if (primaryChannels > 5) {
            throw new MediaException.Malformed("DTS primary channel count " + primaryChannels + " above 5");
        }
        var arrangement = AMODE_MASKS[header.amode];
        if (primaryChannels > arrangement.length) {
            throw new MediaException.Malformed("DTS frame codes " + primaryChannels + " channels for arrangement "
                    + header.amode);
        }
        primary.channels = primaryChannels;
        primary.firstPlane = 0;
        primary.readHeader(reader);
        if (header.crcPresent) {
            reader.skip(16);
        }
        channels = primaryChannels;
        for (var ch = 0; ch < primaryChannels; ch++) {
            masks[ch] = arrangement[ch];
        }
        if (header.predictorHistory) {
            buffer.restoreHistory();
        } else {
            buffer.clearHistory();
        }
        operations = 0;
        synthesized = false;
        x96Present = false;
        var position = 0;
        lfeSamples = 0;
        for (var subframe = 0; subframe < subframes; subframe++) {
            var subsubframes = reader.read(2) + 1;
            var partial = reader.read(3);
            if (partial != 0) {
                // TODO: partial subsubframes of termination frames (PSC above zero) are not decoded.
                throw new MediaException.Unsupported("DTS", "partial subsubframes are not supported");
            }
            if (position + subsubframes * 8 > SubbandBuffer.MAX_SAMPLES) {
                throw new MediaException.Malformed("DTS frame holds more than 128 subband samples");
            }
            subsubframeCounts[subframe] = subsubframes;
            subframeOffsets[subframe] = position;
            primary.readSideInformation(reader, subsubframes);
            recordTransients(primary, subframe);
            if (header.dynamicRange) {
                reader.skip(8);
            }
            if (header.crcPresent) {
                reader.skip(16);
            }
            primary.readVectors(reader, buffer, position, subsubframes);
            if (header.lfe > 0) {
                readLfe(subsubframes);
            }
            for (var subsubframe = 0; subsubframe < subsubframes; subsubframe++) {
                primary.readSubsubframe(reader, buffer, position + 8 * subsubframe, subsubframe, subsubframes, steps);
                if (subsubframe == subsubframes - 1 || header.syncEverySubsubframe) {
                    if (reader.read(16) != 0xFFFF) {
                        throw new MediaException.Malformed("DTS DSYNC word missing");
                    }
                }
            }
            primary.applyJointIntensity(buffer, position, subsubframes * 8);
            primary.clearInactive(buffer, position, subsubframes * 8);
            position += subsubframes * 8;
        }
        subframeOffsets[subframes] = position;
        samples = position;
        if (samples != header.blocks) {
            throw new MediaException.Malformed("DTS frame codes " + samples + " subband samples for "
                    + header.blocks + " blocks");
        }
        reader.checkNotOverrun();
        for (var ch = 0; ch < primaryChannels; ch++) {
            buffer.saveHistory(ch, 0, 32, samples);
            buffer.clearBands(ch, 32, samples);
        }
        if (header.amode == 3 || header.frontSumDifference) {
            var left = header.amode >= 5 && header.amode != 6 && header.amode != 8 ? 1 : 0;
            sumDifference(left, left + 1);
        }
        if (header.surroundSumDifference && primaryChannels >= 4 && header.amode >= 8) {
            sumDifference(primaryChannels - 2, primaryChannels - 1);
        }
        if (extensions && header.extension) {
            decodeExtensions(data, offset);
        }
    }

    /**
     * Reads the decimated LFE samples of one subframe (Table 5-29, {@code LFE}).
     */
    private void readLfe(int subsubframes) {
        var count = 2 * header.lfe * subsubframes;
        var codes = lfeCodes;
        for (var n = 0; n < count; n++) {
            codes[n] = reader.readSigned(8);
        }
        var index = reader.read(8);
        if (index >= DtsCoreTables.RMS7.length) {
            throw new MediaException.Malformed("invalid DTS LFE scale factor index " + index);
        }
        var scale = DtsCoreTables.RMS7[index] * 0.035f;
        for (var n = 0; n < count; n++) {
            lfeDecimated[lfeSamples++] = ChannelSet.clip(codes[n] * scale);
        }
    }

    /**
     * Applies sum/difference decoding to a pair of channels in the subband domain (clause C.3.5).
     */
    private void sumDifference(int first, int second) {
        var a = buffer.planes[first];
        var b = buffer.planes[second];
        for (var n = 0; n < 32; n++) {
            for (var m = SubbandBuffer.HISTORY; m < SubbandBuffer.HISTORY + samples; m++) {
                var x = a[n][m];
                var y = b[n][m];
                a[n][m] = x + y;
                b[n][m] = x - y;
            }
        }
    }

    /**
     * Locates and decodes the extensions in the rest of the core frame.
     */
    private void decodeExtensions(byte[] data, int offset) {
        var start = (int) ((reader.bitPosition() + 31) >>> 5) << 2;
        var end = header.frameSize;
        for (var position = start; position + 8 <= end; position += 4) {
            var word = readInt(data, offset + position);
            if (word == XCH_SYNC && header.extensionType == 0 && xchFits(data, offset, position, end)) {
                decodeXch(data, offset + position, end - position);
                return;
            }
            if (word == XXCH_SYNC && header.extensionType == 6) {
                if (decodeXxch(data, offset + position, end - position)) {
                    return;
                }
            }
            if (word == X96_SYNC && header.extensionType == 2 && x96Fits(data, offset, position, end)) {
                x96.decodeCore(data, offset + position, end - position, this);
                return;
            }
        }
    }

    /**
     * {@return whether an XCh frame header at a position fits the frame}
     *
     * @param data the bytes
     * @param offset the index of the core sync word
     * @param position the index of the XCh sync word
     * @param end the end of the frame
     */
    private static boolean xchFits(byte[] data, int offset, int position, int end) {
        var size = (readInt(data, offset + position + 4) >>> 22) & 0x3FF;
        var distance = end - position;
        return distance == size + 1 || distance == size;
    }

    /**
     * {@return whether an X96 frame header at a position fits the frame}
     *
     * @param data the bytes
     * @param offset the index of the core sync word
     * @param position the index of the X96 sync word
     * @param end the end of the frame
     */
    private static boolean x96Fits(byte[] data, int offset, int position, int end) {
        var size = ((readInt(data, offset + position + 4) >>> 20) & 0xFFF) + 1;
        var distance = end - position;
        return distance == size || distance == size + 1;
    }

    /**
     * Decodes an XCh extension (clause 6.4): the centre surround channel, which the core mixed into its surround
     * channels.
     */
    private void decodeXch(byte[] data, int offset, int length) {
        reader.reset(data, offset, length);
        reader.skip(32 + 10);
        reader.skip(4);
        var count = reader.read(3) + 1;
        if (count != 1) {
            throw new MediaException.Malformed("DTS XCh codes " + count + " channels");
        }
        xch.channels = 1;
        xch.firstPlane = channels;
        xch.readHeader(reader);
        if (header.crcPresent) {
            reader.skip(16);
        }
        decodeSubframes(xch, false);
        var cs = channels;
        masks[cs] = Speakers.CS;
        channels++;
        var ls = indexOf(Speakers.LS);
        var rs = indexOf(Speakers.RS);
        if (ls >= 0 && rs >= 0) {
            mixOut(cs, ls, MINUS_3DB);
            mixOut(cs, rs, MINUS_3DB);
        }
    }

    /**
     * Decodes the subframes of an extension channel set coded with the core tools, following the subframe and
     * subsubframe partitioning of the core.
     *
     * @param set the channel set, its header read
     * @param syncEveryFrame whether the set ends each subframe with DSYNC only when the core does
     */
    void decodeSubframes(ChannelSet set, boolean syncEveryFrame) {
        decodeExtensionSet(set, reader);
    }

    /**
     * Decodes the subframes of an extension channel set from a reader standing on its first subframe.
     *
     * @param set the channel set, its header read and its first plane set
     * @param reader the bit stream, standing on the first subframe of the set
     */
    void decodeExtensionSet(ChannelSet set, MsbBitReader reader) {
        for (var subframe = 0; subframe < subframes; subframe++) {
            var subsubframes = subsubframeCounts[subframe];
            var position = subframeOffsets[subframe];
            set.readSideInformation(reader, subsubframes);
            recordTransients(set, subframe);
            if (header.crcPresent) {
                reader.skip(16);
            }
            set.readVectors(reader, buffer, position, subsubframes);
            for (var subsubframe = 0; subsubframe < subsubframes; subsubframe++) {
                set.readSubsubframe(reader, buffer, position + 8 * subsubframe, subsubframe, subsubframes, steps);
                if (subsubframe == subsubframes - 1 || header.syncEverySubsubframe) {
                    if (reader.read(16) != 0xFFFF) {
                        throw new MediaException.Malformed("DTS extension DSYNC word missing");
                    }
                }
            }
            set.applyJointIntensity(buffer, position, subsubframes * 8);
            set.clearInactive(buffer, position, subsubframes * 8);
        }
        reader.checkNotOverrun();
        for (var ch = 0; ch < set.channels; ch++) {
            buffer.saveHistory(set.firstPlane + ch, 0, 32, samples);
            buffer.clearBands(set.firstPlane + ch, 32, samples);
        }
    }

    /**
     * Decodes an XXCH extension of the core frame.
     *
     * @return whether the extension was found valid and decoded
     */
    private boolean decodeXxch(byte[] data, int offset, int length) {
        return xxch.decode(data, offset, length, this, reader);
    }

    /**
     * Keeps the transient modes and scale factor code books of a channel set for the XBR extension.
     */
    private void recordTransients(ChannelSet set, int subframe) {
        for (var ch = 0; ch < set.channels; ch++) {
            var plane = set.firstPlane + ch;
            scaleBooks[plane] = set.scaleBook[ch];
            for (var n = 0; n < 32; n++) {
                transients[subframe][plane][n] = set.transientMode(ch, n);
            }
        }
    }

    /**
     * {@return the transient mode of a subband of a channel in a subframe, {@code 0} above the core subbands}
     *
     * @param subframe the index of the subframe in the frame
     * @param plane the channel
     * @param n the subband
     */
    int transientMode(int subframe, int plane, int n) {
        return n < 32 ? transients[subframe][plane][n] : 0;
    }

    /**
     * {@return the scale factor code book ({@code SHUFF}) of a channel}
     *
     * @param plane the channel
     */
    int scaleBook(int plane) {
        return scaleBooks[plane];
    }

    /**
     * Scales the subband samples of a channel.
     *
     * @param plane the channel
     * @param factor the multiplier of every subband sample
     */
    void scaleChannel(int plane, float factor) {
        addOperation(-1, plane, factor);
    }

    /**
     * Scales the decimated LFE samples of the frame.
     *
     * @param factor the multiplier of every LFE sample
     */
    void scaleLfe(float factor) {
        addOperation(-1, LFE_PLANE, factor);
    }

    /**
     * Records an operation that undoes an embedded downmix, applied at synthesis.
     *
     * @param source the extension channel, or {@code -1} to scale the target
     * @param target the channel the downmix went into
     * @param gain the coefficient or the scale
     */
    private void addOperation(int source, int target, float gain) {
        if (operations == operationSources.length) {
            throw new MediaException.Malformed("DTS frame undoes too many downmixes");
        }
        operationSources[operations] = source;
        operationTargets[operations] = target;
        operationGains[operations] = gain;
        operations++;
    }

    /**
     * Applies the recorded downmix reversals to the subband samples.
     */
    private void undoDownmixes() {
        for (var i = 0; i < operations; i++) {
            if (operationSources[i] < 0) {
                applyScale(operationTargets[i], operationGains[i]);
            } else {
                applyMixOut(operationSources[i], operationTargets[i], operationGains[i]);
            }
        }
    }

    /**
     * Scales the subband samples of a channel, or the LFE samples.
     *
     * @param plane the channel, or {@link #LFE_PLANE}
     * @param factor the multiplier
     */
    private void applyScale(int plane, float factor) {
        if (plane == LFE_PLANE) {
            for (var n = 0; n < lfeSamples; n++) {
                lfeDecimated[n] *= factor;
            }
            return;
        }
        for (var n = 0; n < 64; n++) {
            var samples = buffer.planes[plane][n];
            for (var m = SubbandBuffer.HISTORY; m < SubbandBuffer.HISTORY + this.samples; m++) {
                samples[m] *= factor;
            }
        }
    }

    /**
     * Subtracts an extension channel the encoder mixed into a core channel, in the subband domain.
     *
     * @param source the extension channel
     * @param target the channel it was mixed into
     * @param gain the mixing coefficient
     */
    void mixOut(int source, int target, float gain) {
        addOperation(source, target, gain);
    }

    /**
     * Subtracts a channel times a gain from another, in the subband domain.
     *
     * @param source the extension channel
     * @param target the channel it was mixed into
     * @param gain the mixing coefficient
     */
    private void applyMixOut(int source, int target, float gain) {
        var from = buffer.planes[source];
        var to = buffer.planes[target];
        for (var n = 0; n < 64; n++) {
            for (var m = SubbandBuffer.HISTORY; m < SubbandBuffer.HISTORY + samples; m++) {
                to[n][m] -= gain * from[n][m];
            }
        }
    }

    /**
     * Synthesizes every channel into {@link #output(int)}, the embedded downmixes of the channel extensions undone.
     */
    public void synthesize() {
        synthesize(true);
    }

    /**
     * Synthesizes every channel into {@link #output(int)}.
     *
     * @param undo whether to undo the downmixes the channel extensions embedded in the core channels; without, the
     *             core channels keep the downmix they were coded with, as the lossless extension expects
     */
    public void synthesize(boolean undo) {
        if (synthesized) {
            return;
        }
        synthesized = true;
        if (undo) {
            undoDownmixes();
        }
        var out = pcm;
        if (x96Present != previousX96) {
            for (var ch = 0; ch < SubbandBuffer.MAX_CHANNELS; ch++) {
                qmf32[ch].reset();
                qmf64[ch].reset();
            }
            previousX96 = x96Present;
        }
        for (var ch = 0; ch < channels; ch++) {
            if (x96Present) {
                qmf64[ch].synthesize(buffer.planes[ch], 0, samples, out[ch], 0, X96_SYNTHESIS_SCALE);
            } else {
                qmf32[ch].synthesize(buffer.planes[ch], 0, samples, header.perfectReconstruction, out[ch], 0,
                        SYNTHESIS_SCALE);
            }
        }
        if (header.lfe > 0) {
            var target = out[channels];
            var factor = header.lfe == 1 ? 128 : 64;
            if (x96Present) {
                lfeInterpolator.interpolate(lfeDecimated, lfeSamples, factor, lfeX96, 0, OUTPUT_SCALE);
                X96Decoder.interpolateLfe(lfeX96, lfeSamples * factor, target, lfeHistory);
            } else {
                lfeInterpolator.interpolate(lfeDecimated, lfeSamples, factor, target, 0, OUTPUT_SCALE);
            }
        }
    }

    /**
     * {@return the PCM of a channel of the last synthesized frame, {@link #outputSamples()} samples}
     *
     * @param ch the channel, indexed like {@link #mask(int)}; {@link #channels()} is the LFE channel
     */
    public float[] output(int ch) {
        return pcm[ch];
    }

    /**
     * {@return the PCM of the channel with a DTS speaker bit in the last synthesized frame}
     *
     * @param mask the speaker bit
     * @throws MediaException.Malformed if no channel has the bit
     */
    float[] channelOutput(int mask) {
        if (mask == Speakers.LFE1 && hasLfe()) {
            return pcm[channels];
        }
        var index = indexOf(mask);
        if (index < 0) {
            index = indexOf(switch (mask) {
                case Speakers.LS -> Speakers.LSS;
                case Speakers.RS -> Speakers.RSS;
                case Speakers.LSS -> Speakers.LS;
                case Speakers.RSS -> Speakers.RS;
                default -> mask;
            });
        }
        if (index < 0) {
            throw new MediaException.Malformed("DTS core has no channel for speaker bit 0x" + Integer.toHexString(mask));
        }
        return pcm[index];
    }

    /**
     * {@return the header of the last decoded frame}
     */
    public CoreHeader header() {
        return header;
    }

    /**
     * {@return the active subband count by plane, which the channel sets of the extensions share with the core}
     */
    int[] planeBands() {
        return primary.planeBands;
    }

    /**
     * {@return the number of channels of the last decoded frame, LFE excluded}
     */
    public int channels() {
        return channels;
    }

    /**
     * {@return whether the last decoded frame has an LFE channel}
     */
    public boolean hasLfe() {
        return header.lfe > 0;
    }

    /**
     * {@return the DTS speaker mask bit of a decoded channel}
     *
     * @param ch the channel, from 0 to {@link #channels()}; {@link #channels()} is the LFE channel
     */
    public int mask(int ch) {
        return ch == channels ? Speakers.LFE1 : masks[ch];
    }

    /**
     * {@return the number of PCM samples per channel the last decoded frame synthesizes}
     */
    public int outputSamples() {
        return samples * (x96Present ? 64 : 32);
    }

    /**
     * {@return the output sample rate of the last decoded frame}
     */
    public int outputRate() {
        return header.sampleRate * (x96Present ? 2 : 1);
    }

    /**
     * {@return the subband buffer}
     */
    SubbandBuffer buffer() {
        return buffer;
    }

    /**
     * {@return the number of subframes of the frame}
     */
    int subframes() {
        return subframes;
    }

    /**
     * {@return the number of subsubframes of a subframe}
     *
     * @param subframe the index of the subframe in the frame
     */
    int subsubframes(int subframe) {
        return subsubframeCounts[subframe];
    }

    /**
     * {@return the index of the first subband sample of a subframe}
     *
     * @param subframe the index of the subframe in the frame
     */
    int subframeOffset(int subframe) {
        return subframeOffsets[subframe];
    }

    /**
     * {@return the number of subband samples per subband of the frame}
     */
    int samples() {
        return samples;
    }

    /**
     * {@return the primary channel set}
     */
    ChannelSet primary() {
        return primary;
    }

    /**
     * {@return the step size table of the frame}
     */
    int[] steps() {
        return steps;
    }

    /**
     * Records a channel added by an extension.
     *
     * @param mask the DTS speaker mask bit of the channel
     * @return the plane of the channel
     */
    int addChannel(int mask) {
        if (channels >= SubbandBuffer.MAX_CHANNELS - 1) {
            throw new MediaException.Malformed("DTS frame codes more than " + (SubbandBuffer.MAX_CHANNELS - 1)
                    + " channels");
        }
        masks[channels] = mask;
        return channels++;
    }

    /**
     * Gives the primary channels the speakers of a core loudspeaker activity mask (Table 6-22), which an XXCH
     * extension carries to tell the speaker layout of more than 5.1 channels: the side surround speakers rather
     * than the surround ones of the core arrangement, for example. A mask that does not name as many speakers,
     * LFE excluded, as the core codes channels is ignored.
     *
     * @param mask the speaker bits of the core channels, LFE included or not
     */
    void assignCoreSpeakers(int mask) {
        var speakers = mask & ~Speakers.LFE1;
        if (Integer.bitCount(speakers) != primary.channels) {
            return;
        }
        var ch = 0;
        for (var bit = 0; bit < 32; bit++) {
            if ((speakers >> bit & 1) != 0) {
                masks[ch++] = 1 << bit;
            }
        }
    }

    /**
     * {@return the channel of a DTS speaker mask bit, or {@code -1}}
     *
     * @param mask the bit
     */
    int indexOf(int mask) {
        for (var ch = 0; ch < channels; ch++) {
            if (masks[ch] == mask) {
                return ch;
            }
        }
        return -1;
    }

    /**
     * Marks the frame as decoded with X96, which doubles the output rate.
     */
    void markX96() {
        x96Present = true;
    }

    /**
     * Clears the decoder state, as at the start of a stream.
     */
    public void reset() {
        for (var ch = 0; ch < SubbandBuffer.MAX_CHANNELS; ch++) {
            qmf32[ch].reset();
            qmf64[ch].reset();
        }
        lfeInterpolator.reset();
        buffer.clearHistory();
        buffer.clearSaved();
        Arrays.fill(masks, 0);
        channels = 0;
    }

    /**
     * {@return the big-endian 32-bit word at an index}
     *
     * @param data the bytes
     * @param offset the index
     */
    static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }
}
