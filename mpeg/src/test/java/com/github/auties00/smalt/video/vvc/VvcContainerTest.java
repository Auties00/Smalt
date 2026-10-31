package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carries the VVenC test streams through the containers that map VVC: Smalt's MP4 and fragmented MP4 ({@code vvc1}
 * with {@code vvcC}, and {@code vvi1} on the demuxer side) and Matroska ({@code V_MPEGI/ISO/VVC}) muxers and demuxers, and files ffmpeg writes as a black box
 * (MP4, MPEG-TS with {@code stream_type 0x33}, and Matroska, which ffmpeg 7.1 stores as {@code V_QUICKTIME} with a
 * {@code vvc1} image description), and requires the demuxed track to decode to the reference MD5.
 */
@DisplayName("VVC in containers")
class VvcContainerTest {
    private static final Path VECTORS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "video", "vvc");

    private static final int FRAME = 3600;

    private static final Map<String, int[]> SIZES = Map.of("ra-420-10bit", new int[]{136, 104}, "ld-420-8bit",
            new int[]{128, 96}, "intra-400-10bit", new int[]{104, 72}, "tiles-420-10bit", new int[]{256, 128},
            "hdr-420-10bit", new int[]{128, 64});

    private static VvcDecoderTest.Vector vector(String name) {
        return VvcDecoderTest.vector(name);
    }

    /**
     * Builds the track and the samples of a stream: one length-prefixed sample per access unit, timestamped in
     * decoding order, and the {@code vvcC} record of the parameter sets before the first slice.
     */
    private static VideoTrack track(List<byte[]> nals, int width, int height) {
        var sets = new ArrayList<byte[]>();
        for (var nal : nals) {
            if (VvcTestStreams.type(nal) <= 11) {
                break;
            }
            if (VvcTestStreams.isParameterSet(nal)) {
                sets.add(nal);
            }
        }
        return VideoTrack.builder().id(1).codec(Codec.VVC).timeBase(new Rational(1, 90_000)).width(width)
                .height(height).frameRate(new Rational(25, 1)).codecConfig(VvcTestStreams.vvcC(sets, false)).build();
    }

    private static byte[] mux(String name, Function<MediaSink, ? extends AutoCloseable> open, PacketWriter writer)
            throws Exception {
        var nals = VvcTestStreams.nalUnits(vector(name).stream());
        var size = SIZES.get(name);
        var track = track(nals, size[0], size[1]);
        var sink = MediaSink.memory();
        try (var muxer = open.apply(sink)) {
            writer.addTrack(muxer, track);
            var units = VvcTestStreams.accessUnits(nals);
            var ranks = outputRanks(units);
            var delay = 0;
            for (var i = 0; i < ranks.length; i++) {
                delay = Math.max(delay, i - ranks[i]);
            }
            for (var i = 0; i < units.size(); i++) {
                var key = units.get(i).stream().anyMatch(nal -> {
                    var type = VvcTestStreams.type(nal);
                    return type >= 7 && type <= 9;
                });
                writer.write(muxer, new Packet(1, VvcTestStreams.lengthPrefixed(units.get(i)),
                        (long) (ranks[i] + delay) * FRAME, (long) i * FRAME, FRAME, key));
            }
            writer.finish(muxer);
        }
        return sink.toByteArray();
    }

    /**
     * Returns, for each access unit in decoding order, its index in output order, as the decoder outputs them.
     */
    private static int[] outputRanks(List<List<byte[]>> units) {
        var order = new ArrayList<Long>();
        try (var decoder = new VvcDecoder(VvcTestStreams.track())) {
            Consumer<VideoFrame> sink = frame -> order.add(frame.pts());
            for (var i = 0; i < units.size(); i++) {
                decoder.decode(new Packet(1, annexB(units.get(i)), i, i, 1, false), sink);
            }
            decoder.flush(sink);
        }
        var ranks = new int[units.size()];
        for (var rank = 0; rank < order.size(); rank++) {
            ranks[(int) (long) order.get(rank)] = rank;
        }
        return ranks;
    }

    private static byte[] annexB(List<byte[]> nals) {
        var out = new java.io.ByteArrayOutputStream();
        for (var nal : nals) {
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }

    private interface PacketWriter {
        void addTrack(AutoCloseable muxer, VideoTrack track);

        void write(AutoCloseable muxer, Packet packet);

        void finish(AutoCloseable muxer);
    }

    private static final PacketWriter MP4 = new PacketWriter() {
        @Override
        public void addTrack(AutoCloseable muxer, VideoTrack track) {
            ((Mp4Muxer) muxer).addTrack(track);
        }

        @Override
        public void write(AutoCloseable muxer, Packet packet) {
            ((Mp4Muxer) muxer).writePacket(packet);
        }

        @Override
        public void finish(AutoCloseable muxer) {
            ((Mp4Muxer) muxer).finish();
        }
    };

    private static final PacketWriter FRAGMENTED_MP4 = new PacketWriter() {
        @Override
        public void addTrack(AutoCloseable muxer, VideoTrack track) {
            ((FragmentedMp4Muxer) muxer).addTrack(track);
        }

        @Override
        public void write(AutoCloseable muxer, Packet packet) {
            ((FragmentedMp4Muxer) muxer).writePacket(packet);
        }

        @Override
        public void finish(AutoCloseable muxer) {
            ((FragmentedMp4Muxer) muxer).finish();
        }
    };

    private static final PacketWriter MATROSKA = new PacketWriter() {
        @Override
        public void addTrack(AutoCloseable muxer, VideoTrack track) {
            ((MatroskaMuxer) muxer).addTrack(track);
        }

        @Override
        public void write(AutoCloseable muxer, Packet packet) {
            ((MatroskaMuxer) muxer).writePacket(packet);
        }

        @Override
        public void finish(AutoCloseable muxer) {
            ((MatroskaMuxer) muxer).finish();
        }
    };

    /**
     * Decodes the only track of a demuxer, in presentation order, and returns the MD5 of its frames.
     */
    private static String decode(Demuxer demuxer, int frames) {
        var track = assertInstanceOf(VideoTrack.class, demuxer.tracks().getFirst());
        assertEquals(Codec.VVC, track.codec());
        var digest = VvcTestStreams.md5();
        var count = new int[1];
        var options = VvcDecoderOptions.builder().highBitDepth(true).hashVerification(true).build();
        try (var decoder = new VvcDecoder(track, options)) {
            Consumer<VideoFrame> sink = frame -> {
                count[0]++;
                VvcTestStreams.hash(digest, frame.picture());
            };
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        assertEquals(frames, count[0]);
        return HexFormat.of().formatHex(digest.digest());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "ld-420-8bit", "intra-400-10bit"})
    @DisplayName("round-trips through Smalt's MP4 muxer and demuxer as vvc1 with a vvcC box")
    void mp4(String name) throws Exception {
        var vector = vector(name);
        var file = mux(name, Mp4Muxer::new, MP4);
        var text = new String(file, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("vvc1") && text.contains("vvcC"));
        try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
            var config = demuxer.tracks().getFirst().codecConfig().orElseThrow();
            assertEquals(0xF8, config[0] & 0xF8, "the record starts without the full box header");
            assertEquals(vector.md5(), decode(demuxer, vector.frames()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "ld-420-8bit"})
    @DisplayName("round-trips through Smalt's fragmented MP4 muxer and the MP4 demuxer as vvc1 with a vvcC box")
    void fragmentedMp4(String name) throws Exception {
        var vector = vector(name);
        var file = mux(name, FragmentedMp4Muxer::new, FRAGMENTED_MP4);
        var text = new String(file, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("vvc1") && text.contains("vvcC") && text.contains("moof"));
        try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
            assertEquals(vector.md5(), decode(demuxer, vector.frames()));
        }
    }

    @Test
    @DisplayName("reads a vvi1 sample entry, whose parameter sets may also be in band, as a VVC track")
    void vvi1() throws Exception {
        var vector = vector("ld-420-8bit");
        var file = mux("ld-420-8bit", Mp4Muxer::new, MP4);
        var text = new String(file, java.nio.charset.StandardCharsets.ISO_8859_1);
        var at = text.indexOf("vvc1");
        assertTrue(at > 0 && text.indexOf("vvc1", at + 1) < 0, "one vvc1 sample entry");
        file[at + 2] = 'i';
        file[at + 3] = '1';
        try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
            assertEquals(vector.md5(), decode(demuxer, vector.frames()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "tiles-420-10bit"})
    @DisplayName("round-trips through Smalt's Matroska muxer and demuxer as V_MPEGI/ISO/VVC")
    void matroska(String name) throws Exception {
        var vector = vector(name);
        var file = mux(name, MatroskaMuxer::new, MATROSKA);
        assertTrue(new String(file, java.nio.charset.StandardCharsets.ISO_8859_1).contains("V_MPEGI/ISO/VVC"));
        try (var demuxer = new MatroskaDemuxer(MediaSource.of(file))) {
            assertEquals(vector.md5(), decode(demuxer, vector.frames()));
        }
    }

    @Test
    @DisplayName("keeps the vvcC record of the track as the track configuration")
    void configuration() throws Exception {
        var nals = VvcTestStreams.nalUnits(vector("ld-420-8bit").stream());
        var expected = track(nals, 128, 96).codecConfig().orElseThrow();
        for (var file : List.of(mux("ld-420-8bit", Mp4Muxer::new, MP4),
                mux("ld-420-8bit", MatroskaMuxer::new, MATROSKA))) {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                assertArrayEquals(expected, demuxer.tracks().getFirst().codecConfig().orElseThrow());
            }
        }
    }

    private static Path ffmpegMp4(String name) {
        return Fixtures.ffmpeg("mp4", List.of("-r", "25", "-f", "vvc", "-i",
                VECTORS.resolve(name + ".vvc").toAbsolutePath().toString()), List.of("-c", "copy"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "ld-420-8bit", "hdr-420-10bit"})
    @DisplayName("demuxes ffmpeg's MP4 files")
    void ffmpegMp4Files(String name) throws Exception {
        var vector = vector(name);
        try (var demuxer = new Mp4Demuxer(MediaSource.of(Fixtures.read(ffmpegMp4(name))))) {
            assertEquals(vector.md5(), decode(demuxer, vector.frames()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "ld-420-8bit"})
    @DisplayName("demuxes ffmpeg's MPEG-TS files (stream_type 0x33)")
    void ffmpegTransportStream(String name) throws Exception {
        var vector = vector(name);
        var ts = Fixtures.ffmpeg("ts", List.of("-i", ffmpegMp4(name).toAbsolutePath().toString()),
                List.of("-c", "copy"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(ts)))) {
            assertEquals(vector.md5(), decode(demuxer, vector.frames()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "intra-400-10bit"})
    @DisplayName("demuxes ffmpeg's Matroska files")
    void ffmpegMatroska(String name) throws Exception {
        var vector = vector(name);
        var mkv = Fixtures.ffmpeg("mkv", List.of("-i", ffmpegMp4(name).toAbsolutePath().toString()),
                List.of("-c", "copy"));
        try (var demuxer = new MatroskaDemuxer(MediaSource.of(Fixtures.read(mkv)))) {
            assertEquals(vector.md5(), decode(demuxer, vector.frames()));
        }
    }

    @Test
    @DisplayName("muxes VVC into MP4 and Matroska only with its configuration")
    void support() {
        assertTrue(Mp4Muxer.supports(com.github.auties00.smalt.container.mp4.Mp4MuxerOptions.Brand.MP4, Codec.VVC));
        assertTrue(MatroskaMuxer.supports(com.github.auties00.smalt.container.matroska.MatroskaDocType.MATROSKA,
                Codec.VVC));
        var track = VideoTrack.builder().id(1).codec(Codec.VVC).timeBase(new Rational(1, 90_000)).width(64)
                .height(64).build();
        try (var muxer = new Mp4Muxer(MediaSink.memory())) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(track));
        }
    }
}
