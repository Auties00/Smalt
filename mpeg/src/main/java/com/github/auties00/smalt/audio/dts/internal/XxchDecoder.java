package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

/**
 * Decodes the XXCH channel extension (ETSI TS 102 114 clause 6.5): up to four channel sets coded with the core
 * tools, whose downmix into the core and the lower channel sets the decoder undoes.
 *
 * <p>Instances are not thread-safe.
 */
final class XxchDecoder {
    /**
     * The channel sets of the frame.
     */
    private final ChannelSet[] sets = new ChannelSet[4];

    /**
     * The size in bytes of each channel set.
     */
    private final int[] setSizes = new int[4];

    /**
     * The downmix coefficient by channel set, channel and speaker bit of the channel it goes into.
     */
    private final float[][][] coefficients = new float[4][8][32];

    /**
     * The downmix map ({@code DownMixChMapMask}) by channel set and channel.
     */
    private final int[][] maps = new int[4][8];

    /**
     * Whether the encoder embedded the downmix of each channel set ({@code bDownMixEmbedded}).
     */
    private final boolean[] embedded = new boolean[4];

    /**
     * The inverse of the downmix scale of each channel set.
     */
    private final float[] inverseScales = new float[4];

    /**
     * The first plane of each channel set in the subband buffer.
     */
    private final int[] firstPlanes = new int[4];

    /**
     * The speaker bit by channel set and channel.
     */
    private final int[][] layouts = new int[4][8];

    /**
     * Constructs a decoder.
     */
    XxchDecoder() {
        for (var i = 0; i < sets.length; i++) {
            sets[i] = new ChannelSet();
        }
    }

    /**
     * Decodes an XXCH frame, adding its channels to the core decoder and undoing the downmixes they carry.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the number of bytes available
     * @param core the core decoder, its frame decoded
     * @param reader the reader to use
     * @return whether the frame was valid and decoded; {@code false} if the sync word is an alias
     * @throws MediaException.Malformed if the frame breaks the syntax after a valid header
     */
    boolean decode(byte[] data, int offset, int length, CoreDecoder core, MsbBitReader reader) {
        if (length < 8 || CoreDecoder.readInt(data, offset) != CoreDecoder.XXCH_SYNC) {
            return false;
        }
        reader.reset(data, offset, length);
        reader.skip(32);
        var headerSize = reader.read(6) + 1;
        if (headerSize > length || headerSize < 6 || !DtsCrc.check(data, offset + 4, headerSize - 4)) {
            return false;
        }
        var setCrc = reader.readFlag();
        var maskBits = reader.read(5) + 1;
        var setCount = reader.read(2) + 1;
        for (var s = 0; s < setCount; s++) {
            setSizes[s] = reader.read(14) + 1;
        }
        core.assignCoreSpeakers(reader.read(maskBits));
        var position = offset + headerSize;
        for (var s = 0; s < setCount; s++) {
            if (position + setSizes[s] > offset + length) {
                throw new MediaException.Malformed("DTS XXCH channel set overruns the frame");
            }
            decodeSet(data, position, setSizes[s], s, maskBits, setCrc, core, reader);
            position += setSizes[s];
        }
        for (var s = setCount - 1; s >= 0; s--) {
            if (embedded[s]) {
                undoDownmix(s, core);
            }
        }
        return true;
    }

    /**
     * Decodes one channel set: its header, then its subframes.
     */
    private void decodeSet(byte[] data, int position, int size, int index, int maskBits, boolean setCrc,
                           CoreDecoder core, MsbBitReader reader) {
        reader.reset(data, position, size);
        var headerSize = reader.read(7) + 1;
        if (headerSize > size || setCrc && !DtsCrc.check(data, position, headerSize)) {
            throw new MediaException.Malformed("DTS XXCH channel set header CRC mismatch");
        }
        var set = sets[index];
        set.channels = reader.read(3) + 1;
        var layout = reader.read(maskBits - 6) << 6;
        embedded[index] = false;
        if (reader.readFlag()) {
            embedded[index] = reader.readFlag();
            inverseScales[index] = DownmixCodes.inverseScale(reader.read(6));
            for (var ch = 0; ch < set.channels; ch++) {
                maps[index][ch] = reader.read(maskBits);
            }
            for (var ch = 0; ch < set.channels; ch++) {
                for (var bit = 0; bit < maskBits; bit++) {
                    coefficients[index][ch][bit] = (maps[index][ch] >> bit & 1) != 0
                            ? DownmixCodes.coefficient(reader.read(7)) : 0;
                }
            }
        }
        var ch = 0;
        for (var bit = 0; bit < 32 && ch < set.channels; bit++) {
            if ((layout >> bit & 1) != 0) {
                layouts[index][ch++] = 1 << bit;
            }
        }
        if (ch != set.channels) {
            throw new MediaException.Malformed("DTS XXCH layout mask names " + ch + " of " + set.channels
                    + " channels");
        }
        firstPlanes[index] = core.channels();
        set.firstPlane = firstPlanes[index];
        set.planeBands = core.planeBands();
        set.readHeader(reader);
        reader.checkNotOverrun();
        reader.seek(headerSize * 8L);
        for (var c = 0; c < set.channels; c++) {
            core.addChannel(layouts[index][c]);
        }
        core.decodeExtensionSet(set, reader);
    }

    /**
     * Undoes the downmix of a channel set into the core and lower channel sets, in the subband domain: every
     * channel of the downmix, LFE included, is divided by the downmix scale, then the channels of the set, times
     * their coefficients divided by the scale too, are subtracted from the channels their map names.
     */
    private void undoDownmix(int index, CoreDecoder core) {
        var set = sets[index];
        var undone = 0;
        for (var ch = 0; ch < set.channels; ch++) {
            undone |= maps[index][ch];
        }
        var inverse = inverseScales[index];
        if (inverse != 1) {
            for (var plane = 0; plane < firstPlanes[index]; plane++) {
                core.scaleChannel(plane, inverse);
            }
            core.scaleLfe(inverse);
        }
        for (var bit = 0; bit < 32; bit++) {
            if ((undone >> bit & 1) == 0) {
                continue;
            }
            var target = core.indexOf(1 << bit);
            if (target < 0 || target >= firstPlanes[index]) {
                continue;
            }
            for (var ch = 0; ch < set.channels; ch++) {
                var coefficient = coefficients[index][ch][bit];
                if (coefficient != 0) {
                    core.mixOut(set.firstPlane + ch, target, coefficient * inverse);
                }
            }
        }
    }
}
