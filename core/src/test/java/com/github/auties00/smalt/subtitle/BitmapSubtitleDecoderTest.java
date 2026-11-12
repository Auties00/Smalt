package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.subtitle.BitmapStreams;
import com.github.auties00.smalt.testkit.subtitle.BitmapStreams.DvbPixels;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes PGS, DVB and DVD subpicture streams written by the test kit's {@link BitmapStreams} and checks every
 * pixel against the palette the stream defines: colors converted from limited-range Y'CbCr with double precision
 * (within one level of rounding), the DVB default CLUTs computed from ETSI EN 300 743 tables 36 to 38, and the
 * cue timing rules of {@link BitmapSubtitleDecoder}.
 */
@DisplayName("bitmap subtitle decoding")
class BitmapSubtitleDecoderTest {
    private static final Rational TICKS = Rational.timeBaseOf(90_000);

    static DataTrack track(Codec codec, byte[] config) {
        return DataTrack.builder().id(1).codec(codec).timeBase(TICKS).codecConfig(config).build();
    }

    static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] join(List<byte[]> parts) {
        return join(parts.toArray(new byte[0][]));
    }

    static Packet packet(byte[] data, long ticks) {
        return new Packet(1, data, ticks, ticks, Packet.UNKNOWN_DURATION, true);
    }

    static List<BitmapCue> decode(BitmapSubtitleDecoder decoder, List<Packet> packets) {
        var cues = new ArrayList<BitmapCue>();
        for (var packet : packets) {
            cues.addAll(decoder.decode(packet));
        }
        cues.addAll(decoder.flush());
        return cues;
    }

    /**
     * Returns the RGBA of a limited-range Y'CbCr color with double precision.
     */
    static int[] rgb(int y, int cb, int cr, boolean bt709) {
        double kr = bt709 ? 0.2126 : 0.299;
        double kb = bt709 ? 0.0722 : 0.114;
        double luma = (y - 16) * 255.0 / 219;
        double u = (cb - 128) * 255.0 / 224;
        double v = (cr - 128) * 255.0 / 224;
        double r = luma + 2 * (1 - kr) * v;
        double b = luma + 2 * (1 - kb) * u;
        double g = (luma - kr * r - kb * b) / (1 - kr - kb);
        return new int[]{clamp(r), clamp(g), clamp(b)};
    }

    static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    static void assertPixel(RgbaPicture picture, int x, int y, int r, int g, int b, int a) {
        var at = y * picture.stride() + x * 4;
        var pixels = picture.pixels();
        var actual = new int[]{pixels[at] & 0xFF, pixels[at + 1] & 0xFF, pixels[at + 2] & 0xFF, pixels[at + 3] & 0xFF};
        if (a == 0) {
            assertEquals(0, actual[3], "alpha at " + x + "," + y);
            return;
        }
        assertEquals(a, actual[3], "alpha at " + x + "," + y);
        assertTrue(Math.abs(r - actual[0]) <= 1 && Math.abs(g - actual[1]) <= 1 && Math.abs(b - actual[2]) <= 1,
                "color at " + x + "," + y + ": " + List.of(actual[0], actual[1], actual[2]) + " for "
                        + List.of(r, g, b));
    }

    @Nested
    @DisplayName("PGS")
    class Pgs {
        static final int[][] PALETTE = {{1, 235, 128, 128, 255}, {2, 16, 128, 128, 255}, {3, 81, 90, 240, 200},
                {4, 145, 34, 54, 128}};

        static byte[] object(int width, int height) {
            return BitmapStreams.indices(width, height, (x, y) -> x < 70 ? (x + y) % 5 : x % 3 == 0 ? 4 : 0);
        }

        @ParameterizedTest(name = "{0} lines")
        @ValueSource(ints = {480, 1080})
        @DisplayName("decodes every pixel through the palette, BT.601 up to 576 lines and BT.709 above")
        void pixels(int height) {
            var width = 200;
            var indices = object(width, 9);
            var set = join(BitmapStreams.pcs(1920, height, 0, 0x80, false, 0, new int[]{7, 0, 30, 40, 0}),
                    BitmapStreams.wds(new int[]{0, 30, 40, width, 9}), BitmapStreams.pds(0, 0, PALETTE),
                    join(BitmapStreams.ods(7, width, 9, indices, 40)), BitmapStreams.pgsEnd());
            var clear = join(BitmapStreams.pcs(1920, height, 1, 0, false, 0), BitmapStreams.pgsEnd());
            var decoder = new BitmapSubtitleDecoder(track(Codec.PGS, null));
            var cues = decode(decoder, List.of(packet(set, 90_000), packet(clear, 180_000)));
            assertEquals(1, cues.size());
            var cue = cues.getFirst();
            assertEquals(1_000_000, cue.startMicros());
            assertEquals(2_000_000, cue.endMicros());
            assertEquals(1920, cue.canvasWidth());
            assertEquals(height, cue.canvasHeight());
            assertFalse(cue.forced());
            var region = cue.regions().getFirst();
            assertEquals(30, region.x());
            assertEquals(40, region.y());
            for (var y = 0; y < 9; y++) {
                for (var x = 0; x < width; x++) {
                    var index = indices[y * width + x];
                    if (index == 0) {
                        assertPixel(region.picture(), x, y, 0, 0, 0, 0);
                        continue;
                    }
                    var entry = PALETTE[index - 1];
                    var color = rgb(entry[1], entry[3], entry[2], height > 576);
                    assertPixel(region.picture(), x, y, color[0], color[1], color[2], entry[4]);
                }
            }
        }

        @Test
        @DisplayName("redraws on a palette update, crops, marks forced compositions and starts epochs afresh")
        void compositions() {
            var indices = object(80, 4);
            var first = join(BitmapStreams.pcs(720, 480, 0, 0x80, false, 0, new int[]{1, 0, 0, 0, 0}),
                    BitmapStreams.pds(0, 0, PALETTE), join(BitmapStreams.ods(1, 80, 4, indices, 1000)),
                    BitmapStreams.pgsEnd());
            var update = join(BitmapStreams.pcs(720, 480, 1, 0, true, 0, new int[]{1, 0, 0, 0, 0}),
                    BitmapStreams.pds(0, 1, new int[]{1, 81, 90, 240, 255}), BitmapStreams.pgsEnd());
            var cropped = join(BitmapStreams.pcs(720, 480, 2, 0, false, 0,
                    new int[]{1, 0, 100, 200, 0xC0, 10, 1, 20, 2}), BitmapStreams.pgsEnd());
            var epoch = join(BitmapStreams.pcs(720, 480, 3, 0x80, false, 0, new int[]{1, 0, 0, 0, 0}),
                    BitmapStreams.pgsEnd());
            var decoder = new BitmapSubtitleDecoder(track(Codec.PGS, null));
            var cues = decode(decoder, List.of(packet(first, 0), packet(update, 9000), packet(cropped, 18000),
                    packet(epoch, 27000)));
            assertEquals(3, cues.size(), "the new epoch has no object and clears the screen");
            var updated = cues.get(1).regions().getFirst().picture();
            var color = rgb(81, 240, 90, false);
            var x = 0;
            while (indices[x] != 1) {
                x++;
            }
            assertPixel(updated, x, 0, color[0], color[1], color[2], 255);
            var crop = cues.get(2);
            assertTrue(crop.forced());
            var region = crop.regions().getFirst();
            assertEquals(List.of(100, 200, 20, 2), List.of(region.x(), region.y(), region.width(), region.height()));
            var expected = new BitmapCue(0, 0, 720, 480, List.of(new BitmapRegion(0, 0,
                    cues.get(1).regions().getFirst().picture())), false);
            for (var row = 0; row < 2; row++) {
                for (var column = 0; column < 20; column++) {
                    var source = expected.regions().getFirst().picture();
                    var a = (row + 1) * source.stride() + (column + 10) * 4;
                    var b = row * region.picture().stride() + column * 4;
                    for (var channel = 0; channel < 4; channel++) {
                        assertEquals(source.pixels()[a + channel], region.picture().pixels()[b + channel]);
                    }
                }
            }
        }

        @Test
        @DisplayName("assembles an object from fragments spread over packets")
        void fragments() {
            var indices = object(120, 30);
            var segments = BitmapStreams.ods(3, 120, 30, indices, 50);
            assertTrue(segments.size() > 3);
            var packets = new ArrayList<Packet>();
            packets.add(packet(join(BitmapStreams.pcs(720, 480, 0, 0x80, false, 0, new int[]{3, 0, 5, 6, 0}),
                    BitmapStreams.pds(0, 0, PALETTE)), 0));
            for (var segment : segments) {
                packets.add(packet(segment, 0));
            }
            packets.add(packet(BitmapStreams.pgsEnd(), 0));
            var whole = decode(new BitmapSubtitleDecoder(track(Codec.PGS, null)), packets);
            var single = decode(new BitmapSubtitleDecoder(track(Codec.PGS, null)), List.of(packet(join(
                    BitmapStreams.pcs(720, 480, 0, 0x80, false, 0, new int[]{3, 0, 5, 6, 0}),
                    BitmapStreams.pds(0, 0, PALETTE), join(BitmapStreams.ods(3, 120, 30, indices, 100000)),
                    BitmapStreams.pgsEnd()), 0), packet(join(BitmapStreams.pcs(720, 480, 1, 0, false, 0),
                    BitmapStreams.pgsEnd()), 9000)));
            assertEquals(0, whole.size(), "a cue shown at the last packet has no end and is dropped by flush");
            assertEquals(1, single.size());
            var decoder = new BitmapSubtitleDecoder(track(Codec.PGS, null));
            packets.add(packet(join(BitmapStreams.pcs(720, 480, 1, 0, false, 0), BitmapStreams.pgsEnd()), 9000));
            var fragmented = decode(decoder, packets);
            assertEquals(single, fragmented);
        }
    }

    @Nested
    @DisplayName("DVB")
    class Dvb {
        static final byte[] SERVICE = {'e', 'n', 'g', 0x10, 0, 1, 0, 2};

        @Test
        @DisplayName("fills the default CLUTs of tables 36 to 38")
        void defaultCluts() {
            for (var depth : new int[]{1, 2, 3}) {
                var entries = depth == 1 ? 4 : depth == 2 ? 16 : 256;
                var width = entries;
                var pixels = new DvbPixels();
                pixels.string(depth == 1 ? 2 : depth == 2 ? 4 : 8);
                for (var entry = 0; entry < entries; entry++) {
                    if (depth == 1) {
                        pixels.run2(1, entry);
                    } else if (depth == 2) {
                        pixels.run4(1, entry);
                    } else {
                        pixels.run8(1, entry);
                    }
                }
                if (depth == 1) {
                    pixels.end2();
                } else if (depth == 2) {
                    pixels.end4();
                } else {
                    pixels.end8();
                }
                pixels.endLine();
                var data = join(BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 0, 0}),
                        BitmapStreams.dvbRegion(1, 0, false, width, 2, depth, 0, 0, new int[]{5, 0, 0}),
                        BitmapStreams.dvbObject(1, 5, pixels.bytes(), new byte[0], false), BitmapStreams.dvbEnd(1));
                var cues = decode(new BitmapSubtitleDecoder(track(Codec.DVB_SUBTITLE, SERVICE)),
                        List.of(packet(data, 0), packet(join(BitmapStreams.dvbPage(1, 0, 0),
                                BitmapStreams.dvbEnd(1)), 9000)));
                assertEquals(1, cues.size());
                var picture = cues.getFirst().regions().getFirst().picture();
                for (var entry = 0; entry < entries; entry++) {
                    var expected = expectedDefault(depth, entry);
                    for (var row = 0; row < 2; row++) {
                        assertPixel(picture, entry, row, expected[0], expected[1], expected[2], expected[3]);
                    }
                }
            }
        }

        /**
         * Returns the RGBA of a default CLUT entry, from the percentages of ETSI EN 300 743 tables 36 to 38.
         */
        static int[] expectedDefault(int depth, int entry) {
            if (depth == 1) {
                return switch (entry) {
                    case 0 -> new int[]{0, 0, 0, 0};
                    case 1 -> new int[]{255, 255, 255, 255};
                    case 2 -> new int[]{0, 0, 0, 255};
                    default -> new int[]{128, 128, 128, 255};
                };
            }
            if (depth == 2) {
                if (entry == 0) {
                    return new int[]{0, 0, 0, 0};
                }
                var level = (entry & 8) == 0 ? 255 : 128;
                return new int[]{level * (entry & 1), level * (entry >> 1 & 1), level * (entry >> 2 & 1), 255};
            }
            var b = new int[9];
            for (var bit = 1; bit <= 8; bit++) {
                b[bit] = entry >> (8 - bit) & 1;
            }
            if (b[1] == 0 && b[5] == 0) {
                if (b[2] == 0 && b[3] == 0 && b[4] == 0) {
                    if (b[6] == 0 && b[7] == 0 && b[8] == 0) {
                        return new int[]{0, 0, 0, 0};
                    }
                    return new int[]{255 * b[8], 255 * b[7], 255 * b[6], 64};
                }
                return new int[]{pct(33.3 * b[8] + 66.7 * b[4]), pct(33.3 * b[7] + 66.7 * b[3]),
                        pct(33.3 * b[6] + 66.7 * b[2]), 255};
            }
            if (b[1] == 0) {
                return new int[]{pct(33.3 * b[8] + 66.7 * b[4]), pct(33.3 * b[7] + 66.7 * b[3]),
                        pct(33.3 * b[6] + 66.7 * b[2]), 128};
            }
            var base = b[5] == 0 ? 50 : 0;
            return new int[]{pct(16.7 * b[8] + 33.3 * b[4] + base), pct(16.7 * b[7] + 33.3 * b[3] + base),
                    pct(16.7 * b[6] + 33.3 * b[2] + base), 255};
        }

        static int pct(double percent) {
            return (int) Math.round(percent * 255 / 100);
        }

        @Test
        @DisplayName("decodes every run form of the code strings, map tables, fields and the region fill")
        void codeStrings() {
            var top = new DvbPixels().string(2).run2(1, 3).run2(5, 2).run2(1, 0).run2(2, 0).run2(14, 1)
                    .run2(40, 3).end2().string(4).run4(1, 9).run4(6, 0).run4(5, 12).run4(2, 0).run4(1, 0)
                    .run4(20, 7).run4(30, 15).end4().endLine()
                    .map(0x20, 0, 5, 10, 15).string(2).run2(4, 1).run2(4, 2).run2(4, 3).end2()
                    .string(8).run8(1, 200).run8(3, 0).run8(10, 77).end8().endLine().bytes();
            var bottom = new DvbPixels().string(4).run4(130, 3).end4().endLine().string(4).run4(130, 3).end4()
                    .endLine().bytes();
            var data = join(BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 10, 20}),
                    BitmapStreams.dvbRegion(1, 0, true, 140, 4, 2, 0, 6, new int[]{9, 0, 0}),
                    BitmapStreams.dvbObject(1, 9, top, bottom, false), BitmapStreams.dvbEnd(1));
            var cues = decode(new BitmapSubtitleDecoder(track(Codec.DVB_SUBTITLE, SERVICE)),
                    List.of(packet(data, 0), packet(join(BitmapStreams.dvbPage(1, 0, 0), BitmapStreams.dvbEnd(1)),
                            90)));
            var region = cues.getFirst().regions().getFirst();
            assertEquals(List.of(10, 20, 140, 4), List.of(region.x(), region.y(), region.width(), region.height()));
            var expected0 = new ArrayList<Integer>();
            add(expected0, 1, mapped(2, 3));
            add(expected0, 5, mapped(2, 2));
            add(expected0, 3, mapped(2, 0));
            add(expected0, 14, mapped(2, 1));
            add(expected0, 40, mapped(2, 3));
            add(expected0, 1, 9);
            add(expected0, 6, 0);
            add(expected0, 5, 12);
            add(expected0, 3, 0);
            add(expected0, 20, 7);
            add(expected0, 30, 15);
            var expected2 = new ArrayList<Integer>();
            add(expected2, 4, 5);
            add(expected2, 4, 10);
            add(expected2, 4, 15);
            add(expected2, 1, 200 >> 4);
            add(expected2, 3, 0);
            add(expected2, 10, 77 >> 4);
            var picture = region.picture();
            for (var x = 0; x < 140; x++) {
                assertEquals(color16(x < expected0.size() ? expected0.get(x) : 6), color(picture, x, 0),
                        "row 0 column " + x);
                assertEquals(color16(x < expected2.size() ? expected2.get(x) : 6), color(picture, x, 2),
                        "row 2 column " + x);
                assertEquals(color16(x < 130 ? 3 : 6), color(picture, x, 1), "row 1 column " + x);
                assertEquals(color16(x < 130 ? 3 : 6), color(picture, x, 3), "row 3 column " + x);
            }
        }

        static int mapped(int bits, int code) {
            return new int[]{0x0, 0x7, 0x8, 0xF}[code];
        }

        static void add(List<Integer> line, int run, int code) {
            for (var index = 0; index < run; index++) {
                line.add(code);
            }
        }

        static int color16(int entry) {
            var rgba = expectedDefault(2, entry);
            return rgba[3] == 0 ? 0 : rgba[0] << 24 | rgba[1] << 16 | rgba[2] << 8 | rgba[3];
        }

        static int color(RgbaPicture picture, int x, int y) {
            var at = y * picture.stride() + x * 4;
            var p = picture.pixels();
            var alpha = p[at + 3] & 0xFF;
            return alpha == 0 ? 0 : (p[at] & 0xFF) << 24 | (p[at + 1] & 0xFF) << 16 | (p[at + 2] & 0xFF) << 8 | alpha;
        }

        @Test
        @DisplayName("applies CLUT definitions of full and reduced range, Y=0 meaning transparent")
        void clutDefinition() {
            var top = new DvbPixels().string(4).run4(1, 1).run4(1, 2).run4(1, 3).run4(1, 4).end4().endLine().bytes();
            var data = join(BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 0, 0}),
                    BitmapStreams.dvbRegion(1, 0, false, 4, 2, 2, 3, 0, new int[]{1, 0, 0}),
                    BitmapStreams.dvbClut(1, 3, new int[]{1, 0x41, 81, 240, 90, 0}, new int[]{2, 0x41, 0, 0, 0, 0},
                            new int[]{3, 0x41, 180, 128, 32, 64}, new int[]{4, 0x40, 200, 64, 192, 128}),
                    BitmapStreams.dvbObject(1, 1, top, new byte[0], false), BitmapStreams.dvbEnd(1));
            var cues = decode(new BitmapSubtitleDecoder(track(Codec.DVB_SUBTITLE, SERVICE)),
                    List.of(packet(data, 0), packet(join(BitmapStreams.dvbPage(1, 0, 0), BitmapStreams.dvbEnd(1)), 90)));
            var picture = cues.getFirst().regions().getFirst().picture();
            var c1 = rgb(81, 90, 240, false);
            assertPixel(picture, 0, 0, c1[0], c1[1], c1[2], 255);
            assertPixel(picture, 1, 0, 0, 0, 0, 0);
            var c3 = rgb(180, 32, 128, false);
            assertPixel(picture, 2, 0, c3[0], c3[1], c3[2], 191);
            var c4 = rgb(200 >> 2 << 2, 192 >> 4 << 4, 64 >> 4 << 4, false);
            assertPixel(picture, 3, 1, c4[0], c4[1], c4[2], 255 - (128 >> 6 << 6));
        }

        @Test
        @DisplayName("ends a page at its time-out, places regions in the display window, and decodes progressive objects")
        void pageFeatures() {
            var indices = BitmapStreams.indices(30, 6, (x, y) -> (x * 3 + y * 7) % 256);
            for (var filter = 0; filter <= 4; filter++) {
                var data = join(BitmapStreams.dvbDisplay(1, 1920, 1080, new int[]{100, 1819, 50, 1029}),
                        BitmapStreams.dvbPage(1, 3, 2, new int[]{0, 4, 8}),
                        BitmapStreams.dvbRegion(1, 0, false, 30, 6, 3, 0, 0, new int[]{2, 0, 0}),
                        BitmapStreams.dvbProgressive(1, 2, indices, 30, 6, filter), BitmapStreams.dvbEnd(1));
                var decoder = new BitmapSubtitleDecoder(track(Codec.DVB_SUBTITLE, SERVICE));
                var cues = new ArrayList<>(decoder.decode(packet(data, 0)));
                assertTrue(cues.isEmpty());
                cues.addAll(decoder.decode(packet(join(BitmapStreams.dvbPage(1, 3, 0, new int[]{0, 4, 8}),
                        BitmapStreams.dvbEnd(1)), 90_000 * 5)));
                assertEquals(1, cues.size(), "the time-out ends the page; the repeat after it starts another");
                var cue = cues.getFirst();
                assertEquals(3_000_000, cue.endMicros());
                assertEquals(1920, cue.canvasWidth());
                assertEquals(1080, cue.canvasHeight());
                var region = cue.regions().getFirst();
                assertEquals(104, region.x());
                assertEquals(58, region.y());
                for (var y = 0; y < 6; y++) {
                    for (var x = 0; x < 30; x++) {
                        var expected = expectedDefault(3, indices[y * 30 + x] & 0xFF);
                        assertPixel(region.picture(), x, y, expected[0], expected[1], expected[2], expected[3]);
                    }
                }
            }
        }

        @Test
        @DisplayName("follows the composition and ancillary pages of its service and ignores the others")
        void pages() {
            var top = new DvbPixels().string(4).run4(10, 1).end4().endLine().bytes();
            var data = join(BitmapStreams.dvbPage(3, 0, 2, new int[]{0, 0, 0}),
                    BitmapStreams.dvbRegion(3, 0, false, 10, 2, 2, 0, 0, new int[]{1, 0, 0}),
                    BitmapStreams.dvbObject(3, 1, new DvbPixels().string(4).run4(10, 2).end4().endLine().bytes(),
                            new byte[0], false),
                    BitmapStreams.dvbEnd(3),
                    BitmapStreams.dvbPage(1, 0, 2, new int[]{0, 0, 0}),
                    BitmapStreams.dvbRegion(1, 0, false, 10, 2, 2, 0, 0, new int[]{1, 0, 0}),
                    BitmapStreams.dvbObject(2, 1, top, new byte[0], false), BitmapStreams.dvbEnd(1));
            var cues = decode(new BitmapSubtitleDecoder(track(Codec.DVB_SUBTITLE, SERVICE)),
                    List.of(packet(data, 0), packet(join(BitmapStreams.dvbPage(1, 0, 0), BitmapStreams.dvbEnd(1)), 90)));
            assertEquals(1, cues.size());
            assertEquals(color16(1), color(cues.getFirst().regions().getFirst().picture(), 0, 0),
                    "the object comes from ancillary page 2, not from the other service's page 3");
        }
    }

    @Nested
    @DisplayName("DVD subpictures")
    class Dvd {
        static final byte[] HEADER = ("# VobSub index file, v7 (do not modify this line!)\nsize: 720x576\norg: 4, 6\n"
                + "palette: 000000, ffffff, 808080, ff0000, 00ff00, 0000ff, ffff00, ff00ff, 00ffff, 404040, c0c0c0, "
                + "800000, 008000, 000080, 808000, 800080\n").getBytes(StandardCharsets.UTF_8);

        @Test
        @DisplayName("decodes the run lengths of both fields with the palette, contrast, area, origin and delays")
        void unit() {
            var width = 300;
            var indices = BitmapStreams.indices(width, 7, (x, y) -> x < 2 ? 1 : x < 20 ? 2 : x < 90 ? 3 : x < 290 ? y % 4 : 0);
            var spu = BitmapStreams.spu(width, 7, indices, 50, 400, 10, 100, new int[]{3, 4, 1, 0},
                    new int[]{15, 8, 15, 0}, true);
            var decoder = new BitmapSubtitleDecoder(track(Codec.DVD_SUBTITLE, HEADER));
            var cues = decode(decoder, List.of(packet(spu, 90_000)));
            assertEquals(1, cues.size());
            var cue = cues.getFirst();
            assertEquals(1_000_000 + (10 * 102_400L + 4) / 9, cue.startMicros());
            assertEquals(1_000_000 + (100 * 102_400L + 4) / 9, cue.endMicros());
            assertTrue(cue.forced());
            assertEquals(720, cue.canvasWidth());
            assertEquals(576, cue.canvasHeight());
            var region = cue.regions().getFirst();
            assertEquals(54, region.x());
            assertEquals(406, region.y());
            int[][] colors = {{0, 0, 0, 0}, {0xff, 0xff, 0xff, 255}, {0x00, 0xff, 0x00, 136}, {0xff, 0x00, 0x00, 255}};
            for (var y = 0; y < 7; y++) {
                for (var x = 0; x < width; x++) {
                    var expected = colors[indices[y * width + x]];
                    assertPixel(region.picture(), x, y, expected[0], expected[1], expected[2], expected[3]);
                }
            }
        }

        @Test
        @DisplayName("applies the color and contrast areas of a CHG_COLCON command by screen line and column")
        void colorAreas() {
            var width = 40;
            var indices = BitmapStreams.indices(width, 6, (x, y) -> x % 2 == 0 ? 1 : 2);
            var commands = BitmapStreams.colcon(new int[]{201, 202, 110, 0x0650, 0x0FF0, 120, 0x0780, 0x0F80},
                    new int[]{300, 400, 0, 0x0650, 0x0FF0},
                    new int[]{204, 210, 90, 0x0650, 0x0FF0, 105, 0x3410, 0xFFF0});
            var spu = BitmapStreams.spu(width, 6, indices, 100, 200, 0, 50, new int[]{3, 4, 1, 0},
                    new int[]{15, 15, 15, 0}, false, commands);
            var cues = decode(new BitmapSubtitleDecoder(track(Codec.DVD_SUBTITLE, HEADER)), List.of(packet(spu, 0)));
            assertEquals(1, cues.size());
            var picture = cues.getFirst().regions().getFirst().picture();
            int[][] base = {{0xff, 0xff, 0xff, 255}, {0x00, 0xff, 0x00, 255}};
            int[][] first = {{0x00, 0x00, 0xff, 255}, {0xff, 0xff, 0x00, 255}};
            int[][] second = {{0x00, 0xff, 0xff, 136}, {0xff, 0x00, 0xff, 255}};
            for (var y = 0; y < 6; y++) {
                for (var x = 0; x < width; x++) {
                    int[][] set;
                    if (y == 1 || y == 2) {
                        set = x < 10 ? base : x < 20 ? first : second;
                    } else if (y >= 4) {
                        set = x < 5 ? first : base;
                    } else {
                        set = base;
                    }
                    var expected = set[x % 2];
                    assertPixel(picture, x, y, expected[0], expected[1], expected[2], expected[3]);
                }
            }
        }

        @Test
        @DisplayName("shows a gray palette without a header, and custom colors when the header turns them on")
        void palettes() {
            var indices = BitmapStreams.indices(8, 2, (x, y) -> x % 4);
            var spu = BitmapStreams.spu(8, 2, indices, 0, 500, 0, 5, new int[]{15, 10, 5, 0}, new int[]{15, 15, 15, 15},
                    false);
            var gray = decode(new BitmapSubtitleDecoder(track(Codec.DVD_SUBTITLE, null)), List.of(packet(spu, 0)));
            assertEquals(576, gray.getFirst().canvasHeight(), "a picture below line 480 needs a 576-line frame");
            assertPixel(gray.getFirst().regions().getFirst().picture(), 3, 0, 255, 255, 255, 255);
            assertPixel(gray.getFirst().regions().getFirst().picture(), 2, 0, 170, 170, 170, 255);
            var custom = ("palette: 000000, ffffff, 808080, ff0000, 00ff00, 0000ff, ffff00, ff00ff, 00ffff, 404040, "
                    + "c0c0c0, 800000, 008000, 000080, 808000, 800080\ncustom colors: ON, tridx: 1000, colors: "
                    + "112233, 445566, 778899, aabbcc\n").getBytes(StandardCharsets.UTF_8);
            var picture = decode(new BitmapSubtitleDecoder(track(Codec.DVD_SUBTITLE, custom)), List.of(packet(spu, 0)))
                    .getFirst().regions().getFirst().picture();
            assertPixel(picture, 0, 0, 0, 0, 0, 0);
            assertPixel(picture, 1, 0, 0x44, 0x55, 0x66, 255);
            assertPixel(picture, 3, 1, 0xaa, 0xbb, 0xcc, 255);
        }
    }

    @Nested
    @DisplayName("cue timing")
    class Timing {
        static byte[] show(int number, int color) {
            return join(BitmapStreams.pcs(720, 480, number, 0x80, false, 0, new int[]{1, 0, 0, 0, 0}),
                    BitmapStreams.pds(0, 0, new int[]{1, 235, 128, 128, 255}, new int[]{2, 16, 128, 128, 255}),
                    join(BitmapStreams.ods(1, 4, 2, BitmapStreams.indices(4, 2, (x, y) -> color), 100)),
                    BitmapStreams.pgsEnd());
        }

        static byte[] clear(int number) {
            return join(BitmapStreams.pcs(720, 480, number, 0, false, 0), BitmapStreams.pgsEnd());
        }

        @Test
        @DisplayName("ends a cue at the screen that replaces it, merges repeats and drops cues replaced at once")
        void replacement() {
            var decoder = new BitmapSubtitleDecoder(track(Codec.PGS, null));
            assertEquals(List.of(), decoder.decode(packet(show(0, 1), 0)));
            assertEquals(List.of(), decoder.decode(packet(show(1, 1), 900)), "the same pictures continue the cue");
            var replaced = decoder.decode(packet(show(2, 2), 1800));
            assertEquals(1, replaced.size());
            assertEquals(0, replaced.getFirst().startMicros());
            assertEquals(20_000, replaced.getFirst().endMicros());
            assertEquals(List.of(), decoder.decode(packet(show(3, 1), 1800)), "replaced at the instant it appears");
            var cleared = decoder.decode(packet(clear(4), 2700));
            assertEquals(1, cleared.size());
            assertEquals(20_000, cleared.getFirst().startMicros());
            assertEquals(30_000, cleared.getFirst().endMicros());
            assertEquals(List.of(), decoder.flush());
        }

        @Test
        @DisplayName("caps a cue at its packet's duration and at the end of the stream")
        void durations() {
            var decoder = new BitmapSubtitleDecoder(track(Codec.PGS, null));
            assertEquals(List.of(), decoder.decode(new Packet(1, show(0, 1), 0, 0, 4500, true)));
            var expired = decoder.decode(packet(clear(1), 9000));
            assertEquals(1, expired.size());
            assertEquals(50_000, expired.getFirst().endMicros());
            decoder.decode(new Packet(1, show(2, 2), 18000, 18000, 900, true));
            var flushed = decoder.flush();
            assertEquals(1, flushed.size());
            assertEquals(210_000, flushed.getFirst().endMicros());
        }

        @Test
        @DisplayName("bounds pictures by the limits")
        void limits() {
            var limits = MediaLimits.builder().maxDimension(100).build();
            var decoder = new BitmapSubtitleDecoder(track(Codec.PGS, null), limits);
            assertThrows(MediaException.LimitExceeded.class, () -> decoder.decode(packet(show(0, 1), 0)));
            assertThrows(IllegalArgumentException.class, () -> new BitmapSubtitleDecoder(track(Codec.SUBRIP, null)));
        }

        @Test
        @DisplayName("renders the regions over a transparent canvas with source-over blending")
        void render() {
            var red = RgbaPicture.allocate(2, 1, true);
            red.pixels()[0] = (byte) 255;
            red.pixels()[3] = (byte) 255;
            red.pixels()[4] = (byte) 255;
            red.pixels()[7] = (byte) 128;
            var blue = RgbaPicture.allocate(1, 1, true);
            blue.pixels()[2] = (byte) 255;
            blue.pixels()[3] = (byte) 128;
            var cue = new BitmapCue(0, 1, 4, 2, List.of(new BitmapRegion(2, 1, red), new BitmapRegion(3, 1, blue)),
                    false);
            var canvas = cue.render().pixels();
            assertArrayEquals(new byte[]{(byte) 255, 0, 0, (byte) 255}, Arrays.copyOfRange(canvas, 24, 28));
            assertArrayEquals(new byte[]{85, 0, (byte) 170, (byte) 192}, Arrays.copyOfRange(canvas, 28, 32));
            assertEquals(0, canvas[3]);
        }
    }
}
