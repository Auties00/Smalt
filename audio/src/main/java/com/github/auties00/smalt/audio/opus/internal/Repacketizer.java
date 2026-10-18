/* Ports libopus 1.5.2 (src/repacketizer.c). Copyright Xiph.Org Foundation and Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.*;

/**
 * Merges the frames of several Opus packets with the same configuration into one packet, and
 * pads packets to a fixed size.
 *
 * <p>The encoder uses it to assemble 40 and 60 ms CELT or hybrid packets (and 80 to 120 ms
 * packets) from 20 ms frames, and to pad constant-bitrate packets.
 *
 * @implNote This implementation omits the padding-extension machinery: the encoder's own frames only ever carry zero
 * padding, which holds no extensions, so the output is identical.
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
     * The frame duration at 8 kHz.
     */
    private int framesize;

    /**
     * The layout scratch used to parse input packets.
     */
    private final OpusPacket.Layout layout = new OpusPacket.Layout();

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
        }
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
        if (begin < 0 || begin >= end || end > nb_frames) {
            return OPUS_BAD_ARG;
        }
        var count = end - begin;
        var tot_size = 0;
        var ptr = off;
        var ones_begin = 0;
        var ones_end = 0;
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
        if (count > 2 || (pad && tot_size < maxlen)) {
            ptr = off;
            tot_size = 0;
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
            if (pad_amount != 0) {
                data[off + 1] |= 0x40;
                var nb_255s = (pad_amount - 1) / 255;
                if (tot_size + nb_255s + 1 > maxlen) {
                    return OPUS_BUFFER_TOO_SMALL;
                }
                ones_begin = tot_size + nb_255s + 1;
                ones_end = tot_size + pad_amount;
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
        for (var i = 0; i < count; i++) {
            System.arraycopy(frameData[begin + i], frames[begin + i], data, ptr, len[begin + i]);
            ptr += len[begin + i];
        }
        for (var i = ones_begin; i < ones_end; i++) {
            data[off + i] = 0x01;
        }
        if (pad) {
            while (ptr < off + maxlen) {
                data[ptr++] = 0;
            }
        }
        return tot_size;
    }

    /**
     * Pads a packet in place to exactly {@code newLen} bytes ({@code opus_packet_pad}).
     *
     * @param data   the buffer holding the packet, with room for {@code newLen} bytes
     * @param off    the index of the first byte
     * @param n      the current packet length
     * @param newLen the padded length
     * @return {@link OpusDefines#OPUS_OK} or a negative error code
     */
    static int pad(byte[] data, int off, int n, int newLen) {
        if (n < 1) {
            return OPUS_BAD_ARG;
        }
        if (n == newLen) {
            return OPUS_OK;
        } else if (n > newLen) {
            return OPUS_BAD_ARG;
        }
        var copy = new byte[n];
        System.arraycopy(data, off, copy, 0, n);
        var rp = new Repacketizer();
        var ret = rp.cat(copy, 0, n);
        if (ret != OPUS_OK) {
            return ret;
        }
        ret = rp.outRange(0, rp.nb_frames, data, off, newLen, true);
        return ret > 0 ? OPUS_OK : ret;
    }
}
