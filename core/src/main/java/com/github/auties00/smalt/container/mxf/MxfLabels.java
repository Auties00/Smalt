package com.github.auties00.smalt.container.mxf;

import java.util.Arrays;

/**
 * The SMPTE universal labels and keys MXF files are made of (SMPTE ST 377-1, ST 379-2, ST 381-1, ST 381-3, ST 382,
 * ST 2019-4, RDD 44 and the SMPTE Labels, Groups and Elements registers), with the byte tests that classify them.
 *
 * <p>Labels compare without their version byte (index 7), which registries and writers set differently.
 */
final class MxfLabels {
    /**
     * The first eleven bytes of a partition pack key, through the byte that names a partition pack.
     */
    static final byte[] PARTITION_PREFIX = bytes("060e2b34020501010d010201");

    /**
     * The key of the primer pack.
     */
    static final byte[] PRIMER = bytes("060e2b34020501010d01020101050100");

    /**
     * The key of the random index pack.
     */
    static final byte[] RANDOM_INDEX = bytes("060e2b34020501010d01020101110100");

    /**
     * The key of an index table segment.
     */
    static final byte[] INDEX_SEGMENT = bytes("060e2b34025301010d01020101100100");

    /**
     * The key of a KLV fill item, as ST 377-1 writes it (version byte 2).
     */
    static final byte[] FILL = bytes("060e2b34010101020301021001000000");

    /**
     * The first fourteen bytes of the key of a header metadata set; byte 14 names the set.
     */
    static final byte[] SET_PREFIX = bytes("060e2b34025301010d0101010101");

    /**
     * The set byte (byte 14 of the key) of the preface.
     */
    static final int PREFACE = 0x2F;

    /**
     * The set byte of an identification set.
     */
    static final int IDENTIFICATION = 0x30;

    /**
     * The set byte of the content storage set.
     */
    static final int CONTENT_STORAGE = 0x18;

    /**
     * The set byte of an essence container data set.
     */
    static final int ESSENCE_CONTAINER_DATA = 0x23;

    /**
     * The set byte of a material package.
     */
    static final int MATERIAL_PACKAGE = 0x36;

    /**
     * The set byte of a source package.
     */
    static final int SOURCE_PACKAGE = 0x37;

    /**
     * The set byte of a static track.
     */
    static final int STATIC_TRACK = 0x3A;

    /**
     * The set byte of a timeline track.
     */
    static final int TIMELINE_TRACK = 0x3B;

    /**
     * The set byte of an event track.
     */
    static final int EVENT_TRACK = 0x39;

    /**
     * The set byte of a sequence.
     */
    static final int SEQUENCE = 0x0F;

    /**
     * The set byte of a source clip.
     */
    static final int SOURCE_CLIP = 0x11;

    /**
     * The set byte of a timecode component.
     */
    static final int TIMECODE_COMPONENT = 0x14;

    /**
     * The set byte of a tagged value.
     */
    static final int TAGGED_VALUE = 0x3F;

    /**
     * The set byte of a multiple descriptor.
     */
    static final int MULTIPLE_DESCRIPTOR = 0x44;

    /**
     * The set byte of a CDCI picture descriptor.
     */
    static final int CDCI_DESCRIPTOR = 0x28;

    /**
     * The set byte of a generic sound descriptor.
     */
    static final int SOUND_DESCRIPTOR = 0x42;

    /**
     * The set byte of an AES3 audio descriptor.
     */
    static final int AES3_DESCRIPTOR = 0x47;

    /**
     * The set byte of a wave audio descriptor.
     */
    static final int WAVE_DESCRIPTOR = 0x48;

    /**
     * The set byte of an MPEG video descriptor.
     */
    static final int MPEG_DESCRIPTOR = 0x51;

    /**
     * The set byte of an AVC sub-descriptor.
     */
    static final int AVC_SUB_DESCRIPTOR = 0x6E;

    /**
     * The first twelve bytes of a generic container essence element key; bytes 12 to 15 are the track number.
     */
    static final byte[] ELEMENT_PREFIX = bytes("060e2b34010201010d010301");

    /**
     * The item type of a generic container picture item.
     */
    static final int PICTURE_ITEM = 0x15;

    /**
     * The item type of a generic container sound item.
     */
    static final int SOUND_ITEM = 0x16;

    /**
     * Operational pattern 1a, single item, single package, multi-track, stream file, internal essence.
     */
    static final byte[] OP1A = bytes("060e2b34040101010d01020101010900");

    /**
     * Operational pattern Atom, N tracks and N source clips, as written for one track.
     */
    static final byte[] OP_ATOM = bytes("060e2b34040101020d01020110030000");

    /**
     * The essence container label of the generic container with several wrappings.
     */
    static final byte[] MULTIPLE_WRAPPINGS = bytes("060e2b34040101030d010301027f0100");

    /**
     * The essence container label of frame-wrapped Broadcast Wave audio (ST 382).
     */
    static final byte[] BWF_FRAME = bytes("060e2b34040101010d01030102060100");

    /**
     * The essence container label of clip-wrapped Broadcast Wave audio (ST 382).
     */
    static final byte[] BWF_CLIP = bytes("060e2b34040101010d01030102060200");

    /**
     * The essence container label of frame-wrapped progressive JPEG XS pictures (SMPTE ST 2124).
     */
    static final byte[] JXS_FRAME = bytes("060e2b340401010d0d01030102210100");

    /**
     * The essence container label of frame-wrapped interlaced JPEG XS pictures, two fields per element.
     */
    static final byte[] JXS_FRAME_INTERLACED = bytes("060e2b340401010d0d01030102210200");

    /**
     * The essence container label of clip-wrapped JPEG XS pictures.
     */
    static final byte[] JXS_CLIP = bytes("060e2b340401010d0d01030102210300");

    /**
     * The picture essence coding label of a JPEG XS codestream; byte 14 names its profile (1 unrestricted).
     */
    static final byte[] JXS_CODING = bytes("060e2b340401010d0401020203080100");

    /**
     * The key of the JPEG XS picture sub-descriptor set.
     */
    static final byte[] JXS_SUB_DESCRIPTOR = bytes("060e2b34025301010d01010101018102");

    /**
     * The first local tag of the JPEG XS sub-descriptor items, {@code Ppih} to {@code Hsl} in the order of SMPTE ST
     * 2124 table 4.
     */
    static final int JXS_FIRST_WRITE_TAG = 0x8002;

    /**
     * The essence container label of frame-wrapped VC-3 (ST 2019-4).
     */
    static final byte[] VC3_FRAME = bytes("060e2b340401010a0d01030102110100");

    /**
     * The essence container label of clip-wrapped VC-3 (ST 2019-4).
     */
    static final byte[] VC3_CLIP = bytes("060e2b340401010a0d01030102110200");

    /**
     * The essence container label of frame-wrapped ProRes (RDD 44).
     */
    static final byte[] PRORES_FRAME = bytes("060e2b340401010d0d010301021c0100");

    /**
     * The essence container label of a frame-wrapped AVC byte stream with video stream id {@code 0xE0} (ST 381-3).
     */
    static final byte[] AVC_FRAME = bytes("060e2b340401010a0d01030102106001");

    /**
     * The essence container label of a clip-wrapped AVC byte stream with video stream id {@code 0xE0} (ST 381-3).
     */
    static final byte[] AVC_CLIP = bytes("060e2b340401010a0d01030102106002");

    /**
     * The essence container label of a frame-wrapped MPEG video elementary stream with stream id {@code 0xE0}
     * (ST 381-1).
     */
    static final byte[] MPEG_FRAME = bytes("060e2b34040101020d01030102046001");

    /**
     * The essence container label of a clip-wrapped MPEG video elementary stream with stream id {@code 0xE0}
     * (ST 381-1).
     */
    static final byte[] MPEG_CLIP = bytes("060e2b34040101020d01030102046002");

    /**
     * The data definition of picture tracks.
     */
    static final byte[] PICTURE_DEFINITION = bytes("060e2b34040101010103020201000000");

    /**
     * The data definition of sound tracks.
     */
    static final byte[] SOUND_DEFINITION = bytes("060e2b34040101010103020202000000");

    /**
     * The data definition of timecode tracks.
     */
    static final byte[] TIMECODE_DEFINITION = bytes("060e2b34040101010103020101000000");

    /**
     * The picture coding label prefix of VC-3, followed by its compression id byte.
     */
    static final byte[] VC3_CODING = bytes("060e2b340401010a0401020271000000");

    /**
     * The picture coding label prefix of ProRes, followed by its profile byte.
     */
    static final byte[] PRORES_CODING = bytes("060e2b340401010d0401020203060000");

    /**
     * The local tag reserved by this implementation for the {@code SubDescriptors} item, which has no static tag.
     */
    static final int SUB_DESCRIPTORS_TAG = 0x10000;

    /**
     * The label of the {@code SubDescriptors} item of a generic descriptor.
     */
    static final byte[] SUB_DESCRIPTORS = bytes("060e2b34010101090601010406100000");

    /**
     * The local tag this implementation writes {@code SubDescriptors} under, declared in the primer pack.
     */
    static final int SUB_DESCRIPTORS_WRITE_TAG = 0x8000;

    /**
     * The local tag reserved by this implementation for the {@code AVCDecodingDelay} item, which has no static tag.
     */
    static final int AVC_DECODING_DELAY_TAG = 0x10001;

    /**
     * The label of the {@code AVCDecodingDelay} item of an AVC sub-descriptor (SMPTE ST 381-3).
     */
    static final byte[] AVC_DECODING_DELAY = bytes("060e2b340101010e04010606010e0000");

    /**
     * The local tag this implementation writes {@code AVCDecodingDelay} under, declared in the primer pack.
     */
    static final int AVC_DECODING_DELAY_WRITE_TAG = 0x8001;

    /**
     * The first sixteen bytes of a basic UMID of the material and source packages this implementation writes:
     * universal label, length {@code 0x13}, and instance number zero.
     */
    static final byte[] UMID_PREFIX = bytes("060a2b340101010501010d2013000000");

    /**
     * The last eight bytes of the UUIDs of the AAF data definitions (picture
     * {@code 6f3c8ce1-6cef-11d2-807d-006008143e6f}, sound {@code 78e1ebe1-6cef-11d2-807d-006008143e6f}, timecode
     * {@code 7f275e81-77e5-11d2-807f-006008143e6f}),
     * which an AUID stores first.
     */
    private static final byte[] LEGACY_SUFFIX = bytes("807d006008143e6f");

    private MxfLabels() {
        throw new AssertionError();
    }

    /**
     * Parses a hexadecimal string.
     *
     * @param hex the digits, two per byte
     * @return the bytes
     */
    static byte[] bytes(String hex) {
        var out = new byte[hex.length() / 2];
        for (var index = 0; index < out.length; index++) {
            out[index] = (byte) Integer.parseInt(hex, 2 * index, 2 * index + 2, 16);
        }
        return out;
    }

    /**
     * Returns whether a key starts with a prefix, ignoring the version byte (index 7).
     *
     * @param key the key, at least as long as {@code prefix}
     * @param prefix the expected leading bytes
     * @return {@code true} when every byte but the version byte matches
     */
    static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) {
            return false;
        }
        for (var index = 0; index < prefix.length; index++) {
            if (index != 7 && key[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether two labels are equal, ignoring the version byte.
     *
     * @param label the first label
     * @param other the second label, or {@code null}
     * @return {@code true} when both are 16 bytes that match but for byte 7
     */
    static boolean matches(byte[] label, byte[] other) {
        return other != null && label.length == 16 && other.length == 16 && startsWith(label, other);
    }

    /**
     * {@return whether a key is a partition pack key}
     *
     * @param key the 16-byte key
     */
    static boolean isPartition(byte[] key) {
        return startsWith(key, PARTITION_PREFIX) && (key[13] == 2 || key[13] == 3 || key[13] == 4)
                && key[14] >= 1 && key[14] <= 4;
    }

    /**
     * {@return whether a key is a KLV fill key, of either version}
     *
     * @param key the 16-byte key
     */
    static boolean isFill(byte[] key) {
        return startsWith(key, FILL);
    }

    /**
     * {@return whether a key is the key of a header metadata set}
     *
     * @param key the 16-byte key
     */
    static boolean isSet(byte[] key) {
        return (key[0] & 0xFF) == 0x06 && key[1] == 0x0E && key[2] == 0x2B && key[3] == 0x34 && key[4] == 0x02
                && key[5] == 0x53;
    }

    /**
     * {@return whether a key is the key of a generic container essence element}
     *
     * @param key the 16-byte key
     */
    static boolean isElement(byte[] key) {
        return startsWith(key, ELEMENT_PREFIX) || (key[0] & 0xFF) == 0x06 && key[1] == 0x0E && key[2] == 0x2B
                && key[3] == 0x34 && key[4] == 0x01 && key[5] == 0x02 && key[6] == 0x01 && key[8] == 0x0E
                && key[9] == 0x04 && key[10] == 0x03 && key[11] == 0x01;
    }

    /**
     * {@return the track number an essence element key carries in its last four bytes}
     *
     * @param key the 16-byte key
     */
    static int trackNumber(byte[] key) {
        return (key[12] & 0xFF) << 24 | (key[13] & 0xFF) << 16 | (key[14] & 0xFF) << 8 | key[15] & 0xFF;
    }

    /**
     * Returns the kind of track a data definition label names.
     *
     * @param label the 16-byte data definition
     * @return 'p' for picture, 's' for sound, 't' for timecode, 'd' for data, or {@code 0} when unknown
     */
    static char dataKind(byte[] label) {
        if (label == null || label.length != 16) {
            return 0;
        }
        if (startsWith(label, bytes("060e2b3404010101010302"))) {
            return switch (label[11]) {
                case 1 -> 't';
                case 2 -> switch (label[12]) {
                    case 1 -> 'p';
                    case 2 -> 's';
                    case 3 -> 'd';
                    default -> 0;
                };
                case 3 -> 'd';
                default -> 0;
            };
        }
        // AAF legacy definitions are UUIDs, stored with their two halves swapped
        if (Arrays.equals(label, 0, 8, LEGACY_SUFFIX, 0, 8)) {
            return switch (label[8] & 0xFF) {
                case 0x6F -> 'p';
                case 0x78 -> 's';
                case 0x7F -> 't';
                default -> 0;
            };
        }
        return 0;
    }
}
