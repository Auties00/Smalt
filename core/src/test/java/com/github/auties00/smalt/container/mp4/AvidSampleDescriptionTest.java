package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the extensions of Avid's QuickTime sample descriptions of DNxHD ({@code ACLR},
 * {@code APRG}, {@code ARES}, {@code AALP}, {@code ADHR}) that a DNxHD track configuration carries
 * after its sample entry type: QuickTime, fragmented MP4 and Matroska write them after the color
 * table ID with a 32-bit depth when they declare alpha, the demuxers read them back, and anything
 * else after the type is rejected. The frames are opaque bytes; the muxers do not decode them.
 */
@DisplayName("Avid sample description extensions")
class AvidSampleDescriptionTest {
    /** The AALP atom Avid writes for 8-bit samples stored after the video. */
    static final byte[] AALP = atom("AALP", "AALP0001", 1, "AUA8", 0);

    /** An ACLR atom of video range. */
    static final byte[] ACLR = atom("ACLR", "ACLR0001", 1, null, 0);

    static byte[] atom(String type, String tag, int value, String fourCc, int last) {
        var size = 8 + tag.length() + 4 + 4 + (fourCc == null ? 0 : 4);
        var atom = new byte[size];
        atom[3] = (byte) size;
        System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, atom, 4, 4);
        System.arraycopy(tag.getBytes(StandardCharsets.US_ASCII), 0, atom, 8, tag.length());
        atom[8 + tag.length() + 3] = (byte) value;
        if (fourCc != null) {
            System.arraycopy(fourCc.getBytes(StandardCharsets.US_ASCII), 0, atom, 8 + tag.length() + 4, 4);
        }
        atom[size - 1] = (byte) last;
        return atom;
    }

    static byte[] config(byte[]... atoms) {
        var config = "AVdh".getBytes(StandardCharsets.US_ASCII);
        for (var atom : atoms) {
            var grown = Arrays.copyOf(config, config.length + atom.length);
            System.arraycopy(atom, 0, grown, config.length, atom.length);
            config = grown;
        }
        return config;
    }

    /** Returns a fake DNxHR frame: header version 3 and no alpha flag, which the muxers read. */
    static byte[] frame() {
        var frame = new byte[200];
        frame[3] = (byte) 0x80;
        frame[2] = 2;
        frame[4] = 3;
        return frame;
    }

    static VideoTrack track(byte[] config) {
        return VideoTrack.builder().id(1).codec(Codec.DNXHD).timeBase(new Rational(1, 25)).codecConfig(config)
                .width(64).height(32).build();
    }

    static Path mux(Path file, String kind, byte[] config) {
        var packet = new Packet(1, frame(), 0, 0, 1, true);
        switch (kind) {
            case "mov" -> {
                try (var muxer = new Mp4Muxer(MediaSink.of(file), Mp4MuxerOptions.quickTime())) {
                    muxer.addTrack(track(config));
                    muxer.writePacket(packet);
                    muxer.finish();
                }
            }
            case "fragmented" -> {
                try (var muxer = new FragmentedMp4Muxer(MediaSink.of(file))) {
                    muxer.addTrack(track(config));
                    muxer.writePacket(packet);
                    muxer.finish();
                }
            }
            default -> {
                try (var muxer = new MatroskaMuxer(MediaSink.of(file), MatroskaMuxerOptions.matroska())) {
                    muxer.addTrack(track(config));
                    muxer.writePacket(packet);
                    muxer.finish();
                }
            }
        }
        return file;
    }

    static byte[] demuxedConfig(Path file) throws Exception {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Files.readAllBytes(file)))) {
            var track = demuxer.tracks().getFirst();
            assertEquals(Codec.DNXHD, track.codec());
            var packet = demuxer.read();
            assertArrayEquals(frame(), packet.data());
            return track.codecConfig().orElseThrow();
        }
    }

    static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var i = 0; i + pattern.length <= data.length; i++) {
            for (var j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mov", "fragmented", "mkv"})
    @DisplayName("writes the extensions after the color table ID with a 32-bit depth for alpha, and reads them back")
    void roundTrip(String kind, @TempDir Path directory) throws Exception {
        for (var withAlpha : new boolean[]{true, false}) {
            var config = withAlpha ? config(ACLR, AALP) : config(ACLR);
            var file = mux(directory.resolve(kind + withAlpha), kind, config);
            var bytes = Files.readAllBytes(file);
            // the sample description: size, type, 78 fixed bytes ending with depth and color table ID
            var entry = indexOf(bytes, "AVdh".getBytes(StandardCharsets.US_ASCII)) - 4;
            assertEquals(withAlpha ? 32 : 24, (bytes[entry + 82] & 0xFF) << 8 | bytes[entry + 83] & 0xFF,
                    kind + ": depth");
            assertEquals(0xFFFF, (bytes[entry + 84] & 0xFF) << 8 | bytes[entry + 85] & 0xFF, kind);
            assertArrayEquals(Arrays.copyOfRange(config, 4, config.length),
                    Arrays.copyOfRange(bytes, entry + 86, entry + 86 + config.length - 4), kind + ": extensions");
            assertArrayEquals(config, demuxedConfig(file), kind + ": configuration read back");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mov", "fragmented", "mkv"})
    @DisplayName("rejects bytes after the sample entry type that are not Avid extensions")
    void rejectsOtherBytes(String kind, @TempDir Path directory) {
        var foreign = atom("colr", "nclc0001", 1, null, 0);
        for (var config : new byte[][]{config(foreign), config(ACLR, new byte[]{0, 0, 0, 9}),
                config(Arrays.copyOf(AALP, AALP.length - 1)), config(new byte[]{1, 2, 3})}) {
            assertThrows(IllegalArgumentException.class, () -> mux(directory.resolve(kind), kind, config), kind);
        }
    }

    @Test
    @DisplayName("keeps only the Avid atoms of a sample description, stopping at the first that does not fit")
    void extensionsOfDescription() {
        var pasp = atom("pasp", "", 1, "\0\0\0\1", 0);
        var data = Arrays.copyOf(ACLR, ACLR.length + pasp.length + AALP.length + 6);
        System.arraycopy(pasp, 0, data, ACLR.length, pasp.length);
        System.arraycopy(AALP, 0, data, ACLR.length + pasp.length, AALP.length);
        data[data.length - 3] = 99;
        var kept = CodecConfigs.avidExtensions(data, 0, data.length);
        assertArrayEquals(Arrays.copyOfRange(config(ACLR, AALP), 4, 4 + ACLR.length + AALP.length), kept);
        assertTrue(CodecConfigs.dnxhdAvidAlpha(config(ACLR, AALP)));
        assertFalse(CodecConfigs.dnxhdAvidAlpha(config(ACLR)));
        assertFalse(CodecConfigs.dnxhdAvidAlpha(null));
        assertFalse(CodecConfigs.dnxhdAvidAlpha(config(new byte[]{0, 0, 0, 1, 'A', 'A', 'L', 'P'})));
        assertEquals(0x41566468, CodecConfigs.quickTimeVisualType(false, config(AALP), null));
        // a ProRes configuration is the type alone; anything longer is ignored for the frame's type
        var prores = Arrays.copyOf("ap4h".getBytes(StandardCharsets.US_ASCII), 8);
        assertEquals(0x6170636E, CodecConfigs.quickTimeVisualType(true, prores, null));
        assertEquals(0x61703468, CodecConfigs.quickTimeVisualType(true, Arrays.copyOf(prores, 4), null));
    }
}
