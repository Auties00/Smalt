/* Ports libopus 1.5.2 (src/repacketizer.c). Copyright Xiph.Org Foundation and Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.*;

/**
 * Merges the frames of several Opus packets with the same configuration into one packet, and
 * pads packets to a fixed size.
 *
 * <p>The encoder uses it to assemble 40 and 60 ms CELT or hybrid packets (and 80 to 120 ms
 * packets) from 20 ms frames, to pad constant-bitrate packets, and to add the DRED extension. The padding
 * extensions of the input packets ({@link OpusExtensions}) are carried to the output, renumbered to the frames they
 * belong to there.
 *
 * @implNote This implementation gives an output of frames {@code [begin, end)} exactly the input extensions of those
 * frames. libopus 1.5.2 instead gives the output every extension of each input packet whose first frame lies in the
 * range, and none of a packet whose first frame precedes it, so splitting a packet moves all its extensions to the
 * first frame. Both agree whenever the range covers whole input packets whose extensions name frames of their own
 * packet, as every packet the encoder writes does; an extension naming a frame past the end of the range is dropped.
 */
public final class Repacketizer {
    /**
     * The table-of-contents byte shared by every frame.
     */
    private int toc;

    /**
     * The number of frames collected.
     */
    private int nb_frames;

    /**
     * The buffer holding each frame.
     */
    private final byte[][] frameData = new byte[OpusPacket.MAX_FRAMES][];

    /**
     * The offset of each frame in its buffer.
     */
    private final int[] frames = new int[OpusPacket.MAX_FRAMES];

    /**
     * The length of each frame.
     */
    private final int[] len = new int[OpusPacket.MAX_FRAMES];

    /**
     * The offset of the padding of the packet each frame starts, in {@link #frameData}; meaningful only where
     * {@link #paddingLen} is positive.
     */
    private final int[] paddings = new int[OpusPacket.MAX_FRAMES];

    /**
     * The padding length of the packet each frame starts, or 0 for the other frames of a packet.
     */
    private final int[] paddingLen = new int[OpusPacket.MAX_FRAMES];

    /**
     * The frame duration at 8 kHz.
     */
    private int framesize;

    /**
     * The layout scratch used to parse input packets.
     */
    private final OpusPacket.Layout layout = new OpusPacket.Layout();

    /**
     * The copy of the packet {@link #pad} reads its frames from, grown to the longest packet padded.
     */
    private byte[] padCopy = new byte[0];

    /**
     * The extensions of an output packet: the buffer, id, frame, payload offset and length of each, grown on demand.
     */
    private byte[][] extData = new byte[0][];
    private int[] extIds = new int[0];
    private int[] extFrames = new int[0];
    private int[] extOffsets = new int[0];
    private int[] extLengths = new int[0];

    /**
     * Creates an empty repacketizer.
     */
    public Repacketizer() {
    }

    /**
     * Discards the collected frames ({@code opus_repacketizer_init}).
     */
    public void init() {
        nb_frames = 0;
    }

    /**
     * Returns the number of frames collected ({@code opus_repacketizer_get_nb_frames}).
     *
     * @return the frame count
     */
    public int nbFrames() {
        return nb_frames;
    }

    /**
     * Appends the frames of a packet ({@code opus_repacketizer_cat}).
     *
     * <p>The packet's bytes are referenced, not copied, until the next {@link #init()}.
     *
     * @param data the buffer holding the packet
     * @param off  the index of the first byte
     * @param n    the packet length
     * @return {@link OpusDefines#OPUS_OK}, or {@link OpusDefines#OPUS_INVALID_PACKET} when the
     *         packet is corrupt, differs in configuration, or would exceed 120 ms
     */
    public int cat(byte[] data, int off, int n) {
        if (n < 1) {
            return OPUS_INVALID_PACKET;
        }
        if (nb_frames == 0) {
            toc = data[off] & 0xFF;
            framesize = OpusPacket.getSamplesPerFrame(toc, 8000);
        } else if ((toc & 0xFC) != (data[off] & 0xFC)) {
            return OPUS_INVALID_PACKET;
        }
        var curr_nb_frames = OpusPacket.getNbFrames(data, off, n);
        if (curr_nb_frames < 1) {
            return OPUS_INVALID_PACKET;
        }
        if ((curr_nb_frames + nb_frames) * framesize > 960) {
            return OPUS_INVALID_PACKET;
        }
        var ret = OpusPacket.parseImpl(data, off, n, false, layout);
        if (ret < 1) {
            return ret;
        }
        for (var i = 0; i < ret; i++) {
            frameData[nb_frames + i] = data;
            frames[nb_frames + i] = off + layout.frames[i];
            len[nb_frames + i] = layout.size[i];
            paddingLen[nb_frames + i] = 0;
        }
        paddings[nb_frames] = off + layout.paddingOffset;
        paddingLen[nb_frames] = layout.paddingLen;
        nb_frames += ret;
        return OPUS_OK;
    }

    /**
     * Writes frames {@code [begin, end)} as one packet ({@code opus_repacketizer_out_range_impl}).
     *
     * @param begin  the first frame
     * @param end    one past the last frame
     * @param data   the output buffer, which may hold the input frames when padding in place
     * @param off    the index of the first output byte
     * @param maxlen the space available
     * @param pad    whether to pad the packet to exactly {@code maxlen} bytes
     * @return the packet length, or a negative error code
     */
    public int outRange(int begin, int end, byte[] data, int off, int maxlen, boolean pad) {
        return outRange(begin, end, data, off, maxlen, false, pad, null, 0, 0);
    }

    /**
     * Writes frames {@code [begin, end)} as one packet, optionally in the self-delimited framing of
     * RFC 6716 appendix B that every stream of a multistream packet but the last uses
     * ({@code opus_repacketizer_out_range_impl}).
     *
     * @param begin         the first frame
     * @param end           one past the last frame
     * @param data          the output buffer, which may hold the input frames when padding in place
     * @param off           the index of the first output byte
     * @param maxlen        the space available
     * @param selfDelimited whether to code the length of the last frame
     * @param pad           whether to pad the packet to exactly {@code maxlen} bytes
     * @return the packet length, or a negative error code
     */
    public int outRange(int begin, int end, byte[] data, int off, int maxlen, boolean selfDelimited, boolean pad) {
        return outRange(begin, end, data, off, maxlen, selfDelimited, pad, null, 0, 0);
    }

    /**
     * Grows the extension arrays to hold {@code count} entries.
     */
    private void ensureExtensions(int count) {
        if (extIds.length < count) {
            var n = Math.max(count, 2 * extIds.length);
            extData = java.util.Arrays.copyOf(extData, n);
            extIds = java.util.Arrays.copyOf(extIds, n);
            extFrames = java.util.Arrays.copyOf(extFrames, n);
            extOffsets = java.util.Arrays.copyOf(extOffsets, n);
            extLengths = java.util.Arrays.copyOf(extLengths, n);
        }
    }

    /**
     * Writes frames {@code [begin, end)} as one packet with an extra extension in frame 0 of the output, after the
     * extensions of the input packets ({@code opus_repacketizer_out_range_impl}).
     *
     * @param begin         the first frame
     * @param end           one past the last frame
     * @param data          the output buffer, which may hold the input frames when padding in place
     * @param off           the index of the first output byte
     * @param maxlen        the space available
     * @param selfDelimited whether to code the length of the last frame
     * @param pad           whether to pad the packet to exactly {@code maxlen} bytes
     * @param extension     the buffer holding the payload of the extra extension, or {@code null} for none
     * @param extensionId   the id of the extra extension, from 32 to 127
     * @param extensionLen  the payload length of the extra extension, starting at index 0 of {@code extension}
     * @return the packet length, or a negative error code
     */
    public int outRange(int begin, int end, byte[] data, int off, int maxlen, boolean selfDelimited, boolean pad,
                        byte[] extension, int extensionId, int extensionLen) {
        if (begin < 0 || begin >= end || end > nb_frames) {
            return OPUS_BAD_ARG;
        }
        var count = end - begin;
        var tot_size = selfDelimited ? 1 + (len[end - 1] >= 252 ? 1 : 0) : 0;
        var ptr = off;
        var ones_begin = 0;
        var ones_end = 0;
        var ext_begin = 0;
        var ext_len = 0;
        var total_ext_count = extension != null ? 1 : 0;
        // The padding of an input packet is held by its first frame, which may precede begin.
        for (var i = 0; i < end; i++) {
            if (paddingLen[i] > 0) {
                var n = OpusExtensions.count(frameData[i], paddings[i], paddingLen[i]);
                if (n > 0) {
                    total_ext_count += n;
                }
            }
        }
        ensureExtensions(total_ext_count);
        var ext_count = 0;
        if (extension != null) {
            extData[0] = extension;
            extIds[0] = extensionId;
            extFrames[0] = 0;
            extOffsets[0] = 0;
            extLengths[0] = extensionLen;
            ext_count = 1;
        }
        for (var i = 0; i < end; i++) {
            if (paddingLen[i] <= 0) {
                continue;
            }
            var frameExtCount = OpusExtensions.parse(frameData[i], paddings[i], paddingLen[i], extIds, extFrames,
                    extOffsets, extLengths, ext_count, total_ext_count - ext_count);
            if (frameExtCount < 0) {
                return OPUS_INTERNAL_ERROR;
            }
            var kept = 0;
            for (var j = 0; j < frameExtCount; j++) {
                var frame = i + extFrames[ext_count + j];
                if (frame < begin || frame >= end) {
                    continue;
                }
                var at = ext_count + kept++;
                extData[at] = frameData[i];
                extIds[at] = extIds[ext_count + j];
                extFrames[at] = frame - begin;
                extOffsets[at] = extOffsets[ext_count + j];
                extLengths[at] = extLengths[ext_count + j];
            }
            ext_count += kept;
        }
        if (count == 1) {
            tot_size += len[begin] + 1;
            if (tot_size > maxlen) {
                return OPUS_BUFFER_TOO_SMALL;
            }
            data[ptr++] = (byte) (toc & 0xFC);
        } else if (count == 2) {
            if (len[begin + 1] == len[begin]) {
                tot_size += 2 * len[begin] + 1;
                if (tot_size > maxlen) {
                    return OPUS_BUFFER_TOO_SMALL;
                }
                data[ptr++] = (byte) ((toc & 0xFC) | 0x1);
            } else {
                tot_size += len[begin] + len[begin + 1] + 2 + (len[begin] >= 252 ? 1 : 0);
                if (tot_size > maxlen) {
                    return OPUS_BUFFER_TOO_SMALL;
                }
                data[ptr++] = (byte) ((toc & 0xFC) | 0x2);
                ptr += OpusPacket.encodeSize(len[begin], data, ptr);
            }
        }
        if (count > 2 || (pad && tot_size < maxlen) || ext_count > 0) {
            ptr = off;
            tot_size = selfDelimited ? 1 + (len[end - 1] >= 252 ? 1 : 0) : 0;
            var vbr = false;
            for (var i = 1; i < count; i++) {
                if (len[begin + i] != len[begin]) {
                    vbr = true;
                    break;
                }
            }
            if (vbr) {
                tot_size += 2;
                for (var i = 0; i < count - 1; i++) {
                    tot_size += 1 + (len[begin + i] >= 252 ? 1 : 0) + len[begin + i];
                }
                tot_size += len[begin + count - 1];
                if (tot_size > maxlen) {
                    return OPUS_BUFFER_TOO_SMALL;
                }
                data[ptr++] = (byte) ((toc & 0xFC) | 0x3);
                data[ptr++] = (byte) (count | 0x80);
            } else {
                tot_size += count * len[begin] + 2;
                if (tot_size > maxlen) {
                    return OPUS_BUFFER_TOO_SMALL;
                }
                data[ptr++] = (byte) ((toc & 0xFC) | 0x3);
                data[ptr++] = (byte) count;
            }
            var pad_amount = pad ? maxlen - tot_size : 0;
            if (ext_count > 0) {
                ext_len = OpusExtensions.generate(null, 0, maxlen - tot_size, extData, extIds, extFrames, extOffsets,
                        extLengths, ext_count, false);
                if (ext_len < 0) {
                    return ext_len;
                }
                if (!pad) {
                    pad_amount = ext_len + ext_len / 254 + 1;
                }
            }
            if (pad_amount != 0) {
                data[off + 1] |= 0x40;
                var nb_255s = (pad_amount - 1) / 255;
                if (tot_size + ext_len + nb_255s + 1 > maxlen) {
                    return OPUS_BUFFER_TOO_SMALL;
                }
                ext_begin = tot_size + pad_amount - ext_len;
                ones_begin = tot_size + nb_255s + 1;
                ones_end = tot_size + pad_amount - ext_len;
                for (var i = 0; i < nb_255s; i++) {
                    data[ptr++] = (byte) 255;
                }
                data[ptr++] = (byte) (pad_amount - 255 * nb_255s - 1);
                tot_size += pad_amount;
            }
            if (vbr) {
                for (var i = 0; i < count - 1; i++) {
                    ptr += OpusPacket.encodeSize(len[begin + i], data, ptr);
                }
            }
        }
        if (selfDelimited) {
            ptr += OpusPacket.encodeSize(len[end - 1], data, ptr);
        }
        for (var i = 0; i < count; i++) {
            System.arraycopy(frameData[begin + i], frames[begin + i], data, ptr, len[begin + i]);
            ptr += len[begin + i];
        }
        if (ext_len > 0) {
            OpusExtensions.generate(data, off + ext_begin, ext_len, extData, extIds, extFrames, extOffsets,
                    extLengths, ext_count, false);
        }
        for (var i = ones_begin; i < ones_end; i++) {
            data[off + i] = 0x01;
        }
        if (pad && ext_count == 0) {
            while (ptr < off + maxlen) {
                data[ptr++] = 0;
            }
        }
        return tot_size;
    }

    /**
     * Pads a packet in place to exactly {@code newLen} bytes ({@code opus_packet_pad}), discarding
     * the frames this repacketizer collected.
     *
     * @param data   the buffer holding the packet, with room for {@code newLen} bytes
     * @param off    the index of the first byte
     * @param n      the current packet length
     * @param newLen the padded length
     * @return {@link OpusDefines#OPUS_OK} or a negative error code
     */
    int pad(byte[] data, int off, int n, int newLen) {
        var ret = padWith(data, off, n, newLen, true, null, 0, 0);
        return ret > 0 ? OPUS_OK : ret;
    }

    /**
     * Rewrites a packet in place with an extra extension, padded to {@code newLen} bytes or, without {@code pad},
     * only as long as the extensions need ({@code opus_packet_pad_impl}).
     *
     * @param data         the buffer holding the packet, with room for {@code newLen} bytes
     * @param off          the index of the first byte
     * @param n            the current packet length
     * @param newLen       the largest packet length
     * @param pad          whether to pad to exactly {@code newLen} bytes
     * @param extension    the buffer holding the payload of the extra extension, or {@code null} for none
     * @param extensionId  the id of the extra extension, from 32 to 127
     * @param extensionLen the payload length of the extra extension, starting at index 0 of {@code extension}
     * @return the new packet length, {@link OpusDefines#OPUS_OK} when {@code n == newLen}, or a negative error code
     */
    int padWith(byte[] data, int off, int n, int newLen, boolean pad, byte[] extension, int extensionId,
                int extensionLen) {
        if (n < 1) {
            return OPUS_BAD_ARG;
        }
        if (n == newLen) {
            return OPUS_OK;
        } else if (n > newLen) {
            return OPUS_BAD_ARG;
        }
        if (padCopy.length < n) {
            padCopy = new byte[n];
        }
        System.arraycopy(data, off, padCopy, 0, n);
        init();
        var ret = cat(padCopy, 0, n);
        if (ret != OPUS_OK) {
            return ret;
        }
        return outRange(0, nb_frames, data, off, newLen, false, pad, extension, extensionId, extensionLen);
    }
}
