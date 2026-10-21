package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264Setup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the decoder's damage and key-frame-needed signals and its low-delay mode. The loss tests
 * replay the open-loop model of the calls investigation (lib's RFC 6184 packetization at a
 * 1200-byte MTU, independent loss per RTP packet, five seeds) and require that a receiver showing
 * only the frames the decoder reports intact never shows a frame that differs from the clean
 * decode; the closed-loop test adds the picture loss indication a receiver sends while a key frame
 * is needed, with a three-frame round trip, against a live encoder.
 */
@DisplayName("H264Decoder damage signaling and low-delay mode")
class H264DamageTest {
    private static final VideoTrack TRACK = H264TestStreams.track(Optional.empty(), Optional.empty());

    private static final int TICKS = 3000;

    private static List<byte[]> javaStream;

    private static List<byte[]> x264Stream;

    /** A camera-like picture: moving gradients and a moving block over mild noise. */
    static YuvPicture moving(int width, int height, int frame) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        var random = new Random(frame * 7919L);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var u = x + 3 * frame;
                var v = y + 2 * frame;
                var value = 60 + ((u * 3 + v * 2) & 127) + (int) (30 * Math.sin(u * 0.05) * Math.cos(v * 0.07));
                if (Math.abs(x - width / 2 - (int) (width / 4.0 * Math.sin(frame * 0.15))) < width / 8
                        && Math.abs(y - height / 2) < height / 6) {
                    value = 210 - ((x + y) & 31);
                }
                picture.y()[y * width + x] = (byte) Math.clamp(value + random.nextInt(5) - 2, 0, 255);
            }
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            for (var x = 0; x < picture.chromaWidth(); x++) {
                picture.u()[y * picture.chromaWidth() + x] = (byte) (128 + (int) (40 * Math.sin((x + frame) * 0.08)));
                picture.v()[y * picture.chromaWidth() + x] = (byte) (128 + (int) (40 * Math.cos((y + frame) * 0.06)));
            }
        }
        return picture;
    }

    /** Encodes a call-like stream with the real-time preset: 640x480 at 600 kbps, an IDR every 2 s. */
    static synchronized List<byte[]> javaStream() {
        if (javaStream == null) {
            var config = H264Setup.realtime(640, 480, new Rational(30, 1), 600_000).withKeyframeInterval(60);
            var units = new ArrayList<byte[]>();
            try (var encoder = config.encoder()) {
                for (var i = 0; i < 150; i++) {
                    encoder.encode(new VideoFrame(moving(640, 480, i), (long) i * TICKS, 0, false),
                            packet -> units.add(packet.data()));
                }
            }
            javaStream = units;
        }
        return javaStream;
    }

    /** An x264 Baseline stream with three reference frames, so damage must follow the references used. */
    static synchronized List<byte[]> x264Stream() {
        if (x264Stream == null) {
            var file = Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=640x480:rate=30:duration=5"),
                    List.of("-c:v", "libx264", "-profile:v", "baseline", "-preset", "veryfast", "-x264-params",
                            "keyint=60:min-keyint=60:scenecut=0:ref=3:bframes=0", "-b:v", "600k", "-pix_fmt",
                            "yuv420p", "-f", "h264"));
            var units = new ArrayList<byte[]>();
            for (var unit : H264TestStreams.accessUnits(H264TestStreams.nalUnits(Fixtures.read(file)))) {
                units.add(H264TestStreams.annexB(unit));
            }
            x264Stream = units;
        }
        return x264Stream;
    }

    static Stream<Arguments> streams() {
        return Stream.of(Arguments.of("java-realtime"), Arguments.of("x264-baseline-3ref"));
    }

    static List<byte[]> stream(String name) {
        return name.equals("java-realtime") ? javaStream() : x264Stream();
    }

    private static Packet packet(byte[] data, int index) {
        return new Packet(1, data, (long) index * TICKS, (long) index * TICKS, TICKS, false);
    }

    /** Decodes every access unit intact and returns the frame of each, asserting none is damaged. */
    static List<byte[]> referenceFrames(List<byte[]> units) {
        var frames = new ArrayList<byte[]>();
        try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
            for (var i = 0; i < units.size(); i++) {
                var before = frames.size();
                decoder.decode(packet(units.get(i), i), (frame, damaged) -> {
                    assertFalse(damaged, "an intact stream decodes without damage");
                    frames.add(H264TestStreams.serialize((YuvPicture) frame.picture()));
                });
                assertEquals(before + 1, frames.size(), "one frame per access unit, before decode returns");
                assertFalse(decoder.keyframeNeeded());
            }
        }
        return frames;
    }

    private static void decode(H264Decoder decoder, Packet packet, boolean incomplete,
                               H264Decoder.FrameConsumer output) {
        if (incomplete) {
            decoder.decodeIncomplete(packet, output);
        } else {
            decoder.decode(packet, output);
        }
    }

    private static boolean isIdr(byte[] unit) {
        for (var nal : H264TestStreams.nalUnits(unit)) {
            if ((nal[0] & 0x1F) == 5) {
                return true;
            }
        }
        return false;
    }

    /** The outcome of one lossy replay. */
    record Replay(int slots, int shown, int corruptShown, int intactIdrHidden, int corruptUnflagged) {
    }

    /**
     * Replays a stream over the lossy channel and shows only the frames the decoder reports intact.
     *
     * @param honorIncomplete whether the receiver passes the per-access-unit loss flag on
     */
    static Replay replay(List<byte[]> units, List<byte[]> reference, double loss, long seed, boolean requireMarker,
                         boolean honorIncomplete) {
        return replay(units, reference, loss, seed, requireMarker, honorIncomplete, 0);
    }

    /**
     * Replays a stream over the lossy channel with a low-delay decoder, or with a frame-threaded
     * decoder when {@code threads} is positive, showing only the frames reported intact.
     */
    static Replay replay(List<byte[]> units, List<byte[]> reference, double loss, long seed, boolean requireMarker,
                         boolean honorIncomplete, int threads) {
        var packets = RtpH264.packetize(units);
        var random = new Random(seed * 7919L);
        var lost = new boolean[packets.size()];
        for (var p = 0; p < packets.size(); p++) {
            lost[p] = packets.get(p).accessUnit() >= 30 && random.nextDouble() < loss;
        }
        var received = RtpH264.receive(packets, lost, requireMarker);
        var counts = new int[4];
        var shownSlots = new boolean[units.size()];
        H264Decoder.FrameConsumer sink = (frame, damaged) -> {
            var slot = (int) (frame.pts() / TICKS);
            var exact = Arrays.equals(H264TestStreams.serialize((YuvPicture) frame.picture()), reference.get(slot));
            if (!damaged) {
                counts[0]++;
                shownSlots[slot] = true;
                if (!exact) {
                    counts[1]++;
                }
            } else if (!exact) {
                counts[3]++;
            }
        };
        var intactIdrs = new ArrayList<Integer>();
        try (var decoder = threads > 0 ? new H264Decoder(TRACK, H264DecoderOptions.builder().threads(threads).build())
                : new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
            for (var unit : received) {
                if (!unit.incomplete() && isIdr(unit.data())) {
                    intactIdrs.add(unit.accessUnit());
                }
                decode(decoder, packet(unit.data(), unit.accessUnit()), honorIncomplete && unit.incomplete(), sink);
            }
            decoder.flush(sink);
        }
        for (var idr : intactIdrs) {
            if (!shownSlots[idr]) {
                counts[2]++;
            }
        }
        return new Replay(units.size(), counts[0], counts[1], counts[2], counts[3]);
    }

    @Nested
    @DisplayName("on intact streams")
    class Intact {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264ConformanceTest#decodable")
        @DisplayName("conformance bitstreams decode bit-exactly with no frame reported damaged")
        void conformance(H264ConformanceTest.Vector vector) throws IOException {
            var archive = com.github.auties00.smalt.testkit.Conformance.fetch("h264-" + vector.directory() + "-"
                    + vector.archive(), "https://www.itu.int/wftp3/av-arch/jvt-site/draft_conformance/"
                    + vector.directory() + "/" + vector.archive(), vector.sha256());
            byte[] stream;
            byte[] reference;
            try (var zip = new ZipFile(archive.toFile())) {
                stream = zip.getInputStream(zip.getEntry(vector.bitstream())).readAllBytes();
                reference = zip.getInputStream(zip.getEntry(vector.reference())).readAllBytes();
            }
            var count = new int[1];
            try (var decoder = new H264Decoder(H264ConformanceTest.track())) {
                H264Decoder.FrameConsumer sink = (frame, damaged) -> {
                    assertFalse(damaged, vector.name() + ": frame " + count[0] + " reported damaged");
                    H264ConformanceTest.compare(vector, (YuvPicture) frame.picture(), reference, count[0]);
                    count[0]++;
                };
                for (var nal : H264TestStreams.splitAnnexB(stream)) {
                    decoder.decode(new Packet(1, nal, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false), sink);
                }
                decoder.flush(frame -> count[0]++);
                assertFalse(decoder.keyframeNeeded(), vector.name() + ": a key frame is reported needed");
            }
            assertEquals(vector.frames(), count[0], "frame count of " + vector.name());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264DamageTest#streams")
        @DisplayName("call streams give one intact frame per access unit before decode returns")
        void callStreams(String name) {
            var units = stream(name);
            assertEquals(units.size(), referenceFrames(units).size());
        }
    }

    static Stream<Arguments> openLoopCases() {
        var cases = new ArrayList<Arguments>();
        for (var name : List.of("java-realtime", "x264-baseline-3ref")) {
            for (var loss : new double[]{0.01, 0.03, 0.10}) {
                for (var requireMarker : new boolean[]{true, false}) {
                    cases.add(Arguments.of(name, loss, requireMarker));
                }
            }
        }
        return cases.stream();
    }

    @Nested
    @DisplayName("under open-loop packet loss")
    class OpenLoop {
        @ParameterizedTest(name = "{0} loss={1} markerGated={2}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264DamageTest#openLoopCases")
        @DisplayName("a receiver showing only intact frames never shows a damaged one, and resumes at every intact IDR")
        void noDamageShown(String name, double loss, boolean requireMarker, TestReporter reporter) {
            var units = stream(name);
            var reference = referenceFrames(units);
            int slots = 0, shown = 0, unflagged = 0;
            for (var seed = 1; seed <= 5; seed++) {
                var replay = replay(units, reference, loss, seed, requireMarker, true);
                assertEquals(0, replay.corruptShown(), "seed " + seed + ": damaged frames shown");
                assertEquals(0, replay.intactIdrHidden(), "seed " + seed + ": intact IDR access units not shown");
                slots += replay.slots();
                shown += replay.shown();
                var decoderOnly = replay(units, reference, loss, seed, requireMarker, false);
                unflagged += decoderOnly.corruptShown();
            }
            reporter.publishEntry(String.format(Locale.ROOT,
                    "%s loss=%.0f%% markerGated=%s: shown intact %.1f%% of slots; without the incomplete flag %d corrupt frames would be shown over 5 seeds",
                    name, loss * 100, requireMarker, 100.0 * shown / slots, unflagged));
            assertTrue(shown > 0);
        }

        // MR4_TANDBERG_C holds long-term references, whose staleness must be judged in stream
        // order; CAPM3_Sony_D, CABA3_SVA_B and CVBS3_Sony_C have B pictures whose lists skip the
        // stand-ins of lost reference pictures.
        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.ValueSource(strings = {"MR4_TANDBERG_C", "CAPM3_Sony_D", "CABA3_SVA_B",
                "CVBS3_Sony_C"})
        @DisplayName("conformance streams missing whole pictures show only exact frames as intact, the same with four threads")
        void droppedPictures(String name) throws IOException {
            H264InterlacedTest.assertDroppedPictures(name);
        }
    }

    @Nested
    @DisplayName("with frame threads")
    class Threaded {
        @Test
        @DisplayName("a frame-threaded decoder flags damage the same way, flushed frames included")
        void noDamageShown() {
            var units = javaStream();
            var reference = referenceFrames(units);
            for (var seed = 1; seed <= 3; seed++) {
                var replay = replay(units, reference, 0.03, seed, false, true, 4);
                assertEquals(0, replay.corruptShown(), "seed " + seed + ": damaged frames shown");
                assertEquals(0, replay.intactIdrHidden(), "seed " + seed + ": intact IDR access units not shown");
                assertTrue(replay.shown() > 0);
            }
        }
    }

    @Nested
    @DisplayName("in a closed loop with picture loss indications")
    class ClosedLoop {
        @ParameterizedTest(name = "loss={0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264DamageTest#lossRates")
        @DisplayName("requests while a key frame is needed restore exact frames, and no damaged frame is shown")
        void recovers(double loss, double minimumExact, TestReporter reporter) {
            var rtt = 3;
            var frames = 240;
            var config = H264Setup.realtime(320, 240, new Rational(30, 1), 300_000).withKeyframeInterval(0);
            var random = new Random(31);
            var exact = 0;
            var shownCorrupt = 0;
            var requests = 0;
            var lastRequest = -rtt;
            var pending = new ArrayList<Integer>();
            try (var encoder = config.encoder();
                 var reference = new H264Decoder(TRACK, H264DecoderOptions.forRealtime());
                 var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                for (var i = 0; i < frames; i++) {
                    if (pending.contains(i)) {
                        encoder.requestKeyframe();
                    }
                    var unit = new byte[1][];
                    encoder.encode(new VideoFrame(moving(320, 240, i), (long) i * TICKS, 0, false),
                            packet -> unit[0] = packet.data());
                    var clean = new byte[1][];
                    reference.decode(packet(unit[0], i),
                            (frame, damaged) -> clean[0] = H264TestStreams.serialize((YuvPicture) frame.picture()));
                    var packets = RtpH264.packetize(List.of(unit[0]));
                    var lost = new boolean[packets.size()];
                    for (var p = 0; p < lost.length; p++) {
                        lost[p] = i >= 5 && random.nextDouble() < loss;
                    }
                    var shown = new boolean[1];
                    var corrupt = new boolean[1];
                    for (var received : RtpH264.receive(packets, lost, false)) {
                        decode(decoder, packet(received.data(), i), received.incomplete(), (frame, damaged) -> {
                            if (!damaged) {
                                shown[0] = true;
                                corrupt[0] = !Arrays.equals(H264TestStreams.serialize((YuvPicture) frame.picture()), clean[0]);
                            }
                        });
                    }
                    if (shown[0] && !corrupt[0]) {
                        exact++;
                    }
                    if (corrupt[0]) {
                        shownCorrupt++;
                    }
                    if (decoder.keyframeNeeded() && i - lastRequest >= rtt) {
                        pending.add(i + rtt);
                        lastRequest = i;
                        requests++;
                    }
                }
            }
            reporter.publishEntry(String.format(Locale.ROOT, "loss=%.0f%%: exact frames %.1f%%, %d key frame requests",
                    loss * 100, 100.0 * exact / frames, requests));
            assertEquals(0, shownCorrupt, "damaged frames shown");
            assertTrue(exact >= minimumExact * frames, "exact frames " + exact + " of " + frames);
        }
    }

    static Stream<Arguments> lossRates() {
        return Stream.of(Arguments.of(0.01, 0.80), Arguments.of(0.03, 0.60), Arguments.of(0.10, 0.20));
    }

    @Nested
    @DisplayName("signals")
    class Signals {
        @Test
        @DisplayName("a stream joined after its IDR needs a key frame and shows nothing until one arrives")
        void joinedMidStream() {
            var units = javaStream();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                var frames = new int[1];
                for (var i = 5; i < 60; i++) {
                    decoder.decode(packet(units.get(i), i), (frame, damaged) -> frames[0]++);
                    assertTrue(decoder.keyframeNeeded(), "access unit " + i);
                }
                assertEquals(0, frames[0]);
                var intact = new boolean[1];
                decoder.decode(packet(units.get(60), 60), (frame, damaged) -> intact[0] = !damaged);
                assertTrue(intact[0], "the IDR picture is intact");
                assertFalse(decoder.keyframeNeeded());
            }
        }

        @Test
        @DisplayName("a lost reference picture damages every later picture until the next IDR")
        void lostReference() {
            var units = javaStream();
            var damage = new ArrayList<Boolean>();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                for (var i = 0; i < 90; i++) {
                    if (i == 20) {
                        continue;
                    }
                    decoder.decode(packet(units.get(i), i), (frame, damaged) -> damage.add(damaged));
                    assertEquals(i > 20 && i < 60, decoder.keyframeNeeded(), "access unit " + i);
                }
            }
            for (var k = 0; k < damage.size(); k++) {
                var unit = k < 20 ? k : k + 1;
                assertEquals(unit > 20 && unit < 60, damage.get(k), "access unit " + unit);
            }
        }

        @Test
        @DisplayName("an access unit declared incomplete is damaged even when what arrived decodes cleanly")
        void declaredIncomplete() {
            var units = javaStream();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                var damage = new boolean[3];
                for (var i = 0; i < 3; i++) {
                    var at = i;
                    decode(decoder, packet(units.get(i), i), i == 1, (frame, damaged) -> damage[at] = damaged);
                }
                assertFalse(damage[0]);
                assertTrue(damage[1]);
                assertTrue(damage[2], "the next picture predicts from the damaged one");
                assertTrue(decoder.keyframeNeeded());
            }
        }

        @Test
        @DisplayName("truncated and corrupt access units are reported as damage, never thrown")
        void corruptData() {
            var units = javaStream();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                decoder.decode(packet(units.get(0), 0), (frame, damaged) -> assertFalse(damaged));
                var truncated = Arrays.copyOf(units.get(1), units.get(1).length / 2);
                var damage = new boolean[1];
                decoder.decode(packet(truncated, 1), (frame, damaged) -> damage[0] = damaged);
                assertTrue(damage[0]);
                var garbage = units.get(2).clone();
                for (var k = 8; k < garbage.length; k += 7) {
                    garbage[k] ^= (byte) 0x5A;
                }
                decoder.decode(packet(garbage, 2), (frame, damaged) -> assertTrue(damaged));
                assertTrue(decoder.keyframeNeeded());
            }
        }

        @Test
        @DisplayName("a reported loss needs a key frame at once, and the next intact frame clears it")
        void reportedLoss() {
            var units = javaStream();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                decoder.decode(packet(units.get(0), 0), (frame, damaged) -> {
                });
                decoder.reportLoss();
                assertTrue(decoder.keyframeNeeded());
                decoder.decode(packet(units.get(1), 1), (frame, damaged) -> assertFalse(damaged));
                assertFalse(decoder.keyframeNeeded(), "nothing the next picture needs was lost");
            }
        }

        /**
         * A 32x32 Baseline stream like openh264's long-term reference mode: every IDR picture is
         * marked long-term and every P picture predicts (P_Skip) from long-term picture 0.
         */
        static List<byte[]> longTermStream() {
            var sps = new H264BitWriter().bits(66, 8).bits(0xC0, 8).bits(10, 8).ue(0).ue(0).ue(0).ue(0).ue(2).bit(1)
                    .ue(1).ue(1).bit(1).bit(1).bit(0).bit(0).trailing();
            var pps = new H264BitWriter().ue(0).ue(0).bit(0).bit(0).ue(0).ue(0).ue(0).bit(0).bits(0, 2).se(0).se(0).se(0)
                    .bit(0).bit(0).bit(0).trailing();
            var units = new ArrayList<byte[]>();
            for (var idr = 0; idr < 2; idr++) {
                var slice = new H264BitWriter().ue(0).ue(7).ue(0).bits(0, 4).ue(idr).bits(0, 4).bit(0).bit(1).se(0);
                for (var mb = 0; mb < 4; mb++) {
                    slice.ue(25);
                    while (!slice.aligned()) {
                        slice.bit(0);
                    }
                    for (var k = 0; k < 384; k++) {
                        slice.bits(16 + ((k * (7 + 50 * idr) + mb * 31) % 220), 8);
                    }
                }
                units.add(H264TestStreams.annexB(List.of(H264BitWriter.nal(3, 7, sps), H264BitWriter.nal(3, 8, pps),
                        H264BitWriter.nal(3, 5, slice.trailing()))));
                for (var n = 1; n <= 4; n++) {
                    var p = new H264BitWriter().ue(0).ue(5).ue(0).bits(n, 4).bits(2 * n, 4).bit(0).bit(1).ue(2).ue(0)
                            .ue(3).bit(0).se(0).ue(4);
                    units.add(H264TestStreams.annexB(List.of(H264BitWriter.nal(2, 1, p.trailing()))));
                }
            }
            return units;
        }

        @Test
        @DisplayName("after a reported loss, an earlier long-term picture no longer passes for the one a lost IDR replaced")
        void staleLongTermReference() {
            var units = longTermStream();
            var clean = new ArrayList<Boolean>();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                for (var i = 0; i < units.size(); i++) {
                    decoder.decode(packet(units.get(i), i), (frame, damaged) -> clean.add(damaged));
                }
            }
            assertEquals(List.of(false, false, false, false, false, false, false, false, false, false), clean);
            var lossy = new ArrayList<Boolean>();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                for (var i = 0; i < units.size(); i++) {
                    if (i != 5) {
                        // the stream allows frame_num gaps, so the receiver's loss report is what marks the loss
                        decode(decoder, packet(units.get(i), i), i == 6, (frame, damaged) -> lossy.add(damaged));
                    }
                }
                assertTrue(decoder.keyframeNeeded());
            }
            assertEquals(List.of(false, false, false, false, false, true, true, true, true), lossy,
                    "the P pictures after the lost IDR predict from the wrong long-term picture");
            var decoderOnly = new ArrayList<Boolean>();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                for (var i = 0; i < units.size(); i++) {
                    if (i == 5) {
                        decoder.reportLoss();
                        continue;
                    }
                    decoder.decode(packet(units.get(i), i), (frame, damaged) -> decoderOnly.add(damaged));
                }
            }
            assertEquals(lossy, decoderOnly, "reportLoss marks the same loss");
        }

        @Test
        @DisplayName("the consumer's own exception propagates unchanged")
        void consumerFailure() {
            var units = javaStream();
            var failure = new IllegalStateException("consumer");
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                var thrown = assertThrows(IllegalStateException.class,
                        () -> decoder.decode(packet(units.get(0), 0), (frame, damaged) -> {
                            throw failure;
                        }));
                assertSame(failure, thrown);
            }
        }

        @Test
        @DisplayName("the consumer's exception thrown while a failed packet is abandoned propagates unchanged")
        void consumerFailureWhileAbandoning() {
            // a data partition NAL unit after a cut slice fails the packet with Unsupported while the
            // picture is still open, so the low-delay decoder outputs it while abandoning the packet
            var units = javaStream();
            var partition = new byte[]{0, 0, 1, 0x62, (byte) 0x80};
            var partial = Arrays.copyOf(units.get(1), Math.max(8, units.get(1).length / 3));
            var data = Arrays.copyOf(partial, partial.length + partition.length);
            System.arraycopy(partition, 0, data, partial.length, partition.length);
            var failure = new IllegalStateException("consumer");
            var packet = packet(data, 1);
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                decoder.decode(packet(units.get(0), 0), (frame, damaged) -> {
                });
                var thrown = assertThrows(RuntimeException.class, () -> decoder.decode(packet, (frame, damaged) -> {
                    throw failure;
                }));
                assertSame(failure, thrown);
            }
        }

        @Test
        @DisplayName("the consumer's exception propagates unchanged from decode with a plain consumer")
        void plainConsumerFailure() {
            var units = javaStream();
            var failure = new IllegalStateException("consumer");
            try (var decoder = new H264Decoder(TRACK)) {
                var thrown = assertThrows(RuntimeException.class, () -> {
                    for (var i = 0; i < units.size(); i++) {
                        decoder.decode(packet(units.get(i), i), frame -> {
                            throw failure;
                        });
                    }
                });
                assertSame(failure, thrown);
            }
        }

        @Test
        @DisplayName("unsupported streams still raise Unsupported")
        void unsupported() {
            var file = Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=30:duration=0.1"),
                    List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "h264"));
            var units = H264TestStreams.accessUnits(H264TestStreams.nalUnits(Fixtures.read(file)));
            // a slice data partition A (Extended profile) ahead of the first picture's slices
            var first = new ArrayList<>(units.getFirst());
            first.add(first.size() - 1, H264BitWriter.nal(2, 2, new H264BitWriter().ue(0).ue(2).ue(0).ue(0)
                    .bits(0, 7).trailing()));
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                assertThrows(MediaException.Unsupported.class,
                        () -> decoder.decode(packet(H264TestStreams.annexB(first), 0), (frame, damaged) -> {
                        }));
            }
        }
    }

    @Nested
    @DisplayName("low-delay mode")
    class LowDelay {
        /** A 32x32 Baseline stream with picture order count type 0 and no VUI: I_PCM IDR, then P_Skip pictures. */
        static List<byte[]> pocType0Stream(int pictures) {
            var sps = new H264BitWriter().bits(66, 8).bits(0xC0, 8).bits(10, 8).ue(0).ue(0).ue(0).ue(0).ue(1).bit(0)
                    .ue(1).ue(1).bit(1).bit(1).bit(0).bit(0).trailing();
            var pps = new H264BitWriter().ue(0).ue(0).bit(0).bit(0).ue(0).ue(0).ue(0).bit(0).bits(0, 2).se(0).se(0).se(0)
                    .bit(0).bit(0).bit(0).trailing();
            var units = new ArrayList<byte[]>();
            for (var n = 0; n < pictures; n++) {
                var slice = new H264BitWriter();
                byte[] nal;
                if (n == 0) {
                    slice.ue(0).ue(7).ue(0).bits(0, 4).ue(0).bits(0, 4).bit(0).bit(0).se(0);
                    for (var mb = 0; mb < 4; mb++) {
                        slice.ue(25);
                        while (!slice.aligned()) {
                            slice.bit(0);
                        }
                        for (var k = 0; k < 384; k++) {
                            slice.bits(16 + ((k * 7 + mb * 31) % 220), 8);
                        }
                    }
                    nal = H264BitWriter.nal(3, 5, slice.trailing());
                    units.add(H264TestStreams.annexB(List.of(H264BitWriter.nal(3, 7, sps), H264BitWriter.nal(3, 8, pps), nal)));
                } else {
                    slice.ue(0).ue(5).ue(0).bits(n & 15, 4).bits((2 * n) & 15, 4).bit(0).bit(0).bit(0).se(0).ue(4);
                    nal = H264BitWriter.nal(2, 1, slice.trailing());
                    units.add(H264TestStreams.annexB(List.of(nal)));
                }
            }
            return units;
        }

        @Test
        @DisplayName("outputs each picture of a Baseline stream with no reordering limit before decode returns")
        void baselineWithoutVui() {
            var units = pocType0Stream(12);
            var buffered = new int[1];
            try (var decoder = new H264Decoder(TRACK)) {
                for (var i = 0; i < units.size(); i++) {
                    decoder.decode(packet(units.get(i), i), frame -> buffered[0]++);
                }
                assertTrue(buffered[0] < units.size(), "the regular decoder holds pictures for reordering");
            }
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                for (var i = 0; i < units.size(); i++) {
                    var out = new ArrayList<Long>();
                    decoder.decode(packet(units.get(i), i), (frame, damaged) -> {
                        assertFalse(damaged);
                        out.add(frame.pts());
                    });
                    assertEquals(List.of((long) i * TICKS), out, "access unit " + i);
                }
            }
        }

        @Test
        @DisplayName("an access unit missing its last slice data is output at once, concealed and damaged")
        void finishesAtPacketEnd() {
            var units = javaStream();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                decoder.decode(packet(units.get(0), 0), (frame, damaged) -> {
                });
                var partial = Arrays.copyOf(units.get(1), Math.max(8, units.get(1).length / 3));
                var out = new ArrayList<Boolean>();
                decoder.decode(packet(partial, 1), (frame, damaged) -> out.add(damaged));
                assertEquals(List.of(true), out);
            }
        }
    }
}
