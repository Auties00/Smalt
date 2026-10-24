package com.github.auties00.smalt.container.mp4;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Builds and checks the {@code stpp} sample entry body and the documents of a TTML track
 * (ISO/IEC 14496-30 clause 6), in the form {@link com.github.auties00.smalt.track.Codec#TTML}
 * describes.
 */
final class TtmlEntries {
    /**
     * The TTML namespace, written when a track has no configuration.
     */
    static final String NAMESPACE = "http://www.w3.org/ns/ttml";

    /**
     * The document of a sample that shows nothing, written where the packets leave a gap.
     */
    private static final byte[] EMPTY_DOCUMENT = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<tt xmlns=\"" + NAMESPACE + "\" xml:lang=\"\"/>\n").getBytes(StandardCharsets.UTF_8);

    private TtmlEntries() {
        throw new AssertionError();
    }

    /**
     * {@return the configuration of a track that declares none: the TTML namespace and empty
     * schema location and auxiliary MIME types, a new array}
     */
    static byte[] defaultConfig() {
        var namespace = NAMESPACE.getBytes(StandardCharsets.US_ASCII);
        var config = new byte[namespace.length + 3];
        System.arraycopy(namespace, 0, config, 0, namespace.length);
        return config;
    }

    /**
     * {@return the document of an empty sample, a new array}
     */
    static byte[] emptyDocument() {
        return EMPTY_DOCUMENT.clone();
    }

    /**
     * Checks a configuration: three null-terminated UTF-8 strings, the first not empty, followed
     * by whole boxes.
     *
     * @param config the configuration
     * @throws IllegalArgumentException if it is malformed
     */
    static void validate(byte[] config) {
        var problem = problem(config);
        if (problem != null) {
            throw new IllegalArgumentException("the TTML configuration " + problem);
        }
    }

    /**
     * {@return what makes a configuration malformed, or {@code null} when it is well formed}
     *
     * @param config the configuration
     */
    static String problem(byte[] config) {
        var position = 0;
        for (var field = 0; field < 3; field++) {
            var end = position;
            while (end < config.length && config[end] != 0) {
                end++;
            }
            if (end == config.length) {
                return "lacks the terminator of its " + fieldName(field) + " field";
            }
            if (field == 0 && end == position) {
                return "has an empty namespace";
            }
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(config, position, end - position));
            } catch (CharacterCodingException exception) {
                return "has a " + fieldName(field) + " field that is not UTF-8";
            }
            position = end + 1;
        }
        while (position < config.length) {
            if (config.length - position < 8) {
                return "ends with a truncated box header";
            }
            var size = readU32(config, position);
            if (size == 1) {
                if (config.length - position < 16) {
                    return "ends with a truncated box header";
                }
                size = (readU32(config, position + 8) << 32) | readU32(config, position + 12);
                if (size < 16) {
                    return "holds a box of size " + size;
                }
            } else if (size == 0) {
                size = config.length - position;
            } else if (size < 8) {
                return "holds a box of size " + size;
            }
            if (size > config.length - position) {
                return "holds a box that overruns it";
            }
            position += (int) size;
        }
        return null;
    }

    /**
     * {@return the name of a configuration field}
     *
     * @param field the zero-based field index
     */
    private static String fieldName(int field) {
        return switch (field) {
            case 0 -> "namespace";
            case 1 -> "schema_location";
            default -> "auxiliary_mime_types";
        };
    }

    /**
     * {@return the unsigned big-endian 32-bit value at an index}
     *
     * @param data the bytes
     * @param position the index of the first byte
     */
    private static long readU32(byte[] data, int position) {
        return ((data[position] & 0xFFL) << 24) | ((data[position + 1] & 0xFF) << 16)
                | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
    }
}
