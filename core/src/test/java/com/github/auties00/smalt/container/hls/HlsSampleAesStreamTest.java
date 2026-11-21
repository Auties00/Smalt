package com.github.auties00.smalt.container.hls;

import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the decryption of a SAMPLE-AES transport stream presentation made by another packager with ffmpeg's: the
 * directory {@code -Dsmalt.test.sampleAesStream} names holds {@code local.m3u8}, its key and segments, and
 * {@code ffmpeg-clear.ts}, which {@code ffmpeg -allowed_extensions ALL -i local.m3u8 -map 0 -c copy -f mpegts} wrote
 * from them. The streams are third-party content, read locally and never committed: the first four segments of the
 * lowest variant of Unified Streaming's public demo
 * {@code https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel-sample-aes.ism/.m3u8}
 * (Unified Streaming Platform 1.13.0, H.264 and AAC, the media sequence number as the vector), 2026-11-04.
 *
 * <p>ffmpeg decrypts the units it still holds when a segment ends with the next segment's vector, so a few H.264
 * packets before each segment boundary differ (and fail to decode in ffmpeg's output); the packets Smalt decrypts,
 * muxed again, decode without an error.
 */
@DisplayName("SAMPLE-AES presentation of another packager")
class HlsSampleAesStreamTest {
    /** Returns the hash of every packet, by the codec of its track. */
    private static Map<Codec, List<String>> hashes(Demuxer demuxer) throws Exception {
        var codecs = new HashMap<Integer, Codec>();
        for (var track : demuxer.tracks()) {
            codecs.put(track.id(), track.codec());
        }
        var result = new TreeMap<Codec, List<String>>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            result.computeIfAbsent(codecs.get(packet.trackId()), _ -> new ArrayList<>()).add(HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(packet.data())));
        }
        return result;
    }

    @Test
    @DisplayName("decrypts to the packets ffmpeg decrypts")
    void sameAsFfmpeg() throws Exception {
        var setting = System.getProperty("smalt.test.sampleAesStream");
        Skip.unless(setting != null && Files.isRegularFile(Path.of(setting, "local.m3u8")),
                "opt-in:sample-aes-stream", "-Dsmalt.test.sampleAesStream names no SAMPLE-AES presentation");
        var directory = Path.of(setting);
        Map<Codec, List<String>> smalt;
        Map<Codec, List<String>> ffmpeg;
        try (var demuxer = HlsDemuxer.open(directory.resolve("local.m3u8"))) {
            smalt = hashes(demuxer);
        }
        var remuxed = directory.resolve("smalt-decrypted.ts");
        try (var demuxer = HlsDemuxer.open(directory.resolve("local.m3u8")); var sink = MediaSink.of(remuxed);
             var muxer = new TsMuxer(sink)) {
            demuxer.tracks().forEach(muxer::addTrack);
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        Oracle.assertFfmpegDecodes(remuxed);
        try (var source = MediaSource.of(directory.resolve("ffmpeg-clear.ts")); var demuxer = new TsDemuxer(source)) {
            ffmpeg = hashes(demuxer);
        }
        assertEquals(java.util.Set.of(Codec.H264, Codec.AAC), smalt.keySet());
        assertEquals(smalt.keySet(), ffmpeg.keySet());
        for (var track : smalt.keySet()) {
            var want = ffmpeg.get(track);
            var got = smalt.get(track);
            assertTrue(want.size() > 50, want.size() + " packets");
            assertEquals(want.size(), got.size(), "track " + track);
            // ffmpeg flushes the packets still pending at the end of the playlist without decrypting them
            var mismatches = new ArrayList<Integer>();
            for (var index = 0; index < want.size() - 2; index++) {
                if (!want.get(index).equals(got.get(index))) {
                    mismatches.add(index);
                }
            }
            // at most two units held at each of the three segment boundaries
            assertTrue(track == Codec.H264 ? mismatches.size() <= 6 : mismatches.isEmpty(), "track " + track + " of "
                    + want.size() + " packets differs at " + mismatches);
        }
    }
}
