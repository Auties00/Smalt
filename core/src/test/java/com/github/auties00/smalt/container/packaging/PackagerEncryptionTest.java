package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.EncryptedFixtures;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.validate.Validators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packages encrypted HLS and DASH presentations and checks them against their clear packaging: ffmpeg decodes the
 * AES-128 and SAMPLE-AES playlists, and the decrypted CMAF tracks, to the frames of the clear ones, the validators
 * accept the playlists, manifests and decrypted segments, and the segments are encrypted as the methods define.
 */
@DisplayName("Packager encryption")
class PackagerEncryptionTest {
    private static final byte[] KEY_ID = HexFormat.of().parseHex("0c1d2e3f405162738495a6b7c8d9eafb");
    private static final byte[] KEY = HexFormat.of().parseHex("8899aabbccddeeff0011223344556677");
    private static final byte[] IV = HexFormat.of().parseHex("f0e1d2c3b4a5968778695a4b3c2d1e0f");

    private static Path input(String audio) {
        return Fixtures.ffmpeg("mp4", List.of(
                        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=5",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=5"),
                List.of("-c:v", "libx264", "-g", "30", "-keyint_min", "30", "-sc_threshold", "0", "-bf", "2",
                        "-c:a", audio, "-ac", "2", "-b:a", "128k"));
    }

    private static Path pack(Path input, PackagingOptions options, String name) {
        var directory = Scratch.directory("media-fixtures/packager-encryption").resolve(name);
        try {
            if (Files.isDirectory(directory)) {
                try (var files = Files.list(directory)) {
                    for (var file : files.toList()) {
                        Files.delete(file);
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        try (var source = MediaSource.of(input)) {
            Packager.pack(List.of(Rendition.of(source)), PackageOutput.directory(directory),
                    options.toBuilder().segmentMicros(2_000_000).build());
        }
        return directory;
    }

    /**
     * Decodes a playlist or file with ffmpeg and returns the MD5 of every decoded frame of each stream.
     */
    private static List<String> decode(Path file, List<String> inputOptions) {
        var arguments = new ArrayList<>(List.of("-v", "error"));
        if (file.toString().endsWith(".m3u8")) {
            arguments.addAll(List.of("-allowed_extensions", "ALL"));
        }
        arguments.addAll(inputOptions);
        arguments.addAll(List.of("-i", file.toAbsolutePath().toString().replace('\\', '/'), "-map", "0", "-f",
                "framemd5", "-"));
        var output = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(3), arguments), StandardCharsets.UTF_8);
        return output.lines().filter(line -> !line.startsWith("#")).toList();
    }

    private static List<String> decodePlaylist(Path playlist) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        return decode(playlist, List.of());
    }

    private static Path concatenate(Path directory, String stream) {
        try (var files = Files.list(directory)) {
            var parts = files.map(Path::getFileName).map(Path::toString)
                    .filter(name -> name.startsWith(stream + "_")).sorted().toList();
            var out = new ByteArrayOutputStream();
            out.writeBytes(Files.readAllBytes(directory.resolve(stream + "_init.mp4")));
            for (var part : parts) {
                if (part.endsWith(".m4s")) {
                    out.writeBytes(Files.readAllBytes(directory.resolve(part)));
                }
            }
            var file = directory.resolve(stream + "-joined.mp4");
            Files.write(file, out.toByteArray());
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @ParameterizedTest(name = "{0} audio, byte ranges {1}, IV {2}")
    @CsvSource({"aac, false, false", "aac, true, false", "aac, false, true", "ac3, false, false"})
    @DisplayName("encrypts transport stream segments whole with AES-128")
    void aes128Ts(String audio, boolean byteRanges, boolean explicitIv) throws Exception {
        var file = input(audio);
        var clearOptions = PackagingOptions.hls().toBuilder().byteRanges(byteRanges).build();
        var encryption = PackagingEncryption.builder().method(PackagingEncryption.Method.AES_128).key(KEY)
                .keyUri("key.bin").keyFileName("key.bin");
        if (explicitIv) {
            encryption.initializationVector(IV);
        }
        var clear = pack(file, clearOptions, "ts-clear-" + audio + byteRanges);
        var encrypted = pack(file, clearOptions.toBuilder().encryption(encryption.build()).build(),
                "ts-aes-" + audio + byteRanges + explicitIv);
        assertArrayEquals(KEY, Files.readAllBytes(encrypted.resolve("key.bin")));
        var playlist = Files.readString(encrypted.resolve("r0.m3u8"));
        assertTrue(playlist.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\""
                + (explicitIv ? ",IV=0x" + HexFormat.of().formatHex(IV) : "") + "\n"), playlist);
        Validators.assertValidHls(encrypted.resolve("master.m3u8"));
        if (!byteRanges) {
            // the first segment, media sequence 0, decrypts with its sequence number or the given vector
            var segment = Files.readAllBytes(encrypted.resolve("r0_00001.ts"));
            assertEquals(0, segment.length % 16);
            assertFalse(segment[0] == 0x47 && segment[188] == 0x47, "a clear transport stream");
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(explicitIv ? IV
                    : new byte[16]));
            assertArrayEquals(Files.readAllBytes(clear.resolve("r0_00001.ts")), cipher.doFinal(segment));
        }
        assertEquals(decodePlaylist(clear.resolve("r0.m3u8")), decodePlaylist(encrypted.resolve("r0.m3u8")));
    }

    @ParameterizedTest(name = "{0} audio")
    @CsvSource({"aac", "ac3", "eac3"})
    @DisplayName("encrypts the samples of transport stream segments with SAMPLE-AES")
    void sampleAesTs(String audio) throws Exception {
        var file = input(audio);
        var encryption = PackagingEncryption.builder().method(PackagingEncryption.Method.SAMPLE_AES).key(KEY)
                .keyUri("key.bin").keyFileName("key.bin").initializationVector(IV).build();
        var clear = pack(file, PackagingOptions.hls(), "ts-clear-" + audio);
        var encrypted = pack(file, PackagingOptions.hls().toBuilder().encryption(encryption).build(),
                "ts-sample-aes-" + audio);
        var playlist = Files.readString(encrypted.resolve("r0.m3u8"));
        assertTrue(playlist.contains("#EXT-X-VERSION:5"), playlist);
        assertTrue(playlist.contains("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key.bin\",IV=0x"
                + HexFormat.of().formatHex(IV)), playlist);
        Validators.assertValidHls(encrypted.resolve("master.m3u8"));
        var segment = Files.readAllBytes(encrypted.resolve("r0_00001.ts"));
        var text = new String(segment, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("zavc") && text.contains("apad"), "the SAMPLE-AES descriptors");
        // ffmpeg's demuxer decrypts every packet to the clear one, except that it flushes the packets still pending at
        // the end of the playlist without decrypting them (as it does with Shaka Packager's SAMPLE-AES) and drops
        // the timestamps of E-AC-3 packets; the encryption itself is checked bit for bit by SampleAesEncryptionTest
        var options = List.of("-allowed_extensions", "ALL");
        var plain = EncryptedFixtures.ffmpegPacketHashes(clear.resolve("r0.m3u8"), null, options);
        var decrypted = EncryptedFixtures.ffmpegPacketHashes(encrypted.resolve("r0.m3u8"), null, options);
        assertEquals(plain.keySet(), decrypted.keySet());
        for (var stream : plain.keySet()) {
            var want = plain.get(stream);
            var got = decrypted.get(stream);
            assertEquals(want.size(), got.size(), "stream " + stream);
            assertTrue(want.size() > 100, "a five-second clip has every frame");
            for (var index = 0; index < want.size() - 2; index++) {
                assertEquals(want.get(index), got.get(index), "stream " + stream + " packet " + index);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"SAMPLE_AES, cbcs", "SAMPLE_AES_CTR, cenc"})
    @DisplayName("encrypts the samples of CMAF segments with Common Encryption for HLS and DASH")
    void commonEncryption(PackagingEncryption.Method method, String scheme) throws Exception {
        var file = input("aac");
        var encryption = PackagingEncryption.builder().method(method).keyId(KEY_ID).key(KEY).keyUri("key.bin")
                .build();
        var clear = pack(file, PackagingOptions.cmaf(), "cmaf-clear");
        var encrypted = pack(file, PackagingOptions.cmaf().toBuilder().encryption(encryption).build(),
                "cmaf-" + scheme);
        var manifest = Files.readString(encrypted.resolve("manifest.mpd"));
        assertTrue(manifest.contains("value=\"" + scheme + "\" cenc:default_KID=\"0c1d2e3f-4051-6273-8495-a6b7c8d9eafb\""),
                manifest);
        assertTrue(manifest.contains("urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e"), manifest);
        var playlist = Files.readString(encrypted.resolve("r0_video.m3u8"));
        assertTrue(playlist.contains("#EXT-X-KEY:METHOD=" + (method == PackagingEncryption.Method.SAMPLE_AES
                ? "SAMPLE-AES" : "SAMPLE-AES-CTR") + ",URI=\"key.bin\""), playlist);
        Validators.assertValidDash(encrypted.resolve("manifest.mpd"));
        Validators.assertValidHls(encrypted.resolve("master.m3u8"));
        for (var stream : List.of("r0_video", "r0_audio")) {
            var plain = concatenate(clear, stream);
            var joined = concatenate(encrypted, stream);
            Oracle.assume(Oracle.Tool.FFMPEG);
            assertEquals(decode(plain, List.of()), decode(joined, List.of("-decryption_key",
                    HexFormat.of().formatHex(KEY))), stream);
            try (var plainSource = MediaSource.of(plain); var plainDemuxer = new Mp4Demuxer(plainSource);
                 var source = MediaSource.of(joined); var demuxer = new Mp4Demuxer(source,
                         DemuxerOptions.builder().decryptionKeys(DecryptionKeys.of(KEY_ID, KEY)).build())) {
                EncryptedFixtures.assertSamePackets(stream, EncryptedFixtures.packets(plainDemuxer).values().iterator()
                        .next(), EncryptedFixtures.packets(demuxer).values().iterator().next());
            }
            try (var source = MediaSource.of(joined)) {
                assertThrows(MediaException.Unsupported.class, () -> new Mp4Demuxer(source).close());
            }
        }
    }

    @Test
    @DisplayName("encrypts CMAF media segments whole with AES-128 and leaves the initialization segments clear")
    void aes128Cmaf() throws Exception {
        var file = input("aac");
        var encryption = PackagingEncryption.forAes128(KEY, "key.bin").toBuilder().keyFileName("key.bin").build();
        var clear = pack(file, PackagingOptions.hlsCmaf(), "hls-cmaf-clear");
        var encrypted = pack(file, PackagingOptions.hlsCmaf().toBuilder().encryption(encryption).build(),
                "hls-cmaf-aes");
        assertArrayEquals(Files.readAllBytes(clear.resolve("r0_video_init.mp4")),
                Files.readAllBytes(encrypted.resolve("r0_video_init.mp4")));
        var playlist = Files.readString(encrypted.resolve("r0_video.m3u8"));
        assertTrue(playlist.indexOf("#EXT-X-MAP") < playlist.indexOf("#EXT-X-KEY"), playlist);
        Validators.assertValidHls(encrypted.resolve("master.m3u8"));
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(new byte[]{0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}));
        assertArrayEquals(Files.readAllBytes(clear.resolve("r0_video_00002.m4s")),
                cipher.doFinal(Files.readAllBytes(encrypted.resolve("r0_video_00002.m4s"))));
        assertEquals(decodePlaylist(clear.resolve("r0_video.m3u8")), decodePlaylist(encrypted.resolve("r0_video.m3u8")));
    }

    @Test
    @DisplayName("rejects encryption settings that do not fit the packaging")
    void rejects() {
        var aes = PackagingEncryption.forAes128(KEY, "key.bin");
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.dash().toBuilder().encryption(aes).build());
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.dashOnDemand().toBuilder().hls(true)
                .encryption(aes).build());
        var ctr = PackagingEncryption.forSampleAesCtr(KEY_ID, KEY, "key.bin");
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().encryption(ctr).build());
        var noKeyId = PackagingEncryption.builder().key(KEY).keyUri("key.bin").build();
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.cmaf().toBuilder().encryption(noKeyId)
                .build());
        var noUri = PackagingEncryption.builder().keyId(KEY_ID).key(KEY).build();
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().encryption(noUri).build());
        PackagingOptions.dash().toBuilder().encryption(noUri).build();
        assertThrows(IllegalArgumentException.class, () -> PackagingEncryption.builder().key(new byte[15]).build());
        assertThrows(IllegalArgumentException.class, () -> PackagingEncryption.builder().key(KEY).keyUri("a\"b")
                .build());
        assertThrows(IllegalArgumentException.class, () -> PackagingEncryption.builder().key(KEY)
                .initializationVector(new byte[8]).build());
        for (var name : List.of("", "../key.bin", "keys/key.bin", "keys\\key.bin")) {
            assertThrows(IllegalArgumentException.class, () -> PackagingEncryption.builder().key(KEY).keyFileName(name)
                    .build(), name);
        }
        var overwrites = PackagingEncryption.forAes128(KEY, "key.bin").toBuilder().keyFileName("master.m3u8").build();
        assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().encryption(overwrites)
                .build());
        var hevc = Fixtures.testVideo("mp4", 160, 96, 1, List.of("-c:v", "libx265", "-tag:v", "hvc1", "-x265-params",
                "log-level=error", "-an"));
        var sampleAes = PackagingEncryption.builder().key(KEY).keyUri("key.bin").build();
        assertThrows(MediaException.Unsupported.class, () -> pack(hevc, PackagingOptions.hls().toBuilder()
                .encryption(sampleAes).build(), "hevc-sample-aes"));
    }
}
