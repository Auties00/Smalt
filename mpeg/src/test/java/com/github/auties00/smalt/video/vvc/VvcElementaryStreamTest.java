package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.raw.AnnexBDemuxer;
import com.github.auties00.smalt.container.ts.PsMuxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carries the VVenC test streams through the VVC paths that work from the Annex B byte stream: the raw elementary
 * stream ({@link ContainerFormat#VVC}, read by {@link AnnexBDemuxer} and written by the raw muxer), the access unit
 * splitting and {@code vvcC} building of the MPEG-TS and MPEG-PS demuxers, and Smalt's TS and PS muxers. The
 * {@code vvcC} records Smalt builds from in-band parameter sets must equal the ones ffmpeg writes into MP4, the
 * decoded frames must hash to the reference MD5, presentation times must rise in output order, and ffmpeg (a
 * black-box decoder with its own VVC implementation) must decode the files Smalt writes to the same samples.
 */
@DisplayName("VVC elementary streams, MPEG-TS and MPEG-PS")
class VvcElementaryStreamTest {
    private static final Path VECTORS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "video", "vvc");

    private static VvcDecoderTest.Vector vector(String name) {
        return VvcDecoderTest.vector(name);
    }

    private static Path ffmpegMp4(String name) {
        return Fixtures.ffmpeg("mp4", List.of("-r", "25", "-f", "vvc", "-i",
                VECTORS.resolve(name + ".vvc").toAbsolutePath().toString()), List.of("-c", "copy"));
    }

    private static byte[] ffmpegConfig(String name) throws Exception {
        try (var demuxer = new Mp4Demuxer(MediaSource.of(Fixtures.read(ffmpegMp4(name))))) {
            return demuxer.tracks().getFirst().codecConfig().orElseThrow();
        }
    }

    private record Decoded(int frames, String md5, List<Long> pts, List<Packet> packets) {
    }

    /**
     * Reads every packet of a demuxer's only track and decodes them, returning the MD5 of the frames in the
     * reference decoder's sample layout and the frames' timestamps.
     */
    private static Decoded decode(Demuxer demuxer) {
        var track = assertInstanceOf(VideoTrack.class, demuxer.tracks().getFirst());
        assertEquals(Codec.VVC, track.codec());
        var packets = new ArrayList<Packet>();
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            packets.add(packet);
        }
        var digest = VvcTestStreams.md5();
        var pts = new ArrayList<Long>();
        var options = VvcDecoderOptions.builder().highBitDepth(true).hashVerification(true).build();
        try (var decoder = new VvcDecoder(track, options)) {
            Consumer<VideoFrame> sink = frame -> {
                pts.add(frame.pts());
                VvcTestStreams.hash(digest, frame.picture());
            };
            for (var p : packets) {
                decoder.decode(p, sink);
            }
            decoder.flush(sink);
        }
        return new Decoded(pts.size(), HexFormat.of().formatHex(digest.digest()), pts, packets);
    }

    private static void assertRising(List<Long> pts) {
        for (var i = 1; i < pts.size(); i++) {
            assertTrue(pts.get(i) > pts.get(i - 1), "presentation times rise in output order: " + pts);
        }
    }

    /**
     * Decodes a file with ffmpeg into the reference decoder's sample layout and returns the MD5 of the samples.
     */
    private static String ffmpegMd5(Path file, String pixelFormat) throws Exception {
        var samples = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i",
                file.toString(), "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", pixelFormat, "-"));
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(samples));
    }

    private static String pixelFormat(String name) {
        return name.startsWith("intra-400") ? "gray10le" : name.contains("8bit") ? "yuv420p" : "yuv420p10le";
    }

    private static Path write(String name, byte[] data) {
        try {
            return Files.write(Scratch.directory("vvc-streams").resolve(name), data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Copies the packets of ffmpeg's MP4 file of a vector into a muxer.
     */
    private static byte[] remux(String name, Function<MediaSink, Muxer> open) throws Exception {
        var sink = MediaSink.memory();
        try (var demuxer = new Mp4Demuxer(MediaSource.of(Fixtures.read(ffmpegMp4(name))));
             var muxer = open.apply(sink)) {
            muxer.addTrack(demuxer.tracks().getFirst());
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /**
     * Returns the length of the {@code VvcPTLRecord} that starts at an index of a record.
     */
    private static int ptlLength(byte[] record, int offset, int sublayers) {
        var position = offset + 1 + 2 + (record[offset] & 0x3F);
        if (sublayers > 1) {
            position += 1 + Integer.bitCount(record[position] & 0xFF);
        }
        return position + 1 + 4 * (record[position] & 0xFF) - offset;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "OLS_A_5, 1, 7, 416, 240", "OLS_B_5, 1, 7, 416, 240", "OLS_C_5, 2, 7, 416, 240",
            "VPS_A_3, 1, 1, 832, 480", "VPS_B_1, 2, 2, 832, 480", "ILRPL_A_2, 1, 7, 416, 240",
            "OPI_B_3, 1, 7, 416, 240"})
    @DisplayName("describes the output layer set with the most layers in the vvcC record of a multi-layer stream")
    void multiLayerConfig(String name, int olsIndex, int sublayers, int width, int height) throws Exception {
        var stream = VvcConformanceTest.bitstream(name, name + ".bit");
        var file = Files.write(Scratch.directory("vvc-layers").resolve(name + ".vvc"), stream);
        var mp4 = Fixtures.ffmpeg("mp4", List.of("-r", "25", "-f", "vvc", "-i", file.toAbsolutePath().toString()),
                List.of("-c", "copy"));
        byte[] reference;
        try (var demuxer = new Mp4Demuxer(MediaSource.of(Fixtures.read(mp4)))) {
            reference = demuxer.tracks().getFirst().codecConfig().orElseThrow();
        }
        byte[] record;
        try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
            record = demuxer.tracks().getFirst().codecConfig().orElseThrow();
        }
        assertEquals(1, record[0] & 1, "ptl_present_flag");
        assertEquals(olsIndex, ((record[1] & 0xFF) << 1) | ((record[2] & 0xFF) >> 7), "ols_idx");
        assertEquals(sublayers, (record[2] >> 4) & 7, "num_sublayers");
        assertEquals(1, record[2] & 3, "chroma_format_idc");
        assertEquals(10, ((record[3] & 0xFF) >> 5) + 8, "bit depth");
        // ffmpeg 7.1 copies the profile, tier and level the VPS gives the set; its ols_idx is always 0, its
        // constant_frame_rate always 1 and its picture size that of the base layer, so only the structure and the
        // parameter sets are compared
        var length = ptlLength(record, 4, sublayers);
        assertArrayEquals(Arrays.copyOfRange(reference, 4, 4 + length),
                Arrays.copyOfRange(record, 4, 4 + length), "VvcPTLRecord");
        var size = 4 + length;
        assertEquals(width, ((record[size] & 0xFF) << 8) | (record[size + 1] & 0xFF), "max_picture_width");
        assertEquals(height, ((record[size + 2] & 0xFF) << 8) | (record[size + 3] & 0xFF), "max_picture_height");
        assertEquals(arrays(reference, size + 6), arrays(record, size + 6), "parameter set arrays");
    }

    /**
     * Lists the NAL unit type and the hexadecimal NAL units of the arrays of a record from an index, without the zero
     * bytes ffmpeg keeps after a unit when the next start code has four bytes (H.266 7.4.2.2: a NAL unit never ends in
     * a zero byte).
     */
    private static List<String> arrays(byte[] record, int offset) {
        var result = new ArrayList<String>();
        var position = offset + 1;
        for (var array = 0; array < (record[offset] & 0xFF); array++) {
            var type = record[position] & 0x1F;
            var count = ((record[position + 1] & 0xFF) << 8) | (record[position + 2] & 0xFF);
            position += 3;
            for (var i = 0; i < count; i++) {
                var length = ((record[position] & 0xFF) << 8) | (record[position + 1] & 0xFF);
                var end = position + 2 + length;
                while (end > position + 2 && record[end - 1] == 0) {
                    end--;
                }
                result.add(type + ":" + HexFormat.of().formatHex(record, position + 2, end));
                position += 2 + length;
            }
        }
        return result;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"concat-420-8bit, ''", "no-output-420-8bit, 0 5 6 7 8"})
    @DisplayName("leaves without a presentation time the pictures an IDR picture's sh_no_output_of_prior_pics_flag "
            + "discards")
    void noOutputOfPriorPics(String name, String discarded) throws Exception {
        var vector = vector(name);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(vector.stream()))) {
            var decoded = decode(demuxer);
            assertEquals(vector.md5(), decoded.md5());
            assertEquals(vector.frames(), decoded.frames());
            assertEquals(18, decoded.packets().size());
            // the first sequence decodes order counts 7, 3, 1, 0, 2, 5, 4, 6 and 8, and outputs 0 to 3 under its
            // reorder bound of 5; the pictures still waiting (7, 5, 4, 6 and 8) are discarded by the second sequence's
            // IDR picture when it sets the flag
            var unpresented = new ArrayList<Integer>();
            for (var i = 0; i < decoded.packets().size(); i++) {
                var packet = decoded.packets().get(i);
                if (packet.pts() == Packet.NO_TIMESTAMP) {
                    unpresented.add(i);
                } else {
                    assertTrue(packet.dts() <= packet.pts(), "no decoding time follows its presentation time");
                }
            }
            assertEquals(discarded.isEmpty() ? List.of() : Arrays.stream(discarded.split(" ")).map(Integer::valueOf)
                    .toList(), unpresented);
            assertEquals(vector.frames(), decoded.packets().size() - unpresented.size());
            assertRising(decoded.pts());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "ld-420-8bit", "intra-400-10bit", "tiles-420-10bit", "hdr-420-10bit"})
    @DisplayName("reads the raw stream as one VVC track of access units with ffmpeg's vvcC record")
    void rawStream(String name) throws Exception {
        var vector = vector(name);
        var stream = vector.stream();
        assertEquals(ContainerFormat.VVC, AnnexBDemuxer.detect(stream).orElseThrow());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
            assertInstanceOf(AnnexBDemuxer.class, demuxer);
            assertEquals(ContainerFormat.VVC, demuxer.format());
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertArrayEquals(ffmpegConfig(name), track.codecConfig().orElseThrow());
            assertEquals(new Rational(25, 1), track.frameRate().orElseThrow());
            var decoded = decode(demuxer);
            assertEquals(vector.md5(), decoded.md5());
            assertEquals(vector.frames(), decoded.frames());
            assertEquals(vector.frames(), decoded.packets().size(), "one packet per access unit");
            assertTrue(decoded.packets().getFirst().keyframe());
            assertRising(decoded.pts());
            for (var packet : decoded.packets()) {
                assertTrue(packet.dts() <= packet.pts(), "no decoding time follows its presentation time");
            }
        }
    }

    @Test
    @DisplayName("reports the picture size, color, profile and level ffprobe reports")
    void probe() throws Exception {
        for (var name : List.of("ra-420-10bit", "hdr-420-10bit", "intra-400-10bit")) {
            var file = VECTORS.resolve(name + ".vvc");
            var info = Media.probe(MediaSource.of(Fixtures.read(file))).videoStreams().getFirst();
            var ffprobe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                    "-show_entries", "stream=profile,level,width,height,color_primaries,color_transfer", "-of",
                    "default=noprint_wrappers=1", file.toString())), StandardCharsets.UTF_8);
            assertTrue(ffprobe.contains("profile=" + info.profile().orElseThrow()), name + ": " + ffprobe);
            assertTrue(ffprobe.contains("level=" + info.level().orElseThrow()), name + ": " + ffprobe);
            assertTrue(ffprobe.contains("width=" + info.width()), name + ": " + ffprobe);
            assertTrue(ffprobe.contains("height=" + info.height()), name + ": " + ffprobe);
            if (name.startsWith("hdr")) {
                assertTrue(ffprobe.contains("color_transfer=smpte2084"), ffprobe);
                assertEquals(com.github.auties00.smalt.image.TransferCharacteristics.PQ,
                        info.color().orElseThrow().transfer());
            }
        }
    }

    @Test
    @DisplayName("seeks the raw stream to the random access point before the target")
    void seek() throws Exception {
        var vector = vector("intra-400-10bit");
        try (var demuxer = new AnnexBDemuxer(MediaSource.of(vector.stream()))) {
            var all = decode(demuxer);
            assertTrue(demuxer.isSeekable());
            demuxer.seek(demuxer.tracks().getFirst().timeBase().toMicros(all.pts().get(2)));
            var packet = demuxer.read();
            assertTrue(packet.keyframe());
            assertEquals(all.pts().get(2), packet.pts());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "ld-420-8bit", "intra-400-10bit"})
    @DisplayName("writes a raw stream that Smalt and ffmpeg decode to the reference")
    void rawMuxer(String name) throws Exception {
        var vector = vector(name);
        assertTrue(Containers.isMuxable(ContainerFormat.VVC, Codec.VVC));
        assertFalse(Containers.isMuxable(ContainerFormat.VVC, Codec.HEVC));
        var file = remux(name, sink -> Containers.openMuxer(ContainerFormat.VVC, sink));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            assertEquals(ContainerFormat.VVC, demuxer.format());
            assertEquals(vector.md5(), decode(demuxer).md5());
        }
        assertEquals(vector.md5(), ffmpegMd5(write(name + ".vvc", file), pixelFormat(name)));
        assertEquals(OutputContainer.VVC, OutputContainer.forFileName("clip.266").orElseThrow());
        assertTrue(OutputContainer.VVC.supports(Codec.VVC));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "ld-420-8bit", "tiles-420-10bit"})
    @DisplayName("splits ffmpeg's MPEG-TS stream into access units with ffmpeg's vvcC record")
    void transportStreamAccessUnits(String name) throws Exception {
        var vector = vector(name);
        var ts = Fixtures.ffmpeg("ts", List.of("-i", ffmpegMp4(name).toAbsolutePath().toString()),
                List.of("-c", "copy"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(ts)))) {
            var track = demuxer.tracks().getFirst();
            assertArrayEquals(ffmpegConfig(name), track.codecConfig().orElseThrow());
            var decoded = decode(demuxer);
            assertEquals(vector.md5(), decoded.md5());
            assertEquals(vector.frames(), decoded.packets().size(), "one packet per access unit");
            assertTrue(decoded.packets().getFirst().keyframe());
            for (var packet : decoded.packets()) {
                // the packets are length-prefixed NAL units without access unit delimiters
                var units = VvcTestStreams.nalUnits(com.github.auties00.smalt.internal.nal.NalUnits.toAnnexB(
                        packet.data(), 4, Codec.VVC, null, null));
                assertTrue(units.stream().noneMatch(unit -> VvcTestStreams.type(unit) == 20));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "ld-420-8bit", "intra-400-10bit"})
    @DisplayName("writes MPEG-TS that Smalt reads back and ffmpeg decodes to the reference")
    void transportStreamMuxer(String name) throws Exception {
        var vector = vector(name);
        assertTrue(TsMuxer.supports(Codec.VVC));
        var file = remux(name, TsMuxer::new);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            assertEquals(ContainerFormat.MPEG_TS, demuxer.format());
            var decoded = decode(demuxer);
            assertEquals(vector.md5(), decoded.md5());
            assertEquals(vector.frames(), decoded.packets().size());
        }
        var path = write(name + ".ts", file);
        var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_entries", "stream=codec_name", "-of", "csv=p=0", path.toString())), StandardCharsets.UTF_8);
        assertEquals(List.of("vvc"), probe.lines().filter(line -> !line.isBlank()).distinct().toList());
        assertEquals(vector.md5(), ffmpegMd5(path, pixelFormat(name)));
    }

    @Test
    @DisplayName("starts every TS access unit with a delimiter that flags the random access points")
    void transportStreamDelimiters() throws Exception {
        var file = remux("ra-420-10bit", TsMuxer::new);
        var payload = new java.io.ByteArrayOutputStream();
        for (var offset = 0; offset + 188 <= file.length; offset += 188) {
            var pid = ((file[offset + 1] & 0x1F) << 8) | (file[offset + 2] & 0xFF);
            if (pid == 0 || pid == 0x1FFF || (file[offset + 3] & 0x10) == 0) {
                continue;
            }
            var start = offset + 4 + ((file[offset + 3] & 0x20) != 0 ? 1 + (file[offset + 4] & 0xFF) : 0);
            if ((file[offset + 1] & 0x40) != 0 && start + 9 < offset + 188 && file[start] == 0 && file[start + 1] == 0
                    && file[start + 2] == 1 && (file[start + 3] & 0xF0) == 0xE0) {
                start += 9 + (file[start + 8] & 0xFF);
            }
            payload.write(file, start, offset + 188 - start);
        }
        var delimiters = new ArrayList<Integer>();
        for (var unit : VvcTestStreams.nalUnits(payload.toByteArray())) {
            if (VvcTestStreams.type(unit) == 20) {
                delimiters.add(unit[2] & 0xFF);
            }
        }
        assertEquals(vector("ra-420-10bit").frames(), delimiters.size());
        // aud_irap_or_gdr_flag set on the IDR access unit only, aud_pic_type 2 everywhere
        assertEquals(0xA8, delimiters.getFirst());
        for (var i = 1; i < delimiters.size(); i++) {
            assertEquals(0x28, delimiters.get(i));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "intra-400-10bit"})
    @DisplayName("writes MPEG-PS that Smalt reads back, its program stream map declaring stream_type 0x33")
    void programStream(String name) throws Exception {
        var vector = vector(name);
        assertTrue(PsMuxer.supports(Codec.VVC));
        var file = remux(name, PsMuxer::new);
        var text = new String(file, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("\u0000\u0000\u0001¼"), "a program stream map is written");
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            assertEquals(ContainerFormat.MPEG_PS, demuxer.format());
            var decoded = decode(demuxer);
            assertEquals(vector.md5(), decoded.md5());
            assertEquals(vector.frames(), decoded.packets().size());
        }
    }

    @Test
    @DisplayName("recognizes a VVC program stream without a stream map from its NAL units")
    void programStreamWithoutMap() throws Exception {
        var vector = vector("ra-420-10bit");
        var file = remux("ra-420-10bit", PsMuxer::new);
        var stripped = new java.io.ByteArrayOutputStream();
        var maps = 0;
        for (var i = 0; i < file.length; ) {
            if (i + 6 <= file.length && file[i] == 0 && file[i + 1] == 0 && file[i + 2] == 1
                    && (file[i + 3] & 0xFF) == 0xBC) {
                // the program stream map packet: its PES_packet_length follows the start code
                i += 6 + (((file[i + 4] & 0xFF) << 8) | (file[i + 5] & 0xFF));
                maps++;
                continue;
            }
            stripped.write(file[i++]);
        }
        assertTrue(maps > 0);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(stripped.toByteArray()))) {
            assertEquals(ContainerFormat.MPEG_PS, demuxer.format());
            assertEquals(vector.md5(), decode(demuxer).md5());
        }
    }

    @Test
    @DisplayName("rejects a VVC track without its vvcC record in the raw, TS and PS muxers")
    void needsConfiguration() {
        var track = VideoTrack.builder().id(1).codec(Codec.VVC).timeBase(new Rational(1, 90_000)).width(64)
                .height(64).build();
        for (Function<MediaSink, Muxer> open : List.<Function<MediaSink, Muxer>>of(TsMuxer::new, PsMuxer::new,
                sink -> Containers.openMuxer(ContainerFormat.VVC, sink))) {
            try (var muxer = open.apply(MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(track));
            }
        }
    }
}
