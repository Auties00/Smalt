package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers a tagged raw FLAC stream whose encoder reports its final configuration after the first packet with a seek
 * table carved out of the padding, as the FLAC encoder does: the rebuilt comment block goes before the padding, so
 * the final header has the length of the one written and is stored in place. ffmpeg decoding the output to PCM whose
 * MD5 is the stored signature is the black-box check.
 */
@DisplayName("RawMuxer tagged FLAC final configuration")
class RawFlacTagsConfigTest {
    @Test
    @DisplayName("stores the final STREAMINFO and seek table of a tagged stream in place")
    void finalConfigWithTags() throws Exception {
        var file = Fixtures.ffmpeg("flac", List.of("-f", "lavfi", "-i",
                "aevalsrc=exprs=0.5*sin(2*PI*440*t):s=44100:d=1.3"), List.of("-c:a", "flac", "-sample_fmt", "s16"));
        var demuxed = MuxSupport.demux(Fixtures.read(file));
        var track = (AudioTrack) demuxed.tracks().getFirst();
        var streamInfo = Arrays.copyOfRange(track.codecConfig().orElseThrow(), 8, 42);
        var initialInfo = streamInfo.clone();
        initialInfo[13] &= (byte) 0xF0;
        Arrays.fill(initialInfo, 14, 34, (byte) 0);
        Arrays.fill(initialInfo, 4, 10, (byte) 0);
        var placeholder = new byte[18];
        Arrays.fill(placeholder, 0, 8, (byte) 0xFF);
        var initial = config(block(0, initialInfo, false), block(1, new byte[36], true));
        var fin = config(block(0, streamInfo, false), block(3, placeholder, false), block(1, new byte[14], true));
        assertEquals(initial.length, fin.length);

        var tags = MediaTags.builder().set(StandardTag.TITLE, "Tagged").set(StandardTag.ARTIST, "Encoder").build();
        var sink = MediaSink.memory();
        try (var muxer = new RawMuxer(sink)) {
            muxer.addTrack(track.toBuilder().codecConfig(initial).build());
            muxer.setTags(tags);
            demuxed.packetsOf(track).forEach(muxer::writePacket);
            muxer.updateCodecConfig(track.id(), fin);
            muxer.finish();
        }
        var output = sink.toByteArray();

        assertArrayEquals(Arrays.copyOfRange(streamInfo, 18, 34), Arrays.copyOfRange(output, 26, 42), "MD5 signature");
        var types = blockTypes(output);
        assertEquals(List.of(0, 3, 4, 1), types, "STREAMINFO, SEEKTABLE, VORBIS_COMMENT, PADDING");
        var read = TagEditor.read(MediaSource.of(output));
        assertEquals("Tagged", read.get(StandardTag.TITLE).orElseThrow());
        assertEquals("Encoder", read.get(StandardTag.ARTIST).orElseThrow());

        var path = MuxSupport.save("raw-flac-tagged-final.flac", output);
        var pcm = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", path.toString(),
                "-f", "s16le", "-"));
        assertArrayEquals(Arrays.copyOfRange(streamInfo, 18, 34), MessageDigest.getInstance("MD5").digest(pcm),
                "MD5 signature of the decoded samples");
        assertTrue(pcm.length > 0);
    }

    private static byte[] block(int type, byte[] body, boolean last) {
        var block = new byte[4 + body.length];
        block[0] = (byte) (type | (last ? 0x80 : 0));
        block[1] = (byte) (body.length >>> 16);
        block[2] = (byte) (body.length >>> 8);
        block[3] = (byte) body.length;
        System.arraycopy(body, 0, block, 4, body.length);
        return block;
    }

    private static byte[] config(byte[]... blocks) {
        var length = 4;
        for (var block : blocks) {
            length += block.length;
        }
        var config = Arrays.copyOf("fLaC".getBytes(java.nio.charset.StandardCharsets.US_ASCII), length);
        var offset = 4;
        for (var block : blocks) {
            System.arraycopy(block, 0, config, offset, block.length);
            offset += block.length;
        }
        return config;
    }

    private static List<Integer> blockTypes(byte[] file) {
        var types = new ArrayList<Integer>();
        var offset = 4;
        while (true) {
            var header = file[offset] & 0xFF;
            types.add(header & 0x7F);
            offset += 4 + ((file[offset + 1] & 0xFF) << 16 | (file[offset + 2] & 0xFF) << 8 | (file[offset + 3] & 0xFF));
            if ((header & 0x80) != 0) {
                return types;
            }
        }
    }
}
