package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

/**
 * Decodes the XBR extended bit rate extension (ETSI TS 102 114 clause 6.3): residual subband samples that refine
 * the core and its channel extensions, channel set by channel set.
 *
 * <p>Instances are not thread-safe.
 */
final class XbrDecoder {
    /**
     * The XBR sync word.
     */
    static final int SYNC = 0x655E315E;

    /**
     * The block code widths in bits, by ABITS 1 to 7.
     */
    private static final int[] BLOCK_BITS = {0, 7, 10, 12, 13, 15, 17, 19};

    /**
     * The block code levels, by ABITS 1 to 7.
     */
    private static final int[] LEVELS = {0, 3, 5, 7, 9, 13, 17, 25};

    /**
     * The reader of the extension.
     */
    private final MsbBitReader reader = new MsbBitReader(new byte[0]);

    /**
     * The size in bytes of each channel set.
     */
    private final int[] setSizes = new int[4];

    /**
     * The number of channels of each channel set.
     */
    private final int[] setChannels = new int[4];

    /**
     * The number of active subbands by channel set and channel.
     */
    private final int[][] activeBands = new int[4][8];

    /**
     * The bit allocation index by channel and subband.
     */
    private final int[][] allocation = new int[8][64];

    /**
     * The scale factors by channel and subband, before and after the transient.
     */
    private final float[][][] scales = new float[8][64][2];

    /**
     * The quantization indexes of the eight samples being read.
     */
    private final int[] codes = new int[8];

    /**
     * The width of the bit allocation indexes of each channel of the subframe.
     */
    private final int[] abitsBits = new int[8];

    /**
     * The width of the scale factor indexes of each channel of the subframe.
     */
    private final int[] scaleBits = new int[8];

    /**
     * Decodes an XBR component and adds its residual to the subband samples of the core.
     *
     * @param data the bytes
     * @param offset the index of the component
     * @param length the component size
     * @param core the core decoder, its frame decoded
     * @throws MediaException.Malformed if the component breaks the syntax
     */
    void decode(byte[] data, int offset, int length, CoreDecoder core) {
        if (length < 8 || CoreDecoder.readInt(data, offset) != SYNC) {
            throw new MediaException.Malformed("missing DTS XBR sync word");
        }
        reader.reset(data, offset, length);
        reader.skip(32);
        var headerSize = reader.read(6) + 1;
        if (headerSize > length || !DtsCrc.check(data, offset + 4, headerSize - 4)) {
            throw new MediaException.Malformed("DTS XBR header CRC mismatch");
        }
        var setCount = reader.read(2) + 1;
        for (var s = 0; s < setCount; s++) {
            setSizes[s] = reader.read(14) + 1;
        }
        var useTransients = reader.readFlag();
        for (var s = 0; s < setCount; s++) {
            setChannels[s] = reader.read(3) + 1;
            var bandBits = reader.read(2) + 5;
            for (var ch = 0; ch < setChannels[s]; ch++) {
                activeBands[s][ch] = reader.read(bandBits) + 1;
                if (activeBands[s][ch] > 64) {
                    throw new MediaException.Malformed("DTS XBR codes " + activeBands[s][ch] + " subbands");
                }
            }
        }
        reader.checkNotOverrun();
        var position = offset + headerSize;
        var firstPlane = 0;
        for (var s = 0; s < setCount; s++) {
            if (position + setSizes[s] > offset + length) {
                throw new MediaException.Malformed("DTS XBR channel set overruns the component");
            }
            if (firstPlane + setChannels[s] > core.channels()) {
                throw new MediaException.Malformed("DTS XBR refines more channels than the core decoded");
            }
            reader.reset(data, position, setSizes[s]);
            decodeSet(s, firstPlane, useTransients, core);
            firstPlane += setChannels[s];
            position += setSizes[s];
        }
    }

    /**
     * Decodes the subframes of one channel set and adds the residual to its planes.
     */
    private void decodeSet(int set, int firstPlane, boolean useTransients, CoreDecoder core) {
        var channels = setChannels[set];
        var buffer = core.buffer();
        var steps = DtsCoreTables.STEP_LOSSLESS;
        var everySubsubframe = core.header().syncEverySubsubframe;
        for (var subframe = 0; subframe < core.subframes(); subframe++) {
            var subsubframes = core.subsubframes(subframe);
            var offset = core.subframeOffset(subframe);
            for (var ch = 0; ch < channels; ch++) {
                abitsBits[ch] = reader.read(2) + 2;
            }
            for (var ch = 0; ch < channels; ch++) {
                for (var n = 0; n < activeBands[set][ch]; n++) {
                    allocation[ch][n] = reader.read(abitsBits[ch]);
                    if (allocation[ch][n] > 26) {
                        throw new MediaException.Malformed("invalid DTS XBR bit allocation index");
                    }
                }
            }
            for (var ch = 0; ch < channels; ch++) {
                scaleBits[ch] = reader.read(3);
                if (scaleBits[ch] < 1) {
                    throw new MediaException.Malformed("invalid DTS XBR scale index width");
                }
            }
            for (var ch = 0; ch < channels; ch++) {
                var plane = firstPlane + ch;
                var table = core.scaleBook(plane) == 6 ? ChannelSet.RMS7 : ChannelSet.RMS6;
                for (var n = 0; n < activeBands[set][ch]; n++) {
                    scales[ch][n][0] = 0;
                    scales[ch][n][1] = 0;
                    if (allocation[ch][n] > 0) {
                        scales[ch][n][0] = lookUp(table, reader.read(scaleBits[ch]));
                        if (useTransients && core.transientMode(subframe, plane, n) > 0) {
                            scales[ch][n][1] = lookUp(table, reader.read(scaleBits[ch]));
                        }
                    }
                }
            }
            for (var subsubframe = 0; subsubframe < subsubframes; subsubframe++) {
                for (var ch = 0; ch < channels; ch++) {
                    var plane = firstPlane + ch;
                    var planes = buffer.planes[plane];
                    for (var n = 0; n < activeBands[set][ch]; n++) {
                        var abits = allocation[ch][n];
                        if (abits == 0) {
                            continue;
                        }
                        readCodes(abits);
                        var mode = useTransients ? core.transientMode(subframe, plane, n) : 0;
                        var scale = subsubframe < (mode == 0 ? subsubframes : mode) ? scales[ch][n][0]
                                : scales[ch][n][1];
                        var factor = scale * (steps[abits] * (1.0f / (1 << 22)));
                        var base = SubbandBuffer.HISTORY + offset + 8 * subsubframe;
                        var samples = planes[n];
                        for (var m = 0; m < 8; m++) {
                            samples[base + m] += factor * codes[m];
                        }
                    }
                }
                if (subsubframe == subsubframes - 1 || everySubsubframe) {
                    if (reader.read(16) != 0xFFFF) {
                        throw new MediaException.Malformed("DTS XBR DSYNC word missing");
                    }
                }
            }
        }
        reader.checkNotOverrun();
    }

    /**
     * Reads the eight residual codes of one subband and subsubframe.
     */
    private void readCodes(int abits) {
        if (abits > 7) {
            for (var m = 0; m < 8; m++) {
                codes[m] = reader.readSigned(abits - 3);
            }
            return;
        }
        var levels = LEVELS[abits];
        var offset = (levels - 1) >> 1;
        for (var block = 0; block < 2; block++) {
            var code = reader.read(BLOCK_BITS[abits]);
            for (var m = 0; m < 4; m++) {
                codes[block * 4 + m] = code % levels - offset;
                code /= levels;
            }
            if (code != 0) {
                throw new MediaException.Malformed("invalid DTS XBR block code");
            }
        }
    }

    /**
     * Looks a scale factor index up in a root square table.
     *
     * @param table the root square table
     * @param index the scale factor index
     * @return the scale factor
     * @throws MediaException.Malformed if the index is outside the table
     */
    private static float lookUp(float[] table, int index) {
        if (index >= table.length) {
            throw new MediaException.Malformed("invalid DTS XBR scale factor index " + index);
        }
        return table[index];
    }
}
