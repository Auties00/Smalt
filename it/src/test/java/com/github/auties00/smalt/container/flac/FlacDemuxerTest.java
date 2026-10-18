package com.github.auties00.smalt.container.flac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.flac.FlacDecoder;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.audio.flac.FlacConformanceCorpus;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers native FLAC demuxing: packet-for-packet agreement with ffprobe, metadata and codec
 * configuration, tag stripping, streams without the fLaC marker, truncation, unknown-length sources
 * and mutated input.
 */
class FlacDemuxerTest {
    static Path fixture(int rate, int channels, List<String> options) {
        var exprs = new ArrayList<String>();
        for (var c = 0; c < channels; c++) {
            exprs.add("0.6*sin(2*PI*" + (220 + 110 * c) + "*t)+0.3*(random(" + c + ")-0.5)");
        }
        var output = new ArrayList<>(List.of("-c:a", "flac"));
        if (!options.contains("-sample_fmt")) {
            output.addAll(List.of("-sample_fmt", "s16"));
        }
        output.addAll(options);
        return Fixtures.ffmpeg("flac", List.of("-f", "lavfi", "-i", "aevalsrc=exprs=" + String.join("|", exprs)
                + ":s=" + rate + ":d=2"), output);
    }

    static Path standard() {
        return fixture(44100, 2, List.of("-metadata", "title=Smalt test", "-metadata", "artist=Media"));
    }

    static List<Packet> packets(MediaSource source) {
        try (var demuxer = new FlacDemuxer(source)) {
            var result = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                result.add(packet);
            }
            assertNull(demuxer.read(), "end of stream is sticky");
            return result;
        }
    }

    static List<ProbedPacket> probed(List<Packet> packets) {
        return packets.stream().map(ProbedPacket::of).toList();
    }

    static void decodeAll(byte[] file) {
        try (var demuxer = new FlacDemuxer(MediaSource.of(file));
             var decoder = new FlacDecoder((AudioTrack) demuxer.tracks().getFirst())) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                decoder.decode(packet, buffer -> {
                });
            }
        }
    }

    @Nested
    @DisplayName("ffprobe agreement")
    class Probe {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"16-bit stereo", "24-bit stereo", "frame size 16", "frame size 65535", "8 channels",
                "32-bit", "mono 8 kHz"})
        @DisplayName("returns the same packets as ffprobe: data, timestamps and durations")
        void fixtures(String name) {
            var file = switch (name) {
                case "16-bit stereo" -> standard();
                case "24-bit stereo" -> fixture(48000, 2, List.of("-sample_fmt", "s32", "-bits_per_raw_sample", "24"));
                case "frame size 16" -> fixture(44100, 2, List.of("-frame_size", "16"));
                case "frame size 65535" -> fixture(44100, 1, List.of("-frame_size", "65535"));
                case "8 channels" -> fixture(48000, 8, List.of());
                case "32-bit" -> fixture(48000, 2, List.of("-sample_fmt", "s32", "-strict", "experimental"));
                default -> fixture(8000, 1, List.of());
            };
            try (var source = MediaSource.of(file)) {
                assertEquals(ProbedPacket.probe(file), probed(packets(source)));
            }
        }

        @ParameterizedTest(name = "subset/{0}")
        @ValueSource(strings = {"24", "25", "26", "27"})
        @DisplayName("splits variable-blocksize corpus files like ffprobe")
        void variableBlocksize(String number) {
            var vector = FlacConformanceCorpus.vector("subset", number);
            try (var source = MediaSource.of(vector)) {
                var ours = probed(packets(source));
                var theirs = ProbedPacket.probe(vector);
                assertEquals(theirs.size(), ours.size(), "packet count");
                var position = 0L;
                for (var i = 0; i < ours.size(); i++) {
                    assertEquals(theirs.get(i).crc32(), ours.get(i).crc32(), "packet " + i + " data");
                    assertEquals(theirs.get(i).size(), ours.get(i).size(), "packet " + i + " size");
                    // file 27 sets the fixed-blocking bit but numbers samples (the pre-1.0 variable-blocksize
                    // format); ffprobe multiplies those sample numbers by the block size, we count samples
                    assertEquals(number.equals("27") ? position : theirs.get(i).pts(), ours.get(i).pts(), "packet " + i + " pts");
                    position += ours.get(i).duration();
                }
            }
        }
    }

    @Nested
    @DisplayName("stream description")
    class Description {
        @Test
        @DisplayName("describes the track from STREAMINFO and exposes the Vorbis comments")
        void track() {
            var file = standard();
            try (var source = MediaSource.of(file); var demuxer = new FlacDemuxer(source)) {
                assertEquals(ContainerFormat.FLAC, demuxer.format());
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(1, demuxer.tracks().size());
                assertEquals(Codec.FLAC, track.codec());
                assertEquals(Rational.timeBaseOf(44100), track.timeBase());
                assertEquals(44100, track.sampleRate());
                assertEquals(ChannelLayout.STEREO, track.layout().orElseThrow());
                assertEquals(16, track.bitsPerSample());
                assertEquals(88200, track.duration());
                assertEquals(0, track.skipSamples());
                assertEquals(2_000_000, demuxer.durationMicros());
                assertEquals("Smalt test", demuxer.metadata().get("title"));
                assertEquals("Media", demuxer.metadata().get("artist"));
                assertThrows(UnsupportedOperationException.class, () -> demuxer.metadata().put("x", "y"));
            }
        }

        @Test
        @DisplayName("keeps every metadata block but padding and pictures in the codec configuration")
        void codecConfig() {
            var picture = FlacConformanceCorpus.vector("subset", "56");
            for (var file : List.of(standard(), picture)) {
                try (var source = MediaSource.of(file); var demuxer = new FlacDemuxer(source)) {
                    var config = demuxer.tracks().getFirst().codecConfig().orElseThrow();
                    assertArrayEquals("fLaC".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(config, 4));
                    var position = 4;
                    var types = new ArrayList<Integer>();
                    var last = false;
                    while (position < config.length) {
                        assertFalse(last, "a block follows the last-block flag");
                        last = (config[position] & 0x80) != 0;
                        types.add(config[position] & 0x7F);
                        position += 4 + ((config[position + 1] & 0xFF) << 16 | (config[position + 2] & 0xFF) << 8
                                | config[position + 3] & 0xFF);
                    }
                    assertEquals(config.length, position);
                    assertTrue(last, "the final block carries the last-block flag");
                    assertEquals(0, types.getFirst());
                    assertFalse(types.contains(1), "padding kept");
                    assertFalse(types.contains(6), "picture kept");
                    assertTrue(types.contains(4), "Vorbis comment dropped");
                }
            }
        }

        @Test
        @DisplayName("reports an unknown duration when STREAMINFO has no sample count")
        void unknownDuration() {
            var file = FlacConformanceCorpus.vector("subset", "45");
            try (var source = MediaSource.of(file); var demuxer = new FlacDemuxer(source)) {
                assertEquals(-1, demuxer.tracks().getFirst().duration());
                assertEquals(-1, demuxer.durationMicros());
            }
        }

        @Test
        @DisplayName("returns no packets for a stream with metadata only")
        void metadataOnly() {
            var file = Fixtures.read(standard());
            var headerEnd = firstFrameOffset(file);
            try (var demuxer = new FlacDemuxer(MediaSource.of(Arrays.copyOf(file, headerEnd)))) {
                assertNull(demuxer.read());
                assertEquals(1, demuxer.tracks().size());
            }
        }
    }

    /** Returns the offset of the first frame of a regular file by walking its metadata blocks. */
    static int firstFrameOffset(byte[] file) {
        var position = 4;
        while (true) {
            var last = (file[position] & 0x80) != 0;
            position += 4 + ((file[position + 1] & 0xFF) << 16 | (file[position + 2] & 0xFF) << 8 | file[position + 3] & 0xFF);
            if (last) {
                return position;
            }
        }
    }

    @Nested
    @DisplayName("framing variants")
    class Variants {
        @Test
        @DisplayName("skips leading ID3v2 tags and trailing ID3v1, APE and Lyrics3 tags")
        void tags() {
            var file = Fixtures.read(standard());
            var expected = probed(packets(MediaSource.of(file)));
            var id3v2 = id3v2Tag("TIT2", "Leading title", true);
            var out = new ByteArrayOutputStream();
            out.writeBytes(id3v2);
            out.writeBytes(id3v2Tag("TPE1", "Second tag", false));
            out.writeBytes(file);
            out.writeBytes(apeTag());
            var id3v1 = new byte[128];
            id3v1[0] = 'T';
            id3v1[1] = 'A';
            id3v1[2] = 'G';
            out.writeBytes(id3v1);
            var tagged = out.toByteArray();
            assertEquals(expected, probed(packets(MediaSource.of(tagged))));
            try (var demuxer = new FlacDemuxer(MediaSource.of(tagged))) {
                assertEquals("Leading title", demuxer.metadata().get("TIT2"));
                assertEquals("Second tag", demuxer.metadata().get("TPE1"));
                assertEquals("Smalt test", demuxer.metadata().get("title"));
            }
            decodeAll(tagged);
        }

        @Test
        @DisplayName("finds the first frame of a stream without metadata, even behind garbage with false syncs")
        void headerless() {
            var file = Fixtures.read(standard());
            var frames = Arrays.copyOfRange(file, firstFrameOffset(file), file.length);
            var expected = probed(packets(MediaSource.of(file)));
            var garbage = new byte[5000];
            var random = new SplittableRandom(7);
            for (var i = 0; i < garbage.length; i++) {
                garbage[i] = (byte) random.nextInt(256);
            }
            for (var i = 0; i + 1 < garbage.length; i += 97) {
                garbage[i] = (byte) 0xFF;
                garbage[i + 1] = (byte) 0xF8;
            }
            for (var prefix : List.of(new byte[0], garbage)) {
                var stream = new byte[prefix.length + frames.length];
                System.arraycopy(prefix, 0, stream, 0, prefix.length);
                System.arraycopy(frames, 0, stream, prefix.length, frames.length);
                try (var demuxer = new FlacDemuxer(MediaSource.of(stream))) {
                    var track = (AudioTrack) demuxer.tracks().getFirst();
                    assertTrue(track.codecConfig().isEmpty());
                    assertEquals(-1, track.duration());
                    assertEquals(44100, track.sampleRate());
                    assertEquals(16, track.bitsPerSample());
                }
                assertEquals(expected, probed(packets(MediaSource.of(stream))));
                decodeAll(stream);
            }
        }

        @Test
        @DisplayName("reads a source of unknown length")
        void unknownLength() {
            var file = Fixtures.read(standard());
            var inner = MediaSource.of(file);
            var source = new MediaSource() {
                @Override
                public long size() {
                    return -1;
                }

                @Override
                public int read(long position, byte[] buffer, int offset, int length) {
                    return inner.read(position, buffer, offset, Math.min(length, 1000));
                }

                @Override
                public void close() {
                }
            };
            assertEquals(probed(packets(MediaSource.of(file))), probed(packets(source)));
        }

        @Test
        @DisplayName("returns a truncated last frame, which the decoder rejects, and fails on truncated metadata")
        void truncation() {
            var file = Fixtures.read(standard());
            var all = packets(MediaSource.of(file));
            var cut = Arrays.copyOf(file, file.length - all.getLast().size() / 2);
            var packets = packets(MediaSource.of(cut));
            assertEquals(all.size(), packets.size());
            assertThrows(MediaException.Malformed.class, () -> decodeAll(cut));
            for (var length : new int[]{0, 3, 10, 40, firstFrameOffset(file) - 1}) {
                var head = Arrays.copyOf(file, length);
                assertThrows(MediaException.Malformed.class, () -> new FlacDemuxer(MediaSource.of(head)),
                        "truncated to " + length);
            }
        }

        @Test
        @DisplayName("enforces the allocation limit on metadata blocks")
        void limits() {
            var file = Fixtures.read(standard());
            var tight = MediaLimits.builder().maxDimension(16).maxPixels(256).maxFrames(16).maxChannels(8).maxBytes(100)
                    .build();
            assertThrows(MediaException.LimitExceeded.class, () -> new FlacDemuxer(MediaSource.of(file),
                    DemuxerOptions.builder().limits(tight).build()));
            var oneChannel = MediaLimits.builder().maxDimension(16).maxPixels(256).maxFrames(16).maxChannels(1)
                    .maxBytes(1 << 20).build();
            assertThrows(MediaException.LimitExceeded.class, () -> new FlacDemuxer(MediaSource.of(file),
                    DemuxerOptions.builder().limits(oneChannel).build()));
        }

        @Test
        @DisplayName("rejects frames of STREAMINFO's format that break its bounds")
        void streamInfoBounds() {
            var file = Fixtures.read(fixture(44100, 1, List.of("-frame_size", "1024")));
            // STREAMINFO body starts at 8: max block size at 10..11, max frame size at 15..17
            var smallMaxBlock = file.clone();
            smallMaxBlock[10] = 0;
            smallMaxBlock[11] = (byte) 200;
            smallMaxBlock[8] = 0;
            smallMaxBlock[9] = 16;
            assertThrows(MediaException.Malformed.class, () -> packets(MediaSource.of(smallMaxBlock)));
            var smallMaxFrame = file.clone();
            smallMaxFrame[15] = 0;
            smallMaxFrame[16] = 0;
            smallMaxFrame[17] = 20;
            assertThrows(MediaException.Malformed.class, () -> packets(MediaSource.of(smallMaxFrame)));
            var shortTotal = file.clone();
            shortTotal[21] &= (byte) 0xF0;
            shortTotal[22] = 0;
            shortTotal[23] = 0;
            shortTotal[24] = 0;
            shortTotal[25] = 100;
            assertThrows(MediaException.Malformed.class, () -> packets(MediaSource.of(shortTotal)));
        }

        @Test
        @DisplayName("rejects streams that are not FLAC")
        void notFlac() {
            var random = new SplittableRandom(1);
            var noise = new byte[4096];
            for (var i = 0; i < noise.length; i++) {
                noise[i] = (byte) random.nextInt(256);
            }
            assertThrows(MediaException.Malformed.class, () -> new FlacDemuxer(MediaSource.of(noise)));
            assertThrows(MediaException.Malformed.class, () -> new FlacDemuxer(MediaSource.of(new byte[0])));
        }
    }

    static byte[] id3v2Tag(String frame, String text, boolean footer) {
        var body = new ByteArrayOutputStream();
        var payload = ("\u0003" + text).getBytes(StandardCharsets.UTF_8);
        body.writeBytes(frame.getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(syncsafe(payload.length));
        body.write(0);
        body.write(0);
        body.writeBytes(payload);
        body.writeBytes(new byte[37]);
        var bytes = body.toByteArray();
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'D', '3', 4, 0, (byte) (footer ? 0x10 : 0)});
        out.writeBytes(syncsafe(bytes.length));
        out.writeBytes(bytes);
        if (footer) {
            out.writeBytes(new byte[]{'3', 'D', 'I', 4, 0, 0x10});
            out.writeBytes(syncsafe(bytes.length));
        }
        return out.toByteArray();
    }

    static byte[] syncsafe(int value) {
        return new byte[]{(byte) (value >>> 21 & 0x7F), (byte) (value >>> 14 & 0x7F), (byte) (value >>> 7 & 0x7F), (byte) (value & 0x7F)};
    }

    static byte[] apeTag() {
        var footer = new byte[32];
        System.arraycopy("APETAGEX".getBytes(StandardCharsets.US_ASCII), 0, footer, 0, 8);
        footer[8] = (byte) 0xD0;
        footer[9] = 0x07;
        footer[12] = 32 + 16;
        var tag = new byte[16 + 32];
        tag[0] = (byte) 0xFF;
        tag[1] = (byte) 0xF8;
        System.arraycopy(footer, 0, tag, 16, 32);
        return tag;
    }

    @Test
    @DisplayName("mutated files decode or fail with a MediaException, never hang or leak")
    void robustness() {
        var inputs = List.of(
                Fixtures.read(standard()),
                Fixtures.read(fixture(48000, 2, List.of("-sample_fmt", "s32", "-bits_per_raw_sample", "24", "-frame_size", "576"))),
                Fixtures.read(fixture(22050, 1, List.of("-frame_size", "192", "-compression_level", "12"))),
                Fixtures.read(fixture(48000, 2, List.of("-sample_fmt", "s32", "-strict", "experimental", "-frame_size", "1024"))));
        var outcomes = AudioMutations.assertRobust(inputs, 100, 20260928L, Duration.ofSeconds(20), FlacDemuxerTest::decodeAll);
        System.out.println("FLAC mutation outcomes: " + outcomes);
        assertEquals(400, outcomes.values().stream().mapToInt(Integer::intValue).sum());
    }
}
