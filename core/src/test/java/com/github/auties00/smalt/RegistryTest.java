package com.github.auties00.smalt;

import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the registries with only the core artifact present: what core handles itself (PCM,
 * every container), and the failures for everything else, which name the
 * artifact to add.
 */
@DisplayName("Registries without codec artifacts")
class RegistryTest {
    private static void assertMissing(String format, String artifact, Executable action) {
        var failure = assertThrows(MediaException.Unsupported.class, action);
        assertEquals(format, failure.format());
        assertEquals(Optional.ofNullable(artifact), failure.artifact());
        if (artifact != null) {
            assertTrue(failure.getMessage().contains(artifact), failure.getMessage());
        }
    }

    @Test
    @DisplayName("decode PCM and name smalt-mpeg for AAC, H.264 and HEVC")
    void decoders() {
        assertTrue(AudioDecoders.isSupported(Codec.PCM_S16LE));
        assertFalse(AudioDecoders.isSupported(Codec.AAC));
        assertFalse(VideoDecoders.isSupported(Codec.H264));
        assertFalse(VideoDecoders.isSupported(Codec.HEVC));
        var aac = Fixtures.read(Fixtures.tone("m4a", 440, 48_000, 1, 0.2, List.of("-c:a", "aac")));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(aac))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertMissing("aac", "smalt-mpeg", () -> AudioDecoders.create(track));
        }
        var h264 = Fixtures.read(Fixtures.testVideo("mp4", 64, 48, 0.2, List.of("-c:v", "libx264", "-an")));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(h264))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertMissing("h264", "smalt-mpeg", () -> VideoDecoders.create(track));
        }
        var hevc = Fixtures.read(Fixtures.testVideo("mp4", 64, 48, 0.2, List.of("-c:v", "libx265", "-x265-params", "log-level=error", "-an")));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(hevc))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertMissing("hevc", "smalt-mpeg", () -> VideoDecoders.create(track));
        }
    }

    @Test
    @DisplayName("name the artifact of each missing encoder and reject codecs of the wrong kind")
    void encoders() {
        assertFalse(AudioEncoders.isSupported(Codec.OPUS));
        assertMissing("opus", "smalt-audio", () -> AudioEncoders.create(Codec.OPUS));
        assertMissing("aac", "smalt-mpeg", () -> AudioEncoders.create(Codec.AAC));
        assertMissing("vorbis", "smalt-audio", () -> AudioEncoders.create(Codec.VORBIS));
        assertMissing("mp3", "smalt-audio", () -> AudioEncoders.create(Codec.MP3));
        assertMissing("alac", null, () -> AudioEncoders.create(Codec.ALAC));
        assertThrows(IllegalArgumentException.class, () -> AudioEncoders.create(Codec.H264));
        var options = VideoEncoderOptions.of(64, 48, new Rational(30, 1), 100_000);
        assertFalse(VideoEncoders.isSupported(Codec.VP9));
        assertMissing("vp9", "smalt-video", () -> VideoEncoders.create(Codec.VP9, options));
        assertMissing("vp8", "smalt-image", () -> VideoEncoders.create(Codec.VP8, options));
        assertMissing("h264", "smalt-mpeg", () -> VideoEncoders.create(Codec.H264, options));
        assertMissing("av1", "smalt-video", () -> VideoEncoders.create(Codec.AV1, options));
        assertThrows(IllegalArgumentException.class, () -> VideoEncoders.create(Codec.OPUS, options));
    }

    @Test
    @DisplayName("name smalt-image, smalt-video or smalt-mpeg for images")
    void imagesAndContainers() {
        assertFalse(Images.isDecodable(ImageFormat.JPEG));
        assertFalse(Images.isEncodable(ImageFormat.PNG));
        var picture = RgbaPicture.allocate(2, 2, false);
        assertMissing("png", "smalt-image", () -> Images.encode(picture, ImageFormat.PNG));
        assertMissing("heic", null, () -> Images.encode(picture, ImageFormat.HEIC));
        assertFalse(Images.isDecodable(ImageFormat.AVIF));
        var avif = com.github.auties00.smalt.testkit.HeifBuilder.box("ftyp", "avif\0\0\0\0avifmif1".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        assertMissing("avif", "smalt-video", () -> Images.decode(MediaSource.of(avif)));
        assertFalse(Images.isDecodable(ImageFormat.HEIC));
        var heic = com.github.auties00.smalt.testkit.HeifBuilder.box("ftyp", "heic\0\0\0\0heicmif1".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        assertMissing("heic", "smalt-mpeg", () -> Images.decode(MediaSource.of(heic)));
        var png = Fixtures.read(Fixtures.testImage("png", 8, 8, List.of()));
        assertMissing("png", "smalt-image", () -> Images.decode(MediaSource.of(png)));
        assertFalse(Images.isAnimationEncodable(ImageFormat.WEBP));
        assertMissing("webp", "smalt-image", () -> Images.openAnimationWriter(ImageFormat.WEBP, 2, 2, MediaSink.memory()));
        assertMissing("gif", "smalt-image", () -> Images.openAnimationWriter(ImageFormat.GIF, 2, 2, MediaSink.memory()));
        assertMissing("bmp", null, () -> Images.openAnimationWriter(ImageFormat.BMP, 2, 2, MediaSink.memory()));
        var mjpeg = VideoTrack.builder().codec(Codec.MJPEG).timeBase(new Rational(1, 1000)).width(2).height(2).build();
        assertMissing("mjpeg", "smalt-image", () -> VideoDecoders.create(mjpeg));
    }
}
