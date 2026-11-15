package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.header.MpegAudioHeader;

import java.util.Arrays;

/**
 * Decodes the ISO/IEC 13818-3 multichannel extension of a Layer III frame: the left, right, center and surround
 * channels carried as transmission channels ({@code TC}s) in the main data of an MPEG-1 compatible frame, after its
 * Huffman data, and, when they do not fit, in an extension frame.
 *
 * <p>The base frame carries the compatible stereo signal {@code Lo/Ro}. The extension has its own side information
 * and its own bit reservoir, which {@code mc_data_begin} points into and which holds only the extension's main data.
 * Each output channel is assembled from segments of scalefactor band groups: a segment comes from the channel's own
 * transmission channel, from another one scaled by an attenuation (composite coding), or from dematrixing
 * {@code Lo/Ro}. A transmission channel codes only the bands some segment refers to. Prediction from {@code Lo/Ro}
 * applies to the transmission channels, and the channels are combined in the spectral domain, before the hybrid
 * synthesis.
 *
 * <p>Up to seven multilingual channels, each coded like a mono Layer III channel of the frame's sampling rate or of
 * half of it, follow in the same main data, and are decoded when {@link #decodeMultilingual(boolean)} asks for them.
 *
 * <p>Subband samples are written to the {@link Mp2Multichannel} the instance is given, in its channel order and
 * layout, so that the decoder outputs both layers' channels alike. Instances are not thread-safe.
 *
 * @implNote This implementation reads the syntax of ISO/IEC 13818-3, 2.5.1.16 and 2.5.1.19 to 2.5.1.21. Where the
 * text is silent, the choices are these. The CRC-16 covers {@code mc_header} and everything after the CRC word up to
 * the end of the side information, as the ISO/IEC 13818-4 test 23 stream shows. Region counts, the split between the
 * two scalefactor lengths and the preemphasis table count the bands a transmission channel actually codes, as that
 * stream shows too, while {@code scfsi} keeps the bands of the spectrum, as the syntax has them; the stream has no
 * {@code scfsi}. A dematrixed center comes from the {@code Lo} equation unless its left or left
 * surround channel is dematrixed too, and a mono surround likewise. A predictor applies to a band group when its
 * channel carries data there and has the block type of the source in such a granule. The extension frame's own CRC is
 * not checked, because the test 23 stream leaves it zero.
 */
final class Layer3Multichannel {
    /**
     * The largest value of {@code mc_data_begin}, so the extension's reservoir bytes that can be referred to.
     */
    private static final int MAX_RESERVOIR = 2047;

    /**
     * The largest number of bytes one frame adds to the stream: its main data and an extension frame.
     */
    private static final int MAX_FRAME_BYTES = Layer3.MAX_RESERVOIR + Layer3.MAX_FRAME_PAYLOAD + 2048;

    /**
     * Bytes after the end of the stream the Huffman reader may look at: its big values region is not bounded by the
     * channel's length, and 288 pairs of at most 47 bits fit here.
     */
    private static final int READ_SLACK = 2048;

    /**
     * The length of {@code mc_header} in a Layer III frame.
     */
    private static final int HEADER_BITS = 24;

    /**
     * The transmission channel number of the left channel, the first of the extension's five.
     */
    private static final int LEFT = 2;

    private static final int RIGHT = 3;

    private static final int CENTER = 4;

    private static final int LEFT_SURROUND = 5;

    private static final int RIGHT_SURROUND = 6;

    /**
     * The {@code tc_select} value of a segment reconstructed by dematrixing.
     */
    private static final int DEMATRIXED = 7;

    /**
     * The source of a band group that is set to zero.
     */
    private static final int ZERO = -1;

    /**
     * The first scalefactor band of each long block scalefactor band group, ending with the total band count.
     */
    private static final int[] GROUPS_LONG = {0, 3, 6, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 22};

    /**
     * The first scalefactor band of each short block scalefactor band group, ending with the total band count.
     */
    private static final int[] GROUPS_SHORT = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13};

    /**
     * The channels each 4-bit {@code dematrix_select} value dematrixes, bit {@code ch - 2} for channel {@code ch};
     * {@code -1} for the reserved value.
     */
    private static final int[] SELECT_4 = {0, 1, 2, 3, 4, 1 | 4, 2 | 4, 8, 2 | 8, 4 | 8, 16, 1 | 16, 4 | 16, 8 | 16,
            1 | 8, -1};

    /**
     * The channels each 3-bit {@code dematrix_select} value dematrixes, the third column standing for the center
     * or the mono surround channel (bit 2, moved to the surround's bit for a mono surround); {@code -1} for the
     * reserved values.
     */
    private static final int[] SELECT_3 = {0, 1, 1 | 2, 4, 1 | 4, 2 | 4, -1, -1};

    /**
     * The dequantized prediction coefficients.
     */
    private static final float[] PREDICTION = {-0.61199f, -0.24565f, 0.24565f, 0.61199f, 1.15831f, 1.97304f, 3.18805f,
            5.0f};

    /**
     * The window of the low-frequency enhancement channel's transform, over its 12 output samples.
     */
    private static final float[] LFE_WINDOW = new float[12];

    /**
     * The inverse transform of the low-frequency enhancement channel: output sample {@code i}, coefficient
     * {@code k} at {@code i * 6 + k}.
     */
    private static final float[] LFE_COS = new float[12 * 6];

    /**
     * The downmix factors {@code alpha}, {@code beta} and {@code gamma} of each dematrix procedure.
     */
    private static final float[][] DOWNMIX = new float[4][];

    /**
     * All zero spectral lines.
     */
    private static final float[] NO_LINES = new float[576];

    static {
        for (var i = 0; i < 12; i++) {
            LFE_WINDOW[i] = (float) StrictMath.sin(StrictMath.PI / 12 * (i + 0.5));
            for (var k = 0; k < 6; k++) {
                LFE_COS[i * 6 + k] = (float) StrictMath.cos(StrictMath.PI / 24 * (2 * i + 1 + 6) * (2 * k + 1));
            }
        }
        var sqrt2 = StrictMath.sqrt(2.0);
        DOWNMIX[0] = new float[]{(float) (1 / (1 + sqrt2)), (float) (1 / sqrt2), (float) (1 / sqrt2)};
        DOWNMIX[1] = new float[]{(float) (1 / (1.5 + 0.5 * sqrt2)), (float) (1 / sqrt2), 0.5f};
        DOWNMIX[2] = DOWNMIX[0];
        DOWNMIX[3] = new float[]{1, 1, 1};
    }

    private final Layer3 layer3;

    /**
     * Receives the subband samples and holds the channel configuration.
     */
    private final Mp2Multichannel target;

    /**
     * The bits of the frame's extension: its main data part, then the extension frame's data.
     */
    private final byte[] frame = new byte[MAX_FRAME_BYTES + READ_SLACK];

    private final BitReader frameReader = new BitReader();

    /**
     * The extension's main data stream: the reservoir, then the bytes of the current frame.
     */
    private final byte[] stream = new byte[MAX_RESERVOIR + MAX_FRAME_BYTES + READ_SLACK];

    private final BitReader streamReader = new BitReader();

    /**
     * The number of reservoir bytes at the start of {@link #stream}.
     */
    private int reservoirLength;

    private int center;

    private int surround;

    private int lfe;

    private int procedure;

    private int multilingual;

    private int baseChannels;

    private int mainDataBegin;

    /**
     * The first byte of the main data in {@link #frame}, and its length.
     */
    private int mainFrom;

    private int mainLength;

    /**
     * The number of scalefactor band groups whose dematrixed channels {@code dematrix_select} names.
     */
    private int dematrixLength;

    private final int[][] blockType = new int[2][7];

    /**
     * Whether a channel's segment list is present: granule, channel.
     */
    private final boolean[][] segmentListPresent = new boolean[2][7];

    /**
     * The channels each of the first {@link #dematrixLength} band groups dematrixes.
     */
    private final int[] dematrixSelect = new int[15];

    /**
     * Whether a channel's first granule transmitted a segment list the second one may repeat.
     */
    private final boolean[] firstListTransmitted = new boolean[7];

    /**
     * The transmitted coefficients of each band group, one bit per possible coefficient.
     */
    private final long[] selectedPredictors = new long[15];

    private final boolean[][] tcPresent = new boolean[2][7];

    /**
     * The source of each band group of each channel: granule, channel, group: a transmission channel number,
     * {@link #DEMATRIXED} or {@link #ZERO}.
     */
    private final int[][][] source = new int[2][7][15];

    /**
     * The gain of each band group of each channel, in the layout of {@link #source}.
     */
    private final float[][][] gain = new float[2][7][15];

    /**
     * Whether a transmission channel codes a band group: granule, channel, group.
     */
    private final boolean[][][] dataPresent = new boolean[2][7][15];

    /**
     * Whether a transmission channel carries a band group for another channel: granule, channel, group.
     */
    private final boolean[][][] carrier = new boolean[2][7][15];

    private boolean matrixAttenuation;

    /**
     * The gains applied to a carrier's band groups in the {@code Lo} and {@code Ro} dematrixing equations:
     * equation, granule, channel, group.
     */
    private final float[][][][] matrixGain = new float[2][2][7][15];

    private boolean prediction;

    /**
     * The prediction coefficients: band group, channel, source ({@code Lo} or {@code Ro}).
     */
    private final float[][][] predictor = new float[15][7][2];

    private final int[][] scfsi = new int[7][4];

    /**
     * The side information of each granule and transmission channel.
     */
    private final GranuleInfo[][] side = new GranuleInfo[2][7];

    private int lfeBits;

    private int lfeGain;

    private int lfeTable;

    /**
     * The scalefactors of a transmission channel's last granule, for {@code scfsi}.
     */
    private final int[][] scalefactorMemory = new int[7][39];

    /**
     * The scalefactors of the current granule and channel, by band and window.
     */
    private final int[] scalefactorValues = new int[39];

    /**
     * The band widths and gains of the bands a transmission channel codes, as {@link Layer3#huffman} takes them.
     */
    private final int[] codedBands = new int[40];

    private final float[] codedGains = new float[40];

    /**
     * The spectral lines of the transmission channels, in the coded order: granule, channel.
     */
    private final float[][][] lines = new float[2][7][576];

    /**
     * The Huffman output of one transmission channel, without the bands it does not code.
     */
    private final float[] coded = new float[576 + 4];

    /**
     * The spectral lines of the output channels being assembled, by channel number.
     */
    private final float[][] output = new float[7][576];

    /**
     * The channels each line dematrixes, bit {@code ch - 2} for channel {@code ch}.
     */
    private final int[] lineMask = new int[576];

    /**
     * The matrix attenuation of each line of each channel in the {@code Lo} and {@code Ro} equations.
     */
    private final float[][][] lineGain = new float[2][7][576];

    /**
     * The working area of the hybrid synthesis.
     */
    private final float[] hybrid = new float[2 * 576];

    /**
     * The overlap of the channels other than left and right: center, left and right surround.
     */
    private final float[][] overlap = new float[3][9 * 32];

    private final float[] lfeOverlap = new float[6];

    private final float[] lfeCoefficients = new float[12];

    /**
     * The low-frequency enhancement output of the frame.
     */
    private final float[] lfeOutput = new float[12];

    private final int[] lineStartLong = new int[GROUPS_LONG.length];

    private final int[] lineStartShort = new int[GROUPS_SHORT.length];

    private int[] sfbLong;

    private int[] sfbShort;

    /**
     * The sampling rate of the frame.
     */
    private int rate;

    /**
     * The number of dematrixed center lines kept in phantom coding.
     */
    private int phantomLines;

    /**
     * The synthesis kernel of the multilingual channels' decoders.
     */
    private final SynthesisKernel kernel;

    /**
     * Whether the multilingual channels of the frames that carry them are decoded.
     */
    private boolean decodeMultilingual;

    /**
     * Whether the multilingual channels of the frame are at half its sampling rate.
     */
    private boolean multilingualHalfRate;

    /**
     * Whether the multilingual channels of the frame are coded as Layer III.
     */
    private boolean multilingualLayer3;

    /**
     * The frame header of the extension parsed last.
     */
    private int frameHeader;

    /**
     * The side information of the multilingual channels: granule, channel.
     */
    private final GranuleInfo[][] multilingualInfo = new GranuleInfo[2][7];

    private final int[] multilingualScfsi = new int[7];

    /**
     * The state of each multilingual channel, created when first needed.
     */
    private final Layer3[] multilingualDecoders = new Layer3[7];

    Layer3Multichannel(Layer3 layer3, Mp2Multichannel target, SynthesisKernel kernel) {
        this.layer3 = layer3;
        this.target = target;
        this.kernel = kernel;
        for (var granule : side) {
            for (var channel = 0; channel < granule.length; channel++) {
                granule[channel] = new GranuleInfo();
            }
        }
        for (var granule : multilingualInfo) {
            for (var channel = 0; channel < granule.length; channel++) {
                granule[channel] = new GranuleInfo();
            }
        }
    }

    /**
     * Sets whether {@link #readMainData} reads the multilingual channels of the frames that carry them.
     *
     * @param decode whether to read them
     */
    void decodeMultilingual(boolean decode) {
        this.decodeMultilingual = decode;
    }

    /**
     * Forgets the reservoir, after a frame whose extension could not be read.
     */
    void dropReservoir() {
        reservoirLength = 0;
    }

    /**
     * Clears the reservoir and the transforms' history.
     */
    void reset() {
        reservoirLength = 0;
        clearExtensionHistory();
        for (var decoder : multilingualDecoders) {
            if (decoder != null) {
                decoder.reset();
            }
        }
    }

    /**
     * Clears the history of the channels the base frame does not carry, which a frame decoded without its extension
     * leaves behind.
     */
    void clearExtensionHistory() {
        for (var state : overlap) {
            Arrays.fill(state, 0);
        }
        Arrays.fill(lfeOverlap, 0);
    }

    /**
     * Parses the extension of a frame up to the end of its side information.
     *
     * @param main the reader over the frame's main data, whose limit is the end of the frame
     * @param position the bit offset in {@code main} where the base channels' Huffman data ends
     * @param nextBegin the {@code main_data_begin} of the next frame, the frame's last bytes that belong to it
     * @param header the frame header
     * @param channels the number of base channels
     * @param extension the bytes holding the extension frame, or {@code null}
     * @param extensionOffset the index of the extension frame
     * @param extensionLength the extension frame length in bytes
     * @param crcCheck whether a CRC mismatch rejects the extension
     * @return {@code false} when the frame carries no extension that can be decoded
     */
    boolean parse(BitReader main, int position, int nextBegin, int header, int channels, byte[] extension,
                  int extensionOffset, int extensionLength, boolean crcCheck) {
        var end = main.limit - nextBegin * 8;
        if (end - position < 40) {
            return false;
        }
        var data = main.data;
        var resume = main.position;
        main.position = position;
        var present = main.read(1);
        var ancillary = main.read(8);
        center = main.read(2);
        surround = main.read(2);
        lfe = main.read(1);
        main.read(1);
        procedure = main.read(2);
        multilingual = main.read(3);
        multilingualHalfRate = main.read(1) == 1;
        multilingualLayer3 = main.read(1) == 1;
        main.read(2);
        main.position = resume;
        baseChannels = channels;
        end -= ancillary * 8;
        if (end - position < 40 || (present == 1 && extension == null) || !validConfiguration()) {
            return false;
        }
        var from = position >> 3;
        var base = (end >> 3) - from;
        var extra = present == 1 ? extensionLength - 5 : 0;
        System.arraycopy(data, main.start + from, frame, 0, base);
        if (extra > 0) {
            System.arraycopy(extension, extensionOffset + 5, frame, base, extra);
        }
        Arrays.fill(frame, base + extra, base + extra + READ_SLACK, (byte) 0);
        var bs = frameReader;
        bs.init(frame, 0, base + extra);
        bs.position = position & 7;
        var headerStart = bs.position;
        var headerEnd = headerStart + HEADER_BITS;
        bs.position = headerEnd;
        var storedCrc = bs.read(16);
        var crcStart = bs.position;
        setRate(header);
        if (!readCompositeStatus(bs) || !readSideInfo(bs) || !readMultilingualSide(bs, header) || bs.overrun()) {
            return false;
        }
        this.frameHeader = header;
        if (crcCheck) {
            var crc = crc(frame, headerStart, headerEnd, 0xFFFF);
            if (crc(frame, crcStart, bs.position, crc) != storedCrc) {
                return false;
            }
        }
        mainFrom = (bs.position + 7) >> 3;
        mainLength = Math.max(0, base + extra - mainFrom);
        return true;
    }

    /**
     * {@return whether the header's configuration has channels to add and can be carried by the base channels}
     */
    private boolean validConfiguration() {
        if (center == 2 || (center == 0 && surround == 0 && lfe == 0 && multilingual == 0)) {
            return false;
        }
        if (procedure == 2 && surround != 1 && surround != 2) {
            return false;
        }
        return baseChannels == 2 || (surround == 3 && center == 0)
                || (decodeMultilingual && multilingual > 0 && surround == 0 && center == 0);
    }

    /**
     * Derives the band layout of a frame's sampling rate.
     *
     * @param header the frame header
     */
    private void setRate(int header) {
        rate = MpegAudioHeader.sampleRate(header);
        var index = MpegAudioHeader.sampleRateClass(header);
        if (index != 0) {
            index--;
        }
        sfbLong = Mp3Tables.SFB_LONG[index];
        sfbShort = Mp3Tables.SFB_SHORT[index];
        phantomLines = rate == 48000 ? 230 : rate == 44100 ? 238 : 296;
        var line = 0;
        for (var group = 0; group + 1 < GROUPS_LONG.length; group++) {
            lineStartLong[group] = line;
            for (var band = GROUPS_LONG[group]; band < GROUPS_LONG[group + 1]; band++) {
                line += sfbLong[band];
            }
        }
        lineStartLong[GROUPS_LONG.length - 1] = line;
        line = 0;
        for (var group = 0; group + 1 < GROUPS_SHORT.length; group++) {
            lineStartShort[group] = line;
            for (var band = GROUPS_SHORT[group]; band < GROUPS_SHORT[group + 1]; band++) {
                line += 3 * sfbShort[3 * band];
            }
        }
        lineStartShort[GROUPS_SHORT.length - 1] = line;
    }

    /**
     * {@return whether audio channel {@code ch} is present in the header's configuration}
     *
     * @param ch the channel number, from 2 to 6
     */
    private boolean channelPresent(int ch) {
        return switch (ch) {
            case LEFT -> baseChannels == 2;
            case RIGHT -> baseChannels == 2;
            case CENTER -> center != 0;
            case LEFT_SURROUND -> surround != 0;
            default -> surround >= 2;
        };
    }

    /**
     * Reads the block flags of one channel over one granule.
     */
    private void readChannelFlags(BitReader bs, int granule, int ch, boolean[][] listPresent) {
        listPresent[granule][ch] = bs.read(1) == 1;
        tcPresent[granule][ch] = bs.read(1) == 1;
        blockType[granule][ch] = bs.read(2);
    }

    /**
     * Reads {@code mc_composite_status_info}.
     *
     * @return {@code false} for an illegal value
     */
    private boolean readCompositeStatus(BitReader bs) {
        mainDataBegin = bs.read(11);
        var listPresent = segmentListPresent;
        for (var granule = 0; granule < 2; granule++) {
            Arrays.fill(listPresent[granule], false);
            Arrays.fill(tcPresent[granule], false);
            Arrays.fill(blockType[granule], 0);
        }
        for (var granule = 0; granule < 2; granule++) {
            readChannelFlags(bs, granule, LEFT, listPresent);
            readChannelFlags(bs, granule, RIGHT, listPresent);
        }
        if (center != 0) {
            for (var granule = 0; granule < 2; granule++) {
                readChannelFlags(bs, granule, CENTER, listPresent);
            }
        }
        if (surround == 1) {
            for (var granule = 0; granule < 2; granule++) {
                readChannelFlags(bs, granule, LEFT_SURROUND, listPresent);
            }
        } else if (surround >= 2) {
            for (var granule = 0; granule < 2; granule++) {
                readChannelFlags(bs, granule, LEFT_SURROUND, listPresent);
                readChannelFlags(bs, granule, RIGHT_SURROUND, listPresent);
            }
        }
        dematrixLength = procedure != 3 ? bs.read(4) : 0;
        var selectBits = surround == 3 || !(center != 0 && surround != 0 || surround == 2) ? 3 : 4;
        for (var group = 0; group < dematrixLength; group++) {
            var mask = dematrixMask(bs.read(selectBits), selectBits);
            if (mask < 0) {
                return false;
            }
            dematrixSelect[group] = mask;
        }
        if (!readSegmentLists(bs, listPresent)) {
            return false;
        }
        readPrediction(bs);
        return true;
    }

    /**
     * Maps a {@code dematrix_select} value to the channels it dematrixes.
     *
     * @return the mask, or {@code -1} for an illegal value
     */
    private int dematrixMask(int value, int bits) {
        if (bits == 4) {
            return SELECT_4[value];
        }
        var mask = SELECT_3[value];
        if (mask >= 4 && center == 0) {
            mask = mask - 4 + 8;
        }
        return mask;
    }

    /**
     * Reads the segment lists and expands them into {@link #source}, {@link #gain}, {@link #dataPresent} and
     * {@link #carrier}.
     *
     * @return {@code false} when a segment runs past the last band group
     */
    private boolean readSegmentLists(BitReader bs, boolean[][] listPresent) {
        Arrays.fill(firstListTransmitted, false);
        for (var granule = 0; granule < 2; granule++) {
            for (var ch = LEFT; ch <= RIGHT_SURROUND; ch++) {
                var groups = groupCount(granule, ch);
                Arrays.fill(source[granule][ch], ZERO);
                Arrays.fill(gain[granule][ch], 1);
                Arrays.fill(dataPresent[granule][ch], false);
                Arrays.fill(carrier[granule][ch], false);
                if (!channelPresent(ch)) {
                    continue;
                }
                var defaultSource = defaultSource(ch);
                for (var group = 0; group < Math.min(dematrixLength, groups); group++) {
                    var dematrixed = (dematrixSelect[group] & (1 << (ch - LEFT))) != 0;
                    source[granule][ch][group] = dematrixed ? dematrixedSource(ch) : defaultSource;
                }
                if (!listPresent[granule][ch]) {
                    fillGroups(granule, ch, dematrixLength, groups, dematrixedSource(ch));
                    continue;
                }
                if (bs.read(1) == 0) {
                    fillGroups(granule, ch, dematrixLength, groups, defaultSource);
                    continue;
                }
                if (granule == 1 && firstListTransmitted[ch] && bs.read(1) == 1) {
                    if (blockType[0][ch] == 2 != (blockType[1][ch] == 2)) {
                        return false;
                    }
                    System.arraycopy(source[0][ch], 0, source[1][ch], 0, 15);
                    System.arraycopy(gain[0][ch], 0, gain[1][ch], 0, 15);
                    continue;
                }
                if (!readSegmentList(bs, granule, ch, groups, defaultSource)) {
                    return false;
                }
                if (granule == 0) {
                    firstListTransmitted[ch] = true;
                }
            }
        }
        for (var granule = 0; granule < 2; granule++) {
            for (var ch = LEFT; ch <= RIGHT_SURROUND; ch++) {
                if (!channelPresent(ch)) {
                    continue;
                }
                for (var group = 0; group < groupCount(granule, ch); group++) {
                    var tc = source[granule][ch][group];
                    if (tc >= LEFT && tc != DEMATRIXED) {
                        dataPresent[granule][tc][group] = tcPresent[granule][tc];
                        if (tc != ch) {
                            carrier[granule][tc][group] = true;
                        }
                    }
                }
            }
        }
        return true;
    }

    private void fillGroups(int granule, int ch, int from, int to, int value) {
        for (var group = from; group < to; group++) {
            source[granule][ch][group] = value;
        }
    }

    /**
     * {@return the source of a channel's band group that is transmitted in its own transmission channel}
     *
     * @param ch the channel number
     */
    private int defaultSource(int ch) {
        return procedure == 3 && ch <= RIGHT ? ch - LEFT : ch;
    }

    /**
     * {@return the source of a channel's band group that its header or list marks as dematrixed}: without
     * dematrixing, left and right are the base channels and the others are zero.
     *
     * @param ch the channel number
     */
    private int dematrixedSource(int ch) {
        if (procedure == 3) {
            return ch <= RIGHT ? ch - LEFT : ZERO;
        }
        if (surround == 3 && ch >= LEFT_SURROUND) {
            return ZERO;
        }
        return baseChannels == 2 ? DEMATRIXED : ZERO;
    }

    private int groupCount(int granule, int ch) {
        return blockType[granule][ch] == 2 ? 12 : 15;
    }

    /**
     * Reads one {@code segment_list}.
     *
     * @return {@code false} when a segment runs past the last band group
     */
    private boolean readSegmentList(BitReader bs, int granule, int ch, int groups, int defaultSource) {
        var range = bs.read(2);
        var scale = bs.read(1);
        var bits = range + 2;
        var maximum = 1 << bits;
        var step = scale == 1 ? StrictMath.pow(2, -0.5) : StrictMath.pow(2, -0.25);
        var group = dematrixLength;
        while (group < groups) {
            var length = bs.read(4);
            if (length == 0) {
                break;
            }
            var select = bs.read(3);
            if (group + length > groups) {
                return false;
            }
            for (var g = group; g < group + length; g++) {
                if (select == DEMATRIXED) {
                    source[granule][ch][g] = dematrixedSource(ch);
                } else if (select == ch) {
                    source[granule][ch][g] = defaultSource;
                } else {
                    var attenuation = bs.read(bits);
                    var exponent = 4 * attenuation < 3 * maximum ? attenuation : attenuation - maximum;
                    source[granule][ch][g] = select;
                    gain[granule][ch][g] = (float) StrictMath.pow(step, exponent);
                }
            }
            group += length;
            if (bs.overrun()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the prediction part of {@code mc_composite_status_info}.
     */
    private void readPrediction(BitReader bs) {
        prediction = bs.read(1) == 1;
        for (var group : predictor) {
            for (var channel : group) {
                Arrays.fill(channel, 0);
            }
        }
        if (!prediction) {
            return;
        }
        var flags = 0;
        for (var group = 0; group < 15; group++) {
            flags |= bs.read(1) << group;
        }
        var selected = selectedPredictors;
        for (var group = 0; group < 15; group++) {
            selected[group] = 0;
            if ((flags & (1 << group)) == 0) {
                continue;
            }
            var pci = 0;
            for (var ch = LEFT; ch <= RIGHT_SURROUND; ch++) {
                for (var from = 0; from < 2; from++) {
                    if (predictorPossible(group, ch, from)) {
                        selected[group] |= (long) bs.read(1) << pci;
                        pci++;
                    }
                }
            }
        }
        for (var group = 0; group < 15; group++) {
            if ((flags & (1 << group)) == 0) {
                continue;
            }
            var pci = 0;
            for (var ch = LEFT; ch <= RIGHT_SURROUND; ch++) {
                for (var from = 0; from < 2; from++) {
                    if (predictorPossible(group, ch, from)) {
                        if ((selected[group] & (1L << pci)) != 0) {
                            predictor[group][ch][from] = PREDICTION[bs.read(3)];
                        }
                        pci++;
                    }
                }
            }
        }
    }

    /**
     * {@return whether a band group's transmission channel can have a predictor from a base channel}
     *
     * @param group the band group
     * @param ch the transmission channel
     * @param from the source, 0 for {@code Lo} and 1 for {@code Ro}
     */
    private boolean predictorPossible(int group, int ch, int from) {
        if (from >= baseChannels) {
            return false;
        }
        var possible = false;
        var both = ch == CENTER || (ch == LEFT_SURROUND && surround == 1);
        for (var granule = 0; granule < 2; granule++) {
            if (group >= groupCount(granule, ch) || !dataPresent[granule][ch][group]) {
                continue;
            }
            if (layer3.granules[granule * baseChannels + from].blockType == blockType[granule][ch]) {
                possible = true;
            }
            both |= carrier[granule][ch][group];
        }
        if (!possible) {
            return false;
        }
        if (both) {
            return true;
        }
        var left = ch == LEFT || ch == LEFT_SURROUND;
        return from == (left ? 0 : 1);
    }

    /**
     * Reads {@code mc_side_info} and {@code lfe_side_info}.
     *
     * @return {@code false} for an illegal value
     */
    private boolean readSideInfo(BitReader bs) {
        matrixAttenuation = procedure != 3 && bs.read(1) == 1;
        if (matrixAttenuation) {
            for (var granule = 0; granule < 2; granule++) {
                for (var ch = LEFT; ch <= RIGHT_SURROUND; ch++) {
                    var groups = groupCount(granule, ch);
                    for (var group = dematrixLength; group < groups; group++) {
                        if (carrier[granule][ch][group]) {
                            matrixGain[0][granule][ch][group] = (float) StrictMath.pow(2, -0.25 * bs.read(3));
                            matrixGain[1][granule][ch][group] = (float) StrictMath.pow(2, -0.25 * bs.read(3));
                        }
                    }
                }
            }
        }
        for (var tc = LEFT; tc <= RIGHT_SURROUND; tc++) {
            for (var band = 0; band < 4; band++) {
                scfsi[tc][band] = tcPresent[0][tc] && tcPresent[1][tc] ? bs.read(1) : 0;
                if (blockType[0][tc] == 2 || blockType[1][tc] == 2) {
                    scfsi[tc][band] = 0;
                }
            }
        }
        for (var granule = 0; granule < 2; granule++) {
            for (var tc = LEFT; tc <= RIGHT_SURROUND; tc++) {
                if (!tcPresent[granule][tc]) {
                    continue;
                }
                var gr = side[granule][tc];
                gr.part23Length = bs.read(12);
                gr.bigValues = bs.read(9);
                if (gr.bigValues > 288) {
                    return false;
                }
                gr.globalGain = bs.read(8);
                gr.scalefacCompress = bs.read(4);
                gr.sfb = codedBands;
                gr.blockType = blockType[granule][tc];
                gr.tableSelect[2] = 0;
                if (gr.blockType != 0) {
                    gr.tableSelect[0] = bs.read(5);
                    gr.tableSelect[1] = bs.read(5);
                    gr.regionCount[0] = gr.blockType == 2 ? 8 : 7;
                    gr.regionCount[1] = 255;
                    gr.regionCount[2] = 255;
                    if (gr.blockType == 2) {
                        gr.subblockGain[0] = bs.read(3);
                        gr.subblockGain[1] = bs.read(3);
                        gr.subblockGain[2] = bs.read(3);
                    }
                } else {
                    gr.tableSelect[0] = bs.read(5);
                    gr.tableSelect[1] = bs.read(5);
                    gr.tableSelect[2] = bs.read(5);
                    gr.regionCount[0] = bs.read(4);
                    gr.regionCount[1] = bs.read(3);
                    gr.regionCount[2] = 255;
                }
                gr.preflag = bs.read(1) == 1;
                gr.scalefacScale = bs.read(1);
                gr.count1Table = bs.read(1);
            }
        }
        if (lfe != 0) {
            lfeBits = bs.read(8);
            lfeGain = bs.read(8);
            lfeTable = bs.read(5);
        }
        return true;
    }

    /**
     * Computes a CRC-16 over a run of bits.
     *
     * @return the CRC
     */
    private static int crc(byte[] data, int from, int to, int crc) {
        for (var i = from; i < to; i++) {
            crc = MpegAudioCrc.update(crc, (data[i >> 3] >> (7 - (i & 7))) & 1, 1);
        }
        return crc;
    }

    /**
     * Reads the main data of the frame parsed last, adding the frame's bytes to the reservoir.
     *
     * @return {@code false} when the data begins before the reservoir or runs past the end of the frame
     */
    boolean readMainData() {
        var have = reservoirLength;
        System.arraycopy(frame, mainFrom, stream, have, mainLength);
        var total = have + mainLength;
        Arrays.fill(stream, total, total + READ_SLACK, (byte) 0);
        var complete = mainDataBegin <= have;
        if (complete) {
            var bs = streamReader;
            bs.init(stream, 0, total);
            bs.position = (have - mainDataBegin) * 8;
            complete = readTransmissionChannels(bs) && readLfe(bs) && !bs.overrun();
            target.setMultilingual(0, false, false);
            if (complete && decodeMultilingual && multilingual > 0 && multilingualLayer3) {
                readMultilingualMain(bs, total);
            }
        }
        var keep = Math.min(total, MAX_RESERVOIR);
        System.arraycopy(stream, total - keep, stream, 0, keep);
        reservoirLength = keep;
        return complete;
    }

    /**
     * Reads {@code ml_side_info}, which the CRC covers whether or not the channels are decoded.
     *
     * @param bs the reader after {@code lfe_side_info}
     * @param header the frame header
     * @return {@code false} for an illegal value
     */
    private boolean readMultilingualSide(BitReader bs, int header) {
        // TODO: Layer II multilingual channels on a Layer III base (multi_lingual_layer clear), which no 13818-4
        //  set carries; the main program of such a frame decodes and its multilingual channels are not output.
        if (multilingual == 0 || !multilingualLayer3) {
            return true;
        }
        var srIndex = MpegAudioHeader.sampleRateClass(multilingualHeader(header));
        if (srIndex != 0) {
            srIndex--;
        }
        var scratch = multilingualInfo;
        if (!multilingualHalfRate) {
            for (var ch = 0; ch < multilingual; ch++) {
                multilingualScfsi[ch] = bs.read(4);
            }
        }
        var granules = multilingualHalfRate ? 1 : 2;
        for (var granule = 0; granule < granules; granule++) {
            for (var ch = 0; ch < multilingual; ch++) {
                if (!Layer3.readGranuleInfo(bs, scratch[granule][ch], srIndex, !multilingualHalfRate)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the header of a mono frame of a multilingual channel.
     *
     * @param header the frame header
     * @return the header: mono, and MPEG-2 for channels at half the sampling rate
     */
    private int multilingualHeader(int header) {
        var mono = header | 0xC0;
        return multilingualHalfRate ? mono & ~0x00080000 : mono;
    }

    /**
     * Reads and transforms the multilingual channels, whose side information was read.
     *
     * @param bs the reader after the low-frequency enhancement channel
     * @param total the number of bytes in the stream
     */
    private void readMultilingualMain(BitReader bs, int total) {
        var header = multilingualHeader(frameHeader);
        var granules = multilingualHalfRate ? 1 : 2;
        var position = bs.position;
        var needed = 0L;
        for (var granule = 0; granule < granules; granule++) {
            for (var ch = 0; ch < multilingual; ch++) {
                needed += multilingualInfo[granule][ch].part23Length;
            }
        }
        if (position + needed > bs.limit) {
            target.setMultilingual(0, multilingualHalfRate, true);
            return;
        }
        for (var ch = 0; ch < multilingual; ch++) {
            if (multilingualDecoders[ch] == null) {
                multilingualDecoders[ch] = new Layer3(kernel);
            }
        }
        for (var granule = 0; granule < granules; granule++) {
            for (var ch = 0; ch < multilingual; ch++) {
                var decoder = multilingualDecoders[ch];
                var from = multilingualInfo[granule][ch];
                var to = decoder.granules[granule];
                copy(from, to);
                var shortBlocks = multilingualInfo[0][ch].blockType == 2
                        || (granules == 2 && multilingualInfo[1][ch].blockType == 2);
                to.scfsi = granule == 1 && !shortBlocks ? multilingualScfsi[ch] : 0;
                decoder.readFrom(stream, total, position);
                Arrays.fill(hybrid, 0);
                decoder.decodeSpectrum(header, granule, 1, hybrid);
                position = decoder.mainBits().position;
                decoder.finishGranule(header, granule, 1, hybrid);
                var samples = target.multilingualSamples(ch);
                for (var sb = 0; sb < 32; sb++) {
                    System.arraycopy(hybrid, sb * 18, samples, sb * 36 + granule * 18, 18);
                }
            }
        }
        bs.position = position;
        target.setMultilingual(multilingual, multilingualHalfRate, false);
    }

    private static void copy(GranuleInfo from, GranuleInfo to) {
        to.sfb = from.sfb;
        to.part23Length = from.part23Length;
        to.bigValues = from.bigValues;
        to.scalefacCompress = from.scalefacCompress;
        to.globalGain = from.globalGain;
        to.blockType = from.blockType;
        to.mixedBlock = from.mixedBlock;
        to.longBands = from.longBands;
        to.shortBands = from.shortBands;
        to.preflag = from.preflag;
        to.scalefacScale = from.scalefacScale;
        to.count1Table = from.count1Table;
        System.arraycopy(from.tableSelect, 0, to.tableSelect, 0, 3);
        System.arraycopy(from.regionCount, 0, to.regionCount, 0, 3);
        System.arraycopy(from.subblockGain, 0, to.subblockGain, 0, 3);
    }

    /**
     * Reads the scalefactors and Huffman data of every transmission channel.
     *
     * @return {@code false} when a channel runs past the end of the stream
     */
    private boolean readTransmissionChannels(BitReader bs) {
        for (var granule = 0; granule < 2; granule++) {
            for (var tc = LEFT; tc <= RIGHT_SURROUND; tc++) {
                var spectrum = lines[granule][tc];
                Arrays.fill(spectrum, 0);
                if (!tcPresent[granule][tc]) {
                    continue;
                }
                var gr = side[granule][tc];
                var limit = bs.position + gr.part23Length;
                if (limit > bs.limit) {
                    return false;
                }
                readScalefactors(bs, granule, tc, gr);
                Arrays.fill(coded, 0);
                buildBands(granule, tc, gr);
                Layer3.huffman(bs, codedGains, coded, 0, gr, limit);
                scatter(granule, tc, spectrum);
            }
        }
        return true;
    }

    /**
     * Reads the scalefactors of one granule of a transmission channel into {@link #scalefactorValues}, leaving out
     * the bands the channel does not code and taking those {@code scfsi} repeats from the first granule.
     */
    private void readScalefactors(BitReader bs, int granule, int tc, GranuleInfo gr) {
        var part = Mp3Tables.SCFC_DECODE[gr.scalefacCompress];
        var first = part >> 2;
        var second = part & 3;
        var values = scalefactorValues;
        var memory = scalefactorMemory[tc];
        var present = dataPresent[granule][tc];
        // the two scalefactor lengths split the bands the channel codes, not the bands of the spectrum
        var coded = 0;
        if (gr.blockType == 2) {
            for (var band = 0; band < 12; band++) {
                var transmitted = present[band];
                var length = coded < 6 ? first : second;
                for (var window = 0; window < 3; window++) {
                    values[3 * band + window] = transmitted && length > 0 ? bs.read(length) : 0;
                }
                if (transmitted) {
                    coded++;
                }
            }
            return;
        }
        for (var band = 0; band < 21; band++) {
            var transmitted = present[longGroup(band)];
            var length = coded < 11 ? first : second;
            if (transmitted) {
                coded++;
            }
            var group = band < 6 ? 0 : band < 11 ? 1 : band < 16 ? 2 : 3;
            if (granule == 1 && scfsi[tc][group] == 1) {
                values[band] = memory[band];
                continue;
            }
            values[band] = transmitted && length > 0 ? bs.read(length) : 0;
            memory[band] = values[band];
        }
    }

    /**
     * {@return the long block band group of a scalefactor band}
     *
     * @param band the scalefactor band
     */
    private static int longGroup(int band) {
        return band < 9 ? band / 3 : Math.min(band - 6, 14);
    }

    /**
     * Fills {@link #codedBands} and {@link #codedGains} with the bands one granule of a transmission channel codes.
     */
    private void buildBands(int granule, int tc, GranuleInfo gr) {
        var present = dataPresent[granule][tc];
        var shift = gr.scalefacScale + 1;
        var gainExponent = gr.globalGain - 4 - 210;
        var unit = Layer3.ldexpQ2(1 << 11, 44 - gainExponent);
        var n = 0;
        if (gr.blockType == 2) {
            var sh = 3 - shift;
            for (var group = 0; group < 12; group++) {
                if (!present[group]) {
                    continue;
                }
                for (var band = GROUPS_SHORT[group]; band < GROUPS_SHORT[group + 1]; band++) {
                    for (var window = 0; window < 3; window++) {
                        var scale = (band < 12 ? scalefactorValues[3 * band + window] : 0)
                                + (gr.subblockGain[window] << sh);
                        codedBands[n] = sfbShort[3 * band + window];
                        codedGains[n++] = Layer3.ldexpQ2(unit, scale << shift);
                    }
                }
            }
        } else {
            for (var group = 0; group < 15; group++) {
                if (!present[group]) {
                    continue;
                }
                for (var band = GROUPS_LONG[group]; band < GROUPS_LONG[group + 1]; band++) {
                    var scale = band < 21 ? scalefactorValues[band] : 0;
                    if (gr.preflag && n >= 11 && n < 21) {
                        scale += Mp3Tables.PREAMP[n - 11];
                    }
                    codedBands[n] = sfbLong[band];
                    codedGains[n++] = Layer3.ldexpQ2(unit, scale << shift);
                }
            }
        }
        codedBands[n] = 0;
    }

    /**
     * Moves the lines of {@link #coded} to the positions of their bands.
     */
    private void scatter(int granule, int tc, float[] spectrum) {
        var present = dataPresent[granule][tc];
        var starts = blockType[granule][tc] == 2 ? lineStartShort : lineStartLong;
        var from = 0;
        for (var group = 0; group + 1 < starts.length; group++) {
            if (present[group]) {
                var length = starts[group + 1] - starts[group];
                System.arraycopy(coded, from, spectrum, starts[group], length);
                from += length;
            }
        }
    }

    /**
     * Reads and transforms the low-frequency enhancement channel of both granules.
     *
     * @return {@code false} when its data runs past the end of the stream
     */
    private boolean readLfe(BitReader bs) {
        if (lfe == 0) {
            return true;
        }
        var end = bs.position + lfeBits;
        if (end > bs.limit) {
            return false;
        }
        Arrays.fill(lfeCoefficients, 0);
        var codebook = Mp3Tables.HUFFMAN_INDEX[lfeTable];
        var linbits = Mp3Tables.LINBITS[lfeTable];
        var scale = (float) StrictMath.pow(2, (lfeGain - 210) / 4.0);
        for (var l = 0; l < 6 && bs.position < end; l++) {
            var w = 5;
            int leaf = Mp3Tables.HUFFMAN[codebook + peek(bs, 5)];
            while (leaf < 0) {
                bs.position += w;
                w = leaf & 7;
                leaf = Mp3Tables.HUFFMAN[codebook + peek(bs, w) - (leaf >> 3)];
            }
            if (leaf >> 8 == 0) {
                break;
            }
            bs.position += leaf >> 8;
            for (var j = 0; j < 2; j++, leaf >>= 4) {
                var value = leaf & 0x0F;
                if (value == 15 && linbits != 0) {
                    value += bs.read(linbits);
                }
                var line = value == 0 ? 0 : scale * Layer3.pow43(value);
                if (value != 0 && bs.read(1) == 1) {
                    line = -line;
                }
                lfeCoefficients[j * 6 + l] = line;
            }
        }
        bs.position = end;
        for (var granule = 0; granule < 2; granule++) {
            for (var i = 0; i < 12; i++) {
                var sum = 0.0f;
                for (var k = 0; k < 6; k++) {
                    sum += lfeCoefficients[granule * 6 + k] * LFE_COS[i * 6 + k];
                }
                var windowed = sum * LFE_WINDOW[i];
                if (i < 6) {
                    lfeOutput[granule * 6 + i] = windowed + lfeOverlap[i];
                } else {
                    lfeOverlap[i - 6] = windowed;
                }
            }
        }
        System.arraycopy(lfeOutput, 0, target.lfeSamples(), 0, 12);
        return true;
    }

    /**
     * {@return the next {@code count} bits of a reader without moving it, 0 past its end}
     */
    private static int peek(BitReader bs, int count) {
        var position = bs.position;
        var value = bs.read(count);
        bs.position = position;
        return value;
    }

    /**
     * Assembles the channels of one granule and writes their subband samples to the target.
     *
     * @param granule the granule, 0 or 1
     * @param base the spectrum of the base channels in the coded order, {@code Ro} at index 576
     */
    void synthesize(int granule, float[] base) {
        if (center == 0 && surround == 0 && baseChannels == 1) {
            System.arraycopy(base, 0, output[LEFT], 0, 576);
            finish(granule, LEFT, 0, layer3.overlap[0]);
            return;
        }
        applyPrediction(granule, base);
        assemble(granule, base);
        if (procedure != 3 && baseChannels == 2) {
            dematrix(base);
        }
        var map = baseChannels == 2 ? 2 + (center != 0 ? 1 : 0) : 1;
        finish(granule, LEFT, 0, layer3.overlap[0]);
        if (baseChannels == 2) {
            finish(granule, RIGHT, 1, layer3.overlap[1]);
        }
        if (center != 0) {
            finish(granule, CENTER, 2, overlap[0]);
        }
        if (surround != 0) {
            finish(granule, LEFT_SURROUND, map, overlap[1]);
        }
        if (surround >= 2) {
            finish(granule, RIGHT_SURROUND, map + 1, overlap[2]);
        }
    }

    /**
     * Sets the target's channel configuration to that of the extension parsed last.
     */
    void configureTarget() {
        target.setChannels(baseChannels, center, surround, lfe);
    }

    /**
     * Adds the predictions to the transmission channels of one granule.
     */
    private void applyPrediction(int granule, float[] base) {
        if (!prediction) {
            return;
        }
        for (var tc = LEFT; tc <= RIGHT_SURROUND; tc++) {
            if (!tcPresent[granule][tc]) {
                continue;
            }
            var starts = blockType[granule][tc] == 2 ? lineStartShort : lineStartLong;
            var spectrum = lines[granule][tc];
            for (var group = 0; group + 1 < starts.length; group++) {
                if (!dataPresent[granule][tc][group]) {
                    continue;
                }
                for (var from = 0; from < baseChannels; from++) {
                    var coefficient = predictor[group][tc][from];
                    if (coefficient == 0
                            || layer3.granules[granule * baseChannels + from].blockType != blockType[granule][tc]) {
                        continue;
                    }
                    var offset = from * 576;
                    for (var line = starts[group]; line < starts[group + 1]; line++) {
                        spectrum[line] += coefficient * base[offset + line];
                    }
                }
            }
        }
    }

    /**
     * Assembles the output channels of one granule from their sources, marking the lines to dematrix.
     */
    private void assemble(int granule, float[] base) {
        Arrays.fill(lineMask, 0);
        for (var ch = LEFT; ch <= RIGHT_SURROUND; ch++) {
            var out = output[ch];
            Arrays.fill(out, 0);
            if (!channelPresent(ch) && !(ch == LEFT && baseChannels == 1)) {
                continue;
            }
            if (ch == LEFT && baseChannels == 1) {
                System.arraycopy(base, 0, out, 0, 576);
                continue;
            }
            var starts = blockType[granule][ch] == 2 ? lineStartShort : lineStartLong;
            for (var group = 0; group + 1 < starts.length; group++) {
                var from = source[granule][ch][group];
                var first = starts[group];
                var last = starts[group + 1];
                if (from == ZERO) {
                    continue;
                }
                if (from == DEMATRIXED) {
                    var bit = 1 << (ch - LEFT);
                    for (var line = first; line < last; line++) {
                        lineMask[line] |= bit;
                    }
                    continue;
                }
                var amount = gain[granule][ch][group];
                if (from < LEFT) {
                    var offset = from * 576;
                    for (var line = first; line < last; line++) {
                        out[line] = base[offset + line] * amount;
                    }
                } else {
                    var data = lines[granule][from];
                    for (var line = first; line < last; line++) {
                        out[line] = data[line] * amount;
                    }
                }
                if (matrixAttenuation) {
                    var js = from >= LEFT && carrier[granule][from][group];
                    Arrays.fill(lineGain[0][ch], first, last, js ? matrixGain[0][granule][from][group] : 1);
                    Arrays.fill(lineGain[1][ch], first, last, js ? matrixGain[1][granule][from][group] : 1);
                }
            }
        }
    }

    /**
     * Recovers the lines marked in {@link #lineMask} from the downmix equations of {@code Lo} and {@code Ro}.
     *
     * @param base the spectrum of the base channels, {@code Ro} at index 576
     */
    private void dematrix(float[] base) {
        var factors = DOWNMIX[procedure];
        var alpha = factors[0];
        var beta = factors[1];
        var gamma = factors[2];
        var l = output[LEFT];
        var r = output[RIGHT];
        var c = output[CENTER];
        // the channels of a second stereo program are not in the downmix
        var ls = surround == 3 ? NO_LINES : output[LEFT_SURROUND];
        var rs = surround == 3 ? NO_LINES : output[RIGHT_SURROUND];
        var mono = surround == 1;
        var phantom = center == 3;
        var matrixed = matrixAttenuation;
        var surroundMinus = procedure == 2;
        for (var i = 0; i < 576; i++) {
            var mask = lineMask[i];
            if (mask == 0) {
                continue;
            }
            var lo = base[i] / alpha;
            var ro = base[576 + i] / alpha;
            // the attenuated values of the two equations; a channel recovered here keeps gain 1
            var lGain = matrixed && (mask & 1) == 0 ? lineGain[0][LEFT][i] : 1;
            var rGain = matrixed && (mask & 2) == 0 ? lineGain[1][RIGHT][i] : 1;
            var cLeft = matrixed && (mask & 4) == 0 ? lineGain[0][CENTER][i] : 1;
            var cRight = matrixed && (mask & 4) == 0 ? lineGain[1][CENTER][i] : 1;
            var sLeft = matrixed && (mask & 8) == 0 ? lineGain[0][LEFT_SURROUND][i] : 1;
            var sRight = matrixed && (mask & (mono ? 8 : 16)) == 0
                    ? lineGain[1][mono ? LEFT_SURROUND : RIGHT_SURROUND][i] : 1;
            if ((mask & 4) != 0) {
                var value = (mask & (1 | 8)) == 0
                        ? (lo - lGain * l[i] - gamma * sLeft * leftSurroundTerm(ls, rs, i, mono, surroundMinus)) / beta
                        : (ro - rGain * r[i] - gamma * sRight * rightSurroundTerm(ls, rs, i, mono, surroundMinus)) / beta;
                c[i] = phantom && i >= phantomLines ? 0 : value;
            }
            if ((mask & 8) != 0 || (mask & 16) != 0) {
                recoverSurround(mask, i, lo, ro, lGain, rGain, cLeft, cRight, beta, gamma, mono, surroundMinus);
            }
            if ((mask & 1) != 0) {
                l[i] = lo - beta * cLeft * c[i] - gamma * sLeft * leftSurroundTerm(ls, rs, i, mono, surroundMinus);
            }
            if ((mask & 2) != 0) {
                r[i] = ro - beta * cRight * c[i] - gamma * sRight * rightSurroundTerm(ls, rs, i, mono, surroundMinus);
            }
        }
    }

    /**
     * {@return the surround term of the {@code Lo} equation of a line}
     */
    private static float leftSurroundTerm(float[] ls, float[] rs, int i, boolean mono, boolean minus) {
        if (minus) {
            return mono ? -ls[i] : -0.5f * (ls[i] + rs[i]);
        }
        return ls[i];
    }

    /**
     * {@return the surround term of the {@code Ro} equation of a line}
     */
    private static float rightSurroundTerm(float[] ls, float[] rs, int i, boolean mono, boolean minus) {
        if (minus) {
            return mono ? ls[i] : 0.5f * (ls[i] + rs[i]);
        }
        return mono ? ls[i] : rs[i];
    }

    /**
     * Recovers the dematrixed surround lines of one line.
     */
    private void recoverSurround(int mask, int i, float lo, float ro, float lGain, float rGain, float cLeft,
                                 float cRight, float beta, float gamma, boolean mono, boolean minus) {
        var l = output[LEFT];
        var r = output[RIGHT];
        var c = output[CENTER];
        var ls = output[LEFT_SURROUND];
        var rs = output[RIGHT_SURROUND];
        var fromLeft = (lo - lGain * l[i] - beta * cLeft * c[i]) / gamma;
        var fromRight = (ro - rGain * r[i] - beta * cRight * c[i]) / gamma;
        if (mono) {
            var useRight = (mask & 1) != 0;
            var value = useRight ? fromRight : fromLeft;
            ls[i] = minus && !useRight ? -value : value;
            return;
        }
        if (minus) {
            // the equations hold the mean of the surround channels with opposite signs
            var mean = (mask & 1) != 0 ? fromRight : -fromLeft;
            if ((mask & 24) == 24) {
                ls[i] = mean;
                rs[i] = mean;
            } else if ((mask & 8) != 0) {
                ls[i] = 2 * mean - rs[i];
            } else {
                rs[i] = 2 * mean - ls[i];
            }
            return;
        }
        if ((mask & 8) != 0) {
            ls[i] = fromLeft;
        }
        if ((mask & 16) != 0) {
            rs[i] = fromRight;
        }
    }

    /**
     * Runs the hybrid synthesis of one channel of one granule and stores its subband samples.
     *
     * @param granule the granule, 0 or 1
     * @param ch the channel number
     * @param index the channel's index in the target
     * @param state the channel's overlap
     */
    private void finish(int granule, int ch, int index, float[] state) {
        var base = wholeBaseChannel(granule, ch);
        var type = base != null ? base.blockType : blockType[granule][ch];
        var longBands = base != null && base.mixedBlock ? 2 : 0;
        var work = hybrid;
        System.arraycopy(output[ch], 0, work, 0, 576);
        if (type == 2) {
            layer3.reorder(work, longBands * 18, base != null ? base.sfb : sfbShort, base != null ? base.longBands : 0);
            Layer3.antialias(work, 0, longBands - 1);
        } else {
            Layer3.antialias(work, 0, 31);
        }
        layer3.imdct(work, 0, state, type, longBands);
        Layer3.changeSign(work, 0);
        var samples = target.channelSamples(index);
        for (var sb = 0; sb < 32; sb++) {
            System.arraycopy(work, sb * 18, samples, sb * 36 + granule * 18, 18);
        }
    }

    /**
     * {@return the base channel's side information when an output channel is that base channel in every band group,
     * so that its transform is the base channel's, whose windows can be mixed, a form {@code block_type} cannot name;
     * else {@code null}}
     *
     * @param granule the granule, 0 or 1
     * @param ch the channel number
     */
    private GranuleInfo wholeBaseChannel(int granule, int ch) {
        var from = ch - LEFT;
        if (ch > RIGHT || from >= baseChannels) {
            return null;
        }
        if (baseChannels == 2) {
            for (var group = 0; group < groupCount(granule, ch); group++) {
                if (source[granule][ch][group] != from) {
                    return null;
                }
            }
        }
        return layer3.granules[granule * baseChannels + from];
    }
}
