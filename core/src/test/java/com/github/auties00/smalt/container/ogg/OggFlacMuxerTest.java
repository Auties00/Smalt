package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Ogg FLAC mapping of {@link OggMuxer}: the identification packet and header pages,
 * granule positions, remuxing ffmpeg's FLAC to the same packets and PCM, the comment block from the
 * tags, the configuration or the options, the dropped native-file blocks, and the final
 * {@code STREAMINFO} rewritten in place on a seekable sink, checked by ffmpeg decoding to PCM
 * whose MD5 is the stored signature.
 */
@DisplayName("OggMuxer FLAC")
class OggFlacMuxerTest {
    record Source(AudioTrack track, List<Packet> packets) {
        byte[] config() {
            return track.codecConfig().orElseThrow();
        }
    }

    static Source source(int channels, String sampleFormat) {
        var expressions = channels == 1 ? "0.6*sin(2*PI*330*t)" : "0.5*sin(2*PI*440*t)|0.3*sin(2*PI*660*t)";
        var file = Fixtures.ffmpeg("flac", List.of("-f", "lavfi", "-i", "aevalsrc=exprs=" + expressions + ":s=44100:d=2.3"),
                List.of("-c:a", "flac", "-sample_fmt", sampleFormat));
        var demuxed = MuxSupport.demux(Fixtures.read(file));
        var track = (AudioTrack) demuxed.tracks().getFirst();
        assertEquals(Codec.FLAC, track.codec());
        return new Source(track, demuxed.packetsOf(track));
    }

    static Source source() {
        return source(2, "s16");
    }

    static void mux(MediaSink sink, OggMuxerOptions options, AudioTrack track, List<Packet> packets,
                    Consumer<OggMuxer> beforeFinish) {
        try (var muxer = new OggMuxer(sink, options)) {
            muxer.addTrack(track);
            packets.forEach(muxer::writePacket);
            beforeFinish.accept(muxer);
            muxer.finish();
        }
    }

    static byte[] toMemory(AudioTrack track, List<Packet> packets, Consumer<OggMuxer> beforeFinish) {
        var sink = MediaSink.memory();
        mux(sink, OggMuxerOptions.defaults(), track, packets, beforeFinish);
        return sink.toByteArray();
    }

    static byte[] withBlocks(byte[] config, byte[]... blocks) {
        var out = new ByteArrayOutputStream();
        out.write(config, 0, 4);
        var all = new ArrayList<byte[]>();
        for (var offset = 4; offset + 4 <= config.length; ) {
            var size = (config[offset + 1] & 0xFF) << 16 | (config[offset + 2] & 0xFF) << 8 | (config[offset + 3] & 0xFF);
            all.add(Arrays.copyOfRange(config, offset, offset + 4 + size));
            offset += 4 + size;
        }
        all.addAll(List.of(blocks));
        for (var index = 0; index < all.size(); index++) {
            var block = all.get(index).clone();
            block[0] = (byte) ((block[0] & 0x7F) | (index == all.size() - 1 ? 0x80 : 0));
            out.writeBytes(block);
        }
        return out.toByteArray();
    }

    static byte[] block(int type, int size, int fill) {
        var block = new byte[4 + size];
        block[0] = (byte) type;
        block[1] = (byte) (size >> 16);
        block[2] = (byte) (size >> 8);
        block[3] = (byte) size;
        Arrays.fill(block, 4, block.length, (byte) fill);
        return block;
    }

    static byte[] pcmMd5(Path file, String format) throws Exception {
        var pcm = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:a:0", "-f", format, "-"));
        return MessageDigest.getInstance("MD5").digest(pcm);
    }

    static boolean contains(byte[] data, String text) {
        var needle = text.getBytes(StandardCharsets.UTF_8);
        for (var index = 0; index + needle.length <= data.length; index++) {
            if (Arrays.equals(data, index, index + needle.length, needle, 0, needle.length)) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest(name = "{0} channels")
    @ValueSource(ints = {1, 2})
    @DisplayName("remuxes ffmpeg's FLAC to the same configuration, packets and PCM, with the mapping's header pages")
    void remux(int channels) {
        var source = source(channels, channels == 1 ? "s32" : "s16");
        var output = toMemory(source.track(), source.packets(), muxer -> {
        });
        var pages = OggMuxerTest.pages(output);
        var head = pages.getFirst();
        assertEquals(OggPageReader.FLAG_BOS, head.flags());
        assertEquals(0, head.granule());
        assertEquals(1, head.packetsCompleted());
        var body = head.body();
        assertEquals(51, body.length);
        assertEquals(0x7F, body[0] & 0xFF);
        assertEquals("FLAC", new String(body, 1, 4, StandardCharsets.US_ASCII));
        assertEquals(1, body[5]);
        assertEquals(0, body[6]);
        assertEquals(1, (body[7] & 0xFF) << 8 | (body[8] & 0xFF), "header packets after the first");
        assertEquals("fLaC", new String(body, 9, 4, StandardCharsets.US_ASCII));
        assertEquals(0, body[13], "STREAMINFO without the last-block flag");
        assertArrayEquals(Arrays.copyOfRange(source.config(), 5, 42), Arrays.copyOfRange(body, 14, 51));
        var comment = pages.get(1);
        assertEquals(0, comment.granule());
        assertEquals(0x84, comment.body()[0] & 0xFF, "VORBIS_COMMENT, the last header block");
        var data = pages.get(2);
        assertEquals(0, data.flags() & OggPageReader.FLAG_CONTINUED);
        assertEquals(0xFF, data.body()[0] & 0xFF);
        assertEquals(0xF8, data.body()[1] & 0xFE);
        var total = 0L;
        for (var packet : source.packets()) {
            total += packet.duration();
        }
        assertEquals(OggPageReader.FLAG_EOS, pages.getLast().flags() & OggPageReader.FLAG_EOS);
        assertEquals(total, pages.getLast().granule());
        for (var index = 1; index < pages.size(); index++) {
            assertTrue(pages.get(index).granule() >= pages.get(index - 1).granule() || pages.get(index).granule() == -1);
        }

        var read = OggMuxerTest.demux(output);
        assertEquals(Codec.FLAC, read.track().codec());
        assertArrayEquals(source.config(), read.track().codecConfig().orElseThrow());
        assertEquals(source.packets().size(), read.packets().size());
        for (var index = 0; index < source.packets().size(); index++) {
            assertArrayEquals(source.packets().get(index).data(), read.packets().get(index).data(), "packet " + index);
            assertEquals(source.packets().get(index).pts(), read.packets().get(index).pts(), "pts " + index);
        }

        var path = MuxSupport.save("flac-" + channels + ".oga", output);
        Oracle.assertDecodesCleanly(path);
        assertEquals("flac", ContainerProbe.probe(path).streams().getFirst().codecName());
        var original = MuxSupport.save("flac-source-" + channels + ".flac", Fixtures.read(Fixtures.ffmpeg("flac",
                List.of("-f", "lavfi", "-i", "aevalsrc=exprs=" + (channels == 1 ? "0.6*sin(2*PI*330*t)"
                        : "0.5*sin(2*PI*440*t)|0.3*sin(2*PI*660*t)") + ":s=44100:d=2.3"),
                List.of("-c:a", "flac", "-sample_fmt", channels == 1 ? "s32" : "s16"))));
        assertArrayEquals(Oracle.decodeAudio(original), Oracle.decodeAudio(path));
    }

    @ParameterizedTest(name = "before the first packet: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("rewrites the identification page with the final STREAMINFO on a seekable sink")
    void finalStreamInfo(boolean early) throws Exception {
        var source = source();
        var initial = source.config().clone();
        initial[21] &= (byte) 0xF0;
        Arrays.fill(initial, 22, 42, (byte) 0);
        var track = source.track().toBuilder().codecConfig(initial).build();
        byte[] output;
        if (early) {
            var sink = MediaSink.memory();
            try (var muxer = new OggMuxer(sink)) {
                muxer.addTrack(track);
                muxer.updateCodecConfig(track.id(), source.config());
                source.packets().forEach(muxer::writePacket);
                muxer.finish();
            }
            output = sink.toByteArray();
        } else {
            output = toMemory(track, source.packets(), muxer -> muxer.updateCodecConfig(track.id(), source.config()));
        }
        var pages = OggMuxerTest.pages(output);
        assertArrayEquals(Arrays.copyOfRange(source.config(), 8, 42), Arrays.copyOfRange(pages.getFirst().body(), 17, 51));
        var path = MuxSupport.save("flac-final-" + early + ".oga", output);
        Oracle.assertDecodesCleanly(path);
        assertArrayEquals(Arrays.copyOfRange(source.config(), 26, 42), pcmMd5(path, "s16le"),
                "MD5 signature of the decoded samples");
        assertNotEquals(Arrays.toString(new byte[16]), Arrays.toString(Arrays.copyOfRange(source.config(), 26, 42)));
        var read = OggMuxerTest.demux(output);
        assertArrayEquals(source.config(), read.track().codecConfig().orElseThrow());

        var stream = new ByteArrayOutputStream();
        try (var sink = MediaSink.of(stream)) {
            mux(sink, OggMuxerOptions.defaults(), track, source.packets(),
                    muxer -> muxer.updateCodecConfig(track.id(), source.config()));
        }
        var streamed = OggMuxerTest.pages(stream.toByteArray());
        assertArrayEquals(Arrays.copyOfRange(initial, 8, 42), Arrays.copyOfRange(streamed.getFirst().body(), 17, 51),
                "a stream keeps the initial STREAMINFO");
        assertEquals(pages.size(), streamed.size());
    }

    @Test
    @DisplayName("drops SEEKTABLE and PADDING, keeps the other blocks after VORBIS_COMMENT and counts them")
    void blocks() {
        var source = source();
        var application = block(2, 12, 0x41);
        var config = withBlocks(source.config(), block(3, 36, 0), block(1, 100, 0), application);
        var track = source.track().toBuilder().codecConfig(config).build();
        var output = toMemory(track, source.packets(), muxer -> {
        });
        var pages = OggMuxerTest.pages(output);
        var head = pages.getFirst().body();
        assertEquals(2, (head[7] & 0xFF) << 8 | (head[8] & 0xFF));
        var read = OggMuxerTest.demux(output);
        var expected = withBlocks(Arrays.copyOf(source.config(), source.config().length), application);
        assertArrayEquals(expected, read.track().codecConfig().orElseThrow());
        Oracle.assertDecodesCleanly(MuxSupport.save("flac-blocks.oga", output));
    }

    @Test
    @DisplayName("builds the comment block from the tags, the configuration's own, or the configured vendor and comments")
    void comments() {
        var source = source();
        var tagged = new ByteArrayOutputStream();
        try (var muxer = new OggMuxer(MediaSink.of(tagged))) {
            muxer.addTrack(source.track());
            muxer.setTags(MediaTags.builder().add(StandardTag.TITLE, "Smalt FLAC").build());
            source.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        var taggedPages = OggMuxerTest.pages(tagged.toByteArray());
        assertTrue(contains(taggedPages.get(1).body(), "TITLE=Smalt FLAC"));
        assertTrue(contains(taggedPages.get(1).body(), "Smalt"));
        var probe = ContainerProbe.probe(MuxSupport.save("flac-tags.oga", tagged.toByteArray()));
        assertEquals("Smalt FLAC", probe.streams().getFirst().fields().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("tag:title")).map(Map.Entry::getValue).findFirst()
                .orElse(probe.format().get("tag:title")));

        var bare = Arrays.copyOf(source.config(), 42);
        bare[4] |= (byte) 0x80;
        var options = OggMuxerOptions.builder().vendor("vendor-x").comments(Map.of("ARTIST", "Someone")).build();
        var sink = MediaSink.memory();
        mux(sink, options, source.track().toBuilder().codecConfig(bare).build(), source.packets(), muxer -> {
        });
        var comment = OggMuxerTest.pages(sink.toByteArray()).get(1).body();
        assertTrue(contains(comment, "vendor-x"));
        assertTrue(contains(comment, "ARTIST=Someone"));

        var kept = OggMuxerTest.pages(toMemory(source.track(), source.packets(), muxer -> {
        })).get(1).body();
        var original = Arrays.copyOfRange(source.config(), 42, source.config().length);
        assertArrayEquals(original, kept, "the configuration's own comment block");
        assertFalse(contains(kept, "vendor-x"));
    }

    @Test
    @DisplayName("rewrites the identification page of every grouped FLAC stream on a seekable sink")
    void groupedStreams() throws Exception {
        var source = source();
        var initial = source.config().clone();
        initial[21] &= (byte) 0xF0;
        Arrays.fill(initial, 22, 42, (byte) 0);
        var first = source.track().toBuilder().id(5).codecConfig(initial).build();
        var second = source.track().toBuilder().id(6).codecConfig(initial).build();
        var sink = MediaSink.memory();
        try (var muxer = new OggMuxer(sink, OggMuxerOptions.defaults().toBuilder().serial(100).build())) {
            muxer.addTrack(first);
            muxer.addTrack(second);
            for (var packet : source.packets()) {
                muxer.writePacket(packet.withTrackId(5));
                muxer.writePacket(packet.withTrackId(6));
            }
            muxer.updateCodecConfig(6, source.config());
            muxer.updateCodecConfig(5, source.config());
            muxer.finish();
        }
        var output = sink.toByteArray();
        var pages = OggMuxerTest.pages(output);
        var heads = pages.stream().filter(page -> (page.flags() & OggPageReader.FLAG_BOS) != 0).toList();
        assertEquals(2, heads.size());
        assertEquals(List.of(100, 101), heads.stream().map(OggMuxerTest.Page::serial).toList());
        for (var head : heads) {
            assertArrayEquals(Arrays.copyOfRange(source.config(), 8, 42), Arrays.copyOfRange(head.body(), 17, 51),
                    "STREAMINFO of stream " + head.serial());
        }
        var path = MuxSupport.save("flac-grouped.oga", output);
        Oracle.assertDecodesCleanly(path);
        for (var stream = 0; stream < 2; stream++) {
            var pcm = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", path.toString(),
                    "-map", "0:a:" + stream, "-f", "s16le", "-"));
            assertArrayEquals(Arrays.copyOfRange(source.config(), 26, 42), MessageDigest.getInstance("MD5").digest(pcm),
                    "MD5 signature of stream " + stream);
        }
    }

    @Test
    @DisplayName("ends a truncated block chain at its last complete block")
    void truncatedBlocks() {
        var source = source();
        var application = block(2, 12, 0x41);
        var truncated = withBlocks(Arrays.copyOf(source.config(), 42), application, block(6, 100, 0));
        truncated = Arrays.copyOf(truncated, truncated.length - 90);
        var output = toMemory(source.track().toBuilder().codecConfig(truncated).build(), source.packets(), muxer -> {
        });
        var pages = OggMuxerTest.pages(output);
        var head = pages.getFirst().body();
        assertEquals(2, (head[7] & 0xFF) << 8 | (head[8] & 0xFF), "the comment and the APPLICATION block");
        var read = OggMuxerTest.demux(output);
        assertEquals(source.packets().size(), read.packets().size());
        var config = read.track().codecConfig().orElseThrow();
        assertTrue(contains(config, "AAAAAAAAAAAA"), "the APPLICATION block is kept");
        var types = new ArrayList<Integer>();
        for (var offset = 4; offset + 4 <= config.length; ) {
            types.add(config[offset] & 0x7F);
            offset += 4 + ((config[offset + 1] & 0xFF) << 16 | (config[offset + 2] & 0xFF) << 8 | (config[offset + 3] & 0xFF));
        }
        assertEquals(List.of(0, 4, 2), types, "STREAMINFO, VORBIS_COMMENT and APPLICATION, without the truncated PICTURE");
    }

    @Test
    @DisplayName("rejects invalid frames and configurations, and a final configuration of another stream")
    void rejections() {
        var source = source();
        try (var muxer = new OggMuxer(MediaSink.memory())) {
            assertThrows(IllegalArgumentException.class,
                    () -> muxer.addTrack(source.track().toBuilder().codecConfig(new byte[20]).build()));
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(source.track().toBuilder().codecConfig(null).build()));
            var badLength = source.config().clone();
            badLength[7] = 33;
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(source.track().toBuilder().codecConfig(badLength).build()));
            var zeroRate = source.config().clone();
            zeroRate[18] = 0;
            zeroRate[19] = 0;
            zeroRate[20] &= 0x0F;
            var noRate = assertThrows(IllegalArgumentException.class,
                    () -> muxer.addTrack(source.track().toBuilder().codecConfig(zeroRate).build()));
            assertTrue(noRate.getMessage().contains("sample rate"), noRate.getMessage());
            muxer.addTrack(source.track());
            assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(source.track().id(), badLength));
            assertThrows(IllegalArgumentException.class,
                    () -> muxer.writePacket(new Packet(source.track().id(), new byte[]{1, 2, 3, 4, 5, 6}, 0, 0, 1, true)));
            var otherChannels = source.config().clone();
            otherChannels[20] ^= 0x02;
            assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(source.track().id(), otherChannels));
            assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(source.track().id(), new byte[4]));
            assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(42, source.config()));
            source.packets().forEach(muxer::writePacket);
            // after the header pages the STREAMINFO is copied into the identification page, so a block
            // of another length must still be refused
            assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(source.track().id(), badLength));
            assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(source.track().id(), otherChannels));
            muxer.finish();
            assertThrows(IllegalStateException.class, () -> muxer.updateCodecConfig(source.track().id(), source.config()));
        }
        assertTrue(OggMuxer.supports(Codec.FLAC));
    }
}
