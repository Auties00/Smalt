package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the Ogg Opus muxer: remuxing ffmpeg's Opus output bit-exactly at the decoded-PCM level,
 * page structure (flags, sequence numbers, lacing, CRC, granule positions, page flushing rules),
 * OpusTags content, end trimming, grouped streams and the muxer state machine.
 */
class OggMuxerTest {
    @TempDir
    Path directory;

    record Demuxed(AudioTrack track, List<Packet> packets) {
    }

    static Demuxed demux(byte[] file) {
        try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            return new Demuxed(track, AudioPipeline.packets(demuxer, track.id()));
        }
    }

    static byte[] mux(OggMuxerOptions options, AudioTrack track, List<Packet> packets) {
        var bytes = new ByteArrayOutputStream();
        try (var muxer = new OggMuxer(MediaSink.of(bytes), options)) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return bytes.toByteArray();
    }

    Path write(byte[] data, String name) {
        try {
            var file = directory.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    /** One parsed page of our own output. */
    record Page(int flags, long granule, int serial, int sequence, int[] lacing, byte[] body) {
        int packetsCompleted() {
            var count = 0;
            for (var value : lacing) {
                if (value < 255) {
                    count++;
                }
            }
            return count;
        }
    }

    static List<Page> pages(byte[] data) {
        var pages = new ArrayList<Page>();
        var offset = 0;
        while (offset < data.length) {
            assertEquals("OggS", new String(data, offset, 4, StandardCharsets.US_ASCII), "capture at " + offset);
            assertEquals(0, data[offset + 4], "version");
            var segments = data[offset + 26] & 0xFF;
            var lacing = new int[segments];
            var size = 0;
            for (var i = 0; i < segments; i++) {
                lacing[i] = data[offset + 27 + i] & 0xFF;
                size += lacing[i];
            }
            var total = 27 + segments + size;
            var copy = java.util.Arrays.copyOfRange(data, offset, offset + total);
            var stored = le32(copy, 22);
            copy[22] = copy[23] = copy[24] = copy[25] = 0;
            assertEquals(stored, new OggCrc().checksum(copy, 0, copy.length), "CRC of page at " + offset);
            var granule = 0L;
            for (var i = 7; i >= 0; i--) {
                granule = granule << 8 | (data[offset + 6 + i] & 0xFF);
            }
            pages.add(new Page(data[offset + 5] & 0xFF, granule, le32(data, offset + 14), le32(data, offset + 18), lacing,
                    java.util.Arrays.copyOfRange(data, offset + 27 + segments, offset + total)));
            offset += total;
        }
        return pages;
    }

    static int le32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16 | (data[offset + 3] & 0xFF) << 24;
    }

    @Nested
    @DisplayName("remuxing ffmpeg output")
    class Remux {
        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 2, 6})
        @DisplayName("produces a file ffmpeg decodes to exactly the same PCM as the original")
        void identicalPcm(int channels) {
            var options = new ArrayList<>(List.of("-c:a", "libopus"));
            if (channels > 2) {
                options.addAll(List.of("-mapping_family", "1"));
            }
            var source = Fixtures.tone("opus", 523, 48000, channels, 2.345, options);
            var original = demux(Fixtures.read(source));
            var remuxed = mux(OggMuxerOptions.defaults(), original.track(), original.packets());
            var output = write(remuxed, "remux" + channels + ".opus");
            Oracle.assertDecodesCleanly(output);
            assertArrayEquals(Oracle.decodeAudio(source), Oracle.decodeAudio(output));
            var probe = Oracle.probe(output);
            assertTrue(probe.contains("\"initial_padding\": 312"), probe);
            assertTrue(probe.contains("\"start_time\": \"0.000000\""), probe);
            var duration = java.util.regex.Pattern.compile("\"duration\": \"([0-9.]+)\"");
            var expectedDuration = duration.matcher(Oracle.probe(source));
            var actualDuration = duration.matcher(probe);
            assertTrue(expectedDuration.find() && actualDuration.find());
            assertEquals(expectedDuration.group(1), actualDuration.group(1), "ffprobe duration");
            var again = demux(remuxed);
            assertEquals(original.track().duration(), again.track().duration());
            assertEquals(original.packets().size(), again.packets().size());
            for (var i = 0; i < original.packets().size(); i++) {
                assertEquals(ProbedPacket.of(original.packets().get(i)), ProbedPacket.of(again.packets().get(i)), "packet " + i);
            }
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 2, 6})
        @DisplayName("remuxes ffmpeg's Ogg Vorbis to the same PCM, packets and length")
        void vorbis(int channels) {
            var source = Fixtures.tone("ogg", 523, 44100, channels, 2.345, List.of("-c:a", "libvorbis"));
            var original = demux(Fixtures.read(source));
            assertEquals(Codec.VORBIS, original.track().codec());
            var remuxed = mux(OggMuxerOptions.defaults(), original.track(), original.packets());
            var pages = pages(remuxed);
            assertEquals(OggPageReader.FLAG_BOS, pages.get(0).flags());
            assertEquals(1, pages.get(0).packetsCompleted());
            assertEquals(0, pages.get(1).granule());
            assertEquals(OggPageReader.FLAG_EOS, pages.getLast().flags() & OggPageReader.FLAG_EOS);
            var output = write(remuxed, "remux" + channels + ".ogg");
            Oracle.assertDecodesCleanly(output);
            assertArrayEquals(Oracle.decodeAudio(source), Oracle.decodeAudio(output));
            var again = demux(remuxed);
            assertEquals(original.track().duration(), again.track().duration());
            assertEquals(original.packets().size(), again.packets().size());
            for (var i = 0; i < original.packets().size(); i++) {
                assertEquals(ProbedPacket.of(original.packets().get(i)), ProbedPacket.of(again.packets().get(i)), "packet " + i);
            }
        }

        @Test
        @DisplayName("writes a voice-note layout: 16 kHz mono, 40 packets per page")
        void voiceNote() {
            var source = Fixtures.tone("opus", 440, 16000, 1, 4.1, List.of("-c:a", "libopus", "-b:a", "24k", "-application", "voip"));
            var original = demux(Fixtures.read(source));
            var options = OggMuxerOptions.builder().vendor("voice-note").comments(Map.of()).maxPacketsPerPage(40).maxPageDurationMicros(10_000_000).serial(0x1234).build();
            var remuxed = mux(options, original.track(), original.packets());
            var pages = pages(remuxed);
            var dataPages = pages.subList(2, pages.size());
            for (var i = 0; i < dataPages.size() - 1; i++) {
                assertEquals(40, dataPages.get(i).packetsCompleted(), "page " + i);
            }
            assertTrue(dataPages.getLast().packetsCompleted() <= 40);
            var output = write(remuxed, "voice.opus");
            Oracle.assertDecodesCleanly(output);
            assertArrayEquals(Oracle.decodeAudio(source), Oracle.decodeAudio(output));
        }
    }

    @Nested
    @DisplayName("page structure")
    class Structure {
        static final byte[] HEAD = head(2, 312);

        static byte[] head(int channels, int preSkip) {
            var head = new byte[19];
            System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, head, 0, 8);
            head[8] = 1;
            head[9] = (byte) channels;
            head[10] = (byte) preSkip;
            head[11] = (byte) (preSkip >> 8);
            head[12] = (byte) 0x80;
            head[13] = (byte) 0xBB;
            return head;
        }

        static AudioTrack track(int id, long duration) {
            return AudioTrack.builder().id(id).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).duration(duration)
                    .codecConfig(HEAD)
                    .sampleRate(48000)
                    .layout(ChannelLayout.STEREO)
                    .build();
        }

        /** A syntactically valid 20 ms CELT packet (TOC config 31, code 0) of the given size. */
        static Packet packet(int id, int size, long pts) {
            var data = new byte[size];
            data[0] = (byte) 0xF8;
            for (var i = 1; i < size; i++) {
                data[i] = (byte) (i * 31 + size);
            }
            return new Packet(id, data, pts, pts, 960, true);
        }

        @Test
        @DisplayName("lays out header pages, flags, sequence numbers and granule positions per RFC 7845")
        void layout() {
            var packets = new ArrayList<Packet>();
            for (var i = 0; i < 100; i++) {
                packets.add(packet(0, 50 + i, i * 960L));
            }
            var comments = new LinkedHashMap<String, String>();
            comments.put("TITLE", "Voice note");
            comments.put("ENCODER", "smalt");
            var bytes = mux(OggMuxerOptions.builder().vendor("smalt-test-vendor").comments(comments).maxPacketsPerPage(30).maxPageDurationMicros(10_000_000).serial(99).build(), track(0, -1), packets);
            var pages = pages(bytes);
            assertEquals(0x02, pages.get(0).flags(), "BOS on the OpusHead page");
            assertArrayEquals(HEAD, pages.get(0).body());
            assertEquals(0, pages.get(0).granule());
            assertEquals(0, pages.get(1).flags());
            assertEquals(0, pages.get(1).granule());
            var tags = pages.get(1).body();
            assertEquals("OpusTags", new String(tags, 0, 8, StandardCharsets.US_ASCII));
            var vendorLength = le32(tags, 8);
            assertEquals("smalt-test-vendor", new String(tags, 12, vendorLength, StandardCharsets.UTF_8));
            var metadata = new LinkedHashMap<String, String>();
            com.github.auties00.smalt.container.internal.Annotations.readVorbisComments(tags, 8, tags.length - 8, metadata, null);
            assertEquals(comments, metadata);
            for (var i = 0; i < pages.size(); i++) {
                assertEquals(99, pages.get(i).serial());
                assertEquals(i, pages.get(i).sequence());
            }
            var granule = 0L;
            for (var i = 2; i < pages.size(); i++) {
                var page = pages.get(i);
                granule += page.packetsCompleted() * 960L;
                assertEquals(granule, page.granule(), "granule of page " + i);
                assertEquals(i == pages.size() - 1 ? 0x04 : 0, page.flags());
                assertTrue(page.packetsCompleted() <= 30);
            }
            assertEquals(96000, granule);
        }

        @ParameterizedTest(name = "{0} bytes")
        @ValueSource(ints = {1, 254, 255, 256, 510, 765, 65024, 65025, 65026, 70000, 130050})
        @DisplayName("laces packets of every size, including multiples of 255 and packets spanning pages")
        void lacing(int size) {
            var packets = List.of(packet(0, 100, 0), packet(0, size, 960), packet(0, 77, 1920));
            var bytes = mux(OggMuxerOptions.defaults(), track(0, -1), packets);
            var pages = pages(bytes);
            for (var page : pages) {
                if (page.lacing().length > 0 && (page.flags() & 0x01) != 0) {
                    assertTrue(pages.indexOf(page) > 2);
                }
            }
            var again = demux(bytes);
            assertEquals(3, again.packets().size());
            for (var i = 0; i < 3; i++) {
                assertArrayEquals(packets.get(i).data(), again.packets().get(i).data());
            }
            var probed = ProbedPacket.probe(write(bytes, "lacing" + size + ".opus"));
            assertEquals(3, probed.size());
            assertEquals(ProbedPacket.crc32(packets.get(1).data()), probed.get(1).crc32());
            if (size % 255 == 0 && size < 65025) {
                var dataPage = pages.get(2);
                var zeroTerminated = false;
                for (var i = 1; i < dataPage.lacing().length; i++) {
                    zeroTerminated |= dataPage.lacing()[i] == 0 && dataPage.lacing()[i - 1] == 255;
                }
                assertTrue(zeroTerminated, "a packet of 255*k bytes ends with a zero lacing value");
            }
        }

        @Test
        @DisplayName("flushes pages when their duration reaches the configured limit")
        void duration() {
            var packets = new ArrayList<Packet>();
            for (var i = 0; i < 50; i++) {
                packets.add(packet(0, 40, i * 960L));
            }
            var pages = pages(mux(OggMuxerOptions.builder().vendor("v").comments(Map.of()).maxPacketsPerPage(255).maxPageDurationMicros(100_000).serial(1).build(), track(0, -1), packets));
            for (var i = 2; i < pages.size(); i++) {
                assertEquals(5, pages.get(i).packetsCompleted(), "100 ms of 20 ms packets per page");
            }
        }

        @Test
        @DisplayName("trims the final granule to the declared track duration")
        void endTrimming() {
            var packets = new ArrayList<Packet>();
            for (var i = 0; i < 10; i++) {
                packets.add(packet(0, 40, i * 960L));
            }
            var pages = pages(mux(OggMuxerOptions.defaults(), track(0, 9000), packets));
            assertEquals(312 + 9000, pages.getLast().granule());
            var tooShort = pages(mux(OggMuxerOptions.builder().vendor("v").comments(Map.of()).maxPacketsPerPage(4).maxPageDurationMicros(10_000_000).build(), track(0, 5), packets));
            assertEquals(8 * 960, tooShort.getLast().granule(), "never below the previous page's granule");
            var viaPacket = new ArrayList<>(packets);
            var last = packets.getLast();
            viaPacket.set(9, new Packet(0, last.data(), last.pts(), last.dts(), 500, true));
            assertEquals(9600 - 460, pages(mux(OggMuxerOptions.defaults(), track(0, -1), viaPacket)).getLast().granule());
        }

        @Test
        @DisplayName("interleaves grouped streams with consecutive serial numbers")
        void grouped() {
            var bytes = new ByteArrayOutputStream();
            try (var muxer = new OggMuxer(MediaSink.of(bytes), OggMuxerOptions.builder().vendor("v").comments(Map.of())
                    .maxPacketsPerPage(10).maxPageDurationMicros(10_000_000).serial(500).build())) {
                muxer.addTrack(track(3, -1));
                muxer.addTrack(track(7, -1));
                for (var i = 0; i < 40; i++) {
                    muxer.writePacket(packet(3, 60, i * 960L));
                    muxer.writePacket(packet(7, 61, i * 960L));
                }
                muxer.finish();
            }
            var pages = pages(bytes.toByteArray());
            assertEquals(0x02, pages.get(0).flags());
            assertEquals(0x02, pages.get(1).flags());
            assertEquals(500, pages.get(0).serial());
            assertEquals(501, pages.get(1).serial());
            try (var demuxer = new OggDemuxer(MediaSource.of(bytes.toByteArray()))) {
                assertEquals(2, demuxer.tracks().size());
                var counts = new int[2];
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    counts[packet.trackId()]++;
                }
                assertArrayEquals(new int[]{40, 40}, counts);
            }
            Oracle.assume(Oracle.Tool.FFPROBE);
            var probe = Oracle.probe(write(bytes.toByteArray(), "grouped.opus"));
            assertEquals(2, probe.split("\"codec_name\": \"opus\"").length - 1, probe);
        }

        @Test
        @DisplayName("writes a valid empty stream when no packet arrives")
        void empty() {
            var bytes = mux(OggMuxerOptions.defaults(), track(0, -1), List.of());
            var pages = pages(bytes);
            assertEquals(3, pages.size());
            assertEquals(0x04, pages.getLast().flags());
            assertEquals(0, demux(bytes).packets().size());
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {
        @Test
        @DisplayName("rejects misuse with the documented exceptions")
        void misuse() {
            var output = Channels.newChannel(new ByteArrayOutputStream());
            var muxer = new OggMuxer(MediaSink.of(Channels.newOutputStream(output)), OggMuxerOptions.defaults());
            var vorbis = AudioTrack.builder().codec(Codec.VORBIS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(ChannelLayout.STEREO)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(vorbis), "Vorbis without headers");
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(vorbis.toBuilder().codecConfig(new byte[]{2, 1, 1, 0, 0, 0}).build()),
                    "Vorbis with invalid headers");
            var flac = AudioTrack.builder().codec(Codec.FLAC).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(ChannelLayout.STEREO).build();
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(flac));
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(Structure.packet(0, 10, 0)), "no track yet");
            assertThrows(IllegalStateException.class, muxer::finish, "no track yet");
            muxer.addTrack(Structure.track(0, -1));
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(Structure.track(0, -1)));
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(Structure.packet(1, 10, 0)));
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(0, new byte[0], 0, 0, 0, true)));
            muxer.writePacket(Structure.packet(0, 10, 0));
            assertThrows(IllegalStateException.class, () -> muxer.addTrack(Structure.track(1, -1)));
            muxer.finish();
            assertThrows(IllegalStateException.class, muxer::finish);
            assertThrows(IllegalStateException.class, () -> muxer.writePacket(Structure.packet(0, 10, 0)));
        }

        @Test
        @DisplayName("synthesizes an OpusHead for a stereo track without configuration")
        void synthesizedHead() {
            var track = AudioTrack.builder().codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(ChannelLayout.STEREO)
                    .skipSamples(312)
                    .build();
            var bytes = mux(OggMuxerOptions.defaults(), track, List.of(Structure.packet(0, 20, 0)));
            var head = pages(bytes).getFirst().body();
            assertEquals(2, head[9]);
            assertEquals(312, (head[10] & 0xFF) | (head[11] & 0xFF) << 8);
        }

        @Test
        @DisplayName("validates its options")
        void options() {
            assertThrows(IllegalArgumentException.class, () -> OggMuxerOptions.builder().vendor("v").comments(Map.of()).maxPacketsPerPage(0).maxPageDurationMicros(1).build());
            assertThrows(IllegalArgumentException.class, () -> OggMuxerOptions.builder().vendor("v").comments(Map.of()).maxPacketsPerPage(256).maxPageDurationMicros(1).build());
            assertThrows(IllegalArgumentException.class, () -> OggMuxerOptions.builder().vendor("v").comments(Map.of()).maxPacketsPerPage(1).maxPageDurationMicros(0).build());
            assertThrows(IllegalArgumentException.class, () -> OggMuxerOptions.builder().vendor("v").comments(Map.of("A=B", "x")).maxPacketsPerPage(1).maxPageDurationMicros(1).build());
            assertThrows(IllegalArgumentException.class, () -> OggMuxerOptions.builder().vendor("v").comments(Map.of("", "x")).maxPacketsPerPage(1).maxPageDurationMicros(1).build());
        }

        @Test
        @DisplayName("translates output failures into MediaException.Io")
        void ioFailure() {
            WritableByteChannel failing = new WritableByteChannel() {
                @Override
                public int write(ByteBuffer source) throws IOException {
                    throw new IOException("disk full");
                }

                @Override
                public boolean isOpen() {
                    return true;
                }

                @Override
                public void close() {
                }
            };
            var muxer = new OggMuxer(MediaSink.of(Channels.newOutputStream(failing)), OggMuxerOptions.defaults());
            muxer.addTrack(Structure.track(0, -1));
            assertThrows(MediaException.Io.class, () -> muxer.writePacket(Structure.packet(0, 10, 0)));
        }
    }

    @Test
    @DisplayName("round-trips thousands of packets within a second")
    void throughput() {
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < 20_000; i++) {
            packets.add(Structure.packet(0, 40 + i % 200, i * 960L));
        }
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var bytes = mux(OggMuxerOptions.defaults(), Structure.track(0, -1), packets);
            assertEquals(20_000, demux(bytes).packets().size());
        });
    }
}
