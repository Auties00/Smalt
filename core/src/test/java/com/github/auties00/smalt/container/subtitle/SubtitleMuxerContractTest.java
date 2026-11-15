package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the state machine every subtitle file muxer shares: tracks are declared before the first packet, a file
 * holds the tracks and codecs it can, packets name a declared track, a codec configuration change is refused, and
 * nothing is accepted after {@code finish} or {@code close}.
 */
@DisplayName("subtitle file muxer contract")
class SubtitleMuxerContractTest {
    private static final Rational MILLIS = Rational.timeBaseOf(1000);

    /**
     * One subtitle muxer under test.
     *
     * @param name the display name
     * @param factory creates the muxer over a sink
     * @param codec the codec it carries
     * @param maxTracks the most tracks it holds
     * @param payload a valid packet payload of the codec
     */
    record Case(String name, Function<MediaSink, Muxer> factory, Codec codec, int maxTracks, byte[] payload) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> muxers() {
        var text = "line".getBytes(StandardCharsets.UTF_8);
        var ass = "0,0,Default,,0,0,0,,line".getBytes(StandardCharsets.UTF_8);
        var ttml = ("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"0s\" end=\"1s\">line</p>"
                + "</div></body></tt>").getBytes(StandardCharsets.UTF_8);
        var pgs = new byte[]{(byte) 0x80, 0, 0};
        var spu = new byte[]{0, 8, 0, 4, (byte) 0xFF, 0, 0, 0};
        return Stream.of(
                new Case("SubRip", SubRipMuxer::new, Codec.SUBRIP, 1, text),
                new Case("WebVTT", WebVttMuxer::new, Codec.WEBVTT, 1, text),
                new Case("LRC", LrcMuxer::new, Codec.WEBVTT, 1, text),
                new Case("MicroDVD", MicroDvdMuxer::new, Codec.SUBRIP, 1, text),
                new Case("SAMI", SamiMuxer::new, Codec.SUBRIP, Integer.MAX_VALUE, text),
                new Case("ASS", AssMuxer::new, Codec.ASS, 1, ass),
                new Case("SSA", sink -> new AssMuxer(sink, AssScriptType.V4), Codec.ASS, 1, ass),
                new Case("TTML", TtmlMuxer::new, Codec.TTML, 1, ttml),
                new Case("SUP", SupMuxer::new, Codec.PGS, 1, pgs),
                new Case("VobSub", sink -> new VobSubMuxer(MediaSink.memory(), sink), Codec.DVD_SUBTITLE, 32, spu));
    }

    static Track track(int id, Codec codec) {
        return DataTrack.builder().id(id).codec(codec).timeBase(MILLIS).build();
    }

    static Codec other(Codec codec) {
        return codec == Codec.TTML ? Codec.SUBRIP : Codec.TTML;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("refuses a track of another codec")
    void foreignCodec(Case c) {
        try (var muxer = c.factory().apply(MediaSink.memory())) {
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(track(1, other(c.codec()))));
            assertThrows(NullPointerException.class, () -> muxer.addTrack(null));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("holds no more tracks than the format can")
    void trackLimit(Case c) {
        try (var muxer = c.factory().apply(MediaSink.memory())) {
            muxer.addTrack(track(1, c.codec()));
            if (c.maxTracks() == 1) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(track(2, c.codec())));
            } else {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(track(1, c.codec())),
                        "a repeated identifier");
                assertDoesNotThrow(() -> muxer.addTrack(track(2, c.codec())));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("refuses packets of undeclared tracks, and before any track")
    void undeclaredTrack(Case c) {
        try (var muxer = c.factory().apply(MediaSink.memory())) {
            assertThrows(IllegalArgumentException.class,
                    () -> muxer.writePacket(new Packet(1, c.payload(), 0, 0, 100, true)));
            muxer.addTrack(track(1, c.codec()));
            assertThrows(IllegalArgumentException.class,
                    () -> muxer.writePacket(new Packet(9, c.payload(), 0, 0, 100, true)));
            assertThrows(NullPointerException.class, () -> muxer.writePacket(null));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("declares no track after the first packet")
    void lateTrack(Case c) {
        try (var muxer = c.factory().apply(MediaSink.memory())) {
            muxer.addTrack(track(1, c.codec()));
            muxer.writePacket(new Packet(1, c.payload(), 0, 0, 100, true));
            assertThrows(IllegalStateException.class, () -> muxer.addTrack(track(2, c.codec())));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("accepts nothing after finish, and finishes once")
    void afterFinish(Case c) {
        var sink = MediaSink.memory();
        try (var muxer = c.factory().apply(sink)) {
            muxer.addTrack(track(1, c.codec()));
            muxer.writePacket(new Packet(1, c.payload(), Packet.NO_TIMESTAMP, 5, 100, true));
            muxer.finish();
            assertThrows(IllegalStateException.class, muxer::finish);
            assertThrows(IllegalStateException.class,
                    () -> muxer.writePacket(new Packet(1, c.payload(), 0, 0, 100, true)));
            assertThrows(IllegalStateException.class, () -> muxer.addTrack(track(2, c.codec())));
        }
        if (c.codec() != Codec.DVD_SUBTITLE) {
            assertTrue(sink.toByteArray().length > 0, "the file was written");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("accepts nothing after close")
    void afterClose(Case c) {
        var muxer = c.factory().apply(MediaSink.memory());
        muxer.addTrack(track(1, c.codec()));
        muxer.close();
        assertThrows(IllegalStateException.class, () -> muxer.writePacket(new Packet(1, c.payload(), 0, 0, 100, true)));
        assertThrows(IllegalStateException.class, () -> muxer.addTrack(track(2, c.codec())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("refuses a packet that changes the codec configuration")
    void configChange(Case c) {
        try (var muxer = c.factory().apply(MediaSink.memory())) {
            muxer.addTrack(track(1, c.codec()));
            var side = PacketSideData.builder().newCodecConfig(new byte[]{1, 2, 3}).build();
            var packet = new Packet(1, c.payload(), 0, 0, 100, EnumSet.of(PacketFlag.KEY), side);
            if (c.codec() == Codec.TTML) {
                // each TTML packet is a whole document, so the muxer stores no configuration to change
                assertDoesNotThrow(() -> muxer.writePacket(packet));
            } else {
                assertThrows(MediaException.Unsupported.class, () -> muxer.writePacket(packet));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("finishes an empty file")
    void empty(Case c) {
        try (var muxer = c.factory().apply(MediaSink.memory())) {
            assertDoesNotThrow(muxer::finish);
        }
    }

    @Test
    @DisplayName("TTML joins the cues of several documents within each document's window")
    void ttmlDocuments() {
        var sink = MediaSink.memory();
        try (var muxer = new TtmlMuxer(sink)) {
            muxer.addTrack(track(1, Codec.TTML));
            muxer.writePacket(new Packet(1, ttml("0s", "4s", "first"), 0, 0, 2000, true));
            muxer.writePacket(new Packet(1, ttml("0s", "4s", "first"), 2000, 2000, 2000, true));
            muxer.writePacket(new Packet(1, ttml("3s", "5s", "second"), 3000, 3000, Packet.UNKNOWN_DURATION, true));
            muxer.writePacket(new Packet(1, ttml("9s", "10s", "outside"), 0, 0, 1000, true));
            muxer.finish();
        }
        var written = new String(sink.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(1, count(written, "first"), "the two halves of the same cue join: " + written);
        assertTrue(written.contains("second"), written);
        assertFalse(written.contains("outside"), "a cue outside its document's window is dropped: " + written);
    }

    @Test
    @DisplayName("TTML writes one document repeated by every packet as it is")
    void ttmlSameDocument() {
        var sink = MediaSink.memory();
        var document = ttml("0s", "1s", "same");
        try (var muxer = new TtmlMuxer(sink)) {
            muxer.addTrack(track(1, Codec.TTML));
            muxer.writePacket(new Packet(1, document, 0, 0, 1000, true));
            muxer.writePacket(new Packet(1, document, 1000, 1000, 1000, true));
            muxer.finish();
        }
        assertEquals(new String(document, StandardCharsets.UTF_8), new String(sink.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("TTML refuses a malformed document among several")
    void ttmlMalformed() {
        try (var muxer = new TtmlMuxer(MediaSink.memory())) {
            muxer.addTrack(track(1, Codec.TTML));
            muxer.writePacket(new Packet(1, ttml("0s", "1s", "a"), 0, 0, 1000, true));
            muxer.writePacket(new Packet(1, "<tt><body".getBytes(StandardCharsets.UTF_8), 1000, 1000, 1000, true));
            assertThrows(MediaException.Malformed.class, muxer::finish);
        }
    }

    @Test
    @DisplayName("MicroDVD refuses frame rates it cannot declare")
    void microDvdRates() {
        var sink = MediaSink.memory();
        assertThrows(IllegalArgumentException.class, () -> new MicroDvdMuxer(sink, new Rational(1, 1_000_000)));
        assertThrows(IllegalArgumentException.class, () -> new MicroDvdMuxer(sink, new Rational(5000, 1)));
        assertThrows(NullPointerException.class, () -> new MicroDvdMuxer(sink, null));
        assertDoesNotThrow(() -> new MicroDvdMuxer(sink, new Rational(25, 1)).close());
    }

    @Test
    @DisplayName("SAMI names each language's class after its two-letter code")
    void samiLanguages() {
        var sink = MediaSink.memory();
        try (var muxer = new SamiMuxer(sink)) {
            muxer.addTrack(DataTrack.builder().id(1).codec(Codec.SUBRIP).timeBase(MILLIS).language("fre").build());
            muxer.addTrack(DataTrack.builder().id(2).codec(Codec.SUBRIP).timeBase(MILLIS).language("en").build());
            muxer.addTrack(DataTrack.builder().id(3).codec(Codec.SUBRIP).timeBase(MILLIS).language("qqq").build());
            muxer.addTrack(DataTrack.builder().id(4).codec(Codec.SUBRIP).timeBase(MILLIS).language("x").build());
            for (var id = 1; id <= 4; id++) {
                muxer.writePacket(new Packet(id, ("text " + id).getBytes(StandardCharsets.UTF_8), 0, 0, 1000, true));
            }
            muxer.finish();
        }
        var written = new String(sink.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(written.contains("lang: fr"), written);
        assertTrue(written.contains("lang: en"), written);
        for (var id = 1; id <= 4; id++) {
            assertTrue(written.contains("text " + id), written);
        }
    }

    private static byte[] ttml(String begin, String end, String text) {
        return ("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"" + begin + "\" end=\"" + end + "\">"
                + text + "</p></div></body></tt>").getBytes(StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        var count = 0;
        for (var at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }
}
