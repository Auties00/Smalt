package com.github.auties00.smalt.testkit.fuzz;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The input format of the packet decoder targets: a stream header followed by packets, each part separated
 * by {@link #MARKER}.
 *
 * <p>A marker between the parts keeps the packets apart, so that a mutation of one packet leaves the others intact:
 * {@snippet :
 * input  = header (MARKER packet)*
 * header = options:u8 fields codec-config      // video fields: width:u16 height:u16
 *                                              // audio fields: sample-rate:u24 channels:u8 bits:u8
 * packet = flags:u8 payload                    // flags bit 0: keyframe
 * }
 * Multi-byte fields are big-endian. Missing header bytes read as zero, an empty codec configuration means
 * none, and parts past {@link Fuzz#MAX_PACKETS} are ignored. The options byte selects decoder options; each
 * target documents its bits.
 *
 * @param header the stream header
 * @param packets the packets, in order
 */
public record FuzzPackets(Header header, List<Part> packets) {
    /** The separator between the parts of an input. */
    public static final byte[] MARKER = "FUZZ-TAG".getBytes(StandardCharsets.US_ASCII);

    /** The header length of a video stream: options, width and height. */
    public static final int VIDEO_FIELDS = 5;

    /** The header length of an audio stream: options, sample rate, channels and bits per sample. */
    public static final int AUDIO_FIELDS = 6;

    /**
     * Splits an input into its parts.
     *
     * @param data the input
     * @param fields the length of the header fields before the codec configuration, {@link #VIDEO_FIELDS}
     * or {@link #AUDIO_FIELDS}
     * @return the parts
     */
    public static FuzzPackets parse(byte[] data, int fields) {
        var parts = new ArrayList<byte[]>();
        var start = 0;
        var position = indexOf(data, 0);
        while (position >= 0 && parts.size() <= Fuzz.MAX_PACKETS) {
            parts.add(Arrays.copyOfRange(data, start, position));
            start = position + MARKER.length;
            position = indexOf(data, start);
        }
        if (parts.size() <= Fuzz.MAX_PACKETS) {
            parts.add(Arrays.copyOfRange(data, start, data.length));
        }
        var header = new Header(parts.getFirst(), fields);
        var packets = new ArrayList<Part>(parts.size() - 1);
        for (var index = 1; index < parts.size(); index++) {
            var part = parts.get(index);
            if (part.length == 0) {
                packets.add(new Part(part, false));
            } else {
                packets.add(new Part(Arrays.copyOfRange(part, 1, part.length), (part[0] & 1) != 0));
            }
        }
        return new FuzzPackets(header, List.copyOf(packets));
    }

    /**
     * Builds a video input.
     *
     * @param options the options byte
     * @param width the width, from 0 to 65535
     * @param height the height, from 0 to 65535
     * @param config the codec configuration, or {@code null} for none
     * @param packets the packets
     * @return the input
     */
    public static byte[] video(int options, int width, int height, byte[] config, List<Part> packets) {
        var header = new ByteArrayOutputStream();
        header.write(options);
        writeBig(header, Math.clamp(width, 0, 0xFFFF), 2);
        writeBig(header, Math.clamp(height, 0, 0xFFFF), 2);
        return join(header, config, packets);
    }

    /**
     * Builds an audio input.
     *
     * @param options the options byte
     * @param sampleRate the sample rate, from 0 to 16777215
     * @param channels the channel count, from 0 to 255
     * @param bitsPerSample the bits per coded sample, from 0 to 255
     * @param config the codec configuration, or {@code null} for none
     * @param packets the packets
     * @return the input
     */
    public static byte[] audio(int options, int sampleRate, int channels, int bitsPerSample, byte[] config,
                               List<Part> packets) {
        var header = new ByteArrayOutputStream();
        header.write(options);
        writeBig(header, Math.clamp(sampleRate, 0, 0xFFFFFF), 3);
        header.write(Math.clamp(channels, 0, 0xFF));
        header.write(Math.clamp(bitsPerSample, 0, 0xFF));
        return join(header, config, packets);
    }

    private static byte[] join(ByteArrayOutputStream header, byte[] config, List<Part> packets) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.toByteArray());
        if (config != null) {
            out.writeBytes(config);
        }
        for (var packet : packets) {
            out.writeBytes(MARKER);
            out.write(packet.keyframe() ? 1 : 0);
            out.writeBytes(packet.data());
        }
        return out.toByteArray();
    }

    private static void writeBig(ByteArrayOutputStream out, int value, int bytes) {
        for (var shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
            out.write(value >>> shift);
        }
    }

    private static int indexOf(byte[] data, int from) {
        outer:
        for (var index = from; index <= data.length - MARKER.length; index++) {
            for (var offset = 0; offset < MARKER.length; offset++) {
                if (data[index + offset] != MARKER[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    /**
     * One packet of an input.
     *
     * @param data the payload
     * @param keyframe whether the packet is flagged as a keyframe
     */
    public record Part(byte[] data, boolean keyframe) {
    }

    /**
     * The stream header of an input.
     *
     * @param bytes the header bytes
     * @param fields the length of the fields before the codec configuration
     */
    public record Header(byte[] bytes, int fields) {
        /**
         * {@return the options byte}
         */
        public int options() {
            return unsigned(0, 1);
        }

        /**
         * Reads an unsigned big-endian field; bytes past the header read as zero.
         *
         * @param offset the offset of the field
         * @param length the length of the field, from 1 to 3
         * @return the value
         */
        public int unsigned(int offset, int length) {
            var value = 0;
            for (var index = offset; index < offset + length; index++) {
                value = (value << 8) | (index < bytes.length ? bytes[index] & 0xFF : 0);
            }
            return value;
        }

        /**
         * {@return the codec configuration, or empty when the header has none}
         */
        public Optional<byte[]> config() {
            if (bytes.length <= fields) {
                return Optional.empty();
            }
            return Optional.of(Arrays.copyOfRange(bytes, fields, bytes.length));
        }
    }
}
