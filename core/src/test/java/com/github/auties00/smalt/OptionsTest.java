package com.github.auties00.smalt;

import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.container.mp4.RemuxOptions;
import com.github.auties00.smalt.container.ogg.OggMuxerOptions;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the options convention on the core options types: defaults and presets, withers that
 * leave the original untouched, builders that round-trip through {@code toBuilder()}, value
 * equality, and validation.
 */
@DisplayName("Options types")
class OptionsTest {
    private static final MediaLimits SMALL = MediaLimits.builder().maxDimension(64).maxPixels(4096).maxFrames(2)
            .maxChannels(2).maxBytes(1 << 20).build();

    @Test
    @DisplayName("ImageDecoderOptions")
    void imageDecoder() {
        var defaults = ImageDecoderOptions.defaults();
        assertEquals(MediaLimits.DEFAULT, defaults.limits());
        assertEquals(0, defaults.minWidth());
        var hinted = ImageDecoderOptions.ofMinimumSize(640, 480).toBuilder().limits(SMALL).build();
        assertEquals(640, hinted.minWidth());
        assertEquals(480, hinted.minHeight());
        assertEquals(SMALL, hinted.limits());
        assertEquals(hinted, hinted.toBuilder().build());
        assertEquals(hinted.toBuilder().fullSize().build(), ImageDecoderOptions.builder().limits(SMALL).build());
        assertEquals(0, defaults.minHeight());
        assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().minSize(-1, 0).build());
        assertThrows(NullPointerException.class, () -> defaults.toBuilder().limits(null).build());
    }

    @Test
    @DisplayName("ImageEncoderOptions")
    void imageEncoder() {
        assertEquals(OptionalInt.empty(), ImageEncoderOptions.defaults().quality());
        assertFalse(ImageEncoderOptions.defaults().isLossless());
        assertTrue(ImageEncoderOptions.lossless().isLossless());
        assertEquals(OptionalInt.of(90), ImageEncoderOptions.ofQuality(90).quality());
        assertEquals(ImageEncoderOptions.ofQuality(90), ImageEncoderOptions.builder().quality(90).build());
        assertThrows(IllegalArgumentException.class, () -> ImageEncoderOptions.ofQuality(0));
        assertThrows(IllegalArgumentException.class, () -> ImageEncoderOptions.ofQuality(101));
        assertEquals(0, ImageEncoderOptions.defaults().loopCount());
        var looped = ImageEncoderOptions.lossless().toBuilder().loopCount(3).build();
        assertEquals(3, looped.loopCount());
        assertTrue(looped.isLossless());
        assertEquals(looped, looped.toBuilder().build());
        assertEquals(looped, ImageEncoderOptions.builder().lossless(true).loopCount(3).build());
        assertThrows(IllegalArgumentException.class, () -> ImageEncoderOptions.builder().loopCount(-1).build());
    }

    @Test
    @DisplayName("AudioDecoderOptions and VideoDecoderOptions")
    void decoders() {
        assertEquals(OptionalInt.empty(), AudioDecoderOptions.defaults().preferredSampleRate());
        var audio = AudioDecoderOptions.builder().preferredSampleRate(16_000).limits(SMALL).build();
        assertEquals(OptionalInt.of(16_000), audio.preferredSampleRate());
        assertEquals(audio, audio.toBuilder().build());
        assertEquals(OptionalInt.empty(), audio.toBuilder().nativeSampleRate().build().preferredSampleRate());
        assertThrows(IllegalArgumentException.class, () -> AudioDecoderOptions.builder().preferredSampleRate(0)
                .build());
        assertEquals(0, VideoDecoderOptions.defaults().threads());
        var video = VideoDecoderOptions.builder().threads(4).limits(SMALL).build();
        assertEquals(VideoDecoderOptions.builder().threads(4).limits(SMALL).build(), video);
        assertNotEquals(VideoDecoderOptions.defaults(), video);
        assertThrows(IllegalArgumentException.class, () -> VideoDecoderOptions.builder().threads(-1).build());
        assertFalse(VideoDecoderOptions.defaults().highBitDepth());
        var wide = video.toBuilder().highBitDepth(true).build();
        assertTrue(wide.highBitDepth());
        assertEquals(wide, video.toBuilder().highBitDepth(true).build());
        assertEquals(wide.hashCode(), wide.toBuilder().build().hashCode());
        assertNotEquals(video, wide);
    }

    @Test
    @DisplayName("AudioEncoderOptions and VideoEncoderOptions")
    void encoders() {
        var audio = AudioEncoderOptions.defaults();
        assertEquals(48_000, audio.sampleRate());
        assertEquals(ChannelLayout.STEREO, audio.layout());
        assertEquals(OptionalInt.empty(), audio.bitrate());
        assertFalse(audio.realtime());
        var speech = AudioEncoderOptions.forRealtime(16_000, ChannelLayout.MONO).toBuilder().bitrate(24_000).build();
        assertTrue(speech.realtime());
        assertEquals(OptionalInt.of(24_000), speech.bitrate());
        assertEquals(speech, speech.toBuilder().build());
        assertEquals(OptionalInt.empty(), speech.toBuilder().automaticBitrate().build().bitrate());
        assertThrows(IllegalArgumentException.class, () -> audio.toBuilder().bitrate(0).build());
        assertEquals(OptionalInt.empty(), audio.bitsPerSample());
        var lossless = audio.toBuilder().bitsPerSample(24).build();
        assertEquals(OptionalInt.of(24), lossless.bitsPerSample());
        assertEquals(lossless, lossless.toBuilder().build());
        assertNotEquals(audio, lossless);
        assertEquals(audio, lossless.toBuilder().automaticBitsPerSample().build());
        assertThrows(IllegalArgumentException.class, () -> audio.toBuilder().bitsPerSample(33).build());
        assertThrows(IllegalArgumentException.class, () -> audio.toBuilder().bitsPerSample(0).build());
        var video = VideoEncoderOptions.forRealtime(640, 360, new Rational(30, 1), 800_000).toBuilder().threads(1)
                .build();
        assertTrue(video.realtime());
        assertEquals(1, video.threads());
        assertEquals(video, video.toBuilder().build());
        assertEquals(VideoEncoderOptions.of(640, 360, new Rational(30, 1), 800_000).toBuilder().realtime(true)
                .threads(1).build(), video);
        assertThrows(IllegalArgumentException.class, () -> VideoEncoderOptions.of(0, 360, new Rational(30, 1), 1));
        assertThrows(IllegalArgumentException.class, () -> video.toBuilder().bitrate(0).build());
    }

    @Test
    @DisplayName("reader and demuxer options")
    void readers() {
        assertEquals(MediaLimits.DEFAULT, DemuxerOptions.defaults().limits());
        assertEquals(SMALL, DemuxerOptions.builder().limits(SMALL).build().limits());
        var audio = AudioReaderOptions.defaults();
        assertEquals(OptionalInt.empty(), audio.sampleRate());
        assertEquals(Optional.empty(), audio.layout());
        assertEquals(AudioReaderOptions.DEFAULT_BLOCK_FRAMES, audio.blockFrames());
        var converted = AudioReaderOptions.of(16_000, ChannelLayout.MONO);
        assertEquals(OptionalInt.of(16_000), converted.sampleRate());
        assertEquals(Optional.of(ChannelLayout.MONO), converted.layout());
        var stereoOnly = audio.toBuilder().layout(ChannelLayout.STEREO).build();
        assertEquals(OptionalInt.empty(), stereoOnly.sampleRate());
        assertEquals(stereoOnly, AudioReaderOptions.builder().layout(ChannelLayout.STEREO).build());
        assertEquals(audio, converted.toBuilder().sourceSampleRate().sourceLayout().build());
        assertThrows(IllegalArgumentException.class, () -> audio.toBuilder().blockFrames(0).build());
        var video = VideoDecoderOptions.builder().threads(2).limits(SMALL).build();
        assertEquals(2, video.threads());
        assertEquals(SMALL, video.limits());
        assertEquals(video, video.toBuilder().build());
    }

    @Test
    @DisplayName("muxing and remuxing options")
    void muxers() {
        var mp4 = Mp4MuxerOptions.defaults();
        assertEquals(Mp4MuxerOptions.mp4(), mp4);
        assertTrue(mp4.fastStart());
        assertEquals(Optional.empty(), mp4.temporaryDirectory());
        var custom = Mp4MuxerOptions.m4a().toBuilder().fastStart(false).temporaryDirectory(Path.of("spool"))
                .interleaveMicros(100_000).build();
        assertEquals(custom, custom.toBuilder().build());
        assertEquals(Mp4MuxerOptions.Brand.M4A, custom.brand());
        assertEquals(Optional.empty(), custom.toBuilder().temporaryDirectory(null).build().temporaryDirectory());
        assertThrows(IllegalArgumentException.class, () -> mp4.toBuilder().interleaveMicros(0).build());
        var remux = RemuxOptions.video();
        assertTrue(remux.includesVideo());
        assertEquals(OptionalInt.empty(), remux.videoTrackId());
        assertFalse(RemuxOptions.audio().includesVideo());
        var picked = remux.toBuilder().videoTrack(2).noAudio().build();
        assertEquals(OptionalInt.of(2), picked.videoTrackId());
        assertFalse(picked.includesAudio());
        assertEquals(picked, RemuxOptions.builder().videoTrack(2).noAudio().build());
        assertEquals(remux, picked.toBuilder().firstVideoTrack().firstAudioTrack().build());
        assertThrows(IllegalArgumentException.class, () -> remux.toBuilder().audioTracks().build());
        // Ogg serial numbers and AVI stream numbers are identifiers too, zero and negative ones included
        assertEquals(OptionalInt.of(0), remux.toBuilder().audioTrack(0).build().audioTrackId());
        var comments = new LinkedHashMap<String, String>();
        comments.put("TITLE", "x");
        var ogg = OggMuxerOptions.builder().vendor("v").comments(comments).serial(7)
                .maxPacketsPerPage(10).maxPageDurationMicros(20_000).build();
        assertEquals(OptionalInt.of(7), ogg.serial());
        assertEquals(Map.of("TITLE", "x"), ogg.comments());
        assertEquals(ogg, ogg.toBuilder().build());
        assertEquals(OptionalInt.empty(), ogg.toBuilder().randomSerial().build().serial());
        assertEquals(OptionalInt.empty(), OggMuxerOptions.defaults().serial());
        assertThrows(IllegalArgumentException.class, () -> ogg.toBuilder().comments(Map.of("A=B", "x")).build());
        assertThrows(IllegalArgumentException.class, () -> ogg.toBuilder().maxPacketsPerPage(256).build());
    }
}
