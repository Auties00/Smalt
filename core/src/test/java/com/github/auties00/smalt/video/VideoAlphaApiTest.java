package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the public surface of transparent video: the alpha frame of {@link PacketSideData}, the alpha flag of
 * {@link VideoTrack}, the alpha settings of the encoder, writer and transcode options, and the rejection of alpha
 * by codecs and files that cannot carry it.
 */
@DisplayName("transparent video API")
class VideoAlphaApiTest {
    @Test
    @DisplayName("the alpha frame is a typed side data entry with value equality")
    void sideData() {
        var alpha = new byte[]{1, 2, 3};
        var side = PacketSideData.builder().alphaFrame(alpha).build();
        assertArrayEquals(alpha, side.alphaFrame().orElseThrow());
        assertFalse(side.isEmpty());
        assertTrue(side.extra().isEmpty());
        assertEquals(side, PacketSideData.builder().alphaFrame(new byte[]{1, 2, 3}).build());
        assertEquals(side.hashCode(), PacketSideData.builder().alphaFrame(new byte[]{1, 2, 3}).build().hashCode());
        assertNotEquals(side, PacketSideData.builder().alphaFrame(new byte[]{1, 2}).build());
        assertTrue(side.toString().contains("alphaFrame=3 bytes"), side.toString());
        assertEquals(side, side.toBuilder().build());
        assertTrue(side.toBuilder().alphaFrame(null).build().isEmpty());
        assertEquals(PacketSideData.NONE, side.toBuilder().alphaFrame(null).build());
    }

    @Test
    @DisplayName("a track declares alpha through its builder, which equality and toBuilder keep")
    void track() {
        var track = VideoTrack.builder().codec(Codec.VP9).timeBase(new Rational(1, 1000)).build();
        assertFalse(track.hasAlpha());
        var alpha = track.toBuilder().alpha(true).build();
        assertTrue(alpha.hasAlpha());
        assertTrue(alpha.toBuilder().build().hasAlpha());
        assertNotEquals(track, alpha);
        assertEquals(alpha, alpha.toBuilder().build());
        assertTrue(alpha.toString().contains("alpha=true"), alpha.toString());
    }

    @Test
    @DisplayName("the encoder and writer options carry the alpha setting")
    void options() {
        var encoder = VideoEncoderOptions.of(16, 16, new Rational(25, 1), 100_000);
        assertFalse(encoder.alpha());
        var alpha = encoder.toBuilder().alpha(true).build();
        assertTrue(alpha.alpha());
        assertNotEquals(encoder, alpha);
        assertTrue(alpha.toBuilder().build().alpha());
        assertFalse(VideoWriterOptions.defaults().alpha());
        var writer = VideoWriterOptions.builder().alpha(true).build();
        assertTrue(writer.alpha());
        assertTrue(writer.toBuilder().build().alpha());
        assertNotEquals(VideoWriterOptions.defaults(), writer);
        assertFalse(VideoTranscodeOptions.defaults().flatten());
        var flatten = VideoTranscodeOptions.builder().flatten(true).build();
        assertTrue(flatten.flatten());
        assertTrue(flatten.toBuilder().build().flatten());
        assertNotEquals(VideoTranscodeOptions.defaults(), flatten);
    }

    @Test
    @DisplayName("a codec without an alpha stream rejects alpha, and only Matroska and WebM store it")
    void rejections() {
        assertTrue(VideoEncoders.isSupported(Codec.RAW_VIDEO));
        assertFalse(VideoEncoders.encodesAlpha(Codec.RAW_VIDEO));
        var options = VideoEncoderOptions.of(16, 16, new Rational(25, 1), 100_000).toBuilder().alpha(true).build();
        var unsupported = assertThrows(MediaException.Unsupported.class, () -> VideoEncoders.create(Codec.RAW_VIDEO, options));
        assertTrue(unsupported.getMessage().contains("alpha"), unsupported.getMessage());
        VideoEncoders.create(Codec.RAW_VIDEO, options.toBuilder().alpha(false).build()).close();
        for (var container : OutputContainer.values()) {
            assertEquals(container == OutputContainer.MATROSKA || container == OutputContainer.WEBM, container.storesAlpha(),
                    container.toString());
        }
        var writerOptions = VideoWriterOptions.builder().alpha(true).build();
        assertThrows(IllegalArgumentException.class, () -> VideoWriter.open(MediaSink.memory(), OutputContainer.MP4,
                writerOptions));
        try (var muxer = new MatroskaMuxer(MediaSink.memory())) {
            assertTrue(muxer.supportsAlpha());
            assertThrows(MediaException.Unsupported.class, () -> VideoWriter.open(muxer,
                    writerOptions.toBuilder().codec(Codec.RAW_VIDEO).build()));
        }
        for (var container : OutputContainer.values()) {
            com.github.auties00.smalt.container.Muxer muxer;
            try {
                muxer = com.github.auties00.smalt.container.Containers.openMuxer(container, MediaSink.memory());
            } catch (MediaException.Unsupported exception) {
                continue;
            }
            try (muxer) {
                assertEquals(container.storesAlpha(), muxer.supportsAlpha(), container.toString());
            }
        }
        try (var muxer = new com.github.auties00.smalt.container.mp4.Mp4Muxer(MediaSink.memory())) {
            var rejected = assertThrows(IllegalArgumentException.class, () -> VideoWriter.open(muxer,
                    writerOptions.toBuilder().codec(Codec.RAW_VIDEO).build()));
            assertTrue(rejected.getMessage().contains("alpha"), rejected.getMessage());
        }
    }
}
