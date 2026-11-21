package com.github.auties00.smalt.video.hevc.encoder;

import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import com.github.auties00.smalt.video.hevc.internal.HevcNal;
import com.github.auties00.smalt.video.hevc.internal.HevcPps;
import com.github.auties00.smalt.video.hevc.internal.HevcSliceHeader;
import com.github.auties00.smalt.video.hevc.internal.HevcSps;
import com.github.auties00.smalt.video.hevc.internal.HevcVps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the encoder's streams against the bitstream conformance constraints of ITU-T H.265 C.4 on the decoded
 * picture buffer, which decoders rely on to output pictures in order without waiting for the end of the stream:
 * the stream is parsed with the decoder's parameter set and slice header parsers, the picture order counts are
 * derived as 8.3.1 specifies, and the output order operation of the decoded picture buffer (C.5.2) runs with the
 * sizes the sequence parameter set announces. Every reference of a reference picture set must be in the buffer,
 * the buffer must never hold more pictures than {@code sps_max_dec_pic_buffering_minus1 + 1}, no picture may be
 * preceded in decoding order by more than {@code sps_max_num_reorder_pics} pictures that follow it in output
 * order, and the bumping process must output every picture in increasing picture order count. The level is
 * forced low in some cases so that its {@code maxDpbSize} of A.4.2 caps the buffer. The entry points of the
 * wavefront substreams, which sequential decoders ignore, are checked by decoders that start every substream from
 * its entry point: Smalt's decoder on several threads and ffmpeg's in slice threading mode.
 */
@DisplayName("HEVC encoder bitstream conformance")
class HevcEncoderConformanceTest {
    static Stream<Arguments> cases() {
        var arguments = new ArrayList<Arguments>();
        for (var speed : HevcEncoderSpeed.values()) {
            for (var bFrames : new int[]{0, 1, 2, 3, 5, 7}) {
                // 192x176 fills more than three quarters of level 1, whose maxDpbSize is then 6
                arguments.add(Arguments.of(speed, bFrames, 192, 176, HevcLevel.LEVEL_1));
                arguments.add(Arguments.of(speed, bFrames, 64, 48, null));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} b={1} {2}x{3} {4}")
    @MethodSource("cases")
    @DisplayName("every stream fits the decoded picture buffer and reorder depth its sequence parameter set announces")
    void decodedPictureBuffer(HevcEncoderSpeed speed, int bFrames, int width, int height, HevcLevel level) {
        var builder = HevcEncoderOptions.builder().rateControl(HevcRateControl.CONSTANT_QUALITY).quality(36)
                .speed(speed).bFrames(bFrames).keyframeInterval(23).frameRate(new Rational(15, 1))
                .pictureHash(HevcPictureHash.NONE);
        if (level != null) {
            builder.level(level);
        }
        var options = builder.build();
        var frames = 40;
        var pictures = HevcEncoderHarness.sequence(width, height, frames, 31);
        var packets = new ArrayList<Packet>();
        byte[] config;
        try (var encoder = new HevcEncoder(width, height, options)) {
            config = encoder.codecConfig().orElseThrow();
            for (var i = 0; i < frames; i++) {
                if (i == 9) {
                    encoder.requestKeyframe();
                }
                encoder.encode(new VideoFrame(pictures.get(i), i * 6000L, 6000, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        assertEquals(frames, packets.size());
        var stream = new Checker();
        stream.parameterSets(config);
        for (var packet : packets) {
            stream.accessUnit(packet);
        }
        var outputs = stream.finish();
        var expected = new long[frames];
        for (var i = 0; i < frames; i++) {
            expected[i] = i * 6000L;
        }
        assertEquals(Arrays.toString(expected), Arrays.toString(outputs), "bumping output order");
        if (level != null) {
            assertTrue(stream.sps.maxDecPicBuffering[0] <= 6, "maxDpbSize of the level: "
                    + stream.sps.maxDecPicBuffering[0]);
        }
    }

    static Stream<Arguments> wavefronts() {
        return Stream.of(
                Arguments.of("noise", 256, 256, 8, 0, HevcEncoderSpeed.FASTEST, 0),
                Arguments.of("noise", 200, 136, 10, 0, HevcEncoderSpeed.FAST, 1),
                Arguments.of("flat", 2048, 128, 8, 30, HevcEncoderSpeed.FAST, 1),
                Arguments.of("flat", 1024, 192, 10, 22, HevcEncoderSpeed.BALANCED, 0),
                Arguments.of("sequence", 320, 200, 8, 30, HevcEncoderSpeed.SLOW, 3),
                Arguments.of("sequence", 72, 300, 8, 24, HevcEncoderSpeed.BALANCED, 1),
                Arguments.of("sequence", 352, 288, 10, 32, HevcEncoderSpeed.FAST, 3));
    }

    @ParameterizedTest(name = "{0} {1}x{2} {3}-bit q={4} {5} b={6}")
    @MethodSource("wavefronts")
    @DisplayName("the entry points locate every wavefront substream: decoders that start each row from its entry "
            + "point verify every picture hash and agree with the sequential decode")
    void entryPoints(String content, int width, int height, int depth, int qp, HevcEncoderSpeed speed, int bFrames) {
        var frames = 3;
        List<com.github.auties00.smalt.image.Picture> pictures;
        if (!content.equals("sequence")) {
            // noise at quantizer 0 makes long substreams of nearly random bins and large entry point offsets; a flat
            // picture makes runs of zero bits (bypass-coded zero MPM indices between most probable context-coded
            // bins), so its substreams hold emulation prevention bytes, which the entry points count
            var random = new java.util.Random(width * 7L + height);
            var max = (1 << depth) - 1;
            pictures = new ArrayList<>();
            for (var f = 0; f < frames; f++) {
                var luma = new int[width * height];
                var chroma = new int[2 * (width / 2) * (height / 2)];
                for (var i = 0; i < luma.length; i++) {
                    luma[i] = content.equals("noise") ? random.nextInt(max + 1) : (max + 1) / 2;
                }
                for (var i = 0; i < chroma.length; i++) {
                    chroma[i] = content.equals("noise") ? random.nextInt(max + 1) : (max + 1) / 2;
                }
                pictures.add(HevcEncoderHarness.planar(width, height, depth, luma, chroma));
            }
        } else {
            pictures = HevcEncoderHarness.sequence(width, height, frames, 37, depth);
        }
        var builder = HevcEncoderOptions.builder().rateControl(HevcRateControl.CONSTANT_QUALITY).quality(qp)
                .speed(speed).bFrames(bFrames).threads(3).pictureHash(HevcPictureHash.MD5)
                .packetFormat(HevcPacketFormat.ANNEX_B);
        if (depth == 10) {
            builder.profile(HevcProfile.MAIN_10).bitDepth(10);
        }
        var encoded = HevcEncoderHarness.encode(width, height, builder.build(), pictures);
        if (content.equals("flat")) {
            var escapes = 0;
            for (var packet : encoded.packets()) {
                var data = packet.data();
                // the NAL units of an Annex B access unit start after 00 00 01, which never occurs inside one
                var slice = false;
                for (var i = 0; i + 3 < data.length; i++) {
                    if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                        slice = ((data[i + 3] >> 1) & 0x3F) <= HevcNal.CRA_NUT;
                        i += 2;
                    } else if (slice && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 3) {
                        escapes++;
                    }
                }
            }
            assertTrue(escapes > 0, "the slices hold emulation prevention bytes for the entry points to count");
        }
        var sequential = HevcEncoderHarness.raw(HevcEncoderHarness.decode(encoded, 1));
        var parallel = HevcEncoderHarness.raw(HevcEncoderHarness.decode(encoded, 4));
        assertTrue(Arrays.equals(sequential, parallel), "Smalt's parallel decode differs");
        var name = "wpp-" + content + "-" + width + "x" + height + "-" + depth;
        var format = depth == 8 ? "yuv420p" : "yuv420p10le";
        var reference = HevcEncoderHarness.ffmpegWavefront(name, encoded.annexB(), format);
        assertTrue(Arrays.equals(sequential, reference), "ffmpeg's slice-threaded decode differs");
    }

    /** Follows a length-prefixed stream through the output order operation of the decoded picture buffer. */
    private static final class Checker {
        private final HevcSps[] spsTable = new HevcSps[16];
        private final HevcPps[] ppsTable = new HevcPps[64];
        private final List<Entry> dpb = new ArrayList<>();
        private final List<Long> output = new ArrayList<>();
        private final List<Entry> decoded = new ArrayList<>();
        HevcSps sps;
        private int prevTid0Poc;
        private int cvs = -1;
        private int lastOutputPoc;
        private int outputCvs = -1;

        /** A picture of the decoded picture buffer. */
        private static final class Entry {
            int cvs;
            int poc;
            long pts;
            boolean reference = true;
            boolean neededForOutput = true;
        }

        void parameterSets(byte[] config) {
            var arrays = config[22] & 0xFF;
            var position = 23;
            for (var array = 0; array < arrays; array++) {
                var count = ((config[position + 1] & 0xFF) << 8) | (config[position + 2] & 0xFF);
                position += 3;
                for (var i = 0; i < count; i++) {
                    var length = ((config[position] & 0xFF) << 8) | (config[position + 1] & 0xFF);
                    position += 2;
                    nal(Arrays.copyOfRange(config, position, position + length), Long.MIN_VALUE);
                    position += length;
                }
            }
        }

        void accessUnit(Packet packet) {
            var data = packet.data();
            var offset = 0;
            var slices = 0;
            while (offset < data.length) {
                var length = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                        | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
                if (nal(Arrays.copyOfRange(data, offset + 4, offset + 4 + length), packet.pts())) {
                    slices++;
                }
                offset += 4 + length;
            }
            assertEquals(1, slices, "slices of the access unit at " + packet.pts());
        }

        private static H264BitReader reader(byte[] unit) {
            var rbsp = NalUnits.unescape(unit, 2, unit.length - 2);
            var reader = new H264BitReader();
            reader.init(Arrays.copyOf(rbsp, rbsp.length + H264BitReader.PADDING), 0, rbsp.length);
            return reader;
        }

        /** Parses one NAL unit, returning whether it is a slice. */
        private boolean nal(byte[] unit, long pts) {
            var type = (unit[0] >> 1) & 0x3F;
            var temporalId = (unit[1] & 7) - 1;
            assertEquals(0, temporalId, "TemporalId");
            switch (type) {
                case HevcNal.VPS -> HevcVps.parse(reader(unit));
                case HevcNal.SPS -> {
                    var parsed = HevcSps.parse(reader(unit));
                    spsTable[parsed.id] = parsed;
                    sps = parsed;
                }
                case HevcNal.PPS -> {
                    var parsed = HevcPps.parse(reader(unit));
                    ppsTable[parsed.id] = parsed;
                }
                default -> {
                    if (type > HevcNal.CRA_NUT) {
                        return false;
                    }
                    picture(HevcSliceHeader.parse(reader(unit), type, 0, ppsTable, spsTable, null), type, pts);
                    return true;
                }
            }
            return false;
        }

        private void picture(HevcSliceHeader header, int type, long pts) {
            var maxDpb = sps.maxDecPicBuffering[0];
            var maxReorder = sps.maxNumReorder[0];
            var idr = type == HevcNal.IDR_W_RADL || type == HevcNal.IDR_N_LP;
            assertTrue(type == HevcNal.IDR_W_RADL || type == HevcNal.TRAIL_R || type == HevcNal.TRAIL_N,
                    "NAL unit type " + type);
            int poc;
            if (idr) {
                poc = 0;
                cvs++;
                // C.5.2.2: an IRAP picture with NoRaslOutputFlag 1 and no_output_of_prior_pics_flag 0 empties the
                // buffer by bumping
                while (dpb.stream().anyMatch(e -> e.neededForOutput)) {
                    bump();
                }
                dpb.clear();
            } else {
                var maxLsb = 1 << sps.log2MaxPocLsb;
                var prevLsb = Math.floorMod(prevTid0Poc, maxLsb);
                var prevMsb = prevTid0Poc - prevLsb;
                var lsb = header.pocLsb;
                int msb;
                if (lsb < prevLsb && prevLsb - lsb >= maxLsb / 2) {
                    msb = prevMsb + maxLsb;
                } else if (lsb > prevLsb && lsb - prevLsb > maxLsb / 2) {
                    msb = prevMsb - maxLsb;
                } else {
                    msb = prevMsb;
                }
                poc = msb + lsb;
                var rps = header.shortTermRps;
                assertTrue(rps.numNegative <= maxDpb - 1 && rps.numPositive <= maxDpb - 1 - rps.numNegative,
                        "7.4.8: reference picture set of " + rps.numDeltaPocs() + " pictures with a buffer of "
                                + maxDpb);
                var kept = new ArrayList<Entry>();
                for (var i = 0; i < rps.numNegative + rps.numPositive; i++) {
                    var delta = i < rps.numNegative ? rps.deltaS0[i] : rps.deltaS1[i - rps.numNegative];
                    var target = poc + delta;
                    var found = dpb.stream().filter(e -> e.reference && e.poc == target).findFirst()
                            .orElseThrow(() -> new AssertionError("picture " + poc + " references the missing "
                                    + "picture " + target));
                    kept.add(found);
                }
                for (var e : dpb) {
                    if (!kept.contains(e)) {
                        e.reference = false;
                    }
                }
                dpb.removeIf(e -> !e.reference && !e.neededForOutput);
                while (count() > maxReorder || dpb.size() >= maxDpb) {
                    if (count() == 0) {
                        fail("picture " + poc + ": the buffer of " + maxDpb + " holds " + dpb.size()
                                + " reference pictures");
                    }
                    bump();
                }
            }
            if (type == HevcNal.IDR_W_RADL || type == HevcNal.TRAIL_R) {
                prevTid0Poc = poc;
            }
            var current = new Entry();
            current.cvs = cvs;
            current.poc = poc;
            current.pts = pts;
            for (var earlier : decoded) {
                assertTrue(earlier.cvs != cvs || earlier.poc != poc, "picture order count " + poc + " repeated");
            }
            var reordered = decoded.stream().filter(e -> e.cvs == cvs && e.poc > poc).count();
            assertTrue(reordered <= maxReorder, "C.4: picture " + poc + " follows " + reordered
                    + " pictures in output order that precede it in decoding order, more than " + maxReorder);
            decoded.add(current);
            dpb.add(current);
            assertTrue(dpb.size() <= maxDpb, "C.4: " + dpb.size() + " pictures in a buffer of " + maxDpb);
            // C.5.2.3: additional bumping
            while (count() > maxReorder) {
                bump();
            }
        }

        private long count() {
            return dpb.stream().filter(e -> e.neededForOutput).count();
        }

        private void bump() {
            var next = dpb.stream().filter(e -> e.neededForOutput).min((a, b) -> Integer.compare(a.poc, b.poc))
                    .orElseThrow();
            if (next.cvs == outputCvs) {
                assertTrue(next.poc > lastOutputPoc, "picture " + next.poc + " output after picture "
                        + lastOutputPoc);
            }
            next.neededForOutput = false;
            lastOutputPoc = next.poc;
            outputCvs = next.cvs;
            output.add(next.pts);
            if (!next.reference) {
                dpb.remove(next);
            }
        }

        long[] finish() {
            while (count() > 0) {
                bump();
            }
            return output.stream().mapToLong(Long::longValue).toArray();
        }
    }
}
