package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.github.auties00.smalt.container.matroska.EbmlWriter.binary;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.block;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.file;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.master;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.string;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.uint;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers transparent video in Matroska and WebM: the {@code AlphaMode} of a video track and the alpha frames its
 * blocks carry in {@code BlockAdditional} 1, read by {@link MatroskaDemuxer}, written by {@link MatroskaMuxer},
 * checked by ffprobe, and decoded end to end by {@link VideoReader} over raw video, whose alpha frames are raw
 * pictures too.
 */
@DisplayName("Matroska transparent video")
class MatroskaAlphaTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "matroska");

    private static VideoTrack vp8(boolean alpha) {
        return VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 1000)).width(64).height(48)
                .frameRate(new Rational(25, 1)).alpha(alpha).build();
    }

    private static byte[] bytes(Random random, int length) {
        var data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    private static List<Packet> packets(boolean hdr10Plus) {
        var random = new Random(7);
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < 12; index++) {
            var side = PacketSideData.builder();
            if (index % 4 != 3) {
                side.alphaFrame(bytes(random, 10 + index));
            }
            if (hdr10Plus && index % 3 == 0) {
                side.hdr10Plus(new byte[]{(byte) 0xB5, 0, 0x3C, 0, 1, 4, 1, (byte) index});
            }
            packets.add(new Packet(1, bytes(random, 40 + index), index * 40L, index * 40L, 40, index % 5 == 0)
                    .withSideData(side.build()));
        }
        return packets;
    }

    private static VideoTrack videoTrack(List<Track> tracks) {
        return tracks.stream().filter(VideoTrack.class::isInstance).map(VideoTrack.class::cast).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("muxing")
    class Muxing {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"webm", "matroska", "live"})
        @DisplayName("an alpha track keeps its alpha frames through a mux and demux, the other side data too")
        void roundTrip(String profile) {
            var options = switch (profile) {
                case "webm" -> MatroskaMuxerOptions.webm();
                case "live" -> MatroskaMuxerOptions.liveWebm();
                default -> MatroskaMuxerOptions.matroska();
            };
            var packets = packets(true);
            var output = profile.equals("live")
                    ? MuxSupport.toStream(List.of(vp8(true)), packets, sink -> new MatroskaMuxer(sink, options))
                    : MuxSupport.toMemory(List.of(vp8(true)), packets, sink -> new MatroskaMuxer(sink, options));
            var demuxed = MuxSupport.demux(output);
            assertTrue(videoTrack(demuxed.tracks()).hasAlpha());
            var read = demuxed.order();
            assertEquals(packets.size(), read.size());
            for (var index = 0; index < packets.size(); index++) {
                var expected = packets.get(index);
                var actual = read.get(index);
                assertArrayEquals(expected.data(), actual.data(), "packet " + index);
                assertEquals(expected.keyframe(), actual.keyframe(), "packet " + index);
                assertEquals(expected.sideData().alphaFrame().map(Arrays::toString),
                        actual.sideData().alphaFrame().map(Arrays::toString), "packet " + index);
                assertEquals(expected.sideData().hdr10Plus().map(Arrays::toString),
                        actual.sideData().hdr10Plus().map(Arrays::toString), "packet " + index);
                assertTrue(actual.sideData().extra().isEmpty(), "packet " + index);
            }
        }

        @Test
        @DisplayName("declares AlphaMode 1 and MaxBlockAdditionID 1, and writes alpha frames as BlockAddID 1")
        void structure() {
            var output = MuxSupport.toMemory(List.of(vp8(true)), packets(false),
                    sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.webm()));
            var entry = Ebml.path(output, EbmlIds.SEGMENT, EbmlIds.TRACKS, EbmlIds.TRACK_ENTRY).getFirst();
            assertEquals(1, Ebml.unsigned(Ebml.children(output, entry, EbmlIds.MAX_BLOCK_ADDITION_ID).getFirst(), output));
            assertTrue(Ebml.children(output, entry, EbmlIds.BLOCK_ADDITION_MAPPING).isEmpty());
            var video = Ebml.children(output, entry, EbmlIds.VIDEO).getFirst();
            assertEquals(1, Ebml.unsigned(Ebml.children(output, video, EbmlIds.ALPHA_MODE).getFirst(), output));
            var groups = 0;
            var simple = 0;
            for (var cluster : Ebml.path(output, EbmlIds.SEGMENT, EbmlIds.CLUSTER)) {
                simple += Ebml.children(output, cluster, EbmlIds.SIMPLE_BLOCK).size();
                for (var group : Ebml.children(output, cluster, EbmlIds.BLOCK_GROUP)) {
                    groups++;
                    var more = Ebml.children(output, Ebml.children(output, group, EbmlIds.BLOCK_ADDITIONS).getFirst(),
                            EbmlIds.BLOCK_MORE).getFirst();
                    assertEquals(1, Ebml.unsigned(Ebml.children(output, more, EbmlIds.BLOCK_ADD_ID).getFirst(), output));
                }
            }
            assertEquals(9, groups);
            assertEquals(3, simple);
        }

        @Test
        @DisplayName("alpha frames and HDR10+ messages share the BlockAdditions, under the mapping of the HDR10+ id")
        void withHdr10Plus() {
            var output = MuxSupport.toMemory(List.of(vp8(true)), packets(true),
                    sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.webm()));
            var entry = Ebml.path(output, EbmlIds.SEGMENT, EbmlIds.TRACKS, EbmlIds.TRACK_ENTRY).getFirst();
            assertEquals(List.of(4L), Ebml.children(output, entry, EbmlIds.MAX_BLOCK_ADDITION_ID).stream()
                    .map(element -> Ebml.unsigned(element, output)).toList());
            var first = Ebml.children(output, Ebml.path(output, EbmlIds.SEGMENT, EbmlIds.CLUSTER).getFirst(),
                    EbmlIds.BLOCK_GROUP).getFirst();
            var ids = Ebml.children(output, Ebml.children(output, first, EbmlIds.BLOCK_ADDITIONS).getFirst(),
                    EbmlIds.BLOCK_MORE).stream()
                    .map(more -> Ebml.unsigned(Ebml.children(output, more, EbmlIds.BLOCK_ADD_ID).getFirst(), output))
                    .toList();
            assertEquals(List.of(1L, 4L), ids);
        }

        @Test
        @DisplayName("a track without alpha writes no AlphaMode and drops the alpha frames of its packets")
        void opaqueTrack() {
            var output = MuxSupport.toMemory(List.of(vp8(false)), packets(false),
                    sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.webm()));
            var entry = Ebml.path(output, EbmlIds.SEGMENT, EbmlIds.TRACKS, EbmlIds.TRACK_ENTRY).getFirst();
            assertTrue(Ebml.children(output, entry, EbmlIds.MAX_BLOCK_ADDITION_ID).isEmpty());
            var video = Ebml.children(output, entry, EbmlIds.VIDEO).getFirst();
            assertTrue(Ebml.children(output, video, EbmlIds.ALPHA_MODE).isEmpty());
            var demuxed = MuxSupport.demux(output);
            assertFalse(videoTrack(demuxed.tracks()).hasAlpha());
            assertTrue(demuxed.order().stream().allMatch(packet -> packet.sideData().isEmpty()));
        }

        @Test
        @DisplayName("ffprobe reads the alpha mode of a written track")
        void ffprobe() {
            Oracle.assume(Oracle.Tool.FFPROBE);
            var output = MuxSupport.toMemory(List.of(vp8(true)), packets(false),
                    sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.webm()));
            var file = MuxSupport.save("alpha-mode.webm", output);
            var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                    "-show_entries", "stream_tags=alpha_mode", "-of", "default=nw=1", file.toString())),
                    StandardCharsets.UTF_8);
            assertTrue(probe.contains("TAG:alpha_mode=1"), probe);
        }
    }

    @Nested
    @DisplayName("demuxing")
    class Demuxing {
        @Test
        @DisplayName("BlockAdditional 1 is the alpha frame only on a track whose AlphaMode is 1")
        void alphaModeGates() {
            var frame = new byte[]{1, 2, 3, 4};
            var alpha = new byte[]{5, 6, 7};
            var opaque = new byte[]{8, 9};
            var data = file("webm", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                    master(EbmlIds.TRACKS,
                            master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1), uint(EbmlIds.TRACK_UID, 1),
                                    uint(EbmlIds.TRACK_TYPE, 1), string(EbmlIds.CODEC_ID, "V_VP9"),
                                    master(EbmlIds.VIDEO, uint(EbmlIds.PIXEL_WIDTH, 16), uint(EbmlIds.PIXEL_HEIGHT, 16),
                                            uint(EbmlIds.ALPHA_MODE, 1))),
                            master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 2), uint(EbmlIds.TRACK_UID, 2),
                                    uint(EbmlIds.TRACK_TYPE, 1), string(EbmlIds.CODEC_ID, "V_VP9"),
                                    master(EbmlIds.VIDEO, uint(EbmlIds.PIXEL_WIDTH, 16), uint(EbmlIds.PIXEL_HEIGHT, 16),
                                            uint(EbmlIds.ALPHA_MODE, 2)))),
                    master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0),
                            master(EbmlIds.BLOCK_GROUP, binary(EbmlIds.BLOCK, block(1, 0, 0, frame)),
                                    master(EbmlIds.BLOCK_ADDITIONS, master(EbmlIds.BLOCK_MORE,
                                            uint(EbmlIds.BLOCK_ADD_ID, 1), binary(EbmlIds.BLOCK_ADDITIONAL, alpha)))),
                            master(EbmlIds.BLOCK_GROUP, binary(EbmlIds.BLOCK, block(2, 0, 0, frame)),
                                    master(EbmlIds.BLOCK_ADDITIONS, master(EbmlIds.BLOCK_MORE,
                                            binary(EbmlIds.BLOCK_ADDITIONAL, opaque)))),
                            binary(EbmlIds.SIMPLE_BLOCK, block(1, 40, 0x80, frame))));
            var demuxed = MuxSupport.demux(data);
            var tracks = demuxed.tracks();
            assertTrue(((VideoTrack) tracks.get(0)).hasAlpha());
            assertFalse(((VideoTrack) tracks.get(1)).hasAlpha());
            var packets = demuxed.order();
            assertArrayEquals(alpha, packets.get(0).sideData().alphaFrame().orElseThrow());
            assertTrue(packets.get(0).sideData().extra().isEmpty());
            assertTrue(packets.get(1).sideData().alphaFrame().isEmpty());
            assertArrayEquals(opaque, packets.get(1).sideData().extra("matroska:BlockAdditional:1").orElseThrow());
            assertTrue(packets.get(2).sideData().isEmpty());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"vp8-alpha.webm", "vp9-alpha.webm"})
        @DisplayName("the files ffmpeg writes with libvpx carry an alpha frame in every block")
        void ffmpegFiles(String name) throws IOException {
            var demuxed = MuxSupport.demux(Files.readAllBytes(SAMPLES.resolve(name)));
            var track = videoTrack(demuxed.tracks());
            assertTrue(track.hasAlpha());
            assertEquals(10, demuxed.order().size());
            for (var packet : demuxed.order()) {
                assertTrue(packet.sideData().alphaFrame().orElseThrow().length > 0);
                assertTrue(packet.sideData().extra().isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {
        private static final int WIDTH = 16;
        private static final int HEIGHT = 8;

        private static byte[] rawFrame(Random random, byte[] luma) {
            var frame = new byte[WIDTH * HEIGHT * 3 / 2];
            random.nextBytes(frame);
            if (luma != null) {
                System.arraycopy(luma, 0, frame, 0, luma.length);
            }
            return frame;
        }

        @Test
        @DisplayName("VideoReader returns pictures whose alpha plane is the luma of each block's alpha frame")
        void rawVideo() {
            var random = new Random(11);
            var colors = new ArrayList<byte[]>();
            var alphas = new ArrayList<byte[]>();
            var blocks = new ArrayList<EbmlWriter.Node>();
            blocks.add(uint(EbmlIds.TIMESTAMP, 0));
            for (var index = 0; index < 6; index++) {
                var color = rawFrame(random, null);
                byte[] alpha = null;
                if (index == 2) {
                    blocks.add(binary(EbmlIds.SIMPLE_BLOCK, block(1, index * 100, 0x80, color)));
                } else {
                    alpha = new byte[WIDTH * HEIGHT];
                    random.nextBytes(alpha);
                    blocks.add(master(EbmlIds.BLOCK_GROUP, binary(EbmlIds.BLOCK, block(1, index * 100, 0, color)),
                            master(EbmlIds.BLOCK_ADDITIONS, master(EbmlIds.BLOCK_MORE, uint(EbmlIds.BLOCK_ADD_ID, 1),
                                    binary(EbmlIds.BLOCK_ADDITIONAL, rawFrame(random, alpha))))));
                }
                colors.add(color);
                alphas.add(alpha);
            }
            var output = file("matroska", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                    master(EbmlIds.TRACKS, master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1),
                            uint(EbmlIds.TRACK_UID, 1), uint(EbmlIds.TRACK_TYPE, 1),
                            string(EbmlIds.CODEC_ID, "V_UNCOMPRESSED"),
                            master(EbmlIds.VIDEO, uint(EbmlIds.PIXEL_WIDTH, WIDTH), uint(EbmlIds.PIXEL_HEIGHT, HEIGHT),
                                    binary(EbmlIds.COLOUR_SPACE, "I420".getBytes(StandardCharsets.US_ASCII)),
                                    uint(EbmlIds.ALPHA_MODE, 1)))),
                    master(EbmlIds.CLUSTER, blocks));
            try (var reader = VideoReader.open(MediaSource.of(output))) {
                assertEquals(Codec.RAW_VIDEO, reader.track().codec());
                assertTrue(reader.track().hasAlpha());
                for (var index = 0; index < 6; index++) {
                    var frame = reader.read();
                    var picture = (YuvPicture) frame.picture();
                    assertTrue(picture.hasAlpha(), "frame " + index);
                    var alpha = new byte[WIDTH * HEIGHT];
                    var luma = new byte[WIDTH * HEIGHT];
                    for (var row = 0; row < HEIGHT; row++) {
                        System.arraycopy(picture.alpha(), row * picture.alphaStride(), alpha, row * WIDTH, WIDTH);
                        System.arraycopy(picture.y(), row * picture.yStride(), luma, row * WIDTH, WIDTH);
                    }
                    var expected = alphas.get(index);
                    if (expected == null) {
                        expected = new byte[WIDTH * HEIGHT];
                        Arrays.fill(expected, (byte) 255);
                    }
                    assertArrayEquals(expected, alpha, "frame " + index);
                    assertArrayEquals(Arrays.copyOf(colors.get(index), WIDTH * HEIGHT), luma, "frame " + index);
                }
                assertEquals(null, reader.read());
                reader.seek(300_000);
                var picture = (YuvPicture) reader.read().picture();
                assertEquals(alphas.get(3)[5], picture.alpha()[5]);
            }
        }
    }

    /** Walks the EBML elements of a file, enough to inspect the elements the muxer writes. */
    private static final class Ebml {
        /** An element: its identifier and its data range in the file. */
        record Element(long id, int start, int end) {
        }

        static List<Element> children(byte[] data, Element parent, long id) {
            return children(data, parent.start(), parent.end()).stream().filter(element -> element.id() == id).toList();
        }

        static List<Element> path(byte[] data, long... ids) {
            var level = children(data, 0, data.length);
            List<Element> found = List.of();
            for (var depth = 0; depth < ids.length; depth++) {
                var id = ids[depth];
                found = level.stream().filter(element -> element.id() == id).toList();
                if (depth + 1 < ids.length) {
                    var next = new ArrayList<Element>();
                    for (var element : found) {
                        next.addAll(children(data, element.start(), element.end()));
                    }
                    level = next;
                }
            }
            return found;
        }

        static long unsigned(Element element, byte[] data) {
            var value = 0L;
            for (var index = element.start(); index < element.end(); index++) {
                value = (value << 8) | (data[index] & 0xFF);
            }
            return value;
        }

        private static List<Element> children(byte[] data, int from, int to) {
            var result = new ArrayList<Element>();
            var position = from;
            while (position < to) {
                var idLength = Integer.numberOfLeadingZeros(data[position] & 0xFF) - 23;
                var id = 0L;
                for (var index = 0; index < idLength; index++) {
                    id = (id << 8) | (data[position++] & 0xFF);
                }
                var first = data[position++] & 0xFF;
                var sizeLength = Integer.numberOfLeadingZeros(first) - 23;
                var size = (long) (first & (0xFF >> sizeLength));
                var unknown = size == (0xFF >> sizeLength);
                for (var index = 1; index < sizeLength; index++) {
                    size = (size << 8) | (data[position++] & 0xFF);
                    unknown &= (data[position - 1] & 0xFF) == 0xFF;
                }
                var end = unknown ? to : (int) Math.min(to, position + size);
                result.add(new Element(id, position, end));
                position = end;
            }
            return result;
        }
    }
}
