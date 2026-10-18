package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
                "x.mov, MOV", "x.qt, MOV"})
        @DisplayName("are found by file extension")
        void extension(String name, OutputContainer container) {
            assertEquals(Optional.of(container), OutputContainer.forFileName(name));
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
            assertFalse(OutputContainer.OGG.supports(Codec.FLAC));
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
    }
}
