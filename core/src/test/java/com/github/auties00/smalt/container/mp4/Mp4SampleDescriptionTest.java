package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.github.auties00.smalt.container.mp4.Mp4Builder.box;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.file;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.full;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mvhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.table;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u32;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.visualEntry;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.zeros;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tracks with several sample descriptions ({@code stsd} entries selected by {@code stsc}, {@code tfhd}
 * and {@code trex}), the configuration changes the demuxer signals for them, and the sample
 * descriptions the muxer and the remuxer write back.
 */
@DisplayName("MP4 sample descriptions")
class Mp4SampleDescriptionTest {
    /** An avcC whose sequence parameter set has a given level, so that two records differ. */
    static byte[] avcC(int level) {
        return new byte[]{1, 66, 0, (byte) level, (byte) 0xFF, (byte) 0xE1, 0, 5, 0x67, 66, 0, (byte) level, (byte) 0x80,
                1, 0, 2, 0x68, (byte) 0xCE};
    }

    static byte[] avc1(int level) {
        return visualEntry("avc1", 64, 48, box("avcC", avcC(level)));
    }

    static byte[] stsd(byte[]... entries) {
        return full("stsd", 0, 0, u32(entries.length), Mp4Builder.concat(entries));
    }

    /** Samples 0 to 5 use the first description, 6 to 8 the second, 9 to 11 the first again. */
    static byte[] twoDescriptions(byte[] edts, byte[] second) {
        return file(Mp4Builder.samples(Mp4EditListTest.DISPLAY.length, 4), mvhd(1000, 1200),
                Mp4EditListTest.videoTrak(edts, stsd(avc1(30), second), table("stsc", 0, 3, 1, 1, 1, 7, 1, 2, 10, 1, 1)));
    }

    static List<byte[]> configs(List<Packet> packets) {
        var result = new ArrayList<byte[]>();
        for (var packet : packets) {
            result.add(packet.sideData().newCodecConfig().orElse(null));
        }
        return result;
    }

    @Test
    @DisplayName("signals the configuration of a new description on the first sample using it")
    void signalsChanges() {
        var demuxed = Mp4EdgeCaseTest.demux(twoDescriptions(null, avc1(31)));
        assertArrayEquals(avcC(30), demuxed.tracks().getFirst().codecConfig().orElseThrow());
        var changes = configs(demuxed.order());
        for (var index = 0; index < changes.size(); index++) {
            switch (index) {
                case 6 -> assertArrayEquals(avcC(31), changes.get(index));
                case 9 -> assertArrayEquals(avcC(30), changes.get(index));
                default -> assertNull(changes.get(index), "packet " + index);
            }
        }
    }

    @Test
    @DisplayName("signals the description in use after a seek")
    void afterSeek() throws Exception {
        try (var source = MediaSource.of(twoDescriptions(null, avc1(31))); var demuxer = new Mp4Demuxer(source)) {
            demuxer.seek(900_000);
            var first = demuxer.read();
            assertEquals(8, first.data()[0]);
            assertArrayEquals(avcC(31), first.sideData().newCodecConfig().orElseThrow());
            assertArrayEquals(avcC(30), demuxer.read().sideData().newCodecConfig().orElseThrow());
            demuxer.seek(0);
            assertNull(demuxer.read().sideData().newCodecConfig().orElse(null));
        }
    }

    @Test
    @DisplayName("signals nothing between descriptions with the same configuration")
    void identicalDescriptions() {
        var demuxed = Mp4EdgeCaseTest.demux(twoDescriptions(null, avc1(30)));
        assertEquals(12, demuxed.order().size());
        assertEquals(List.of(), configs(demuxed.order()).stream().filter(config -> config != null).toList());
    }

    @Test
    @DisplayName("rejects a description of another codec when a sample uses it")
    void incompatible() {
        var mp4v = visualEntry("mp4v", 64, 48);
        assertThrows(MediaException.Unsupported.class, () -> Mp4EdgeCaseTest.demux(twoDescriptions(null, mp4v)));
    }

    @Test
    @DisplayName("ignores a damaged description no sample uses and rejects it when one does")
    void damaged() {
        var truncated = box("avc1", zeros(10));
        var unused = file(Mp4Builder.samples(12, 4), mvhd(1000, 1200), Mp4EditListTest.videoTrak(null,
                stsd(avc1(30), truncated), table("stsc", 0, 3, 1, 1, 1)));
        assertEquals(12, Mp4EdgeCaseTest.demux(unused).order().size());
        assertThrows(MediaException.Malformed.class, () -> Mp4EdgeCaseTest.demux(twoDescriptions(null, truncated)));
    }

    @Test
    @DisplayName("survives random description indices and damaged descriptions")
    void randomIndices() {
        var random = new java.util.Random(11);
        var damaged = box("avc1", zeros(80));
        for (var round = 0; round < 200; round++) {
            var entries = new long[12 * 3];
            for (var chunk = 0; chunk < 12; chunk++) {
                entries[chunk * 3] = chunk + 1;
                entries[chunk * 3 + 1] = 1;
                entries[chunk * 3 + 2] = random.nextInt(5);
            }
            var second = switch (random.nextInt(3)) {
                case 0 -> avc1(31);
                case 1 -> damaged;
                default -> visualEntry("hvc1", 64, 48);
            };
            var data = file(Mp4Builder.samples(12, 4), mvhd(1000, 1200),
                    Mp4EditListTest.videoTrak(null, stsd(avc1(30), second), table("stsc", 0, 3, entries)));
            try {
                Mp4EdgeCaseTest.demux(data);
            } catch (MediaException expected) {
                // a sample of an unusable description is rejected, never crashes the demuxer
            }
        }
    }

    @Test
    @DisplayName("selects the description of a fragment from tfhd, or else from trex")
    void fragments() {
        var data = Mp4EditListTest.fragmentedFile(new byte[0], stsd(avc1(30), avc1(31)), 1, 2);
        var changes = configs(Mp4EdgeCaseTest.demux(data).order());
        assertNull(changes.get(0));
        assertArrayEquals(avcC(31), changes.get(6));
        var fromTrex = Mp4EditListTest.fragmentedFile(new byte[0], stsd(avc1(30), avc1(31)), 2, 1);
        var demuxed = Mp4EdgeCaseTest.demux(fromTrex);
        assertArrayEquals(avcC(31), demuxed.tracks().getFirst().codecConfig().orElseThrow());
        assertArrayEquals(avcC(30), configs(demuxed.order()).get(6));
    }

    /** Writes packets through an Mp4Muxer and returns the file. */
    static byte[] mux(ContainerProbe.Demuxed demuxed, boolean fastStart) {
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.builder().fastStart(fastStart).build())) {
            muxer.addTrack(demuxed.tracks().getFirst());
            for (var packet : demuxed.order()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return com.github.auties00.smalt.container.MuxSupport.validated("descriptions", sink.toByteArray());
    }

    @Test
    @DisplayName("writes a sample description per configuration and reads them back")
    void muxerRoundTrip() {
        var demuxed = Mp4EdgeCaseTest.demux(twoDescriptions(null, avc1(31)));
        for (var fastStart : new boolean[]{true, false}) {
            var output = mux(demuxed, fastStart);
            var again = Mp4EdgeCaseTest.demux(output);
            assertEquals(demuxed.order().size(), again.order().size());
            var expected = configs(demuxed.order());
            var actual = configs(again.order());
            for (var index = 0; index < expected.size(); index++) {
                assertArrayEquals(expected.get(index), actual.get(index), "packet " + index);
                assertArrayEquals(demuxed.order().get(index).data(), again.order().get(index).data());
            }
            assertEquals(2, countDescriptions(output));
        }
    }

    @Test
    @DisplayName("gives a new Opus description the channel count of its own configuration")
    void opusChannels() {
        var mono = com.github.auties00.smalt.internal.codec.AudioConfigs.opusHead(1, 312, 48000, false);
        var stereo = com.github.auties00.smalt.internal.codec.AudioConfigs.opusHead(2, 312, 48000, false);
        var track = com.github.auties00.smalt.track.AudioTrack.builder().id(1).codec(Codec.OPUS)
                .timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                .layout(com.github.auties00.smalt.audio.ChannelLayout.defaultFor(1)).codecConfig(mono).build();
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink)) {
            muxer.addTrack(track);
            for (var index = 0; index < 6; index++) {
                var sideData = index == 3 ? PacketSideData.builder().newCodecConfig(stereo).build() : PacketSideData.NONE;
                muxer.writePacket(new Packet(1, new byte[]{(byte) (index < 3 ? 0xF8 : 0xFC), 0, 0}, index * 960L,
                        index * 960L, 960, java.util.Set.of(com.github.auties00.smalt.track.PacketFlag.KEY), sideData));
            }
            muxer.finish();
        }
        var output = com.github.auties00.smalt.container.MuxSupport.validated("descriptions", sink.toByteArray());
        assertEquals(2, countDescriptions(output));
        var channels = new ArrayList<Integer>();
        for (var index = 0; index + 22 < output.length; index++) {
            if (output[index] == 'O' && output[index + 1] == 'p' && output[index + 2] == 'u' && output[index + 3] == 's') {
                channels.add((output[index + 20] & 0xFF) << 8 | output[index + 21] & 0xFF);
            }
        }
        assertEquals(List.of(1, 2), channels);
        var demuxed = Mp4EdgeCaseTest.demux(output);
        var signaled = configs(demuxed.order());
        for (var index = 0; index < signaled.size(); index++) {
            if (index == 3) {
                assertArrayEquals(stereo, signaled.get(index));
            } else {
                assertNull(signaled.get(index), "packet " + index);
            }
        }
    }

    /** Returns the entry count of the first stsd box of a file. */
    static int countDescriptions(byte[] file) {
        for (var index = 0; index + 12 < file.length; index++) {
            if (file[index] == 's' && file[index + 1] == 't' && file[index + 2] == 's' && file[index + 3] == 'd') {
                return (file[index + 8] & 0xFF) << 24 | (file[index + 9] & 0xFF) << 16 | (file[index + 10] & 0xFF) << 8
                        | file[index + 11] & 0xFF;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("rejects a configuration change on a codec whose sample description has none")
    void muxerRejects() {
        var mp3 = com.github.auties00.smalt.track.AudioTrack.builder().id(1).codec(Codec.MP3)
                .timeBase(Rational.timeBaseOf(44100)).sampleRate(44100).build();
        try (var muxer = new Mp4Muxer(MediaSink.memory())) {
            muxer.addTrack(mp3);
            var packet = new Packet(1, new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0}, 0, 0, 1152,
                    java.util.Set.of(com.github.auties00.smalt.track.PacketFlag.KEY),
                    PacketSideData.builder().newCodecConfig(new byte[]{1}).build());
            assertThrows(MediaException.Unsupported.class, () -> muxer.writePacket(packet));
        }
    }

    @Test
    @DisplayName("remuxes descriptions and a multi-segment edit list as they are")
    void remux() {
        var edits = Mp4EditListTest.elst(400, 500, 1, 200, -1, 1, 300, 350, 0, 250, 1050, 1);
        var input = twoDescriptions(edits, avc1(31));
        var expected = Mp4EdgeCaseTest.demux(input);
        var sink = MediaSink.memory();
        Mp4Remuxer.remux(MediaSource.of(input), sink);
        var output = sink.toByteArray();
        var actual = Mp4EdgeCaseTest.demux(output);
        assertEquals(expected.order().stream().map(Mp4EditListTest::describe).toList(),
                actual.order().stream().map(Mp4EditListTest::describe).toList());
        var expectedConfigs = configs(expected.order());
        var actualConfigs = configs(actual.order());
        for (var index = 0; index < expectedConfigs.size(); index++) {
            assertArrayEquals(expectedConfigs.get(index), actualConfigs.get(index), "packet " + index);
        }
        assertEquals(expected.tracks().getFirst().duration(), actual.tracks().getFirst().duration());
        assertEquals(2, countDescriptions(output));
        assertEquals(12, sampleCount(output));
    }

    static long sampleCount(byte[] file) {
        try (var source = MediaSource.of(file); var demuxer = new Mp4Demuxer(source)) {
            return demuxer.sampleCount(demuxer.tracks().getFirst().id());
        }
    }
}
