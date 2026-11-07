package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.loudness.LoudnessNormalization;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.WatermarkOptions;
import com.github.auties00.smalt.subtitle.SubtitleFont;
import com.github.auties00.smalt.subtitle.SubtitleStyle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the named resets and the {@code maxSize} setters of the transcode options builders: each
 * reset restores the default of its setting after the setting was given a value, and leaves the
 * other settings alone.
 */
@DisplayName("Transcode options builders")
class TranscodeOptionsResetsTest {
    @Nested
    @DisplayName("TranscodeOptions.Builder")
    class Job {
        @Test
        @DisplayName("noVideo, noAudio and noSubtitles drop their stream and keep its other settings")
        void drops() {
            var video = VideoTranscodeOptions.builder().trackId(3).build();
            var options = TranscodeOptions.builder()
                    .video(video)
                    .noVideo()
                    .noAudio()
                    .noSubtitles()
                    .build();
            assertEquals(StreamMode.DROP, options.video().mode());
            assertEquals(OptionalInt.of(3), options.video().trackId());
            assertEquals(StreamMode.DROP, options.audio().mode());
            assertEquals(StreamMode.DROP, options.subtitles().mode());
            assertEquals(VideoTranscodeOptions.drop(), TranscodeOptions.builder().noVideo().build().video());
            assertEquals(AudioTranscodeOptions.drop(), TranscodeOptions.builder().noAudio().build().audio());
            assertEquals(SubtitleTranscodeOptions.drop(), TranscodeOptions.builder().noSubtitles().build().subtitles());
        }

        @Test
        @DisplayName("noMaxDuration removes the duration limit")
        void noMaxDuration() {
            var limited = TranscodeOptions.builder().maxDuration(5_000_000).build();
            assertEquals(OptionalLong.of(5_000_000), limited.maxDurationMicros());
            var options = limited.toBuilder().noMaxDuration().build();
            assertEquals(OptionalLong.empty(), options.maxDurationMicros());
            assertEquals(TranscodeOptions.defaults(), options);
        }
    }

    @Nested
    @DisplayName("AudioTranscodeOptions.Builder")
    class Audio {
        @Test
        @DisplayName("resets the codec, bitrate, sampling rate, layout and channel limit")
        void resets() {
            var set = AudioTranscodeOptions.builder()
                    .codec(Codec.OPUS)
                    .bitrate(96_000)
                    .sampleRate(24_000)
                    .layout(ChannelLayout.MONO)
                    .maxChannels(2)
                    .gainDb(3)
                    .build();
            var reset = set.toBuilder()
                    .automaticCodec()
                    .automaticBitrate()
                    .sourceSampleRate()
                    .sourceLayout()
                    .noMaxChannels()
                    .build();
            assertEquals(Optional.empty(), reset.codec());
            assertEquals(OptionalInt.empty(), reset.bitrate());
            assertEquals(OptionalInt.empty(), reset.sampleRate());
            assertEquals(Optional.empty(), reset.layout());
            assertEquals(OptionalInt.empty(), reset.maxChannels());
            assertEquals(3, reset.gainDb());
            assertEquals(AudioTranscodeOptions.builder().gainDb(3).build(), reset);
        }

        @Test
        @DisplayName("noLoudnessNormalization removes the normalization, and the setter rejects null")
        void noLoudnessNormalization() {
            var set = AudioTranscodeOptions.builder().loudnessNormalization(LoudnessNormalization.forPodcast())
                    .bitrate(96_000).build();
            assertEquals(Optional.of(LoudnessNormalization.forPodcast()), set.loudnessNormalization());
            var reset = set.toBuilder().noLoudnessNormalization().build();
            assertEquals(Optional.empty(), reset.loudnessNormalization());
            assertEquals(AudioTranscodeOptions.builder().bitrate(96_000).build(), reset);
            assertThrows(NullPointerException.class, () -> AudioTranscodeOptions.builder().loudnessNormalization(null));
        }
    }

    @Nested
    @DisplayName("VideoTranscodeOptions.Builder")
    class Video {
        @Test
        @DisplayName("maxSize fits the picture in a box")
        void maxSize() {
            var options = VideoTranscodeOptions.builder().maxSize(640, 360).build();
            assertEquals(OptionalInt.of(640), options.width());
            assertEquals(OptionalInt.of(360), options.height());
            assertEquals(ScaleMode.FIT, options.scaleMode());
            assertEquals(VideoTranscodeOptions.builder().size(640, 360, ScaleMode.FIT).build(), options);
        }

        @Test
        @DisplayName("resets the codec, bitrate, edges, frame rate, chroma format and color")
        void resets() {
            var set = VideoTranscodeOptions.builder()
                    .codec(Codec.VP9)
                    .bitrate(1_000_000)
                    .maxLongEdge(1280)
                    .maxShortEdge(720)
                    .frameRate(new Rational(30, 1))
                    .chromaFormat(ChromaFormat.YUV444)
                    .color(ColorInfo.BT709_LIMITED)
                    .threads(2)
                    .build();
            var reset = set.toBuilder()
                    .automaticCodec()
                    .automaticBitrate()
                    .noMaxLongEdge()
                    .noMaxShortEdge()
                    .sourceFrameRate()
                    .automaticChromaFormat()
                    .sourceColor()
                    .build();
            assertEquals(Optional.empty(), reset.codec());
            assertEquals(OptionalInt.empty(), reset.bitrate());
            assertEquals(OptionalInt.empty(), reset.maxLongEdge());
            assertEquals(OptionalInt.empty(), reset.maxShortEdge());
            assertEquals(Optional.empty(), reset.frameRate());
            assertEquals(Optional.empty(), reset.chromaFormat());
            assertEquals(Optional.empty(), reset.color());
            assertEquals(VideoTranscodeOptions.builder().threads(2).build(), reset);
        }

        @Test
        @DisplayName("resets the depth, deinterlacing, field order, burn-in and watermark, and checks the depth")
        void processingResets() throws java.io.IOException {
            byte[] font;
            try (var stream = TranscodeOptionsResetsTest.class.getResourceAsStream(
                    "/com/github/auties00/smalt/subtitle/smalt-test.ttf")) {
                font = stream.readAllBytes();
            }
            var burnIn = SubtitleBurnIn.of(SubtitleStyle.of(SubtitleFont.of(font)));
            var mark = RgbaPicture.allocate(4, 4, true);
            var set = VideoTranscodeOptions.builder()
                    .bitDepth(12)
                    .deinterlacer(Deinterlacer.ELA)
                    .fieldRate(true)
                    .fieldOrder(FieldOrder.BOTTOM_FIRST)
                    .frameRateConversion(FrameRateConversion.BLEND)
                    .pulldown(Pulldown.INVERSE_TELECINE)
                    .subtitleBurnIn(burnIn)
                    .watermark(mark, WatermarkOptions.builder().opacity(0.5).build())
                    .threads(2)
                    .build();
            assertEquals(set, set.toBuilder().build());
            assertEquals(OptionalInt.of(12), set.bitDepth());
            assertEquals(Optional.of(Deinterlacer.ELA), set.deinterlacer());
            assertEquals(Optional.of(FieldOrder.BOTTOM_FIRST), set.fieldOrder());
            assertEquals(Optional.of(burnIn), set.subtitleBurnIn());
            // the options hold a copy of the mark, and hand out copies
            mark.pixels()[0] = 1;
            assertEquals(0, ((RgbaPicture) set.watermark().orElseThrow()).pixels()[0]);
            var reset = set.toBuilder()
                    .automaticBitDepth()
                    .noDeinterlacer()
                    .trackFieldOrder()
                    .noSubtitleBurnIn()
                    .noWatermark()
                    .fieldRate(false)
                    .frameRateConversion(FrameRateConversion.DROP_REPEAT)
                    .pulldown(Pulldown.NONE)
                    .build();
            assertEquals(OptionalInt.empty(), reset.bitDepth());
            assertEquals(Optional.empty(), reset.deinterlacer());
            assertEquals(Optional.empty(), reset.fieldOrder());
            assertEquals(Optional.empty(), reset.subtitleBurnIn());
            assertEquals(Optional.empty(), reset.watermark());
            assertEquals(WatermarkOptions.defaults(), reset.watermarkOptions());
            assertEquals(VideoTranscodeOptions.builder().threads(2).build(), reset);
            assertThrows(IllegalArgumentException.class, () -> VideoTranscodeOptions.builder().bitDepth(7).build());
            assertThrows(IllegalArgumentException.class, () -> VideoTranscodeOptions.builder().bitDepth(17).build());
            assertThrows(NullPointerException.class, () -> VideoTranscodeOptions.builder().deinterlacer(null));
            assertThrows(IllegalArgumentException.class, () -> SubtitleBurnIn.builder(burnIn.style()).trackId(-2).build());
            assertEquals(OptionalInt.empty(), SubtitleBurnIn.builder(burnIn.style()).trackId(4).firstTrack().build()
                    .trackId());
        }
    }

    @Nested
    @DisplayName("SubtitleTranscodeOptions.Builder")
    class Subtitles {
        @Test
        @DisplayName("automaticCodec lets the container choose")
        void automaticCodec() {
            var options = SubtitleTranscodeOptions.builder().codec(Codec.WEBVTT).automaticCodec().build();
            assertEquals(Optional.empty(), options.codec());
            assertEquals(SubtitleTranscodeOptions.defaults(), options);
        }
    }

    @Nested
    @DisplayName("ImageTranscodeOptions.Builder")
    class Image {
        @Test
        @DisplayName("maxSize fits the picture in a box")
        void maxSize() {
            var options = ImageTranscodeOptions.builder().maxSize(300, 200).build();
            assertEquals(OptionalInt.of(300), options.width());
            assertEquals(OptionalInt.of(200), options.height());
            assertEquals(ScaleMode.FIT, options.scaleMode());
        }

        @Test
        @DisplayName("resets the format, edges and byte budget")
        void resets() {
            var set = ImageTranscodeOptions.builder()
                    .format(ImageFormat.PNG)
                    .maxLongEdge(1000)
                    .maxShortEdge(500)
                    .maxBytes(65_536)
                    .flatten(true)
                    .build();
            var reset = set.toBuilder()
                    .sourceFormat()
                    .noMaxLongEdge()
                    .noMaxShortEdge()
                    .noMaxBytes()
                    .build();
            assertEquals(Optional.empty(), reset.format());
            assertEquals(OptionalInt.empty(), reset.maxLongEdge());
            assertEquals(OptionalInt.empty(), reset.maxShortEdge());
            assertEquals(OptionalLong.empty(), reset.maxBytes());
            assertEquals(ImageTranscodeOptions.builder().flatten(true).build(), reset);
        }
    }
}
