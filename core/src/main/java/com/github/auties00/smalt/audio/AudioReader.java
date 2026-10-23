package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.TrackKind;

import java.util.Locale;
import java.util.Objects;

/**
 * Reads the first audio track of any supported media file as planar float samples in a chosen
 * sampling rate and channel layout.
 *
 * <p>The reader recognizes the container from the content and feeds the track's packets to an
 * {@link AudioStreamDecoder}, which drops the encoder priming and end padding the container marks,
 * stops after the track's declared duration, mixes to the requested layout and resamples to the
 * requested rate: the output is exactly the samples a player would output, whatever the input (an
 * MP3, an M4A, a WebM or MP4 video, an Ogg file, a WAV). Corrupt packets are skipped as the stream
 * decoder describes.
 *
 * <p>Blocks are pulled with {@link #read()}, which returns {@code null} at the end; the returned
 * buffer is reused, so it is valid only until the next call. After {@link #seek(long)} the next
 * {@code read()} starts with the sample presented at the requested time.
 *
 * <p>Instances are not thread-safe.
 */
public final class AudioReader implements AutoCloseable {
    /**
     * The demuxer reading the container.
     */
    private final Demuxer demuxer;

    /**
     * The track being decoded.
     */
    private final AudioTrack track;

    /**
     * The output rate, layout and limits of every decoder of the track.
     */
    private final AudioStreamDecoderOptions decoderOptions;

    /**
     * The block returned by {@link #read()}, reused across calls.
     */
    private final AudioBuffer block;

    /**
     * The decoder of the track, replaced by every seek.
     */
    private AudioStreamDecoder decoder;

    /**
     * The timing every seek restarts the decoder with, or {@code null} before the first seek.
     */
    private AudioStreamDecoder.Timing timing;

    /**
     * Whether the last seek failed, leaving the demuxer and the decoder out of step until the next
     * successful seek.
     */
    private boolean seekFailed;

    /**
     * Constructs a reader over an open demuxer.
     *
     * @param demuxer the demuxer, owned by the reader
     * @param track the track to decode
     * @param decoder the decoder of the track, owned by the reader
     * @param decoderOptions the output rate, layout and limits of every decoder of the track
     * @param blockFrames the largest number of samples per block
     */
    private AudioReader(Demuxer demuxer, AudioTrack track, AudioStreamDecoder decoder,
                        AudioStreamDecoderOptions decoderOptions, int blockFrames) {
        this.demuxer = demuxer;
        this.track = track;
        this.decoder = decoder;
        this.decoderOptions = decoderOptions;
        this.block = new AudioBuffer(decoder.layout(), decoder.sampleRate(), blockFrames);
    }

    /**
     * Creates a reader of the first audio track of a media file that keeps the codec's rate and
     * layout, under the default limits.
     *
     * @param source the input, read by the reader and not closed by it
     * @return the reader, which the caller closes
     * @throws MediaException.Malformed if the container or the codec configuration is corrupt
     * @throws MediaException.Unsupported if the container is not recognized, has no audio track, or
     * its audio codec is not decodable
     * @throws MediaException.Io if reading the source fails
     */
    public static AudioReader open(MediaSource source) {
        return open(source, AudioReaderOptions.defaults());
    }

    /**
     * Creates a reader of the first audio track of a media file.
     *
     * @param source the input, read by the reader and not closed by it
     * @param options the output rate, layout, block size and limits
     * @return the reader, which the caller closes
     * @throws MediaException.Malformed if the container or the codec configuration is corrupt
     * @throws MediaException.Unsupported if the container is not recognized, has no audio track, or
     * its audio codec is not decodable
     * @throws MediaException.Io if reading the source fails
     */
    public static AudioReader open(MediaSource source, AudioReaderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var demuxer = Containers.openDemuxer(source, DemuxerOptions.builder().limits(options.limits()).build());
        try {
            var track = demuxer.tracks().stream()
                    .filter(candidate -> candidate.kind() == TrackKind.AUDIO)
                    .map(AudioTrack.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new MediaException.Unsupported("no audio", "the input has no audio track"));
            var decoderOptions = decoderOptions(options);
            var decoder = AudioStreamDecoder.create(track, decoderOptions);
            return new AudioReader(demuxer, track, decoder, decoderOptions, options.blockFrames());
        } catch (RuntimeException exception) {
            demuxer.close();
            throw exception;
        }
    }

    /**
     * {@return the container the input was recognized as}
     */
    public ContainerFormat format() {
        return demuxer.format();
    }

    /**
     * {@return the track being decoded}
     */
    public AudioTrack track() {
        return track;
    }

    /**
     * {@return the sampling rate of the blocks {@link #read()} returns, in hertz}
     */
    public int sampleRate() {
        return decoder.sampleRate();
    }

    /**
     * {@return the channel layout of the blocks {@link #read()} returns}
     */
    public ChannelLayout layout() {
        return decoder.layout();
    }

    /**
     * {@return the duration of the audio this reader outputs as the container declares it, in
     * microseconds, or -1 when the container does not declare it}
     */
    public long durationMicros() {
        return track.duration() >= 0 ? track.timeBase().toMicros(track.duration()) : demuxer.durationMicros();
    }

    /**
     * Returns the next block of converted samples.
     *
     * @return a block of up to {@link AudioReaderOptions#blockFrames()} samples per channel, valid
     * until the next call, or {@code null} at the end of the audio
     * @throws IllegalStateException if the last {@link #seek(long)} failed
     * @throws MediaException.Malformed if the container is corrupt or too many consecutive packets
     * are corrupt
     * @throws MediaException.Io if reading the source fails
     */
    public AudioBuffer read() {
        if (seekFailed) {
            throw new IllegalStateException("the last seek failed; seek again before reading");
        }
        var wanted = block.capacity();
        var trackId = track.id();
        while (decoder.available() < wanted && !decoder.isDrained()) {
            var packet = demuxer.read();
            if (packet == null) {
                decoder.finish();
                break;
            }
            if (packet.trackId() == trackId) {
                decoder.decode(packet);
            }
        }
        var frames = decoder.read(block.planes(), 0, wanted);
        if (frames == 0) {
            return null;
        }
        block.setFrames(frames);
        return block;
    }

    /**
     * {@return whether {@link #seek(long)} is supported, which depends on the container}
     */
    public boolean isSeekable() {
        return demuxer.isSeekable();
    }

    /**
     * Positions the reader so that the next {@link #read()} starts at the sample presented at
     * {@code micros}.
     *
     * <p>From {@code micros} on, the output equals a decode from the start of the track, except that
     * Opus and AMR output is not sample-exact until the codec state has converged, and AAC
     * noise-substituted bands and AC-3 and E-AC-3 dithered mantissas may differ. A time before zero
     * seeks to the start; a time at or past the end makes the next {@code read()} return {@code null}.
     *
     * <p>A seek that fails with {@link MediaException.Malformed} or {@link MediaException.Io} leaves
     * the reader unusable until a later seek succeeds: {@code read()} throws
     * {@link IllegalStateException} in between. A seek rejected with
     * {@link MediaException.Unsupported} changes nothing.
     *
     * @implNote This implementation pre-rolls by {@link AudioStreamDecoder.Timing#prerollMicros()}
     * after measuring, on the first seek, how the container's timestamps map to the decoder's output.
     * When container timestamps are coarser than a sample ({@link AudioStreamDecoder.Timing#isExact()}
     * is {@code false}), for AAC Main, LTP and LD, whose prediction state reaches back to the start
     * of the stream, and for AAC-ELD with low delay spectral band replication, it decodes from the
     * start of the track and drops the samples before {@code micros}.
     * @param micros the presentation time, in microseconds
     * @throws MediaException.Malformed if the index needed to seek is corrupt
     * @throws MediaException.Unsupported if the container cannot seek ({@link #isSeekable()} is
     * {@code false})
     * @throws MediaException.Io if reading the source fails
     */
    public void seek(long micros) {
        if (!demuxer.isSeekable()) {
            throw new MediaException.Unsupported("seek", demuxer.format().name().toLowerCase(Locale.ROOT)
                    + " input cannot seek");
        }
        seekFailed = true;
        var timing = timing();
        demuxer.seek(timing.seekMicros(micros));
        var replacement = AudioStreamDecoder.create(track, decoderOptions);
        try {
            replacement.startAt(micros, timing);
        } catch (RuntimeException exception) {
            replacement.close();
            throw exception;
        }
        var previous = decoder;
        decoder = replacement;
        seekFailed = false;
        previous.close();
    }

    /**
     * Returns how the track's timestamps map to the decoder's output, measured on the first seek
     * by decoding the first packets of the track once.
     *
     * @return the timing
     */
    private AudioStreamDecoder.Timing timing() {
        if (timing == null) {
            timing = AudioStreamDecoder.measure(demuxer, track, decoderOptions);
        }
        return timing;
    }

    /**
     * Returns the stream decoder settings of reader options: their rate, layout and limits.
     *
     * @param options the reader options
     * @return the stream decoder options
     */
    private static AudioStreamDecoderOptions decoderOptions(AudioReaderOptions options) {
        var builder = AudioStreamDecoderOptions.builder().limits(options.limits());
        options.sampleRate().ifPresent(builder::sampleRate);
        options.layout().ifPresent(builder::layout);
        return builder.build();
    }

    /**
     * Releases the decoder and the demuxer; the source is not closed.
     */
    @Override
    public void close() {
        try {
            decoder.close();
        } finally {
            demuxer.close();
        }
    }
}
