package com.github.auties00.smalt.container.dsd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads synthesized DSF and DSDIFF files: the track, every byte of the sound data in its packets, the ends of the
 * last packet, seeking, the DSF ID3v2 tag (also against ExifTool), the DSDIFF texts and markers, and the stream
 * parameters against ffprobe; and rejects damaged headers.
 */
@DisplayName("DSF and DSDIFF demuxers")
class DsdDemuxerTest {
    static List<Packet> packets(Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            packets.add(packet);
        }
        return packets;
    }

    /**
     * Returns the first string value of a field in ffprobe's JSON.
     */
    static String field(String json, String name) {
        var matcher = java.util.regex.Pattern.compile("\"" + name + "\": \"([^\"]*)\"").matcher(json);
        assertTrue(matcher.find(), name + " in " + json);
        return matcher.group(1);
    }

    static MediaTags tags(Demuxer demuxer) {
        var builder = MediaTags.builder();
        demuxer.metadata().forEach(builder::add);
        return builder.build();
    }

    @Nested
    @DisplayName("DSF")
    class Dsf {
        @ParameterizedTest(name = "1 bit per sample: {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("delivers every channel block, the padding of the last excluded from its duration")
        void blocks(boolean lsbFirst) {
            var planes = DsdSamples.random(2, 4096 * 2 + 1000, 5);
            var file = DsdSamples.dsf(2822400, 2, planes, lsbFirst, null);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                assertEquals(ContainerFormat.DSF, demuxer.format());
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(lsbFirst ? Codec.DSD_LSBF_PLANAR : Codec.DSD_MSBF_PLANAR, track.codec());
                assertEquals(2822400, track.sampleRate());
                assertEquals(ChannelLayout.STEREO, track.layout().orElseThrow());
                assertEquals((4096 * 2 + 1000) * 8L, track.duration());
                var packets = packets(demuxer);
                assertEquals(3, packets.size());
                assertEquals(1000 * 8L, packets.getLast().duration());
                assertEquals(2 * 4096 * 8L, packets.getLast().pts());
                for (var p = 0; p < 3; p++) {
                    var data = packets.get(p).data();
                    assertEquals(2 * 4096, data.length);
                    for (var c = 0; c < 2; c++) {
                        for (var i = 0; i < 4096 && p * 4096 + i < planes[c].length; i++) {
                            var expected = planes[c][p * 4096 + i];
                            if (lsbFirst) {
                                expected = DsdSamples.reverse(expected);
                            }
                            assertEquals(expected, data[c * 4096 + i]);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("maps the channel types of the specification to speakers")
        void channelTypes() {
            var expected = List.of(
                    List.of(Speaker.FRONT_CENTER),
                    List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT),
                    List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER),
                    List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT),
                    List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY),
                    List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.BACK_LEFT,
                            Speaker.BACK_RIGHT),
                    List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                            Speaker.BACK_LEFT, Speaker.BACK_RIGHT));
            for (var type = 1; type <= 7; type++) {
                var speakers = expected.get(type - 1);
                var file = DsdSamples.dsf(2822400, type, DsdSamples.random(speakers.size(), 100, type), true, null);
                try (var demuxer = new DsfDemuxer(MediaSource.of(file))) {
                    var track = (AudioTrack) demuxer.tracks().getFirst();
                    assertEquals(new ChannelLayout(speakers), track.layout().orElseThrow());
                }
            }
        }

        @Test
        @DisplayName("seeks to the block holding the target")
        void seek() {
            var file = DsdSamples.dsf(2822400, 2, DsdSamples.random(2, 4096 * 5, 9), true, null);
            try (var demuxer = new DsfDemuxer(MediaSource.of(file))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertTrue(demuxer.isSeekable());
                demuxer.seek(track.timeBase().toMicros(3L * 4096 * 8 + 100));
                assertEquals(3L * 4096 * 8, demuxer.read().pts());
                demuxer.seek(0);
                assertEquals(0, demuxer.read().pts());
                demuxer.seek(demuxer.durationMicros());
                assertNull(demuxer.read());
            }
        }

        @Test
        @DisplayName("reads the ID3v2 tag the DSD chunk points at, as ExifTool does")
        void id3() throws IOException {
            var id3 = DsdSamples.id3("TIT2", "Sigma Delta Suite", "TPE1", "Smalt Ensemble", "TALB", "One Bit");
            var file = DsdSamples.dsf(2822400, 2, DsdSamples.random(2, 5000, 2), true, id3);
            try (var demuxer = new DsfDemuxer(MediaSource.of(file))) {
                var tags = tags(demuxer);
                assertEquals("Sigma Delta Suite", tags.get(StandardTag.TITLE).orElseThrow());
                assertEquals("Smalt Ensemble", tags.get(StandardTag.ARTIST).orElseThrow());
                assertEquals("One Bit", tags.get(StandardTag.ALBUM).orElseThrow());
            }
            Oracle.assume(Oracle.Tool.EXIFTOOL);
            var path = Scratch.directory("dsf-tags").resolve("tagged.dsf");
            Files.write(path, file);
            var json = new String(Oracle.run(Oracle.Tool.EXIFTOOL, Duration.ofMinutes(1),
                    List.of("-j", "-Title", "-Artist", "-Album", "-SampleRate", "-ChannelType", path.toString())),
                    StandardCharsets.UTF_8);
            assertTrue(json.contains("\"Title\": \"Sigma Delta Suite\""), json);
            assertTrue(json.contains("\"Artist\": \"Smalt Ensemble\""), json);
            assertTrue(json.contains("\"Album\": \"One Bit\""), json);
            assertTrue(json.contains("\"SampleRate\": 2822400"), json);
        }

        @Test
        @DisplayName("agrees with ffprobe on the stream parameters and duration")
        void ffprobe() throws IOException {
            Oracle.assume(Oracle.Tool.FFPROBE);
            var file = DsdSamples.dsf(5644800, 7, DsdSamples.random(6, 4096 * 3 + 7, 4), true, null);
            var path = Scratch.directory("dsf-probe").resolve("surround.dsf");
            Files.write(path, file);
            var json = Oracle.probe(path);
            assertTrue(json.contains("\"codec_name\": \"dsd_lsbf_planar\""), json);
            assertTrue(json.contains("\"channels\": 6"), json);
            try (var demuxer = new DsfDemuxer(MediaSource.of(file))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.DSD_LSBF_PLANAR, track.codec());
                assertEquals(6, track.layout().orElseThrow().channels());
                // ffprobe counts DSD bytes as samples: its rate is the DSD rate over 8
                assertEquals(String.valueOf(track.sampleRate() / 8), field(json, "sample_rate"), json);
                // ffprobe's duration includes the padding of the last block; the fmt chunk's sample count excludes it
                assertEquals((4096 * 3 + 7) * 8L, track.duration());
                assertEquals(4 * 4096 * 8 / 5644800.0, Double.parseDouble(field(json, "duration")), 1e-5, json);
            }
        }

        @Test
        @DisplayName("rejects a file without a fmt chunk or with an unknown format")
        void malformed() {
            var file = DsdSamples.dsf(2822400, 2, DsdSamples.random(2, 100, 1), true, null);
            var noFmt = file.clone();
            noFmt[28] = 'x';
            assertThrows(MediaException.Malformed.class, () -> new DsfDemuxer(MediaSource.of(noFmt)));
            var format = file.clone();
            format[44] = 1;
            assertThrows(MediaException.Unsupported.class, () -> new DsfDemuxer(MediaSource.of(format)));
            var bits = file.clone();
            bits[60] = 3;
            assertThrows(MediaException.Malformed.class, () -> new DsfDemuxer(MediaSource.of(bits)));
        }
    }

    @Nested
    @DisplayName("DSDIFF")
    class Dsdiff {
        @Test
        @DisplayName("delivers the interleaved sound data, the channel speakers, the texts and the markers")
        void sound() {
            var planes = DsdSamples.random(6, 10000, 8);
            var ids = new String[]{"MLFT", "MRGT", "C", "LFE", "LS", "RS"};
            var rate = 2822400;
            var file = DsdSamples.dsdiff(rate, ids, planes, 0, 30000);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                assertEquals(ContainerFormat.DSDIFF, demuxer.format());
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.DSD_MSBF, track.codec());
                assertEquals(ChannelLayout.SURROUND_5_1.channels(), track.layout().orElseThrow().channels());
                assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                        Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT),
                        track.layout().orElseThrow().speakers());
                assertEquals(10000 * 8L, track.duration());
                var out = new ByteArrayOutputStream();
                for (var packet : packets(demuxer)) {
                    out.writeBytes(packet.data());
                }
                var sound = out.toByteArray();
                assertEquals(60000, sound.length);
                for (var i = 0; i < 10000; i++) {
                    for (var c = 0; c < 6; c++) {
                        assertEquals(planes[c][i], sound[i * 6 + c]);
                    }
                }
                var tags = demuxer.metadata();
                assertEquals("Smalt Ensemble", tags.get("DIAR"));
                assertEquals("Sigma Delta Suite", tags.get("DITI"));
                assertEquals("Synthesized", tags.get("COMT"));
                var chapters = demuxer.chapters();
                assertEquals(2, chapters.size());
                assertEquals(0, chapters.get(0).startMicros());
                assertEquals(track.timeBase().toMicros(30000), chapters.get(1).startMicros());
                assertEquals("Track 2", chapters.get(1).title().orElseThrow());
            }
        }

        @Test
        @DisplayName("seeks to the packet holding the target")
        void seek() {
            var planes = DsdSamples.random(2, 4096 * 4, 3);
            var file = DsdSamples.dsdiff(2822400, new String[]{"SLFT", "SRGT"}, planes);
            try (var demuxer = new DsdiffDemuxer(MediaSource.of(file))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                demuxer.seek(track.timeBase().toMicros(2L * 4096 * 8 + 5));
                var packet = demuxer.read();
                assertEquals(2L * 4096 * 8, packet.pts());
                assertEquals(planes[0][2 * 4096], packet.data()[0]);
                demuxer.seek(demuxer.durationMicros() + 1);
                assertNull(demuxer.read());
            }
        }

        @Test
        @DisplayName("agrees with ffprobe on the stream parameters")
        void ffprobe() throws IOException {
            Oracle.assume(Oracle.Tool.FFPROBE);
            var file = DsdSamples.dsdiff(5644800, new String[]{"SLFT", "SRGT"}, DsdSamples.random(2, 9000, 6));
            var path = Scratch.directory("dff-probe").resolve("stereo.dff");
            Files.write(path, file);
            var json = Oracle.probe(path);
            assertTrue(json.contains("\"codec_name\": \"dsd_msbf\""), json);
            assertTrue(json.contains("\"channels\": 2"), json);
            try (var demuxer = new DsdiffDemuxer(MediaSource.of(file))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(String.valueOf(track.sampleRate() / 8), field(json, "sample_rate"), json);
                // ffprobe's duration counts the chunks after the sound data as sound; the sound chunk's size does not
                assertEquals(9000 * 8L, track.duration());
            }
        }

        @Test
        @DisplayName("rejects a file without its properties or with an unknown compression")
        void malformed() {
            var file = DsdSamples.dsdiff(2822400, new String[]{"SLFT", "SRGT"}, DsdSamples.random(2, 100, 1));
            var text = new String(file, StandardCharsets.ISO_8859_1);
            var noProperties = file.clone();
            var at = text.indexOf("PROP");
            noProperties[at] = 'X';
            assertThrows(MediaException.Malformed.class, () -> new DsdiffDemuxer(MediaSource.of(noProperties)));
            var compression = file.clone();
            at = text.indexOf("CMPR") + 12;
            compression[at] = 'Q';
            assertThrows(MediaException.Unsupported.class, () -> new DsdiffDemuxer(MediaSource.of(compression)));
        }
    }

    @Test
    @DisplayName("random damage to either format raises only MediaException")
    void damage() {
        var files = List.of(DsdSamples.dsf(2822400, 2, DsdSamples.random(2, 9000, 1), true,
                        DsdSamples.id3("TIT2", "x")),
                DsdSamples.dsdiff(2822400, new String[]{"SLFT", "SRGT"}, DsdSamples.random(2, 9000, 2), 0, 100));
        var random = new Random(42);
        for (var original : files) {
            for (var round = 0; round < 200; round++) {
                var data = original.clone();
                for (var i = 0; i < 4; i++) {
                    data[random.nextInt(Math.min(data.length, 400))] = (byte) random.nextInt();
                }
                try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
                    packets(demuxer);
                } catch (MediaException expected) {
                    assertTrue(expected.getMessage() != null);
                }
            }
        }
        assertArrayEquals(files.get(0), files.get(0).clone());
    }
}
