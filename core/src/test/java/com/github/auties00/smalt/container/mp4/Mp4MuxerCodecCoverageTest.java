package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.internal.codec.Jp2Frames;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the codecs {@link Mp4Muxer} writes besides the common ones: PCM in its QuickTime and ISO
 * encodings, A-law and mu-law, MP2, VP8, MPEG-4 Part 2, H.263 and Motion-JPEG. Each fixture is
 * encoded by ffmpeg, demuxed by Smalt, muxed and demuxed again; the packets must come back
 * unchanged, ffprobe must name the same codec and read the same packet payloads, and ffmpeg must
 * decode the audio to the same samples as the source.
 */
@DisplayName("Mp4Muxer codec coverage")
class Mp4MuxerCodecCoverageTest {
    record Case(String name, Supplier<Path> fixture, Mp4MuxerOptions.Brand brand, Codec codec, String probeCodec,
                int format) {
        @Override
        public String toString() {
            return name + " (" + brand + ")";
        }
    }

    static Path fixture(String extension, List<String> inputs, List<String> outputs) {
        return Fixtures.ffmpeg(extension, inputs, outputs);
    }

    static List<String> video(int width, int height) {
        return Mp4Fixtures.video(width, height, 1, 25);
    }

    static List<String> tone(int rate, int channels) {
        return List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=" + rate + ":duration=0.6",
                "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=" + rate + ":duration=0.6", "-filter_complex",
                "[0][1]amerge=inputs=2,pan=" + channels + "c|" + pan(channels));
    }

    static String pan(int channels) {
        var mix = new ArrayList<String>();
        for (var channel = 0; channel < channels; channel++) {
            mix.add("c" + channel + "=" + (channel % 2 == 0 ? "c0" : "c1"));
        }
        return String.join("|", mix);
    }

    static Case pcm(String codec, Codec expected, Mp4MuxerOptions.Brand brand, int format, int rate, int channels) {
        var extension = brand == Mp4MuxerOptions.Brand.QUICKTIME ? "mov" : "wav";
        return new Case(codec + " " + rate + " Hz " + channels + " ch", () -> fixture(extension, tone(rate, channels),
                List.of("-c:a", codec)), brand, expected, codec, format);
    }

    static List<Case> cases() {
        var qt = Mp4MuxerOptions.Brand.QUICKTIME;
        var mp4 = Mp4MuxerOptions.Brand.MP4;
        var m4a = Mp4MuxerOptions.Brand.M4A;
        var cases = new ArrayList<Case>();
        cases.add(pcm("pcm_s16be", Codec.PCM_S16BE, qt, BoxTypes.TWOS, 44100, 2));
        cases.add(pcm("pcm_s16le", Codec.PCM_S16LE, qt, BoxTypes.SOWT, 48000, 2));
        cases.add(pcm("pcm_s24be", Codec.PCM_S24BE, qt, BoxTypes.IN24, 48000, 2));
        cases.add(pcm("pcm_s24le", Codec.PCM_S24LE, qt, BoxTypes.LPCM, 48000, 2));
        cases.add(pcm("pcm_s32be", Codec.PCM_S32BE, qt, BoxTypes.IN32, 48000, 1));
        cases.add(pcm("pcm_s32le", Codec.PCM_S32LE, qt, BoxTypes.LPCM, 48000, 2));
        cases.add(pcm("pcm_f32be", Codec.PCM_F32BE, qt, BoxTypes.FL32, 48000, 2));
        cases.add(pcm("pcm_f32le", Codec.PCM_F32LE, qt, BoxTypes.LPCM, 48000, 2));
        cases.add(pcm("pcm_f64be", Codec.PCM_F64BE, qt, BoxTypes.FL64, 32000, 1));
        cases.add(pcm("pcm_f64le", Codec.PCM_F64LE, qt, BoxTypes.LPCM, 32000, 2));
        cases.add(pcm("pcm_u8", Codec.PCM_U8, qt, BoxTypes.RAW, 22050, 1));
        cases.add(pcm("pcm_s8", Codec.PCM_S8, qt, BoxTypes.TWOS, 22050, 2));
        cases.add(pcm("pcm_alaw", Codec.PCM_ALAW, qt, BoxTypes.ALAW, 8000, 1));
        cases.add(pcm("pcm_mulaw", Codec.PCM_MULAW, qt, BoxTypes.ULAW, 8000, 2));
        cases.add(pcm("pcm_s24le", Codec.PCM_S24LE, qt, BoxTypes.LPCM, 96000, 6));
        cases.add(pcm("pcm_s16le", Codec.PCM_S16LE, qt, BoxTypes.LPCM, 48000, 6));
        cases.add(pcm("pcm_mulaw", Codec.PCM_MULAW, qt, BoxTypes.ULAW, 8000, 4));
        cases.add(pcm("pcm_s16le", Codec.PCM_S16LE, mp4, BoxTypes.IPCM, 48000, 2));
        cases.add(pcm("pcm_s24le", Codec.PCM_S24LE, mp4, BoxTypes.IPCM, 96000, 2));
        cases.add(pcm("pcm_s32le", Codec.PCM_S32LE, mp4, BoxTypes.IPCM, 44100, 6));
        cases.add(pcm("pcm_f32le", Codec.PCM_F32LE, mp4, BoxTypes.FPCM, 48000, 2));
        cases.add(pcm("pcm_f64le", Codec.PCM_F64LE, mp4, BoxTypes.FPCM, 192000, 1));
        cases.add(new Case("pcm_s24be big-endian ipcm", () -> fixture("mov", tone(48000, 2), List.of("-c:a", "pcm_s24be")),
                mp4, Codec.PCM_S24BE, "pcm_s24be", BoxTypes.IPCM));
        // ffprobe names MPEG audio in mp4a by its object type, so it reports MP2 as mp3, as for ffmpeg's own files
        cases.add(new Case("mp2 48 kHz", () -> fixture("mp4", tone(48000, 2), List.of("-c:a", "mp2", "-b:a", "192k")),
                mp4, Codec.MP2, "mp3", BoxTypes.MP4A));
        cases.add(new Case("mp2 24 kHz", () -> fixture("mp4", tone(24000, 1), List.of("-c:a", "mp2", "-b:a", "64k")),
                m4a, Codec.MP2, "mp3", BoxTypes.MP4A));
        cases.add(new Case("vp8 from webm", () -> fixture("webm", video(176, 144), List.of("-c:v", "libvpx", "-b:v", "300k",
                "-g", "10")), mp4, Codec.VP8, "vp8", BoxTypes.VP08));
        cases.add(new Case("vp8 from mkv", () -> fixture("mkv", video(160, 120), List.of("-c:v", "libvpx", "-b:v", "300k",
                "-g", "10", "-auto-alt-ref", "0")), qt, Codec.VP8, "vp8", BoxTypes.VP08));
        cases.add(new Case("mpeg4 from mp4", () -> fixture("mp4", video(176, 144), List.of("-c:v", "mpeg4", "-bf", "2",
                "-g", "12")), mp4, Codec.MPEG4_PART2, "mpeg4", BoxTypes.MP4V));
        cases.add(new Case("mpeg4 from avi", () -> fixture("avi", video(176, 144), List.of("-c:v", "mpeg4", "-g", "12")),
                qt, Codec.MPEG4_PART2, "mpeg4", BoxTypes.MP4V));
        cases.add(new Case("h263 3gp", () -> fixture("3gp", video(176, 144), List.of("-c:v", "h263", "-g", "12")),
                mp4, Codec.H263, "h263", BoxTypes.S263));
        cases.add(new Case("h263 quicktime", () -> fixture("mov", video(352, 288), List.of("-c:v", "h263", "-g", "12")),
                qt, Codec.H263, "h263", BoxTypes.H263));
        cases.add(new Case("mjpeg quicktime", () -> fixture("mov", video(160, 120), List.of("-c:v", "mjpeg", "-q:v", "5")),
                qt, Codec.MJPEG, "mjpeg", BoxTypes.JPEG));
        cases.add(new Case("mjpeg iso", () -> fixture("mov", video(160, 120), List.of("-c:v", "mjpeg", "-q:v", "5")),
                mp4, Codec.MJPEG, "mjpeg", BoxTypes.MP4V));
        cases.add(new Case("jpeg2000 quicktime", () -> fixture("mov", video(160, 120), List.of("-c:v", "jpeg2000",
                "-pix_fmt", "yuv420p")), qt, Codec.JPEG2000, "jpeg2000", BoxTypes.MJP2));
        cases.add(new Case("jpeg2000 iso from mp4v", () -> fixture("mp4", video(160, 120), List.of("-c:v", "jpeg2000",
                "-pix_fmt", "yuv444p")), mp4, Codec.JPEG2000, "jpeg2000", BoxTypes.MJP2));
        cases.add(new Case("jpeg2000 codestreams from mkv", () -> fixture("mkv", video(160, 120), List.of("-c:v",
                "libopenjpeg", "-format", "j2k", "-pix_fmt", "yuv422p")), mp4, Codec.JPEG2000, "jpeg2000",
                BoxTypes.MJP2));
        return cases;
    }

    static Path output(String name) {
        try {
            Files.createDirectories(Mp4MuxerTest.OUTPUT);
            return Mp4MuxerTest.OUTPUT.resolve("coverage-" + name.replaceAll("[^A-Za-z0-9]+", "-") + ".mp4");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Demuxes a fixture with the demuxer of its container and muxes the tracks the brand carries.
     */
    static Path mux(Path input, String name, Mp4MuxerOptions.Brand brand) {
        var target = output(name);
        try (var source = MediaSource.of(input); var demuxer = Containers.openDemuxer(source);
             var channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING);
             var muxer = new Mp4Muxer(MediaSink.of(channel), Mp4MuxerOptions.builder().brand(brand).build())) {
            var kept = new ArrayList<Integer>();
            for (var track : demuxer.tracks()) {
                if (Mp4Muxer.supports(brand, track.codec())) {
                    muxer.addTrack(track);
                    kept.add(track.id());
                }
            }
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (kept.contains(packet.trackId())) {
                    muxer.writePacket(packet);
                }
            }
            muxer.finish();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return target;
    }

    static ContainerProbe.Demuxed demux(Path file) {
        return ContainerProbe.demux(file, Containers::openDemuxer);
    }

    static Track only(ContainerProbe.Demuxed demuxed, TrackKind kind) {
        return demuxed.tracks().stream().filter(track -> track.kind() == kind).findFirst().orElseThrow();
    }

    static byte[] concatenate(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        for (var packet : packets) {
            out.writeBytes(packet.data());
        }
        return out.toByteArray();
    }

    static String decodedMd5(Path file) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var output = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:a:0", "-c:a", "pcm_f64le", "-f", "md5", "-"));
        return new String(output, StandardCharsets.US_ASCII).trim();
    }

    static int sampleEntryType(Path file) {
        var data = Fixtures.read(file);
        var stsd = indexOf(data, "stsd");
        assertTrue(stsd > 0, "stsd present");
        return (data[stsd + 16] & 0xFF) << 24 | (data[stsd + 17] & 0xFF) << 16 | (data[stsd + 18] & 0xFF) << 8
                | data[stsd + 19] & 0xFF;
    }

    static int indexOf(byte[] data, String type) {
        var pattern = type.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (var index = 0; index + pattern.length <= data.length; index++) {
            for (var offset = 0; offset < pattern.length; offset++) {
                if (data[index + offset] != pattern[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("round-trips the packets and reads back identically in ffprobe and ffmpeg")
    void roundTrip(Case fixture) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = fixture.fixture().get();
        var source = demux(input);
        var kind = fixture.codec().kind();
        var sourceTrack = only(source, kind);
        assertEquals(fixture.codec(), sourceTrack.codec(), "source codec");
        var muxed = mux(input, fixture.toString(), fixture.brand());
        assertEquals(fixture.format(), sampleEntryType(muxed), "sample entry type");
        var result = ContainerProbe.demux(muxed, Mp4Demuxer::new);
        var track = only(result, kind);
        assertEquals(fixture.codec(), track.codec(), "codec read back");
        var before = source.packetsOf(sourceTrack);
        var after = result.packetsOf(track);
        if (fixture.codec().isPcm()) {
            var audio = (AudioTrack) track;
            var original = (AudioTrack) sourceTrack;
            assertEquals(original.sampleRate(), audio.sampleRate(), "sample rate");
            assertEquals(original.layout().orElseThrow().channels(), audio.layout().orElseThrow().channels(), "channels");
            assertArrayEquals(concatenate(before), concatenate(after), "PCM samples");
            assertEquals(decodedMd5(input), decodedMd5(muxed), "ffmpeg decode");
        } else {
            assertEquals(before.size(), after.size(), "packet count");
            for (var index = 0; index < before.size(); index++) {
                var expected = before.get(index);
                var actual = after.get(index);
                assertArrayEquals(expected.data(), actual.data(), "packet " + index);
                assertEquals(expected.keyframe(), actual.keyframe(), "keyframe " + index);
                assertEquals(sourceTrack.timeBase().rescale(expected.pts(), Rational.timeBaseOf(1_000_000)),
                        track.timeBase().rescale(actual.pts(), Rational.timeBaseOf(1_000_000)), 1000, "pts " + index);
            }
            var config = sourceTrack.codecConfig().orElse(null);
            if (config != null) {
                assertArrayEquals(config, track.codecConfig().orElseThrow(), "codec configuration");
            }
            if (kind == TrackKind.AUDIO) {
                assertEquals(decodedMd5(input), decodedMd5(muxed), "ffmpeg decode");
            }
        }
        var probe = ContainerProbe.probe(muxed);
        var stream = probe.streams().stream().filter(candidate -> fixture.probeCodec().equals(candidate.codecName()))
                .findFirst().orElse(null);
        assertNotNull(stream, "ffprobe reads " + fixture.probeCodec() + " in " + probe.streams());
        if (kind == TrackKind.VIDEO) {
            var original = ContainerProbe.probe(input);
            var originalStream = original.streams().stream().filter(candidate -> fixture.probeCodec().equals(candidate.codecName()))
                    .findFirst().orElseThrow();
            assertEquals(originalStream.get("width"), stream.get("width"), "width");
            assertEquals(originalStream.get("height"), stream.get("height"), "height");
            var hashes = original.packetsOf(originalStream.index()).stream().map(ContainerProbe.ProbePacket::hash).toList();
            var written = probe.packetsOf(stream.index()).stream().map(ContainerProbe.ProbePacket::hash).toList();
            assertEquals(hashes, written, "ffprobe packet payloads");
        } else {
            var original = ContainerProbe.probe(input).streams().stream()
                    .filter(candidate -> "audio".equals(candidate.get("codec_type"))).findFirst().orElseThrow();
            assertEquals(original.get("sample_rate"), stream.get("sample_rate"), "ffprobe sample rate");
            assertEquals(original.get("channels"), stream.get("channels"), "ffprobe channels");
        }
    }

    @Nested
    @DisplayName("derived configurations")
    class Derived {
        @Test
        @DisplayName("derives the VP8 vpcC record from the first key frame")
        void vp8() {
            var input = fixture("webm", video(320, 240), List.of("-c:v", "libvpx", "-b:v", "300k"));
            var source = only(demux(input), TrackKind.VIDEO);
            assertTrue(source.codecConfig().isEmpty(), "WebM VP8 has no configuration");
            var muxed = mux(input, "vp8-derived", Mp4MuxerOptions.Brand.MP4);
            var config = only(ContainerProbe.demux(muxed, Mp4Demuxer::new), TrackKind.VIDEO).codecConfig().orElseThrow();
            // profile 0, level 2 for 320x240, 8-bit 4:2:0 colocated, unspecified color, no initialization data
            assertArrayEquals(new byte[]{0, 20, (byte) 0x82, 2, 2, 2, 0, 0}, config);
        }

        @Test
        @DisplayName("takes the MPEG-4 Part 2 decoder configuration from the headers of the first frame")
        void mpeg4() {
            var input = fixture("avi", video(176, 144), List.of("-c:v", "mpeg4", "-g", "12"));
            var demuxed = demux(input);
            var source = only(demuxed, TrackKind.VIDEO);
            var first = demuxed.packetsOf(source).getFirst().data();
            var muxed = mux(input, "mpeg4-derived", Mp4MuxerOptions.Brand.MP4);
            var config = only(ContainerProbe.demux(muxed, Mp4Demuxer::new), TrackKind.VIDEO).codecConfig().orElseThrow();
            var gov = indexOfStartCode(first, 0xB3);
            var vop = indexOfStartCode(first, 0xB6);
            assertTrue(vop > 0, "the first frame carries its headers in band");
            assertArrayEquals(Arrays.copyOf(first, gov > 0 ? Math.min(gov, vop) : vop), config);
            var stream = ContainerProbe.probe(muxed).streams().getFirst();
            assertArrayEquals(config, stream.extradata(), "ffprobe extradata");
        }

        @Test
        @DisplayName("writes an mjpa entry for Motion-JPEG format A frames in a QuickTime file")
        void motionJpegA() {
            var input = fixture("mov", video(160, 120), List.of("-c:v", "mjpeg", "-q:v", "5"));
            var demuxed = demux(input);
            var source = (VideoTrack) only(demuxed, TrackKind.VIDEO);
            var track = source.toBuilder().id(1).build();
            var packets = new ArrayList<Packet>();
            for (var packet : demuxed.packetsOf(source)) {
                packets.add(new Packet(1, formatA(packet.data()), packet.pts(), packet.dts(), packet.duration(),
                        packet.keyframe()));
            }
            var target = output("mjpeg-format-a");
            try (var channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING);
                 var muxer = new Mp4Muxer(MediaSink.of(channel), Mp4MuxerOptions.builder()
                         .brand(Mp4MuxerOptions.Brand.QUICKTIME).build())) {
                muxer.addTrack(track);
                for (var packet : packets) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            assertEquals(BoxTypes.MJPA, sampleEntryType(target));
            var read = ContainerProbe.demux(target, Mp4Demuxer::new);
            var written = read.packetsOf(only(read, TrackKind.VIDEO));
            assertEquals(Codec.MJPEG, only(read, TrackKind.VIDEO).codec());
            assertEquals(packets.size(), written.size());
            for (var index = 0; index < packets.size(); index++) {
                assertArrayEquals(packets.get(index).data(), written.get(index).data(), "packet " + index);
            }
            assertEquals("mjpeg", ContainerProbe.probe(target).streams().getFirst().codecName());
            assertEquals(videoMd5(input), videoMd5(target), "ffmpeg decodes the same pictures");
        }

        /** Inserts the Motion-JPEG format A APP1 header (QuickTime File Format) after the SOI marker. */
        static byte[] formatA(byte[] jpeg) {
            var length = jpeg.length + 44;
            var out = new byte[length];
            out[0] = (byte) 0xFF;
            out[1] = (byte) 0xD8;
            out[2] = (byte) 0xFF;
            out[3] = (byte) 0xE1;
            out[4] = 0;
            out[5] = 42;
            System.arraycopy("mjpg".getBytes(StandardCharsets.US_ASCII), 0, out, 10, 4);
            System.arraycopy(jpeg, 2, out, 46, jpeg.length - 2);
            putInt(out, 14, length);
            putInt(out, 18, length);
            putInt(out, 22, 0);
            putInt(out, 26, marker(out, 0xDB));
            putInt(out, 30, marker(out, 0xC4));
            putInt(out, 34, marker(out, 0xC0));
            var sos = marker(out, 0xDA);
            putInt(out, 38, sos);
            putInt(out, 42, sos + 2 + ((out[sos + 2] & 0xFF) << 8 | out[sos + 3] & 0xFF));
            return out;
        }

        static int marker(byte[] jpeg, int code) {
            var position = 2;
            while (position + 4 <= jpeg.length && (jpeg[position] & 0xFF) == 0xFF) {
                if ((jpeg[position + 1] & 0xFF) == code) {
                    return position;
                }
                position += 2 + ((jpeg[position + 2] & 0xFF) << 8 | jpeg[position + 3] & 0xFF);
            }
            return 0;
        }

        static void putInt(byte[] data, int offset, int value) {
            data[offset] = (byte) (value >>> 24);
            data[offset + 1] = (byte) (value >>> 16);
            data[offset + 2] = (byte) (value >>> 8);
            data[offset + 3] = (byte) value;
        }

        @Test
        @DisplayName("writes a Motion JPEG 2000 file of jp2c samples that ffmpeg decodes to the source's pictures")
        void motionJpeg2000() {
            var input = fixture("mov", Mp4Fixtures.both(160, 120, 1, 48000), List.of("-c:v", "jpeg2000", "-pix_fmt",
                    "yuv420p", "-c:a", "aac", "-shortest"));
            var demuxed = demux(input);
            var source = only(demuxed, TrackKind.VIDEO);
            var before = demuxed.packetsOf(source);
            var muxed = mux(input, "mj2", Mp4MuxerOptions.Brand.MJ2);
            var data = Fixtures.read(muxed);
            assertArrayEquals(new byte[]{0, 0, 0, 12, 'j', 'P', ' ', ' ', 13, 10, (byte) 0x87, 10},
                    Arrays.copyOf(data, 12), "signature box");
            assertArrayEquals("ftypmjp2____mjp2mj2s".replace('_', (char) 0).getBytes(StandardCharsets.ISO_8859_1),
                    Arrays.copyOfRange(data, 16, 36), "file type box");
            assertEquals(BoxTypes.MJP2, sampleEntryType(muxed));
            for (var box : List.of("jp2h", "fiel", "jsub", "orfo")) {
                assertTrue(indexOf(data, box) > 0, box + " box");
            }
            assertEquals(java.util.Optional.of(ContainerFormat.MP4), Containers.detect(MediaSource.of(data)));
            assertTrue(ImageFormat.detect(data, data.length).isEmpty(), "not a JP2 image");
            var read = demux(muxed);
            var track = only(read, TrackKind.VIDEO);
            assertEquals(Codec.JPEG2000, track.codec());
            var first = before.getFirst().data();
            assertArrayEquals(Jp2Frames.header(first, 0, first.length), track.codecConfig().orElseThrow(),
                    "jp2h of the first frame");
            var after = read.packetsOf(track);
            assertEquals(before.size(), after.size());
            for (var index = 0; index < before.size(); index++) {
                var expected = Jp2Frames.toSample(before.get(index).data());
                assertEquals(Jp2Frames.BOXES, Jp2Frames.form(expected, 0, expected.length));
                assertArrayEquals(expected, after.get(index).data(), "sample " + index);
            }
            assertEquals(1, read.tracks().stream().filter(candidate -> candidate.kind() == TrackKind.AUDIO).count(),
                    "the AAC track is kept");
            assertEquals("jpeg2000", ContainerProbe.probe(muxed).streams().stream()
                    .filter(stream -> "video".equals(stream.get("codec_type"))).findFirst().orElseThrow().codecName());
            assertEquals(videoMd5(input), videoMd5(muxed), "ffmpeg decodes the same pictures");
        }

        @Test
        @DisplayName("builds the jp2h box of a track of raw codestreams from the first frame's main header")
        void synthesizedHeader() {
            var input = fixture("mkv", video(96, 64), List.of("-c:v", "libopenjpeg", "-format", "j2k", "-pix_fmt",
                    "yuv420p"));
            var muxed = mux(input, "jpeg2000-synthesized", Mp4MuxerOptions.Brand.MP4);
            var config = only(demux(muxed), TrackKind.VIDEO).codecConfig().orElseThrow();
            assertTrue(Jp2Frames.isHeader(config));
            assertEquals(18, Jp2Frames.headerColorSpace(config), "sYCC for subsampled chroma");
            assertArrayEquals(new long[]{96, 64}, Jp2Frames.headerSize(config));
            assertEquals(8, Jp2Frames.headerDepth(config));
            var h264 = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(Rational.timeBaseOf(90_000)).width(96)
                    .height(64).build();
            var pcm = AudioTrack.builder().id(2).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48_000))
                    .sampleRate(48_000).layout(ChannelLayout.STEREO).build();
            for (var track : List.<Track>of(h264, pcm)) {
                try (var muxer = new Mp4Muxer(MediaSink.memory(), Mp4MuxerOptions.mj2())) {
                    assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(track),
                            "MJ2 carries no " + track.codec());
                }
            }
            assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.MJ2, Codec.AAC));
            assertFalse(Mp4Muxer.supports(Mp4MuxerOptions.Brand.M4A, Codec.JPEG2000));
        }

        static String videoMd5(Path file) {
            var output = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                    "-map", "0:v:0", "-f", "md5", "-"));
            return new String(output, StandardCharsets.US_ASCII).trim();
        }

        static int indexOfStartCode(byte[] data, int code) {
            for (var index = 0; index + 3 < data.length; index++) {
                if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1 && (data[index + 3] & 0xFF) == code) {
                    return index;
                }
            }
            return -1;
        }
    }

    @Nested
    @DisplayName("PCM layout")
    class PcmLayout {
        static AudioTrack track(Codec codec, int rate, ChannelLayout layout) {
            return AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(rate)).sampleRate(rate)
                    .layout(layout).build();
        }

        static Path write(String name, Mp4MuxerOptions.Brand brand, AudioTrack track, List<byte[]> packets, long firstPts) {
            var target = output(name);
            try (var channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING);
                 var muxer = new Mp4Muxer(MediaSink.of(channel), Mp4MuxerOptions.builder().brand(brand).build())) {
                muxer.addTrack(track);
                var time = firstPts;
                for (var packet : packets) {
                    muxer.writePacket(new Packet(track.id(), packet, time, time, Packet.UNKNOWN_DURATION, true));
                    time += 7;
                }
                muxer.finish();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return target;
        }

        static byte[] noise(int size, int seed) {
            var data = new byte[size];
            new java.util.Random(seed).nextBytes(data);
            return data;
        }

        @Test
        @DisplayName("stores one frame per sample and keeps frames contiguous whatever the packet timestamps")
        void tables() {
            var track = track(Codec.PCM_S16LE, 48000, ChannelLayout.STEREO);
            var packets = List.of(noise(4000, 1), noise(400, 2), noise(12, 3));
            var file = write("pcm-tables", Mp4MuxerOptions.Brand.QUICKTIME, track, packets, 0);
            var data = Fixtures.read(file);
            var stsz = indexOf(data, "stsz");
            assertEquals(4, readInt(data, stsz + 8), "constant sample size is the frame size");
            assertEquals(1103, readInt(data, stsz + 12), "one sample per frame");
            var stts = indexOf(data, "stts");
            assertEquals(1, readInt(data, stts + 8), "one timing run");
            assertEquals(1103, readInt(data, stts + 12));
            assertEquals(1, readInt(data, stts + 16), "each frame lasts one tick");
            var demuxed = ContainerProbe.demux(file, Mp4Demuxer::new);
            var read = only(demuxed, TrackKind.AUDIO);
            assertEquals(48000, ((AudioTrack) read).sampleRate());
            assertArrayEquals(concatenate(packets.stream().map(bytes -> new Packet(1, bytes, 0, 0, 0, true)).toList()),
                    concatenate(demuxed.packetsOf(read)));
        }

        @Test
        @DisplayName("places a late first packet with an empty edit and writes the speaker layout")
        void startAndLayout() {
            var layout = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));
            for (var brand : List.of(Mp4MuxerOptions.Brand.QUICKTIME, Mp4MuxerOptions.Brand.MP4)) {
                var track = track(Codec.PCM_S24LE, 48000, layout);
                var file = write("pcm-start-" + brand, brand, track, List.of(noise(18 * 480, 4)), 24000);
                var demuxed = ContainerProbe.demux(file, Mp4Demuxer::new);
                var read = (AudioTrack) only(demuxed, TrackKind.AUDIO);
                assertEquals(layout, read.layout().orElseThrow(), brand + " layout");
                var first = demuxed.packetsOf(read).getFirst();
                assertEquals(500_000, read.timeBase().rescale(first.pts(), Rational.timeBaseOf(1_000_000)), brand + " start");
            }
        }

        @Test
        @DisplayName("writes the height speakers of a 7.1.4 layout with a top center to chnl and chan")
        void heightLayout() {
            var layout = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT,
                    Speaker.TOP_CENTER, Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_CENTER, Speaker.TOP_FRONT_RIGHT,
                    Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_CENTER, Speaker.TOP_BACK_RIGHT));
            for (var brand : List.of(Mp4MuxerOptions.Brand.QUICKTIME, Mp4MuxerOptions.Brand.MP4)) {
                var track = track(Codec.PCM_S16LE, 48000, layout);
                var file = write("pcm-height-" + brand, brand, track, List.of(noise(30 * 480, 5)), 0);
                var data = Fixtures.read(file);
                if (brand == Mp4MuxerOptions.Brand.MP4) {
                    var chnl = indexOf(data, "chnl");
                    assertTrue(chnl > 0, "chnl present");
                    var positions = Arrays.copyOfRange(data, chnl + 10, chnl + 10 + layout.channels());
                    // ISO/IEC 23091-3: Lsr 8, Rsr 9, Ls 4, Rs 5, Ts 25, Lv 17, Cv 19, Rv 18, Lvr 20, Cvr 22, Rvr 21
                    assertArrayEquals(new byte[]{0, 1, 2, 3, 8, 9, 4, 5, 25, 17, 19, 18, 20, 22, 21}, positions);
                }
                var demuxed = ContainerProbe.demux(file, Mp4Demuxer::new);
                var read = (AudioTrack) only(demuxed, TrackKind.AUDIO);
                assertEquals(layout, read.layout().orElseThrow(), brand + " layout");
                Oracle.assume(Oracle.Tool.FFPROBE);
                var stream = ContainerProbe.probe(file).streams().getFirst();
                assertEquals("15", stream.get("channels"), brand + " ffprobe channels");
                assertEquals(PROBE_HEIGHT_LAYOUT, stream.get("channel_layout"), brand + " ffprobe layout");
            }
        }

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.ValueSource(strings = {"5.1", "5.1(side)", "7.1"})
        @DisplayName("reads the chnl box ffmpeg writes as the layout ffmpeg declares, and writes it back alike")
        void ffmpegChnl(String ffmpegLayout) {
            var pan = new StringBuilder("pan=" + ffmpegLayout);
            var channels = ffmpegLayout.equals("7.1") ? 8 : 6;
            for (var channel = 0; channel < channels; channel++) {
                pan.append("|c").append(channel).append("=c0");
            }
            var input = fixture("mp4", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.2",
                    "-af", pan.toString()), List.of("-c:a", "pcm_s16le"));
            var expected = switch (ffmpegLayout) {
                case "5.1" -> ChannelLayout.SURROUND_5_1;
                case "7.1" -> ChannelLayout.SURROUND_7_1;
                default -> new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                        Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));
            };
            var read = (AudioTrack) only(ContainerProbe.demux(input, Mp4Demuxer::new), TrackKind.AUDIO);
            assertEquals(expected, read.layout().orElseThrow(), "layout of ffmpeg's file");
            var muxed = mux(input, "chnl-" + ffmpegLayout, Mp4MuxerOptions.Brand.MP4);
            var original = Fixtures.read(input);
            var written = Fixtures.read(muxed);
            var at = indexOf(original, "chnl");
            var to = indexOf(written, "chnl");
            assertArrayEquals(Arrays.copyOfRange(original, at + 10, at + 10 + channels),
                    Arrays.copyOfRange(written, to + 10, to + 10 + channels), "speaker positions");
        }

        /** The layout ffprobe names for FL FR FC LFE BL BR SL SR TC TFL TFC TFR TBL TBC TBR. */
        static final String PROBE_HEIGHT_LAYOUT = "15 channels (FL+FR+FC+LFE+BL+BR+SL+SR+TC+TFL+TFC+TFR+TBL+TBC+TBR)";

        @Test
        @DisplayName("writes and reads the 22.2 and wide layouts, the surround direct pair only through chnl")
        void extendedLayouts() {
            var twentyTwoTwo = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.FRONT_LEFT_OF_CENTER,
                    Speaker.FRONT_RIGHT_OF_CENTER, Speaker.BACK_CENTER, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT,
                    Speaker.TOP_CENTER, Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_CENTER, Speaker.TOP_FRONT_RIGHT,
                    Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_CENTER, Speaker.TOP_BACK_RIGHT, Speaker.LOW_FREQUENCY_2,
                    Speaker.TOP_SIDE_LEFT, Speaker.TOP_SIDE_RIGHT, Speaker.BOTTOM_FRONT_CENTER,
                    Speaker.BOTTOM_FRONT_LEFT, Speaker.BOTTOM_FRONT_RIGHT));
            var wide = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.WIDE_LEFT,
                    Speaker.WIDE_RIGHT, Speaker.SURROUND_DIRECT_LEFT, Speaker.SURROUND_DIRECT_RIGHT));
            for (var brand : List.of(Mp4MuxerOptions.Brand.QUICKTIME, Mp4MuxerOptions.Brand.MP4)) {
                for (var layout : List.of(twentyTwoTwo, wide)) {
                    var track = track(Codec.PCM_S16LE, 48000, layout);
                    var file = write("pcm-" + layout.channels() + "-" + brand, brand, track,
                            List.of(noise(2 * layout.channels() * 480, 5)), 0);
                    var read = (AudioTrack) only(ContainerProbe.demux(file, Mp4Demuxer::new), TrackKind.AUDIO);
                    var expected = layout;
                    if (brand == Mp4MuxerOptions.Brand.QUICKTIME && layout == wide) {
                        // QuickTime labels the side pair as the surround direct pair, so the latter has no label
                        expected = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.WIDE_LEFT,
                                Speaker.WIDE_RIGHT, Speaker.UNKNOWN, Speaker.UNKNOWN));
                    }
                    assertEquals(expected, read.layout().orElseThrow(), brand + " " + layout.channels());
                    if (brand == Mp4MuxerOptions.Brand.MP4 && Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                        var probe = Oracle.probe(file);
                        // the side pair is written as the surround pair (Ls, Rs), which ffprobe reads as SL and
                        // SR; it names that 24-speaker layout 22.2 or spells it out
                        assertTrue(layout == wide ? probe.contains("FL+FR+WL+WR+SDL+SDR")
                                : probe.contains("(FL+FR+FC+LFE+BL+BR+FLC+FRC+BC+SL+SR+TC+TFL+TFC+TFR+TBL+TBC+TBR+LFE2+TSL"
                                + "+TSR+BFC+BFL+BFR)") || probe.contains("\"channel_layout\": \"22.2\""), probe);
                    }
                }
            }
        }

        @Test
        @DisplayName("reads the QuickTime rear surround and side surround labels as the back and side pairs")
        void quickTimeSurroundLabels() throws Exception {
            var track = track(Codec.PCM_S16LE, 48000, ChannelLayout.SURROUND_7_1);
            var file = write("pcm-chan-labels", Mp4MuxerOptions.Brand.QUICKTIME, track, List.of(noise(16 * 480, 6)), 0);
            var data = Fixtures.read(file);
            var chan = indexOf(data, "chan");
            assertEquals(8, readInt(data, chan + 16), "channel descriptions");
            // labels 5, 6, 10 and 11 become RearSurroundLeft/Right (33, 34) and LeftSideSurround/RightSideSurround (55, 56)
            var replaced = new int[]{1, 2, 3, 4, 33, 34, 55, 56};
            for (var i = 0; i < replaced.length; i++) {
                var at = chan + 20 + 20 * i;
                data[at] = 0;
                data[at + 1] = 0;
                data[at + 2] = 0;
                data[at + 3] = (byte) replaced[i];
            }
            var patched = output("pcm-chan-labels-patched");
            Files.write(patched, data);
            var read = (AudioTrack) only(ContainerProbe.demux(patched, Mp4Demuxer::new), TrackKind.AUDIO);
            assertEquals(ChannelLayout.SURROUND_7_1, read.layout().orElseThrow());
        }

        @Test
        @DisplayName("rejects packets that are not whole frames, tracks without rate or layout, and brands without the format")
        void rejects() {
            var target = output("pcm-rejects");
            try (var channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING);
                 var muxer = new Mp4Muxer(MediaSink.of(channel), Mp4MuxerOptions.builder()
                         .brand(Mp4MuxerOptions.Brand.QUICKTIME).build())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(9)
                        .codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48000)).build()));
                muxer.addTrack(track(Codec.PCM_S24LE, 48000, ChannelLayout.STEREO));
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(1, new byte[7], 0, 0,
                        Packet.UNKNOWN_DURATION, true)));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            assertFalse(Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, Codec.PCM_U8));
            assertFalse(Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, Codec.PCM_ALAW));
            assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, Codec.PCM_F64BE));
            assertFalse(Mp4Muxer.supports(Mp4MuxerOptions.Brand.M4A, Codec.PCM_F64BE));
            assertFalse(Mp4Muxer.supports(Mp4MuxerOptions.Brand.M4A, Codec.PCM_S16LE));
            assertFalse(Mp4Muxer.supports(Mp4MuxerOptions.Brand.M4A, Codec.VP8));
            assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.QUICKTIME, Codec.PCM_MULAW));
            try (var muxer = new Mp4Muxer(MediaSink.of(new com.github.auties00.smalt.container.MemoryChannel()))) {
                var error = assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(track(Codec.PCM_U8, 8000,
                        ChannelLayout.MONO)));
                assertTrue(error.getMessage().contains("QuickTime"), error.getMessage());
            }
            try (var muxer = new Mp4Muxer(MediaSink.of(new com.github.auties00.smalt.container.MemoryChannel()),
                    Mp4MuxerOptions.m4a())) {
                var error = assertThrows(IllegalArgumentException.class,
                        () -> muxer.addTrack(track(Codec.PCM_S16LE, 8000, ChannelLayout.MONO)));
                assertTrue(error.getMessage().contains("M4A"), error.getMessage());
            }
        }

        static int readInt(byte[] data, int offset) {
            return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                    | data[offset + 3] & 0xFF;
        }
    }

    @Test
    @DisplayName("demuxes and remuxes mutated files of the new sample entries without hanging or leaking unchecked exceptions")
    void mutations() {
        var inputs = new ArrayList<byte[]>();
        for (var fixture : cases()) {
            if (fixture.name().contains("6 ch") || fixture.name().contains("96000") || fixture.name().startsWith("mpeg4 from mp4")
                    || fixture.name().startsWith("h263 3gp") || fixture.name().startsWith("vp8 from webm")
                    || fixture.name().startsWith("mjpeg quicktime") || fixture.name().startsWith("pcm_mulaw 8000 Hz 4")) {
                inputs.add(Fixtures.read(mux(fixture.fixture().get(), "mutation-" + fixture, fixture.brand())));
            }
        }
        var pcm = demux(fixture("wav", tone(48000, 2), List.of("-c:a", "pcm_s16le")));
        inputs.add(com.github.auties00.smalt.container.MuxSupport.toMemory(pcm.tracks(), pcm.order(),
                sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.builder().fragmentMicros(100_000).build())));
        var seed = 0x5EED_0C0DL;
        var total = new java.util.TreeMap<String, Integer>();
        for (var input : inputs) {
            var variants = com.github.auties00.smalt.container.Mutations.variants(input, Mp4RobustnessTest.sizeFields(input),
                    seed++, 40, false);
            var outcomes = com.github.auties00.smalt.container.Mutations.run(variants, Duration.ofSeconds(20), source -> {
                try (var demuxer = new Mp4Demuxer(source)) {
                    while (demuxer.read() != null) {
                        // drain every packet
                    }
                }
                Mp4Remuxer.remux(source, MediaSink.of(new com.github.auties00.smalt.container.MemoryChannel()),
                        RemuxOptions.video().toBuilder().brand(Mp4MuxerOptions.Brand.QUICKTIME).allAudioTracks().build());
            });
            outcomes.forEach((key, value) -> total.merge(key, value, Integer::sum));
        }
        assertTrue(inputs.size() >= 8, "inputs " + inputs.size());
        assertTrue(total.values().stream().mapToInt(Integer::intValue).sum() >= 300, total.toString());
    }

    @Test
    @DisplayName("keeps the video packets of each new codec unchanged in a progressive file too")
    void progressive() {
        var input = fixture("mp4", video(176, 144), List.of("-c:v", "mpeg4", "-bf", "2", "-g", "12"));
        var target = output("mpeg4-progressive");
        try (var source = MediaSource.of(input); var demuxer = Containers.openDemuxer(source);
             var channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING);
             var muxer = new Mp4Muxer(MediaSink.of(channel), Mp4MuxerOptions.builder().fastStart(false).build())) {
            for (var track : demuxer.tracks()) {
                muxer.addTrack(track);
            }
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        Mp4MuxerTest.assertSameProbe(input, target);
        assertTrue(only(demux(target), TrackKind.VIDEO) instanceof VideoTrack);
    }
}
