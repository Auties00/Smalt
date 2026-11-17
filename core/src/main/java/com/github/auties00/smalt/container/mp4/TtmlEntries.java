package com.github.auties00.smalt.container.mp4;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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

    /**
     * The eight-byte signature that starts a PNG image.
     */
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * The type of the PNG chunk that ends an image, {@code IEND}.
     */
    private static final long IEND = 0x49454E44L;

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
     * Returns the sub-samples of a TTML sample that carries image resources after its document (ISO/IEC 14496-30
     * clause 6.4, the IMSC image profile): the document, then each resource, which the document references as
     * {@code urn:mpeg:14496-30:subs:N} with {@code N} counting resources from 1.
     *
     * <p>The document ends after its root element and the whitespace, comments and processing instructions that
     * follow it. The bytes after it are split into PNG images, each running from its signature to its
     * {@code IEND} chunk; bytes that do not form a whole PNG image are one last resource.
     *
     * @param data the array holding the sample
     * @param offset the index of the first sample byte
     * @param length the sample size
     * @return the sizes of the sub-samples, the document first, or {@code null} when the sample is the document
     * alone (zero bytes after it included), its root element does not end or it would have more than 65535
     * sub-samples
     */
    static int[] subsamples(byte[] data, int offset, int length) {
        var end = documentEnd(data, offset, offset + length);
        if (end < 0 || zeros(data, end, offset + length)) {
            return null;
        }
        var sizes = new int[8];
        var count = 0;
        sizes[count++] = end - offset;
        var position = end;
        while (position < offset + length) {
            var image = pngEnd(data, position, offset + length);
            var next = image > 0 ? image : offset + length;
            if (count == sizes.length) {
                sizes = Arrays.copyOf(sizes, count * 2);
            }
            sizes[count++] = next - position;
            position = next;
        }
        return count > 0xFFFF ? null : Arrays.copyOf(sizes, count);
    }

    /**
     * Writes a sub-sample information box ({@code subs}, ISO/IEC 14496-12 clause 8.7.7) listing the sub-samples of
     * some samples of a sample table or a track fragment. Its version is 1, with 32-bit sizes, when a size needs
     * more than 16 bits, and 0 otherwise; priorities, discardable flags and codec-specific parameters are zero.
     *
     * @param writer the destination, inside the {@code stbl} or {@code traf}
     * @param samples the zero-based sample numbers, ascending, relative to the first sample the box describes
     * @param sizes the sub-sample sizes of each sample of {@code samples}
     * @param count the number of samples listed
     */
    static void writeSubsampleBox(BoxWriter writer, int[] samples, int[][] sizes, int count) {
        var wide = false;
        for (var entry = 0; entry < count; entry++) {
            for (var size : sizes[entry]) {
                wide |= size > 0xFFFF;
            }
        }
        writer.beginFull(BoxTypes.SUBS, wide ? 1 : 0, 0);
        writer.u32(count);
        var previous = -1;
        for (var entry = 0; entry < count; entry++) {
            writer.u32(samples[entry] - previous);
            previous = samples[entry];
            writer.u16(sizes[entry].length);
            for (var size : sizes[entry]) {
                if (wide) {
                    writer.u32(size);
                } else {
                    writer.u16(size);
                }
                writer.u8(0);
                writer.u8(0);
                writer.u32(0);
            }
        }
        writer.end();
    }

    /**
     * Returns the end of the XML document at the start of a sample, after its root element and the
     * miscellaneous markup and whitespace that follow it.
     *
     * <p>The document is read in UTF-16 when it starts with a UTF-16 byte order mark or a {@code <} in UTF-16, and
     * in an ASCII-compatible encoding otherwise; only its markup characters, all ASCII, are interpreted. A control
     * character that XML does not allow (any below U+0020 but tab, line feed and carriage return, such as the
     * {@code 1A} of a PNG signature) before the root element ends means the document is cut short.
     *
     * @param data the array holding the sample
     * @param start the index of the first sample byte
     * @param limit the index after the last sample byte
     * @return the index after the document, or {@code -1} when its root element does not end
     */
    static int documentEnd(byte[] data, int start, int limit) {
        var unit = 1;
        var high = 0;
        if (limit - start >= 2) {
            var first = data[start] & 0xFF;
            var second = data[start + 1] & 0xFF;
            if ((first == 0xFE && second == 0xFF) || (first == 0 && second == '<')) {
                unit = 2;
            } else if ((first == 0xFF && second == 0xFE) || (first == '<' && second == 0)) {
                unit = 2;
                high = 1;
            }
        }
        var text = new XmlUnits(data, start, limit, unit, high);
        if (unit == 2 && text.at(0) == 0xFEFF) {
            text.position = unit;
        } else if (unit == 1 && limit - start >= 3 && (data[start] & 0xFF) == 0xEF && (data[start + 1] & 0xFF) == 0xBB
                && (data[start + 2] & 0xFF) == 0xBF) {
            text.position = 3;
        }
        var depth = 0;
        var rootClosed = false;
        while (text.position < text.length) {
            var character = text.at(text.position);
            if (rootClosed && character != '<' && !whitespace(character)) {
                break;
            }
            if (forbidden(character)) {
                return -1;
            }
            if (character != '<') {
                text.position += unit;
                continue;
            }
            var mark = text.position;
            if (text.startsWith(mark, "<?")) {
                if (!text.skipPast(mark + 2 * unit, "?>")) {
                    return -1;
                }
            } else if (text.startsWith(mark, "<!--")) {
                if (!text.skipPast(mark + 4 * unit, "-->")) {
                    return -1;
                }
            } else if (rootClosed) {
                text.position = mark;
                break;
            } else if (text.startsWith(mark, "<![CDATA[")) {
                if (!text.skipPast(mark + 9 * unit, "]]>")) {
                    return -1;
                }
            } else if (text.startsWith(mark, "<!")) {
                if (!text.skipDeclaration(mark + 2 * unit)) {
                    return -1;
                }
            } else if (text.startsWith(mark, "</")) {
                if (!text.skipTag(mark + 2 * unit)) {
                    return -1;
                }
                depth--;
                rootClosed = depth <= 0;
            } else {
                if (!text.skipTag(mark + unit)) {
                    return -1;
                }
                if (text.at(text.position - 2 * unit) != '/') {
                    depth++;
                } else if (depth == 0) {
                    rootClosed = true;
                }
            }
        }
        if (!rootClosed) {
            return -1;
        }
        var end = Math.min(text.position, text.length);
        return start + end - end % unit;
    }

    /**
     * {@return whether a range holds only zero bytes, as the terminator a writer may put after a document; an empty
     * range does}
     *
     * @param data the array holding the range
     * @param start the index of the first byte
     * @param limit the index after the last byte
     */
    private static boolean zeros(byte[] data, int start, int limit) {
        for (var index = start; index < limit; index++) {
            if (data[index] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the end of a PNG image starting at an index: after its {@code IEND} chunk.
     *
     * @param data the array holding the image
     * @param start the index of the signature
     * @param limit the index after the last byte that may belong to the image
     * @return the index after the image, or {@code -1} when no whole PNG image starts there
     */
    private static int pngEnd(byte[] data, int start, int limit) {
        if (limit - start < PNG_SIGNATURE.length + 12
                || !Arrays.equals(data, start, start + PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            return -1;
        }
        var position = (long) start + PNG_SIGNATURE.length;
        while (position + 12 <= limit) {
            var length = readU32(data, (int) position);
            var type = readU32(data, (int) position + 4);
            var next = position + 12 + length;
            if (next > limit) {
                return -1;
            }
            if (type == IEND) {
                return (int) next;
            }
            position = next;
        }
        return -1;
    }

    /**
     * {@return whether a code unit is a control character an XML document never holds, which ends the markup
     * scan of a document followed by binary resources}
     *
     * @param character the code unit
     */
    private static boolean forbidden(int character) {
        return character >= 0 && character < 0x20 && !whitespace(character);
    }

    /**
     * {@return whether a character is XML whitespace}
     *
     * @param character the code unit to classify
     */
    private static boolean whitespace(int character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\r';
    }

    /**
     * Reads the code units of an XML document stored in one- or two-byte units.
     */
    private static final class XmlUnits {
        /**
         * The array holding the document.
         */
        private final byte[] data;

        /**
         * The index of the first document byte.
         */
        private final int start;

        /**
         * The number of document bytes.
         */
        final int length;

        /**
         * The size of a code unit in bytes, 1 or 2.
         */
        private final int unit;

        /**
         * The index of the high byte within a two-byte unit, 0 for big-endian.
         */
        private final int high;

        /**
         * The byte offset of the next unit to read, from the start of the document.
         */
        int position;

        XmlUnits(byte[] data, int start, int limit, int unit, int high) {
            this.data = data;
            this.start = start;
            this.length = limit - start;
            this.unit = unit;
            this.high = high;
        }

        /**
         * {@return the code unit at a byte offset, or {@code -1} past the end}
         *
         * @param offset the byte offset from the start of the document
         */
        int at(int offset) {
            if (offset < 0 || offset + unit > length) {
                return -1;
            }
            if (unit == 1) {
                return data[start + offset] & 0xFF;
            }
            return (data[start + offset + high] & 0xFF) << 8 | (data[start + offset + 1 - high] & 0xFF);
        }

        /**
         * {@return whether the units at a byte offset spell an ASCII string}
         *
         * @param offset the byte offset from the start of the document
         * @param text the string
         */
        boolean startsWith(int offset, String text) {
            for (var index = 0; index < text.length(); index++) {
                if (at(offset + index * unit) != text.charAt(index)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Moves past the first occurrence of a terminator at or after a byte offset.
         *
         * @param offset the byte offset to search from
         * @param terminator the ASCII string that ends the construct
         * @return {@code false} when the terminator does not occur
         */
        boolean skipPast(int offset, String terminator) {
            for (var at = offset; at + terminator.length() * unit <= length; at += unit) {
                if (forbidden(at(at))) {
                    return false;
                }
                if (startsWith(at, terminator)) {
                    position = at + terminator.length() * unit;
                    return true;
                }
            }
            return false;
        }

        /**
         * Moves past the {@code >} that ends a tag, skipping the quoted attribute values.
         *
         * @param offset the byte offset after the tag's {@code <} or {@code </}
         * @return {@code false} when the tag does not end
         */
        boolean skipTag(int offset) {
            var quote = -1;
            for (var at = offset; at + unit <= length; at += unit) {
                var character = at(at);
                if (forbidden(character)) {
                    return false;
                }
                if (quote >= 0) {
                    if (character == quote) {
                        quote = -1;
                    }
                } else if (character == '"' || character == '\'') {
                    quote = character;
                } else if (character == '>') {
                    position = at + unit;
                    return true;
                }
            }
            return false;
        }

        /**
         * Moves past a markup declaration such as a document type declaration, its internal subset included.
         *
         * @param offset the byte offset after the declaration's {@code <!}
         * @return {@code false} when the declaration does not end
         */
        boolean skipDeclaration(int offset) {
            var quote = -1;
            var brackets = 0;
            for (var at = offset; at + unit <= length; at += unit) {
                var character = at(at);
                if (forbidden(character)) {
                    return false;
                }
                if (quote >= 0) {
                    if (character == quote) {
                        quote = -1;
                    }
                } else if (character == '"' || character == '\'') {
                    quote = character;
                } else if (character == '[') {
                    brackets++;
                } else if (character == ']') {
                    brackets--;
                } else if (character == '>' && brackets <= 0) {
                    position = at + unit;
                    return true;
                }
            }
            return false;
        }
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
