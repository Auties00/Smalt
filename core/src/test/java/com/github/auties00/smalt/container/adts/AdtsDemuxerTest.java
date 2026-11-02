package com.github.auties00.smalt.container.adts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the ADTS demuxer: packets equal to ffprobe's frames minus their headers for ffmpeg's AAC
 * output at several rates and layouts, the synthesized AudioSpecificConfig (including a copied
 * program configuration element), CRC-protected and multi-block frames, tags, resynchronization and
 * mutation robustness.
 */
class AdtsDemuxerTest {
    record Frame(long pts, long duration, int size, long pos) {
    }

    static List<Frame> probeFrames(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_entries", "packet=pts,duration,size,pos", "-of", "compact=p=0:nk=1", file.toString())), StandardCharsets.UTF_8);
        var frames = new ArrayList<Frame>();
        for (var line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            var fields = line.split("\\|");
            frames.add(new Frame(Long.parseLong(fields[0]), Long.parseLong(fields[1]), Integer.parseInt(fields[2]), Long.parseLong(fields[3])));
        }
        return frames;
    }

    static List<Packet> packets(byte[] data) {
        try (var demuxer = new AdtsDemuxer(MediaSource.of(data))) {
            return AudioPipeline.packets(demuxer, 0);
        }
    }

    @ParameterizedTest(name = "{0}ch {1}Hz")
    @CsvSource({"1, 8000", "1, 16000", "2, 22050", "2, 44100", "2, 48000", "6, 48000", "8, 48000", "1, 96000", "2, 11025", "2, 7350"})
    @DisplayName("returns every raw data block of ffmpeg's ADTS output, timestamped like ffprobe")
    void ffmpeg(int channels, int rate) {
        var file = Fixtures.tone("aac", 440, rate, channels, 0.77, List.of("-c:a", "aac", "-f", "adts"));
        var bytes = Fixtures.read(file);
        var frames = probeFrames(file);
        try (var demuxer = new AdtsDemuxer(MediaSource.of(bytes))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.AAC, track.codec());
            assertEquals(rate, track.sampleRate());
            var packets = AudioPipeline.packets(demuxer, 0);
            assertEquals(frames.size(), packets.size());
            assertEquals(frames.size() * 1024L, track.duration());
            for (var i = 0; i < frames.size(); i++) {
                var frame = frames.get(i);
                var payload = Arrays.copyOfRange(bytes, (int) frame.pos() + 7, (int) (frame.pos() + frame.size()));
                assertArrayEquals(payload, packets.get(i).data(), "packet " + i);
                assertEquals(frame.pts() * rate / 28_224_000, packets.get(i).pts());
                assertEquals(1024, packets.get(i).duration());
            }
            var config = track.codecConfig().orElseThrow();
            var channelConfig = channels == 8 ? 7 : channels;
            var expected = new byte[]{(byte) (2 << 3 | index(rate) >> 1), (byte) ((index(rate) & 1) << 7 | channelConfig << 3)};
            assertArrayEquals(expected, config);
        }
    }

    static int index(int rate) {
        return List.of(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350).indexOf(rate);
    }

    static byte[] ffmpegAdts(int channels, int rate, double seconds) {
        return Fixtures.read(Fixtures.tone("aac", 440, rate, channels, seconds, List.of("-c:a", "aac", "-f", "adts")));
    }

    /** Splits a CRC-less ADTS stream into its frames (header included). */
    static List<byte[]> frames(byte[] data) {
        var frames = new ArrayList<byte[]>();
        var offset = 0;
        while (offset + 7 <= data.length) {
            var length = ((data[offset + 3] & 3) << 11) | ((data[offset + 4] & 0xFF) << 3) | ((data[offset + 5] & 0xFF) >> 5);
            frames.add(Arrays.copyOfRange(data, offset, offset + length));
            offset += length;
        }
        return frames;
    }

    static byte[] withLength(byte[] header, int length, int blocks, boolean crc) {
        var copy = header.clone();
        copy[1] = (byte) (crc ? copy[1] & ~1 : copy[1] | 1);
        copy[3] = (byte) ((copy[3] & 0xFC) | (length >> 11));
        copy[4] = (byte) (length >> 3);
        copy[5] = (byte) ((copy[5] & 0x1F) | (length & 7) << 5);
        copy[6] = (byte) ((copy[6] & 0xFC) | (blocks - 1));
        return copy;
    }

    @Nested
    @DisplayName("frame variants")
    class Variants {
        @Test
        @DisplayName("strips the CRC word of protected single-block frames")
        void crcFrames() {
            var original = frames(ffmpegAdts(2, 44100, 0.5));
            var protectedStream = new ByteArrayOutputStream();
            for (var frame : original) {
                var header = withLength(Arrays.copyOf(frame, 7), frame.length + 2, 1, true);
                protectedStream.writeBytes(header);
                protectedStream.writeBytes(new byte[]{(byte) 0xAB, (byte) 0xCD});
                protectedStream.writeBytes(Arrays.copyOfRange(frame, 7, frame.length));
            }
            var packets = packets(protectedStream.toByteArray());
            assertEquals(original.size(), packets.size());
            for (var i = 0; i < original.size(); i++) {
                assertArrayEquals(Arrays.copyOfRange(original.get(i), 7, original.get(i).length), packets.get(i).data());
            }
        }

        @Test
        @DisplayName("splits CRC-protected frames holding two raw data blocks into two packets")
        void multiBlockWithCrc() {
            var original = frames(ffmpegAdts(1, 48000, 0.5));
            var stream = new ByteArrayOutputStream();
            for (var i = 0; i + 1 < original.size(); i += 2) {
                var first = Arrays.copyOfRange(original.get(i), 7, original.get(i).length);
                var second = Arrays.copyOfRange(original.get(i + 1), 7, original.get(i + 1).length);
                var length = 7 + 2 + 2 + first.length + 2 + second.length + 2;
                stream.writeBytes(withLength(Arrays.copyOf(original.get(i), 7), length, 2, true));
                var position = 7 + 2 + 2 + first.length + 2;
                stream.writeBytes(new byte[]{(byte) (position >> 8), (byte) position, 0x11, 0x22});
                stream.writeBytes(first);
                stream.writeBytes(new byte[]{0x33, 0x44});
                stream.writeBytes(second);
                stream.writeBytes(new byte[]{0x55, 0x66});
            }
            var packets = packets(stream.toByteArray());
            var expectedCount = original.size() / 2 * 2;
            assertEquals(expectedCount, packets.size());
            for (var i = 0; i < expectedCount; i++) {
                assertArrayEquals(Arrays.copyOfRange(original.get(i), 7, original.get(i).length), packets.get(i).data(), "block " + i);
                assertEquals(1024L * i, packets.get(i).pts());
            }
        }

        @Test
        @DisplayName("rejects a raw data block position past the end of its frame")
        void blockPastFrame() {
            var length = 7 + 2 + 2 + 4 + 2 + 4 + 2;
            var stream = new ByteArrayOutputStream();
            stream.writeBytes(withLength(new byte[]{(byte) 0xFF, (byte) 0xF1, (byte) (1 << 6 | 3 << 2), 0, 0, 0, 0},
                    length, 2, true));
            // the second block claims to start at offset 0x0200 of a 23-byte frame
            stream.writeBytes(new byte[]{0x02, 0x00, 0x11, 0x22, 1, 2, 3, 4, 0x33, 0x44, 5, 6, 7, 8, 0x55, 0x66});
            var failure = assertThrows(MediaException.Malformed.class, () -> packets(stream.toByteArray()));
            assertNull(failure.getCause(), "the position is checked, not caught");
        }

        @Test
        @DisplayName("passes CRC-less multi-block frames through as one packet of 1024 samples per block")
        void multiBlockWithoutCrc() {
            var original = frames(ffmpegAdts(1, 48000, 0.3));
            var stream = new ByteArrayOutputStream();
            var first = Arrays.copyOfRange(original.get(0), 7, original.get(0).length);
            var second = Arrays.copyOfRange(original.get(1), 7, original.get(1).length);
            stream.writeBytes(withLength(Arrays.copyOf(original.get(0), 7), 7 + first.length + second.length, 2, false));
            stream.writeBytes(first);
            stream.writeBytes(second);
            for (var i = 2; i < original.size(); i++) {
                stream.writeBytes(original.get(i));
            }
            var packets = packets(stream.toByteArray());
            assertEquals(original.size() - 1, packets.size());
            assertEquals(2048, packets.getFirst().duration());
            assertEquals(first.length + second.length, packets.getFirst().size());
            assertEquals(2048, packets.get(1).pts());
        }

        @Test
        @DisplayName("copies a leading program configuration element into the AudioSpecificConfig")
        void programConfig() {
            var block = new MsbBitWriter(64);
            block.write(5, 3);
            writePce(block);
            block.write(7, 3);
            block.alignWithZeros();
            var payload = block.toByteArray();
            var header = withLength(new byte[]{(byte) 0xFF, (byte) 0xF1, (byte) (1 << 6 | 3 << 2), 0, 0, 0, 0}, 7 + payload.length, 1, false);
            var frame = new ByteArrayOutputStream();
            frame.writeBytes(header);
            frame.writeBytes(payload);
            var bytes = frame.toByteArray();
            var twice = new byte[bytes.length * 2];
            System.arraycopy(bytes, 0, twice, 0, bytes.length);
            System.arraycopy(bytes, 0, twice, bytes.length, bytes.length);
            try (var demuxer = new AdtsDemuxer(MediaSource.of(twice))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertTrue(track.layout().isEmpty());
                var expected = new MsbBitWriter(64);
                expected.write(2, 5);
                expected.write(3, 4);
                expected.write(0, 4);
                expected.write(0, 3);
                writePce(expected);
                expected.alignWithZeros();
                assertArrayEquals(expected.toByteArray(), track.codecConfig().orElseThrow());
            }
        }

        static void writePce(MsbBitWriter writer) {
            writer.write(0, 4);
            writer.write(1, 2);
            writer.write(3, 4);
            writer.write(1, 4);
            writer.write(1, 4);
            writer.write(0, 4);
            writer.write(1, 2);
            writer.write(0, 3);
            writer.write(0, 4);
            writer.write(0, 1);
            writer.write(1, 1);
            writer.write(2, 4);
            writer.write(0, 1);
            writer.write(1, 1);
            writer.write(0, 4);
            writer.write(0, 1);
            writer.write(1, 4);
            writer.write(3, 4);
            writer.alignWithZeros();
            writer.write(2, 8);
            writer.write('h', 8);
            writer.write('i', 8);
        }
    }

    @Nested
    @DisplayName("tags and damage")
    class Damage {
        @Test
        @DisplayName("skips a leading ID3v2 tag and trailing ID3v1 and APE tags")
        void tags() {
            var aac = ffmpegAdts(2, 44100, 0.4);
            var expected = packets(aac);
            var tagged = new ByteArrayOutputStream();
            var id3Body = new ByteArrayOutputStream();
            var text = "\u0003An AAC title".getBytes(StandardCharsets.UTF_8);
            id3Body.writeBytes("TIT2".getBytes(StandardCharsets.US_ASCII));
            id3Body.writeBytes(new byte[]{0, 0, 0, (byte) text.length, 0, 0});
            id3Body.writeBytes(text);
            id3Body.writeBytes(new byte[3000]);
            var size = id3Body.size();
            tagged.writeBytes(new byte[]{'I', 'D', '3', 4, 0, 0, (byte) (size >> 21 & 0x7F), (byte) (size >> 14 & 0x7F),
                    (byte) (size >> 7 & 0x7F), (byte) (size & 0x7F)});
            tagged.writeBytes(id3Body.toByteArray());
            tagged.writeBytes(aac);
            var ape = new byte[64];
            System.arraycopy("APETAGEX".getBytes(StandardCharsets.US_ASCII), 0, ape, 32, 8);
            ape[32 + 8] = (byte) 0xD0;
            ape[32 + 9] = 0x07;
            ape[32 + 12] = 32;
            ape[32 + 23] = (byte) 0x80;
            System.arraycopy("APETAGEX".getBytes(StandardCharsets.US_ASCII), 0, ape, 0, 8);
            tagged.writeBytes(ape);
            var id3v1 = new byte[128];
            id3v1[0] = 'T';
            id3v1[1] = 'A';
            id3v1[2] = 'G';
            id3v1[3] = (byte) 0xFF;
            id3v1[4] = (byte) 0xF1;
            tagged.writeBytes(id3v1);
            try (var demuxer = new AdtsDemuxer(MediaSource.of(tagged.toByteArray()))) {
                assertEquals("An AAC title", demuxer.metadata().get("TIT2"));
                var actual = AudioPipeline.packets(demuxer, 0);
                assertEquals(expected.size(), actual.size());
                for (var i = 0; i < expected.size(); i++) {
                    assertEquals(ProbedPacket.of(expected.get(i)), ProbedPacket.of(actual.get(i)));
                }
            }
        }

        @Test
        @DisplayName("resynchronizes after garbage in the middle of the stream")
        void garbage() {
            var original = frames(ffmpegAdts(2, 44100, 0.6));
            var stream = new ByteArrayOutputStream();
            stream.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xF1, 1, 2, 3});
            for (var i = 0; i < original.size(); i++) {
                stream.writeBytes(original.get(i));
                if (i == 5) {
                    stream.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80, 0x10, 0x00, 0x00, 0x42, 0x42});
                }
            }
            var packets = packets(stream.toByteArray());
            assertEquals(original.size(), packets.size());
            for (var i = 0; i < original.size(); i++) {
                assertArrayEquals(Arrays.copyOfRange(original.get(i), 7, original.get(i).length), packets.get(i).data());
            }
        }

        @Test
        @DisplayName("rejects input without ADTS frames")
        void rejects() {
            assertThrows(MediaException.Malformed.class, () -> new AdtsDemuxer(MediaSource.of(new byte[4096])));
            assertThrows(MediaException.Malformed.class, () -> new AdtsDemuxer(MediaSource.of(new byte[0])));
        }

        @Test
        @DisplayName("resists hundreds of random mutations")
        void mutations() {
            var inputs = List.of(ffmpegAdts(2, 44100, 0.3), ffmpegAdts(1, 16000, 0.3));
            var stats = AudioMutations.assertRobust(inputs, 200, 0xADL, Duration.ofSeconds(5), bytes -> packets(bytes));
            System.out.println("AdtsDemuxer mutation outcomes: " + stats);
        }
    }

    /** A sized source that is not in memory and counts the bytes it delivers. */
    static final class CountingSource implements MediaSource {
        final byte[] data;
        long bytes;

        CountingSource(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return length == 0 ? 0 : -1;
            }
            var count = (int) Math.min(Math.min(length, 5000), data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, count);
            bytes += count;
            return count;
        }

        @Override
        public void close() {
        }
    }

    static List<ProbedPacket> probed(AdtsDemuxer demuxer) {
        return AudioPipeline.packets(demuxer, 0).stream().map(ProbedPacket::of).toList();
    }

    @Nested
    @DisplayName("single pass")
    class SinglePass {
        @Test
        @DisplayName("reads a source that is not in memory once, keeping the duration walk's packets")
        void readsOnce() {
            var data = ffmpegAdts(2, 44100, 20);
            var source = new CountingSource(data);
            try (var counted = new AdtsDemuxer(source);
                 var memory = new AdtsDemuxer(MediaSource.of(data))) {
                assertEquals(memory.tracks().getFirst().duration(), counted.tracks().getFirst().duration());
                assertEquals(memory.durationMicros(), counted.durationMicros());
                assertEquals(probed(memory), probed(counted));
            }
            // the trailing-tag probe reads the last 4 KiB once more, in one read
            assertTrue(source.bytes <= data.length + 4096 + 64, source.bytes + " bytes read for " + data.length);
        }

        @Test
        @DisplayName("continues from the first frame it did not keep when the budget runs out")
        void budget() {
            var data = ffmpegAdts(1, 48000, 10);
            for (var maxBytes : new long[]{1, 3000, 20_000, 70_000}) {
                var limits = MediaLimits.builder().maxDimension(16_384).maxPixels(1 << 20).maxFrames(1_000_000)
                        .maxChannels(64).maxBytes(maxBytes).build();
                var options = DemuxerOptions.builder().limits(limits).build();
                try (var counted = new AdtsDemuxer(new CountingSource(data), options);
                     var memory = new AdtsDemuxer(MediaSource.of(data), options)) {
                    assertEquals(memory.tracks().getFirst().duration(), counted.tracks().getFirst().duration());
                    assertEquals(probed(memory), probed(counted), "maxBytes " + maxBytes);
                }
            }
        }

        @Test
        @DisplayName("resists hundreds of random mutations through a source that is not in memory")
        void mutations() {
            var inputs = List.of(ffmpegAdts(2, 44100, 1.5), ffmpegAdts(1, 16000, 2));
            var stats = AudioMutations.assertRobust(inputs, 200, 0xADA, Duration.ofSeconds(5), bytes -> {
                try (var demuxer = new AdtsDemuxer(new CountingSource(bytes))) {
                    while (demuxer.read() != null) {
                        // drain
                    }
                }
            });
            System.out.println("AdtsDemuxer file-source mutation outcomes: " + stats);
        }
    }
}
