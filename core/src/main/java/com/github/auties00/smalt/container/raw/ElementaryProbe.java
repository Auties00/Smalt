package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.codec.VvcSyntax;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

/**
 * Recognizes elementary video streams from their leading bytes: H.264, HEVC and VVC Annex B byte streams, AV1 OBU
 * streams in the low-overhead and Annex B formats, and MPEG-4 Part 2 and H.263 streams.
 *
 * <p>Each test walks the structure of the stream as far as the bytes given reach and requires a parsable sequence
 * header (a sequence parameter set, an AV1 sequence header OBU, a video object plane or a second picture start
 * code), so that other content that happens to start with a start code is not taken for a stream. This class is
 * thread-safe.
 */
final class ElementaryProbe {
    /**
     * The H.264 {@code profile_idc} values of ITU-T H.264 Annex A, G and H.
     */
    private static final int[] AVC_PROFILES = {66, 77, 88, 100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134,
            135};

    /**
     * The OBU type of a sequence header.
     */
    static final int OBU_SEQUENCE_HEADER = 1;

    /**
     * The OBU type of a temporal delimiter.
     */
    static final int OBU_TEMPORAL_DELIMITER = 2;

    /**
     * The OBU type of a frame header.
     */
    static final int OBU_FRAME_HEADER = 3;

    /**
     * The OBU type of metadata.
     */
    static final int OBU_METADATA = 5;

    /**
     * The OBU type of a frame.
     */
    static final int OBU_FRAME = 6;

    /**
     * The OBU type of padding.
     */
    static final int OBU_PADDING = 15;

    private ElementaryProbe() {
        throw new AssertionError();
    }

    /**
     * Recognizes an H.264, HEVC or VVC Annex B byte stream: start-code prefixed NAL units with valid headers, among
     * them a parsable sequence parameter set of the base layer.
     *
     * <p>A VVC NAL unit header has its {@code nuh_reserved_zero_bit} clear, a nonzero {@code nuh_temporal_id_plus1}
     * and no reserved NAL unit type; the parameter set headers of H.264 and HEVC streams set that bit, so no stream
     * passes two of the tests.
     *
     * @param header the leading bytes of the input
     * @return {@link Codec#H264}, {@link Codec#HEVC}, {@link Codec#VVC}, or {@code null}
     */
    static Codec annexB(byte[] header) {
        if (!NalUnits.looksLikeAnnexB(header)) {
            return null;
        }
        var avc = new int[]{1, 0};
        var hevc = new int[]{1, 0};
        var vvc = new int[]{1, 0};
        NalUnits.forEachAnnexB(header, (data, offset, length) -> {
            var first = data[offset] & 0xFF;
            if ((first & 0x80) != 0) {
                avc[0] = 0;
                hevc[0] = 0;
                vvc[0] = 0;
                return;
            }
            if ((first & 0x40) != 0 || length < 2 || (data[offset + 1] & 7) == 0) {
                vvc[0] = 0;
            } else {
                var vvcType = VvcSyntax.type(data, offset);
                if ((vvcType >= 4 && vvcType <= 6) || vvcType == 11 || vvcType == 26 || vvcType == 27) {
                    vvc[0] = 0;
                } else if (vvcType == VvcSyntax.SPS && VvcSyntax.layer(data, offset) == 0
                        && VvcSyntax.sps(data, offset, length) != null) {
                    vvc[1] = 1;
                }
            }
            var avcType = first & 0x1F;
            if (avcType == 0 || (avcType >= 22 && avcType <= 31)) {
                avc[0] = 0;
            } else if (avcType == 7 && length > 1 && isAvcProfile(data[offset + 1] & 0xFF)
                    && AvcSyntax.sps(data, offset, length) != null) {
                avc[1] = 1;
            }
            if (length < 2 || (data[offset + 1] & 7) == 0) {
                hevc[0] = 0;
                return;
            }
            var hevcType = first >> 1;
            var layer = ((first & 1) << 5) | ((data[offset + 1] & 0xFF) >> 3);
            if ((hevcType >= 22 && hevcType <= 31) || (hevcType >= 41 && hevcType <= 47)) {
                hevc[0] = 0;
            } else if (hevcType == 33 && layer == 0 && HevcSyntax.sps(data, offset, length) != null) {
                hevc[1] = 1;
            }
        });
        if (avc[0] == 1 && avc[1] == 1) {
            return Codec.H264;
        }
        if (hevc[0] == 1 && hevc[1] == 1) {
            return Codec.HEVC;
        }
        if (vvc[0] == 1 && vvc[1] == 1) {
            return Codec.VVC;
        }
        return null;
    }

    /**
     * Returns whether a {@code profile_idc} is an H.264 profile.
     *
     * @param profile the value
     * @return {@code true} for a profile ITU-T H.264 defines
     */
    private static boolean isAvcProfile(int profile) {
        for (var known : AVC_PROFILES) {
            if (known == profile) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recognizes an AV1 stream in the low-overhead format (AV1 specification section 5.2): OBUs with size fields,
     * starting with a temporal delimiter or a sequence header, with a parsable sequence header before any frame.
     *
     * @param header the leading bytes of the input
     * @return {@code true} when the bytes start such a stream
     */
    static boolean isLowOverheadObu(byte[] header) {
        var position = 0;
        var first = true;
        while (position < header.length) {
            var obuHeader = header[position] & 0xFF;
            var type = (obuHeader >> 3) & 0x0F;
            if ((obuHeader & 0x81) != 0 || (obuHeader & 0x02) == 0 || !isObuType(type)) {
                return false;
            }
            if (first && type != OBU_TEMPORAL_DELIMITER && type != OBU_SEQUENCE_HEADER) {
                return false;
            }
            first = false;
            var payload = position + 1 + ((obuHeader & 0x04) != 0 ? 1 : 0);
            var size = leb128(header, payload, header.length);
            if (size < 0) {
                return false;
            }
            payload += leb128Length(header, payload);
            if (type == OBU_TEMPORAL_DELIMITER && size != 0) {
                return false;
            }
            if (type == OBU_SEQUENCE_HEADER) {
                var end = payload + size;
                if (end > header.length || !isSequenceHeader(header, payload, (int) size)) {
                    return false;
                }
                return end >= header.length || isFollowingObu(header[(int) end] & 0xFF);
            }
            if (type == OBU_FRAME || type == OBU_FRAME_HEADER) {
                return false;
            }
            if (payload + size > header.length) {
                return false;
            }
            position = (int) (payload + size);
        }
        return false;
    }

    /**
     * Recognizes an AV1 stream in the length-delimited format of the AV1 specification's Annex B: a temporal unit
     * size, a frame unit size and an OBU length, then a temporal delimiter, with a parsable sequence header in the
     * first frame unit.
     *
     * @param header the leading bytes of the input
     * @return {@code true} when the bytes start such a stream
     */
    static boolean isAnnexBObu(byte[] header) {
        var unitSize = leb128(header, 0, header.length);
        if (unitSize <= 0) {
            return false;
        }
        var position = leb128Length(header, 0);
        var unitEnd = position + unitSize;
        var frameSize = leb128(header, position, header.length);
        if (frameSize <= 0 || frameSize > unitSize) {
            return false;
        }
        position += leb128Length(header, position);
        var frameEnd = Math.min(position + frameSize, header.length);
        if (position + frameSize > unitEnd) {
            return false;
        }
        var first = true;
        while (position < frameEnd) {
            var obuLength = leb128(header, position, header.length);
            if (obuLength <= 0) {
                return false;
            }
            position += leb128Length(header, position);
            if (position >= header.length) {
                return false;
            }
            var obuHeader = header[position] & 0xFF;
            var type = (obuHeader >> 3) & 0x0F;
            if ((obuHeader & 0x81) != 0 || !isObuType(type)) {
                return false;
            }
            if (first && (type != OBU_TEMPORAL_DELIMITER || obuLength > 3)) {
                return false;
            }
            first = false;
            if (type == OBU_SEQUENCE_HEADER) {
                var payload = position + 1 + ((obuHeader & 0x04) != 0 ? 1 : 0);
                var end = position + obuLength;
                if ((obuHeader & 0x02) != 0) {
                    var size = leb128(header, payload, header.length);
                    if (size < 0) {
                        return false;
                    }
                    payload += leb128Length(header, payload);
                    end = payload + size;
                    if (end > position + obuLength) {
                        return false;
                    }
                }
                return end <= header.length && isSequenceHeader(header, (int) payload, (int) (end - payload));
            }
            position += obuLength;
        }
        return false;
    }

    /**
     * Returns whether bytes hold a plausible sequence header payload: a profile of 0 to 2, a payload that parses
     * and ends with its trailing bits.
     *
     * @param data the bytes
     * @param offset the index of the payload
     * @param length the payload length
     * @return {@code true} for a plausible sequence header
     */
    private static boolean isSequenceHeader(byte[] data, int offset, int length) {
        if (length < 3 || length > 1024 || (data[offset] & 0xFF) >>> 5 > 2 || data[offset + length - 1] == 0) {
            return false;
        }
        return VideoConfigs.parseSequenceHeader(data, offset, length) != null;
    }

    /**
     * Returns whether a byte can be the header of an OBU that follows a sequence header in a low-overhead stream:
     * a frame, frame header, tile group, metadata, tile list or padding OBU with a size field.
     *
     * @param header the OBU header byte
     * @return {@code true} for a plausible following OBU
     */
    private static boolean isFollowingObu(int header) {
        var type = (header >> 3) & 0x0F;
        return (header & 0x81) == 0 && (header & 0x02) != 0 && type >= OBU_FRAME_HEADER && isObuType(type);
    }

    /**
     * Returns whether an OBU type is defined (AV1 specification section 6.2.2).
     *
     * @param type the {@code obu_type}, from 0 to 15
     * @return {@code true} for types 1 to 8 and 15
     */
    private static boolean isObuType(int type) {
        return (type >= 1 && type <= 8) || type == OBU_PADDING;
    }

    /**
     * Recognizes an MPEG-4 Part 2 or H.263 elementary stream.
     *
     * <p>An MPEG-4 stream starts with a visual object sequence, visual object, video object or video object layer
     * start code and holds a video object plane start code ({@code 00 00 01 B6}) within the bytes given. An H.263
     * stream starts with a picture start code whose picture type has its marker bits set and a source format other
     * than the forbidden one, followed by a second such picture start code.
     *
     * @param header the leading bytes of the input
     * @return {@link ContainerFormat#MPEG4_VIDEO}, {@link ContainerFormat#H263}, or {@code null}
     */
    static ContainerFormat mpeg4(byte[] header) {
        if (header.length < 5 || header[0] != 0 || header[1] != 0) {
            return null;
        }
        if ((header[2] & 0xFC) == 0x80) {
            if (!isH263Picture(header, 0)) {
                return null;
            }
            for (var i = 3; i + 5 <= header.length; i++) {
                if (header[i] == 0 && header[i + 1] == 0 && (header[i + 2] & 0xFC) == 0x80) {
                    return isH263Picture(header, i) ? ContainerFormat.H263 : null;
                }
            }
            return null;
        }
        if (header[2] != 1) {
            return null;
        }
        var code = header[3] & 0xFF;
        if (code != 0xB0 && code != 0xB5 && code > 0x2F) {
            return null;
        }
        for (var i = 4; i + 3 < header.length; i++) {
            if (header[i] == 0 && header[i + 1] == 0 && header[i + 2] == 1) {
                var next = header[i + 3] & 0xFF;
                if (next == 0xB6) {
                    return ContainerFormat.MPEG4_VIDEO;
                }
                if (next != 0xB0 && next != 0xB1 && next != 0xB2 && next != 0xB3 && next != 0xB5 && next > 0x2F) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Returns whether an H.263 picture start code is followed by a plausible picture type: the two marker bits
     * ({@code 1} then {@code 0}) and a source format other than the forbidden 0.
     *
     * @param data the bytes
     * @param offset the index of the picture start code
     * @return {@code true} for a plausible picture header
     */
    private static boolean isH263Picture(byte[] data, int offset) {
        if (offset + 5 > data.length) {
            return false;
        }
        var bits = new MsbBitReader(data, offset, 5);
        bits.skip(22 + 8);
        if (bits.read(1) != 1 || bits.read(1) != 0) {
            return false;
        }
        bits.skip(3);
        return bits.read(3) != 0;
    }

    /**
     * Reads an unsigned LEB128 value (AV1 specification section 4.10.5).
     *
     * @param data the bytes
     * @param position the index of the first byte
     * @param end the index one past the last readable byte
     * @return the value, or {@code -1} when it is truncated, longer than 8 bytes or above {@code 2^31 - 1}
     */
    static long leb128(byte[] data, int position, int end) {
        var value = 0L;
        for (var i = 0; i < 8; i++) {
            if (position + i >= end) {
                return -1;
            }
            var b = data[position + i] & 0xFF;
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value > Integer.MAX_VALUE ? -1 : value;
            }
        }
        return -1;
    }

    /**
     * Returns the number of bytes of an LEB128 value.
     *
     * @param data the bytes
     * @param position the index of the first byte
     * @return the length, from 1 to 8
     */
    static int leb128Length(byte[] data, int position) {
        var length = 1;
        while (length < 8 && position + length - 1 < data.length && (data[position + length - 1] & 0x80) != 0) {
            length++;
        }
        return length;
    }

    /**
     * Reads the fields of an AV1 sequence header OBU payload this package needs: whether it uses the reduced still
     * picture header, and the frame rate its timing information gives.
     *
     * @param data the bytes
     * @param offset the index of the payload
     * @param length the payload length
     * @return {@code [reduced, numerator, denominator]}, the rate zero when not signaled, or {@code null} when the
     * payload is truncated
     */
    static long[] av1Timing(byte[] data, int offset, int length) {
        var bits = new MsbBitReader(data, offset, length);
        bits.skip(4);
        var reduced = bits.readFlag();
        long numerator = 0;
        long denominator = 0;
        if (!reduced && bits.readFlag()) {
            var units = bits.readLong(32);
            var scale = bits.readLong(32);
            var ticks = 1L;
            if (bits.readFlag()) {
                var zeros = 0;
                while (zeros < 32 && bits.read(1) == 0) {
                    zeros++;
                }
                ticks = zeros >= 32 ? -1 : (zeros == 0 ? 0 : bits.readLong(zeros)) + (1L << zeros) - 1 + 1;
            }
            if (units > 0 && scale > 0 && ticks > 0 && units * ticks < 1L << 31 && scale < 1L << 31) {
                numerator = scale;
                denominator = units * ticks;
            }
        }
        if (bits.overrun()) {
            return null;
        }
        return new long[]{reduced ? 1 : 0, numerator, denominator};
    }

    /**
     * Returns the frame rate the timing of an AV1 sequence header gives.
     *
     * @param timing the result of {@link #av1Timing}
     * @return the rate, or {@code null} when not signaled
     */
    static Rational av1FrameRate(long[] timing) {
        return timing == null || timing[1] == 0 ? null : new Rational(timing[1], timing[2]).reduce();
    }
}
