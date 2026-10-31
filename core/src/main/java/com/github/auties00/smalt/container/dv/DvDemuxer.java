package com.github.auties00.smalt.container.dv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.container.internal.DifAudioTracks;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.header.DifFrames;
import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads raw DV streams ({@code .dv}, {@code .dif}): the DIF frames of IEC 61834-2 DV, SMPTE ST 314 DVCPRO and
 * DVCPRO50 and SMPTE ST 370 DVCPRO HD, one after the other, as camcorders transfer them over IEEE 1394.
 *
 * <p>The format is read from the header block and the VAUX source pack of the first frame. The demuxer exposes one
 * {@link Codec#DV} video track, whose packets are whole frames (audio, subcode and auxiliary data included, as DV
 * decoders and other containers take them), timestamped in frames, and one {@link Codec#PCM_S16LE} stereo track for
 * each audio pair the AAUX source packs of the first frame name (of the first two packets for the 720-line formats,
 * whose frames alternate between two pairs of DIF channels), timestamped in samples; 12-bit audio is expanded to 16
 * bits and samples holding the audio error code are read as silence. Each frame's audio packets follow its video
 * packet. The video track carries the frame rate, the sample aspect ratio of the 4:3 or 16:9 display of the VAUX
 * source control pack and the field order; the demuxer reports the time code of the first frame's subcode, none when
 * it names an address its counting does not have.
 *
 * <p>A frame whose first block is not a header block is skipped: the demuxer moves on to the next header block of
 * DIF sequence 0, 80 bytes at a time. A truncated last frame ends the stream. The demuxer seeks to any frame of a
 * source whose size is known. A source that reads its input once, front to back, is told after every frame which
 * bytes are no longer needed.
 */
public final class DvDemuxer implements Demuxer {
    /**
     * The track ID of the video track.
     */
    private static final int VIDEO_ID = 1;

    /**
     * The bytes that identify the start of a frame: its first eight blocks.
     */
    private static final int PROBE = 8 * DifProfile.BLOCK;

    private final MediaLimits limits;
    private final SourceReader reader;
    private final DifProfile profile;
    private final VideoTrack video;
    private final DifAudioTracks audio;
    private final List<Track> tracks;
    private final Timecode timecode;
    private final ArrayDeque<Packet> pending = new ArrayDeque<>();
    private final byte[] probe = new byte[PROBE];
    private final long start;
    private long frame;
    private long released;
    private boolean finished;

    /**
     * Constructs a demuxer over a source with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the input does not start with a DIF frame
     * @throws MediaException.Io if reading fails
     */
    public DvDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over a source that enforces the limits of the options.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input does not start with a DIF frame
     * @throws MediaException.Io if reading fails
     */
    public DvDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        this.limits = options.limits();
        try {
            this.reader = new SourceReader(source, 1 << 20);
            this.start = reader.position();
            var head = new byte[(int) Math.min(DifProfile.DVCPRO_HD_1080I50.frameSize(),
                    reader.remaining(DifProfile.DVCPRO_HD_1080I50.frameSize()))];
            reader.readFully(head, 0, head.length);
            var detected = DifProfile.detect(head, 0, head.length);
            if (detected == null || head.length < detected.frameSize()) {
                throw new MediaException.Malformed("not a DV stream");
            }
            this.profile = detected;
            var size = detected.frameSize();
            var available = Math.min(head.length, detected.progressive() ? 2 * size : size);
            this.audio = DifAudioTracks.of(detected, head, 0, available, VIDEO_ID + 1);
            var wide = detected.wide(head, 0, size);
            var rate = detected.frameRate();
            var builder = VideoTrack.builder()
                    .id(VIDEO_ID)
                    .codec(Codec.DV)
                    .timeBase(new Rational(rate.denominator(), rate.numerator()))
                    .width(detected.width())
                    .height(detected.height())
                    .frameRate(rate)
                    .sampleAspectRatio(detected.sampleAspectRatio(wide))
                    .color(detected.highDefinition() ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED)
                    .interlacing(detected.progressive() ? Interlacing.PROGRESSIVE
                            : detected.highDefinition() ? Interlacing.TOP_FIELD_FIRST : Interlacing.BOTTOM_FIELD_FIRST);
            var total = reader.size();
            if (total >= 0) {
                builder.duration((total - start) / size);
            }
            this.video = builder.build();
            var list = new ArrayList<Track>();
            list.add(video);
            list.addAll(audio.tracks());
            this.tracks = List.copyOf(list);
            this.timecode = timecode(DifFrames.readTimecode(head, 0, detected), detected);
            reader.seek(start);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DV stream", exception);
        }
    }

    /**
     * Builds the time code of the first frame.
     *
     * @param code the hours, minutes, seconds, frames and drop-frame flag of the subcode, or {@code null}
     * @param profile the format of the stream
     * @return the time code, or {@code null} when there is none or it names an address its counting skips
     */
    private static Timecode timecode(int[] code, DifProfile profile) {
        if (code == null) {
            return null;
        }
        try {
            return new Timecode(code[0], code[1], code[2], code[3], profile.fiftyHz() ? 25 : 30, code[4] == 1);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Returns whether a stream starts with a DIF frame: a header block of DIF sequence 0, then the two subcode
     * blocks, the three VAUX blocks, an audio block and a video block of that sequence.
     *
     * @param header the first bytes of the stream, at least 640 to match
     * @return {@code true} for a raw DV stream
     */
    public static boolean matches(byte[] header) {
        Objects.requireNonNull(header, "header");
        return startsFrame(header) && DifProfile.detect(header, 0, header.length) != null;
    }

    /**
     * Returns whether bytes start with the first eight blocks of a DIF sequence 0: a header block, two subcode
     * blocks, three VAUX blocks, an audio block and a video block, numbered as they come.
     *
     * @param data the bytes, at least {@link #PROBE} of them to match
     * @return {@code true} when the blocks have those IDs
     */
    private static boolean startsFrame(byte[] data) {
        if (data.length < PROBE) {
            return false;
        }
        int[] sections = {0, 1, 1, 2, 2, 2, 3, 4};
        int[] numbers = {0, 0, 1, 0, 1, 2, 0, 0};
        for (var block = 0; block < sections.length; block++) {
            var at = block * DifProfile.BLOCK;
            if (((data[at] & 0xFF) >>> 5) != sections[block] || ((data[at + 1] & 0xFF) >>> 4) != 0
                    || (data[at + 2] & 0xFF) != numbers[block]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.DV;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    @Override
    public Optional<Timecode> timecode() {
        return Optional.ofNullable(timecode);
    }

    /**
     * {@inheritDoc}
     *
     * @return the duration of the whole frames of the source, or {@code -1} when its size is unknown
     */
    @Override
    public long durationMicros() {
        if (video.duration() == Track.UNKNOWN_DURATION) {
            return -1;
        }
        var rate = profile.frameRate();
        return Math.floorDiv(video.duration() * 1_000_000L * rate.denominator(), rate.numerator());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} when the size of the source is known
     */
    @Override
    public boolean isSeekable() {
        return reader.size() >= 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The demuxer moves to the frame whose time span holds the target; every frame is a key frame. Each audio
     * track restarts at its last packet starting at or before the target, its timestamps counting the samples of the
     * frames read so far and the nominal count of SMPTE ST 314 clause 4.6.2.1.5 for the others. A target past the
     * last frame ends the input.
     *
     * @throws MediaException.Unsupported if the size of the source is unknown, or the source reads its input once and
     * the target frame lies before the bytes it was told are no longer needed and those bytes are gone; the demuxer
     * then stays where it was
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "the DV source has no known size");
        }
        var target = Math.max(0, SeekTimes.floor(micros, video.timeBase()));
        if (reader.source() instanceof ForwardOnlySource && target < video.duration()
                && start + target * profile.frameSize() < released
                && !reader.buffers(start + target * profile.frameSize(), released)
                && !ForwardOnlySource.keeps(reader.source(), start + target * profile.frameSize(), released)) {
            throw new MediaException.Unsupported("seek", "the DV stream is read once and its data before "
                    + released + " is gone");
        }
        pending.clear();
        if (target >= video.duration()) {
            finished = true;
            return;
        }
        frame = target;
        finished = false;
        reader.seek(start + target * profile.frameSize());
        audio.restart(target, micros);
    }

    @Override
    public Packet read() {
        if (!pending.isEmpty()) {
            return pending.poll();
        }
        if (finished) {
            return null;
        }
        try {
            var size = profile.frameSize();
            while (true) {
                if (reader.remaining(size) < size) {
                    finished = true;
                    return null;
                }
                var at = reader.position();
                reader.readFully(probe, 0, probe.length);
                reader.seek(at);
                if (startsFrame(probe) && (probe[1] & 0x08) == 0) {
                    break;
                }
                reader.seek(at + DifProfile.BLOCK);
            }
            LimitChecks.checkFrames(limits, frame + 1);
            LimitChecks.checkAllocation(limits, size);
            var data = new byte[size];
            reader.readFully(data, 0, size);
            released = reader.position();
            ForwardOnlySource.release(reader.source(), released);
            var pts = frame++;
            audio.split(data, 0, pending::add);
            return new Packet(VIDEO_ID, data, pts, pts, 1, true);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DV frame", exception);
        }
    }
}
