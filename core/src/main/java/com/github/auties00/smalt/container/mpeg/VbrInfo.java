package com.github.auties00.smalt.container.mpeg;

import com.github.auties00.smalt.internal.header.MpegAudioHeader;

/**
 * Describes the Xing, Info or VBRI tag frame that encoders place before the audio frames.
 *
 * <p>The Xing and Info tags sit right after the side information of a Layer III frame: a magic
 * word, a 32-bit flags word, then optionally the frame count (flag 1), the byte count (flag 2), a
 * 100-byte seek table (flag 4) and a quality indicator (flag 8). A LAME tag may follow: a 9-byte
 * encoder string, then at offset 21 two 12-bit fields holding the encoder delay and the padding
 * in samples. The VBRI tag (Fraunhofer) sits 32 bytes after the header and holds a 32-bit frame
 * count at offset 14.
 *
 * @param header the header of the tag frame
 * @param frames the number of audio frames after the tag frame, or {@code -1} when not recorded
 * @param delay the encoder delay in samples, or {@code -1} without a LAME tag
 * @param padding the encoder padding in samples, or {@code -1} without a LAME tag
 * @param bytes the number of bytes of the stream from the tag frame on, or {@code -1} when not recorded
 * @param toc the 100-entry Xing seek table, entry {@code i} being the byte position of {@code i} percent of the
 * duration in 256ths of {@code bytes}, or {@code null}
 */
record VbrInfo(int header, long frames, int delay, int padding, long bytes, byte[] toc) {
    /**
     * Returns whether a LAME tag gave the encoder delay and padding.
     *
     * @return {@code true} when gapless trimming information is present
     */
    boolean hasGapless() {
        return delay >= 0 && padding >= 0;
    }

    /**
     * Parses the tag frame of a stream, if its first frame is one.
     *
     * @param header the frame header
     * @param frame the whole frame, header included
     * @return the tag, or {@code null} when the frame is an ordinary audio frame
     */
    static VbrInfo parse(int header, byte[] frame) {
        if (MpegAudioHeader.layer(header) != 3) {
            return null;
        }
        var offset = MpegAudioHeader.SIZE + (MpegAudioHeader.hasCrc(header) ? 2 : 0) + MpegAudioHeader.sideInfoBytes(header);
        if (frame.length >= offset + 8 && (matches(frame, offset, "Xing") || matches(frame, offset, "Info"))) {
            var flags = frame[offset + 7] & 0xFF;
            var position = offset + 8;
            long frames = -1;
            if ((flags & 1) != 0) {
                if (frame.length < position + 4) {
                    return new VbrInfo(header, -1, -1, -1, -1, null);
                }
                frames = be32(frame, position);
                position += 4;
            }
            long bytes = -1;
            if ((flags & 2) != 0) {
                if (frame.length >= position + 4) {
                    bytes = be32(frame, position);
                }
                position += 4;
            }
            byte[] toc = null;
            if ((flags & 4) != 0) {
                if (frame.length >= position + 100) {
                    toc = java.util.Arrays.copyOfRange(frame, position, position + 100);
                }
                position += 100;
            }
            if ((flags & 8) != 0) {
                position += 4;
            }
            var delay = -1;
            var padding = -1;
            if (frame.length >= position + 24 && (matches(frame, position, "LAME") || matches(frame, position, "Lavf")
                    || matches(frame, position, "Lavc"))) {
                var p = position + 21;
                delay = (frame[p] & 0xFF) << 4 | (frame[p + 1] & 0xFF) >> 4;
                padding = (frame[p + 1] & 0x0F) << 8 | (frame[p + 2] & 0xFF);
            }
            return new VbrInfo(header, frames, delay, padding, bytes, toc);
        }
        var vbri = MpegAudioHeader.SIZE + 32;
        if (frame.length >= vbri + 18 && matches(frame, vbri, "VBRI")) {
            return new VbrInfo(header, be32(frame, vbri + 14), -1, -1, be32(frame, vbri + 10), null);
        }
        return null;
    }

    /**
     * Returns whether the bytes at an offset spell an ASCII word.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param word the expected ASCII characters
     * @return {@code true} on a match
     */
    private static boolean matches(byte[] data, int offset, String word) {
        for (var i = 0; i < word.length(); i++) {
            if (data[offset + i] != word.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads an unsigned big-endian 32-bit value.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return the value
     */
    private static long be32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) << 24 | (data[offset + 1] & 0xFFL) << 16 | (data[offset + 2] & 0xFFL) << 8
                | (data[offset + 3] & 0xFFL);
    }
}
