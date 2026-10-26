package com.github.auties00.smalt.container.hls;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.packaging.PackageOutput;
import com.github.auties00.smalt.container.packaging.Packager;
import com.github.auties00.smalt.container.packaging.PackagingEncryption;
import com.github.auties00.smalt.container.packaging.PackagingOptions;
import com.github.auties00.smalt.container.packaging.Rendition;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.container.PresentationSamples;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads HLS presentations: hand-written playlists over in-memory files (attribute lists, byte ranges with implicit
 * offsets, gaps, keys, URI resolution, WebVTT segments with timestamp maps and repeated cues, malformed input) and the
 * packager's presentations, whose clear, byte-range, AES-128 and Common Encryption layouts must all give the packets
 * of the clear segmented one.
 */
@DisplayName("HLS demuxer")
class HlsDemuxerTest {
    /** Opens a presentation of in-memory files. */
    private static HlsDemuxer open(String playlist, Map<String, byte[]> files) {
        return new HlsDemuxer(MediaSource.of(playlist.getBytes(StandardCharsets.UTF_8)), options(files));
    }

    private static DemuxerOptions options(Map<String, byte[]> files) {
        DataReferenceResolver resolver = reference -> Optional.ofNullable(files.get(reference.location()))
                .map(MediaSource::of);
        return DemuxerOptions.builder().dataReferences(resolver).build();
    }

    private static List<Packet> packets(HlsDemuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("playlists")
    class Playlists {
        @Test
        @DisplayName("parse attribute lists with quoted commas, and byte ranges that continue the previous one")
        void parse() {
            var attributes = HlsPlaylist.attributes("TYPE=AUDIO,NAME=\"a, b\",DEFAULT=YES,CHANNELS=\"2\"");
            assertEquals(Map.of("TYPE", "AUDIO", "NAME", "a, b", "DEFAULT", "YES", "CHANNELS", "2"), attributes);
            var playlist = HlsPlaylist.media(HlsPlaylist.lines("""
                    #EXTM3U
                    #EXT-X-TARGETDURATION:2
                    #EXT-X-MEDIA-SEQUENCE:7
                    #EXTINF:2.0,
                    #EXT-X-BYTERANGE:100@50
                    all.ts
                    #EXTINF:1.5,
                    #EXT-X-BYTERANGE:30
                    all.ts
                    #EXT-X-GAP
                    #EXTINF:2,
                    missing.ts
                    #EXT-X-KEY:METHOD=AES-128,URI="k.bin",IV=0x1F
                    #EXTINF:2,
                    sub/next.ts
                    """), "media/index.m3u8");
            var segments = playlist.segments();
            assertEquals(3, segments.size(), "the gap is skipped");
            assertEquals(new HlsPlaylist.Segment("media/all.ts", 50, 100, 2_000_000, null, null, 7), segments.get(0));
            assertEquals(150, segments.get(1).offset(), "a range without an offset continues the previous one");
            assertEquals(1_500_000, segments.get(1).durationMicros());
            assertEquals("media/sub/next.ts", segments.get(2).uri());
            assertEquals(10, segments.get(2).sequence());
            assertArrayEquals(HexFormat.of().parseHex("0000000000000000000000000000001f"), segments.get(2).key().iv());
            assertEquals("media/k.bin", segments.get(2).key().uri());
        }

        @Test
        @DisplayName("resolve URIs against the playlist that names them")
        void resolve() {
            assertEquals("a/b.ts", HlsPlaylist.resolve("a/index.m3u8", "b.ts"));
            assertEquals("b.ts", HlsPlaylist.resolve("a/index.m3u8", "../b.ts"));
            assertEquals("../b.ts", HlsPlaylist.resolve("index.m3u8", "../b.ts"));
            assertEquals("a/c/d.ts", HlsPlaylist.resolve("a/x/index.m3u8", "../c/./d.ts"));
            assertEquals("https://cdn.example/v/s.ts", HlsPlaylist.resolve("https://cdn.example/v/i.m3u8", "s.ts"));
            assertEquals("https://other/s.ts", HlsPlaylist.resolve("v/i.m3u8", "https://other/s.ts"));
        }

        @Test
        @DisplayName("reject malformed playlists")
        void malformed() {
            var none = Map.<String, byte[]>of();
            assertThrows(MediaException.Malformed.class, () -> open("#EXTINF:1,\na.ts\n", none));
            assertThrows(MediaException.Malformed.class, () -> open("#EXTM3U\na.ts\n", none));
            assertThrows(MediaException.Malformed.class, () -> open("#EXTM3U\n#EXTINF:x,\na.ts\n", none));
            assertThrows(MediaException.Malformed.class, () -> open("#EXTM3U\n#EXTINF:1,\n#EXT-X-BYTERANGE:10\na.ts\n",
                    none));
            assertThrows(MediaException.Malformed.class, () -> open("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\","
                    + "IV=0xZZ\n#EXTINF:1,\na.ts\n", none));
            assertThrows(MediaException.Malformed.class, () -> open("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", none));
            assertThrows(MediaException.Unsupported.class, () -> open("#EXTM3U\n#EXT-X-KEY:METHOD=FOO\n#EXTINF:1,\na.ts\n",
                    none));
            assertThrows(MediaException.Malformed.class, () -> open("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n"
                    + "#EXTINF:1,\na.ts\n", Map.of("k", new byte[8], "a.ts", new byte[16])));
        }

        @Test
        @DisplayName("are recognized by their first line, and need a resolver for the files they name")
        void recognition() {
            var playlist = utf8("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\na.ts\n");
            assertEquals(ContainerFormat.HLS, Containers.detect(MediaSource.of(playlist)).orElseThrow());
            assertTrue(Containers.detect(MediaSource.of(utf8("#EXTM3Ux\n"))).isEmpty());
            var error = assertThrows(MediaException.Unsupported.class, () -> Containers.openDemuxer(
                    MediaSource.of(playlist)));
            assertTrue(error.getMessage().contains("DataReferenceResolver"), error.getMessage());
        }
    }

    @Nested
    @DisplayName("WebVTT segments")
    class WebVtt {
        @Test
        @DisplayName("move cue times by the timestamp map and keep a repeated cue once")
        void timestampMap() {
            var files = new LinkedHashMap<String, byte[]>();
            files.put("s1.vtt", utf8("WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\n\n"
                    + "00:00:01.000 --> 00:00:03.000\nspanning\n\n"));
            files.put("s2.vtt", utf8("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n"
                    + "00:00:01.000 --> 00:00:03.000\nspanning\n\n00:00:02.500 --> 00:00:03.500 line:0\nsecond\n\n"));
            files.put("s3.vtt", utf8("WEBVTT\n\n00:00:20.000 --> 00:00:21.000\nunmapped\n"));
            try (var demuxer = open("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\ns1.vtt\n#EXTINF:2,\ns2.vtt\n"
                    + "#EXTINF:2,\ns3.vtt\n#EXT-X-ENDLIST\n", files)) {
                var track = demuxer.tracks().getFirst();
                assertEquals(Codec.WEBVTT, track.codec());
                var decoder = new SubtitleDecoder(track);
                var cues = packets(demuxer).stream().map(decoder::decode).toList();
                assertEquals(3, cues.size(), cues.toString());
                // MPEGTS 900000 is ten seconds; without a map, cue time zero is timestamp zero
                assertEquals(11_000_000, cues.get(0).startMicros());
                assertEquals(13_000_000, cues.get(0).endMicros());
                assertEquals("spanning", cues.get(0).text());
                assertEquals(12_500_000, cues.get(1).startMicros());
                assertEquals("line:0", cues.get(1).settings());
                assertEquals(20_000_000, cues.get(2).startMicros());
            }
        }

        @Test
        @DisplayName("reject a malformed timestamp map")
        void malformed() {
            var files = Map.of("s.vtt", utf8("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:abc,LOCAL:00:00:00.000\n\n"));
            assertThrows(MediaException.Malformed.class, () -> open("#EXTM3U\n#EXTINF:2,\ns.vtt\n", files));
        }
    }

    @Nested
    @DisplayName("packed audio")
    class PackedAudio {
        @Test
        @DisplayName("joins ADTS segments that each open with an ID3 timestamp tag")
        void adts() throws IOException {
            var aac = Files.readAllBytes(Path.of("src", "test", "resources", "samples", "adts", "aac.aac"));
            var frames = new ArrayList<byte[]>();
            for (var at = 0; at + 7 <= aac.length; ) {
                var length = (aac[at + 3] & 0x03) << 11 | (aac[at + 4] & 0xFF) << 3 | (aac[at + 5] & 0xFF) >> 5;
                frames.add(java.util.Arrays.copyOfRange(aac, at, at + length));
                at += length;
            }
            // an ID3v2.4 tag holding the PRIV frame of the HLS packed audio timestamp
            var owner = "com.apple.streaming.transportStreamTimestamp\0".getBytes(StandardCharsets.ISO_8859_1);
            var files = new LinkedHashMap<String, byte[]>();
            var playlist = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:1\n");
            var segments = 3;
            var per = frames.size() / segments + 1;
            for (var segment = 0; segment < segments; segment++) {
                var out = new java.io.ByteArrayOutputStream();
                var frame = new byte[10 + owner.length + 8];
                System.arraycopy("PRIV".getBytes(StandardCharsets.ISO_8859_1), 0, frame, 0, 4);
                var body = owner.length + 8;
                frame[7] = (byte) body;
                System.arraycopy(owner, 0, frame, 10, owner.length);
                frame[10 + owner.length + 7] = (byte) segment;
                out.writeBytes(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, (byte) frame.length});
                out.writeBytes(frame);
                for (var index = segment * per; index < Math.min(frames.size(), (segment + 1) * per); index++) {
                    out.writeBytes(frames.get(index));
                }
                files.put("audio" + segment + ".aac", out.toByteArray());
                playlist.append("#EXTINF:1,\naudio").append(segment).append(".aac\n");
            }
            try (var demuxer = open(playlist.append("#EXT-X-ENDLIST\n").toString(), files)) {
                assertEquals(Codec.AAC, demuxer.tracks().getFirst().codec());
                var packets = packets(demuxer);
                assertEquals(frames.size(), packets.size());
                for (var index = 0; index < frames.size(); index++) {
                    var frame = frames.get(index);
                    var header = (frame[1] & 0x01) == 0 ? 9 : 7;
                    assertArrayEquals(java.util.Arrays.copyOfRange(frame, header, frame.length),
                            packets.get(index).data(), "frame " + index);
                }
            }
        }
    }

    @Nested
    @DisplayName("packaged presentations")
    class Packaged {
        private Path pack(String name, PackagingOptions options) {
            var directory = Scratch.directory("hls-demuxer").resolve(name);
            try {
                if (Files.isDirectory(directory)) {
                    try (var stale = Files.list(directory)) {
                        for (var file : stale.toList()) {
                            Files.delete(file);
                        }
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var clip = Path.of("src", "test", "resources", "samples", "mp4", "faststart.mp4");
            try (var source = MediaSource.of(clip)) {
                var result = Packager.pack(List.of(Rendition.of("clip", source)), PackageOutput.directory(directory),
                        options.toBuilder().segmentMicros(500_000).build());
                return directory.resolve(result.masterPlaylist().orElseThrow());
            }
        }

        private List<Packet> read(Path master) {
            try (var demuxer = new HlsDemuxer(MediaSource.of(master), DemuxerOptions.builder()
                    .dataReferences(DataReferenceResolver.directory(master.getParent())).build())) {
                assertEquals(ContainerFormat.HLS, demuxer.format());
                return packets(demuxer);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"hls-byte-ranges", "hls-aes", "hls-aes-sequence", "cmaf-sample-aes",
                "cmaf-sample-aes-ctr"})
        @DisplayName("give the packets of the clear presentation, decrypted with the keys the playlists name")
        void sameAsClear(String layout) {
            var key = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
            var keyId = HexFormat.of().parseHex("0f0e0d0c0b0a09080706050403020100");
            var cmaf = layout.startsWith("cmaf");
            var options = switch (layout) {
                case "hls-byte-ranges" -> PackagingOptions.hls().toBuilder().byteRanges(true).build();
                case "hls-aes" -> PackagingOptions.hls().toBuilder().encryption(PackagingEncryption.builder()
                        .method(PackagingEncryption.Method.AES_128).key(key).keyUri("k.bin").keyFileName("k.bin")
                        .initializationVector(keyId).build()).build();
                case "hls-aes-sequence" -> PackagingOptions.hls().toBuilder().encryption(PackagingEncryption
                        .forAes128(key, "k.bin").toBuilder().keyFileName("k.bin").build()).build();
                case "cmaf-sample-aes" -> PackagingOptions.hlsCmaf().toBuilder().encryption(PackagingEncryption
                        .forSampleAes(keyId, key, "k.bin").toBuilder().keyFileName("k.bin").build()).build();
                default -> PackagingOptions.hlsCmaf().toBuilder().encryption(PackagingEncryption
                        .forSampleAesCtr(keyId, key, "k.bin").toBuilder().keyFileName("k.bin").build()).build();
            };
            var clear = read(pack(cmaf ? "clear-cmaf" : "clear-ts", cmaf ? PackagingOptions.hlsCmaf()
                    : PackagingOptions.hls()));
            var other = read(pack(layout, options));
            assertEquals(clear.size(), other.size());
            for (var index = 0; index < clear.size(); index++) {
                assertEquals(clear.get(index).trackId(), other.get(index).trackId());
                assertEquals(clear.get(index).pts(), other.get(index).pts(), "packet " + index);
                assertArrayEquals(clear.get(index).data(), other.get(index).data(), "packet " + index);
            }
        }

        @Test
        @DisplayName("reject SAMPLE-AES transport stream segments, which it cannot decrypt")
        void sampleAesTransportStream() {
            var master = pack("ts-sample-aes", PackagingOptions.hls().toBuilder().encryption(PackagingEncryption
                    .forSampleAes(new byte[16], new byte[16], "k.bin").toBuilder().noKeyId().build()).build());
            assertThrows(MediaException.Unsupported.class, () -> read(master));
        }

        @Test
        @DisplayName("seek every stream to the key frame at or before the target")
        void seek() {
            var sample = PresentationSamples.of("hls-cmaf", Long.MAX_VALUE);
            var master = PresentationSamples.write(sample, Scratch.directory("hls-demuxer").resolve("seek"));
            try (var demuxer = new HlsDemuxer(MediaSource.of(master), DemuxerOptions.builder()
                    .dataReferences(DataReferenceResolver.directory(master.getParent())).build())) {
                assertTrue(demuxer.isSeekable());
                demuxer.seek(1_200_000);
                var seen = new java.util.HashSet<Integer>();
                for (var packet = demuxer.read(); packet != null && seen.size() < demuxer.tracks().size();
                     packet = demuxer.read()) {
                    if (seen.add(packet.trackId())) {
                        var track = demuxer.tracks().get(packet.trackId() - 1);
                        assertTrue(track.timeBase().toMicros(packet.pts()) <= 1_200_000 || track.kind() == TrackKind.DATA,
                                "the first packet of track " + packet.trackId() + " is at or before the target");
                        if (track.kind() == TrackKind.VIDEO) {
                            assertTrue(packet.keyframe());
                        }
                    }
                }
                assertFalse(seen.isEmpty());
            }
        }
    }
}
