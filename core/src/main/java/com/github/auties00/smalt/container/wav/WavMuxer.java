package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.tags.MuxerTags;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Writes RIFF WAVE files, switching to RF64 for files of 4 GiB and more, from the packets of one
 * PCM or coded audio track.
 *
 * <p>The track must be an {@link AudioTrack} that declares its sample rate and channel layout,
 * with one of the codecs {@link #supports(Codec, WavMuxerOptions)} accepts under the muxer's
 * options. PCM is stored little-endian, as the
 * format requires:
 * <ul>
 *   <li>unsigned 8-bit, signed 16, 24, 32 and 64-bit little-endian integers, 32 and 64-bit
 *       little-endian floats ({@code WAVE_FORMAT_IEEE_FLOAT}), A-law and mu-law are written as they
 *       are;</li>
 *   <li>big-endian integers and floats are written with their bytes swapped, and signed 8-bit
 *       samples as unsigned ones (the sign bit flipped), so the file declares
 *       {@link Codec#PCM_S16LE} for a {@link Codec#PCM_S16BE} track and {@link Codec#PCM_U8} for a
 *       {@link Codec#PCM_S8} one, with the same sample values.</li>
 * </ul>
 * The {@code fmt } chunk of PCM uses {@code WAVE_FORMAT_EXTENSIBLE} when the plain format cannot
 * describe the stream: integer samples wider than 16 bits, more than two channels, or a layout
 * other than mono or stereo; its channel mask has one bit per {@link Speaker} of the layout when
 * the speakers are positioned and in mask order, and is zero otherwise. A layout that is exactly
 * the Furse-Malham B-format of {@link com.github.auties00.smalt.audio.Ambisonics#ofFurseMalham(int)}
 * gets the Ambisonic B-format sub-format GUID instead, with a zero mask; other ambisonic layouts,
 * which that sub-format cannot describe, are written as channels without positions. Float, A-law
 * and mu-law files carry a {@code fact} chunk with the sample count. Each PCM packet must hold
 * whole frames.
 *
 * <p>Coded audio is stored as {@link WavDemuxer} reads it back, packet for packet:
 * <table class="striped">
 * <caption>Coded payloads</caption>
 * <thead><tr><th scope="col">Codec</th><th scope="col">Format</th><th scope="col">Data chunk</th></tr></thead>
 * <tbody>
 * <tr><td>{@link Codec#MP2} (Layer I and II)</td><td>{@code 0x0050}, {@code MPEG1WAVEFORMAT}, and a Broadcast Wave
 * {@code mext} chunk (EBU Tech 3285 Supplement 1)</td><td>the frames</td></tr>
 * <tr><td>{@link Codec#MP3}</td><td>{@code 0x0055}, {@code MPEGLAYER3WAVEFORMAT}</td><td>the frames</td></tr>
 * <tr><td>{@link Codec#AC3}</td><td>{@code 0x2000}</td><td>the sync frames</td></tr>
 * <tr><td>{@link Codec#EAC3}</td><td>{@code WAVE_FORMAT_EXTENSIBLE} with the {@code MEDIASUBTYPE_DOLBY_DDPLUS}
 * sub-format and the layout's channel mask</td><td>the sync frames</td></tr>
 * <tr><td>{@link Codec#AAC} whose {@code AudioSpecificConfig} an ADTS header can express</td><td>{@code 0x1600}
 * ({@code WAVE_FORMAT_MPEG_ADTS_AAC})</td><td>one ADTS frame without CRC per packet</td></tr>
 * <tr><td>other {@link Codec#AAC}</td><td>{@code 0x1610}, an {@code HEAACWAVEINFO} of payload type 0 followed by
 * the {@code AudioSpecificConfig}, with the sampling rate and channel count of the core stream and a block alignment
 * of 1</td><td>the raw data blocks</td></tr>
 * <tr><td>{@link Codec#FLAC}</td><td>{@code 0xF1AC} with the {@code STREAMINFO} block as extra bytes</td>
 * <td>the frames</td></tr>
 * <tr><td>{@link Codec#OPUS}</td><td>{@code 0x704F} with the {@code OpusHead} as extra bytes</td>
 * <td>the packets in the self-delimiting framing of RFC 6716 Appendix B</td></tr>
 * <tr><td>{@link Codec#AMR_NB}, {@link Codec#AMR_WB}</td><td>{@code 0x0057}, {@code 0x0058}</td>
 * <td>the storage-format frames of RFC 4867 without the magic; with two or more channels, the multichannel magic and
 * channel description, then the frame-blocks</td></tr>
 * <tr><td>{@link Codec#DTS}</td><td>{@code 0x2001}</td><td>the frames</td></tr>
 * <tr><td>{@link Codec#ADPCM_IMA_WAV}</td><td>{@code 0x0011}, {@code IMAADPCMWAVEFORMAT}</td><td>the blocks</td></tr>
 * <tr><td>{@link Codec#ADPCM_MS}</td><td>{@code 0x0002}, {@code ADPCMWAVEFORMAT} with the coefficients of the
 * configuration, or the seven standard pairs</td><td>the blocks</td></tr>
 * <tr><td>{@link Codec#GSM_MS}</td><td>{@code 0x0031}, {@code GSM610WAVEFORMAT}</td><td>the 65-byte blocks</td></tr>
 * <tr><td>{@link Codec#ADPCM_G722}</td><td>{@code 0x028F}</td><td>the codes</td></tr>
 * <tr><td>{@link Codec#ADPCM_G726}</td><td>{@code 0x0045}, the code size as the bits per sample</td>
 * <td>the codes</td></tr>
 * <tr><td>{@link Codec#ATRAC9}</td><td>the ATRAC9 {@code WAVEFORMATEXTENSIBLE} sub-format, the superframe size as
 * the block alignment and its samples in the valid-bits field, version 1, the configuration word and a reserved
 * field as extra bytes (or the 12 bytes of a track configured with them), and a {@code fact} chunk of the sample
 * count after the track's {@link AudioTrack#skipSamples()} and that delay, twice</td><td>the superframes, of which
 * only the last may be shorter</td></tr>
 * </tbody>
 * </table>
 * A coded file carries a {@code fact} chunk with the number of samples per channel its packets
 * code, counted from their frame headers at the sampling rate the format declares, and for ADPCM from their
 * sizes, each ADPCM packet's count cut to its duration when the track's time base resolves that
 * duration as shorter than the samples the packet codes. Each packet
 * must start with a frame of its codec: MPEG audio of the track's layer, a sync frame, a DTS core
 * frame, a FLAC frame header, whole AMR frame-blocks, or a valid Opus packet. ADPCM packets must
 * be as their {@link Codec} constant defines them: IMA and Microsoft ADPCM blocks of the size of
 * the first, the last possibly shorter, whole GSM blocks, and G.726 codes that end on a unit
 * boundary except in the last packet. G.722, G.726 and GSM 06.10 tracks must be mono. The {@code fmt } fields that
 * describe the whole stream follow Tech 3285 Supplement 1 and the Microsoft structures: the block
 * alignment is the size of the frames (MPEG audio) or packets when they all have one size and 1
 * otherwise (the size of the first block for IMA and Microsoft ADPCM, of the smallest whole run
 * of codes for GSM 06.10 and G.726), the average byte rate is the bit rate of constant-bitrate
 * MPEG audio, the rate the Microsoft structures define for ADPCM, and the average of the stream
 * otherwise, and the MPEG header fields are the union of those of every frame; the
 * {@code mext} chunk declares homogeneous sound data, with its nominal frame size, when every frame
 * has the same header apart from the padding, private, mode and mode extension bits. These fields
 * are written with the header from the first packet, stating only what one packet shows (a block
 * alignment of 1, an unknown bit rate, non-homogeneous data), and rewritten from every packet by
 * {@link #finish()} on a seekable sink.
 *
 * <p>Under the IEC 61937 {@linkplain WavMuxerOptions#encapsulation() encapsulations}, coded audio is
 * stored as IEC 61937 data bursts in 16-bit PCM samples instead, the stream a S/PDIF or HDMI
 * receiver decodes, which {@link WavDemuxer} also reads back packet for packet. The file declares
 * {@code WAVE_FORMAT_PCM} (or {@code WAVE_FORMAT_EXTENSIBLE} PCM for 8 channels), or, under
 * {@link WavMuxerOptions.Encapsulation#IEC61937_EXTENSIBLE}, a {@code WAVEFORMATEXTENSIBLE_IEC61937}
 * structure with the sub-format of the codec and the sampling rate, channel count and average byte
 * rate of the coded audio:
 * <table class="striped">
 * <caption>IEC 61937 bursts</caption>
 * <thead><tr><th scope="col">Codec</th><th scope="col">Data types</th><th scope="col">Sub-format</th>
 * <th scope="col">Samples of the file</th></tr></thead>
 * <tbody>
 * <tr><td>{@link Codec#AC3}</td><td>1, one sync frame per burst (IEC 61937-3)</td>
 * <td>{@code 0x0092} ({@code KSDATAFORMAT_SUBTYPE_IEC61937_DOLBY_DIGITAL})</td><td>2 channels at the sampling
 * rate</td></tr>
 * <tr><td>{@link Codec#EAC3}</td><td>21, the access units of six audio blocks per burst (IEC 61937-3)</td>
 * <td>CEA-861 type 10</td><td>2 channels at four times the sampling rate</td></tr>
 * <tr><td>{@link Codec#MP2}, {@link Codec#MP3}</td><td>4, 5 for MPEG-1, 8, 9, 10 for MPEG-2 at half rate, one
 * frame per burst (IEC 61937-4)</td><td>CEA-861 type 3 (MPEG-1 Layers I and II), 4 (Layer III), 5 (MPEG-2 Layers I
 * and II)</td><td>2 channels at the sampling rate, twice it for MPEG-2 at half rate</td></tr>
 * <tr><td>{@link Codec#AAC} of 1024-sample frames whose {@code AudioSpecificConfig} an ADTS header can
 * express</td><td>7, one ADTS frame per burst (IEC 61937-6)</td><td>CEA-861 type 6</td><td>2 channels at the
 * ADTS sampling rate</td></tr>
 * <tr><td>{@link Codec#DTS} of 512, 1024 or 2048-sample frames</td><td>11, 12, 13, one core frame per burst, or 17
 * (DTS-HD) with the extension substream when the first packet carries one (IEC 61937-5)</td><td>{@code 0x0008}
 * ({@code KSDATAFORMAT_SUBTYPE_IEC61937_DTS}), CEA-861 type 11 for DTS-HD</td><td>2 channels at the sampling rate,
 * 8 channels at four times it for DTS-HD</td></tr>
 * <tr><td>{@link Codec#TRUEHD}</td><td>22, one MAT frame of 24 access units per burst (IEC 61937-9)</td>
 * <td>CEA-861 type 12</td><td>8 channels at 192 kHz, 176.4 kHz for the 44.1 kHz family</td></tr>
 * </tbody>
 * </table>
 * Zeros fill each burst up to its repetition period; a DTS core frame that fills its period is
 * stored without a burst preamble, and a packet flagged
 * {@link com.github.auties00.smalt.track.PacketFlag#CORRUPT} sets the error flag of its burst. The
 * last E-AC-3 group and MAT frame are written, padded, by {@link #finish()}. Other coded audio
 * cannot be carried in bursts, and PCM is stored as it is.
 *
 * <p>Packets are appended to the {@code data} chunk in the order they arrive; their timestamps
 * are ignored, so a gap between packets is not filled. On a seekable sink the header is written
 * with the first packet and its sizes are patched by {@link #finish()}; under
 * {@link WavMuxerOptions.Rf64#AUTO} a {@code JUNK} chunk reserves the room of the {@code ds64}
 * chunk, which replaces it, together with an {@code RF64} form, when the file reaches 4 GiB. On a
 * streaming sink the RIFF and {@code data} sizes are {@code 0xFFFFFFFF}, the value readers take as
 * "until the end of the file", and the {@code fact} count is zero; such a file can be read but its
 * sizes are never corrected. An odd-sized chunk is followed by a pad byte. The sink is written
 * from its position at construction and is never closed by the muxer.
 *
 * <p>Instances are not thread-safe.
 */
public final class WavMuxer implements Muxer {
    /**
     * The largest RIFF size a 32-bit field holds.
     */
    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * The size of the {@code ds64} chunk body, without its table.
     */
    private static final int DS64_SIZE = 28;

    /**
     * The output sink.
     */
    private final MediaSink output;

    /**
     * The configuration.
     */
    private final WavMuxerOptions options;

    /**
     * The sink position at construction, where the file starts.
     */
    private final long base;

    /**
     * The largest RIFF size written without RF64; {@link #U32_MAX} outside tests.
     */
    private final long riffLimit;

    /**
     * Whether the file is Sony Wave64 rather than RIFF WAVE.
     */
    private final boolean wave64;

    /**
     * The declared track, or {@code null}.
     */
    private AudioTrack track;

    /**
     * The PCM codec the file declares, or {@code null} for coded audio.
     */
    private Codec storedCodec;

    /**
     * The writer of coded audio, or {@code null} for PCM and IEC 61937 bursts.
     */
    private WavCodedWriter coded;

    /**
     * The writer of IEC 61937 bursts, or {@code null} for PCM and the native coded payloads.
     */
    private Iec61937Writer bursts;

    /**
     * The size of one interleaved PCM frame in bytes.
     */
    private int blockAlign;

    /**
     * The PCM samples of the last packet converted to the stored byte order.
     */
    private byte[] converted = new byte[0];

    /**
     * The size of the header up to the first sample.
     */
    private int headerSize;

    /**
     * The file offset of the {@code fmt } chunk body.
     */
    private int formatOffset;

    /**
     * The file offset of the {@code fact} sample count, or {@code -1}.
     */
    private int factOffset = -1;

    /**
     * The file offset of the {@code mext} chunk body, or {@code -1}.
     */
    private int extensionOffset = -1;

    /**
     * The file offset of the {@code JUNK} or {@code ds64} chunk, or {@code -1}.
     */
    private int reservedOffset = -1;

    /**
     * The number of bytes written to the {@code data} chunk.
     */
    private long dataSize;

    /**
     * The tags of {@link #setTags(MediaTags)}, encoded into chunks before {@code data} when the header is written.
     */
    private MediaTags tags = MediaTags.empty();

    /**
     * Whether the header was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing to a sink with {@link WavMuxerOptions#defaults()}.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @throws MediaException.Io if the sink position cannot be read
     */
    public WavMuxer(MediaSink output) {
        this(output, WavMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @param options the configuration
     * @throws MediaException.Unsupported if the options ask for RF64 and the sink is not seekable
     * @throws MediaException.Io if the sink position cannot be read
     */
    public WavMuxer(MediaSink output, WavMuxerOptions options) {
        this(output, options, U32_MAX);
    }

    /**
     * Constructs a muxer with a lowered RF64 threshold, so tests reach the RF64 path with small
     * files.
     *
     * @param output the sink
     * @param options the configuration
     * @param riffLimit the largest RIFF size written without RF64
     */
    WavMuxer(MediaSink output, WavMuxerOptions options, long riffLimit) {
        this(output, options, riffLimit, false);
    }

    /**
     * Constructs a muxer writing RIFF WAVE or Sony Wave64 to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @param options the options; a Wave64 file uses only their encapsulation
     * @param riffLimit the largest RIFF size written without RF64
     * @param wave64 whether to write Sony Wave64
     * @throws MediaException.Unsupported if the options always write RF64 and the sink is not seekable
     * @throws MediaException.Io if the sink position cannot be read
     */
    WavMuxer(MediaSink output, WavMuxerOptions options, long riffLimit, boolean wave64) {
        this.wave64 = wave64;
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        if (!wave64 && options.rf64Policy() == WavMuxerOptions.Rf64.ALWAYS && !output.isSeekable()) {
            throw new MediaException.Unsupported("non-seekable output",
                    "an RF64 header is patched after the samples are written, so it needs a seekable output");
        }
        this.riffLimit = riffLimit;
        this.base = output.position();
    }

    /**
     * Returns whether WAV files can carry a codec.
     *
     * @param codec the codec of the track to write
     * @return {@code true} for every integer and float PCM codec, {@link Codec#PCM_ALAW},
     * {@link Codec#PCM_MULAW}, {@link Codec#MP2}, {@link Codec#MP3}, {@link Codec#AC3},
     * {@link Codec#EAC3}, {@link Codec#AAC}, {@link Codec#FLAC}, {@link Codec#OPUS},
     * {@link Codec#AMR_NB}, {@link Codec#AMR_WB}, {@link Codec#DTS}, {@link Codec#ADPCM_IMA_WAV},
     * {@link Codec#ADPCM_MS}, {@link Codec#GSM_MS}, {@link Codec#ADPCM_G722} and {@link Codec#ADPCM_G726}
     */
    public static boolean supports(Codec codec) {
        Objects.requireNonNull(codec, "codec");
        return storedPcm(codec) != null || WavCodedWriter.supports(codec);
    }

    /**
     * Returns whether WAV files written with some options can carry a codec.
     *
     * @param codec the codec of the track to write
     * @param options the configuration
     * @return what {@link #supports(Codec)} returns under {@link WavMuxerOptions.Encapsulation#NATIVE}; under the IEC
     * 61937 encapsulations, {@code true} for every PCM codec, {@link Codec#AC3}, {@link Codec#EAC3}, {@link Codec#MP2},
     * {@link Codec#MP3}, {@link Codec#AAC}, {@link Codec#DTS} and {@link Codec#TRUEHD}
     */
    public static boolean supports(Codec codec, WavMuxerOptions options) {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(options, "options");
        if (options.encapsulation() == WavMuxerOptions.Encapsulation.NATIVE) {
            return supports(codec);
        }
        return storedPcm(codec) != null || Iec61937Writer.supports(codec);
    }

    /**
     * Returns the PCM codec a file declares for a track's PCM codec.
     *
     * @param codec the track's codec
     * @return the little-endian (or unsigned 8-bit) codec of the same samples, or {@code null} when the codec is not
     * PCM
     */
    private static Codec storedPcm(Codec codec) {
        return switch (codec) {
            case PCM_U8, PCM_S16LE, PCM_S24LE, PCM_S32LE, PCM_S64LE, PCM_F32LE, PCM_F64LE, PCM_ALAW, PCM_MULAW -> codec;
            case PCM_S8 -> Codec.PCM_U8;
            case PCM_S16BE -> Codec.PCM_S16LE;
            case PCM_S24BE -> Codec.PCM_S24LE;
            case PCM_S32BE -> Codec.PCM_S32LE;
            case PCM_S64BE -> Codec.PCM_S64LE;
            case PCM_F32BE -> Codec.PCM_F32LE;
            case PCM_F64BE -> Codec.PCM_F64LE;
            default -> null;
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not an audio track of a codec
     * {@link #supports(Codec, WavMuxerOptions)} accepts under the options, declares no sample rate or layout, has
     * more than 65535 channels, or lacks what its codec needs: an {@code AudioSpecificConfig} of AAC Main, LC, SSR or
     * LTP, alone or under SBR and PS, the {@code STREAMINFO} of FLAC, the {@code OpusHead} of Opus with more than two
     * channels, and for AMR the rate of its codec (8 or 16 kHz) and at most 15 channels; G.722, G.726 and GSM 06.10
     * need one channel, G.726 its code size
     * as the track's {@link AudioTrack#bitsPerSample()}, IMA and Microsoft ADPCM a {@link AudioTrack#bitsPerSample()}
     * of 4 or 0 and block headers of every channel that fit a 16-bit block alignment, and a Microsoft ADPCM
     * configuration must hold its coefficient table; in IEC 61937 bursts, AAC needs
     * a configuration of 1024-sample frames an ADTS header can express, MPEG audio a rate of MPEG-1 or of MPEG-2 at
     * half rate, and TrueHD one of its rates
     * @throws IllegalStateException if a track was already declared or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalStateException("a WAV file carries one track");
        }
        if (!(track instanceof AudioTrack audio) || !supports(track.codec(), options)) {
            throw new IllegalArgumentException("WAV cannot carry " + track.codec().displayName());
        }
        if (audio.sampleRate() <= 0 || audio.layout().isEmpty()) {
            throw new IllegalArgumentException("a WAV track needs its sample rate and channel layout");
        }
        var channels = audio.layout().get().channels();
        if (channels > 0xFFFF) {
            throw new IllegalArgumentException(channels + " channels do not fit a WAV header");
        }
        var stored = storedPcm(audio.codec());
        if (stored != null) {
            this.blockAlign = stored.pcmBitsPerSample().orElseThrow() / 8 * channels;
        } else if (options.encapsulation() != WavMuxerOptions.Encapsulation.NATIVE) {
            this.bursts = new Iec61937Writer(audio,
                    options.encapsulation() == WavMuxerOptions.Encapsulation.IEC61937_EXTENSIBLE);
        } else {
            this.coded = new WavCodedWriter(audio);
        }
        this.storedCodec = stored;
        this.track = audio;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsTags() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags become a {@code LIST/INFO} chunk for the values it can hold and, when pictures
     * or other keys need it, an {@code id3 } chunk with every tag, and the Broadcast Wave,
     * {@code iXML}, {@code axml}, {@code chna} and {@code smpl} keys their own chunks, the XMP packet a {@code _PMX}
     * chunk, as
     * {@link com.github.auties00.smalt.metadata.TagEditor} writes them. The chapters become cue points:
     * a {@code cue } chunk and a {@code LIST adtl} chunk holding a {@code labl} title and an {@code ltxt}
     * length per chapter. Every chunk is written before {@code data}.
     *
     * @throws IllegalArgumentException if the value of a {@code chna}, {@code smpl}, {@code umid}, loudness or
     * {@code time_reference} key is malformed
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        MuxerTags.riffChunks(tags, false, 0);
        this.tags = tags;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track, does not hold whole frames of PCM, does
     * not start with a frame of its codec, is not a packet of ADPCM as its codec defines them or follows a shorter last
     * one, or, in IEC 61937 bursts, does not hold whole frames, does not fit a burst
     * or changes the form of a DTS stream
     * @throws IllegalStateException if no track was declared, the muxer is finished, or the
     * {@link WavMuxerOptions.Rf64#NEVER} policy forbids the size the packet would reach
     * @throws MediaException.Unsupported if the packet changes the codec configuration
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
        ConfigChanges.reject(packet, track.codecConfig().orElse(null), "WAV");
        int stored;
        if (coded != null) {
            stored = coded.prepare(packet);
        } else if (bursts != null) {
            stored = bursts.prepare(packet);
        } else if (packet.size() % blockAlign != 0) {
            throw new IllegalArgumentException("a packet of " + packet.size() + " bytes does not hold whole frames of "
                    + blockAlign + " bytes");
        } else {
            stored = packet.size();
        }
        if (!started) {
            writeHeader(packet, stored);
        }
        var end = dataSize + stored;
        checkSize(end);
        if (coded != null) {
            coded.write(packet, stored, output);
        } else if (bursts != null) {
            bursts.write(output);
        } else {
            writePcm(packet);
        }
        dataSize = end;
    }

    /**
     * Checks that the {@link WavMuxerOptions.Rf64#NEVER} policy allows a {@code data} chunk size.
     *
     * @param end the size the chunk would reach
     * @throws IllegalStateException if the file would reach 4 GiB under the policy
     */
    private void checkSize(long end) {
        if (!wave64 && options.rf64Policy() == WavMuxerOptions.Rf64.NEVER && headerSize - 8L + end + (end & 1) > riffLimit) {
            throw new IllegalStateException("the WAV file would exceed 4 GiB; use an RF64 policy other than NEVER");
        }
    }

    /**
     * Writes the samples of a PCM packet in the stored byte order.
     *
     * @param packet the packet, of whole frames
     */
    private void writePcm(Packet packet) {
        var data = packet.data();
        var codec = track.codec();
        if (codec == storedCodec) {
            output.write(ByteBuffer.wrap(data));
            return;
        }
        if (converted.length < data.length) {
            converted = new byte[Math.max(data.length, converted.length * 2)];
        }
        if (codec == Codec.PCM_S8) {
            for (var index = 0; index < data.length; index++) {
                converted[index] = (byte) (data[index] ^ 0x80);
            }
        } else {
            var width = codec.pcmBitsPerSample().orElseThrow() / 8;
            for (var sample = 0; sample < data.length; sample += width) {
                for (var index = 0; index < width; index++) {
                    converted[sample + index] = data[sample + width - 1 - index];
                }
            }
        }
        output.write(ByteBuffer.wrap(converted, 0, data.length));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A muxer without packets writes a header with an empty {@code data} chunk. IEC 61937 bursts end with the
     * burst of the last E-AC-3 group or MAT frame, padded.
     *
     * @throws IllegalStateException if no track was declared, the muxer is finished, or the
     * {@link WavMuxerOptions.Rf64#NEVER} policy forbids the size the last burst would reach
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
            writeHeader(null, 0);
        }
        if (bursts != null) {
            var pending = bursts.flush();
            checkSize(dataSize + pending);
            bursts.write(output);
            dataSize += pending;
        }
        finished = true;
        if (wave64) {
            finishWave64();
            return;
        }
        if ((dataSize & 1) != 0) {
            output.write(new byte[1]);
        }
        if (!output.isSeekable()) {
            return;
        }
        var end = output.position();
        var riffSize = headerSize - 8L + dataSize + (dataSize & 1);
        var frames = coded != null ? coded.samples() : dataSize / blockAlign;
        if (coded != null) {
            writeAt(formatOffset, coded.format());
            if (extensionOffset >= 0) {
                writeAt(extensionOffset, coded.finalExtension());
            }
        } else if (bursts != null) {
            writeAt(formatOffset, bursts.format(true));
        }
        var policy = options.rf64Policy();
        var large = riffSize > riffLimit || dataSize > riffLimit;
        if (policy == WavMuxerOptions.Rf64.ALWAYS || policy == WavMuxerOptions.Rf64.AUTO && large) {
            var form = new ByteWriter(8);
            form.fourCc("RF64");
            form.u32le(U32_MAX);
            writeAt(0, form);
            var ds64 = new ByteWriter(8 + DS64_SIZE);
            ds64.fourCc("ds64");
            ds64.u32le(DS64_SIZE);
            ds64.u64le(riffSize);
            ds64.u64le(dataSize);
            ds64.u64le(frames);
            ds64.u32le(0);
            writeAt(reservedOffset, ds64);
            patch32(headerSize - 4, U32_MAX);
            if (factOffset >= 0) {
                patch32(factOffset, Math.min(frames, U32_MAX));
            }
        } else {
            patch32(4, riffSize);
            patch32(headerSize - 4, dataSize);
            if (factOffset >= 0) {
                patch32(factOffset, Math.min(frames, U32_MAX));
            }
        }
        output.position(end);
    }

    /**
     * Marks the muxer finished; an unfinished file keeps the sizes of its header as written.
     */
    @Override
    public void close() {
        finished = true;
    }

    /**
     * Writes the header up to the {@code data} chunk's samples, and the bytes coded audio stores before its first
     * packet.
     *
     * @param first the first packet, or {@code null} for a file without packets
     * @param stored the stored size of {@code first}
     */
    private void writeHeader(Packet first, int stored) {
        started = true;
        if (wave64) {
            writeWave64Header(first, stored);
            return;
        }
        var seekable = output.isSeekable();
        var policy = options.rf64Policy();
        var header = new ByteWriter(128);
        if (policy == WavMuxerOptions.Rf64.ALWAYS) {
            header.fourCc("RF64");
            header.u32le(U32_MAX);
            header.fourCc("WAVE");
            reservedOffset = header.size();
            header.fourCc("ds64");
            header.u32le(DS64_SIZE);
            header.zeros(DS64_SIZE);
        } else {
            header.fourCc("RIFF");
            header.u32le(seekable ? 0 : U32_MAX);
            header.fourCc("WAVE");
            if (policy == WavMuxerOptions.Rf64.AUTO && seekable) {
                reservedOffset = header.size();
                header.fourCc("JUNK");
                header.u32le(DS64_SIZE);
                header.zeros(DS64_SIZE);
            }
        }
        byte[] format;
        if (coded != null) {
            format = coded.format(first, stored);
        } else if (bursts != null) {
            format = bursts.format(false);
            blockAlign = bursts.blockAlign();
        } else {
            format = pcmFormat();
        }
        header.fourCc("fmt ");
        header.u32le(format.length);
        formatOffset = header.size();
        header.bytes(format);
        if ((format.length & 1) != 0) {
            header.u8(0);
        }
        if (coded != null || storedCodec == Codec.PCM_F32LE || storedCodec == Codec.PCM_F64LE
                || storedCodec == Codec.PCM_ALAW || storedCodec == Codec.PCM_MULAW) {
            var delay = coded != null ? coded.factDelay() : -1;
            header.fourCc("fact");
            header.u32le(delay >= 0 ? 12 : 4);
            factOffset = header.size();
            header.u32le(0);
            if (delay >= 0) {
                // ATRAC9: the encoder delay, twice
                header.u32le(delay);
                header.u32le(delay);
            }
        }
        if (coded != null && coded.hasExtension()) {
            header.fourCc("mext");
            header.u32le(WavCodedWriter.MEXT_SIZE);
            extensionOffset = header.size();
            header.bytes(coded.extension());
        }
        var tagRate = bursts != null ? (int) Math.min(bursts.rate(), Integer.MAX_VALUE) : track.sampleRate();
        for (var chunk : MuxerTags.riffChunks(tags, false, tagRate)) {
            header.bytes(chunk);
        }
        header.fourCc("data");
        header.u32le(seekable || policy == WavMuxerOptions.Rf64.ALWAYS ? 0 : U32_MAX);
        headerSize = header.size();
        output.position(base);
        header.writeTo(output);
        if (coded != null) {
            var prefix = coded.prefix();
            if (prefix.length > 0) {
                output.write(ByteBuffer.wrap(prefix));
                dataSize += prefix.length;
            }
        }
    }

    /**
     * Writes the header of a Sony Wave64 file up to the first sample.
     *
     * @param first the first packet, or {@code null} when there is none
     * @param stored the size the first packet takes in the data chunk
     */
    private void writeWave64Header(Packet first, int stored) {
        var header = new ByteWriter(160);
        wave64Guid(header, "riff");
        header.u64le(0);
        wave64Guid(header, "wave");
        byte[] format;
        if (coded != null) {
            format = coded.format(first, stored);
        } else if (bursts != null) {
            format = bursts.format(false);
            blockAlign = bursts.blockAlign();
        } else {
            format = pcmFormat();
        }
        wave64Guid(header, "fmt ");
        header.u64le(24L + format.length);
        formatOffset = header.size();
        header.bytes(format);
        header.zeros(-format.length & 7);
        if (coded != null || storedCodec == Codec.PCM_F32LE || storedCodec == Codec.PCM_F64LE
                || storedCodec == Codec.PCM_ALAW || storedCodec == Codec.PCM_MULAW) {
            wave64Guid(header, "fact");
            header.u64le(32);
            factOffset = header.size();
            header.u64le(0);
        }
        wave64Guid(header, "data");
        header.u64le(0);
        headerSize = header.size();
        output.position(base);
        header.writeTo(output);
        if (coded != null) {
            var prefix = coded.prefix();
            if (prefix.length > 0) {
                output.write(ByteBuffer.wrap(prefix));
                dataSize += prefix.length;
            }
        }
    }

    /**
     * On a seekable sink, pads the data of a Sony Wave64 file to a multiple of eight bytes and writes the sizes, the
     * sample count and the final format into its header; a streaming file keeps its zero sizes and ends with the
     * data.
     */
    private void finishWave64() {
        if (!output.isSeekable()) {
            return;
        }
        var padding = (int) (-dataSize & 7);
        if (padding > 0) {
            output.write(new byte[padding]);
        }
        var end = output.position();
        var frames = coded != null ? coded.samples() : dataSize / blockAlign;
        if (coded != null) {
            writeAt(formatOffset, coded.format());
        } else if (bursts != null) {
            writeAt(formatOffset, bursts.format(true));
        }
        patch64(16, headerSize + dataSize + padding);
        patch64(headerSize - 8, 24 + dataSize);
        if (factOffset >= 0) {
            patch64(factOffset, frames);
        }
        output.position(end);
    }

    /**
     * Writes the GUID that names a Sony Wave64 chunk: its four-character code followed by the GUID suffix of the
     * {@code riff} header or of the WAVE chunks.
     *
     * @param header the header being built
     * @param code the four-character code
     */
    private static void wave64Guid(ByteWriter header, String code) {
        header.fourCc(code);
        if (code.equals("riff")) {
            header.bytes(new byte[]{0x2E, (byte) 0x91, (byte) 0xCF, 0x11, (byte) 0xA5, (byte) 0xD6, 0x28,
                    (byte) 0xDB, 0x04, (byte) 0xC1, 0x00, 0x00});
        } else {
            header.bytes(new byte[]{(byte) 0xF3, (byte) 0xAC, (byte) 0xD3, 0x11, (byte) 0x8C, (byte) 0xD1, 0x00,
                    (byte) 0xC0, 0x4F, (byte) 0x8E, (byte) 0xDB, (byte) 0x8A});
        }
    }

    /**
     * Returns the {@code fmt } chunk body of PCM.
     *
     * @return the body
     */
    private byte[] pcmFormat() {
        var layout = track.layout().orElseThrow();
        var channels = layout.channels();
        var bits = storedCodec.pcmBitsPerSample().orElseThrow();
        var tag = switch (storedCodec) {
            case PCM_F32LE, PCM_F64LE -> WaveFormat.FLOAT;
            case PCM_ALAW -> WaveFormat.ALAW;
            case PCM_MULAW -> WaveFormat.MULAW;
            default -> WaveFormat.PCM;
        };
        var standardLayout = channels <= 2 && layout.equals(ChannelLayout.defaultFor(channels));
        var bFormat = tag != WaveFormat.ALAW && tag != WaveFormat.MULAW && WaveFormat.isAmbisonicBFormat(layout);
        var extensible = tag != WaveFormat.ALAW && tag != WaveFormat.MULAW
                && (channels > 2 || !standardLayout || tag == WaveFormat.PCM && bits > 16);
        var subFormat = bFormat ? WaveFormat.SubFormat.AMBISONIC
                : extensible ? WaveFormat.SubFormat.STANDARD : WaveFormat.SubFormat.NONE;
        var format = new WaveFormat(tag, channels, track.sampleRate(), (long) track.sampleRate() * blockAlign, blockAlign,
                bits, bits, extensible && !bFormat ? WaveFormat.channelMask(layout) : 0, subFormat, new byte[0]);
        var out = new ByteWriter(format.size(tag != WaveFormat.PCM));
        format.write(out, tag != WaveFormat.PCM);
        return out.toByteArray();
    }

    /**
     * Rewrites a 64-bit little-endian field of the header.
     *
     * @param offset the field offset relative to the start of the file
     * @param value the field value
     */
    private void patch64(int offset, long value) {
        var field = new ByteWriter(8);
        field.u64le(value);
        writeAt(offset, field);
    }

    /**
     * Rewrites a 32-bit little-endian field of the header.
     *
     * @param offset the field offset relative to the start of the file
     * @param value the field value, an unsigned 32-bit integer
     */
    private void patch32(int offset, long value) {
        var field = new ByteWriter(4);
        field.u32le(value);
        writeAt(offset, field);
    }

    /**
     * Writes assembled bytes at an offset relative to the start of the file.
     *
     * @param offset the offset relative to the start of the file
     * @param bytes the bytes to write, from their first
     */
    private void writeAt(long offset, ByteWriter bytes) {
        output.position(base + offset);
        bytes.writeTo(output);
    }

    /**
     * Writes bytes at an offset relative to the start of the file.
     *
     * @param offset the offset relative to the start of the file
     * @param bytes the bytes to write
     */
    private void writeAt(long offset, byte[] bytes) {
        output.position(base + offset);
        output.write(ByteBuffer.wrap(bytes));
    }
}
