package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.heif.BoxCursor;

/**
 * Reads and writes the MPEG-4 elementary stream descriptor carried by an {@code esds} box, per
 * ISO/IEC 14496-1 clause 7.2.6 and ISO/IEC 14496-14 clause 5.6.
 *
 * <p>Only the fields a container needs are kept: the {@code objectTypeIndication} naming the
 * codec, the bit rates, and the {@code DecoderSpecificInfo} bytes that become the track's codec
 * configuration.
 *
 * @param objectType the {@code objectTypeIndication}, for example {@code 0x40} for MPEG-4 audio
 * @param streamType the six-bit {@code streamType}
 * @param bufferSize the decoder buffer size in bytes
 * @param maxBitrate the peak bit rate in bits per second
 * @param avgBitrate the average bit rate in bits per second
 * @param specificInfo the {@code DecoderSpecificInfo} bytes, or {@code null} when absent
 */
record EsDescriptor(int objectType, int streamType, int bufferSize, long maxBitrate, long avgBitrate, byte[] specificInfo) {
    /**
     * The tag of an {@code ES_Descriptor}.
     */
    private static final int ES_DESCRIPTOR_TAG = 0x03;

    /**
     * The tag of a {@code DecoderConfigDescriptor}.
     */
    private static final int DECODER_CONFIG_TAG = 0x04;

    /**
     * The tag of a {@code DecoderSpecificInfo}.
     */
    private static final int DECODER_SPECIFIC_TAG = 0x05;

    /**
     * The tag of an {@code SLConfigDescriptor}.
     */
    private static final int SL_CONFIG_TAG = 0x06;

    /**
     * The {@code objectTypeIndication} of MPEG-4 audio (AAC and its relatives).
     */
    static final int MPEG4_AUDIO = 0x40;

    /**
     * The {@code objectTypeIndication} of MPEG-2 audio (ISO/IEC 13818-3), used for MP3 at the
     * MPEG-2 sampling rates.
     */
    static final int MPEG2_AUDIO = 0x69;

    /**
     * The {@code objectTypeIndication} of MPEG-1 audio (ISO/IEC 11172-3).
     */
    static final int MPEG1_AUDIO = 0x6B;

    /**
     * The {@code streamType} of an audio stream.
     */
    static final int AUDIO_STREAM = 0x05;

    /**
     * Parses the payload of an {@code esds} box, starting at its full box header.
     *
     * <p>Some QuickTime writers omit the {@code ES_Descriptor} and start directly with the
     * {@code DecoderConfigDescriptor}; both layouts are accepted.
     *
     * @param payload the box payload
     * @return the descriptor
     * @throws MediaException.Malformed if the descriptors are truncated or missing
     */
    static EsDescriptor parse(BoxCursor payload) {
        payload.fullBoxHeader();
        var tag = payload.u8();
        var length = readLength(payload);
        var descriptor = payload.slice(Math.min(length, payload.remaining()));
        if (tag == ES_DESCRIPTOR_TAG) {
            descriptor.u16();
            var flags = descriptor.u8();
            if ((flags & 0x80) != 0) {
                descriptor.u16();
            }
            if ((flags & 0x40) != 0) {
                descriptor.skip(descriptor.u8());
            }
            if ((flags & 0x20) != 0) {
                descriptor.u16();
            }
            while (descriptor.remaining() >= 2) {
                var childTag = descriptor.u8();
                var childLength = readLength(descriptor);
                var child = descriptor.slice(Math.min(childLength, descriptor.remaining()));
                if (childTag == DECODER_CONFIG_TAG) {
                    return parseDecoderConfig(child);
                }
            }
            throw new MediaException.Malformed("esds has no DecoderConfigDescriptor");
        }
        if (tag == DECODER_CONFIG_TAG) {
            return parseDecoderConfig(descriptor);
        }
        throw new MediaException.Malformed("esds starts with unexpected descriptor tag " + tag);
    }

    /**
     * Parses a {@code DecoderConfigDescriptor} body.
     *
     * @param descriptor the cursor over the descriptor body
     * @return the descriptor fields
     */
    private static EsDescriptor parseDecoderConfig(BoxCursor descriptor) {
        var objectType = descriptor.u8();
        var streamType = descriptor.u8() >>> 2;
        var bufferSize = descriptor.u24();
        var maxBitrate = descriptor.u32();
        var avgBitrate = descriptor.u32();
        byte[] specificInfo = null;
        while (descriptor.remaining() >= 2) {
            var tag = descriptor.u8();
            var length = readLength(descriptor);
            if (length > descriptor.remaining()) {
                throw new MediaException.Malformed("DecoderSpecificInfo of " + length + " bytes runs past its descriptor");
            }
            if (tag == DECODER_SPECIFIC_TAG) {
                specificInfo = descriptor.bytes(length);
                break;
            }
            descriptor.skip(length);
        }
        return new EsDescriptor(objectType, streamType, bufferSize, maxBitrate, avgBitrate, specificInfo);
    }

    /**
     * Reads the expandable length of a descriptor: up to four bytes of seven bits each, the high
     * bit of every byte but the last set.
     *
     * @param cursor the cursor positioned at the length
     * @return the length in bytes
     * @throws MediaException.Malformed if the length uses more than four bytes
     */
    private static int readLength(BoxCursor cursor) {
        var length = 0;
        for (var index = 0; index < 4; index++) {
            var value = cursor.u8();
            length = (length << 7) | (value & 0x7F);
            if ((value & 0x80) == 0) {
                return length;
            }
        }
        throw new MediaException.Malformed("descriptor length longer than four bytes");
    }

    /**
     * Writes the payload of an {@code esds} box for this descriptor.
     *
     * @param writer the writer positioned inside the {@code esds} box, after its full box header
     * @param esId the elementary stream identifier, usually the track identifier
     */
    void write(BoxWriter writer, int esId) {
        var specificLength = specificInfo == null ? 0 : 1 + lengthSize(specificInfo.length) + specificInfo.length;
        var decoderConfigLength = 13 + specificLength;
        var esLength = 3 + 1 + lengthSize(decoderConfigLength) + decoderConfigLength + 3;
        writer.u8(ES_DESCRIPTOR_TAG);
        writeLength(writer, esLength);
        writer.u16(esId);
        writer.u8(0);
        writer.u8(DECODER_CONFIG_TAG);
        writeLength(writer, decoderConfigLength);
        writer.u8(objectType);
        writer.u8((streamType << 2) | 1);
        writer.u24(bufferSize);
        writer.u32(maxBitrate);
        writer.u32(avgBitrate);
        if (specificInfo != null) {
            writer.u8(DECODER_SPECIFIC_TAG);
            writeLength(writer, specificInfo.length);
            writer.bytes(specificInfo);
        }
        writer.u8(SL_CONFIG_TAG);
        writer.u8(1);
        writer.u8(2);
    }

    /**
     * Returns the number of bytes the expandable encoding of a length takes.
     *
     * @param length the length to encode, non-negative
     * @return from 1 to 4
     */
    private static int lengthSize(int length) {
        return length < 0x80 ? 1 : length < 0x4000 ? 2 : length < 0x200000 ? 3 : 4;
    }

    /**
     * Writes a length in the shortest expandable encoding.
     *
     * @param writer the destination
     * @param length the length; below {@code 2^28}
     */
    private static void writeLength(BoxWriter writer, int length) {
        var size = lengthSize(length);
        for (var index = size - 1; index >= 0; index--) {
            var value = (length >>> (7 * index)) & 0x7F;
            writer.u8(index == 0 ? value : value | 0x80);
        }
    }
}
