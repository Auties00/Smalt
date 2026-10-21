package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.container.internal.AacRawBlocks;
import com.github.auties00.smalt.container.internal.Latm;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.DtsConfigs;
import com.github.auties00.smalt.internal.codec.TrueHdConfigs;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
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

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the data bursts of an IEC 61937 stream (IEC 61937-1 to -5, -9 and -11) carried in the 16-bit stereo samples
 * of a WAVE {@code data} chunk: AC-3, E-AC-3, MPEG-1 and MPEG-2 audio, MPEG-2 AAC in ADTS framing, MPEG-4 AAC in LOAS
 * framing, DTS (types I to IV, the last carrying DTS-HD frames) and Dolby TrueHD in MAT frames.
 *
 * <p>A burst starts on a 16-bit boundary with the preamble words {@code Pa = 0xF872} and {@code Pb = 0x4E1F}, the
 * burst information {@code Pc} (the data type in bits 0 to 4, the error flag in bit 7, data-type-dependent
 * information in bits 5, 6 and 8 to 12, the bitstream number in bits 13 to 15) and the length code {@code Pd} (the
 * payload length in bytes for E-AC-3, DTS type IV and MAT, in bits for the other data types). The words are stored
 * in the byte order of the samples, little-endian in RIFF and big-endian in RIFX, and either order is accepted. The
 * stream follows the codec family and bitstream number of its first burst of a supported data type; null, pause and
 * other bursts are skipped, as are the bytes between bursts.
 *
 * <table class="striped">
 * <caption>Data types</caption>
 * <thead><tr><th scope="col">Data type</th><th scope="col">Payload</th><th scope="col">Packets</th>
 * <th scope="col">Repetition period, in frames</th></tr></thead>
 * <tbody>
 * <tr><td>1 (AC-3), 21 (E-AC-3)</td><td>sync frames</td><td>one per access unit (an AC-3 frame, or an independent
 * E-AC-3 frame with the dependent frames that follow it)</td><td>1536, 6144</td></tr>
 * <tr><td>4, 5, 6, 8, 9, 10 (MPEG audio)</td><td>MPEG audio frames</td><td>one per frame; the MPEG-2 extension
 * bitstream that follows the base frame in data type 6 stays with it</td><td>384, 1152, 1152, 768, 2304, 1152</td></tr>
 * <tr><td>7, 19 (MPEG-2 AAC)</td><td>ADTS frames</td><td>one per frame's raw data blocks, as the ADTS demuxer
 * splits them</td><td>the frame's samples</td></tr>
 * <tr><td>20 (MPEG-4 AAC)</td><td>LOAS frames</td><td>one per subframe, a raw data block of 1024 samples; the
 * track takes the first {@code StreamMuxConfig}</td><td>the frame's samples</td></tr>
 * <tr><td>11, 12, 13 (DTS types I to III)</td><td>DTS core frames, in the 16-bit or the 14-bit form</td><td>one
 * per frame, in the 16-bit form</td><td>512, 1024, 2048</td></tr>
 * <tr><td>17 (DTS type IV)</td><td>a 12-byte header, then a DTS-HD frame (core and extension substream)</td>
 * <td>one per frame</td><td>512 shifted left by {@code Pc} bits 8 to 10</td></tr>
 * <tr><td>22 (MAT)</td><td>MAT frames carrying TrueHD access units</td><td>one per access unit</td><td>15360</td></tr>
 * </tbody>
 * </table>
 *
 * <p>Timestamps are in samples of the coded stream. The first packet of a burst is stamped with the burst's position
 * on the IEC 61937 timeline, a repetition period lasting the samples one burst carries (for example 1536 for AC-3,
 * 576 for an MPEG-2 Layer III frame at half rate, and 24 TrueHD access units for MAT); the packets after it follow at
 * their durations. TrueHD access units run across MAT frames, so they are stamped continuously from the first one
 * with a major sync, and those before it are skipped; only units with a major sync are key frames. A burst whose
 * error flag is set yields packets flagged {@link PacketFlag#CORRUPT}, and a burst cut short by the end of the data
 * is dropped. The track's duration is the length of the data on the timeline.
 *
 * <p>Instances are not thread-safe.
 */
final class Iec61937Demuxer implements Demuxer {
    /**
     * The data type of AC-3 bursts.
     */
    static final int TYPE_AC3 = 1;

    /**
     * The data type of MPEG-1 Layer I bursts.
     */
    static final int TYPE_MPEG1_LAYER1 = 4;

    /**
     * The data type of MPEG-1 Layer II and III bursts and MPEG-2 bursts without extension.
     */
    static final int TYPE_MPEG1_LAYER23 = 5;

    /**
     * The data type of MPEG-2 bursts with extension.
     */
    static final int TYPE_MPEG2_EXTENSION = 6;

    /**
     * The data type of MPEG-2 AAC bursts of 1024 samples.
     */
    static final int TYPE_AAC = 7;

    /**
     * The data type of MPEG-2 Layer I bursts at half the sampling rate.
     */
    static final int TYPE_MPEG2_LAYER1_LSF = 8;

    /**
     * The data type of MPEG-2 Layer II bursts at half the sampling rate.
     */
    static final int TYPE_MPEG2_LAYER2_LSF = 9;

    /**
     * The data type of MPEG-2 Layer III bursts at half the sampling rate.
     */
    static final int TYPE_MPEG2_LAYER3_LSF = 10;

    /**
     * The data type of DTS type I bursts (512 samples).
     */
    static final int TYPE_DTS1 = 11;

    /**
     * The data type of DTS type II bursts (1024 samples).
     */
    static final int TYPE_DTS2 = 12;

    /**
     * The data type of DTS type III bursts (2048 samples).
     */
    static final int TYPE_DTS3 = 13;

    /**
     * The data type of DTS type IV bursts, which carry DTS-HD frames.
     */
    static final int TYPE_DTS4 = 17;

    /**
     * The data type of MPEG-2 AAC bursts of 2048 or 4096 samples.
     */
    static final int TYPE_AAC_LONG = 19;

    /**
     * The data type of MPEG-4 AAC bursts in LOAS framing (IEC 61937-11).
     */
    static final int TYPE_MPEG4_AAC = 20;

    /**
     * The data type of E-AC-3 bursts.
     */
    static final int TYPE_EAC3 = 21;

    /**
     * The data type of MAT bursts, which carry Dolby TrueHD.
     */
    static final int TYPE_MAT = 22;

    /**
     * The size of a burst preamble ({@code Pa}, {@code Pb}, {@code Pc}, {@code Pd}), in bytes.
     */
    private static final int PREAMBLE = 8;

    /**
     * The number of payload bytes the search for the first burst checks.
     */
    private static final int PROBE = 16;

    /**
     * The size of one IEC 61937 frame, a 16-bit sample of both channels, in bytes.
     */
    private static final int FRAME = 4;

    /**
     * The size of the window the data is read through, in bytes.
     */
    private static final int WINDOW = 128 * 1024;

    /**
     * The size of the blocks the search for the next preamble reads, in bytes.
     */
    private static final int SCAN_BLOCK = 4096;

    /**
     * The number of stored bytes that hold the header of a DTS frame in the 14-bit form.
     */
    private static final int PACKED_DTS_HEADER = 14;

    /**
     * The size of the header of a DTS type IV payload.
     */
    private static final int DTS4_HEADER = 12;

    /**
     * The number of TrueHD access units of a MAT frame.
     */
    private static final int MAT_UNITS = 24;

    /**
     * The repetition period of MAT bursts, in frames.
     */
    private static final int MAT_PERIOD = 15360;

    /**
     * The offset and size of the MAT start code, middle code and end code in a MAT payload of
     * {@value #MAT_PAYLOAD} bytes.
     */
    private static final int MAT_START = 20;
    private static final int MAT_MIDDLE = 30708;
    private static final int MAT_MIDDLE_SIZE = 12;
    private static final int MAT_END_SIZE = 16;
    private static final int MAT_PAYLOAD = 61424;

    /**
     * The largest number of samples of an AAC burst, four raw data blocks.
     */
    private static final int MAX_AAC_SAMPLES = 4096;

    /**
     * The largest number of access units between two TrueHD major syncs a seek looks back over.
     */
    private static final int MAT_SEEK_UNITS = 128;

    /**
     * The flags of an intact access unit that is a key frame.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * The flags of an access unit of a burst whose error flag is set.
     */
    private static final Set<PacketFlag> KEY_CORRUPT = Set.of(PacketFlag.KEY, PacketFlag.CORRUPT);

    /**
     * The flags of an intact access unit that is not a key frame.
     */
    private static final Set<PacketFlag> DELTA = Set.of();

    /**
     * The flags of an access unit that is not a key frame, of a burst whose error flag is set.
     */
    private static final Set<PacketFlag> DELTA_CORRUPT = Set.of(PacketFlag.CORRUPT);

    /**
     * The codec families of the supported data types.
     */
    enum Family {
        /**
         * AC-3 and E-AC-3.
         */
        AC3,

        /**
         * MPEG-1 and MPEG-2 audio.
         */
        MPEG,

        /**
         * MPEG-2 AAC in ADTS framing.
         */
        AAC,

        /**
         * MPEG-4 AAC in LOAS framing.
         */
        LOAS,

        /**
         * DTS types I to III.
         */
        DTS,

        /**
         * DTS type IV.
         */
        DTS_HD,

        /**
         * MAT carrying Dolby TrueHD.
         */
        MAT
    }

    /**
     * Holds where the stream starts and how its bursts are stored.
     *
     * @param offset the source offset of the first burst's preamble
     * @param littleEndian whether the 16-bit words are stored little-endian
     * @param burstInfo the {@code Pc} word of the first burst
     */
    record Start(long offset, boolean littleEndian, int burstInfo) {
        /**
         * {@return the data type of the first burst}
         */
        int type() {
            return burstInfo & 0x1F;
        }

        /**
         * {@return the bitstream number of the first burst, from 0 to 7}
         */
        int bitstream() {
            return burstInfo >>> 13;
        }
    }

    private final MediaSource source;
    private final MediaLimits limits;

    /**
     * The offset of the first sample byte.
     */
    private final long dataStart;

    /**
     * The offset of the end of the samples, or {@link Long#MAX_VALUE} when unknown.
     */
    private final long dataEnd;

    /**
     * The window over the samples.
     */
    private final ReadAheadCache cache;

    /**
     * Whether the 16-bit words are stored little-endian.
     */
    private final boolean littleEndian;

    /**
     * The codec family followed.
     */
    private final Family family;

    /**
     * The bitstream number followed.
     */
    private final int bitstream;

    /**
     * The first burst.
     */
    private final Start start;

    /**
     * The repetition period of the first burst, in frames.
     */
    private final long periodFrames;

    /**
     * The number of samples a repetition period carries, or 0 while the first bursts are read to describe the
     * stream.
     */
    private long periodSamples;

    /**
     * The number of samples of a TrueHD access unit, or 0 before the first major sync.
     */
    private int matUnitSamples;

    /**
     * The description of a MAT stream, once its first major sync is read.
     */
    private Description matDescription;

    /**
     * The header of the first ADTS frame, kept while describing an AAC stream.
     */
    private AdtsHeader adtsHeader;

    /**
     * The single audio track.
     */
    private final AudioTrack track;

    /**
     * The track list exposed by {@link #tracks()}.
     */
    private final List<Track> tracks;

    /**
     * The bytes of one search block.
     */
    private final byte[] scan = new byte[SCAN_BLOCK];

    /**
     * The preamble of the burst being read.
     */
    private final byte[] preamble = new byte[PREAMBLE];

    /**
     * The payload of the current burst, in bitstream byte order.
     */
    private byte[] payload = new byte[0];

    /**
     * The bytes the access units of the current burst are taken from: {@link #payload}, or {@link #mat} for MAT.
     */
    private byte[] units = payload;

    /**
     * The TrueHD data of the MAT frames read so far that no complete access unit took yet, followed by the data of
     * the current burst.
     */
    private byte[] mat = new byte[0];

    /**
     * The offset in {@link #mat} of the data carried from the previous bursts.
     */
    private int matCarryStart;

    /**
     * The number of bytes of {@link #mat} carried from the previous bursts.
     */
    private int matCarry;

    /**
     * Whether the carried TrueHD data came from a burst whose error flag is set.
     */
    private boolean matCarryCorrupt;

    /**
     * Whether TrueHD access units are skipped until one with a major sync.
     */
    private boolean matResync = true;

    /**
     * The timestamp of the next TrueHD access unit.
     */
    private long matTime;

    /**
     * The input timing of the first TrueHD major sync unit, or {@code -1} before it.
     */
    private int matTimingOrigin = -1;

    /**
     * The timestamp of the first TrueHD major sync unit.
     */
    private long matTimeOrigin;

    /**
     * The header of the DTS frame being split, in the 16-bit form.
     */
    private final byte[] dtsHeader = new byte[DtsConfigs.HEADER_SIZE + 1];

    /**
     * The DTS frames of the current burst converted from the 14-bit form, or the raw data blocks of its LOAS frames.
     */
    private byte[] unpacked = new byte[0];

    /**
     * The reader of the LOAS frame being split.
     */
    private final MsbBitReader loasReader = new MsbBitReader(new byte[0], 0, 0);

    /**
     * The first {@code StreamMuxConfig} of an MPEG-4 AAC stream, once read.
     */
    private Latm.Config loasConfig;

    /**
     * The offsets of the access units of the current burst in {@link #units}.
     */
    private int[] unitStarts = new int[8];

    /**
     * The offsets of the ends of the access units of the current burst in {@link #units}.
     */
    private int[] unitEnds = new int[8];

    /**
     * The timestamps of the access units of the current burst.
     */
    private long[] unitTimes = new long[8];

    /**
     * The durations of the access units of the current burst, in samples.
     */
    private int[] unitSamples = new int[8];

    /**
     * Whether each access unit of the current burst is a key frame.
     */
    private boolean[] unitKeys = new boolean[8];

    /**
     * Whether each access unit of the current burst comes from a burst whose error flag is set.
     */
    private boolean[] unitCorrupt = new boolean[8];

    /**
     * The number of access units of the current burst.
     */
    private int unitCount;

    /**
     * The index of the next access unit of the current burst to return.
     */
    private int unitIndex;

    /**
     * The offset from which the next preamble is searched.
     */
    private long searchFrom;

    /**
     * The offset where the next burst is expected, one repetition period after the last, or {@code -1}.
     */
    private long expected;

    /**
     * The sample before which access units are dropped after a seek.
     */
    private long skipBefore;

    /**
     * Whether a seek waits for the last TrueHD key frame at or before {@link #skipBefore}.
     */
    private boolean holding;

    /**
     * The packets from the last TrueHD key frame at or before the seek target, while {@link #holding}, and those
     * still to return after it.
     */
    private final ArrayDeque<Packet> held = new ArrayDeque<>();

    /**
     * The {@code Pc} word of the burst being split.
     */
    private int lastBurstInfo;

    /**
     * Whether the end of the bursts was reached.
     */
    private boolean finished;

    /**
     * Constructs a demuxer over the bursts of a data chunk.
     *
     * @param source the input, not closed by this demuxer
     * @param dataStart the offset of the first sample byte
     * @param dataEnd the offset of the end of the samples, or {@code -1} when unknown
     * @param limits the resource bounds
     * @param start the first burst, as {@link #locate} found it
     * @throws MediaException.Malformed if the first bursts do not carry a valid access unit
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     */
    Iec61937Demuxer(MediaSource source, long dataStart, long dataEnd, MediaLimits limits, Start start) {
        this.source = source;
        this.limits = limits;
        this.dataStart = dataStart;
        this.dataEnd = dataEnd < 0 ? Long.MAX_VALUE : dataEnd;
        this.cache = new ReadAheadCache(source, WINDOW, PREAMBLE, this.dataEnd);
        this.littleEndian = start.littleEndian();
        this.family = family(start.type());
        this.bitstream = start.bitstream();
        this.start = start;
        this.searchFrom = start.offset();
        this.expected = -1;
        this.periodFrames = family == Family.AAC || family == Family.LOAS ? 1 : period(start.burstInfo(), 0);
        var description = describe();
        this.periodSamples = description.periodSamples;
        if (description.layout != null) {
            LimitChecks.checkChannels(limits, description.layout.channels());
        }
        restart(start.offset());
        var frames = dataEnd < 0 ? -1 : (dataEnd - dataStart) / FRAME * periodSamples / periodFrames;
        var builder = AudioTrack.builder()
                .codec(description.codec)
                .timeBase(Rational.timeBaseOf(description.sampleRate))
                .duration(frames)
                .sampleRate(description.sampleRate)
                .layout(description.layout);
        if (description.config != null) {
            builder.codecConfig(description.config);
        }
        this.track = description.refine(builder.build());
        this.tracks = List.of(track);
    }

    /**
     * Describes the stream from its first access unit.
     *
     * @param codec the coding format of the track
     * @param sampleRate the sampling rate
     * @param layout the channel layout
     * @param config the codec configuration, or {@code null}
     * @param periodSamples the number of samples a repetition period carries
     * @param firstUnit the first access unit, for refining an AAC description, or {@code null}
     */
    private record Description(Codec codec, int sampleRate, ChannelLayout layout, byte[] config, long periodSamples,
                               byte[] firstUnit) {
        /**
         * Refines an AAC track for the extensions its first access unit may carry.
         *
         * @param track the track as described
         * @return the refined track
         */
        AudioTrack refine(AudioTrack track) {
            return codec == Codec.AAC && AacExtensions.mayRefine(track) ? AacExtensions.refine(track, firstUnit) : track;
        }
    }

    /**
     * Reads the first bursts and describes the stream.
     *
     * @return the description
     * @throws MediaException.Malformed if the first bursts do not carry a valid access unit
     */
    private Description describe() {
        var invalid = "IEC 61937 burst without a valid " + family.name().replace('_', '-') + " access unit";
        if (family == Family.MAT) {
            for (var burst = 0; burst < 64 && matDescription == null; burst++) {
                if (!nextBurst()) {
                    break;
                }
            }
            if (matDescription == null) {
                throw new MediaException.Malformed(invalid);
            }
            return matDescription;
        }
        if (!nextBurst() || unitCount == 0) {
            throw new MediaException.Malformed(invalid);
        }
        var data = units;
        var offset = unitStarts[0];
        var length = unitEnds[0] - offset;
        return switch (family) {
            case AC3 -> {
                var stream = Ac3Configs.describe(data, offset, length);
                if (stream == null || stream.sampleRate() <= 0) {
                    throw new MediaException.Malformed(invalid);
                }
                yield new Description(stream.codec(), stream.sampleRate(), stream.layout(), null, 1536, null);
            }
            case MPEG -> {
                var header = MpegAudioHeader.read(data, offset);
                var layout = MpegAudioHeader.isMono(header) ? ChannelLayout.MONO : ChannelLayout.STEREO;
                yield new Description(MpegAudioHeader.layer(header) == 3 ? Codec.MP3 : Codec.MP2,
                        MpegAudioHeader.sampleRate(header), layout, null, MpegAudioHeader.samplesPerFrame(header), null);
            }
            case AAC -> {
                var header = adtsHeader;
                var config = header.channelConfiguration() == 0 ? programConfig(header, data, offset, length)
                        : header.audioSpecificConfig();
                var layout = AudioConfigs.aacLayout(header.channelConfiguration());
                yield new Description(Codec.AAC, header.sampleRate(), layout,
                        config, 1, Arrays.copyOfRange(data, offset, offset + length));
            }
            case LOAS -> {
                var config = loasConfig;
                var layout = AudioConfigs.aacLayout(config.channels());
                yield new Description(Codec.AAC, config.coreRate(), layout, config.audioSpecificConfig(), 1,
                        Arrays.copyOfRange(data, offset, offset + length));
            }
            case DTS, DTS_HD -> {
                var rate = DtsConfigs.sampleRate(data, offset);
                var layout = DtsConfigs.layout(data, offset);
                if (rate <= 0 || layout == null) {
                    throw new MediaException.Malformed(invalid);
                }
                yield new Description(Codec.DTS, rate, layout, null, DtsConfigs.samples(data, offset), null);
            }
            case MAT -> throw new AssertionError();
        };
    }

    /**
     * Builds the {@code AudioSpecificConfig} of an ADTS stream of channel configuration 0 from the program
     * configuration element that opens its first raw data block.
     *
     * @param header the ADTS header
     * @param data the bytes holding the block
     * @param offset the offset of the block
     * @param length the length of the block
     * @return the configuration, of channel configuration 0 without a program configuration when the block does not
     * open with one
     */
    private static byte[] programConfig(AdtsHeader header, byte[] data, int offset, int length) {
        var writer = new MsbBitWriter(64);
        writer.write(header.profile() + 1, 5);
        writer.write(header.frequencyIndex(), 4);
        writer.write(0, 4);
        writer.write(0, 3);
        var reader = new MsbBitReader(data, offset, length);
        if (reader.read(3) == 5) {
            writer.write(reader.read(10), 10);
            AacRawBlocks.copyProgramConfig(reader, writer);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /**
     * Returns the codec family of a data type.
     *
     * @param type the data type
     * @return the family, or {@code null} for a data type this demuxer does not read
     */
    static Family family(int type) {
        return switch (type) {
            case TYPE_AC3, TYPE_EAC3 -> Family.AC3;
            case TYPE_MPEG1_LAYER1, TYPE_MPEG1_LAYER23, TYPE_MPEG2_EXTENSION, TYPE_MPEG2_LAYER1_LSF,
                 TYPE_MPEG2_LAYER2_LSF, TYPE_MPEG2_LAYER3_LSF -> Family.MPEG;
            case TYPE_AAC, TYPE_AAC_LONG -> Family.AAC;
            case TYPE_MPEG4_AAC -> Family.LOAS;
            case TYPE_DTS1, TYPE_DTS2, TYPE_DTS3 -> Family.DTS;
            case TYPE_DTS4 -> Family.DTS_HD;
            case TYPE_MAT -> Family.MAT;
            default -> null;
        };
    }

    /**
     * Returns the repetition period of a burst, in frames.
     *
     * @param burstInfo the {@code Pc} word
     * @param samples the number of samples of the burst's payload, used for the AAC data types
     * @return the period
     */
    private static int period(int burstInfo, int samples) {
        return switch (burstInfo & 0x1F) {
            case TYPE_AC3 -> 1536;
            case TYPE_EAC3 -> 6144;
            case TYPE_MPEG1_LAYER1 -> 384;
            case TYPE_MPEG2_LAYER1_LSF -> 768;
            case TYPE_MPEG2_LAYER2_LSF -> 2304;
            case TYPE_MPEG1_LAYER23, TYPE_MPEG2_EXTENSION, TYPE_MPEG2_LAYER3_LSF -> 1152;
            case TYPE_DTS1 -> 512;
            case TYPE_DTS2 -> 1024;
            case TYPE_DTS3 -> 2048;
            case TYPE_DTS4 -> 512 << (burstInfo >>> 8 & 7);
            case TYPE_MAT -> MAT_PERIOD;
            default -> samples;
        };
    }

    /**
     * Returns the payload length of a burst.
     *
     * @param type the data type
     * @param lengthCode the {@code Pd} word
     * @return the length in bytes
     */
    private static int payloadLength(int type, int lengthCode) {
        return type == TYPE_EAC3 || type == TYPE_DTS4 || type == TYPE_MAT ? lengthCode : (lengthCode + 7) >>> 3;
    }

    /**
     * Searches the start of a data chunk for a burst of a supported data type whose payload starts with a valid
     * access unit.
     *
     * @param source the input
     * @param dataStart the offset of the first sample byte
     * @param dataEnd the offset of the end of the samples, or {@code -1} when unknown
     * @param searchBytes the number of bytes from {@code dataStart} in which the preamble may start
     * @return the first burst, or {@code null} when none starts in the searched range
     */
    static Start locate(MediaSource source, long dataStart, long dataEnd, long searchBytes) {
        var end = dataEnd < 0 ? Long.MAX_VALUE : dataEnd;
        var cache = new ReadAheadCache(source, WINDOW, PREAMBLE, end);
        var limit = dataStart + searchBytes > end - PREAMBLE ? end - PREAMBLE : dataStart + searchBytes;
        var block = new byte[SCAN_BLOCK + PREAMBLE + PROBE];
        var probe = new byte[PROBE];
        for (var position = dataStart; position <= limit; position += SCAN_BLOCK) {
            var read = cache.readUpTo(position, block, 0, block.length);
            var last = (int) Math.min(Math.min(read - PREAMBLE, SCAN_BLOCK - 2), limit - position);
            for (var index = 0; index <= last; index += 2) {
                boolean little;
                if ((block[index] & 0xFF) == 0x72 && (block[index + 1] & 0xFF) == 0xF8 && block[index + 2] == 0x1F
                        && block[index + 3] == 0x4E) {
                    little = true;
                } else if ((block[index] & 0xFF) == 0xF8 && block[index + 1] == 0x72 && block[index + 2] == 0x4E
                        && block[index + 3] == 0x1F) {
                    little = false;
                } else {
                    continue;
                }
                var pc = word(block, index + 4, little);
                var burstType = pc & 0x1F;
                var length = payloadLength(burstType, word(block, index + 6, little));
                var burstFamily = family(burstType);
                if (burstFamily == null || index + PREAMBLE + PROBE > read) {
                    continue;
                }
                System.arraycopy(block, index + PREAMBLE, probe, 0, PROBE);
                if (little) {
                    swap(probe, PROBE);
                }
                if (startsUnit(burstFamily, burstType, probe, length)) {
                    return new Start(position + index, little, pc);
                }
            }
            if (read < block.length) {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns whether a payload starts with a valid access unit of its family.
     *
     * @param family the codec family of the burst
     * @param type the data type
     * @param probe the first {@value #PROBE} payload bytes, in bitstream byte order
     * @param length the payload length
     * @return {@code true} when the payload starts with an access unit that fits it
     */
    private static boolean startsUnit(Family family, int type, byte[] probe, int length) {
        return switch (family) {
            case AC3 -> {
                var frame = Ac3Configs.frameLength(probe, 0, probe.length);
                yield length >= 6 && frame > 0 && frame <= length && Ac3Configs.startsAccessUnit(probe, 0)
                        && (type == TYPE_EAC3) == ((probe[5] & 0xFF) >> 3 > 10);
            }
            case MPEG -> {
                var header = MpegAudioHeader.read(probe, 0);
                yield MpegAudioHeader.isValid(header) && !MpegAudioHeader.isFreeFormat(header)
                        && MpegAudioHeader.frameBytes(header, 0) + MpegAudioHeader.padding(header) <= length;
            }
            case AAC -> {
                var header = AdtsHeader.parse(probe, 0);
                yield header != null && header.frameLength() <= length;
            }
            case LOAS -> {
                var frame = Latm.frameLength(probe, 0, probe.length);
                yield frame > 0 && frame <= length;
            }
            case DTS -> length >= DtsConfigs.HEADER_SIZE && DtsConfigs.isSync(probe, 0)
                    && DtsConfigs.frameSize(probe, 0) <= length || length >= PACKED_DTS_HEADER && packedFits(probe, length);
            case DTS_HD -> length >= DTS4_HEADER + 4 && probe[8] == (byte) 0xFE && probe[9] == (byte) 0xFE
                    && DtsConfigs.isSync(probe, DTS4_HEADER)
                    && ((probe[10] & 0xFF) << 8 | probe[11] & 0xFF) <= length - DTS4_HEADER;
            case MAT -> length == MAT_PAYLOAD && probe[0] == 0x07 && probe[1] == (byte) 0x9E && probe[2] == 0x00
                    && probe[3] == 0x03;
        };
    }

    /**
     * Returns whether a payload starts with a DTS frame in the 14-bit form that fits it.
     *
     * @param probe the first {@value #PROBE} payload bytes, in bitstream byte order
     * @param length the payload length
     * @return {@code true} when the frame fits
     */
    private static boolean packedFits(byte[] probe, int length) {
        if (!DtsConfigs.isPackedSync(probe, 0)) {
            return false;
        }
        var header = new byte[DtsConfigs.HEADER_SIZE + 1];
        DtsConfigs.unpack(probe, 0, header, 0, header.length);
        return DtsConfigs.packedSize(DtsConfigs.frameSize(header, 0)) <= length;
    }

    /**
     * Returns a 16-bit word.
     *
     * @param data the bytes
     * @param offset the index of the word
     * @param little whether the word is little-endian
     * @return the word, from 0 to 65535
     */
    private static int word(byte[] data, int offset, boolean little) {
        var first = data[offset] & 0xFF;
        var second = data[offset + 1] & 0xFF;
        return little ? second << 8 | first : first << 8 | second;
    }

    /**
     * Swaps the bytes of every 16-bit word.
     *
     * @param data the bytes
     * @param length the number of bytes swapped, rounded down to a whole number of words
     */
    private static void swap(byte[] data, int length) {
        for (var index = 0; index + 1 < length; index += 2) {
            var first = data[index];
            data[index] = data[index + 1];
            data[index + 1] = first;
        }
    }

    /**
     * Returns whether a preamble of this stream's byte order starts at an offset, reading it into
     * {@link #preamble}.
     *
     * @param position the offset
     * @return {@code true} when {@code Pa} and {@code Pb} start there
     */
    private boolean preambleAt(long position) {
        if (position < dataStart || position > dataEnd - PREAMBLE
                || cache.readUpTo(position, preamble, 0, PREAMBLE) < PREAMBLE) {
            return false;
        }
        return word(preamble, 0, littleEndian) == 0xF872 && word(preamble, 2, littleEndian) == 0x4E1F;
    }

    /**
     * Searches for the next preamble of this stream's byte order.
     *
     * @param from the first offset searched
     * @return the offset of the preamble, read into {@link #preamble}, or {@code -1} at the end of the data
     */
    private long findPreamble(long from) {
        var position = from + ((from - dataStart) & 1);
        var first = littleEndian ? (byte) 0x72 : (byte) 0xF8;
        var second = littleEndian ? (byte) 0xF8 : (byte) 0x72;
        var third = littleEndian ? (byte) 0x1F : (byte) 0x4E;
        var fourth = littleEndian ? (byte) 0x4E : (byte) 0x1F;
        while (position <= dataEnd - PREAMBLE) {
            var read = cache.readUpTo(position, scan, 0, SCAN_BLOCK);
            if (read < 4) {
                return -1;
            }
            for (var index = 0; index + 4 <= read; index += 2) {
                if (scan[index] == first && scan[index + 1] == second && scan[index + 2] == third
                        && scan[index + 3] == fourth) {
                    return preambleAt(position + index) ? position + index : -1;
                }
            }
            position += (read - 2) & ~1;
        }
        return -1;
    }

    /**
     * Positions the reader at a burst, with no access unit pending and the TrueHD state reset.
     *
     * @param at the offset from which the next preamble is searched
     */
    private void restart(long at) {
        searchFrom = at;
        expected = -1;
        unitCount = 0;
        unitIndex = 0;
        matCarryStart = 0;
        matCarry = 0;
        matCarryCorrupt = false;
        matResync = true;
        finished = false;
    }

    /**
     * Reads the next burst of the followed family and bitstream into the access unit buffers.
     *
     * @return {@code false} at the end of the bursts
     */
    private boolean nextBurst() {
        unitCount = 0;
        unitIndex = 0;
        while (true) {
            long at;
            var contiguous = expected >= searchFrom && preambleAt(expected);
            if (contiguous) {
                at = expected;
            } else {
                at = findPreamble(searchFrom);
            }
            if (at < 0) {
                return false;
            }
            var pc = word(preamble, 4, littleEndian);
            var burstType = pc & 0x1F;
            var length = payloadLength(burstType, word(preamble, 6, littleEndian));
            var payloadStart = at + PREAMBLE;
            var stored = length + (length & 1);
            if (stored > dataEnd - payloadStart) {
                return false;
            }
            searchFrom = payloadStart + stored;
            expected = -1;
            if (family(burstType) != family || pc >>> 13 != bitstream || length == 0
                    || family == Family.AC3 && burstType != start.type()) {
                continue;
            }
            if (payload.length < stored) {
                LimitChecks.checkAllocation(limits, stored);
                payload = new byte[stored];
            }
            if (cache.readUpTo(payloadStart, payload, 0, stored) < stored) {
                return false;
            }
            ForwardOnlySource.release(source, searchFrom);
            if (littleEndian) {
                swap(payload, stored);
            }
            var corrupt = (pc & 0x80) != 0;
            lastBurstInfo = pc;
            var samples = split(length, at, corrupt, contiguous);
            expected = at + (long) period(pc, samples) * FRAME;
            if (unitCount > 0) {
                return true;
            }
        }
    }

    /**
     * Returns the position of a burst on the IEC 61937 timeline.
     *
     * @param at the offset of the burst's preamble
     * @return the timestamp, in samples
     */
    private long timeAt(long at) {
        return periodSamples == 0 ? 0 : (at - dataStart) / FRAME * periodSamples / periodFrames;
    }

    /**
     * Splits the payload of a burst into access units.
     *
     * @param length the payload length
     * @param at the offset of the burst's preamble
     * @param corrupt whether the burst's error flag is set
     * @param contiguous whether the burst follows the previous one at its repetition period
     * @return the number of samples the payload carries
     */
    private int split(int length, long at, boolean corrupt, boolean contiguous) {
        units = payload;
        var time = timeAt(at);
        return switch (family) {
            case AC3 -> splitAc3(length, time, corrupt);
            case MPEG -> splitMpeg(length, time, corrupt, (lastBurstInfo & 0x1F) == TYPE_MPEG2_EXTENSION);
            case AAC -> splitAac(length, time, corrupt);
            case LOAS -> splitLoas(length, time, corrupt);
            case DTS -> splitDts(length, time, corrupt);
            case DTS_HD -> splitDtsHd(length, time, corrupt);
            case MAT -> splitMat(length, at, corrupt, contiguous);
        };
    }

    /**
     * Appends an access unit.
     *
     * @param from the offset of its first byte in {@link #units}
     * @param to the offset after its last byte
     * @param time its timestamp
     * @param samples its duration
     * @param key whether it is a key frame
     * @param corrupt whether its burst's error flag is set
     */
    private void addUnit(int from, int to, long time, int samples, boolean key, boolean corrupt) {
        if (unitStarts.length == unitCount) {
            var grown = unitCount * 2;
            unitStarts = Arrays.copyOf(unitStarts, grown);
            unitEnds = Arrays.copyOf(unitEnds, grown);
            unitTimes = Arrays.copyOf(unitTimes, grown);
            unitSamples = Arrays.copyOf(unitSamples, grown);
            unitKeys = Arrays.copyOf(unitKeys, grown);
            unitCorrupt = Arrays.copyOf(unitCorrupt, grown);
        }
        unitStarts[unitCount] = from;
        unitEnds[unitCount] = to;
        unitTimes[unitCount] = time;
        unitSamples[unitCount] = samples;
        unitKeys[unitCount] = key;
        unitCorrupt[unitCount] = corrupt;
        unitCount++;
    }

    /**
     * Splits an AC-3 or E-AC-3 payload into access units.
     *
     * @param length the payload length
     * @param time the timestamp of the first access unit
     * @param corrupt whether the burst's error flag is set
     * @return the number of samples the payload carries
     */
    private int splitAc3(int length, long time, boolean corrupt) {
        var position = 0;
        var unitStart = -1;
        var samples = 0;
        var total = 0;
        while (position + 6 <= length) {
            var frame = Ac3Configs.frameLength(payload, position, length - position);
            if (frame <= 0 || frame > length - position) {
                break;
            }
            if (Ac3Configs.startsAccessUnit(payload, position)) {
                if (unitStart >= 0) {
                    addUnit(unitStart, position, time, samples, true, corrupt);
                    time += samples;
                }
                unitStart = position;
                samples = 256 * blocks(payload, position);
                total += samples;
            }
            position += frame;
        }
        if (unitStart >= 0) {
            addUnit(unitStart, position, time, samples, true, corrupt);
        }
        return total;
    }

    /**
     * Returns the number of audio blocks of a sync frame.
     *
     * @param data the bytes
     * @param offset the offset of the sync word
     * @return 6 for AC-3, the {@code numblkscod} blocks for E-AC-3
     */
    private static int blocks(byte[] data, int offset) {
        if ((data[offset + 5] & 0xFF) >> 3 <= 10) {
            return 6;
        }
        var fscod = (data[offset + 4] & 0xFF) >> 6;
        return fscod == 3 ? 6 : Ac3Configs.BLOCKS[(data[offset + 4] >> 4) & 0x03];
    }

    /**
     * Splits an MPEG audio payload into frames; the MPEG-2 extension bitstream that follows the base frame in a
     * burst of data type 6 stays with the last frame, where a decoder of the base frames skips it.
     *
     * @param length the payload length
     * @param time the timestamp of the first frame
     * @param corrupt whether the burst's error flag is set
     * @param extension whether the burst is of data type 6
     * @return the number of samples the payload carries
     */
    private int splitMpeg(int length, long time, boolean corrupt, boolean extension) {
        var position = 0;
        var total = 0;
        while (position + MpegAudioHeader.SIZE <= length) {
            var header = MpegAudioHeader.read(payload, position);
            if (!MpegAudioHeader.isValid(header) || MpegAudioHeader.isFreeFormat(header)) {
                break;
            }
            var size = MpegAudioHeader.frameBytes(header, 0) + MpegAudioHeader.padding(header);
            if (size > length - position) {
                break;
            }
            var samples = MpegAudioHeader.samplesPerFrame(header);
            addUnit(position, position + size, time + total, samples, true, corrupt);
            total += samples;
            position += size;
        }
        if (extension && unitCount > 0) {
            unitEnds[unitCount - 1] = length;
        }
        return total;
    }

    /**
     * Splits an AAC payload into the raw data blocks of its ADTS frames, as the ADTS demuxer does: one packet per
     * frame, or per block when a frame of several blocks carries their positions.
     *
     * @param length the payload length
     * @param time the timestamp of the first block
     * @param corrupt whether the burst's error flag is set
     * @return the number of samples the payload carries
     */
    private int splitAac(int length, long time, boolean corrupt) {
        var position = 0;
        var total = 0;
        while (position + AdtsHeader.SIZE <= length) {
            var header = AdtsHeader.parse(payload, position);
            if (header == null || header.frameLength() > length - position) {
                break;
            }
            if (adtsHeader == null) {
                adtsHeader = header;
            }
            var frameEnd = position + header.frameLength();
            var blocks = header.blocks();
            if (blocks == 1 || header.protectionAbsent()) {
                addUnit(position + header.headerSize(), frameEnd, time + total, 1024 * blocks, true, corrupt);
                total += 1024 * blocks;
            } else {
                var from = position + header.headerSize();
                for (var block = 0; block < blocks; block++) {
                    var to = block + 1 < blocks ? position + ((payload[position + 7 + 2 * block] & 0xFF) << 8
                            | payload[position + 8 + 2 * block] & 0xFF) : frameEnd;
                    if (to - 2 <= from || to > frameEnd) {
                        throw new MediaException.Malformed("invalid ADTS raw data block position");
                    }
                    addUnit(from, to - 2, time + total, 1024, true, corrupt);
                    total += 1024;
                    from = to;
                }
            }
            position = frameEnd;
        }
        return total;
    }

    /**
     * Splits an AAC payload in LOAS framing into the payloads of the subframes of its {@code AudioMuxElement}s, one
     * raw data block of 1024 samples each; the track takes the first {@code StreamMuxConfig}.
     *
     * @param length the payload length
     * @param time the timestamp of the first block
     * @param corrupt whether the burst's error flag is set
     * @return the number of samples the payload carries
     */
    private int splitLoas(int length, long time, boolean corrupt) {
        var position = 0;
        var total = 0;
        var copied = 0;
        while (position + 3 <= length) {
            var frame = Latm.frameLength(payload, position, length - position);
            if (frame <= 0 || frame > length - position) {
                break;
            }
            loasReader.reset(payload, position + 3, frame - 3);
            byte[][] payloads = null;
            if (!loasReader.readFlag()) {
                var parsed = Latm.readStreamMuxConfig(loasReader);
                if (parsed != null && loasConfig == null) {
                    loasConfig = parsed;
                }
                if (parsed != null) {
                    payloads = Latm.readPayloads(loasReader, parsed, frame);
                }
            } else if (loasConfig != null) {
                payloads = Latm.readPayloads(loasReader, loasConfig, frame);
            }
            if (payloads != null) {
                for (var block : payloads) {
                    if (unpacked.length < copied + block.length) {
                        unpacked = Arrays.copyOf(unpacked, Math.max(copied + block.length, 2 * unpacked.length));
                    }
                    System.arraycopy(block, 0, unpacked, copied, block.length);
                    addUnit(copied, copied + block.length, time + total, 1024, true, corrupt);
                    copied += block.length;
                    total += 1024;
                }
            }
            position += frame;
        }
        units = unpacked;
        return total;
    }

    /**
     * Splits a payload of DTS core frames into frames.
     *
     * @param length the payload length
     * @param time the timestamp of the first frame
     * @param corrupt whether the burst's error flag is set
     * @return the number of samples the payload carries
     */
    private int splitDts(int length, long time, boolean corrupt) {
        if (length >= PACKED_DTS_HEADER && DtsConfigs.isPackedSync(payload, 0)) {
            return splitPackedDts(length, time, corrupt);
        }
        var position = 0;
        var total = 0;
        while (position + DtsConfigs.HEADER_SIZE <= length && DtsConfigs.isSync(payload, position)) {
            var size = DtsConfigs.frameSize(payload, position);
            if (size > length - position) {
                break;
            }
            var samples = DtsConfigs.samples(payload, position);
            addUnit(position, position + size, time + total, samples, true, corrupt);
            total += samples;
            position += size;
        }
        return total;
    }

    /**
     * Splits a payload of DTS core frames in the 14-bit form into frames, converted to the 16-bit form.
     *
     * @param length the payload length
     * @param time the timestamp of the first frame
     * @param corrupt whether the burst's error flag is set
     * @return the number of samples the payload carries
     */
    private int splitPackedDts(int length, long time, boolean corrupt) {
        var position = 0;
        var converted = 0;
        var total = 0;
        while (position + PACKED_DTS_HEADER <= length && DtsConfigs.isPackedSync(payload, position)) {
            DtsConfigs.unpack(payload, position, dtsHeader, 0, dtsHeader.length);
            var bytes = DtsConfigs.frameSize(dtsHeader, 0);
            var stored = DtsConfigs.packedSize(bytes);
            if (stored > length - position) {
                break;
            }
            if (unpacked.length < converted + bytes) {
                unpacked = Arrays.copyOf(unpacked, Math.max(converted + bytes, 2 * unpacked.length));
            }
            DtsConfigs.unpack(payload, position, unpacked, converted, bytes);
            var samples = DtsConfigs.samples(dtsHeader, 0);
            addUnit(converted, converted + bytes, time + total, samples, true, corrupt);
            total += samples;
            converted += bytes;
            position += stored;
        }
        units = unpacked;
        return total;
    }

    /**
     * Splits a DTS type IV payload: its header, then one DTS-HD frame, the core frame first.
     *
     * @param length the payload length
     * @param time the timestamp of the frame
     * @param corrupt whether the burst's error flag is set
     * @return the number of samples the payload carries
     */
    private int splitDtsHd(int length, long time, boolean corrupt) {
        if (length < DTS4_HEADER + DtsConfigs.HEADER_SIZE || payload[8] != (byte) 0xFE || payload[9] != (byte) 0xFE
                || !DtsConfigs.isSync(payload, DTS4_HEADER)) {
            return 0;
        }
        var size = (payload[10] & 0xFF) << 8 | payload[11] & 0xFF;
        if (size > length - DTS4_HEADER) {
            return 0;
        }
        var samples = DtsConfigs.samples(payload, DTS4_HEADER);
        addUnit(DTS4_HEADER, DTS4_HEADER + size, time, samples, true, corrupt);
        return samples;
    }

    /**
     * Appends the TrueHD data of a MAT frame to the carried data and splits off the complete access units; the
     * start, middle and end codes of the frame are dropped, and so is the zero padding between access units.
     *
     * @param length the payload length
     * @param at the offset of the burst's preamble
     * @param corrupt whether the burst's error flag is set
     * @param contiguous whether the burst follows the previous one at its repetition period
     * @return the number of samples a MAT frame carries
     */
    private int splitMat(int length, long at, boolean corrupt, boolean contiguous) {
        var valid = length == MAT_PAYLOAD && payload[0] == 0x07 && payload[1] == (byte) 0x9E
                && payload[MAT_MIDDLE] == (byte) 0xC3 && payload[MAT_MIDDLE + 1] == (byte) 0xC1
                && payload[MAT_PAYLOAD - MAT_END_SIZE] == (byte) 0xC3
                && payload[MAT_PAYLOAD - MAT_END_SIZE + 1] == (byte) 0xC2;
        if (!contiguous || !valid) {
            matCarry = 0;
            matCarryCorrupt = false;
            matResync = true;
        }
        if (!valid) {
            return MAT_UNITS * matUnitSamples;
        }
        var first = MAT_MIDDLE - MAT_START;
        var second = MAT_PAYLOAD - MAT_END_SIZE - MAT_MIDDLE - MAT_MIDDLE_SIZE;
        var carried = matCarry;
        var total = carried + first + second;
        if (mat.length < total) {
            LimitChecks.checkAllocation(limits, total);
            var grown = new byte[total];
            System.arraycopy(mat, matCarryStart, grown, 0, carried);
            mat = grown;
        } else {
            System.arraycopy(mat, matCarryStart, mat, 0, carried);
        }
        System.arraycopy(payload, MAT_START, mat, carried, first);
        System.arraycopy(payload, MAT_MIDDLE + MAT_MIDDLE_SIZE, mat, carried + first, second);
        units = mat;
        var carriedCorrupt = matCarryCorrupt;
        var position = 0;
        while (position + 4 <= total) {
            var unit = TrueHdConfigs.unitLength(mat, position);
            if (unit == 0) {
                position += 2;
                continue;
            }
            if (matResync) {
                if (position + TrueHdConfigs.MAJOR_SYNC_SIZE > total) {
                    break;
                }
                if (!TrueHdConfigs.isMajorSync(mat, position) || TrueHdConfigs.unitSamples(mat, position) == 0) {
                    position += 2;
                    continue;
                }
                matResync = false;
                matTime = resyncTime(TrueHdConfigs.inputTiming(mat, position), at, position);
            }
            if (unit < 4) {
                matResync = true;
                position += 2;
                continue;
            }
            if (position + unit > total) {
                break;
            }
            var key = unit >= TrueHdConfigs.MAJOR_SYNC_SIZE && TrueHdConfigs.isMajorSync(mat, position);
            addUnit(position, position + unit, matTime, matUnitSamples, key,
                    corrupt || position < carried && carriedCorrupt);
            matTime += matUnitSamples;
            position += unit;
        }
        matCarryStart = position;
        matCarry = total - position;
        matCarryCorrupt = corrupt;
        return MAT_UNITS * matUnitSamples;
    }

    /**
     * Returns the timestamp of the TrueHD major sync unit from which the access units are followed again: the
     * position of its burst for the first one, which also describes the stream, and for the others the first one's
     * timestamp advanced by the difference of their input timings, unwrapped to the value nearest the position of
     * their burst.
     *
     * @param timing the unit's input timing
     * @param at the offset of the preamble of the burst holding the unit
     * @param position the unit's offset in {@link #mat}
     * @return the timestamp
     */
    private long resyncTime(int timing, long at, int position) {
        if (matDescription == null) {
            matUnitSamples = TrueHdConfigs.unitSamples(mat, position);
            periodSamples = (long) MAT_UNITS * matUnitSamples;
            matDescription = new Description(Codec.TRUEHD, TrueHdConfigs.sampleRate(mat, position),
                    TrueHdConfigs.layout(mat, position), null, periodSamples, null);
        }
        var burstTime = timeAt(at);
        if (matTimingOrigin < 0) {
            matTimingOrigin = timing;
            matTimeOrigin = burstTime;
            return burstTime;
        }
        var base = matTimeOrigin + ((timing - matTimingOrigin) & 0xFFFF);
        return base + Math.round((burstTime - base) / 65536.0) * 65536;
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
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The next packet is the access unit that holds the sample presented at {@code micros}, found from the IEC
     * 61937 timeline, or for TrueHD the last access unit with a major sync at or before it.
     */
    @Override
    public void seek(long micros) {
        // the time base counts samples of the coded rate, which an HE-AAC refinement does not change
        var rate = track.timeBase().denominator();
        var target = micros <= 0 ? 0 : micros / 1_000_000 * rate + micros % 1_000_000 * rate / 1_000_000;
        var back = switch (family) {
            case MAT -> (long) MAT_SEEK_UNITS * matUnitSamples + periodSamples;
            case AAC, LOAS -> MAX_AAC_SAMPLES;
            default -> periodSamples;
        };
        var frame = Math.max(0, target - back) * periodFrames / periodSamples;
        var from = frame > (dataEnd - dataStart) / FRAME ? dataEnd : dataStart + frame * FRAME;
        restart(Math.max(dataStart, from));
        skipBefore = target;
        held.clear();
        holding = family == Family.MAT;
    }

    @Override
    public Packet read() {
        try {
            while (true) {
                if (!holding && !held.isEmpty()) {
                    return held.poll();
                }
                var packet = nextUnit();
                if (packet == null) {
                    holding = false;
                    return held.poll();
                }
                if (!holding) {
                    if (packet.pts() + packet.duration() <= skipBefore) {
                        continue;
                    }
                    return packet;
                }
                if (packet.keyframe() && packet.pts() <= skipBefore) {
                    held.clear();
                    held.add(packet);
                } else if (!held.isEmpty() || packet.keyframe()) {
                    held.add(packet);
                }
                if (packet.pts() + packet.duration() > skipBefore && !held.isEmpty()) {
                    holding = false;
                }
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid IEC 61937 data", exception);
        }
    }

    /**
     * Returns the next access unit.
     *
     * @return the packet, or {@code null} at the end of the bursts
     */
    private Packet nextUnit() {
        while (!finished) {
            if (unitIndex < unitCount) {
                var index = unitIndex++;
                var data = Arrays.copyOfRange(units, unitStarts[index], unitEnds[index]);
                var flags = unitKeys[index] ? unitCorrupt[index] ? KEY_CORRUPT : KEY
                        : unitCorrupt[index] ? DELTA_CORRUPT : DELTA;
                return new Packet(track.id(), data, unitTimes[index], unitTimes[index], unitSamples[index], flags,
                        PacketSideData.NONE);
            }
            if (!nextBurst()) {
                finished = true;
            }
        }
        return null;
    }
}
