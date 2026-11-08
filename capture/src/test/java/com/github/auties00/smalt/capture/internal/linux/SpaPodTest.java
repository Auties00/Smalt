package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes the offered video format and reads negotiated ones laid out by hand from the SPA pod
 * definitions (spa/pod/pod.h, spa/param/format.h, spa/param/video/raw.h), and checks malformed pods.
 */
@DisplayName("SPA pods")
class SpaPodTest {
    /** Builds pods by hand, independently of SpaPod's writer. */
    private static final class Pod {
        final ByteBuffer buffer = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
        final List<Integer> starts = new ArrayList<>();

        Pod begin(int type) {
            starts.add(buffer.position());
            buffer.putInt(0).putInt(type);
            return this;
        }

        Pod end() {
            var start = starts.removeLast();
            buffer.putInt(start, buffer.position() - start - 8);
            while (buffer.position() % 8 != 0) {
                buffer.put((byte) 0);
            }
            return this;
        }

        Pod ints(int... values) {
            for (var value : values) {
                buffer.putInt(value);
            }
            return this;
        }

        Pod id(int key, int value) {
            return ints(key, 0, 4, SpaPod.TYPE_ID, value, 0);
        }

        byte[] bytes() {
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }

    private static Pod format(int videoFormat, boolean wrapFormatInChoice) {
        var pod = new Pod().begin(SpaPod.TYPE_OBJECT).ints(SpaPod.OBJECT_FORMAT, SpaPod.PARAM_FORMAT);
        pod.id(SpaPod.KEY_MEDIA_TYPE, SpaPod.MEDIA_VIDEO).id(SpaPod.KEY_MEDIA_SUBTYPE, SpaPod.SUBTYPE_RAW);
        if (wrapFormatInChoice) {
            pod.ints(SpaPod.KEY_VIDEO_FORMAT, 0).begin(SpaPod.TYPE_CHOICE).ints(SpaPod.CHOICE_NONE, 0, 4, SpaPod.TYPE_ID, videoFormat).end();
        } else {
            pod.id(SpaPod.KEY_VIDEO_FORMAT, videoFormat);
        }
        pod.ints(SpaPod.KEY_VIDEO_SIZE, 0, 8, SpaPod.TYPE_RECTANGLE, 1366, 768);
        pod.ints(SpaPod.KEY_VIDEO_FRAMERATE, 0, 8, SpaPod.TYPE_FRACTION, 0, 1);
        return pod;
    }

    @Test
    @DisplayName("a negotiated raw video format is read, plain or wrapped in a choice of one value")
    void read() {
        for (var wrapped : new boolean[]{false, true}) {
            var bytes = format(8, wrapped).end().bytes();
            var parsed = SpaPod.readVideoFormat(bytes);
            assertEquals(new SpaPod.VideoFormat(8, 1366, 768, 0, 1, false), parsed);
            assertEquals(parsed, SpaPod.readVideoFormat(MemorySegment.ofArray(Arrays.copyOf(bytes, bytes.length + 64))));
        }
        var withModifier = format(8, false).ints(SpaPod.KEY_VIDEO_MODIFIER, 0, 8, SpaPod.TYPE_LONG, 0, 0).end().bytes();
        assertTrue(SpaPod.readVideoFormat(withModifier).modifier());
    }

    @Test
    @DisplayName("pods that are not raw video formats are ignored")
    void ignored() {
        var audio = new Pod().begin(SpaPod.TYPE_OBJECT).ints(SpaPod.OBJECT_FORMAT, SpaPod.PARAM_FORMAT)
                .id(SpaPod.KEY_MEDIA_TYPE, 1).id(SpaPod.KEY_MEDIA_SUBTYPE, SpaPod.SUBTYPE_RAW).end().bytes();
        assertNull(SpaPod.readVideoFormat(audio));
        var notObject = new Pod().begin(SpaPod.TYPE_ID).ints(3).end().bytes();
        assertNull(SpaPod.readVideoFormat(notObject));
        var otherObject = new Pod().begin(SpaPod.TYPE_OBJECT).ints(0x40004, SpaPod.PARAM_FORMAT).end().bytes();
        assertNull(SpaPod.readVideoFormat(otherObject));
    }

    @Test
    @DisplayName("the offered format is an EnumFormat object of raw video with every format, a size range and a rate range")
    void offer() {
        var formats = PortalScreenSession.FORMATS;
        var bytes = SpaPod.videoEnumFormat(formats, 1280, 720, 30);
        assertEquals(0, bytes.length % 8);
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        assertEquals(bytes.length - 8, buffer.getInt());
        assertEquals(SpaPod.TYPE_OBJECT, buffer.getInt());
        assertEquals(SpaPod.OBJECT_FORMAT, buffer.getInt());
        assertEquals(SpaPod.PARAM_ENUM_FORMAT, buffer.getInt());
        var keys = new ArrayList<Integer>();
        while (buffer.hasRemaining()) {
            var key = buffer.getInt();
            keys.add(key);
            buffer.getInt();
            var size = buffer.getInt();
            var type = buffer.getInt();
            var body = buffer.position();
            switch (key) {
                case SpaPod.KEY_MEDIA_TYPE -> assertEquals(List.of(SpaPod.TYPE_ID, SpaPod.MEDIA_VIDEO), List.of(type, buffer.getInt(body)));
                case SpaPod.KEY_MEDIA_SUBTYPE -> assertEquals(List.of(SpaPod.TYPE_ID, SpaPod.SUBTYPE_RAW), List.of(type, buffer.getInt(body)));
                case SpaPod.KEY_VIDEO_FORMAT -> {
                    assertEquals(SpaPod.TYPE_CHOICE, type);
                    assertEquals(SpaPod.CHOICE_ENUM, buffer.getInt(body));
                    assertEquals(4, buffer.getInt(body + 8));
                    assertEquals(SpaPod.TYPE_ID, buffer.getInt(body + 12));
                    assertEquals(16 + 4 * (formats.size() + 1), size);
                    var values = new ArrayList<Integer>();
                    for (var index = 0; index <= formats.size(); index++) {
                        values.add(buffer.getInt(body + 16 + 4 * index));
                    }
                    var expected = new ArrayList<>(formats);
                    expected.addFirst(formats.getFirst());
                    assertEquals(expected, values, "the default, then the alternatives");
                }
                case SpaPod.KEY_VIDEO_SIZE -> {
                    assertEquals(SpaPod.CHOICE_RANGE, buffer.getInt(body));
                    assertEquals(SpaPod.TYPE_RECTANGLE, buffer.getInt(body + 12));
                    assertEquals(List.of(1280, 720, 1, 1, SpaPod.MAX_SIDE, SpaPod.MAX_SIDE), List.of(buffer.getInt(body + 16),
                            buffer.getInt(body + 20), buffer.getInt(body + 24), buffer.getInt(body + 28), buffer.getInt(body + 32),
                            buffer.getInt(body + 36)));
                }
                case SpaPod.KEY_VIDEO_FRAMERATE -> {
                    assertEquals(SpaPod.CHOICE_RANGE, buffer.getInt(body));
                    assertEquals(SpaPod.TYPE_FRACTION, buffer.getInt(body + 12));
                    assertEquals(List.of(30, 1, 0, 1, SpaPod.MAX_RATE, 1), List.of(buffer.getInt(body + 16), buffer.getInt(body + 20),
                            buffer.getInt(body + 24), buffer.getInt(body + 28), buffer.getInt(body + 32), buffer.getInt(body + 36)));
                }
                default -> throw new AssertionError("unexpected key " + key);
            }
            buffer.position(body + ((size + 7) & ~7));
        }
        assertEquals(List.of(SpaPod.KEY_MEDIA_TYPE, SpaPod.KEY_MEDIA_SUBTYPE, SpaPod.KEY_VIDEO_FORMAT, SpaPod.KEY_VIDEO_SIZE,
                SpaPod.KEY_VIDEO_FRAMERATE), keys);
        assertNull(SpaPod.readVideoFormat(bytes), "an offer of choices is not a negotiated format");
    }

    @Test
    @DisplayName("every offered format maps to the byte order spa/param/video/raw.h gives it")
    void layouts() {
        assertEquals(RgbLayout.BGRX, PortalScreenSession.layout(8));
        assertEquals(RgbLayout.BGRX, PortalScreenSession.layout(12));
        assertEquals(new RgbLayout(0, 1, 2), PortalScreenSession.layout(7));
        assertEquals(new RgbLayout(0, 1, 2), PortalScreenSession.layout(11));
        assertEquals(new RgbLayout(1, 2, 3), PortalScreenSession.layout(9));
        assertEquals(new RgbLayout(1, 2, 3), PortalScreenSession.layout(13));
        assertEquals(new RgbLayout(3, 2, 1), PortalScreenSession.layout(10));
        assertEquals(new RgbLayout(3, 2, 1), PortalScreenSession.layout(14));
        assertNull(PortalScreenSession.layout(2), "I420 is not converted");
        for (var format : PortalScreenSession.FORMATS) {
            assertTrue(PortalScreenSession.layout(format) != null, "offered format " + format);
        }
    }

    @Test
    @DisplayName("sizes leaving the pod raise Malformed, and random corruption raises nothing else")
    void malformed() {
        var bytes = format(8, true).end().bytes();
        var oversized = bytes.clone();
        ByteBuffer.wrap(oversized).order(ByteOrder.nativeOrder()).putInt(0, bytes.length);
        assertThrows(MediaException.Malformed.class, () -> SpaPod.readVideoFormat(oversized));
        var property = bytes.clone();
        ByteBuffer.wrap(property).order(ByteOrder.nativeOrder()).putInt(16 + 8, 4096);
        assertThrows(MediaException.Malformed.class, () -> SpaPod.readVideoFormat(property));
        assertThrows(MediaException.Malformed.class, () -> SpaPod.readVideoFormat(new byte[4]));
        var shortObject = new Pod().begin(SpaPod.TYPE_OBJECT).ints(SpaPod.OBJECT_FORMAT).end().bytes();
        assertThrows(MediaException.Malformed.class, () -> SpaPod.readVideoFormat(shortObject));
        var random = new SplittableRandom(5);
        for (var trial = 0; trial < 20_000; trial++) {
            var copy = bytes.clone();
            copy[random.nextInt(copy.length)] = (byte) random.nextInt(256);
            try {
                SpaPod.readVideoFormat(copy);
            } catch (MediaException.Malformed expected) {
                assertTrue(expected.getMessage() != null);
            }
        }
    }
}
