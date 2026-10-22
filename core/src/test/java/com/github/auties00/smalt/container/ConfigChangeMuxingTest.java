package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.adts.AdtsMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec configuration changes ({@link PacketSideData#newCodecConfig()}) through the muxers that
 * write them in band (MPEG-TS, Matroska, ADTS, raw streams) and the transport stream demuxer that
 * detects them again, plus the muxers that must reject them.
 */
@DisplayName("codec configuration changes")
class ConfigChangeMuxingTest {
    static final byte[] SPS1 = {0x67, 0x42, 0x00, 0x1E, (byte) 0x80};
    static final byte[] SPS2 = {0x67, 0x42, 0x00, 0x1F, (byte) 0x80};
    static final byte[] PPS = {0x68, (byte) 0xCE};
    static final byte[] CONFIG1 = NalConfigs.avcConfig(List.of(SPS1), List.of(PPS));
    static final byte[] CONFIG2 = NalConfigs.avcConfig(List.of(SPS2), List.of(PPS));

    /** AAC-LC at 44.1 kHz in stereo. */
    static final byte[] ASC1 = {0x12, 0x10};

    /** AAC-LC at 48 kHz in mono. */
    static final byte[] ASC2 = {0x11, (byte) 0x88};

    static VideoTrack video() {
        return VideoTrack.builder().id(1).codec(Codec.H264).timeBase(Rational.timeBaseOf(90_000)).width(64).height(48)
                .codecConfig(CONFIG1).build();
    }

    static byte[] unit(byte[]... nals) {
        var out = new ByteArrayOutputStream();
        for (var nal : nals) {
            out.writeBytes(new byte[]{0, 0, 0, (byte) nal.length});
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }

    static byte[] slice(boolean key, int index) {
        // a slice never ends with a zero byte, which an Annex B stream would read as trailing padding
        return key ? new byte[]{0x65, (byte) 0xB8, (byte) (index + 1)} : new byte[]{0x41, (byte) 0xE0, (byte) (index + 1)};
    }

    /** Six access units, key frames at 0, 3 and 5; the configuration changes at 3. */
    static List<Packet> videoPackets() {
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < 6; index++) {
            var key = index == 0 || index == 3 || index == 5;
            var side = index == 3 ? PacketSideData.builder().newCodecConfig(CONFIG2).build() : PacketSideData.NONE;
            packets.add(new Packet(1, unit(slice(key, index)), index * 3000L, index * 3000L, 3000,
                    key ? Set.of(PacketFlag.KEY) : Set.of(), side));
        }
        return packets;
    }

    static List<Packet> write(Muxer muxer, Track track, List<Packet> packets) {
        try (muxer) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return packets;
    }

    static List<Packet> read(Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        try (demuxer) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
            }
        }
        return packets;
    }

    static List<byte[]> parameterSets(byte[] config) {
        return NalConfigs.parseAvc(config).parameterSets();
    }

    static String hex(List<byte[]> units) {
        return units.stream().map(HexFormat.of()::formatHex).toList().toString();
    }

    @Nested
    @DisplayName("H.264")
    class Video {
        @Test
        @DisplayName("round-trips a parameter set change through MPEG-TS")
        void transportStream() {
            var sink = MediaSink.memory();
            write(new TsMuxer(sink), video(), videoPackets());
            var packets = read(new TsDemuxer(MediaSource.of(sink.toByteArray())));
            assertEquals(6, packets.size());
            var track = packets.getFirst().trackId();
            assertTrue(packets.stream().allMatch(packet -> packet.trackId() == track));
            for (var index = 0; index < 6; index++) {
                var change = packets.get(index).sideData().newCodecConfig().orElse(null);
                if (index == 3) {
                    assertEquals(hex(List.of(SPS2, PPS)), hex(parameterSets(change)));
                } else {
                    assertNull(change, "packet " + index);
                }
            }
            assertArrayEquals(unit(SPS2, PPS, slice(true, 5)), packets.get(5).data());
            assertArrayEquals(unit(SPS1, PPS, slice(true, 0)), packets.getFirst().data());
        }

        @Test
        @DisplayName("writes the new parameter sets in band in Matroska")
        void matroska() {
            var sink = MediaSink.memory();
            write(new MatroskaMuxer(sink), video(), videoPackets());
            var packets = read(new MatroskaDemuxer(MediaSource.of(sink.toByteArray())));
            assertEquals(6, packets.size());
            assertArrayEquals(unit(slice(true, 0)), packets.get(0).data());
            assertArrayEquals(unit(SPS2, PPS, slice(true, 3)), packets.get(3).data());
            assertArrayEquals(unit(slice(false, 4)), packets.get(4).data());
            assertArrayEquals(unit(SPS2, PPS, slice(true, 5)), packets.get(5).data());
        }

        @Test
        @DisplayName("repeats the new parameter sets before key frames of a raw stream")
        void raw() {
            var sink = MediaSink.memory();
            write(new RawMuxer(sink), video(), videoPackets());
            var stream = HexFormat.of().formatHex(sink.toByteArray());
            var first = HexFormat.of().formatHex(SPS1);
            var second = HexFormat.of().formatHex(SPS2);
            assertTrue(stream.startsWith("00000001" + first), stream);
            assertEquals(stream.indexOf(first), stream.lastIndexOf(first));
            assertEquals(2, (stream.length() - stream.replace(second, "").length()) / second.length());
        }

        @Test
        @DisplayName("marks decode-only packets invisible in Matroska")
        void invisible() {
            var packets = new ArrayList<Packet>();
            for (var packet : videoPackets()) {
                var flags = EnumSet.noneOf(PacketFlag.class);
                flags.addAll(packet.flags());
                if (packet.pts() < 6000) {
                    flags.add(PacketFlag.DISCARD);
                }
                packets.add(new Packet(1, packet.data(), packet.pts(), packet.dts(), packet.duration(), flags,
                        PacketSideData.NONE));
            }
            var sink = MediaSink.memory();
            write(new MatroskaMuxer(sink), video(), packets);
            var read = read(new MatroskaDemuxer(MediaSource.of(sink.toByteArray())));
            assertEquals(List.of(true, true, false, false, false, false),
                    read.stream().map(packet -> packet.flags().contains(PacketFlag.DISCARD)).toList());
            assertTrue(read.getFirst().keyframe());
        }

        @Test
        @DisplayName("is rejected by a fragmented MP4")
        void fragmented() {
            var muxer = new FragmentedMp4Muxer(MediaSink.memory());
            assertThrows(MediaException.Unsupported.class, () -> write(muxer, video(), videoPackets()));
        }
    }

    @Nested
    @DisplayName("AAC")
    class Audio {
        static AudioTrack track() {
            return AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(44_100)).sampleRate(44_100)
                    .layout(ChannelLayout.STEREO).codecConfig(ASC1).build();
        }

        static List<Packet> packets() {
            var packets = new ArrayList<Packet>();
            var pts = 0L;
            for (var index = 0; index < 8; index++) {
                var side = index == 4 ? PacketSideData.builder().newCodecConfig(ASC2).build() : PacketSideData.NONE;
                var duration = index < 4 ? 1024 : index % 2 == 0 ? 940 : 941;
                var payload = new byte[20];
                Arrays.fill(payload, (byte) (index + 1));
                packets.add(new Packet(1, payload, pts, pts, duration, Set.of(PacketFlag.KEY), side));
                pts += duration;
            }
            return packets;
        }

        @Test
        @DisplayName("switches the ADTS header at the change")
        void adts() {
            var sink = MediaSink.memory();
            write(new AdtsMuxer(sink), track(), packets());
            var data = sink.toByteArray();
            var configs = new ArrayList<String>();
            for (var position = 0; position < data.length; ) {
                var header = AdtsHeader.parse(data, position);
                configs.add(HexFormat.of().formatHex(header.audioSpecificConfig()));
                position += header.frameLength();
            }
            assertEquals(List.of("1210", "1210", "1210", "1210", "1188", "1188", "1188", "1188"), configs);
        }

        @Test
        @DisplayName("round-trips an AudioSpecificConfig change through MPEG-TS")
        void transportStream() {
            var sink = MediaSink.memory();
            write(new TsMuxer(sink), track(), packets());
            var packets = read(new TsDemuxer(MediaSource.of(sink.toByteArray())));
            assertEquals(8, packets.size());
            for (var index = 0; index < 8; index++) {
                var change = packets.get(index).sideData().newCodecConfig().orElse(null);
                if (index == 4) {
                    assertArrayEquals(ASC2, change);
                } else {
                    assertNull(change, "packet " + index);
                }
                assertEquals(index + 1, packets.get(index).data()[0]);
            }
            var newDurations = packets.subList(4, 8).stream().mapToLong(Packet::duration).sum();
            // four frames of 1024 samples at 48 kHz, counted at 44.1 kHz
            assertTrue(Math.abs(newDurations - 4 * 1024 * 44_100 / 48_000) <= 1, "durations " + newDurations);
        }

        @Test
        @DisplayName("announces the change again after a seek, even when the probe already met it")
        void transportStreamSeek() {
            // the video's first key frame comes after the audio change, so the probe reads past it
            var sink = MediaSink.memory();
            try (var muxer = new TsMuxer(sink)) {
                muxer.addTrack(track());
                muxer.addTrack(video().toBuilder().id(2).build());
                var audio = packets();
                for (var packet : audio) {
                    muxer.writePacket(packet);
                }
                for (var index = 0; index < 3; index++) {
                    muxer.writePacket(new Packet(2, unit(slice(index == 0, index)), 45_000 + index * 3000L,
                            45_000 + index * 3000L, 3000, index == 0 ? Set.of(PacketFlag.KEY) : Set.of(), PacketSideData.NONE));
                }
                muxer.finish();
            }
            var data = sink.toByteArray();
            try (var demuxer = new TsDemuxer(MediaSource.of(data))) {
                var audioId = demuxer.tracks().stream().filter(candidate -> candidate instanceof AudioTrack).findFirst()
                        .orElseThrow().id();
                var fromStart = new ArrayList<String>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() == audioId) {
                        fromStart.add(packet.data()[0] + ":" + packet.sideData().newCodecConfig().map(HexFormat.of()::formatHex)
                                .orElse("-"));
                    }
                }
                assertEquals(List.of("1:-", "2:-", "3:-", "4:-", "5:1188", "6:-", "7:-", "8:-"), fromStart);
                for (var target : new long[]{120_000, 150_000}) {
                    demuxer.seek(target);
                    var announced = false;
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        if (packet.trackId() != audioId) {
                            continue;
                        }
                        var change = packet.sideData().newCodecConfig().orElse(null);
                        if (packet.data()[0] <= 4) {
                            assertNull(change, "packet " + packet.data()[0] + " after a seek to " + target);
                        } else {
                            assertArrayEquals(ASC2, change, "packet " + packet.data()[0] + " after a seek to " + target);
                            announced = true;
                            break;
                        }
                    }
                    assertTrue(announced, "no packet of the second configuration after a seek to " + target);
                }
            }
        }

        @Test
        @DisplayName("is rejected by Ogg")
        void ogg() {
            var head = new byte[19];
            System.arraycopy("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, head, 0, 8);
            head[8] = 1;
            head[9] = 2;
            var opus = AudioTrack.builder().id(1).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48_000))
                    .sampleRate(48_000).layout(ChannelLayout.STEREO).codecConfig(head).build();
            var other = head.clone();
            other[10] = 56;
            var packet = new Packet(1, new byte[]{(byte) 0xFC, 1, 2}, 0, 0, 960, Set.of(PacketFlag.KEY),
                    PacketSideData.builder().newCodecConfig(other).build());
            var muxer = new OggMuxer(MediaSink.memory());
            assertThrows(MediaException.Unsupported.class, () -> write(muxer, opus, List.of(packet)));
        }
    }
}
