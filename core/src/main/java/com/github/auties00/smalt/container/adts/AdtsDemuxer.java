package com.github.auties00.smalt.container.adts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.container.mpeg.internal.TrailingTags;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.header.AacRates;
import com.github.auties00.smalt.internal.io.ArrayBacked;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.internal.io.SegmentBacked;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads AAC elementary streams in ADTS framing (ISO/IEC 13818-7 and 14496-3 section 1.A.3).
 *
 * <p>Leading ID3v2 tags are skipped (their text frames become {@link #metadata()}) and trailing
 * ID3v1, APE, Lyrics3 and appended ID3v2 tags are excluded from the audio. An ID3v2.4 tag
 * appended after the audio or located by a {@code SEEK} frame replaces the ID3v2 fields and
 * pictures read before it, or only those it holds when flagged as an update (ID3v2.4 section 5);
 * the APE tag and then the ID3v1 tag add the fields still missing, and APE cover art counts only
 * when no ID3v2 tag holds a picture. The stream is located by its first
 * ADTS header whose successor is another consistent header (same MPEG version, object type,
 * sampling frequency and channel configuration) or the end of the audio; after garbage in the
 * middle of the stream the demuxer resynchronizes the same way.
 *
 * <p>The track's {@link AudioTrack#codecConfig()} is an {@code AudioSpecificConfig} synthesized
 * from the first header: the object type is the ADTS profile plus one, followed by the sampling
 * frequency index, the channel configuration and a {@code GASpecificConfig} with all flags
 * cleared. When the channel configuration is zero the program configuration element that opens
 * the first raw data block is copied into the {@code GASpecificConfig}. The declared layout is the
 * channel configuration's speakers in WAVE order, the order this module's AAC decoder outputs;
 * it is empty for configuration zero.
 *
 * <p>ADTS cannot signal HE-AAC: spectral band replication and parametric stereo travel inside the
 * raw data blocks of a Low Complexity stream (implicit signaling). For a Low Complexity stream at
 * a core rate of at most 24 kHz, where HE-AAC is found, the first frame is therefore decoded
 * through the AAC decoder that {@link AudioDecoders} provides, as players do; when it outputs
 * more than the core rate, the track declares that output rate and layout, and its configuration
 * signals the extensions explicitly with the backward-compatible sync extensions of ISO/IEC
 * 14496-3 ({@code 0x2B7} for spectral band replication, {@code 0x548} for parametric stereo when
 * a mono core decodes to stereo). Without an AAC decoder, or when the first frame does not
 * decode, the track keeps the header's description. Timestamps stay in samples of the core
 * rate.
 *
 * <p>Each packet is one {@code raw_data_block} without the ADTS header and CRC words, lasting 1024
 * samples, with timestamps in samples. A frame that holds several raw data blocks is split into
 * one packet per block when its header carries a CRC (the block positions are then signaled);
 * without CRC the block boundaries are only known to an AAC parser, so the frame's blocks are
 * passed through as a single packet lasting {@code 1024 * blocks} samples, and the AAC decoder
 * must decode consecutive raw data blocks until the packet is consumed. CRC words are not
 * verified.
 *
 * <p>When the audio is at most 64 MiB the duration is exact, from a walk over every frame header,
 * and is reported both on the track and by {@link #durationMicros()}. For larger files the track
 * duration is {@code -1}, because readers trim decoded output with it, and
 * {@link #durationMicros()} reports an estimate from the average size of the first 1000 frames;
 * when the source size is unknown both are {@code -1}.
 *
 * <p>Header probes, resynchronization searches and frame reads go through a 64 KiB read-ahead
 * window that slides forward, so a file or network source is read sequentially in large blocks
 * rather than several times per frame, and an in-memory source is read in place. When the source
 * is not in memory, the frames the construction-time walk visits are split into packets as they
 * pass, up to 8 MiB of them, and {@link #read()} returns those packets before reading on
 * from the first frame the walk did not keep: audio of up to 8 MiB is read once, and a larger
 * file is read again only from where the kept packets end.
 *
 * <p>A stream of known size can seek. A stream of at most 64 MiB is indexed on the first
 * {@link #seek(long)} (every sixteenth frame header with its exact timestamp), so the next packet
 * is exactly the raw data block holding the target sample. A larger stream jumps to the byte
 * position the average frame size of its first frames gives, resynchronizes there, and estimates
 * its timestamps from that position, rounded to whole frames. ID3v2 pictures and chapters become
 * {@link #attachedPictures()} and {@link #chapters()}.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every frame which
 * bytes are no longer needed. Its trailing tags and the count of every frame are read only when
 * the whole input fits the source's memory budget; otherwise the duration is estimated from the
 * first frames.
 *
 * @implNote This implementation keeps the walk's packets because an exact duration needs every
 * frame header, and frames are at most 8 KiB apart, so the walk reads the whole file whatever it
 * skips; reading the frames a second time would fetch a network source twice. The kept packets
 * are the ones {@link #read()} would allocate anyway, so the total allocation is unchanged;
 * only their lifetime grows, bounded by the 8 MiB budget (or {@link MediaLimits#maxBytes()} when
 * smaller). An in-memory source keeps nothing, since reading it again costs no input.
 */
public final class AdtsDemuxer implements Demuxer {
    /**
     * The sync extension type announcing a backward-compatible spectral band replication.
     */
    private static final int SYNC_EXTENSION_SBR = 0x2B7;

    /**
     * The sync extension type announcing a backward-compatible parametric stereo.
     */
    private static final int SYNC_EXTENSION_PS = 0x548;

    /**
     * The largest audio region whose frames are counted for an exact duration.
     */
    private static final long MAX_SCANNED_BYTES = 64L << 20;

    /**
     * The number of frames averaged for a duration estimate.
     */
    private static final int ESTIMATE_FRAMES = 1000;

    /**
     * The largest number of bytes of packets the construction-time walk keeps for
     * {@link #read()}, counted as frame sizes plus {@link #PACKET_OVERHEAD} per packet.
     */
    private static final long MAX_KEPT_BYTES = 8L << 20;

    /**
     * The heap size charged to the kept-packet budget for each packet beyond its payload: the
     * packet and array headers and the queue slot.
     */
    private static final int PACKET_OVERHEAD = 64;

    /**
     * The smallest initial capacity of the packet queue, the default capacity of an
     * {@link ArrayDeque}.
     */
    private static final int MIN_QUEUE = 16;

    /**
     * The largest initial capacity of the queue of kept packets.
     *
     * @implNote This implementation caps it at 16384 slots (64 KiB of references), more than the
     * frames of the 8 MiB budget at typical bitrates, so a crafted stream of tiny frames cannot
     * make the queue allocate much ahead of the packets it actually keeps.
     */
    private static final int MAX_EXPECTED_KEPT = 1 << 14;

    /**
     * The identifier of the single track.
     */
    private static final int TRACK_ID = 0;

    /**
     * The size of the search window used while resynchronizing.
     */
    private static final int WINDOW_SIZE = 32 * 1024;

    /**
     * The largest ADTS frame, whose 13-bit length field bounds how far a frame read lies behind
     * the probe of the next header.
     */
    private static final int MAX_FRAME_LENGTH = (1 << 13) - 1;

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * The offset of the end of the audio, or {@code -1} when the source size is unknown.
     */
    private final long audioEnd;

    /**
     * The header of the first frame, to which every later frame must be consistent, packed by
     * {@link #parseWord(byte[], int)}.
     */
    private final int reference;

    /**
     * The single audio track.
     */
    private final AudioTrack track;

    /**
     * The track list exposed by {@link #tracks()}.
     */
    private final List<Track> tracks;

    /**
     * The container metadata.
     */
    private final Map<String, String> metadata;

    /**
     * The duration in microseconds, exact or estimated, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * The packets split from the current frame and not yet returned.
     */
    private final ArrayDeque<Packet> pending;

    /**
     * The scratch buffer for header reads.
     */
    private final byte[] headerBytes;

    /**
     * The search window used while resynchronizing.
     */
    private final byte[] window;

    /**
     * The read-ahead window that serves header probes, resynchronization searches and frame reads.
     *
     * @implNote This implementation makes it twice {@link #WINDOW_SIZE}, so a search window and the
     * probes of the frames it finds are served by one read-ahead window, and the walk that follows
     * the first search starts inside what the search already read.
     */
    private final ReadAheadCache cache;

    /**
     * The array behind an in-memory source, whose headers are parsed in place, or {@code null}.
     */
    private final ArrayBacked backing;

    /**
     * The offset of the next frame.
     */
    private long position;

    /**
     * The timestamp of the next raw data block.
     */
    private long timestamp;

    /**
     * Whether the end of the audio was reached, or is known to lie right after the packets in
     * {@link #pending}.
     */
    private boolean finished;

    /**
     * The offset of the frame found by the last successful {@link #frameAt(long)}.
     */
    private long locatedStart;

    /**
     * The packed header of the frame found by the last successful {@link #frameAt(long)}.
     */
    private int locatedWord;

    /**
     * The packets of the frame the construction-time walk is keeping, before they join
     * {@link #pending}.
     */
    private final ArrayDeque<Packet> kept;

    /**
     * The offset of the first frame.
     */
    private long firstFrame;

    /**
     * The index of every sixteenth frame, built on the first exact seek, or {@code null}.
     */
    private SeekIndex index;

    /**
     * The number of samples the walk that built {@link #index} counted.
     */
    private long indexSamples;

    /**
     * The offset after the last frame the walk that built {@link #index} found.
     */
    private long indexEnd;

    /**
     * The chapters of the ID3v2 tags.
     */
    private List<Chapter> chapters;

    /**
     * The pictures of the ID3v2 tags.
     */
    private List<AttachedPicture> pictures;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if no ADTS frame is found
     * @throws MediaException.Io if reading fails
     */
    public AdtsDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if no ADTS frame is found
     * @throws MediaException.Io if reading fails
     */
    public AdtsDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private AdtsDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        this.headerBytes = new byte[9];
        this.window = new byte[WINDOW_SIZE];
        this.cache = new ReadAheadCache(source, 2 * WINDOW_SIZE, MAX_FRAME_LENGTH);
        this.backing = ArrayBacked.of(source);
        this.kept = new ArrayDeque<>(4);
        try {
            var tags = new LinkedHashMap<String, String>();
            var size = source.size();
            var annotations = new Annotations();
            var leading = Id3v2.read(source, 0, size < 0 ? Long.MAX_VALUE : size, tags, annotations);
            var start = leading.end();
            if (size >= 0 && ForwardOnlySource.isCheapToReach(source, size)) {
                var tail = new TrailingTags.Tail(source, start);
                tail.load(size);
                var trailing = TrailingTags.regions(tail, start, size);
                this.audioEnd = trailing.isEmpty() ? size : trailing.getFirst().offset();
                var located = TrailingTags.seekRegions(tail, leading.seekTarget(), start, audioEnd);
                TrailingTags.readMetadata(tail, located, trailing, tags, annotations, Id3v2.Merge.REPLACE);
            } else {
                this.audioEnd = size < 0 ? -1 : size;
            }
            this.pictures = annotations.pictures();
            var first = synchronize(start, 0);
            if (first < 0) {
                throw new MediaException.Malformed("no ADTS frame found");
            }
            this.reference = readWord(first);
            this.firstFrame = first;
            var header = Header.of(reference);
            this.position = first;
            this.metadata = Collections.unmodifiableMap(tags);
            var sampleRate = AacRates.rate(header.frequencyIndex);
            var config = audioSpecificConfig(header, first, 0, false);
            LimitChecks.checkChannels(limits, Math.max(1, header.channelConfiguration == 7 ? 8 : header.channelConfiguration));
            long samples = -1;
            long micros;
            var budget = SegmentBacked.isInMemory(source) ? 0 : Math.min(MAX_KEPT_BYTES, limits.maxBytes());
            this.pending = new ArrayDeque<>(expectedKept(first, header.frameLength, budget));
            if (audioEnd >= 0 && audioEnd - first <= MAX_SCANNED_BYTES && ForwardOnlySource.isCheapToReach(source, audioEnd)) {
                samples = countSamples(first, budget);
                micros = Rational.timeBaseOf(sampleRate).toMicros(samples);
            } else {
                micros = estimateMicros(first, sampleRate, budget);
            }
            this.durationMicros = micros;
            this.chapters = annotations.chapters(micros);
            var described = AudioTrack.builder().id(TRACK_ID).codec(Codec.AAC).timeBase(Rational.timeBaseOf(sampleRate))
                    .duration(samples)
                    .codecConfig(config)
                    .sampleRate(sampleRate)
                    .layout(layout(header.channelConfiguration).orElse(null))
                    .build();
            if (header.profile == 1 && sampleRate <= AacExtensions.MAX_IMPLICIT_CORE_RATE) {
                described = detectExtensions(described, header, first);
            }
            this.track = described;
            this.tracks = List.of(track);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid ADTS stream", exception);
        }
    }

    /**
     * Holds the fields of an ADTS header that matter for framing.
     *
     * @param mpeg2 whether the ID bit signals MPEG-2
     * @param crc whether the header carries CRC words
     * @param profile the profile, the object type minus one
     * @param frequencyIndex the sampling frequency index
     * @param channelConfiguration the channel configuration
     * @param frameLength the frame length in bytes, header included
     * @param blocks the number of raw data blocks in the frame
     */
    private record Header(boolean mpeg2, boolean crc, int profile, int frequencyIndex, int channelConfiguration,
                          int frameLength, int blocks) {
        /**
         * Returns the size of the header, including the CRC word and block position table.
         *
         * @return the header size in bytes
         */
        int size() {
            if (!crc) {
                return 7;
            }
            return blocks == 1 ? 9 : 7 + 2 * (blocks - 1) + 2;
        }

        /**
         * Unpacks a header packed by {@link #parseWord(byte[], int)}.
         *
         * @param word the packed header, not {@code 0}
         * @return the header
         */
        static Header of(int word) {
            return new Header((word & MPEG2_BIT) != 0, isCrc(word), (word >>> PROFILE_SHIFT) & 3,
                    (word >>> FREQUENCY_SHIFT) & 0x0F, (word >>> CHANNELS_SHIFT) & 7, AdtsDemuxer.frameLength(word),
                    AdtsDemuxer.blocks(word));
        }
    }

    /**
     * The bit of a packed header holding the CRC-presence flag.
     */
    private static final int CRC_BIT = 1 << 15;

    /**
     * The bit of a packed header holding the MPEG-2 flag.
     */
    private static final int MPEG2_BIT = 1 << 16;

    /**
     * The position of the profile in a packed header.
     */
    private static final int PROFILE_SHIFT = 17;

    /**
     * The position of the sampling frequency index in a packed header.
     */
    private static final int FREQUENCY_SHIFT = 19;

    /**
     * The position of the channel configuration in a packed header.
     */
    private static final int CHANNELS_SHIFT = 23;

    /**
     * The bits of a packed header that must match between frames of one stream: MPEG version,
     * profile, sampling frequency index and channel configuration.
     */
    private static final int STREAM_BITS = 0x3FF << 16;

    /**
     * Parses an ADTS header into a packed word.
     *
     * <p>The word holds the frame length in bits 0 to 12, the number of raw data blocks minus one
     * in bits 13 and 14, the CRC-presence flag in bit 15, the MPEG-2 flag in bit 16, the profile
     * in bits 17 and 18, the sampling frequency index in bits 19 to 22 and the channel
     * configuration in bits 23 to 25; a valid header is never zero, since its frame length
     * exceeds its own size.
     *
     * @param data the bytes
     * @param offset the index of the syncword
     * @return the packed header, or {@code 0} when the bytes are not a valid ADTS header
     */
    private static int parseWord(byte[] data, int offset) {
        var b0 = data[offset] & 0xFF;
        var b1 = data[offset + 1] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xF6) != 0xF0) {
            return 0;
        }
        var b2 = data[offset + 2] & 0xFF;
        var b3 = data[offset + 3] & 0xFF;
        var b4 = data[offset + 4] & 0xFF;
        var b5 = data[offset + 5] & 0xFF;
        var b6 = data[offset + 6] & 0xFF;
        var frequencyIndex = (b2 >> 2) & 0x0F;
        if (AacRates.rate(frequencyIndex) == 0) {
            return 0;
        }
        var channels = ((b2 & 1) << 2) | (b3 >> 6);
        var frameLength = ((b3 & 3) << 11) | (b4 << 3) | (b5 >> 5);
        var blocks = (b6 & 3) + 1;
        var word = frameLength | (blocks - 1) << 13 | ((b1 & 1) == 0 ? CRC_BIT : 0) | ((b1 & 0x08) != 0 ? MPEG2_BIT : 0)
                | (b2 >> 6) << PROFILE_SHIFT | frequencyIndex << FREQUENCY_SHIFT | channels << CHANNELS_SHIFT;
        if (frameLength <= headerSize(word) + (isCrc(word) && blocks > 1 ? 2 * blocks : 0)) {
            return 0;
        }
        return word;
    }

    /**
     * Returns the frame length of a packed header.
     *
     * @param word the packed header
     * @return the frame length in bytes, header included
     */
    private static int frameLength(int word) {
        return word & 0x1FFF;
    }

    /**
     * Returns the number of raw data blocks of a packed header.
     *
     * @param word the packed header
     * @return the block count, from 1 to 4
     */
    private static int blocks(int word) {
        return ((word >>> 13) & 3) + 1;
    }

    /**
     * Returns whether a packed header carries CRC words.
     *
     * @param word the packed header
     * @return {@code true} when the protection-absent bit is clear
     */
    private static boolean isCrc(int word) {
        return (word & CRC_BIT) != 0;
    }

    /**
     * Returns the size of a packed header, including the CRC word and block position table.
     *
     * @param word the packed header
     * @return the header size in bytes
     */
    private static int headerSize(int word) {
        if (!isCrc(word)) {
            return 7;
        }
        var blocks = blocks(word);
        return blocks == 1 ? 9 : 7 + 2 * (blocks - 1) + 2;
    }

    /**
     * Returns whether two packed headers describe the same stream.
     *
     * @param word a packed header
     * @param other another packed header
     * @return {@code true} when version, profile, frequency and channels match
     */
    private static boolean consistent(int word, int other) {
        return ((word ^ other) & STREAM_BITS) == 0;
    }

    /**
     * Reads and parses the header at an offset.
     *
     * @param offset the offset of the syncword
     * @return the packed header, or {@code 0} when the bytes there are not a valid header or the
     * audio ends first
     */
    private int readWord(long offset) {
        if (audioEnd >= 0 && audioEnd - offset < 7) {
            return 0;
        }
        if (backing != null) {
            return offset + 7 > source.size() ? 0 : parseWord(backing.array(), backing.arrayOffset() + (int) offset);
        }
        var done = 0;
        while (done < 7) {
            var read = cache.read(offset + done, headerBytes, done, 7 - done);
            if (read <= 0) {
                return 0;
            }
            done += read;
        }
        return parseWord(headerBytes, 0);
    }

    /**
     * Returns whether a frame at {@code offset} is confirmed by what follows it.
     *
     * @param offset the frame offset
     * @param word the packed frame header
     * @return {@code true} when the frame ends exactly at the end of the audio or is followed by a
     * consistent header
     */
    private boolean confirmed(long offset, int word) {
        var next = offset + frameLength(word);
        if (audioEnd >= 0 && next >= audioEnd) {
            return next == audioEnd;
        }
        var following = readWord(next);
        if (following == 0) {
            return audioEnd < 0 && cache.read(next, headerBytes, 0, 1) <= 0;
        }
        return consistent(following, word);
    }

    /**
     * Searches for the first confirmed frame at or after {@code from}.
     *
     * @param from the offset where the search starts
     * @param reference the packed header later frames must be consistent with, or {@code 0} while
     * locating the first frame
     * @return the offset of the frame, or {@code -1} when none is found before the end
     */
    private long synchronize(long from, int reference) {
        var windowStart = from;
        while (true) {
            var length = audioEnd < 0 ? WINDOW_SIZE : (int) Math.min(WINDOW_SIZE, audioEnd - windowStart);
            if (length < 7) {
                return -1;
            }
            var read = cache.readUpTo(windowStart, window, 0, length);
            if (read < 7) {
                return -1;
            }
            for (var i = 0; i + 7 <= read; i++) {
                if (window[i] != (byte) 0xFF) {
                    continue;
                }
                var word = parseWord(window, i);
                if (word == 0 || (reference != 0 && !consistent(word, reference))) {
                    continue;
                }
                var offset = windowStart + i;
                if (confirmed(offset, word)) {
                    return offset;
                }
            }
            if (read < length) {
                return -1;
            }
            windowStart += read - 6;
        }
    }

    /**
     * Builds the {@code AudioSpecificConfig} of the stream.
     *
     * @param header the first header
     * @param offset the offset of the first frame
     * @param outputRate the output rate of a detected spectral band replication, or 0
     * @param stereo whether detected parametric stereo turns a mono core into stereo
     * @return the configuration bytes
     */
    private byte[] audioSpecificConfig(Header header, long offset, int outputRate, boolean stereo) {
        var writer = new MsbBitWriter(64);
        writer.write(header.profile + 1, 5);
        writer.write(header.frequencyIndex, 4);
        writer.write(header.channelConfiguration, 4);
        writer.write(0, 3);
        if (header.channelConfiguration == 0) {
            copyProgramConfig(header, offset, writer);
        }
        if (outputRate > 0) {
            writer.write(SYNC_EXTENSION_SBR, 11);
            writer.write(5, 5);
            writer.write(1, 1);
            var index = AacRates.index(outputRate);
            if (index >= 0) {
                writer.write(index, 4);
            } else {
                writer.write(15, 4);
                writer.write(outputRate, 24);
            }
            if (stereo) {
                writer.write(SYNC_EXTENSION_PS, 11);
                writer.write(1, 1);
            }
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /**
     * Decodes the first frame to detect implicitly signaled HE-AAC, and returns the track
     * description that declares it.
     *
     * @param track the description from the header
     * @param header the first header
     * @param first the offset of the first frame
     * @return the description with the output rate, layout and explicit signaling of the
     * extensions, or {@code track} when none is detected
     */
    private AudioTrack detectExtensions(AudioTrack track, Header header, long first) {
        var headerSize = headerSize(reference);
        var payload = new byte[header.frameLength - headerSize];
        if (cache.readUpTo(first + headerSize, payload, 0, payload.length) < payload.length) {
            return track;
        }
        var format = AacExtensions.decodeFormat(track, payload);
        if (format == null || format.sampleRate() <= AacRates.rate(header.frequencyIndex)) {
            return track;
        }
        var stereo = header.channelConfiguration == 1 && format.layout().channels() == 2;
        return track.toBuilder()
                .codecConfig(audioSpecificConfig(header, first, format.sampleRate(), stereo))
                .sampleRate(format.sampleRate())
                .layout(format.layout())
                .build();
    }

    /**
     * Copies the program configuration element opening the first raw data block into an
     * {@code AudioSpecificConfig} being written.
     *
     * <p>The element's fields are re-emitted rather than copied bit for bit, because its
     * internal byte alignment is relative to the enclosing structure and differs between a raw
     * data block and an {@code AudioSpecificConfig}.
     *
     * @param header the frame header
     * @param offset the frame offset
     * @param writer the configuration writer, positioned after the {@code GASpecificConfig} flags
     */
    private void copyProgramConfig(Header header, long offset, MsbBitWriter writer) {
        var frame = new byte[header.frameLength];
        source.readFully(offset, frame, 0, frame.length);
        var start = header.size();
        var reader = new MsbBitReader(frame, start, frame.length - start);
        if (reader.read(3) != 5) {
            return;
        }
        var tag = reader.read(4);
        var objectType = reader.read(2);
        var frequencyIndex = reader.read(4);
        var front = reader.read(4);
        var side = reader.read(4);
        var back = reader.read(4);
        var lfe = reader.read(2);
        var assoc = reader.read(3);
        var cc = reader.read(4);
        writer.write(tag, 4);
        writer.write(objectType, 2);
        writer.write(frequencyIndex, 4);
        writer.write(front, 4);
        writer.write(side, 4);
        writer.write(back, 4);
        writer.write(lfe, 2);
        writer.write(assoc, 3);
        writer.write(cc, 4);
        for (var i = 0; i < 2; i++) {
            var present = reader.read(1);
            writer.write(present, 1);
            if (present != 0) {
                writer.write(reader.read(4), 4);
            }
        }
        var matrix = reader.read(1);
        writer.write(matrix, 1);
        if (matrix != 0) {
            writer.write(reader.read(3), 3);
        }
        var elements = front + side + back;
        for (var i = 0; i < elements; i++) {
            writer.write(reader.read(5), 5);
        }
        for (var i = 0; i < lfe + assoc; i++) {
            writer.write(reader.read(4), 4);
        }
        for (var i = 0; i < cc; i++) {
            writer.write(reader.read(5), 5);
        }
        var misaligned = (int) (reader.bitPosition() & 7);
        if (misaligned != 0) {
            reader.read(8 - misaligned);
        }
        writer.alignWithZeros();
        var comment = reader.read(8);
        writer.write(comment, 8);
        for (var i = 0; i < comment; i++) {
            writer.write(reader.read(8), 8);
        }
        reader.checkNotOverrun();
    }

    /**
     * Returns the layout of a channel configuration in WAVE order.
     *
     * @param configuration the channel configuration
     * @return the layout, or empty for configuration zero
     */
    private static Optional<ChannelLayout> layout(int configuration) {
        return Optional.ofNullable(AudioConfigs.aacLayout(configuration));
    }

    /**
     * Estimates how many packets the construction-time walk keeps, so that the queue holding them
     * is allocated once at about the right size instead of growing by doubling.
     *
     * <p>The estimate assumes every frame is as long as the first and holds one raw data block,
     * adds an eighth for variable frame sizes, and is kept between {@value #MIN_QUEUE}, the
     * capacity that holds the packets of one frame, and {@value #MAX_EXPECTED_KEPT}; the queue
     * still grows past it when needed.
     *
     * @param first the offset of the first frame
     * @param frameLength the length of the first frame
     * @param budget the bytes of packets that may be kept, {@code 0} to keep none
     * @return the initial queue capacity
     */
    private int expectedKept(long first, int frameLength, long budget) {
        var frames = budget / (frameLength + PACKET_OVERHEAD);
        if (audioEnd >= 0) {
            frames = Math.min(frames, (audioEnd - first) / frameLength + 1);
        }
        return (int) Math.clamp(frames + frames / 8, MIN_QUEUE, MAX_EXPECTED_KEPT);
    }

    /**
     * Counts the samples of every frame from {@code first} to the end of the audio, keeping the
     * packets of the frames visited first.
     *
     * <p>Packets are kept, as {@link #keep(long, int, long, long)} describes, until {@code budget}
     * bytes are used or a frame cannot be kept; when every frame up to the end of the audio is
     * kept, the demuxer is marked finished, since {@link #readFrame()} would find the same end.
     *
     * @param first the offset of the first frame, where {@link #position} stands
     * @param budget the bytes of packets that may be kept, {@code 0} to keep none
     * @return the number of samples
     */
    private long countSamples(long first, long budget) {
        var offset = first;
        var samples = 0L;
        var used = budget > 0 ? 0L : -1L;
        while (true) {
            if (!frameAt(offset)) {
                finished = used >= 0;
                return samples;
            }
            var frameStart = locatedStart;
            var word = locatedWord;
            if (audioEnd >= 0 && frameStart + frameLength(word) > audioEnd) {
                finished = used >= 0;
                return samples;
            }
            samples += 1024L * blocks(word);
            if (used >= 0) {
                used = keep(frameStart, word, used, budget);
            }
            offset = frameStart + frameLength(word);
        }
    }

    /**
     * Estimates the duration from the average size of the first frames, keeping their packets.
     *
     * <p>Packets are kept as in {@link #countSamples(long, long)}; when the estimate stops before
     * the end of the audio, {@link #read()} continues from the first frame it did not keep.
     *
     * @param first the offset of the first frame, where {@link #position} stands
     * @param sampleRate the sampling rate
     * @param budget the bytes of packets that may be kept, {@code 0} to keep none
     * @return the estimate in microseconds, or {@code -1} when the source size is unknown
     */
    private long estimateMicros(long first, int sampleRate, long budget) {
        if (audioEnd < 0) {
            return -1;
        }
        var offset = first;
        var samples = 0L;
        var used = budget > 0 ? 0L : -1L;
        for (var i = 0; i < ESTIMATE_FRAMES; i++) {
            if (!frameAt(offset)) {
                finished = used >= 0;
                break;
            }
            var frameStart = locatedStart;
            var word = locatedWord;
            samples += 1024L * blocks(word);
            if (used >= 0) {
                if (frameStart + frameLength(word) > audioEnd) {
                    finished = true;
                    used = -1;
                } else {
                    used = keep(frameStart, word, used, budget);
                }
            }
            offset = frameStart + frameLength(word);
        }
        var bytes = offset - first;
        if (bytes <= 0) {
            return -1;
        }
        var total = (double) samples * (audioEnd - first) / bytes;
        return Math.round(total * 1_000_000 / sampleRate);
    }

    /**
     * Splits a frame visited by the construction-time walk into packets queued for
     * {@link #read()}, exactly as {@link #readFrame()} would when it reached the frame.
     *
     * <p>The frame is kept only when it fits the remaining budget and {@link #readFrame()} would
     * return all of its packets without failing; otherwise nothing is queued and keeping stops,
     * leaving {@link #position} and {@link #timestamp} at this frame, so {@link #readFrame()}
     * meets the frame itself, with the same outcome, once the kept packets are returned.
     *
     * @param start the frame offset
     * @param word the packed frame header
     * @param used the bytes of the budget already used
     * @param budget the bytes of packets that may be kept
     * @return the bytes of the budget used after keeping the frame, or {@code -1} when keeping
     * stops
     */
    private long keep(long start, int word, long used, long budget) {
        var cost = used + frameLength(word) + (long) PACKET_OVERHEAD * blocks(word);
        if (cost > budget) {
            return -1;
        }
        var savedPosition = position;
        var savedTimestamp = timestamp;
        kept.clear();
        boolean complete;
        try {
            complete = split(start, word, kept);
        } catch (RuntimeException exception) {
            complete = false;
        }
        if (!complete) {
            kept.clear();
            position = savedPosition;
            timestamp = savedTimestamp;
            return -1;
        }
        pending.addAll(kept);
        kept.clear();
        ForwardOnlySource.release(source, position - MAX_FRAME_LENGTH);
        return cost;
    }

    /**
     * Returns the frame at {@code offset}, resynchronizing when the bytes there are not a
     * consistent header.
     *
     * <p>A consistent header that is not followed by another one (or the end of the audio) may be
     * garbage that happens to look like a header; it is kept unless a confirmed frame starts
     * inside the bytes it claims, in which case reading continues at that frame.
     *
     * <p>The frame found is recorded in {@link #locatedStart} and {@link #locatedWord}.
     *
     * @param offset the expected offset of the next frame
     * @return {@code false} at the end of the audio
     */
    private boolean frameAt(long offset) {
        if (audioEnd >= 0 && audioEnd - offset < 7) {
            return false;
        }
        var word = readWord(offset);
        if (word == 0 || !consistent(word, reference)) {
            var next = synchronize(offset + 1, reference);
            if (next < 0) {
                return false;
            }
            return locate(next, readWord(next));
        }
        if (!confirmed(offset, word)) {
            var next = synchronize(offset + 1, reference);
            if (next >= 0 && next < offset + frameLength(word)) {
                return locate(next, readWord(next));
            }
        }
        return locate(offset, word);
    }

    /**
     * Records the frame {@link #frameAt(long)} found.
     *
     * @param start the frame offset
     * @param word the packed frame header
     * @return {@code true}
     * @throws MediaException.Malformed if the header cannot be read again
     */
    private boolean locate(long start, int word) {
        if (word == 0) {
            throw new MediaException.Malformed("ADTS header at offset " + start + " vanished");
        }
        locatedStart = start;
        locatedWord = word;
        return true;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.ADTS;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    @Override
    public List<Chapter> chapters() {
        return chapters;
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return pictures;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} for a stream of known size
     */
    @Override
    public boolean isSeekable() {
        return audioEnd >= 0;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Unsupported if the size of the source is unknown
     */
    @Override
    public void seek(long micros) {
        if (audioEnd < 0) {
            throw new MediaException.Unsupported("seek", "an ADTS stream of unknown size cannot seek");
        }
        try {
            // the time base counts samples of the core rate, which an HE-AAC refinement does not change
            var rate = track.timeBase().denominator();
            var target = micros <= 0 ? 0 : micros > Long.MAX_VALUE / rate ? Long.MAX_VALUE / 4 : micros * rate / 1_000_000;
            long offset;
            long time;
            if (audioEnd - firstFrame <= MAX_SCANNED_BYTES) {
                if (index == null) {
                    buildIndex();
                }
                if (target >= indexSamples || index.size() == 0) {
                    pending.clear();
                    position = indexEnd;
                    timestamp = indexSamples;
                    finished = true;
                    return;
                }
                var entry = Math.max(0, index.floor(target));
                offset = index.offset(entry);
                time = index.timestamp(entry);
            } else {
                var average = averageFrameBytes();
                var frames = target / 1024;
                offset = Math.min(audioEnd, firstFrame + Math.round(frames * average));
                var found = offset <= firstFrame ? firstFrame : synchronize(offset, reference);
                if (found < 0) {
                    pending.clear();
                    position = audioEnd;
                    finished = true;
                    return;
                }
                offset = found;
                time = Math.round((offset - firstFrame) / average) * 1024;
            }
            while (frameAt(offset)) {
                var start = locatedStart;
                var word = locatedWord;
                var samples = 1024L * blocks(word);
                if (time + samples > target || (audioEnd >= 0 && start + frameLength(word) + 7 > audioEnd)) {
                    offset = start;
                    break;
                }
                offset = start + frameLength(word);
                time += samples;
            }
            pending.clear();
            position = offset;
            timestamp = time;
            finished = false;
            while (pending.isEmpty() && readFrame()) {
                while (!pending.isEmpty() && pending.peek().pts() + pending.peek().duration() <= target
                        && pending.size() > 1) {
                    pending.poll();
                }
            }
            if (pending.isEmpty()) {
                finished = true;
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid ADTS frame", exception);
        }
    }

    /**
     * Walks every frame header from the first frame, indexing every sixteenth one.
     */
    private void buildIndex() {
        var built = new SeekIndex();
        var offset = firstFrame;
        var samples = 0L;
        var count = 0L;
        while (frameAt(offset)) {
            var start = locatedStart;
            var word = locatedWord;
            if (start + frameLength(word) > audioEnd) {
                break;
            }
            if (count % 16 == 0) {
                built.add(samples, start, 0, 0);
            }
            samples += 1024L * blocks(word);
            offset = start + frameLength(word);
            count++;
        }
        indexSamples = samples;
        indexEnd = offset;
        index = built;
    }

    /**
     * Returns the average size of the first frames.
     *
     * @return the average frame size in bytes, at least one
     */
    private double averageFrameBytes() {
        var offset = firstFrame;
        var frames = 0;
        while (frames < ESTIMATE_FRAMES && frameAt(offset)) {
            offset = locatedStart + frameLength(locatedWord);
            frames++;
        }
        return frames == 0 ? 1 : Math.max(1.0, (double) (offset - firstFrame) / frames);
    }

    @Override
    public Packet read() {
        try {
            while (pending.isEmpty()) {
                if (finished || !readFrame()) {
                    finished = true;
                    return null;
                }
            }
            return pending.poll();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid ADTS frame", exception);
        }
    }

    /**
     * Reads the next frame and queues its packets.
     *
     * @return {@code false} at the end of the audio
     */
    private boolean readFrame() {
        if (!frameAt(position)) {
            return false;
        }
        var start = locatedStart;
        var word = locatedWord;
        if (audioEnd >= 0 && start + frameLength(word) > audioEnd) {
            return false;
        }
        if (!split(start, word, pending)) {
            return false;
        }
        ForwardOnlySource.release(source, position - MAX_FRAME_LENGTH);
        return true;
    }

    /**
     * Reads a located frame, appends its packets to a queue, and advances {@link #position} past
     * it and {@link #timestamp} by its duration.
     *
     * <p>When the frame holds several raw data blocks and one of their positions is invalid, the
     * packets of the blocks before it are already appended when the exception is thrown.
     *
     * @param start the frame offset
     * @param word the packed frame header
     * @param out the queue receiving the packets
     * @return {@code false}, with nothing appended and nothing advanced, when the source ends
     * inside the frame
     * @throws MediaException.Malformed if a raw data block position is invalid
     */
    private boolean split(long start, int word, ArrayDeque<Packet> out) {
        var frameLength = frameLength(word);
        var blocks = blocks(word);
        var headerSize = headerSize(word);
        if (blocks == 1 || !isCrc(word)) {
            // the payload follows the header bytes that readWord already found, so the source
            // ending inside the frame is detected by this read alone
            var payload = new byte[frameLength - headerSize];
            if (cache.readUpTo(start + headerSize, payload, 0, payload.length) < payload.length) {
                return false;
            }
            position = start + frameLength;
            var duration = 1024L * blocks;
            out.add(new Packet(TRACK_ID, payload, timestamp, timestamp, duration, true));
            timestamp += duration;
            return true;
        }
        var frame = new byte[frameLength];
        if (cache.readUpTo(start, frame, 0, frame.length) < frame.length) {
            return false;
        }
        position = start + frameLength;
        // ISO/IEC 14496-3 1.A.3.2.2: raw_data_block_position[i] is the offset of block i from the start of the frame
        var starts = new int[blocks + 1];
        starts[0] = headerSize;
        for (var i = 1; i < blocks; i++) {
            starts[i] = ((frame[7 + 2 * (i - 1)] & 0xFF) << 8) | (frame[8 + 2 * (i - 1)] & 0xFF);
        }
        starts[blocks] = frame.length;
        for (var i = 0; i < blocks; i++) {
            var from = starts[i];
            var to = starts[i + 1] - 2;
            if (from < headerSize || to <= from || to > frame.length) {
                throw new MediaException.Malformed("invalid ADTS raw data block position");
            }
            out.add(new Packet(TRACK_ID, Arrays.copyOfRange(frame, from, to), timestamp, timestamp,
                    1024, true));
            timestamp += 1024;
        }
        return true;
    }
}
