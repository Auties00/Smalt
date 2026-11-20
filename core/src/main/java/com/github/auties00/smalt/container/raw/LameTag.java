package com.github.auties00.smalt.container.raw;

import java.nio.charset.StandardCharsets;

/**
 * Writes the LAME tag, the 36-byte extension of a Xing or Info tag frame that records the encoder
 * delay and padding of an MP3 stream for gapless playback.
 *
 * <p>The layout, from the first byte after the Xing quality indicator:
 * {@snippet :
 * offset  size  field
 *      0     9  encoder string ("LAMESmalt"; readers look for the "LAME" prefix)
 *      9     1  tag revision (high 4 bits, 1) and bitrate method (low 4 bits: 1 constant, 0 unknown)
 *     10     1  lowpass frequency in hundreds of hertz (0, unknown)
 *     11     4  peak signal amplitude (0, unknown)
 *     15     4  radio and audiophile replay gain (0, none)
 *     19     1  encoding flags and ATH type (0)
 *     20     1  constant bitrate, or the smallest bitrate, in kilobits per second (255 and above saturate)
 *     21     3  encoder delay (12 bits) and padding (12 bits), in samples
 *     24     1  noise shaping, stereo mode, unwise settings and source rate (0, unknown)
 *     25     1  MP3 gain (0)
 *     26     2  surround and preset (0)
 *     28     4  stream length in bytes, from the first byte of the tag frame to the last audio byte
 *     32     2  CRC-16 of the audio frames after the tag frame
 *     34     2  CRC-16 of the first 190 bytes of the tag frame
 * }
 * Both checksums are the CRC-16 of polynomial {@code 0x8005} computed least significant bit first
 * from zero (the reflected form, polynomial {@code 0xA001}). The tag checksum counts its own field,
 * when it falls inside the first 190 bytes (mono and MPEG-2 frames), as zero, and bytes past the end of
 * a tag frame shorter than 190 bytes as zeros.
 */
final class LameTag {
    /**
     * The size of the tag in bytes.
     */
    static final int SIZE = 36;

    /**
     * The decoder delay of the MPEG synthesis filterbank the delay and padding fields assume, in
     * samples.
     */
    static final int DECODER_DELAY = 529;

    /**
     * The largest value of the delay and padding fields.
     */
    static final int MAX_FIELD = 4095;

    /**
     * The number of leading bytes of the tag frame the tag checksum covers.
     */
    private static final int TAG_CRC_BYTES = 190;

    /**
     * The encoder string.
     */
    private static final byte[] ENCODER = "LAMESmalt".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The reflected CRC-16 of every byte value.
     */
    private static final char[] CRC_TABLE = new char[256];

    static {
        for (var value = 0; value < 256; value++) {
            var crc = value;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? crc >>> 1 ^ 0xA001 : crc >>> 1;
            }
            CRC_TABLE[value] = (char) crc;
        }
    }

    private LameTag() {
    }

    /**
     * Continues the reflected CRC-16 of the LAME tag over a byte range.
     *
     * @param crc the checksum of the preceding bytes, or 0 for the first range
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the checksum, from 0 to 65535
     */
    static int crc(int crc, byte[] data, int offset, int length) {
        for (var i = offset; i < offset + length; i++) {
            crc = crc >>> 8 ^ CRC_TABLE[(crc ^ data[i]) & 0xFF];
        }
        return crc;
    }

    /**
     * Writes the tag into a Xing tag frame and its checksum over the frame.
     *
     * @param frame the tag frame, header first, at least {@code offset + 36} bytes long
     * @param offset the index of the tag's first byte
     * @param constant whether every audio frame has the same bitrate
     * @param kbps the constant or smallest bitrate in kilobits per second
     * @param delay the encoder delay, from 0 to 4095
     * @param padding the padding, from 0 to 4095
     * @param bytes the stream length from the tag frame on
     * @param musicCrc the checksum of the audio frames after the tag frame
     */
    static void write(byte[] frame, int offset, boolean constant, int kbps, int delay, int padding, long bytes,
                      int musicCrc) {
        System.arraycopy(ENCODER, 0, frame, offset, ENCODER.length);
        frame[offset + 9] = (byte) (0x10 | (constant ? 1 : 0));
        frame[offset + 20] = (byte) Math.min(255, kbps);
        frame[offset + 21] = (byte) (delay >> 4);
        frame[offset + 22] = (byte) ((delay & 0x0F) << 4 | padding >> 8);
        frame[offset + 23] = (byte) padding;
        var length = Math.min(bytes, 0xFFFFFFFFL);
        frame[offset + 28] = (byte) (length >>> 24);
        frame[offset + 29] = (byte) (length >>> 16);
        frame[offset + 30] = (byte) (length >>> 8);
        frame[offset + 31] = (byte) length;
        frame[offset + 32] = (byte) (musicCrc >> 8);
        frame[offset + 33] = (byte) musicCrc;
        var covered = Math.min(TAG_CRC_BYTES, frame.length);
        var tagCrc = crc(0, frame, 0, covered);
        tagCrc = crc(tagCrc, new byte[TAG_CRC_BYTES - covered], 0, TAG_CRC_BYTES - covered);
        frame[offset + 34] = (byte) (tagCrc >> 8);
        frame[offset + 35] = (byte) tagCrc;
    }
}
