package com.github.auties00.smalt.container.mpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.container.CountingSource;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the tag helpers shared by the audio demuxers: ID3v2 skipping and text-frame decoding in
 * versions 2.2 to 2.4 (footers, extended headers, unsynchronization, every text encoding),
 * trailing-tag detection (ID3v1, enhanced TAG+, APE with and without header, Lyrics3v2, appended
 * ID3v2.4).
 */
class TagHelpersTest {
    static byte[] syncsafe(int value) {
        return new byte[]{(byte) (value >> 21 & 0x7F), (byte) (value >> 14 & 0x7F), (byte) (value >> 7 & 0x7F), (byte) (value & 0x7F)};
    }

    static byte[] tag(int version, int flags, byte[] body, boolean footer) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{'I', 'D', '3', (byte) version, 0, (byte) flags});
        output.writeBytes(syncsafe(body.length));
        output.writeBytes(body);
        if (footer) {
            output.writeBytes(new byte[]{'3', 'D', 'I', (byte) version, 0, (byte) flags});
            output.writeBytes(syncsafe(body.length));
        }
        return output.toByteArray();
    }

    static byte[] frame(int version, String id, byte[] payload, int frameFlags) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        if (version == 2) {
            output.writeBytes(new byte[]{(byte) (payload.length >> 16), (byte) (payload.length >> 8), (byte) payload.length});
        } else {
            output.writeBytes(version == 4 ? syncsafe(payload.length) : ByteBuffer.allocate(4).putInt(payload.length).array());
            output.writeBytes(new byte[]{(byte) (frameFlags >> 8), (byte) frameFlags});
        }
        output.writeBytes(payload);
        return output.toByteArray();
    }

    static byte[] text(int encoding, String value) {
        var charset = switch (encoding) {
            case 0 -> StandardCharsets.ISO_8859_1;
            case 1 -> StandardCharsets.UTF_16;
            case 2 -> StandardCharsets.UTF_16BE;
            default -> StandardCharsets.UTF_8;
        };
        var bytes = value.getBytes(charset);
        var output = new ByteArrayOutputStream();
        output.write(encoding);
        output.writeBytes(bytes);
        return output.toByteArray();
    }

    static Map<String, String> parse(byte[] data, long[] end) {
        var metadata = new LinkedHashMap<String, String>();
        end[0] = Id3v2.skip(MediaSource.of(data), 0, data.length, metadata);
        return metadata;
    }

    @Nested
    @DisplayName("ID3v2")
    class Id3 {
        @ParameterizedTest(name = "encoding {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("decodes text frames in every encoding of version 2.3")
        void encodings(int encoding) {
            var value = encoding == 0 ? "Caf\u00e9" : "Caf\u00e9 \u266b";
            var body = frame(3, "TIT2", text(encoding, value), 0);
            var data = tag(3, 0, body, false);
            var end = new long[1];
            assertEquals(Map.of("TIT2", value), parse(data, end));
            assertEquals(data.length, end[0]);
        }

        @Test
        @DisplayName("maps version 2.2 identifiers and skips version 2.4 footers")
        void versions() {
            var v22 = tag(2, 0, frame(2, "TT2", text(0, "Old"), 0), false);
            var v24 = tag(4, 0x10, frame(4, "TPE1", text(3, "Artist\u0000Other"), 0), true);
            var both = new byte[v22.length + v24.length + 4];
            System.arraycopy(v22, 0, both, 0, v22.length);
            System.arraycopy(v24, 0, both, v22.length, v24.length);
            var end = new long[1];
            var metadata = parse(both, end);
            assertEquals("Old", metadata.get("TIT2"));
            assertEquals("Artist; Other", metadata.get("TPE1"));
            assertEquals(v22.length + v24.length, end[0], "footer counted, consecutive tags skipped");
        }

        @Test
        @DisplayName("reverses whole-tag unsynchronization of version 2.3 and per-frame unsynchronization of 2.4")
        void unsynchronization() {
            var payload = text(0, "A\u00ff\u00e0B");
            var v23Body = frame(3, "TALB", payload, 0);
            var unsynced = new ByteArrayOutputStream();
            for (var b : v23Body) {
                unsynced.write(b);
                if ((b & 0xFF) == 0xFF) {
                    unsynced.write(0);
                }
            }
            var v23 = tag(3, 0x80, unsynced.toByteArray(), false);
            assertEquals("A\u00ff\u00e0B", parse(v23, new long[1]).get("TALB"));
            var frameUnsynced = new ByteArrayOutputStream();
            for (var b : payload) {
                frameUnsynced.write(b);
                if ((b & 0xFF) == 0xFF) {
                    frameUnsynced.write(0);
                }
            }
            var v24 = tag(4, 0, frame(4, "TALB", frameUnsynced.toByteArray(), 0x0002), false);
            assertEquals("A\u00ff\u00e0B", parse(v24, new long[1]).get("TALB"));
        }

        @Test
        @DisplayName("decodes TXXX and COMM frames and skips an extended header")
        void userFrames() {
            var txxx = frame(3, "TXXX", text(3, "REPLAYGAIN\u0000-6.5 dB"), 0);
            var comm = frame(3, "COMM", concat(new byte[]{3, 'e', 'n', 'g'}, "desc\u0000A comment".getBytes(StandardCharsets.UTF_8)), 0);
            var extended = new byte[]{0, 0, 0, 6, 0, 0, 0, 0, 0, 0};
            var data = tag(3, 0x40, concat(extended, concat(txxx, comm)), false);
            var metadata = parse(data, new long[1]);
            assertEquals("-6.5 dB", metadata.get("TXXX:REPLAYGAIN"));
            assertEquals("A comment", metadata.get("COMM"));
        }

        @Test
        @DisplayName("skips an oversized tag without decoding it and rejects invalid headers")
        void limits() {
            var huge = new byte[(2 << 20) + 100];
            var header = tag(3, 0, new byte[0], false);
            System.arraycopy(header, 0, huge, 0, 6);
            System.arraycopy(syncsafe(2 << 20), 0, huge, 6, 4);
            var end = new long[1];
            assertTrue(parse(huge, end).isEmpty());
            assertEquals(10 + (2 << 20), end[0]);
            assertEquals(-1, com.github.auties00.smalt.internal.tags.Id3v2Tag.size(new byte[]{'I', 'D', '3', 5, 0, 0, 0, 0, 0, 0}, 0), "unknown major version");
            assertEquals(-1, com.github.auties00.smalt.internal.tags.Id3v2Tag.size(new byte[]{'I', 'D', '3', 3, 0, 0, (byte) 0x80, 0, 0, 0}, 0), "not syncsafe");
            assertEquals(0, Id3v2.skip(MediaSource.of(new byte[20]), 0, 20, null));
        }

        @Test
        @DisplayName("resists hundreds of random mutations")
        void mutations() {
            var body = concat(frame(3, "TIT2", text(1, "Title"), 0), frame(3, "TXXX", text(0, "k\u0000v"), 0));
            var inputs = List.of(tag(3, 0, body, false), tag(4, 0x50, concat(new byte[]{0, 0, 0, 6, 1, 0}, frame(4, "TIT2", text(3, "x"), 3)), true));
            AudioMutations.assertRobust(inputs, 300, 0x1D3L, Duration.ofSeconds(2), bytes -> {
                Id3v2.skip(MediaSource.of(bytes), 0, bytes.length, new LinkedHashMap<>());
                TrailingTags.audioEnd(MediaSource.of(bytes), 0, bytes.length);
            });
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        var result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    @Nested
    @DisplayName("trailing tags")
    class Trailing {
        static byte[] ape(boolean header, int items) {
            var itemBytes = new byte[items];
            var size = itemBytes.length + 32;
            var footer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).put("APETAGEX".getBytes(StandardCharsets.US_ASCII))
                    .putInt(2000).putInt(size).putInt(1).putInt(header ? 0x80000000 : 0).putLong(0).array();
            var output = new ByteArrayOutputStream();
            if (header) {
                output.writeBytes(footer);
            }
            output.writeBytes(itemBytes);
            output.writeBytes(footer);
            return output.toByteArray();
        }

        static byte[] id3v1(boolean enhanced) {
            var output = new ByteArrayOutputStream();
            if (enhanced) {
                var plus = new byte[227];
                plus[0] = 'T';
                plus[1] = 'A';
                plus[2] = 'G';
                plus[3] = '+';
                output.writeBytes(plus);
            }
            var tag = new byte[128];
            tag[0] = 'T';
            tag[1] = 'A';
            tag[2] = 'G';
            output.writeBytes(tag);
            return output.toByteArray();
        }

        static byte[] lyrics() {
            var content = "LYRICSBEGININD00002" + "10";
            var size = String.format("%06d", content.length());
            return (content + size + "LYRICS200").getBytes(StandardCharsets.US_ASCII);
        }

        long end(byte[]... parts) {
            var audio = new byte[1000];
            var data = audio;
            for (var part : parts) {
                data = concat(data, part);
            }
            return TrailingTags.audioEnd(MediaSource.of(data), 0, data.length);
        }

        @Test
        @DisplayName("peels off every combination of trailing tags")
        void combinations() {
            assertEquals(1000, end());
            assertEquals(1000, end(id3v1(false)));
            assertEquals(1000, end(id3v1(true)));
            assertEquals(1000, end(ape(false, 50)));
            assertEquals(1000, end(ape(true, 51)));
            assertEquals(1000, end(ape(true, 10), id3v1(false)));
            assertEquals(1000, end(lyrics(), id3v1(false)));
            assertEquals(1000, end(tag(4, 0x10, frame(4, "TIT2", text(0, "t"), 0), true)));
            assertEquals(1000, end(ape(true, 3), lyrics(), tag(4, 0x10, new byte[5], true), id3v1(true)));
        }

        @Test
        @DisplayName("probes tags that fit in the tail block with one read and reads again only for larger ones")
        void tailReads() {
            var small = concat(concat(new byte[100_000], ape(true, 300)), id3v1(true));
            var source = new CountingSource(small);
            assertEquals(100_000, TrailingTags.audioEnd(source, 0, small.length));
            assertEquals(1, source.reads, source.toString());
            var large = concat(concat(new byte[100_000], ape(true, 9000)), id3v1(false));
            var counted = new CountingSource(large);
            assertEquals(100_000, TrailingTags.audioEnd(counted, 0, large.length));
            assertEquals(2, counted.reads, counted.toString());
            var none = new byte[100_000];
            var plain = new CountingSource(none);
            assertEquals(none.length, TrailingTags.audioEnd(plain, 0, none.length));
            assertEquals(1, plain.reads, plain.toString());
        }

        @ParameterizedTest(name = "chunk {0}")
        @ValueSource(ints = {1, 97, 4096, Integer.MAX_VALUE})
        @DisplayName("finds the same end through a tail block as reading the source directly")
        void tailEquivalence(int chunk) {
            var random = new java.util.SplittableRandom(chunk);
            for (var round = 0; round < 300; round++) {
                var data = new byte[1000 + random.nextInt(3000)];
                var parts = 1 + random.nextInt(4);
                for (var part = 0; part < parts; part++) {
                    var tag = switch (random.nextInt(5)) {
                        case 0 -> id3v1(random.nextBoolean());
                        case 1 -> ape(random.nextBoolean(), random.nextInt(random.nextBoolean() ? 100 : 9000));
                        case 2 -> lyrics();
                        case 3 -> tag(4, 0x10, frame(4, "TIT2", text(0, "t"), 0), true);
                        default -> new byte[random.nextInt(50)];
                    };
                    data = concat(data, tag);
                }
                var start = random.nextInt(1200);
                var expected = TrailingTags.audioEnd(MediaSource.of(data), start, data.length);
                assertEquals(expected, TrailingTags.audioEnd(new CountingSource(data, chunk), start, data.length), "round " + round);
            }
        }

        static byte[] lyrics3v1(String text) {
            return ("LYRICSBEGIN" + text + "LYRICSEND").getBytes(StandardCharsets.US_ASCII);
        }

        List<TrailingTags.Region> regions(byte[]... parts) {
            var data = new byte[1000];
            for (var part : parts) {
                data = concat(data, part);
            }
            return TrailingTags.regions(MediaSource.of(data), 0, data.length);
        }

        @Test
        @DisplayName("reports the kind, offset and size of every trailing tag in file order")
        void regionLayout() {
            var ape = ape(true, 40);
            var lyrics = lyrics();
            var appended = tag(4, 0x10, frame(4, "TIT2", text(0, "t"), 0), true);
            var found = regions(appended, ape, lyrics, id3v1(true));
            assertEquals(List.of(
                    new TrailingTags.Region(TrailingTags.Kind.ID3V2, 1000, appended.length),
                    new TrailingTags.Region(TrailingTags.Kind.APE, 1000 + appended.length, ape.length),
                    new TrailingTags.Region(TrailingTags.Kind.LYRICS3, 1000 + appended.length + ape.length, lyrics.length),
                    new TrailingTags.Region(TrailingTags.Kind.ID3V1_ENHANCED, 1000 + appended.length + ape.length + lyrics.length, 227),
                    new TrailingTags.Region(TrailingTags.Kind.ID3V1, 1000 + appended.length + ape.length + lyrics.length + 227, 128)),
                    found);
            assertTrue(regions().isEmpty());
        }

        @Test
        @DisplayName("finds a Lyrics3v1 block only right before an ID3v1 tag")
        void lyrics3v1() {
            var block = lyrics3v1("[00:01]la la");
            assertEquals(List.of(new TrailingTags.Region(TrailingTags.Kind.LYRICS3, 1000, block.length),
                    new TrailingTags.Region(TrailingTags.Kind.ID3V1, 1000 + block.length, 128)), regions(block, id3v1(false)));
            assertTrue(regions(block).isEmpty(), "no ID3v1 tag follows");
            var long_ = lyrics3v1("x".repeat(5100));
            assertEquals(1000, end(long_, id3v1(false)));
            var tooLong = lyrics3v1("x".repeat(5101));
            assertEquals(1000 + tooLong.length, end(tooLong, id3v1(false)));
        }

        @Test
        @DisplayName("requires an appended ID3v2 tag to be a version 2.4 tag whose header matches its footer")
        void appendedValidation() {
            var appended = tag(4, 0x10, frame(4, "TIT2", text(0, "t"), 0), true);
            var noHeader = appended.clone();
            noHeader[0] = 'X';
            assertEquals(1000 + noHeader.length, end(noHeader));
            var v3 = appended.clone();
            v3[appended.length - 7] = 3;
            assertEquals(1000 + v3.length, end(v3));
            var noFlag = appended.clone();
            noFlag[appended.length - 5] = 0;
            assertEquals(1000 + noFlag.length, end(noFlag));
        }

        static byte[] metadataFixture() {
            var appended = tag(4, 0x10, concat(frame(4, "TIT2", text(3, "Appended title"), 0),
                    frame(4, "TALB", text(3, "Appended album"), 0)), true);
            var apeTag = com.github.auties00.smalt.internal.tags.ApeTag.encode(com.github.auties00.smalt.metadata.MediaTags.builder()
                    .set("title", "APE title").set("artist", "APE artist").set("REPLAYGAIN_TRACK_GAIN", "-2 dB").build(), List.of());
            var v1 = id3v1(false);
            System.arraycopy("V1 title".getBytes(StandardCharsets.US_ASCII), 0, v1, 3, 8);
            System.arraycopy("V1 comment".getBytes(StandardCharsets.US_ASCII), 0, v1, 97, 10);
            v1[127] = (byte) 0xFF;
            return concat(concat(concat(new byte[1000], appended), apeTag), v1);
        }

        static Map<String, String> readMetadata(byte[] data, Id3v2.Merge merge, Map<String, String> leading) {
            var tail = new TrailingTags.Tail(MediaSource.of(data), 0);
            tail.load(data.length);
            var metadata = new LinkedHashMap<>(leading);
            TrailingTags.readMetadata(tail, List.of(), TrailingTags.regions(tail, 0, data.length), metadata, null, merge);
            return metadata;
        }

        @Test
        @DisplayName("lets an appended ID3v2 tag replace the leading ID3v2 fields, then adds the missing APE and ID3v1 fields")
        void metadataReplace() {
            var metadata = readMetadata(metadataFixture(), Id3v2.Merge.REPLACE, Map.of("TPE1", "Leading artist"));
            assertEquals("Appended title", metadata.get("TIT2"));
            assertEquals("Appended album", metadata.get("TALB"));
            assertEquals("APE title", metadata.get("title"));
            assertEquals("-2 dB", metadata.get("replaygain_track_gain"));
            assertEquals("V1 comment", metadata.get("COMM"));
            assertEquals(List.of("TIT2", "TALB", "title", "artist", "replaygain_track_gain", "COMM"),
                    List.copyOf(metadata.keySet()), "the leading TPE1 is discarded");
        }

        @Test
        @DisplayName("lets an appended ID3v2 tag only add missing fields when the main tags are in another format")
        void metadataFill() {
            var leading = new LinkedHashMap<String, String>();
            leading.put("TPE1", "Leading artist");
            leading.put("TIT2", "Vorbis");
            var metadata = readMetadata(metadataFixture(), Id3v2.Merge.FILL, leading);
            assertEquals("Vorbis", metadata.get("TIT2"));
            assertEquals("Leading artist", metadata.get("TPE1"));
            assertEquals("Appended album", metadata.get("TALB"));
            assertEquals(List.of("TPE1", "TIT2", "TALB", "title", "artist", "replaygain_track_gain", "COMM"),
                    List.copyOf(metadata.keySet()));
        }

        @Test
        @DisplayName("lets an appended ID3v2 tag flagged as an update replace only the values it holds")
        void updateFlag() {
            var extended = new byte[]{0, 0, 0, 6, 1, 0x40};
            var update = tag(4, 0x50, concat(extended, frame(4, "TIT2", text(3, "Updated"), 0)), true);
            var metadata = readMetadata(concat(new byte[1000], update), Id3v2.Merge.REPLACE,
                    Map.of("TIT2", "Leading", "TALB", "Leading album"));
            assertEquals("Updated", metadata.get("TIT2"));
            assertEquals("Leading album", metadata.get("TALB"));
            var plain = tag(4, 0x10, frame(4, "TIT2", text(3, "Replaced"), 0), true);
            var replaced = readMetadata(concat(new byte[1000], plain), Id3v2.Merge.REPLACE,
                    Map.of("TIT2", "Leading", "TALB", "Leading album"));
            assertEquals(Map.of("TIT2", "Replaced"), replaced);
        }

        @Test
        @DisplayName("counts the APE header only when the footer flags it and it is present")
        void apeHeaderFlag() {
            var withHeader = ape(true, 40);
            assertEquals(1000, end(withHeader));
            var footerOnly = java.util.Arrays.copyOfRange(withHeader, 32, withHeader.length);
            // the footer still flags a header, which the 32 bytes before the items do not hold
            assertEquals(1032, end(new byte[32], footerOnly));
            assertEquals(List.of(new TrailingTags.Region(TrailingTags.Kind.APE, 1000, footerOnly.length)), regions(footerOnly));
        }

        @Test
        @DisplayName("finds a Lyrics3v1 block before an enhanced ID3v1 tag")
        void lyrics3v1Enhanced() {
            var block = lyrics3v1("[00:01]la la");
            assertEquals(1000, end(block, id3v1(true)));
        }

        @Nested
        @DisplayName("SEEK chains")
        class Seek {
            static byte[] seekTag(long offset) {
                return tag(4, 0, frame(4, "SEEK", ByteBuffer.allocate(4).putInt((int) offset).array(), 0), false);
            }

            List<TrailingTags.Region> located(byte[] data, long target, long end) {
                return TrailingTags.seekRegions(new TrailingTags.Tail(MediaSource.of(data), 0), target, 0, end);
            }

            @Test
            @DisplayName("follows a chain of SEEK frames through tags inside the audio")
            void chain() {
                var last = tag(4, 0, frame(4, "TIT2", text(3, "x"), 0), false);
                var first = seekTag(50);
                var data = concat(concat(concat(concat(new byte[100], first), new byte[50]), last), new byte[100]);
                assertEquals(List.of(new TrailingTags.Region(TrailingTags.Kind.ID3V2, 100, first.length),
                        new TrailingTags.Region(TrailingTags.Kind.ID3V2, 150 + first.length, last.length)),
                        located(data, 100, data.length));
            }

            @ParameterizedTest(name = "{0} bytes short")
            @ValueSource(ints = {0, 1, 9, 10, 100, 4096, 8192, 8193, 70_000})
            @DisplayName("searches forward from a SEEK target that falls short of the tag (ID3v2.4 4.29: a minimum offset)")
            void searchesForward(int shortfall) {
                var last = tag(4, 0, frame(4, "TIT2", text(3, "x"), 0), false);
                var first = seekTag(50);
                var gap = new byte[50 + shortfall];
                java.util.Arrays.fill(gap, (byte) 0x5A);
                var data = concat(concat(concat(concat(new byte[100], first), gap), last), new byte[100]);
                var tagAt = 100 + first.length + gap.length;
                assertEquals(List.of(new TrailingTags.Region(TrailingTags.Kind.ID3V2, 100, first.length),
                        new TrailingTags.Region(TrailingTags.Kind.ID3V2, tagAt, last.length)),
                        located(data, 100, data.length));
                assertEquals(List.of(new TrailingTags.Region(TrailingTags.Kind.ID3V2, tagAt, last.length)),
                        located(data, tagAt - shortfall, data.length), "the first target itself may fall short");
                assertTrue(located(data, tagAt + 1, data.length).isEmpty(), "the search never goes backward");
            }

            @Test
            @DisplayName("finds a tag through a slow source and through the buffered tail alike")
            void slowSource() {
                var last = tag(4, 0, frame(4, "TIT2", text(3, "x"), 0), false);
                var data = concat(concat(new byte[20_000], last), new byte[100]);
                var expected = List.of(new TrailingTags.Region(TrailingTags.Kind.ID3V2, 20_000, last.length));
                var counting = new CountingSource(data);
                assertEquals(expected, TrailingTags.seekRegions(new TrailingTags.Tail(counting, 0), 3, 0, data.length));
                var tail = new TrailingTags.Tail(MediaSource.of(data), 0);
                tail.load(data.length);
                assertEquals(expected, TrailingTags.seekRegions(tail, 3, 0, data.length));
            }

            @Test
            @DisplayName("follows every hop of a chain whose SEEK offsets all fall short")
            void chainForward() {
                var third = tag(4, 0, frame(4, "TALB", text(3, "z"), 0), false);
                var second = tag(4, 0, concat(frame(4, "TIT2", text(3, "y"), 0),
                        frame(4, "SEEK", ByteBuffer.allocate(4).putInt(10).array(), 0)), false);
                var first = seekTag(3);
                var data = concat(concat(concat(concat(concat(concat(new byte[100], first), new byte[300]), second),
                        new byte[4000]), third), new byte[64]);
                var secondAt = 100 + first.length + 300;
                var thirdAt = secondAt + second.length + 4000;
                assertEquals(List.of(new TrailingTags.Region(TrailingTags.Kind.ID3V2, 100, first.length),
                        new TrailingTags.Region(TrailingTags.Kind.ID3V2, secondAt, second.length),
                        new TrailingTags.Region(TrailingTags.Kind.ID3V2, thirdAt, third.length)),
                        located(data, 100, data.length));
            }

            @Test
            @DisplayName("passes over bytes that only look like a tag between the target and the tag")
            void falseHeaders() {
                var last = tag(4, 0, frame(4, "TIT2", text(3, "x"), 0), false);
                // a header whose flags set an undefined bit, a header of a tag holding no frame, a header of a tag
                // running past the region, a header with a version no reader knows and a bare "ID3"
                var undefinedFlag = tag(4, 0x01, frame(4, "TIT2", text(3, "bogus"), 0), false);
                var empty = tag(4, 0, new byte[32], false);
                var tooLong = new byte[]{'I', 'D', '3', 4, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F};
                var badVersion = new byte[]{'I', 'D', '3', 5, 0, 0, 0, 0, 0, 1};
                var junk = concat(concat(concat(concat(concat(undefinedFlag, empty), tooLong), badVersion),
                        "ID3ID3".getBytes(StandardCharsets.US_ASCII)), new byte[7]);
                var data = concat(concat(concat(new byte[50], junk), last), new byte[10]);
                var tagAt = 50 + junk.length;
                assertEquals(List.of(new TrailingTags.Region(TrailingTags.Kind.ID3V2, tagAt, last.length)),
                        located(data, 49, data.length));
                assertEquals(List.of(new TrailingTags.Region(TrailingTags.Kind.ID3V2, 50, undefinedFlag.length)),
                        located(data, 50, data.length), "a tag at the target itself needs only a valid header");
                assertEquals(List.of(new TrailingTags.Region(TrailingTags.Kind.ID3V2, 50 + undefinedFlag.length,
                        empty.length)), located(data, 50 + undefinedFlag.length, data.length),
                        "a frameless tag counts at the target itself");
            }

            @Test
            @DisplayName("searches at most MAX_SEEK_SCAN bytes past each target")
            void scanBound() {
                var last = tag(4, 0, frame(4, "TIT2", text(3, "x"), 0), false);
                var reach = concat(concat(new byte[TrailingTags.MAX_SEEK_SCAN], last), new byte[10]);
                assertEquals(1, located(reach, 0, reach.length).size(), "a tag starting at the last byte in reach");
                var beyond = concat(concat(new byte[TrailingTags.MAX_SEEK_SCAN + 1], last), new byte[10]);
                assertTrue(located(beyond, 0, beyond.length).isEmpty());
                assertEquals(1, located(beyond, 1, beyond.length).size());
            }

            @Test
            @DisplayName("gives up after MAX_SEEK_CANDIDATES headers that are not usable tags")
            void candidateBound() {
                var last = tag(4, 0, frame(4, "TIT2", text(3, "x"), 0), false);
                var bogus = tag(4, 0, new byte[16], false);
                for (var count : new int[]{TrailingTags.MAX_SEEK_CANDIDATES - 1, TrailingTags.MAX_SEEK_CANDIDATES}) {
                    var junk = new byte[0];
                    for (var index = 0; index < count; index++) {
                        junk = concat(junk, bogus);
                    }
                    var data = concat(concat(concat(new byte[]{1}, junk), last), new byte[10]);
                    assertEquals(count < TrailingTags.MAX_SEEK_CANDIDATES ? 1 : 0, located(data, 0, data.length).size(),
                            count + " bogus headers");
                }
            }

            @Test
            @DisplayName("bounds the work on a region full of tag-like headers")
            void hostile() {
                // headers 10 bytes apart, each claiming a 1 MiB body that holds no frame
                var data = new byte[8 << 20];
                for (var offset = 1; offset + 10 <= data.length; offset += 10) {
                    System.arraycopy(new byte[]{'I', 'D', '3', 4, 0, 0, 0x00, 0x40, 0x00, 0x00}, 0, data, offset, 10);
                }
                var counting = new CountingSource(data);
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertTrue(TrailingTags.seekRegions(
                        new TrailingTags.Tail(counting, 0), 0, 0, data.length).isEmpty()));
                assertTrue(counting.bytes < 64 << 10, "read " + counting.bytes + " bytes");
                // a chain of tags whose SEEK frames point right behind them ends after MAX_TAGS (16) tags
                var hop = seekTag(0);
                var chain = new byte[0];
                for (var index = 0; index < 40; index++) {
                    chain = concat(chain, hop);
                }
                assertEquals(16, located(chain, 0, chain.length).size());
            }

            @Test
            @DisplayName("finds version 2.2, 2.3 and extended-header tags past the target, and applies each version's flags")
            void versionsForward() {
                var v22 = tag(2, 0, frame(2, "TT2", text(0, "x"), 0), false);
                var v23 = tag(3, 0, frame(3, "TIT2", text(0, "x"), 0), false);
                var v23Extended = tag(3, 0x40, concat(new byte[]{0, 0, 0, 6, 0, 0, 0, 0, 0, 0},
                        frame(3, "TIT2", text(0, "x"), 0)), false);
                var v24Extended = tag(4, 0x40, concat(new byte[]{0, 0, 0, 6, 1, 0}, frame(4, "TIT2", text(3, "x"), 0)), false);
                // a CRC ending in 0xFF 0xFF, so the unsynchronised extended header is one byte longer than declared
                var v23Unsync = tag(3, 0xC0, concat(new byte[]{0, 0, 0, 10, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0,
                        (byte) 0xFF, 0, (byte) 0xFF}, frame(3, "TIT2", text(0, "x"), 0)), false);
                for (var tag : List.of(v22, v23, v23Extended, v24Extended, v23Unsync)) {
                    var data = concat(concat(new byte[100], tag), new byte[10]);
                    assertEquals(List.of(new TrailingTags.Region(TrailingTags.Kind.ID3V2, 100, tag.length)),
                            located(data, 37, data.length), "version " + tag[3] + " flags " + tag[5]);
                }
                // flags a version leaves undefined (0x10 is the v2.4 footer, undefined in v2.3; 0x20 is undefined in v2.2)
                for (var tag : List.of(tag(3, 0x10, frame(3, "TIT2", text(0, "x"), 0), false),
                        tag(2, 0x20, frame(2, "TT2", text(0, "x"), 0), false))) {
                    var data = concat(concat(new byte[100], tag), new byte[10]);
                    assertTrue(located(data, 37, data.length).isEmpty(), "version " + tag[3] + " flags " + tag[5]);
                    assertEquals(1, located(data, 100, data.length).size(), "counted at the target itself");
                }
                // an extended header of a size version 2.3 does not define, a version 2.4 extended header whose size
                // is not syncsafe or under 6 bytes, a first frame with no valid identifier, and a first frame with a
                // valid identifier whose size runs past the tag, so the tag holds no frame
                for (var tag : List.of(tag(3, 0x40, concat(new byte[]{0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0},
                                frame(3, "TIT2", text(0, "x"), 0)), false),
                        tag(4, 0x40, concat(new byte[]{0, 0, 0, (byte) 0x86, 1, 0}, frame(4, "TIT2", text(3, "x"), 0)),
                                false),
                        tag(4, 0x40, concat(new byte[]{0, 0, 0, 5, 1}, frame(4, "TIT2", text(3, "x"), 0)), false),
                        tag(4, 0, concat(new byte[]{'t', 'i', 't', '2', 0, 0, 0, 2, 0, 0, 3, 'x'},
                                frame(4, "TIT2", text(3, "x"), 0)), false),
                        tag(4, 0, new byte[]{'T', 'I', 'T', '2', 0x7F, 0x7F, 0x7F, 0x7F, 0, 0, 0, 0, 0, 0, 0, 0},
                                false))) {
                    var data = concat(concat(new byte[100], tag), new byte[10]);
                    assertTrue(located(data, 37, data.length).isEmpty(), "version " + tag[3] + " flags " + tag[5]);
                }
            }

            @Test
            @DisplayName("bounds the work on tag-like headers that declare an extended header")
            void hostileExtended() {
                // each header claims a 1 MiB body opening with an extended header, followed by bytes that are no frame
                var data = new byte[8 << 20];
                for (var offset = 1; offset + 20 <= data.length; offset += 20) {
                    System.arraycopy(new byte[]{'I', 'D', '3', 4, 0, 0x40, 0x00, 0x40, 0x00, 0x00, 0, 0, 0, 6, 1, 0},
                            0, data, offset, 16);
                }
                var counting = new CountingSource(data);
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertTrue(TrailingTags.seekRegions(
                        new TrailingTags.Tail(counting, 0), 0, 0, data.length).isEmpty()));
                assertTrue(counting.bytes < 64 << 10, "read " + counting.bytes + " bytes");
            }

            @Test
            @DisplayName("stops at a target with no tag in reach, at a tag running past the audio and after a tag without SEEK")
            void stops() {
                var target = tag(4, 0, frame(4, "TIT2", text(3, "x"), 0), false);
                var data = concat(concat(new byte[100], target), new byte[100]);
                assertEquals(1, located(data, 99, data.length).size(), "the tag one byte past the target is found");
                assertTrue(located(data, 101, data.length).isEmpty());
                assertTrue(located(data, -1, data.length).isEmpty());
                assertTrue(located(data, 100, 100 + target.length - 1).isEmpty(), "the tag must end before the trailing tags");
                assertEquals(1, located(data, 100, data.length).size());
                var next = seekTag(0);
                var chained = concat(concat(new byte[100], next), new byte[100]);
                assertEquals(1, located(chained, 100, chained.length).size(), "the SEEK target after the tag holds none");
                var corrupt = target.clone();
                corrupt[3] = 9;
                assertTrue(located(concat(concat(new byte[100], corrupt), new byte[100]), 100, 300).isEmpty());
            }
        }

        @Test
        @DisplayName("ignores tag-like bytes that do not form a valid tag")
        void falsePositives() {
            var broken = ape(false, 10);
            broken[broken.length - 32] = (byte) 'X';
            assertEquals(1000 + broken.length, end(broken));
            assertEquals(1000 + 9, end("LYRICS200".getBytes(StandardCharsets.US_ASCII)));
        }
    }
}
