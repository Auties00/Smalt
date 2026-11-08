package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.track.DolbyVision;

/**
 * Reads and writes the Dolby Vision configuration as the containers store it: the 24-byte
 * {@code DOVIDecoderConfigurationRecord} of the MP4 {@code dvcC}, {@code dvvC} and {@code dvwC} boxes and of the
 * Matroska {@code BlockAddIDExtraData}, and the body of the MPEG-TS DOVI video stream descriptor (tag
 * {@code 0xB0}), as Dolby's "Dolby Vision Streams Within the ISO Base Media File Format" and "Dolby Vision Streams
 * Within the MPEG-2 Transport Stream Format" define them.
 *
 * <p>Both start with the same fields:
 * {@snippet :
 * dv_version_major              8 bits
 * dv_version_minor              8 bits
 * dv_profile                    7 bits
 * dv_level                      6 bits
 * rpu_present_flag              1 bit
 * el_present_flag               1 bit
 * bl_present_flag               1 bit
 * dependency_pid               13 bits, then 3 reserved bits (descriptor only, when bl_present_flag is 0)
 * dv_bl_signal_compatibility_id 4 bits
 * dv_md_compression             2 bits
 * reserved                      26 bits in the record (then 4 reserved 32-bit words), 2 in the descriptor
 * }
 */
public final class DolbyVisionConfigs {
    /**
     * The configuration box of profiles 0 to 7 ({@code dvcC}).
     */
    public static final int DVCC = 0x64766343;

    /**
     * The configuration box of profiles 8 to 10 ({@code dvvC}).
     */
    public static final int DVVC = 0x64767643;

    /**
     * The configuration box of profiles above 10 ({@code dvwC}).
     */
    public static final int DVWC = 0x64767743;

    /**
     * The tag of the MPEG-TS DOVI video stream descriptor.
     */
    public static final int DESCRIPTOR_TAG = 0xB0;

    /**
     * The registration {@code format_identifier} of Dolby Vision streams ({@code "DOVI"}).
     */
    public static final int REGISTRATION = 0x444F5649;

    /**
     * The size of a configuration record.
     */
    public static final int RECORD_LENGTH = 24;

    /**
     * The PID a descriptor of a stream without a base layer names when the base layer's PID is unknown.
     */
    public static final int NO_PID = 0x1FFF;

    /**
     * Prevents instantiation.
     */
    private DolbyVisionConfigs() {
        throw new AssertionError();
    }

    /**
     * Returns whether a box or {@code BlockAddIDType} is one of the three Dolby Vision configuration types.
     *
     * @param type the four-character code
     * @return {@code true} for {@code dvcC}, {@code dvvC} and {@code dvwC}
     */
    public static boolean isConfigType(long type) {
        return type == DVCC || type == DVVC || type == DVWC;
    }

    /**
     * Returns the configuration type a muxer writes for a profile: {@code dvcC} up to profile 7, {@code dvvC} for
     * profiles 8 to 10 and {@code dvwC} above.
     *
     * @param configuration the Dolby Vision configuration whose profile selects the type
     * @return the four-character code
     */
    public static int configType(DolbyVision configuration) {
        var profile = configuration.profile();
        return profile <= 7 ? DVCC : profile <= 10 ? DVVC : DVWC;
    }

    /**
     * Parses a configuration record.
     *
     * <p>A record shorter than 24 bytes is read when it holds the first five; its missing reserved bytes are
     * ignored.
     *
     * @param data the buffer
     * @param offset the index of the record
     * @param length the record length
     * @return the configuration, or {@code null} when fewer than five bytes are present
     */
    public static DolbyVision parseRecord(byte[] data, int offset, int length) {
        if (length < 5) {
            return null;
        }
        var flags = (data[offset + 2] & 0xFF) << 8 | data[offset + 3] & 0xFF;
        var last = data[offset + 4] & 0xFF;
        return new DolbyVision(data[offset] & 0xFF, data[offset + 1] & 0xFF, flags >>> 9, flags >>> 3 & 0x3F,
                (flags & 4) != 0, (flags & 2) != 0, (flags & 1) != 0, last >>> 4, last >>> 2 & 3);
    }

    /**
     * Parses a whole configuration record.
     *
     * @param record the record, or {@code null}
     * @return the configuration, or {@code null} when {@code record} is {@code null} or shorter than five bytes
     */
    public static DolbyVision parseRecord(byte[] record) {
        return record == null ? null : parseRecord(record, 0, record.length);
    }

    /**
     * Serializes a configuration record, its reserved bits zero.
     *
     * @param configuration the Dolby Vision configuration to store
     * @return the 24 bytes of the record
     */
    public static byte[] record(DolbyVision configuration) {
        var record = new byte[RECORD_LENGTH];
        writeCommon(record, configuration);
        record[4] = (byte) (configuration.baseLayerCompatibility() << 4 | configuration.metadataCompression() << 2);
        return record;
    }

    /**
     * Parses the body of a DOVI video stream descriptor.
     *
     * <p>A body that ends before the compatibility identifier, as descriptors of the first version of the
     * transport stream specification do, reads as identifier 0.
     *
     * @param data the buffer
     * @param offset the index of the body, after the tag and length bytes
     * @param length the body length
     * @return the configuration, or {@code null} for a body too short to hold the profile and the flags
     */
    public static DolbyVision parseDescriptor(byte[] data, int offset, int length) {
        if (length < 4) {
            return null;
        }
        var flags = (data[offset + 2] & 0xFF) << 8 | data[offset + 3] & 0xFF;
        var base = (flags & 1) != 0;
        var position = offset + (base ? 4 : 6);
        var last = position < offset + length ? data[position] & 0xFF : 0;
        return new DolbyVision(data[offset] & 0xFF, data[offset + 1] & 0xFF, flags >>> 9, flags >>> 3 & 0x3F,
                (flags & 4) != 0, (flags & 2) != 0, base, last >>> 4, last >>> 2 & 3);
    }

    /**
     * Serializes the body of a DOVI video stream descriptor.
     *
     * @param configuration the Dolby Vision configuration to store
     * @param dependencyPid the PID of the base layer, written only when the configuration has no base layer
     * @return the body, five bytes or seven without a base layer
     */
    public static byte[] descriptor(DolbyVision configuration, int dependencyPid) {
        var base = configuration.baseLayerPresent();
        var body = new byte[base ? 5 : 7];
        writeCommon(body, configuration);
        var position = 4;
        if (!base) {
            body[4] = (byte) (dependencyPid >>> 5);
            body[5] = (byte) (dependencyPid << 3 | 0x07);
            position = 6;
        }
        body[position] = (byte) (configuration.baseLayerCompatibility() << 4 | configuration.metadataCompression() << 2
                | 0x03);
        return body;
    }

    /**
     * Writes the versions, the profile, the level and the presence flags.
     *
     * @param out the destination, at least four bytes
     * @param configuration the Dolby Vision configuration to store
     */
    private static void writeCommon(byte[] out, DolbyVision configuration) {
        out[0] = (byte) configuration.versionMajor();
        out[1] = (byte) configuration.versionMinor();
        var flags = configuration.profile() << 9 | configuration.level() << 3
                | (configuration.rpuPresent() ? 4 : 0) | (configuration.enhancementLayerPresent() ? 2 : 0)
                | (configuration.baseLayerPresent() ? 1 : 0);
        out[2] = (byte) (flags >>> 8);
        out[3] = (byte) flags;
    }
}
