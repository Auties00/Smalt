package com.github.auties00.smalt.internal.subtitle.bitmap;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.testkit.subtitle.BitmapStreams;
import com.github.auties00.smalt.testkit.subtitle.BitmapStreams.DvbPixels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the DVB subtitling decoder on the paths the service-level tests leave out: code strings of another depth
 * than their region (with the default and transmitted map tables), progressive objects in 2- and 4-bit regions,
 * the data identifier byte, ancillary pages, removed regions, character objects, short display definitions, and
 * every segment cut to every shorter length.
 */
@DisplayName("DVB subtitle stream")
class DvbSubtitleStreamTest {
    /**
     * Records what the decoder shows.
     */
    static final class Recorder implements BitmapStream.Sink {
        final List<BitmapStream.Screen> screens = new ArrayList<>();
        int clears;

        @Override
        public void show(long micros, BitmapStream.Screen screen, long deadlineMicros) {
            screens.add(screen);
        }

        @Override
        public void clear(long micros) {
            clears++;
        }
    }

    static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static Recorder decode(int composition, int ancillary, byte[]... packets) {
        var stream = new DvbSubtitleStream(MediaLimits.defaults(), composition, ancillary);
        var recorder = new Recorder();
        for (var index = 0; index < packets.length; index++) {
            stream.decode(packets[index], index * 1_000_000L, recorder);
        }
        return recorder;
    }

    /**
     * Returns the pixels of the first region of the only screen shown.
     */
    static byte[] pixels(Recorder recorder) {
        assertEquals(1, recorder.screens.size());
        return recorder.screens.getFirst().regions().getFirst().picture().pixels();
    }

    static byte[] page(int depth, byte[] top) {
        return join(BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 0, 0}),
                BitmapStreams.dvbRegion(1, 0, false, 8, 2, depth, 0, 0, new int[]{1, 0, 0}),
                BitmapStreams.dvbObject(1, 1, top, new byte[0], false), BitmapStreams.dvbEnd(1));
    }

    @ParameterizedTest(name = "{0}-bit code {1} in a 4-bit region")
    @CsvSource({"2,1", "2,2", "2,3"})
    @DisplayName("maps 2-bit codes into 4-bit regions through the default 2-to-4 table")
    void twoInFour(int bits, int code) {
        var map = new int[]{0x0, 0x7, 0x8, 0xF};
        var expected = pixels(decode(-1, -1, page(2, new DvbPixels().string(4).run4(8, map[code]).end4().endLine()
                .bytes())));
        var actual = pixels(decode(-1, -1, page(2, new DvbPixels().string(2).run2(8, code).end2().endLine().bytes())));
        assertArrayEquals(expected, actual);
    }

    @ParameterizedTest(name = "4-bit code {0} in a 2-bit region")
    @CsvSource({"3,1", "8,2", "9,3", "15,3"})
    @DisplayName("maps 4-bit codes into 2-bit regions by their top bit and the rest")
    void fourInTwo(int code, int mapped) {
        var expected = pixels(decode(-1, -1, page(1, new DvbPixels().string(2).run2(8, mapped).end2().endLine()
                .bytes())));
        var actual = pixels(decode(-1, -1, page(1, new DvbPixels().string(4).run4(8, code).end4().endLine().bytes())));
        assertArrayEquals(expected, actual, "code " + code);
    }

    @ParameterizedTest(name = "8-bit code {0} in a 2-bit region")
    @CsvSource({"16,1", "128,2", "144,3"})
    @DisplayName("maps 8-bit codes into 2-bit regions")
    void eightInTwo(int code, int mapped) {
        var expected = pixels(decode(-1, -1, page(1, new DvbPixels().string(2).run2(8, mapped).end2().endLine()
                .bytes())));
        var actual = pixels(decode(-1, -1, page(1, new DvbPixels().string(8).run8(8, code).end8().endLine().bytes())));
        assertArrayEquals(expected, actual, "code " + code);
    }

    @Test
    @DisplayName("maps 2- and 4-bit codes into 8-bit regions through default and transmitted tables")
    void intoEight() {
        var direct = pixels(decode(-1, -1, page(3, new DvbPixels().string(8).run8(4, 0x88).run8(4, 0x55).end8()
                .endLine().bytes())));
        var mapped = pixels(decode(-1, -1, page(3, new DvbPixels().string(2).run2(4, 2).end2()
                .string(4).run4(4, 5).end4().endLine().bytes())));
        assertArrayEquals(direct, mapped);
        var tables = pixels(decode(-1, -1, page(3, new DvbPixels().map(0x21, 1, 2, 0x88, 4).map(0x22, 0, 1, 2, 3, 4,
                0x55, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15).string(2).run2(4, 2).end2().string(4).run4(4, 5).end4()
                .endLine().bytes())));
        assertArrayEquals(direct, tables);
        var fourTable = pixels(decode(-1, -1, page(2, new DvbPixels().map(0x20, 0, 1, 9, 3).string(2).run2(8, 2).end2()
                .endLine().bytes())));
        var nine = pixels(decode(-1, -1, page(2, new DvbPixels().string(4).run4(8, 9).end4().endLine().bytes())));
        assertArrayEquals(nine, fourTable);
    }

    @Test
    @DisplayName("ignores map tables cut short by the end of the field")
    void shortTables() {
        for (var type : new int[]{0x20, 0x21, 0x22}) {
            var top = new DvbPixels().string(4).run4(8, 3).end4().endLine().bytes();
            var cut = join(top, new byte[]{(byte) type, 1});
            decode(-1, -1, page(2, cut));
        }
    }

    @ParameterizedTest(name = "depth code {0}")
    @CsvSource({"1", "2"})
    @DisplayName("draws progressive objects in 2- and 4-bit regions through the 8-bit mapping")
    void progressiveLowDepth(int depth) {
        var indices = new byte[8 * 2];
        Arrays.fill(indices, (byte) 0x88);
        var progressive = join(BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 0, 0}),
                BitmapStreams.dvbRegion(1, 0, false, 8, 2, depth, 0, 0, new int[]{1, 0, 0}),
                BitmapStreams.dvbProgressive(1, 1, indices, 8, 2, 4), BitmapStreams.dvbEnd(1));
        var coded = page(depth, new DvbPixels().string(8).run8(8, 0x88).end8().endLine().bytes());
        assertArrayEquals(pixels(decode(-1, -1, coded)), pixels(decode(-1, -1, progressive)));
    }

    @Test
    @DisplayName("refuses progressive objects whose zlib data is cut or invalid")
    void progressiveMalformed() {
        var indices = new byte[64 * 16];
        for (var index = 0; index < indices.length; index++) {
            indices[index] = (byte) (index * 37);
        }
        var segment = BitmapStreams.dvbProgressive(1, 1, indices, 64, 16, 0);
        var header = join(BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 0, 0}),
                BitmapStreams.dvbRegion(1, 0, false, 64, 16, 3, 0, 0, new int[]{1, 0, 0}));
        var cut = segment.clone();
        var length = (cut[6 + 7] & 0xFF) << 8 | (cut[6 + 8] & 0xFF);
        var shorter = length / 2;
        cut = Arrays.copyOf(cut, cut.length - (length - shorter));
        cut[6 + 7] = (byte) (shorter >> 8);
        cut[6 + 8] = (byte) shorter;
        cut[4] = (byte) ((cut.length - 6) >> 8);
        cut[5] = (byte) (cut.length - 6);
        var truncated = join(header, cut);
        assertThrows(MediaException.Malformed.class, () -> decode(-1, -1, truncated));
        var garbage = segment.clone();
        for (var index = 6 + 9; index < garbage.length; index++) {
            garbage[index] = (byte) 0xFF;
        }
        assertThrows(MediaException.Malformed.class, () -> decode(-1, -1, join(header, garbage)));
    }

    @Test
    @DisplayName("skips the data identifier and stream identifier bytes of a PES payload")
    void dataIdentifier() {
        var plain = page(2, new DvbPixels().string(4).run4(8, 3).end4().endLine().bytes());
        var wrapped = join(new byte[]{0x20, 0}, plain);
        assertArrayEquals(pixels(decode(-1, -1, plain)), pixels(decode(-1, -1, wrapped)));
        assertTrue(decode(-1, -1, join(new byte[]{0x20, 1}, plain)).screens.isEmpty());
        assertTrue(decode(-1, -1, new byte[]{0x20}).screens.isEmpty());
    }

    @Test
    @DisplayName("takes CLUTs and objects from the ancillary page, and from any page that composes nothing")
    void ancillary() {
        var top = new DvbPixels().string(4).run4(8, 1).end4().endLine().bytes();
        var shared = join(BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 0, 0}),
                BitmapStreams.dvbRegion(1, 0, false, 8, 2, 2, 7, 0, new int[]{1, 0, 0}),
                BitmapStreams.dvbClut(5, 7, new int[]{1, 0x41, 81, 240, 90, 0}),
                BitmapStreams.dvbObject(5, 1, top, new byte[0], false), BitmapStreams.dvbEnd(5), BitmapStreams.dvbEnd(1));
        var withAncillary = pixels(decode(1, 5, shared));
        var withoutAncillary = pixels(decode(1, -1, shared));
        assertArrayEquals(withAncillary, withoutAncillary);
        var other = decode(1, 6, shared);
        assertEquals(1, other.clears + other.screens.size());
    }

    @Test
    @DisplayName("removes a region redefined without a depth or a size, and skips the 8-byte character objects")
    void regions() {
        var top = new DvbPixels().string(4).run4(8, 3).end4().endLine().bytes();
        var removed = join(BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 0, 0}),
                BitmapStreams.dvbRegion(1, 0, false, 8, 2, 2, 0, 0, new int[]{1, 0, 0}),
                BitmapStreams.dvbRegion(1, 0, false, 8, 2, 0, 0, 0),
                BitmapStreams.dvbObject(1, 1, top, new byte[0], false), BitmapStreams.dvbEnd(1));
        var recorder = decode(-1, -1, removed);
        assertTrue(recorder.screens.isEmpty());
        assertEquals(1, recorder.clears);
        var empty = join(BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 0, 0}),
                BitmapStreams.dvbRegion(1, 0, false, 0, 2, 2, 0, 0), BitmapStreams.dvbEnd(1));
        assertTrue(decode(-1, -1, empty).screens.isEmpty());
        var region = BitmapStreams.dvbRegion(1, 0, true, 8, 2, 2, 0, 3);
        var body = Arrays.copyOfRange(region, 6, region.length);
        var characters = join(body, new byte[]{0, 2, 0x40, 0, (byte) 0xF0, 0, 1, 2}, new byte[]{0, 1, 0, 0, (byte) 0xF0, 0});
        var page = join(BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 0, 0}), BitmapStreams.dvbSegment(0x11, 1, characters),
                BitmapStreams.dvbObject(1, 1, new DvbPixels().string(4).run4(4, 5).end4().endLine().bytes(), new byte[0],
                        false), BitmapStreams.dvbEnd(1));
        var filled = pixels(decode(-1, -1, page));
        var reference = pixels(decode(-1, -1, page(2, new DvbPixels().string(4).run4(4, 5).run4(4, 3).end4().endLine()
                .bytes())));
        assertArrayEquals(reference, filled);
    }

    @Test
    @DisplayName("reads display definitions without a window or too short to hold one")
    void displays() {
        var top = new DvbPixels().string(4).run4(8, 3).end4().endLine().bytes();
        var noWindow = decode(-1, -1, join(BitmapStreams.dvbDisplay(1, 1280, 720, null), page(2, top)));
        assertEquals(1280, noWindow.screens.getFirst().width());
        var window = BitmapStreams.dvbDisplay(1, 1920, 1080, new int[]{100, 1819, 50, 1029});
        var shortWindow = Arrays.copyOf(window, window.length - 4);
        shortWindow[5] = (byte) (shortWindow.length - 6);
        var cut = decode(-1, -1, join(BitmapStreams.dvbSegment(0x14, 1, new byte[]{0, 1}), shortWindow, page(2, top)));
        assertEquals(1920, cut.screens.getFirst().width());
        assertEquals(0, cut.screens.getFirst().regions().getFirst().x(), "the window is ignored");
    }

    @Test
    @DisplayName("survives every segment of a display set cut to every shorter length")
    void shrunkSegments() {
        var indices = new byte[16 * 4];
        Arrays.fill(indices, (byte) 7);
        var segments = List.of(
                BitmapStreams.dvbDisplay(1, 1920, 1080, new int[]{100, 1819, 50, 1029}),
                BitmapStreams.dvbPage(1, 5, 2, new int[]{0, 4, 8}, new int[]{1, 20, 30}),
                BitmapStreams.dvbRegion(1, 0, true, 16, 4, 2, 2, 3, new int[]{1, 0, 0}, new int[]{2, 2, 1}),
                BitmapStreams.dvbRegion(1, 1, false, 16, 4, 3, 2, 0, new int[]{3, 0, 0}),
                BitmapStreams.dvbClut(1, 2, new int[]{1, 0x41, 81, 240, 90, 0}, new int[]{2, 0xE0, 50, 60, 70, 80},
                        new int[]{3, 0x41, 0, 0, 0, 0}),
                BitmapStreams.dvbObject(1, 1, new DvbPixels().map(0x20, 0, 1, 2, 3).map(0x21, 1, 2, 3, 4)
                        .map(0x22, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15).string(2).run2(30, 1).run2(13, 2)
                        .end2().string(4).run4(26, 4).end4().endLine().string(8).run8(5, 9).end8().endLine().bytes(),
                        new DvbPixels().string(4).run4(3, 2).run4(9, 0).end4().bytes(), true),
                BitmapStreams.dvbObject(1, 2, new DvbPixels().string(4).run4(1, 1).end4().bytes(), new byte[0], false),
                BitmapStreams.dvbProgressive(1, 3, indices, 16, 4, 2),
                BitmapStreams.dvbEnd(1));
        var whole = join(segments.toArray(new byte[0][]));
        var recorder = decode(-1, -1, whole);
        assertEquals(1, recorder.screens.size());
        for (var index = 0; index < segments.size(); index++) {
            var segment = segments.get(index);
            for (var length = 0; length < segment.length - 6; length++) {
                var shrunk = Arrays.copyOf(segment, 6 + length);
                shrunk[4] = (byte) (length >> 8);
                shrunk[5] = (byte) length;
                var parts = new ArrayList<>(segments);
                parts.set(index, shrunk);
                try {
                    decode(-1, -1, join(parts.toArray(new byte[0][])));
                } catch (MediaException expected) {
                    // a cut progressive object is malformed
                }
            }
            var overlong = segment.clone();
            overlong[4] = (byte) 0x7F;
            var parts = new ArrayList<>(segments);
            parts.set(index, overlong);
            decode(-1, -1, join(parts.toArray(new byte[0][])));
        }
    }
}
