/* Ports libopus 1.5.2 (src/extensions.c). Copyright Amazon; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.OPUS_BAD_ARG;
import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.OPUS_BUFFER_TOO_SMALL;
import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.OPUS_INVALID_PACKET;

/**
 * Reads and writes the extensions that an Opus packet carries in its padding (the Opus extension mechanism of
 * libopus 1.5): a sequence of entries, each a header byte {@code (id << 1) | L} followed by its payload, where
 * id 0 is padding, id 1 a frame separator, ids 2 to 31 carry at most one byte and ids 32 to 127 carry a payload
 * whose length is coded with 255-continued bytes unless the entry is the last.
 *
 * <p>Extensions are described by parallel arrays: {@code ids}, {@code frames} (the 0-based frame the extension
 * belongs to), {@code offsets} (the index of the payload in its buffer) and {@code lengths}.
 */
public final class OpusExtensions {
    private OpusExtensions() {
        throw new AssertionError();
    }

    /**
     * Skips one extension ({@code skip_extension}).
     *
     * @param data the buffer
     * @param pos the index of the extension header
     * @param len the number of bytes left from {@code pos}, positive
     * @return the number of bytes left after the extension shifted left by 32, ored with the header size, or
     * {@code -1} if the extension is truncated; the header of an extension whose length is coded with many
     * 255-continued bytes may exceed 255 bytes, so neither part is masked to a byte
     */
    public static long skip(byte[] data, int pos, int len) {
        var id = (data[pos] & 0xFF) >> 1;
        var L = data[pos] & 1;
        if (id == 0 && L == 1) {
            return (long) (len - 1) << 32 | 1;
        } else if (id > 0 && id < 32) {
            if (len < 1 + L) {
                return -1;
            }
            return (long) (len - (1 + L)) << 32 | 1;
        } else if (L == 0) {
            return 1;
        }
        var bytes = 0;
        var header = 1;
        do {
            pos++;
            len--;
            if (len == 0) {
                return -1;
            }
            bytes += data[pos] & 0xFF;
            header++;
        } while ((data[pos] & 0xFF) == 255);
        len--;
        if (bytes > len) {
            return -1;
        }
        return (long) (len - bytes) << 32 | header;
    }

    /**
     * Counts the extensions of a padding area, excluding padding and separators ({@code opus_packet_extensions_count}).
     *
     * @param data the buffer
     * @param offset the index of the padding area
     * @param len its length
     * @return the count, or {@code OPUS_INVALID_PACKET} if an extension is truncated
     */
    public static int count(byte[] data, int offset, int len) {
        var count = 0;
        var pos = offset;
        while (len > 0) {
            var id = (data[pos] & 0xFF) >> 1;
            var skipped = skip(data, pos, len);
            if (skipped < 0) {
                return OPUS_INVALID_PACKET;
            }
            var left = (int) (skipped >>> 32);
            pos += len - left;
            len = left;
            if (id > 1) {
                count++;
            }
        }
        return count;
    }

    /**
     * Lists the extensions of a padding area, excluding padding and separators
     * ({@code opus_packet_extensions_parse}).
     *
     * @param data the buffer
     * @param offset the index of the padding area
     * @param len its length
     * @param ids receives the extension ids
     * @param frames receives the frame of each extension
     * @param offsets receives the payload index of each extension
     * @param lengths receives the payload length of each extension
     * @param first the index of the first array entry to fill
     * @param capacity the number of entries available from {@code first}
     * @return the number of extensions listed, {@code OPUS_INVALID_PACKET} if the area is malformed or
     * {@code OPUS_BUFFER_TOO_SMALL} if the arrays are too short
     */
    public static int parse(byte[] data, int offset, int len, int[] ids, int[] frames, int[] offsets, int[] lengths,
                            int first, int capacity) {
        var pos = offset;
        var frame = 0;
        var count = 0;
        while (len > 0) {
            var id = (data[pos] & 0xFF) >> 1;
            if (id == 1) {
                var L = data[pos] & 1;
                if (L == 0) {
                    frame++;
                } else if (len >= 2) {
                    frame += data[pos + 1] & 0xFF;
                }
                if (frame >= 48) {
                    return OPUS_INVALID_PACKET;
                }
            }
            var start = pos;
            var skipped = skip(data, pos, len);
            if (skipped < 0) {
                return OPUS_INVALID_PACKET;
            }
            var header = (int) skipped;
            var left = (int) (skipped >>> 32);
            pos += len - left;
            len = left;
            if (id > 1) {
                if (count == capacity) {
                    return OPUS_BUFFER_TOO_SMALL;
                }
                ids[first + count] = id;
                frames[first + count] = frame;
                offsets[first + count] = start + header;
                lengths[first + count] = pos - start - header;
                count++;
            }
        }
        return count;
    }

    /**
     * Writes extensions in frame order, inserting separators, and optionally fills the rest of {@code len} with
     * leading padding bytes ({@code opus_packet_extensions_generate}).
     *
     * @param out the buffer to write, or {@code null} to only measure
     * @param outOffset the index of the first byte to write
     * @param len the space available
     * @param buffers the buffer holding the payload of each extension
     * @param ids the extension ids, from 2 to 127
     * @param frames the frame of each extension, below 48
     * @param offsets the payload index of each extension in {@code buffer}
     * @param lengths the payload length of each extension; 0 or 1 for ids below 32
     * @param count the number of extensions
     * @param pad whether to fill the whole of {@code len}
     * @return the number of bytes written, or {@code OPUS_BAD_ARG} or {@code OPUS_BUFFER_TOO_SMALL}
     */
    public static int generate(byte[] out, int outOffset, int len, byte[][] buffers, int[] ids, int[] frames,
                               int[] offsets, int[] lengths, int count, boolean pad) {
        var maxFrame = 0;
        for (var i = 0; i < count; i++) {
            maxFrame = Math.max(maxFrame, frames[i]);
            if (ids[i] < 2 || ids[i] > 127) {
                return OPUS_BAD_ARG;
            }
        }
        if (maxFrame >= 48) {
            return OPUS_BAD_ARG;
        }
        var pos = 0;
        var written = 0;
        var currFrame = 0;
        for (var frame = 0; frame <= maxFrame; frame++) {
            for (var i = 0; i < count; i++) {
                if (frames[i] != frame) {
                    continue;
                }
                if (frame != currFrame) {
                    var diff = frame - currFrame;
                    if (len - pos < 2) {
                        return OPUS_BUFFER_TOO_SMALL;
                    }
                    if (diff == 1) {
                        if (out != null) {
                            out[outOffset + pos] = 0x02;
                        }
                        pos++;
                    } else {
                        if (out != null) {
                            out[outOffset + pos] = 0x03;
                            out[outOffset + pos + 1] = (byte) diff;
                        }
                        pos += 2;
                    }
                    currFrame = frame;
                }
                if (ids[i] < 32) {
                    if (lengths[i] < 0 || lengths[i] > 1) {
                        return OPUS_BAD_ARG;
                    }
                    if (len - pos < lengths[i] + 1) {
                        return OPUS_BUFFER_TOO_SMALL;
                    }
                    if (out != null) {
                        out[outOffset + pos] = (byte) ((ids[i] << 1) + lengths[i]);
                    }
                    pos++;
                    if (lengths[i] > 0) {
                        if (out != null) {
                            out[outOffset + pos] = buffers[i][offsets[i]];
                        }
                        pos++;
                    }
                } else {
                    if (lengths[i] < 0) {
                        return OPUS_BAD_ARG;
                    }
                    var last = written == count - 1;
                    var lengthBytes = last ? 0 : 1 + lengths[i] / 255;
                    if (len - pos < 1 + lengthBytes + lengths[i]) {
                        return OPUS_BUFFER_TOO_SMALL;
                    }
                    if (out != null) {
                        out[outOffset + pos] = (byte) ((ids[i] << 1) + (last ? 0 : 1));
                    }
                    pos++;
                    if (!last) {
                        for (var j = 0; j < lengths[i] / 255; j++) {
                            if (out != null) {
                                out[outOffset + pos] = (byte) 255;
                            }
                            pos++;
                        }
                        if (out != null) {
                            out[outOffset + pos] = (byte) (lengths[i] % 255);
                        }
                        pos++;
                    }
                    if (out != null) {
                        System.arraycopy(buffers[i], offsets[i], out, outOffset + pos, lengths[i]);
                    }
                    pos += lengths[i];
                }
                written++;
            }
        }
        if (pad && pos < len) {
            var padding = len - pos;
            if (out != null) {
                System.arraycopy(out, outOffset, out, outOffset + padding, pos);
                for (var i = 0; i < padding; i++) {
                    out[outOffset + i] = 0x01;
                }
            }
            pos += padding;
        }
        return pos;
    }
}
