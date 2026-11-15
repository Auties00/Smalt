package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.EncryptedFixtures;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Decrypts the Common Encryption files of the MPEG file format conformance suite (ISO/IEC 14496-32,
 * {@code under_consideration/cenc}, written by GPAC from one HEVC clip and one AAC clip in every scheme, with 8 and
 * 16-byte initialization vectors, constant ones, and keys rolled through {@code seig} sample groups of the sample
 * table, and the multi-key files of ISO/IEC 23001-7:2023) with the keys of their {@code drm_cfg} files: every file
 * of a clip decrypts to the same packets, which ffmpeg's decryption of the single-key files gives too.
 */
@DisplayName("MPEG Common Encryption conformance")
class MpegCencConformanceTest {
    private static final String KID_A = "279926496a7f5d25da69f2b3b2799a7f";
    private static final String KID_B = "597669572e55547e656b56586e2f6f68";
    private static final String KID_C = "279926496a7f5d25da69f2b3b2799a7e";

    // the keys of the suite's drm_cfg files: cenc_*.xml and cens_*.xml use KEY_1, cbc1.xml and cbcs*.xml KEY_2, and
    // the roll files add KID_B
    private static final String KEY_1 = "5544694d47473326622665665a396b36";
    private static final String KEY_2 = "ccc0f2b3b279926496a7f5d25da692f6";
    private static final String KEY_B = "7959493a764556786527517849756635";
    // the first key of the multi-key files
    private static final String KEY_C = "5544694d47473326622665665a396b35";

    private static List<Packet> demux(String name, DecryptionKeys keys) {
        return demux(Conformance.fetchListed("mpegff-" + name), name, keys);
    }

    private static List<Packet> demux(Path file, String name, DecryptionKeys keys) {
        try (var source = MediaSource.of(file);
             var demuxer = new Mp4Demuxer(source, DemuxerOptions.builder().decryptionKeys(keys).build())) {
            var packets = EncryptedFixtures.packets(demuxer);
            assertEquals(1, packets.size(), name + ": track count");
            return packets.values().iterator().next();
        }
    }

    private static DecryptionKeys keys(String key, boolean roll) {
        return roll ? DecryptionKeys.ofHex(Map.of(KID_A, key, KID_B, KEY_B)) : DecryptionKeys.ofHex(Map.of(KID_A, key));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "video_cenc_128iv.mp4, " + KEY_1 + ", false",
            "video_cens_64iv.mp4, " + KEY_1 + ", false",
            "video_cens_128iv.mp4, " + KEY_1 + ", false",
            "video_cbc1.mp4, " + KEY_2 + ", false",
            "video_cbcs_const_iv.mp4, " + KEY_2 + ", false",
            "video_cenc_roll.mp4, " + KEY_1 + ", true",
            "video_cenc_roll_frag1s.mp4, " + KEY_1 + ", true",
            "video_cbcs_const_iv_roll.mp4, " + KEY_1 + ", true",
            "video_cbcs_const_iv_roll_frag1s.mp4, " + KEY_1 + ", true",
    })
    // video_cbcs.mp4, cbcs with per-sample initialization vectors, chains the blocks of a sample across its
    // subsamples, where ISO/IEC 23001-7 cbcs restarts the chain at each one as Shaka Packager and ffmpeg do
    @DisplayName("decrypts every video file to the packets of the 8-byte IV cenc file")
    void video(String name, String key, boolean roll) {
        var reference = demux("video_cenc_64iv.mp4", keys(KEY_1, false));
        EncryptedFixtures.assertSameData(name, reference, demux(name, keys(key, roll)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "audio_cbcs.mp4, " + KEY_2,
            "audio_cbcs_const_iv.mp4, " + KEY_2,
    })
    @DisplayName("decrypts every audio file to the packets of the cenc file")
    void audio(String name, String key) {
        var reference = demux("audio_cenc_128iv.mp4", keys(KEY_1, false));
        EncryptedFixtures.assertSameData(name, reference, demux(name, keys(key, false)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "video_cenc_64iv.mp4, " + KEY_1,
            "video_cbc1.mp4, " + KEY_2,
            "video_cbcs_const_iv.mp4, " + KEY_2,
            "audio_cenc_128iv.mp4, " + KEY_1,
            "audio_cbcs.mp4, " + KEY_2,
    })
    // ffmpeg 7.1 decrypts the 10:1 pattern of the cens files otherwise than the cenc files' packets give
    @DisplayName("decrypts as ffmpeg does")
    void ffmpeg(String name, String key) {
        var file = Conformance.fetchListed("mpegff-" + name);
        var reference = EncryptedFixtures.ffmpegPacketHashes(file, HexFormat.of().parseHex(key), List.of());
        assertEquals(reference.get(0), EncryptedFixtures.md5s(demux(file, name, keys(key, false))), name);
    }

    @Test
    @DisplayName("needs the rolled key of the seig groups")
    void rolledKey() {
        var file = Conformance.fetchListed("mpegff-video_cenc_roll.mp4");
        assertThrows(MediaException.Unsupported.class, () -> demux(file, "roll", keys(KEY_1, false)));
    }

    // the multi-key files (ISO/IEC 23001-7:2023) protect each subsample with one of two keys, named in their seig
    // group, with an initialization vector per key in the sample auxiliary information (or a constant one per key)
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"video_cenc_mkey_base.mp4", "video_cenc_mkey_roll.mp4", "video_cenc_mkey_subs.mp4",
            "video_cbcs_mkey_const_iv_subs.mp4"})
    @DisplayName("decrypts every multi-key file, subsample by subsample, to the packets of the 8-byte IV cenc file")
    void multiKey(String name) {
        var reference = demux("video_cenc_64iv.mp4", keys(KEY_1, false));
        var keys = DecryptionKeys.ofHex(Map.of(KID_A, KEY_1, KID_C, KEY_C));
        EncryptedFixtures.assertSameData(name, reference, demux(name, keys));
        // the second key is needed as well
        var file = Conformance.fetchListed("mpegff-" + name);
        assertThrows(MediaException.Unsupported.class, () -> demux(file, name, keys(KEY_1, false)));
    }
}
