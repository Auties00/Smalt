package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the option types of the transcode API (the options convention: defaults, presets,
 * builders and value equality), the output containers and the size planning.
 */
class TranscodeApiTest {
    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("video options start from the defaults and round-trip through the builder")
        void video() {
            var defaults = VideoTranscodeOptions.defaults();
            assertEquals(StreamMode.AUTO, defaults.mode());
            assertEquals(OptionalInt.empty(), defaults.trackId());
            assertTrue(defaults.evenDimensions());
            assertEquals(OrientationMode.AUTO, defaults.orientation());
            var custom = VideoTranscodeOptions.encode(Codec.VP9).toBuilder().bitrate(500_000).maxSize(640, 0)
                    .upscale(true)
                    .maxLongEdge(800).maxShortEdge(480).evenDimensions(false).filter(ResizeFilter.AREA)
                    .crop(new Region(1, 2, 3, 4)).orientation(OrientationMode.APPLY)
                    .frameRate(new Rational(25, 1)).maxFrameRate(new Rational(30, 1))
                    .chromaFormat(ChromaFormat.YUV420).color(ColorInfo.BT709_LIMITED).background(0x123456)
                    .threads(2).trackId(3).build();
            assertEquals(custom, custom.toBuilder().build());
            assertEquals(custom.hashCode(), custom.toBuilder().build().hashCode());
            assertNotEquals(defaults, custom);
            assertEquals(Optional.of(Codec.VP9), custom.codec());
            assertEquals(OptionalInt.of(640), custom.width());
            assertEquals(OptionalInt.empty(), custom.height());
            assertEquals(0x123456, custom.background());
            assertEquals(Optional.empty(), custom.toBuilder().automaticCodec().build().codec());
            assertEquals(Optional.of(new Rational(30, 1)), custom.maxFrameRate());
            assertEquals(Optional.empty(), custom.toBuilder().noMaxFrameRate().build().maxFrameRate());
            assertEquals(OptionalInt.empty(), custom.toBuilder().firstTrack().build().trackId());
            assertEquals(StreamMode.COPY, VideoTranscodeOptions.copy().mode());
            assertEquals(StreamMode.DROP, VideoTranscodeOptions.drop().mode());
        }

        @Test
        @DisplayName("video options reject invalid values")
        void videoValidation() {
            var defaults = VideoTranscodeOptions.defaults();
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().codec(Codec.AAC).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().bitrate(0).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().threads(-1).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().size(-1, 2, ScaleMode.FIT).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().trackId(-1).build());
            assertThrows(IllegalArgumentException.class, () -> new Region(0, 0, 0, 1));
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().mode(null).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().maxFrameRate(null));
        }

        @Test
        @DisplayName("audio options start from the defaults and round-trip through the builder")
        void audio() {
            var defaults = AudioTranscodeOptions.defaults();
            assertEquals(StreamMode.AUTO, defaults.mode());
            assertFalse(defaults.allTracks());
            var custom = AudioTranscodeOptions.encode(Codec.OPUS).toBuilder().bitrate(64_000).sampleRate(24_000)
                    .layout(ChannelLayout.MONO).maxChannels(2).gainDb(-3).allTracks().build();
            assertEquals(custom, custom.toBuilder().build());
            assertTrue(custom.allTracks());
            assertEquals(OptionalInt.empty(), custom.trackId());
            assertFalse(custom.toBuilder().trackId(2).build().allTracks());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().codec(Codec.H264).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().gainDb(Double.NaN).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().sampleRate(-1).build());
            assertEquals(ChannelLayout.STEREO, defaults.toBuilder().maxChannels(2).build()
                    .outputLayout(ChannelLayout.SURROUND_5_1));
            assertEquals(ChannelLayout.MONO, defaults.outputLayout(ChannelLayout.MONO));
            assertFalse(defaults.changesSamples(48_000, ChannelLayout.STEREO));
            assertTrue(defaults.toBuilder().gainDb(1).build().changesSamples(48_000, ChannelLayout.STEREO));
            assertTrue(defaults.toBuilder().sampleRate(44_100).build().changesSamples(48_000, ChannelLayout.STEREO));
            assertFalse(defaults.toBuilder().sampleRate(48_000).build().changesSamples(48_000, ChannelLayout.STEREO));
        }

        @Test
        @DisplayName("job options compute the effective end from the range and the duration limit")
        void job() {
            var defaults = TranscodeOptions.defaults();
            assertEquals(OutputContainer.MP4, defaults.container());
            assertEquals(OptionalLong.empty(), defaults.endMicros());
            assertTrue(defaults.seeking());
            assertEquals(MediaLimits.DEFAULT, defaults.limits());
            assertEquals(-1, defaults.effectiveEndMicros());
            assertEquals(3_000_000, defaults.toBuilder().range(1_000_000, 3_000_000).build().effectiveEndMicros());
            assertEquals(1_500_000, defaults.toBuilder().range(1_000_000, 3_000_000).maxDuration(500_000).build()
                    .effectiveEndMicros());
            assertEquals(2_500_000, defaults.toBuilder().start(2_000_000).maxDuration(500_000).build()
                    .effectiveEndMicros());
            assertEquals(StreamMode.COPY, TranscodeOptions.remux(OutputContainer.WEBM).video().mode());
            assertEquals(StreamMode.DROP, defaults.toBuilder().noAudio().build().audio().mode());
            var custom = defaults.toBuilder().container(OutputContainer.OGG).seeking(false).range(1, 2).build();
            assertEquals(custom, custom.toBuilder().build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().range(5, 5).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().start(-1).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().maxDuration(0).build());
        }

        @Test
        @DisplayName("image options start from the defaults and round-trip through the builder")
        void image() {
            var defaults = ImageTranscodeOptions.defaults();
            assertEquals(Optional.empty(), defaults.format());
            assertEquals(AnimationMode.KEEP, defaults.animation());
            assertTrue(defaults.passthrough());
            var custom = ImageTranscodeOptions.of(ImageFormat.WEBP).toBuilder()
                    .encoderOptions(ImageEncoderOptions.ofQuality(80))
                    .size(100, 100, ScaleMode.FILL).maxBytes(1000).flatten(true).background(0)
                    .animation(AnimationMode.FIRST_FRAME).passthrough(false).crop(new Region(0, 0, 5, 5)).build();
            assertEquals(custom, custom.toBuilder().build());
            assertEquals(OptionalLong.of(1000), custom.maxBytes());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().maxBytes(-1).build());
        }
    }

    @Nested
    @DisplayName("output containers")
    class Containers {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"clip.mp4, MP4", "a.M4A, M4A", "x.mkv, MATROSKA", "x.webm, WEBM", "voice.opus, OGG", "x.ogg, OGG",
                "x.wav, WAV", "x.aiff, AIFF", "x.aac, ADTS", "x.ivf, IVF", "x.mp3, MP3", "x.flac, FLAC", "x.amr, AMR", "webm, WEBM",
                "x.mov, MOV", "x.qt, MOV", "x.ac3, AC3", "x.ec3, EAC3", "x.eac3, EAC3", "x.h264, H264", "x.264, H264",
                "x.h265, HEVC", "x.265, HEVC", "x.hevc, HEVC", "x.obu, AV1", "x.oga, OGG"})
        @DisplayName("are found by file extension")
        void extension(String name, OutputContainer container) {
            assertEquals(Optional.of(container), OutputContainer.forFileName(name));
        }

        @Test
        @DisplayName("write elementary streams and Ogg FLAC restricted to their codecs")
        void elementaryTargets() {
            assertEquals(java.util.List.of(Codec.FLAC), OutputContainer.OGG_FLAC.audioCodecs());
            assertTrue(OutputContainer.OGG_FLAC.supports(Codec.FLAC));
            assertFalse(OutputContainer.OGG_FLAC.supports(Codec.OPUS));
            assertTrue(OutputContainer.OGG_FLAC.needsConfig(Codec.FLAC));
            assertTrue(OutputContainer.AC3.supports(Codec.AC3));
            assertFalse(OutputContainer.AC3.supports(Codec.EAC3));
            assertTrue(OutputContainer.EAC3.supports(Codec.EAC3));
            assertEquals("audio/eac3", OutputContainer.EAC3.mimeType());
            for (var container : java.util.List.of(OutputContainer.H264, OutputContainer.HEVC, OutputContainer.AV1)) {
                var codec = container.videoCodecs().getFirst();
                assertTrue(container.supports(codec), container.toString());
                assertTrue(container.audioCodecs().isEmpty());
                assertTrue(container.subtitleCodecs().isEmpty());
                assertFalse(container.storesOrientation());
                assertFalse(container.carriesSeveralTracks());
                assertEquals(codec != Codec.AV1, container.needsConfig(codec));
                assertTrue(com.github.auties00.smalt.container.Containers.isMuxable(container.format(), codec));
                assertFalse(com.github.auties00.smalt.container.Containers.isMuxable(container.format(), Codec.VP9));
            }
            assertEquals(com.github.auties00.smalt.container.ContainerFormat.OGG, OutputContainer.OGG_FLAC.format());
            assertEquals(com.github.auties00.smalt.container.ContainerFormat.AC3, OutputContainer.EAC3.format());
            var opus = com.github.auties00.smalt.track.AudioTrack.builder().id(1).codec(Codec.OPUS)
                    .timeBase(new Rational(1, 48_000)).sampleRate(48_000).layout(ChannelLayout.STEREO).codecConfig(new byte[19]).build();
            try (var muxer = com.github.auties00.smalt.container.Containers.openMuxer(OutputContainer.OGG_FLAC,
                    com.github.auties00.smalt.io.MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(opus));
            }
            var ac3 = com.github.auties00.smalt.track.AudioTrack.builder().id(1).codec(Codec.AC3)
                    .timeBase(new Rational(1, 48_000)).sampleRate(48_000).layout(ChannelLayout.STEREO).build();
            try (var muxer = com.github.auties00.smalt.container.Containers.openMuxer(OutputContainer.EAC3,
                    com.github.auties00.smalt.io.MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(ac3));
            }
            try (var muxer = com.github.auties00.smalt.container.Containers.openMuxer(OutputContainer.AC3,
                    com.github.auties00.smalt.io.MediaSink.memory())) {
                muxer.addTrack(ac3);
            }
        }

        @Test
        @DisplayName("know which codecs they carry")
        void supports() {
            assertTrue(OutputContainer.WEBM.supports(Codec.VP9));
            assertFalse(OutputContainer.WEBM.supports(Codec.H264));
            assertTrue(OutputContainer.MATROSKA.supports(Codec.H264));
            assertFalse(OutputContainer.M4A.supports(Codec.H264));
            assertTrue(OutputContainer.M4A.supports(Codec.AAC));
            assertTrue(OutputContainer.WAV.supports(Codec.PCM_F32LE));
            assertTrue(OutputContainer.OGG.supports(Codec.VORBIS));
            assertTrue(OutputContainer.OGG.supports(Codec.FLAC));
            assertFalse(OutputContainer.OGG.supports(Codec.AAC));
            assertTrue(OutputContainer.OGG.needsConfig(Codec.FLAC));
            assertTrue(OutputContainer.MATROSKA.needsConfig(Codec.ALAC));
            assertFalse(OutputContainer.MATROSKA.needsConfig(Codec.MPEG4_PART2));
            // WavMuxer stores the AudioSpecificConfig and STREAMINFO in the format, and nothing for the others
            assertTrue(OutputContainer.WAV.needsConfig(Codec.AAC));
            assertTrue(OutputContainer.WAV.needsConfig(Codec.FLAC));
            assertFalse(OutputContainer.WAV.needsConfig(Codec.MP3));
            assertFalse(OutputContainer.WAV.needsConfig(Codec.PCM_S16LE));
            // core has no decoder of MP3 or ADPCM, so WAV copies them; PCM is always copied, and other files copy
            assertTrue(OutputContainer.WAV.copiesByDefault(Codec.PCM_S24BE));
            assertTrue(OutputContainer.WAV.copiesByDefault(Codec.MP3));
            assertTrue(OutputContainer.WAV.copiesByDefault(Codec.ADPCM_IMA_WAV));
            assertTrue(OutputContainer.MATROSKA.copiesByDefault(Codec.MP3));
            assertTrue(OutputContainer.MP4.storesOrientation());
            assertTrue(OutputContainer.MOV.supports(Codec.PRORES));
            assertTrue(OutputContainer.MOV.supports(Codec.DNXHD));
            assertTrue(OutputContainer.MOV.supports(Codec.H264));
            assertFalse(OutputContainer.MP4.supports(Codec.PRORES));
            assertFalse(OutputContainer.WEBM.hasEditLists());
            assertEquals(Optional.empty(), OutputContainer.forFileName("x.unknown"));
            for (var container : OutputContainer.values()) {
                for (var codec : container.videoCodecs()) {
                    assertTrue(container.supports(codec), container + " " + codec);
                }
                for (var codec : container.audioCodecs()) {
                    assertTrue(container.supports(codec), container + " " + codec);
                }
            }
        }
    }

    @Nested
    @DisplayName("copy decisions")
    class Copy {
        @Test
        @DisplayName("count only real changes as reasons to decode")
        void changes() {
            var defaults = VideoTranscodeOptions.defaults();
            assertFalse(defaults.changesPicture(641, 481, new Rational(30, 1)), "odd sizes are copied");
            assertTrue(defaults.toBuilder().maxShortEdge(480).build().changesPicture(1280, 720, null));
            assertFalse(defaults.toBuilder().maxShortEdge(720).build().changesPicture(1280, 720, null));
            assertTrue(defaults.toBuilder().maxFrameRate(new Rational(30, 1)).build()
                    .changesPicture(640, 480, new Rational(60, 1)));
            assertFalse(defaults.toBuilder().maxFrameRate(new Rational(30, 1)).build()
                    .changesPicture(640, 480, new Rational(25, 1)));
            assertTrue(defaults.toBuilder().maxFrameRate(new Rational(30, 1)).build().changesPicture(640, 480, null));
        }

        @Test
        @DisplayName("copy multichannel AMR into WAV, which carries it after the multichannel magic")
        void multichannelAmrIntoWav() {
            // RFC 4867 multichannel storage: the magic, a channel description of 2, then frame-blocks of two
            // 12.2 kbit/s frames (frame type 7, 31 payload bytes)
            var file = new ByteArrayOutputStream();
            file.writeBytes("#!AMR_MC1.0\n".getBytes(StandardCharsets.US_ASCII));
            file.writeBytes(new byte[]{0, 0, 0, 2});
            for (var block = 0; block < 20; block++) {
                for (var channel = 0; channel < 2; channel++) {
                    var frame = new byte[32];
                    frame[0] = 7 << 3 | 0x04;
                    Arrays.fill(frame, 1, frame.length, (byte) (block * 2 + channel));
                    file.writeBytes(frame);
                }
            }
            var input = file.toByteArray();
            for (var audio : List.of(AudioTranscodeOptions.defaults(), AudioTranscodeOptions.copy())) {
                var sink = MediaSink.memory();
                var result = Transcoder.transcode(MediaSource.of(input), sink,
                        TranscodeOptions.of(OutputContainer.WAV).toBuilder().audio(audio).build());
                assertTrue(result.streams().getFirst().copied(), audio.mode().toString());
                try (var demuxer = new WavDemuxer(MediaSource.of(sink.toByteArray()))) {
                    var track = (AudioTrack) demuxer.tracks().getFirst();
                    assertEquals(Codec.AMR_NB, track.codec());
                    assertEquals(2, track.layout().orElseThrow().channels());
                    var data = new ByteArrayOutputStream();
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        data.writeBytes(packet.data());
                    }
                    assertArrayEquals(Arrays.copyOfRange(input, 16, input.length), data.toByteArray());
                }
            }
        }
    }
}
