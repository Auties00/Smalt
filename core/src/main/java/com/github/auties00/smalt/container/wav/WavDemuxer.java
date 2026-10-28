package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.ac3.Ac3Demuxer;
import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.container.amr.AmrDemuxer;
import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.PrefixedSource;
import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.internal.io.SlicedSource;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.tags.RiffMetadataChunks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads RIFF WAVE files, including the 64-bit RF64 and BW64 variants and big-endian RIFX.
 *
 * <p>The {@code fmt } chunk may use {@code WAVE_FORMAT_PCM} (8-bit unsigned, 16, 24, 32 and 64-bit
 * signed integers), {@code WAVE_FORMAT_IEEE_FLOAT} (32 and 64-bit), {@code WAVE_FORMAT_ALAW},
 * {@code WAVE_FORMAT_MULAW}, or {@code WAVE_FORMAT_EXTENSIBLE} wrapping any of those; the
 * extensible channel mask becomes the track's {@link ChannelLayout}, one {@link Speaker} per set
 * bit in mask order, with {@link Speaker#UNKNOWN} for channels beyond the mask. The sample
 * container size comes from the block alignment, so 24-bit samples in 32-bit containers decode as
 * left-justified 32-bit samples. The Ambisonic B-format sub-formats (integer and float) read as PCM
 * whose layout is the Furse-Malham B-format of {@link
 * com.github.auties00.smalt.audio.Ambisonics#ofFurseMalham(int)} for the channel counts it defines, and
 * channels of {@link Speaker#UNKNOWN} position without ambisonics for other counts.
 *
 * <p>The ADPCM and telephony formats are read by this demuxer, in the packets their {@link Codec} constant defines:
 * <table class="striped">
 * <caption>ADPCM and telephony formats</caption>
 * <thead><tr><th scope="col">Format</th><th scope="col">Codec</th><th scope="col">Packets</th></tr></thead>
 * <tbody>
 * <tr><td>{@code 0x0011}, 4 bits per sample</td><td>{@link Codec#ADPCM_IMA_WAV}</td><td>one block of the block
 * alignment</td></tr>
 * <tr><td>{@code 0x0002}, 4 bits per sample</td><td>{@link Codec#ADPCM_MS}, configured with the format's extra
 * bytes up to the end of their coefficient table, and without a configuration (the standard coefficients) when
 * they hold no complete table</td><td>one block of the block alignment</td></tr>
 * <tr><td>{@code 0x0031}, one channel, 65-byte blocks</td><td>{@link Codec#GSM_MS}</td><td>whole blocks</td></tr>
 * <tr><td>{@code 0x028F}, one channel</td><td>{@link Codec#ADPCM_G722}</td><td>bytes</td></tr>
 * <tr><td>{@code 0x0045}, one channel, codes of 2 to 5 bits</td><td>{@link Codec#ADPCM_G726}</td><td>bytes holding
 * eight codes for 3 and 5-bit codes</td></tr>
 * </tbody>
 * </table>
 * The code streams are read in packets of the whole units (a byte, eight codes, a GSM block) within
 * the largest power of two of samples that lasts at most 100 ms, at least one unit, and blocks one
 * per packet; at the end of the data, the bytes left that still code samples
 * (a block's header and whole groups of its codes, whole GSM blocks) form a last, shorter packet.
 * Every packet is a key frame, timestamped in samples. The {@code fact} chunk, when it counts
 * fewer samples than the packets code, ends the stream: the packets after it are dropped and the
 * last one's duration is cut to it; in an RF64 file, a {@code fact} count of {@code 0xFFFFFFFF}
 * refers to the sample count of the {@code ds64} chunk. The G.726 code size is the format's bits
 * per sample, or, when that is not between 2 and 5, its bit rate over its sampling rate. ADPCM in
 * a big-endian RIFX file raises {@link MediaException.Unsupported}.
 *
 * <p>A {@code data} chunk of coded audio is handed to the demuxer of its elementary stream, whose
 * track, packets, timestamps, duration and seeking this demuxer exposes unchanged, and whose tags
 * complete {@link #metadata()}:
 * <table class="striped">
 * <caption>Coded payloads</caption>
 * <thead><tr><th scope="col">Format</th><th scope="col">Data chunk</th><th scope="col">Read by</th></tr></thead>
 * <tbody>
 * <tr><td>{@code 0x0050}, {@code 0x0055}</td><td>MPEG audio frames</td><td>{@link MpegAudioDemuxer}</td></tr>
 * <tr><td>{@code 0x2000}, the {@code MEDIASUBTYPE_DOLBY_AC3} and {@code MEDIASUBTYPE_DOLBY_DDPLUS}
 * sub-formats</td><td>AC-3 or E-AC-3 sync frames</td><td>{@link Ac3Demuxer}</td></tr>
 * <tr><td>{@code 0x00FF}, {@code 0x1600}, {@code 0x1610}, {@code 0x4143}, {@code 0x706D},
 * {@code 0xA106}</td><td>AAC in ADTS framing</td><td>{@link AdtsDemuxer}</td></tr>
 * <tr><td>{@code 0x1602}, and the AAC tags above when their payload type or data is LOAS</td>
 * <td>AAC in LOAS framing</td><td>this demuxer</td></tr>
 * <tr><td>the AAC tags above when their payload type or data is neither ADTS nor LOAS</td>
 * <td>raw data blocks, optionally after an ADIF header</td><td>this demuxer</td></tr>
 * <tr><td>{@code 0xF1AC}</td><td>FLAC frames, after the {@code STREAMINFO} of the format's extra bytes
 * (or a native FLAC stream)</td><td>{@link FlacDemuxer}</td></tr>
 * <tr><td>{@code 0x0038}, {@code 0x0057}, {@code 0x00FE} (AMR-NB), {@code 0x0058} (AMR-WB)</td>
 * <td>RFC 4867 storage-format frames, with or without the storage magic</td><td>{@link AmrDemuxer}</td></tr>
 * <tr><td>{@code 0x704F}</td><td>Opus packets in the self-delimiting framing of RFC 6716 Appendix B</td>
 * <td>this demuxer</td></tr>
 * <tr><td>{@code 0x2001}, {@code 0x0008} whose data holds DTS frames rather than bursts, and 16-bit stereo PCM
 * whose samples carry DTS frames (a DTS audio CD)</td>
 * <td>DTS frames in the 16-bit or 14-bit form</td><td>this demuxer</td></tr>
 * <tr><td>{@code 0x0092}, {@code 0x0008}, the IEC 61937 sub-formats of AC-3, MPEG-1, MP3, MPEG-2, AAC, DTS,
 * E-AC-3, DTS-HD, DTS:X and MAT, and 16-bit stereo PCM whose samples carry IEC 61937 bursts</td><td>IEC 61937 data
 * bursts of AC-3, E-AC-3, MPEG audio, AAC, DTS and TrueHD</td><td>this demuxer</td></tr>
 * </tbody>
 * </table>
 *
 * <p>An AAC format tag selects the framing its {@code HEAACWAVEINFO} payload type declares, or, for
 * the other tags, the one the data starts with. LOAS frames are read as ISO/IEC 14496-3 clause
 * 1.7 defines them (one program and layer, {@code frameLengthType} 0): each subframe becomes a raw data
 * block of 1024 samples, the track takes the first {@code StreamMuxConfig}, refined for HE-AAC from
 * the first payload, and a later configuration that differs is announced on the next packet as its
 * new codec configuration. Raw data blocks carry no lengths, so each one's end is found by walking its
 * syntax (AAC Main, Low Complexity, Scalable Sample Rate and Long Term Prediction, also as the core of
 * HE-AAC and HE-AAC v2); each block becomes one packet of one frame, the track's configuration is the
 * {@code AudioSpecificConfig} of the format (after the {@code HEAACWAVEINFO} fields for {@code 0x1610}),
 * the one an ADIF header's program configuration gives, or a Low Complexity one built from the format,
 * and the error-resilient object types raise {@link MediaException.Unsupported}. DTS frames become one
 * packet each in the 16-bit big-endian form, with the DTS-HD extension substreams that follow them.
 * IEC 61937 bursts are followed from the first burst of a supported data type, by codec family and
 * bitstream number; each burst becomes one packet per access unit, stamped with the burst's position
 * on the IEC 61937 timeline, TrueHD access units are stamped continuously across the MAT frames that
 * carry them, and a burst whose error flag is set yields packets flagged
 * {@link com.github.auties00.smalt.track.PacketFlag#CORRUPT}. Bursts are carried by 16-bit stereo
 * samples, or by the 8 channels of the high bit rate formats (DTS-HD and MAT over HDMI), whose samples
 * hold the same sequence of 16-bit words. A 16-bit stereo or 8-channel PCM file is read as bursts
 * when its data starts, within its first 128 KiB, with a burst whose payload starts with a valid access
 * unit, and a stereo one as DTS frames when it starts with two consecutive DTS frames; other PCM files,
 * and bursts of other data types, read as PCM. Opus packets
 * are returned in the standard framing of RFC 6716 section 3; the track's {@code OpusHead} is the
 * format's extra bytes when they hold one, and is otherwise built from the channel count with a
 * pre-skip of zero. Any other format tag or sub-format GUID raises {@link MediaException.Unsupported}
 * naming it.
 *
 * <p>Chunks are walked in order, honoring the pad byte after odd-sized chunks (and tolerating
 * writers that omit it). A {@code data} chunk whose size is {@code 0} or {@code 0xFFFFFFFF}
 * outside RF64 is a streaming header and extends to the end of the file, as does a size larger
 * than the file. RF64/BW64 sizes come from the {@code ds64} chunk. Coded audio whose demuxer cannot tell
 * its duration takes the sample count of the {@code fact} chunk. {@code LIST INFO} entries, an embedded
 * {@code id3 } tag, and the Broadcast Wave {@code bext} (EBU Tech 3285), {@code iXML}, {@code axml},
 * {@code chna} (ITU-R BS.2088) and {@code smpl} chunks become {@link #metadata()}, under the keys
 * {@link com.github.auties00.smalt.metadata.TagEditor} reads them under (for example {@code originator},
 * {@code time_reference}, {@code coding_history}, {@code ixml}, {@code smpl:unity_note}). The cue points of the
 * {@code cue } chunk become {@link #chapters()}, titled by the {@code labl} entries of the {@code LIST adtl}
 * chunk and ended by its {@code ltxt} entries or at the next cue point, unless coded audio carries chapters of
 * its own.
 *
 * <p>PCM packets hold the largest power of two of frames that lasts at most 100 ms (4096 frames at
 * 44.1 and 48 kHz), capped at 1 MiB, with timestamps in samples.
 * A source that is not in memory is read in blocks of {@value #READ_BLOCK} bytes that never
 * extend past the sample data, rather than once per packet.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every packet which
 * bytes are no longer needed, and the chunks after {@code data} are read only when they lie within
 * the source's memory budget.
 */
public final class WavDemuxer implements Demuxer {
    /**
     * The largest packet, in bytes.
     */
    private static final int MAX_PACKET_BYTES = 1 << 20;

    /**
     * The size of the blocks the sample data of a source that is not in memory is read in.
     *
     * @implNote This implementation reads 256 KiB at a time, 16 packets of 16 KiB at 44.1 and 48 kHz stereo, which
     * turns one positional read per packet into one per 16 packets for a file source at the cost of one copy from the
     * block into each packet.
     */
    private static final int READ_BLOCK = 256 * 1024;

    /**
     * The largest {@code LIST} chunk whose entries are decoded into metadata.
     */
    private static final int MAX_LIST_SIZE = 1 << 20;

    /**
     * The largest {@code fmt } chunk read in full; the bytes after it are ignored.
     */
    private static final int MAX_FORMAT_SIZE = 1 << 16;

    /**
     * The number of bytes at the start of the data of a 16-bit stereo PCM file searched for an
     * IEC 61937 burst.
     */
    private static final int IEC61937_PROBE = 128 * 1024;

    /**
     * The number of bytes at the start of the data of a file that declares IEC 61937 searched for its
     * first burst.
     */
    private static final int IEC61937_SEARCH = 4 << 20;

    /**
     * The {@code wPayloadType} values of an {@code HEAACWAVEINFO}, and the framing found at the start of
     * an AAC data chunk.
     */
    private enum AacFraming {
        RAW, ADTS, ADIF, LOAS, UNKNOWN
    }

    /**
     * The kinds of data chunk payload.
     */
    private enum Payload {
        PCM, ADPCM, MPEG, AC3, AAC, FLAC, AMR, OPUS, DTS, IEC61937
    }

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The single audio track.
     */
    private final AudioTrack track;

    /**
     * The demuxer of the coded audio stored in the {@code data} chunk, or {@code null} for PCM.
     */
    private final Demuxer coded;

    /**
     * The track list exposed by {@link #tracks()}.
     */
    private final List<Track> tracks;

    /**
     * The container metadata.
     */
    private final Map<String, String> metadata;

    /**
     * The chapters of the cue points.
     */
    private final List<Chapter> cueChapters;

    /**
     * The offset of the first sample byte.
     */
    private final long dataStart;

    /**
     * The offset of the end of the sample data, or {@code -1} when it extends to an unknown end.
     */
    private final long dataEnd;

    /**
     * The size of one interleaved frame in bytes.
     */
    private final int frameSize;

    /**
     * The number of frames per packet.
     */
    private final int packetFrames;

    /**
     * The samples per channel of one frame of ADPCM (a block or a unit of codes), or 0 for PCM, whose frames are
     * samples.
     */
    private final int unitSamples;

    /**
     * The channel count.
     */
    private final int channels;

    /**
     * The G.726 code size in bits, or 0.
     */
    private final int codeSize;

    /**
     * The samples per channel of ADPCM the packets end at, or {@code -1} when unknown.
     */
    private final long sampleLimit;

    /**
     * The offset of the next packet.
     */
    private long position;

    /**
     * The read-ahead window over the sample data, or {@code null} for coded audio.
     */
    private final ReadAheadCache data;

    /**
     * The index of the first frame of the next packet.
     */
    private long frameIndex;

    /**
     * Whether the end of the sample data was reached.
     */
    private boolean finished;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the file is not a valid WAVE file
     * @throws MediaException.Unsupported if the file uses a format this demuxer cannot read
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public WavDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file is not a valid WAVE file
     * @throws MediaException.Unsupported if the file uses a format this demuxer cannot read
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public WavDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private WavDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(limits, "limits");
        try {
            var header = new Header();
            parse(new SourceReader(source, 4096), header);
            if (header.payload == Payload.PCM && (header.channels == 2 || header.channels == 8)
                    && (header.codec == Codec.PCM_S16LE || header.codec == Codec.PCM_S16BE)) {
                detectBursts(header);
            }
            if (header.payload != Payload.PCM && header.payload != Payload.ADPCM) {
                this.coded = openPayload(header);
                for (var entry : coded.metadata().entrySet()) {
                    header.metadata.putIfAbsent(entry.getKey(), entry.getValue());
                }
                this.metadata = Collections.unmodifiableMap(header.metadata);
                var inner = (AudioTrack) coded.tracks().getFirst();
                if (inner.duration() < 0 && header.sampleCount() > 0) {
                    inner = inner.toBuilder().duration(Rational.timeBaseOf(header.sampleRate)
                            .rescale(header.sampleCount(), inner.timeBase())).build();
                }
                this.track = inner;
                this.tracks = List.of(track);
                this.cueChapters = cueChapters(header, inner.durationMicros().orElse(-1));
                this.frameSize = 1;
                this.packetFrames = 1;
                this.unitSamples = 0;
                this.channels = header.channels;
                this.codeSize = 0;
                this.sampleLimit = -1;
                this.dataStart = header.dataStart;
                this.dataEnd = header.dataEnd;
                this.data = null;
                return;
            }
            this.coded = null;
            this.metadata = Collections.unmodifiableMap(header.metadata);
            this.frameSize = header.blockAlign;
            this.channels = header.channels;
            this.dataStart = header.dataStart;
            this.dataEnd = header.dataEnd;
            this.position = dataStart;
            this.data = new ReadAheadCache(source, READ_BLOCK, 0, dataEnd < 0 ? Long.MAX_VALUE : dataEnd);
            long frames;
            if (header.payload == Payload.ADPCM) {
                this.unitSamples = header.unitSamples;
                this.codeSize = header.codeSize;
                this.packetFrames = adpcmPacketUnits(header);
                var codedSamples = dataEnd < 0 ? -1 : codedSamples(header.codec, dataEnd - dataStart);
                var factSamples = header.sampleCount();
                if (factSamples > 0) {
                    frames = codedSamples < 0 ? factSamples : Math.min(codedSamples, factSamples);
                } else {
                    frames = codedSamples;
                }
                this.sampleLimit = frames;
            } else {
                this.unitSamples = 0;
                this.codeSize = 0;
                this.sampleLimit = -1;
                this.packetFrames = Math.max(1, Math.min(Integer.highestOneBit(Math.max(1, header.sampleRate / 10)),
                        MAX_PACKET_BYTES / frameSize));
                frames = dataEnd < 0 ? -1 : (dataEnd - dataStart) / frameSize;
            }
            this.track = AudioTrack.builder().codec(header.codec).timeBase(Rational.timeBaseOf(header.sampleRate))
                    .duration(frames)
                    .codecConfig(header.config)
                    .sampleRate(header.sampleRate)
                    .layout(header.layout)
                    .bitsPerSample(header.validBits)
                    .build();
            this.tracks = List.of(track);
            this.cueChapters = cueChapters(header, track.durationMicros().orElse(-1));
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid WAVE file", exception);
        }
    }

    /**
     * Collects the values parsed from the chunks.
     */
    private static final class Header {
        /**
         * Whether the fields are big-endian (RIFX).
         */
        private boolean bigEndian;

        /**
         * Whether the file is RF64 or BW64.
         */
        private boolean rf64;

        /**
         * The 64-bit size of the {@code data} chunk from {@code ds64}, or {@code -1}.
         */
        private long ds64DataSize = -1;

        /**
         * The 64-bit sample count of {@code ds64}, or {@code -1}.
         */
        private long ds64Samples = -1;

        /**
         * Whether a {@code fmt } chunk was seen.
         */
        private boolean hasFormat;

        /**
         * The kind of payload the {@code data} chunk holds.
         */
        private Payload payload = Payload.PCM;

        /**
         * The parsed {@code fmt } chunk.
         */
        private WaveFormat format;

        /**
         * The channel count.
         */
        private int channels;

        /**
         * The first IEC 61937 burst of an {@link Payload#IEC61937} payload, once found.
         */
        private Iec61937Demuxer.Start burst;

        /**
         * The sample count of the {@code fact} chunk, or {@code -1}.
         */
        private long factSamples = -1;

        /**
         * Returns the sample count of the file: the {@code fact} count, or the {@code ds64} one of an RF64 file whose
         * {@code fact} count is {@code 0xFFFFFFFF}, the value that refers to it (EBU Tech 3306).
         *
         * @return the samples per channel, or {@code -1} without a {@code fact} chunk
         */
        private long sampleCount() {
            return rf64 && factSamples == 0xFFFFFFFFL && ds64Samples >= 0 ? ds64Samples : factSamples;
        }

        /**
         * The sample coding.
         */
        private Codec codec;

        /**
         * The sampling rate.
         */
        private int sampleRate;

        /**
         * The number of meaningful bits per sample.
         */
        private int validBits;

        /**
         * The size of one frame in bytes: of a sample of every channel for PCM, of a block or a unit of codes for
         * ADPCM.
         */
        private int blockAlign;

        /**
         * The samples per channel of an ADPCM block or unit of codes.
         */
        private int unitSamples;

        /**
         * The G.726 code size in bits.
         */
        private int codeSize;

        /**
         * The codec configuration of ADPCM, or {@code null}.
         */
        private byte[] config;

        /**
         * The channel layout.
         */
        private ChannelLayout layout;

        /**
         * The offset of the first sample byte, or {@code -1} before the {@code data} chunk.
         */
        private long dataStart = -1;

        /**
         * The offset of the end of the samples, or {@code -1} when unknown.
         */
        private long dataEnd = -1;

        /**
         * The metadata collected so far.
         */
        private final Map<String, String> metadata = new LinkedHashMap<>();

        /**
         * The payload of the first {@code cue } chunk, or {@code null}.
         */
        private byte[] cue;

        /**
         * The payload of the first {@code LIST} chunk of form {@code adtl}, or {@code null}.
         */
        private byte[] adtl;
    }

    /**
     * Walks the chunks of the file.
     *
     * @param reader the reader positioned at offset zero
     * @param header receives the parsed values
     */
    private void parse(SourceReader reader, Header header) {
        var riff = reader.fourCc();
        switch (riff) {
            case 0x52494646 -> {
            }
            case 0x52494658 -> header.bigEndian = true;
            case 0x52463634, 0x42573634 -> header.rf64 = true;
            default -> throw new MediaException.Malformed("not a RIFF WAVE file");
        }
        reader.skip(4);
        if (reader.fourCc() != 0x57415645) {
            throw new MediaException.Malformed("RIFF file is not of form WAVE");
        }
        var size = source.size();
        var first = true;
        while (true) {
            var chunkStart = reader.position();
            if (size >= 0 && size - chunkStart < 8) {
                break;
            }
            if (size < 0 && reader.atEnd()) {
                break;
            }
            var id = reader.fourCc();
            var chunkSize = header.bigEndian ? reader.u32be() : reader.u32le();
            var body = reader.position();
            if (header.rf64 && first) {
                if (id != 0x64733634) {
                    throw new MediaException.Malformed("RF64 file without a leading ds64 chunk");
                }
                parseDs64(reader, header, chunkSize);
            }
            first = false;
            switch (id) {
                case 0x666D7420 -> parseFormat(reader, header, chunkSize);
                case 0x64617461 -> {
                    if (header.dataStart >= 0) {
                        break;
                    }
                    header.dataStart = body;
                    var dataSize = chunkSize;
                    if (header.rf64 && chunkSize == 0xFFFFFFFFL) {
                        dataSize = header.ds64DataSize;
                    } else if (!header.rf64 && (chunkSize == 0 || chunkSize == 0xFFFFFFFFL)) {
                        dataSize = -1;
                    }
                    header.dataEnd = dataSize < 0 ? size : size < 0 ? body + dataSize : Math.min(size, body + dataSize);
                    if (dataSize < 0 || size < 0 || header.dataEnd < body + dataSize || size - (body + dataSize) < 8
                            || !ForwardOnlySource.isCheapToReach(source, body + dataSize + 8)) {
                        if (!header.hasFormat) {
                            throw new MediaException.Malformed("WAVE file without a fmt chunk before its data");
                        }
                        return;
                    }
                    chunkSize = dataSize;
                }
                case 0x4C495354 -> parseList(reader, header, chunkSize);
                case 0x62657874, 0x69584D4C, 0x61786D6C, 0x63686E61, 0x736D706C, 0x63756520 ->
                        parseDescriptive(reader, header, id, chunkSize);
                case 0x66616374 -> {
                    if (chunkSize >= 4 && reader.remaining() >= 4) {
                        header.factSamples = header.bigEndian ? reader.u32be() : reader.u32le();
                    }
                }
                case 0x69643320, 0x49443320 -> {
                    if (size < 0 || chunkSize <= size - body) {
                        Id3v2.skip(source, body, body + chunkSize, header.metadata);
                    }
                }
                default -> {
                }
            }
            if (size >= 0 && chunkSize > size - body) {
                break;
            }
            var next = body + chunkSize;
            if ((chunkSize & 1) != 0) {
                next = paddedChunkStart(next, size);
            }
            reader.seek(next);
        }
        if (!header.hasFormat) {
            throw new MediaException.Malformed("WAVE file without a fmt chunk");
        }
        if (header.dataStart < 0) {
            throw new MediaException.Malformed("WAVE file without a data chunk");
        }
    }

    /**
     * Returns where the chunk after an odd-sized chunk starts, tolerating a missing pad byte.
     *
     * @param unpadded the offset right after the odd-sized chunk body
     * @param size the source size, or {@code -1} when unknown
     * @return {@code unpadded + 1}, or {@code unpadded} when only that offset holds a plausible chunk identifier
     */
    private long paddedChunkStart(long unpadded, long size) {
        if (size >= 0 && size - unpadded < 9) {
            return unpadded + 1;
        }
        var probe = new byte[5];
        if (SourceBytes.readAvailable(source, unpadded, probe, 0, 5) < 5) {
            return unpadded + 1;
        }
        var paddedValid = isChunkId(probe, 1);
        var unpaddedValid = isChunkId(probe, 0);
        return !paddedValid && unpaddedValid ? unpadded : unpadded + 1;
    }

    /**
     * Returns whether four bytes look like a chunk identifier (printable ASCII).
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return {@code true} when every byte is printable
     */
    private static boolean isChunkId(byte[] data, int offset) {
        for (var i = 0; i < 4; i++) {
            var b = data[offset + i] & 0xFF;
            if (b < 0x20 || b > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses the RF64 {@code ds64} chunk.
     *
     * @param reader the reader positioned at the chunk body
     * @param header receives the data size
     * @param chunkSize the chunk body size
     */
    private static void parseDs64(SourceReader reader, Header header, long chunkSize) {
        if (chunkSize < 24) {
            throw new MediaException.Malformed("ds64 chunk of " + chunkSize + " bytes is too small");
        }
        reader.skip(8);
        var dataSize = reader.u64le();
        if (dataSize < 0) {
            throw new MediaException.Malformed("ds64 data size is negative");
        }
        header.ds64DataSize = dataSize;
        header.ds64Samples = reader.u64le();
    }

    /**
     * Parses the {@code fmt } chunk.
     *
     * @param reader the reader positioned at the chunk body
     * @param header receives the format
     * @param chunkSize the chunk body size
     */
    private void parseFormat(SourceReader reader, Header header, long chunkSize) {
        if (header.hasFormat) {
            return;
        }
        if (chunkSize < 16) {
            throw new MediaException.Malformed("fmt chunk of " + chunkSize + " bytes is too small");
        }
        var bytes = new byte[(int) Math.min(Math.min(chunkSize, reader.remaining()), MAX_FORMAT_SIZE)];
        if (bytes.length < 16) {
            throw new MediaException.Malformed("truncated fmt chunk");
        }
        reader.readFully(bytes, 0, bytes.length);
        var format = WaveFormat.parse(bytes, bytes.length, header.bigEndian);
        var tag = format.tag();
        var channels = format.channels();
        var sampleRate = format.sampleRate();
        var bits = format.bitsPerSample();
        LimitChecks.checkChannels(limits, channels);
        if (sampleRate == 0 || sampleRate > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("invalid WAVE sample rate " + sampleRate);
        }
        if (tag == WaveFormat.EXTENSIBLE && !format.extensible()) {
            throw new MediaException.Malformed("WAVE_FORMAT_EXTENSIBLE fmt chunk of " + chunkSize + " bytes");
        }
        header.format = format;
        header.channels = channels;
        header.sampleRate = (int) sampleRate;
        // TODO: the ambisonic layouts that BW64 ADM metadata (chna and axml HOA type definitions) declares
        header.layout = format.layout();
        header.hasFormat = true;
        var payload = payload(format);
        var dtsTag = tag == WaveFormat.DTS_SPDIF && format.subFormat() != WaveFormat.SubFormat.IEC61937;
        if (payload == Payload.IEC61937 && (channels != 2 && channels != 8 || bits != 16)) {
            if (!dtsTag) {
                throw new MediaException.Malformed("IEC 61937 WAVE data of " + channels + " channels of " + bits
                        + " bits");
            }
            payload = Payload.DTS;
        }
        if (payload == Payload.ADPCM) {
            if (header.bigEndian) {
                throw new MediaException.Unsupported(String.format("rifx format 0x%04x", tag),
                        "ADPCM in a big-endian RIFX file is not supported");
            }
            header.payload = payload;
            parseAdpcm(format, header);
            LimitChecks.checkAllocation(limits, (long) header.blockAlign * adpcmPacketUnits(header));
            return;
        }
        if (payload != Payload.PCM && payload != Payload.IEC61937) {
            header.payload = payload;
            header.codec = format.codec();
            return;
        }
        header.payload = payload;
        var containerBytes = containerBytes(channels, format.blockAlign(), bits);
        header.codec = codec(payload == Payload.IEC61937 ? WaveFormat.PCM : tag, containerBytes, header.bigEndian);
        header.blockAlign = containerBytes * channels;
        header.validBits = header.codec == Codec.PCM_ALAW || header.codec == Codec.PCM_MULAW ? 8 : Math.max(format.validBits(), 1);
        LimitChecks.checkAllocation(limits, Math.min(MAX_PACKET_BYTES, (long) header.blockAlign * Integer.highestOneBit(Math.max(1, header.sampleRate / 10))));
    }

    /**
     * Reads the format of ADPCM or telephony audio.
     *
     * @param format the parsed {@code fmt } chunk, of an ADPCM tag
     * @param header receives the codec, the frame layout and the configuration
     * @throws MediaException.Unsupported if the format uses a variant that is not readable: IMA or Microsoft ADPCM of
     * other than 4 bits per sample, GSM 06.10 in blocks of other than 65 bytes, G.722, G.726 or GSM 06.10 of more
     * than one channel
     * @throws MediaException.Malformed if the format has no channels, or its block alignment or G.726 code size is
     * invalid
     */
    private static void parseAdpcm(WaveFormat format, Header header) {
        var tag = format.tag();
        var channels = format.channels();
        var bits = format.bitsPerSample();
        var codec = switch (tag) {
            case WaveFormat.IMA_ADPCM -> Codec.ADPCM_IMA_WAV;
            case WaveFormat.MS_ADPCM -> Codec.ADPCM_MS;
            case WaveFormat.GSM610 -> Codec.GSM_MS;
            case WaveFormat.G722 -> Codec.ADPCM_G722;
            default -> Codec.ADPCM_G726;
        };
        if (channels == 0) {
            throw new MediaException.Malformed(codec.displayName() + " of no channels");
        }
        header.codec = codec;
        if (AdpcmBlocks.hasBlocks(codec)) {
            if (bits != 4) {
                throw new MediaException.Unsupported(codec.displayName() + " " + bits + "-bit",
                        bits + "-bit " + codec.displayName() + " is not supported, only 4-bit");
            }
            var block = format.blockAlign();
            if (!AdpcmBlocks.isBlock(codec, block, channels)) {
                throw new MediaException.Malformed(codec.displayName() + " block of " + block + " bytes for " + channels
                        + " channels");
            }
            header.blockAlign = block;
            header.unitSamples = (int) AdpcmBlocks.samples(codec, block, channels, 0);
            header.validBits = 4;
            if (codec == Codec.ADPCM_MS) {
                header.config = msConfig(format.extra());
            }
            return;
        }
        if (channels != 1) {
            throw new MediaException.Unsupported(codec.displayName() + " " + channels + " channels",
                    codec.displayName() + " of " + channels + " channels is not supported, only mono");
        }
        if (codec == Codec.GSM_MS && format.blockAlign() != AdpcmBlocks.GSM_BLOCK) {
            throw new MediaException.Unsupported("gsm_ms block " + format.blockAlign(),
                    "GSM 06.10 in blocks of " + format.blockAlign() + " bytes is not supported, only 65");
        }
        var codeSize = 0;
        if (codec == Codec.ADPCM_G726) {
            codeSize = bits;
            if (codeSize < 2 || codeSize > 5) {
                // a format without the code size states it through the bit rate
                var rate = format.byteRate() * 8;
                codeSize = rate % format.sampleRate() == 0 ? (int) Math.min(rate / format.sampleRate(), 6) : 0;
            }
            if (codeSize < 2 || codeSize > 5) {
                throw new MediaException.Malformed("G.726 of " + bits + " bits per sample at " + format.byteRate()
                        + " bytes per second");
            }
            header.validBits = codeSize;
        }
        header.codeSize = codeSize;
        header.blockAlign = AdpcmBlocks.unitBytes(codec, codeSize);
        header.unitSamples = AdpcmBlocks.unitSamples(codec, codeSize);
    }

    /**
     * Returns the configuration of Microsoft ADPCM from the extra bytes of its format.
     *
     * @param extra the {@code ADPCMWAVEFORMAT} fields after {@code cbSize}
     * @return {@code wSamplesPerBlock}, {@code wNumCoef} and the coefficient table, without the bytes after it, or
     * {@code null} when the bytes hold no table or a shorter one than {@code wNumCoef} declares, so that the standard
     * coefficients apply
     */
    private static byte[] msConfig(byte[] extra) {
        if (extra.length < 4) {
            return null;
        }
        var count = (extra[2] & 0xFF) | (extra[3] & 0xFF) << 8;
        var length = 4 + 4 * count;
        if (count == 0 || extra.length < length) {
            return null;
        }
        return extra.length == length ? extra : Arrays.copyOf(extra, length);
    }

    /**
     * Returns the number of blocks or units of codes of an ADPCM packet: one block, or the units of the largest power
     * of two of samples that lasts at most 100 ms, at least one, within {@value #MAX_PACKET_BYTES} bytes.
     *
     * @param header the parsed format of ADPCM
     * @return the units per packet
     */
    private static int adpcmPacketUnits(Header header) {
        if (AdpcmBlocks.hasBlocks(header.codec)) {
            return 1;
        }
        var units = Integer.highestOneBit(Math.max(1, header.sampleRate / 10)) / header.unitSamples;
        return Math.max(1, Math.min(units, MAX_PACKET_BYTES / header.blockAlign));
    }

    /**
     * Returns the samples per channel the ADPCM of a {@code data} chunk codes: those of its whole frames and of the
     * last packet its remaining bytes make.
     *
     * @param codec the ADPCM codec
     * @param bytes the size of the sample data
     * @return the samples per channel
     */
    private long codedSamples(Codec codec, long bytes) {
        var units = bytes / frameSize;
        var rest = (int) (bytes - units * frameSize);
        var tail = AdpcmBlocks.tail(codec, rest, channels, codeSize);
        return units * unitSamples + (tail == 0 ? 0 : AdpcmBlocks.samples(codec, tail, channels, codeSize));
    }

    /**
     * Classifies the payload a format announces.
     *
     * @param format the parsed {@code fmt } chunk
     * @return the payload kind
     * @throws MediaException.Unsupported if the sub-format GUID, the IEC 61937 data type or the format tag is not
     * readable
     */
    private static Payload payload(WaveFormat format) {
        var tag = format.tag();
        return switch (format.subFormat()) {
            case UNKNOWN -> throw new MediaException.Unsupported("wav subformat",
                    "unsupported WAVE_FORMAT_EXTENSIBLE sub-format GUID");
            case DOLBY_AC3, DOLBY_DIGITAL_PLUS -> Payload.AC3;
            case AMBISONIC -> Payload.PCM;
            case IEC61937 -> switch (tag) {
                // AC-3, MPEG-1 Layer I and II, MP3, MPEG-2, AAC, DTS, E-AC-3 (and with Atmos), DTS-HD (and DTS:X
                // E1 and E2), MAT (MLP, MAT 2.0 and MAT 2.1)
                case 2, 3, 4, 5, 6, 7, 10, 0x10A, 11, 0x10B, 0x30B, 12, 0x10C, 0x30C -> Payload.IEC61937;
                // TODO: bursts of ATRAC (8), one-bit audio (9), DST (13) and WMA Professional (14), once Smalt has
                // their codecs
                default -> throw new MediaException.Unsupported("wav iec61937 type " + tag,
                        "IEC 61937 data of CEA-861 audio coding type " + tag + " is not supported");
            };
            case NONE, STANDARD -> switch (tag) {
                case WaveFormat.PCM, WaveFormat.FLOAT, WaveFormat.ALAW, WaveFormat.MULAW -> Payload.PCM;
                case WaveFormat.IMA_ADPCM, WaveFormat.MS_ADPCM, WaveFormat.GSM610, WaveFormat.G722, WaveFormat.G726 ->
                        Payload.ADPCM;
                case WaveFormat.DOLBY_AC3_SPDIF, WaveFormat.DTS_SPDIF -> Payload.IEC61937;
                case WaveFormat.MPEG, WaveFormat.MPEG_LAYER3 -> Payload.MPEG;
                default -> switch (WaveFormat.codec(tag, format.bitsPerSample())) {
                    case AC3 -> Payload.AC3;
                    case AAC -> Payload.AAC;
                    case FLAC -> Payload.FLAC;
                    case OPUS -> Payload.OPUS;
                    case AMR_NB, AMR_WB -> Payload.AMR;
                    case DTS -> Payload.DTS;
                    default -> throw new MediaException.Unsupported(String.format("wav format 0x%04x", tag),
                            String.format("WAVE format tag 0x%04x is not supported", tag));
                };
            };
        };
    }

    /**
     * Switches a 16-bit stereo or 8-channel PCM file to IEC 61937 bursts when its data starts with one, or a stereo
     * one to DTS frames when it starts with two of them, and finds the first burst of a file that declares them.
     *
     * @param header the parsed values
     */
    private void detectBursts(Header header) {
        var probe = header.dataEnd < 0 ? IEC61937_PROBE : Math.min(IEC61937_PROBE, header.dataEnd - header.dataStart);
        if (!ForwardOnlySource.isCheapToReach(source, header.dataStart + probe)) {
            return;
        }
        var start = Iec61937Demuxer.locate(source, header.dataStart, header.dataEnd, probe);
        if (start != null) {
            header.payload = Payload.IEC61937;
            header.burst = start;
        } else if (header.channels == 2 && DtsFramesDemuxer.startsWithFrames(source, header.dataStart, header.dataEnd)) {
            header.payload = Payload.DTS;
        }
    }

    /**
     * Opens the demuxer of the coded audio stored in the {@code data} chunk.
     *
     * @param header the parsed values
     * @return the demuxer
     */
    private Demuxer openPayload(Header header) {
        var start = header.dataStart;
        var length = header.dataEnd < 0 ? -1 : header.dataEnd - start;
        var options = DemuxerOptions.builder().limits(limits).build();
        var format = header.format;
        var lead = leadingBytes(header, 16);
        return switch (header.payload) {
            case MPEG -> new MpegAudioDemuxer(new SlicedSource(source, start, length), options);
            case AC3 -> new Ac3Demuxer(new SlicedSource(source, start, length), options);
            case AAC -> switch (aacFraming(format, lead)) {
                case ADTS -> new AdtsDemuxer(new SlicedSource(source, start, length), options);
                case LOAS -> new LoasDemuxer(source, start, header.dataEnd, limits);
                default -> new RawAacDemuxer(source, start, header.dataEnd, limits, aacConfig(format),
                        (int) Math.min(format.sampleRate(), Integer.MAX_VALUE), format.channels());
            };
            case FLAC -> new FlacDemuxer(flacSource(format, lead, start, length), options);
            case AMR -> {
                var magic = (WaveFormat.codec(format.tag(), 0) == Codec.AMR_WB ? "#!AMR-WB\n" : "#!AMR\n")
                        .getBytes(StandardCharsets.US_ASCII);
                var stored = startsWith(lead, "#!AMR".getBytes(StandardCharsets.US_ASCII));
                yield new AmrDemuxer(stored ? new SlicedSource(source, start, length)
                        : new PrefixedSource(magic, source, start, length), options);
            }
            case DTS -> new DtsFramesDemuxer(source, start, header.dataEnd, limits);
            case OPUS -> new SelfDelimitedOpusDemuxer(source, start, header.dataEnd, format.channels(),
                    format.sampleRate(), format.extra(), limits);
            case IEC61937 -> {
                var burst = header.burst;
                if (burst == null) {
                    var search = header.dataEnd < 0 ? IEC61937_SEARCH : Math.min(IEC61937_SEARCH, length);
                    burst = Iec61937Demuxer.locate(source, start, header.dataEnd, search);
                }
                if (burst == null && format.tag() == WaveFormat.DTS_SPDIF
                        && format.subFormat() != WaveFormat.SubFormat.IEC61937
                        && DtsFramesDemuxer.startsWithFrames(source, start, header.dataEnd)) {
                    yield new DtsFramesDemuxer(source, start, header.dataEnd, limits);
                }
                if (burst == null) {
                    throw new MediaException.Malformed("IEC 61937 WAVE data without a burst of a supported data type");
                }
                yield new Iec61937Demuxer(source, start, header.dataEnd, limits, burst);
            }
            case PCM, ADPCM -> throw new IllegalStateException("PCM and ADPCM are not handed to another demuxer");
        };
    }

    /**
     * Reads the first bytes of the {@code data} chunk.
     *
     * @param header the parsed values
     * @param count the number of bytes wanted
     * @return the bytes, fewer when the data is shorter
     */
    private byte[] leadingBytes(Header header, int count) {
        var wanted = header.dataEnd < 0 ? count : (int) Math.min(count, header.dataEnd - header.dataStart);
        var bytes = new byte[Math.max(0, wanted)];
        var done = 0;
        while (done < bytes.length) {
            var read = source.read(header.dataStart + done, bytes, done, bytes.length - done);
            if (read <= 0) {
                break;
            }
            done += read;
        }
        return done == bytes.length ? bytes : Arrays.copyOf(bytes, done);
    }

    /**
     * Returns whether bytes start with a prefix.
     *
     * @param data the bytes
     * @param prefix the bytes {@code data} must start with
     * @return {@code true} when {@code data} starts with {@code prefix}
     */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    /**
     * Returns the framing of an AAC payload.
     *
     * @param format the parsed {@code fmt } chunk
     * @param lead the first bytes of the data
     * @return the framing the format declares, or else the one the data starts with
     */
    private static AacFraming aacFraming(WaveFormat format, byte[] lead) {
        var declared = switch (format.tag()) {
            case WaveFormat.MPEG_ADTS_AAC -> AacFraming.ADTS;
            case WaveFormat.MPEG_LOAS -> AacFraming.LOAS;
            case WaveFormat.MPEG_HEAAC -> {
                var extra = format.extra();
                if (extra.length < 2) {
                    yield AacFraming.UNKNOWN;
                }
                yield switch ((extra[0] & 0xFF) | (extra[1] & 0xFF) << 8) {
                    case 0 -> AacFraming.RAW;
                    case 1 -> AacFraming.ADTS;
                    case 2 -> AacFraming.ADIF;
                    case 3 -> AacFraming.LOAS;
                    default -> AacFraming.UNKNOWN;
                };
            }
            default -> AacFraming.UNKNOWN;
        };
        if (declared != AacFraming.UNKNOWN) {
            return declared;
        }
        if (lead.length >= 2 && (lead[0] & 0xFF) == 0xFF && (lead[1] & 0xF6) == 0xF0
                || startsWith(lead, "ID3".getBytes(StandardCharsets.US_ASCII))) {
            return AacFraming.ADTS;
        }
        if (lead.length >= 2 && lead[0] == 0x56 && (lead[1] & 0xE0) == 0xE0) {
            return AacFraming.LOAS;
        }
        return startsWith(lead, "ADIF".getBytes(StandardCharsets.US_ASCII)) ? AacFraming.ADIF : AacFraming.RAW;
    }

    /**
     * Returns the {@code AudioSpecificConfig} an AAC format declares: the bytes after the 12-byte
     * {@code HEAACWAVEINFO} fields for {@code WAVE_FORMAT_MPEG_HEAAC}, and the extra bytes for the other tags.
     *
     * @param format the parsed {@code fmt } chunk
     * @return the configuration, or {@code null} when the format declares none
     */
    private static byte[] aacConfig(WaveFormat format) {
        var extra = format.extra();
        var skip = format.tag() == WaveFormat.MPEG_HEAAC ? 12 : 0;
        return extra.length >= skip + 2 ? Arrays.copyOfRange(extra, skip, extra.length) : null;
    }

    /**
     * Returns the FLAC stream of a data chunk: the data itself when it starts with the {@code fLaC} marker,
     * the data after the marker and the {@code STREAMINFO} block of the format's extra bytes when they hold
     * one, and otherwise the bare frames.
     *
     * @param format the parsed {@code fmt } chunk
     * @param lead the first bytes of the data
     * @param start the offset of the data
     * @param length the data length, or {@code -1} when unknown
     * @return the source of the stream
     */
    private MediaSource flacSource(WaveFormat format, byte[] lead, long start, long length) {
        var marker = "fLaC".getBytes(StandardCharsets.US_ASCII);
        var extra = format.extra();
        if (startsWith(lead, marker)) {
            return new SlicedSource(source, start, length);
        }
        if (extra.length == 34) {
            var prefix = new byte[4 + 4 + 34];
            System.arraycopy(marker, 0, prefix, 0, 4);
            prefix[4] = (byte) 0x80;
            prefix[7] = 34;
            System.arraycopy(extra, 0, prefix, 8, 34);
            return new PrefixedSource(prefix, source, start, length);
        }
        if (startsWith(extra, marker)) {
            return new PrefixedSource(extra, source, start, length);
        }
        return new SlicedSource(source, start, length);
    }

    /**
     * Returns the size of one sample container in bytes.
     *
     * @param channels the channel count
     * @param blockAlign the declared block alignment
     * @param bits the declared bits per sample
     * @return the container size, from the block alignment when it is consistent, otherwise from the bit depth
     */
    private static int containerBytes(int channels, int blockAlign, int bits) {
        var fromBits = (bits + 7) / 8;
        if (blockAlign > 0 && blockAlign % channels == 0) {
            var fromAlign = blockAlign / channels;
            if (fromAlign >= fromBits && (fromAlign <= 4 || fromAlign == 8)) {
                return fromAlign;
            }
        }
        if (fromBits == 0) {
            throw new MediaException.Malformed("WAVE sample size of 0 bits");
        }
        return fromBits;
    }

    /**
     * Maps a format tag and container size to a codec.
     *
     * @param tag the format tag
     * @param containerBytes the sample container size in bytes
     * @param bigEndian whether samples are big-endian (RIFX)
     * @return the codec
     * @throws MediaException.Unsupported if the combination is not decodable
     */
    private static Codec codec(int tag, int containerBytes, boolean bigEndian) {
        return switch (tag) {
            case WaveFormat.PCM -> switch (containerBytes) {
                case 1 -> Codec.PCM_U8;
                case 2 -> bigEndian ? Codec.PCM_S16BE : Codec.PCM_S16LE;
                case 3 -> bigEndian ? Codec.PCM_S24BE : Codec.PCM_S24LE;
                case 4 -> bigEndian ? Codec.PCM_S32BE : Codec.PCM_S32LE;
                case 8 -> bigEndian ? Codec.PCM_S64BE : Codec.PCM_S64LE;
                default -> throw new MediaException.Unsupported("pcm_s" + containerBytes * 8,
                        containerBytes * 8 + "-bit integer PCM is not supported");
            };
            case WaveFormat.FLOAT -> switch (containerBytes) {
                case 4 -> bigEndian ? Codec.PCM_F32BE : Codec.PCM_F32LE;
                case 8 -> bigEndian ? Codec.PCM_F64BE : Codec.PCM_F64LE;
                default -> throw new MediaException.Unsupported("pcm_f" + containerBytes * 8,
                        containerBytes * 8 + "-bit float PCM is not supported");
            };
            case WaveFormat.ALAW -> requireByte(Codec.PCM_ALAW, containerBytes);
            case WaveFormat.MULAW -> requireByte(Codec.PCM_MULAW, containerBytes);
            default -> throw new MediaException.Unsupported(String.format("wav format 0x%04x", tag),
                    String.format("WAVE format tag 0x%04x is not supported", tag));
        };
    }

    /**
     * Checks that a G.711 stream uses one-byte samples.
     *
     * @param codec the G.711 codec
     * @param containerBytes the sample container size
     * @return {@code codec}
     * @throws MediaException.Malformed if the container is not one byte
     */
    private static Codec requireByte(Codec codec, int containerBytes) {
        if (containerBytes != 1) {
            throw new MediaException.Malformed(codec.displayName() + " with " + containerBytes + "-byte samples");
        }
        return codec;
    }

    /**
     * Parses a {@code LIST} chunk, decoding {@code INFO} entries into metadata.
     *
     * @param reader the reader positioned at the chunk body
     * @param header receives the metadata
     * @param chunkSize the chunk body size
     */
    private void parseList(SourceReader reader, Header header, long chunkSize) {
        if (chunkSize < 4 || chunkSize > MAX_LIST_SIZE || chunkSize > reader.remaining()) {
            return;
        }
        var body = reader.bytes(chunkSize, MAX_LIST_SIZE);
        if (body[0] == 'a' && body[1] == 'd' && body[2] == 't' && body[3] == 'l') {
            if (header.adtl == null) {
                header.adtl = body;
            }
            return;
        }
        if (body[0] != 'I' || body[1] != 'N' || body[2] != 'F' || body[3] != 'O') {
            return;
        }
        var offset = 4;
        while (body.length - offset >= 8) {
            var key = new String(body, offset, 4, StandardCharsets.ISO_8859_1);
            var size = header.bigEndian
                    ? (body[offset + 4] & 0xFFL) << 24 | (body[offset + 5] & 0xFF) << 16 | (body[offset + 6] & 0xFF) << 8 | (body[offset + 7] & 0xFF)
                    : (body[offset + 7] & 0xFFL) << 24 | (body[offset + 6] & 0xFF) << 16 | (body[offset + 5] & 0xFF) << 8 | (body[offset + 4] & 0xFF);
            offset += 8;
            if (size > body.length - offset) {
                return;
            }
            var end = offset + (int) size;
            var textEnd = offset;
            while (textEnd < end && body[textEnd] != 0) {
                textEnd++;
            }
            var value = new String(body, offset, textEnd - offset, StandardCharsets.UTF_8).strip();
            if (!value.isEmpty() && isChunkId(body, offset - 8)) {
                header.metadata.merge(key, value, (previous, next) -> previous + "; " + next);
            }
            offset = end + (int) (size & 1);
        }
    }

    /**
     * Parses a descriptive chunk: {@code bext}, {@code iXML}, {@code axml}, {@code chna} and {@code smpl} into
     * metadata, and keeps the {@code cue } chunk for the chapters.
     *
     * @param reader the reader positioned at the chunk body
     * @param header receives the metadata
     * @param id the chunk identifier
     * @param chunkSize the chunk body size
     */
    private void parseDescriptive(SourceReader reader, Header header, int id, long chunkSize) {
        if (chunkSize > MAX_LIST_SIZE || chunkSize > reader.remaining()) {
            return;
        }
        var body = reader.bytes(chunkSize, MAX_LIST_SIZE);
        var little = !header.bigEndian;
        var entries = new ArrayList<MediaTags.Entry>();
        switch (id) {
            case 0x62657874 -> RiffMetadataChunks.decodeBext(body, little, entries);
            case 0x69584D4C -> RiffMetadataChunks.decodeXml("iXML", body, entries);
            case 0x61786D6C -> RiffMetadataChunks.decodeXml("axml", body, entries);
            case 0x63686E61 -> RiffMetadataChunks.decodeChna(body, little, entries);
            case 0x736D706C -> RiffMetadataChunks.decodeSmpl(body, little, entries);
            default -> {
                if (header.cue == null) {
                    header.cue = body;
                }
            }
        }
        for (var entry : entries) {
            header.metadata.merge(entry.key(), entry.value(), (previous, next) -> previous + "; " + next);
        }
    }

    /**
     * Decodes the chapters of the {@code cue } chunk and its {@code adtl} list.
     *
     * @param header the parsed header
     * @param durationMicros the duration of the audio, or {@code -1}
     * @return the chapters, empty without a {@code cue } chunk
     */
    private static List<Chapter> cueChapters(Header header, long durationMicros) {
        if (header.cue == null) {
            return List.of();
        }
        return List.copyOf(RiffMetadataChunks.decodeCue(header.cue, header.adtl, !header.bigEndian,
                header.sampleRate, durationMicros));
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
        return metadata;
    }

    @Override
    public List<Chapter> chapters() {
        return coded == null || coded.chapters().isEmpty() ? cueChapters : coded.chapters();
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return coded == null ? List.of() : coded.attachedPictures();
    }

    @Override
    public long durationMicros() {
        if (coded != null && coded.durationMicros() >= 0) {
            return coded.durationMicros();
        }
        return track.duration() < 0 ? -1 : track.timeBase().toMicros(track.duration());
    }

    /**
     * Returns whether the file can seek: always for uncompressed or companded samples and ADPCM, whose frames
     * are at computable offsets, and as the demuxer of the elementary stream decides for coded audio.
     *
     * @return {@code true} for PCM and ADPCM, the elementary stream demuxer's answer for coded audio
     */
    @Override
    public boolean isSeekable() {
        return coded == null || coded.isSeekable();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every packet of uncompressed audio is a key frame, so the next packet starts at the
     * sample frame displayed at {@code micros}; ADPCM seeks to the packet holding that sample, the block or
     * unit of codes it falls in. Coded audio seeks as the demuxer of its elementary
     * stream does: MPEG audio through its frame index, IEC 61937 bursts from the position of the target
     * on their timeline, self-delimited Opus through an index of its packets.
     *
     * @throws MediaException.Unsupported if the elementary stream cannot seek
     */
    @Override
    public void seek(long micros) {
        if (coded != null) {
            coded.seek(micros);
            return;
        }
        var frame = frameAt(micros, track.sampleRate());
        if (unitSamples > 0) {
            seekAdpcm(frame);
            return;
        }
        var available = dataEnd >= 0 ? (dataEnd - dataStart) / frameSize : (Long.MAX_VALUE - dataStart) / frameSize;
        frame = Math.min(frame, Math.max(0, available));
        frameIndex = frame;
        position = dataStart + frame * frameSize;
        finished = false;
    }

    /**
     * Positions ADPCM at the packet holding a sample, as {@link #nextAdpcmPacket()} cuts them: whole packets from the
     * start of the data, and in the last, shorter run of bytes its whole units, then the bytes left.
     *
     * @param sample the index of the sample, per channel
     */
    private void seekAdpcm(long sample) {
        var packetSamples = (long) packetFrames * unitSamples;
        var packetBytes = (long) packetFrames * frameSize;
        var index = sample / packetSamples;
        if (dataEnd >= 0) {
            index = Math.min(index, (dataEnd - dataStart) / packetBytes);
        }
        var start = index * packetSamples;
        var offset = dataStart + index * packetBytes;
        if (dataEnd >= 0 && dataEnd - offset < packetBytes) {
            var whole = (dataEnd - offset) / frameSize * frameSize;
            var wholeSamples = whole / frameSize * unitSamples;
            if (whole > 0 && whole < dataEnd - offset && sample >= start + wholeSamples) {
                offset += whole;
                start += wholeSamples;
            }
        }
        position = offset;
        frameIndex = start;
        finished = dataEnd >= 0 && offset >= dataEnd || sampleLimit >= 0 && sample >= sampleLimit;
    }

    /**
     * Converts a presentation time to the index of the sample frame displayed at it.
     *
     * @param micros the time in microseconds
     * @param sampleRate the sampling rate in hertz
     * @return the frame index, rounded down, {@code 0} for a negative time
     */
    private static long frameAt(long micros, int sampleRate) {
        if (micros <= 0) {
            return 0;
        }
        return BigInteger.valueOf(micros)
                .multiply(BigInteger.valueOf(sampleRate))
                .divide(BigInteger.valueOf(1_000_000))
                .min(BigInteger.valueOf(Long.MAX_VALUE))
                .longValue();
    }

    @Override
    public Packet read() {
        if (coded != null) {
            return coded.read();
        }
        try {
            return nextPacket();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid WAVE data", exception);
        }
    }

    /**
     * Reads the next packet of whole frames.
     *
     * @return the packet, or {@code null} at the end of the samples
     */
    private Packet nextPacket() {
        if (finished) {
            return null;
        }
        if (unitSamples > 0) {
            return nextAdpcmPacket();
        }
        var wanted = (long) packetFrames * frameSize;
        if (dataEnd >= 0) {
            wanted = Math.min(wanted, (dataEnd - position) / frameSize * frameSize);
        }
        if (wanted <= 0) {
            finished = true;
            return null;
        }
        var data = new byte[(int) wanted];
        var done = this.data.readUpTo(position, data, 0, data.length);
        var whole = done / frameSize * frameSize;
        if (whole < data.length) {
            finished = true;
            if (whole == 0) {
                return null;
            }
            data = Arrays.copyOf(data, whole);
        }
        var frames = whole / frameSize;
        var packet = new Packet(track.id(), data, frameIndex, frameIndex, frames, true);
        position += whole;
        frameIndex += frames;
        ForwardOnlySource.release(source, position);
        return packet;
    }

    /**
     * Reads the next packet of ADPCM: one block, or whole units of a code stream, and at the end of the data the
     * bytes {@link AdpcmBlocks#tail} keeps of a truncated one.
     *
     * @return the packet, or {@code null} at the end of the samples
     */
    private Packet nextAdpcmPacket() {
        var wanted = (long) packetFrames * frameSize;
        if (dataEnd >= 0) {
            wanted = Math.min(wanted, dataEnd - position);
        }
        if (wanted <= 0 || sampleLimit >= 0 && frameIndex >= sampleLimit) {
            finished = true;
            return null;
        }
        var data = new byte[(int) wanted];
        var done = this.data.readUpTo(position, data, 0, data.length);
        var whole = done / frameSize * frameSize;
        var size = whole;
        if (whole == 0 && (done < data.length || dataEnd >= 0 && position + done >= dataEnd)) {
            size = AdpcmBlocks.tail(track.codec(), done, channels, codeSize);
            finished = true;
        } else if (done < data.length && whole == done) {
            finished = true;
        }
        if (size == 0) {
            finished = true;
            return null;
        }
        if (size < data.length) {
            data = Arrays.copyOf(data, size);
        }
        var samples = AdpcmBlocks.samples(track.codec(), size, channels, codeSize);
        var duration = sampleLimit >= 0 ? Math.min(samples, sampleLimit - frameIndex) : samples;
        var packet = new Packet(track.id(), data, frameIndex, frameIndex, duration, true);
        position += size;
        frameIndex += samples;
        ForwardOnlySource.release(source, position);
        return packet;
    }

    /**
     * Releases the demuxer of the coded audio; the source is not closed.
     */
    @Override
    public void close() {
        if (coded != null) {
            coded.close();
        }
    }
}
