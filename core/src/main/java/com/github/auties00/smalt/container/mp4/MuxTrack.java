package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.internal.codec.AmrConfigs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Arrays;

/**
 * Collects the samples of one track while an {@link Mp4Muxer} writes, and derives the timing
 * tables and edit list of its {@code trak} when the muxer finishes.
 *
 * <p>Timestamps are converted to the track's media timescale as packets arrive. When a track's
 * packets carry presentation timestamps only (as Matroska delivers codecs with frame
 * reordering), decoding timestamps are derived at the end: the sorted presentation timestamps,
 * lowered by the largest reordering delay so that no sample is decoded after it is presented.
 * A video track whose time base is a frame duration in nanoseconds is written in its frame
 * rate's timescale ({@link #frameTimescale}).
 */
final class MuxTrack {
    /**
     * The finest media timescale a time base of whole frames keeps as its own: a finer time base
     * whose tick is a frame of a common rate is written in that rate's timescale instead.
     */
    private static final long MAX_FRAME_TIMESCALE = 1_000_000;

    /**
     * The largest relative difference between a time base's tick and the frame duration of the
     * rate it is written at.
     */
    private static final double FRAME_RATE_TOLERANCE = 1e-6;

    /**
     * The description the caller declared.
     */
    final Track track;

    /**
     * The track identifier written in the file, starting at one.
     */
    final int number;

    /**
     * The media timescale in ticks per second.
     */
    final long timescale;

    /**
     * The factor converting the track's time base to the media timescale exactly, or {@code 0}
     * when timestamps are rescaled with rounding.
     */
    private final long multiplier;

    /**
     * The target of rounded rescaling, when {@link #multiplier} is zero.
     */
    private final Rational media;

    /**
     * Whether the track's packets carry presentation timestamps only.
     */
    boolean ptsOnly;

    /**
     * The size of each sample.
     */
    int[] sizes = new int[256];

    /**
     * The decoding timestamp of each sample in media timescale units.
     */
    long[] dts = new long[256];

    /**
     * The presentation timestamp of each sample in media timescale units.
     */
    long[] pts = new long[256];

    /**
     * Whether each sample is a sync sample.
     */
    boolean[] keyframes = new boolean[256];

    /**
     * The number of samples.
     */
    int count;

    /**
     * The duration of the last packet in media timescale units, or {@code 0} when unknown.
     */
    long lastDuration;

    /**
     * The offset of each chunk relative to the start of the media data.
     */
    long[] chunkOffsets = new long[64];

    /**
     * The number of samples in each chunk.
     */
    int[] chunkSamples = new int[64];

    /**
     * The number of chunks.
     */
    int chunkCount;

    /**
     * The initial size of {@link #pending}.
     */
    private static final int INITIAL_PENDING = 64 * 1024;

    /**
     * The bytes of the chunk being assembled when they are copied, allocated on first use.
     */
    byte[] pending;

    /**
     * The payloads of the chunk being assembled when they are held by reference.
     */
    byte[][] held = new byte[16][];

    /**
     * The number of payloads in {@link #held}.
     */
    int heldCount;

    /**
     * The number of bytes of the chunk being assembled, copied or held.
     */
    int pendingBytes;

    /**
     * The number of samples in the chunk being assembled.
     */
    int pendingSamples;

    /**
     * The start time of the chunk being assembled, in microseconds.
     */
    long pendingStartMicros;

    /**
     * The first bytes of the first sample, used to identify MPEG audio parameters and the scan and
     * alpha of ProRes and DNxHD frames, or the whole first sample of an AC-3 or E-AC-3 track, from
     * which the {@code dac3} or {@code dec3} box is built.
     */
    byte[] firstBytes;

    /**
     * The AMR speech modes the samples use, one bit per frame type, for the {@code damr} mode set;
     * {@code 0} for other codecs or until a sample with data is recorded.
     */
    int amrModes;

    /**
     * The codec configuration the sample entry stores: the track's, or for a VP9 or AV1 track
     * without one, the configuration derived from its first key frame; {@code null} until known.
     */
    byte[] config;

    /**
     * Constructs the state of a declared track.
     *
     * @param track the description
     * @param number the track identifier written in the file
     */
    MuxTrack(Track track, int number) {
        this.track = track;
        this.number = number;
        this.config = track.codecConfig().filter(bytes -> bytes.length > 0).orElse(null);
        var timeBase = track.timeBase();
        var frameTimescale = track instanceof VideoTrack ? frameTimescale(timeBase) : 0;
        if (frameTimescale > 0) {
            this.timescale = frameTimescale;
            this.multiplier = 0;
            this.media = new Rational(1, frameTimescale);
        } else if (timeBase.denominator() <= 0xFFFFFFFFL) {
            this.timescale = timeBase.denominator();
            this.multiplier = timeBase.numerator();
            this.media = null;
        } else {
            var fallback = track instanceof AudioTrack audio && audio.sampleRate() > 0 ? audio.sampleRate() : 90000;
            this.timescale = fallback;
            this.multiplier = 0;
            this.media = new Rational(1, fallback);
        }
    }

    /**
     * Returns the media timescale of a time base finer than {@link #MAX_FRAME_TIMESCALE} ticks per
     * second whose tick is, within {@link #FRAME_RATE_TOLERANCE}, one frame of {@code N} frames per
     * second (timescale {@code N}) or of {@code N * 1000 / 1001} frames per second (timescale
     * {@code N * 1000}, a frame lasting 1001 ticks).
     *
     * <p>Such time bases are frame durations measured in nanoseconds, as Matroska stores them, for
     * example:
     * {@snippet :
     * 33333333/1000000000 -> 30     // 30 frames per second
     * 33366667/1000000000 -> 30000  // 29.97 frames per second
     * }
     * Their exact timescale, a billion ticks per second, would push the composition offsets of a
     * stream that reorders frames by a quarter of a second past 2^28 ticks, beyond which some readers
     * discard the {@code ctts} table and present the frames in decoding order. The
     * timestamps are rounded to the frame timescale, which moves a frame by less than a
     * millionth of its duration per frame of the stream.
     *
     * @param timeBase the track's time base
     * @return the frame rate's timescale, or {@code 0} when the time base is kept or is not a frame
     * duration
     */
    static long frameTimescale(Rational timeBase) {
        if (timeBase.denominator() <= MAX_FRAME_TIMESCALE || timeBase.numerator() <= 1) {
            return 0;
        }
        var rate = (double) timeBase.denominator() / timeBase.numerator();
        var whole = Math.round(rate);
        if (whole > 0 && Math.abs(rate - whole) <= rate * FRAME_RATE_TOLERANCE) {
            return whole;
        }
        var ntsc = Math.round(rate * 1.001);
        if (ntsc > 0 && Math.abs(rate * 1.001 - ntsc) <= rate * FRAME_RATE_TOLERANCE) {
            return ntsc * 1000;
        }
        return 0;
    }

    /**
     * Derives the configuration of a VP9 or AV1 track that declares none from a key frame.
     *
     * <p>Does nothing when the configuration is known, the codec is neither VP9 nor AV1, or the
     * frame does not hold the headers the configuration comes from.
     *
     * @param data the array holding the key frame
     * @param offset the index of the first frame byte
     * @param size the frame size
     */
    void deriveConfig(byte[] data, int offset, int size) {
        if (config != null || data == null) {
            return;
        }
        var frame = offset == 0 && size == data.length ? data : Arrays.copyOfRange(data, offset, offset + size);
        if (track.codec() == Codec.AV1) {
            config = CodecConfigs.av1Config(frame);
        } else if (track.codec() == Codec.VP9 && track instanceof VideoTrack video) {
            config = CodecConfigs.vp9Config(frame, video.width(), video.height(), video.color().orElse(null));
        }
    }

    /**
     * {@return the kind of the track}
     */
    TrackKind kind() {
        return track.kind();
    }

    /**
     * {@return the codec of the track}
     */
    Codec codec() {
        return track.codec();
    }

    /**
     * Converts a timestamp from the track's time base to the media timescale.
     *
     * @param value the timestamp
     * @return the converted value
     * @throws IllegalArgumentException if the value overflows
     */
    long toMedia(long value) {
        try {
            return multiplier > 0 ? Math.multiplyExact(value, multiplier) : track.timeBase().rescale(value, media);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("timestamp " + value + " overflows the media timescale", exception);
        }
    }

    /**
     * Converts a media timescale value to microseconds.
     *
     * @param value the value in media timescale units
     * @return the value in microseconds
     */
    long toMicros(long value) {
        return Mp4Track.rescale(value, timescale, 1_000_000);
    }

    /**
     * Records one sample.
     *
     * @param size the sample size in bytes
     * @param packetPts the presentation timestamp in the track's time base, or
     * {@link Packet#NO_TIMESTAMP}
     * @param packetDts the decoding timestamp in the track's time base, or
     * {@link Packet#NO_TIMESTAMP}
     * @param packetDuration the duration in the track's time base, or {@link Packet#UNKNOWN_DURATION}
     * @param keyframe whether the sample is a sync sample
     * @param data the sample bytes, or {@code null} when the muxer only plans the layout
     * @throws IllegalArgumentException if the decoding timestamps decrease or the timestamps are
     * missing when the track needs them
     */
    void addSample(int size, long packetPts, long packetDts, long packetDuration, boolean keyframe, byte[] data) {
        addSample(size, packetPts, packetDts, packetDuration, keyframe, data, 0);
    }

    /**
     * Records one sample whose bytes start at an offset of an array.
     *
     * @param size the sample size in bytes
     * @param packetPts the presentation timestamp in the track's time base, or
     * {@link Packet#NO_TIMESTAMP}
     * @param packetDts the decoding timestamp in the track's time base, or
     * {@link Packet#NO_TIMESTAMP}
     * @param packetDuration the duration in the track's time base, or {@link Packet#UNKNOWN_DURATION}
     * @param keyframe whether the sample is a sync sample
     * @param data the array holding the sample bytes, or {@code null} when the muxer only plans the
     * layout
     * @param offset the index of the first sample byte in {@code data}
     * @throws IllegalArgumentException if the decoding timestamps decrease or the timestamps are
     * missing when the track needs them
     */
    void addSample(int size, long packetPts, long packetDts, long packetDuration, boolean keyframe, byte[] data, int offset) {
        if (count == 0) {
            ptsOnly = packetDts == Packet.NO_TIMESTAMP && packetPts != Packet.NO_TIMESTAMP;
        }
        long decode;
        long present;
        var duration = packetDuration > 0 ? toMedia(packetDuration) : 0;
        if (ptsOnly) {
            present = packetPts != Packet.NO_TIMESTAMP ? toMedia(packetPts) : nextTime(pts);
            decode = present;
        } else {
            decode = packetDts != Packet.NO_TIMESTAMP ? toMedia(packetDts) : nextTime(dts);
            present = packetPts != Packet.NO_TIMESTAMP ? toMedia(packetPts) : decode;
            if (count > 0 && decode < dts[count - 1]) {
                throw new IllegalArgumentException("decoding timestamps of track " + track.id() + " decrease: "
                        + dts[count - 1] + " then " + decode);
            }
        }
        if (count == sizes.length) {
            var capacity = count * 2;
            sizes = Arrays.copyOf(sizes, capacity);
            dts = Arrays.copyOf(dts, capacity);
            pts = Arrays.copyOf(pts, capacity);
            keyframes = Arrays.copyOf(keyframes, capacity);
        }
        sizes[count] = size;
        dts[count] = decode;
        pts[count] = present;
        keyframes[count] = keyframe;
        count++;
        lastDuration = duration;
        if (firstBytes == null && data != null) {
            firstBytes = Arrays.copyOfRange(data, offset, offset + Math.min(size, firstBytesLength(track.codec())));
        }
        if (data != null && (track.codec() == Codec.AMR_NB || track.codec() == Codec.AMR_WB)) {
            amrModes |= AmrConfigs.modes(track.codec() == Codec.AMR_WB, data, offset, size);
        }
    }

    /**
     * Returns the timestamp following the last sample, for a packet without one.
     *
     * @param times the decoding or presentation timestamps recorded so far
     * @return the last timestamp plus the last known duration, or {@code 0} for the first sample
     */
    private long nextTime(long[] times) {
        if (count == 0) {
            return 0;
        }
        var step = lastDuration > 0 ? lastDuration : count > 1 ? times[count - 1] - times[count - 2] : 0;
        return times[count - 1] + Math.max(step, 0);
    }

    /**
     * Appends a copy of a sample to the chunk being assembled.
     *
     * @param data the sample bytes, or {@code null} when only the layout is planned
     * @param size the sample size
     */
    void appendPending(byte[] data, int size) {
        appendPending(data, 0, size);
    }

    /**
     * Appends a copy of a sample to the chunk being assembled.
     *
     * @param data the array holding the sample bytes, or {@code null} when only the layout is
     * planned
     * @param offset the index of the first sample byte in {@code data}
     * @param size the sample size
     */
    void appendPending(byte[] data, int offset, int size) {
        if (data != null) {
            if (pending == null) {
                pending = new byte[Math.max(INITIAL_PENDING, size)];
            } else if (pendingBytes + size > pending.length) {
                pending = Arrays.copyOf(pending, Math.max(pendingBytes + size, pending.length * 2));
            }
            System.arraycopy(data, offset, pending, pendingBytes, size);
        }
        pendingBytes += size;
        pendingSamples++;
    }

    /**
     * Appends a sample to the chunk being assembled by reference.
     *
     * <p>The array is not copied: {@link Packet} payloads are immutable, so it can be written
     * when the chunk closes.
     *
     * @param data the whole sample
     */
    void holdPending(byte[] data) {
        if (heldCount == held.length) {
            held = Arrays.copyOf(held, heldCount * 2);
        }
        held[heldCount++] = data;
        pendingBytes += data.length;
        pendingSamples++;
    }

    /**
     * Records a written chunk and empties the pending one.
     *
     * @param offset the chunk offset relative to the start of the media data
     */
    void closeChunk(long offset) {
        if (chunkCount == chunkOffsets.length) {
            chunkOffsets = Arrays.copyOf(chunkOffsets, chunkCount * 2);
            chunkSamples = Arrays.copyOf(chunkSamples, chunkCount * 2);
        }
        chunkOffsets[chunkCount] = offset;
        chunkSamples[chunkCount] = pendingSamples;
        chunkCount++;
        pendingBytes = 0;
        pendingSamples = 0;
        if (heldCount > 0) {
            Arrays.fill(held, 0, heldCount, null);
            heldCount = 0;
        }
        if (pending != null && pending.length > 4 << 20) {
            pending = new byte[INITIAL_PENDING];
        }
    }

    /**
     * Derives decoding timestamps for a track that carries presentation timestamps only.
     */
    void deriveDecodingTimes() {
        if (!ptsOnly || count == 0) {
            return;
        }
        var sorted = Arrays.copyOf(pts, count);
        Arrays.sort(sorted);
        var delay = 0L;
        for (var index = 0; index < count; index++) {
            delay = Math.max(delay, sorted[index] - pts[index]);
        }
        for (var index = 0; index < count; index++) {
            dts[index] = sorted[index] - delay;
        }
    }

    /**
     * Returns the duration of each sample: the gap to the next decoding timestamp, and for the
     * last sample its packet duration, the previous gap, or the codec's frame duration.
     *
     * @return the durations, one per sample
     */
    long[] durations() {
        var result = new long[count];
        for (var index = 0; index + 1 < count; index++) {
            result[index] = dts[index + 1] - dts[index];
        }
        if (count > 0) {
            var last = lastDuration;
            if (last <= 0 && count > 1) {
                last = result[count - 2];
            }
            if (last <= 0) {
                last = defaultFrameDuration();
            }
            result[count - 1] = Math.max(last, 0);
        }
        return result;
    }

    /**
     * Returns the duration of one frame of an audio codec with fixed frames, in media units.
     *
     * @return the frame duration, or {@code 0} when unknown
     */
    private long defaultFrameDuration() {
        if (!(track instanceof AudioTrack audio) || audio.sampleRate() <= 0) {
            return 0;
        }
        var samples = switch (audio.codec()) {
            case AAC -> 1024;
            case MP3 -> audio.sampleRate() >= 32000 ? 1152 : 576;
            case OPUS -> 960 * audio.sampleRate() / 48000;
            case AMR_NB, AMR_WB -> audio.sampleRate() / 50;
            default -> 0;
        };
        return Mp4Track.rescale(samples, audio.sampleRate(), timescale);
    }

    /**
     * Returns the priming the edit list must skip, in media timescale units: the track's
     * {@link AudioTrack#skipSamples()}, plus the Opus pre-skip, which the Opus binding requires
     * the edit list to cover.
     *
     * @return the skip, non-negative
     */
    long priming() {
        if (!(track instanceof AudioTrack audio)) {
            return 0;
        }
        var rate = audio.sampleRate() > 0 ? audio.sampleRate() : timescale;
        var skip = Mp4Track.rescale(audio.skipSamples(), rate, timescale);
        var preSkip = audio.codec() == Codec.OPUS ? AudioConfigs.opusPreSkip(audio.codecConfig().orElse(null)) : -1;
        if (preSkip >= 0) {
            skip += Mp4Track.rescale(preSkip, 48000, timescale);
        }
        return Math.max(skip, 0);
    }

    /**
     * Returns how many leading bytes of the first sample a track keeps.
     *
     * @param codec the codec of the track
     * @return the whole sample, up to 64 KiB, for AC-3 and E-AC-3, whose configuration boxes
     * describe every substream of the first access unit; eight bytes otherwise
     */
    static int firstBytesLength(Codec codec) {
        return codec == Codec.AC3 || codec == Codec.EAC3 ? 64 << 10 : 64;
    }
}
