package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.container.internal.AacRawBlocks;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.header.AacRates;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads unframed AAC, raw data blocks back to back, optionally after an ADIF header: the payload of a
 * WAVE {@code data} chunk of an AAC format tag whose data is neither ADTS nor LOAS.
 *
 * <p>The blocks carry no lengths, so each one's end is found by walking its syntax with
 * {@link AacRawBlocks}; each block becomes one packet of one frame (1024 or 960 samples at the core
 * rate, the track's rate), timestamped in samples. The track's configuration is the
 * {@code AudioSpecificConfig} of the format's extra bytes, the one built from the ADIF header's first
 * program configuration, or, without either, a Low Complexity configuration built from the format's
 * rate and channel count; it is refined for HE-AAC from the first block, as players do. A block cut
 * short by the end of the data, and bytes after the last block that do not start one, end the stream.
 *
 * <p>When the data is at most {@value #MAX_WALK} bytes, a walk over the blocks at construction gives an
 * exact duration and indexes every sixteenth block for {@link #seek(long)}; a larger stream is walked on
 * its first seek, and its duration is left to the {@code fact} chunk.
 *
 * <p>Instances are not thread-safe.
 */
final class RawAacDemuxer implements Demuxer {
    /**
     * The largest raw data block, in bytes.
     */
    private static final int MAX_BLOCK = 1 << 16;

    /**
     * The largest data walked at construction for an exact duration and seek index.
     */
    static final long MAX_WALK = 16L << 20;

    /**
     * The flags of a raw data block.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

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
     * The offset of the first raw data block, after the ADIF header when there is one.
     */
    private final long firstBlock;

    /**
     * The walker of the blocks.
     */
    private final AacRawBlocks blocks;

    /**
     * The number of samples of a block at the core rate.
     */
    private final int frameLength;

    /**
     * The single audio track.
     */
    private final AudioTrack track;

    /**
     * The track list exposed by {@link #tracks()}.
     */
    private final List<Track> tracks;

    /**
     * The bytes read from the data, from {@link #bufferStart}.
     */
    private final byte[] buffer = new byte[2 * MAX_BLOCK];

    /**
     * The source offset of the first byte of {@link #buffer}.
     */
    private long bufferStart;

    /**
     * The number of valid bytes in {@link #buffer}.
     */
    private int bufferLength;

    /**
     * The offset where the data was found to end, at or before {@link #dataEnd}.
     */
    private long end;

    /**
     * The index of every sixteenth block, or {@code null} before the walk.
     */
    private SeekIndex index;

    /**
     * The number of samples the walk counted, or {@code -1}.
     */
    private long walkedSamples = -1;

    /**
     * The offset of the next block.
     */
    private long position;

    /**
     * The timestamp of the next packet.
     */
    private long timestamp;

    /**
     * The timestamp before which packets are dropped after a seek.
     */
    private long skipBefore = Long.MIN_VALUE;

    /**
     * Constructs a demuxer over the blocks of a data chunk.
     *
     * @param source the input, not closed by this demuxer
     * @param dataStart the offset of the first byte of the data
     * @param dataEnd the offset of the end of the data, or {@code -1} when unknown
     * @param limits the resource bounds
     * @param config the {@code AudioSpecificConfig} the format declares, or {@code null} for none
     * @param sampleRate the rate the format declares
     * @param channels the channel count the format declares
     * @throws MediaException.Malformed if the data does not start with a valid raw data block or ADIF header
     * @throws MediaException.Unsupported if the object type has no raw data block element syntax
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     */
    RawAacDemuxer(MediaSource source, long dataStart, long dataEnd, MediaLimits limits, byte[] config, int sampleRate,
                  int channels) {
        this.source = source;
        this.dataStart = dataStart;
        this.dataEnd = dataEnd < 0 ? Long.MAX_VALUE : dataEnd;
        this.end = this.dataEnd;
        var available = fill(dataStart);
        var start = (int) (dataStart - bufferStart);
        var declaredChannels = 0;
        if (AacRawBlocks.isAdif(buffer, start, available)) {
            var adif = AacRawBlocks.readAdif(buffer, start, available);
            config = adif.audioSpecificConfig();
            declaredChannels = adif.channels();
            this.firstBlock = dataStart + adif.length();
        } else {
            this.firstBlock = dataStart;
            if (config == null) {
                config = synthesizedConfig(sampleRate, channels);
            }
        }
        var parsed = AudioConfigs.parseAac(config);
        if (!AacRawBlocks.supports(parsed.objectType())) {
            // TODO: an er_raw_data_block (object types 17, 19, 20, 23, 39) ends with extension payloads that fill
            //  its access unit, so its length comes from the transport; WAVE_FORMAT_MPEG_HEAAC defines its raw
            //  payload over raw_data_block only, and no specification delimits unframed error resilient blocks.
            throw new MediaException.Unsupported("aac raw object type " + parsed.objectType(),
                    "raw data blocks of AAC object type " + parsed.objectType() + " are not supported");
        }
        var coreRate = parsed.sampleRate();
        var samplingIndex = AacRates.index(coreRate);
        this.blocks = new AacRawBlocks(parsed.objectType(),
                samplingIndex >= 0 ? samplingIndex : AacRawBlocks.samplingIndexFor(coreRate), parsed.frameLength());
        this.frameLength = parsed.frameLength();
        var configuration = channelConfiguration(config);
        var layout = AudioConfigs.aacLayout(configuration);
        var count = layout != null ? layout.channels() : Math.max(parsed.channels(), declaredChannels);
        if (count > 0) {
            LimitChecks.checkChannels(limits, count);
        }
        this.position = firstBlock;
        var first = nextBlock();
        if (first == null) {
            throw new MediaException.Malformed("raw AAC data without a complete raw data block");
        }
        var described = AudioTrack.builder()
                .codec(Codec.AAC)
                .timeBase(Rational.timeBaseOf(coreRate))
                .codecConfig(config)
                .sampleRate(coreRate)
                .layout(layout)
                .build();
        if (AacExtensions.mayRefine(described)) {
            described = AacExtensions.refine(described, first);
        }
        if (dataEnd >= 0 && dataEnd - dataStart <= MAX_WALK && ForwardOnlySource.isCheapToReach(source, dataEnd)) {
            walk();
            described = described.toBuilder().duration(walkedSamples).build();
        }
        this.track = described;
        this.tracks = List.of(track);
        restart(firstBlock, 0);
    }

    /**
     * Returns the {@code AudioSpecificConfig} of a Low Complexity stream of a rate and channel count.
     *
     * @param sampleRate the rate
     * @param channels the channel count
     * @return the configuration
     * @throws MediaException.Malformed if the rate or channel count names no configuration
     */
    private static byte[] synthesizedConfig(int sampleRate, int channels) {
        var configuration = channels >= 1 && channels <= 6 ? channels : channels == 8 ? 7 : -1;
        if (configuration < 0 || sampleRate <= 0 || sampleRate > 0xFFFFFF) {
            throw new MediaException.Malformed("raw AAC of " + channels + " channels at " + sampleRate
                    + " Hz without an AudioSpecificConfig");
        }
        return AudioConfigs.audioSpecificConfig(AacRawBlocks.AOT_LC, sampleRate, configuration, 0, false);
    }

    /**
     * Returns the channel configuration of an {@code AudioSpecificConfig}.
     *
     * @param config the configuration
     * @return the channel configuration, from 0 to 15
     */
    private static int channelConfiguration(byte[] config) {
        var reader = new MsbBitReader(config);
        if (reader.read(5) == 31) {
            reader.skip(6);
        }
        if (reader.read(4) == AacRates.ESCAPE) {
            reader.skip(24);
        }
        return reader.read(4);
    }

    /**
     * Makes the bytes from an offset available in {@link #buffer}, up to {@value #MAX_BLOCK} of them or the end of
     * the data.
     *
     * @param at the offset, at or after {@link #bufferStart} when the buffer is to be kept
     * @return the number of bytes available from {@code at}
     */
    private int fill(long at) {
        var held = bufferStart + bufferLength - at;
        if (at < bufferStart || held < 0) {
            bufferStart = at;
            bufferLength = 0;
            held = 0;
        }
        if (held >= MAX_BLOCK || bufferStart + bufferLength >= end) {
            return (int) Math.min(held, MAX_BLOCK);
        }
        var keep = (int) held;
        System.arraycopy(buffer, (int) (at - bufferStart), buffer, 0, keep);
        bufferStart = at;
        bufferLength = keep;
        while (bufferLength < buffer.length && bufferStart + bufferLength < end) {
            var want = (int) Math.min(buffer.length - bufferLength, end - (bufferStart + bufferLength));
            var read = source.read(bufferStart + bufferLength, buffer, bufferLength, want);
            if (read <= 0) {
                end = bufferStart + bufferLength;
                break;
            }
            bufferLength += read;
        }
        return Math.min(bufferLength, MAX_BLOCK);
    }

    /**
     * Measures the block at {@link #position}, advancing past it.
     *
     * @return the length of the block, or {@code -1} at the end of the stream
     * @throws MediaException.Malformed if the block is invalid or longer than {@value #MAX_BLOCK} bytes
     */
    private int measure() {
        if (position >= end) {
            return -1;
        }
        var available = fill(position);
        var length = blocks.measure(buffer, (int) (position - bufferStart), available);
        if (length < 0) {
            if (position + available < end) {
                throw new MediaException.Malformed("AAC raw data block longer than " + MAX_BLOCK + " bytes");
            }
            position = end;
            return -1;
        }
        return length;
    }

    /**
     * Reads the block at {@link #position}.
     *
     * @return the block, or {@code null} at the end of the stream
     */
    private byte[] nextBlock() {
        var length = measure();
        if (length < 0) {
            return null;
        }
        var offset = (int) (position - bufferStart);
        position += length;
        return Arrays.copyOfRange(buffer, offset, offset + length);
    }

    /**
     * Walks the blocks of the data, counting the samples and indexing every sixteenth block; a block that is not
     * valid ends the walk.
     */
    private void walk() {
        var walkIndex = new SeekIndex();
        position = firstBlock;
        var count = 0L;
        try {
            while (true) {
                var at = position;
                var length = measure();
                if (length < 0) {
                    break;
                }
                if (count % 16 == 0) {
                    walkIndex.add(count * frameLength, at, 0, 0);
                }
                position += length;
                count++;
            }
        } catch (MediaException.Malformed exception) {
            position = end;
        }
        index = walkIndex;
        walkedSamples = count * frameLength;
    }

    /**
     * Positions the reader at a block.
     *
     * @param at the offset of the block
     * @param time its timestamp
     */
    private void restart(long at, long time) {
        position = at;
        timestamp = time;
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
            throw new MediaException.Unsupported("seek", "raw AAC in a WAVE file of unknown size cannot seek");
        }
        if (index == null) {
            walk();
        }
        // the time base counts samples of the core rate, which an HE-AAC refinement does not change
        var rate = track.timeBase().denominator();
        var target = micros <= 0 ? 0 : micros / 1_000_000 * rate + micros % 1_000_000 * rate / 1_000_000;
        var entry = index.size() == 0 ? -1 : Math.max(0, index.floor(target));
        if (entry < 0) {
            restart(end, walkedSamples);
        } else {
            restart(index.offset(entry), index.timestamp(entry));
        }
        skipBefore = target;
    }

    @Override
    public Packet read() {
        try {
            while (true) {
                var block = nextBlock();
                if (block == null) {
                    return null;
                }
                ForwardOnlySource.release(source, position);
                var time = timestamp;
                timestamp += frameLength;
                if (time + frameLength <= skipBefore) {
                    continue;
                }
                return new Packet(track.id(), block, time, time, frameLength, KEY, PacketSideData.NONE);
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid raw AAC data", exception);
        }
    }
}
