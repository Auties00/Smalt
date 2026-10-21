package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Encodes synthetic video with libx264 through ffmpeg across profiles, entropy coders, B-frame
 * structures, weighted prediction, direct modes, the 8x8 transform, custom quantization
 * matrices, multiple slices, intra refresh, open GOPs, reference counts, quantizers and picture
 * sizes, then requires every frame decoded by {@link H264Decoder} to equal ffmpeg's decode
 * byte for byte. The streams are fed as Annex B access units and, repackaged, as
 * length-prefixed samples with an {@code avcC} record.
 */
@DisplayName("H.264 differential decoding against ffmpeg")
class H264DifferentialTest {
    record Case(String name, String size, int frames, String pixelFormat, List<String> options) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("baseline cavlc", "352x288", 30, "yuv420p", List.of("-profile:v", "baseline", "-x264-params", "keyint=15")),
                new Case("baseline 4x4 partitions esa", "352x288", 20, "yuv420p", List.of("-profile:v", "baseline", "-x264-params", "partitions=all:subme=10:me=esa:merange=64")),
                new Case("baseline 16 references", "176x144", 30, "yuv420p", List.of("-profile:v", "baseline", "-refs", "16")),
                new Case("main cabac 3 bframes pyramid normal", "352x288", 40, "yuv420p", List.of("-profile:v", "main", "-bf", "3", "-x264-params", "b-pyramid=normal:keyint=30")),
                new Case("main cabac 1 bframe no pyramid", "176x144", 30, "yuv420p", List.of("-profile:v", "main", "-bf", "1", "-x264-params", "b-pyramid=none")),
                new Case("main cavlc bframes", "176x144", 30, "yuv420p", List.of("-profile:v", "main", "-bf", "2", "-x264-params", "cabac=0")),
                new Case("high 16 bframes strict pyramid weightp2 temporal", "352x288", 60, "yuv420p", List.of("-profile:v", "high", "-bf", "16", "-refs", "16", "-x264-params",
                        "b-pyramid=strict:weightp=2:weightb=1:direct=temporal:8x8dct=1:cqm=jvt:partitions=all:me=umh:subme=9:trellis=2")),
                new Case("high spatial direct weightp1", "352x288", 50, "yuv420p", List.of("-profile:v", "high", "-bf", "5", "-refs", "4", "-x264-params", "b-pyramid=normal:weightp=1:direct=spatial:partitions=all")),
                new Case("high auto direct weightb0", "176x144", 40, "yuv420p", List.of("-profile:v", "high", "-bf", "4", "-x264-params", "direct=auto:weightb=0")),
                new Case("high cavlc 8x8", "352x288", 30, "yuv420p", List.of("-profile:v", "high", "-bf", "3", "-x264-params", "cabac=0:8x8dct=1:partitions=all")),
                new Case("high no 8x8", "176x144", 30, "yuv420p", List.of("-profile:v", "high", "-bf", "2", "-x264-params", "8x8dct=0")),
                new Case("four slices", "352x288", 30, "yuv420p", List.of("-profile:v", "high", "-bf", "2", "-x264-params", "slices=4")),
                new Case("slice max size", "352x288", 20, "yuv420p", List.of("-profile:v", "main", "-x264-params", "slice-max-size=600")),
                new Case("intra refresh", "352x288", 60, "yuv420p", List.of("-profile:v", "main", "-bf", "0", "-x264-params", "intra-refresh=1:keyint=30")),
                new Case("open gop", "352x288", 60, "yuv420p", List.of("-profile:v", "high", "-bf", "3", "-x264-params", "open-gop=1:keyint=20")),
                new Case("16x16 picture", "16x16", 20, "yuv420p", List.of("-profile:v", "high", "-bf", "2")),
                new Case("18x18 cropped picture", "18x18", 20, "yuv420p", List.of("-profile:v", "high", "-bf", "2")),
                new Case("34x50 picture", "34x50", 20, "yuv420p", List.of("-profile:v", "main", "-bf", "2")),
                new Case("1920x1080 cropped from 1088", "1920x1080", 6, "yuv420p", List.of("-profile:v", "high", "-bf", "2")),
                new Case("qp 1", "176x144", 10, "yuv420p", List.of("-profile:v", "high", "-qp", "1")),
                new Case("qp 51", "176x144", 10, "yuv420p", List.of("-profile:v", "high", "-qp", "51")),
                new Case("tiny qp 51", "32x32", 10, "yuv420p", List.of("-profile:v", "high", "-bf", "2", "-qp", "51")),
                new Case("qp 20 cavlc", "176x144", 20, "yuv420p", List.of("-profile:v", "main", "-qp", "20", "-x264-params", "cabac=0")),
                new Case("crf 5", "352x288", 15, "yuv420p", List.of("-profile:v", "high", "-crf", "5", "-bf", "2")),
                new Case("crf 40", "352x288", 30, "yuv420p", List.of("-profile:v", "high", "-crf", "40", "-bf", "4")),
                new Case("monochrome", "176x144", 20, "gray", List.of("-profile:v", "high", "-bf", "2")),
                new Case("fake interlaced frame pictures", "352x288", 20, "yuv420p", List.of("-profile:v", "high", "-bf", "2", "-x264-params", "fake-interlaced=1:pic-struct=1")),
                new Case("interlaced mbaff tff cabac bframes", "352x288", 30, "yuv420p", List.of("-profile:v", "high", "-bf", "3", "-x264-params", "interlaced=1:tff=1:b-pyramid=normal")),
                new Case("interlaced mbaff bff cavlc", "352x288", 30, "yuv420p", List.of("-profile:v", "main", "-bf", "2", "-x264-params", "interlaced=1:bff=1:cabac=0")),
                new Case("interlaced ildct ilme flags", "352x288", 20, "yuv420p", List.of("-profile:v", "high", "-bf", "2", "-flags", "+ildct+ilme")),
                new Case("interlaced temporal direct weightp", "352x288", 30, "yuv420p", List.of("-profile:v", "high", "-bf", "3", "-refs", "4", "-x264-params", "interlaced=1:direct=temporal:weightp=2:weightb=1")),
                new Case("interlaced spatial direct all partitions", "176x144", 30, "yuv420p", List.of("-profile:v", "high", "-bf", "4", "-refs", "3", "-x264-params", "interlaced=1:direct=spatial:partitions=all:8x8dct=1:me=umh:subme=9")),
                new Case("interlaced implicit weights", "176x144", 30, "yuv420p", List.of("-profile:v", "main", "-bf", "3", "-x264-params", "interlaced=1:weightb=1:weightp=0")),
                new Case("interlaced constrained intra slices", "352x288", 20, "yuv420p", List.of("-profile:v", "main", "-bf", "2", "-x264-params", "interlaced=1:constrained-intra=1:slices=4")),
                new Case("interlaced deblocking strong", "176x144", 20, "yuv420p", List.of("-profile:v", "high", "-bf", "2", "-x264-params", "interlaced=1:deblock=6,6")),
                new Case("interlaced 1920x1080 cropped", "1920x1080", 6, "yuv420p", List.of("-profile:v", "high", "-bf", "2", "-x264-params", "interlaced=1")),
                new Case("interlaced monochrome", "176x144", 20, "gray", List.of("-profile:v", "high", "-bf", "2", "-x264-params", "interlaced=1")),
                new Case("interlaced qp 1", "176x144", 10, "yuv420p", List.of("-profile:v", "high", "-qp", "1", "-x264-params", "interlaced=1")),
                new Case("fast decode tune", "352x288", 30, "yuv420p", List.of("-profile:v", "high", "-tune", "fastdecode")),
                new Case("zero latency tune", "352x288", 30, "yuv420p", List.of("-profile:v", "high", "-tune", "zerolatency")),
                new Case("deblocking disabled", "176x144", 20, "yuv420p", List.of("-profile:v", "main", "-x264-params", "no-deblock=1")),
                new Case("strong deblocking", "176x144", 20, "yuv420p", List.of("-profile:v", "main", "-x264-params", "deblock=6,6")),
                new Case("weak deblocking", "176x144", 20, "yuv420p", List.of("-profile:v", "main", "-x264-params", "deblock=-6,-6")),
                new Case("weightp 2 multiple references", "352x288", 40, "yuv420p", List.of("-profile:v", "main", "-bf", "0", "-refs", "3", "-x264-params", "weightp=2")),
                new Case("full range bt709", "176x144", 10, "yuvj420p", List.of("-profile:v", "high", "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709"))
        );
    }

    static Path encode(Case c) {
        var options = new ArrayList<String>(List.of("-filter_complex",
                "[0][1]blend=all_mode=average,format=" + c.pixelFormat(), "-frames:v", Integer.toString(c.frames()),
                "-c:v", "libx264"));
        options.addAll(c.options());
        options.addAll(List.of("-f", "h264"));
        return Fixtures.ffmpeg("h264", List.of(
                "-f", "lavfi", "-i", "testsrc2=size=" + c.size() + ":rate=25",
                "-f", "lavfi", "-i", "mandelbrot=size=" + c.size() + ":rate=25"), options);
    }

    static byte[] reference(Path stream, String pixelFormat) {
        // the decoder's own planar 4:2:0 format, so no range conversion applies to a full-range stream (ffmpeg
        // releases before 7 decode it as yuvj420p)
        var arguments = new ArrayList<>(List.of("-v", "error", "-i", stream.toString(), "-fps_mode", "passthrough",
                "-f", "rawvideo"));
        if (pixelFormat.equals("gray")) {
            arguments.addAll(List.of("-pix_fmt", "gray"));
        }
        arguments.add("-");
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
    }

    static void assertMatches(Case c, byte[] reference, List<H264TestStreams.Decoded> frames) {
        assertTrue(!frames.isEmpty(), "no frames decoded");
        var first = frames.getFirst();
        var lumaSize = first.width() * first.height();
        var gray = c.pixelFormat().equals("gray");
        var frameSize = gray ? lumaSize : first.samples().length;
        assertEquals(reference.length / frameSize, frames.size(), "frame count");
        for (var i = 0; i < frames.size(); i++) {
            var expected = Arrays.copyOfRange(reference, i * frameSize, (i + 1) * frameSize);
            var actual = gray ? Arrays.copyOf(frames.get(i).samples(), lumaSize) : frames.get(i).samples();
            assertArrayEquals(expected, actual, c.name() + " frame " + i);
        }
    }

    static List<Packet> annexBPackets(byte[] stream) {
        var units = H264TestStreams.accessUnits(H264TestStreams.nalUnits(stream));
        var payloads = new ArrayList<byte[]>();
        for (var unit : units) {
            payloads.add(H264TestStreams.annexB(unit));
        }
        return H264TestStreams.packets(payloads);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("Annex B access units decode exactly like ffmpeg")
    void annexB(Case c) {
        var stream = encode(c);
        var reference = reference(stream, c.pixelFormat());
        var frames = H264TestStreams.decode(H264TestStreams.track(Optional.empty(), Optional.empty()),
                annexBPackets(Fixtures.read(stream)), 1);
        assertMatches(c, reference, frames);
        if (c.pixelFormat().equals("gray")) {
            assertEquals(ChromaFormat.MONOCHROME, frames.getFirst().format());
        }
    }

    static Stream<Arguments> packaging() {
        var fourByte = cases().filter(c -> c.frames() <= 40 && !c.size().equals("1920x1080")).map(c -> Arguments.of(c, 4));
        var twoByte = cases().filter(c -> c.name().startsWith("baseline") || c.name().startsWith("main")).map(c -> Arguments.of(c, 2));
        var oneByte = cases().filter(c -> c.name().equals("tiny qp 51") || c.name().equals("16x16 picture")).map(c -> Arguments.of(c, 1));
        return Stream.of(fourByte, twoByte, oneByte).flatMap(stream -> stream);
    }

    @ParameterizedTest(name = "{0} with {1}-byte lengths")
    @MethodSource("packaging")
    @DisplayName("length-prefixed samples with an avcC record decode exactly like ffmpeg")
    void lengthPrefixed(Case c, int lengthSize) {
        var stream = encode(c);
        var reference = reference(stream, c.pixelFormat());
        var nals = H264TestStreams.nalUnits(Fixtures.read(stream));
        var units = H264TestStreams.accessUnits(nals);
        var limit = (1L << (8 * lengthSize)) - 1;
        var payloads = new ArrayList<byte[]>();
        for (var unit : units) {
            // Parameter sets travel in the avcC record; SEI too large for the length field is dropped.
            var slices = unit.stream().filter(nal -> {
                var type = nal[0] & 0x1F;
                return type != 7 && type != 8 && (type != 6 || nal.length <= limit);
            }).toList();
            if (!slices.isEmpty()) {
                payloads.add(H264TestStreams.lengthPrefixed(slices, lengthSize));
            }
        }
        assumeTrue(nals.stream().allMatch(nal -> (nal[0] & 0x1F) == 6 || nal.length <= limit),
                "NAL units exceed the length field");
        var track = H264TestStreams.track(Optional.of(H264TestStreams.avcC(nals, lengthSize, true)), Optional.empty());
        assertMatches(c, reference, H264TestStreams.decode(track, H264TestStreams.packets(payloads), 1));
    }

    @Nested
    @DisplayName("container contracts")
    class Contracts {
        @Test
        @DisplayName("an avc3 record without parameter sets uses the in-band SPS and PPS")
        void avc3() {
            var c = cases().filter(x -> x.name().equals("main cabac 3 bframes pyramid normal")).findFirst().orElseThrow();
            var stream = encode(c);
            var nals = H264TestStreams.nalUnits(Fixtures.read(stream));
            var payloads = new ArrayList<byte[]>();
            for (var unit : H264TestStreams.accessUnits(nals)) {
                payloads.add(H264TestStreams.lengthPrefixed(unit, 4));
            }
            var track = H264TestStreams.track(Optional.of(H264TestStreams.avcC(nals, 4, false)), Optional.empty());
            assertMatches(c, reference(stream, c.pixelFormat()), H264TestStreams.decode(track, H264TestStreams.packets(payloads), 1));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"high 16 bframes", "interlaced mbaff tff cabac bframes", "interlaced mbaff bff cavlc",
                "interlaced temporal direct weightp", "interlaced 1920x1080 cropped"})
        @DisplayName("frame threads produce the same frames as one thread")
        void threads(String name) {
            var c = cases().filter(x -> x.name().startsWith(name)).findFirst().orElseThrow();
            var stream = Fixtures.read(encode(c));
            var single = H264TestStreams.decode(H264TestStreams.track(Optional.empty(), Optional.empty()), annexBPackets(stream), 1);
            for (var threads : new int[]{2, 4, 8}) {
                var threaded = H264TestStreams.decode(H264TestStreams.track(Optional.empty(), Optional.empty()), annexBPackets(stream), threads);
                assertEquals(single.size(), threaded.size());
                for (var i = 0; i < single.size(); i++) {
                    assertArrayEquals(single.get(i).samples(), threaded.get(i).samples(), "frame " + i + " with " + threads + " threads");
                }
            }
        }

        @Test
        @DisplayName("output follows presentation order with each access unit's pts, negative pts and missing dts included")
        void presentationTimestamps() {
            var c = cases().filter(x -> x.name().startsWith("high 16 bframes")).findFirst().orElseThrow();
            var stream = Fixtures.read(encode(c));
            var units = H264TestStreams.accessUnits(H264TestStreams.nalUnits(stream));
            var reference = H264TestStreams.decode(H264TestStreams.track(Optional.empty(), Optional.empty()), annexBPackets(stream), 1);
            // Assign each access unit the pts of its presentation index, shifted negative like samples before an edit list.
            var order = presentationOrder(units);
            var packets = new ArrayList<Packet>();
            for (var i = 0; i < units.size(); i++) {
                packets.add(new Packet(1, H264TestStreams.annexB(units.get(i)), (order[i] - 3) * 3600L, Packet.NO_TIMESTAMP, 3600, false));
            }
            var frames = H264TestStreams.decode(H264TestStreams.track(Optional.empty(), Optional.empty()), packets, 1);
            assertEquals(reference.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertEquals((i - 3) * 3600L, frames.get(i).pts(), "pts of frame " + i);
                assertEquals(3600, frames.get(i).duration());
                assertArrayEquals(reference.get(i).samples(), frames.get(i).samples());
            }
        }

        private int[] presentationOrder(List<List<byte[]>> units) {
            var frames = H264TestStreams.decode(H264TestStreams.track(Optional.empty(), Optional.empty()),
                    decodeIndexPackets(units), 1);
            var order = new int[units.size()];
            for (var i = 0; i < frames.size(); i++) {
                order[(int) frames.get(i).pts()] = i;
            }
            return order;
        }

        private List<Packet> decodeIndexPackets(List<List<byte[]>> units) {
            var packets = new ArrayList<Packet>();
            for (var i = 0; i < units.size(); i++) {
                packets.add(new Packet(1, H264TestStreams.annexB(units.get(i)), i, Packet.NO_TIMESTAMP, 0, false));
            }
            return packets;
        }

        @Test
        @DisplayName("missing pts are derived from the frame rate")
        void derivedTimestamps() {
            var c = cases().filter(x -> x.name().startsWith("main cabac 3")).findFirst().orElseThrow();
            var stream = Fixtures.read(encode(c));
            var frames = H264TestStreams.decode(H264TestStreams.track(Optional.empty(), Optional.of(new Rational(25, 1))),
                    annexBPackets(stream), 1);
            for (var i = 0; i < frames.size(); i++) {
                assertEquals(i * 3600L, frames.get(i).pts(), "pts of frame " + i);
                assertEquals(3600, frames.get(i).duration());
            }
        }
    }
}
