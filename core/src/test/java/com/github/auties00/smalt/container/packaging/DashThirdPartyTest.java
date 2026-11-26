package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.ThirdPartyDemux;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the DASH presentations of other packagers, each representation's initialization segment joined to its media
 * segments as a player appends them: DASH-IF livesim's multi-period ad insertion, low-latency chunked CMAF (several
 * {@code moof}/{@code mdat} pairs a segment) and AV1 in CMAF, livesim2's two-period live stream, and Axinom's
 * multi-key Common Encryption stream (one key for audio, one for SD and one for HD video), decrypted with its
 * published content keys and compared with the clear stream Axinom packaged from the same source. Each joined
 * representation is compared with ffprobe packet by packet; the manifests and livesim's segment data must describe
 * the segments read.
 */
@DisplayName("DASH presentations of other packagers")
class DashThirdPartyTest {
    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Joins an initialization segment and media segments into one file. */
    private static Path join(String name, List<Path> segments) {
        var out = new ByteArrayOutputStream();
        for (var segment : segments) {
            out.writeBytes(bytes(segment));
        }
        var file = Scratch.directory("dash-third-party").resolve(name + ".mp4");
        try {
            Files.write(file, out.toByteArray());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return file;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"dash-livesim-ads-train_ad-V1, V1, 5", "dash-livesim-ads-train_ad-A, A, 5",
            "dash-livesim-testpic_2s_low_delay-LD_360, LD_360, 4", "dash-livesim-testpic_2s_low_delay-LD_A48, LD_A48, 4",
            "dash-livesim-testpic_2s_av1-av1, av1, 5", "dash-livesim-testpic_2s_av1-A48, A48, 5"})
    @DisplayName("read livesim's representations as ffprobe does, and as their segment data describes them")
    void livesim(String set, String representation, int count) throws IOException {
        var archive = Conformance.archive(set);
        var segments = new ArrayList<Path>();
        segments.add(archive.member(representation + "/init.mp4"));
        for (var number = 1; number <= count; number++) {
            segments.add(archive.member(representation + "/" + number + ".m4s"));
        }
        var file = join(set, segments);
        var ours = ThirdPartyDemux.assertMatchesFfprobe(file, source -> new Mp4Demuxer(source),
                ThirdPartyDemux.Options.DEFAULT);
        // the livesim segment data: the end of each segment in the manifest's timescale, which the packets reach
        var prefix = set.substring(0, set.lastIndexOf('-'));
        var data = new String(new GZIPInputStream(Files.newInputStream(Conformance.vector(prefix + "-" + representation
                + "_data.json.gz"))).readAllBytes(), StandardCharsets.UTF_8);
        var timescale = Long.parseLong(match(data, "\"mpdTimescale\":\\s*(\\d+)"));
        var end = Pattern.compile("\"endTime\":\\s*(\\d+),\\s*\"nr\":\\s*(\\d+)").matcher(data);
        var last = -1L;
        while (end.find()) {
            if (Integer.parseInt(end.group(2)) == count) {
                last = Long.parseLong(end.group(1));
            }
        }
        assertTrue(last > 0, "segment " + count + " of the data");
        var track = ours.tracks().getFirst();
        var packets = ours.packetsOf(track);
        var lastPacket = packets.getLast();
        var endTicks = track.timeBase().rescale(lastPacket.pts() + lastPacket.duration(),
                com.github.auties00.smalt.track.Rational.timeBaseOf(timescale));
        assertTrue(Math.abs(endTicks - last) <= timescale / 10 || endTicks <= last,
                set + ": the packets end at " + endTicks + ", segment " + count + " at " + last);
        // the manifest lists the representation
        var manifest = Files.readString(Conformance.vector(set.contains("ads") ? prefix + "-manifest.mpd"
                : prefix + "-Manifest.mpd"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("id=\"" + representation + "\""), representation + " in the manifest");
    }

    private static String match(String text, String regex) {
        var matcher = Pattern.compile(regex).matcher(text);
        assertTrue(matcher.find(), regex);
        return matcher.group(1);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"A48", "V300"})
    @DisplayName("read livesim2's two-period stream across its period boundary as ffprobe does")
    void periods(String representation) throws IOException {
        var prefix = "dash-livesim2-periods_60-testpic_2s-" + representation + "-";
        var segments = new ArrayList<Path>();
        segments.add(Conformance.vector(prefix + "init.mp4"));
        for (var number = 897817497L; number <= 897817503L; number++) {
            segments.add(Conformance.vector(prefix + number + ".m4s"));
        }
        ThirdPartyDemux.assertMatchesFfprobe(join("periods-" + representation, segments),
                source -> new Mp4Demuxer(source), ThirdPartyDemux.Options.DEFAULT);
        var manifest = Files.readString(Conformance.vector("dash-livesim2-periods_60-testpic_2s-Manifest.mpd"),
                StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("<Period").matcher(manifest).results().count() >= 2, "two periods");
        assertTrue(manifest.contains("presentationTimeOffset"), "a presentationTimeOffset");
        assertTrue(manifest.contains("id=\"" + representation + "\""));
    }

    /** Returns the content keys Axinom publishes for its multi-key stream. */
    private static DecryptionKeys axinomKeys() {
        var text = new String(bytes(Conformance.vector(
                "axinom-ContentKeys-Axinom-Encoding-Dash-Hls-H264-protected-MultiKey.txt")), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("Key ID: ([0-9a-f-]{36})\\s+Content Key: (\\S+)").matcher(text);
        var keys = new java.util.LinkedHashMap<String, String>();
        var count = 0;
        while (matcher.find()) {
            keys.put(matcher.group(1).replace("-", ""), HexFormat.of().formatHex(Base64.getDecoder()
                    .decode(matcher.group(2))));
            count++;
        }
        assertEquals(3, count, "the three content keys");
        return DecryptionKeys.ofHex(keys);
    }

    private static List<Path> axinom(String stream, String representation) {
        var segments = new ArrayList<Path>();
        var prefix = "axinom-" + stream + "-" + representation;
        segments.add(Conformance.vector(prefix + "_init.mp4"));
        for (var number = 1; number <= 3; number++) {
            segments.add(Conformance.vector(prefix + "_" + number + ".m4s"));
        }
        return segments;
    }

    private static List<Packet> demux(Path file, DecryptionKeys keys) {
        try (var source = MediaSource.of(file);
             var demuxer = new Mp4Demuxer(source, DemuxerOptions.builder().decryptionKeys(keys).build())) {
            return ContainerProbe.demuxAll(demuxer).order();
        }
    }

    @Test
    @DisplayName("decrypts the 288p video of the multi-key stream to the packets of Axinom's clear stream")
    void multiKeyClearReference() {
        var decrypted = demux(join("axinom-288-cenc", axinom("MultiKey-Dash_h264_1080p_cenc", "video-H264-288-400k")),
                axinomKeys());
        var clear = demux(join("axinom-288-clear", axinom("Dash-not_protected_dash_1080p_h264",
                "video-H264-288-400k")), DecryptionKeys.none());
        assertEquals(clear.size(), decrypted.size());
        // the two packagings share the encode of their first two segments only (the third segments differ in size
        // already when packaged; ffmpeg's decryption of the third equals ours, see multiKey)
        var segments = axinom("Dash-not_protected_dash_1080p_h264", "video-H264-288-400k").subList(0, 3);
        var compared = demux(join("axinom-288-clear-2", segments), DecryptionKeys.none()).size();
        assertTrue(compared > 100, compared + " packets");
        clear = clear.subList(0, compared);
        decrypted = decrypted.subList(0, compared);
        var differing = new ArrayList<Integer>();
        for (var index = 0; index < clear.size(); index++) {
            if (!java.util.Arrays.equals(clear.get(index).data(), decrypted.get(index).data())) {
                differing.add(index);
            }
        }
        assertEquals(List.of(), differing, "packets differing from the clear stream");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"audio-en", "video-H264-288-400k", "video-H264-720-2100k", "subtitle-en"})
    @DisplayName("decrypts every representation of the multi-key stream, each with its key, as ffmpeg does")
    void multiKey(String representation) {
        var file = join("axinom-" + representation, axinom("MultiKey-Dash_h264_1080p_cenc", representation));
        var ours = demux(file, axinomKeys());
        if (representation.startsWith("subtitle")) {
            // the clear WebVTT track's samples are all empty: one 8-byte vtte box each (ISO/IEC 14496-30), which
            // carries no cue, so the demuxer returns none
            var data = ContainerProbe.packetData(file);
            assertTrue(!data.isEmpty());
            for (var sample : data) {
                assertEquals(8, sample.length);
                assertEquals("vtte", new String(sample, 4, 4, StandardCharsets.US_ASCII));
            }
            assertEquals(List.of(), ours);
        } else {
            assertTrue(!ours.isEmpty());
        }
        var manifest = new String(bytes(Conformance.vector("axinom-MultiKey-Dash_h264_1080p_cenc-manifest.mpd")),
                StandardCharsets.UTF_8);
        assertTrue(manifest.contains(representation.replace("video-", "").replace("audio-", "")
                .replace("subtitle-", "")) || manifest.contains(representation), representation + " in the manifest");
        if (representation.startsWith("subtitle")) {
            return;
        }
        // ffmpeg decrypts with one key: the key of the representation's KID, from the track encryption box
        var keyText = new String(bytes(Conformance.vector(
                "axinom-ContentKeys-Axinom-Encoding-Dash-Hls-H264-protected-MultiKey.txt")), StandardCharsets.UTF_8);
        var init = bytes(Conformance.vector("axinom-MultiKey-Dash_h264_1080p_cenc-" + representation + "_init.mp4"));
        var tenc = new String(init, StandardCharsets.ISO_8859_1).indexOf("tenc");
        var keyId = HexFormat.of().formatHex(init, tenc + 12, tenc + 28);
        var matcher = Pattern.compile("Key ID: ([0-9a-f-]{36})\\s+Content Key: (\\S+)").matcher(keyText);
        byte[] key = null;
        while (matcher.find()) {
            if (matcher.group(1).replace("-", "").equals(keyId)) {
                key = Base64.getDecoder().decode(matcher.group(2));
            }
        }
        assertTrue(key != null, "the key of KID " + keyId);
        var reference = com.github.auties00.smalt.container.EncryptedFixtures.ffmpegPacketHashes(file, key, List.of());
        assertEquals(reference.get(0), com.github.auties00.smalt.container.EncryptedFixtures.md5s(ours), representation);
    }
}
