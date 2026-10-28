package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.DtsConfigs;
import com.github.auties00.smalt.internal.codec.TrueHdConfigs;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Writes the coded audio of a {@link WavMuxer} track as IEC 61937 data bursts (IEC 61937-1 to -6 and -9) in the
 * samples of 16-bit PCM: the {@code fmt } structure of the burst stream and the bursts of each packet.
 *
 * <p>A burst starts with the preamble words {@code Pa = 0xF872}, {@code Pb = 0x4E1F}, the burst information
 * {@code Pc} (the data type, the error flag of a packet flagged {@link PacketFlag#CORRUPT}, and data-type-dependent
 * bits) and the length code {@code Pd}; the payload follows in 16-bit words, a last odd byte padded with zero, and
 * zeros fill the rest of the repetition period. Words are stored little-endian, the byte order of the RIFF samples.
 * Every stream uses bitstream number 0.
 *
 * <table class="striped">
 * <caption>Bursts</caption>
 * <thead><tr><th scope="col">Codec</th><th scope="col">Data type</th><th scope="col">Payload</th>
 * <th scope="col">Repetition period, in IEC 61937 frames</th><th scope="col">Frame rate of the burst stream</th>
 * </tr></thead>
 * <tbody>
 * <tr><td>AC-3</td><td>1, {@code bsmod} in {@code Pc} bits 8 to 10</td><td>one sync frame</td><td>1536</td>
 * <td>the sampling rate</td></tr>
 * <tr><td>E-AC-3</td><td>21</td><td>the access units of six audio blocks</td><td>6144</td>
 * <td>four times the sampling rate</td></tr>
 * <tr><td>MPEG-1 audio</td><td>4 (Layer I), 5 (Layers II and III)</td><td>one frame</td><td>the frame's samples</td>
 * <td>the sampling rate</td></tr>
 * <tr><td>MPEG-2 audio at half rate</td><td>8, 9, 10 (Layers I, II, III)</td><td>one frame</td>
 * <td>twice the frame's samples</td><td>twice the sampling rate</td></tr>
 * <tr><td>AAC</td><td>7</td><td>one ADTS frame of 1024 samples</td><td>1024</td><td>the ADTS sampling rate</td></tr>
 * <tr><td>DTS</td><td>11, 12, 13 (512, 1024, 2048 samples)</td><td>one core frame</td><td>the frame's samples</td>
 * <td>the sampling rate</td></tr>
 * <tr><td>DTS-HD</td><td>17, {@code log2(period / 512)} in {@code Pc} bits 8 to 10</td><td>a 12-byte header, then the
 * core frame and its extension substream</td><td>16 times the frame's samples</td><td>16 times the sampling rate,
 * 8 channels at 4 times the sampling rate</td></tr>
 * <tr><td>Dolby TrueHD</td><td>22</td><td>a MAT frame of 24 access units</td><td>15360</td><td>16 times the 44.1 or
 * 48 kHz base rate, 8 channels at 4 times it</td></tr>
 * </tbody>
 * </table>
 *
 * <p>{@code Pd} is the payload length in bits for AC-3, MPEG audio, AAC and DTS, and in bytes for E-AC-3, DTS-HD and
 * MAT; the DTS-HD length is rounded up so that the burst fills whole 16-byte blocks. A DTS core frame that fills its
 * whole repetition period leaves no room for the preamble and is stored without one, as on a DTS audio CD, which
 * the first frame decides for the stream. The first DTS packet also decides between core and DTS-HD bursts: core
 * bursts carry the core frame only, and DTS-HD bursts follow when it carries an extension substream. The access
 * units of a MAT frame sit in slots of 2560 bytes from the start of its payload, after the start code for the first
 * and after the middle code for the thirteenth; a unit longer than its slot pushes the next ones back and runs over
 * the middle and end codes into the next frame. A stream ends with its last E-AC-3 group or MAT frame, padded.
 *
 * <p>Instances are not thread-safe.
 */
final class Iec61937Writer {
    /**
     * The size of a burst preamble, in bytes.
     */
    private static final int PREAMBLE = 8;

    /**
     * The size of one IEC 61937 frame, a 16-bit sample of both channels, in bytes.
     */
    private static final int FRAME = 4;

    /**
     * The repetition period of AC-3 bursts, in frames.
     */
    private static final int AC3_PERIOD = 1536;

    /**
     * The repetition period of E-AC-3 bursts, in frames.
     */
    private static final int EAC3_PERIOD = 6144;

    /**
     * The audio blocks of an E-AC-3 burst.
     */
    private static final int EAC3_BLOCKS = 6;

    /**
     * The repetition period of AAC bursts of one raw data block, in frames.
     */
    private static final int AAC_PERIOD = 1024;

    /**
     * The size of the header of a DTS type IV payload.
     */
    private static final int DTS4_HEADER = 12;

    /**
     * The DTS-HD extension substream sync word.
     */
    private static final int EXSS_SYNC = 0x64582025;

    /**
     * The number of TrueHD access units of a MAT frame.
     */
    private static final int MAT_UNITS = 24;

    /**
     * The size of the slot of each access unit of a MAT frame, in bytes.
     */
    private static final int MAT_SLOT = 2560;

    /**
     * The size of a MAT payload, in bytes.
     */
    private static final int MAT_PAYLOAD = 61424;

    /**
     * The repetition period of MAT bursts, in frames.
     */
    private static final int MAT_PERIOD = 15360;

    /**
     * The offset of the MAT middle code in a MAT payload.
     */
    private static final int MAT_MIDDLE = 30708;

    /**
     * The MAT start code, which opens every MAT payload.
     */
    private static final byte[] MAT_START_CODE = hex("079e000384010101800056a53bf48183498077e0");

    /**
     * The MAT middle code.
     */
    private static final byte[] MAT_MIDDLE_CODE = hex("c3c142493bfa8283498077e0");

    /**
     * The MAT end code, which closes every MAT payload.
     */
    private static final byte[] MAT_END_CODE = hex("c3c2c0c4000000000000000000009711");

    /**
     * The offset of the MAT end code in a MAT payload.
     */
    private static final int MAT_END = MAT_PAYLOAD - MAT_END_CODE.length;

    /**
     * The codec written.
     */
    private final Codec codec;

    /**
     * The sampling rate of the coded audio.
     */
    private final int sampleRate;

    /**
     * The channel count of the coded audio.
     */
    private final int codedChannels;

    /**
     * The channel mask of the coded audio's layout.
     */
    private final long codedMask;

    /**
     * Whether the format is a {@code WAVEFORMATEXTENSIBLE_IEC61937} structure.
     */
    private final boolean extensible;

    /**
     * Whether MPEG audio is of the half-rate MPEG-2 extension.
     */
    private final boolean lowSampleRate;

    /**
     * The ADTS header written before each AAC packet, or {@code null} for the other codecs.
     */
    private final AdtsHeader adts;

    /**
     * The samples of a TrueHD access unit.
     */
    private final int matUnitSamples;

    /**
     * The bursts of the last {@link #prepare(Packet)} or {@link #flush()}, ready for {@link #write(MediaSink)}.
     */
    private byte[] out = new byte[0];

    /**
     * The number of bytes of {@link #out} to write.
     */
    private int outLength;

    /**
     * The bitstream bytes of the payload being assembled: an E-AC-3 group, an AAC frame or a DTS-HD payload.
     */
    private byte[] payload = new byte[0];

    /**
     * The number of bytes of the E-AC-3 group being assembled.
     */
    private int groupLength;

    /**
     * The audio blocks of the E-AC-3 group being assembled.
     */
    private int groupBlocks;

    /**
     * Whether a packet of the E-AC-3 group or MAT frame being assembled is flagged corrupt.
     */
    private boolean pendingCorrupt;

    /**
     * Whether the first packet was seen, which fixes the DTS form.
     */
    private boolean started;

    /**
     * The samples of each DTS frame, fixed by the first packet.
     */
    private int dtsSamples;

    /**
     * Whether DTS is written as DTS-HD (type IV) bursts.
     */
    private boolean dtsHd;

    /**
     * Whether DTS core frames are written without bursts, because they fill their repetition period.
     */
    private boolean dtsRaw;

    /**
     * The MAT payload being assembled, in bitstream byte order.
     */
    private final byte[] mat;

    /**
     * The offset in {@link #mat} where the next TrueHD byte goes.
     */
    private int matPosition;

    /**
     * Whether {@link #mat} holds access unit bytes not yet written.
     */
    private boolean matPending;

    /**
     * The number of TrueHD access units placed, which selects the slot of the next.
     */
    private long matUnits;

    /**
     * The number of MAT frames emitted.
     */
    private long matFrames;

    /**
     * The coded bytes of the packets written.
     */
    private long codedBytes;

    /**
     * The samples per channel of the packets written.
     */
    private long samples;

    /**
     * Constructs a writer for a track.
     *
     * @param track the track, of a codec {@link #supports(Codec)} accepts, with its sampling rate and layout
     * @param extensible whether the format is a {@code WAVEFORMATEXTENSIBLE_IEC61937} structure rather than PCM
     * @throws IllegalArgumentException if the track's configuration or sampling rate cannot be carried in bursts
     */
    Iec61937Writer(AudioTrack track, boolean extensible) {
        this.codec = track.codec();
        this.sampleRate = track.sampleRate();
        var layout = track.layout().orElseThrow();
        this.codedChannels = layout.channels();
        this.codedMask = WaveFormat.channelMask(layout);
        this.extensible = extensible;
        var lowSampleRate = false;
        AdtsHeader adts = null;
        var unitSamples = 0;
        switch (codec) {
            case MP2, MP3 -> lowSampleRate = switch (sampleRate) {
                case 32_000, 44_100, 48_000 -> false;
                case 16_000, 22_050, 24_000 -> true;
                default -> throw new IllegalArgumentException("IEC 61937 carries no MPEG audio at " + sampleRate + " Hz");
            };
            case AAC -> {
                var config = track.codecConfig().orElseThrow(
                        () -> new IllegalArgumentException("an AAC track needs its AudioSpecificConfig"));
                AudioConfigs.AacConfig aac;
                try {
                    aac = AudioConfigs.parseAac(config);
                } catch (MediaException.Malformed exception) {
                    throw new IllegalArgumentException("malformed AudioSpecificConfig", exception);
                }
                if (aac.frameLength() != 1024) {
                    throw new IllegalArgumentException("IEC 61937 carries AAC frames of 1024 samples, not "
                            + aac.frameLength());
                }
                adts = AdtsHeader.fromAudioSpecificConfig(config);
            }
            case TRUEHD -> unitSamples = switch (sampleRate) {
                case 44_100, 48_000 -> 40;
                case 88_200, 96_000 -> 80;
                case 176_400, 192_000 -> 160;
                default -> throw new IllegalArgumentException("Dolby TrueHD has no " + sampleRate + " Hz rate");
            };
            default -> {
            }
        }
        this.lowSampleRate = lowSampleRate;
        this.adts = adts;
        this.matUnitSamples = unitSamples;
        this.mat = codec == Codec.TRUEHD ? new byte[MAT_PAYLOAD] : null;
    }

    /**
     * Returns whether IEC 61937 bursts carry a codec.
     *
     * @param codec the codec of a track
     * @return {@code true} for AC-3, E-AC-3, MPEG audio, AAC, DTS and Dolby TrueHD
     */
    static boolean supports(Codec codec) {
        return switch (codec) {
            case AC3, EAC3, MP2, MP3, AAC, DTS, TRUEHD -> true;
            default -> false;
        };
    }

    /**
     * Converts a hexadecimal string to bytes.
     *
     * @param hex the digits, two per byte
     * @return the bytes
     */
    private static byte[] hex(String hex) {
        var bytes = new byte[hex.length() / 2];
        for (var index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(hex, 2 * index, 2 * index + 2, 16);
        }
        return bytes;
    }

    /**
     * {@return whether the burst stream has the 8 channels of the high bit rate formats}
     */
    private boolean highBitRate() {
        return codec == Codec.TRUEHD || codec == Codec.DTS && dtsHd;
    }

    /**
     * {@return the channel count of the burst stream: 8 for DTS-HD and TrueHD, 2 otherwise}
     */
    int channels() {
        return highBitRate() ? 8 : 2;
    }

    /**
     * {@return the size of one sample frame of the burst stream, in bytes}
     */
    int blockAlign() {
        return 2 * channels();
    }

    /**
     * {@return the sampling rate of the burst stream, the rate of its sample frames}
     */
    long rate() {
        return switch (codec) {
            case EAC3 -> 4L * sampleRate;
            case MP2, MP3 -> lowSampleRate ? 2L * sampleRate : sampleRate;
            case AAC -> adts.sampleRate();
            case DTS -> dtsHd ? 4L * sampleRate : sampleRate;
            case TRUEHD -> 4L * sampleRate * 40 / matUnitSamples;
            default -> sampleRate;
        };
    }

    /**
     * Returns the {@code fmt } chunk body.
     *
     * <p>The plain form is {@code WAVE_FORMAT_PCM}, or {@code WAVE_FORMAT_EXTENSIBLE} PCM without a channel mask for 8
     * channels. The extensible form names the IEC 61937 sub-format of the codec and appends the coded sampling rate,
     * channel count and average byte rate of {@code WAVEFORMATEXTENSIBLE_IEC61937}; the byte rate is 0 until the
     * stream is complete. Both forms have the same size at every stage.
     *
     * @param complete whether every packet was written
     * @return the body
     */
    byte[] format(boolean complete) {
        var channels = channels();
        var rate = rate();
        var align = blockAlign();
        WaveFormat format;
        if (extensible) {
            var subFormat = WaveFormat.SubFormat.IEC61937;
            int tag;
            switch (codec) {
                case AC3 -> {
                    tag = WaveFormat.DOLBY_AC3_SPDIF;
                    subFormat = WaveFormat.SubFormat.STANDARD;
                }
                case DTS -> {
                    tag = dtsHd ? 11 : WaveFormat.DTS_SPDIF;
                    subFormat = dtsHd ? WaveFormat.SubFormat.IEC61937 : WaveFormat.SubFormat.STANDARD;
                }
                case EAC3 -> tag = 10;
                case MP2 -> tag = lowSampleRate ? 5 : 3;
                case MP3 -> tag = 4;
                case AAC -> tag = 6;
                case TRUEHD -> tag = 12;
                default -> throw new IllegalStateException("not an IEC 61937 codec: " + codec);
            }
            var average = complete && samples > 0 ? Math.round((double) codedBytes * sampleRate / samples) : 0;
            var extra = new ByteWriter(12);
            extra.u32le(sampleRate);
            extra.u32le(codedChannels);
            extra.u32le(Math.min(average, 0xFFFFFFFFL));
            format = new WaveFormat(tag, channels, rate, rate * align, align, 16, 16, codedMask, subFormat,
                    extra.toByteArray());
        } else {
            format = new WaveFormat(WaveFormat.PCM, channels, rate, rate * align, align, 16, 16, 0,
                    channels > 2 ? WaveFormat.SubFormat.STANDARD : WaveFormat.SubFormat.NONE, new byte[0]);
        }
        var bytes = new ByteWriter(format.size(false));
        format.write(bytes, false);
        return bytes.toByteArray();
    }

    /**
     * Converts a packet to the bursts it completes, which the next {@link #write(MediaSink)} writes.
     *
     * <p>E-AC-3 and TrueHD packets are gathered into groups and MAT frames, so a packet may complete no burst. A
     * rejected packet leaves the stream as it was.
     *
     * @param packet the packet, of the track
     * @return the number of bytes of the bursts, a multiple of the burst stream's sample frame
     * @throws IllegalArgumentException if the packet does not hold whole frames of the codec, mixes forms the stream
     * cannot carry, or does not fit a burst
     */
    int prepare(Packet packet) {
        outLength = 0;
        var data = packet.data();
        var corrupt = packet.flags().contains(PacketFlag.CORRUPT);
        var counted = samples;
        try {
            switch (codec) {
                case AC3 -> prepareAc3(data, corrupt);
                case EAC3 -> prepareEac3(data, corrupt);
                case MP2, MP3 -> prepareMpeg(data, corrupt);
                case AAC -> prepareAac(data, corrupt);
                case DTS -> prepareDts(data, corrupt);
                case TRUEHD -> prepareTrueHd(data, corrupt);
                default -> throw new IllegalStateException("not an IEC 61937 codec: " + codec);
            }
        } catch (IllegalArgumentException exception) {
            samples = counted;
            outLength = 0;
            throw exception;
        }
        started = true;
        codedBytes += data.length;
        return outLength;
    }

    /**
     * Converts what the last packets left pending, the last E-AC-3 group or MAT frame, to its burst, which the next
     * {@link #write(MediaSink)} writes.
     *
     * @return the number of bytes of the burst, 0 when nothing is pending
     */
    int flush() {
        outLength = 0;
        if (codec == Codec.EAC3 && groupLength > 0) {
            emitGroup();
        } else if (codec == Codec.TRUEHD && matPending) {
            emitMat();
        }
        return outLength;
    }

    /**
     * Writes the bursts of the last {@link #prepare(Packet)} or {@link #flush()}.
     *
     * @param sink the destination
     */
    void write(MediaSink sink) {
        if (outLength > 0) {
            sink.write(ByteBuffer.wrap(out, 0, outLength));
        }
    }

    /**
     * Splits an AC-3 packet into sync frames, one burst each.
     *
     * @param data the packet
     * @param corrupt whether the packet is flagged corrupt
     */
    private void prepareAc3(byte[] data, boolean corrupt) {
        var offset = 0;
        while (offset < data.length) {
            var length = Ac3Configs.frameLength(data, offset, data.length - offset);
            if (length <= 0 || length > data.length - offset || (data[offset + 5] & 0xFF) >> 3 > 10) {
                throw new IllegalArgumentException("the packet does not hold whole AC-3 sync frames");
            }
            var info = Iec61937Demuxer.TYPE_AC3 | (data[offset + 5] & 7) << 8;
            burst(info, corrupt, data, offset, length, 8L * length, AC3_PERIOD);
            samples += AC3_PERIOD;
            offset += length;
        }
    }

    /**
     * Adds an E-AC-3 access unit to the group of six audio blocks, emitting the group when it is complete or when the
     * unit would overfill it.
     *
     * @param data the access unit
     * @param corrupt whether the packet is flagged corrupt
     */
    private void prepareEac3(byte[] data, boolean corrupt) {
        var blocks = 0;
        var offset = 0;
        while (offset < data.length) {
            var length = Ac3Configs.frameLength(data, offset, data.length - offset);
            if (length <= 0 || length > data.length - offset || (data[offset + 5] & 0xFF) >> 3 <= 10) {
                throw new IllegalArgumentException("the packet does not hold whole E-AC-3 sync frames");
            }
            if (Ac3Configs.startsAccessUnit(data, offset)) {
                var info = data[offset + 4] & 0xFF;
                blocks += info >> 6 == 3 ? 6 : Ac3Configs.BLOCKS[(info >> 4) & 3];
            }
            offset += length;
        }
        if (blocks == 0) {
            throw new IllegalArgumentException("the packet holds no independent E-AC-3 frame of substream 0");
        }
        var capacity = EAC3_PERIOD * FRAME - PREAMBLE;
        if (data.length > capacity) {
            throw new IllegalArgumentException("an E-AC-3 access unit of " + data.length
                    + " bytes does not fit an IEC 61937 burst of " + capacity);
        }
        if (groupLength > 0 && (groupBlocks + blocks > EAC3_BLOCKS || groupLength + data.length > capacity)) {
            emitGroup();
        }
        if (payload.length < capacity) {
            payload = new byte[capacity];
        }
        System.arraycopy(data, 0, payload, groupLength, data.length);
        groupLength += data.length;
        groupBlocks += blocks;
        pendingCorrupt |= corrupt;
        samples += 256L * blocks;
        if (groupBlocks >= EAC3_BLOCKS) {
            emitGroup();
        }
    }

    /**
     * Emits the burst of the E-AC-3 group.
     */
    private void emitGroup() {
        burst(Iec61937Demuxer.TYPE_EAC3, pendingCorrupt, payload, 0, groupLength, groupLength, EAC3_PERIOD);
        groupLength = 0;
        groupBlocks = 0;
        pendingCorrupt = false;
    }

    /**
     * Splits an MPEG audio packet into frames, one burst each; bytes after the last frame that do not start a
     * compatible frame belong to it.
     *
     * @param data the packet
     * @param corrupt whether the packet is flagged corrupt
     */
    private void prepareMpeg(byte[] data, boolean corrupt) {
        var offset = 0;
        while (offset < data.length) {
            var header = data.length - offset >= MpegAudioHeader.SIZE ? MpegAudioHeader.read(data, offset) : 0;
            if (!MpegAudioHeader.isValid(header) || (MpegAudioHeader.layer(header) == 3) != (codec == Codec.MP3)) {
                throw new IllegalArgumentException("the packet does not hold whole " + codec.displayName() + " frames");
            }
            if (MpegAudioHeader.isFreeFormat(header)) {
                throw new IllegalArgumentException("IEC 61937 carries no free-format MPEG audio");
            }
            if (MpegAudioHeader.sampleRate(header) != sampleRate) {
                throw new IllegalArgumentException("an MPEG audio frame at " + MpegAudioHeader.sampleRate(header)
                        + " Hz in a " + sampleRate + " Hz track");
            }
            var length = MpegAudioHeader.frameBytes(header, 0) + MpegAudioHeader.padding(header);
            if (length > data.length - offset) {
                throw new IllegalArgumentException("the packet cuts an MPEG audio frame short");
            }
            if (data.length - offset - length < MpegAudioHeader.SIZE) {
                length = data.length - offset;
            } else {
                var next = MpegAudioHeader.read(data, offset + length);
                if (!MpegAudioHeader.isValid(next) || !MpegAudioHeader.isCompatible(header, next)) {
                    length = data.length - offset;
                }
            }
            var layer = MpegAudioHeader.layer(header);
            int type;
            if (lowSampleRate) {
                type = layer == 1 ? Iec61937Demuxer.TYPE_MPEG2_LAYER1_LSF : layer == 2
                        ? Iec61937Demuxer.TYPE_MPEG2_LAYER2_LSF : Iec61937Demuxer.TYPE_MPEG2_LAYER3_LSF;
            } else {
                type = layer == 1 ? Iec61937Demuxer.TYPE_MPEG1_LAYER1 : Iec61937Demuxer.TYPE_MPEG1_LAYER23;
            }
            var frameSamples = MpegAudioHeader.samplesPerFrame(header);
            burst(type, corrupt, data, offset, length, 8L * length, lowSampleRate ? 2 * frameSamples : frameSamples);
            samples += frameSamples;
            offset += length;
        }
    }

    /**
     * Writes an AAC raw data block as one ADTS frame in one burst.
     *
     * @param data the raw data block
     * @param corrupt whether the packet is flagged corrupt
     */
    private void prepareAac(byte[] data, boolean corrupt) {
        var length = AdtsHeader.SIZE + data.length;
        if (length > AdtsHeader.MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("an AAC packet of " + data.length + " bytes does not fit an ADTS frame");
        }
        if (payload.length < length) {
            payload = new byte[Math.max(length, 2 * payload.length)];
        }
        adts.write(payload, 0, length);
        System.arraycopy(data, 0, payload, AdtsHeader.SIZE, data.length);
        burst(Iec61937Demuxer.TYPE_AAC, corrupt, payload, 0, length, 8L * length, AAC_PERIOD);
        samples += AAC_PERIOD;
    }

    /**
     * Writes a DTS packet: its core frame in a core burst, the whole packet in a DTS-HD burst, or the core frame
     * without a burst.
     *
     * @param data the packet, a core frame in the 16-bit big-endian form and an optional extension substream
     * @param corrupt whether the packet is flagged corrupt
     */
    private void prepareDts(byte[] data, boolean corrupt) {
        if (data.length < DtsConfigs.HEADER_SIZE || !DtsConfigs.isSync(data, 0)) {
            throw new IllegalArgumentException("the packet does not start with a DTS core frame");
        }
        var core = DtsConfigs.frameSize(data, 0);
        if (core < DtsConfigs.MIN_FRAME_SIZE || core > data.length) {
            throw new IllegalArgumentException("the packet cuts a DTS core frame short");
        }
        var frameSamples = DtsConfigs.samples(data, 0);
        if (!started) {
            if (frameSamples != 512 && frameSamples != 1024 && frameSamples != 2048) {
                throw new IllegalArgumentException("IEC 61937 carries DTS frames of 512, 1024 or 2048 samples, not "
                        + frameSamples);
            }
            dtsSamples = frameSamples;
            dtsHd = data.length - core >= 4 && ByteBuffer.wrap(data, core, 4).getInt() == EXSS_SYNC;
            dtsRaw = !dtsHd && core == frameSamples * FRAME;
        } else if (frameSamples != dtsSamples) {
            throw new IllegalArgumentException("a DTS frame of " + frameSamples + " samples in a stream of "
                    + dtsSamples);
        }
        samples += frameSamples;
        if (dtsHd) {
            var size = data.length;
            var length = (DTS4_HEADER + size + PREAMBLE + 15 & ~15) - PREAMBLE;
            if (size > 0xFFFF) {
                throw new IllegalArgumentException("a DTS-HD frame of " + size + " bytes");
            }
            if (payload.length < length) {
                payload = new byte[Math.max(length, 2 * payload.length)];
            }
            Arrays.fill(payload, 0, DTS4_HEADER, (byte) 0);
            payload[0] = 1;
            payload[8] = (byte) 0xFE;
            payload[9] = (byte) 0xFE;
            payload[10] = (byte) (size >> 8);
            payload[11] = (byte) size;
            System.arraycopy(data, 0, payload, DTS4_HEADER, size);
            Arrays.fill(payload, DTS4_HEADER + size, length, (byte) 0);
            var shift = Integer.numberOfTrailingZeros(16 * frameSamples / 512);
            burst(Iec61937Demuxer.TYPE_DTS4 | shift << 8, corrupt, payload, 0, length, length, 16 * frameSamples);
        } else if (dtsRaw) {
            if (core != frameSamples * FRAME) {
                throw new IllegalArgumentException("a DTS core frame of " + core + " bytes in a stream of frames that"
                        + " fill their repetition period");
            }
            reserve(core);
            swapInto(data, 0, core, outLength);
            outLength += core;
        } else {
            var type = switch (frameSamples) {
                case 512 -> Iec61937Demuxer.TYPE_DTS1;
                case 1024 -> Iec61937Demuxer.TYPE_DTS2;
                default -> Iec61937Demuxer.TYPE_DTS3;
            };
            burst(type, corrupt, data, 0, core, 8L * core, frameSamples);
        }
    }

    /**
     * Places a TrueHD access unit in the MAT frames, emitting each frame it completes.
     *
     * @param data the access unit
     * @param corrupt whether the packet is flagged corrupt
     */
    private void prepareTrueHd(byte[] data, boolean corrupt) {
        if (data.length < 4 || TrueHdConfigs.unitLength(data, 0) != data.length) {
            throw new IllegalArgumentException("the packet is not one TrueHD access unit");
        }
        var slot = (int) (matUnits % MAT_UNITS);
        var frame = matUnits / MAT_UNITS;
        if (frame > matFrames) {
            emitMat();
        }
        if (frame == matFrames) {
            matPosition = Math.max(matPosition, Math.max(MAT_START_CODE.length, slot * MAT_SLOT));
        }
        matPosition = skipCodes(matPosition);
        var written = 0;
        while (written < data.length) {
            if (matPosition == MAT_END) {
                emitMat();
                matPosition = MAT_START_CODE.length;
            }
            var limit = matPosition < MAT_MIDDLE ? MAT_MIDDLE : MAT_END;
            var count = Math.min(data.length - written, limit - matPosition);
            System.arraycopy(data, written, mat, matPosition, count);
            matPending = true;
            pendingCorrupt |= corrupt;
            written += count;
            matPosition = skipCodes(matPosition + count);
        }
        matUnits++;
        samples += matUnitSamples;
    }

    /**
     * Moves a MAT payload offset past the middle code it falls in.
     *
     * @param position the offset
     * @return the offset, after the middle code when it falls in it
     */
    private static int skipCodes(int position) {
        return position >= MAT_MIDDLE && position < MAT_MIDDLE + MAT_MIDDLE_CODE.length
                ? MAT_MIDDLE + MAT_MIDDLE_CODE.length : position;
    }

    /**
     * Emits the burst of the MAT frame being assembled, with its codes, and starts the next frame.
     */
    private void emitMat() {
        System.arraycopy(MAT_START_CODE, 0, mat, 0, MAT_START_CODE.length);
        System.arraycopy(MAT_MIDDLE_CODE, 0, mat, MAT_MIDDLE, MAT_MIDDLE_CODE.length);
        System.arraycopy(MAT_END_CODE, 0, mat, MAT_END, MAT_END_CODE.length);
        burst(Iec61937Demuxer.TYPE_MAT, pendingCorrupt, mat, 0, MAT_PAYLOAD, MAT_PAYLOAD, MAT_PERIOD);
        Arrays.fill(mat, (byte) 0);
        matPosition = 0;
        matPending = false;
        pendingCorrupt = false;
        matFrames++;
    }

    /**
     * Appends one burst to {@link #out}.
     *
     * @param info the {@code Pc} word without the error flag
     * @param corrupt whether the error flag is set
     * @param data the bytes holding the payload, in bitstream byte order
     * @param offset the offset of the payload
     * @param length the payload length, in bytes
     * @param lengthCode the {@code Pd} word
     * @param period the repetition period, in frames
     * @throws IllegalArgumentException if the payload does not fit the repetition period
     */
    private void burst(int info, boolean corrupt, byte[] data, int offset, int length, long lengthCode, int period) {
        var size = period * FRAME;
        if (PREAMBLE + length > size) {
            throw new IllegalArgumentException("a payload of " + length + " bytes does not fit an IEC 61937 burst of "
                    + size);
        }
        reserve(size);
        var at = outLength;
        word(at, 0xF872);
        word(at + 2, 0x4E1F);
        word(at + 4, info | (corrupt ? 0x80 : 0));
        word(at + 6, (int) lengthCode);
        swapInto(data, offset, length, at + PREAMBLE);
        Arrays.fill(out, at + PREAMBLE + (length + 1 & ~1), at + size, (byte) 0);
        outLength += size;
    }

    /**
     * Grows {@link #out} so that it holds {@code bytes} more after {@link #outLength}.
     *
     * @param bytes the room needed
     */
    private void reserve(int bytes) {
        if (out.length - outLength < bytes) {
            out = Arrays.copyOf(out, Math.max(outLength + bytes, 2 * out.length));
        }
    }

    /**
     * Stores a 16-bit word little-endian in {@link #out}.
     *
     * @param at the offset
     * @param value the word
     */
    private void word(int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
    }

    /**
     * Copies bitstream bytes into {@link #out} as little-endian 16-bit words, a last odd byte padded with zero.
     *
     * @param data the bytes
     * @param offset the offset of the first byte
     * @param length the number of bytes
     * @param at the offset in {@link #out}
     */
    private void swapInto(byte[] data, int offset, int length, int at) {
        var pairs = length & ~1;
        for (var index = 0; index < pairs; index += 2) {
            out[at + index] = data[offset + index + 1];
            out[at + index + 1] = data[offset + index];
        }
        if ((length & 1) != 0) {
            out[at + pairs] = 0;
            out[at + pairs + 1] = data[offset + pairs];
        }
    }
}
