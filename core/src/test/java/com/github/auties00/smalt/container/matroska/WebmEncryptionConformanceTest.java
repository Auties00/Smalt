package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.EncryptedFixtures;
import com.github.auties00.smalt.container.ivf.IvfMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.auties00.smalt.container.EncryptedFixtures.KEY;
import static com.github.auties00.smalt.container.EncryptedFixtures.KEY_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Decrypts the WebM encryption test files of Chromium (whole-frame and partitioned AES-CTR frames, a clear lead,
 * frames left clear) and checks that every packet equals the clear file's.
 */
@DisplayName("WebM encryption conformance")
class WebmEncryptionConformanceTest {
    private static final DecryptionKeys KEYS = DecryptionKeys.of(KEY_ID, KEY);

    private record Demuxed(List<Track> tracks, Map<Integer, List<Packet>> packets) {
    }

    private static Demuxed demux(Path file, DecryptionKeys keys) {
        try (var source = MediaSource.of(file);
             var demuxer = new MatroskaDemuxer(source, DemuxerOptions.builder().decryptionKeys(keys).build())) {
            return new Demuxed(demuxer.tracks(), EncryptedFixtures.packets(demuxer));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "bear-320x240-av_enc-av.webm, bear-320x240.webm",
            "bear-320x240-av_enc-av_clear-1s.webm, bear-320x240.webm",
            "bear-320x240-av_enc-av_clear-all.webm, bear-320x240.webm",
            "bear-320x240-v-vp9_fullsample_enc-v.webm, bear-vp9.webm",
            "bear-320x240-opus-av_enc-av.webm, bear-vp9-opus.webm",
    })
    @DisplayName("decrypts to the packets of the clear file")
    void decrypts(String encrypted, String clear) {
        var expected = demux(Conformance.fetchListed("chromium-" + clear), DecryptionKeys.none());
        var actual = demux(Conformance.fetchListed("chromium-" + encrypted), KEYS);
        assertEquals(expected.tracks().size(), actual.tracks().size(), "track count");
        for (var index = 0; index < expected.tracks().size(); index++) {
            var want = expected.tracks().get(index);
            var got = actual.tracks().get(index);
            assertEquals(want.codec(), got.codec(), encrypted + ": codec of track " + index);
            EncryptedFixtures.assertSameData(encrypted + " track " + index,
                    expected.packets().getOrDefault(want.id(), List.of()),
                    actual.packets().getOrDefault(got.id(), List.of()));
        }
    }

    @Test
    @DisplayName("decrypts the AV1 frames Shaka Packager encrypted from an MP4 file to that file's samples")
    void av1() {
        List<Packet> expected;
        try (var source = MediaSource.of(Conformance.fetchListed("chromium-bear-av1.mp4"));
             var demuxer = new Mp4Demuxer(source)) {
            expected = EncryptedFixtures.packets(demuxer).values().iterator().next();
        }
        var actual = demux(Conformance.fetchListed("chromium-bear-av1-cenc.webm"), KEYS);
        EncryptedFixtures.assertSameData("bear-av1-cenc.webm", expected, actual.packets().values().iterator().next());
    }

    @Test
    @DisplayName("decrypts partitioned VP9 frames that ffmpeg then decodes without an error")
    void partitioned() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var actual = demux(Conformance.fetchListed("chromium-bear-320x240-v-vp9_subsample_enc-v.webm"), KEYS);
        var track = actual.tracks().getFirst();
        var file = Scratch.directory("webm-encryption").resolve("partitioned.ivf");
        try (var sink = MediaSink.of(file); var muxer = new IvfMuxer(sink)) {
            muxer.addTrack(track);
            for (var packet : actual.packets().get(track.id())) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var result = Oracle.execute(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), null, List.of("-v", "error", "-xerror",
                "-i", file.toString(), "-f", "framemd5", "-"));
        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr().strip(), "ffmpeg reports decoding errors");
        var frames = new String(result.stdout(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("#"))
                .count();
        assertEquals(actual.packets().get(track.id()).size(), frames, "decoded frames");
        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("rejects an encrypted file without keys, and a key it does not know")
    void keys() {
        var file = Conformance.fetchListed("chromium-bear-320x240-av_enc-av.webm");
        assertEquals("matroska encryption", assertThrows(MediaException.Unsupported.class,
                () -> demux(file, DecryptionKeys.none())).format());
        assertEquals("matroska encryption", assertThrows(MediaException.Unsupported.class,
                () -> demux(file, DecryptionKeys.of(new byte[16], KEY))).format());
        var expected = demux(Conformance.fetchListed("chromium-bear-320x240.webm"), DecryptionKeys.none());
        var any = demux(file, DecryptionKeys.single(KEY));
        var tracks = new ArrayList<>(expected.packets().keySet());
        var actualTracks = new ArrayList<>(any.packets().keySet());
        EncryptedFixtures.assertSameData("any key ID", expected.packets().get(tracks.getFirst()),
                any.packets().get(actualTracks.getFirst()));
    }
}
