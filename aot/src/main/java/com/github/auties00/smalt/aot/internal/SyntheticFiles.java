package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.AacEncoder;
import com.github.auties00.smalt.audio.aac.AacEncoderOptions;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the MP4 files the training workloads read: synthetic video coded by Smalt's H.264 file
 * encoder and synthetic music coded by its AAC encoder, muxed by decoding time.
 */
final class SyntheticFiles {
    /**
     * The sample rate of the audio tracks.
     */
    private static final int RATE = 44100;

    /**
     * The frame rate of the video tracks.
     */
    private static final int FRAME_RATE = 30;

    /**
     * Prevents instantiation.
     */
    private SyntheticFiles() {
        throw new AssertionError();
    }

    /**
     * Builds an MP4 with an H.264 video track at 30 frames per second and a 44.1 kHz stereo AAC
     * track at 128 kbit/s.
     *
     * @param width   the width of the video, a positive even number
     * @param height  the height of the video, a positive even number
     * @param seconds the duration, positive
     * @return the file
     * @throws IllegalArgumentException if an argument is out of range
     */
    static byte[] mp4(int width, int height, double seconds) {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("size must be positive and even: " + width + "x" + height);
        }
        checkSeconds(seconds);
        var encoder = new H264Encoder(width, height, H264EncoderOptions.forFile().toBuilder()
                .timeBase(new Rational(1, FRAME_RATE)).bitrate(1_000_000).build());
        var video = new ArrayList<Packet>();
        var pts = 0L;
        for (var picture : SyntheticVideo.natural(width, height, Math.max(1, (int) Math.round(seconds * FRAME_RATE)))) {
            encoder.encode(new VideoFrame(picture, pts++, 1, false), video::add);
        }
        encoder.flush(video::add);
        var videoTrack = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(encoder.timeBase())
                .codecConfig(encoder.codecConfig().orElseThrow()).width(width).height(height)
                .frameRate(new Rational(FRAME_RATE, 1)).build();
        encoder.close();
        var aac = new AacEncoder(AacEncoderOptions.of(RATE, ChannelLayout.STEREO, 128_000));
        var audio = aac(aac, seconds);
        var audioTrack = AudioTrack.builder().id(2).codec(Codec.AAC).timeBase(new Rational(1, RATE)).sampleRate(RATE)
                .layout(ChannelLayout.STEREO).codecConfig(aac.codecConfig().orElseThrow()).build();
        return mux(List.of(videoTrack, audioTrack), video, audio);
    }

    /**
     * Builds an audio-only MP4 (an M4A file) with a 44.1 kHz stereo AAC track at 128 kbit/s.
     *
     * @param seconds the duration, positive
     * @return the file
     * @throws IllegalArgumentException if {@code seconds} is not positive
     */
    static byte[] m4a(double seconds) {
        checkSeconds(seconds);
        var aac = new AacEncoder(AacEncoderOptions.of(RATE, ChannelLayout.STEREO, 128_000));
        var audio = aac(aac, seconds);
        var audioTrack = AudioTrack.builder().id(2).codec(Codec.AAC).timeBase(new Rational(1, RATE)).sampleRate(RATE)
                .layout(ChannelLayout.STEREO).codecConfig(aac.codecConfig().orElseThrow()).build();
        return mux(List.of(audioTrack), List.of(), audio);
    }

    /**
     * Validates a duration.
     *
     * @param seconds the duration
     * @throws IllegalArgumentException if it is not positive
     */
    private static void checkSeconds(double seconds) {
        if (!(seconds > 0)) {
            throw new IllegalArgumentException("seconds must be positive: " + seconds);
        }
    }

    /**
     * Encodes synthetic music with an AAC encoder.
     *
     * @param aac     the encoder
     * @param seconds the duration
     * @return the packets, the encoder flushed
     */
    private static List<Packet> aac(AacEncoder aac, double seconds) {
        var audio = new ArrayList<Packet>();
        var block = new AudioBuffer(ChannelLayout.STEREO, RATE, AudioReaderOptions.DEFAULT_BLOCK_FRAMES);
        SyntheticAudio.feed(SyntheticAudio.music(RATE, seconds), block, buffer -> aac.encode(buffer, audio::add));
        aac.flush(audio::add);
        return audio;
    }

    /**
     * Muxes a video and an audio track into an MP4, interleaving their packets by decoding time.
     *
     * @param tracks the tracks: the video track first when there is one, then the audio track
     * @param video  the packets of track 1, or none
     * @param audio  the packets of track 2
     * @return the file
     */
    private static byte[] mux(List<? extends Track> tracks, List<Packet> video, List<Packet> audio) {
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.defaults())) {
            for (var track : tracks) {
                muxer.addTrack(track);
            }
            var v = 0;
            var a = 0;
            while (v < video.size() || a < audio.size()) {
                var takeVideo = a >= audio.size()
                        || (v < video.size() && video.get(v).dts() * RATE <= audio.get(a).dts() * (long) FRAME_RATE);
                if (takeVideo) {
                    muxer.writePacket(video.get(v++).withTrackId(1));
                } else {
                    muxer.writePacket(audio.get(a++).withTrackId(2));
                }
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }
}
