package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@link MatroskaMuxer#updateCodecConfig(int, byte[])}: a FLAC track written with an
 * encoder's initial {@code STREAMINFO} (no length, no MD5 signature) gets the final one in place on
 * a seekable sink, through the {@code Void} reserved after {@code CodecPrivate}, and keeps the
 * initial one on a stream and in live output. The input is an ffmpeg-made FLAC file whose real
 * {@code STREAMINFO} plays the final configuration; ffmpeg decoding the output to PCM whose MD5 is
 * the stored signature is the black-box check that the rewrite reached the file.
 */
@DisplayName("MatroskaMuxer FLAC final configuration")
class MatroskaFlacConfigTest {
    record Source(AudioTrack track, List<Packet> packets, byte[] finalConfig, byte[] initialConfig) {
    }

    static Source source() {
        var file = Fixtures.ffmpeg("flac", List.of("-f", "lavfi", "-i",
                "aevalsrc=exprs=0.5*sin(2*PI*440*t)|0.3*sin(2*PI*660*t):s=44100:d=1.7"), List.of("-c:a", "flac",
                "-sample_fmt", "s16"));
        var demuxed = MuxSupport.demux(Fixtures.read(file));
        var track = (AudioTrack) demuxed.tracks().getFirst();
        var finalConfig = track.codecConfig().orElseThrow();
        var initial = finalConfig.clone();
        initial[21] &= (byte) 0xF0;
        Arrays.fill(initial, 22, 42, (byte) 0);
        Arrays.fill(initial, 12, 18, (byte) 0);
        assertNotEquals(0, Arrays.hashCode(Arrays.copyOfRange(finalConfig, 26, 42)) - Arrays.hashCode(new byte[16]));
        return new Source(track.toBuilder().codecConfig(initial).build(), demuxed.packetsOf(track), finalConfig, initial);
    }

    static void mux(Source source, MediaSink sink, MatroskaMuxerOptions options, byte[] update, boolean early) {
        try (var muxer = new MatroskaMuxer(sink, options)) {
            muxer.addTrack(source.track());
            if (early) {
                muxer.updateCodecConfig(source.track().id(), update);
            }
            source.packets().forEach(muxer::writePacket);
            if (!early && update != null) {
                muxer.updateCodecConfig(source.track().id(), update);
            }
            muxer.finish();
        }
    }

    static byte[] toMemory(Source source, MatroskaMuxerOptions options, byte[] update, boolean early) {
        var sink = MediaSink.memory();
        mux(source, sink, options, update, early);
        return sink.toByteArray();
    }

    static byte[] privateData(byte[] file) {
        return MatroskaStructure.child(MatroskaStructure.check(file).tracks.getFirst(), EbmlIds.CODEC_PRIVATE).payload();
    }

    static byte[] pcmMd5(Path file) throws Exception {
        var pcm = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:a:0", "-f", "s16le", "-"));
        return MessageDigest.getInstance("MD5").digest(pcm);
    }

    static long totalSamples(byte[] config) {
        var value = config[21] & 0x0FL;
        for (var index = 22; index < 26; index++) {
            value = value << 8 | (config[index] & 0xFF);
        }
        return value;
    }

    @ParameterizedTest(name = "before the first packet: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("stores the final STREAMINFO on a seekable sink, which ffmpeg verifies against the decoded PCM")
    void seekable(boolean early) throws Exception {
        var source = source();
        var output = toMemory(source, MatroskaMuxerOptions.matroska(), source.finalConfig(), early);
        var structure = MatroskaStructure.check(output);
        var entry = structure.tracks.getFirst();
        var codecPrivate = MatroskaStructure.child(entry, EbmlIds.CODEC_PRIVATE);
        assertArrayEquals(AudioConfigs.flacContainerConfig(source.finalConfig()), codecPrivate.payload());
        var next = entry.get(entry.indexOf(codecPrivate) + 1);
        assertEquals(EbmlIds.VOID, next.id(), "the reserved Void follows CodecPrivate");
        var read = MuxSupport.demux(output);
        var config = read.tracks().getFirst().codecConfig().orElseThrow();
        assertArrayEquals(Arrays.copyOfRange(source.finalConfig(), 8, 42), Arrays.copyOfRange(config, 8, 42));
        MuxSupport.assertSamePayloads(MuxSupport.demux(toMemory(source, MatroskaMuxerOptions.matroska(), null, false)),
                read, java.util.Map.of(read.tracks().getFirst().id(), read.tracks().getFirst().id()));

        var path = MuxSupport.save("flac-final-" + early + ".mkv", output);
        MuxSupport.assertFfmpegClean("flac-final-clean-" + early + ".mkv", output);
        assertArrayEquals(Arrays.copyOfRange(config, 26, 42), pcmMd5(path), "MD5 signature of the decoded samples");
        var decoded = Oracle.decodeAudio(path);
        assertEquals(totalSamples(config) * 2, decoded.length, "total samples");
        var extradata = ContainerProbe.probe(path).streams().getFirst().extradata();
        var streamInfo = extradata.length == 34 ? extradata : Arrays.copyOfRange(extradata, 8, 42);
        assertArrayEquals(Arrays.copyOfRange(source.finalConfig(), 8, 42), streamInfo, "STREAMINFO ffprobe reads");
    }

    @Test
    @DisplayName("keeps the initial STREAMINFO on a stream and in live output")
    void notRewritten() throws Exception {
        var source = source();
        var stream = new ByteArrayOutputStream();
        try (var sink = MediaSink.of(stream)) {
            mux(source, sink, MatroskaMuxerOptions.matroska(), source.finalConfig(), false);
        }
        var expected = AudioConfigs.flacContainerConfig(source.initialConfig());
        assertArrayEquals(expected, privateData(stream.toByteArray()));
        var live = MatroskaMuxerOptions.matroska().toBuilder().live(true).build();
        assertArrayEquals(expected, privateData(toMemory(source, live, source.finalConfig(), false)));
        MuxSupport.assertFfmpegClean("flac-stream.mkv", stream.toByteArray());
    }

    @Test
    @DisplayName("rewrites a longer final configuration while it fits the reserved Void, growing the size field past one Void byte")
    void longer() {
        var source = source();
        var initialLength = AudioConfigs.flacContainerConfig(source.initialConfig()).length;
        var area = 2 + sizeLength(initialLength) + initialLength + 64;
        var outcomes = new java.util.TreeSet<Integer>();
        for (var extra = 52; extra <= 72; extra++) {
            var container = AudioConfigs.flacContainerConfig(source.finalConfig());
            var longer = Arrays.copyOf(container, container.length + extra);
            for (var offset = 4; offset < container.length; ) {
                var size = (container[offset + 1] & 0xFF) << 16 | (container[offset + 2] & 0xFF) << 8 | (container[offset + 3] & 0xFF);
                longer[offset] &= 0x7F;
                offset += 4 + size;
            }
            var body = extra - 4;
            longer[container.length] = (byte) 0x82;
            longer[container.length + 1] = (byte) (body >> 16);
            longer[container.length + 2] = (byte) (body >> 8);
            longer[container.length + 3] = (byte) body;
            for (var index = 4; index < extra; index++) {
                longer[container.length + index] = (byte) (index * 7);
            }
            var rest = area - 2 - sizeLength(longer.length) - longer.length;
            outcomes.add(Math.min(rest, 2));
            var output = toMemory(source, MatroskaMuxerOptions.matroska(), longer, false);
            var entry = MatroskaStructure.check(output).tracks.getFirst();
            var element = MatroskaStructure.child(entry, EbmlIds.CODEC_PRIVATE);
            var position = entry.indexOf(element);
            var following = position + 1 < entry.size() ? entry.get(position + 1).id() : -1;
            if (rest < 0) {
                assertArrayEquals(AudioConfigs.flacContainerConfig(source.initialConfig()), element.payload(),
                        "a final configuration " + extra + " bytes longer keeps the initial one");
                continue;
            }
            assertArrayEquals(longer, element.payload(), "a final configuration " + extra + " bytes longer fits");
            assertArrayEquals(longer, MuxSupport.demux(output).tracks().getFirst().codecConfig().orElseThrow());
            var fieldLength = Integer.numberOfLeadingZeros(output[element.offset() + 2] & 0xFF) - 23;
            assertEquals(rest == 1 ? sizeLength(longer.length) + 1 : sizeLength(longer.length), fieldLength,
                    "size field of a configuration " + extra + " bytes longer");
            assertEquals(rest >= 2, following == EbmlIds.VOID, "Void after a configuration " + extra + " bytes longer");
            if (rest == 1) {
                MuxSupport.assertFfmpegClean("flac-longer.mkv", output);
            }
        }
        assertEquals(java.util.Set.of(-1, 0, 1, 2), java.util.Set.of(outcomes.toArray(Integer[]::new))
                .stream().map(value -> Math.max(value, -1)).collect(java.util.stream.Collectors.toSet()),
                "the lengths cover a remaining Void, an exact fit, a one-byte remainder and an overflow");
    }

    @Test
    @DisplayName("rewrites a shorter final configuration, the Void after it taking up the bytes it frees")
    void shorter() throws Exception {
        var source = source();
        var initial = AudioConfigs.flacContainerConfig(source.initialConfig());
        // the initial configuration carries a 40-byte APPLICATION block the final one lacks
        var withApplication = Arrays.copyOf(initial, initial.length + 44);
        for (var offset = 4; offset < initial.length; ) {
            var size = (initial[offset + 1] & 0xFF) << 16 | (initial[offset + 2] & 0xFF) << 8 | (initial[offset + 3] & 0xFF);
            withApplication[offset] &= 0x7F;
            offset += 4 + size;
        }
        withApplication[initial.length] = (byte) 0x82;
        withApplication[initial.length + 3] = 40;
        Arrays.fill(withApplication, initial.length + 4, withApplication.length, (byte) 0x5A);
        var padded = new Source(source.track().toBuilder().codecConfig(withApplication).build(), source.packets(),
                source.finalConfig(), withApplication);
        var output = toMemory(padded, MatroskaMuxerOptions.matroska(), source.finalConfig(), false);
        var unchanged = toMemory(padded, MatroskaMuxerOptions.matroska(), null, false);
        assertEquals(unchanged.length, output.length, "the rewrite stays in place");
        var entry = MatroskaStructure.check(output).tracks.getFirst();
        var element = MatroskaStructure.child(entry, EbmlIds.CODEC_PRIVATE);
        var expected = AudioConfigs.flacContainerConfig(source.finalConfig());
        assertArrayEquals(expected, element.payload());
        assertEquals(EbmlIds.VOID, entry.get(entry.indexOf(element) + 1).id());
        var before = MatroskaStructure.check(unchanged).tracks.getFirst();
        var beforePrivate = MatroskaStructure.child(before, EbmlIds.CODEC_PRIVATE);
        assertArrayEquals(withApplication, beforePrivate.payload());
        assertEquals(before.get(before.indexOf(beforePrivate) + 2).offset(), entry.get(entry.indexOf(element) + 2).offset(),
                "the element after the Void keeps its offset");
        assertArrayEquals(expected, MuxSupport.demux(output).tracks().getFirst().codecConfig().orElseThrow());
        var path = MuxSupport.save("flac-shorter.mkv", output);
        MuxSupport.assertFfmpegClean("flac-shorter-clean.mkv", output);
        assertArrayEquals(Arrays.copyOfRange(source.finalConfig(), 26, 42), pcmMd5(path), "MD5 signature of the decoded samples");
    }

    @Test
    @DisplayName("rewrites the CodecPrivate of every FLAC track behind a data track whose entry shrinks its size fields")
    void severalTracks() throws Exception {
        var source = source();
        // a 300-byte CodecPrivate makes the first TrackEntry need a two-byte size, so every later
        // offset in Tracks moves when the size fields are shortened
        var data = com.github.auties00.smalt.track.DataTrack.builder().id(10).timeBase(Rational.MILLISECONDS)
                .handler("M_SMALT/TEST").codecConfig(new byte[300]).build();
        var first = source.track().toBuilder().id(11).build();
        var second = source.track().toBuilder().id(12).build();
        var sink = MediaSink.memory();
        try (var muxer = new MatroskaMuxer(sink)) {
            muxer.addTrack(data);
            muxer.addTrack(first);
            muxer.addTrack(second);
            muxer.writePacket(new Packet(10, new byte[]{1, 2, 3}, 0, 0, 100, true));
            for (var packet : source.packets()) {
                muxer.writePacket(packet.withTrackId(11));
                muxer.writePacket(packet.withTrackId(12));
            }
            muxer.updateCodecConfig(12, source.finalConfig());
            muxer.updateCodecConfig(11, source.finalConfig());
            muxer.finish();
        }
        var output = sink.toByteArray();
        var structure = MatroskaStructure.check(output);
        assertEquals(3, structure.tracks.size());
        assertEquals(300, MatroskaStructure.child(structure.tracks.getFirst(), EbmlIds.CODEC_PRIVATE).payload().length);
        var expected = AudioConfigs.flacContainerConfig(source.finalConfig());
        for (var index = 1; index < 3; index++) {
            var entry = structure.tracks.get(index);
            var codecPrivate = MatroskaStructure.child(entry, EbmlIds.CODEC_PRIVATE);
            assertArrayEquals(expected, codecPrivate.payload(), "CodecPrivate of track " + index);
            assertEquals(EbmlIds.VOID, entry.get(entry.indexOf(codecPrivate) + 1).id(), "Void after track " + index);
        }
        var read = MuxSupport.demux(output);
        for (var index = 1; index < 3; index++) {
            var config = read.tracks().get(index).codecConfig().orElseThrow();
            assertArrayEquals(Arrays.copyOfRange(source.finalConfig(), 8, 42), Arrays.copyOfRange(config, 8, 42));
            assertEquals(source.packets().size(), read.packetsOf(read.tracks().get(index)).size());
        }
        var path = MuxSupport.save("flac-final-several.mkv", output);
        MuxSupport.assertFfmpegClean("flac-final-several-clean.mkv", output);
        for (var stream = 0; stream < 2; stream++) {
            var pcm = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", path.toString(),
                    "-map", "0:a:" + stream, "-f", "s16le", "-"));
            assertArrayEquals(Arrays.copyOfRange(source.finalConfig(), 26, 42), MessageDigest.getInstance("MD5").digest(pcm),
                    "MD5 signature of audio stream " + stream);
        }
    }

    static int sizeLength(long value) {
        var length = 1;
        while (value >= (1L << (7 * length)) - 1) {
            length++;
        }
        return length;
    }

    @Test
    @DisplayName("ignores other codecs and rejects a configuration of another stream, an undeclared track and a late call")
    void rejections() {
        var source = source();
        var otherRate = source.finalConfig().clone();
        otherRate[18] ^= 0x10;
        try (var muxer = new MatroskaMuxer(MediaSink.memory())) {
            muxer.addTrack(source.track());
            assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(source.track().id(), otherRate));
            assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(source.track().id(), new byte[8]));
            assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(99, source.finalConfig()));
            muxer.finish();
            assertThrows(IllegalStateException.class, () -> muxer.updateCodecConfig(source.track().id(), source.finalConfig()));
        }
        var pcm = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000)
                .layout(ChannelLayout.MONO).bitsPerSample(16).build();
        var packets = List.of(new Packet(1, new byte[320], 0, 0, 160, true));
        var plain = MuxSupport.toMemory(List.of(pcm), packets, MatroskaMuxer::new);
        var sink = MediaSink.memory();
        try (var muxer = new MatroskaMuxer(sink)) {
            muxer.addTrack(pcm);
            packets.forEach(muxer::writePacket);
            muxer.updateCodecConfig(1, new byte[]{1, 2, 3});
            muxer.finish();
        }
        assertArrayEquals(plain, sink.toByteArray());
    }
}
