package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.DemuxAssertions;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the DASH-IF test content of livesim2 (github.com/Dash-Industry-Forum/livesim-content, Apache-2.0): CMAF
 * representations of AVC video at three bit rates and with CEA-608 captions or trick-mode pictures, AAC audio, and
 * TTML and IMSC1 image subtitles in 2, 6 and 8-second segments. For each representation the initialization segment
 * and the first eight media segments, joined into one fragmented MP4 file, must:
 * <ul>
 *   <li>demux packet by packet like ffprobe (video and audio: ffprobe reads neither the {@code stpp} subtitles of
 *       every representation nor their sample flags);</li>
 *   <li>span the media times of the segment list (the representation's {@code _data.json} metadata), in the media
 *       timescale it declares, starting at the first segment's decode time, or before it by no more than the
 *       largest composition offset when negative composition offsets lower the decoding times;</li>
 *   <li>give the RFC 6381 codecs string of the representation's manifest entry (video and audio);</li>
 *   <li>survive packaging into DASH: the packager's manifest declares the same codecs string, and its segments demux
 *       back to the same samples (video and audio).</li>
 * </ul>
 */
@DisplayName("DASH-IF test vectors (livesim2 content)")
class DashTestVectorsTest {
    private static final Pattern SEGMENT = Pattern.compile("\\{[^{}]*\"startTime\"[^{}]*}");
    private static final Pattern NUMBER = Pattern.compile("^\\D*(\\d+)\\.(?:m4s|cmft)$");

    @TempDir
    Path work;

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({
            "testpic_2s, V300, video", "testpic_2s, A48, audio", "testpic_2s, V300_trick, video",
            "testpic_2s, V300_with_cc1_and_cc3, video", "testpic_2s, sub_eng, text", "testpic_2s, sub_eng_cap, text",
            "testpic_2s, sub_ttml_qbb, text", "testpic_2s, S1, text", "testpic_2s, S1_two_regions_multi_color, text",
            "testpic_2s, imsc1_img_eng, text", "testpic_6s, V300, video", "testpic_6s, A48, audio",
            "testpic4_8s, V300, video", "testpic4_8s, V600, video", "testpic4_8s, V1200, video",
            "testpic4_8s, A48, audio"
    })
    @DisplayName("demux like ffprobe, at the segment list's times, with the manifest's codecs, and repackage")
    void representation(String presentation, String representation, String kind) throws IOException {
        var file = join(presentation, representation);
        var metadata = metadata(presentation, representation);
        var ours = ContainerProbe.demux(file, Mp4Demuxer::new);
        if (!kind.equals("text")) {
            DemuxAssertions.assertPacketsMatch(ours, ContainerProbe.probe(file), track -> 0, true);
        }
        DemuxAssertions.assertMonotonicDts(ours);

        assertEquals(1, ours.tracks().size(), "track count");
        var track = ours.tracks().getFirst();
        var packets = ours.packetsOf(track);
        assertFalse(packets.isEmpty());
        assertEquals(metadata.timescale(), track.timeBase().denominator(), "media timescale");
        var first = packets.getFirst();
        var last = packets.getLast();
        var reorder = packets.stream().filter(packet -> packet.pts() != Packet.NO_TIMESTAMP)
                .mapToLong(packet -> packet.pts() - packet.dts()).max().orElse(0);
        var lowered = metadata.start() - first.dts();
        assertTrue(lowered >= 0 && lowered <= reorder, "decode time of the first segment " + first.dts() + ", "
                + metadata.start() + " in the segment list");
        assertEquals(metadata.end() - metadata.start(), last.dts() + last.duration() - first.dts(),
                "span of the eight segments");
        if (kind.equals("text")) {
            return;
        }
        var key = packets.stream().filter(Packet::keyframe).findFirst().orElseThrow();
        assertEquals(metadata.codecs(), CodecStrings.of(track, key.data()), "codecs string");

        var packaged = work.resolve("packaged");
        try (var source = MediaSource.of(file)) {
            Packager.pack(List.of(Rendition.of("r", source)), PackageOutput.directory(packaged), PackagingOptions.dash());
        }
        var manifest = Files.readString(packaged.resolve(PackagingOptions.dash().manifestName()), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("codecs=\"" + metadata.codecs() + "\""), "the manifest declares "
                + metadata.codecs() + ": " + manifest);
        var repackaged = ContainerProbe.demux(joinPackaged(packaged), Mp4Demuxer::new);
        var again = repackaged.packetsOf(repackaged.tracks().getFirst());
        assertEquals(packets.size(), again.size(), "repackaged sample count");
        for (var i = 0; i < packets.size(); i++) {
            assertEquals(ContainerProbe.crc32(packets.get(i).data()), ContainerProbe.crc32(again.get(i).data()),
                    "repackaged sample " + i);
            assertEquals(packets.get(i).dts() - first.dts(), again.get(i).dts() - again.getFirst().dts(),
                    "repackaged decode time " + i);
        }
    }

    /** The segment list of a representation: media timescale, codecs and the span of its first eight segments. */
    private record Metadata(long timescale, String codecs, long start, long end) {
    }

    private static Metadata metadata(String presentation, String representation) throws IOException {
        var file = Conformance.fetchListed("dash-livesim-" + presentation + "-" + representation + "_data.json.gz");
        String json;
        try (var input = new GZIPInputStream(Files.newInputStream(file))) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var segments = new ArrayList<long[]>();
        var matcher = SEGMENT.matcher(json);
        while (matcher.find()) {
            var segment = matcher.group();
            segments.add(new long[]{field(segment, "nr"), field(segment, "startTime"), field(segment, "endTime")});
        }
        segments.sort(Comparator.comparingLong(segment -> segment[0]));
        var codecs = Pattern.compile("\"codecs\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertTrue(codecs.find(), "no codecs in the metadata");
        return new Metadata(field(json, "mediaTimescale"), codecs.group(1), segments.get(0)[1], segments.get(7)[2]);
    }

    private static long field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        assertTrue(matcher.find(), "no " + name + " in " + json.substring(0, Math.min(200, json.length())));
        return Long.parseLong(matcher.group(1));
    }

    /** Writes the initialization segment and the first eight media segments of a representation into one file. */
    private Path join(String presentation, String representation) {
        var archive = Conformance.archive("dash-livesim-" + presentation + "-" + representation);
        var members = archive.members();
        var init = members.stream().filter(member -> member.contains("/init.")).findFirst().orElseThrow();
        var media = members.stream().filter(member -> NUMBER.matcher(member.substring(member.lastIndexOf('/') + 1)).find())
                .sorted(Comparator.comparingInt(DashTestVectorsTest::number)).toList();
        assertEquals(8, media.size(), "media segments " + media);
        var out = new ByteArrayOutputStream();
        try {
            out.write(Files.readAllBytes(archive.fetch(init)));
            for (var member : media) {
                out.write(Files.readAllBytes(archive.fetch(member)));
            }
            var file = work.resolve(presentation + "-" + representation + ".mp4");
            Files.write(file, out.toByteArray());
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static int number(String member) {
        var name = member.substring(member.lastIndexOf('/') + 1);
        var matcher = NUMBER.matcher(name);
        assertTrue(matcher.find(), name);
        return Integer.parseInt(matcher.group(1));
    }

    /** Writes the packager's initialization segment and media segments, in order, into one file. */
    private Path joinPackaged(Path directory) throws IOException {
        List<Path> files;
        try (var listing = Files.list(directory)) {
            files = listing.sorted().toList();
        }
        var init = files.stream().filter(path -> path.getFileName().toString().endsWith("_init.mp4")).findFirst()
                .orElseThrow(() -> new AssertionError("no initialization segment in " + files));
        var out = new ByteArrayOutputStream();
        out.write(Files.readAllBytes(init));
        for (var file : files) {
            if (file.getFileName().toString().endsWith(".m4s")) {
                out.write(Files.readAllBytes(file));
            }
        }
        var joined = work.resolve("repackaged.mp4");
        Files.write(joined, out.toByteArray());
        return joined;
    }
}
