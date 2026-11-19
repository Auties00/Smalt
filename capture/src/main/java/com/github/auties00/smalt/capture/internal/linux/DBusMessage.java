package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * A D-Bus message and its little-endian wire encoding, as the D-Bus specification defines it.
 *
 * <p>The body's values are Java objects chosen by their type code: {@code y} a {@link Byte},
 * {@code b} a {@link Boolean}, {@code n} and {@code q} a {@link Short} and an {@link Integer},
 * {@code i} an {@link Integer}, {@code u} a {@link Long} (unsigned), {@code x} and {@code t} a
 * {@link Long}, {@code d} a {@link Double}, {@code s}, {@code o} and {@code g} a {@link String},
 * {@code h} an {@link Integer} (a received descriptor, or the index of one to send), {@code v} a
 * {@link Variant}, an array a {@link List} except an array of dictionary entries, which is a
 * {@link Map}, and a structure a {@link List} of its fields. Decoding checks every length, alignment
 * and nesting depth against the bytes received, so a malformed message raises
 * {@link MediaException.Malformed} instead of reading out of bounds.
 *
 * @param type the message type: {@link #METHOD_CALL}, {@link #METHOD_RETURN}, {@link #ERROR} or
 * {@link #SIGNAL}
 * @param flags the header flags
 * @param serial the serial, positive once sent
 * @param path the object path, or {@code null}
 * @param iface the interface, or {@code null}
 * @param member the member, or {@code null}
 * @param errorName the error name of an error, or {@code null}
 * @param replySerial the serial a reply answers, or {@code 0}
 * @param destination the destination, or {@code null}
 * @param sender the sender, or {@code null}
 * @param signature the body's signature, empty for no body
 * @param body the body's values
 */
record DBusMessage(int type, int flags, long serial, String path, String iface, String member, String errorName,
                   long replySerial, String destination, String sender, String signature, List<Object> body) {
    /**
     * The method call type.
     */
    static final int METHOD_CALL = 1;

    /**
     * The method return type.
     */
    static final int METHOD_RETURN = 2;

    /**
     * The error type.
     */
    static final int ERROR = 3;

    /**
     * The signal type.
     */
    static final int SIGNAL = 4;

    /**
     * The size of the fixed part of a header.
     */
    static final int FIXED_HEADER = 16;

    /**
     * The largest message the specification allows, 128 MiB.
     */
    static final int MAX_MESSAGE = 1 << 27;

    /**
     * The deepest nesting of arrays and structures the specification allows, 32 each.
     */
    private static final int MAX_DEPTH = 64;

    /**
     * The header field codes.
     */
    private static final int PATH = 1, INTERFACE = 2, MEMBER = 3, ERROR_NAME = 4, REPLY_SERIAL = 5, DESTINATION = 6,
            SENDER = 7, SIGNATURE = 8, UNIX_FDS = 9;

    /**
     * A variant: a value with its own signature.
     *
     * @param signature the value's single complete type
     * @param value the value of that type
     */
    record Variant(String signature, Object value) {
        /**
         * Validates the variant.
         *
         * @throws NullPointerException if a component is {@code null}
         */
        Variant {
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Validates the message.
     *
     * @throws NullPointerException if {@code signature} or {@code body} is {@code null}
     */
    DBusMessage {
        Objects.requireNonNull(signature, "signature");
        body = List.copyOf(body);
    }

    /**
     * Builds a method call.
     *
     * @param destination the bus name
     * @param path the object path
     * @param iface the interface
     * @param member the method
     * @param signature the arguments' signature
     * @param arguments the argument values, in the order of the signature
     * @return the call, without a serial
     */
    static DBusMessage call(String destination, String path, String iface, String member, String signature, Object... arguments) {
        return new DBusMessage(METHOD_CALL, 0, 0, path, iface, member, null, 0, destination, null, signature,
                Arrays.asList(arguments));
    }

    /**
     * Returns the number of descriptors the message carries.
     *
     * @return the count of {@code h} values in the body
     */
    int descriptors() {
        return countDescriptors(signature, body);
    }

    /**
     * Counts the descriptors among values.
     */
    private static int countDescriptors(String signature, List<Object> values) {
        var count = 0;
        var position = 0;
        for (var value : values) {
            var end = typeEnd(signature, position);
            count += countDescriptors(signature.substring(position, end), value);
            position = end;
        }
        return count;
    }

    /**
     * Counts the descriptors of one value.
     */
    private static int countDescriptors(String type, Object value) {
        return switch (type.charAt(0)) {
            case 'h' -> 1;
            case 'v' -> countDescriptors(((Variant) value).signature(), ((Variant) value).value());
            case 'a' -> {
                var element = type.substring(1);
                var count = 0;
                if (element.charAt(0) == '{') {
                    var keyEnd = typeEnd(element, 1);
                    var keyType = element.substring(1, keyEnd);
                    var valueType = element.substring(keyEnd, element.length() - 1);
                    for (var entry : ((Map<?, ?>) value).entrySet()) {
                        count += countDescriptors(keyType, entry.getKey()) + countDescriptors(valueType, entry.getValue());
                    }
                } else {
                    for (var item : (List<?>) value) {
                        count += countDescriptors(element, item);
                    }
                }
                yield count;
            }
            case '(' -> {
                @SuppressWarnings("unchecked")
                var fields = (List<Object>) value;
                yield countDescriptors(type.substring(1, type.length() - 1), fields);
            }
            default -> 0;
        };
    }

    /**
     * Encodes the message.
     *
     * @param serial the serial to send it with, positive
     * @return the wire bytes
     * @throws IllegalArgumentException if a value does not match the signature
     */
    byte[] encode(long serial) {
        var writer = new Writer();
        writer.put((byte) 'l');
        writer.put((byte) type);
        writer.put((byte) flags);
        writer.put((byte) 1);
        writer.putInt(0);
        writer.putInt((int) serial);
        var fields = new ArrayList<Object>();
        addField(fields, PATH, "o", path);
        addField(fields, INTERFACE, "s", iface);
        addField(fields, MEMBER, "s", member);
        addField(fields, ERROR_NAME, "s", errorName);
        if (replySerial != 0) {
            addField(fields, REPLY_SERIAL, "u", replySerial);
        }
        addField(fields, DESTINATION, "s", destination);
        addField(fields, SENDER, "s", sender);
        if (!signature.isEmpty()) {
            addField(fields, SIGNATURE, "g", signature);
        }
        var fds = descriptors();
        if (fds > 0) {
            addField(fields, UNIX_FDS, "u", (long) fds);
        }
        writer.value("a(yv)", fields, 0);
        writer.align(8);
        var bodyStart = writer.size();
        var position = 0;
        for (var value : body) {
            var end = typeEnd(signature, position);
            writer.value(signature.substring(position, end), value, 0);
            position = end;
        }
        if (position != signature.length()) {
            throw new IllegalArgumentException("the body has fewer values than the signature " + signature);
        }
        var bytes = writer.toByteArray();
        putInt(bytes, 4, bytes.length - bodyStart);
        return bytes;
    }

    /**
     * Adds a header field when its value is present.
     */
    private static void addField(List<Object> fields, int code, String type, Object value) {
        if (value != null) {
            fields.add(List.of((byte) code, new Variant(type, value)));
        }
    }

    /**
     * Returns the length of the message that starts a buffer, once its fixed header is there.
     *
     * @param buffer the received bytes
     * @param offset the start of the message
     * @param available the number of bytes received from {@code offset}
     * @return the message's total length, or {@code -1} when fewer than {@value #FIXED_HEADER} bytes
     * are available
     * @throws MediaException.Malformed if the header is not a little-endian D-Bus 1 header or the
     * message is too long
     */
    static int length(byte[] buffer, int offset, int available) {
        if (available < FIXED_HEADER) {
            return -1;
        }
        if (buffer[offset] != 'l' || buffer[offset + 3] != 1) {
            throw new MediaException.Malformed("a D-Bus message is not a little-endian version 1 message");
        }
        var bodyLength = Integer.toUnsignedLong(getInt(buffer, offset + 4));
        var fieldsLength = Integer.toUnsignedLong(getInt(buffer, offset + 12));
        var total = align(FIXED_HEADER + fieldsLength, 8) + bodyLength;
        if (fieldsLength > MAX_MESSAGE || total > MAX_MESSAGE) {
            throw new MediaException.Malformed("a D-Bus message of " + total + " bytes is too long");
        }
        return (int) total;
    }

    /**
     * Decodes a message.
     *
     * @param buffer the bytes holding the message
     * @param offset the start of the message
     * @param length the message's length, from {@link #length}
     * @param fds supplies the descriptors received with the message, given the count its header names
     * @return the message, its {@code h} values replaced by the descriptors
     * @throws MediaException.Malformed if the message is malformed or names a missing descriptor
     */
    static DBusMessage decode(byte[] buffer, int offset, int length, IntFunction<int[]> fds) {
        var reader = new Reader(buffer, offset, length);
        reader.skip(1);
        var type = Byte.toUnsignedInt(reader.get());
        var flags = Byte.toUnsignedInt(reader.get());
        reader.skip(1);
        var bodyLength = Integer.toUnsignedLong(reader.getInt());
        var serial = Integer.toUnsignedLong(reader.getInt());
        @SuppressWarnings("unchecked")
        var fields = (List<Object>) reader.value("a(yv)", 0);
        String path = null;
        String iface = null;
        String member = null;
        String errorName = null;
        String destination = null;
        String sender = null;
        var signature = "";
        var replySerial = 0L;
        var unixFds = 0L;
        for (var field : fields) {
            var pair = (List<?>) field;
            var code = Byte.toUnsignedInt((Byte) pair.get(0));
            var value = ((Variant) pair.get(1)).value();
            switch (code) {
                case PATH -> path = string(value);
                case INTERFACE -> iface = string(value);
                case MEMBER -> member = string(value);
                case ERROR_NAME -> errorName = string(value);
                case REPLY_SERIAL -> replySerial = value instanceof Long number ? number : 0;
                case DESTINATION -> destination = string(value);
                case SENDER -> sender = string(value);
                case SIGNATURE -> signature = string(value);
                case UNIX_FDS -> unixFds = value instanceof Long number ? number : 0;
                default -> {
                }
            }
        }
        reader.align(8);
        if (reader.position() + bodyLength != length) {
            throw new MediaException.Malformed("a D-Bus message body does not end the message");
        }
        validateSignature(signature);
        if (unixFds > 255) {
            throw new MediaException.Malformed("a D-Bus message names " + unixFds + " descriptors");
        }
        reader.fds = fds.apply((int) unixFds);
        var body = new ArrayList<Object>();
        var position = 0;
        while (position < signature.length()) {
            var end = typeEnd(signature, position);
            body.add(reader.value(signature.substring(position, end), 0));
            position = end;
        }
        if (reader.position() != length) {
            throw new MediaException.Malformed("a D-Bus message body is longer than its signature");
        }
        return new DBusMessage(type, flags, serial, path, iface, member, errorName, replySerial, destination, sender,
                signature, body);
    }

    /**
     * Returns a value as a string.
     */
    private static String string(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new MediaException.Malformed("a D-Bus header field has the wrong type");
    }

    /**
     * Checks that a signature is a sequence of complete types.
     *
     * @throws MediaException.Malformed if it is not
     */
    static void validateSignature(String signature) {
        var position = 0;
        while (position < signature.length()) {
            position = typeEnd(signature, position);
        }
    }

    /**
     * Returns the end of the single complete type that starts at a position of a signature.
     *
     * @param signature a sequence of complete types
     * @param start the type's first character
     * @return the index after the type
     * @throws MediaException.Malformed if no complete type starts there
     */
    static int typeEnd(String signature, int start) {
        return typeEnd(signature, start, 0);
    }

    /**
     * Returns the end of a complete type, bounding the nesting.
     */
    private static int typeEnd(String signature, int start, int depth) {
        if (start >= signature.length() || depth > MAX_DEPTH) {
            throw new MediaException.Malformed("the D-Bus signature " + signature + " is incomplete or too deep");
        }
        return switch (signature.charAt(start)) {
            case 'y', 'b', 'n', 'q', 'i', 'u', 'x', 't', 'd', 's', 'o', 'g', 'h', 'v' -> start + 1;
            case 'a' -> typeEnd(signature, start + 1, depth + 1);
            case '(' -> {
                var position = start + 1;
                if (position < signature.length() && signature.charAt(position) == ')') {
                    throw new MediaException.Malformed("the D-Bus signature " + signature + " has an empty structure");
                }
                while (position < signature.length() && signature.charAt(position) != ')') {
                    position = typeEnd(signature, position, depth + 1);
                }
                if (position >= signature.length()) {
                    throw new MediaException.Malformed("the D-Bus signature " + signature + " has an unclosed structure");
                }
                yield position + 1;
            }
            case '{' -> {
                if (start == 0 || signature.charAt(start - 1) != 'a') {
                    throw new MediaException.Malformed("the D-Bus signature " + signature + " has a dictionary entry outside an array");
                }
                var key = typeEnd(signature, start + 1, depth + 1);
                if ("ybnqiuxtdsogh".indexOf(signature.charAt(start + 1)) < 0) {
                    throw new MediaException.Malformed("the D-Bus signature " + signature + " has a dictionary key that is not basic");
                }
                var value = typeEnd(signature, key, depth + 1);
                if (value >= signature.length() || signature.charAt(value) != '}') {
                    throw new MediaException.Malformed("the D-Bus signature " + signature + " has a malformed dictionary entry");
                }
                yield value + 1;
            }
            default -> throw new MediaException.Malformed("the D-Bus signature " + signature + " has an unknown type");
        };
    }

    /**
     * Returns the alignment of a type.
     *
     * @param code the type's first character
     * @return 1, 2, 4 or 8
     */
    static int alignment(char code) {
        return switch (code) {
            case 'y', 'g', 'v' -> 1;
            case 'n', 'q' -> 2;
            case 'b', 'i', 'u', 's', 'o', 'a', 'h' -> 4;
            default -> 8;
        };
    }

    /**
     * Rounds a position up to an alignment.
     */
    private static long align(long position, int alignment) {
        return (position + alignment - 1) & -alignment;
    }

    /**
     * Reads a little-endian 32-bit value.
     */
    private static int getInt(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | (buffer[offset + 1] & 0xFF) << 8 | (buffer[offset + 2] & 0xFF) << 16
                | (buffer[offset + 3] & 0xFF) << 24;
    }

    /**
     * Writes a little-endian 32-bit value.
     */
    private static void putInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
        buffer[offset + 2] = (byte) (value >>> 16);
        buffer[offset + 3] = (byte) (value >>> 24);
    }

    /**
     * Encodes values into a growing buffer.
     */
    private static final class Writer {
        private byte[] bytes = new byte[256];
        private int size;

        int size() {
            return size;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(bytes, size);
        }

        private void ensure(int more) {
            if (size + more > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + more));
            }
        }

        void put(byte value) {
            ensure(1);
            bytes[size++] = value;
        }

        void putInt(int value) {
            ensure(4);
            DBusMessage.putInt(bytes, size, value);
            size += 4;
        }

        void putLong(long value) {
            putInt((int) value);
            putInt((int) (value >>> 32));
        }

        void align(int alignment) {
            while ((size & (alignment - 1)) != 0) {
                put((byte) 0);
            }
        }

        void putString(String text, boolean shortLength) {
            var encoded = text.getBytes(StandardCharsets.UTF_8);
            if (shortLength) {
                if (encoded.length > 255) {
                    throw new IllegalArgumentException("a D-Bus signature longer than 255 bytes");
                }
                put((byte) encoded.length);
            } else {
                putInt(encoded.length);
            }
            ensure(encoded.length + 1);
            System.arraycopy(encoded, 0, bytes, size, encoded.length);
            size += encoded.length;
            bytes[size++] = 0;
        }

        void value(String type, Object value, int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("a D-Bus value nested too deeply");
            }
            var code = type.charAt(0);
            align(alignment(code));
            switch (code) {
                case 'y' -> put((Byte) value);
                case 'b' -> putInt((Boolean) value ? 1 : 0);
                case 'n' -> {
                    ensure(2);
                    var number = (Short) value;
                    bytes[size++] = (byte) (short) number;
                    bytes[size++] = (byte) (number >>> 8);
                }
                case 'q' -> {
                    ensure(2);
                    var number = (Integer) value;
                    bytes[size++] = (byte) (int) number;
                    bytes[size++] = (byte) (number >>> 8);
                }
                case 'i', 'h' -> putInt((Integer) value);
                case 'u' -> putInt((int) (long) (Long) value);
                case 'x', 't' -> putLong((Long) value);
                case 'd' -> putLong(Double.doubleToRawLongBits((Double) value));
                case 's', 'o' -> putString((String) value, false);
                case 'g' -> putString((String) value, true);
                case 'v' -> {
                    var variant = (Variant) value;
                    putString(variant.signature(), true);
                    value(variant.signature(), variant.value(), depth + 1);
                }
                case 'a' -> {
                    var element = type.substring(1);
                    var lengthAt = size;
                    putInt(0);
                    align(alignment(element.charAt(0)));
                    var start = size;
                    if (element.charAt(0) == '{') {
                        var keyEnd = typeEnd(element, 1);
                        var keyType = element.substring(1, keyEnd);
                        var valueType = element.substring(keyEnd, element.length() - 1);
                        for (var entry : ((Map<?, ?>) value).entrySet()) {
                            align(8);
                            value(keyType, entry.getKey(), depth + 1);
                            value(valueType, entry.getValue(), depth + 1);
                        }
                    } else {
                        for (var item : (List<?>) value) {
                            value(element, item, depth + 1);
                        }
                    }
                    DBusMessage.putInt(bytes, lengthAt, size - start);
                }
                case '(' -> {
                    var inner = type.substring(1, type.length() - 1);
                    var fields = (List<?>) value;
                    var position = 0;
                    for (var field : fields) {
                        var end = typeEnd(inner, position);
                        value(inner.substring(position, end), field, depth + 1);
                        position = end;
                    }
                    if (position != inner.length()) {
                        throw new IllegalArgumentException("a structure has fewer fields than " + type);
                    }
                }
                default -> throw new IllegalArgumentException("unsupported D-Bus type " + type);
            }
        }
    }

    /**
     * Decodes values from a received message, checking every bound.
     */
    private static final class Reader {
        private final byte[] buffer;
        private final int start;
        private final int end;
        private int[] fds = new int[0];
        private int position;

        Reader(byte[] buffer, int offset, int length) {
            this.buffer = buffer;
            this.start = offset;
            this.end = offset + length;
            this.position = offset;
        }

        int position() {
            return position - start;
        }

        private void need(long count) {
            if (count < 0 || position + count > end) {
                throw new MediaException.Malformed("a D-Bus message is truncated");
            }
        }

        void skip(int count) {
            need(count);
            position += count;
        }

        byte get() {
            need(1);
            return buffer[position++];
        }

        int getInt() {
            need(4);
            var value = DBusMessage.getInt(buffer, position);
            position += 4;
            return value;
        }

        long getLong() {
            var low = Integer.toUnsignedLong(getInt());
            return low | (long) getInt() << 32;
        }

        void align(int alignment) {
            var aligned = (int) DBusMessage.align(position - start, alignment) + start;
            need(aligned - position);
            while (position < aligned) {
                if (buffer[position++] != 0) {
                    throw new MediaException.Malformed("a D-Bus message has non-zero padding");
                }
            }
        }

        String getString(boolean shortLength) {
            var length = shortLength ? Byte.toUnsignedInt(get()) : Integer.toUnsignedLong(getInt());
            need(length + 1);
            if (buffer[position + (int) length] != 0) {
                throw new MediaException.Malformed("a D-Bus string is not terminated");
            }
            var text = new String(buffer, position, (int) length, StandardCharsets.UTF_8);
            position += (int) length + 1;
            return text;
        }

        Object value(String type, int depth) {
            if (depth > MAX_DEPTH) {
                throw new MediaException.Malformed("a D-Bus value is nested too deeply");
            }
            var code = type.charAt(0);
            align(alignment(code));
            return switch (code) {
                case 'y' -> get();
                case 'b' -> {
                    var value = getInt();
                    if (value != 0 && value != 1) {
                        throw new MediaException.Malformed("a D-Bus boolean is " + value);
                    }
                    yield value == 1;
                }
                case 'n' -> {
                    need(2);
                    var value = (short) ((buffer[position] & 0xFF) | buffer[position + 1] << 8);
                    position += 2;
                    yield value;
                }
                case 'q' -> {
                    need(2);
                    var value = (buffer[position] & 0xFF) | (buffer[position + 1] & 0xFF) << 8;
                    position += 2;
                    yield value;
                }
                case 'i' -> getInt();
                case 'h' -> {
                    var index = getInt();
                    if (index < 0 || index >= fds.length) {
                        throw new MediaException.Malformed("a D-Bus message names descriptor " + index + " of " + fds.length);
                    }
                    yield fds[index];
                }
                case 'u' -> Integer.toUnsignedLong(getInt());
                case 'x', 't' -> getLong();
                case 'd' -> Double.longBitsToDouble(getLong());
                case 's', 'o' -> getString(false);
                case 'g' -> {
                    var signature = getString(true);
                    validateSignature(signature);
                    yield signature;
                }
                case 'v' -> {
                    var signature = getString(true);
                    if (signature.isEmpty() || typeEnd(signature, 0) != signature.length()) {
                        throw new MediaException.Malformed("a D-Bus variant has the signature " + signature);
                    }
                    yield new Variant(signature, value(signature, depth + 1));
                }
                case 'a' -> {
                    var length = Integer.toUnsignedLong(getInt());
                    if (length > 1 << 26) {
                        throw new MediaException.Malformed("a D-Bus array of " + length + " bytes");
                    }
                    var element = type.substring(1);
                    align(alignment(element.charAt(0)));
                    need(length);
                    var limit = position + (int) length;
                    if (element.charAt(0) == '{') {
                        var keyEnd = typeEnd(element, 1);
                        var keyType = element.substring(1, keyEnd);
                        var valueType = element.substring(keyEnd, element.length() - 1);
                        var map = new LinkedHashMap<Object, Object>();
                        while (position < limit) {
                            align(8);
                            var key = value(keyType, depth + 1);
                            map.put(key, value(valueType, depth + 1));
                        }
                        if (position != limit) {
                            throw new MediaException.Malformed("a D-Bus dictionary overruns its length");
                        }
                        yield map;
                    }
                    var list = new ArrayList<Object>();
                    while (position < limit) {
                        list.add(value(element, depth + 1));
                    }
                    if (position != limit) {
                        throw new MediaException.Malformed("a D-Bus array overruns its length");
                    }
                    yield list;
                }
                case '(' -> {
                    var inner = type.substring(1, type.length() - 1);
                    var fields = new ArrayList<Object>();
                    var index = 0;
                    while (index < inner.length()) {
                        var stop = typeEnd(inner, index);
                        fields.add(value(inner.substring(index, stop), depth + 1));
                        index = stop;
                    }
                    yield fields;
                }
                default -> throw new MediaException.Malformed("unsupported D-Bus type " + type);
            };
        }
    }
}
