package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.container.internal.Latm;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads AAC in LOAS framing, {@code AudioSyncStream} frames back to back as ISO/IEC 14496-3 clause
 * 1.7 defines them, the payload of a WAVE {@code data} chunk of format tag {@code 0x1602} or of an
 * {@code HEAACWAVEINFO} of payload type 3.
 *
 * <p>Each frame holds one {@code AudioMuxElement} in the layouts {@link Latm} reads; each of its
 * subframes becomes one packet, the raw data block of 1024 samples, timestamped in samples of the
 * track's rate. The track is described by the first {@code StreamMuxConfig} and refined for HE-AAC
 * from its first payload, as players do; a later configuration that differs is announced on the next
 * packet as its {@link PacketSideData#newCodecConfig() new codec configuration}, and the durations of
 * frames at another core rate are converted to the track's. Elements that reuse a configuration
 * before one was seen, and damaged elements, are skipped; bytes that do not start a frame are
 * skipped up to the next frame whose length leads to another frame or to the end of the data.
 *
 * <p>When the data is at most 64 MiB, a walk over the frames at construction gives an exact duration
 * and indexes every sixteenth frame for {@link #seek(long)}; a larger stream is walked on its first
 * seek.
 *
 * <p>Instances are not thread-safe.
 */
final class LoasDemuxer implements Demuxer {
    /**
     * The largest data walked at construction for an exact duration and seek index.
     */
    private static final long MAX_WALK = 64L << 20;

    /**
     * The largest number of bytes searched for the first frame and for a configuration.
     */
    private static final int MAX_LEADING = 1 << 20;

    /**
     * The largest frame, header included: 3 bytes and a 13-bit length.
     */
    private static final int MAX_FRAME = 3 + 8191;

    /**
     * The flags of a raw data block.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * The payloads of a frame that yields none.
     */
    private static final byte[][] NO_PAYLOADS = new byte[0][];

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * The offset of the first byte of the data.
     */
    private final long dataStart;

    /**
     * The offset of the end of the data, or {@link Long#MAX_VALUE} when unknown.
     */
    private final long dataEnd;

    /**
     * The window over the frames.
     */
    private final ReadAheadCache cache;

    /**
     * The single audio track.
     */
    private final AudioTrack track;

    /**
     * The track list exposed by {@link #tracks()}.
     */
    private final List<Track> tracks;

    /**
     * The rate of the track's timestamps, the core rate of the first configuration.
     */
    private final int trackRate;

    /**
     * The bytes of the frame being read.
     */
    private final byte[] frame = new byte[MAX_FRAME];

    /**
     * The header of the frame that follows a candidate frame.
     */
    private final byte[] probe = new byte[3];

    /**
     * The reader of the frame being parsed.
     */
    private final MsbBitReader reader = new MsbBitReader(frame, 0, 0);

    /**
     * The in-band {@code AudioSpecificConfig} of the first configuration, which the track describes before
     * its HE-AAC refinement.
     */
    private final byte[] baseConfig;

    /**
     * The distinct configurations the walk met, by their index in {@link #index}.
     */
    private final List<Latm.Config> configs = new ArrayList<>();

    /**
     * The index of every sixteenth frame with the index of its configuration in {@link #configs}, or
     * {@code null} before the walk.
     */
    private SeekIndex index;

    /**
     * The number of samples the walk counted, or {@code -1}.
     */
    private long walkedSamples = -1;

    /**
     * The offset of the next frame.
     */
    private long position;

    /**
     * The timestamp of the next packet.
     */
    private long timestamp;

    /**
     * The remainder of converting durations at another core rate, in units of one over that rate times the track's.
     */
    private long remainder;

    /**
     * The configuration in force, or {@code null} before the first.
     */
    private Latm.Config config;

    /**
     * The {@code AudioSpecificConfig} the track or the last announced configuration carries.
     */
    private byte[] reported;

    /**
     * The configuration to announce with the next packet, or {@code null}.
     */
    private Latm.Config pending;

    /**
     * The payloads of the current frame.
     */
    private byte[][] payloads = NO_PAYLOADS;

    /**
     * The index of the next payload of the current frame to return.
     */
    private int payloadIndex;

    /**
     * The timestamp before which packets are dropped after a seek.
     */
    private long skipBefore = Long.MIN_VALUE;

    /**
     * Constructs a demuxer over the frames of a data chunk.
     *
     * @param source the input, not closed by this demuxer
     * @param dataStart the offset of the first byte of the data
     * @param dataEnd the offset of the end of the data, or {@code -1} when unknown
     * @param limits the resource bounds
     * @throws MediaException.Malformed if no frame with a supported configuration starts the data
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     */
    LoasDemuxer(MediaSource source, long dataStart, long dataEnd, MediaLimits limits) {
        this.source = source;
        this.dataStart = dataStart;
        this.dataEnd = dataEnd < 0 ? Long.MAX_VALUE : dataEnd;
        this.cache = new ReadAheadCache(source, 64 * 1024, MAX_FRAME, this.dataEnd);
        var first = findFrame(dataStart, MAX_LEADING);
        if (first < 0) {
            throw new MediaException.Malformed("no LOAS frame found");
        }
        this.position = first;
        while (config == null || payloads.length == 0) {
            if (position - first > MAX_LEADING || !nextFrame()) {
                throw new MediaException.Malformed("LOAS stream without a supported StreamMuxConfig");
            }
        }
        var layout = AudioConfigs.aacLayout(config.channels());
        var count = layout != null ? layout.channels() : config.declaredChannels();
        LimitChecks.checkChannels(limits, count);
        this.trackRate = config.coreRate();
        this.baseConfig = config.audioSpecificConfig();
        var described = AudioTrack.builder()
                .codec(Codec.AAC)
                .timeBase(Rational.timeBaseOf(trackRate))
                .codecConfig(config.audioSpecificConfig())
                .sampleRate(trackRate)
                .layout(layout)
                .build();
        described = AacExtensions.refine(described, payloads[0]);
        var size = this.dataEnd - dataStart;
        if (dataEnd >= 0 && size <= MAX_WALK && ForwardOnlySource.isCheapToReach(source, dataEnd)) {
            walk(first);
            described = described.toBuilder().duration(walkedSamples).build();
        }
        this.track = described;
        this.tracks = List.of(track);
        restart(first, 0, null);
    }

    /**
     * Positions the reader at a frame, with no configuration change pending.
     *
     * @param at the offset of the frame
     * @param time the timestamp of its first packet
     * @param current the configuration in force there, or {@code null}
     */
    private void restart(long at, long time, Latm.Config current) {
        position = at;
        timestamp = time;
        remainder = 0;
        config = current;
        reported = baseConfig;
        pending = current != null && !Arrays.equals(current.audioSpecificConfig(), baseConfig) ? current : null;
        payloads = NO_PAYLOADS;
        payloadIndex = 0;
    }

    /**
     * Returns the length of the frame at an offset, when another frame or the end of the data follows it.
     *
     * @param at the offset
     * @param confirm whether the frame must be followed by another frame or by the end of the data
     * @return the length, or {@code -1} when no frame starts there
     */
    private int frameAt(long at, boolean confirm) {
        if (cache.readUpTo(at, frame, 0, 3) < 3) {
            return -1;
        }
        var length = Latm.frameLength(frame, 0, 3);
        if (length <= 0 || length > dataEnd - at) {
            return -1;
        }
        if (!confirm || dataEnd - at == length) {
            return length;
        }
        if (cache.readUpTo(at + length, probe, 0, 3) < 3) {
            return dataEnd == Long.MAX_VALUE ? length : -1;
        }
        return Latm.frameLength(probe, 0, 3) > 0 ? length : -1;
    }

    /**
     * Searches for a frame confirmed by its successor.
     *
     * @param from the first offset searched
     * @param limit the largest number of bytes searched
     * @return the offset, or {@code -1} when none is found
     */
    private long findFrame(long from, long limit) {
        for (var at = from; at < dataEnd && at - from < limit; at++) {
            if (frameAt(at, true) > 0) {
                return at;
            }
        }
        return -1;
    }

    /**
     * Reads the frame at {@link #position} into {@link #payloads}, resynchronizing past bytes that do not
     * start one.
     *
     * @return {@code false} at the end of the data
     */
    private boolean nextFrame() {
        payloads = NO_PAYLOADS;
        payloadIndex = 0;
        var length = frameAt(position, false);
        if (length < 0) {
            var found = findFrame(position + 1, MAX_LEADING);
            if (found < 0) {
                position = dataEnd;
                return false;
            }
            position = found;
            length = frameAt(position, false);
        }
        if (cache.readUpTo(position, frame, 0, length) < length) {
            position = dataEnd;
            return false;
        }
        position += length;
        ForwardOnlySource.release(source, position);
        parse(length);
        return true;
    }

    /**
     * Parses the {@code AudioMuxElement} of the frame in {@link #frame}.
     *
     * @param length the frame length
     */
    private void parse(int length) {
        var bits = reader;
        bits.reset(frame, 3, length - 3);
        try {
            if (!bits.readFlag()) {
                var parsed = Latm.readStreamMuxConfig(bits);
                if (parsed == null) {
                    return;
                }
                if (reported != null && !Arrays.equals(parsed.audioSpecificConfig(), reported)) {
                    pending = parsed;
                }
                config = parsed;
            } else if (config == null) {
                return;
            }
            var read = Latm.readPayloads(bits, config, length);
            if (read != null) {
                payloads = read;
            }
        } catch (RuntimeException exception) {
            payloads = NO_PAYLOADS;
        }
    }

    /**
     * Walks the frames of the data, counting the samples and indexing every sixteenth frame.
     *
     * @param first the offset of the first frame
     */
    private void walk(long first) {
        var walkIndex = new SeekIndex();
        configs.clear();
        position = first;
        config = null;
        reported = baseConfig;
        var time = 0L;
        var frames = 0L;
        var carried = 0L;
        while (true) {
            var at = position;
            var before = config;
            if (!nextFrame()) {
                break;
            }
            if (frames % 16 == 0) {
                walkIndex.add(time, at, configIndex(before), 0);
            }
            frames++;
            if (config == null) {
                continue;
            }
            configIndex(config);
            for (var unused = 0; unused < payloads.length; unused++) {
                var scaled = 1024L * trackRate + carried;
                carried = scaled % config.coreRate();
                time += scaled / config.coreRate();
            }
        }
        index = walkIndex;
        walkedSamples = time;
    }

    /**
     * Returns the index of a configuration in {@link #configs}, adding it when it is new.
     *
     * @param value the configuration, or {@code null}
     * @return the index, or {@code -1} for {@code null}
     */
    private int configIndex(Latm.Config value) {
        if (value == null) {
            return -1;
        }
        for (var candidate = 0; candidate < configs.size(); candidate++) {
            var known = configs.get(candidate);
            if (Arrays.equals(known.audioSpecificConfig(), value.audioSpecificConfig())
                    && known.subframes() == value.subframes()) {
                return candidate;
            }
        }
        configs.add(value);
        return configs.size() - 1;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.WAV;
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
    public long durationMicros() {
        return track.duration() < 0 ? -1 : track.timeBase().toMicros(track.duration());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} when the end of the data is known
     */
    @Override
    public boolean isSeekable() {
        return dataEnd != Long.MAX_VALUE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every raw data block is a key frame for this demuxer; the next packet is the one that holds the
     * sample presented at {@code micros}, with its exact timestamp.
     *
     * @throws MediaException.Unsupported if the end of the data is unknown
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "LOAS in a WAVE file of unknown size cannot seek");
        }
        if (index == null) {
            walk(dataStart);
        }
        var target = micros <= 0 ? 0 : micros / 1_000_000 * trackRate + micros % 1_000_000 * trackRate / 1_000_000;
        var entry = index.size() == 0 ? -1 : Math.max(0, index.floor(target));
        if (entry < 0) {
            restart(dataEnd, walkedSamples, null);
        } else {
            var state = index.state(entry);
            restart(index.offset(entry), index.timestamp(entry), state < 0 ? null : configs.get(state));
        }
        skipBefore = target;
    }

    @Override
    public Packet read() {
        try {
            while (true) {
                while (payloadIndex < payloads.length) {
                    var payload = payloads[payloadIndex++];
                    var scaled = 1024L * trackRate + remainder;
                    remainder = scaled % config.coreRate();
                    var samples = scaled / config.coreRate();
                    var time = timestamp;
                    timestamp += samples;
                    if (time + samples <= skipBefore) {
                        continue;
                    }
                    var sideData = PacketSideData.NONE;
                    if (pending != null) {
                        var announced = AacExtensions.refine(track.toBuilder()
                                .codecConfig(pending.audioSpecificConfig())
                                .sampleRate(pending.coreRate())
                                .layout(AudioConfigs.aacLayout(pending.channels()))
                                .build(), payload);
                        sideData = PacketSideData.builder().newCodecConfig(announced.codecConfig().orElseThrow()).build();
                        reported = pending.audioSpecificConfig();
                        pending = null;
                    }
                    return new Packet(track.id(), payload, time, time, samples, KEY, sideData);
                }
                if (position >= dataEnd || !nextFrame()) {
                    return null;
                }
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid LOAS data", exception);
        }
    }
}
