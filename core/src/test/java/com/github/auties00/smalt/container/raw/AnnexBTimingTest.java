package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how {@link AnnexBDemuxer} times pictures beyond their order counts: frame rate changes between sequence
 * parameter sets, repeated fields of picture timing SEI messages, HEVC field sequences, the pictures an IDR picture
 * with {@code no_output_of_prior_pics_flag} discards (checked against ffmpeg's decode), and the reorder depth H.264
 * sequence parameter sets imply when their VUI does not state it.
 */
@DisplayName("Annex B timing")
class AnnexBTimingTest {
    private static final List<String> X264 = List.of("-c:v", "libx264", "-preset", "fast", "-bf", "3", "-b_strategy",
            "0", "-x264-params", "b-pyramid=normal:threads=1:keyint=15:min-keyint=15:scenecut=0", "-an",
            "-f", "h264");

    private static byte[] x264(int rate, double seconds) {
        return Fixtures.read(Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=" + rate
                + ":duration=" + seconds), X264));
    }

    private static byte[] x265(int rate, double seconds, String parameters) {
        return Fixtures.read(Fixtures.ffmpeg("h265", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=" + rate
                + ":duration=" + seconds), List.of("-c:v", "libx265", "-x265-params",
                "log-level=none:pools=1:frame-threads=1:" + parameters, "-an", "-f", "hevc")));
    }

    private static List<Packet> readAll(AnnexBDemuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    /** Checks decoding times and returns the packets with a presentation time, in presentation order. */
    private static List<Packet> presented(List<Packet> packets) {
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            assertTrue(packet.pts() == Packet.NO_TIMESTAMP || packet.dts() <= packet.pts(), "dts after pts at " + i);
            if (i > 0) {
                assertTrue(packet.dts() >= packets.get(i - 1).dts(), "decreasing dts at " + i);
            }
        }
        var shown = packets.stream().filter(packet -> packet.pts() != Packet.NO_TIMESTAMP)
                .sorted(Comparator.comparingLong(Packet::pts)).toList();
        for (var i = 1; i < shown.size(); i++) {
            assertEquals(shown.get(i - 1).pts() + shown.get(i - 1).duration(), shown.get(i).pts(),
                    "each picture starts when the previous one ends, at " + i);
        }
        return shown;
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("times each sequence at its own frame rate, or every picture at the rate the options set")
    void rateChange() {
        var stream = concat(x264(25, 1), x264(50, 1));
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(new Rational(25, 1), track.frameRate().orElseThrow());
            assertEquals(new Rational(1, 50), track.timeBase());
            var shown = presented(readAll(demuxer));
            assertEquals(75, shown.size());
            for (var i = 0; i < shown.size(); i++) {
                assertEquals(i < 25 ? 2 * i : 50 + (i - 25), shown.get(i).pts(), "presentation time of " + i);
                assertEquals(i < 25 ? 2 : 1, shown.get(i).duration(), "duration of " + i);
            }
            demuxer.seek(1_500_000);
            var after = readAll(demuxer);
            // the 50 fps sequence starts at tick 50 with an IDR picture every 15 frames
            assertEquals(65, after.getFirst().pts(), "the last key frame at or before tick 75");
            assertEquals(2_000_000, demuxer.durationMicros());
        }
        var fixed = ElementaryStreamOptions.builder().frameRate(new Rational(25, 1)).build();
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream), fixed)) {
            var shown = presented(readAll(demuxer));
            assertEquals(148, shown.getLast().pts());
            assertTrue(shown.stream().allMatch(packet -> packet.duration() == 2));
        }
    }

    @Test
    @DisplayName("converts a rate the time base cannot hold exactly without accumulating rounding errors")
    void inexactRate() {
        // 25 fps first, then 30 fps: a 30 fps frame is 5/3 ticks of 1/50 second
        var stream = concat(x264(25, 0.4), x264(30, 1));
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
            var shown = presented(readAll(demuxer));
            assertEquals(40, shown.size());
            for (var i = 10; i < 40; i++) {
                // rounding happens in decoding order, so a picture is off by less than a tick per reordered picture
                var exact = 20 + (i - 10) * 5.0 / 3;
                assertTrue(Math.abs(shown.get(i).pts() - exact) < 4, "picture " + i + " at " + shown.get(i).pts()
                        + " for " + exact);
            }
            assertEquals(70, shown.getLast().pts() + shown.getLast().duration(), "no error accumulates");
        }
    }

    @ParameterizedTest(name = "pic_struct {0}")
    @CsvSource({"7, 4", "5, 3", "0, 2"})
    @DisplayName("gives HEVC frames the fields their picture timing pic_struct displays")
    void picStruct(int picStruct, int ticks) {
        var stream = x265(25, 1, "bframes=2:hrd=1:vbv-bufsize=2000:vbv-maxrate=1000:pic-struct=" + picStruct);
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
            assertEquals(new Rational(1, 50), demuxer.tracks().getFirst().timeBase());
            var shown = presented(readAll(demuxer));
            assertEquals(25, shown.size());
            for (var i = 0; i < shown.size(); i++) {
                assertEquals((long) ticks * i, shown.get(i).pts());
            }
        }
    }

    @Test
    @DisplayName("times an HEVC field sequence one field per picture at half the picture rate")
    void fieldSequence() {
        var stream = x265(25, 1, "bframes=2:interlace=tff");
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(new Rational(25, 2), track.frameRate().orElseThrow());
            assertEquals(new Rational(1, 25), track.timeBase());
            var shown = presented(readAll(demuxer));
            assertEquals(25, shown.size());
            for (var i = 0; i < shown.size(); i++) {
                assertEquals(i, shown.get(i).pts());
                assertEquals(1, shown.get(i).duration());
            }
        }
    }

    /** Sets no_output_of_prior_pics_flag on every HEVC IDR picture after the first. */
    static byte[] discardAtIdr(byte[] stream) {
        var out = stream.clone();
        var idrs = 0;
        for (var i = 0; i + 5 < out.length; i++) {
            if (out[i] == 0 && out[i + 1] == 0 && out[i + 2] == 1) {
                var type = (out[i + 3] >> 1) & 0x3F;
                // the flag follows first_slice_segment_in_pic_flag in the first byte after the NAL unit header;
                // setting a bit never creates or removes an emulation prevention byte here
                if ((type == 19 || type == 20) && (out[i + 5] & 0x80) != 0 && idrs++ > 0) {
                    out[i + 5] |= 0x40;
                }
            }
        }
        assertTrue(idrs > 2, "the stream has IDR pictures to flag");
        return out;
    }

    @Test
    @DisplayName("gives no presentation time to the HEVC pictures an IDR picture discards, as ffmpeg drops them")
    void noOutputOfPriorPics() {
        var stream = x265(30, 2, "keyint=15:min-keyint=15:scenecut=0:bframes=3:open-gop=0");
        var flagged = discardAtIdr(stream);
        var file = Scratch.share(Scratch.directory("annexb-timing").resolve("no-output.h265"), flagged);
        var frameSize = 96 * 64 * 3 / 2;
        var decoded = Oracle.decodeVideo(file, List.of("-f", "hevc"), "yuv420p", 0).length / frameSize;
        assertTrue(decoded < 60, "ffmpeg discards the waiting pictures");
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(flagged))) {
            var packets = readAll(demuxer);
            assertEquals(60, packets.size());
            var shown = presented(packets);
            assertEquals(decoded, shown.size(), "pictures with a presentation time");
            assertEquals(0, shown.getFirst().pts());
            assertTrue(packets.stream().filter(packet -> packet.pts() == Packet.NO_TIMESTAMP)
                    .allMatch(packet -> packet.duration() == 0));
        }
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
            assertEquals(60, presented(readAll(demuxer)).size(), "without the flag every picture is output");
        }
    }

    /** Writes the bits of an H.264 sequence parameter set. */
    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private int current;

        private int count;

        Bits bits(long value, int length) {
            for (var i = length - 1; i >= 0; i--) {
                current = current << 1 | (int) (value >>> i & 1);
                if (++count == 8) {
                    out.write(current);
                    current = 0;
                    count = 0;
                }
            }
            return this;
        }

        Bits ue(int value) {
            var length = 32 - Integer.numberOfLeadingZeros(value + 1);
            return bits(0, length - 1).bits(value + 1, length);
        }

        byte[] nal() {
            bits(1, 1);
            while (count != 0) {
                bits(0, 1);
            }
            var rbsp = out.toByteArray();
            var nal = new ByteArrayOutputStream();
            nal.write(0x67);
            var zeros = 0;
            for (var b : rbsp) {
                if (zeros >= 2 && (b & 0xFF) <= 3) {
                    nal.write(3);
                    zeros = 0;
                }
                nal.write(b);
                zeros = b == 0 ? zeros + 1 : 0;
            }
            return nal.toByteArray();
        }
    }

    /**
     * Writes a Main or Baseline profile SPS of 22x18 macroblocks (CIF) with one reference frame.
     *
     * @param vui the VUI tail after {@code vui_parameters_present_flag}, or {@code null} for none
     */
    private static byte[] sps(int profile, int constraints, int level, int pocType, int refFrames, Bits vui) {
        var bits = new Bits().bits(profile, 8).bits(constraints, 8).bits(level, 8).ue(0).ue(0).ue(pocType);
        if (pocType == 0) {
            bits.ue(4);
        }
        bits.ue(refFrames).bits(0, 1).ue(21).ue(17).bits(1, 1).bits(1, 1).bits(0, 1);
        if (vui == null) {
            bits.bits(0, 1);
        } else {
            bits.bits(1, 1);
            var tail = vui.out.toByteArray();
            for (var b : tail) {
                bits.bits(b & 0xFF, 8);
            }
            bits.bits(vui.current, vui.count);
        }
        return bits.nal();
    }

    @Test
    @DisplayName("infer an H.264 reorder depth from the level and picture size when the VUI does not state it")
    void inferredReorder() {
        // CIF (396 macroblocks): MaxDpbMbs 8100 at level 3 gives 20 frames, bounded to 16
        var level3 = sps(77, 0, 30, 0, 1, null);
        assertEquals(16, AvcSyntax.sps(level3, 0, level3.length).reorderFrames());
        // level 2.1: 4752 / 396 = 12
        var level21 = sps(77, 0, 21, 0, 1, null);
        assertEquals(12, AvcSyntax.sps(level21, 0, level21.length).reorderFrames());
        // level 1b (level_idc 11 with constraint_set3_flag in Baseline): 396 / 396 = 1, at least 4 reference frames
        var oneB = sps(66, 0x10, 11, 0, 4, null);
        assertEquals(4, AvcSyntax.sps(oneB, 0, oneB.length).reorderFrames());
        // output order is decoding order with pic_order_cnt_type 2
        var pocType2 = sps(77, 0, 30, 2, 1, null);
        assertEquals(0, AvcSyntax.sps(pocType2, 0, pocType2.length).reorderFrames());
        // a VUI with timing information, pic_struct_present_flag and bitstream_restriction stating 1 frame
        var vui = new Bits().bits(0, 4).bits(1, 1).bits(1001, 32).bits(60000, 32).bits(1, 1).bits(0, 2)
                .bits(1, 1).bits(1, 1).bits(1, 1).ue(0).ue(0).ue(16).ue(16).ue(1).ue(3);
        var restricted = sps(77, 0, 30, 0, 1, vui);
        var parsed = AvcSyntax.sps(restricted, 0, restricted.length);
        assertEquals(1, parsed.reorderFrames());
        assertEquals(new Rational(30000, 1001), parsed.frameRate());
        assertTrue(parsed.picStructPresent());
        assertEquals(1001, parsed.timingUnits());
        assertEquals(5, AvcSyntax.picStruct(new byte[]{0x50}, parsed));
        assertEquals(-1, AvcSyntax.picStruct(new byte[0], parsed));
        var plain = AvcSyntax.sps(level21, 0, level21.length);
        assertEquals(-1, AvcSyntax.picStruct(new byte[]{0x50}, plain));
        assertNull(plain.frameRate());
    }
}
