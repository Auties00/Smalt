package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.validate.MxfValidator;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers writing MXF files: every sample's tracks muxed in OP1a (seekable and streaming sinks, a KLV alignment grid
 * of one byte, short body partitions) and in OP-Atom, track by track, then validated by the test kit's MXF validator,
 * demuxed back packet for packet, read by ffprobe with the same payloads, key frames and presentation timestamps and
 * decoded by ffmpeg to the frames and samples of the source; the AVC-Intra layout of SMPTE RP 2027 with and without
 * parameter sets, the sound cadence at 29.97 Hz, PCM conversions, tags and timecode, determinism, and the rejected
 * tracks, packets and options.
 */
@DisplayName("MXF muxing")
class MxfMuxerTest {
    /** The tracks and packets of a source, in file order. */
    record Source(List<Track> tracks, List<Packet> packets) {
        Source only(Track track) {
            return new Source(List.of(track), packets.stream().filter(packet -> packet.trackId() == track.id())
                    .toList());
        }
    }

    static Source source(byte[] data) {
        try (var demuxer = new MxfDemuxer(MediaSource.of(data))) {
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return new Source(demuxer.tracks(), packets);
        }
    }

    static Source sample(String name) {
        return source(Fixtures.read(MxfDemuxerTest.SAMPLES.resolve(name)));
    }

    static byte[] mux(Source source, MxfMuxerOptions options, MediaTags tags) {
        var sink = MediaSink.memory();
        try (var muxer = new MxfMuxer(sink, options)) {
            source.tracks().forEach(muxer::addTrack);
            if (tags != null) {
                muxer.setTags(tags);
            }
            source.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    static byte[] mux(Source source, MxfMuxerOptions options) {
        return mux(source, options, null);
    }

    static byte[] stream(Source source, MxfMuxerOptions options) {
        var out = new ByteArrayOutputStream();
        try (var sink = MediaSink.of(out); var muxer = new MxfMuxer(sink, options)) {
            source.tracks().forEach(muxer::addTrack);
            source.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        return out.toByteArray();
    }

    static byte[] remux(byte[] mxf, MxfMuxerOptions options) {
        return mux(source(mxf), options);
    }

    static MxfValidator.Summary validate(String name, byte[] data) {
        keep(name, data);
        return Validators.assertValidMxf(name, data);
    }

    /** Keeps a written file under {@code target/media-fixtures/mxf}, which CI uploads, for inspection. */
    static void keep(String name, byte[] data) {
        try {
            var directory = java.nio.file.Path.of("target", "media-fixtures", "mxf");
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.write(directory.resolve(name.replaceAll("[^A-Za-z0-9.-]", "_") + ".mxf"), data);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /** {@return the NAL units of a length-prefixed access unit other than its parameter sets} */
    static List<String> withoutParameterSets(byte[] data) {
        var units = new ArrayList<>(MxfDemuxerTest.lengthPrefixedUnits(data));
        units.removeIf(unit -> {
            var type = Integer.parseInt(unit.substring(0, 2), 16) & 0x1F;
            return type == 7 || type == 8;
        });
        return units;
    }

    /** Asserts that a written file demuxes to the source's tracks and packets. */
    static void assertSame(Source expected, Source actual) {
        assertEquals(expected.tracks().size(), actual.tracks().size(), "track count");
        for (var index = 0; index < expected.tracks().size(); index++) {
            var want = expected.tracks().get(index);
            var got = actual.tracks().get(index);
            assertEquals(want.codec(), got.codec());
            if (want instanceof VideoTrack video) {
                var written = (VideoTrack) got;
                assertEquals(video.width(), written.width());
                assertEquals(video.height(), written.height());
                assertEquals(video.frameRate(), written.frameRate());
                assertEquals(video.timeBase(), written.timeBase());
                assertArrayEquals(video.codecConfig().orElse(null), written.codecConfig().orElse(null));
            } else if (want instanceof AudioTrack audio) {
                var written = (AudioTrack) got;
                assertEquals(audio.sampleRate(), written.sampleRate());
                assertEquals(audio.layout(), written.layout());
            }
            assertEquals(want.duration(), got.duration(), "duration of track " + index);
            var wanted = expected.packets().stream().filter(packet -> packet.trackId() == want.id()).toList();
            var read = actual.packets().stream().filter(packet -> packet.trackId() == got.id()).toList();
            if (want.codec().isPcm()) {
                assertArrayEquals(concat(wanted), concat(read), "samples of track " + index);
                continue;
            }
            assertEquals(wanted.size(), read.size(), "packet count of track " + index);
            for (var at = 0; at < wanted.size(); at++) {
                var a = wanted.get(at);
                var b = read.get(at);
                if (want.codec() == Codec.H264) {
                    // a plain AVC byte stream carries the parameter sets in band at each key frame, also when the
                    // source was a headerless AVC-Intra stream that had them in its configuration only
                    assertEquals(withoutParameterSets(a.data()), withoutParameterSets(b.data()),
                            "payload " + at + " of track " + index);
                } else {
                    assertArrayEquals(a.data(), b.data(), "payload " + at + " of track " + index);
                }
                assertEquals(a.pts(), b.pts(), "pts " + at + " of track " + index);
                assertEquals(a.dts(), b.dts(), "dts " + at + " of track " + index);
                assertEquals(a.keyframe(), b.keyframe(), "key flag " + at + " of track " + index);
            }
        }
    }

    static byte[] concat(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        packets.forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    static Stream<Arguments> variants() {
        var out = new ArrayList<Arguments>();
        var samples = List.of("op1a-dnxhr.mxf", "op1a-prores.mxf", "op1a-h264.mxf", "op1a-mpeg2.mxf", "op1a-avci50.mxf",
                "opatom-dnxhr.mxf", "opatom-pcm.mxf", "bmx-op1a-avci50-headerless.mxf", "bmx-op1a-vc3.mxf",
                "bmx-op1a-prores.mxf", "bmx-op1a-clip-pcm.mxf");
        for (var sample : samples) {
            out.add(Arguments.of(sample, "defaults"));
            out.add(Arguments.of(sample, "stream"));
            out.add(Arguments.of(sample, "kag1"));
            out.add(Arguments.of(sample, "partitions"));
            out.add(Arguments.of(sample, "atom"));
        }
        return out.stream();
    }

    static MxfMuxerOptions options(String variant) {
        return switch (variant) {
            case "kag1" -> MxfMuxerOptions.builder().kagSize(1).build();
            case "partitions" -> MxfMuxerOptions.builder().partitionMicros(40_000).build();
            case "atom" -> MxfMuxerOptions.forOpAtom();
            default -> MxfMuxerOptions.defaults();
        };
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {
        @ParameterizedTest(name = "{0}, {1}")
        @MethodSource("com.github.auties00.smalt.container.mxf.MxfMuxerTest#variants")
        @DisplayName("valid, and demuxed back packet for packet")
        void identity(String sample, String variant) {
            var source = sample(sample);
            var options = options(variant);
            var parts = variant.equals("atom") ? source.tracks().stream().map(source::only).toList() : List.of(source);
            for (var part : parts) {
                var data = variant.equals("stream") ? stream(part, options) : mux(part, options);
                var summary = validate(sample + "-" + variant, data);
                assertEquals(variant.equals("atom") ? "OPAtom" : "OP1a", summary.pattern());
                assertEquals(!variant.equals("stream"), summary.closedHeader(), "the header is closed when seekable");
                assertTrue(summary.randomIndex());
                assertSame(part, source(data));
            }
        }

        @ParameterizedTest(name = "{0}, {1}")
        @MethodSource("com.github.auties00.smalt.container.mxf.MxfMuxerTest#variants")
        @DisplayName("ffprobe reads every packet of the output as Smalt does, and ffmpeg decodes it cleanly")
        void ffmpeg(String sample, String variant) {
            Oracle.assume(Oracle.Tool.FFPROBE);
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = sample(sample);
            var options = options(variant);
            var parts = variant.equals("atom") ? source.tracks().stream().map(source::only).toList() : List.of(source);
            var index = 0;
            for (var part : parts) {
                var data = variant.equals("stream") ? stream(part, options) : mux(part, options);
                keep("ffmpeg-" + sample + "-" + variant + "-" + index, data);
                var file = MuxSupport.save(sample + "-" + variant + "-" + index++ + ".mxf", data);
                MxfDemuxerTest.assertMatchesFfprobe(file, MxfDemuxerTest.read(data));
                Oracle.assertFfmpegDecodes(file);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"op1a-dnxhr.mxf", "op1a-prores.mxf", "op1a-h264.mxf", "op1a-mpeg2.mxf",
                "op1a-avci50.mxf", "bmx-op1a-vc3.mxf", "bmx-op1a-prores.mxf"})
        @DisplayName("ffmpeg decodes the output to the source's frames and samples")
        void decodes(String sample) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var original = MxfDemuxerTest.SAMPLES.resolve(sample);
            var written = MuxSupport.save("decoded-" + sample, mux(sample(sample), MxfMuxerOptions.defaults()));
            assertArrayEquals(frames(original), frames(written));
            assertArrayEquals(Oracle.decodeAudio(original), Oracle.decodeAudio(written));
        }

        @Test
        @DisplayName("an OP-Atom picture file is clip wrapped and indexed so that ffmpeg finds every frame")
        void clipWrapped() {
            var source = sample("op1a-dnxhr.mxf");
            var data = mux(source.only(source.tracks().getFirst()), MxfMuxerOptions.forOpAtom());
            assertTrue(validate("atom-clip", data).clipWrapped());
            if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                var file = MuxSupport.save("atom-clip.mxf", data);
                MxfDemuxerTest.assertMatchesFfprobe(file, MxfDemuxerTest.read(data));
            }
        }

        @Test
        @DisplayName("a file written to a stream carries the final durations in its footer")
        void streamingDurations() {
            var source = sample("op1a-dnxhr.mxf");
            var data = stream(source, MxfMuxerOptions.defaults());
            try (var demuxer = new MxfDemuxer(MediaSource.of(data))) {
                assertEquals(source.tracks().get(0).duration(), demuxer.tracks().get(0).duration());
                assertEquals(240_000, demuxer.durationMicros());
            }
        }

        @Test
        @DisplayName("the same input and material package UMID give the same bytes; random UMIDs differ")
        void deterministic() {
            var source = sample("op1a-prores.mxf");
            var umid = new byte[32];
            Arrays.fill(umid, (byte) 7);
            var fixed = MxfMuxerOptions.builder().materialPackageUmid(umid).build();
            assertArrayEquals(mux(source, fixed), mux(source, fixed));
            assertFalse(Arrays.equals(mux(source, MxfMuxerOptions.defaults()), mux(source,
                    MxfMuxerOptions.defaults())));
        }
    }

    /** {@return every decoded frame of the first video stream, one per packet whatever the timestamps say} */
    static byte[] frames(java.nio.file.Path file) {
        return Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(2), List.of("-v", "error", "-i",
                file.toString(), "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt",
                "yuv444p16le", "-"));
    }

    @Nested
    @DisplayName("picture coding labels")
    class CodingLabels {
        /** {@return an MPEG-2 video frame of a sequence header, a sequence extension and a picture header} */
        static byte[] frame(int profileLevel, int chroma, int pictureType) {
            return new byte[]{0, 0, 1, (byte) 0xB3, 0x0A, 0x00, (byte) 0x80, 0x13, 0, 0, 0x20, 0, 0, 0, 1,
                    (byte) 0xB5, (byte) (0x10 | profileLevel >>> 4), (byte) ((profileLevel & 0x0F) << 4 | 0x08
                    | chroma << 1), 0, 1, 0, 0, 0, 0, 1, 0, 0, (byte) (pictureType << 3), 0, 0};
        }

        /** {@return a file of two pictures of an MPEG video track} */
        static byte[] mux(Codec codec, byte[] first, byte[] second) {
            var video = VideoTrack.builder().id(1).codec(codec).timeBase(new Rational(1, 25))
                    .frameRate(new Rational(25, 1)).width(160).height(128).build();
            var packets = List.of(new Packet(1, first, 0, 0, 1, true), new Packet(1, second, 1, 1, 1,
                    second == first));
            return MxfMuxerTest.mux(new Source(List.of(video), packets), MxfMuxerOptions.defaults());
        }

        /** {@return whether a local set item of a tag and value occurs in a file} */
        static boolean hasItem(byte[] data, int tag, byte[] value) {
            var item = new byte[4 + value.length];
            item[0] = (byte) (tag >>> 8);
            item[1] = (byte) tag;
            item[2] = (byte) (value.length >>> 8);
            item[3] = (byte) value.length;
            System.arraycopy(value, 0, item, 4, value.length);
            for (var at = 0; at + item.length <= data.length; at++) {
                if (Arrays.equals(data, at, at + item.length, item, 0, item.length)) {
                    return true;
                }
            }
            return false;
        }

        @ParameterizedTest(name = "profile and level {0}, chroma format {1}")
        @org.junit.jupiter.params.provider.CsvSource({
                "0x48, 1, 060e2b34040101030401020201011100, 060e2b34040101030401020201011000, 2",
                "0x85, 2, 060e2b34040101030401020201020300, 060e2b34040101030401020201020200, 1",
                "0x44, 1, 060e2b34040101030401020201030300, 060e2b34040101030401020201030200, 2",
                "0x82, 2, 060e2b34040101030401020201040300, 060e2b34040101030401020201040200, 1",
                "0x46, 1, 060e2b34040101080401020201050300, 060e2b34040101080401020201050200, 2",
                "0x18, 1, 060e2b34040101090401020201060300, 060e2b34040101090401020201060200, 2",
                "0x14, 3, 060e2b34040101090401020201070300, 060e2b34040101090401020201070200, 1",
                "0x16, 2, 060e2b34040101090401020201080300, 060e2b34040101090401020201080200, 1",
                "0x58, 1, 060e2b340401010a0401020201090200, 060e2b340401010a0401020201090200, 2",
                "0x4A, 1, 060e2b340401010a0401020201091000, 060e2b340401010a0401020201091000, 2",
                "0x1A, 1, 060e2b340401010a0401020201092000, 060e2b340401010a0401020201092000, 2",
                "0x28, 1, 060e2b340401010a0401020201093100, 060e2b340401010a0401020201093100, 2",
                "0x34, 1, 060e2b340401010a0401020201094200, 060e2b340401010a0401020201094200, 2",
                "0x8D, 1, 060e2b340401010a0401020201095100, 060e2b340401010a0401020201095100, 2",
                "0x8B, 1, 060e2b340401010a0401020201095300, 060e2b340401010a0401020201095300, 2",
                "0x0F, 1, 060e2b340401010a0401020201090000, 060e2b340401010a0401020201090000, 2"})
        @DisplayName("MPEG-2: the register's coding label of the profile, level and GOP structure; the chroma format")
        void mpeg2(String profileLevel, int chroma, String longGop, String intra, int vertical) {
            var level = Integer.decode(profileLevel);
            var hex = java.util.HexFormat.of();
            var predicted = mux(Codec.MPEG2_VIDEO, frame(level, chroma, 1), frame(level, chroma, 2));
            validate("mpeg2-" + profileLevel + "-gop", predicted);
            assertTrue(hasItem(predicted, 0x3201, hex.parseHex(longGop)), "the long GOP label");
            assertTrue(hasItem(predicted, 0x3302, hex.parseHex(chroma == 3 ? "00000001" : "00000002")));
            assertTrue(hasItem(predicted, 0x3308, hex.parseHex(vertical == 2 ? "00000002" : "00000001")));
            var key = frame(level, chroma, 1);
            var intraOnly = mux(Codec.MPEG2_VIDEO, key, key);
            validate("mpeg2-" + profileLevel + "-intra", intraOnly);
            assertTrue(hasItem(intraOnly, 0x3201, hex.parseHex(intra)), "the I-frame only label");
            if (!intra.equals(longGop)) {
                assertFalse(hasItem(intraOnly, 0x3201, hex.parseHex(longGop)), "no long GOP label is left");
            }
            assertEquals(Codec.MPEG2_VIDEO, source(predicted).tracks().getFirst().codec());
        }

        @Test
        @DisplayName("MPEG-2: a frame of an I and a P field picture is long GOP coding")
        void fieldPair() {
            var first = frame(0x48, 1, 1);
            var pair = Arrays.copyOf(first, first.length + 8);
            System.arraycopy(new byte[]{0, 0, 1, 0, 0, 2 << 3, 0, 0}, 0, pair, first.length, 8);
            var data = mux(Codec.MPEG2_VIDEO, pair, pair);
            assertTrue(hasItem(data, 0x3201, java.util.HexFormat.of().parseHex("060e2b34040101030401020201011100")));
        }

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.CsvSource({"bmx-op1a-vc3.mxf, 060e2b340401010a0401020271130000",
                "op1a-dnxhr.mxf, 060e2b340401010d0401020271280000"})
        @DisplayName("VC-3: the register's label of the compression id, with the register version that added it")
        void vc3(String sample, String label) {
            var data = MxfMuxerTest.mux(sample(sample), MxfMuxerOptions.defaults());
            assertTrue(hasItem(data, 0x3201, java.util.HexFormat.of().parseHex(label)));
        }

        @ParameterizedTest(name = "constrained parameters {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("MPEG-1: the constrained or unconstrained coding label of the sequence header")
        void mpeg1(boolean constrained) {
            var frame = new byte[]{0, 0, 1, (byte) 0xB3, 0x0A, 0x00, (byte) 0x80, 0x13, 0, 0, 0x20,
                    (byte) (constrained ? 0x04 : 0), 0, 0, 1, 0, 0, 0x08, 0, 0};
            var data = mux(Codec.MPEG1_VIDEO, frame, frame);
            validate("mpeg1-" + constrained, data);
            var label = constrained ? "060e2b34040101030401020201100100" : "060e2b34040101030401020201100200";
            assertTrue(hasItem(data, 0x3201, java.util.HexFormat.of().parseHex(label)));
            assertTrue(hasItem(data, 0x3308, java.util.HexFormat.of().parseHex("00000002")), "4:2:0");
        }

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.CsvSource({"bmx-op1a-vc3.mxf, 8, 225", "op1a-prores.mxf, 10, 897"})
        @DisplayName("limited range: the black and white levels and the 225 or 897 color difference values of ST 377-1")
        void colorRange(String sample, int depth, int range) {
            var data = MxfMuxerTest.mux(sample(sample), MxfMuxerOptions.defaults());
            var scale = 1 << (depth - 8);
            assertTrue(hasItem(data, 0x3301, u32(depth)), "component depth");
            assertTrue(hasItem(data, 0x3304, u32(16 * scale)), "black level");
            assertTrue(hasItem(data, 0x3305, u32(235 * scale)), "white level");
            assertTrue(hasItem(data, 0x3306, u32(range)), "color range");
        }

        @Test
        @DisplayName("ProRes 4:4:4 without a sample entry type: the 4444 label, 12 bits and no horizontal subsampling")
        void prores4444() {
            var frame = new byte[64];
            frame[3] = 64;
            frame[4] = 'i';
            frame[5] = 'c';
            frame[6] = 'p';
            frame[7] = 'f';
            frame[9] = 28;
            frame[8 + 8] = 0;
            frame[8 + 9] = (byte) 160;
            frame[8 + 11] = (byte) 128;
            frame[8 + 12] = (byte) 0xC0;
            var video = VideoTrack.builder().id(1).codec(Codec.PRORES).timeBase(new Rational(1, 25))
                    .frameRate(new Rational(25, 1)).width(160).height(128).build();
            var packets = List.of(new Packet(1, frame, 0, 0, 1, true), new Packet(1, frame, 1, 1, 1, true));
            var data = MxfMuxerTest.mux(new Source(List.of(video), packets), MxfMuxerOptions.defaults());
            var label = MxfLabels.PRORES_CODING.clone();
            label[14] = 5;
            assertTrue(hasItem(data, 0x3201, label), "the ProRes 4444 coding label");
            assertTrue(hasItem(data, 0x3301, u32(12)), "12-bit components");
            assertTrue(hasItem(data, 0x3302, u32(1)), "no horizontal subsampling");
            assertTrue(hasItem(data, 0x3306, u32(224 * 16 + 1)), "the 12-bit color range");
        }

        @Test
        @DisplayName("D-10 422P@ML coding is intra only: every picture decodes when it is presented")
        void d10() {
            var key = frame(0x85, 2, 1);
            var data = mux(Codec.MPEG2_VIDEO, key, key);
            var hex = java.util.HexFormat.of();
            var intra = hex.parseHex("060e2b34040101030401020201020200");
            var d10 = hex.parseHex("060e2b34040101010401020201020101");
            var replaced = 0;
            for (var at = 0; at + intra.length <= data.length; at++) {
                if (Arrays.equals(data, at, at + intra.length, intra, 0, intra.length)) {
                    System.arraycopy(d10, 0, data, at, d10.length);
                    replaced++;
                }
            }
            assertTrue(replaced > 0, "the I-frame only label is written");
            var read = source(data);
            assertEquals(Codec.MPEG2_VIDEO, read.tracks().getFirst().codec());
            assertEquals(2, read.packets().size());
            for (var packet : read.packets()) {
                assertEquals(packet.pts(), packet.dts(), "no decoding delay");
                assertTrue(packet.keyframe());
            }
        }

        @Test
        @DisplayName("AVC index entries: random access and sequence header at key frames, P or B by display order")
        void avcIndexFlags() {
            var source = sample("op1a-h264.mxf").only(sample("op1a-h264.mxf").tracks().getFirst());
            var data = MxfMuxerTest.mux(source, MxfMuxerOptions.defaults());
            var index = new MxfIndex();
            for (var at = 0; at + 17 <= data.length; at++) {
                if (!Arrays.equals(data, at, at + 16, MxfLabels.INDEX_SEGMENT, 0, 16)) {
                    continue;
                }
                var first = data[at + 16] & 0xFF;
                var count = first < 0x80 ? 0 : first & 0x7F;
                var length = first < 0x80 ? first : 0;
                for (var byteIndex = 0; byteIndex < count; byteIndex++) {
                    length = length << 8 | data[at + 17 + byteIndex] & 0xFF;
                }
                var start = at + 17 + count;
                index.add(MxfSet.parseStatic(MxfLabels.INDEX_SEGMENT.clone(),
                        Arrays.copyOfRange(data, start, start + length)), 1 << 20);
            }
            var packets = source.packets();
            assertEquals(packets.size(), index.entryCount());
            var latest = Long.MIN_VALUE;
            var bidirectional = 0;
            for (var unit = 0; unit < packets.size(); unit++) {
                var packet = packets.get(unit);
                var flags = index.flags(unit);
                if (packet.keyframe()) {
                    assertEquals(0xC0, flags, "key frame " + unit);
                } else if (packet.pts() < latest) {
                    assertEquals(0x30, flags, "bidirectional picture " + unit);
                    bidirectional++;
                } else {
                    assertEquals(0x20, flags, "forward predicted picture " + unit);
                }
                latest = Math.max(latest, packet.pts());
            }
            assertTrue(bidirectional > 0, "the sample has B pictures");
            assertTrue(index.hasBidirectional());
        }

        /** {@return a big-endian 32-bit value} */
        static byte[] u32(long value) {
            return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        }
    }

    @Nested
    @DisplayName("AVC-Intra")
    class AvcIntraLayout {
        /** {@return the value lengths of the picture elements of a file} */
        static List<Long> elementLengths(byte[] data) {
            var out = new ArrayList<Long>();
            for (var at = 0; at + 20 < data.length; at++) {
                if (data[at] == 0x06 && data[at + 1] == 0x0E && data[at + 2] == 0x2B && data[at + 3] == 0x34
                        && data[at + 4] == 0x01 && data[at + 5] == 0x02 && data[at + 8] == 0x0D
                        && data[at + 12] == 0x15) {
                    var count = data[at + 16] & 0x7F;
                    var length = 0L;
                    for (var index = 0; index < count; index++) {
                        length = length << 8 | data[at + 17 + index] & 0xFF;
                    }
                    out.add(length);
                }
            }
            return out;
        }

        @ParameterizedTest(name = "parameter sets in band: {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("RP 2027 frames: the class's coded frame size after 19 or 18 blocks of 512 bytes, its coding label")
        void layout(boolean headers) {
            var options = MxfMuxerOptions.builder().avcIntraClass(50).avcIntraParameterSets(headers).build();
            var data = mux(sample("op1a-avci50.mxf"), options);
            validate("avci-layout-" + headers, data);
            var lengths = elementLengths(data);
            assertFalse(lengths.isEmpty());
            for (var length : lengths) {
                assertEquals(135_168 + 512L * (headers ? 11 : 10), length, "720/50p class 50");
            }
            var label = AvcIntra.codingLabel(50, 5);
            var found = false;
            for (var at = 0; at + 16 <= data.length && !found; at++) {
                found = Arrays.equals(data, at, at + 16, label, 0, 16);
            }
            assertTrue(found, "the class 50 720/50p picture coding label is written");
            var demuxed = MxfDemuxerTest.read(data);
            var original = MxfDemuxerTest.read(Fixtures.read(MxfDemuxerTest.SAMPLES.resolve("op1a-avci50.mxf")));
            var written = demuxed.packetsOf(demuxed.tracks().getFirst());
            var source = original.packetsOf(original.tracks().getFirst());
            for (var index = 0; index < source.size(); index++) {
                var want = MxfDemuxerTest.lengthPrefixedUnits(source.get(index).data()).stream()
                        .filter(unit -> !unit.startsWith("0c") && !unit.startsWith("09") && (headers
                                || !unit.startsWith("67") && !unit.startsWith("68"))).toList();
                var got = MxfDemuxerTest.lengthPrefixedUnits(written.get(index).data()).stream()
                        .filter(unit -> !unit.startsWith("09")).toList();
                assertEquals(want, got, "NAL units of frame " + index);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var file = MuxSupport.save("avci-layout-" + headers + ".mxf", data);
                assertArrayEquals(Oracle.decodeVideo(MxfDemuxerTest.SAMPLES.resolve("op1a-avci50.mxf"),
                        "yuv420p10le", 0), Oracle.decodeVideo(file, "yuv420p10le", 0));
            }
        }

        @Test
        @DisplayName("a stream that is not of the class's profile or raster is rejected")
        void wrongRaster() {
            var h264 = sample("op1a-h264.mxf");
            var options = MxfMuxerOptions.builder().avcIntraClass(50).build();
            try (var muxer = new MxfMuxer(MediaSink.memory(), options)) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(h264.tracks().getFirst()));
            }
            var avci = sample("op1a-avci50.mxf");
            var hundred = MxfMuxerOptions.builder().avcIntraClass(100).build();
            try (var muxer = new MxfMuxer(MediaSink.memory(), hundred)) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(avci.tracks().getFirst()));
            }
        }

        @Test
        @DisplayName("an access unit larger than the coded frame size is rejected")
        void oversized() {
            var avci = sample("op1a-avci50.mxf");
            var video = avci.tracks().getFirst();
            var options = MxfMuxerOptions.builder().avcIntraClass(50).build();
            try (var muxer = new MxfMuxer(MediaSink.memory(), options)) {
                muxer.addTrack(video);
                var huge = new byte[200_000];
                huge[4] = 0x65;
                var length = huge.length - 4;
                huge[0] = (byte) (length >>> 24);
                huge[1] = (byte) (length >>> 16);
                huge[2] = (byte) (length >>> 8);
                huge[3] = (byte) length;
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(video.id(), huge, 0,
                        0, 1, true)));
            }
        }
    }

    @Nested
    @DisplayName("sound")
    class Sound {
        @Test
        @DisplayName("48 kHz with 29.97 Hz pictures: elements of 1602, 1601, 1602, 1601, 1602 samples")
        void cadence() {
            var video = VideoTrack.builder().id(1).codec(Codec.DNXHD).timeBase(new Rational(1001, 30000))
                    .frameRate(new Rational(30000, 1001)).width(256).height(128).codecConfig("AVdh".getBytes())
                    .build();
            var audio = AudioTrack.builder().id(2).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48000))
                    .sampleRate(48000).layout(ChannelLayout.MONO).build();
            var frame = sample("op1a-dnxhr.mxf").packets().getFirst().data();
            var packets = new ArrayList<Packet>();
            for (var index = 0; index < 10; index++) {
                packets.add(new Packet(1, frame, index, index, 1, true));
                packets.add(new Packet(2, new byte[2 * 1600], 1600L * index, 1600L * index, 1600, true));
            }
            var data = mux(new Source(List.of(video, audio), packets), MxfMuxerOptions.defaults());
            validate("cadence", data);
            var read = source(data);
            var durations = read.packets().stream().filter(packet -> packet.trackId() == read.tracks().get(1).id())
                    .map(Packet::duration).toList();
            // the last element holds the samples left: 16000 less the 14414 of the first nine edit units
            assertEquals(List.of(1602L, 1601L, 1602L, 1601L, 1602L, 1602L, 1601L, 1602L, 1601L, 1586L),
                    durations.subList(0, 10));
            var total = durations.stream().mapToLong(Long::longValue).sum();
            assertEquals(16_000, total, "every sample is written");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"PCM_S16BE", "PCM_S24BE", "PCM_S32BE", "PCM_S8"})
        @DisplayName("big-endian and signed 8-bit PCM are written as Broadcast Wave samples")
        void converted(String name) {
            var codec = Codec.valueOf(name);
            var bytes = codec.pcmBitsPerSample().orElseThrow() / 8;
            var audio = AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(ChannelLayout.STEREO).build();
            var samples = new byte[bytes * 2 * 4800];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (byte) (index * 37 + 11);
            }
            var data = mux(new Source(List.of(audio), List.of(new Packet(1, samples, 0, 0, 4800, true))),
                    MxfMuxerOptions.defaults());
            keep("converted-" + name, data);
            var read = source(data);
            var expected = new byte[samples.length];
            for (var at = 0; at < samples.length; at += bytes) {
                for (var index = 0; index < bytes; index++) {
                    expected[at + index] = codec == Codec.PCM_S8 ? (byte) (samples[at] ^ 0x80)
                            : samples[at + bytes - 1 - index];
                }
            }
            var little = switch (bytes) {
                case 1 -> Codec.PCM_U8;
                case 2 -> Codec.PCM_S16LE;
                case 3 -> Codec.PCM_S24LE;
                default -> Codec.PCM_S32LE;
            };
            assertEquals(little, read.tracks().getFirst().codec());
            assertArrayEquals(expected, concat(read.packets()));
        }

        @Test
        @DisplayName("several sound tracks without pictures: frame wrapped at 25 Hz")
        void soundOnly() {
            var first = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48000))
                    .sampleRate(48000).layout(ChannelLayout.MONO).build();
            var second = AudioTrack.builder().id(2).codec(Codec.PCM_S24LE).timeBase(Rational.timeBaseOf(48000))
                    .sampleRate(48000).layout(ChannelLayout.STEREO).build();
            var packets = List.of(new Packet(1, new byte[2 * 4800], 0, 0, 4800, true),
                    new Packet(2, new byte[6 * 4800], 0, 0, 4800, true));
            var data = mux(new Source(List.of(first, second), packets), MxfMuxerOptions.defaults());
            assertFalse(validate("sound-only", data).clipWrapped());
            var read = source(data);
            assertEquals(2, read.tracks().size());
            assertTrue(read.packets().stream().allMatch(packet -> packet.duration() == 1920 || packet.duration() == 960));
        }
    }

    @Nested
    @DisplayName("tags")
    class Tags {
        @Test
        @DisplayName("title, user comments, track names and timecode, read back by Smalt and ffprobe")
        void tags() {
            var source = sample("op1a-dnxhr.mxf");
            var video = source.tracks().getFirst();
            var tags = MediaTags.builder().set("title", "Smalt test").set("comment", "a comment")
                    .set("timecode", "01:02:03:04").set("project", "MXF")
                    .track(new TrackTags(video.id(), MediaTags.builder().set("title", "Picture").build(), Set.of()))
                    .build();
            var data = mux(source, MxfMuxerOptions.defaults(), tags);
            try (var demuxer = new MxfDemuxer(MediaSource.of(data))) {
                assertEquals("Smalt test", demuxer.metadata().get("title"));
                assertEquals("a comment", demuxer.metadata().get("comment"));
                assertEquals("MXF", demuxer.metadata().get("project"));
                assertEquals(new Timecode(1, 2, 3, 4, 25, false), demuxer.timecode().orElseThrow());
                assertEquals("Picture", demuxer.trackMetadata(demuxer.tracks().getFirst().id()).get("title"));
            }
            if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                var probe = ContainerProbe.probe(MuxSupport.save("tags.mxf", data));
                assertTrue(probe.format().entrySet().stream().anyMatch(entry -> entry.getKey().endsWith("timecode")
                        && entry.getValue().equals("01:02:03:04")), "ffprobe reads the timecode: " + probe.format());
            }
        }

        @Test
        @DisplayName("a timecode tag that is not a timecode is rejected")
        void badTimecode() {
            var source = sample("op1a-dnxhr.mxf");
            try (var muxer = new MxfMuxer(MediaSink.memory())) {
                source.tracks().forEach(muxer::addTrack);
                assertThrows(MediaException.Unsupported.class, () -> muxer.setTags(MediaTags.builder()
                        .set("timecode", "noon").build()));
            }
        }
    }

    @Nested
    @DisplayName("rejected input")
    class Rejected {
        @Test
        @DisplayName("tracks, packets and orders the muxer refuses")
        void misuse() {
            var source = sample("op1a-dnxhr.mxf");
            var aac = AudioTrack.builder().id(9).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48000))
                    .sampleRate(48000).layout(ChannelLayout.STEREO).build();
            try (var muxer = new MxfMuxer(MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(aac));
                muxer.addTrack(source.tracks().getFirst());
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(source.tracks().getFirst()));
                var other = ((VideoTrack) source.tracks().getFirst()).toBuilder().id(77)
                        .frameRate(new Rational(50, 1)).build();
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(other));
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(5, new byte[4], 0, 0,
                        1, true)));
                muxer.writePacket(source.packets().getFirst());
                assertThrows(IllegalStateException.class, () -> muxer.addTrack(source.tracks().get(1)));
                muxer.finish();
                assertThrows(IllegalStateException.class, muxer::finish);
            }
            try (var muxer = new MxfMuxer(MediaSink.memory(), MxfMuxerOptions.forOpAtom())) {
                muxer.addTrack(source.tracks().getFirst());
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(source.tracks().get(1)));
            }
            assertThrows(IllegalArgumentException.class, () -> new MxfMuxer(MediaSink.of(new ByteArrayOutputStream()),
                    MxfMuxerOptions.forOpAtom()));
            var prores = sample("op1a-prores.mxf");
            var clip = MxfMuxerOptions.builder().wrapping(MxfMuxerOptions.Wrapping.CLIP).build();
            try (var muxer = new MxfMuxer(MediaSink.memory(), clip)) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(prores.tracks().getFirst()));
            }
        }

        @Test
        @DisplayName("options out of range")
        void options() {
            assertThrows(IllegalArgumentException.class, () -> MxfMuxerOptions.builder().kagSize(0).build());
            assertThrows(IllegalArgumentException.class, () -> MxfMuxerOptions.builder().kagSize(1 << 21).build());
            assertThrows(IllegalArgumentException.class, () -> MxfMuxerOptions.builder().avcIntraClass(75).build());
            assertThrows(IllegalArgumentException.class, () -> MxfMuxerOptions.builder().partitionMicros(-1).build());
            assertThrows(IllegalArgumentException.class, () -> MxfMuxerOptions.builder()
                    .materialPackageUmid(new byte[16]).build());
            assertThrows(IllegalArgumentException.class, () -> MxfMuxerOptions.builder()
                    .audioEditRate(new Rational(1L << 32, 1)).build());
            var options = MxfMuxerOptions.builder().kagSize(1).avcIntraClass(100).build();
            assertEquals(options, options.toBuilder().build());
            assertNotEquals(options, MxfMuxerOptions.defaults());
            assertEquals(MxfMuxerOptions.Pattern.OP_ATOM, MxfMuxerOptions.forOpAtom().pattern());
            assertTrue(MxfMuxerOptions.defaults().wrapping().isEmpty());
        }

        @Test
        @DisplayName("the containers registry opens MXF muxers and reports the codecs they carry")
        void registry() {
            assertTrue(Containers.isMuxable(com.github.auties00.smalt.container.ContainerFormat.MXF, Codec.DNXHD));
            assertTrue(Containers.isMuxable(com.github.auties00.smalt.container.ContainerFormat.MXF, Codec.PCM_S24LE));
            assertFalse(Containers.isMuxable(com.github.auties00.smalt.container.ContainerFormat.MXF, Codec.AAC));
            try (var muxer = Containers.openMuxer(com.github.auties00.smalt.container.ContainerFormat.MXF,
                    MediaSink.memory())) {
                assertTrue(muxer instanceof MxfMuxer);
                assertTrue(muxer.supportsTags());
            }
        }
    }
}
