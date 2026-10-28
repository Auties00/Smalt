package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.internal.AacRawBlocks;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.AmrConfigs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.DtsConfigs;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.internal.header.FlacStreamInfo;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes the coded audio of a {@link WavMuxer} track: the {@code fmt } structure of its codec, the Broadcast Wave
 * {@code mext} chunk of MPEG Layer I and II, the sample count of the {@code fact} chunk, and the packets in the framing
 * the {@code data} chunk stores.
 *
 * <p>The structures come in two forms: the provisional one written with the header, which depends only on the first
 * packet and states nothing it has not seen (variable frame sizes, an unknown bit rate, non-homogeneous data), and the
 * final one {@link WavMuxer#finish()} writes over it, from every packet. Both have the same size.
 *
 * <p>Instances are not thread-safe.
 */
final class WavCodedWriter {
    /**
     * The size of the {@code MPEG1WAVEFORMAT} fields after {@code cbSize}.
     */
    private static final int MPEG1_EXTRA = 22;

    /**
     * The size of the {@code MPEGLAYER3WAVEFORMAT} fields after {@code cbSize}.
     */
    private static final int MPEG_LAYER3_EXTRA = 12;

    /**
     * The size of the {@code mext} chunk body (EBU Tech 3285 Supplement 1, section 2.1).
     */
    static final int MEXT_SIZE = 12;

    /**
     * The MPEG audio header bits that frames of homogeneous sound data share: all but the padding, private, mode and
     * mode extension bits (EBU Tech 3285 Supplement 1, section 2.2).
     */
    private static final int HOMOGENEOUS_MASK = 0xFFFFFC0F;

    /**
     * The largest Opus frame, in bytes (RFC 6716 rule R2).
     */
    private static final int MAX_OPUS_FRAME = 1275;

    /**
     * The seven standard predictor coefficient pairs of Microsoft ADPCM, as an {@code ADPCMWAVEFORMAT} stores them.
     */
    private static final int[] MS_ADPCM_COEFFICIENTS = {256, 0, 512, -256, 0, 0, 192, 64, 240, 0, 460, -208, 392, -232};

    /**
     * The codec written.
     */
    private final Codec codec;

    /**
     * The channel count of the format.
     */
    private final int channels;

    /**
     * The sampling rate of the format, which the {@code fact} count is expressed in.
     */
    private final int sampleRate;

    /**
     * The rate the sample counts of {@link Stats} are kept at.
     */
    private final int countRate;

    /**
     * The channel mask of an E-AC-3 format.
     */
    private final long channelMask;

    /**
     * The bits per sample the format declares: the depth of FLAC, zero otherwise.
     */
    private final int bitsPerSample;

    /**
     * The codec-specific bytes of the format that do not depend on the packets.
     */
    private final byte[] extra;

    /**
     * The ADTS header written before each AAC packet, or {@code null} for raw data blocks and the other codecs.
     */
    private final AdtsHeader adts;

    /**
     * The samples one AAC packet codes, at {@link #countRate}.
     */
    private final int aacFrameLength;

    /**
     * The channel count of an AMR stream, which is the number of frames in each packet.
     */
    private final int amrChannels;

    /**
     * The encoder delay an MP3 format declares, in samples.
     */
    private final int codecDelay;

    /**
     * The time base of the track's packet durations.
     */
    private final Rational timeBase;

    /**
     * The time base of one sample at {@link #sampleRate}.
     */
    private final Rational sampleBase;

    /**
     * The G.726 code size in bits, or 0.
     */
    private final int codeSize;

    /**
     * The predictor coefficient pairs of Microsoft ADPCM, as an {@code ADPCMWAVEFORMAT} stores them after
     * {@code wNumCoef}, or {@code null}.
     */
    private final byte[] coefficients;

    /**
     * The statistics of every packet written.
     */
    private final Stats stats = new Stats();

    /**
     * Whether an ADPCM packet shorter than the stream's blocks, or not ending on a unit boundary, was written, so that
     * it was the last.
     */
    private boolean ended;

    /**
     * The bytes of an ADTS header.
     */
    private final byte[] adtsBytes = new byte[AdtsHeader.SIZE];

    /**
     * The last packet {@link #prepare(Packet)} converted to the self-delimiting Opus framing.
     */
    private byte[] scratch = new byte[0];

    /**
     * Constructs a writer for a track.
     *
     * @param track the track, of a codec {@link #supports(Codec)} accepts, with its sampling rate and layout
     * @throws IllegalArgumentException if the track lacks the configuration its codec needs, or its rate or channel
     * count does not fit the codec
     */
    WavCodedWriter(AudioTrack track) {
        this.codec = track.codec();
        var layout = track.layout().orElseThrow();
        var channels = layout.channels();
        var sampleRate = track.sampleRate();
        this.channelMask = codec == Codec.EAC3 ? WaveFormat.channelMask(layout) : 0;
        this.timeBase = track.timeBase();
        var config = track.codecConfig().orElse(null);
        var rate = sampleRate;
        var bits = 0;
        var extra = new byte[0];
        AdtsHeader adts = null;
        var aacFrameLength = 0;
        var amrChannels = 0;
        var codecDelay = 0;
        var codeSize = 0;
        byte[] coefficients = null;
        if (AdpcmBlocks.supports(codec) && !AdpcmBlocks.hasBlocks(codec) && channels != 1) {
            throw new IllegalArgumentException("a WAV " + codec.displayName() + " track must have one channel");
        }
        if (AdpcmBlocks.hasBlocks(codec) && AdpcmBlocks.headerBytes(codec, channels) > 0xFFFF) {
            throw new IllegalArgumentException("a " + codec.displayName() + " block of " + channels
                    + " channels is larger than a WAVE format can declare");
        }
        switch (codec) {
            case AAC -> {
                if (config == null) {
                    throw new IllegalArgumentException("a WAV AAC track needs its AudioSpecificConfig");
                }
                AudioConfigs.AacConfig aac;
                try {
                    aac = AudioConfigs.parseAac(config);
                } catch (MediaException.Malformed exception) {
                    throw new IllegalArgumentException("malformed AudioSpecificConfig", exception);
                }
                if (!AacRawBlocks.supports(aac.objectType())) {
                    throw new IllegalArgumentException("WAV carries AAC Main, LC, SSR and LTP, alone or under SBR and"
                            + " PS, in ADTS frames or raw data blocks, not object type " + aac.objectType());
                }
                try {
                    adts = AdtsHeader.fromAudioSpecificConfig(config);
                } catch (IllegalArgumentException exception) {
                    // HEAACWAVEINFO: raw payload, no profile and level indication, AudioSpecificConfig follows; the
                    // format describes the core stream, without SBR or PS
                    sampleRate = aac.sampleRate();
                    channels = aac.channels() > 0 ? aac.channels() : channels;
                    var info = new ByteWriter(12 + config.length);
                    info.u16le(0);
                    info.u16le(0xFE);
                    info.u16le(0);
                    info.u16le(0);
                    info.u32le(0);
                    info.bytes(config);
                    extra = info.toByteArray();
                }
                rate = aac.sampleRate();
                aacFrameLength = aac.frameLength();
            }
            case FLAC -> {
                if (config == null) {
                    throw new IllegalArgumentException("a WAV FLAC track needs its STREAMINFO configuration");
                }
                try {
                    AudioConfigs.parseFlac(config);
                } catch (MediaException.Malformed exception) {
                    throw new IllegalArgumentException("malformed FLAC configuration", exception);
                }
                extra = Arrays.copyOfRange(config, 8, 8 + FlacStreamInfo.SIZE);
                bits = FlacStreamInfo.read(config, 8).bitsPerSample();
            }
            case OPUS -> {
                rate = 48_000;
                if (config != null && config.length >= 19 && new String(config, 0, 8, StandardCharsets.ISO_8859_1)
                        .equals("OpusHead")) {
                    extra = config.clone();
                } else if (channels <= 2) {
                    extra = AudioConfigs.opusHead(channels, (int) Math.min(0xFFFF, Math.max(0, track.skipSamples())),
                            sampleRate, false);
                } else {
                    throw new IllegalArgumentException("a WAV Opus track of " + channels + " channels needs its OpusHead");
                }
            }
            case AMR_NB, AMR_WB -> {
                var wanted = codec == Codec.AMR_NB ? 8000 : 16_000;
                if (sampleRate != wanted || channels > 15) {
                    throw new IllegalArgumentException("a WAV " + codec.displayName() + " track must have 1 to 15 channels"
                            + " at " + wanted + " Hz");
                }
                amrChannels = channels;
            }
            case MP3 -> codecDelay = (int) Math.min(0xFFFF, Math.max(0, track.skipSamples()));
            case ADPCM_G726 -> {
                codeSize = track.bitsPerSample();
                if (codeSize < 2 || codeSize > 5) {
                    throw new IllegalArgumentException("a WAV G.726 track needs its code size, 2 to 5 bits per sample");
                }
                bits = codeSize;
            }
            case ADPCM_IMA_WAV, ADPCM_MS -> {
                if (track.bitsPerSample() != 0 && track.bitsPerSample() != 4) {
                    throw new IllegalArgumentException("a WAV " + codec.displayName() + " track has 4-bit codes, not "
                            + track.bitsPerSample());
                }
                bits = 4;
                if (codec == Codec.ADPCM_MS) {
                    coefficients = msCoefficients(config);
                }
            }
            case ADPCM_G722 -> bits = 4;
            default -> {
            }
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.sampleBase = Rational.timeBaseOf(sampleRate);
        this.countRate = rate;
        this.bitsPerSample = bits;
        this.extra = extra;
        this.adts = adts;
        this.aacFrameLength = aacFrameLength;
        this.amrChannels = amrChannels;
        this.codecDelay = codecDelay;
        this.codeSize = codeSize;
        this.coefficients = coefficients;
    }

    /**
     * Returns the predictor coefficients of a Microsoft ADPCM configuration.
     *
     * @param config the configuration, the {@code ADPCMWAVEFORMAT} fields after {@code cbSize}, or {@code null}
     * @return {@code wNumCoef} and the coefficient pairs, the seven standard pairs without a configuration
     * @throws IllegalArgumentException if the configuration is shorter than its coefficient table, or declares none
     */
    private static byte[] msCoefficients(byte[] config) {
        if (config == null) {
            var out = new ByteWriter(2 + 2 * MS_ADPCM_COEFFICIENTS.length);
            out.u16le(MS_ADPCM_COEFFICIENTS.length / 2);
            for (var coefficient : MS_ADPCM_COEFFICIENTS) {
                out.u16le(coefficient & 0xFFFF);
            }
            return out.toByteArray();
        }
        var count = config.length >= 4 ? (config[2] & 0xFF) | (config[3] & 0xFF) << 8 : 0;
        if (count == 0 || config.length < 4 + 4 * count) {
            throw new IllegalArgumentException("a Microsoft ADPCM configuration of " + config.length + " bytes and "
                    + count + " coefficient pairs");
        }
        return Arrays.copyOfRange(config, 2, 4 + 4 * count);
    }

    /**
     * Returns whether this writer handles a codec.
     *
     * @param codec the codec of a track
     * @return {@code true} for MPEG audio, AC-3, E-AC-3, AAC, FLAC, Opus, AMR, DTS and the ADPCM and telephony codecs
     */
    static boolean supports(Codec codec) {
        return switch (codec) {
            case MP2, MP3, AC3, EAC3, AAC, FLAC, OPUS, AMR_NB, AMR_WB, DTS -> true;
            default -> AdpcmBlocks.supports(codec);
        };
    }

    /**
     * {@return whether the file carries a {@code mext} chunk: for MPEG Layer I and II}
     */
    boolean hasExtension() {
        return codec == Codec.MP2;
    }

    /**
     * Returns the bytes the {@code data} chunk starts with before the first packet: the multichannel magic and channel
     * description of AMR with more than one channel.
     *
     * @return the bytes, empty for the other streams
     */
    byte[] prefix() {
        if (amrChannels <= 1) {
            return new byte[0];
        }
        var magic = (codec == Codec.AMR_WB ? "#!AMR-WB_MC1.0\n" : "#!AMR_MC1.0\n").getBytes(StandardCharsets.ISO_8859_1);
        var header = Arrays.copyOf(magic, magic.length + 4);
        header[header.length - 1] = (byte) amrChannels;
        return header;
    }

    /**
     * Checks a packet and returns the number of bytes it takes in the {@code data} chunk.
     *
     * <p>An Opus packet is converted to the self-delimiting framing, which the next {@link #write} writes.
     *
     * @param packet the packet to store, of the track
     * @return the stored size, in bytes
     * @throws IllegalArgumentException if the packet does not start with a frame of the codec, or is not a whole number
     * of frames where the codec's frames carry their own size; for ADPCM, if it is not a block, not a whole number of
     * units of a code stream, or follows the last packet
     */
    int prepare(Packet packet) {
        var data = packet.data();
        var size = data.length;
        return switch (codec) {
            case ADPCM_IMA_WAV, ADPCM_MS, GSM_MS, ADPCM_G722, ADPCM_G726 -> {
                checkAdpcm(size);
                yield size;
            }
            case MP2, MP3 -> {
                if (size < MpegAudioHeader.SIZE || !MpegAudioHeader.isValid(MpegAudioHeader.read(data, 0))) {
                    throw new IllegalArgumentException("the packet does not start with an MPEG audio frame header");
                }
                var layer = MpegAudioHeader.layer(MpegAudioHeader.read(data, 0));
                if (layer == 3 != (codec == Codec.MP3)) {
                    throw new IllegalArgumentException("a Layer " + layer + " frame in a " + codec.displayName() + " track");
                }
                yield size;
            }
            case AC3, EAC3 -> {
                if (Ac3Configs.frameLength(data, 0, size) <= 0) {
                    throw new IllegalArgumentException("the packet does not start with an AC-3 or E-AC-3 sync frame");
                }
                yield size;
            }
            case DTS -> {
                if (size < DtsConfigs.HEADER_SIZE || !DtsConfigs.isSync(data, 0)) {
                    throw new IllegalArgumentException("the packet does not start with a DTS core frame");
                }
                yield size;
            }
            case AMR_NB, AMR_WB -> {
                amrFrames(data);
                yield size;
            }
            case FLAC -> {
                if (AudioConfigs.flacFrameSamples(data) == 0) {
                    throw new IllegalArgumentException("the packet does not start with a FLAC frame header");
                }
                yield size;
            }
            case OPUS -> selfDelimit(data);
            case AAC -> {
                if (adts == null) {
                    yield size;
                }
                if (size + AdtsHeader.SIZE > AdtsHeader.MAX_FRAME_LENGTH) {
                    throw new IllegalArgumentException("an AAC packet of " + size + " bytes does not fit an ADTS frame");
                }
                yield size + AdtsHeader.SIZE;
            }
            default -> throw new IllegalStateException("not a coded payload: " + codec);
        };
    }

    /**
     * Writes a packet {@link #prepare(Packet)} accepted and adds it to the statistics.
     *
     * @param packet the packet to store, which {@code prepare} accepted
     * @param stored the size {@code prepare} returned for it
     * @param sink the destination
     */
    void write(Packet packet, int stored, MediaSink sink) {
        var data = packet.data();
        if (AdpcmBlocks.supports(codec)) {
            ended |= AdpcmBlocks.hasBlocks(codec) ? stats.units > 0 && stored < stats.first
                    : stored % AdpcmBlocks.unitBytes(codec, codeSize) != 0;
        }
        stats.add(data, packet.duration(), stored);
        switch (codec) {
            case OPUS -> sink.write(ByteBuffer.wrap(scratch, 0, stored));
            case AAC -> {
                if (adts != null) {
                    adts.write(adtsBytes, 0, stored);
                    sink.write(ByteBuffer.wrap(adtsBytes));
                }
                sink.write(ByteBuffer.wrap(data));
            }
            default -> sink.write(ByteBuffer.wrap(data));
        }
    }

    /**
     * Returns the {@code fact} sample count of the packets written.
     *
     * @return the samples per channel, at the format's sampling rate
     */
    long samples() {
        return countRate == sampleRate ? stats.samples : Math.multiplyExact(stats.samples, (long) sampleRate) / countRate;
    }

    /**
     * Returns the provisional {@code fmt } chunk body, which depends only on the first packet.
     *
     * @param first the first packet, accepted by {@link #prepare(Packet)}, or {@code null} for a stream without packets
     * @param stored the stored size of {@code first}
     * @return the body
     */
    byte[] format(Packet first, int stored) {
        return format(provisional(first, stored), false);
    }

    /**
     * {@return the final {@code fmt } chunk body, from every packet written}
     */
    byte[] format() {
        return format(stats, true);
    }

    /**
     * Returns the provisional {@code mext} chunk body: data not known to be homogeneous.
     *
     * @return the body
     */
    byte[] extension() {
        return extension(stats, false);
    }

    /**
     * {@return the final {@code mext} chunk body, from every packet written}
     */
    byte[] finalExtension() {
        return extension(stats, true);
    }

    /**
     * Returns the statistics of one packet.
     *
     * @param first the packet, or {@code null}
     * @param stored its stored size
     * @return the statistics
     */
    private Stats provisional(Packet first, int stored) {
        var provisional = new Stats();
        if (first != null) {
            provisional.add(first.data(), first.duration(), stored);
        }
        return provisional;
    }

    /**
     * Builds a {@code fmt } chunk body.
     *
     * @param stats the packets described
     * @param complete whether {@code stats} covers the whole stream
     * @return the body
     */
    private byte[] format(Stats stats, boolean complete) {
        var blockAlign = complete && stats.constant && stats.units > 0 && stats.first <= 0xFFFF ? stats.first : 1;
        var byteRate = stats.samples > 0 ? Math.round((double) stats.bytes * countRate / stats.samples) : 0;
        var tag = 0;
        var formatExtra = extra;
        var subFormat = WaveFormat.SubFormat.NONE;
        switch (codec) {
            case MP2, MP3 -> {
                var header = stats.frames > 0 ? stats.header : 0;
                var bitrate = complete && stats.frames > 0 && stats.constantBitrate
                        ? MpegAudioHeader.bitrateKbps(header) * 1000L : 0;
                if (bitrate > 0) {
                    byteRate = bitrate / 8;
                }
                var out = new ByteWriter(MPEG1_EXTRA);
                if (codec == Codec.MP2) {
                    tag = WaveFormat.MPEG;
                    out.u16le(stats.layers != 0 ? stats.layers : 2);
                    out.u32le(bitrate);
                    out.u16le(stats.modes != 0 ? stats.modes : channels == 1 ? 8 : 1);
                    out.u16le(stats.modeExtensions);
                    out.u16le(stats.frames > 0 ? (header & 3) + 1 : 1);
                    out.u16le(stats.frames > 0 ? stats.flags : 0x10);
                    out.u32le(0);
                    out.u32le(0);
                } else {
                    tag = WaveFormat.MPEG_LAYER3;
                    var padding = !complete || stats.padded > 0 && stats.padded < stats.frames ? 0
                            : stats.padded == 0 ? 2 : 1;
                    var blockSize = complete && stats.frames > 0 && stats.constantBitrate
                            ? MpegAudioHeader.frameBytes(header, 0) : stats.largest;
                    out.u16le(1);
                    out.u32le(padding);
                    out.u16le(Math.min(blockSize, 0xFFFF));
                    out.u16le(1);
                    out.u16le(codecDelay);
                }
                formatExtra = out.toByteArray();
            }
            case AC3 -> tag = WaveFormat.DOLBY_AC3;
            case EAC3 -> {
                tag = WaveFormat.DOLBY_AC3;
                subFormat = WaveFormat.SubFormat.DOLBY_DIGITAL_PLUS;
            }
            case AAC -> {
                tag = adts != null ? WaveFormat.MPEG_ADTS_AAC : WaveFormat.MPEG_HEAAC;
                if (adts == null) {
                    blockAlign = 1;
                }
            }
            case FLAC -> tag = WaveFormat.FLAC;
            case OPUS -> tag = WaveFormat.OPUS;
            case AMR_NB -> tag = WaveFormat.AMR_NB;
            case AMR_WB -> tag = WaveFormat.AMR_WB;
            case DTS -> tag = WaveFormat.DTS;
            case ADPCM_IMA_WAV, ADPCM_MS -> {
                tag = codec == Codec.ADPCM_MS ? WaveFormat.MS_ADPCM : WaveFormat.IMA_ADPCM;
                // a stream without packets declares blocks of 256 bytes per channel, a size both formats allow, or
                // blocks of only the headers when those do not fit the 16-bit field
                blockAlign = stats.units > 0 ? stats.first : 256 * channels <= 0xFFFF ? 256 * channels
                        : (int) AdpcmBlocks.headerBytes(codec, channels);
                var samplesPerBlock = blockAlign > 0 ? AdpcmBlocks.samples(codec, blockAlign, channels, 0) : 0;
                byteRate = samplesPerBlock > 0 ? (long) sampleRate * blockAlign / samplesPerBlock : 0;
                var out = new ByteWriter(2 + (coefficients == null ? 0 : coefficients.length));
                out.u16le((int) samplesPerBlock);
                if (coefficients != null) {
                    out.bytes(coefficients);
                }
                formatExtra = out.toByteArray();
            }
            case GSM_MS -> {
                tag = WaveFormat.GSM610;
                blockAlign = AdpcmBlocks.GSM_BLOCK;
                byteRate = (long) sampleRate * AdpcmBlocks.GSM_BLOCK / AdpcmBlocks.GSM_SAMPLES;
                var out = new ByteWriter(2);
                out.u16le(AdpcmBlocks.GSM_SAMPLES);
                formatExtra = out.toByteArray();
            }
            case ADPCM_G722 -> {
                tag = WaveFormat.G722;
                blockAlign = 1;
                byteRate = sampleRate / 2;
            }
            case ADPCM_G726 -> {
                tag = WaveFormat.G726;
                blockAlign = AdpcmBlocks.unitBytes(codec, codeSize);
                byteRate = (long) sampleRate * codeSize / 8;
            }
            default -> throw new IllegalStateException("not a coded payload: " + codec);
        }
        var format = new WaveFormat(tag, channels, sampleRate, Math.min(byteRate, 0xFFFFFFFFL), blockAlign, bitsPerSample,
                bitsPerSample, channelMask, subFormat, formatExtra);
        var out = new ByteWriter(format.size(true));
        format.write(out, true);
        return out.toByteArray();
    }

    /**
     * Builds a {@code mext} chunk body.
     *
     * @param stats the frames described
     * @param complete whether {@code stats} covers the whole stream
     * @return the body
     */
    private byte[] extension(Stats stats, boolean complete) {
        var information = 0;
        var frameSize = 0;
        if (complete && stats.frames > 0 && stats.homogeneous) {
            information = 1;
            if (stats.padded == 0) {
                information |= 2;
                var rate = MpegAudioHeader.sampleRate(stats.header);
                if (rate == 44_100 || rate == 22_050) {
                    information |= 4;
                }
            }
            if (MpegAudioHeader.isFreeFormat(stats.header)) {
                information |= 8;
            }
            // a padding bit set in every frame makes the frames constant, whose size the field then holds
            var slot = stats.padded == stats.frames ? MpegAudioHeader.padding(stats.header) : 0;
            frameSize = Math.min(stats.nominal + slot, 0xFFFF);
        }
        var out = new ByteWriter(MEXT_SIZE);
        out.u16le(information);
        out.u16le(frameSize);
        out.u16le(0);
        out.u16le(0);
        out.zeros(4);
        return out.toByteArray();
    }

    /**
     * Checks the size of an ADPCM packet against the packets written before it.
     *
     * @param size the packet size
     * @throws IllegalArgumentException if the packet is empty, not a block, a block larger than the first or of
     * another size after a shorter one, not a whole number of units of a code stream, or follows the last packet
     */
    private void checkAdpcm(int size) {
        var name = codec.displayName();
        if (size == 0) {
            throw new IllegalArgumentException("an empty " + name + " packet");
        }
        if (ended) {
            throw new IllegalArgumentException("a " + name + " packet after the last one, which was shorter than the "
                    + "others or ended inside a unit of codes");
        }
        if (AdpcmBlocks.hasBlocks(codec)) {
            if (!AdpcmBlocks.isBlock(codec, size, channels)) {
                throw new IllegalArgumentException("a " + name + " packet of " + size + " bytes is not a block of "
                        + channels + " channels");
            }
            if (size > 0xFFFF || AdpcmBlocks.samples(codec, size, channels, 0) > 0xFFFF) {
                throw new IllegalArgumentException("a " + name + " block of " + size + " bytes is larger than a WAVE "
                        + "format can declare");
            }
            if (stats.units > 0 && size > stats.first) {
                throw new IllegalArgumentException("a " + name + " block of " + size + " bytes after blocks of "
                        + stats.first);
            }
        } else if (codec == Codec.GSM_MS && size % AdpcmBlocks.GSM_BLOCK != 0) {
            throw new IllegalArgumentException("a GSM 06.10 packet of " + size + " bytes is not whole 65-byte blocks");
        } else if (AdpcmBlocks.samples(codec, size, channels, codeSize) == 0) {
            throw new IllegalArgumentException("a " + name + " packet of " + size + " bytes codes no sample");
        }
    }

    /**
     * Counts the frames of an AMR packet, one per channel.
     *
     * @param data the packet
     * @return the frame count
     * @throws IllegalArgumentException if the packet is not a whole number of frame-blocks
     */
    private int amrFrames(byte[] data) {
        var wideband = codec == Codec.AMR_WB;
        var frames = 0;
        var offset = 0;
        while (offset < data.length) {
            offset += 1 + AmrConfigs.payloadSize(wideband, (data[offset] >> 3) & 0x0F);
            frames++;
        }
        if (frames == 0 || offset != data.length || frames % amrChannels != 0) {
            throw new IllegalArgumentException("an AMR packet must hold whole frame-blocks of " + amrChannels
                    + " frames");
        }
        return frames;
    }

    /**
     * Converts an Opus packet from the standard framing of RFC 6716 section 3 to the self-delimiting framing of its
     * Appendix B into {@link #scratch}, adding the length of the frame the standard framing leaves implicit.
     *
     * @param packet the packet in the standard framing
     * @return the converted length
     * @throws IllegalArgumentException if the packet breaks the framing rules
     */
    private int selfDelimit(byte[] packet) {
        var size = packet.length;
        if (size == 0) {
            throw new IllegalArgumentException("an empty Opus packet");
        }
        var samples = AudioConfigs.opusPacketSamples(packet);
        if (samples == 0) {
            throw new IllegalArgumentException("an Opus packet of zero frames or longer than 120 ms");
        }
        int insertAt;
        long length;
        switch (packet[0] & 3) {
            case 0 -> {
                insertAt = 1;
                length = size - 1;
            }
            case 1 -> {
                if (((size - 1) & 1) != 0) {
                    throw new IllegalArgumentException("a code 1 Opus packet of an odd payload size");
                }
                insertAt = 1;
                length = (size - 1) / 2;
            }
            case 2 -> {
                var first = opusLength(packet, 1);
                insertAt = 1 + (first >>> 16);
                length = size - insertAt - (long) (first & 0xFFFF);
                checkOpusFrame(first & 0xFFFF);
            }
            default -> {
                if (size < 2) {
                    throw new IllegalArgumentException("a code 3 Opus packet without its frame count");
                }
                var count = packet[1] & 0xFF;
                var frames = count & 0x3F;
                var position = 2;
                long padding = 0;
                if ((count & 0x40) != 0) {
                    int value;
                    do {
                        if (position >= size) {
                            throw new IllegalArgumentException("a truncated Opus padding length");
                        }
                        value = packet[position++] & 0xFF;
                        padding += value == 255 ? 254 : value;
                    } while (value == 255);
                }
                if ((count & 0x80) == 0) {
                    var body = size - position - padding;
                    if (body < 0 || body % frames != 0) {
                        throw new IllegalArgumentException("a constant-bitrate Opus packet of unequal frames");
                    }
                    insertAt = position;
                    length = body / frames;
                } else {
                    long sum = 0;
                    for (var frame = 0; frame < frames - 1; frame++) {
                        var field = opusLength(packet, position);
                        checkOpusFrame(field & 0xFFFF);
                        sum += field & 0xFFFF;
                        position += field >>> 16;
                    }
                    insertAt = position;
                    length = size - position - padding - sum;
                }
            }
        }
        if (length < 0) {
            throw new IllegalArgumentException("an Opus packet shorter than its frame lengths");
        }
        checkOpusFrame(length);
        var field = length < 252 ? 1 : 2;
        var stored = size + field;
        if (scratch.length < stored) {
            scratch = new byte[Math.max(stored, scratch.length * 2)];
        }
        System.arraycopy(packet, 0, scratch, 0, insertAt);
        if (field == 1) {
            scratch[insertAt] = (byte) length;
        } else {
            var low = 252 + (int) (length & 3);
            scratch[insertAt] = (byte) low;
            scratch[insertAt + 1] = (byte) ((length - low) >> 2);
        }
        System.arraycopy(packet, insertAt, scratch, insertAt + field, size - insertAt);
        return stored;
    }

    /**
     * Reads a frame length field of an Opus packet (RFC 6716 section 3.1).
     *
     * @param packet the packet as the track carries it
     * @param offset the index of the field
     * @return the length in the low 16 bits and the field size above them
     * @throws IllegalArgumentException if the field is cut short
     */
    private static int opusLength(byte[] packet, int offset) {
        if (offset >= packet.length) {
            throw new IllegalArgumentException("a truncated Opus frame length");
        }
        var first = packet[offset] & 0xFF;
        if (first < 252) {
            return 1 << 16 | first;
        }
        if (offset + 1 >= packet.length) {
            throw new IllegalArgumentException("a truncated Opus frame length");
        }
        return 2 << 16 | (first + 4 * (packet[offset + 1] & 0xFF));
    }

    /**
     * Checks an Opus frame length against RFC 6716 rule R2.
     *
     * @param length the frame length
     * @throws IllegalArgumentException if the frame is longer than 1275 bytes
     */
    private static void checkOpusFrame(long length) {
        if (length > MAX_OPUS_FRAME) {
            throw new IllegalArgumentException("an Opus frame of " + length + " bytes");
        }
    }

    /**
     * Returns the samples an AC-3 or E-AC-3 access unit codes: those of its AC-3 frames and of the independent frames
     * of its substream 0.
     *
     * @param data the access unit
     * @return the samples per channel
     */
    private static int ac3Samples(byte[] data) {
        var samples = 0;
        var offset = 0;
        while (data.length - offset >= 6) {
            var length = Ac3Configs.frameLength(data, offset, data.length - offset);
            if (length <= 0) {
                break;
            }
            if (Ac3Configs.startsAccessUnit(data, offset)) {
                var info = data[offset + 4] & 0xFF;
                var blocks = (data[offset + 5] & 0xFF) >> 3 <= 10 || info >> 6 == 3 ? 6
                        : Ac3Configs.BLOCKS[(info >> 4) & 3];
                samples += 256 * blocks;
            }
            offset += length;
        }
        return samples;
    }

    /**
     * Accumulates what the format structures describe about the packets.
     */
    private final class Stats {
        /**
         * The number of units: MPEG audio frames, or packets for the other codecs.
         */
        private long units;

        /**
         * The stored size of the first unit.
         */
        private int first;

        /**
         * The stored size of the largest unit.
         */
        private int largest;

        /**
         * Whether every unit has the stored size of the first.
         */
        private boolean constant = true;

        /**
         * The bytes stored.
         */
        private long bytes;

        /**
         * The samples per channel, at {@link #countRate}.
         */
        private long samples;

        /**
         * The number of MPEG audio frames.
         */
        private long frames;

        /**
         * The header of the first MPEG audio frame.
         */
        private int header;

        /**
         * The size of the first MPEG audio frame without its padding slot.
         */
        private int nominal;

        /**
         * The {@code ACM_MPEG_LAYER} flags of the frames.
         */
        private int layers;

        /**
         * The {@code ACM_MPEG} mode flags of the frames.
         */
        private int modes;

        /**
         * The mode extensions of the joint stereo frames, one bit per value.
         */
        private int modeExtensions;

        /**
         * The {@code ACM_MPEG} header flags of the frames.
         */
        private int flags;

        /**
         * The number of MPEG audio frames with the padding bit set.
         */
        private long padded;

        /**
         * Whether every MPEG audio frame has the bit rate of the first, which is not free format.
         */
        private boolean constantBitrate = true;

        /**
         * Whether the MPEG audio frames are homogeneous sound data.
         */
        private boolean homogeneous = true;

        /**
         * Adds a packet.
         *
         * @param data the packet as given
         * @param duration the packet duration in the track's time base, or {@link Packet#UNKNOWN_DURATION}
         * @param stored the bytes it takes in the {@code data} chunk
         */
        private void add(byte[] data, long duration, int stored) {
            bytes += stored;
            switch (codec) {
                case ADPCM_IMA_WAV, ADPCM_MS, GSM_MS, ADPCM_G722, ADPCM_G726 -> {
                    var coded = AdpcmBlocks.samples(codec, stored, channels, codeSize);
                    // a duration cuts the count only when the time base resolves it as shorter than the codes, so
                    // that the rounding of a coarse time base cuts nothing
                    if (duration != Packet.UNKNOWN_DURATION && duration < sampleBase.rescale(coded, timeBase)) {
                        coded = Math.min(coded, timeBase.rescale(duration, sampleBase));
                    }
                    samples += coded;
                }
                case MP2, MP3 -> addMpeg(data);
                case AC3, EAC3 -> samples += ac3Samples(data);
                case DTS -> samples += DtsConfigs.samples(data, 0);
                case AMR_NB, AMR_WB -> samples += (long) amrFrames(data) / amrChannels * (codec == Codec.AMR_WB ? 320 : 160);
                case FLAC -> samples += AudioConfigs.flacFrameSamples(data);
                case OPUS -> samples += AudioConfigs.opusPacketSamples(data);
                case AAC -> samples += aacFrameLength;
                default -> {
                }
            }
            if (codec != Codec.MP2 && codec != Codec.MP3) {
                unit(stored);
            }
        }

        /**
         * Adds the frames of an MPEG audio packet; bytes after the last frame that do not start a compatible frame
         * belong to it.
         *
         * @param data the packet
         */
        private void addMpeg(byte[] data) {
            var offset = 0;
            while (offset < data.length) {
                var current = MpegAudioHeader.read(data, offset);
                var nominal = MpegAudioHeader.frameBytes(current, 0);
                var length = nominal + MpegAudioHeader.padding(current);
                if (nominal == 0 || length > data.length - offset - MpegAudioHeader.SIZE) {
                    length = data.length - offset;
                } else {
                    var next = MpegAudioHeader.read(data, offset + length);
                    if (!MpegAudioHeader.isValid(next) || !MpegAudioHeader.isCompatible(current, next)) {
                        length = data.length - offset;
                    }
                }
                addFrame(current, length);
                offset += length;
            }
        }

        /**
         * Adds one MPEG audio frame.
         *
         * @param current the frame header
         * @param length the frame size in bytes
         */
        private void addFrame(int current, int length) {
            samples += MpegAudioHeader.samplesPerFrame(current);
            unit(length);
            var nominal = length - MpegAudioHeader.padding(current);
            if (frames == 0) {
                header = current;
                this.nominal = nominal;
                constantBitrate = !MpegAudioHeader.isFreeFormat(current);
            } else {
                if (MpegAudioHeader.bitrateIndex(current) != MpegAudioHeader.bitrateIndex(header)) {
                    constantBitrate = false;
                }
                if (((current ^ header) & HOMOGENEOUS_MASK) != 0 || MpegAudioHeader.isMono(current) != MpegAudioHeader.isMono(header)
                        || nominal != this.nominal) {
                    homogeneous = false;
                }
            }
            frames++;
            if (MpegAudioHeader.isPadded(current)) {
                padded++;
            }
            layers |= 1 << (MpegAudioHeader.layer(current) - 1);
            var mode = MpegAudioHeader.mode(current);
            modes |= 1 << mode;
            if (mode == MpegAudioHeader.MODE_JOINT_STEREO) {
                modeExtensions |= 1 << MpegAudioHeader.modeExtension(current);
            }
            flags |= (current & 0x100) != 0 ? 1 : 0;
            flags |= (current & 0x08) != 0 ? 2 : 0;
            flags |= (current & 0x04) != 0 ? 4 : 0;
            flags |= MpegAudioHeader.hasCrc(current) ? 8 : 0;
            flags |= MpegAudioHeader.isMpeg1(current) ? 0x10 : 0;
        }

        /**
         * Adds a unit size.
         *
         * @param size the stored size
         */
        private void unit(int size) {
            if (units == 0) {
                first = size;
            } else if (size != first) {
                constant = false;
            }
            largest = Math.max(largest, size);
            units++;
        }
    }
}
