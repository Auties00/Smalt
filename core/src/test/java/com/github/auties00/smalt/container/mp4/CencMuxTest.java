package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.EncryptedFixtures;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encrypts H.264, HEVC and AAC tracks with the fragmented MP4 muxer in every Common Encryption scheme and checks the
 * result three ways: the demuxer decrypts it back to the clear packets, ffmpeg's decryption gives the same packets,
 * and ffmpeg decodes the decrypted video to the frames of the clear file.
 */
@DisplayName("Common Encryption muxing")
class CencMuxTest {
    private static final byte[] KEY_ID = HexFormat.of().parseHex("a1b2c3d4e5f60718293a4b5c6d7e8f90");
    private static final byte[] KEY = HexFormat.of().parseHex("1f2e3d4c5b6a79880796a5b4c3d2e1f0");

    private record Demuxed(List<Track> tracks, Map<Integer, List<Packet>> packets) {
    }

    private static Demuxed demux(Path file, DecryptionKeys keys) {
        try (var source = MediaSource.of(file);
             var demuxer = new Mp4Demuxer(source, DemuxerOptions.builder().decryptionKeys(keys).build())) {
            return new Demuxed(demuxer.tracks(), EncryptedFixtures.packets(demuxer));
        }
    }

    private static Path encrypt(Path clear, CommonEncryption encryption, String name) {
        var output = Scratch.directory("cenc-mux").resolve(name + ".mp4");
        var input = demux(clear, DecryptionKeys.none());
        var options = FragmentedMp4MuxerOptions.builder().fragmentMicros(1_000_000);
        if (encryption != null) {
            options.encryption(encryption);
        }
        try (var sink = MediaSink.of(output); var muxer = new FragmentedMp4Muxer(sink, options.build())) {
            for (var track : input.tracks()) {
                muxer.addTrack(track);
            }
            try (var source = MediaSource.of(clear); var demuxer = new Mp4Demuxer(source)) {
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    muxer.writePacket(packet);
                }
            }
            muxer.finish();
        }
        return output;
    }

    private static Path clip(String codec, String options) {
        var arguments = new ArrayList<>(List.of("-c:v", codec));
        arguments.addAll(List.of(options.trim().split(" ")));
        arguments.addAll(List.of("-c:a", "aac", "-b:a", "96k"));
        if (codec.equals("libx265")) {
            arguments.addAll(List.of("-tag:v", "hvc1"));
        }
        return Fixtures.testVideo("mp4", 320, 192, 3, arguments);
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource(delimiter = '|', value = {
            "CENC | libx264 | -profile:v high -bf 3 -x264-params slices=2:weightp=2",
            "CBC1 | libx264 | -profile:v baseline",
            "CENS | libx264 | -profile:v main -bf 2",
            "CBCS | libx264 | -profile:v high -bf 3 -x264-params slices=3",
            "CENC | libx265 | -x265-params log-level=error:wpp=1",
            "CBCS | libx265 | -x265-params log-level=error:slices=2:bframes=3",
            "CENS | libx265 | -x265-params log-level=error",
            "CBC1 | libx265 | -x265-params log-level=error:weightp=1",
            "CENC | libvpx-vp9 | -b:v 300k -tile-columns 1 -auto-alt-ref 1 -lag-in-frames 16",
            "CBCS | libvpx-vp9 | -b:v 300k -auto-alt-ref 1 -lag-in-frames 16",
            "CENS | libvpx-vp9 | -b:v 200k",
            "CBC1 | libvpx-vp9 | -b:v 200k -error-resilient 1",
            "CENC | libaom-av1 | -cpu-used 8 -b:v 300k -tile-columns 1 -tile-rows 1",
            "CBCS | libaom-av1 | -cpu-used 8 -b:v 300k -lag-in-frames 16",
            "CENS | libaom-av1 | -cpu-used 8 -crf 40",
            "CBC1 | libaom-av1 | -cpu-used 8 -b:v 200k -aom-params enable-cdef=0:enable-restoration=1",
            "CENC | mpeg4 | -q:v 4 -bf 1",
            "CBCS | mpeg4 | -q:v 6",
            "CENS | mpeg4 | -q:v 5 -bf 1",
    })
    @DisplayName("decrypts back to the clear packets, as ffmpeg does")
    void roundTrip(CommonEncryption.Scheme scheme, String codec, String options) {
        var clear = clip(codec, options);
        var encryption = CommonEncryption.builder().scheme(scheme).keyId(KEY_ID).key(KEY).build();
        var encrypted = encrypt(clear, encryption, scheme + "-" + codec + "-" + Integer.toHexString(options.hashCode()));
        Validators.assertValidIsoBmff(encrypted);
        var expected = demux(clear, DecryptionKeys.none());
        var actual = demux(encrypted, DecryptionKeys.of(KEY_ID, KEY));
        assertEquals(expected.tracks().size(), actual.tracks().size(), "track count");
        for (var index = 0; index < expected.tracks().size(); index++) {
            EncryptedFixtures.assertSamePackets(scheme + " track " + index,
                    expected.packets().get(expected.tracks().get(index).id()),
                    actual.packets().get(actual.tracks().get(index).id()));
        }
        // ffmpeg reads the clear and the encrypted fragmented files the muxer writes from the same packets
        var plain = encrypt(clear, null, "clear-" + codec + "-" + Integer.toHexString(options.hashCode()));
        var expectedHashes = EncryptedFixtures.ffmpegPacketHashes(plain, null, List.of());
        var actualHashes = EncryptedFixtures.ffmpegPacketHashes(encrypted, KEY, List.of());
        if (scheme == CommonEncryption.Scheme.CENS) {
            // ffmpeg 7.1 decrypts the last partial block of whole-sample cens audio, which the pattern leaves clear
            expectedHashes.keySet().retainAll(Set.of(0));
            actualHashes.keySet().retainAll(Set.of(0));
        }
        assertEquals(expectedHashes, actualHashes, "ffmpeg's decryption");
        assertThrows(MediaException.Unsupported.class, () -> demux(encrypted, DecryptionKeys.none()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"CENC", "CBCS"})
    @DisplayName("keeps NAL unit headers and slice headers clear and encrypts the slice data")
    void clearHeaders(CommonEncryption.Scheme scheme) {
        var clear = clip("libx264", "-profile:v high -bf 2");
        var encrypted = encrypt(clear, CommonEncryption.builder().scheme(scheme).keyId(KEY_ID).key(KEY).build(),
                "headers-" + scheme);
        // a wrong key decrypts the protected bytes to other bytes and leaves the clear ones as they are
        var wrong = KEY.clone();
        wrong[15] ^= 0x55;
        var expected = demux(clear, DecryptionKeys.none());
        var actual = demux(encrypted, DecryptionKeys.of(KEY_ID, wrong));
        var video = expected.tracks().stream().filter(track -> track instanceof VideoTrack).findFirst().orElseThrow();
        var want = expected.packets().get(video.id());
        var got = actual.packets().get(actual.tracks().stream().filter(track -> track instanceof VideoTrack)
                .findFirst().orElseThrow().id());
        var protectedSamples = 0;
        for (var index = 0; index < want.size(); index++) {
            var plain = want.get(index).data();
            var other = got.get(index).data();
            assertEquals(plain.length, other.length);
            // the 4-byte length and the first bytes of every slice NAL unit (header and slice header start) stay
            for (var position = 0; position + 4 < plain.length; ) {
                var length = ((plain[position] & 0xFF) << 24) | ((plain[position + 1] & 0xFF) << 16)
                        | ((plain[position + 2] & 0xFF) << 8) | (plain[position + 3] & 0xFF);
                for (var at = position; at < position + 4 + Math.min(4, length); at++) {
                    assertEquals(plain[at], other[at], "sample " + index + " byte " + at);
                }
                position += 4 + length;
            }
            if (!Arrays.equals(plain, other)) {
                protectedSamples++;
            }
        }
        assertTrue(protectedSamples > want.size() / 2, protectedSamples + " of " + want.size() + " samples changed");
    }

    @Test
    @DisplayName("decodes the decrypted video to the frames of the clear file in ffmpeg")
    void decodes() {
        var clear = clip("libx264", "-profile:v high -bf 3");
        var encrypted = encrypt(clear, CommonEncryption.forCbcs(KEY_ID, KEY), "decode-cbcs");
        var arguments = List.of("-map", "0:v", "-f", "framemd5", "-");
        var expected = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), concat(List.of("-v", "error", "-i",
                clear.toString()), arguments));
        var actual = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), concat(List.of("-v", "error",
                "-decryption_key", HexFormat.of().formatHex(KEY), "-i", encrypted.toString()), arguments));
        assertEquals(frames(expected), frames(actual));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        var result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static List<String> frames(byte[] framemd5) {
        return new String(framemd5, java.nio.charset.StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("#")).map(line -> line.substring(line.lastIndexOf(',') + 1).trim())
                .toList();
    }

    @Test
    @DisplayName("writes the sinf, the common pssh and the sample encryption boxes")
    void boxes() throws Exception {
        var clear = clip("libx264", "-profile:v high");
        var pssh = HexFormat.of().parseHex("000000207073736800000000edef8ba979d64acea3c827dcd51d21ed00000000");
        var encrypted = encrypt(clear, CommonEncryption.builder().scheme(CommonEncryption.Scheme.CENS).keyId(KEY_ID)
                .key(KEY).initializationVector(HexFormat.of().parseHex("0102030405060708")).pattern(2, 8)
                .protectionSystems(List.of(pssh)).build(), "boxes");
        var bytes = Files.readAllBytes(encrypted);
        var text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (var box : List.of("encv", "enca", "sinf", "frma", "schm", "cens", "tenc", "pssh", "senc", "saiz", "saio")) {
            assertTrue(text.contains(box), box);
        }
        assertTrue(text.contains(new String(pssh, java.nio.charset.StandardCharsets.ISO_8859_1)), "the given pssh box");
        var common = HexFormat.of().parseHex(CommonEncryption.COMMON_SYSTEM_ID);
        assertTrue(text.contains(new String(common, java.nio.charset.StandardCharsets.ISO_8859_1)), "the common pssh");
        assertTrue(text.contains(new String(KEY_ID, java.nio.charset.StandardCharsets.ISO_8859_1)), "the key ID");
        var actual = demux(encrypted, DecryptionKeys.of(KEY_ID, KEY));
        var expected = demux(clear, DecryptionKeys.none());
        assertEquals(expected.packets().values().stream().mapToInt(List::size).sum(),
                actual.packets().values().stream().mapToInt(List::size).sum());
        var noCommon = encrypt(clear, CommonEncryption.forCenc(KEY_ID, KEY).toBuilder().commonProtectionSystem(false)
                .build(), "no-common");
        assertFalse(new String(Files.readAllBytes(noCommon), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("pssh"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"CENC", "CBCS"})
    @DisplayName("writes a sample whose auxiliary information fills a saiz entry and rejects a larger one")
    void subsampleLimit(CommonEncryption.Scheme scheme) throws Exception {
        var clear = clip("libx264", "-profile:v baseline -bf 0");
        var input = demux(clear, DecryptionKeys.none());
        var video = (VideoTrack) input.tracks().stream().filter(track -> track instanceof VideoTrack).findFirst()
                .orElseThrow();
        byte[] slice = null;
        var first = input.packets().get(video.id()).getFirst().data();
        for (var position = 0; position + 4 < first.length && slice == null; ) {
            var length = ((first[position] & 0xFF) << 24) | ((first[position + 1] & 0xFF) << 16)
                    | ((first[position + 2] & 0xFF) << 8) | (first[position + 3] & 0xFF);
            if ((first[position + 4] & 0x1F) == 5) {
                slice = Arrays.copyOfRange(first, position, position + 4 + length);
            }
            position += 4 + length;
        }
        assertTrue(slice != null && slice.length > 64, "an IDR slice of the clip");
        var encryption = CommonEncryption.builder().scheme(scheme).keyId(KEY_ID).key(KEY).build();
        // each copy of the slice adds one 6-byte subsample: the largest sample written has 250 to 255 bytes of
        // auxiliary information, as its saiz entry says, and one more slice is rejected
        var largest = 0;
        var largestSize = 0;
        for (var copies = 30; copies <= 50; copies++) {
            var sample = repeat(slice, copies);
            var output = Scratch.directory("cenc-mux").resolve("limit-" + scheme + "-" + copies + ".mp4");
            try (var sink = MediaSink.of(output); var muxer = new FragmentedMp4Muxer(sink,
                    FragmentedMp4MuxerOptions.builder().encryption(encryption).build())) {
                muxer.addTrack(video);
                try {
                    muxer.writePacket(new Packet(video.id(), sample, 0, 0, 3000, true));
                } catch (MediaException.Unsupported rejected) {
                    assertTrue(rejected.getMessage().contains("subsamples"), rejected.getMessage());
                    break;
                }
                muxer.finish();
            }
            var bytes = Files.readAllBytes(output);
            var saiz = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("saiz");
            assertTrue(saiz > 0, "a saiz box");
            var size = bytes[saiz + 8] & 0xFF;
            assertTrue(size > largestSize, copies + " copies: saiz entry of " + size + " bytes");
            var decrypted = demux(output, DecryptionKeys.of(KEY_ID, KEY)).packets().get(video.id());
            assertEquals(1, decrypted.size());
            assertTrue(Arrays.equals(sample, decrypted.getFirst().data()), copies + " copies decrypt");
            largest = copies;
            largestSize = size;
        }
        assertTrue(largest > 30 && largest < 50, "the limit lies between 30 and 50 slices, not at " + largest);
        assertTrue(largestSize > 255 - 6, "the largest sample written has " + largestSize + " bytes of information");
    }

    private static byte[] repeat(byte[] unit, int count) {
        var result = new byte[unit.length * count];
        for (var index = 0; index < count; index++) {
            System.arraycopy(unit, 0, result, index * unit.length, unit.length);
        }
        return result;
    }

    @Test
    @DisplayName("rejects invalid settings")
    void rejects() {
        assertThrows(IllegalArgumentException.class, () -> CommonEncryption.builder().key(KEY).build());
        assertThrows(IllegalArgumentException.class, () -> CommonEncryption.builder().keyId(KEY_ID).key(new byte[8])
                .build());
        assertThrows(IllegalArgumentException.class, () -> CommonEncryption.builder().scheme(CommonEncryption.Scheme.CBCS)
                .keyId(KEY_ID).key(KEY).initializationVector(new byte[8]).build());
        assertThrows(IllegalArgumentException.class, () -> CommonEncryption.builder().keyId(KEY_ID).key(KEY)
                .pattern(0, 9).build());
        assertThrows(IllegalArgumentException.class, () -> CommonEncryption.builder().keyId(KEY_ID).key(KEY)
                .protectionSystems(List.of(new byte[40])).build());
    }
}
