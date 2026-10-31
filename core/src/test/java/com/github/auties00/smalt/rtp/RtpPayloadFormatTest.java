package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RtpPayloadFormat and the packetizer options")
class RtpPayloadFormatTest {
    @Test
    @DisplayName("parses the attribute values ffmpeg writes, with or without their prefixes")
    void fromSdp() {
        var aac = RtpPayloadFormat.fromSdp("a=rtpmap:97 MPEG4-GENERIC/44100/2",
                "a=fmtp:97 profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3; "
                        + "config=121056E500");
        assertEquals("mpeg4-generic", aac.encodingName());
        assertEquals(44_100, aac.clockRate());
        assertEquals(OptionalInt.of(2), aac.channels());
        assertEquals(Codec.AAC, aac.codec());
        assertEquals("AAC-hbr", aac.parameter("MODE").orElseThrow());
        assertEquals("13", aac.parameter("sizeLength").orElseThrow());
        var h264 = RtpPayloadFormat.fromSdp("96 H264/90000", "96 packetization-mode=1; sprop-parameter-sets="
                + "Z0LACtkEewEQAAADABAAAAMBQPEiZIA=,aMuDyyA=; profile-level-id=42C00A");
        assertEquals(Codec.H264, h264.codec());
        assertEquals(OptionalInt.empty(), h264.channels());
        assertEquals("H264/90000", h264.rtpmap());
        assertEquals(2, H264Format.of(h264).parameterSets.size());
        assertEquals("packetization-mode=1; sprop-parameter-sets=Z0LACtkEewEQAAADABAAAAMBQPEiZIA=,aMuDyyA=; "
                + "profile-level-id=42C00A", h264.fmtp());
        assertEquals(h264, RtpPayloadFormat.fromSdp(h264.rtpmap(), h264.fmtp()));
        assertEquals(Codec.AMR_WB, RtpPayloadFormat.fromSdp("AMR-WB/16000/1", "octet-align").codec());
        assertEquals("", RtpPayloadFormat.fromSdp("AMR-WB/16000/1", "octet-align").parameter("octet-align")
                .orElseThrow());
        assertThrows(MediaException.Malformed.class, () -> RtpPayloadFormat.fromSdp("H264", null));
        assertThrows(MediaException.Malformed.class, () -> RtpPayloadFormat.fromSdp("H264/abc", null));
        assertThrows(MediaException.Malformed.class, () -> RtpPayloadFormat.fromSdp("H264/0", null));
    }

    @Test
    @DisplayName("describes the presets as their RFCs register them")
    void presets() {
        assertEquals("opus/48000/2", RtpPayloadFormat.opus(true).rtpmap());
        assertEquals("stereo=1; sprop-stereo=1", RtpPayloadFormat.opus(true).fmtp());
        assertEquals("", RtpPayloadFormat.opus(false).fmtp());
        assertEquals("AMR/8000/1", RtpPayloadFormat.amr(false, false).rtpmap());
        assertEquals("octet-align=1", RtpPayloadFormat.amr(true, true).fmtp());
        assertEquals("VP9/90000", RtpPayloadFormat.vp9().rtpmap());
        assertEquals("AV1/90000", RtpPayloadFormat.av1().rtpmap());
        assertEquals(Codec.VP8, RtpPayloadFormat.vp8().codec());
        var lbr = RtpPayloadFormat.aacLbr(HexFormat.of().parseHex("1388"));
        assertEquals(22_050, lbr.clockRate());
        assertEquals(OptionalInt.of(1), lbr.channels());
        assertEquals("streamtype=5; profile-level-id=1; mode=AAC-lbr; sizelength=6; indexlength=2; "
                + "indexdeltalength=2; config=1388", lbr.fmtp());
        assertThrows(IllegalArgumentException.class, () -> RtpPayloadFormat.h264(3));
        assertEquals(Codec.UNKNOWN_VIDEO, RtpPayloadFormat.builder("H265", 90_000).build().codec());
        assertEquals(Codec.UNKNOWN_AUDIO, RtpPayloadFormat.builder("mpeg4-generic", 90_000)
                .parameter("streamType", "4").build().codec());
    }

    @Test
    @DisplayName("validates names, values and numeric parameters")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> RtpPayloadFormat.builder("a b", 1));
        assertThrows(IllegalArgumentException.class, () -> RtpPayloadFormat.builder("a/b", 1));
        assertThrows(IllegalArgumentException.class, () -> RtpPayloadFormat.builder("x", 0));
        assertThrows(IllegalArgumentException.class, () -> RtpPayloadFormat.vp8().toBuilder().parameter("a=b", "1"));
        assertThrows(IllegalArgumentException.class, () -> RtpPayloadFormat.vp8().toBuilder().parameter("a", "1;2"));
        var bad = RtpPayloadFormat.h264(1).toBuilder().parameter("packetization-mode", "x").build();
        assertThrows(MediaException.Malformed.class, () -> RtpPacketizer.create(bad, RtpPacketizerOptions.defaults()));
        var range = RtpPayloadFormat.h264(1).toBuilder().parameter("packetization-mode", "7").build();
        assertThrows(MediaException.Malformed.class,
                () -> RtpDepacketizer.create(range, RtpDepacketizerOptions.defaults()));
        var sprop = RtpPayloadFormat.h264(1).toBuilder().parameter("sprop-parameter-sets", "@@").build();
        assertThrows(MediaException.Malformed.class,
                () -> RtpDepacketizer.create(sprop, RtpDepacketizerOptions.defaults()));
        var a = RtpPayloadFormat.vp8().toBuilder().parameter("Max-FR", "30").build();
        assertEquals(Map.of("max-fr", "30"), a.parameters());
        assertNotEquals(a, RtpPayloadFormat.vp8());
        assertEquals(RtpPayloadFormat.vp8(), a.toBuilder().removeParameter("MAX-FR").build());
        assertEquals(a.hashCode(), RtpPayloadFormat.vp8().toBuilder().parameter("max-fr", "30").build().hashCode());
    }

    @Test
    @DisplayName("derives the format of a track from its configuration")
    void forTrack() {
        var opus = AudioTrack.builder().id(1).codec(Codec.OPUS).timeBase(new Rational(1, 48_000)).sampleRate(48_000)
                .layout(ChannelLayout.STEREO).build();
        assertEquals(RtpPayloadFormat.opus(true), RtpPayloadFormat.forTrack(opus));
        var amr = AudioTrack.builder().id(1).codec(Codec.AMR_NB).timeBase(new Rational(1, 8000)).sampleRate(8000)
                .layout(ChannelLayout.STEREO).build();
        assertEquals("AMR/8000/2", RtpPayloadFormat.forTrack(amr).rtpmap());
        var aac = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(new Rational(1, 48_000)).sampleRate(48_000)
                .layout(ChannelLayout.STEREO).codecConfig(HexFormat.of().parseHex("1190")).build();
        assertEquals("1190", RtpPayloadFormat.forTrack(aac).parameter("config").orElseThrow());
        var noConfig = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(new Rational(1, 48_000))
                .sampleRate(48_000).layout(ChannelLayout.STEREO).build();
        assertThrows(MediaException.Malformed.class, () -> RtpPayloadFormat.forTrack(noConfig));
        var hevc = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 90_000)).width(16)
                .height(16).build();
        assertThrows(MediaException.Unsupported.class, () -> RtpPayloadFormat.forTrack(hevc));
        var av1 = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(new Rational(1, 1000)).width(16).height(16)
                .build();
        var packetizer = RtpPacketizer.create(av1, RtpChannel.fixed().build());
        assertEquals(RtpPayloadFormat.av1(), packetizer.format());
        var packets = packetizer.packetize(new RtpChannel.Unit(new byte[]{0x32, 0x01, 0x10}, 40, true).packet());
        assertEquals((0xFFFF_F000L + 3600) & 0xFFFF_FFFFL, packets.getFirst().timestamp(), "track time base");
    }

    @Test
    @DisplayName("keeps the options convention: defaults, round trips through toBuilder, value equality, ranges")
    void options() {
        var defaults = RtpPacketizerOptions.defaults();
        assertEquals(96, defaults.payloadType());
        assertEquals(OptionalLong.empty(), defaults.ssrc());
        assertEquals(1200, defaults.maxPacketSize());
        assertEquals(15, defaults.pictureIdBits());
        assertTrue(defaults.parameterSetsBeforeKeyFrames());
        var custom = RtpChannel.fixed().payloadType(0).framesPerPacket(3).timeBase(Rational.MILLISECONDS).build();
        assertEquals(custom, custom.toBuilder().build());
        assertEquals(custom.hashCode(), custom.toBuilder().build().hashCode());
        assertEquals(defaults, custom.toBuilder().payloadType(96).randomSsrc().randomSequenceNumber()
                .randomTimestamp().framesPerPacket(1).clockTimeBase().build());
        assertTrue(custom.toString().contains("framesPerPacket=3"));
        assertThrows(IllegalArgumentException.class, () -> RtpPacketizerOptions.builder().maxPacketSize(63));
        assertThrows(IllegalArgumentException.class, () -> RtpPacketizerOptions.builder().pictureIdBits(8));
        assertThrows(IllegalArgumentException.class, () -> RtpPacketizerOptions.builder().interleavingLength(17));
        assertThrows(IllegalArgumentException.class, () -> RtpPacketizerOptions.builder().ssrc(1L << 32));
        assertThrows(IllegalArgumentException.class,
                () -> RtpPacketizerOptions.builder().timeBase(new Rational(0, 1)));
        var depacketizer = RtpDepacketizerOptions.builder().reorderWindow(0).trackId(3).build();
        assertEquals(depacketizer, depacketizer.toBuilder().build());
        assertEquals(RtpDepacketizerOptions.defaults(), depacketizer.toBuilder().reorderWindow(16).trackId(1)
                .build());
        assertThrows(IllegalArgumentException.class, () -> RtpDepacketizerOptions.builder().reorderWindow(1025));
        assertThrows(NullPointerException.class, () -> RtpDepacketizerOptions.builder().limits(null));
        var random = List.of(RtpPacketizer.create(RtpPayloadFormat.vp8(), defaults).ssrc(),
                RtpPacketizer.create(RtpPayloadFormat.vp8(), defaults).ssrc());
        assertTrue(random.stream().allMatch(ssrc -> ssrc >= 0 && ssrc <= 0xFFFF_FFFFL));
    }
}
