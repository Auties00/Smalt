package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.HdrFixtures;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.github.auties00.smalt.container.HdrFixtures.CLLI;
import static com.github.auties00.smalt.container.HdrFixtures.MDCV;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code mdcv} and {@code clli} boxes {@link Mp4Muxer} and {@link FragmentedMp4Muxer}
 * write for the static HDR metadata of a color description: their exact bytes, the round trip
 * through {@link Mp4Demuxer}, ffprobe reading them as stream side data, ffmpeg copying them to
 * Matroska and back, and {@link Mp4Remuxer} keeping those of an ffmpeg-written HDR10 file and of
 * an entry without {@code colr}; truncated boxes are ignored on demux.
 */
@DisplayName("MP4 HDR metadata")
class Mp4HdrMetadataTest {
    private static ContainerProbe.Demuxed hevc() {
        return MuxSupport.demux(Fixtures.read(Fixtures.testVideo("mp4", 160, 120, 1, List.of("-c:v", "libx265",
                "-x265-params", "log-level=none:keyint=10:bframes=3:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc",
                "-pix_fmt", "yuv420p10le", "-c:a", "aac"))));
    }

    private static VideoTrack video(ContainerProbe.Demuxed demuxed) {
        return demuxed.tracks().stream().filter(VideoTrack.class::isInstance).map(VideoTrack.class::cast).findFirst().orElseThrow();
    }

    static Stream<Arguments> muxers() {
        return Stream.of(
                Arguments.of("mp4 fast start", (Function<MediaSink, Muxer>) sink -> new Mp4Muxer(sink, Mp4MuxerOptions.mp4())),
                Arguments.of("mp4 in place", (Function<MediaSink, Muxer>) sink -> new Mp4Muxer(sink,
                        Mp4MuxerOptions.mp4().toBuilder().fastStart(false).build())),
                Arguments.of("mov", (Function<MediaSink, Muxer>) sink -> new Mp4Muxer(sink, Mp4MuxerOptions.quickTime())),
                Arguments.of("fragmented", (Function<MediaSink, Muxer>) sink -> new FragmentedMp4Muxer(sink)),
                Arguments.of("cmaf", (Function<MediaSink, Muxer>) sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.cmaf())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("writes exact mdcv and clli boxes that Smalt and ffprobe read back")
    void boxes(String name, Function<MediaSink, Muxer> factory) {
        var source = hevc();
        var video = video(source);
        // a CMAF track file holds one track
        var tracks = HdrFixtures.withColor(List.of(video), HdrFixtures.hdr10(ChromaSiting.TOP_LEFT));
        var output = MuxSupport.toMemory(tracks, source.packetsOf(video), factory);
        assertArrayEquals(MDCV, HdrFixtures.boxPayload(output, "mdcv"));
        assertArrayEquals(CLLI, HdrFixtures.boxPayload(output, "clli"));
        assertEquals(1, HdrFixtures.countBoxes(output, "mdcv"));
        assertEquals(1, HdrFixtures.countBoxes(output, "clli"));

        var result = MuxSupport.demux(output);
        var color = video(result).color().orElseThrow();
        HdrFixtures.assertColorClose(HdrFixtures.hdr10(ChromaSiting.UNSPECIFIED), color, 1e-12);
        assertEquals(34000 * 0.00002, color.masteringDisplay().orElseThrow().red().x());
        assertEquals(1 * 0.0001, color.masteringDisplay().orElseThrow().minLuminance());

        // a second pass over what the demuxer read writes the same boxes
        var again = MuxSupport.toMemory(result.tracks(), result.order(), factory);
        assertArrayEquals(MDCV, HdrFixtures.boxPayload(again, "mdcv"));
        assertArrayEquals(CLLI, HdrFixtures.boxPayload(again, "clli"));

        var file = MuxSupport.save("hdr-" + name.replace(' ', '-') + (name.equals("mov") ? ".mov" : ".mp4"), output);
        HdrFixtures.assertProbedHdr10(HdrFixtures.ffprobe(file, "-show_streams", "-select_streams", "v"));
        MuxSupport.assertFfmpegClean("hdr-clean-" + name.replace(' ', '-') + ".mp4", output);
    }

    @Test
    @DisplayName("writes mdcv and clli in a QuickTime ProRes sample entry")
    void prores() {
        var source = MuxSupport.demux(Fixtures.read(Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i",
                "testsrc2=size=96x64:rate=10:duration=0.3"), List.of("-c:v", "prores_ks"))));
        var tracks = HdrFixtures.withColor(source.tracks(), HdrFixtures.hdr10(ChromaSiting.LEFT));
        var output = MuxSupport.toMemory(tracks, source.order(), sink -> new Mp4Muxer(sink, Mp4MuxerOptions.quickTime()));
        assertArrayEquals(MDCV, HdrFixtures.boxPayload(output, "mdcv"));
        assertArrayEquals(CLLI, HdrFixtures.boxPayload(output, "clli"));
        assertEquals("nclc", new String(HdrFixtures.boxPayload(output, "colr"), 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        HdrFixtures.assertColorClose(HdrFixtures.hdr10(ChromaSiting.UNSPECIFIED),
                video(MuxSupport.demux(output)).color().orElseThrow(), 1e-12);
        var file = MuxSupport.save("hdr-prores.mov", output);
        HdrFixtures.assertProbedHdr10(HdrFixtures.ffprobe(file, "-show_streams", "-select_streams", "v"));
    }

    @Test
    @DisplayName("writes the same mdcv and clli as ffmpeg when ffmpeg copies them between MP4 and Matroska")
    void oracle() {
        var source = hevc();
        var tracks = HdrFixtures.withColor(source.tracks(), HdrFixtures.hdr10(ChromaSiting.TOP_LEFT));
        var mp4 = MuxSupport.save("hdr-oracle.mp4", MuxSupport.toMemory(tracks, source.order(), Mp4Muxer::new));
        var mkv = MuxSupport.save("hdr-oracle.mkv", MuxSupport.toMemory(tracks, source.order(),
                sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.matroska())));

        // ffmpeg rewrites the mdcv of its own MP4 copies from the metadata it read
        assertArrayEquals(MDCV, HdrFixtures.boxPayload(read(HdrFixtures.ffmpegCopy(mp4, "hdr-oracle-copy.mp4")), "mdcv"));
        var fromMatroska = read(HdrFixtures.ffmpegCopy(mkv, "hdr-oracle-from-mkv.mp4"));
        assertArrayEquals(MDCV, HdrFixtures.boxPayload(fromMatroska, "mdcv"));
        assertArrayEquals(CLLI, HdrFixtures.boxPayload(fromMatroska, "clli"));

        // and the Matroska it makes from Smalt's MP4 carries the same values
        var toMatroska = MuxSupport.demux(read(HdrFixtures.ffmpegCopy(mp4, "hdr-oracle-from-mp4.mkv")));
        HdrFixtures.assertColorClose(HdrFixtures.hdr10(ChromaSiting.UNSPECIFIED), video(toMatroska).color().orElseThrow(), 1e-6);
    }

    @Test
    @DisplayName("keeps the mdcv and clli of an ffmpeg-written HDR10 file through Mp4Remuxer")
    void remux() {
        var source = hevc();
        var tracks = HdrFixtures.withColor(source.tracks(), HdrFixtures.hdr10(ChromaSiting.TOP_LEFT));
        var mkv = MuxSupport.save("hdr-remux-source.mkv", MuxSupport.toMemory(tracks, source.order(),
                sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.matroska())));
        var sample = read(HdrFixtures.ffmpegCopy(mkv, "hdr-remux-sample.mp4"));
        for (var input : List.of(sample, read(mkv))) {
            var sink = MediaSink.memory();
            Mp4Remuxer.remux(MediaSource.of(input), sink);
            var output = sink.toByteArray();
            assertArrayEquals(HdrFixtures.boxPayload(sample, "mdcv"), HdrFixtures.boxPayload(output, "mdcv"));
            assertArrayEquals(HdrFixtures.boxPayload(sample, "clli"), HdrFixtures.boxPayload(output, "clli"));
            HdrFixtures.assertColorClose(video(MuxSupport.demux(sample)).color().orElseThrow(),
                    video(MuxSupport.demux(output)).color().orElseThrow(), 1e-12);
        }
    }

    @Test
    @DisplayName("rounds to the nearest unit and clamps values that overflow the fields")
    void clamping() {
        var display = new MasteringDisplay(new Chromaticity(1.4, 0.000011), new Chromaticity(0.00000999, 0.5),
                new Chromaticity(0.150009, 0.06), new Chromaticity(0.3127, 0.329), 500_000, 0.00004);
        var color = HdrFixtures.hdr10(ChromaSiting.UNSPECIFIED).withMasteringDisplay(display)
                .withContentLightLevel(new ContentLightLevel(65535, 0));
        var source = hevc();
        var output = MuxSupport.toMemory(HdrFixtures.withColor(source.tracks(), color), source.order(), Mp4Muxer::new);
        var mdcv = HdrFixtures.boxPayload(output, "mdcv");
        assertEquals(0, u16(mdcv, 0)); // green x 0.00000999 -> 0.4995 -> 0
        assertEquals(25000, u16(mdcv, 2));
        assertEquals(7500, u16(mdcv, 4)); // blue x 0.150009 -> 7500.45 -> 7500
        assertEquals(65535, u16(mdcv, 8)); // red x 1.4 -> 70000, clamped
        assertEquals(1, u16(mdcv, 10)); // red y 0.000011 -> 0.55 -> 1
        assertEquals(0xFFFFFFFFL, u32(mdcv, 16)); // 500000 cd/m2 overflows 32 bits of 0.0001
        assertEquals(0, u32(mdcv, 20)); // 0.00004 -> 0.4 -> 0
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, 0, 0}, HdrFixtures.boxPayload(output, "clli"));
    }

    @Test
    @DisplayName("writes neither box for a color description without HDR metadata, nor for a track without color")
    void sdr() {
        var source = hevc();
        var withSdr = MuxSupport.toMemory(HdrFixtures.withColor(source.tracks(), ColorInfo.BT709_LIMITED), source.order(), Mp4Muxer::new);
        assertTrue(HdrFixtures.countBoxes(withSdr, "colr") > 0);
        assertNull(HdrFixtures.boxPayload(withSdr, "mdcv"));
        assertNull(HdrFixtures.boxPayload(withSdr, "clli"));
        var onlyLight = MuxSupport.toMemory(HdrFixtures.withColor(source.tracks(),
                ColorInfo.BT709_LIMITED.withContentLightLevel(HdrFixtures.LIGHT)), source.order(), Mp4Muxer::new);
        assertNull(HdrFixtures.boxPayload(onlyLight, "mdcv"));
        assertArrayEquals(CLLI, HdrFixtures.boxPayload(onlyLight, "clli"));
        var plain = source.tracks().stream().map(track -> track instanceof VideoTrack video
                ? (Track) video.toBuilder().color(null).build() : track).toList();
        var none = MuxSupport.toMemory(plain, source.order(), Mp4Muxer::new);
        assertNull(HdrFixtures.boxPayload(none, "mdcv"));
        assertNull(HdrFixtures.boxPayload(none, "clli"));
    }

    @Test
    @DisplayName("reads mdcv and clli without a colr box as an unspecified color that a remux keeps")
    void withoutColr() {
        var source = hevc();
        var output = MuxSupport.toMemory(HdrFixtures.withColor(source.tracks(), HdrFixtures.hdr10(ChromaSiting.UNSPECIFIED)),
                source.order(), Mp4Muxer::new);
        var stripped = rename(output, "colr", "xolr");
        assertNull(HdrFixtures.boxPayload(stripped, "colr"));
        var expected = new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.LIMITED, ColorPrimaries.UNSPECIFIED,
                TransferCharacteristics.UNSPECIFIED).withMasteringDisplay(HdrFixtures.hdr10(ChromaSiting.UNSPECIFIED)
                .masteringDisplay().orElseThrow()).withContentLightLevel(HdrFixtures.LIGHT);
        HdrFixtures.assertColorClose(expected, video(MuxSupport.demux(stripped)).color().orElseThrow(), 1e-12);

        var sink = MediaSink.memory();
        Mp4Remuxer.remux(MediaSource.of(stripped), sink);
        var remuxed = sink.toByteArray();
        assertArrayEquals(MDCV, HdrFixtures.boxPayload(remuxed, "mdcv"));
        assertArrayEquals(CLLI, HdrFixtures.boxPayload(remuxed, "clli"));
        assertArrayEquals(new byte[]{'n', 'c', 'l', 'x', 0, 2, 0, 2, 0, 2, 0}, HdrFixtures.boxPayload(remuxed, "colr"));

        // a clli alone, without colr or mdcv, still gives the track its light level
        var lightOnly = rename(stripped, "mdcv", "xdcv");
        var color = video(MuxSupport.demux(lightOnly)).color().orElseThrow();
        assertTrue(color.masteringDisplay().isEmpty());
        assertEquals(HdrFixtures.LIGHT, color.contentLightLevel().orElseThrow());
    }

    @Test
    @DisplayName("ignores truncated mdcv and clli boxes")
    void truncated() {
        var source = hevc();
        var output = MuxSupport.toMemory(HdrFixtures.withColor(source.tracks(), HdrFixtures.hdr10(ChromaSiting.UNSPECIFIED)),
                source.order(), Mp4Muxer::new);
        // the 4-byte clli becomes an mdcv too short for its 24 bytes, and the real mdcv is hidden
        var shortMastering = rename(rename(output, "mdcv", "xdcv"), "clli", "mdcv");
        var color = video(MuxSupport.demux(shortMastering)).color().orElseThrow();
        assertTrue(color.masteringDisplay().isEmpty());
        assertTrue(color.contentLightLevel().isEmpty());
        assertEquals(TransferCharacteristics.PQ, color.transfer());

        // a 16-byte mdcv followed by an empty clli carved from its last 8 bytes
        var cut = shrink(rename(output, "clli", "xlli"), "mdcv", 8, "clli");
        assertEquals(16, HdrFixtures.boxPayload(cut, "mdcv").length);
        assertEquals(0, HdrFixtures.boxPayload(cut, "clli").length);
        var light = video(MuxSupport.demux(cut)).color().orElseThrow();
        assertTrue(light.contentLightLevel().isEmpty());
        assertTrue(light.masteringDisplay().isEmpty());
        assertEquals(TransferCharacteristics.PQ, light.transfer());
    }

    // renames the first box of a type, keeping every size
    private static byte[] rename(byte[] file, String from, String to) {
        var at = boxStart(file, from);
        var copy = file.clone();
        var name = to.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, copy, at + 4, 4);
        return copy;
    }

    // shrinks the first box of a type by some bytes, which become an empty box of another type
    private static byte[] shrink(byte[] file, String type, int bytes, String freed) {
        var at = boxStart(file, type);
        var copy = file.clone();
        var size = (int) u32(copy, at);
        var next = at + size - bytes;
        writeU32(copy, at, size - bytes);
        writeU32(copy, next, bytes);
        System.arraycopy(freed.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, copy, next + 4, 4);
        return copy;
    }

    private static int boxStart(byte[] file, String type) {
        var name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (var index = 4; index + 4 <= file.length; index++) {
            if (file[index] == name[0] && file[index + 1] == name[1] && file[index + 2] == name[2] && file[index + 3] == name[3]) {
                return index - 4;
            }
        }
        throw new AssertionError("no " + type + " box");
    }

    private static void writeU32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >> 24);
        data[offset + 1] = (byte) (value >> 16);
        data[offset + 2] = (byte) (value >> 8);
        data[offset + 3] = (byte) value;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF);
    }

    private static long u32(byte[] data, int offset) {
        return ((long) u16(data, offset) << 16) | u16(data, offset + 2);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
