package com.github.auties00.smalt.container.amr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.container.CountingSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the multichannel AMR storage format of RFC 4867 section 5.2: the header, frame-blocks of hand-built frames of
 * every type, the RFC 3551 channel layouts, files interleaved from single-channel encodes (whose channels must
 * de-interleave back to the original frames), seeking, truncation, the read-once path, the raw muxer round trip and
 * mutation robustness.
 */
class AmrMultichannelTest {
    private static final int[] NB_SIZES = {12, 13, 15, 17, 19, 20, 26, 31, 5, 6, 5, 5, 0, 0, 0, 0};
    private static final int[] WB_SIZES = {17, 23, 32, 36, 40, 46, 50, 58, 60, 5, 0, 0, 0, 0, 0, 0};

    static byte[] header(boolean wideband, int description) {
        var magic = (wideband ? "#!AMR-WB_MC1.0\n" : "#!AMR_MC1.0\n").getBytes(StandardCharsets.US_ASCII);
        var header = Arrays.copyOf(magic, magic.length + 4);
        header[magic.length] = (byte) (description >>> 24);
        header[magic.length + 1] = (byte) (description >>> 16);
        header[magic.length + 2] = (byte) (description >>> 8);
        header[magic.length + 3] = (byte) description;
        return header;
    }

    // a frame of a type whose payload bytes are a pattern of the frame, channel and type, so frames differ
    static byte[] frame(boolean wideband, int type, int block, int channel) {
        var size = (wideband ? WB_SIZES : NB_SIZES)[type];
        var frame = new byte[1 + size];
        frame[0] = (byte) (type << 3 | 0x04);
        for (var i = 1; i < frame.length; i++) {
            frame[i] = (byte) (block * 31 + channel * 7 + i);
        }
        return frame;
    }

    // frame-blocks whose channel c of block k has type (k + 3c) mod the number of types, all types included
    static List<byte[]> blocks(boolean wideband, int channels, int count) {
        var types = wideband ? new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 14, 15} : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15};
        var blocks = new ArrayList<byte[]>();
        for (var k = 0; k < count; k++) {
            var block = new ByteArrayOutputStream();
            for (var c = 0; c < channels; c++) {
                block.writeBytes(frame(wideband, types[(k + 3 * c) % types.length], k, c));
            }
            blocks.add(block.toByteArray());
        }
        return blocks;
    }

    static byte[] file(boolean wideband, int description, List<byte[]> blocks) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(header(wideband, description));
        blocks.forEach(out::writeBytes);
        return out.toByteArray();
    }

    static List<Packet> packets(byte[] data) {
        try (var demuxer = new AmrDemuxer(MediaSource.of(data))) {
            return AudioPipeline.packets(demuxer, 0);
        }
    }

    static AudioTrack track(byte[] data) {
        try (var demuxer = new AmrDemuxer(MediaSource.of(data))) {
            return (AudioTrack) demuxer.tracks().getFirst();
        }
    }

    // splits the frames of a single-channel file, header bytes included
    static List<byte[]> frames(byte[] file, boolean wideband) {
        var position = wideband ? 9 : 6;
        var frames = new ArrayList<byte[]>();
        while (position < file.length) {
            var size = 1 + (wideband ? WB_SIZES : NB_SIZES)[(file[position] >> 3) & 0x0F];
            if (position + size > file.length) {
                break;
            }
            frames.add(Arrays.copyOfRange(file, position, position + size));
            position += size;
        }
        return frames;
    }

    @Nested
    @DisplayName("header")
    class Header {
        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 15})
        @DisplayName("reads the channel count and the RFC 3551 layout of AMR-NB and AMR-WB files")
        void layouts(int channels) {
            for (var wideband : new boolean[]{false, true}) {
                var data = file(wideband, channels, blocks(wideband, channels, 20));
                var track = track(data);
                assertEquals(wideband ? Codec.AMR_WB : Codec.AMR_NB, track.codec());
                assertEquals(wideband ? 16000 : 8000, track.sampleRate());
                assertEquals(20L * (wideband ? 320 : 160), track.duration());
                var layout = track.layout().orElseThrow();
                assertEquals(channels, layout.channels());
                var expected = switch (channels) {
                    case 1 -> List.of(Speaker.FRONT_CENTER);
                    case 2 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT);
                    case 3 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER);
                    case 4 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_CENTER, Speaker.FRONT_RIGHT,
                            Speaker.BACK_CENTER);
                    case 5 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                            Speaker.BACK_LEFT, Speaker.BACK_RIGHT);
                    case 6 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_CENTER,
                            Speaker.FRONT_RIGHT, Speaker.FRONT_RIGHT_OF_CENTER, Speaker.BACK_CENTER);
                    default -> java.util.Collections.nCopies(channels, Speaker.UNKNOWN);
                };
                assertEquals(expected, layout.speakers());
                assertEquals(ContainerFormat.AMR, Containers.detect(MediaSource.of(data)).orElseThrow());
            }
        }

        @Test
        @DisplayName("ignores the reserved bits of the channel description")
        void reservedBits() {
            var blocks = blocks(false, 2, 5);
            assertEquals(2, track(file(false, 0xFFFFFFF2, blocks)).layout().orElseThrow().channels());
            assertEquals(packets(file(false, 2, blocks)).stream().map(Packet::data).map(Arrays::toString).toList(),
                    packets(file(false, 0xABCDE012, blocks)).stream().map(Packet::data).map(Arrays::toString)
                            .toList());
        }

        @Test
        @DisplayName("rejects a file without channels or with a cut header as malformed")
        void malformed() {
            assertThrows(MediaException.Malformed.class, () -> new AmrDemuxer(MediaSource.of(header(false, 0))));
            assertThrows(MediaException.Malformed.class, () -> new AmrDemuxer(MediaSource.of(header(true, 0xF0))));
            var cut = Arrays.copyOf(header(true, 2), 17);
            assertThrows(MediaException.Malformed.class, () -> new AmrDemuxer(MediaSource.of(cut)));
            assertEquals(0, packets(header(false, 3)).size());
        }

        @Test
        @DisplayName("enforces the channel limit")
        void limit() {
            var limits = MediaLimits.builder().maxChannels(2).build();
            var options = DemuxerOptions.builder().limits(limits).build();
            var data = file(false, 3, blocks(false, 3, 2));
            assertThrows(MediaException.LimitExceeded.class, () -> new AmrDemuxer(MediaSource.of(data), options));
            new AmrDemuxer(MediaSource.of(file(false, 2, blocks(false, 2, 2))), options).close();
        }
    }

    @Nested
    @DisplayName("frame-blocks")
    class FrameBlocks {
        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {2, 3, 6, 15})
        @DisplayName("returns one packet per frame-block of hand-built frames of every type")
        void handBuilt(int channels) {
            for (var wideband : new boolean[]{false, true}) {
                var blocks = blocks(wideband, channels, 40);
                var packets = packets(file(wideband, channels, blocks));
                assertEquals(blocks.size(), packets.size());
                var frameSamples = wideband ? 320 : 160;
                for (var k = 0; k < blocks.size(); k++) {
                    assertArrayEquals(blocks.get(k), packets.get(k).data(), "block " + k);
                    assertEquals((long) k * frameSamples, packets.get(k).pts());
                    assertEquals(frameSamples, packets.get(k).duration());
                }
            }
        }

        @Test
        @DisplayName("de-interleaves to the frames of the single-channel encodes it was interleaved from")
        void interleavedEncodes() {
            for (var wideband : new boolean[]{false, true}) {
                var rate = wideband ? 16000 : 8000;
                var encoder = wideband ? "libvo_amrwbenc" : "libopencore_amrnb";
                var left = frames(Fixtures.read(Fixtures.tone("amr", 440, rate, 1, 0.6, List.of("-c:a", encoder))),
                        wideband);
                var right = frames(Fixtures.read(Fixtures.tone("amr", 1250, rate, 1, 0.6,
                        List.of("-c:a", encoder, "-b:a", wideband ? "8850" : "5150"))), wideband);
                var count = Math.min(left.size(), right.size());
                var blocks = new ArrayList<byte[]>();
                for (var k = 0; k < count; k++) {
                    var block = new ByteArrayOutputStream();
                    block.writeBytes(left.get(k));
                    block.writeBytes(right.get(k));
                    blocks.add(block.toByteArray());
                }
                var packets = packets(file(wideband, 2, blocks));
                assertEquals(count, packets.size());
                for (var k = 0; k < count; k++) {
                    var data = packets.get(k).data();
                    var split = left.get(k).length;
                    assertArrayEquals(left.get(k), Arrays.copyOfRange(data, 0, split), "channel 1 of block " + k);
                    assertArrayEquals(right.get(k), Arrays.copyOfRange(data, split, data.length),
                            "channel 2 of block " + k);
                }
            }
        }

        @Test
        @DisplayName("drops a final frame-block cut short in any of its frames")
        void truncated() {
            var blocks = blocks(false, 3, 10);
            var full = file(false, 3, blocks);
            var last = blocks.getLast();
            for (var cut = 1; cut <= last.length; cut++) {
                var data = Arrays.copyOf(full, full.length - cut);
                assertEquals(9, packets(data).size(), "cut " + cut);
                assertEquals(9L * 160, track(data).duration(), "cut " + cut);
            }
        }

        @Test
        @DisplayName("seeks to the frame-block holding the target")
        void seek() {
            var blocks = blocks(true, 3, 70);
            var data = file(true, 3, blocks);
            try (var demuxer = new AmrDemuxer(MediaSource.of(data))) {
                for (var target : new int[]{0, 33, 15, 69, 1, 47}) {
                    demuxer.seek(target * 20_000L + 7_000);
                    var packet = demuxer.read();
                    assertEquals(target * 320L, packet.pts());
                    assertArrayEquals(blocks.get(target), packet.data(), "block " + target);
                }
                demuxer.seek(70 * 20_000L);
                assertNull(demuxer.read());
            }
        }

        @Test
        @DisplayName("reads a source that is not in memory like an in-memory one, under any memory budget")
        void readOnce() {
            var data = file(false, 2, blocks(false, 2, 300));
            for (var maxBytes : new long[]{1, 500, 3000, 1L << 30}) {
                var limits = MediaLimits.builder().maxBytes(maxBytes).build();
                var options = DemuxerOptions.builder().limits(limits).build();
                for (var input : List.of(data, Arrays.copyOf(data, data.length - 7))) {
                    for (var chunk : new int[]{7, 1000, Integer.MAX_VALUE}) {
                        try (var counted = new AmrDemuxer(new CountingSource(input, chunk), options);
                             var memory = new AmrDemuxer(MediaSource.of(input), options)) {
                            assertEquals(memory.tracks(), counted.tracks());
                            assertEquals(AmrDemuxerTest.probed(memory), AmrDemuxerTest.probed(counted),
                                    "budget " + maxBytes + ", chunk " + chunk);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("resists hundreds of random mutations")
        void mutations() {
            var inputs = List.of(file(false, 2, blocks(false, 2, 30)), file(true, 3, blocks(true, 3, 12)));
            var stats = AudioMutations.assertRobust(inputs, 200, 0xA3BL, Duration.ofSeconds(5),
                    AmrMultichannelTest::packets);
            System.out.println("AmrDemuxer multichannel mutation outcomes: " + stats);
        }
    }

    @Nested
    @DisplayName("raw muxer")
    class Muxing {
        static AudioTrack amr(Codec codec, ChannelLayout layout) {
            var rate = codec == Codec.AMR_WB ? 16000 : 8000;
            return AudioTrack.builder().id(0).codec(codec).timeBase(Rational.timeBaseOf(rate)).duration(-1)
                    .sampleRate(rate).layout(layout).build();
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {2, 4, 6, 15})
        @DisplayName("writes the multichannel header and the frame-blocks, which demux back unchanged")
        void roundTrip(int channels) {
            for (var wideband : new boolean[]{false, true}) {
                var blocks = blocks(wideband, channels, 25);
                var expected = file(wideband, channels, blocks);
                var track = amr(wideband ? Codec.AMR_WB : Codec.AMR_NB,
                        com.github.auties00.smalt.internal.codec.AmrConfigs.storageLayout(channels));
                var frameSamples = wideband ? 320 : 160;
                var input = new ArrayList<Packet>();
                for (var k = 0; k < blocks.size(); k++) {
                    input.add(new Packet(0, blocks.get(k), (long) k * frameSamples, (long) k * frameSamples,
                            frameSamples, true));
                }
                var output = MuxSupport.toStream(List.of(track), input, RawMuxer::new);
                assertArrayEquals(expected, output);
                var demuxed = packets(output);
                assertEquals(blocks.size(), demuxed.size());
                for (var k = 0; k < blocks.size(); k++) {
                    assertArrayEquals(blocks.get(k), demuxed.get(k).data());
                }
                assertEquals(track.layout(), track(output).layout());
            }
        }

        @Test
        @DisplayName("writes the single-channel magic for one channel, and rejects more than 15 channels")
        void bounds() {
            var frame = frame(false, 7, 0, 0);
            var output = MuxSupport.toStream(List.of(amr(Codec.AMR_NB, ChannelLayout.MONO)),
                    List.of(new Packet(0, frame, 0, 0, 160, true)), RawMuxer::new);
            assertEquals("#!AMR\n", new String(output, 0, 6, StandardCharsets.US_ASCII));
            assertEquals(6 + frame.length, output.length);
            var wide = amr(Codec.AMR_WB, ChannelLayout.defaultFor(16));
            assertThrows(IllegalArgumentException.class, () -> new RawMuxer(MediaSink.memory()).addTrack(wide));
        }

        @Test
        @DisplayName("keeps multichannel AMR out of the MP4 sample entries and Matroska, which carry one channel")
        void mp4() {
            var stereo = amr(Codec.AMR_NB, ChannelLayout.STEREO);
            assertThrows(IllegalArgumentException.class, () -> new Mp4Muxer(MediaSink.memory()).addTrack(stereo));
            assertThrows(IllegalArgumentException.class,
                    () -> new FragmentedMp4Muxer(MediaSink.memory()).addTrack(stereo));
            assertThrows(IllegalArgumentException.class, () -> new MatroskaMuxer(MediaSink.memory()).addTrack(stereo));
            new Mp4Muxer(MediaSink.memory()).addTrack(amr(Codec.AMR_NB, ChannelLayout.MONO));
            new FragmentedMp4Muxer(MediaSink.memory()).addTrack(amr(Codec.AMR_WB, ChannelLayout.MONO));
        }

        @Test
        @DisplayName("copies a multichannel file into an AMR file unchanged, and into no other container")
        void transcode() {
            var input = file(false, 2, blocks(false, 2, 25));
            var output = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(input), output, TranscodeOptions.remux(OutputContainer.AMR));
            assertArrayEquals(input, output.toByteArray());
            for (var container : new OutputContainer[]{OutputContainer.MP4, OutputContainer.MATROSKA}) {
                assertThrows(MediaException.Unsupported.class, () -> Transcoder.transcode(MediaSource.of(input),
                        MediaSink.memory(), TranscodeOptions.remux(container)), container.toString());
            }
        }
    }

    @Test
    @DisplayName("demuxes random frame-blocks of random channel counts back to their frames")
    void randomized() {
        var random = new Random(0x4867);
        for (var round = 0; round < 50; round++) {
            var wideband = random.nextBoolean();
            var channels = 1 + random.nextInt(15);
            var sizes = wideband ? WB_SIZES : NB_SIZES;
            var blocks = new ArrayList<byte[]>();
            var count = 1 + random.nextInt(30);
            for (var k = 0; k < count; k++) {
                var block = new ByteArrayOutputStream();
                for (var c = 0; c < channels; c++) {
                    var type = random.nextInt(16);
                    var payload = new byte[sizes[type]];
                    random.nextBytes(payload);
                    block.write(type << 3 | (random.nextBoolean() ? 0x04 : 0) | (random.nextBoolean() ? 0x80 : 0));
                    block.writeBytes(payload);
                }
                blocks.add(block.toByteArray());
            }
            var packets = packets(file(wideband, random.nextInt() & 0xFFFFFFF0 | channels, blocks));
            assertEquals(count, packets.size(), "round " + round);
            for (var k = 0; k < count; k++) {
                assertArrayEquals(blocks.get(k), packets.get(k).data(), "round " + round + " block " + k);
            }
        }
    }
}
