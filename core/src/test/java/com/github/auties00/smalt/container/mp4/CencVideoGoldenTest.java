package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.EncryptedFixtures;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encrypts VP9 and AV1 conformance vectors with the fragmented MP4 muxer under cenc and cbcs and compares every sample
 * (its bytes, initialization vector and subsample map) with what Shaka Packager writes for the same vector, key and
 * initialization vector (tools/testdata/cenc/make-video-golden.sh). The demuxer then decrypts the output back to the
 * clear samples.
 */
@DisplayName("Common Encryption of VP9 and AV1 against Shaka Packager")
class CencVideoGoldenTest {
    private static final byte[] KEY_ID = HexFormat.of().parseHex("0c1d2e3f405162738495a6b7c8d9eafb");
    private static final byte[] KEY = HexFormat.of().parseHex("8899aabbccddeeff0011223344556677");
    private static final byte[] CENC_IV = HexFormat.of().parseHex("f0e1d2c3b4a59687");
    private static final byte[] CBCS_IV = HexFormat.of().parseHex("f0e1d2c3b4a5968778695a4b3c2d1e0f");
    private static final Path GOLDEN = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "container", "mp4", "cenc-video", "cenc-video-shaka.txt");

    private static Map<String, List<String>> golden() throws IOException {
        var result = new LinkedHashMap<String, List<String>>();
        for (var line : Files.readAllLines(GOLDEN)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            result.computeIfAbsent(line.substring(0, line.indexOf(' ')), _ -> new ArrayList<>()).add(line);
        }
        return result;
    }

    static Stream<String> vectors() throws IOException {
        return golden().keySet().stream().map(key -> key.substring(0, key.lastIndexOf(':'))).distinct();
    }

    private static byte[] mux(Track track, List<Packet> packets, CommonEncryption encryption) {
        var out = new ByteArrayOutputStream();
        var options = FragmentedMp4MuxerOptions.builder().fragmentMicros(1_000_000);
        if (encryption != null) {
            options.encryption(encryption);
        }
        try (var sink = MediaSink.of(out); var muxer = new FragmentedMp4Muxer(sink, options.build())) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return out.toByteArray();
    }

    /**
     * Lists the samples of a fragmented MP4 file as tools/testdata/cenc/cenc-samples.py does.
     */
    static List<String> listing(String name, byte[] file) throws NoSuchAlgorithmException {
        var lines = new ArrayList<String>();
        var data = ByteBuffer.wrap(file);
        var tenc = new String(file, StandardCharsets.ISO_8859_1).indexOf("tenc");
        var ivSize = tenc >= 0 ? file[tenc + 11] & 0xFF : 0;
        var number = 0;
        for (var position = 0; position + 8 <= file.length; ) {
            var size = data.getInt(position) & 0xFFFFFFFFL;
            var type = new String(file, position + 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 1) {
                size = data.getLong(position + 8);
            }
            if (type.equals("moof")) {
                number = fragment(name, file, position, (int) (position + size), ivSize, number, lines);
            }
            position += (int) size;
        }
        return lines;
    }

    private static int[] child(byte[] file, int start, int end, String kind) {
        var data = ByteBuffer.wrap(file);
        for (var position = start; position + 8 <= end; ) {
            var size = data.getInt(position);
            if (new String(file, position + 4, 4, StandardCharsets.ISO_8859_1).equals(kind)) {
                return new int[]{position + 8, position + size};
            }
            position += size;
        }
        return null;
    }

    private static int fragment(String name, byte[] file, int moof, int end, int ivSize, int number,
                                List<String> lines) throws NoSuchAlgorithmException {
        var data = ByteBuffer.wrap(file);
        var traf = child(file, moof + 8, end, "traf");
        var tfhd = child(file, traf[0], traf[1], "tfhd");
        var flags = data.getInt(tfhd[0]) & 0xFFFFFF;
        var position = tfhd[0] + 8;
        long base = moof;
        if ((flags & 1) != 0) {
            base = data.getLong(position);
            position += 8;
        }
        if ((flags & 2) != 0) {
            position += 4;
        }
        if ((flags & 8) != 0) {
            position += 4;
        }
        var defaultSize = (flags & 0x10) != 0 ? data.getInt(position) : -1;
        var trun = child(file, traf[0], traf[1], "trun");
        var trunFlags = data.getInt(trun[0]) & 0xFFFFFF;
        var count = data.getInt(trun[0] + 4);
        position = trun[0] + 8;
        var offset = base;
        if ((trunFlags & 1) != 0) {
            offset += data.getInt(position);
            position += 4;
        }
        if ((trunFlags & 4) != 0) {
            position += 4;
        }
        var sizes = new int[count];
        for (var index = 0; index < count; index++) {
            if ((trunFlags & 0x100) != 0) {
                position += 4;
            }
            sizes[index] = defaultSize;
            if ((trunFlags & 0x200) != 0) {
                sizes[index] = data.getInt(position);
                position += 4;
            }
            if ((trunFlags & 0x400) != 0) {
                position += 4;
            }
            if ((trunFlags & 0x800) != 0) {
                position += 4;
            }
        }
        var senc = child(file, traf[0], traf[1], "senc");
        var sencFlags = senc == null ? 0 : data.getInt(senc[0]) & 0xFFFFFF;
        position = senc == null ? 0 : senc[0] + 8;
        for (var index = 0; index < count; index++) {
            var sample = java.util.Arrays.copyOfRange(file, (int) offset, (int) offset + sizes[index]);
            offset += sizes[index];
            var line = new StringBuilder(name + " " + number++ + " " + sample.length + " "
                    + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(sample)));
            if (senc != null) {
                var iv = HexFormat.of().formatHex(file, position, position + ivSize);
                position += ivSize;
                line.append(" iv=").append(iv.isEmpty() ? "-" : iv).append(' ');
                if ((sencFlags & 2) != 0) {
                    var subsamples = data.getShort(position) & 0xFFFF;
                    position += 2;
                    for (var pair = 0; pair < subsamples; pair++) {
                        line.append(pair > 0 ? "," : "").append(data.getShort(position) & 0xFFFF).append(':')
                                .append(data.getInt(position + 2) & 0xFFFFFFFFL);
                        position += 6;
                    }
                } else {
                    line.append("whole");
                }
            }
            lines.add(line.toString());
        }
        return number;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("writes every sample, initialization vector and subsample map as Shaka Packager does")
    void matchesShakaPackager(String vector) throws Exception {
        var golden = golden();
        Track track;
        List<Packet> packets;
        try (var source = MediaSource.of(Conformance.fetchListed(vector)); var demuxer = Containers.openDemuxer(source)) {
            track = demuxer.tracks().stream().filter(candidate -> candidate.kind() == TrackKind.VIDEO).findFirst()
                    .orElseThrow();
            packets = EncryptedFixtures.packets(demuxer).get(track.id());
        }
        // the remux the golden file starts from drops the temporal delimiter OBU that leads each IVF temporal unit
        packets = packets.stream().map(packet -> packet.data().length >= 2 && packet.data()[0] == 0x12
                && packet.data()[1] == 0 ? packet.withData(java.util.Arrays.copyOfRange(packet.data(), 2,
                packet.data().length)) : packet).toList();
        var clear = listing(vector + ":clear", mux(track, packets, null));
        assertEquals(String.join("\n", golden.get(vector + ":clear")), String.join("\n", clear), "the clear samples");
        var cenc = mux(track, packets, CommonEncryption.builder().scheme(CommonEncryption.Scheme.CENC).keyId(KEY_ID)
                .key(KEY).initializationVector(CENC_IV).build());
        assertEquals(String.join("\n", golden.get(vector + ":cenc")), String.join("\n", listing(vector + ":cenc",
                cenc)), "cenc");
        var cbcs = mux(track, packets, CommonEncryption.builder().scheme(CommonEncryption.Scheme.CBCS).keyId(KEY_ID)
                .key(KEY).initializationVector(CBCS_IV).build());
        assertEquals(String.join("\n", golden.get(vector + ":cbcs")), String.join("\n", listing(vector + ":cbcs",
                cbcs)), "cbcs");
        for (var file : List.of(cenc, cbcs)) {
            try (var source = MediaSource.of(file); var demuxer = new Mp4Demuxer(source, DemuxerOptions.builder()
                    .decryptionKeys(DecryptionKeys.of(KEY_ID, KEY)).build())) {
                var decrypted = EncryptedFixtures.packets(demuxer).values().iterator().next();
                EncryptedFixtures.assertSameData(vector, packets, decrypted);
            }
        }
    }

    @Test
    @DisplayName("the golden file covers VP9 superframes, resizing and tiles, and AV1 layers and lossless frames")
    void coverage() throws IOException {
        var names = golden().keySet();
        for (var vector : List.of("vp90-2-20-big_superframe-01.webm", "vp90-2-21-resize_inter_320x180_5_1-2.webm",
                "vp90-2-14-resize-fp-tiles-1-2-4-8-16.webm", "vp90-2-17-show-existing-frame.webm",
                "av1-av1-1-b8-22-svc-L2T2-2.ivf", "av1-av1-1-b10-00-quantizer-00.ivf", "av1-av1-1-b8-03-sizeup.mkv",
                "av1-av1-1-b8-23-film_grain-50.ivf")) {
            assertTrue(names.contains(vector + ":cbcs"), vector);
        }
    }
}
