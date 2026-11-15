package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the interlaced behavior the conformance rows do not reach: a frame left with a single
 * field, which is output with its other field mid-gray and damaged only when data was lost next
 * to the field, the timestamps and durations of woven frames, low-delay mode, damage tracking
 * through a lost reference of an MBAFF stream, and conformance streams missing whole pictures,
 * whose frames reported intact must be exact and whose output must not depend on the thread count.
 */
@DisplayName("H264Decoder interlaced pictures")
class H264InterlacedTest {
    private static final int TICKS = 3000;

    /** Reads the bitstream of a conformance vector as NAL units without start codes. */
    private static List<byte[]> conformanceNals(String name) throws IOException {
        var vector = H264ConformanceTest.vectors().stream().filter(entry -> entry.name().equals(name)).findFirst()
                .orElseThrow();
        var archive = Conformance.fetch("h264-" + vector.directory() + "-" + vector.archive(),
                "https://www.itu.int/wftp3/av-arch/jvt-site/draft_conformance/" + vector.directory() + "/"
                        + vector.archive(), vector.sha256());
        try (var zip = new ZipFile(archive.toFile())) {
            return H264TestStreams.nalUnits(zip.getInputStream(zip.getEntry(vector.bitstream())).readAllBytes());
        }
    }

    /** A decoded frame, copied, with its damage flag. */
    private record Output(byte[] samples, int width, int height, boolean damaged) {
    }

    /** Decodes NAL units one per packet, flushes, and returns every frame in output order. */
    private static List<Output> decode(List<byte[]> nals, boolean lossAtEnd, int threads) {
        var frames = new ArrayList<Output>();
        H264Decoder.FrameConsumer sink = (frame, damaged) -> {
            var picture = (YuvPicture) frame.picture();
            frames.add(new Output(H264TestStreams.serialize(picture), picture.width(), picture.height(), damaged));
        };
        try (var decoder = new H264Decoder(H264ConformanceTest.track(),
                H264DecoderOptions.builder().threads(threads).build())) {
            for (var nal : nals) {
                decoder.decode(new Packet(1, H264TestStreams.annexB(List.of(nal)), Packet.NO_TIMESTAMP,
                        Packet.NO_TIMESTAMP, 0, false), sink);
            }
            if (lossAtEnd) {
                decoder.reportLoss();
            }
            decoder.flush(sink);
        }
        return frames;
    }

    /** Returns the NAL units before the last picture: the last slice with {@code first_mb_in_slice} 0. */
    private static List<byte[]> withoutLastPicture(List<byte[]> nals) {
        var last = -1;
        for (var k = 0; k < nals.size(); k++) {
            var type = nals.get(k)[0] & 0x1F;
            if ((type == 1 || type == 5) && (nals.get(k)[1] & 0x80) != 0) {
                last = k;
            }
        }
        return nals.subList(0, last);
    }

    /**
     * Returns whether the rows of one parity of a frame equal the reference and the rows of the
     * other parity are mid-gray, in every plane of a 4:2:0 frame.
     */
    private static boolean singleField(Output frame, byte[] reference, int keptParity) {
        var w = frame.width();
        var h = frame.height();
        var cw = (w + 1) / 2;
        var ch = (h + 1) / 2;
        for (var plane = 0; plane < 3; plane++) {
            var base = plane == 0 ? 0 : w * h + (plane - 1) * cw * ch;
            var width = plane == 0 ? w : cw;
            var height = plane == 0 ? h : ch;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = base + y * width + x;
                    var expected = (y & 1) == keptParity ? reference[at] : (byte) 128;
                    if (frame.samples()[at] != expected) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Nested
    @DisplayName("a frame left with a single field")
    class Unpaired {
        @ParameterizedTest(name = "{0} threads")
        @ValueSource(ints = {1, 4})
        @DisplayName("is output with its decoded field and a mid-gray field, undamaged")
        void graySecondField(int threads) throws IOException {
            var nals = conformanceNals("CVFI1_SVA_C");
            var full = decode(nals, false, threads);
            var cut = decode(withoutLastPicture(nals), false, threads);
            assertEquals(full.size(), cut.size(), "the frame holding the single field is still output");
            for (var k = 0; k < full.size() - 1; k++) {
                assertArrayEquals(full.get(k).samples(), cut.get(k).samples(), "frame " + k);
                assertFalse(cut.get(k).damaged(), "frame " + k);
            }
            var last = cut.getLast();
            var reference = full.getLast().samples();
            assertTrue(singleField(last, reference, 0) || singleField(last, reference, 1),
                    "one field is decoded and the other is mid-gray");
            assertFalse(last.damaged(), "nothing was lost after the field");
        }

        @Test
        @DisplayName("is damaged when a loss is reported after its field")
        void damagedAfterLoss() throws IOException {
            var nals = conformanceNals("CVFI1_SVA_C");
            var cut = decode(withoutLastPicture(nals), true, 1);
            assertTrue(cut.getLast().damaged(), "the lost data may have held the second field");
            for (var k = 0; k < cut.size() - 1; k++) {
                assertFalse(cut.get(k).damaged(), "frame " + k);
            }
        }
    }

    /** A decoded frame with its timestamps and damage flag. */
    private record Timed(long pts, long duration, byte[] samples, boolean damaged) {
    }

    /**
     * Decodes access units one per packet, packet {@code i} stamped {@code i * TICKS}, skipping the
     * dropped ones and reporting each drop as a loss when asked.
     */
    private static List<Timed> decodeUnits(List<List<byte[]>> units, boolean[] dropped, boolean reportLoss,
                                           H264DecoderOptions options) {
        var frames = new ArrayList<Timed>();
        H264Decoder.FrameConsumer sink = (frame, damaged) -> frames.add(new Timed(frame.pts(), frame.duration(),
                H264TestStreams.serialize((YuvPicture) frame.picture()), damaged));
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var i = 0; i < units.size(); i++) {
                if (dropped[i]) {
                    if (reportLoss) {
                        decoder.reportLoss();
                    }
                    continue;
                }
                var packet = new Packet(1, H264TestStreams.annexB(units.get(i)), (long) i * TICKS, (long) i * TICKS,
                        TICKS, false);
                try {
                    decoder.decode(packet, sink);
                } catch (com.github.auties00.smalt.MediaException expected) {
                    // A picture predicting from a lost one may be rejected; decoding continues, but
                    // whole lost pictures never make the decoder fail internally.
                    assertInternalFailureFree(expected);
                }
            }
            try {
                decoder.flush(sink);
            } catch (com.github.auties00.smalt.MediaException expected) {
                assertInternalFailureFree(expected);
            }
        }
        return frames;
    }

    private static void assertInternalFailureFree(com.github.auties00.smalt.MediaException failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (!(cause instanceof com.github.auties00.smalt.MediaException)) {
                throw new AssertionError("internal decoder failure", failure);
            }
        }
    }

    private static List<List<byte[]>> conformanceUnits(String name) throws IOException {
        return H264TestStreams.accessUnits(conformanceNals(name));
    }

    /** Reads Exp-Golomb and fixed-length fields of an RBSP, emulation prevention bytes removed. */
    private static final class Bits {
        private final byte[] data;
        private int position;

        Bits(byte[] nal) {
            var out = new java.io.ByteArrayOutputStream();
            var zeros = 0;
            for (var i = 1; i < nal.length; i++) {
                if (zeros >= 2 && nal[i] == 3) {
                    zeros = 0;
                    continue;
                }
                zeros = nal[i] == 0 ? zeros + 1 : 0;
                out.write(nal[i]);
            }
            data = out.toByteArray();
        }

        int bit() {
            var value = (data[position >> 3] >> (7 - (position & 7))) & 1;
            position++;
            return value;
        }

        int bits(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                value = (value << 1) | bit();
            }
            return value;
        }

        int ue() {
            var zeros = 0;
            while (bit() == 0) {
                zeros++;
            }
            return (1 << zeros) - 1 + bits(zeros);
        }
    }

    /**
     * Returns, for each access unit, whether it is a field picture, read from its first slice
     * header up to {@code field_pic_flag} with the fields of the last sequence parameter set
     * (which must carry no scaling matrices).
     */
    private static boolean[] fieldFlags(List<List<byte[]>> units) {
        var flags = new boolean[units.size()];
        var log2MaxFrameNum = -1;
        var frameMbsOnly = true;
        for (var i = 0; i < units.size(); i++) {
            var sliceSeen = false;
            for (var nal : units.get(i)) {
                var type = nal[0] & 0x1F;
                if (type == 7) {
                    var bits = new Bits(nal);
                    var profile = bits.bits(8);
                    bits.bits(16);
                    bits.ue();
                    if (List.of(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135).contains(profile)) {
                        if (bits.ue() == 3) {
                            bits.bit();
                        }
                        bits.ue();
                        bits.ue();
                        bits.bit();
                        assertEquals(0, bits.bit(), "the test parser reads no scaling matrices");
                    }
                    log2MaxFrameNum = bits.ue() + 4;
                    var pocType = bits.ue();
                    if (pocType == 0) {
                        bits.ue();
                    } else if (pocType == 1) {
                        bits.bit();
                        bits.ue();
                        bits.ue();
                        var cycle = bits.ue();
                        for (var k = 0; k < cycle; k++) {
                            bits.ue();
                        }
                    }
                    bits.ue();
                    bits.bit();
                    bits.ue();
                    bits.ue();
                    frameMbsOnly = bits.bit() == 1;
                } else if ((type == 1 || type == 5) && !sliceSeen) {
                    sliceSeen = true;
                    var bits = new Bits(nal);
                    bits.ue();
                    bits.ue();
                    bits.ue();
                    bits.bits(log2MaxFrameNum);
                    flags[i] = !frameMbsOnly && bits.bit() == 1;
                }
            }
        }
        return flags;
    }

    @Nested
    @DisplayName("timing")
    class Timing {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"CVFI1_SVA_C", "CAPAMA3_Sand_F", "CVPA1_TOSHIBA_B"})
        @DisplayName("a woven frame carries the timestamp of the packet holding its first field and the durations of both fields' packets")
        void firstFieldTimestamp(String name) throws IOException {
            var units = conformanceUnits(name);
            var fields = fieldFlags(units);
            var expected = new java.util.TreeMap<Long, Long>();
            for (var i = 0; i < units.size(); i++) {
                if (!fields[i]) {
                    expected.put((long) i * TICKS, (long) TICKS);
                } else if (pairedBefore(fields, i)) {
                    expected.put((long) i * TICKS, 2L * TICKS);
                }
            }
            var frames = decodeUnits(units, new boolean[units.size()], false, H264DecoderOptions.builder().build());
            var actual = new java.util.TreeMap<Long, Long>();
            for (var frame : frames) {
                actual.put(frame.pts(), frame.duration());
            }
            assertEquals(frames.size(), actual.size(), "every frame has its own timestamp");
            assertEquals(expected, actual);
            var packed = new ArrayList<List<byte[]>>();
            for (var i = 0; i < units.size(); i++) {
                if (fields[i] && !pairedBefore(fields, i)) {
                    packed.getLast().addAll(units.get(i));
                } else {
                    packed.add(new ArrayList<>(units.get(i)));
                }
            }
            for (var frame : decodeUnits(packed, new boolean[packed.size()], false, H264DecoderOptions.builder().build())) {
                assertEquals(TICKS, frame.duration(), "a packet holding both fields gives the frame its own duration");
            }
        }

        /** Returns whether field {@code i} follows a field that already completed a pair. */
        private static boolean pairedBefore(boolean[] fields, int i) {
            var run = 0;
            for (var k = i - 1; k >= 0 && fields[k]; k--) {
                run++;
            }
            return (run & 1) == 0;
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"CVFI1_SVA_C", "CAPAMA3_Sand_F", "Sharp_MP_PAFF_1r2"})
        @DisplayName("low-delay mode outputs the frames of the default mode, in the same order")
        void lowDelay(String name) throws IOException {
            var units = conformanceUnits(name);
            var none = new boolean[units.size()];
            var reference = decodeUnits(units, none, false, H264DecoderOptions.builder().build());
            var realtime = decodeUnits(units, none, false, H264DecoderOptions.forRealtime());
            assertEquals(reference.size(), realtime.size());
            for (var k = 0; k < reference.size(); k++) {
                assertEquals(reference.get(k).pts(), realtime.get(k).pts(), "frame " + k);
                assertArrayEquals(reference.get(k).samples(), realtime.get(k).samples(), "frame " + k);
                assertFalse(realtime.get(k).damaged(), "frame " + k);
            }
        }
    }

    @Nested
    @DisplayName("under loss")
    class Loss {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"CVFI1_Sony_D", "CAPAMA3_Sand_F", "CVPA1_TOSHIBA_B", "Sharp_MP_Field_2_B",
                "cavlc_mot_picaff0_full_B", "CAMA3_Sand_E", "cabac_mot_mbaff0_full", "CVMP_MOT_FLD_L30_B", "CAPA1_TOSHIBA_B",
                "MR9_BT_B", "HPCAFL_BRCM_C", "CVSEFDFT3_Sony_E"})
        @DisplayName("frames reported intact are exact, and one and four threads give the same frames")
        void droppedFields(String name) throws IOException {
            assertDroppedPictures(name);
        }
    }

    /**
     * Drops random access units of a conformance vector (after the first two), each drop reported
     * as a loss or not, and checks that one and four threads output the same frames with the same
     * damage flags, that every frame reported intact after reported losses equals the frame of
     * the clean stream with its timestamp, and that the decoder never fails internally.
     */
    static void assertDroppedPictures(String name) throws IOException {
        var vector = H264ConformanceTest.vectors().stream()
                .filter(entry -> entry.name().equals(name)).findFirst().orElseThrow();
        var units = conformanceUnits(vector.name());
        var reference = new java.util.HashMap<Long, byte[]>();
        for (var frame : decodeUnits(units, new boolean[units.size()], false, H264DecoderOptions.builder().build())) {
            reference.put(frame.pts(), frame.samples());
        }
        var random = new java.util.Random(name.hashCode());
        var shown = new int[1];
        assertTimeoutPreemptively(java.time.Duration.ofMinutes(3), () -> lossTrials(units, reference, random, shown));
        assertTrue(shown[0] > 0, "some frames are reported intact");
    }

    private static void lossTrials(List<List<byte[]>> units, java.util.Map<Long, byte[]> reference,
                                   java.util.Random random, int[] shown) {
        for (var trial = 0; trial < 4; trial++) {
            var dropped = new boolean[units.size()];
            for (var i = 2; i < units.size(); i++) {
                dropped[i] = random.nextInt(100) < 8;
            }
            for (var reportLoss : new boolean[]{true, false}) {
                var single = decodeUnits(units, dropped, reportLoss, H264DecoderOptions.builder().threads(1).build());
                var threaded = decodeUnits(units, dropped, reportLoss, H264DecoderOptions.builder().threads(4).build());
                assertEquals(single.size(), threaded.size(), "trial " + trial);
                for (var k = 0; k < single.size(); k++) {
                    var label = "trial " + trial + " (loss reported: " + reportLoss + "), frame " + k;
                    assertEquals(single.get(k).pts(), threaded.get(k).pts(), label);
                    assertEquals(single.get(k).damaged(), threaded.get(k).damaged(), label);
                    assertArrayEquals(single.get(k).samples(), threaded.get(k).samples(), label);
                }
                if (!reportLoss) {
                    continue;
                }
                for (var frame : single) {
                    if (!frame.damaged()) {
                        shown[0]++;
                        assertArrayEquals(reference.get(frame.pts()), frame.samples(),
                                "trial " + trial + ": the intact frame at " + frame.pts());
                    }
                }
            }
        }
    }

    /** An x264 MBAFF stream of 60 frames at 320x240 with an IDR every 30 frames and no B frames. */
    private static List<byte[]> mbaffStream() {
        var file = Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=2"),
                List.of("-c:v", "libx264", "-profile:v", "main", "-preset", "veryfast", "-x264-params",
                        "keyint=30:min-keyint=30:scenecut=0:ref=2:bframes=0:interlaced=1:tff=1", "-pix_fmt",
                        "yuv420p", "-f", "h264"));
        var units = new ArrayList<byte[]>();
        for (var unit : H264TestStreams.accessUnits(H264TestStreams.nalUnits(Fixtures.read(file)))) {
            units.add(H264TestStreams.annexB(unit));
        }
        return units;
    }

    @Nested
    @DisplayName("an MBAFF stream")
    class Mbaff {
        @ParameterizedTest(name = "{0} threads")
        @ValueSource(ints = {1, 4})
        @DisplayName("a lost reference picture damages every later picture until the next IDR")
        void lostReference(int threads) {
            var units = mbaffStream();
            assertEquals(60, units.size());
            var damage = new TreeMap<Integer, Boolean>();
            try (var decoder = new H264Decoder(H264ConformanceTest.track(),
                    H264DecoderOptions.builder().threads(threads).build())) {
                H264Decoder.FrameConsumer sink = (frame, damaged) -> damage.put((int) (frame.pts() / TICKS), damaged);
                for (var i = 0; i < units.size(); i++) {
                    if (i == 10) {
                        continue;
                    }
                    decoder.decode(new Packet(1, units.get(i), (long) i * TICKS, (long) i * TICKS, TICKS, false),
                            sink);
                }
                decoder.flush(sink);
            }
            assertEquals(59, damage.size());
            damage.forEach((slot, damaged) -> assertEquals(slot > 10 && slot < 30, damaged, "access unit " + slot));
        }
    }
}
