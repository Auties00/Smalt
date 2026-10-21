package com.github.auties00.smalt.container.flac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.header.Crc16;
import com.github.auties00.smalt.internal.header.FlacFrameHeader;
import com.github.auties00.smalt.internal.header.FlacStreamInfo;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.container.mpeg.internal.TrailingTags;
import com.github.auties00.smalt.internal.tags.FlacPicture;
import com.github.auties00.smalt.internal.io.ArrayBacked;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Splits a native FLAC stream into frames.
 *
 * <p>The stream may start with ID3v2 tags, which are skipped, and end with ID3v1, APE, Lyrics3 or
 * appended ID3v2 tags, which are excluded from the audio and whose fields complete the metadata
 * after the Vorbis comments (an appended ID3v2 tag first, then the APE tag, then the ID3v1 tag;
 * APE cover art counts only when no other picture was found). A regular file then has the {@code "fLaC"}
 * marker and a chain of metadata blocks whose first is {@code STREAMINFO}; the single track's
 * {@link Track#codecConfig()} is {@code "fLaC"} followed by every metadata block except
 * {@code PADDING} and {@code PICTURE}, with the last-block flag set on the final one, and its
 * {@code VORBIS_COMMENT} tags become {@link #metadata()}. A stream without the marker (a
 * captured multicast stream) starts at the first frame header that a second, consistent frame
 * confirms; any bytes before it are skipped, and its parameters come from that first header.
 *
 * <p>FLAC frames carry no length, so a frame ends where the next valid frame header starts. A
 * candidate next header must pass its CRC-8, continue the frame or sample numbering of the
 * current frame, and either code exactly the current format or complete a frame whose trailing
 * CRC-16 matches; when no candidate qualifies before the end of the input or the largest size a
 * frame of the current format can have, a candidate meeting the numbering or the CRC-16 check
 * alone is used, and the last frame runs to the end of the audio, capped at the declared maximum
 * frame size (bytes trailing it are ignored by the decoder, which checks the CRC-16 at the frame's
 * own end). Frame contents, including the CRC-16 of frames accepted without it, are verified by
 * the decoder. Every packet holds exactly one frame; its timestamp, in samples, is the first
 * frame's position (frame number times block size for fixed blocking, sample number for variable
 * blocking) plus the block sizes of the frames before it.
 *
 * <p>{@link AudioTrack#duration()} is the {@code STREAMINFO} total sample count, or {@code -1}
 * when the stream does not declare it. Structural faults raise {@link MediaException.Malformed}:
 * a missing or misplaced {@code STREAMINFO}, a metadata block running past the end of the file,
 * an invalid {@code VORBIS_COMMENT}, a first frame that disagrees with {@code STREAMINFO}, a
 * frame of the declared format larger than the declared maximum frame size or block size, a
 * stream longer than the declared total, and a block size below 16 anywhere but in the last
 * frame.
 *
 * <p>{@code PICTURE} blocks and ID3v2 {@code APIC} frames become {@link #attachedPictures()}, and
 * the {@code CHAPTERxxx} Vorbis comments become {@link #chapters()} instead of tags.
 *
 * <p>A stream of known size can seek. {@link #seek(long)} starts from the last {@code SEEKTABLE}
 * point at or before the target, when the stream has one, bisects the remaining byte range on the
 * frame and sample numbers of the frame headers it resynchronizes to, and then steps frame by
 * frame to the frame holding the target sample. Every frame is a key frame, so the next packet is
 * the frame that holds the sample displayed at the target, with its exact timestamp.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every frame which
 * bytes are no longer needed, and its trailing tags are read only when the whole input fits the
 * source's memory budget.
 *
 * @implNote This implementation reads an in-memory source ({@link ArrayBacked}) in place: the frame buffer becomes a
 * window over the source's array whose bounds move exactly as a copied buffer's would, including its growth checks
 * against the limits, so no byte is copied before the packet copy.
 */
public final class FlacDemuxer implements Demuxer {
    /**
     * The size of each read from the source.
     */
    private static final int READ_CHUNK = 64 * 1024;

    /**
     * Reads eight bytes at once for the sync search; the byte order is irrelevant to it.
     */
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The number of bytes searched for the first frame of a stream without the {@code "fLaC"}
     * marker.
     */
    private static final int FIRST_FRAME_SEARCH = 1 << 20;

    /**
     * The smallest distance in bytes between the ends of a frame header and of its frame: one
     * byte of subframe and the two-byte CRC-16.
     */
    private static final int MIN_FRAME_BODY = 3;

    /**
     * The result of a strict frame scan that found no confirming boundary.
     */
    private static final long UNCONFIRMED = -2;

    /**
     * The source being read.
     */
    private final MediaSource source;

    /**
     * The allocation bounds.
     */
    private final MediaLimits limits;

    /**
     * The single track.
     */
    private final List<Track> tracks;

    /**
     * The {@code VORBIS_COMMENT} and ID3 tags.
     */
    private final Map<String, String> metadata;

    /**
     * The stream parameters, or {@code null} for a stream without metadata.
     */
    private final FlacStreamInfo streamInfo;

    /**
     * The presentation duration in microseconds, or {@code -1} when unknown.
     */
    private final long durationMicros;

    /**
     * The header of the frame that the next packet returns.
     */
    private final FlacFrameHeader current;

    /**
     * The header being validated as the start of the next frame.
     */
    private final FlacFrameHeader candidate;

    /**
     * The best fallback next header found while scanning.
     */
    private final FlacFrameHeader fallback;

    /**
     * The offset of the end of the audio, lowered when the source ends early.
     */
    private long audioEnd;

    /**
     * The array holding the buffered bytes: the demuxer's own buffer, or the array of an
     * in-memory source.
     */
    private byte[] data;

    /**
     * The index in {@link #data} of buffer index zero; always zero for the demuxer's own buffer.
     */
    private int base;

    /**
     * The buffer capacity in bytes.
     */
    private int capacity;

    /**
     * The array behind an in-memory source, or {@code null} when the demuxer copies into a
     * buffer of its own.
     */
    private final ArrayBacked backing;

    /**
     * The source offset of buffer index zero.
     */
    private long bufferStart;

    /**
     * The number of valid bytes in the buffer.
     */
    private int bufferLength;

    /**
     * The offset of the frame that the next packet returns.
     */
    private long frameStart;

    /**
     * Whether a frame remains to be returned.
     */
    private boolean hasFrame;

    /**
     * The timestamp of the next packet in samples.
     */
    private long nextPts;

    /**
     * The number of samples returned so far.
     */
    private long samplesRead;

    /**
     * The offset of the first frame, or {@code -1} when the stream has none.
     */
    private long firstFrame = -1;

    /**
     * The block size of every frame but the last of a fixed-blocking stream, which turns frame
     * numbers into sample numbers.
     */
    private int nominalBlockSize;

    /**
     * The sample numbers of the {@code SEEKTABLE} points, in increasing order, or {@code null}.
     */
    private long[] seekSamples;

    /**
     * The frame offsets, relative to the first frame, matching {@link #seekSamples}.
     */
    private long[] seekOffsets;

    /**
     * The chapters and pictures found in the metadata.
     */
    private final Annotations annotations = new Annotations();

    /**
     * The chapters of the stream.
     */
    private final List<Chapter> chapters;

    /**
     * The pictures of the stream.
     */
    private final List<AttachedPicture> pictures;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the stream is not FLAC or its metadata is corrupt
     * @throws MediaException.LimitExceeded if a metadata block or the channel count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public FlacDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the stream is not FLAC or its metadata is corrupt
     * @throws MediaException.LimitExceeded if a metadata block or the channel count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public FlacDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private FlacDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.current = new FlacFrameHeader();
        this.candidate = new FlacFrameHeader();
        this.fallback = new FlacFrameHeader();
        this.backing = ArrayBacked.of(source);
        this.data = backing != null ? backing.array() : new byte[READ_CHUNK];
        this.capacity = READ_CHUNK;
        var tags = new LinkedHashMap<String, String>();
        try {
            var size = source.size();
            this.audioEnd = size < 0 ? Long.MAX_VALUE : size;
            var position = Id3v2.skip(source, 0, audioEnd, tags, annotations);
            TrailingTags.Tail tail = null;
            List<TrailingTags.Region> trailing = List.of();
            if (size >= 0 && ForwardOnlySource.isCheapToReach(source, size)) {
                tail = new TrailingTags.Tail(source, position);
                tail.load(audioEnd);
                trailing = TrailingTags.regions(tail, position, audioEnd);
                audioEnd = trailing.isEmpty() ? audioEnd : trailing.getFirst().offset();
            }
            bufferStart = position;
            rebase();
            byte[] config = null;
            FlacStreamInfo info = null;
            if (ensure(position, 4) >= 4 && at(position) == 'f' && at(position + 1) == 'L' && at(position + 2) == 'a'
                    && at(position + 3) == 'C') {
                var blocks = new ByteArrayOutputStream();
                blocks.writeBytes(new byte[]{'f', 'L', 'a', 'C'});
                position = readMetadata(position + 4, blocks, tags);
                info = streamInfoOf(blocks.toByteArray());
                config = finishConfig(blocks.toByteArray());
                discardBefore(position);
                if (ensure(position, 1) > 0) {
                    if (!parseAt(position, current)) {
                        throw new MediaException.Malformed("no FLAC frame header follows the metadata blocks");
                    }
                    hasFrame = true;
                }
            } else {
                position = findFirstFrame(position);
                hasFrame = true;
            }
            this.streamInfo = info;
            this.frameStart = position;
            if (hasFrame) {
                firstFrame = position;
                nominalBlockSize = info != null && info.minBlockSize() == info.maxBlockSize() ? info.maxBlockSize()
                        : current.blockSize();
            }
            int rate;
            int channels;
            int bitsPerSample;
            if (info != null) {
                rate = info.sampleRate();
                channels = info.channels();
                bitsPerSample = info.bitsPerSample();
                if (hasFrame) {
                    checkFirstFrame(info);
                }
            } else {
                rate = current.sampleRate();
                channels = current.channels();
                bitsPerSample = current.bitsPerSample();
                if (rate == 0 || bitsPerSample == 0) {
                    throw new MediaException.Malformed("FLAC frame defers to an absent STREAMINFO");
                }
            }
            LimitChecks.checkChannels(limits, channels);
            if (hasFrame) {
                nextPts = current.variableBlocking() ? current.number() : current.number() * current.blockSize();
            }
            var duration = info != null && info.totalSamples() > 0 ? info.totalSamples() : -1;
            var timeBase = Rational.timeBaseOf(rate);
            this.durationMicros = duration >= 0 ? timeBase.toMicros(duration) : -1;
            this.tracks = List.of(AudioTrack.builder().codec(Codec.FLAC).timeBase(timeBase).duration(duration)
                    .codecConfig(config)
                    .sampleRate(rate)
                    .layout(ChannelLayout.defaultFor(channels))
                    .bitsPerSample(bitsPerSample)
                    .build());
            if (tail != null) {
                TrailingTags.readMetadata(tail, List.of(), trailing, tags, annotations, Id3v2.Merge.FILL);
            }
            this.metadata = Collections.unmodifiableMap(tags);
            this.chapters = annotations.chapters(durationMicros);
            this.pictures = annotations.pictures();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt FLAC stream", exception);
        }
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.FLAC;
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
    public Packet read() {
        try {
            return nextFrame();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt FLAC stream", exception);
        }
    }

    /**
     * Reads the metadata block chain that follows the {@code "fLaC"} marker.
     *
     * @param position the offset of the first block header
     * @param blocks receives the blocks kept in the codec configuration
     * @param tags receives the {@code VORBIS_COMMENT} tags
     * @return the offset of the first byte after the last block
     * @throws MediaException.Malformed if the chain is structurally invalid
     */
    private long readMetadata(long position, ByteArrayOutputStream blocks, Map<String, String> tags) {
        var first = true;
        var header = new byte[4];
        while (true) {
            if (audioEnd - position < 4) {
                throw new MediaException.Malformed("FLAC metadata ends without a last-block flag");
            }
            source.readFully(position, header, 0, 4);
            var last = (header[0] & 0x80) != 0;
            var type = header[0] & 0x7F;
            var length = (header[1] & 0xFF) << 16 | (header[2] & 0xFF) << 8 | header[3] & 0xFF;
            if (type == 127) {
                throw new MediaException.Malformed("invalid FLAC metadata block type 127");
            }
            if (length > audioEnd - position - 4) {
                throw new MediaException.Malformed("FLAC metadata block of " + length + " bytes runs past the end of the file");
            }
            if (first != (type == 0)) {
                throw new MediaException.Malformed(first
                        ? "FLAC metadata does not start with STREAMINFO"
                        : "FLAC metadata holds a second STREAMINFO");
            }
            if (type == 0 && length != FlacStreamInfo.SIZE) {
                throw new MediaException.Malformed("FLAC STREAMINFO of " + length + " bytes");
            }
            if (type == 6) {
                LimitChecks.checkAllocation(limits, length);
                var body = new byte[length];
                source.readFully(position + 4, body, 0, length);
                annotations.addPicture(FlacPicture.decode(body, 0, length));
            } else if (type != 1) {
                LimitChecks.checkAllocation(limits, (long) blocks.size() + 4 + length);
                var body = new byte[length];
                source.readFully(position + 4, body, 0, length);
                if (type == 4) {
                    Annotations.readVorbisComments(body, 0, length, tags, annotations);
                } else if (type == 3) {
                    readSeekTable(body);
                }
                blocks.write(header[0] & 0x7F);
                blocks.write(header, 1, 3);
                blocks.writeBytes(body);
            }
            first = false;
            position += 4 + length;
            if (last) {
                return position;
            }
        }
    }

    /**
     * Keeps the seek points of a {@code SEEKTABLE} block, dropping placeholders and points out of
     * order.
     *
     * @param body the block body, 18 bytes per point
     */
    private void readSeekTable(byte[] body) {
        var count = body.length / 18;
        var samples = new long[count];
        var offsets = new long[count];
        var kept = 0;
        for (var index = 0; index < count; index++) {
            var sample = ByteArrays.i64be(body, index * 18);
            var offset = ByteArrays.i64be(body, index * 18 + 8);
            if (sample == -1 || sample < 0 || offset < 0 || (kept > 0 && (sample <= samples[kept - 1] || offset < offsets[kept - 1]))) {
                continue;
            }
            samples[kept] = sample;
            offsets[kept] = offset;
            kept++;
        }
        if (kept > 0) {
            seekSamples = Arrays.copyOf(samples, kept);
            seekOffsets = Arrays.copyOf(offsets, kept);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} for a stream of known size that holds a frame
     */
    @Override
    public boolean isSeekable() {
        return firstFrame >= 0 && audioEnd != Long.MAX_VALUE;
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
     * @throws MediaException.Unsupported if the size of the source is unknown or the stream holds no frame
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "this FLAC stream cannot seek: its size is unknown or it holds no frame");
        }
        var savedStart = frameStart;
        var savedHeader = new FlacFrameHeader();
        savedHeader.copyFrom(current);
        var savedPts = nextPts;
        var savedRead = samplesRead;
        var savedHasFrame = hasFrame;
        try {
            var rate = tracks.getFirst() instanceof AudioTrack audio ? audio.sampleRate() : 0;
            var target = micros <= 0 || rate <= 0 ? 0 : micros > Long.MAX_VALUE / rate
                    ? Long.MAX_VALUE / 4 : micros * rate / 1_000_000;
            if (streamInfo != null && streamInfo.totalSamples() > 0 && target >= streamInfo.totalSamples()) {
                hasFrame = false;
                return;
            }
            var low = firstFrame;
            if (seekSamples != null) {
                for (var index = seekSamples.length - 1; index >= 0; index--) {
                    var offset = firstFrame + seekOffsets[index];
                    if (seekSamples[index] <= target && offset < audioEnd && locateFrame(offset, offset + 1) >= 0
                            && frameSample(current) <= target) {
                        low = offset;
                        break;
                    }
                }
            }
            var high = audioEnd;
            var stop = Math.max(64 * 1024, maximumFrameSize());
            while (high - low > stop) {
                var middle = low + (high - low) / 2;
                var found = locateFrame(middle, high);
                if (found < 0 || frameSample(current) > target) {
                    high = middle;
                } else {
                    low = found;
                }
            }
            if (locateFrame(low, low + 1) < 0) {
                throw new MediaException.Malformed("no FLAC frame at offset " + low);
            }
            while (frameSample(current) + current.blockSize() <= target) {
                discardBefore(frameStart);
                var end = scanFrame(false);
                if (end < 0) {
                    hasFrame = false;
                    return;
                }
                frameStart = end;
                current.copyFrom(candidate);
            }
            nextPts = frameSample(current);
            samplesRead = nextPts;
            hasFrame = true;
        } catch (RuntimeException exception) {
            moveBuffer(savedStart);
            frameStart = savedStart;
            current.copyFrom(savedHeader);
            nextPts = savedPts;
            samplesRead = savedRead;
            hasFrame = savedHasFrame;
            if (exception instanceof MediaException media) {
                throw media;
            }
            throw new MediaException.Malformed("corrupt FLAC stream", exception);
        }
    }

    /**
     * Returns the number of the first sample of a frame.
     *
     * @param header the frame header
     * @return the sample number
     */
    private long frameSample(FlacFrameHeader header) {
        return header.variableBlocking() ? header.number() : header.number() * nominalBlockSize;
    }

    /**
     * Moves the frame buffer to start at an offset, discarding what it holds.
     *
     * @param position the offset
     */
    private void moveBuffer(long position) {
        bufferStart = position;
        bufferLength = 0;
        rebase();
    }

    /**
     * Finds the first frame that starts at or after an offset and before a limit, with the checks
     * a first frame passes, and makes it the current frame.
     *
     * @param from the offset where the search starts
     * @param limit the offset the frame must start before
     * @return the frame offset, or {@code -1} when no frame starts in the range
     */
    private long locateFrame(long from, long limit) {
        moveBuffer(from);
        var syncSecond = current.variableBlocking() ? (byte) 0xF9 : (byte) 0xF8;
        var bound = Math.min(limit, audioEnd);
        for (var p = from; p < bound; p++) {
            discardBefore(p);
            if (ensure(p, 2) < 2) {
                return -1;
            }
            if (at(p) != (byte) 0xFF || at(p + 1) != syncSecond) {
                continue;
            }
            if (!parseAt(p, candidate) || candidate.channels() != current.channels()
                    || candidate.sampleRate() != current.sampleRate()
                    || candidate.bitsPerSample() != current.bitsPerSample()) {
                continue;
            }
            var previous = frameStart;
            frameStart = p;
            var header = new FlacFrameHeader();
            header.copyFrom(current);
            current.copyFrom(candidate);
            if (scanFrame(true) != UNCONFIRMED) {
                return p;
            }
            frameStart = previous;
            current.copyFrom(header);
        }
        return -1;
    }

    /**
     * Parses the {@code STREAMINFO} kept at the start of the configuration blocks.
     *
     * @param blocks the marker followed by the kept blocks
     * @return the stream parameters
     */
    private static FlacStreamInfo streamInfoOf(byte[] blocks) {
        return FlacStreamInfo.parse(blocks, 8);
    }

    /**
     * Sets the last-block flag on the final kept metadata block.
     *
     * @param blocks the marker followed by the kept blocks, with every last-block flag clear
     * @return the codec configuration
     */
    private static byte[] finishConfig(byte[] blocks) {
        var position = 4;
        var lastHeader = 4;
        while (position + 4 <= blocks.length) {
            lastHeader = position;
            var length = (blocks[position + 1] & 0xFF) << 16 | (blocks[position + 2] & 0xFF) << 8 | blocks[position + 3] & 0xFF;
            position += 4 + length;
        }
        blocks[lastHeader] |= (byte) 0x80;
        return blocks;
    }

    /**
     * Checks that the first frame agrees with {@code STREAMINFO}.
     *
     * @param info the stream parameters
     * @throws MediaException.Malformed if the sample rate, channel count or sample size differs
     */
    private void checkFirstFrame(FlacStreamInfo info) {
        var rate = current.sampleRate() == 0 ? info.sampleRate() : current.sampleRate();
        var bits = current.bitsPerSample() == 0 ? info.bitsPerSample() : current.bitsPerSample();
        if (rate != info.sampleRate() || current.channels() != info.channels() || bits != info.bitsPerSample()) {
            throw new MediaException.Malformed("first FLAC frame (" + rate + " Hz, " + current.channels() + " channels, "
                    + bits + " bits) disagrees with STREAMINFO (" + info.sampleRate() + " Hz, " + info.channels()
                    + " channels, " + info.bitsPerSample() + " bits)");
        }
    }

    /**
     * Finds the first frame of a stream without the {@code "fLaC"} marker: the first valid header
     * whose frame is followed by a consistent second header, or that runs to the end of the
     * input.
     *
     * @param position the offset where the search starts
     * @return the offset of the first frame, with {@link #current} set to its header
     * @throws MediaException.Malformed if no frame is found within the search window
     */
    private long findFirstFrame(long position) {
        var limit = Math.min(audioEnd, position + FIRST_FRAME_SEARCH);
        for (var p = position; p < limit; p++) {
            discardBefore(p);
            if (ensure(p, 2) < 2) {
                break;
            }
            if (at(p) != (byte) 0xFF || (at(p + 1) & 0xFE) != 0xF8) {
                continue;
            }
            if (!parseAt(p, current)) {
                continue;
            }
            frameStart = p;
            if (scanFrame(true) != UNCONFIRMED) {
                return p;
            }
        }
        throw new MediaException.Malformed("no FLAC stream found");
    }

    /**
     * Returns the frame at {@link #frameStart} and advances past it.
     *
     * @return the packet, or {@code null} at the end of the audio
     */
    private Packet nextFrame() {
        if (!hasFrame) {
            return null;
        }
        discardBefore(frameStart);
        var end = scanFrame(false);
        var hasNext = end >= 0;
        if (!hasNext) {
            end = Math.min(audioEnd, bufferStart + bufferLength);
            // bytes that belong to no frame may trail the last one; the decoder ignores what follows a
            // frame's CRC-16, so the packet only needs to be capped at the largest declared frame
            if (streamInfo != null && streamInfo.maxFrameSize() > 0 && matchesStreamInfo()) {
                end = Math.min(end, frameStart + streamInfo.maxFrameSize());
            }
        }
        var size = end - frameStart;
        var blockSize = current.blockSize();
        if (streamInfo != null && matchesStreamInfo()) {
            if (streamInfo.maxFrameSize() > 0 && size > streamInfo.maxFrameSize()) {
                throw new MediaException.Malformed("FLAC frame of " + size + " bytes exceeds the STREAMINFO maximum "
                        + streamInfo.maxFrameSize());
            }
            if (blockSize > streamInfo.maxBlockSize()) {
                throw new MediaException.Malformed("FLAC block size " + blockSize + " exceeds the STREAMINFO maximum "
                        + streamInfo.maxBlockSize());
            }
        }
        if (streamInfo != null) {
            if (streamInfo.totalSamples() > 0 && samplesRead + blockSize > streamInfo.totalSamples()) {
                throw new MediaException.Malformed("FLAC stream holds more than the " + streamInfo.totalSamples()
                        + " samples STREAMINFO declares");
            }
        }
        if (hasNext && blockSize < 16) {
            throw new MediaException.Malformed("FLAC block size " + blockSize + " below 16 outside the last frame");
        }
        var offset = base + (int) (frameStart - bufferStart);
        var payload = Arrays.copyOfRange(data, offset, offset + (int) size);
        var packet = new Packet(0, payload, nextPts, nextPts, blockSize, true);
        nextPts += blockSize;
        samplesRead += blockSize;
        if (hasNext) {
            frameStart = end;
            current.copyFrom(candidate);
        } else {
            hasFrame = false;
        }
        ForwardOnlySource.release(source, frameStart);
        return packet;
    }

    /**
     * Finds where the frame at {@link #frameStart} ends.
     *
     * <p>On success {@link #candidate} holds the header of the next frame.
     *
     * @param strict whether only a candidate passing every check, or a last frame whose CRC-16 matches, is accepted, as
     * when confirming the first frame of a stream without metadata
     * @return the offset of the next frame, {@code -1} when the frame runs to the end of the audio, or
     * {@link #UNCONFIRMED} when {@code strict} and neither holds
     * @throws MediaException.Malformed if no boundary is found within the largest possible frame size and the audio
     * continues past it
     */
    private long scanFrame(boolean strict) {
        var syncSecond = (byte) (current.variableBlocking() ? 0xF9 : 0xF8);
        var bound = maximumFrameSize();
        var limit = frameStart + bound;
        var crc = 0;
        var crcPosition = frameStart;
        var fallbackPosition = -1L;
        var fallbackStrength = 0;
        var p = frameStart + current.size() + MIN_FRAME_BODY;
        while (p < limit) {
            if (bufferStart + bufferLength < p + 2 && !fill()) {
                break;
            }
            var index = (int) (p - bufferStart);
            var stop = (int) Math.min(bufferLength - 1, limit - bufferStart);
            index = findSync(data, base + index, base + stop, syncSecond) - base;
            p = bufferStart + index;
            if (index >= stop) {
                continue;
            }
            while (bufferStart + bufferLength < p + FlacFrameHeader.MAX_SIZE && fill()) {
                index = (int) (p - bufferStart);
            }
            index = (int) (p - bufferStart);
            if (candidate.parse(data, base + index, base + bufferLength)) {
                var continues = continues(candidate);
                if (continues && sameFormat(candidate)) {
                    return p;
                }
                crc = Crc16.crc16(crc, data, base + (int) (crcPosition - bufferStart), (int) (p - 2 - crcPosition));
                crcPosition = p - 2;
                var stored = (data[base + index - 2] & 0xFF) << 8 | data[base + index - 1] & 0xFF;
                var crcMatches = crc == stored;
                if (crcMatches && continues) {
                    return p;
                }
                var strength = continues ? 2 : crcMatches ? 1 : 0;
                if (strength > fallbackStrength) {
                    fallbackStrength = strength;
                    fallbackPosition = p;
                    fallback.copyFrom(candidate);
                }
            }
            p++;
        }
        if (strict) {
            var end = Math.min(audioEnd, bufferStart + bufferLength);
            if (p < limit && end - 2 > crcPosition) {
                crc = Crc16.crc16(crc, data, base + (int) (crcPosition - bufferStart), (int) (end - 2 - crcPosition));
                var index = (int) (end - bufferStart);
                var stored = (data[base + index - 2] & 0xFF) << 8 | data[base + index - 1] & 0xFF;
                if (crc == stored) {
                    return -1;
                }
            }
            return UNCONFIRMED;
        }
        if (fallbackPosition >= 0) {
            candidate.copyFrom(fallback);
            return fallbackPosition;
        }
        if (p >= limit && limit < audioEnd && ensure(limit, 1) > 0) {
            throw new MediaException.Malformed("no FLAC frame boundary within " + bound + " bytes of offset " + frameStart);
        }
        return -1;
    }

    /**
     * Returns the index of the first frame sync code at or after {@code from}.
     *
     * @implNote This implementation skips eight bytes at a time while a word holds no {@code 0xFF} byte, using the
     * zero-byte test {@code (x - 0x01..01) & ~x & 0x80..80} on the complemented word, and examines the bytes of any
     * other word one by one.
     *
     * @param data the buffer
     * @param from the index where the search starts
     * @param stop the index where the search ends, below the buffer length so that the byte after a candidate is
     * readable
     * @param syncSecond the second byte of the sync code
     * @return the index of the first {@code 0xFF} followed by {@code syncSecond}, or {@code stop} when there is none
     * before it
     */
    static int findSync(byte[] data, int from, int stop, byte syncSecond) {
        var index = from;
        while (index < stop) {
            while (index + 8 <= stop) {
                var inverted = ~(long) LONGS.get(data, index);
                if (((inverted - 0x0101010101010101L) & ~inverted & 0x8080808080808080L) != 0) {
                    break;
                }
                index += 8;
            }
            var end = Math.min(stop, index + 8);
            for (; index < end; index++) {
                if (data[index] == (byte) 0xFF && data[index + 1] == syncSecond) {
                    return index;
                }
            }
        }
        return stop;
    }

    /**
     * Returns whether the current frame has the format {@code STREAMINFO} declares.
     *
     * <p>The declared frame and block size bounds describe only frames of the declared format; a
     * stream that switches format mid-way (a valid but uncommon construction) is not held to
     * them after the switch.
     *
     * @return {@code true} when the sample rate, channel count and sample size match
     */
    private boolean matchesStreamInfo() {
        return (current.sampleRate() == 0 || current.sampleRate() == streamInfo.sampleRate())
                && current.channels() == streamInfo.channels()
                && (current.bitsPerSample() == 0 || current.bitsPerSample() == streamInfo.bitsPerSample());
    }

    /**
     * Returns whether a candidate header codes exactly the format of the current frame.
     *
     * <p>Together with a matching CRC-8 and continuous numbering, an identical format fixes
     * about 46 header bits, so a false sync inside frame data is accepted with a probability
     * near {@code 2^-46} per byte and the frame CRC-16 need not be computed. A fixed-blocking
     * candidate must also repeat the block size, which the last frame of a stream does not, so
     * the last boundary is always confirmed by the CRC-16.
     *
     * @param next the candidate header
     * @return {@code true} when sample rate, sample size, channel count and, for fixed blocking, block size all match
     */
    private boolean sameFormat(FlacFrameHeader next) {
        return next.sampleRate() == current.sampleRate() && next.bitsPerSample() == current.bitsPerSample()
                && next.channels() == current.channels()
                && (current.variableBlocking() || next.blockSize() == current.blockSize());
    }

    /**
     * Returns whether a candidate header continues the numbering of the current frame.
     *
     * <p>Fixed-blocking streams number frames; the older variable-blocksize streams that set the
     * fixed-blocking bit number samples, so both continuations are accepted.
     *
     * @param next the candidate header
     * @return {@code true} when the candidate's number follows the current frame's
     */
    private boolean continues(FlacFrameHeader next) {
        var number = current.number();
        if (current.variableBlocking()) {
            return next.number() == number + current.blockSize();
        }
        return next.number() == number + 1 || next.number() == number + current.blockSize();
    }

    /**
     * Returns the largest size a frame with the current header can legitimately have.
     *
     * <p>A verbatim frame needs at most 33 bits per sample; the bound doubles that for
     * pathological Rice coding and adds room for headers, capped by the allocation limit.
     *
     * @return the bound in bytes
     */
    private long maximumFrameSize() {
        var verbatim = (long) current.channels() * (current.blockSize() * 33L + 64) / 8;
        return Math.min(limits.maxBytes(), 2 * verbatim + current.size() + 1024);
    }

    /**
     * Parses a frame header at an absolute offset.
     *
     * @param position the offset of the header
     * @param target receives the header
     * @return {@code true} when a valid header starts there
     */
    private boolean parseAt(long position, FlacFrameHeader target) {
        var available = ensure(position, FlacFrameHeader.MAX_SIZE);
        if (available < 6) {
            return false;
        }
        var index = (int) (position - bufferStart);
        return target.parse(data, base + index, base + index + available);
    }

    /**
     * Returns the buffered byte at an absolute offset, which must be buffered.
     *
     * @param position the offset
     * @return the byte
     */
    private byte at(long position) {
        return data[base + (int) (position - bufferStart)];
    }

    /**
     * Buffers up to {@code length} bytes from an absolute offset at or after
     * {@link #bufferStart}.
     *
     * @param position the offset
     * @param length the number of bytes wanted
     * @return the number of bytes available from {@code position}, at most {@code length}
     */
    private int ensure(long position, int length) {
        while (bufferStart + bufferLength < position + length && fill()) {
            continue;
        }
        return (int) Math.max(0, Math.min(length, bufferStart + bufferLength - position));
    }

    /**
     * Appends source bytes to the buffer, compacting or growing it when full.
     *
     * @return {@code false} when the audio has ended
     */
    private boolean fill() {
        var bufferEnd = bufferStart + bufferLength;
        if (bufferEnd >= audioEnd) {
            return false;
        }
        if (bufferLength == capacity) {
            var keep = Math.max(bufferStart, Math.min(frameStart, bufferEnd));
            if (keep > bufferStart && keep - bufferStart >= capacity / 2) {
                discardBefore(keep);
            } else {
                var grown = (long) capacity * 2;
                LimitChecks.checkAllocation(limits, grown);
                if (backing == null) {
                    data = Arrays.copyOf(data, (int) grown);
                }
                capacity = (int) grown;
            }
        }
        var want = (int) Math.min(capacity - bufferLength, audioEnd - bufferEnd);
        var read = readInto(bufferEnd, want);
        if (read <= 0) {
            audioEnd = bufferEnd;
            return false;
        }
        bufferLength += read;
        return true;
    }

    /**
     * Drops buffered bytes before an offset once they fill half the buffer.
     *
     * @param position the offset of the first byte still needed
     */
    private void discardBefore(long position) {
        if (position <= bufferStart) {
            return;
        }
        var bufferEnd = bufferStart + bufferLength;
        if (position >= bufferEnd) {
            bufferStart = position;
            bufferLength = 0;
            rebase();
            return;
        }
        var drop = (int) (position - bufferStart);
        if (drop < capacity / 2 && bufferLength < capacity) {
            return;
        }
        if (backing == null) {
            System.arraycopy(data, drop, data, 0, bufferLength - drop);
        }
        bufferStart = position;
        bufferLength -= drop;
        rebase();
    }

    /**
     * Points buffer index zero of an in-memory source at {@link #bufferStart}.
     */
    private void rebase() {
        if (backing != null && bufferStart <= source.size()) {
            base = backing.arrayOffset() + (int) bufferStart;
        }
    }

    /**
     * Appends up to {@code length} bytes at {@code position} to the buffer, as one source read
     * does.
     *
     * <p>An in-memory source is not copied: its bytes are already where the buffer window
     * points, so only the count a read of its array would return is computed.
     *
     * @param position the source offset of the buffer end
     * @param length the free capacity to fill
     * @return the number of bytes added, {@code 0} when {@code length} is {@code 0}, or {@code -1} at the end of the
     * input
     */
    private int readInto(long position, int length) {
        if (backing == null) {
            return source.read(position, data, bufferLength, length);
        }
        var size = source.size();
        if (position >= size) {
            return length == 0 ? 0 : -1;
        }
        return (int) Math.min(length, size - position);
    }
}
