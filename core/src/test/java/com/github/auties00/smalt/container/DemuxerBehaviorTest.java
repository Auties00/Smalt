package com.github.auties00.smalt.container;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts.SeekPrecision;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.testkit.io.BehaviorSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every demuxer through the I/O behavior harness ({@link DemuxerAsserts}) over the committed samples
 * of {@code src/test/resources/samples} (made by the scripts of {@code tools/testdata/behavior} and pinned in
 * {@code samples.tsv} there) and a selection of smoke-tier vectors, comparing each with its golden dump under
 * {@code src/test/resources/dumps}.
 */
@DisplayName("demuxer I/O behaviors")
class DemuxerBehaviorTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    /**
     * The smoke-tier vectors, by manifest name.
     */
    private static final List<String> VECTORS = List.of(
            "mpegff-09_text.mp4",
            "mpegff-22_tx3g.mp4",
            "mpegff-a4-tone-fragmented.mp4",
            "mpegff-a9-aac-samplegroups-edit.mp4",
            "mpegff-13_long.mp4",
            "mpegff-timed-metadata.mp4",
            "mpegff-male_amr122.3gp",
            "av1-av1-1-b8-03-sizeup.mkv",
            "vp90-2-02-size-64x64.webm",
            "av1-av1-1-b8-01-size-16x16.ivf",
            "vorbis-chain-test3.ogg",
            "vorbis-test-short.ogg",
            "flac-cellar-subset-60.flac",
            "flac-cellar-subset-38.flac",
            "eac3-csi_miami_5.1_256_spx_small.eac3",
            "mpeg4audio-al00_08.wav");

    /**
     * The seek precision of the samples whose index is coarser than their packets, by sample path.
     */
    private static final Map<String, SeekPrecision> PRECISION = Map.of(
            // a cue that started earlier and is still shown at the target is returned first
            "subtitle/cues.ass", SeekPrecision.AT_OR_BEFORE);

    static Stream<String> samples() {
        return BehaviorSamples.list(SAMPLES).stream();
    }

    static Stream<String> vectors() {
        return VECTORS.stream();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "chromium-bear-640x360-a_frag-cenc.mp4, 30313233343536373839303132333435, ebdd62f16814d27b68ef122afce4ae3c",
            "chromium-bear-640x360-a_frag-cbcs.mp4, 30313233343536373839303132333435, ebdd62f16814d27b68ef122afce4ae3c",
            "chromium-bear-640x360-v_frag-cenc.mp4, 30313233343536373839303132333435, ebdd62f16814d27b68ef122afce4ae3c",
            "chromium-bear-640x360-v_frag-cbcs.mp4, 30313233343536373839303132333435, ebdd62f16814d27b68ef122afce4ae3c",
            "chromium-bear-opus-cenc.mp4, 30313233343536373839303132333435, ebdd62f16814d27b68ef122afce4ae3c",
            "mpegff-video_cenc_64iv.mp4, 279926496a7f5d25da69f2b3b2799a7f, 5544694d47473326622665665a396b36",
            "mpegff-audio_cbcs_const_iv.mp4, 279926496a7f5d25da69f2b3b2799a7f, ccc0f2b3b279926496a7f5d25da692f6",
            "chromium-bear-320x240-av_enc-av.webm, 30313233343536373839303132333435, ebdd62f16814d27b68ef122afce4ae3c",
            "chromium-bear-320x240-v-vp9_subsample_enc-v.webm, 30313233343536373839303132333435, ebdd62f16814d27b68ef122afce4ae3c",
    })
    @DisplayName("protected vectors: identical decrypted packets under every source behavior, clean faults, contract seeks")
    void encrypted(String name, String keyId, String key) throws IOException {
        var data = Files.readAllBytes(Conformance.fetchListed(name));
        var options = DemuxerOptions.builder().decryptionKeys(DecryptionKeys.ofHex(Map.of(keyId, key))).build();
        DemuxerAsserts.of("encrypted/" + name, data)
                .factory(source -> name.endsWith(".webm") ? new MatroskaDemuxer(source, options)
                        : new Mp4Demuxer(source, options))
                .run();
    }

    // the encrypted seeds of the fuzz targets: ffmpeg's progressive cenc and Shaka Packager's fragmented cenc,
    // cbc1, cens and cbcs and WebM encryption of small synthetic clips, made by tools/testdata/cenc/make-seeds.sh
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "mp4Encrypted/valid-cenc-progressive.mp4", "mp4Encrypted/valid-cenc-video.mp4",
            "mp4Encrypted/valid-cbc1-video.mp4", "mp4Encrypted/valid-cens-video.mp4",
            "mp4Encrypted/valid-cbcs-video.mp4", "mp4Encrypted/valid-cbcs-audio.mp4",
            "matroskaEncrypted/valid-webm-video.webm", "matroskaEncrypted/valid-webm-audio.webm",
    })
    @DisplayName("protected seeds: identical decrypted packets under every source behavior, clean faults, contract seeks")
    void encryptedSeeds(String name) throws IOException {
        var data = Files.readAllBytes(Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "fuzz",
                "DemuxerFuzzTestInputs").resolve(name));
        var options = DemuxerOptions.builder().decryptionKeys(DecryptionKeys.ofHex(Map.of(
                "30313233343536373839303132333435", "ebdd62f16814d27b68ef122afce4ae3c"))).build();
        DemuxerAsserts.of("encrypted/" + name.substring(name.indexOf('/') + 1), data)
                .factory(source -> name.endsWith(".webm") ? new MatroskaDemuxer(source, options)
                        : new Mp4Demuxer(source, options))
                .run();
    }

    @Test
    @DisplayName("the samples are the files pinned in tools/testdata/behavior/samples.tsv")
    void pinned() {
        BehaviorSamples.assertPinned(SAMPLES);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("samples: identical packets under every source behavior, clean faults, contract seeks")
    void sample(String sample) throws IOException {
        var data = Files.readAllBytes(SAMPLES.resolve(sample));
        DemuxerAsserts.of(sample, data)
                .seekPrecision(PRECISION.getOrDefault(sample, SeekPrecision.EXACT))
                .run();
    }

    @Test
    @DisplayName("Matroska with a damaged cluster identifier: identical resynchronization under every source behavior")
    void damagedMatroska() throws IOException {
        var data = Files.readAllBytes(SAMPLES.resolve("matroska/vp9-vorbis.webm"));
        var cluster = new byte[]{0x1F, 0x43, (byte) 0xB6, 0x75};
        var first = indexOf(data, cluster, 0);
        var second = indexOf(data, cluster, first + 4);
        assertTrue(first > 0 && second > first, "the sample has two clusters");
        Arrays.fill(data, second, second + 4, (byte) 0);
        DemuxerAsserts.of("matroska/damaged-cluster.webm", data).run();
    }

    @Test
    @DisplayName("WAVE with an odd-sized chunk missing its pad byte: identical packets under every source behavior")
    void unpaddedWave() {
        var out = new ByteArrayOutputStream();
        var samples = new byte[4096];
        for (var i = 0; i < samples.length; i++) {
            samples[i] = (byte) (i * 7);
        }
        // the data size 4096 has a low byte of 0, which no chunk identifier holds, so only the unpadded
        // offset holds a plausible identifier
        writeTag(out, "RIFF");
        writeInt(out, 4 + 8 + 16 + 8 + 3 + 8 + samples.length);
        writeTag(out, "WAVE");
        writeTag(out, "fmt ");
        writeInt(out, 16);
        out.writeBytes(new byte[]{1, 0, 1, 0, 0x40, 0x1F, 0, 0, (byte) 0x80, 0x3E, 0, 0, 2, 0, 16, 0});
        writeTag(out, "odd ");
        writeInt(out, 3);
        out.writeBytes(new byte[]{'a', 'b', 'c'});
        writeTag(out, "data");
        writeInt(out, samples.length);
        out.writeBytes(samples);
        DemuxerAsserts.of("wav/unpadded-odd-chunk.wav", out.toByteArray()).run();
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        for (var i = from; i + pattern.length <= data.length; i++) {
            if (Arrays.equals(data, i, i + pattern.length, pattern, 0, pattern.length)) {
                return i;
            }
        }
        return -1;
    }

    private static void writeTag(ByteArrayOutputStream out, String tag) {
        out.writeBytes(tag.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("smoke vectors: identical packets under every source behavior, clean faults, contract seeks")
    void vector(String name) throws IOException {
        var data = Files.readAllBytes(Conformance.fetchListed(name));
        DemuxerAsserts.of("vectors/" + name, data)
                .seekPrecision(PRECISION.getOrDefault(name, SeekPrecision.EXACT))
                .run();
    }
}
