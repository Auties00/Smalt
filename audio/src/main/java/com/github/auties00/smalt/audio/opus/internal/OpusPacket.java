/* Ports libopus 1.5.2 (src/opus.c, src/opus_decoder.c). Copyright Xiph.Org Foundation and Skype Limited; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.*;

/**
 * Parses and inspects Opus packets (RFC 6716 section 3) and soft-clips decoded audio.
 *
 * <p>Every parser validates each length against the bytes actually present before using it, so
 * any byte sequence either parses into frames that lie inside the packet or is rejected with
 * {@link OpusDefines#OPUS_INVALID_PACKET}.
 */
public final class OpusPacket {
    /**
     * The largest number of frames in one packet (48 frames of 2.5 ms).
     */
    public static final int MAX_FRAMES = 48;

    /**
     * The largest frame payload in bytes.
     */
    public static final int MAX_FRAME_BYTES = 1275;

    /**
     * Holds the result of {@link #parseImpl(byte[], int, int, boolean, Layout)}.
     */
    public static final class Layout {
        /**
         * The table-of-contents byte of the packet.
         */
        public int toc;

        /**
         * The offset, relative to the packet start, of each frame's payload.
         */
        public final int[] frames = new int[MAX_FRAMES];

        /**
         * The size in bytes of each frame.
         */
        public final int[] size = new int[MAX_FRAMES];

        /**
         * The offset, relative to the packet start, of the first frame's payload.
         */
        public int payloadOffset;

        /**
         * The number of bytes the packet occupies including its padding, relevant for
         * self-delimited framing.
         */
        public int packetOffset;

        /**
         * The offset, relative to the packet start, of the padding bytes.
         */
        public int paddingOffset;

        /**
         * The number of padding bytes.
         */
        public int paddingLen;

        /**
         * Creates an empty layout.
         */
        public Layout() {
        }
    }

    private OpusPacket() {
        throw new AssertionError();
    }

    /**
     * Returns the coding mode signaled by a table-of-contents byte ({@code opus_packet_get_mode}).
     *
     * @param toc the table-of-contents byte
     * @return {@link OpusDefines#MODE_SILK_ONLY}, {@link OpusDefines#MODE_HYBRID} or
     *         {@link OpusDefines#MODE_CELT_ONLY}
     */
    public static int getMode(int toc) {
        if ((toc & 0x80) != 0) {
            return MODE_CELT_ONLY;
        } else if ((toc & 0x60) == 0x60) {
            return MODE_HYBRID;
        } else {
            return MODE_SILK_ONLY;
        }
    }

    /**
     * Returns the audio bandwidth signaled by a table-of-contents byte
     * ({@code opus_packet_get_bandwidth}).
     *
     * @param toc the table-of-contents byte
     * @return one of the {@code OPUS_BANDWIDTH_*} constants
     */
    public static int getBandwidth(int toc) {
        int bandwidth;
        if ((toc & 0x80) != 0) {
            bandwidth = OPUS_BANDWIDTH_MEDIUMBAND + ((toc >> 5) & 0x3);
            if (bandwidth == OPUS_BANDWIDTH_MEDIUMBAND) {
                bandwidth = OPUS_BANDWIDTH_NARROWBAND;
            }
        } else if ((toc & 0x60) == 0x60) {
            bandwidth = (toc & 0x10) != 0 ? OPUS_BANDWIDTH_FULLBAND : OPUS_BANDWIDTH_SUPERWIDEBAND;
        } else {
            bandwidth = OPUS_BANDWIDTH_NARROWBAND + ((toc >> 5) & 0x3);
        }
        return bandwidth;
    }

    /**
     * Returns the number of coded channels signaled by a table-of-contents byte
     * ({@code opus_packet_get_nb_channels}).
     *
     * @param toc the table-of-contents byte
     * @return {@code 1} or {@code 2}
     */
    public static int getNbChannels(int toc) {
        return (toc & 0x4) != 0 ? 2 : 1;
    }

    /**
     * Returns the number of samples per frame signaled by a table-of-contents byte
     * ({@code opus_packet_get_samples_per_frame}).
     *
     * @param toc the table-of-contents byte
     * @param Fs  the sampling rate the samples are counted at
     * @return the frame size in samples
     */
    public static int getSamplesPerFrame(int toc, int Fs) {
        int audiosize;
        if ((toc & 0x80) != 0) {
            audiosize = (toc >> 3) & 0x3;
            audiosize = (Fs << audiosize) / 400;
        } else if ((toc & 0x60) == 0x60) {
            audiosize = (toc & 0x08) != 0 ? Fs / 50 : Fs / 100;
        } else {
            audiosize = (toc >> 3) & 0x3;
            if (audiosize == 3) {
                audiosize = Fs * 60 / 1000;
            } else {
                audiosize = (Fs << audiosize) / 100;
            }
        }
        return audiosize;
    }

    /**
     * Returns the number of frames in a packet ({@code opus_packet_get_nb_frames}).
     *
     * @param packet the buffer holding the packet
     * @param off    the index of the table-of-contents byte
     * @param len    the packet length
     * @return the frame count, or a negative error code
     */
    public static int getNbFrames(byte[] packet, int off, int len) {
        if (len < 1) {
            return OPUS_BAD_ARG;
        }
        var count = packet[off] & 0x3;
        if (count == 0) {
            return 1;
        } else if (count != 3) {
            return 2;
        } else if (len < 2) {
            return OPUS_INVALID_PACKET;
        } else {
            return packet[off + 1] & 0x3F;
        }
    }

    /**
     * Returns the number of samples a packet decodes to ({@code opus_packet_get_nb_samples}).
     *
     * @param packet the buffer holding the packet
     * @param off    the index of the table-of-contents byte
     * @param len    the packet length
     * @param Fs     the sampling rate the samples are counted at
     * @return the sample count, or a negative error code for an invalid packet or one longer
     *         than 120 ms
     */
    public static int getNbSamples(byte[] packet, int off, int len, int Fs) {
        var count = getNbFrames(packet, off, len);
        if (count < 0) {
            return count;
        }
        var samples = count * getSamplesPerFrame(packet[off] & 0xFF, Fs);
        if (samples * 25 > Fs * 3) {
            return OPUS_INVALID_PACKET;
        }
        return samples;
    }

    /**
     * Returns whether a packet carries in-band FEC (LBRR) data for the previous packet
     * ({@code opus_packet_has_lbrr}).
     *
     * <p>Only the LBRR flags of the first SILK frame are read: the mid channel's, and for a stereo
     * packet the side channel's too. A CELT-only packet and a packet whose first frame is empty carry
     * none.
     *
     * @implNote This implementation reports an empty first frame as carrying no LBRR data, where the
     * reference reads the byte after the frame (past the end of a one-byte packet).
     *
     * @param packet the buffer holding the packet
     * @param off    the index of the table-of-contents byte
     * @param len    the packet length
     * @param layout receives the frame layout of the packet
     * @return {@code 1} when the packet carries LBRR data, {@code 0} when it does not, or a negative
     *         error code for an invalid packet
     */
    public static int hasLbrr(byte[] packet, int off, int len, Layout layout) {
        if (len < 1) {
            return OPUS_BAD_ARG;
        }
        var toc = packet[off] & 0xFF;
        if (getMode(toc) == MODE_CELT_ONLY) {
            return 0;
        }
        var packetFrameSize = getSamplesPerFrame(toc, 48000);
        var nbFrames = packetFrameSize > 960 ? packetFrameSize / 960 : 1;
        var ret = parseImpl(packet, off, len, false, layout);
        if (ret <= 0) {
            return ret;
        }
        if (layout.size[0] == 0) {
            return 0;
        }
        var first = packet[off + layout.frames[0]] & 0xFF;
        var lbrr = (first >> (7 - nbFrames)) & 0x1;
        if (getNbChannels(toc) == 2) {
            lbrr |= (first >> (6 - 2 * nbFrames)) & 0x1;
        }
        return lbrr;
    }

    /**
     * Writes a frame length in the one- or two-byte packet encoding ({@code encode_size}).
     *
     * @param size the length, at most 1275
     * @param data the output buffer
     * @param off  the index of the first byte to write
     * @return the number of bytes written
     */
    public static int encodeSize(int size, byte[] data, int off) {
        if (size < 252) {
            data[off] = (byte) size;
            return 1;
        } else {
            var first = 252 + (size & 0x3);
            data[off] = (byte) first;
            data[off + 1] = (byte) ((size - first) >> 2);
            return 2;
        }
    }

    /**
     * Reads a frame length in the one- or two-byte packet encoding ({@code parse_size}).
     *
     * @param data the buffer
     * @param off  the index of the first length byte
     * @param len  the number of bytes available
     * @param size receives the length at {@code size[index]}, or {@code -1} when truncated
     * @param index the index in {@code size} to store to
     * @return the number of bytes consumed, or {@code -1} when truncated
     */
    private static int parseSize(byte[] data, int off, int len, int[] size, int index) {
        if (len < 1) {
            size[index] = -1;
            return -1;
        }
        var b0 = data[off] & 0xFF;
        if (b0 < 252) {
            size[index] = b0;
            return 1;
        } else if (len < 2) {
            size[index] = -1;
            return -1;
        } else {
            size[index] = 4 * (data[off + 1] & 0xFF) + b0;
            return 2;
        }
    }

    /**
     * Splits a packet into its frames ({@code opus_packet_parse_impl}).
     *
     * @param data          the buffer holding the packet
     * @param off           the index of the table-of-contents byte
     * @param len           the number of bytes available
     * @param selfDelimited whether the packet uses the self-delimited framing of RFC 6716
     *                      appendix B (every stream of a multistream packet but the last)
     * @param layout        receives the table of contents, frame offsets and sizes, and the
     *                      packet and padding extents
     * @return the number of frames, or a negative error code
     */
    public static int parseImpl(byte[] data, int off, int len, boolean selfDelimited, Layout layout) {
        var size = layout.size;
        var pad = 0;
        if (len < 0) {
            return OPUS_BAD_ARG;
        }
        if (len == 0) {
            return OPUS_INVALID_PACKET;
        }
        var data0 = off;
        var framesize = getSamplesPerFrame(data[off] & 0xFF, 48000);
        var cbr = false;
        var toc = data[off++] & 0xFF;
        len--;
        var lastSize = len;
        int count;
        int bytes;
        switch (toc & 0x3) {
            case 0 -> count = 1;
            case 1 -> {
                count = 2;
                cbr = true;
                if (!selfDelimited) {
                    if ((len & 0x1) != 0) {
                        return OPUS_INVALID_PACKET;
                    }
                    lastSize = len / 2;
                    size[0] = lastSize;
                }
            }
            case 2 -> {
                count = 2;
                bytes = parseSize(data, off, len, size, 0);
                len -= bytes;
                if (size[0] < 0 || size[0] > len) {
                    return OPUS_INVALID_PACKET;
                }
                off += bytes;
                lastSize = len - size[0];
            }
            default -> {
                if (len < 1) {
                    return OPUS_INVALID_PACKET;
                }
                var ch = data[off++] & 0xFF;
                count = ch & 0x3F;
                if (count <= 0 || framesize * count > 5760) {
                    return OPUS_INVALID_PACKET;
                }
                len--;
                if ((ch & 0x40) != 0) {
                    int p;
                    do {
                        if (len <= 0) {
                            return OPUS_INVALID_PACKET;
                        }
                        p = data[off++] & 0xFF;
                        len--;
                        var tmp = p == 255 ? 254 : p;
                        len -= tmp;
                        pad += tmp;
                    } while (p == 255);
                }
                if (len < 0) {
                    return OPUS_INVALID_PACKET;
                }
                cbr = (ch & 0x80) == 0;
                if (!cbr) {
                    lastSize = len;
                    for (var i = 0; i < count - 1; i++) {
                        bytes = parseSize(data, off, len, size, i);
                        len -= bytes;
                        if (size[i] < 0 || size[i] > len) {
                            return OPUS_INVALID_PACKET;
                        }
                        off += bytes;
                        lastSize -= bytes + size[i];
                    }
                    if (lastSize < 0) {
                        return OPUS_INVALID_PACKET;
                    }
                } else if (!selfDelimited) {
                    lastSize = len / count;
                    if (lastSize * count != len) {
                        return OPUS_INVALID_PACKET;
                    }
                    for (var i = 0; i < count - 1; i++) {
                        size[i] = lastSize;
                    }
                }
            }
        }
        if (selfDelimited) {
            bytes = parseSize(data, off, len, size, count - 1);
            len -= bytes;
            if (size[count - 1] < 0 || size[count - 1] > len) {
                return OPUS_INVALID_PACKET;
            }
            off += bytes;
            if (cbr) {
                if (size[count - 1] * count > len) {
                    return OPUS_INVALID_PACKET;
                }
                for (var i = 0; i < count - 1; i++) {
                    size[i] = size[count - 1];
                }
            } else if (bytes + size[count - 1] > lastSize) {
                return OPUS_INVALID_PACKET;
            }
        } else {
            if (lastSize > MAX_FRAME_BYTES) {
                return OPUS_INVALID_PACKET;
            }
            size[count - 1] = lastSize;
        }
        layout.payloadOffset = off - data0;
        for (var i = 0; i < count; i++) {
            layout.frames[i] = off - data0;
            off += size[i];
        }
        layout.paddingOffset = off - data0;
        layout.paddingLen = pad;
        layout.packetOffset = pad + (off - data0);
        layout.toc = toc;
        return count;
    }

    /**
     * Applies the soft-clipping non-linearity that keeps decoded float audio inside
     * {@code [-1, 1]} without hard clipping ({@code opus_pcm_soft_clip}).
     *
     * @param x          the interleaved samples, modified in place
     * @param off        the index of the first sample
     * @param N          the number of samples per channel
     * @param C          the number of channels
     * @param declipMem  the per-channel continuation state, at least {@code C} entries
     */
    public static void softClip(float[] x, int off, int N, int C, float[] declipMem) {
        if (C < 1 || N < 1) {
            return;
        }
        for (var i = 0; i < N * C; i++) {
            x[off + i] = max(-2.f, min(2.f, x[off + i]));
        }
        for (var c = 0; c < C; c++) {
            var xb = off + c;
            var a = declipMem[c];
            for (var i = 0; i < N; i++) {
                if (x[xb + i * C] * a >= 0) {
                    break;
                }
                x[xb + i * C] = x[xb + i * C] + a * x[xb + i * C] * x[xb + i * C];
            }
            var curr = 0;
            var x0 = x[xb];
            while (true) {
                int i;
                for (i = curr; i < N; i++) {
                    if (x[xb + i * C] > 1 || x[xb + i * C] < -1) {
                        break;
                    }
                }
                if (i == N) {
                    a = 0;
                    break;
                }
                var peakPos = i;
                var start = i;
                var end = i;
                var maxval = Math.abs(x[xb + i * C]);
                while (start > 0 && x[xb + i * C] * x[xb + (start - 1) * C] >= 0) {
                    start--;
                }
                while (end < N && x[xb + i * C] * x[xb + end * C] >= 0) {
                    if (Math.abs(x[xb + end * C]) > maxval) {
                        maxval = Math.abs(x[xb + end * C]);
                        peakPos = end;
                    }
                    end++;
                }
                var special = start == 0 && x[xb + i * C] * x[xb] >= 0;
                a = (maxval - 1) / (maxval * maxval);
                a += a * 2.4e-7f;
                if (x[xb + i * C] > 0) {
                    a = -a;
                }
                for (i = start; i < end; i++) {
                    x[xb + i * C] = x[xb + i * C] + a * x[xb + i * C] * x[xb + i * C];
                }
                if (special && peakPos >= 2) {
                    var offset = x0 - x[xb];
                    var delta = offset / peakPos;
                    for (i = curr; i < peakPos; i++) {
                        offset -= delta;
                        x[xb + i * C] += offset;
                        x[xb + i * C] = max(-1.f, min(1.f, x[xb + i * C]));
                    }
                }
                curr = end;
                if (curr == N) {
                    break;
                }
            }
            declipMem[c] = a;
        }
    }

    /**
     * Returns the larger of two floats with C macro semantics ({@code MAX16}).
     *
     * @param a the first value
     * @param b the second value
     * @return {@code a > b ? a : b}
     */
    private static float max(float a, float b) {
        return a > b ? a : b;
    }

    /**
     * Returns the smaller of two floats with C macro semantics ({@code MIN16}).
     *
     * @param a the first value
     * @param b the second value
     * @return {@code a < b ? a : b}
     */
    private static float min(float a, float b) {
        return a < b ? a : b;
    }
}
