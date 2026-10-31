package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes and decodes D-Bus messages: a {@code Hello} call against bytes laid out by hand from the
 * specification's alignment rules, round trips of every type the portal uses, the portal's
 * {@code Start} response, bus addresses, and truncated and corrupted messages, which may only raise
 * {@link MediaException.Malformed}.
 */
@DisplayName("D-Bus messages")
class DBusMessageTest {
    /** Writes a little-endian 32-bit value. */
    private static void u32(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    /** Pads a stream with zeros to an alignment. */
    private static void pad(ByteArrayOutputStream out, int alignment) {
        while (out.size() % alignment != 0) {
            out.write(0);
        }
    }

    /** Writes one header field holding a string or object path. */
    private static void field(ByteArrayOutputStream out, int code, char type, String value) {
        pad(out, 8);
        out.write(code);
        out.write(1);
        out.write(type);
        out.write(0);
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        u32(out, bytes.length);
        out.writeBytes(bytes);
        out.write(0);
    }

    private static DBusMessage roundTrip(DBusMessage message, int... fds) {
        var bytes = message.encode(7);
        assertEquals(bytes.length, DBusMessage.length(bytes, 0, bytes.length));
        return DBusMessage.decode(bytes, 0, bytes.length, count -> Arrays.copyOf(fds, count));
    }

    @Test
    @DisplayName("a Hello call has the header fields, alignment and padding the specification prescribes")
    void hello() {
        var expected = new ByteArrayOutputStream();
        expected.writeBytes(new byte[]{'l', 1, 0, 1});
        u32(expected, 0);
        u32(expected, 1);
        u32(expected, 109);
        field(expected, 1, 'o', "/org/freedesktop/DBus");
        field(expected, 2, 's', "org.freedesktop.DBus");
        field(expected, 3, 's', "Hello");
        field(expected, 6, 's', "org.freedesktop.DBus");
        pad(expected, 8);
        var call = DBusMessage.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello", "");
        assertArrayEquals(expected.toByteArray(), call.encode(1));
        assertEquals(128, expected.size());
    }

    @Test
    @DisplayName("every type the portal uses survives a round trip, descriptors included")
    void types() {
        var options = new LinkedHashMap<String, DBusMessage.Variant>();
        options.put("handle_token", new DBusMessage.Variant("s", "smalt1"));
        options.put("types", new DBusMessage.Variant("u", 1L));
        options.put("multiple", new DBusMessage.Variant("b", false));
        options.put("size", new DBusMessage.Variant("(ii)", List.of(1920, 1080)));
        options.put("nested", new DBusMessage.Variant("v", new DBusMessage.Variant("x", -5L)));
        options.put("empty", new DBusMessage.Variant("a(ii)", List.of()));
        var call = DBusMessage.call("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
                "org.freedesktop.portal.ScreenCast", "SelectSources", "oa{sv}ynqitdgh",
                "/org/freedesktop/portal/desktop/session/1_2/t", options, (byte) -3, (short) -2, 65_535, -7,
                Long.MIN_VALUE, 1.5, "a{sv}", 0);
        assertEquals(1, call.descriptors());
        var decoded = roundTrip(call, 42);
        assertEquals(call.signature(), decoded.signature());
        assertEquals("SelectSources", decoded.member());
        assertEquals(7, decoded.serial());
        var body = new ArrayList<>(call.body());
        body.set(body.size() - 1, 42);
        assertEquals(body, decoded.body());
        assertEquals(options, decoded.body().get(1));
    }

    @Test
    @DisplayName("an unsigned 32-bit value keeps its high bit")
    void unsigned() {
        var decoded = roundTrip(DBusMessage.call("a.b", "/a", "a.b", "m", "u", 0xFFFF_FFF0L));
        assertEquals(0xFFFF_FFF0L, decoded.body().getFirst());
    }

    @Test
    @DisplayName("the portal's Start response yields its streams with their position and size")
    void startResponse() {
        var properties = new LinkedHashMap<String, DBusMessage.Variant>();
        properties.put("position", new DBusMessage.Variant("(ii)", List.of(1920, 0)));
        properties.put("size", new DBusMessage.Variant("(ii)", List.of(1280, 720)));
        properties.put("source_type", new DBusMessage.Variant("u", 1L));
        var results = new LinkedHashMap<String, DBusMessage.Variant>();
        results.put("streams", new DBusMessage.Variant("a(ua{sv})", List.of(List.of(57L, properties))));
        results.put("restore_token", new DBusMessage.Variant("s", "x"));
        var signal = new DBusMessage(DBusMessage.SIGNAL, 0, 0, "/org/freedesktop/portal/desktop/request/1_2/t",
                "org.freedesktop.portal.Request", "Response", null, 0, null, ":1.5", "ua{sv}", List.of(0L, results));
        var decoded = roundTrip(signal);
        assertEquals(DBusMessage.SIGNAL, decoded.type());
        assertEquals(":1.5", decoded.sender());
        var streams = ScreenCastPortal.streams((Map<?, ?>) decoded.body().get(1));
        assertEquals(List.of(new ScreenCastPortal.Stream(57, 1920, 0, 1280, 720, 1)), streams);
        assertEquals(List.of(), ScreenCastPortal.streams(Map.of()));
    }

    @Test
    @DisplayName("signatures are validated: incomplete, unclosed, empty and misplaced types are malformed")
    void signatures() {
        for (var signature : List.of("a", "(i", "()", "{sv}", "a{vs}", "a{s}", "a{sva}", "z", "(ii")) {
            assertThrows(MediaException.Malformed.class, () -> DBusMessage.validateSignature(signature), signature);
        }
        DBusMessage.validateSignature("a{sv}a(ua{sv})(ii)vh");
        var deep = "a".repeat(70) + "i";
        assertThrows(MediaException.Malformed.class, () -> DBusMessage.validateSignature(deep));
    }

    @Test
    @DisplayName("bus addresses yield their Unix socket paths, abstract and escaped ones included")
    void addresses() {
        var paths = DBusConnection.socketPaths("tcp:host=x,port=1;unix:path=/run/user/1000/bus;unix:abstract=/tmp/dbus-a%2cb,guid=12");
        assertEquals(2, paths.size());
        assertArrayEquals("/run/user/1000/bus".getBytes(StandardCharsets.UTF_8), paths.get(0));
        var expected = new byte[]{0, '/', 't', 'm', 'p', '/', 'd', 'b', 'u', 's', '-', 'a', ',', 'b'};
        assertArrayEquals(expected, paths.get(1));
        assertEquals(List.of(), DBusConnection.socketPaths("tcp:host=x"));
    }

    @Test
    @DisplayName("every truncation of a message is incomplete or malformed, never out of bounds")
    void truncated() {
        var bytes = DBusMessage.call("org.freedesktop.portal.Desktop", "/a/b", "c.d", "e", "sa{sv}", "x",
                Map.of("k", new DBusMessage.Variant("(ii)", List.of(1, 2)))).encode(3);
        for (var length = 0; length < bytes.length; length++) {
            var prefix = Arrays.copyOf(bytes, length);
            var announced = length < DBusMessage.FIXED_HEADER ? -1 : DBusMessage.length(prefix, 0, length);
            if (announced < 0) {
                continue;
            }
            assertEquals(bytes.length, announced);
            var end = length;
            assertThrows(MediaException.Malformed.class, () -> DBusMessage.decode(prefix, 0, end, count -> new int[count]));
        }
    }

    @Test
    @DisplayName("corrupted messages decode or raise Malformed, and never anything else")
    void corrupted() {
        var options = new LinkedHashMap<String, DBusMessage.Variant>();
        options.put("streams", new DBusMessage.Variant("a(ua{sv})", List.of(List.of(3L, Map.of("size",
                new DBusMessage.Variant("(ii)", List.of(4, 5)))))));
        var bytes = new DBusMessage(DBusMessage.SIGNAL, 0, 0, "/p", "i.f", "Response", null, 0, null, ":1.1", "ua{sv}h",
                List.of(0L, options, 0)).encode(9);
        var random = new SplittableRandom(11);
        for (var trial = 0; trial < 20_000; trial++) {
            var copy = bytes.clone();
            var flips = 1 + random.nextInt(4);
            for (var flip = 0; flip < flips; flip++) {
                copy[random.nextInt(copy.length)] = (byte) random.nextInt(256);
            }
            try {
                var length = DBusMessage.length(copy, 0, copy.length);
                if (length >= 0 && length <= copy.length) {
                    DBusMessage.decode(copy, 0, length, count -> new int[Math.min(count, 1)]);
                }
            } catch (MediaException.Malformed expected) {
                assertTrue(expected.getMessage() != null);
            }
        }
    }
}
