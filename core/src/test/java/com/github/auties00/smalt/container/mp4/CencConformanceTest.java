package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.EncryptedFixtures;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.github.auties00.smalt.container.EncryptedFixtures.KEY;
import static com.github.auties00.smalt.container.EncryptedFixtures.KEY_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decrypts the Common Encryption test files of Chromium (Shaka Packager output in every scheme, with sample
 * auxiliary information in {@code senc} boxes, in the {@code mdat} through {@code saiz} and {@code saio}, and with key
 * rotation through {@code seig} sample groups) and checks that every packet equals the clear file's, and that
 * ffmpeg's decryption of the same file agrees; ffmpeg also encrypts plain and fragmented files the demuxer then
 * decrypts.
 */
@DisplayName("Common Encryption conformance")
class CencConformanceTest {
    private static final DecryptionKeys KEYS = DecryptionKeys.of(KEY_ID, KEY);

    private static Map<Integer, List<Packet>> demux(Path file, DecryptionKeys keys) {
        try (var source = MediaSource.of(file);
             var demuxer = new Mp4Demuxer(source, DemuxerOptions.builder().decryptionKeys(keys).build())) {
            return EncryptedFixtures.packets(demuxer);
        }
    }

    private static List<Packet> only(Map<Integer, List<Packet>> packets) {
        assertEquals(1, packets.size(), "track count");
        return packets.values().iterator().next();
    }

    @Nested
    @DisplayName("Chromium test files")
    class Chromium {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "bear-640x360-v_frag-cenc.mp4, bear-640x360-v_frag.mp4",
                "bear-640x360-v_frag-cbc1.mp4, bear-640x360-v_frag.mp4",
                "bear-640x360-v_frag-cens.mp4, bear-640x360-v_frag.mp4",
                "bear-640x360-v_frag-cbcs.mp4, bear-640x360-v_frag.mp4",
                "bear-640x360-v_frag-cenc-mdat.mp4, bear-640x360-v_frag.mp4",
                "bear-640x360-a_frag-cenc.mp4, bear-640x360-a_frag.mp4",
                "bear-640x360-a_frag-cbcs.mp4, bear-640x360-a_frag.mp4",
                "bear-flac-cenc.mp4, bear-flac.mp4",
                "bear-opus-cenc.mp4, bear-opus.mp4",
                "bear-av1-cenc.mp4, bear-av1.mp4",
                "bear-320x240-v_frag-vp9-cenc.mp4, bear-320x240-v_frag-vp9.mp4",
        })
        @DisplayName("decrypts to the packets of the clear file")
        void decrypts(String encrypted, String clear) {
            var expected = only(demux(Conformance.fetchListed("chromium-" + clear), DecryptionKeys.none()));
            var actual = only(demux(Conformance.fetchListed("chromium-" + encrypted), KEYS));
            EncryptedFixtures.assertSameData(encrypted, expected, actual);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "bear-640x360-v_frag-cenc-key_rotation.mp4, bear-640x360-v_frag.mp4",
                "bear-640x360-a_frag-cenc-key_rotation.mp4, bear-640x360-a_frag.mp4",
        })
        @DisplayName("takes the key ID of each fragment from its seig sample group")
        void keyRotation(String encrypted, String clear) {
            var expected = only(demux(Conformance.fetchListed("chromium-" + clear), DecryptionKeys.none()));
            var file = Conformance.fetchListed("chromium-" + encrypted);
            EncryptedFixtures.assertSameData(encrypted, expected, only(demux(file, EncryptedFixtures.rotatedKeys())));
            EncryptedFixtures.assertSameData(encrypted, expected, only(demux(file, KEYS)));
            // the track encryption box names an all-zero default key ID, which only the seig groups override
            assertThrows(MediaException.Unsupported.class, () -> demux(file, DecryptionKeys.of(new byte[16], KEY)),
                    "the default key ID of the tenc box");
        }

        @ParameterizedTest(name = "{0}")
        // the senc files were encrypted from a clear file Chromium no longer has
        @ValueSource(strings = {"bear-640x360-v_frag-cenc.mp4", "bear-640x360-v_frag-cbc1.mp4",
                "bear-640x360-v_frag-cenc-senc.mp4", "bear-640x360-v_frag-cenc-senc-no-saiz-saio.mp4",
                "bear-640x360-v_frag-cenc-mdat.mp4",
                "bear-640x360-v_frag-cens.mp4", "bear-640x360-v_frag-cbcs.mp4", "bear-640x360-a_frag-cbcs.mp4",
                "bear-flac-cenc.mp4", "bear-opus-cenc.mp4", "bear-av1-cenc.mp4"})
        @DisplayName("decrypts as ffmpeg does")
        void ffmpeg(String encrypted) {
            var file = Conformance.fetchListed("chromium-" + encrypted);
            var reference = EncryptedFixtures.ffmpegPacketHashes(file, KEY, List.of());
            var actual = only(demux(file, KEYS));
            assertEquals(reference.get(0), EncryptedFixtures.md5s(actual), encrypted);
        }

        @Test
        @DisplayName("rejects a protected file without keys, and a key it does not know")
        void keys() {
            var file = Conformance.fetchListed("chromium-bear-640x360-v_frag-cenc.mp4");
            var unsupported = assertThrows(MediaException.Unsupported.class, () -> demux(file, DecryptionKeys.none()));
            assertEquals("mp4 encryption", unsupported.format());
            var other = DecryptionKeys.of(new byte[16], KEY);
            assertEquals("mp4 encryption", assertThrows(MediaException.Unsupported.class, () -> demux(file, other))
                    .format());
            var any = demux(file, DecryptionKeys.single(KEY));
            var expected = only(demux(Conformance.fetchListed("chromium-bear-640x360-v_frag.mp4"), DecryptionKeys.none()));
            EncryptedFixtures.assertSameData("any key ID", expected, only(any));
        }

        @Test
        @DisplayName("decrypts with a wrong key to other bytes, never to the clear packets")
        void wrongKey() {
            var file = Conformance.fetchListed("chromium-bear-640x360-v_frag-cenc.mp4");
            var expected = only(demux(Conformance.fetchListed("chromium-bear-640x360-v_frag.mp4"), DecryptionKeys.none()));
            var wrong = KEY.clone();
            wrong[0] ^= 1;
            var actual = only(demux(file, DecryptionKeys.of(KEY_ID, wrong)));
            var differing = 0;
            for (var index = 0; index < expected.size(); index++) {
                if (!Arrays.equals(expected.get(index).data(), actual.get(index).data())) {
                    differing++;
                }
            }
            assertTrue(differing > expected.size() / 2, differing + " of " + expected.size() + " packets differ");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "bear-640x360-v_frag-cbcs.mp4, bear-640x360-v_frag.mp4",
                "bear-640x360-a_frag-cenc.mp4, bear-640x360-a_frag.mp4",
        })
        @DisplayName("remuxes a protected file into a clear one")
        void remux(String encrypted, String clear) throws Exception {
            var output = Scratch.directory("cenc-remux").resolve(encrypted);
            try (var source = MediaSource.of(Conformance.fetchListed("chromium-" + encrypted));
                 var sink = MediaSink.of(output)) {
                Mp4Remuxer.remux(source, sink, RemuxOptions.video().toBuilder().decryptionKeys(KEYS).build());
            }
            var expected = only(demux(Conformance.fetchListed("chromium-" + clear), DecryptionKeys.none()));
            EncryptedFixtures.assertSameData(encrypted, expected, only(demux(output, DecryptionKeys.none())));
            assertFalse(new String(Files.readAllBytes(output), StandardCharsets.ISO_8859_1).contains("sinf"),
                    "the remuxed file declares no protection");
        }

        @Test
        @DisplayName("reports a protected sample without auxiliary information as malformed")
        void missingAuxiliaryInformation() {
            var file = Conformance.fetchListed("chromium-bear-1280x720-a_frag-cenc_missing-saiz-saio.mp4");
            assertThrows(MediaException.Malformed.class, () -> demux(file, KEYS));
        }
    }

    @Nested
    @DisplayName("ffmpeg-encrypted files")
    class FfmpegEncrypted {
        // ffmpeg 7.1 writes no sample auxiliary information into the fragments of fragmented output
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "progressive, -movflags +faststart",
                "moov at the end, -movflags +use_metadata_tags",
        })
        @DisplayName("decrypts the cenc samples ffmpeg encrypts, with senc, saiz and saio in the sample table")
        void decrypts(String name, String movflags) {
            var clear = Fixtures.testVideo("mp4", 192, 108, 2, List.of("-c:v", "libx264", "-g", "15", "-c:a", "aac"));
            var options = new ArrayList<>(List.of("-c", "copy", "-encryption_scheme", "cenc-aes-ctr",
                    "-encryption_key", HexFormat.of().formatHex(KEY), "-encryption_kid", HexFormat.of().formatHex(KEY_ID)));
            options.addAll(List.of(movflags.split(" ")));
            var encrypted = Fixtures.ffmpeg("mp4", List.of("-i", clear.toString()), options);
            var expected = demux(clear, DecryptionKeys.none());
            var actual = demux(encrypted, KEYS);
            assertEquals(expected.size(), actual.size(), "track count");
            var tracks = new ArrayList<>(expected.keySet());
            var actualTracks = new ArrayList<>(actual.keySet());
            for (var index = 0; index < tracks.size(); index++) {
                EncryptedFixtures.assertSamePackets(name + " track " + tracks.get(index), expected.get(tracks.get(index)),
                        actual.get(actualTracks.get(index)));
            }
            var protectedBytes = Fixtures.read(encrypted);
            var firstVideo = expected.get(tracks.getFirst()).getFirst().data();
            assertFalse(contains(protectedBytes, Arrays.copyOfRange(firstVideo, firstVideo.length - 64,
                    firstVideo.length)), "the encrypted file holds clear sample bytes");
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (var start = 0; start + needle.length <= haystack.length; start++) {
            for (var index = 0; index < needle.length; index++) {
                if (haystack[start + index] != needle[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
