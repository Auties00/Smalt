package com.github.auties00.smalt.container.hls;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.EncryptedFixtures;
import com.github.auties00.smalt.container.ts.SampleAesStreams;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads HLS media playlists of SAMPLE-AES transport stream segments: the committed clip cut into segments by the
 * transport stream muxer, each encrypted by the encryptor of {@link SampleAesStreams} (written from Apple's
 * definition) under the key and initialization vector its playlist gives it (key rotation, the media sequence number
 * as the vector, an explicit {@code IV}), must give the tracks and packets of the same segments written clear, as
 * media and as I-frame playlists; ffmpeg's HLS demuxer, which decrypts SAMPLE-AES transport streams, must read the
 * same audio packets and key frame from both.
 */
@DisplayName("HLS SAMPLE-AES transport stream segments")
class HlsSampleAesTest {
    private static final byte[] KEY_A = HexFormat.of().parseHex("8899aabbccddeeff0011223344556677");
    private static final byte[] KEY_B = HexFormat.of().parseHex("0123456789abcdeffedcba9876543210");
    private static final byte[] IV_B = HexFormat.of().parseHex("f0e1d2c3b4a5968778695a4b3c2d1e0f");
    private static final long FIRST_SEQUENCE = 5;
    private static final Path INPUT = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "container", "ts", "sample-aes", "sample-aes-input.mp4");
    private static final SampleAesStreams.Setup SETUP = new SampleAesStreams.Setup(true, 0, "zaac", null, 0, false,
            true, true);

    /** The number of segments the clip is cut into. */
    private static final int SEGMENTS = 6;

    /** Muxes the clip into one clear transport stream. */
    private static byte[] clip() {
        var sink = MediaSink.memory();
        try (var source = MediaSource.of(INPUT); var demuxer = Containers.openDemuxer(source);
             var muxer = new TsMuxer(sink)) {
            demuxer.tracks().forEach(muxer::addTrack);
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** The files of a presentation: a playlist and the files it names. */
    private record Presentation(String playlist, Map<String, byte[]> files) {
        Presentation with(String name, byte[] content) {
            var copy = new HashMap<>(files);
            copy.put(name, content);
            return new Presentation(playlist, copy);
        }
    }

    /**
     * Writes the clip cut into segments as a media playlist, clear or encrypted: the first half of the segments
     * under one key with their sequence numbers as vectors, the others under a second key with an explicit vector.
     */
    private static Presentation presentation(byte[] clip, int count, boolean encrypted, boolean iFrames,
                                             SampleAesStreams.Setup setup) {
        return presentation(clip, count, encrypted, iFrames, setup, count / 2);
    }

    /**
     * Writes the clip cut into segments as a media playlist, the segments from {@code rotation} on under the
     * second key.
     */
    private static Presentation presentation(byte[] clip, int count, boolean encrypted, boolean iFrames,
                                             SampleAesStreams.Setup setup, int rotation) {
        var keys = new byte[count][];
        var ivs = new byte[count][];
        for (var index = 0; encrypted && index < count; index++) {
            keys[index] = index < rotation ? KEY_A : KEY_B;
            ivs[index] = index < rotation ? SampleAesStreams.sequenceIv(FIRST_SEQUENCE + index) : IV_B;
        }
        var segments = SampleAesStreams.segments(clip, keys, ivs, setup);
        var files = new HashMap<String, byte[]>();
        var playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:5\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:"
                + FIRST_SEQUENCE + "\n");
        if (iFrames) {
            playlist.append("#EXT-X-I-FRAMES-ONLY\n");
        }
        for (var index = 0; index < count; index++) {
            if (encrypted && index == 0 && rotation > 0) {
                playlist.append("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"a.key\"\n");
                files.put("a.key", KEY_A);
            } else if (encrypted && index == rotation) {
                playlist.append("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"keys/b.key\",IV=0x")
                        .append(HexFormat.of().formatHex(IV_B)).append(",KEYFORMAT=\"identity\"\n");
                files.put("keys/b.key", KEY_B);
            }
            var name = "s" + index + ".ts";
            files.put(name, segments.get(index));
            playlist.append("#EXTINF:0.5,\n").append(name).append('\n');
        }
        playlist.append("#EXT-X-ENDLIST\n");
        return new Presentation(playlist.toString(), files);
    }

    private static DemuxerOptions options(Map<String, byte[]> files) {
        DataReferenceResolver resolver = reference -> Optional.ofNullable(files.get(reference.location()))
                .map(MediaSource::of);
        return DemuxerOptions.builder().dataReferences(resolver).build();
    }

    private record Dump(List<Track> tracks, List<String> packets) {
    }

    private static String line(Packet packet) {
        try {
            return packet.trackId() + " " + packet.pts() + " " + packet.dts() + " " + packet.duration() + " "
                    + packet.flags() + " " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(packet.data()));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Dump read(Presentation presentation, DemuxerOptions options) {
        try (var demuxer = new HlsDemuxer(MediaSource.of(presentation.playlist().getBytes(StandardCharsets.UTF_8)),
                options)) {
            var packets = new ArrayList<String>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(line(packet));
            }
            return new Dump(demuxer.tracks(), packets);
        }
    }

    private static Dump read(Presentation presentation) {
        return read(presentation, options(presentation.files()));
    }

    @Test
    @DisplayName("decrypt each segment with its key and vector to the clear presentation")
    void mediaPlaylist() {
        var clip = clip();
        var clear = read(presentation(clip, SEGMENTS, false, false, SETUP));
        assertEquals(4, clear.tracks().size());
        assertTrue(clear.packets().size() > 40, clear.packets().size() + " packets");
        var encrypted = presentation(clip, SEGMENTS, true, false, SETUP);
        var decrypted = read(encrypted);
        assertEquals(clear.tracks(), decrypted.tracks());
        assertEquals(clear.packets(), decrypted.packets());
        // the vector of the first key's segments is their sequence number: a playlist that names the first sequence
        // number one higher decrypts their slices with the wrong vectors, and the second key's segments as before
        var shifted = read(new Presentation(encrypted.playlist().replace("#EXT-X-MEDIA-SEQUENCE:" + FIRST_SEQUENCE,
                "#EXT-X-MEDIA-SEQUENCE:" + (FIRST_SEQUENCE + 1)), encrypted.files()));
        assertEquals(clear.packets().size(), shifted.packets().size());
        assertNotEquals(clear.packets(), shifted.packets());
        assertEquals(clear.packets().getLast(), shifted.packets().getLast());
        // a key file holding the first key in place of the second fails on the AC-3 syncframes it decrypts wrongly
        var swapped = new HashMap<>(encrypted.files());
        swapped.put("keys/b.key", KEY_A);
        assertThrows(MediaException.Malformed.class, () -> read(new Presentation(encrypted.playlist(), swapped)));
    }

    @Test
    @DisplayName("decrypt the ranges of an I-frame playlist")
    void iFramePlaylist() {
        // one range: a range of a later segment of the clip holds no key frame and no parameter sets
        var clip = clip();
        var clear = read(presentation(clip, 1, false, true, SETUP));
        assertTrue(!clear.packets().isEmpty());
        var decrypted = read(presentation(clip, 1, true, true, SETUP));
        assertEquals(clear.tracks(), decrypted.tracks());
        assertEquals(clear.packets(), decrypted.packets());
    }

    @Test
    @DisplayName("take the key from the options before the key URI, and from them alone for other key formats")
    void optionKeys() {
        var segments = SampleAesStreams.segments(clip(), new byte[][]{KEY_B, KEY_B}, new byte[][]{IV_B, IV_B}, SETUP);
        var clearSegments = SampleAesStreams.segments(clip(), new byte[2][], new byte[2][], SETUP);
        var files = new HashMap<String, byte[]>();
        var playlist = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n"
                + "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://asset\",KEYFORMAT=\"com.apple.streamingkeydelivery\",IV=0x"
                + HexFormat.of().formatHex(IV_B) + "\n");
        var clearFiles = new HashMap<String, byte[]>();
        var clearPlaylist = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n");
        for (var index = 0; index < segments.size(); index++) {
            files.put("s" + index + ".ts", segments.get(index));
            clearFiles.put("s" + index + ".ts", clearSegments.get(index));
            playlist.append("#EXTINF:0.5,\ns").append(index).append(".ts\n");
            clearPlaylist.append("#EXTINF:0.5,\ns").append(index).append(".ts\n");
        }
        var encrypted = new Presentation(playlist.toString(), files);
        var clear = read(new Presentation(clearPlaylist.toString(), clearFiles));
        var exception = assertThrows(MediaException.Unsupported.class, () -> read(encrypted));
        assertTrue(exception.getMessage().contains("com.apple.streamingkeydelivery"), exception.getMessage());
        var keyed = options(files).toBuilder().decryptionKeys(DecryptionKeys.single(KEY_B)).build();
        assertEquals(clear.packets(), read(encrypted, keyed).packets());
        // the options' key wins over an identity URI naming another key
        var identity = new Presentation(encrypted.playlist().replace(
                "URI=\"skd://asset\",KEYFORMAT=\"com.apple.streamingkeydelivery\"", "URI=\"a.key\""), files)
                .with("a.key", KEY_A);
        assertEquals(clear.packets(), read(identity, options(identity.files()).toBuilder()
                .decryptionKeys(DecryptionKeys.single(KEY_B)).build()).packets());
        assertThrows(MediaException.Malformed.class, () -> read(identity));
    }

    @Test
    @DisplayName("reject keys that are not 16 bytes, missing key files and SAMPLE-AES-CTR transport streams")
    void rejections() {
        var encrypted = presentation(clip(), 2, true, false, SETUP);
        assertThrows(MediaException.Malformed.class, () -> read(encrypted.with("a.key", new byte[15])));
        var missing = new HashMap<>(encrypted.files());
        missing.remove("a.key");
        assertThrows(MediaException.Unsupported.class, () -> read(new Presentation(encrypted.playlist(), missing)));
        var ctr = new Presentation(encrypted.playlist().replace("METHOD=SAMPLE-AES,", "METHOD=SAMPLE-AES-CTR,"),
                encrypted.files());
        assertThrows(MediaException.Unsupported.class, () -> read(ctr));
    }

    @Test
    @DisplayName("are read by ffmpeg's HLS demuxer to the packets of the clear presentation")
    void ffmpegOracle() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var clip = clip();
        // ffmpeg's H.264 parser rejects the synthetic slices, so the oracle reads the clip's own units; one key and
        // vector, since ffmpeg decrypts the units it holds at the end of a segment with the next segment's
        // ffmpeg's E-AC-3 probe rejects the dependent substream copies too
        var setup = new SampleAesStreams.Setup(false, 0, "zaac", null, 0, false, false, true);
        var directory = Scratch.directory("hls-sample-aes").resolve("oracle");
        var hashes = new LinkedHashMap<Boolean, Map<Integer, List<String>>>();
        for (var encrypted : new boolean[]{false, true}) {
            var presentation = presentation(clip, 3, encrypted, false, setup, 0);
            var target = directory.resolve(encrypted ? "encrypted" : "clear");
            Files.createDirectories(target.resolve("keys"));
            for (var file : presentation.files().entrySet()) {
                Files.write(target.resolve(file.getKey()), file.getValue());
            }
            var playlist = target.resolve("index.m3u8");
            Files.writeString(playlist, presentation.playlist());
            hashes.put(encrypted, EncryptedFixtures.ffmpegPacketHashes(playlist, null, List.of(
                    "-allowed_extensions", "ALL", "-protocol_whitelist", "file,crypto,data")));
        }
        var clear = hashes.get(false);
        var decrypted = hashes.get(true);
        assertEquals(clear.keySet(), decrypted.keySet());
        assertEquals(4, clear.size());
        for (var stream : clear.keySet()) {
            var want = clear.get(stream);
            var got = decrypted.get(stream);
            assertEquals(want.size(), got.size(), "stream " + stream);
            if (stream == 0) {
                // ffmpeg returns the slices whose clear form holds emulation prevention bytes without them (and with
                // stale bytes at their end), which its own decoder then rejects; the key frame holds none
                assertEquals(want.getFirst(), got.getFirst(), "the key frame");
                continue;
            }
            // ffmpeg flushes the packets still pending at the end of the playlist without decrypting them
            for (var index = 0; index < want.size() - 2; index++) {
                assertEquals(want.get(index), got.get(index), "stream " + stream + " packet " + index);
            }
        }
    }
}
