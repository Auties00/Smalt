package com.github.auties00.smalt.hostile;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagReadOptions;
import com.github.auties00.smalt.probe.ProbeOptions;
import com.github.auties00.smalt.property.Streams;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.github.auties00.smalt.hostile.Hostile.LIMITS;
import static com.github.auties00.smalt.hostile.Hostile.MAX_ALLOCATION;
import static com.github.auties00.smalt.hostile.Hostile.assertRejected;
import static com.github.auties00.smalt.hostile.Hostile.be16;
import static com.github.auties00.smalt.hostile.Hostile.be32;
import static com.github.auties00.smalt.hostile.Hostile.indexOf;
import static com.github.auties00.smalt.hostile.Hostile.le16;
import static com.github.auties00.smalt.hostile.Hostile.le32;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Container and audio bombs: a stream header declaring more channels than the limit, a sample index declaring more
 * frames than the limit, and sizes and counts no file of that length can hold (a sample table of two billion
 * entries, a codec private blob of a gigabyte, an ID3 tag of 256 MB, a packet continued over thousands of pages).
 * Every entry point (demux, probe, audio reader, tag reader) must stay within {@link Hostile#MAX_ALLOCATION}, and
 * the channel, frame and packet bombs must be rejected with {@link MediaException.LimitExceeded}.
 */
@DisplayName("Container and audio bombs")
class ContainerBombTest {
    private static final int SAMPLE_RATE = 8000;

    /**
     * The limits of the size bombs: {@link Hostile#LIMITS} with 1 MiB per buffer, so that what a demuxer may
     * legitimately buffer up to the limit stays well under {@link Hostile#MAX_ALLOCATION}.
     */
    private static final MediaLimits SIZE_LIMITS = LIMITS.toBuilder().maxBytes(1L << 20).build();

    record Input(String name, Supplier<byte[]> data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Input> channelBombs() {
        return Stream.of(
                new Input("WAV", () -> {
                    var data = pcm(ContainerFormat.WAV, Codec.PCM_S16LE);
                    var fmt = indexOf(data, "fmt ", 12) + 8;
                    le16(data, fmt + 2, 100);
                    le32(data, fmt + 8, SAMPLE_RATE * 200L);
                    le16(data, fmt + 12, 200);
                    return data;
                }),
                new Input("AIFF", () -> {
                    var data = pcm(ContainerFormat.AIFF, Codec.PCM_S16BE);
                    be16(data, indexOf(data, "COMM", 12) + 8, 100);
                    return data;
                }),
                new Input("AIFF-C ima4", () -> {
                    var data = encoded(ContainerFormat.AIFF, Codec.ADPCM_IMA_QT, ChannelLayout.STEREO, SAMPLE_RATE);
                    be16(data, indexOf(data, "COMM", 12) + 8, 100);
                    return data;
                }),
                new Input("Wave64", () -> {
                    var data = pcm(ContainerFormat.W64, Codec.PCM_S16LE);
                    // the fmt chunk body follows its 16-byte GUID and 64-bit size
                    var fmt = indexOf(data, "fmt ", 40) + 24;
                    le16(data, fmt + 2, 100);
                    le32(data, fmt + 8, SAMPLE_RATE * 200L);
                    le16(data, fmt + 12, 200);
                    return data;
                }),
                new Input("CAF", () -> {
                    var data = pcm(ContainerFormat.CAF, Codec.PCM_S16BE);
                    // desc body at 20: rate, format, flags, bytes per packet, frames per packet, channels
                    be32(data, 20 + 16, 200);
                    be32(data, 20 + 24, 100);
                    return data;
                }),
                new Input("AU", () -> {
                    var data = pcm(ContainerFormat.AU, Codec.PCM_S16BE);
                    be32(data, 20, 100);
                    return data;
                }),
                new Input("Matroska", () -> {
                    var data = pcm(ContainerFormat.MATROSKA, Codec.PCM_S16LE);
                    var channels = indexOf(data, new byte[]{(byte) 0x9F, (byte) 0x81, 0x02}, 0);
                    data[channels + 2] = 100;
                    return data;
                }),
                new Input("AVI", () -> {
                    var data = pcm(ContainerFormat.AVI, Codec.PCM_S16LE);
                    var strf = indexOf(data, "strf", 12) + 8;
                    le16(data, strf + 2, 100);
                    le16(data, strf + 12, 200);
                    return data;
                }),
                new Input("MP4", () -> {
                    var data = pcm(ContainerFormat.MP4, Codec.PCM_S16LE);
                    var entry = sampleEntry(data);
                    be16(data, entry + 20, 100);
                    return data;
                }),
                new Input("Ogg Vorbis", () -> {
                    var data = encoded(ContainerFormat.OGG, Codec.VORBIS, ChannelLayout.STEREO, 44_100);
                    var header = indexOf(data, new byte[]{1, 'v', 'o', 'r', 'b', 'i', 's'}, 0);
                    data[header + 11] = 100;
                    fixOggCrc(data, 0);
                    return data;
                }),
                new Input("Ogg Opus with 100 streams", ContainerBombTest::opus100),
                new Input("FLAC", () -> encoded(ContainerFormat.FLAC, Codec.FLAC, ChannelLayout.STEREO, SAMPLE_RATE)));
    }

    @Nested
    @DisplayName("a stream declaring more channels than the limit")
    class Channels {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.ContainerBombTest#channelBombs")
        @DisplayName("is rejected by the audio reader with LimitExceeded")
        void reader(Input input) {
            var data = input.data().get();
            var limits = limitsFor(input);
            assertRejected(input + " channels", MediaException.LimitExceeded.class, () -> {
                try (var reader = AudioReader.open(MediaSource.of(data),
                        AudioReaderOptions.builder().limits(limits).build())) {
                    reader.read();
                }
            });
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.ContainerBombTest#channelBombs")
        @DisplayName("is rejected by probing with LimitExceeded or described without allocating for it")
        void probe(Input input) {
            var data = input.data().get();
            assertBounded(input + " probe", data.length, () -> Media.probe(MediaSource.of(data),
                    ProbeOptions.builder().limits(limitsFor(input)).readFirstFrames(true).build()));
        }

        private static MediaLimits limitsFor(Input input) {
            return input.name().equals("FLAC") ? LIMITS.toBuilder().maxChannels(1).build() : LIMITS;
        }
    }

    static Stream<Input> frameBombs() {
        return Stream.of(
                new Input("MP4", () -> video(ContainerFormat.MP4)),
                new Input("AVI", () -> video(ContainerFormat.AVI)),
                new Input("IVF", () -> video(ContainerFormat.IVF)));
    }

    @Nested
    @DisplayName("a sample index with more entries than the frame limit")
    class Frames {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.ContainerBombTest#frameBombs")
        @DisplayName("is rejected by the demuxer with LimitExceeded")
        void demuxer(Input input) {
            var data = input.data().get();
            assertRejected(input + " frames", MediaException.LimitExceeded.class, () -> {
                try (var demuxer = Containers.openDemuxer(MediaSource.of(data),
                        DemuxerOptions.builder().limits(LIMITS).build())) {
                    while (demuxer.read() != null) {
                        // drains
                    }
                    // IVF indexes its frames on the first seek.
                    if (demuxer.isSeekable()) {
                        demuxer.seek(demuxer.durationMicros() / 2);
                    }
                }
            });
        }
    }

    static Stream<Arguments> sizeBombs() {
        return Stream.of(
                Arguments.of(new Input("MP4 stsz of 2^31 entries", () -> mp4Count("stsz", 8, true))),
                Arguments.of(new Input("MP4 stco of 2^31 entries", () -> mp4Count("stco", 4, false))),
                Arguments.of(new Input("MP4 stts of 2^31 entries", () -> mp4Count("stts", 4, false))),
                Arguments.of(new Input("MP4 stsc of 2^31 entries", () -> mp4Count("stsc", 4, false))),
                Arguments.of(new Input("Matroska CodecPrivate of 1 GiB", ContainerBombTest::matroskaPrivate)),
                Arguments.of(new Input("Matroska bzlib frame of 20 MB", () -> matroskaCompressed(1, bzip2Bomb()))),
                Arguments.of(new Input("Matroska LZO1X frame of 25 MB", () -> matroskaCompressed(2, lzoBomb()))),
                Arguments.of(new Input("QuickTime compressed movie of 2 GiB", ContainerBombTest::quickTimeMovie)),
                Arguments.of(new Input("MP3 behind a 256 MB ID3v2 tag", ContainerBombTest::id3)),
                Arguments.of(new Input("FLAC PICTURE block of 16 MB", ContainerBombTest::flacPicture)),
                Arguments.of(new Input("WAV LIST chunk of 2 GB", () -> {
                    var data = pcm(ContainerFormat.WAV, Codec.PCM_S16LE);
                    var out = new ByteArrayOutputStream();
                    out.writeBytes(data);
                    out.writeBytes(new byte[]{'L', 'I', 'S', 'T', (byte) 0xF0, (byte) 0xFF, (byte) 0xFF, 0x7F,
                            'I', 'N', 'F', 'O', 'I', 'N', 'A', 'M', (byte) 0xF0, (byte) 0xFF, (byte) 0xFF, 0x7F});
                    var bytes = out.toByteArray();
                    le32(bytes, 4, bytes.length - 8);
                    return bytes;
                })),
                Arguments.of(new Input("CAF pakt chunk of 2^40 bytes and 2^30 packets", () -> cafChunk("pakt",
                        new byte[]{0, 0, 0, 0, 0x40, 0, 0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}))),
                Arguments.of(new Input("CAF kuki chunk of 2^40 bytes", () -> cafChunk("kuki", new byte[16]))),
                Arguments.of(new Input("CAF info chunk of 2^40 bytes", () -> cafChunk("info",
                        new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 't', 0, 'x', 0}))),
                Arguments.of(new Input("AU annotation of 2 GB", () -> {
                    var data = pcm(ContainerFormat.AU, Codec.PCM_S16BE);
                    be32(data, 4, 0x7FFF_FFF0L);
                    return data;
                })),
                Arguments.of(new Input("Wave64 chunk of 2^62 bytes", () -> {
                    var data = pcm(ContainerFormat.W64, Codec.PCM_S16LE);
                    var fmt = indexOf(data, "fmt ", 40);
                    data[fmt + 16 + 7] = 0x40;
                    return data;
                })),
                Arguments.of(new Input("AVI idx1 of 2 GB", () -> {
                    var data = pcm(ContainerFormat.AVI, Codec.PCM_S16LE);
                    var index = indexOf(data, "idx1", 12);
                    le32(data, index + 4, 0x7FFF_FFF0L);
                    return data;
                })),
                Arguments.of(new Input("Ogg packet continued over 100 pages", ContainerBombTest::endlessOgg)),
                Arguments.of(new Input("MPEG-TS PES of unbounded length over 40000 packets",
                        ContainerBombTest::endlessTs)),
                Arguments.of(new Input("MPEG-PS video access unit continued over 2000 PES packets",
                        ContainerBombTest::endlessPs)),
                Arguments.of(new Input("DASH segment timeline repeated 2^31 times", () -> dash(
                        "<SegmentTemplate media=\"s$Number$.m4s\"><SegmentTimeline><S t=\"0\" d=\"1\" "
                                + "r=\"2147483647\"/></SegmentTimeline></SegmentTemplate>"))),
                Arguments.of(new Input("DASH segment template of 2^40 segments by duration", () -> dash(
                        "<SegmentTemplate media=\"s$Number$.m4s\" duration=\"1\"/>"))));
    }

    /**
     * Returns a DASH manifest of one representation addressed as given, over a presentation of 2^40 seconds.
     */
    static byte[] dash(String addressing) {
        return ("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" "
                + "mediaPresentationDuration=\"PT1099511627776S\"><Period><AdaptationSet mimeType=\"video/mp4\">"
                + "<Representation id=\"r\" bandwidth=\"1\">" + addressing + "</Representation></AdaptationSet>"
                + "</Period></MPD>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("a size or count no file of its length can hold")
    class Sizes {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.ContainerBombTest#sizeBombs")
        @DisplayName("is demuxed or rejected without allocating for the declared size")
        void demuxer(Input input) {
            var data = input.data().get();
            assertBounded(input + " demux", data.length, () -> {
                try (var demuxer = Containers.openDemuxer(MediaSource.of(data),
                        DemuxerOptions.builder().limits(SIZE_LIMITS).build())) {
                    demuxer.metadata();
                    demuxer.attachedPictures();
                    while (demuxer.read() != null) {
                        // drains
                    }
                }
            });
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.ContainerBombTest#sizeBombs")
        @DisplayName("is probed, or rejected, without allocating for the declared size")
        void probe(Input input) {
            var data = input.data().get();
            assertBounded(input + " probe", data.length, () -> Media.probe(MediaSource.of(data),
                    ProbeOptions.builder().limits(SIZE_LIMITS).attachedPictures(true).readFirstFrames(true).build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.ContainerBombTest#sizeBombs")
        @DisplayName("has its tags read, or is rejected, without allocating for the declared size")
        void tags(Input input) {
            var data = input.data().get();
            assertBounded(input + " tags", data.length, () -> {
                var format = Containers.detect(MediaSource.of(data));
                if (format.isPresent() && TagEditor.isSupported(format.get())) {
                    TagEditor.read(MediaSource.of(data), TagReadOptions.builder().limits(SIZE_LIMITS).build());
                }
            });
        }

        @org.junit.jupiter.api.Test
        @DisplayName("a packet continued over more pages than the buffer limit is rejected with LimitExceeded")
        void endlessPacket() {
            var data = endlessOgg();
            assertRejected("endless Ogg packet", MediaException.LimitExceeded.class, () -> {
                try (var demuxer = Containers.openDemuxer(MediaSource.of(data),
                        DemuxerOptions.builder().limits(SIZE_LIMITS).build())) {
                    while (demuxer.read() != null) {
                        // drains
                    }
                }
            });
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"MPEG-TS", "MPEG-PS"})
        @DisplayName("a PES or access unit of unbounded length larger than the buffer limit is never returned whole")
        void endlessPes(String format) {
            var data = format.equals("MPEG-TS") ? endlessTs() : endlessPs();
            assertBounded("endless " + format + " unit", data.length, () -> {
                try (var demuxer = Containers.openDemuxer(MediaSource.of(data),
                        DemuxerOptions.builder().limits(SIZE_LIMITS).build())) {
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        if (packet.size() > SIZE_LIMITS.maxBytes()) {
                            throw new AssertionError("a packet of " + packet.size() + " bytes passed the buffer limit");
                        }
                    }
                }
            });
        }
    }

    /**
     * Runs a step that may succeed or throw a {@code MediaException}, within ten seconds, allocating at most
     * {@link Hostile#MAX_ALLOCATION} plus three times the input: reading the input a few times is allowed, allocating
     * for a size it only declares is not.
     */
    static void assertBounded(String what, long inputLength, Hostile.Step step) {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        var before = threads.getTotalThreadAllocatedBytes();
        var failure = new Throwable[1];
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            try {
                step.run();
            } catch (Throwable thrown) {
                failure[0] = thrown;
            }
        }, what + " did not finish");
        var allocated = threads.getTotalThreadAllocatedBytes() - before;
        if (failure[0] != null && !(failure[0] instanceof MediaException)) {
            throw new AssertionError(what + " threw " + failure[0], failure[0]);
        }
        if (failure[0] instanceof MediaException media) {
            Hostile.assertNoHiddenFailure(what, media);
        }
        if (allocated > MAX_ALLOCATION + 3 * inputLength) {
            throw new AssertionError(what + " allocated " + (allocated >> 20) + " MiB"
                    + (failure[0] != null ? " before throwing " + failure[0] : ""));
        }
    }

    static byte[] pcm(ContainerFormat format, Codec codec) {
        return mux(format, List.of(Streams.audio(codec, ChannelLayout.STEREO, SAMPLE_RATE, 40 * 160,
                builder -> builder)));
    }

    /**
     * Returns a CAF file of PCM samples followed by a chunk of the given type that declares 2^40 bytes and holds the
     * given body.
     */
    static byte[] cafChunk(String type, byte[] body) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(pcm(ContainerFormat.CAF, Codec.PCM_S16BE));
        out.writeBytes(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{0, 0, 1, 0, 0, 0, 0, 0});
        out.writeBytes(body);
        return out.toByteArray();
    }

    /** Returns a file of 40 VP8 frames, more than the 16 frame limit. */
    static byte[] video(ContainerFormat format) {
        return mux(format, List.of(Streams.video(Codec.VP8, 16, 16, 40, builder -> builder.realtime(true))));
    }

    static byte[] encoded(ContainerFormat format, Codec codec, ChannelLayout layout, int sampleRate) {
        return mux(format, List.of(Streams.audio(codec, layout, sampleRate, sampleRate / 2, builder -> builder)));
    }

    static byte[] mux(ContainerFormat format, List<Streams.Encoded> streams) {
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(format, sink)) {
            for (var stream : streams) {
                muxer.addTrack(stream.track());
            }
            for (var stream : streams) {
                for (var packet : stream.packets()) {
                    muxer.writePacket(packet);
                }
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** Returns the offset of the audio sample entry's four-character type in an MP4 file. */
    static int sampleEntry(byte[] data) {
        var stsd = indexOf(data, "stsd", 0);
        return stsd + 4 + 8 + 4;
    }

    static byte[] mp4Count(String box, int countOffset, boolean zeroSampleSize) {
        var data = pcm(ContainerFormat.MP4, Codec.PCM_S16LE);
        var at = indexOf(data, box, 0);
        if (zeroSampleSize) {
            be32(data, at + 8, 0);
        }
        be32(data, at + 4 + countOffset, 0x7FFF_FFFFL);
        return data;
    }

    static byte[] matroskaPrivate() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0xA3,
                0x42, (byte) 0x86, (byte) 0x81, 1, 0x42, (byte) 0xF7, (byte) 0x81, 1, 0x42, (byte) 0xF2, (byte) 0x81, 4,
                0x42, (byte) 0xF3, (byte) 0x81, 8, 0x42, (byte) 0x82, (byte) 0x88, 'm', 'a', 't', 'r', 'o', 's', 'k',
                'a', 0x42, (byte) 0x87, (byte) 0x81, 4, 0x42, (byte) 0x85, (byte) 0x81, 2});
        out.writeBytes(new byte[]{0x18, 0x53, (byte) 0x80, 0x67, 0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        out.writeBytes(new byte[]{0x15, 0x49, (byte) 0xA9, 0x66, (byte) 0x8B, 0x2A, (byte) 0xD7, (byte) 0xB1,
                (byte) 0x83, 0x0F, 0x42, 0x40, 0x4D, (byte) 0x80, (byte) 0x81, 'x'});
        out.writeBytes(new byte[]{0x16, 0x54, (byte) 0xAE, 0x6B, 0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        out.writeBytes(new byte[]{(byte) 0xAE, 0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        out.writeBytes(new byte[]{(byte) 0xD7, (byte) 0x81, 1, 0x73, (byte) 0xC5, (byte) 0x81, 1,
                (byte) 0x83, (byte) 0x81, 2, (byte) 0x86, (byte) 0x86, 'A', '_', 'O', 'P', 'U', 'S'});
        out.writeBytes(new byte[]{0x63, (byte) 0xA2, 0x01, 0, 0, 0, 0x40, 0, 0, 0});
        out.writeBytes("OpusHead".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[]{1, 2, 0x38, 1, (byte) 0x80, (byte) 0xBB, 0, 0, 0, 0, 0});
        out.writeBytes(new byte[4096]);
        return out.toByteArray();
    }

    /** Forty concatenated bzip2 streams of 500000 zero bytes each (libbzip2 at block size 9). */
    static byte[] bzip2Bomb() {
        var stream = java.util.HexFormat.of().parseHex("425a68393141592653598de0391c0003d44004c00004000008200030cc09aa69"
                + "aa486d4a4878bb9229c284846f01c8e0");
        var out = new ByteArrayOutputStream();
        for (var copy = 0; copy < 40; copy++) {
            out.writeBytes(stream);
        }
        return out.toByteArray();
    }

    /** An LZO1X stream of one zero byte and an M3 copy of it extended by 100000 zero length bytes. */
    static byte[] lzoBomb() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{18, 0, 0x20});
        out.writeBytes(new byte[100_000]);
        out.writeBytes(new byte[]{1, 0, 0, 0x11, 0, 0});
        return out.toByteArray();
    }

    /** A Matroska file of one PCM track whose only frame is compressed with a ContentCompAlgo. */
    static byte[] matroskaCompressed(int algorithm, byte[] frame) {
        var header = ebml(0x1A45DFA3L, concat(ebml(0x4286, new byte[]{1}), ebml(0x42F7, new byte[]{1}),
                ebml(0x42F2, new byte[]{4}), ebml(0x42F3, new byte[]{8}),
                ebml(0x4282, "matroska".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), ebml(0x4287, new byte[]{4}),
                ebml(0x4285, new byte[]{2})));
        var info = ebml(0x1549A966L, ebml(0x2AD7B1, new byte[]{0x0F, 0x42, 0x40}));
        var audio = ebml(0xE1, concat(ebml(0xB5, java.nio.ByteBuffer.allocate(8).putDouble(SAMPLE_RATE).array()),
                ebml(0x9F, new byte[]{1}), ebml(0x6264, new byte[]{16})));
        var encodings = ebml(0x6D80, ebml(0x6240, ebml(0x5034, ebml(0x4254, new byte[]{(byte) algorithm}))));
        var track = ebml(0xAE, concat(ebml(0xD7, new byte[]{1}), ebml(0x73C5, new byte[]{1}), ebml(0x83, new byte[]{2}),
                ebml(0x86, "A_PCM/INT/LIT".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), audio, encodings));
        var cluster = ebml(0x1F43B675L, concat(ebml(0xE7, new byte[]{0}),
                ebml(0xA3, concat(new byte[]{(byte) 0x81, 0, 0, (byte) 0x80}, frame))));
        return concat(header, ebml(0x18538067L, concat(info, ebml(0x1654AE6BL, track), cluster)));
    }

    /** A QuickTime file whose movie is a cmov atom declaring an uncompressed movie of 2 GiB. */
    static byte[] quickTimeMovie() {
        var data = pcm(ContainerFormat.MP4, Codec.PCM_S16LE);
        var moov = indexOf(data, "moov", 0) - 4;
        var cmvd = concat(new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xF0}, new byte[]{0x78, (byte) 0x9C, 3, 0});
        var cmov = atom("moov", atom("cmov", concat(atom("dcom", "zlib".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                atom("cmvd", cmvd))));
        var size = java.nio.ByteBuffer.wrap(data, moov, 4).getInt();
        return concat(java.util.Arrays.copyOf(data, moov), cmov, java.util.Arrays.copyOfRange(data, moov + size, data.length));
    }

    private static byte[] atom(String type, byte[] payload) {
        return java.nio.ByteBuffer.allocate(8 + payload.length).putInt(8 + payload.length)
                .put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put(payload).array();
    }

    private static byte[] ebml(long id, byte[] payload) {
        var out = new ByteArrayOutputStream();
        for (var shift = (64 - Long.numberOfLeadingZeros(id) + 7) / 8 - 1; shift >= 0; shift--) {
            out.write((int) (id >>> (8 * shift)));
        }
        var size = 0x0100_0000_0000_0000L | payload.length;
        for (var shift = 7; shift >= 0; shift--) {
            out.write((int) (size >>> (8 * shift)));
        }
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] id3() {
        var mp3 = encoded(ContainerFormat.MPEG_AUDIO, Codec.MP3, ChannelLayout.STEREO, 44_100);
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'D', '3', 4, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F});
        out.writeBytes(new byte[]{'T', 'I', 'T', '2', 0x7F, 0x7F, 0x7F, 0x70, 0, 0, 3});
        out.writeBytes(mp3);
        return out.toByteArray();
    }

    static byte[] flacPicture() {
        var flac = encoded(ContainerFormat.FLAC, Codec.FLAC, ChannelLayout.STEREO, SAMPLE_RATE);
        var out = new ByteArrayOutputStream();
        out.write(flac, 0, 8 + 34);
        var bytes = out.toByteArray();
        bytes[4] &= 0x7F;
        out.reset();
        out.writeBytes(bytes);
        out.writeBytes(new byte[]{(byte) 0x86, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 3, 0, 0, 0, 9});
        out.writeBytes("image/png".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[]{0, 0, 0, 0, 0, 0, 0, 16, 0, 0, 0, 16, 0, 0, 0, 32, 0, 0, 0, 0, 0x7F, (byte) 0xFF,
                (byte) 0xFF, 0});
        out.write(flac, 8 + 34, flac.length - 8 - 34);
        return out.toByteArray();
    }

    static byte[] opus100() {
        var head = new ByteArrayOutputStream();
        head.writeBytes("OpusHead".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        head.writeBytes(new byte[]{1, 100, 0x38, 1, (byte) 0x80, (byte) 0xBB, 0, 0, 0, 0, (byte) 255, 100, 0});
        for (var channel = 0; channel < 100; channel++) {
            head.write(channel);
        }
        var tags = new ByteArrayOutputStream();
        tags.writeBytes("OpusTags".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        tags.writeBytes(new byte[]{1, 0, 0, 0, 'x', 0, 0, 0, 0});
        var out = new ByteArrayOutputStream();
        out.writeBytes(oggPage(0x1234, 0, 0, 2, List.of(head.toByteArray()), false));
        out.writeBytes(oggPage(0x1234, 1, 0, 0, List.of(tags.toByteArray()), false));
        var frame = new byte[100];
        for (var index = 0; index < frame.length; index++) {
            frame[index] = (byte) 0xF8;
        }
        out.writeBytes(oggPage(0x1234, 2, 960, 4, List.of(frame), false));
        return out.toByteArray();
    }

    static byte[] endlessOgg() {
        var out = new ByteArrayOutputStream();
        var head = new ByteArrayOutputStream();
        head.writeBytes("OpusHead".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        head.writeBytes(new byte[]{1, 2, 0x38, 1, (byte) 0x80, (byte) 0xBB, 0, 0, 0, 0, 0});
        var tags = new ByteArrayOutputStream();
        tags.writeBytes("OpusTags".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        tags.writeBytes(new byte[]{1, 0, 0, 0, 'x', 0, 0, 0, 0});
        out.writeBytes(oggPage(7, 0, 0, 2, List.of(head.toByteArray()), false));
        out.writeBytes(oggPage(7, 1, 0, 0, List.of(tags.toByteArray()), false));
        var chunk = new byte[255 * 255];
        chunk[0] = (byte) 0xFC;
        for (var page = 0; page < 100; page++) {
            out.writeBytes(oggPage(7, 2 + page, -1, page == 0 ? 0 : 1, List.of(chunk), true));
        }
        return out.toByteArray();
    }

    /**
     * Builds an Ogg page; with {@code open} the last packet continues on the next page.
     */
    static byte[] oggPage(int serial, int sequence, long granule, int flags, List<byte[]> packets, boolean open) {
        var lacing = new ByteArrayOutputStream();
        var body = new ByteArrayOutputStream();
        for (var index = 0; index < packets.size(); index++) {
            var packet = packets.get(index);
            var remaining = packet.length;
            while (remaining >= 255) {
                lacing.write(255);
                remaining -= 255;
            }
            if (!(open && index == packets.size() - 1)) {
                lacing.write(remaining);
            }
            body.writeBytes(packet);
        }
        var segments = lacing.toByteArray();
        var page = new byte[27 + segments.length + body.size()];
        page[0] = 'O';
        page[1] = 'g';
        page[2] = 'g';
        page[3] = 'S';
        page[5] = (byte) flags;
        for (var index = 0; index < 8; index++) {
            page[6 + index] = (byte) (granule >>> (8 * index));
        }
        le32(page, 14, serial);
        le32(page, 18, sequence);
        page[26] = (byte) segments.length;
        System.arraycopy(segments, 0, page, 27, segments.length);
        System.arraycopy(body.toByteArray(), 0, page, 27 + segments.length, body.size());
        le32(page, 22, oggCrc(page, 0, page.length));
        return page;
    }

    /** Recomputes the CRC of the Ogg page at an offset. */
    static void fixOggCrc(byte[] data, int start) {
        for (var page = start; page >= 0 && page + 27 <= data.length; page = indexOf(data, "OggS", page + 4)) {
            var segments = data[page + 26] & 0xFF;
            var length = 27 + segments;
            for (var index = 0; index < segments; index++) {
                length += data[page + 27 + index] & 0xFF;
            }
            le32(data, page + 22, 0);
            le32(data, page + 22, oggCrc(data, page, length));
        }
    }

    static long oggCrc(byte[] data, int offset, int length) {
        return Hostile.crc32Msb(data, offset, length, 0);
    }

    static byte[] endlessTs() {
        var out = new ByteArrayOutputStream();
        var pat = new byte[]{0, 0, (byte) 0xB0, 13, 0, 1, (byte) 0xC1, 0, 0, 0, 1, (byte) 0xF0, 0, 0, 0, 0, 0};
        be32(pat, 13, Hostile.crc32Msb(pat, 1, 12, 0xFFFF_FFFF));
        var pmt = new byte[]{0, 2, (byte) 0xB0, 18, 0, 1, (byte) 0xC1, 0, 0, (byte) 0xE1, 0, (byte) 0xF0, 0,
                0x1B, (byte) 0xE1, 0, (byte) 0xF0, 0, 0, 0, 0, 0};
        be32(pmt, 18, Hostile.crc32Msb(pmt, 1, 17, 0xFFFF_FFFF));
        out.writeBytes(tsPacket(0, true, 0, pat));
        out.writeBytes(tsPacket(0x1000, true, 0, pmt));
        var pes = new ByteArrayOutputStream();
        pes.writeBytes(new byte[]{0, 0, 1, (byte) 0xE0, 0, 0, (byte) 0x80, (byte) 0x80, 5, 0x21, 0, 1, 0, 1});
        pes.writeBytes(ForgedHeaders.h264AnnexB(64, 64));
        out.writeBytes(tsPacket(0x100, true, 0, pes.toByteArray()));
        var filler = new byte[184];
        for (var index = 0; index < filler.length; index++) {
            filler[index] = (byte) (index | 1);
        }
        for (var packet = 1; packet <= 40_000; packet++) {
            out.writeBytes(tsPacket(0x100, false, packet & 15, filler));
        }
        return out.toByteArray();
    }

    /**
     * Returns a program stream of one MPEG-2 video stream whose first picture never ends: a sequence header and a
     * picture header followed by 4 MB without a start code, over 2000 packs.
     */
    static byte[] endlessPs() {
        var out = new ByteArrayOutputStream();
        var pack = new byte[]{0, 0, 1, (byte) 0xBA, 0x44, 0, 0x04, 0, 0x04, 0x01, 0x01, (byte) 0x89, (byte) 0xC3,
                (byte) 0xF8};
        var headers = new byte[]{0, 0, 1, (byte) 0xB3, 0x0A, 0, 0x78, 0x13, (byte) 0xFF, (byte) 0xFF, (byte) 0xE0,
                (byte) 0xA0, 0, 0, 1, 0, 0, 0x0F, (byte) 0xFF, (byte) 0xF8};
        var filler = new byte[2000];
        for (var index = 0; index < filler.length; index++) {
            filler[index] = (byte) (index | 1);
        }
        for (var packet = 0; packet < 2000; packet++) {
            out.writeBytes(pack);
            var payload = packet == 0 ? headers : filler;
            var header = packet == 0 ? new byte[]{(byte) 0x81, (byte) 0x80, 5, 0x21, 0, 1, 0, 1}
                    : new byte[]{(byte) 0x81, 0, 0};
            var length = header.length + payload.length;
            out.writeBytes(new byte[]{0, 0, 1, (byte) 0xE0, (byte) (length >> 8), (byte) length});
            out.writeBytes(header);
            out.writeBytes(payload);
        }
        return out.toByteArray();
    }

    private static byte[] tsPacket(int pid, boolean start, int continuity, byte[] payload) {
        var packet = new byte[188];
        packet[0] = 0x47;
        packet[1] = (byte) ((start ? 0x40 : 0) | pid >> 8);
        packet[2] = (byte) pid;
        var length = Math.min(payload.length, 184);
        if (length == 184) {
            packet[3] = (byte) (0x10 | continuity);
            System.arraycopy(payload, 0, packet, 4, 184);
        } else {
            packet[3] = (byte) (0x30 | continuity);
            var stuffing = 184 - length - 1;
            packet[4] = (byte) stuffing;
            if (stuffing > 0) {
                packet[5] = 0;
                for (var index = 6; index < 5 + stuffing; index++) {
                    packet[index] = (byte) 0xFF;
                }
            }
            System.arraycopy(payload, 0, packet, 5 + stuffing, length);
        }
        return packet;
    }
}
