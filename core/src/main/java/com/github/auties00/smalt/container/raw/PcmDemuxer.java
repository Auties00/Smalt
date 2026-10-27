package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads raw PCM samples without a header ({@code .pcm}, {@code .raw}), the form {@link RawMuxer} writes PCM tracks
 * in, whose sample format, rate and channel layout the {@linkplain PcmDemuxerOptions options} supply.
 *
 * <p>The file becomes one {@link AudioTrack} with identifier 1 of the options' codec, rate and layout, timed in
 * samples. Each packet holds {@link PcmDemuxerOptions#packetFrames()} interleaved sample frames, the last one the
 * frames left; trailing bytes that do not make a whole frame are ignored. The duration is known when the size of the
 * source is. The demuxer is seekable to the sample frame. The source is not closed. Instances are not thread-safe.
 */
public final class PcmDemuxer implements Demuxer {
    private final MediaSource source;

    private final AudioTrack track;

    /**
     * The bytes of one sample frame.
     */
    private final int frameBytes;

    private final int packetFrames;

    /**
     * The number of whole sample frames, or {@code -1} when the size of the source is unknown.
     */
    private final long frames;

    /**
     * The index of the next sample frame.
     */
    private long next;

    /**
     * Whether the end of the input was met.
     */
    private boolean ended;

    /**
     * Constructs a demuxer.
     *
     * @param source the samples, read from offset zero and not closed by this demuxer
     * @param options the sample format, rate and layout, the packet size and the limits
     * @throws MediaException.LimitExceeded if the channel count or packet size exceeds the limits
     */
    public PcmDemuxer(MediaSource source, PcmDemuxerOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var channels = options.layout().channels();
        LimitChecks.checkChannels(options.limits(), channels);
        var bits = options.codec().pcmBitsPerSample().orElseThrow();
        this.frameBytes = bits / 8 * channels;
        this.packetFrames = options.packetFrames();
        LimitChecks.checkAllocation(options.limits(), (long) frameBytes * packetFrames);
        var size = source.size();
        this.frames = size < 0 ? -1 : size / frameBytes;
        this.track = AudioTrack.builder()
                .id(1)
                .codec(options.codec())
                .timeBase(Rational.timeBaseOf(options.sampleRate()))
                .duration(frames)
                .sampleRate(options.sampleRate())
                .layout(options.layout())
                .bitsPerSample(bits)
                .build();
    }

    /**
     * {@return {@link ContainerFormat#PCM}}
     */
    @Override
    public ContainerFormat format() {
        return ContainerFormat.PCM;
    }

    @Override
    public List<Track> tracks() {
        return List.of(track);
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    @Override
    public long durationMicros() {
        return frames < 0 ? -1 : track.timeBase().toMicros(frames);
    }

    /**
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public Packet read() {
        if (ended || (frames >= 0 && next >= frames)) {
            return null;
        }
        var wanted = frames >= 0 ? (int) Math.min(packetFrames, frames - next) : packetFrames;
        var data = new byte[wanted * frameBytes];
        var filled = 0;
        var position = next * frameBytes;
        while (filled < data.length) {
            var read = source.read(position + filled, data, filled, data.length - filled);
            if (read <= 0) {
                ended = true;
                break;
            }
            filled += read;
        }
        var whole = filled / frameBytes;
        if (whole == 0) {
            ended = true;
            return null;
        }
        if (whole * frameBytes != data.length) {
            data = Arrays.copyOf(data, whole * frameBytes);
        }
        var time = next;
        next += whole;
        return new Packet(1, data, time, time, whole, true);
    }

    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public void seek(long micros) {
        var target = Math.max(0, SeekTimes.floor(micros, track.timeBase()));
        next = frames >= 0 ? Math.min(target, frames) : target;
        ended = false;
    }
}
