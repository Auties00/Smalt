package com.github.auties00.smalt.internal.asf;

import java.util.Arrays;

/**
 * The GUIDs of the Advanced Systems Format (ASF) objects, stream types and payload extension systems, in the byte
 * order a file stores them: the first three fields little-endian, the last eight bytes as written.
 */
public final class AsfGuid {
    /**
     * The Header Object.
     */
    public static final byte[] HEADER = of("75B22630-668E-11CF-A6D9-00AA0062CE6C");

    /**
     * The Data Object.
     */
    public static final byte[] DATA = of("75B22636-668E-11CF-A6D9-00AA0062CE6C");

    /**
     * The Simple Index Object.
     */
    public static final byte[] SIMPLE_INDEX = of("33000890-E5B1-11CF-89F4-00A0C90349CB");

    /**
     * The Index Object.
     */
    public static final byte[] INDEX = of("D6E229D3-35DA-11D1-9034-00A0C90349BE");

    /**
     * The File Properties Object.
     */
    public static final byte[] FILE_PROPERTIES = of("8CABDCA1-A947-11CF-8EE4-00C00C205365");

    /**
     * The Stream Properties Object.
     */
    public static final byte[] STREAM_PROPERTIES = of("B7DC0791-A9B7-11CF-8EE6-00C00C205365");

    /**
     * The Header Extension Object.
     */
    public static final byte[] HEADER_EXTENSION = of("5FBF03B5-A92E-11CF-8EE3-00C00C205365");

    /**
     * The Codec List Object.
     */
    public static final byte[] CODEC_LIST = of("86D15240-311D-11D0-A3A4-00A0C90348F6");

    /**
     * The Script Command Object.
     */
    public static final byte[] SCRIPT_COMMAND = of("1EFB1A30-0B62-11D0-A39B-00A0C90348F6");

    /**
     * The Marker Object.
     */
    public static final byte[] MARKER = of("F487CD01-A951-11CF-8EE6-00C00C205365");

    /**
     * The Content Description Object.
     */
    public static final byte[] CONTENT_DESCRIPTION = of("75B22633-668E-11CF-A6D9-00AA0062CE6C");

    /**
     * The Extended Content Description Object.
     */
    public static final byte[] EXTENDED_CONTENT_DESCRIPTION = of("D2D0A440-E307-11D2-97F0-00A0C95EA850");

    /**
     * The Content Encryption Object (DRM version 1).
     */
    public static final byte[] CONTENT_ENCRYPTION = of("2211B3FB-BD23-11D2-B4B7-00A0C955FC6E");

    /**
     * The Extended Content Encryption Object (DRM version 7).
     */
    public static final byte[] EXTENDED_CONTENT_ENCRYPTION = of("298AE614-2622-4C17-B935-DAE07EE9289C");

    /**
     * The Advanced Content Encryption Object (PlayReady), a Header Extension child.
     */
    public static final byte[] ADVANCED_CONTENT_ENCRYPTION = of("43058533-6981-49E6-9B74-AD12CB86D58C");

    /**
     * The Padding Object.
     */
    public static final byte[] PADDING = of("1806D474-CADF-4509-A4BA-9AABCB96AAE8");

    /**
     * The reserved field of the Header Extension Object.
     */
    public static final byte[] RESERVED_1 = of("ABD3D211-A9BA-11CF-8EE6-00C00C205365");

    /**
     * The reserved field of the Codec List Object.
     */
    public static final byte[] RESERVED_2 = of("86D15241-311D-11D0-A3A4-00A0C90348F6");

    /**
     * The reserved field of the Script Command Object.
     */
    public static final byte[] RESERVED_3 = of("4B1ACBE3-100B-11D0-A39B-00A0C90348F6");

    /**
     * The reserved field of the Marker Object.
     */
    public static final byte[] RESERVED_4 = of("4CFEDB20-75F6-11CF-9C0F-00A0C90349CB");

    /**
     * The Extended Stream Properties Object, a Header Extension child.
     */
    public static final byte[] EXTENDED_STREAM_PROPERTIES = of("14E6A5CB-C672-4332-8399-A96952065B5A");

    /**
     * The Language List Object, a Header Extension child.
     */
    public static final byte[] LANGUAGE_LIST = of("7C4346A9-EFE0-4BFC-B229-393EDE415C85");

    /**
     * The Metadata Object, a Header Extension child.
     */
    public static final byte[] METADATA = of("C5F8CBEA-5BAF-4877-8467-AA8C44FA4CCA");

    /**
     * The Metadata Library Object, a Header Extension child.
     */
    public static final byte[] METADATA_LIBRARY = of("44231C94-9498-49D1-A141-1D134E457054");

    /**
     * The audio media stream type.
     */
    public static final byte[] AUDIO_MEDIA = of("F8699E40-5B4D-11CF-A8FD-00805F5C442B");

    /**
     * The video media stream type.
     */
    public static final byte[] VIDEO_MEDIA = of("BC19EFC0-5B4D-11CF-A8FD-00805F5C442B");

    /**
     * The command media stream type: script commands carried as a stream.
     */
    public static final byte[] COMMAND_MEDIA = of("59DACFC0-59E6-11D0-A3AC-00A0C90348F6");

    /**
     * The JFIF (JPEG) media stream type.
     */
    public static final byte[] JFIF_MEDIA = of("B61BE100-5B4E-11CF-A8FD-00805F5C442B");

    /**
     * The degradable JPEG media stream type.
     */
    public static final byte[] DEGRADABLE_JPEG_MEDIA = of("35907DE0-E415-11CF-A917-00805F5C442B");

    /**
     * The audio spread error correction type, under which audio payloads may be interleaved.
     */
    public static final byte[] AUDIO_SPREAD = of("BFC3CD50-618F-11CF-8BB2-00AA00B4E220");

    /**
     * The sample duration payload extension system: a 16-bit duration in milliseconds.
     */
    public static final byte[] SAMPLE_DURATION_EXTENSION = of("C6BD9450-867F-4907-83A3-C77921B733AD");

    /**
     * The pixel aspect ratio payload extension system: one byte each for the horizontal and vertical terms.
     */
    public static final byte[] PIXEL_ASPECT_RATIO_EXTENSION = of("1B1EE554-F9EA-4BC8-821A-376B74E4C4B8");

    private AsfGuid() {
        throw new AssertionError();
    }

    /**
     * Returns the stored bytes of a GUID written in its registry form.
     *
     * @param text the GUID, such as {@code "75B22630-668E-11CF-A6D9-00AA0062CE6C"}
     * @return the 16 bytes in file order
     */
    public static byte[] of(String text) {
        var hex = text.replace("-", "");
        var canonical = new byte[16];
        for (var i = 0; i < 16; i++) {
            canonical[i] = (byte) Integer.parseInt(hex, 2 * i, 2 * i + 2, 16);
        }
        var result = canonical.clone();
        for (var i = 0; i < 4; i++) {
            result[i] = canonical[3 - i];
        }
        result[4] = canonical[5];
        result[5] = canonical[4];
        result[6] = canonical[7];
        result[7] = canonical[6];
        return result;
    }

    /**
     * Returns whether the 16 bytes at an offset are a GUID.
     *
     * @param data the bytes, at least {@code offset + 16} long
     * @param offset the index of the first GUID byte
     * @param guid the GUID in file order
     * @return {@code true} when the bytes equal it
     */
    public static boolean is(byte[] data, int offset, byte[] guid) {
        return Arrays.equals(data, offset, offset + 16, guid, 0, 16);
    }
}
