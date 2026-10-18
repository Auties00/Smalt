package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the names added next to the existing video API: {@link VideoEncoderOptions#builder()},
 * the default runtime controls of {@link VideoEncoder}, and
 * {@link VideoReader#open(MediaSource, VideoDecoderOptions)}.
 */
@DisplayName("Video API additions")
class VideoApiAdditionsTest {
    private static final Rational FPS_30 = new Rational(30, 1);

    @Nested
    @DisplayName("VideoEncoderOptions.builder")
    class Builder {
        @Test
        @DisplayName("builds the same options as the presets")
        void matchesPresets() {
            var built = VideoEncoderOptions.builder().size(640, 360).frameRate(FPS_30).bitrate(800_000).build();
            assertEquals(VideoEncoderOptions.of(640, 360, FPS_30, 800_000), built);
            var realtime = VideoEncoderOptions.builder()
                    .size(320, 240)
                    .frameRate(FPS_30)
                    .bitrate(300_000)
                    .realtime(true)
                    .threads(2)
                    .build();
            assertEquals(VideoEncoderOptions.forRealtime(320, 240, FPS_30, 300_000).toBuilder().threads(2).build(),
                    realtime);
            assertEquals(0, VideoEncoderOptions.builder().size(2, 2).frameRate(FPS_30).bitrate(1).build().threads());
        }

        @Test
        @DisplayName("requires the size, frame rate and bitrate")
        void requiresSettings() {
            assertThrows(IllegalStateException.class, () -> VideoEncoderOptions.builder().build());
            assertThrows(IllegalStateException.class,
                    () -> VideoEncoderOptions.builder().frameRate(FPS_30).bitrate(1).build());
            assertThrows(IllegalStateException.class,
                    () -> VideoEncoderOptions.builder().size(2, 2).bitrate(1).build());
            assertThrows(IllegalStateException.class,
                    () -> VideoEncoderOptions.builder().size(2, 2).frameRate(FPS_30).build());
        }

        @Test
        @DisplayName("validates explicitly set values as before")
        void validates() {
            assertThrows(NullPointerException.class,
                    () -> VideoEncoderOptions.builder().size(2, 2).frameRate(null).bitrate(1).build());
            assertThrows(IllegalArgumentException.class,
                    () -> VideoEncoderOptions.builder().size(0, 2).frameRate(FPS_30).bitrate(1).build());
            assertThrows(IllegalArgumentException.class,
                    () -> VideoEncoderOptions.builder().size(2, 2).frameRate(FPS_30).bitrate(0).build());
            var options = VideoEncoderOptions.of(2, 2, FPS_30, 1);
            assertEquals(options, options.toBuilder().build());
            assertThrows(NullPointerException.class, () -> options.toBuilder().frameRate(null).build());
        }
    }

    @Nested
    @DisplayName("VideoEncoder defaults")
    class EncoderDefaults {
        private static final class MinimalEncoder implements VideoEncoder {
            @Override
            public Codec codec() {
                return Codec.VP8;
            }

            @Override
            public int width() {
                return 16;
            }

            @Override
            public int height() {
                return 16;
            }

            @Override
            public Rational timeBase() {
                return new Rational(1, 1000);
            }

            @Override
            public Optional<byte[]> codecConfig() {
                return Optional.empty();
            }

            @Override
            public void encode(VideoFrame frame, Consumer<Packet> output) {
            }

            @Override
            public void flush(Consumer<Packet> output) {
            }
        }

        @Test
        @DisplayName("requestKeyframe and setTargetBitrate throw Unsupported unless overridden")
        void unsupported() {
            try (var encoder = new MinimalEncoder()) {
                var keyframe = assertThrows(MediaException.Unsupported.class, encoder::requestKeyframe);
                assertEquals("keyframe request", keyframe.format());
                var bitrate = assertThrows(MediaException.Unsupported.class, () -> encoder.setTargetBitrate(500_000));
                assertEquals("bitrate change", bitrate.format());
            }
        }

        @Test
        @DisplayName("an override replaces the default")
        void overridden() {
            var requests = new int[1];
            var encoder = new VideoEncoder() {
                private final MinimalEncoder delegate = new MinimalEncoder();

                @Override
                public Codec codec() {
                    return delegate.codec();
                }

                @Override
                public int width() {
                    return delegate.width();
                }

                @Override
                public int height() {
                    return delegate.height();
                }

                @Override
                public Rational timeBase() {
                    return delegate.timeBase();
                }

                @Override
                public Optional<byte[]> codecConfig() {
                    return delegate.codecConfig();
                }

                @Override
                public void encode(VideoFrame frame, Consumer<Packet> output) {
                }

                @Override
                public void flush(Consumer<Packet> output) {
                }

                @Override
                public void requestKeyframe() {
                    requests[0]++;
                }
            };
            assertDoesNotThrow(encoder::requestKeyframe);
            assertEquals(1, requests[0]);
        }
    }

    @Nested
    @DisplayName("VideoReader.open(MediaSource, VideoDecoderOptions)")
    class Reader {
        private static byte[] silentWav() {
            var frames = 480;
            var buffer = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * 2)
                    .put("WAVE".getBytes(StandardCharsets.US_ASCII));
            buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                    .putInt(48_000).putInt(96_000).putShort((short) 2).putShort((short) 16);
            buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 2);
            return buffer.array();
        }

        @Test
        @DisplayName("reports a file without video as no video")
        void noVideo() {
            var withDefaults = assertThrows(MediaException.Unsupported.class,
                    () -> VideoReader.open(MediaSource.of(silentWav())));
            var withDecoderOptions = assertThrows(MediaException.Unsupported.class,
                    () -> VideoReader.open(MediaSource.of(silentWav()), VideoDecoderOptions.defaults()));
            assertEquals("no video", withDecoderOptions.format());
            assertEquals(withDefaults.format(), withDecoderOptions.format());
        }

        @Test
        @DisplayName("rejects null decoder options")
        void nullOptions() {
            assertThrows(NullPointerException.class,
                    () -> VideoReader.open(MediaSource.of(silentWav()), (VideoDecoderOptions) null));
        }
    }
}
