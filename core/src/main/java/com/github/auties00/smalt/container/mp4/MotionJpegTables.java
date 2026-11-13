package com.github.auties00.smalt.container.mp4;

import java.util.Arrays;

/**
 * Converts between the {@code mjqt} and {@code mjht} boxes of a QuickTime Motion-JPEG sample description and the
 * codec configuration of its track.
 *
 * <p>The QuickTime File Format names these boxes but gives no layout. Measured on QuickTime 7's decoders (see
 * {@code tools/testdata/mjpeg-qt}), each box holds one marker segment from its length field on, without the marker
 * code: the same form a format B header points to. Bytes after the segment are ignored, and a box whose length field
 * is below 2 or runs past the box holds no tables. The track's configuration is the DQT segment followed by the DHT
 * segment, each with its marker code, as {@link com.github.auties00.smalt.track.Track} describes.
 */
final class MotionJpegTables {
    /**
     * The second byte of a DQT marker code.
     */
    private static final int DQT = 0xDB;

    /**
     * The second byte of a DHT marker code.
     */
    private static final int DHT = 0xC4;

    /**
     * Prevents instantiation.
     */
    private MotionJpegTables() {
        throw new AssertionError();
    }

    /**
     * Builds a track's configuration from its boxes.
     *
     * @param mjqt the body of the {@code mjqt} box, or {@code null} when absent
     * @param mjht the body of the {@code mjht} box, or {@code null} when absent
     * @return the configuration, or {@code null} when neither box holds a segment
     */
    static byte[] toConfig(byte[] mjqt, byte[] mjht) {
        var quantization = segmentLength(mjqt);
        var huffman = segmentLength(mjht);
        var length = (quantization == 0 ? 0 : 2 + quantization) + (huffman == 0 ? 0 : 2 + huffman);
        if (length == 0) {
            return null;
        }
        var config = new byte[length];
        var at = append(config, 0, DQT, mjqt, quantization);
        append(config, at, DHT, mjht, huffman);
        return config;
    }

    /**
     * Writes the boxes of a track's configuration into the open sample entry.
     *
     * @param writer the destination
     * @param config the configuration, or {@code null}; anything but the form {@link #toConfig} builds writes nothing
     */
    static void write(BoxWriter writer, byte[] config) {
        if (config == null || !wellFormed(config)) {
            return;
        }
        var at = 0;
        while (at < config.length) {
            var length = (config[at + 2] & 0xFF) << 8 | config[at + 3] & 0xFF;
            writer.begin((config[at + 1] & 0xFF) == DQT ? BoxTypes.MJQT : BoxTypes.MJHT);
            writer.bytes(Arrays.copyOfRange(config, at + 2, at + 2 + length));
            writer.end();
            at += 2 + length;
        }
    }

    /**
     * Returns whether a configuration holds nothing but DQT and DHT marker segments.
     *
     * @param config the configuration
     * @return {@code true} when every byte belongs to such a segment
     */
    private static boolean wellFormed(byte[] config) {
        var at = 0;
        while (at + 4 <= config.length && (config[at] & 0xFF) == 0xFF
                && ((config[at + 1] & 0xFF) == DQT || (config[at + 1] & 0xFF) == DHT)) {
            var length = (config[at + 2] & 0xFF) << 8 | config[at + 3] & 0xFF;
            if (length < 2 || at + 2 + length > config.length) {
                return false;
            }
            at += 2 + length;
        }
        return at == config.length && at > 0;
    }

    /**
     * Returns the length of the segment a box holds, length field included.
     *
     * @param box the box body, or {@code null}
     * @return the length, or 0 when the box holds no segment
     */
    private static int segmentLength(byte[] box) {
        if (box == null || box.length < 2) {
            return 0;
        }
        var length = (box[0] & 0xFF) << 8 | box[1] & 0xFF;
        return length < 2 || length > box.length ? 0 : length;
    }

    /**
     * Appends a box's segment to a configuration.
     *
     * @param config the configuration
     * @param at the index to write at
     * @param marker the second byte of the marker code
     * @param box the box body
     * @param length the segment length, 0 to write nothing
     * @return the index after the segment
     */
    private static int append(byte[] config, int at, int marker, byte[] box, int length) {
        if (length == 0) {
            return at;
        }
        config[at] = (byte) 0xFF;
        config[at + 1] = (byte) marker;
        System.arraycopy(box, 0, config, at + 2, length);
        return at + 2 + length;
    }
}
