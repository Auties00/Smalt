package com.github.auties00.smalt.property;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

/**
 * Deterministic synthetic content and the packets Smalt's encoders, or ffmpeg for the codecs Smalt does not
 * encode, make of it: the inputs of the property tests.
 */
public final class Streams {
    /** The frame rate of every synthetic video: 25 frames per second. */
    public static final Rational FRAME_RATE = new Rational(25, 1);

    private Streams() {
        throw new AssertionError();
    }

    /**
     * A track and its packets in decoding order.
     *
     * @param track the track, with the configuration the decoder needs
     * @param packets the packets
     */
    public record Encoded(Track track, List<Packet> packets) {
    }

    /**
     * Returns a 4:2:0 picture of moving gradients and seeded noise: every frame differs from the previous one, and
     * the noise keeps the encoders from skipping blocks.
     *
     * @param width the width, even
     * @param height the height, even
     * @param frame the frame index
     * @param seed the noise seed
     * @return the picture
     */
    public static YuvPicture picture(int width, int height, int frame, long seed) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        var random = new Random(seed * 1_000_003L + frame);
        var luma = picture.y();
        var lumaStride = picture.yStride();
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                var value = (column * 3 + row * 2 + frame * 5) % 220 + 16 + random.nextInt(9) - 4;
                luma[row * lumaStride + column] = (byte) Math.clamp(value, 0, 255);
            }
        }
        var chromaWidth = width / 2;
        var chromaHeight = height / 2;
        for (var row = 0; row < chromaHeight; row++) {
            for (var column = 0; column < chromaWidth; column++) {
                picture.u()[row * picture.uvStride() + column] = (byte) (128 + (column + frame) % 40 - 20);
                picture.v()[row * picture.uvStride() + column] = (byte) (128 + (row * 2 - frame) % 30);
            }
        }
        return picture;
    }

    /**
     * Encodes synthetic frames with Smalt's encoder of a codec.
     *
     * @param codec the codec, one {@link VideoEncoders#isSupported} accepts
     * @param width the width
     * @param height the height
     * @param frames the frame count
     * @param options adjusts the encoder options (size, frame rate, a bitrate and a key frame every 10 frames are
     * set)
     * @return the track (id 1) and its packets
     */
    public static Encoded video(Codec codec, int width, int height, int frames,
                                UnaryOperator<VideoEncoderOptions.Builder> options) {
        var builder = VideoEncoderOptions.builder().size(width, height).frameRate(FRAME_RATE).keyframeInterval(10)
                .bitrate(Math.max(200_000L, 4L * width * height));
        var packets = new ArrayList<Packet>();
        try (var encoder = VideoEncoders.create(codec, options.apply(builder).build())) {
            var duration = encoder.timeBase().denominator() / (encoder.timeBase().numerator() * 25L);
            for (var frame = 0; frame < frames; frame++) {
                var picture = picture(width, height, frame, 7);
                encoder.encode(new VideoFrame(picture, frame * duration, duration, frame == 0), packets::add);
            }
            encoder.flush(packets::add);
            var track = VideoTrack.builder()
                    .id(1)
                    .codec(codec)
                    .timeBase(encoder.timeBase())
                    .width(encoder.width())
                    .height(encoder.height())
                    .frameRate(FRAME_RATE);
            encoder.codecConfig().ifPresent(track::codecConfig);
            return new Encoded(track.build(), packets.stream().map(packet -> packet.withTrackId(1)).toList());
        }
    }

    /**
     * Returns planar float audio of seeded sine sweeps and noise, quantized to a grid of {@code bits} bits so that
     * a lossless integer codec of that depth represents every sample exactly.
     *
     * @param layout the channel layout
     * @param sampleRate the sample rate
     * @param frames the samples per channel
     * @param bits the quantization depth, from 8 to 24, or 0 for unquantized
     * @param seed the seed
     * @return the planes
     */
    public static float[][] samples(ChannelLayout layout, int sampleRate, int frames, int bits, long seed) {
        var random = new Random(seed);
        var planes = new float[layout.channels()][frames];
        var scale = bits == 0 ? 0 : 1 << (bits - 1);
        for (var channel = 0; channel < planes.length; channel++) {
            var frequency = 180.0 + 97 * channel;
            for (var index = 0; index < frames; index++) {
                var time = (double) index / sampleRate;
                var value = 0.45 * Math.sin(2 * Math.PI * (frequency + 60 * time) * time) + 0.05 * random.nextGaussian();
                value = Math.clamp(value, -1, 1);
                if (scale != 0) {
                    value = Math.clamp(Math.round(value * scale), -scale, scale - 1) / (double) scale;
                }
                planes[channel][index] = (float) value;
            }
        }
        return planes;
    }

    /**
     * Encodes synthetic audio with Smalt's encoder of a codec, in blocks of the encoder's frame size.
     *
     * @param codec the codec, one {@link AudioEncoders#isSupported} accepts
     * @param layout the layout
     * @param sampleRate the sample rate
     * @param frames the samples per channel
     * @param options adjusts the encoder options (format set)
     * @return the track (id 2) and its packets
     */
    public static Encoded audio(Codec codec, ChannelLayout layout, int sampleRate, int frames,
                                UnaryOperator<AudioEncoderOptions.Builder> options) {
        var planes = samples(layout, sampleRate, frames, 16, 11);
        var packets = new ArrayList<Packet>();
        var builder = AudioEncoderOptions.builder().format(sampleRate, layout);
        try (var encoder = AudioEncoders.create(codec, options.apply(builder).build())) {
            var block = encoder.frameSize() > 0 ? encoder.frameSize() : 1024;
            for (var start = 0; start < frames; start += block) {
                var count = Math.min(block, frames - start);
                var chunk = new float[planes.length][count];
                for (var channel = 0; channel < planes.length; channel++) {
                    System.arraycopy(planes[channel], start, chunk[channel], 0, count);
                }
                encoder.encode(new AudioBuffer(encoder.layout(), encoder.sampleRate(), chunk, count), packets::add);
            }
            encoder.flush(packets::add);
            var track = AudioTrack.builder()
                    .id(2)
                    .codec(codec)
                    .timeBase(new Rational(1, encoder.sampleRate()))
                    .sampleRate(encoder.sampleRate())
                    .layout(encoder.layout());
            encoder.codecConfig().ifPresent(track::codecConfig);
            return new Encoded(track.build(), packets.stream().map(packet -> packet.withTrackId(2)).toList());
        }
    }

    /**
     * Demuxes the first track of a codec from a file, for the codecs whose packets come from ffmpeg.
     *
     * @param file the file
     * @param codec the codec of the track to take
     * @param id the id to give the track and its packets
     * @return the track and its packets
     */
    public static Encoded demuxed(Path file, Codec codec, int id) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = demuxer.tracks().stream().filter(candidate -> candidate.codec() == codec).findFirst()
                    .orElseThrow(() -> new AssertionError(file + " has no " + codec + " track"));
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet.withTrackId(id));
                }
            }
            var renumbered = switch (track) {
                case VideoTrack video -> video.toBuilder().id(id).build();
                case AudioTrack audio -> audio.toBuilder().id(id).build();
                default -> track;
            };
            return new Encoded(renumbered, packets);
        }
    }
}
