package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.raw.AnnexBDemuxer;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.container.SyntheticAvc;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads every JVT conformance bitstream of {@link H264ConformanceTest} through {@link AnnexBDemuxer} and decodes the
 * packets it returns: the output must equal the reference pictures bit for bit, as when the decoder is fed the
 * stream's NAL units one by one, every packet with a presentation time must come out as one frame at that time
 * (and none without), and the durations must tile the timeline. The streams whose picture timing SEI messages repeat
 * fields are timed as ffprobe reports their repeats, the field and reference marking streams come out in the order
 * and with the key frames ffprobe reports, and an x264 stream whose IDR pictures were given
 * {@code no_output_of_prior_pics_flag}, like the synthetic streams whose output depends on the marking of single
 * fields and on the frames inferred for gaps in {@code frame_num}, loses exactly the pictures the decoder drops.
 */
@DisplayName("H.264 Annex B demuxing of the conformance streams")
class H264AnnexBDemuxTest {
    static Stream<H264ConformanceTest.Vector> vectors() {
        return H264ConformanceTest.vectors().stream();
    }

    /** Decodes the packets of a demuxer and returns the presentation times of the frames, in output order. */
    static List<Long> decode(List<Packet> packets, VideoTrack track, H264DecoderOptions options,
                             Consumer<VideoFrame> check) {
        var times = new ArrayList<Long>();
        try (var decoder = new H264Decoder(track, options)) {
            Consumer<VideoFrame> sink = frame -> {
                check.accept(frame);
                times.add(frame.pts());
            };
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return times;
    }

    /** Checks the decoding times and that the output frames are exactly the packets with a presentation time. */
    static void assertTimed(String name, List<Packet> packets, List<Long> frames) {
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            if (i > 0) {
                assertTrue(packet.dts() >= packets.get(i - 1).dts(), name + ": decoding times do not decrease");
            }
            assertTrue(packet.pts() == Packet.NO_TIMESTAMP || packet.pts() >= packet.dts(),
                    name + ": no picture is presented before it is decoded");
        }
        for (var i = 1; i < frames.size(); i++) {
            assertTrue(frames.get(i) > frames.get(i - 1), name + ": frame " + i + " at " + frames.get(i)
                    + " after " + frames.get(i - 1));
        }
        var shown = packets.stream().filter(packet -> packet.pts() != Packet.NO_TIMESTAMP)
                .sorted(Comparator.comparingLong(Packet::pts)).toList();
        assertEquals(shown.stream().map(Packet::pts).toList(), frames,
                name + ": the frames are the packets with a presentation time");
        for (var i = 1; i < shown.size(); i++) {
            assertEquals(shown.get(i - 1).pts() + shown.get(i - 1).duration(), shown.get(i).pts(),
                    name + ": durations tile the timeline at " + i);
        }
    }

    static List<Packet> readAll(AnnexBDemuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("splits access units the decoder decodes bit-exactly, timed in presentation order")
    void conformance(H264ConformanceTest.Vector vector) throws IOException {
        var stream = H264ConformanceTest.bitstream(vector);
        var reference = H264ConformanceTest.reference(vector);
        assertEquals(ContainerFormat.H264, Containers.detect(MediaSource.of(stream)).orElseThrow());
        var count = new int[1];
        List<Packet> packets;
        VideoTrack track;
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
            track = (VideoTrack) demuxer.tracks().getFirst();
            packets = readAll(demuxer);
        }
        var options = H264DecoderOptions.builder().highBitDepth(vector.wide()).build();
        var frames = decode(packets, track, options, frame -> {
            if (vector.wide()) {
                H264ConformanceTest.compareWide(vector, (Yuv16Picture) frame.picture(), reference, count[0]);
            } else {
                H264ConformanceTest.compare(vector, (YuvPicture) frame.picture(), reference, count[0]);
            }
            count[0]++;
        });
        assertEquals(vector.frames(), count[0], "frame count of " + vector.name());
        assertTimed(vector.name(), packets, frames);
    }

    static Stream<H264ConformanceTest.Vector> pictureTimingVectors() {
        // the pic_struct streams: 3:2 pulldown (CVSE2, CVSE3) and frame doubling and tripling (CVSEFDFT3)
        return vectors().filter(vector -> vector.name().startsWith("CVSE"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pictureTimingVectors")
    @DisplayName("times the frames of picture timing SEI messages for the fields ffprobe reports them repeating")
    void pictureTiming(H264ConformanceTest.Vector vector) throws IOException {
        var stream = H264ConformanceTest.bitstream(vector);
        List<Packet> packets;
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
            packets = readAll(demuxer);
        }
        var file = Scratch.share(Scratch.directory("annexb-picstruct").resolve(vector.name() + ".h264"), stream);
        var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                "-f", "h264", "-show_frames", "-show_entries", "frame=repeat_pict", "-of", "csv=p=0",
                file.toString())), StandardCharsets.US_ASCII).lines().map(line -> line.split(",")[0])
                .map(Integer::parseInt).toList();
        var shown = packets.stream().filter(packet -> packet.pts() != Packet.NO_TIMESTAMP)
                .sorted(Comparator.comparingLong(Packet::pts)).toList();
        assertEquals(probe.size(), shown.size(), vector.name() + ": frames ffprobe reports");
        var repeated = 0;
        for (var i = 0; i < shown.size(); i++) {
            assertEquals(2 + probe.get(i), shown.get(i).duration(), vector.name() + ": fields of frame " + i);
            repeated += probe.get(i) > 0 ? 1 : 0;
        }
        assertTrue(repeated > 0, "the stream repeats fields");
    }

    /** Reads the bits of an RBSP. */
    private static final class Bits {
        private final byte[] data;

        int position;

        Bits(byte[] data, int position) {
            this.data = data;
            this.position = position;
        }

        int bit() {
            var value = (data[position >> 3] >> (7 - (position & 7))) & 1;
            position++;
            return value;
        }

        int read(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                value = value << 1 | bit();
            }
            return value;
        }

        int ue() {
            var zeros = 0;
            while (bit() == 0) {
                zeros++;
            }
            return (1 << zeros) - 1 + read(zeros);
        }
    }

    private static byte[] unescape(byte[] nal) {
        var out = new ByteArrayOutputStream();
        var zeros = 0;
        for (var b : nal) {
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    private static byte[] escape(byte[] rbsp) {
        var out = new ByteArrayOutputStream();
        var zeros = 0;
        for (var b : rbsp) {
            if (zeros >= 2 && (b & 0xFF) <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /**
     * Sets {@code no_output_of_prior_pics_flag} in the slices of every IDR picture after the first of an x264 stream
     * (High profile without scaling matrices, frame pictures, {@code pic_order_cnt_type} 0, no redundant pictures).
     */
    static byte[] discardAtIdr(byte[] stream) {
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 3 <= stream.length; i++) {
            if (stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1) {
                starts.add(i + 3);
                i += 2;
            }
        }
        var frameNumBits = new HashMap<Integer, Integer>();
        var pocBits = new HashMap<Integer, Integer>();
        var ppsToSps = new HashMap<Integer, Integer>();
        var out = new ByteArrayOutputStream();
        var idrs = 0;
        for (var k = 0; k < starts.size(); k++) {
            var start = starts.get(k);
            var stop = k + 1 < starts.size() ? starts.get(k + 1) - 3 : stream.length;
            while (stop > start && stream[stop - 1] == 0) {
                stop--;
            }
            var nal = java.util.Arrays.copyOfRange(stream, start, stop);
            var rbsp = unescape(nal);
            var type = nal[0] & 0x1F;
            if (type == 7) {
                var bits = new Bits(rbsp, 8);
                var profile = bits.read(8);
                bits.read(16);
                var id = bits.ue();
                if (profile >= 100) {
                    if (bits.ue() == 3) {
                        bits.bit();
                    }
                    bits.ue();
                    bits.ue();
                    bits.bit();
                    assertEquals(0, bits.bit(), "no scaling matrices");
                }
                frameNumBits.put(id, bits.ue() + 4);
                assertEquals(0, bits.ue(), "pic_order_cnt_type 0");
                pocBits.put(id, bits.ue() + 4);
            } else if (type == 8) {
                var bits = new Bits(rbsp, 8);
                var id = bits.ue();
                ppsToSps.put(id, bits.ue());
            } else if (type == 5) {
                var bits = new Bits(rbsp, 8);
                var firstMb = bits.ue();
                bits.ue();
                var sps = ppsToSps.get(bits.ue());
                if (firstMb == 0) {
                    idrs++;
                }
                bits.read(frameNumBits.get(sps));
                bits.ue();
                bits.read(pocBits.get(sps));
                if (idrs > 1) {
                    rbsp[bits.position >> 3] |= (byte) (0x80 >> (bits.position & 7));
                    nal = escape(rbsp);
                }
            }
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.writeBytes(nal);
        }
        assertTrue(idrs > 2, "the stream has IDR pictures to flag");
        return out.toByteArray();
    }

    @Test
    @DisplayName("gives no presentation time to exactly the pictures an IDR picture with no_output_of_prior_pics_flag "
            + "discards")
    void noOutputOfPriorPics() {
        var stream = Fixtures.read(Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i",
                "testsrc2=size=96x64:rate=30:duration=2"), List.of("-c:v", "libx264", "-preset", "fast", "-bf", "3",
                "-b_strategy", "0", "-x264-params", "b-pyramid=normal:threads=1:keyint=15:min-keyint=15:scenecut=0",
                "-an", "-f", "h264")));
        for (var flagged : new boolean[]{false, true}) {
            var input = flagged ? discardAtIdr(stream) : stream;
            List<Packet> packets;
            VideoTrack track;
            try (var demuxer = new AnnexBDemuxer(MediaSource.of(input))) {
                track = (VideoTrack) demuxer.tracks().getFirst();
                packets = readAll(demuxer);
            }
            assertEquals(60, packets.size());
            var frames = decode(packets, track, H264DecoderOptions.builder().build(), frame -> { });
            assertTimed(flagged ? "flagged" : "original", packets, frames);
            if (flagged) {
                assertTrue(frames.size() < 60, "the flagged IDR pictures discard waiting pictures");
            } else {
                assertEquals(60, frames.size());
            }
        }
    }

    static Stream<String> fieldVectors() {
        return Stream.of("CVFI1_SVA_C", "CVFI1_Sony_D", "CVFI2_SVA_C", "CVFI2_Sony_H", "CAPAMA3_Sand_F",
                "CAPA1_TOSHIBA_B", "CVPA1_TOSHIBA_B", "Sharp_MP_PAFF_1r2", "Sharp_MP_Field_1_B", "Sharp_MP_Field_2_B",
                "Sharp_MP_Field_3_B", "CVMP_MOT_FLD_L30_B", "MR6_BT_B", "MR7_BT_B", "MR8_BT_B", "MR9_BT_B");
    }

    /** Runs ffprobe on a stream and returns one map of the requested entries per frame or packet line. */
    private static List<java.util.Map<String, String>> probe(java.nio.file.Path file, String section,
                                                             String entries) {
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                "-f", "h264", "-show_" + section + "s", "-show_entries", section + "=" + entries, "-of",
                "compact=p=0", file.toString())), StandardCharsets.US_ASCII);
        var lines = new ArrayList<java.util.Map<String, String>>();
        for (var line : output.lines().filter(line -> !line.isBlank()).toList()) {
            var values = new HashMap<String, String>();
            for (var pair : line.split("\\|")) {
                var equals = pair.indexOf('=');
                values.put(pair.substring(0, equals), pair.substring(equals + 1));
            }
            lines.add(values);
        }
        return lines;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldVectors")
    @DisplayName("orders and flags the frames of the field and reference marking streams as ffprobe does")
    void fieldOrderAsFfprobe(String name) throws IOException {
        var vector = vectors().filter(entry -> entry.name().equals(name)).findFirst().orElseThrow();
        var stream = H264ConformanceTest.bitstream(vector);
        List<Packet> packets;
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
            packets = readAll(demuxer);
        }
        var file = Scratch.share(Scratch.directory("annexb-fields").resolve(name + ".h264"), stream);
        var frames = probe(file, "frame", "pkt_pos,key_frame");
        var starts = frames.stream().map(frame -> Long.parseLong(frame.get("pkt_pos"))).sorted().toList();
        assertEquals(packets.size(), starts.size(), name + ": one packet per frame ffprobe outputs");
        assertEquals(starts.size(), new java.util.HashSet<>(starts).size(), name + ": frames start at distinct packets");
        var probed = new ArrayList<Integer>();
        for (var frame : frames) {
            probed.add(starts.indexOf(Long.parseLong(frame.get("pkt_pos"))));
        }
        var order = new ArrayList<Integer>();
        for (var i = 0; i < packets.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingLong(i -> packets.get(i).pts()));
        assertTrue(packets.stream().allMatch(packet -> packet.pts() != Packet.NO_TIMESTAMP),
                name + ": every frame is presented");
        assertEquals(probed, order, name + ": presentation order, as indices in decoding order");
        var keys = new HashMap<Long, Boolean>();
        for (var packet : probe(file, "packet", "pos,flags")) {
            keys.put(Long.parseLong(packet.get("pos")), packet.get("flags").startsWith("K"));
        }
        for (var i = 0; i < packets.size(); i++) {
            assertEquals(keys.get(starts.get(i)), packets.get(i).flags().contains(PacketFlag.KEY),
                    name + ": key frame flag of frame " + i);
        }
    }

    static Stream<SyntheticAvc.Scenario> markingScenarios() {
        return SyntheticAvc.markingScenarios().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("markingScenarios")
    @DisplayName("gives a presentation time to exactly the frames the decoder outputs when single fields leave "
            + "reference and frame_num gaps infer frames")
    void fieldMarkingAndGaps(SyntheticAvc.Scenario scenario) {
        List<Packet> packets;
        VideoTrack track;
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(scenario.stream()))) {
            track = (VideoTrack) demuxer.tracks().getFirst();
            packets = readAll(demuxer);
        }
        var frames = decode(packets, track, H264DecoderOptions.builder().build(), frame -> { });
        assertTimed(scenario.name(), packets, frames);
        assertEquals(scenario.shown().stream().filter(shown -> shown).count(), frames.size(),
                scenario.name() + ": frames the decoder outputs");
    }
}
