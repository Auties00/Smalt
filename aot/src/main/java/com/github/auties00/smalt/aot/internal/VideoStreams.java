package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs the video encoders and decoders of the training workloads on synthetic 640x360 video at
 * 30 frames per second: an encoder that keeps its state across rounds as a long-running stream
 * does, a decoder that decodes the same stream every round, and a real-time session of an encoder
 * feeding a decoder frame by frame.
 */
final class VideoStreams {
    /**
     * The width of the coded video.
     */
    static final int WIDTH = 640;

    /**
     * The height of the coded video.
     */
    static final int HEIGHT = 360;

    /**
     * The time base of the coded video, one tick per frame.
     */
    static final Rational TIME_BASE = new Rational(1, 30);

    /**
     * The frames an encoding workload codes per round.
     */
    static final int ENCODED_FRAMES = 24;

    /**
     * The frames of the streams the decoding workloads decode.
     */
    static final int DECODED_FRAMES = 60;

    /**
     * The frames of each content clip of a real-time session.
     */
    private static final int SESSION_FRAMES = 30;

    /**
     * Prevents instantiation.
     */
    private VideoStreams() {
        throw new AssertionError();
    }

    /**
     * Returns a workload that encodes the same synthetic frames with an encoder every round, the
     * timestamps going on across rounds.
     *
     * @param name the name of the workload
     * @param encoder the encoder, of {@link #WIDTH}x{@link #HEIGHT} pictures
     * @return the workload
     */
    static TrainingWorkload encode(String name, VideoEncoder encoder) {
        var pictures = SyntheticVideo.natural(WIDTH, HEIGHT, ENCODED_FRAMES);
        long[] pts = {0};
        Consumer<Packet> output = packet -> Blackhole.consume(packet.size());
        return TrainingWorkload.of(name, () -> {
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts[0]++, 1, false), output);
            }
        });
    }

    /**
     * Encodes pictures, flushes and closes the encoder, and returns the packets.
     *
     * @param encoder the encoder, flushed and closed after the last picture
     * @param pictures the pictures, in presentation order
     * @return the packets, in decoding order
     */
    static List<Packet> encodeAll(VideoEncoder encoder, List<? extends Picture> pictures) {
        var packets = new ArrayList<Packet>();
        var pts = 0L;
        for (var picture : pictures) {
            encoder.encode(new VideoFrame(picture, pts++, 1, false), packets::add);
        }
        encoder.flush(packets::add);
        encoder.close();
        return packets;
    }

    /**
     * Returns a workload that decodes the same packets every round with one decoder, the
     * timestamps going on across rounds, and drains the decoder at the end of every round.
     *
     * @param name the name of the workload
     * @param packets the packets, in decoding order
     * @param decoder the decoder, reused every round
     * @return the workload
     */
    static TrainingWorkload decode(String name, List<Packet> packets, VideoDecoder decoder) {
        return TrainingWorkload.of(name, decodeRound(packets, decoder));
    }

    /**
     * Returns the round of a workload that decodes the same packets every round with one decoder,
     * the timestamps going on across rounds, and drains the decoder at the end of every round.
     *
     * @param packets the packets, in decoding order
     * @param decoder the decoder, reused every round
     * @return the round
     */
    static Runnable decodeRound(List<Packet> packets, VideoDecoder decoder) {
        var span = 1 + packets.stream().mapToLong(packet -> Math.max(packet.pts(), packet.dts())).max().orElse(0);
        long[] offset = {0};
        Consumer<VideoFrame> output = VideoStreams::consume;
        return () -> {
            for (var packet : packets) {
                decoder.decode(packet.withTrackId(1).withTiming(shift(packet.pts(), offset[0]),
                        shift(packet.dts(), offset[0]), packet.duration()), output);
            }
            decoder.flush(output);
            offset[0] += span;
        };
    }

    /**
     * Shifts a timestamp, leaving {@link Packet#NO_TIMESTAMP} unknown.
     *
     * @param timestamp the timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param offset the shift
     * @return the shifted timestamp
     */
    private static long shift(long timestamp, long offset) {
        return timestamp == Packet.NO_TIMESTAMP ? timestamp : timestamp + offset;
    }

    /**
     * Returns a workload that runs a real-time session: an encoder whose packets a decoder decodes
     * as soon as each frame is encoded.
     *
     * <p>A cache's compiled code runs from the first frame of every encoder and decoder, so the
     * workload profiles their whole life. Every round creates a new encoder and decoder and codes
     * one clip of a content class, the class rotating from round to round: the first frames (key
     * frame, rate control start-up, empty references) of a new session. A second encoder and
     * decoder code every round's clip too and live across rounds: the long run (frame number wrap,
     * full reference lists, converged rate control) of a session that goes on.
     *
     * @param name the name of the workload
     * @param codec the codec of the encoders
     * @param encoders creates an encoder of {@link #WIDTH}x{@link #HEIGHT} pictures
     * @param decoders creates the decoder of a track that carries the encoder's configuration
     * @return the workload
     */
    static TrainingWorkload session(String name, Codec codec, Supplier<VideoEncoder> encoders,
                                    Function<VideoTrack, VideoDecoder> decoders) {
        var clips = new ArrayList<List<YuvPicture>>(SyntheticVideo.CONTENT_CLASSES);
        for (var kind = 0; kind < SyntheticVideo.CONTENT_CLASSES; kind++) {
            clips.add(SyntheticVideo.content(kind, WIDTH, HEIGHT, SESSION_FRAMES));
        }
        var lasting = new Session(codec, encoders, decoders);
        int[] round = {0};
        return TrainingWorkload.of(name, () -> {
            var pictures = clips.get(round[0]++ % clips.size());
            lasting.code(pictures);
            var fresh = new Session(codec, encoders, decoders);
            try {
                fresh.code(pictures);
            } finally {
                fresh.close();
            }
        });
    }

    /**
     * Returns the track of a stream of {@link #WIDTH}x{@link #HEIGHT} video.
     *
     * @param codec the coding format of the track
     * @param codecConfig the decoder configuration, or {@code null} for none
     * @return the track
     */
    static VideoTrack track(Codec codec, byte[] codecConfig) {
        return track(codec, WIDTH, HEIGHT, codecConfig);
    }

    /**
     * Returns the track of a stream of video of any size.
     *
     * @param codec the coding format of the track
     * @param width the width of the video
     * @param height the height of the video
     * @param codecConfig the decoder configuration, or {@code null} for none
     * @return the track
     */
    static VideoTrack track(Codec codec, int width, int height, byte[] codecConfig) {
        return VideoTrack.builder().id(1).codec(codec).timeBase(TIME_BASE).width(width).height(height)
                .codecConfig(codecConfig).build();
    }

    /**
     * Consumes a decoded frame.
     *
     * @param frame the decoded frame, one of whose samples is read
     */
    static void consume(VideoFrame frame) {
        Blackhole.consume(switch (frame.picture()) {
            case YuvPicture picture -> picture.y()[17];
            case Yuv16Picture picture -> picture.y()[17];
            case Picture picture -> picture.width();
        });
    }

    /**
     * An encoder whose packets a decoder decodes as soon as each frame is encoded.
     */
    private static final class Session {
        /**
         * The codec of the session.
         */
        private final Codec codec;

        /**
         * The encoder.
         */
        private final VideoEncoder encoder;

        /**
         * Creates the decoder once the encoder's configuration is known.
         */
        private final Function<VideoTrack, VideoDecoder> decoders;

        /**
         * The packets of the frame being coded.
         */
        private final List<Packet> packets = new ArrayList<>();

        /**
         * The decoder, created after the first frame is encoded.
         */
        private VideoDecoder decoder;

        /**
         * The timestamp of the next frame.
         */
        private long pts;

        /**
         * Constructs the encoder.
         *
         * @param codec the codec of the encoder
         * @param encoders creates the encoder
         * @param decoders creates the decoder of a track
         */
        Session(Codec codec, Supplier<VideoEncoder> encoders, Function<VideoTrack, VideoDecoder> decoders) {
            this.codec = codec;
            this.encoder = encoders.get();
            this.decoders = decoders;
        }

        /**
         * Encodes pictures one at a time and decodes the packets of each before the next.
         *
         * @param pictures the pictures of one round, in presentation order
         */
        void code(List<YuvPicture> pictures) {
            for (var picture : pictures) {
                packets.clear();
                encoder.encode(new VideoFrame(picture, pts++, 1, false), packets::add);
                if (decoder == null) {
                    decoder = decoders.apply(track(codec, encoder.codecConfig().orElse(null)));
                }
                for (var packet : packets) {
                    decoder.decode(packet, VideoStreams::consume);
                }
            }
        }

        /**
         * Releases the encoder and the decoder.
         */
        void close() {
            encoder.close();
            if (decoder != null) {
                decoder.close();
            }
        }
    }
}
