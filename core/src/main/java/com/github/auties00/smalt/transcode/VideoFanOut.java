package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.List;

/**
 * Decodes one video track once for several {@link VideoEncodeStage}s, the encodes of a job with several outputs.
 *
 * <p>Each packet is first noted by every stage, then decoded, and every frame goes to each stage still running, in
 * order. A packet that changes the codec configuration, and the start of a later file of a concatenation, drain
 * the decoder and continue with a new one. {@link #flush()} drains the decoder at the end of the input, before the
 * stages finish. An instance is not thread-safe.
 */
final class VideoFanOut implements TrackReceiver, AutoCloseable {
    private final VideoTrack source;
    private final VideoDecoderOptions options;
    private final List<VideoEncodeStage> stages;
    private VideoDecoder decoder;

    /**
     * The codec configuration of {@link #decoder}, or {@code null} when it has none.
     */
    private byte[] config;

    /**
     * Constructs a fan-out.
     *
     * @param source the input track
     * @param options the decoder options
     * @param stages the stages, each created without a decoder
     */
    VideoFanOut(VideoTrack source, VideoDecoderOptions options, List<VideoEncodeStage> stages) {
        this.source = source;
        this.options = options;
        this.stages = List.copyOf(stages);
        this.decoder = VideoDecoders.create(source, options);
        this.config = source.codecConfig().orElse(null);
    }

    @Override
    public int sourceTrackId() {
        return source.id();
    }

    @Override
    public void accept(Packet packet) {
        if (done()) {
            return;
        }
        var newConfig = ConfigChanges.changed(packet, config);
        if (newConfig != null) {
            decoder.flush(this::deliver);
            replace(source.toBuilder().codecConfig(newConfig).build(), newConfig);
        }
        for (var stage : stages) {
            stage.notePacket(packet);
        }
        decoder.decode(packet, this::deliver);
    }

    @Override
    public void startSegment(Track track, long startMicros) {
        if (done()) {
            return;
        }
        decoder.flush(this::deliver);
        replace(track, track.codecConfig().orElse(null));
        for (var stage : stages) {
            stage.restartTimestamps(track.codec());
        }
    }

    /**
     * Replaces the decoder.
     *
     * @param track the track the new decoder decodes
     * @param newConfig its codec configuration, or {@code null}
     */
    private void replace(Track track, byte[] newConfig) {
        var replacement = VideoDecoders.create((VideoTrack) track, options);
        decoder.close();
        decoder = replacement;
        config = newConfig;
    }

    /**
     * Hands a decoded frame to every stage still running.
     *
     * @param frame the decoded frame
     */
    private void deliver(VideoFrame frame) {
        for (var stage : stages) {
            if (!stage.done()) {
                stage.acceptFrame(frame);
            }
        }
    }

    /**
     * Drains the decoder into the stages at the end of the input.
     */
    void flush() {
        if (!done()) {
            decoder.flush(this::deliver);
        }
    }

    @Override
    public boolean done() {
        for (var stage : stages) {
            if (!stage.done()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        decoder.close();
    }
}
