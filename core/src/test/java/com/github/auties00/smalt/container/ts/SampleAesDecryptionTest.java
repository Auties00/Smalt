package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the HLS SAMPLE-AES decryption of the transport stream demuxer: streams the muxer encrypts (whose encryption
 * SampleAesEncryptionTest checks against Shaka Packager) and streams the encryptor of {@link SampleAesStreams},
 * written from Apple's definition, encrypts must demux to the tracks and packets of the same streams written clear,
 * on the committed clip with H.264, AAC, AC-3 and E-AC-3, and with synthetic slices, 9-byte ADTS headers and dependent
 * E-AC-3 substreams at the corners of the format.
 */
@DisplayName("SAMPLE-AES decryption")
class SampleAesDecryptionTest {
    private static final byte[] KEY = HexFormat.of().parseHex("8899aabbccddeeff0011223344556677");
    private static final byte[] IV = HexFormat.of().parseHex("f0e1d2c3b4a5968778695a4b3c2d1e0f");
    private static final Path INPUT = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "container", "ts", "sample-aes", "sample-aes-input.mp4");

    /** The tracks and packets a demuxer reads, the packets as one line each. */
    record Dump(List<Track> tracks, List<String> packets) {
    }

    static byte[] mux(TsMuxerOptions options) {
        return TsMuxerTest.remux(INPUT, sink -> new TsMuxer(sink, options));
    }

    static byte[] clearClip() {
        return mux(TsMuxerOptions.defaults());
    }

    static TsDemuxerOptions keys(byte[] key, byte[] iv) {
        return TsDemuxerOptions.builder().sampleAes(key, iv).build();
    }

    static String line(Packet packet) {
        try {
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packet.data()));
            return packet.trackId() + " " + packet.pts() + " " + packet.dts() + " " + packet.duration() + " "
                    + packet.flags() + " " + packet.sideData().newCodecConfig().map(HexFormat.of()::formatHex)
                    .orElse("-") + " " + packet.data().length + " " + digest;
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static Dump dump(byte[] ts, TsDemuxerOptions options) {
        try (var source = MediaSource.of(ts); var demuxer = new TsDemuxer(source, options)) {
            var packets = new ArrayList<String>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(line(packet));
            }
            return new Dump(demuxer.tracks(), packets);
        }
    }

    private static void assertSame(Dump expected, Dump actual) {
        assertEquals(expected.tracks(), actual.tracks());
        assertEquals(expected.packets().size(), actual.packets().size());
        for (var index = 0; index < expected.packets().size(); index++) {
            assertEquals(expected.packets().get(index), actual.packets().get(index), "packet " + index);
        }
    }

    private static List<Codec> codecs(Dump dump) {
        return dump.tracks().stream().map(Track::codec).toList();
    }

    @Test
    @DisplayName("decrypts the clip the muxer encrypts to the clip it writes clear")
    void muxedClip() {
        var clear = dump(clearClip(), TsDemuxerOptions.defaults());
        var encrypted = mux(TsMuxerOptions.builder().sampleAes(KEY, IV).build());
        assertEquals(List.of(Codec.H264, Codec.AAC, Codec.AC3, Codec.EAC3), codecs(clear));
        assertTrue(clear.packets().size() > 40, clear.packets().size() + " packets");
        assertSame(clear, dump(encrypted, keys(KEY, IV)));
    }

    @Test
    @DisplayName("decrypts streams encrypted from the format's definition, at the corners of the pattern")
    void specEncryptor() {
        var ts = clearClip();
        var clear = dump(SampleAesStreams.corners(ts, null, null), TsDemuxerOptions.defaults());
        var encrypted = SampleAesStreams.corners(ts, KEY, IV);
        assertEquals(java.util.Set.of(0xDB, 0xCF, 0xC1, 0xC2), java.util.Set.copyOf(
                SampleAesStreams.streamTypes(encrypted).values()));
        assertEquals(List.of(Codec.H264, Codec.AAC, Codec.AC3, Codec.EAC3), codecs(clear));
        var decrypted = dump(encrypted, keys(KEY, IV));
        // the priming of the setup information is the only difference
        var expected = new ArrayList<Track>();
        for (var track : clear.tracks()) {
            expected.add(track instanceof AudioTrack audio ? audio.toBuilder()
                    .skipSamples(audio.codec() == Codec.AAC ? 2112 : 256).build() : track);
        }
        assertSame(new Dump(expected, clear.packets()), decrypted);
        // the plain rewrite of the same stream without the corner cases decrypts too, so the clear rewrite is not
        // what makes the streams equal
        var plain = SampleAesStreams.rewrite(ts, null, null, SampleAesStreams.Setup.plain());
        assertSame(dump(plain, TsDemuxerOptions.defaults()), dump(SampleAesStreams.rewrite(ts, KEY, IV,
                SampleAesStreams.Setup.plain()), keys(KEY, IV)));
        assertNotEquals(dump(plain, TsDemuxerOptions.defaults()).packets(), clear.packets());
    }

    @Test
    @DisplayName("takes an HE-AAC configuration from the audio setup information")
    void heAacSetup() {
        var ts = clearClip();
        var plain = dump(ts, TsDemuxerOptions.defaults());
        var aac = (AudioTrack) plain.tracks().stream().filter(track -> track.codec() == Codec.AAC).findFirst()
                .orElseThrow();
        var core = aac.codecConfig().orElseThrow();
        var index = (core[0] & 0x07) << 1 | (core[1] & 0xFF) >>> 7;
        var channels = (core[1] >>> 3) & 0x0F;
        // AudioSpecificConfig: object type 5, the core frequency index and channels, an extension index, object type 2
        var bits = 5L << 17 | (long) index << 13 | (long) channels << 9 | (long) Math.max(0, index - 3) << 5 | 2;
        var config = new byte[]{(byte) (bits >>> 14), (byte) (bits >>> 6), (byte) (bits << 2)};
        for (var type : List.of("zach", "zaac")) {
            var setup = new SampleAesStreams.Setup(false, 0, type, config, 0, true, false, false);
            var track = (AudioTrack) dump(SampleAesStreams.rewrite(ts, KEY, IV, setup), keys(KEY, IV)).tracks()
                    .stream().filter(candidate -> candidate.codec() == Codec.AAC).findFirst().orElseThrow();
            if (type.equals("zach")) {
                assertArrayEquals(config, track.codecConfig().orElseThrow());
            } else {
                assertArrayEquals(core, track.codecConfig().orElseThrow());
            }
        }
        // a configuration of another core is ignored
        var other = config.clone();
        other[1] ^= (byte) 0x40;
        var setup = new SampleAesStreams.Setup(false, 0, "zach", other, 0, true, false, false);
        var track = dump(SampleAesStreams.rewrite(ts, KEY, IV, setup), keys(KEY, IV)).tracks().stream()
                .filter(candidate -> candidate.codec() == Codec.AAC).findFirst().orElseThrow();
        assertArrayEquals(core, track.codecConfig().orElseThrow());
    }

    @Test
    @DisplayName("decrypts each part of a stream with the key of its range")
    void keyRanges() {
        var ts = clearClip();
        var otherKey = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        var otherIv = SampleAesStreams.sequenceIv(7);
        var first = mux(TsMuxerOptions.builder().sampleAes(KEY, IV).build());
        var second = mux(TsMuxerOptions.builder().sampleAes(otherKey, otherIv).build());
        var joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        var schedule = SampleAesKeys.ranges(new long[]{0, first.length}, new SampleAesKey[]{
                SampleAesKey.of(KEY, IV), SampleAesKey.of(otherKey, otherIv)});
        var clearJoined = new byte[2 * ts.length];
        System.arraycopy(ts, 0, clearJoined, 0, ts.length);
        System.arraycopy(ts, 0, clearJoined, ts.length, ts.length);
        var options = TsDemuxerOptions.builder().sampleAesKeys(schedule).build();
        assertSame(dump(clearJoined, TsDemuxerOptions.defaults()), dump(joined, options));
        // the second key alone fails on the first part's AC-3 syncframes
        assertThrows(MediaException.Malformed.class, () -> dump(joined, keys(otherKey, otherIv)));
    }

    @Test
    @DisplayName("seeks in an encrypted stream as in the clear one")
    void seek() {
        var clear = clearClip();
        var encrypted = mux(TsMuxerOptions.builder().sampleAes(KEY, IV).build());
        try (var clearSource = MediaSource.of(clear); var clearDemuxer = new TsDemuxer(clearSource,
                TsDemuxerOptions.defaults()); var source = MediaSource.of(encrypted);
             var demuxer = new TsDemuxer(source, keys(KEY, IV))) {
            assertTrue(demuxer.isSeekable());
            for (var micros : new long[]{1_000_000, 300_000, 0}) {
                clearDemuxer.seek(micros);
                demuxer.seek(micros);
                for (var count = 0; count < 40; count++) {
                    var want = clearDemuxer.read();
                    var got = demuxer.read();
                    if (want == null) {
                        assertEquals(null, got);
                        break;
                    }
                    assertEquals(line(want), line(got), "packet " + count + " after a seek to " + micros);
                }
            }
        }
    }

    @Nested
    @DisplayName("damaged streams")
    class Damaged {
        @ParameterizedTest(name = "cut at {0}/16")
        @ValueSource(ints = {1, 3, 5, 8, 11, 13, 15})
        @DisplayName("a truncated encrypted stream reads the clear packets up to the cut")
        void truncated(int sixteenths) {
            var encrypted = mux(TsMuxerOptions.builder().sampleAes(KEY, IV).build());
            var clear = dump(clearClip(), TsDemuxerOptions.defaults());
            var cut = Arrays.copyOf(encrypted, encrypted.length * sixteenths / 16 / 188 * 188);
            Dump partial;
            try {
                partial = dump(cut, keys(KEY, IV));
            } catch (MediaException exception) {
                return;
            }
            var matching = 0;
            for (var packet : partial.packets()) {
                if (clear.packets().contains(packet)) {
                    matching++;
                }
            }
            // only the units each track still held at the cut may differ
            assertTrue(matching >= partial.packets().size() - partial.tracks().size(),
                    matching + " of " + partial.packets().size() + " packets are clear ones");
        }

        @Test
        @DisplayName("a lost transport packet in the first AC-3 frame is not taken for a wrong key")
        void lostAudioPacket() {
            var ts = clearClip();
            var setup = SampleAesStreams.Setup.plain();
            var encrypted = SampleAesStreams.rewrite(ts, KEY, IV, setup);
            var ac3Pid = -1;
            for (var entry : SampleAesStreams.streamTypes(encrypted).entrySet()) {
                if (entry.getValue() == 0xC1) {
                    ac3Pid = entry.getKey();
                }
            }
            assertTrue(ac3Pid > 0, "no AC-3 stream");
            var damaged = dump(dropSecond(encrypted, ac3Pid), keys(KEY, IV));
            var clear = dump(dropSecond(SampleAesStreams.rewrite(ts, null, null, setup), ac3Pid),
                    TsDemuxerOptions.defaults());
            assertEquals(clear.packets().size(), damaged.packets().size());
            // the damaged frames (the one missing bytes and one it may run into) decrypt to other bytes than the
            // clear ones hold; every other packet is the same
            var differing = 0;
            for (var index = 0; index < clear.packets().size(); index++) {
                if (!clear.packets().get(index).equals(damaged.packets().get(index))) {
                    differing++;
                }
            }
            assertTrue(differing <= 2, differing + " of " + clear.packets().size() + " packets differ");
        }

        /**
         * Drops the second transport packet of a PID, so that its continuity counter skips one value.
         */
        private static byte[] dropSecond(byte[] ts, int targetPid) {
            var seen = 0;
            var damaged = new java.io.ByteArrayOutputStream();
            for (var offset = 0; offset + 188 <= ts.length; offset += 188) {
                var pid = (ts[offset + 1] & 0x1F) << 8 | (ts[offset + 2] & 0xFF);
                if (pid == targetPid && seen++ == 1) {
                    continue;
                }
                damaged.write(ts, offset, 188);
            }
            return damaged.toByteArray();
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("flipped bytes in an encrypted stream raise only media exceptions")
        void flipped(int seed) {
            var encrypted = SampleAesStreams.corners(clearClip(), KEY, IV);
            var random = new java.util.Random(seed);
            for (var flip = 0; flip < 24; flip++) {
                var at = 188 * 3 + random.nextInt(encrypted.length - 188 * 3);
                encrypted[at] ^= (byte) (1 + random.nextInt(255));
            }
            try {
                dump(encrypted, keys(KEY, IV));
            } catch (MediaException expected) {
                // a damaged stream may be rejected, but only as a media failure
            }
        }
    }

    @Nested
    @DisplayName("keys")
    class Keys {
        @Test
        @DisplayName("an encrypted stream without keys is unsupported")
        void missing() {
            var encrypted = mux(TsMuxerOptions.builder().sampleAes(KEY, IV).build());
            var exception = assertThrows(MediaException.Unsupported.class, () -> dump(encrypted,
                    TsDemuxerOptions.defaults()));
            assertTrue(exception.getMessage().contains("SAMPLE-AES"), exception.getMessage());
            // keys that know nothing for the stream are the same
            assertThrows(MediaException.Unsupported.class, () -> dump(encrypted, TsDemuxerOptions.builder()
                    .sampleAesKeys(offset -> Optional.empty()).build()));
        }

        @Test
        @DisplayName("a wrong key of a stream with AC-3 is malformed")
        void wrongKey() {
            var encrypted = mux(TsMuxerOptions.builder().sampleAes(KEY, IV).build());
            var wrong = KEY.clone();
            wrong[0] ^= 1;
            var exception = assertThrows(MediaException.Malformed.class, () -> dump(encrypted, keys(wrong, IV)));
            assertTrue(exception.getMessage().contains("key"), exception.getMessage());
            var wrongIv = IV.clone();
            wrongIv[15] ^= 1;
            assertThrows(MediaException.Malformed.class, () -> dump(encrypted, keys(KEY, wrongIv)));
        }

        @Test
        @DisplayName("a wrong key of H.264 and AAC gives packets that differ from the clear ones")
        void wrongKeyVideo() {
            var ts = clearClip();
            var setup = new SampleAesStreams.Setup(true, 0, "zaac", null, 0, true, false, false);
            var clear = dump(SampleAesStreams.rewrite(ts, null, null, setup), TsDemuxerOptions.defaults());
            var encrypted = SampleAesStreams.rewrite(ts, KEY, IV, setup);
            assertSame(clear, dump(encrypted, keys(KEY, IV)));
            var wrong = KEY.clone();
            wrong[5] ^= 0x10;
            var garbled = dump(encrypted, keys(wrong, IV));
            assertEquals(clear.tracks(), garbled.tracks());
            var differing = 0;
            for (var index = 0; index < Math.min(clear.packets().size(), garbled.packets().size()); index++) {
                if (!clear.packets().get(index).equals(garbled.packets().get(index))) {
                    differing++;
                }
            }
            assertTrue(differing > clear.packets().size() / 2, differing + " of " + clear.packets().size());
        }

        @Test
        @DisplayName("keys and vectors are 16 bytes and copied")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> SampleAesKey.of(new byte[15], IV));
            assertThrows(IllegalArgumentException.class, () -> SampleAesKey.of(KEY, new byte[17]));
            assertThrows(NullPointerException.class, () -> SampleAesKey.of(null, IV));
            var key = KEY.clone();
            var sampleKey = SampleAesKey.of(key, IV);
            key[0] ^= 1;
            assertArrayEquals(KEY, sampleKey.key());
            sampleKey.key()[0] ^= 1;
            assertArrayEquals(KEY, sampleKey.key());
            assertArrayEquals(IV, sampleKey.initializationVector());
            assertEquals(SampleAesKey.of(KEY, IV), sampleKey);
            assertEquals(SampleAesKey.of(KEY, IV).hashCode(), sampleKey.hashCode());
            assertFalse(sampleKey.toString().contains(HexFormat.of().formatHex(KEY)));
            assertThrows(IllegalArgumentException.class, () -> TsDemuxerOptions.builder().sampleAes(KEY, new byte[8]));
            assertThrows(NullPointerException.class, () -> TsDemuxerOptions.builder().sampleAesKeys(null));
        }

        @Test
        @DisplayName("ranges answer the key in force at an offset")
        void ranges() {
            var a = SampleAesKey.of(KEY, IV);
            var b = SampleAesKey.of(IV, KEY);
            var keys = SampleAesKeys.ranges(new long[]{100, 200, 300}, new SampleAesKey[]{a, null, b});
            assertEquals(Optional.empty(), keys.find(0));
            assertEquals(Optional.empty(), keys.find(99));
            assertEquals(Optional.of(a), keys.find(100));
            assertEquals(Optional.of(a), keys.find(199));
            assertEquals(Optional.empty(), keys.find(200));
            assertEquals(Optional.of(b), keys.find(300));
            assertEquals(Optional.of(b), keys.find(Long.MAX_VALUE));
            assertEquals(Optional.empty(), SampleAesKeys.none().find(0));
            assertEquals(Optional.of(a), SampleAesKeys.of(a).find(12345));
            assertThrows(IllegalArgumentException.class, () -> SampleAesKeys.ranges(new long[]{0, 0},
                    new SampleAesKey[]{a, b}));
            assertThrows(IllegalArgumentException.class, () -> SampleAesKeys.ranges(new long[]{-1},
                    new SampleAesKey[]{a}));
            assertThrows(IllegalArgumentException.class, () -> SampleAesKeys.ranges(new long[]{0},
                    new SampleAesKey[]{a, b}));
        }

        @Test
        @DisplayName("options carry the keys")
        void options() {
            var options = keys(KEY, IV);
            assertEquals(SampleAesKeys.of(SampleAesKey.of(KEY, IV)), options.sampleAesKeys());
            assertEquals(keys(KEY, IV), options);
            assertEquals(keys(KEY, IV).hashCode(), options.hashCode());
            assertNotEquals(TsDemuxerOptions.defaults(), options);
            assertEquals(TsDemuxerOptions.defaults(), options.toBuilder().noSampleAes().build());
            assertEquals(SampleAesKeys.none(), TsDemuxerOptions.defaults().sampleAesKeys());
            assertTrue(options.toString().contains("sampleAes=true"), options.toString());
            assertFalse(options.toString().contains(HexFormat.of().formatHex(KEY)));
        }
    }

    @Nested
    @DisplayName("slices")
    class Slices {
        @ParameterizedTest(name = "{0} bytes")
        @ValueSource(ints = {49, 50, 63, 64, 65, 191, 192, 193, 207, 208, 209, 224, 225, 368, 369, 1000, 4096})
        @DisplayName("decrypt to the stored unit whatever its length")
        void lengths(int length) {
            var nal = SampleAesStreams.syntheticSlices(length).getFirst();
            nal = Arrays.copyOf(nal, length);
            for (var index = 2; index < length; index++) {
                if (nal[index - 2] == 0 && nal[index - 1] == 0 && (nal[index] & 0xFF) <= 3) {
                    nal[index] = 9;
                }
            }
            nal[length - 1] = (byte) 0x80;
            // emulation prevention in the leader, the first encrypted block and the skipped blocks
            for (var at : new int[]{12, 36, 60}) {
                if (at + 4 < length) {
                    nal[at - 1] = 0x11;
                    nal[at] = 0;
                    nal[at + 1] = 0;
                    nal[at + 2] = 3;
                    nal[at + 3] = 1;
                }
            }
            var carried = SampleAesStreams.encryptSlice(nal, KEY, IV);
            var decryptor = new SampleAesDecryptor(SampleAesKeys.of(SampleAesKey.of(KEY, IV)), "test stream");
            assertTrue(SampleAesDecryptor.isEncryptedSlice(carried, 0, carried.length));
            var size = decryptor.slice(carried, 0, carried.length, 0);
            assertEquals(HexFormat.of().formatHex(nal), HexFormat.of().formatHex(Arrays.copyOf(decryptor.slice(),
                    size)));
        }

        @Test
        @DisplayName("leave slices of at most 48 carried bytes and other units clear")
        void clearUnits() {
            var shortSlice = new byte[]{0x01, 0x40, 0, 0, 3, 1, 5, 6, 7, (byte) 0x80};
            assertFalse(SampleAesDecryptor.isEncryptedSlice(shortSlice, 0, shortSlice.length));
            var sps = new byte[100];
            sps[0] = 0x67;
            assertFalse(SampleAesDecryptor.isEncryptedSlice(sps, 0, sps.length));
            var slice = new byte[49];
            slice[0] = 0x65;
            assertTrue(SampleAesDecryptor.isEncryptedSlice(slice, 0, slice.length));
            assertFalse(SampleAesDecryptor.isEncryptedSlice(slice, 0, 48));
        }
    }
}
