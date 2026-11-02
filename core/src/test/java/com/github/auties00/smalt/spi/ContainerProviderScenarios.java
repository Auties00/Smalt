package com.github.auties00.smalt.spi;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.container.y4m.Y4mDemuxer;
import com.github.auties00.smalt.container.y4m.Y4mMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagReadOptions;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.metadata.TagWriteResult;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleReader;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checks {@link ContainerProviderTest} runs in a child JVM on the class path, where a {@code META-INF/services}
 * file registers the providers of this package. Each check prints {@code PASS <name>} or {@code FAIL <name>} and the
 * stack trace of its failure.
 */
public final class ContainerProviderScenarios {
    /** The sample rate of the toy audio file. */
    private static final int RATE = 8_000;

    /** The frames of each toy audio packet. */
    private static final int FRAMES = 400;

    /** The number of toy audio packets. */
    private static final int PACKETS = 10;

    private ContainerProviderScenarios() {
    }

    /**
     * Runs the checks of a scenario.
     *
     * @param args the scenario, {@code "registered"} or {@code "invalid"}, then for the first a scratch directory and
     * for the second the class name the failure must name
     */
    public static void main(String[] args) throws Exception {
        var checks = new LinkedHashMap<String, Check>();
        if (args[0].equals("invalid")) {
            checks.put("invalid provider fails lookups", () -> invalid(args[1]));
        } else {
            var scratch = Path.of(args[1]);
            checks.put("detect", ContainerProviderScenarios::detect);
            checks.put("detect after id3", ContainerProviderScenarios::detectAfterId3);
            checks.put("precedence", ContainerProviderScenarios::precedence);
            checks.put("probe length", ContainerProviderScenarios::probeLength);
            checks.put("demux", ContainerProviderScenarios::demux);
            checks.put("missing operation", ContainerProviderScenarios::missingOperation);
            checks.put("malformed", ContainerProviderScenarios::malformed);
            checks.put("mux", ContainerProviderScenarios::mux);
            checks.put("probe", ContainerProviderScenarios::probe);
            checks.put("audio reader", ContainerProviderScenarios::audioReader);
            checks.put("transcode", ContainerProviderScenarios::transcode);
            checks.put("tags", () -> tags(scratch));
            checks.put("subtitles", ContainerProviderScenarios::subtitles);
            checks.put("named format", ContainerProviderScenarios::namedFormat);
        }
        for (var check : checks.entrySet()) {
            try {
                check.getValue().run();
                System.out.println("PASS " + check.getKey());
            } catch (Throwable failure) {
                System.out.println("FAIL " + check.getKey());
                failure.printStackTrace(System.out);
            }
        }
        System.out.flush();
    }

    /** A named check. */
    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    /** {@return the stereo PCM track of the toy audio file} */
    static AudioTrack audioTrack() {
        return AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(RATE)).sampleRate(RATE)
                .layout(ChannelLayout.STEREO).duration((long) FRAMES * PACKETS).build();
    }

    /** {@return the packets of the toy audio file: a stereo pattern no two packets share} */
    static List<Packet> audioPackets() {
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < PACKETS; index++) {
            var data = new byte[FRAMES * 4];
            for (var frame = 0; frame < FRAMES; frame++) {
                var left = (short) (StrictMath.sin((index * FRAMES + frame) * 0.05) * 12_000);
                var right = (short) (frame * 37 - index * 1_001);
                data[frame * 4] = (byte) left;
                data[frame * 4 + 1] = (byte) (left >> 8);
                data[frame * 4 + 2] = (byte) right;
                data[frame * 4 + 3] = (byte) (right >> 8);
            }
            packets.add(new Packet(1, data, (long) index * FRAMES, (long) index * FRAMES, FRAMES, Set.of(PacketFlag.KEY),
                    PacketSideData.NONE));
        }
        return packets;
    }

    /** {@return the tags of the toy audio file} */
    static MediaTags audioTags() {
        return MediaTags.builder().set(StandardTag.TITLE, "Toy tone").set(StandardTag.ARTIST, "Smalt tests")
                .set("location", "+45.4642+009.1900/").build();
    }

    /** {@return the toy audio file} */
    static byte[] audioFile() {
        return ToyContainerProvider.file(audioTrack(), audioTags(), audioPackets());
    }

    /** {@return the concatenated data of the packets a demuxer reads} */
    static byte[] payload(Demuxer demuxer) {
        var out = new ByteArrayOutputStream();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            out.writeBytes(packet.data());
        }
        return out.toByteArray();
    }

    /** {@return the concatenated data of packets} */
    static byte[] payload(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        packets.forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    /** {@return an ID3v2.4 tag of {@code size} body bytes} */
    static byte[] id3(int size) {
        var tag = new byte[10 + size];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 4;
        tag[6] = (byte) ((size >> 21) & 0x7F);
        tag[7] = (byte) ((size >> 14) & 0x7F);
        tag[8] = (byte) ((size >> 7) & 0x7F);
        tag[9] = (byte) (size & 0x7F);
        return tag;
    }

    /** {@return a WAV file of the toy audio, written by core} */
    static byte[] wavFile() {
        var sink = MediaSink.memory();
        try (var muxer = new WavMuxer(sink)) {
            muxer.addTrack(audioTrack());
            audioPackets().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private static void detect() {
        var toy = MediaSource.of(audioFile());
        assertEquals(ContainerFormat.OTHER, Containers.detect(toy).orElseThrow());
        assertEquals("toy", Containers.detectName(toy).orElseThrow());
        var subrip = MediaSource.of("1\n00:00:01,000 --> 00:00:02,000\nHello\n".getBytes(StandardCharsets.UTF_8));
        // the throwing provider is consulted before the text heuristics and must not break them
        assertEquals(ContainerFormat.SUBRIP, Containers.detect(subrip).orElseThrow());
        assertEquals("subrip", Containers.detectName(subrip).orElseThrow());
        var unknown = new byte[600];
        Arrays.fill(unknown, (byte) 0x5A);
        assertTrue(Containers.detect(MediaSource.of(unknown)).isEmpty());
        assertTrue(Containers.detectName(MediaSource.of(unknown)).isEmpty());
    }

    private static void detectAfterId3() {
        var file = audioFile();
        var tagged = new byte[300 + 10 + file.length];
        System.arraycopy(id3(300), 0, tagged, 0, 310);
        System.arraycopy(file, 0, tagged, 310, file.length);
        var source = MediaSource.of(tagged);
        assertEquals("toy", Containers.detectName(source).orElseThrow());
        // the second question names where the bytes after the tag start
        assertEquals(310, ToyContainerProvider.lastOffset);
        try (var demuxer = Containers.openDemuxer(source)) {
            assertEquals("toy", demuxer.formatName());
            assertArrayEquals(payload(audioPackets()), payload(demuxer));
        }
        // a FLAC stream behind the same tag stays core's
        assertEquals(ContainerFormat.FLAC, Containers.detect(MediaSource.of(concat(id3(300),
                "fLaC".getBytes(StandardCharsets.US_ASCII), new byte[64]))).orElseThrow());
    }

    private static void precedence() {
        // the RIFF-claiming provider sees no WAV file: core recognizes it by its magic number first
        var wav = MediaSource.of(wavFile());
        assertEquals(ContainerFormat.WAV, Containers.detect(wav).orElseThrow());
        try (var demuxer = Containers.openDemuxer(wav)) {
            assertInstanceOf(WavDemuxer.class, demuxer);
            assertEquals("wav", demuxer.formatName());
        }
        // a RIFF form core does not know reaches the provider
        var riff = concat("RIFF".getBytes(StandardCharsets.US_ASCII), new byte[4], "XYZW".getBytes(StandardCharsets.US_ASCII),
                new byte[32]);
        assertEquals(ContainerFormat.OTHER, Containers.detect(MediaSource.of(riff)).orElseThrow());
        assertEquals("riff-claimer", Containers.detectName(MediaSource.of(riff)).orElseThrow());
        // the providers are asked before core's heuristics: core alone takes this file for SubRip
        assertEquals("toy", Containers.detectName(MediaSource.of(ambiguousFile())).orElseThrow());
    }

    /** {@return a file that starts with the toy magic number and then looks like SubRip text} */
    static byte[] ambiguousFile() {
        return concat(ToyContainerProvider.MAGIC, "\n00:00:01,000 --> 00:00:02,000\nHello\n"
                .getBytes(StandardCharsets.US_ASCII));
    }

    private static void probeLength() {
        // the provider asks for Integer.MAX_VALUE bytes and matches only when handed exactly the largest probe
        var large = new byte[100_000];
        System.arraycopy("CLAIM".getBytes(StandardCharsets.US_ASCII), 0, large, 0, 5);
        assertEquals("riff-claimer", Containers.detectName(MediaSource.of(large)).orElseThrow());
        var small = Arrays.copyOf(large, 9_000);
        assertNotEquals(java.util.Optional.of("riff-claimer"), Containers.detectName(MediaSource.of(small)));
    }

    private static void demux() {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(audioFile()))) {
            assertEquals(ContainerFormat.OTHER, demuxer.format());
            assertEquals("toy", demuxer.formatName());
            assertEquals(List.of((Track) audioTrack()), demuxer.tracks());
            assertEquals("Toy tone", demuxer.metadata().get("title"));
            var expected = audioPackets();
            for (var packet : expected) {
                var actual = demuxer.read();
                assertEquals(packet.pts(), actual.pts());
                assertEquals(packet.duration(), actual.duration());
                assertArrayEquals(packet.data(), actual.data());
            }
            assertEquals(null, demuxer.read());
        }
    }

    private static void missingOperation() {
        var riff = concat("RIFF".getBytes(StandardCharsets.US_ASCII), new byte[4], "XYZW".getBytes(StandardCharsets.US_ASCII),
                new byte[32]);
        var failure = assertThrows(MediaException.Unsupported.class, () -> Containers.openDemuxer(MediaSource.of(riff)));
        assertEquals("riff-claimer", failure.format());
        assertEquals(MediaException.Unsupported.Kind.FORMAT, failure.kind());
        assertTrue(failure.artifact().isEmpty());
        var tags = assertThrows(MediaException.Unsupported.class, () -> TagEditor.read(MediaSource.of(riff)));
        assertEquals("riff-claimer", tags.format());
        assertEquals("riff-claimer is not tag-editable by Smalt", tags.getMessage());
        var probe = assertThrows(MediaException.Unsupported.class, () -> Media.probe(MediaSource.of(riff)));
        assertEquals("riff-claimer", probe.format());
        var transcode = assertThrows(MediaException.Unsupported.class, () -> Transcoder.transcode(MediaSource.of(riff),
                MediaSink.memory(), TranscodeOptions.of(OutputContainer.WAV)));
        assertEquals("riff-claimer", transcode.format());
        var writer = assertThrows(MediaException.Unsupported.class, () -> Containers.openMuxer("riff-claimer",
                MediaSink.memory()));
        assertEquals("riff-claimer", writer.format());
        assertFalse(Containers.isMuxable("riff-claimer", Codec.PCM_S16LE));
        assertFalse(TagEditor.isSupported("riff-claimer"));
        var absent = assertThrows(MediaException.Unsupported.class, () -> Containers.openMuxer("absent",
                MediaSink.memory()));
        assertEquals("absent", absent.format());
        assertTrue(absent.artifact().isEmpty());
    }

    private static void malformed() {
        var file = audioFile();
        var truncated = Arrays.copyOf(file, file.length - 7);
        assertThrows(MediaException.Malformed.class, () -> Containers.openDemuxer(MediaSource.of(truncated)));
        assertThrows(MediaException.Malformed.class, () -> Media.probe(MediaSource.of(truncated)));
        assertThrows(MediaException.Malformed.class, () -> TagEditor.read(MediaSource.of(truncated)));
        // a provider plan that copies past the end of the file fails the edit before anything is written
        var over = concat("OVRC".getBytes(StandardCharsets.US_ASCII), new byte[16]);
        assertEquals("overcopy", Containers.detectName(MediaSource.of(over)).orElseThrow());
        assertTrue(TagEditor.read(MediaSource.of(over)).isEmpty());
        var failure = assertThrows(MediaException.Malformed.class, () -> TagEditor.write(over, audioTags(),
                TagWriteOptions.defaults()));
        assertTrue(failure.getMessage().contains("overcopy"), failure.getMessage());
    }

    private static void mux() {
        assertTrue(Containers.isMuxable("toy", Codec.PCM_S16LE));
        assertFalse(Containers.isMuxable("toy", Codec.OPUS));
        assertFalse(Containers.isMuxable(ContainerFormat.OTHER, Codec.PCM_S16LE));
        assertThrows(IllegalArgumentException.class, () -> Containers.openMuxer(ContainerFormat.OTHER,
                MediaSink.memory()));
        assertThrows(IllegalArgumentException.class, () -> Containers.openMuxer("other", MediaSink.memory()));
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer("toy", sink)) {
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(audioTrack().toBuilder()
                    .codec(Codec.OPUS).build()));
            muxer.addTrack(audioTrack());
            muxer.setTags(audioTags());
            audioPackets().forEach(muxer::writePacket);
            muxer.finish();
        }
        assertArrayEquals(audioFile(), sink.toByteArray());
        // a remux through the provider's demuxer and muxer reproduces the file
        var remuxed = MediaSink.memory();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(sink.toByteArray()));
             var muxer = Containers.openMuxer(demuxer.formatName(), remuxed)) {
            muxer.addTrack(demuxer.tracks().getFirst());
            muxer.setTags(TagEditor.read(MediaSource.of(sink.toByteArray())));
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        assertArrayEquals(sink.toByteArray(), remuxed.toByteArray());
    }

    private static void probe() {
        var info = Media.probe(MediaSource.of(audioFile()));
        assertEquals(ContainerFormat.OTHER, info.container().orElseThrow());
        assertEquals("toy", info.containerName().orElseThrow());
        assertFalse(info.isImage());
        assertEquals(1, info.streams().size());
        var audio = info.audio().orElseThrow();
        assertEquals(Codec.PCM_S16LE, audio.codec());
        assertEquals(RATE, audio.sampleRate());
        assertEquals(2, audio.channels());
        assertEquals((long) FRAMES * PACKETS * 1_000_000 / RATE, info.durationMicros().orElseThrow());
        assertEquals("Toy tone", info.tags().get("title"));
        assertEquals("Smalt tests", info.tags().get("artist"));
        assertTrue(info.toString().contains("container=toy"), info.toString());
        // a core file reports its format's short name
        var wav = Media.probe(MediaSource.of(wavFile()));
        assertEquals("wav", wav.containerName().orElseThrow());
    }

    private static void audioReader() {
        var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build();
        try (var reader = AudioReader.open(MediaSource.of(audioFile()), options)) {
            assertEquals(ContainerFormat.OTHER, reader.format());
            assertEquals("toy", reader.formatName());
            assertEquals(RATE, reader.sampleRate());
            var expected = payload(audioPackets());
            var frame = 0;
            for (var buffer = reader.readIntegers(); buffer != null; buffer = reader.readIntegers()) {
                for (var i = 0; i < buffer.frames(); i++, frame++) {
                    for (var channel = 0; channel < 2; channel++) {
                        var offset = frame * 4 + channel * 2;
                        var sample = (short) ((expected[offset] & 0xFF) | (expected[offset + 1] << 8));
                        assertEquals(sample, buffer.plane(channel)[i], "frame " + frame + " channel " + channel);
                    }
                }
            }
            assertEquals(FRAMES * PACKETS, frame);
        }
    }

    private static void transcode() {
        var expected = payload(audioPackets());
        for (var options : List.of(TranscodeOptions.of(OutputContainer.WAV), TranscodeOptions.remux(OutputContainer.WAV))) {
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(audioFile()), sink, options);
            try (var demuxer = new WavDemuxer(MediaSource.of(sink.toByteArray()))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(RATE, track.sampleRate());
                assertEquals(Codec.PCM_S16LE, track.codec());
                assertArrayEquals(expected, payload(demuxer));
            }
            assertEquals("Toy tone", TagEditor.read(MediaSource.of(sink.toByteArray())).get(StandardTag.TITLE)
                    .orElseThrow());
        }
        // into AIFF an untagged toy file gives the file a WAV file of the same audio gives, holding the same samples
        var untagged = ToyContainerProvider.file(audioTrack(), MediaTags.empty(), audioPackets());
        var viaToy = transcoded(untagged, OutputContainer.AIFF);
        assertArrayEquals(transcoded(wavFile(), OutputContainer.AIFF), viaToy);
        assertArrayEquals(samples(audioFile()), samples(viaToy));
        assertEquals("Toy tone", TagEditor.read(MediaSource.of(transcoded(audioFile(), OutputContainer.AIFF)))
                .get(StandardTag.TITLE).orElseThrow());
    }

    /** {@return the file a transcode of a file into a profile writes} */
    private static byte[] transcoded(byte[] file, OutputContainer container) {
        var sink = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(file), sink, TranscodeOptions.of(container));
        return sink.toByteArray();
    }

    /** {@return the interleaved integer samples an audio reader decodes from a file} */
    private static int[] samples(byte[] file) {
        var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build();
        var count = 0;
        var all = new int[FRAMES * PACKETS * 2];
        try (var reader = AudioReader.open(MediaSource.of(file), options)) {
            for (var buffer = reader.readIntegers(); buffer != null; buffer = reader.readIntegers()) {
                for (var i = 0; i < buffer.frames(); i++) {
                    for (var channel = 0; channel < buffer.channels(); channel++) {
                        all[count++] = buffer.plane(channel)[i];
                    }
                }
            }
        }
        assertEquals(all.length, count);
        return all;
    }

    private static void tags(Path scratch) throws Exception {
        assertTrue(TagEditor.isSupported("toy"));
        assertFalse(TagEditor.isSupported(ContainerFormat.OTHER));
        assertFalse(TagEditor.isSupported("absent"));
        var read = TagEditor.read(MediaSource.of(audioFile()));
        assertEquals("Toy tone", read.get(StandardTag.TITLE).orElseThrow());
        var file = scratch.resolve("tags.toy");
        Files.write(file, audioFile());
        var original = Files.readAllBytes(file);
        // a shorter title fits the padded block: only the tag block changes
        var shorter = read.toBuilder().set(StandardTag.TITLE, "Tone").build();
        assertEquals(TagWriteResult.IN_PLACE, TagEditor.write(file, shorter));
        var inPlace = Files.readAllBytes(file);
        assertEquals(original.length, inPlace.length);
        assertEquals("Tone", TagEditor.read(file).get(StandardTag.TITLE).orElseThrow());
        assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, TagEditor.read(file)));
        // tags that outgrow the block rewrite the file and keep every packet
        var longer = shorter.toBuilder().set(StandardTag.COMMENT, "x".repeat(500)).build();
        assertEquals(TagWriteResult.REWRITTEN, TagEditor.write(file, longer));
        assertEquals("x".repeat(500), TagEditor.read(file).get(StandardTag.COMMENT).orElseThrow());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Files.readAllBytes(file)))) {
            assertArrayEquals(payload(audioPackets()), payload(demuxer));
        }
        // a write that may not go in place rewrites even a fitting edit
        var noInPlace = TagWriteOptions.builder().inPlace(false).build();
        assertEquals(TagWriteResult.REWRITTEN, TagEditor.write(file, shorter, noInPlace));
        // location stripping removes the location key alone
        assertTrue(TagEditor.read(file).hasLocation());
        assertEquals(TagWriteResult.IN_PLACE, TagEditor.stripLocation(file));
        var stripped = TagEditor.read(file);
        assertFalse(stripped.hasLocation());
        assertEquals("Tone", stripped.get(StandardTag.TITLE).orElseThrow());
        // the in-memory and streaming forms write the same file as the file form
        var memory = TagEditor.write(audioFile(), shorter, TagWriteOptions.defaults());
        var streamed = MediaSink.memory();
        TagEditor.write(MediaSource.of(audioFile()), streamed, shorter, TagWriteOptions.defaults());
        assertArrayEquals(memory, streamed.toByteArray());
        assertEquals("Tone", TagEditor.read(MediaSource.of(memory), TagReadOptions.defaults()).get(StandardTag.TITLE)
                .orElseThrow());
        assertArrayEquals(payload(audioPackets()), payload(Containers.openDemuxer(MediaSource.of(memory))));
    }

    private static void subtitles() {
        var cues = List.of(SubtitleCue.of(1_000_000, 2_500_000, "First line"),
                SubtitleCue.of(3_000_000, 4_000_000, "Second line"));
        var sink = MediaSink.memory();
        Subtitles.write(cues, SubtitleMarkup.SUBRIP, "toy", sink);
        var file = sink.toByteArray();
        assertEquals("toy", Containers.detectName(MediaSource.of(file)).orElseThrow());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            assertInstanceOf(DataTrack.class, demuxer.tracks().getFirst());
            assertEquals(Codec.SUBRIP, demuxer.tracks().getFirst().codec());
        }
        try (var reader = SubtitleReader.open(MediaSource.of(file))) {
            assertEquals("toy", reader.formatName());
        }
        var read = Subtitles.read(MediaSource.of(file));
        assertEquals(cues.size(), read.size());
        for (var i = 0; i < cues.size(); i++) {
            assertEquals(cues.get(i).startMicros(), read.get(i).startMicros());
            assertEquals(cues.get(i).endMicros(), read.get(i).endMicros());
            assertEquals(cues.get(i).text(), read.get(i).text());
        }
        // a transcode into SubRip keeps every cue
        var srt = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(file), srt, TranscodeOptions.of(OutputContainer.SRT));
        assertEquals(ContainerFormat.SUBRIP, Containers.detect(MediaSource.of(srt.toByteArray())).orElseThrow());
        var converted = Subtitles.read(MediaSource.of(srt.toByteArray()));
        assertEquals(cues.size(), converted.size());
        for (var i = 0; i < cues.size(); i++) {
            assertEquals(cues.get(i).startMicros(), converted.get(i).startMicros());
            assertEquals(cues.get(i).endMicros(), converted.get(i).endMicros());
            assertEquals(cues.get(i).text(), converted.get(i).text());
        }
        assertThrows(IllegalArgumentException.class, () -> Subtitles.write(cues, SubtitleMarkup.SUBRIP,
                "riff-claimer", MediaSink.memory()));
        assertThrows(IllegalArgumentException.class, () -> Subtitles.write(cues, SubtitleMarkup.SUBRIP,
                ContainerFormat.OTHER, MediaSink.memory()));
    }

    private static void namedFormat() {
        var file = y4mFile();
        assertTrue(TagEditor.isSupported(ContainerFormat.Y4M));
        assertTrue(TagEditor.isSupported("y4m"));
        assertEquals("y4m", Containers.detectName(MediaSource.of(file)).orElseThrow());
        var tags = MediaTags.builder().set(StandardTag.TITLE, "Two frames").set(StandardTag.COMMENT, "a b=c").build();
        var edited = TagEditor.write(file, tags, TagWriteOptions.defaults());
        var read = TagEditor.read(MediaSource.of(edited));
        assertEquals("Two frames", read.get(StandardTag.TITLE).orElseThrow());
        assertEquals("a b=c", read.get(StandardTag.COMMENT).orElseThrow());
        // the provider adds tag editing only: core still reads, probes and writes the format
        try (var demuxer = Containers.openDemuxer(MediaSource.of(edited))) {
            assertInstanceOf(Y4mDemuxer.class, demuxer);
            assertEquals("y4m", demuxer.formatName());
            assertArrayEquals(concat(Y4M_FRAMES), payload(demuxer));
        }
        var info = Media.probe(MediaSource.of(edited));
        assertEquals(ContainerFormat.Y4M, info.container().orElseThrow());
        assertEquals("y4m", info.containerName().orElseThrow());
        assertTrue(Containers.isMuxable("y4m", Codec.RAW_VIDEO));
        try (var muxer = Containers.openMuxer("y4m", MediaSink.memory())) {
            assertInstanceOf(Y4mMuxer.class, muxer);
        }
        // an edit back to no tags restores the original file
        assertArrayEquals(file, TagEditor.write(edited, MediaTags.empty(), TagWriteOptions.defaults()));
    }

    /** The frames of the YUV4MPEG2 file, 4 by 2 in 4:2:0. */
    private static final byte[][] Y4M_FRAMES = {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
            {21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}};

    /** {@return a YUV4MPEG2 file of {@link #Y4M_FRAMES}} */
    static byte[] y4mFile() {
        var frame = "FRAME\n".getBytes(StandardCharsets.US_ASCII);
        return concat("YUV4MPEG2 W4 H2 F25:1 Ip A1:1 C420jpeg\n".getBytes(StandardCharsets.US_ASCII), frame,
                Y4M_FRAMES[0], frame, Y4M_FRAMES[1]);
    }

    private static void invalid(String provider) {
        var text = MediaSource.of("1\n00:00:01,000 --> 00:00:02,000\nHello\n".getBytes(StandardCharsets.UTF_8));
        var failure = assertThrows(ServiceConfigurationError.class, () -> Containers.detect(text));
        assertTrue(failure.getMessage().contains(provider), failure.getMessage());
        // every later lookup fails the same way
        assertThrows(ServiceConfigurationError.class, () -> Containers.openMuxer("toy", MediaSink.memory()));
        assertThrows(ServiceConfigurationError.class, () -> TagEditor.isSupported("toy"));
        // a file core recognizes by its magic number needs no provider
        assertEquals(ContainerFormat.WAV, Containers.detect(MediaSource.of(wavFile())).orElseThrow());
    }

    /** {@return the concatenation of arrays} */
    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
