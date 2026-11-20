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
     * The DTS speaker mask bits of the channels of each AMODE (Table 5-4), in coding order up to 9; the primary
     * channels of 10 to 15 take them in the order of the table until an XXCH core loudspeaker mask places them.
     */
    private static final int[][] AMODE_MASKS = {
            {Speakers.C}, {Speakers.L, Speakers.R}, {Speakers.L, Speakers.R}, {Speakers.L, Speakers.R},
            {Speakers.L, Speakers.R}, {Speakers.C, Speakers.L, Speakers.R}, {Speakers.L, Speakers.R, Speakers.CS},
            {Speakers.C, Speakers.L, Speakers.R, Speakers.CS}, {Speakers.L, Speakers.R, Speakers.LS, Speakers.RS},
            {Speakers.C, Speakers.L, Speakers.R, Speakers.LS, Speakers.RS},
            {Speakers.LC, Speakers.RC, Speakers.L, Speakers.R, Speakers.LS, Speakers.RS},
            {Speakers.C, Speakers.L, Speakers.R, Speakers.LSR, Speakers.RSR, Speakers.OH},
            {Speakers.C, Speakers.CS, Speakers.L, Speakers.R, Speakers.LSR, Speakers.RSR},
            {Speakers.LC, Speakers.C, Speakers.RC, Speakers.L, Speakers.R, Speakers.LS, Speakers.RS},
            {Speakers.LC, Speakers.RC, Speakers.L, Speakers.R, Speakers.LS, Speakers.LSR, Speakers.RS, Speakers.RSR},
            {Speakers.LC, Speakers.C, Speakers.RC, Speakers.L, Speakers.R, Speakers.LS, Speakers.CS, Speakers.RS}};

    /**
     * The output scale: 24-bit full scale to 1.0.
     */
    private static final float OUTPUT_SCALE = 1.0f / (1 << 23);

    /**
     * The magnitude the 24-bit samples joined with a silent upper band are clamped to, 16 times full scale, which
     * keeps an overloaded core within the int range of the band synthesis.
     */
    private static final int JOIN_LIMIT = 1 << 27;

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
     * The most subband samples a subframe holds: four subsubframes, the length of a high frequency vector (5.6,
     * {@code HFREQ}).
     */
    private static final int MAX_SUBFRAME_SAMPLES = 32;

    /**
     * The last arrangement ({@code AMODE}) whose channels are all primary channels (Table 5-4).
     */
    private static final int LAST_PRIMARY_AMODE = 9;

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
     * The number of samples of the partial subsubframe ({@code PSC}) by subframe, {@code 0} for none.
     */
    private final int[] partialCounts = new int[16];

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
     * Whether the frame has an arrangement of more than five channels ({@code AMODE} 10 to 15) whose primary channels
     * no XXCH core loudspeaker mask has placed yet.
     */
    private boolean arrangementUnresolved;

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
     * Whether the frame was synthesized at twice the rate of its subband synthesis, its two halves joined as the two
     * frequency bands of XLL with a silent upper band.
     */
    private boolean joined;

    /**
     * Whether the frame codes no X96 data and is synthesized at twice the core rate for a lossless stream of that
     * rate, which interpolates the LFE channel as the fixed-point reconstruction of the lossless extension does.
     */
    private boolean upsampled;

    /**
     * Whether the previous frame was synthesized with {@link #joined}, whose band history continues.
     */
    private boolean previousJoined;

    /**
     * The samples of the frame at twice the rate of its subband synthesis by channel, the LFE channel last, allocated
     * by the first frame synthesized so.
     */
    private float[][] joinedPcm;

    /**
     * The band joining history by channel, the LFE channel last.
     */
    private final int[][] joinHistory = new int[SubbandBuffer.MAX_CHANNELS + 1][XllBandSynthesis.HISTORY];

    /**
     * The 24-bit samples of the lower band being joined.
     */
    private int[] joinLow;

    /**
     * The silent upper band being joined.
     */
    private int[] joinHigh;

    /**
     * The 24-bit joined samples.
     */
    private int[] joinOut;

    /**
     * The ratio of the rate of the lossless channels to the rate of the core for the fixed-point reconstruction the
     * lossless extension codes its residuals against, 1 or 2, or 0 for the floating-point one.
     */
    private int fixedPoint;

    /**
     * The value of {@link #fixedPoint} the frame before the current one was synthesized with.
     */
    private int previousFixed;

    /**
     * The fixed-point 32-band synthesis filter bank by channel.
     */
    private final FixedQmf32[] fixedQmf32 = new FixedQmf32[SubbandBuffer.MAX_CHANNELS];

    /**
     * The fixed-point 64-band synthesis filter bank by channel.
     */
    private final FixedQmf64[] fixedQmf64 = new FixedQmf64[SubbandBuffer.MAX_CHANNELS];

    /**
     * The fixed-point LFE interpolation filter.
     */
    private final FixedLfeInterpolator fixedLfeInterpolator = new FixedLfeInterpolator();

    /**
     * The decimated LFE samples of the frame in the fixed-point reconstruction.
     */
    private final int[] lfeFixed = new int[64];

    /**
     * The fixed-point reconstruction by channel, the LFE channel last, 24-bit samples.
     */
    private int[][] fixedPcm;

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
            fixedQmf32[i] = new FixedQmf32();
            fixedQmf64[i] = new FixedQmf64();
        }
        xch.planeBands = primary.planeBands;
    }

    /**
     * Decodes a core frame and the extensions it carries to subband samples.
     *
     * <p>A termination frame may end its last subframe in a partial subsubframe ({@code PSC}, clause 5.5.1), which the
     * specification describes only as holding {@code PSC} samples per subband after the normal subsubframes, a DSYNC
     * word after it. This decoder reads it as a subsubframe of that many samples: as many Huffman or plain codes per
     * subband, or the block codes that cover them; the DSYNC words of the normal subsubframes as Table 5-29 places
     * them, then one more; the high frequency vectors give the subframe's samples; and the LFE samples stay those of
     * the normal subsubframes, the samples after them interpolated from silence. The XCh, XXCH, X96 and XBR data of
     * the frame follow the same partition.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the number of bytes of the core frame
     * @param extensions whether to decode the XCh, XXCH and X96 extensions
     * @throws MediaException.Malformed if the frame breaks the syntax
     * @throws MediaException.Unsupported if the frame has a user defined channel arrangement ({@code AMODE} 16 to 63)
     */
    public void decode(byte[] data, int offset, int length, boolean extensions) {
        reader.reset(data, offset, length);
        header.read(reader);
        if (header.frameSize > length) {
            throw new MediaException.Malformed("DTS frame of " + header.frameSize + " bytes cut to " + length);
        }
        if (header.amode >= AMODE_MASKS.length) {
            throw new MediaException.Unsupported("DTS", "user defined channel arrangement " + header.amode);
        }
        arrangementUnresolved = header.amode > LAST_PRIMARY_AMODE;
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
        primary.fixedPoint = fixedPoint != 0;
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
        joined = false;
        upsampled = false;
        var position = 0;
        lfeSamples = 0;
        for (var subframe = 0; subframe < subframes; subframe++) {
            var subsubframes = reader.read(2) + 1;
            var partial = reader.read(3);
            if (partial != 0 && header.normal) {
                throw new MediaException.Malformed("DTS partial subsubframe in a normal frame");
            }
            if (partial != 0 && subframe != subframes - 1) {
                // clause 5.5.1 puts the partial subsubframe at the end of the frame; one inside it would also leave
                // the LFE samples of the subframes after it out of step with their subband samples
                throw new MediaException.Malformed("DTS partial subsubframe before the last subframe");
            }
            var count = subsubframes * 8 + partial;
            if (count > MAX_SUBFRAME_SAMPLES) {
                throw new MediaException.Malformed("DTS subframe of " + count + " subband samples");
            }
            if (position + count > SubbandBuffer.MAX_SAMPLES) {
                throw new MediaException.Malformed("DTS frame holds more than 128 subband samples");
            }
            subsubframeCounts[subframe] = subsubframes;
            partialCounts[subframe] = partial;
            subframeOffsets[subframe] = position;
            primary.readSideInformation(reader, subsubframes);
            recordTransients(primary, subframe);
            if (header.dynamicRange) {
                reader.skip(8);
            }
            if (header.crcPresent) {
                reader.skip(16);
            }
            primary.readVectors(reader, buffer, position, count);
            if (header.lfe > 0) {
                readLfe(subsubframes);
            }
            readSubsubframes(primary, reader, subframe, "DTS");
            primary.applyJointIntensity(buffer, position, count);
            primary.clearInactive(buffer, position, count);
            position += count;
        }
        subframeOffsets[subframes] = position;
        samples = position;
        if (samples != header.blocks) {
            throw new MediaException.Malformed("DTS frame codes " + samples + " subband samples for "
                    + header.blocks + " blocks");
        }
        padLfe();
        reader.checkNotOverrun();
        for (var ch = 0; ch < primaryChannels; ch++) {
            buffer.saveHistory(ch, 0, 32, samples);
            buffer.clearBands(ch, 32, samples);
        }
        if (!arrangementUnresolved) {
            if (header.amode == 3 || header.frontSumDifference) {
                var left = header.amode >= 5 && header.amode != 6 && header.amode != 8 ? 1 : 0;
                sumDifference(left, left + 1);
            }
            if (header.surroundSumDifference && primaryChannels >= 4 && header.amode >= 8) {
                sumDifference(primaryChannels - 2, primaryChannels - 1);
            }
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
            lfeFixed[lfeSamples] = FixedLfeInterpolator.dequantize(codes[n], index);
            lfeDecimated[lfeSamples++] = ChannelSet.clip(codes[n] * scale);
        }
    }

    /**
     * Reads the audio data of the subsubframes of one subframe of a channel set and the DSYNC words between them:
     * after the last normal subsubframe, or after every one when the header asks, and after a partial subsubframe.
     *
     * @param set the channel set, the side information of the subframe read
     * @param reader the bit stream, standing on the audio data
     * @param subframe the index of the subframe in the frame
     * @param name the name of the coding in the message of a missing DSYNC word
     */
    private void readSubsubframes(ChannelSet set, MsbBitReader reader, int subframe, String name) {
        var subsubframes = subsubframeCounts[subframe];
        var partial = partialCounts[subframe];
        var position = subframeOffsets[subframe];
        for (var subsubframe = 0; subsubframe < subsubframes; subsubframe++) {
            set.readSubsubframe(reader, buffer, position + 8 * subsubframe, subsubframe, subsubframes, 8, steps);
            if (subsubframe == subsubframes - 1 || header.syncEverySubsubframe) {
                checkSync(reader, name);
            }
        }
        if (partial > 0) {
            set.readSubsubframe(reader, buffer, position + 8 * subsubframes, subsubframes, subsubframes, partial,
                    steps);
            checkSync(reader, name);
        }
    }

    /**
     * Reads a DSYNC word.
     *
     * @param reader the bit stream
     * @param name the name of the coding in the message
     * @throws MediaException.Malformed if the next 16 bits are not a DSYNC word
     */
    static void checkSync(MsbBitReader reader, String name) {
        if (reader.read(16) != 0xFFFF) {
            throw new MediaException.Malformed(name + " DSYNC word missing");
        }
    }

    /**
     * Completes the decimated LFE samples of a frame that ends in a partial subsubframe with silent ones up to the
     * frame's length: the frame codes LFE samples for its normal subsubframes only (Table 5-29), so the interpolation
     * of the samples after them rings out the coded ones.
     */
    private void padLfe() {
        if (header.lfe == 0) {
            return;
        }
        var needed = (samples * header.lfe + 3) >> 2;
        while (lfeSamples < needed) {
            lfeFixed[lfeSamples] = 0;
            lfeDecimated[lfeSamples++] = 0;
        }
    }

    /**
     * Applies sum/difference decoding to a pair of channels in the subband domain (clause C.3.5).
     *
     * @implNote This implementation applies it to the fixed-point reconstruction too, as the clause asks of every
     * core. ffmpeg's lossless output leaves it out, which would keep a sum and a difference in the place of two
     * channels; no known lossless stream sets the flags, so only synthetic streams tell the two apart.
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
            if (word == X96_SYNC && header.extensionType == 2 && fixedPoint == 0 && x96Fits(data, offset, position, end)) {
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
        set.fixedPoint = fixedPoint != 0;
        for (var subframe = 0; subframe < subframes; subframe++) {
            var subsubframes = subsubframeCounts[subframe];
            var position = subframeOffsets[subframe];
            var count = subframeOffsets[subframe + 1] - position;
            set.readSideInformation(reader, subsubframes);
            recordTransients(set, subframe);
            if (header.crcPresent) {
                reader.skip(16);
            }
            set.readVectors(reader, buffer, position, count);
            readSubsubframes(set, reader, subframe, "DTS extension");
            set.applyJointIntensity(buffer, position, count);
            set.clearInactive(buffer, position, count);
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
     * Synthesizes every channel into {@link #output(int)} at a sample rate above the rate of the frame, the embedded
     * downmixes of the channel extensions undone, for a frame without the lossless extension inside a stream whose
     * lossless channels have that rate.
     *
     * <p>A frame without X96 is synthesized at twice the core rate through the 64-band filter bank of X96, its upper
     * 32 subbands silent, as the lossless channels of a band at twice the core rate code their residuals against the
     * core. Four times the core rate then joins that output and a silent upper band as the two frequency bands of the
     * lossless extension are joined.
     *
     * @param rate the output rate: {@link #outputRate()}, or twice or four times the rate of the core frame
     *             ({@link CoreHeader#sampleRate}) when that is not below {@link #outputRate()}; any other rate
     *             synthesizes at {@link #outputRate()}
     */
    public void synthesizeAt(int rate) {
        if (!synthesized) {
            var base = header.sampleRate;
            if ((rate == 2 * base || rate == 4 * base) && rate >= outputRate()) {
                upsampled = !x96Present;
                x96Present = true;
                joined = rate == 4 * base;
            }
        }
        synthesize(true);
    }

    /**
     * Synthesizes every channel into {@link #output(int)}.
     *
     * @param undo whether to undo the downmixes the channel extensions embedded in the core channels; without, the
     *             core channels keep the downmix they were coded with, as the lossless extension expects
     * @throws MediaException.Unsupported if the frame has an arrangement of more than five channels ({@code AMODE} 10
     *         to 15) and no XXCH core loudspeaker mask placed its primary channels
     */
    public void synthesize(boolean undo) {
        if (synthesized) {
            return;
        }
        if (arrangementUnresolved) {
            // AMODE 10 to 15 code at most five of their six to eight channels as primary channels and the rest in
            // "extended data arrays" that ETSI TS 102 114 never defines (5.4.3, PCHS), so only an XXCH core speaker
            // mask tells which speakers the primary channels feed.
            throw new MediaException.Unsupported("DTS", "channel arrangement " + header.amode
                    + " without an XXCH core loudspeaker mask");
        }
        synthesized = true;
        if (undo) {
            undoDownmixes();
        }
        if (joined != previousJoined) {
            for (var history : joinHistory) {
                Arrays.fill(history, 0);
            }
            previousJoined = joined;
        }
        var out = pcm;
        var fixed = undo ? 0 : fixedPoint;
        if (x96Present != previousX96 || fixed != previousFixed) {
            for (var ch = 0; ch < SubbandBuffer.MAX_CHANNELS; ch++) {
                qmf32[ch].reset();
                qmf64[ch].reset();
                fixedQmf32[ch].reset();
                fixedQmf64[ch].reset();
            }
            Arrays.fill(lfeHistory, 0);
            previousX96 = x96Present;
            previousFixed = fixed;
        }
        if (fixed != 0) {
            lfeInterpolator.advance(lfeDecimated, lfeSamples);
            synthesizeFixed(fixed);
            return;
        }
        fixedLfeInterpolator.advance(lfeFixed, lfeSamples);
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
                if (upsampled) {
                    X96Decoder.interpolateLfeLinearly(lfeX96, lfeSamples * factor, target, lfeHistory);
                } else {
                    X96Decoder.interpolateLfe(lfeX96, lfeSamples * factor, target, lfeHistory);
                }
            } else {
                lfeInterpolator.interpolate(lfeDecimated, lfeSamples, factor, target, 0, OUTPUT_SCALE);
            }
        }
        if (joined) {
            joinBands();
        }
    }

    /**
     * Joins the synthesized samples of every channel and a silent upper band into {@link #joinedPcm}, at twice their
     * rate, through the band synthesis of the lossless extension on 24-bit samples.
     */
    private void joinBands() {
        var count = samples * 64;
        if (joinedPcm == null) {
            joinedPcm = new float[SubbandBuffer.MAX_CHANNELS + 1][2 * 8192];
            joinLow = new int[8192];
            joinHigh = new int[8192];
            joinOut = new int[2 * 8192];
        }
        var total = channels + (header.lfe > 0 ? 1 : 0);
        for (var ch = 0; ch < total; ch++) {
            var source = pcm[ch];
            for (var n = 0; n < count; n++) {
                joinLow[n] = Math.clamp(Math.round(source[n] * (1 << 23)), -JOIN_LIMIT, JOIN_LIMIT);
            }
            XllBandSynthesis.synthesize(joinLow, joinHigh, joinHistory[ch], joinOut, count);
            var target = joinedPcm[ch];
            for (var n = 0; n < 2 * count; n++) {
                target[n] = joinOut[n] * OUTPUT_SCALE;
            }
        }
    }

    /**
     * Synthesizes every channel with the fixed-point reconstruction into {@link #fixedPcm}.
     *
     * @param ratio the ratio of the output rate to the rate of the core, 1 or 2
     */
    private void synthesizeFixed(int ratio) {
        if (fixedPcm == null) {
            fixedPcm = new int[SubbandBuffer.MAX_CHANNELS + 1][8192];
        }
        for (var ch = 0; ch < channels; ch++) {
            if (ratio == 2) {
                fixedQmf64[ch].synthesize(buffer.planes[ch], 32, 0, samples, fixedPcm[ch], 0);
            } else {
                fixedQmf32[ch].synthesize(buffer.planes[ch], 0, samples, header.perfectReconstruction, fixedPcm[ch], 0);
            }
        }
        if (header.lfe > 0) {
            if (ratio == 2) {
                fixedLfeInterpolator.interpolateDouble(lfeFixed, lfeSamples, fixedPcm[channels], 0);
            } else {
                fixedLfeInterpolator.interpolate(lfeFixed, lfeSamples, fixedPcm[channels], 0);
            }
        }
}

    /**
     * Selects the reconstruction of the frames decoded from now on when they are synthesized for the lossless
     * extension ({@link #synthesize(boolean)} without undoing the downmixes): the floating-point one, or the
     * fixed-point one the residuals are coded against, which {@link #fixedChannelOutput(int)} reads instead of
     * {@link #output(int)}. That reconstruction leaves the X96 extension out, adds the XBR one in integers and keeps
     * the XCh and XXCH downmixes. A frame with a 128 times interpolated LFE channel has none
     * ({@link #losslessReference()}), and a frame synthesized for its own output is always in floating point.
     *
     * @param ratio the ratio of the rate of the lossless channels to the rate of the core, 1 or 2, or 0 for the
     *              floating-point reconstruction
     */
    public void setFixedPoint(int ratio) {
        this.fixedPoint = ratio;
    }

    /**
     * {@return whether the frame is decoded to the integer subband samples of the fixed-point reconstruction, without
     * the X96 extension}
     */
    boolean fixedPoint() {
        return fixedPoint != 0;
    }

    /**
     * {@return whether the frame is decoded to the fixed-point reconstruction lossless residuals add to}
     */
    boolean losslessReference() {
        return fixedPoint != 0 && header.lfe != 1;
    }

    /**
     * {@return the number of samples per channel of the fixed-point reconstruction of the frame}
     */
    int fixedOutputSamples() {
        return samples * 32 * fixedPoint;
    }

    /**
     * Reads the header of a core frame without decoding the frame.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the frame size
     * @return the header, overwritten by the next call or decode
     * @throws MediaException.Malformed if the header breaks the syntax
     */
    public CoreHeader peekHeader(byte[] data, int offset, int length) {
        reader.reset(data, offset, length);
        header.read(reader);
        return header;
    }

    /**
     * {@return the PCM of a channel of the last synthesized frame, {@link #outputSamples()} samples}
     *
     * @param ch the channel, indexed like {@link #mask(int)}; {@link #channels()} is the LFE channel
     */
    public float[] output(int ch) {
        return joined ? joinedPcm[ch] : pcm[ch];
    }

    /**
     * {@return the 24-bit fixed-point reconstruction of the channel with a DTS speaker bit in the last synthesized
     * frame, {@link #fixedOutputSamples()} samples}
     *
     * @param mask the speaker bit
     * @throws MediaException.Malformed if no channel has the bit
     */
    int[] fixedChannelOutput(int mask) {
        return fixedPcm[channelIndex(mask)];
    }

    /**
     * {@return the channel with a DTS speaker bit, a side surround speaker standing for a surround one and the
     * reverse, {@link #channels()} for the LFE channel}
     *
     * @param mask the speaker bit
     * @throws MediaException.Malformed if no channel has the bit
     */
    private int channelIndex(int mask) {
        if (mask == Speakers.LFE1 && hasLfe()) {
            return channels;
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
        return index;
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
        return samples * (x96Present ? 64 : 32) * (joined ? 2 : 1);
    }

    /**
     * {@return the output sample rate of the last decoded frame}
     */
    public int outputRate() {
        return header.sampleRate * (x96Present ? 2 : 1) * (joined ? 2 : 1);
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
     * {@return the number of samples of the partial subsubframe of a subframe, {@code 0} for none}
     *
     * @param subframe the index of the subframe in the frame
     */
    int partial(int subframe) {
        return partialCounts[subframe];
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
        if (arrangementUnresolved) {
            arrangementUnresolved = false;
            if (header.frontSumDifference) {
                sumDifferencePair(Speakers.L, Speakers.R);
            }
            if (header.surroundSumDifference && !sumDifferencePair(Speakers.LS, Speakers.RS)) {
                sumDifferencePair(Speakers.LSS, Speakers.RSS);
            }
        }
    }

    /**
     * Applies sum/difference decoding to the primary channels of a pair of speakers, when both are primary channels.
     *
     * @param left the speaker bit of the channel that holds the sum
     * @param right the speaker bit of the channel that holds the difference
     * @return whether both are primary channels
     */
    private boolean sumDifferencePair(int left, int right) {
        var first = indexOf(left);
        var second = indexOf(right);
        if (first < 0 || second < 0 || first >= primary.channels || second >= primary.channels) {
            return false;
        }
        sumDifference(first, second);
        return true;
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
            fixedQmf32[ch].reset();
            fixedQmf64[ch].reset();
        }
        lfeInterpolator.reset();
        fixedLfeInterpolator.reset();
        for (var history : joinHistory) {
            Arrays.fill(history, 0);
        }
        previousJoined = false;
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
