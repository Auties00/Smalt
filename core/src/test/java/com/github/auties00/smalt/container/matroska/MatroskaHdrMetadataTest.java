package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.HdrFixtures;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the HDR metadata {@link MatroskaMuxer} writes: the chroma siting, {@code MaxCLL},
 * {@code MaxFALL} and {@code MasteringMetadata} of the {@code Colour} element, and HDR10+
 * messages as {@code BlockAdditions} with the track's {@code BlockAdditionMapping}. Each is read
 * back by {@link MatroskaDemuxer} and by ffprobe, and the HDR10+ messages survive an ffmpeg copy,
 * which parses and serializes them again.
 */
@DisplayName("Matroska HDR metadata")
class MatroskaHdrMetadataTest {
    private static final int SEGMENT = 0x18538067;
    private static final int TRACKS = 0x1654AE6B;
    private static final int TRACK_ENTRY = 0xAE;
    private static final int VIDEO = 0xE0;
    private static final int CLUSTER = 0x1F43B675;

    private static ContainerProbe.Demuxed hevc() {
        return hevc(ChromaSiting.UNSPECIFIED);
    }

    // the stream's VUI agrees with the container, since ffprobe reports what the decoder finds
    private static ContainerProbe.Demuxed hevc(ChromaSiting siting) {
        var location = siting.chromaSampleLocType().isPresent() ? ":chromaloc=" + siting.chromaSampleLocType().getAsInt() : "";
        return MuxSupport.demux(Fixtures.read(Fixtures.testVideo("mp4", 160, 120, 1, List.of("-c:v", "libx265",
                "-x265-params", "log-level=none:keyint=10:bframes=3:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc"
                        + location, "-pix_fmt", "yuv420p10le", "-c:a", "aac"))));
    }

    private static ContainerProbe.Demuxed vp9() {
        return MuxSupport.demux(Fixtures.read(Fixtures.testVideo("webm", 160, 120, 1, List.of("-c:v", "libvpx-vp9",
                "-deadline", "realtime", "-cpu-used", "8", "-g", "10", "-c:a", "libopus"))));
    }

    private static VideoTrack video(ContainerProbe.Demuxed demuxed) {
        return demuxed.tracks().stream().filter(VideoTrack.class::isInstance).map(VideoTrack.class::cast).findFirst().orElseThrow();
    }

    private static Track audio(ContainerProbe.Demuxed demuxed) {
        return demuxed.tracks().stream().filter(track -> track.kind() == TrackKind.AUDIO).findFirst().orElseThrow();
    }

    private static byte[] mux(List<Track> tracks, List<Packet> packets, MatroskaMuxerOptions options) {
        return MuxSupport.toMemory(tracks, packets, sink -> new MatroskaMuxer(sink, options));
    }

    @ParameterizedTest
    @EnumSource(ChromaSiting.class)
    @DisplayName("writes the chroma siting, light level and mastering display that the demuxer and ffprobe read")
    void colour(ChromaSiting siting) {
        var source = hevc(siting);
        var color = HdrFixtures.hdr10(siting);
        var output = mux(HdrFixtures.withColor(source.tracks(), color), source.order(), MatroskaMuxerOptions.matroska());
        MatroskaStructure.check(output);
        var bottom = siting == ChromaSiting.BOTTOM || siting == ChromaSiting.BOTTOM_LEFT;
        assertEquals(bottom ? HdrFixtures.hdr10(ChromaSiting.UNSPECIFIED) : color, video(MuxSupport.demux(output)).color().orElseThrow());

        var colour = EbmlTree.child(EbmlTree.child(videoEntry(output), VIDEO), EbmlIds.COLOR);
        var horizontal = switch (siting) {
            case LEFT, TOP_LEFT, BOTTOM_LEFT -> 1L;
            case CENTER, TOP, BOTTOM -> 2L;
            case UNSPECIFIED -> -1L;
        };
        var vertical = switch (siting) {
            case TOP_LEFT, TOP -> 1L;
            case LEFT, CENTER -> 2L;
            default -> -1L;
        };
        assertEquals(horizontal, EbmlTree.unsigned(colour, EbmlIds.CHROMA_SITING_HORZ));
        assertEquals(vertical, EbmlTree.unsigned(colour, EbmlIds.CHROMA_SITING_VERT));
        assertEquals(1000, EbmlTree.unsigned(colour, EbmlIds.MAX_CLL));
        assertEquals(400, EbmlTree.unsigned(colour, EbmlIds.MAX_FALL));
        var mastering = EbmlTree.child(colour, EbmlIds.MASTERING_METADATA);
        assertEquals(0.68, EbmlTree.floating(mastering, EbmlIds.PRIMARY_R_CHROMATICITY_X));
        assertEquals(0.69, EbmlTree.floating(mastering, EbmlIds.PRIMARY_G_CHROMATICITY_Y));
        assertEquals(0.0001, EbmlTree.floating(mastering, EbmlIds.LUMINANCE_MIN));

        var file = MuxSupport.save("hdr-siting-" + siting + ".mkv", output);
        var probe = HdrFixtures.ffprobe(file, "-show_streams", "-select_streams", "v");
        HdrFixtures.assertProbedHdr10(probe);
        assertTrue(probe.contains("\"color_transfer\": \"smpte2084\""), probe);
        assertTrue(probe.contains("\"color_primaries\": \"bt2020\""), probe);
        var location = switch (siting) {
            case LEFT -> "left";
            case CENTER -> "center";
            case TOP_LEFT -> "topleft";
            case TOP -> "top";
            default -> null;
        };
        if (location != null) {
            assertTrue(probe.contains("\"chroma_location\": \"" + location + "\""), probe);
        }
    }

    @Test
    @DisplayName("writes only the color description of a track without HDR metadata")
    void sdr() {
        var source = vp9();
        var output = mux(HdrFixtures.withColor(source.tracks(), ColorInfo.BT709_LIMITED), source.order(), MatroskaMuxerOptions.webm());
        var entry = videoEntry(output);
        var colour = EbmlTree.child(EbmlTree.child(entry, VIDEO), EbmlIds.COLOR);
        assertEquals(List.of(EbmlIds.MATRIX_COEFFICIENTS, EbmlIds.RANGE, EbmlIds.TRANSFER_CHARACTERISTICS, EbmlIds.PRIMARIES),
                EbmlTree.ids(colour));
        assertEquals(-1, EbmlTree.unsigned(entry, EbmlIds.MAX_BLOCK_ADDITION_ID));
        assertEquals(ColorInfo.BT709_LIMITED, video(MuxSupport.demux(output)).color().orElseThrow());
    }

    @Test
    @DisplayName("writes HDR10+ messages as BlockAdditions that the demuxer, ffprobe and an ffmpeg copy read")
    void hdr10Plus() {
        var source = hevc(ChromaSiting.TOP_LEFT);
        var videoId = video(source).id();
        var tracks = HdrFixtures.withColor(source.tracks(), HdrFixtures.hdr10(ChromaSiting.TOP_LEFT));
        var packets = HdrFixtures.withHdr10Plus(source.order(), packet -> packet.trackId() == videoId);
        var output = mux(tracks, packets, MatroskaMuxerOptions.matroska());
        MatroskaStructure.check(output);

        var entry = videoEntry(output);
        assertEquals(4, EbmlTree.unsigned(entry, EbmlIds.MAX_BLOCK_ADDITION_ID));
        var mapping = EbmlTree.child(entry, EbmlIds.BLOCK_ADDITION_MAPPING);
        assertEquals(4, EbmlTree.unsigned(mapping, EbmlIds.BLOCK_ADD_ID_VALUE));
        assertEquals(4, EbmlTree.unsigned(mapping, EbmlIds.BLOCK_ADD_ID_TYPE));

        var expected = byTrack(packets);
        var result = MuxSupport.demux(output);
        var written = result.packetsOf(video(result));
        var sent = expected.get(videoId);
        assertEquals(sent.size(), written.size());
        var nonKey = 0;
        for (var index = 0; index < sent.size(); index++) {
            assertArrayEquals(sent.get(index).data(), written.get(index).data());
            assertEquals(sent.get(index).keyframe(), written.get(index).keyframe(), "key frame flag of " + index);
            assertArrayEquals(sent.get(index).sideData().hdr10Plus().orElseThrow(), written.get(index).sideData().hdr10Plus().orElseThrow());
            nonKey += sent.get(index).keyframe() ? 0 : 1;
        }
        assertTrue(nonKey > 0, "the fixture has frames that are not key frames");
        assertEquals(nonKey, assertReferenceBlocks(output, (int) EbmlTree.unsigned(entry, EbmlIds.TRACK_NUMBER)));
        for (var packet : result.packetsOf(audio(result))) {
            assertTrue(packet.sideData().hdr10Plus().isEmpty());
        }
        MuxSupport.assertFfmpegClean("hdr10plus-clean.mkv", output);

        var file = MuxSupport.save("hdr10plus.mkv", output);
        var probe = HdrFixtures.ffprobe(file, "-show_packets", "-select_streams", "v");
        var parsed = Pattern.compile("\"side_data_type\": \"HDR10\\+ Dynamic Metadata \\(SMPTE 2094-40\\)\"").matcher(probe).results().count();
        assertEquals(sent.size(), parsed, probe.length() > 4000 ? probe.substring(0, 4000) : probe);
        assertEquals(400, HdrFixtures.rational(probe, "targeted_system_display_maximum_luminance"));
        // ffprobe lists the three maxscl of each packet in file order; the red one identifies the message
        var maxscl = Pattern.compile("\"maxscl\": \"(\\d+)/100000\"").matcher(probe).results()
                .map(match -> Integer.parseInt(match.group(1))).toList();
        assertEquals(3 * sent.size(), maxscl.size());
        for (var index = 0; index < sent.size(); index++) {
            assertEquals(HdrFixtures.maxsclRed(index), maxscl.get(3 * index), "maxscl of packet " + index);
        }

        // ffmpeg parses each message and serializes it again into its own BlockAdditions
        var ffmpegFile = read(HdrFixtures.ffmpegCopy(file, "hdr10plus-ffmpeg.mkv"));
        var ffmpegEntry = videoEntry(ffmpegFile);
        assertEquals(4, EbmlTree.unsigned(ffmpegEntry, EbmlIds.MAX_BLOCK_ADDITION_ID));
        var ffmpegMapping = EbmlTree.child(ffmpegEntry, EbmlIds.BLOCK_ADDITION_MAPPING);
        assertEquals(4, EbmlTree.unsigned(ffmpegMapping, EbmlIds.BLOCK_ADD_ID_VALUE));
        assertEquals(4, EbmlTree.unsigned(ffmpegMapping, EbmlIds.BLOCK_ADD_ID_TYPE));
        var copy = MuxSupport.demux(ffmpegFile);
        var copied = copy.packetsOf(video(copy));
        assertEquals(sent.size(), copied.size());
        for (var index = 0; index < sent.size(); index++) {
            assertArrayEquals(sent.get(index).sideData().hdr10Plus().orElseThrow(), copied.get(index).sideData().hdr10Plus().orElseThrow(),
                    "message of packet " + index);
        }
        assertEquals(HdrFixtures.hdr10(ChromaSiting.TOP_LEFT), video(copy).color().orElseThrow());
    }

    @Test
    @DisplayName("ignores HDR10+ messages on audio packets")
    void audioIgnored() {
        var source = hevc();
        var audioId = audio(source).id();
        var tracks = HdrFixtures.withColor(source.tracks(), HdrFixtures.hdr10(ChromaSiting.LEFT));
        var plain = mux(tracks, source.order(), MatroskaMuxerOptions.matroska());
        var tagged = mux(tracks, HdrFixtures.withHdr10Plus(source.order(), packet -> packet.trackId() == audioId),
                MatroskaMuxerOptions.matroska());
        assertArrayEquals(plain, tagged);
    }

    @Test
    @DisplayName("declares the HDR10+ mapping for PQ video even before a message is queued")
    void pqMapping() {
        var source = vp9();
        var pq = mux(HdrFixtures.withColor(source.tracks(), HdrFixtures.hdr10(ChromaSiting.LEFT)), source.order(),
                MatroskaMuxerOptions.webm());
        assertEquals(4, EbmlTree.unsigned(videoEntry(pq), EbmlIds.MAX_BLOCK_ADDITION_ID));
        var sdr = mux(source.tracks(), source.order(), MatroskaMuxerOptions.webm());
        assertEquals(-1, EbmlTree.unsigned(videoEntry(sdr), EbmlIds.MAX_BLOCK_ADDITION_ID));
        assertEquals(0, countBlockGroups(pq));
    }

    @Test
    @DisplayName("writes HDR10+ messages in live WebM, block by block, whatever the sink")
    void live() {
        var source = vp9();
        var videoId = video(source).id();
        var counter = new int[1];
        // every third video packet after the first carries a message, so none is queued when the header is written
        var packets = HdrFixtures.withHdr10Plus(source.order(), packet -> packet.trackId() == videoId && counter[0]++ % 3 == 1);
        var viaMemory = MuxSupport.toMemory(source.tracks(), packets, sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.liveWebm()));
        var viaStream = MuxSupport.toStream(source.tracks(), packets, sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.liveWebm()));
        assertArrayEquals(viaMemory, viaStream);
        assertTrue(MatroskaStructure.check(viaStream).unknownClusters);
        assertEquals(-1, EbmlTree.unsigned(videoEntry(viaStream), EbmlIds.MAX_BLOCK_ADDITION_ID));

        var sent = byTrack(packets).get(videoId);
        var result = MuxSupport.demux(viaStream);
        var written = result.packetsOf(video(result));
        assertEquals(sent.size(), written.size());
        var tagged = 0;
        for (var index = 0; index < sent.size(); index++) {
            assertEquals(sent.get(index).keyframe(), written.get(index).keyframe(), "key frame flag of " + index);
            var message = sent.get(index).sideData().hdr10Plus();
            assertEquals(message.isPresent(), written.get(index).sideData().hdr10Plus().isPresent());
            if (message.isPresent()) {
                tagged++;
                assertArrayEquals(message.get(), written.get(index).sideData().hdr10Plus().get());
            }
        }
        assertTrue(tagged > 5);
        assertEquals(tagged, countBlockGroups(viaStream));
        var nonKey = (int) java.util.stream.IntStream.range(0, sent.size())
                .filter(index -> !sent.get(index).keyframe() && sent.get(index).sideData().hdr10Plus().isPresent()).count();
        assertTrue(nonKey > 0);
        assertEquals(nonKey, assertReferenceBlocks(viaStream, (int) EbmlTree.unsigned(videoEntry(viaStream), EbmlIds.TRACK_NUMBER)));
        MuxSupport.assertFfmpegClean("hdr10plus-live.webm", viaStream);
    }

    @Test
    @DisplayName("keeps an Opus DiscardPadding next to the BlockGroup of the video's HDR10+ messages")
    void withDiscardPadding() {
        var source = vp9();
        var videoId = video(source).id();
        var audioTrack = audio(source);
        var last = source.packetsOf(audioTrack).getLast();
        var packets = new ArrayList<Packet>();
        for (var packet : HdrFixtures.withHdr10Plus(source.order(), packet -> packet.trackId() == videoId)) {
            packets.add(packet == last ? packet.withTiming(packet.pts(), packet.dts(), packet.duration() / 2) : packet);
        }
        var output = mux(source.tracks(), packets, MatroskaMuxerOptions.webm());
        MatroskaStructure.check(output);
        var result = MuxSupport.demux(output);
        assertTrue(result.packetsOf(audio(result)).getLast().sideData().discardEnd() > 0);
        assertTrue(result.packetsOf(audio(result)).getLast().sideData().hdr10Plus().isEmpty());
        assertTrue(result.packetsOf(video(result)).stream().allMatch(packet -> packet.sideData().hdr10Plus().isPresent()));
    }

    @Test
    @DisplayName("writes empty, truncated and large messages verbatim, in a valid structure")
    void arbitraryPayloads() {
        var source = vp9();
        var videoId = video(source).id();
        var random = new java.util.Random(7);
        var large = HdrFixtures.hdr10Plus(0);
        large = java.util.Arrays.copyOf(large, 70_000);
        for (var index = 64; index < large.length; index++) {
            large[index] = (byte) random.nextInt();
        }
        var payloads = List.of(new byte[0], java.util.Arrays.copyOf(HdrFixtures.hdr10Plus(1), 5), large);
        var packets = new ArrayList<Packet>();
        var count = 0;
        for (var packet : source.order()) {
            if (packet.trackId() == videoId) {
                var side = PacketSideData.builder().hdr10Plus(payloads.get(count++ % payloads.size())).build();
                packet = packet.withSideData(side);
            }
            packets.add(packet);
        }
        for (var options : List.of(MatroskaMuxerOptions.webm(), MatroskaMuxerOptions.liveWebm())) {
            var output = mux(source.tracks(), packets, options);
            MatroskaStructure.check(output);
            var result = MuxSupport.demux(output);
            var written = result.packetsOf(video(result));
            for (var index = 0; index < written.size(); index++) {
                var expected = payloads.get(index % payloads.size());
                var side = written.get(index).sideData();
                // the demuxer only recognizes a message by its HDR10+ T.35 header
                var actual = expected.length >= 6 ? side.hdr10Plus().orElseThrow() : side.extra("matroska:BlockAdditional:4").orElseThrow();
                assertArrayEquals(expected, actual, "payload of packet " + index);
            }
        }
    }

    private static Map<Integer, List<Packet>> byTrack(List<Packet> packets) {
        var map = new HashMap<Integer, List<Packet>>();
        for (var packet : packets) {
            map.computeIfAbsent(packet.trackId(), key -> new ArrayList<>()).add(packet);
        }
        return map;
    }

    private static EbmlTree.Element videoEntry(byte[] file) {
        var segment = EbmlTree.child(EbmlTree.root(file), SEGMENT);
        for (var entry : EbmlTree.children(EbmlTree.child(segment, TRACKS), TRACK_ENTRY)) {
            if (EbmlTree.unsigned(entry, EbmlIds.TRACK_TYPE) == 1) {
                return entry;
            }
        }
        throw new AssertionError("no video track entry");
    }

    private static int countBlockGroups(byte[] file) {
        var segment = EbmlTree.child(EbmlTree.root(file), SEGMENT);
        var count = 0;
        for (var cluster : EbmlTree.children(segment, CLUSTER)) {
            for (var group : EbmlTree.children(cluster, EbmlIds.BLOCK_GROUP)) {
                count += EbmlTree.children(group, EbmlIds.BLOCK_ADDITIONS).isEmpty() ? 0 : 1;
            }
        }
        return count;
    }

    // checks that each ReferenceBlock of a track points at the track's previous block; returns their count
    private static int assertReferenceBlocks(byte[] file, int trackNumber) {
        var segment = EbmlTree.child(EbmlTree.root(file), SEGMENT);
        var previous = 0L;
        var references = 0;
        var blocks = 0;
        for (var cluster : EbmlTree.children(segment, CLUSTER)) {
            var clusterTicks = EbmlTree.unsigned(cluster, EbmlIds.TIMESTAMP);
            for (var element : EbmlTree.children(cluster)) {
                EbmlTree.Element block;
                if (element.id() == EbmlIds.SIMPLE_BLOCK) {
                    block = element;
                } else if (element.id() == EbmlIds.BLOCK_GROUP) {
                    block = EbmlTree.child(element, EbmlIds.BLOCK);
                } else {
                    continue;
                }
                var data = block.data();
                if ((data[block.start()] & 0x7F) != trackNumber) {
                    continue;
                }
                var ticks = clusterTicks + (short) (((data[block.start() + 1] & 0xFF) << 8) | (data[block.start() + 2] & 0xFF));
                if (element.id() == EbmlIds.BLOCK_GROUP) {
                    var reference = EbmlTree.children(element, EbmlIds.REFERENCE_BLOCK);
                    // the first block of the fixture is a key frame, so a reference always has a predecessor
                    if (!reference.isEmpty()) {
                        assertTrue(blocks > 0, "the first block has a ReferenceBlock");
                        var expected = previous == ticks ? -1 : previous - ticks;
                        assertEquals(expected, EbmlTree.signed(reference.getFirst()), "ReferenceBlock of the block at " + ticks);
                        references++;
                    }
                }
                previous = ticks;
                blocks++;
            }
        }
        return references;
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** A minimal EBML walker over whole files; unknown sizes extend to the end of the parent. */
    private static final class EbmlTree {
        record Element(byte[] data, int id, int start, int end) {
        }

        static Element root(byte[] data) {
            return new Element(data, 0, 0, data.length);
        }

        static List<Element> children(Element parent) {
            var result = new ArrayList<Element>();
            var position = parent.start();
            var data = parent.data();
            while (position < parent.end()) {
                var idLength = Integer.numberOfLeadingZeros(data[position] & 0xFF) - 23;
                var id = 0;
                for (var index = 0; index < idLength; index++) {
                    id = (id << 8) | (data[position++] & 0xFF);
                }
                var first = data[position] & 0xFF;
                var sizeLength = Integer.numberOfLeadingZeros(first) - 23;
                var size = (long) (first & (0xFF >> sizeLength));
                var unknown = size == (0xFF >> sizeLength);
                position++;
                for (var index = 1; index < sizeLength; index++) {
                    size = (size << 8) | (data[position++] & 0xFF);
                    unknown &= (data[position - 1] & 0xFF) == 0xFF;
                }
                var end = unknown ? parent.end() : (int) Math.min(parent.end(), position + size);
                if (unknown && id == CLUSTER) {
                    end = nextCluster(data, position, parent.end());
                }
                result.add(new Element(data, id, position, end));
                position = end;
            }
            return result;
        }

        private static int nextCluster(byte[] data, int from, int limit) {
            for (var index = from; index + 4 <= limit; index++) {
                if ((data[index] & 0xFF) == 0x1F && (data[index + 1] & 0xFF) == 0x43 && (data[index + 2] & 0xFF) == 0xB6
                        && (data[index + 3] & 0xFF) == 0x75) {
                    return index;
                }
            }
            return limit;
        }

        static List<Element> children(Element parent, int id) {
            return children(parent).stream().filter(element -> element.id() == id).toList();
        }

        static List<Integer> ids(Element parent) {
            return children(parent).stream().map(Element::id).toList();
        }

        static Element child(Element parent, int id) {
            var found = children(parent, id);
            assertFalse(found.isEmpty(), "no element " + Integer.toHexString(id));
            return found.getFirst();
        }

        static long unsigned(Element parent, int id) {
            var found = children(parent, id);
            if (found.isEmpty()) {
                return -1;
            }
            var element = found.getFirst();
            var value = 0L;
            for (var index = element.start(); index < element.end(); index++) {
                value = (value << 8) | (element.data()[index] & 0xFF);
            }
            return value;
        }

        static long signed(Element element) {
            var value = 0L;
            for (var index = element.start(); index < element.end(); index++) {
                value = (value << 8) | (element.data()[index] & 0xFF);
            }
            var bits = 8 * (element.end() - element.start());
            return bits == 0 || bits == 64 ? value : value << (64 - bits) >> (64 - bits);
        }

        static double floating(Element parent, int id) {
            var element = child(parent, id);
            assertEquals(8, element.end() - element.start());
            var bits = 0L;
            for (var index = element.start(); index < element.end(); index++) {
                bits = (bits << 8) | (element.data()[index] & 0xFF);
            }
            return Double.longBitsToDouble(bits);
        }
    }
}
