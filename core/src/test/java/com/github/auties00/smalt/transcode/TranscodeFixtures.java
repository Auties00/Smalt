package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.pcm.PcmEncoder;
import com.github.auties00.smalt.audio.pcm.PcmEncoderOptions;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.container.y4m.Y4mMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.RawPixelFormat;
import com.github.auties00.smalt.video.RawVideoEncoder;
import com.github.auties00.smalt.video.RawVideoFormat;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Builds the synthetic inputs of the transcode tests with this module's codecs (PCM and uncompressed video), and
 * decodes outputs back to samples and pictures.
 */
final class TranscodeFixtures {
    private TranscodeFixtures() {
    }

    /**
     * A track and its packets in decoding order.
     */
    record Stream(Track track, List<Packet> packets) {
    }

    /**
     * A decoded picture and its presentation time.
     */
    record Frame(long micros, YuvPicture picture) {
    }

    /**
     * Returns a sine of a frequency, at half of full scale.
     */
    static float[] sine(double frequency, int rate, int frames) {
        var samples = new float[frames];
        for (var index = 0; index < frames; index++) {
            samples[index] = (float) (0.5 * Math.sin(2 * Math.PI * frequency * index / rate));
        }
        return samples;
    }

    /**
     * Encodes planes as a PCM track.
     */
    static Stream pcm(int id, Codec codec, int rate, float[][] planes) {
        var layout = ChannelLayout.defaultFor(planes.length);
        var encoder = new PcmEncoder(PcmEncoderOptions.of(codec, rate, layout).toBuilder().framesPerPacket(1024).build());
        var packets = new ArrayList<Packet>();
        var frames = planes[0].length;
        encoder.encode(new AudioBuffer(layout, rate, planes, frames), packets::add);
        encoder.flush(packets::add);
        var track = AudioTrack.builder()
                .id(id)
                .codec(codec)
                .timeBase(Rational.timeBaseOf(rate))
                .duration(frames)
                .sampleRate(rate)
                .layout(layout)
                .bitsPerSample(encoder.bitsPerSample())
                .skipSamples(0)
                .build();
        var renumbered = new ArrayList<Packet>();
        for (var packet : packets) {
            renumbered.add(packet.withTrackId(id));
        }
        return new Stream(track, renumbered);
    }

    /**
     * Returns an I420 picture: a vertical gradient offset by {@code index}, with a bright bar that moves.
     */
    static YuvPicture gradient(int width, int height, int index) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var bar = x >= (3 * index) % width && x < (3 * index) % width + 4;
                picture.y()[y * picture.yStride() + x] = (byte) (bar ? 220 : 40 + y + index % 64);
            }
        }
        for (var position = 0; position < picture.u().length; position++) {
            picture.u()[position] = (byte) (100 + index % 50);
            picture.v()[position] = (byte) (160 - index % 50);
        }
        return picture;
    }

    /**
     * Encodes pictures as an uncompressed I420 track, one frame per tick of the rate.
     */
    static Stream raw(int id, int width, int height, Rational rate, int frames, IntFunction<YuvPicture> pictures) {
        var encoder = new RawVideoEncoder(RawVideoFormat.of(RawPixelFormat.YUV420P), width, height, rate,
                ColorInfo.BT709_LIMITED);
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < frames; index++) {
            encoder.encode(new VideoFrame(pictures.apply(index), index, 1, true), packet -> packets.add(packet.withTrackId(id)));
        }
        encoder.flush(packet -> packets.add(packet.withTrackId(id)));
        var track = VideoTrack.builder()
                .id(id)
                .codec(Codec.RAW_VIDEO)
                .timeBase(encoder.timeBase())
                .codecConfig(encoder.codecConfig().orElse(null))
                .width(width)
                .height(height)
                .frameRate(rate)
                .color(ColorInfo.BT709_LIMITED)
                .duration(frames)
                .build();
        return new Stream(track, packets);
    }

    /**
     * Muxes streams, their packets interleaved by time.
     */
    static byte[] mux(Function<MediaSink, Muxer> factory, Stream... streams) {
        var sink = MediaSink.memory();
        record Timed(long micros, int order, Packet packet) {
        }
        var timed = new ArrayList<Timed>();
        for (var stream : streams) {
            for (var packet : stream.packets()) {
                timed.add(new Timed(stream.track().timeBase().toMicros(packet.dts() != Packet.NO_TIMESTAMP
                        ? packet.dts() : packet.pts()), timed.size(), packet));
            }
        }
        timed.sort(Comparator.comparingLong(Timed::micros).thenComparingInt(Timed::order));
        try (var muxer = factory.apply(sink)) {
            for (var stream : streams) {
                muxer.addTrack(stream.track());
            }
            for (var entry : timed) {
                muxer.writePacket(entry.packet());
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /**
     * Returns a Matroska file of streams.
     */
    static byte[] mkv(Stream... streams) {
        return mux(MatroskaMuxer::new, streams);
    }

    /**
     * Returns a WAV file of 32-bit float samples.
     */
    static byte[] wav(int rate, float[][] planes) {
        return mux(WavMuxer::new, pcm(0, Codec.PCM_F32LE, rate, planes));
    }

    /**
     * Returns a YUV4MPEG2 file of an uncompressed stream.
     */
    static byte[] y4m(Stream video) {
        return mux(Y4mMuxer::new, video);
    }

    /**
     * Returns the bytes of a SubRip file.
     */
    static byte[] srt(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Transcodes a file in memory.
     */
    static byte[] transcode(byte[] input, TranscodeOptions options) {
        var output = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(input), output, options);
        return output.toByteArray();
    }

    /**
     * Decodes every sample of the first audio track.
     */
    static float[][] audio(byte[] file) {
        return PcmTranscodeTest.samples(file);
    }

    /**
     * Returns the sampling rate of the first audio track.
     */
    static int sampleRate(byte[] file) {
        try (var reader = AudioReader.open(MediaSource.of(file))) {
            return reader.sampleRate();
        }
    }

    /**
     * Decodes every frame of the first video track.
     */
    static List<Frame> video(byte[] file) {
        var frames = new ArrayList<Frame>();
        try (var reader = VideoReader.open(MediaSource.of(file))) {
            var timeBase = reader.track().timeBase();
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                var copy = (YuvPicture) com.github.auties00.smalt.internal.plane.PictureCopy.copy(frame.picture(), null);
                frames.add(new Frame(timeBase.toMicros(frame.pts()), copy));
            }
        }
        return frames;
    }

    /**
     * Returns the packets of every track of a file, by track in demuxer order.
     */
    static List<Stream> demux(byte[] file) {
        var streams = new ArrayList<Stream>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var byId = new java.util.LinkedHashMap<Integer, List<Packet>>();
            for (var track : demuxer.tracks()) {
                byId.put(track.id(), new ArrayList<>());
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var list = byId.get(packet.trackId());
                if (list != null) {
                    list.add(packet);
                }
            }
            for (var track : demuxer.tracks()) {
                streams.add(new Stream(track, byId.get(track.id())));
            }
        }
        return streams;
    }

    /**
     * Returns the mean of a picture's luma plane.
     */
    static double meanLuma(YuvPicture picture) {
        var sum = 0L;
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                sum += picture.y()[y * picture.yStride() + x] & 0xFF;
            }
        }
        return (double) sum / (picture.width() * picture.height());
    }

    /**
     * Returns the root mean square of a plane over a range of frames.
     */
    static double rms(float[] samples, int from, int to) {
        var sum = 0.0;
        for (var index = from; index < to; index++) {
            sum += (double) samples[index] * samples[index];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }
}
