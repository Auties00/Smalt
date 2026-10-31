/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/SEIread.cpp and source/Lib/CommonLib/SEI_internal.cpp: the decoded
 * picture hash). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The
 * VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AmbientViewingEnvironment;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ContentColorVolume;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.caption.CcData;
import com.github.auties00.smalt.internal.sei.DisplaySei;
import com.github.auties00.smalt.internal.sei.FilmGrainParameters;
import com.github.auties00.smalt.internal.sei.H274Sei;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FramePacking;
import com.github.auties00.smalt.video.OmnidirectionalVideo;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Reads the SEI messages of a VVC stream (ITU-T H.266 Annex D and Rec. ITU-T H.274) the decoder uses, and verifies
 * decoded pictures against their decoded picture hash (H.274 clause 8.7).
 *
 * <p>An instance collects the prefix messages that describe how to display the next picture: the film grain
 * characteristics (8.5), the frame packing arrangement (8.6), the display orientation (8.25), the mastering display
 * colour volume (8.12), the content light level information (8.13), the alternative transfer characteristics (8.14),
 * the frame-field information (8.16) and the ATSC A/53 caption data of the user data registered by Rec. ITU-T T.35
 * message (8.3), and the messages {@link H274Sei} parses: the ambient viewing environment, the content colour
 * volume, the sample aspect ratio information and the omnidirectional messages. A message too short for its syntax,
 * or whose values are out of range, is ignored; every other message is skipped.
 */
public final class VvcSei {
    /**
     * The payload type of the decoded picture hash SEI message.
     */
    static final int DECODED_PICTURE_HASH = 132;

    /**
     * The payload type of the frame-field information SEI message.
     */
    static final int FRAME_FIELD_INFO = 168;

    /**
     * The payload type of the user data registered by Rec. ITU-T T.35 SEI message.
     */
    static final int USER_DATA_REGISTERED = 4;

    /**
     * The payload type of the film grain characteristics SEI message.
     */
    static final int FILM_GRAIN = 19;

    /**
     * The payload type of the frame packing arrangement SEI message.
     */
    static final int FRAME_PACKING = 45;

    /**
     * The payload type of the display orientation SEI message.
     */
    static final int DISPLAY_ORIENTATION = 47;

    /**
     * The payload type of the mastering display colour volume SEI message.
     */
    static final int MASTERING_DISPLAY = 137;

    /**
     * The payload type of the content light level information SEI message.
     */
    static final int CONTENT_LIGHT_LEVEL = 144;

    /**
     * The payload type of the alternative transfer characteristics SEI message.
     */
    static final int ALTERNATIVE_TRANSFER = 147;

    /**
     * The mastering display of the last message that carried one, or {@code null}.
     */
    public MasteringDisplay masteringDisplay;

    /**
     * The content light level of the last message that carried one, or {@code null}.
     */
    public ContentLightLevel contentLightLevel;

    /**
     * The {@code preferred_transfer_characteristics} of the alternative transfer characteristics message in effect
     * for the coded layer video sequence, or -1.
     */
    public int preferredTransfer = -1;

    /**
     * Whether an alternative transfer characteristics message arrived for the next picture.
     */
    public boolean transferPending;

    /**
     * The film grain characteristics message of the next picture, or {@code null}.
     */
    public FilmGrainParameters filmGrain;

    /**
     * The frame packing arrangement message of the next picture, or {@code null}.
     */
    public DisplaySei.FramePackingMessage framePacking;

    /**
     * The display orientation message of the next picture, or {@code null}.
     */
    public DisplaySei.OrientationMessage orientation;

    /**
     * The {@code cc_data} constructs of the ATSC caption messages of the next picture.
     */
    public final ByteArrayOutputStream captions = new ByteArrayOutputStream();

    /**
     * The parity a frame-field information message gives the next picture when it is a field: 0 top, 1 bottom, or
     * -1 when none arrived or the message describes a frame.
     */
    public int fieldParity = -1;

    /**
     * The scan a frame-field information message gives the next picture when it is a frame, or {@code null} when
     * none arrived or the message describes a field.
     */
    public FieldOrder fieldOrder;

    /**
     * The field periods a frame-field information message displays the next frame for beyond its own two: 1 for a
     * repeated field, 2 for a doubled frame and 4 for a tripled one.
     */
    public int repeatedFields;

    /**
     * The ambient viewing environment of the coded layer video sequence, or {@code null}.
     */
    public AmbientViewingEnvironment ambientViewing;

    /**
     * Whether an ambient viewing environment message arrived for the next picture.
     */
    public boolean ambientPending;

    /**
     * The content colour volume message of the next picture, or {@code null}.
     */
    public H274Sei.Message<ContentColorVolume> contentColorVolume;

    /**
     * The sample aspect ratio information message of the next picture, or {@code null}.
     */
    public H274Sei.Message<Rational> sampleAspectRatio;

    /**
     * The equirectangular projection message of the next picture, or {@code null}.
     */
    public H274Sei.Message<OmnidirectionalVideo.Projection> equirectangular;

    /**
     * The generalized cubemap projection message of the next picture, or {@code null}.
     */
    public H274Sei.Message<OmnidirectionalVideo.Projection> cubemap;

    /**
     * The sphere rotation message of the next picture, or {@code null}.
     */
    public H274Sei.Message<OmnidirectionalVideo.SphereRotation> sphereRotation;

    /**
     * The region-wise packing message of the next picture, or {@code null}.
     */
    public H274Sei.Message<OmnidirectionalVideo.RegionWisePacking> regionWisePacking;

    /**
     * The omnidirectional viewport message of the next picture, or {@code null}.
     */
    public H274Sei.Message<OmnidirectionalVideo.Viewports> viewports;

    /**
     * The reader of message payloads coded with Exp-Golomb codes.
     */
    private final H264BitReader reader = new H264BitReader();

    /**
     * Constructs an empty collector.
     */
    public VvcSei() {
    }

    /**
     * Parses the messages of a prefix SEI RBSP.
     *
     * @param rbsp the unescaped payload, followed by at least {@link H264BitReader#PADDING} bytes
     * @param offset the index of the first byte after the NAL unit header
     * @param end the index one past the last payload byte
     */
    public void parsePrefix(byte[] rbsp, int offset, int end) {
        var position = offset;
        while (position < end - 1) {
            var payloadType = 0;
            while (position < end && (rbsp[position] & 0xFF) == 0xFF) {
                payloadType += 255;
                position++;
            }
            if (position >= end) {
                return;
            }
            payloadType += rbsp[position++] & 0xFF;
            var payloadSize = 0;
            while (position < end && (rbsp[position] & 0xFF) == 0xFF) {
                payloadSize += 255;
                position++;
            }
            if (position >= end) {
                return;
            }
            payloadSize += rbsp[position++] & 0xFF;
            if (payloadSize > end - position) {
                return;
            }
            switch (payloadType) {
                case USER_DATA_REGISTERED -> CcData.t35(rbsp, position, position + payloadSize, captions);
                case FILM_GRAIN -> {
                    reader.init(rbsp, position, payloadSize);
                    var message = FilmGrainParameters.parse(reader, true);
                    if (message != null) {
                        filmGrain = message;
                    }
                }
                case FRAME_PACKING -> {
                    reader.init(rbsp, position, payloadSize);
                    var message = DisplaySei.framePacking(reader, true);
                    // H.274 defines only the side by side, top and bottom and frame alternation arrangements, and
                    // decoders ignore messages of the other types
                    if (message != null && (message.cancel() || isH274Arrangement(message.packing()))) {
                        framePacking = message;
                    }
                }
                case DISPLAY_ORIENTATION -> parseOrientation(rbsp, position, payloadSize);
                case MASTERING_DISPLAY -> {
                    if (payloadSize >= 24) {
                        masteringDisplay = parseMasteringDisplay(rbsp, position);
                    }
                }
                case CONTENT_LIGHT_LEVEL -> {
                    if (payloadSize >= 4) {
                        contentLightLevel = new ContentLightLevel(u16(rbsp, position), u16(rbsp, position + 2));
                    }
                }
                case ALTERNATIVE_TRANSFER -> {
                    if (payloadSize >= 1) {
                        preferredTransfer = rbsp[position] & 0xFF;
                        transferPending = true;
                    }
                }
                case FRAME_FIELD_INFO -> parseFrameFieldInfo(rbsp, position, payloadSize);
                case H274Sei.AMBIENT_VIEWING_ENVIRONMENT -> {
                    reader.init(rbsp, position, payloadSize);
                    var message = H274Sei.ambientViewing(reader);
                    if (message != null) {
                        ambientViewing = message;
                        ambientPending = true;
                    }
                }
                case H274Sei.CONTENT_COLOUR_VOLUME -> {
                    reader.init(rbsp, position, payloadSize);
                    contentColorVolume = keep(contentColorVolume, H274Sei.contentColorVolume(reader));
                }
                case H274Sei.SAMPLE_ASPECT_RATIO_INFO -> {
                    reader.init(rbsp, position, payloadSize);
                    sampleAspectRatio = keep(sampleAspectRatio, H274Sei.sampleAspectRatio(reader));
                }
                case H274Sei.EQUIRECTANGULAR_PROJECTION -> {
                    reader.init(rbsp, position, payloadSize);
                    equirectangular = keep(equirectangular, H274Sei.equirectangular(reader));
                }
                case H274Sei.GENERALIZED_CUBEMAP_PROJECTION -> {
                    reader.init(rbsp, position, payloadSize);
                    cubemap = keep(cubemap, H274Sei.generalizedCubemap(reader));
                }
                case H274Sei.SPHERE_ROTATION -> {
                    reader.init(rbsp, position, payloadSize);
                    sphereRotation = keep(sphereRotation, H274Sei.sphereRotation(reader));
                }
                case H274Sei.REGION_WISE_PACKING -> {
                    reader.init(rbsp, position, payloadSize);
                    regionWisePacking = keep(regionWisePacking, H274Sei.regionWisePacking(reader));
                }
                case H274Sei.OMNI_VIEWPORT -> {
                    reader.init(rbsp, position, payloadSize);
                    viewports = keep(viewports, H274Sei.omniViewport(reader));
                }
                default -> {
                }
            }
            position += payloadSize;
        }
    }

    /**
     * {@return whether a frame packing arrangement is of a type Rec. ITU-T H.274 defines: side by side, top and
     * bottom or frame alternation}
     *
     * @param packing the arrangement
     */
    private static boolean isH274Arrangement(FramePacking packing) {
        return switch (packing.arrangement()) {
            case SIDE_BY_SIDE, TOP_BOTTOM, FRAME_ALTERNATION -> true;
            default -> false;
        };
    }

    /**
     * {@return a parsed message, or the one held before when the new one is unusable}
     *
     * @param held the message held for the next picture, or {@code null}
     * @param parsed the message just parsed, or {@code null}
     * @param <T> the type of the description
     */
    private static <T> H274Sei.Message<T> keep(H274Sei.Message<T> held, H274Sei.Message<T> parsed) {
        return parsed != null ? parsed : held;
    }

    /**
     * Forgets the messages that apply only to the next picture: the film grain characteristics, the frame packing
     * arrangement, the display orientation, the caption data, the frame-field information, the content colour
     * volume, the sample aspect ratio information and omnidirectional messages, and the arrival of an alternative
     * transfer characteristics or ambient viewing environment message.
     */
    public void clearPictureMessages() {
        captions.reset();
        ambientPending = false;
        contentColorVolume = null;
        sampleAspectRatio = null;
        equirectangular = null;
        cubemap = null;
        sphereRotation = null;
        regionWisePacking = null;
        viewports = null;
        filmGrain = null;
        framePacking = null;
        orientation = null;
        fieldParity = -1;
        fieldOrder = null;
        repeatedFields = 0;
        transferPending = false;
    }

    /**
     * Parses a display orientation message (Rec. ITU-T H.274 clause 8.25): {@code display_orientation_cancel_flag},
     * then {@code display_orientation_persistence_flag} and the three-bit
     * {@code display_orientation_transform_type}, whose rotation (anticlockwise) applies before the horizontal mirror
     * (Table 19).
     *
     * @param rbsp the payload buffer, followed by at least {@link H264BitReader#PADDING} bytes
     * @param position the index of the message payload
     * @param size the message payload size
     */
    private void parseOrientation(byte[] rbsp, int position, int size) {
        if (size < 1) {
            return;
        }
        reader.init(rbsp, position, size);
        if (reader.readFlag()) {
            orientation = new DisplaySei.OrientationMessage(true, false, null);
            return;
        }
        var persists = reader.readFlag();
        var type = reader.readBits(3);
        if (reader.overrun()) {
            return;
        }
        orientation = new DisplaySei.OrientationMessage(false, persists, orientationOf(type));
    }

    /**
     * Returns the orientation of a {@code display_orientation_transform_type}.
     *
     * <p>A mirror after an anticlockwise rotation equals the mirror followed by the same rotation clockwise, the form
     * {@link Orientation#ofRotation(int, boolean)} takes; a rotation alone is anticlockwise, the negated clockwise
     * one.
     *
     * @param type the transform type, 0 to 7
     * @return the orientation
     */
    static Orientation orientationOf(int type) {
        return switch (type) {
            case 1 -> Orientation.ofRotation(0, true);
            case 2 -> Orientation.ofRotation(180, false);
            case 3 -> Orientation.ofRotation(180, true);
            case 4 -> Orientation.ofRotation(90, true);
            case 5 -> Orientation.ofRotation(270, false);
            case 6 -> Orientation.ofRotation(270, true);
            case 7 -> Orientation.ofRotation(90, false);
            default -> Orientation.ofRotation(0, false);
        };
    }

    /**
     * Parses a frame-field information message (Rec. ITU-T H.274 clause 8.16) into the parity of a field, or the
     * scan and repetition of a frame as Table 14 gives them: a frame whose fields are displayed in turn is
     * {@link FieldOrder#TOP_FIRST} or {@link FieldOrder#BOTTOM_FIRST}, with one repeated field for three display
     * elemental periods; a frame displayed whole is {@link FieldOrder#PROGRESSIVE}, doubled for two periods and
     * tripled for three.
     *
     * @param rbsp the payload buffer, followed by at least {@link H264BitReader#PADDING} bytes
     * @param position the index of the message payload
     * @param size the message payload size
     */
    private void parseFrameFieldInfo(byte[] rbsp, int position, int size) {
        if (size < 1) {
            return;
        }
        reader.init(rbsp, position, size);
        try {
            if (reader.readFlag()) {
                var bottom = reader.readFlag();
                if (!reader.overrun()) {
                    fieldParity = bottom ? 1 : 0;
                }
                return;
            }
            var fromFrame = reader.readFlag();
            var topFirst = fromFrame && reader.readFlag();
            var periods = reader.readBits(8) + 1;
            if (reader.overrun()) {
                return;
            }
            if (fromFrame) {
                fieldOrder = topFirst ? FieldOrder.TOP_FIRST : FieldOrder.BOTTOM_FIRST;
                repeatedFields = periods == 3 ? 1 : 0;
            } else {
                fieldOrder = FieldOrder.PROGRESSIVE;
                repeatedFields = periods == 2 ? 2 : periods == 3 ? 4 : 0;
            }
        } catch (MediaException ignored) {
            // a truncated message makes the message unusable, not the stream
        }
    }

    /**
     * Parses the payload of a mastering display colour volume message, whose primaries are stored in the order
     * green, blue, red, in units of 0.00002, and whose luminances are in units of 0.0001 candelas per square meter.
     *
     * @param rbsp the payload buffer
     * @param position the index of the message payload
     * @return the mastering display
     */
    private static MasteringDisplay parseMasteringDisplay(byte[] rbsp, int position) {
        var green = new Chromaticity(u16(rbsp, position) * 0.00002, u16(rbsp, position + 2) * 0.00002);
        var blue = new Chromaticity(u16(rbsp, position + 4) * 0.00002, u16(rbsp, position + 6) * 0.00002);
        var red = new Chromaticity(u16(rbsp, position + 8) * 0.00002, u16(rbsp, position + 10) * 0.00002);
        var white = new Chromaticity(u16(rbsp, position + 12) * 0.00002, u16(rbsp, position + 14) * 0.00002);
        var max = u32(rbsp, position + 16) * 0.0001;
        var min = u32(rbsp, position + 20) * 0.0001;
        return new MasteringDisplay(red, green, blue, white, max, min);
    }

    /**
     * Reads a big-endian unsigned 16-bit value.
     *
     * @param data the buffer
     * @param position the index of the first byte
     * @return the value
     */
    private static int u16(byte[] data, int position) {
        return ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
    }

    /**
     * Reads a big-endian unsigned 32-bit value.
     *
     * @param data the buffer
     * @param position the index of the first byte
     * @return the value
     */
    private static long u32(byte[] data, int position) {
        return ((long) u16(data, position) << 16) | u16(data, position + 2);
    }

    /**
     * A decoded picture hash: type 0 MD5, 1 CRC, 2 checksum, and one value per component.
     *
     * @param type the hash type
     * @param values the digest of each component (16 bytes for MD5, 2 for CRC, 4 for checksum), big-endian
     */
    public record Hash(int type, byte[][] values) {
    }

    /**
     * Finds the payload of the first SEI message of a type in an unescaped SEI NAL unit payload.
     *
     * @param data the payload after the NAL unit header
     * @param offset the first byte
     * @param end one past the last byte
     * @param wanted the payload type
     * @return the message's payload start in the high 32 bits and its size in the low 32 bits, or -1 when the unit
     * carries no such message or is malformed
     */
    private static long findPayload(byte[] data, int offset, int end, int wanted) {
        var p = offset;
        while (p < end - 1) {
            var type = 0;
            while (p < end && (data[p] & 0xFF) == 0xFF) {
                type += 255;
                p++;
            }
            if (p >= end) {
                return -1;
            }
            type += data[p++] & 0xFF;
            var size = 0;
            while (p < end && (data[p] & 0xFF) == 0xFF) {
                size += 255;
                p++;
            }
            if (p >= end) {
                return -1;
            }
            size += data[p++] & 0xFF;
            if (p + size > end) {
                return -1;
            }
            if (type == wanted) {
                return ((long) p << 32) | size;
            }
            p += size;
            if (p == end - 1 && (data[p] & 0xFF) == 0x80) {
                break;
            }
        }
        return -1;
    }

    /**
     * Parses the SEI messages of an unescaped SEI NAL unit payload and returns its decoded picture hash, if any.
     *
     * @param data the payload after the NAL unit header
     * @param offset the first byte
     * @param end one past the last byte
     * @return the hash, or {@code null} when the unit carries none or is malformed
     */
    public static Hash findHash(byte[] data, int offset, int end) {
        var found = findPayload(data, offset, end, DECODED_PICTURE_HASH);
        if (found < 0) {
            return null;
        }
        var p = (int) (found >>> 32);
        var size = (int) found;
        if (size < 2) {
            return null;
        }
        var hashType = data[p] & 0xFF;
        var single = (data[p + 1] & 0x80) != 0;
        var count = single ? 1 : 3;
        var length = hashType == 0 ? 16 : hashType == 1 ? 2 : hashType == 2 ? 4 : -1;
        if (length < 0 || size < 2 + count * length) {
            return null;
        }
        var values = new byte[count][];
        for (var c = 0; c < count; c++) {
            values[c] = Arrays.copyOfRange(data, p + 2 + c * length, p + 2 + (c + 1) * length);
        }
        return new Hash(hashType, values);
    }

    /**
     * Checks a decoded picture against a hash.
     *
     * @param pic the picture
     * @param hash the decoded picture hash message of the picture
     * @return whether every component matches
     */
    public static boolean matches(VvcPicture pic, Hash hash) {
        var count = Math.min(hash.values().length, pic.planes.length);
        for (var c = 0; c < count; c++) {
            var expected = hash.values()[c];
            var actual = compute(pic.planes[c], pic.planeWidth[c], pic.planeHeight[c], pic.bitDepth, hash.type());
            if (!Arrays.equals(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes the hash of a plane.
     *
     * @param plane the samples
     * @param width the plane width, also its stride
     * @param height the plane height
     * @param depth the bit depth
     * @param type the hash type: 0 MD5, 1 CRC, 2 checksum
     * @return the digest, big-endian
     */
    static byte[] compute(short[] plane, int width, int height, int depth, int type) {
        return switch (type) {
            case 0 -> md5(plane, width, height, depth);
            case 1 -> {
                var crc = crc(plane, width, height, depth);
                yield new byte[]{(byte) (crc >> 8), (byte) crc};
            }
            default -> {
                var sum = checksum(plane, width, height, depth);
                yield new byte[]{(byte) (sum >> 24), (byte) (sum >> 16), (byte) (sum >> 8), (byte) sum};
            }
        };
    }

    /**
     * Computes the MD5 of a plane, its samples one byte each up to 8 bits and two little-endian bytes otherwise (H.274
     * 8.7.2).
     *
     * @param plane the samples
     * @param width the plane width, also its stride
     * @param height the plane height
     * @param depth the bit depth
     * @return the 16-byte digest
     */
    private static byte[] md5(short[] plane, int width, int height, int depth) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        var bytes = depth > 8 ? 2 : 1;
        var row = new byte[width * bytes];
        for (var y = 0; y < height; y++) {
            var offset = y * width;
            for (var x = 0; x < width; x++) {
                var value = plane[offset + x];
                if (bytes == 1) {
                    row[x] = (byte) value;
                } else {
                    row[2 * x] = (byte) value;
                    row[2 * x + 1] = (byte) (value >> 8);
                }
            }
            digest.update(row);
        }
        return digest.digest();
    }

    /**
     * Computes the CRC of a plane (H.274 8.7.2).
     *
     * @param plane the samples
     * @param width the plane width, also its stride
     * @param height the plane height
     * @param depth the bit depth
     * @return the 16-bit CRC
     */
    private static int crc(short[] plane, int width, int height, int depth) {
        var crc = 0xFFFF;
        var bytes = depth > 8 ? 2 : 1;
        for (var i = 0; i < width * height + (bytes == 1 ? 2 : 1); i++) {
            for (var b = 0; b < bytes; b++) {
                var dataByte = 0;
                if (i < width * height) {
                    var value = plane[i] & 0xFFFF;
                    dataByte = b == 0 ? value & 0xFF : value >> 8;
                }
                for (var bit = 0; bit < 8; bit++) {
                    var msb = (crc >> 15) & 1;
                    var bitVal = (dataByte >> (7 - bit)) & 1;
                    crc = (((crc << 1) + bitVal) & 0xFFFF) ^ (msb * 0x1021);
                }
            }
        }
        return crc;
    }

    /**
     * Computes the checksum of a plane (H.274 8.7.2).
     *
     * @param plane the samples
     * @param width the plane width, also its stride
     * @param height the plane height
     * @param depth the bit depth
     * @return the 32-bit checksum
     */
    private static long checksum(short[] plane, int width, int height, int depth) {
        long sum = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var mask = (x & 0xFF) ^ (y & 0xFF) ^ (x >> 8) ^ (y >> 8);
                var value = plane[y * width + x] & 0xFFFF;
                sum = (sum + ((value & 0xFF) ^ mask)) & 0xFFFFFFFFL;
                if (depth > 8) {
                    sum = (sum + ((value >> 8) ^ mask)) & 0xFFFFFFFFL;
                }
            }
        }
        return sum;
    }
}
