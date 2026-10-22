package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.internal.tags.MuxerTags;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Writes the packets of one track as an elementary stream without a container.
 *
 * <p>The output form depends on the codec:
 * <ul>
 *   <li>PCM ({@code PCM_*}): the samples, back to back (a {@code .pcm} or {@code .raw} file
 *       whose format the reader must know);</li>
 *   <li>MP3 and MP2: the frames, back to back (an {@code .mp3} file);</li>
 *   <li>FLAC: the {@code fLaC} marker and metadata blocks of the track configuration, then the
 *       frames (a native {@code .flac} file);</li>
 *   <li>AMR-NB and AMR-WB: the {@code #!AMR} or {@code #!AMR-WB} magic, then the storage-format
 *       frames (an {@code .amr} file, RFC 4867 section 5);</li>
 *   <li>AC-3 and E-AC-3: the sync frames, back to back (an {@code .ac3} or {@code .ec3}
 *       file);</li>
 *   <li>H.264 and HEVC: an Annex B byte stream; length-prefixed NAL units become start-code
 *       prefixed, and the parameter sets of the track configuration are repeated before every
 *       key frame that does not carry its own, so decoding can start at any key frame;</li>
 *   <li>AV1: a low-overhead OBU stream (AV1 specification section 5): a temporal delimiter
 *       starts every temporal unit that lacks one, and the configuration's sequence header is
 *       inserted before every key frame that does not carry its own.</li>
 * </ul>
 * AAC needs ADTS framing ({@link com.github.auties00.smalt.container.adts.AdtsMuxer}) and VP8
 * and VP9 an IVF file ({@link com.github.auties00.smalt.container.ivf.IvfMuxer}); every other
 * codec has no raw form and is rejected.
 *
 * <p>Timestamps are ignored. The output is written in order; on a seekable sink a FLAC stream is
 * revisited to store the final configuration {@link #updateCodecConfig(int, byte[])} passes (an
 * encoder's {@code STREAMINFO} with its MD5 signature and seek table) and to fill in a
 * {@code STREAMINFO} total sample count and frame size bounds still declared as unknown, and an
 * MP3 stream gets a Xing tag frame (see {@link RawMuxerOptions#xingTag()}) whose frame count,
 * byte count and seek table are filled in by {@link #finish()}. The sink is written from its
 * position at construction and is never closed by the muxer. Instances are not thread-safe.
 */
public final class RawMuxer implements Muxer {
    /**
     * A temporal delimiter OBU with its size field.
     */
    private static final byte[] TEMPORAL_DELIMITER = {0x12, 0x00};

    /**
     * The size of the Xing payload after the side information: the tag word, the flags, the frame
     * and byte counts and the 100-entry seek table.
     */
    private static final int XING_PAYLOAD = 4 + 4 + 4 + 4 + 100;

    /**
     * The output sink.
     */
    private final MediaSink output;

    /**
     * The configuration.
     */
    private final RawMuxerOptions options;

    /**
     * The sink position of the reserved Xing tag frame, or {@code -1} when none was written.
     */
    private long xingPosition = -1;

    /**
     * The header of the reserved Xing tag frame.
     */
    private int xingHeader;

    /**
     * The size of the reserved Xing tag frame in bytes.
     */
    private int xingSize;

    /**
     * The byte offset of each MP3 frame after the tag frame, relative to the tag frame.
     */
    private long[] frameOffsets = new long[0];

    /**
     * The number of MP3 frames written after the tag frame.
     */
    private int frameCount;

    /**
     * The bytes written from the tag frame on.
     */
    private long streamBytes;

    /**
     * The bitrate index shared by every MP3 frame so far, {@code -1} once frames differ, or
     * {@code -2} before the first frame.
     */
    private int commonBitrate = -2;

    /**
     * The sink position at construction, where the stream starts.
     */
    private final long base;

    /**
     * The declared track, or {@code null}.
     */
    private Track track;

    /**
     * The NAL unit length size of an H.264 or HEVC track.
     */
    private int lengthSize;

    /**
     * The parameter sets of an H.264 or HEVC track, start-code prefixed, or the sequence header
     * OBUs of an AV1 track; empty when the configuration has none.
     */
    private byte[] parameterSets = new byte[0];

    /**
     * The codec configuration of the packets being written, initially the track's.
     */
    private byte[] config;

    /**
     * The configuration of a FLAC track: the declared one, or the one
     * {@link #updateCodecConfig(int, byte[])} last stored.
     */
    private byte[] flacConfig;

    /**
     * The FLAC samples written, or {@code -1} once a packet without a duration is seen.
     */
    private long flacSamples;

    /**
     * The smallest FLAC frame written.
     */
    private int flacMinFrame = Integer.MAX_VALUE;

    /**
     * The largest FLAC frame written.
     */
    private int flacMaxFrame;

    /**
     * The tags written before the stream, or {@code null}.
     */
    private MediaTags tags;

    /**
     * Whether the stream header was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @throws MediaException.Io if the sink position cannot be read
     */
    public RawMuxer(MediaSink output) {
        this(output, RawMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer writing to a sink with options.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @param options the configuration
     * @throws MediaException.Io if the sink position cannot be read
     */
    public RawMuxer(MediaSink output, RawMuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        this.base = output.position();
    }

    /**
     * Returns whether a codec has a raw elementary-stream form this muxer writes.
     *
     * @param codec the codec of the track
     * @return {@code true} for PCM, MP3, MP2, FLAC, AMR-NB, AMR-WB, AC-3, E-AC-3, H.264, HEVC and AV1
     */
    public static boolean supports(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case MP3, MP2, FLAC, AMR_NB, AMR_WB, AC3, EAC3, H264, HEVC, AV1 -> true;
            default -> codec.isPcm();
        };
    }

    /**
     * Returns whether an elementary-stream file format can carry a codec.
     *
     * <p>{@link ContainerFormat#MPEG_AUDIO} carries MP3 and MP2, {@link ContainerFormat#FLAC} FLAC,
     * {@link ContainerFormat#AMR} AMR-NB and AMR-WB, {@link ContainerFormat#AC3} AC-3 and E-AC-3,
     * and {@link ContainerFormat#H264}, {@link ContainerFormat#HEVC} and {@link ContainerFormat#AV1}
     * their codec; every other format is not an elementary stream this muxer writes.
     *
     * @param format the file format
     * @param codec the codec of the track
     * @return {@code true} when a file of {@code format} can carry {@code codec}
     */
    public static boolean supports(ContainerFormat format, Codec codec) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(codec, "codec");
        return switch (format) {
            case MPEG_AUDIO -> codec == Codec.MP3 || codec == Codec.MP2;
            case FLAC -> codec == Codec.FLAC;
            case AMR -> codec == Codec.AMR_NB || codec == Codec.AMR_WB;
            case AC3 -> codec == Codec.AC3 || codec == Codec.EAC3;
            case H264 -> codec == Codec.H264;
            case HEVC -> codec == Codec.HEVC;
            case AV1 -> codec == Codec.AV1;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the codec has no raw form, or the configuration a FLAC, H.264 or HEVC track
     * needs is missing or invalid
     * @throws IllegalStateException if a track was already declared or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalStateException("a raw stream carries one track");
        }
        if (!supports(track.codec())) {
            throw new IllegalArgumentException(track.codec().displayName() + " has no raw stream form");
        }
        var config = track.codecConfig().orElse(null);
        switch (track.codec()) {
            case FLAC -> {
                flacHeader(config);
                flacConfig = config;
            }
            case H264 -> {
                if (config == null || config.length < 7) {
                    throw new IllegalArgumentException("an H.264 track needs its avcC configuration");
                }
                var parsed = NalRecords.parseAvc(config);
                if (parsed == null) {
                    throw new IllegalArgumentException("truncated avcC");
                }
                lengthSize = parsed.lengthSize();
                parameterSets = NalUnits.annexBParameterSets(parsed.parameterSets());
            }
            case HEVC -> {
                if (config == null || config.length < 23) {
                    throw new IllegalArgumentException("an HEVC track needs its hvcC configuration");
                }
                var parsed = NalRecords.parseHevc(config);
                if (parsed == null) {
                    throw new IllegalArgumentException("truncated hvcC");
                }
                lengthSize = parsed.lengthSize();
                parameterSets = NalUnits.annexBParameterSets(parsed.parameterSets());
            }
            case AV1 -> parameterSets = config != null && config.length > 4
                    ? Arrays.copyOfRange(config, 4, config.length) : new byte[0];
            default -> {
            }
        }
        this.config = config;
        this.track = track;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} once an MP3, MP2 or FLAC track is declared
     */
    @Override
    public boolean supportsTags() {
        return track != null && (track.codec() == Codec.MP3 || track.codec() == Codec.MP2 || track.codec() == Codec.FLAC);
    }

    /**
     * {@inheritDoc}
     *
     * <p>An MP3 or MP2 stream gets an ID3v2.4 tag before its first frame; a FLAC stream gets a
     * {@code VORBIS_COMMENT} block (with the chapters) and one {@code PICTURE} block per picture,
     * replacing those of the track configuration. The track must be declared first.
     *
     * @throws MediaException.Unsupported if no track is declared or its raw form stores no tags
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        if (!supportsTags()) {
            throw new MediaException.Unsupported("raw", "only MP3, MP2 and FLAC raw streams store tags");
        }
        this.tags = tags;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A packet that changes the codec configuration of an H.264, HEVC or AV1 stream replaces the
     * parameter sets or sequence header repeated before the key frames from then on.
     *
     * @throws IllegalArgumentException if the packet belongs to another track, an H.264 or HEVC packet is not made
     * of length-prefixed NAL units, or a new H.264 or HEVC configuration cannot be parsed
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Unsupported if the packet changes the codec configuration of a stream
     * that is not H.264, HEVC or AV1
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (track == null) {
            throw new IllegalStateException("no track was declared");
        }
        if (packet.trackId() != track.id()) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        var newConfig = ConfigChanges.changed(packet, config);
        if (newConfig != null) {
            switch (track.codec()) {
                case H264, HEVC -> {
                    var parsed = track.codec() == Codec.H264 ? NalRecords.parseAvc(newConfig)
                            : NalRecords.parseHevc(newConfig);
                    if (parsed == null) {
                        throw new IllegalArgumentException("the new " + track.codec().displayName()
                                + " configuration cannot be parsed");
                    }
                    lengthSize = parsed.lengthSize();
                    parameterSets = NalUnits.annexBParameterSets(parsed.parameterSets());
                }
                case AV1 -> parameterSets = newConfig.length > 4 ? Arrays.copyOfRange(newConfig, 4, newConfig.length)
                        : new byte[0];
                default -> throw ConfigChanges.unsupported("a raw " + track.codec().displayName() + " stream",
                        packet.trackId());
            }
            config = newConfig;
        }
        if (!started) {
            writeHeader();
        }
        switch (track.codec()) {
            case H264, HEVC -> writeAnnexB(packet);
            case AV1 -> writeTemporalUnit(packet);
            case FLAC -> {
                output.write(ByteBuffer.wrap(packet.data()));
                flacMinFrame = Math.min(flacMinFrame, packet.size());
                flacMaxFrame = Math.max(flacMaxFrame, packet.size());
                if (flacSamples >= 0) {
                    flacSamples = packet.duration() > 0 ? flacSamples + flacDuration(packet.duration()) : -1;
                }
            }
            case MP3 -> {
                if (commonBitrate == -2) {
                    reserveXing(packet.data());
                }
                if (xingPosition >= 0) {
                    recordFrame(packet.data());
                }
                output.write(ByteBuffer.wrap(packet.data()));
            }
            default -> output.write(ByteBuffer.wrap(packet.data()));
        }
    }

    /**
     * Writes a placeholder Xing tag frame before the first MP3 frame, when the options ask for it,
     * the sink is seekable and the first frame has a valid Layer III header with a bitrate.
     *
     * @param first the first frame
     */
    private void reserveXing(byte[] first) {
        commonBitrate = -1;
        if (!options.xingTag() || !output.isSeekable() || first.length < MpegAudioHeader.SIZE) {
            return;
        }
        var header = MpegAudioHeader.read(first, 0);
        if (!MpegAudioHeader.isValid(header) || MpegAudioHeader.layer(header) != 3 || MpegAudioHeader.isFreeFormat(header)) {
            return;
        }
        commonBitrate = MpegAudioHeader.bitrateIndex(header);
        var template = (header & 0xFFFE0C00) | 0x00010000 | (header & 0xC0) | (header & 0x0F);
        var needed = MpegAudioHeader.SIZE + MpegAudioHeader.sideInfoBytes(template) + XING_PAYLOAD;
        for (var index = commonBitrate; index < 15; index++) {
            var candidate = (template & ~0xF000) | index << 12;
            var size = MpegAudioHeader.frameBytes(candidate, 0);
            if (size >= needed) {
                xingHeader = candidate;
                xingSize = size;
                xingPosition = output.position();
                output.write(ByteBuffer.wrap(xingFrame(new byte[100], 0, 0)));
                streamBytes = size;
                return;
            }
        }
    }

    /**
     * Records the offset of an MP3 frame for the Xing seek table.
     *
     * @param frame the MPEG audio frame written
     */
    private void recordFrame(byte[] frame) {
        if (frameCount == frameOffsets.length) {
            frameOffsets = Arrays.copyOf(frameOffsets, Math.max(1024, frameCount * 2));
        }
        frameOffsets[frameCount++] = streamBytes;
        streamBytes += frame.length;
        if (frame.length >= MpegAudioHeader.SIZE && commonBitrate >= 0
                && MpegAudioHeader.bitrateIndex(MpegAudioHeader.read(frame, 0)) != commonBitrate) {
            commonBitrate = -1;
        }
    }

    /**
     * Returns the Xing tag frame: the reserved header, silent side information, then the
     * {@code Xing} word ({@code Info} when every frame has the same bitrate), the flags (frame
     * count, byte count and seek table present), the counts and the seek table.
     *
     * @param toc the seek table
     * @param frames the number of audio frames after the tag frame
     * @param bytes the number of bytes from the tag frame on
     * @return the frame
     */
    private byte[] xingFrame(byte[] toc, int frames, long bytes) {
        var frame = new ByteWriter(xingSize);
        frame.u32be(xingHeader & 0xFFFFFFFFL);
        frame.bytes(new byte[MpegAudioHeader.sideInfoBytes(xingHeader)]);
        frame.bytes((commonBitrate >= 0 ? "Info" : "Xing").getBytes(StandardCharsets.ISO_8859_1));
        frame.u32be(7);
        frame.u32be(frames);
        frame.u32be(Math.min(bytes, 0xFFFFFFFFL));
        frame.bytes(toc);
        return Arrays.copyOf(frame.toByteArray(), xingSize);
    }

    /**
     * Fills in the Xing tag frame with the final counts and the seek table: entry {@code i} is
     * the position, in 256ths of the stream size, of the frame that starts {@code i} percent of
     * the way through the stream.
     */
    private void patchXing() {
        var toc = new byte[100];
        for (var i = 0; i < 100; i++) {
            var offset = frameCount == 0 ? 0 : frameOffsets[(int) ((long) i * frameCount / 100)];
            toc[i] = (byte) Math.min(255, offset * 256 / Math.max(1, streamBytes));
        }
        var end = output.position();
        output.position(xingPosition);
        output.write(ByteBuffer.wrap(xingFrame(toc, frameCount, streamBytes)));
        output.position(end);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only a FLAC configuration is stored: before the first packet it replaces the declared
     * one, afterwards it is written over the stream header when the sink is seekable and the new
     * header has the same length as the one written; other codecs have no configuration in their
     * raw form.
     *
     * @throws IllegalArgumentException if the track was not declared, or a FLAC configuration does not start with a
     * well-formed {@code STREAMINFO} block
     * @throws IllegalStateException if the muxer is finished
     */
    @Override
    public void updateCodecConfig(int trackId, byte[] config) {
        Objects.requireNonNull(config, "config");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (track == null || track.id() != trackId) {
            throw new IllegalArgumentException("undeclared track " + trackId);
        }
        if (track.codec() != Codec.FLAC) {
            return;
        }
        var header = writtenFlacHeader(config);
        if (!started) {
            flacConfig = config.clone();
            return;
        }
        if (output.isSeekable() && header.length == writtenFlacHeader(flacConfig).length) {
            var end = output.position();
            output.position(base);
            output.write(ByteBuffer.wrap(header));
            output.position(end);
            flacConfig = config.clone();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if no track was declared or the muxer is already finished
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        if (track == null) {
            throw new IllegalStateException("no track was declared");
        }
        if (!started) {
            writeHeader();
        }
        finished = true;
        if (track.codec() == Codec.FLAC && output.isSeekable()) {
            patchStreamInfo();
        }
        if (xingPosition >= 0) {
            patchXing();
        }
    }

    /**
     * Marks the muxer finished.
     */
    @Override
    public void close() {
        finished = true;
    }

    /**
     * Writes what precedes the first packet: the FLAC marker and metadata, or the AMR magic.
     */
    private void writeHeader() {
        started = true;
        switch (track.codec()) {
            case FLAC -> output.write(ByteBuffer.wrap(writtenFlacHeader(flacConfig)));
            case MP3, MP2 -> {
                if (tags != null && !tags.isEmpty()) {
                    output.write(ByteBuffer.wrap(MuxerTags.id3v2(tags)));
                }
            }
            case AMR_NB -> output.write(ByteBuffer.wrap("#!AMR\n".getBytes(StandardCharsets.ISO_8859_1)));
            case AMR_WB -> output.write(ByteBuffer.wrap("#!AMR-WB\n".getBytes(StandardCharsets.ISO_8859_1)));
            default -> {
            }
        }
    }

    /**
     * Returns the native FLAC header written for a configuration: the header itself, or, when tags
     * were set, the header with its tag and picture blocks in place of its padding.
     *
     * @param config the {@code STREAMINFO} configuration and any following metadata blocks
     * @return the header bytes the output starts with
     * @throws IllegalArgumentException if the configuration does not start with a well-formed {@code STREAMINFO} block
     */
    private byte[] writtenFlacHeader(byte[] config) {
        var header = flacHeader(config);
        return tags != null ? MuxerTags.flacMetadata(header, tags) : header;
    }

    /**
     * Validates a FLAC configuration and returns it with the last-block flag set on its final
     * metadata block only.
     *
     * @param config the {@code fLaC} marker followed by the metadata blocks
     * @return the stream header
     * @throws IllegalArgumentException if the configuration is missing or does not start with a well-formed
     * {@code STREAMINFO} block
     */
    private static byte[] flacHeader(byte[] config) {
        if (config == null || config.length < 42 || config[0] != 'f' || config[1] != 'L' || config[2] != 'a' || config[3] != 'C'
                || (config[4] & 0x7F) != 0 || blockLength(config, 4) != 34) {
            throw new IllegalArgumentException("a FLAC track needs its fLaC marker and STREAMINFO block");
        }
        var header = config.clone();
        var offset = 4;
        while (offset + 4 <= header.length) {
            var end = offset + 4 + blockLength(header, offset);
            if (end > header.length) {
                throw new IllegalArgumentException("truncated FLAC metadata block");
            }
            if (end + 4 > header.length) {
                header[offset] |= (byte) 0x80;
                return end == header.length ? header : Arrays.copyOf(header, end);
            }
            header[offset] &= 0x7F;
            offset = end;
        }
        throw new IllegalArgumentException("truncated FLAC metadata block");
    }

    /**
     * Returns the length of a FLAC metadata block.
     *
     * @param data the metadata
     * @param offset the index of the block header
     * @return the block length, header excluded
     */
    private static int blockLength(byte[] data, int offset) {
        return (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8 | (data[offset + 3] & 0xFF);
    }

    /**
     * Converts a FLAC packet duration to samples.
     *
     * @param duration the duration in the track time base
     * @return the duration in samples
     */
    private long flacDuration(long duration) {
        var rate = track instanceof AudioTrack audio && audio.sampleRate() > 0 ? audio.sampleRate() : 0;
        if (rate == 0) {
            var config = track.codecConfig().orElseThrow();
            rate = (config[18] & 0xFF) << 12 | (config[19] & 0xFF) << 4 | (config[20] & 0xF0) >> 4;
        }
        return rate == 0 ? duration : track.timeBase().rescale(duration, Rational.timeBaseOf(rate));
    }

    /**
     * Fills in the {@code STREAMINFO} fields the configuration declared as unknown: the frame
     * size bounds and the total sample count.
     */
    private void patchStreamInfo() {
        var config = flacConfig;
        var end = output.position();
        if (flacMaxFrame > 0 && (config[12] | config[13] | config[14] | config[15] | config[16] | config[17]) == 0) {
            var bounds = new ByteWriter(6);
            bounds.u24be(Math.min(flacMinFrame, 0xFFFFFF));
            bounds.u24be(Math.min(flacMaxFrame, 0xFFFFFF));
            output.position(base + 12);
            bounds.writeTo(output);
        }
        var declared = (config[21] & 0x0FL) << 32 | (config[22] & 0xFFL) << 24 | (config[23] & 0xFFL) << 16
                | (config[24] & 0xFFL) << 8 | (config[25] & 0xFFL);
        if (declared == 0 && flacSamples > 0 && flacSamples < 1L << 36) {
            var total = new ByteWriter(5);
            total.u8((config[21] & 0xF0) | (int) (flacSamples >>> 32));
            total.u32be(flacSamples);
            output.position(base + 21);
            total.writeTo(output);
        }
        output.position(end);
    }

    /**
     * Writes an H.264 or HEVC access unit as start-code prefixed NAL units.
     *
     * @param packet the access unit of length-prefixed NAL units
     */
    private void writeAnnexB(Packet packet) {
        var unit = NalUnits.toAnnexB(packet.data(), lengthSize, track.codec() == Codec.HEVC,
                packet.keyframe() ? parameterSets : null, null);
        output.write(ByteBuffer.wrap(unit));
    }

    /**
     * Writes an AV1 temporal unit, adding the temporal delimiter and sequence header it lacks.
     *
     * @param packet the temporal unit
     */
    private void writeTemporalUnit(Packet packet) {
        var data = packet.data();
        var types = obuTypes(data);
        var unit = new ByteWriter(data.length + TEMPORAL_DELIMITER.length + parameterSets.length);
        if (types.isEmpty() || types.getFirst() != 2) {
            unit.bytes(TEMPORAL_DELIMITER);
        }
        if (packet.keyframe() && !types.contains(1)) {
            unit.bytes(parameterSets);
        }
        unit.bytes(data);
        unit.writeTo(output);
    }

    /**
     * Lists the OBU types of a temporal unit.
     *
     * @param data the temporal unit, OBUs with size fields
     * @return the types in order; empty when the unit is empty or its OBUs carry no sizes
     */
    private static List<Integer> obuTypes(byte[] data) {
        var types = new ArrayList<Integer>();
        var offset = 0;
        while (offset < data.length) {
            var header = data[offset] & 0xFF;
            types.add((header >> 3) & 0x0F);
            if ((header & 0x02) == 0) {
                break;
            }
            var position = offset + 1 + ((header & 0x04) != 0 ? 1 : 0);
            var size = 0L;
            var shift = 0;
            while (true) {
                if (position >= data.length || shift > 56) {
                    return types;
                }
                var b = data[position++] & 0xFF;
                size |= (long) (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (size > data.length - position) {
                break;
            }
            offset = position + (int) size;
        }
        return types;
    }
}
