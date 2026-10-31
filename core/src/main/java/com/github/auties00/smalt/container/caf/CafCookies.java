package com.github.auties00.smalt.container.caf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.util.Arrays;

/**
 * Reads and writes the magic cookies of the {@code kuki} chunk: the elementary stream descriptor of AAC
 * (ISO/IEC 14496-1 clause 7.2.6.5), the {@code ALACSpecificConfig} of ALAC and the {@code OpusHead} of Opus.
 */
final class CafCookies {
    /**
     * The tag of an {@code ES_Descriptor}.
     */
    private static final int ES_DESCRIPTOR = 0x03;

    /**
     * The tag of a {@code DecoderConfigDescriptor}.
     */
    private static final int DECODER_CONFIG = 0x04;

    /**
     * The tag of a {@code DecoderSpecificInfo}.
     */
    private static final int DECODER_SPECIFIC = 0x05;

    /**
     * The tag of an {@code SLConfigDescriptor}.
     */
    private static final int SL_CONFIG = 0x06;

    /**
     * The {@code objectTypeIndication} of MPEG-4 audio.
     */
    private static final int MPEG4_AUDIO = 0x40;

    /**
     * The largest descriptor nesting followed.
     */
    private static final int MAX_DEPTH = 4;

    /**
     * The offset of the 24-bit {@code bufferSizeDB} in a cookie of {@link #aacCookie(byte[], int, long, long)}.
     */
    static final int AAC_BUFFER_SIZE_OFFSET = 15;

    /**
     * The offset of the 32-bit {@code maxBitrate} in a cookie of {@link #aacCookie(byte[], int, long, long)}.
     */
    static final int AAC_MAX_BITRATE_OFFSET = 18;

    /**
     * The offset of the 32-bit {@code avgBitrate} in a cookie of {@link #aacCookie(byte[], int, long, long)}.
     */
    static final int AAC_AVERAGE_BITRATE_OFFSET = 22;

    /**
     * Prevents instantiation.
     */
    private CafCookies() {
        throw new AssertionError();
    }

    /**
     * Returns the {@code AudioSpecificConfig} of an AAC magic cookie.
     *
     * <p>The cookie is an {@code ES_Descriptor}, with or without the four bytes of version and flags of an
     * {@code esds} box before it, or a bare {@code DecoderConfigDescriptor}; the configuration is the payload of its
     * {@code DecoderSpecificInfo}.
     *
     * @param cookie the body of the {@code kuki} chunk
     * @return the configuration, or {@code null} when the cookie holds no descriptor with one
     */
    static byte[] aacConfig(byte[] cookie) {
        for (var start : new int[]{0, 4}) {
            if (cookie.length > start + 2) {
                var tag = cookie[start] & 0xFF;
                if (tag == ES_DESCRIPTOR || tag == DECODER_CONFIG) {
                    var config = find(cookie, start, cookie.length, 0);
                    if (config != null) {
                        return config;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds the {@code DecoderSpecificInfo} among the descriptors of a range.
     *
     * @param data the bytes
     * @param offset the index of the first descriptor
     * @param end the end of the range
     * @param depth the nesting depth
     * @return the payload of the first {@code DecoderSpecificInfo} found, or {@code null}
     */
    private static byte[] find(byte[] data, int offset, int end, int depth) {
        while (end - offset >= 2 && depth <= MAX_DEPTH) {
            var tag = data[offset++] & 0xFF;
            var length = 0;
            var bytes = 0;
            int value;
            do {
                if (offset >= end || bytes == 4) {
                    return null;
                }
                value = data[offset++] & 0xFF;
                length = length << 7 | value & 0x7F;
                bytes++;
            } while ((value & 0x80) != 0);
            if (length > end - offset) {
                return null;
            }
            var bodyEnd = offset + length;
            switch (tag) {
                case ES_DESCRIPTOR -> {
                    if (length < 3) {
                        return null;
                    }
                    var flags = data[offset + 2] & 0xFF;
                    var inner = offset + 3;
                    if ((flags & 0x80) != 0) {
                        inner += 2;
                    }
                    if ((flags & 0x40) != 0) {
                        if (inner >= bodyEnd) {
                            return null;
                        }
                        inner += 1 + (data[inner] & 0xFF);
                    }
                    if ((flags & 0x20) != 0) {
                        inner += 2;
                    }
                    if (inner > bodyEnd) {
                        return null;
                    }
                    return find(data, inner, bodyEnd, depth + 1);
                }
                case DECODER_CONFIG -> {
                    if (length < 13) {
                        return null;
                    }
                    return find(data, offset + 13, bodyEnd, depth + 1);
                }
                case DECODER_SPECIFIC -> {
                    return length == 0 ? null : Arrays.copyOfRange(data, offset, bodyEnd);
                }
                default -> offset = bodyEnd;
            }
        }
        return null;
    }

    /**
     * Writes the magic cookie of an AAC stream: an {@code ES_Descriptor} of MPEG-4 audio whose
     * {@code DecoderSpecificInfo} is the {@code AudioSpecificConfig}, with no {@code esds} version and flags before
     * it, as Core Audio writes it.
     *
     * <p>The buffer size and the bit rates are at {@link #AAC_BUFFER_SIZE_OFFSET}, {@link #AAC_MAX_BITRATE_OFFSET}
     * and {@link #AAC_AVERAGE_BITRATE_OFFSET} of the cookie, so that a writer can fill them in once the packets are
     * known.
     *
     * @param config the {@code AudioSpecificConfig}
     * @param bufferSize the size of the decoding buffer, in bytes, below {@code 2^24}
     * @param maxBitrate the peak bit rate, in bits per second
     * @param averageBitrate the average bit rate, in bits per second
     * @return the cookie
     */
    static byte[] aacCookie(byte[] config, int bufferSize, long maxBitrate, long averageBitrate) {
        var specific = 5 + config.length;
        var decoder = 5 + 13 + specific;
        var es = 5 + 3 + decoder + 5 + 1;
        var writer = new ByteWriter(es);
        descriptor(writer, ES_DESCRIPTOR, es - 5);
        writer.u16be(0);
        writer.u8(0);
        descriptor(writer, DECODER_CONFIG, decoder - 5);
        writer.u8(MPEG4_AUDIO);
        writer.u8(0x15);
        writer.u24be(bufferSize);
        writer.u32be(maxBitrate);
        writer.u32be(averageBitrate);
        descriptor(writer, DECODER_SPECIFIC, config.length);
        writer.bytes(config);
        descriptor(writer, SL_CONFIG, 1);
        writer.u8(2);
        return writer.toByteArray();
    }

    /**
     * Writes a descriptor tag and a four-byte expandable size.
     *
     * @param writer the cookie being built
     * @param tag the descriptor tag
     * @param length the size of the descriptor body, below {@code 2^28}
     */
    private static void descriptor(ByteWriter writer, int tag, int length) {
        writer.u8(tag);
        writer.u8(0x80 | length >>> 21 & 0x7F);
        writer.u8(0x80 | length >>> 14 & 0x7F);
        writer.u8(0x80 | length >>> 7 & 0x7F);
        writer.u8(length & 0x7F);
    }

    /**
     * Returns the {@code ALACSpecificConfig} of an ALAC magic cookie: the 24-byte configuration, alone or followed by
     * the channel layout information, or after the headers of a {@code frma} atom and an {@code alac} atom.
     *
     * @param cookie the body of the {@code kuki} chunk
     * @return the 24-byte configuration
     * @throws MediaException.Malformed if no configuration can be located
     */
    static byte[] alacConfig(byte[] cookie) {
        if (cookie.length >= 12 && cookie[4] == 'f' && cookie[5] == 'r' && cookie[6] == 'm' && cookie[7] == 'a') {
            return AudioConfigs.alacConfig(Arrays.copyOfRange(cookie, 12, cookie.length));
        }
        return AudioConfigs.alacConfig(cookie);
    }

    /**
     * Returns whether a magic cookie is an Opus identification header.
     *
     * @param cookie the body of the {@code kuki} chunk
     * @return {@code true} when it starts with {@code OpusHead} and holds the 19 bytes of a header
     */
    static boolean isOpusHead(byte[] cookie) {
        return cookie.length >= 19 && cookie[0] == 'O' && cookie[1] == 'p' && cookie[2] == 'u' && cookie[3] == 's'
                && cookie[4] == 'H' && cookie[5] == 'e' && cookie[6] == 'a' && cookie[7] == 'd';
    }
}
