package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.AmrConfigs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.subtitle.TimedText;
import com.github.auties00.smalt.internal.subtitle.WebVttText;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Serializes the sample description of a track the {@link Mp4Muxer} writes: a visual sample entry
 * ({@code avc1}, {@code hvc1}, {@code vp08}, {@code vp09}, {@code av01}, {@code mp4v} with its
 * {@code esds}, {@code s263} with its {@code d263}, the QuickTime {@code h263}, {@code jpeg},
 * {@code mjpa} and {@code mjpb}) with its configuration box, an optional {@code colr} followed by the {@code mdcv}
 * and {@code clli} boxes of its high-dynamic-range metadata, and the optional {@code pasp} and
 * {@code fiel} boxes of a declared sample aspect ratio and scan, a QuickTime visual sample entry
 * ({@code apco} to {@code ap4x} for ProRes, {@code AVdn} and {@code AVdh} for DNxHD) with its
 * {@code colr}, {@code mdcv}, {@code clli}, {@code fiel} and {@code pasp} boxes, an audio sample
 * entry ({@code mp4a}, {@code Opus}, {@code alac}, {@code fLaC}, {@code samr}, {@code sawb},
 * {@code ac-3}, {@code ec-3}) with its configuration box, a PCM entry ({@code ipcm} and
 * {@code fpcm} with {@code pcmC} and {@code chnl}, or a QuickTime sound description with a
 * {@code chan} box), or a text sample entry: {@code tx3g} (3GPP TS 26.245), {@code wvtt} with its
 * {@code vttC} box or {@code stpp} (ISO/IEC 14496-30).
 */
final class SampleEntryWriter {
    /**
     * The sample aspect ratio of square samples.
     */
    private static final Rational SQUARE = new Rational(1, 1);

    /**
     * The {@code objectTypeIndication} of MPEG-4 Part 2 video (ISO/IEC 14496-1 Table 5).
     */
    private static final int MPEG4_VISUAL = 0x20;

    /**
     * The {@code objectTypeIndication} of JPEG pictures (ISO/IEC 14496-1 Table 5).
     */
    private static final int JPEG_VISUAL = 0x6C;

    /**
     * The {@code streamType} of a visual stream.
     */
    private static final int VISUAL_STREAM = 0x04;

    /**
     * The H.263 decoder configuration box type ({@code d263}).
     */
    private static final int D263 = 0x64323633;

    /**
     * The vendor code written in a {@code d263} box ({@code SMLT}).
     */
    private static final int VENDOR = 0x534D4C54;

    /**
     * The QuickTime {@code AudioChannelLabel} of each {@link Speaker}, by ordinal: the surround
     * direct pair, whose labels the side pair uses, has the unknown label {@code 0xFFFFFFFF}; the
     * entry of {@link Speaker#UNKNOWN} is unused.
     */
    private static final long[] QUICKTIME_LABELS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            35, 36, 0xFFFFFFFFL, 0xFFFFFFFFL, 37, 49, 51, 59, 57, 58, 0};

    /**
     * The ISO/IEC 23091-3 speaker position of each {@link Speaker}, by ordinal, or {@code -1} for
     * {@link Speaker#UNKNOWN}, for which no {@code chnl} box is written: the side pair is the
     * surround pair (positions 4 and 5), the back pair the rear surround pair (8 and 9), the top
     * speakers the vertical (height) positions 17 to 25, and the wide, surround direct, second LFE
     * and bottom speakers their own positions.
     */
    private static final int[] ISO_POSITIONS = {0, 1, 2, 3, 8, 9, 6, 7, 10, 4, 5, 25, 17, 19, 18, 20, 22, 21, 15, 16,
            11, 12, 26, 23, 24, 29, 27, 28, -1};

    private SampleEntryWriter() {
        throw new AssertionError();
    }

    /**
     * Writes the sample entry of a track.
     *
     * @param writer the writer positioned inside the {@code stsd} box
     * @param plan the track layout
     */
    static void write(BoxWriter writer, Mp4Muxer.TrackPlan plan) {
        write(writer, plan, 0);
    }

    /**
     * Writes one sample entry of a track that has several.
     *
     * @param writer the writer positioned inside the {@code stsd} box
     * @param plan the track layout
     * @param index the zero-based sample description: {@code 0} for the track's own, a later one
     * for a configuration the packets changed to
     */
    static void write(BoxWriter writer, Mp4Muxer.TrackPlan plan, int index) {
        var track = plan.track;
        var config = index == 0 ? track.config : track.extraConfigs.get(index - 1);
        var described = index == 0 ? track.track : describe(track.track, config);
        switch (track.codec()) {
            case H264 -> writeVisual(writer, BoxTypes.AVC1, BoxTypes.AVCC, false, (VideoTrack) described, config);
            case HEVC -> writeHevc(writer, (VideoTrack) described, config);
            case VP8 -> writeVisual(writer, BoxTypes.VP08, BoxTypes.VPCC, true, (VideoTrack) described, config);
            case VP9 -> writeVisual(writer, BoxTypes.VP09, BoxTypes.VPCC, true, (VideoTrack) described, config);
            case AV1 -> writeVisual(writer, BoxTypes.AV01, BoxTypes.AV1C, false, (VideoTrack) described, config);
            case MPEG4_PART2 -> writeVisualEsds(writer, plan, MPEG4_VISUAL, (VideoTrack) described, config);
            case MJPEG -> {
                if (track.quickTime) {
                    var format = isMotionJpegA(track.firstBytes) ? BoxTypes.MJPA
                            : isMotionJpegB(track.firstBytes) ? BoxTypes.MJPB : BoxTypes.JPEG;
                    writeVisualHeader(writer, format, (VideoTrack) described, "Photo - JPEG");
                    writeVisualTrailer(writer, (VideoTrack) described);
                } else {
                    writeVisualEsds(writer, plan, JPEG_VISUAL, (VideoTrack) described, null);
                }
            }
            case H263 -> writeH263(writer, track, (VideoTrack) described);
            case PRORES, DNXHD -> writeQuickTimeVisual(writer, track, (VideoTrack) described, config);
            case MOV_TEXT, WEBVTT -> writeText(writer, track.track);
            case TTML -> writeTtml(writer, track.track);
            default -> {
                if (track.pcmFrameBytes > 0) {
                    writePcm(writer, track, (AudioTrack) described);
                } else {
                    writeAudio(writer, plan, (AudioTrack) described, config);
                }
            }
        }
    }

    /**
     * Returns a track's description with another codec configuration, and the picture size or the
     * sampling rate and channel count that configuration declares.
     *
     * @param track the declared track
     * @param config the configuration
     * @return the description
     */
    static Track describe(Track track, byte[] config) {
        return switch (track) {
            case VideoTrack video -> {
                var builder = video.toBuilder().codecConfig(config);
                var info = VideoConfigs.describe(video.codec(), config);
                if (info != null && info.width() > 0 && info.height() > 0) {
                    builder.width(info.width()).height(info.height());
                }
                yield builder.build();
            }
            case AudioTrack audio -> {
                var builder = audio.toBuilder().codecConfig(config);
                try {
                    var rate = 0;
                    var channels = 0;
                    ChannelLayout layout = null;
                    switch (audio.codec()) {
                        case AAC -> {
                            var aac = AudioConfigs.parseAac(config);
                            rate = aac.sampleRate();
                            channels = aac.channels();
                            layout = aac.layout();
                        }
                        case OPUS -> channels = AudioConfigs.parseOpusHead(config).channels();
                        case FLAC -> {
                            var flac = AudioConfigs.parseFlac(config);
                            rate = flac.sampleRate();
                            channels = flac.channels();
                        }
                        case ALAC -> {
                            var alac = AudioConfigs.parseAlac(config);
                            rate = alac.sampleRate();
                            channels = alac.channels();
                        }
                        default -> {
                        }
                    }
                    if (rate > 0) {
                        builder.sampleRate(rate);
                    }
                    if (layout != null) {
                        builder.layout(layout);
                    } else if (channels > 0 && channels != audio.layout().map(ChannelLayout::channels).orElse(0)) {
                        builder.layout(ChannelLayout.defaultFor(channels));
                    }
                } catch (MediaException.Malformed ignored) {
                    // validation reports the unparsable configuration
                }
                yield builder.build();
            }
            default -> track;
        };
    }

    /**
     * Writes a visual sample entry, ISO/IEC 14496-12 clause 12.1.3.
     *
     * @param writer the destination
     * @param format the sample entry type
     * @param configType the type of the configuration box
     * @param full whether the configuration box is a version 1 full box ({@code vpcC})
     * @param video the track
     * @param config the configuration box body
     * @throws IllegalStateException if the configuration is unknown
     */
    private static void writeVisual(BoxWriter writer, int format, int configType, boolean full, VideoTrack video, byte[] config) {
        if (config == null) {
            throw new IllegalStateException(video.codec().displayName() + " track " + video.id()
                    + " has no codec configuration and no key frame it can be derived from");
        }
        writeVisualHeader(writer, format, video, null);
        if (full) {
            writer.beginFull(configType, 1, 0);
        } else {
            writer.begin(configType);
        }
        writer.bytes(config);
        writer.end();
        writeVisualTrailer(writer, video);
    }

    /**
     * Writes an {@code hvc1} sample entry: the {@code hvcC} box of the base layer's parameter sets
     * and, when the configuration also carries parameter sets of other layers, an {@code lhvC} box
     * holding those.
     *
     * @param writer the destination
     * @param video the track
     * @param config the {@code hvcC} configuration, which may hold the parameter sets of every layer
     */
    private static void writeHevc(BoxWriter writer, VideoTrack video, byte[] config) {
        if (config == null) {
            throw new IllegalStateException(video.codec().displayName() + " track " + video.id()
                    + " has no codec configuration and no key frame it can be derived from");
        }
        var records = NalRecords.splitLayeredHevc(config);
        writeVisualHeader(writer, BoxTypes.HVC1, video, null);
        writer.begin(BoxTypes.HVCC);
        writer.bytes(records[0]);
        writer.end();
        if (records[1] != null) {
            writer.begin(BoxTypes.LHVC);
            writer.bytes(records[1]);
            writer.end();
        }
        writeVisualTrailer(writer, video);
    }

    /**
     * Opens a visual sample entry and writes its fixed fields, ISO/IEC 14496-12 clause 12.1.3.
     *
     * @param writer the destination
     * @param format the sample entry type
     * @param video the track
     * @param compressor the compressor name, at most 31 ASCII characters, or {@code null} for none
     */
    private static void writeVisualHeader(BoxWriter writer, int format, VideoTrack video, String compressor) {
        writer.begin(format);
        writer.zeros(6);
        writer.u16(1);
        writer.zeros(16);
        writer.u16(video.width());
        writer.u16(video.height());
        writer.u32(0x00480000);
        writer.u32(0x00480000);
        writer.u32(0);
        writer.u16(1);
        var name = compressor == null ? new byte[0] : compressor.getBytes(StandardCharsets.US_ASCII);
        writer.u8(name.length);
        writer.bytes(name);
        writer.zeros(31 - name.length);
        writer.u16(0x0018);
        writer.u16(0xFFFF);
    }

    /**
     * Writes the optional {@code colr}, {@code mdcv}, {@code clli}, {@code pasp} and {@code fiel}
     * boxes of a visual sample entry and closes it.
     *
     * @param writer the destination, inside the sample entry
     * @param video the track
     */
    private static void writeVisualTrailer(BoxWriter writer, VideoTrack video) {
        if (video.color().isPresent()) {
            var color = video.color().get();
            writer.begin(BoxTypes.COLR);
            writer.u32(BoxTypes.NCLX);
            writer.u16(color.primaries().code());
            writer.u16(color.transfer().code());
            writer.u16(color.matrix().code());
            writer.u8(color.range() == ColorRange.FULL ? 0x80 : 0);
            writer.end();
            writeHdrMetadata(writer, color);
        }
        video.sampleAspectRatio().ifPresent(aspect -> writeAspect(writer, aspect));
        video.interlacing().ifPresent(interlacing -> writeFields(writer, interlacing));
        writer.end();
    }

    /**
     * Writes an {@code mp4v} visual sample entry with an {@code esds} box (ISO/IEC 14496-14 clause
     * 5.6), the entry of MPEG-4 Part 2 video and of Motion-JPEG in ISO files.
     *
     * @param writer the destination
     * @param plan the track layout
     * @param objectType the {@code objectTypeIndication}
     * @param video the track
     * @param config the {@code DecoderSpecificInfo}, or {@code null} for none
     */
    private static void writeVisualEsds(BoxWriter writer, Mp4Muxer.TrackPlan plan, int objectType, VideoTrack video,
                                        byte[] config) {
        writeVisualHeader(writer, BoxTypes.MP4V, video, null);
        var descriptor = new EsDescriptor(objectType, VISUAL_STREAM, maxSampleSize(plan), maxBitrate(plan),
                averageBitrate(plan), config);
        writer.beginFull(BoxTypes.ESDS, 0, 0);
        descriptor.write(writer, plan.track.number);
        writer.end();
        writeVisualTrailer(writer, video);
    }

    /**
     * Writes the sample entry of an H.263 track: {@code s263} with its {@code d263} box (3GPP TS
     * 26.244 clause 6.8) in ISO files, the QuickTime {@code h263} entry otherwise.
     *
     * <p>The {@code d263} box declares profile 0 and the lowest baseline level whose picture size
     * holds the track's: level 10 up to QCIF, 30 up to CIF, 60 up to 720 by 288 and 70 above.
     *
     * @param writer the destination
     * @param track the track state
     * @param video the track
     */
    private static void writeH263(BoxWriter writer, MuxTrack track, VideoTrack video) {
        writeVisualHeader(writer, track.quickTime ? BoxTypes.H263 : BoxTypes.S263, video, null);
        if (!track.quickTime) {
            var width = video.width();
            var height = video.height();
            var level = width <= 176 && height <= 144 ? 10 : width <= 352 && height <= 288 ? 30
                    : width <= 720 && height <= 288 ? 60 : 70;
            writer.begin(D263);
            writer.u32(VENDOR);
            writer.u8(0);
            writer.u8(level);
            writer.u8(0);
            writer.end();
        }
        writeVisualTrailer(writer, video);
    }

    /**
     * Returns whether a Motion-JPEG frame is in the QuickTime Motion-JPEG format A: a JPEG whose
     * first marker segment after the start of image is an {@code APP1} segment whose payload
     * starts with four reserved bytes and the tag {@code mjpg}.
     *
     * @param frame the first bytes of the first frame, or {@code null}
     * @return {@code true} for a format A frame
     */
    static boolean isMotionJpegA(byte[] frame) {
        return frame != null && frame.length >= 14 && (frame[0] & 0xFF) == 0xFF && (frame[1] & 0xFF) == 0xD8
                && (frame[2] & 0xFF) == 0xFF && (frame[3] & 0xFF) == 0xE1
                && frame[10] == 'm' && frame[11] == 'j' && frame[12] == 'p' && frame[13] == 'g';
    }

    /**
     * Returns whether a Motion-JPEG frame is in the QuickTime Motion-JPEG format B: it starts with
     * the marker-less header, four reserved bytes and the tag {@code mjpg}, rather than with the
     * start of image.
     *
     * @param frame the first bytes of the first frame, or {@code null}
     * @return {@code true} for a format B frame
     */
    static boolean isMotionJpegB(byte[] frame) {
        return frame != null && frame.length >= 8 && !((frame[0] & 0xFF) == 0xFF && (frame[1] & 0xFF) == 0xD8)
                && frame[4] == 'm' && frame[5] == 'j' && frame[6] == 'p' && frame[7] == 'g';
    }

    /**
     * Writes the sample entry of a PCM track, one frame per sample.
     *
     * <p>A QuickTime file uses a version 1 sound description with the format of the sample
     * encoding ({@code raw }, {@code twos}, {@code sowt}, {@code in24}, {@code in32}, {@code fl32},
     * {@code fl64}, {@code alaw}, {@code ulaw}), and a version 2 description when the encoding has
     * no such format (little-endian samples wider than 16 bits, as {@code lpcm}), the channel count
     * exceeds 2 or the sampling rate exceeds 65535 Hz; a {@code chan} box lists the speakers when
     * the track declares a layout. An ISO file uses an {@code ipcm} or {@code fpcm} entry with its
     * {@code pcmC} box (ISO/IEC 23003-5), whose sampling rate field is zero when the rate exceeds
     * 65535 Hz, followed by a {@code chnl} box when every speaker of the layout has an ISO/IEC
     * 23091-3 position.
     *
     * @param writer the destination
     * @param track the PCM track state
     * @param audio the track
     */
    private static void writePcm(BoxWriter writer, MuxTrack track, AudioTrack audio) {
        var codec = audio.codec();
        var channels = audio.layout().orElseThrow().channels();
        var bits = codec.pcmBitsPerSample().orElseThrow();
        var rate = audio.sampleRate();
        var isFloat = codec == Codec.PCM_F32LE || codec == Codec.PCM_F32BE || codec == Codec.PCM_F64LE
                || codec == Codec.PCM_F64BE;
        var littleEndian = codec.name().endsWith("LE");
        if (!track.quickTime) {
            writer.begin(isFloat ? BoxTypes.FPCM : BoxTypes.IPCM);
            writer.zeros(6);
            writer.u16(1);
            writer.zeros(8);
            writer.u16(channels);
            writer.u16(bits);
            writer.u32(0);
            // a version 0 entry with a zero rate stands for a rate above 16.16: readers take the
            // media timescale, which is the sampling rate, and treat version 1 as QuickTime's
            writer.u32(rate > 0xFFFF ? 0 : (long) rate << 16);
            writer.beginFull(BoxTypes.PCMC, 0, 0);
            writer.u8(littleEndian ? 1 : 0);
            writer.u8(bits);
            writer.end();
            writeChnl(writer, audio.layout().get());
            writer.end();
            return;
        }
        var law = codec == Codec.PCM_ALAW || codec == Codec.PCM_MULAW;
        var format = switch (codec) {
            case PCM_U8 -> BoxTypes.RAW;
            case PCM_S8, PCM_S16BE -> BoxTypes.TWOS;
            case PCM_S16LE -> BoxTypes.SOWT;
            case PCM_S24BE -> BoxTypes.IN24;
            case PCM_S32BE -> BoxTypes.IN32;
            case PCM_F32BE -> BoxTypes.FL32;
            case PCM_F64BE -> BoxTypes.FL64;
            case PCM_ALAW -> BoxTypes.ALAW;
            case PCM_MULAW -> BoxTypes.ULAW;
            default -> BoxTypes.LPCM;
        };
        var version2 = format == BoxTypes.LPCM || channels > 2 || rate > 0xFFFF;
        if (version2 && !law) {
            format = BoxTypes.LPCM;
        }
        writer.begin(format);
        writer.zeros(6);
        writer.u16(1);
        writer.u16(version2 ? 2 : 1);
        writer.zeros(6);
        if (version2) {
            writer.u16(3);
            writer.u16(16);
            writer.u16(0xFFFE);
            writer.u16(0);
            writer.u32(0x00010000L);
            writer.u32(72);
            writer.u64(Double.doubleToLongBits(rate));
            writer.u32(channels);
            writer.u32(0x7F000000L);
            writer.u32(bits);
            var flags = law ? 0 : (isFloat ? 1 : 0) | (littleEndian || bits == 8 ? 0 : 2)
                    | (!isFloat && codec != Codec.PCM_U8 ? 4 : 0) | 8;
            writer.u32(flags);
            writer.u32(track.pcmFrameBytes);
            writer.u32(1);
        } else {
            writer.u16(channels);
            writer.u16(law ? 16 : bits);
            writer.u16(0);
            writer.u16(0);
            writer.u32((long) rate << 16);
            writer.u32(1);
            writer.u32(bits / 8);
            writer.u32(track.pcmFrameBytes);
            writer.u32(law ? 2 : bits / 8);
        }
        writeChan(writer, audio.layout().get());
        writer.end();
    }

    /**
     * Writes a QuickTime {@code chan} box listing each speaker of a layout by its channel label.
     *
     * @param writer the destination, inside the sample entry
     * @param layout the channel layout
     */
    private static void writeChan(BoxWriter writer, ChannelLayout layout) {
        writer.beginFull(BoxTypes.CHAN, 0, 0);
        writer.u32(0);
        writer.u32(0);
        writer.u32(layout.channels());
        for (var speaker : layout.speakers()) {
            var label = speaker == Speaker.UNKNOWN ? 0xFFFFFFFFL : QUICKTIME_LABELS[speaker.ordinal()];
            writer.u32(label);
            writer.u32(0);
            writer.zeros(12);
        }
        writer.end();
    }

    /**
     * Writes an ISO {@code chnl} box (ISO/IEC 14496-12 clause 12.2.4, version 0) listing the
     * position of each speaker of a layout, or nothing when a speaker has no position.
     *
     * @param writer the destination, inside the sample entry
     * @param layout the channel layout
     */
    private static void writeChnl(BoxWriter writer, ChannelLayout layout) {
        for (var speaker : layout.speakers()) {
            if (speaker == Speaker.UNKNOWN || ISO_POSITIONS[speaker.ordinal()] < 0) {
                return;
            }
        }
        writer.beginFull(BoxTypes.CHNL, 0, 0);
        writer.u8(1);
        writer.u8(0);
        for (var speaker : layout.speakers()) {
            writer.u8(ISO_POSITIONS[speaker.ordinal()]);
        }
        writer.end();
    }

    /**
     * Writes a {@code pasp} box, ISO/IEC 14496-12 clause 12.1.4.
     *
     * @param writer the destination
     * @param aspect the sample aspect ratio; both terms are clamped to 32 bits
     */
    private static void writeAspect(BoxWriter writer, Rational aspect) {
        var reduced = fit32(aspect);
        writer.begin(BoxTypes.PASP);
        writer.u32(reduced.numerator());
        writer.u32(reduced.denominator());
        writer.end();
    }

    /**
     * Returns a ratio whose terms fit in 32 unsigned bits: the ratio itself when they do, otherwise
     * the closest ratio with a denominator that fits.
     *
     * @param aspect the ratio
     * @return the ratio to store
     */
    private static Rational fit32(Rational aspect) {
        var reduced = aspect.reduce();
        if (reduced.numerator() <= 0xFFFFFFFFL && reduced.denominator() <= 0xFFFFFFFFL) {
            return reduced;
        }
        var scale = (double) 0xFFFFFFFFL / Math.max(reduced.numerator(), reduced.denominator());
        return new Rational(Math.max(1, Math.round(reduced.numerator() * scale)),
                Math.max(1, Math.round(reduced.denominator() * scale))).reduce();
    }

    /**
     * Writes a QuickTime {@code fiel} box: the field count, then the field order of an interlaced scan.
     *
     * @param writer the destination
     * @param interlacing the scan
     */
    private static void writeFields(BoxWriter writer, Interlacing interlacing) {
        writer.begin(BoxTypes.FIEL);
        writer.u8(interlacing.isInterlaced() ? 2 : 1);
        writer.u8(interlacing.isInterlaced() && interlacing != Interlacing.INTERLACED ? interlacing.code() : 0);
        writer.end();
    }

    /**
     * Writes the static high-dynamic-range metadata of a color description (ISO/IEC 14496-12): an
     * {@code mdcv} box for its mastering display and a {@code clli} box for its content light level,
     * each only when present.
     *
     * <p>The {@code mdcv} primaries are written in the order green, blue, red, as the H.265 mastering
     * display SEI message orders them, in units of 0.00002; the luminances in units of 0.0001
     * candelas per square meter. Values are rounded to the nearest unit and clamped to the field.
     *
     * @param writer the destination, inside the sample entry
     * @param color the color description
     */
    private static void writeHdrMetadata(BoxWriter writer, ColorInfo color) {
        color.masteringDisplay().ifPresent(display -> {
            writer.begin(BoxTypes.MDCV);
            writeChromaticity(writer, display.green());
            writeChromaticity(writer, display.blue());
            writeChromaticity(writer, display.red());
            writeChromaticity(writer, display.whitePoint());
            writer.u32(fixed(display.maxLuminance(), 10_000, 0xFFFFFFFFL));
            writer.u32(fixed(display.minLuminance(), 10_000, 0xFFFFFFFFL));
            writer.end();
        });
        color.contentLightLevel().ifPresent(level -> {
            writer.begin(BoxTypes.CLLI);
            writer.u16(level.maxContentLightLevel());
            writer.u16(level.maxFrameAverageLightLevel());
            writer.end();
        });
    }

    /**
     * Writes a chromaticity as two 16-bit values in units of 0.00002.
     *
     * @param writer the destination
     * @param chromaticity the coordinate
     */
    private static void writeChromaticity(BoxWriter writer, Chromaticity chromaticity) {
        writer.u16((int) fixed(chromaticity.x(), 50_000, 0xFFFF));
        writer.u16((int) fixed(chromaticity.y(), 50_000, 0xFFFF));
    }

    /**
     * Converts a non-negative real value to an unsigned fixed-point field.
     *
     * @param value the value, finite and non-negative
     * @param scale the number of field units per unit of {@code value}
     * @param max the largest value the field holds
     * @return the value times {@code scale}, rounded to the nearest integer and clamped to {@code max}
     */
    private static long fixed(double value, long scale, long max) {
        var scaled = Math.rint(value * scale);
        return scaled >= max ? max : (long) scaled;
    }

    /**
     * Writes a QuickTime visual sample entry for a ProRes or DNxHD track (Apple QuickTime File
     * Format, video sample description), followed by an {@code nclc} color box when the track
     * declares its color (with the {@code mdcv} and {@code clli} boxes of its high-dynamic-range
     * metadata), a {@code fiel} box with the declared scan (the scan of the first frame when the
     * track declares none) and a {@code pasp} box with the declared sample aspect ratio (square
     * samples when the track declares none). The depth is 32 when the first frame carries alpha (a
     * ProRes 4444 alpha channel, or the alpha flag {@code ALP} of a VC-3 coding unit), 24
     * otherwise.
     *
     * <p>The sample entry type is the track's four-character configuration; without one it is
     * {@code ap4h} or {@code apcn} for ProRes, by the chroma format of the first frame, and
     * {@code AVdh} or {@code AVdn} for DNxHD, by the header version of the first frame.
     *
     * @param writer the destination
     * @param track the track state, with the first bytes of the first frame
     * @param video the track
     * @param config the four-character configuration of the entry, or {@code null}
     */
    private static void writeQuickTimeVisual(BoxWriter writer, MuxTrack track, VideoTrack video, byte[] config) {
        var first = track.firstBytes;
        var prores = video.codec() == Codec.PRORES;
        var format = CodecConfigs.quickTimeVisualType(prores, config, first);
        var alpha = prores ? first != null && first.length > 25 && (first[25] & 0x0F) != 0
                : first != null && first.length > 7 && (first[7] & 1) != 0;
        var fieldOrder = 0;
        if (prores && first != null && first.length > 20) {
            fieldOrder = (first[20] >>> 2) & 3;
        } else if (!prores && first != null && first.length > 0x22) {
            fieldOrder = (first[0x22] & 0x04) != 0 ? 1 : 0;
        }
        writer.begin(format);
        writer.zeros(6);
        writer.u16(1);
        writer.u16(0);
        writer.u16(0);
        writer.u32(0);
        writer.u32(0x200);
        writer.u32(0x200);
        writer.u16(video.width());
        writer.u16(video.height());
        writer.u32(0x00480000);
        writer.u32(0x00480000);
        writer.u32(0);
        writer.u16(1);
        var name = compressorName(format).getBytes(StandardCharsets.US_ASCII);
        writer.u8(name.length);
        writer.bytes(name);
        writer.zeros(31 - name.length);
        writer.u16(alpha ? 32 : 24);
        writer.u16(0xFFFF);
        if (video.color().isPresent()) {
            var color = video.color().get();
            writer.begin(BoxTypes.COLR);
            writer.u32(BoxTypes.NCLC);
            writer.u16(color.primaries().code());
            writer.u16(color.transfer().code());
            writer.u16(color.matrix().code());
            writer.end();
            writeHdrMetadata(writer, color);
        }
        var scan = fieldOrder == 0 ? Interlacing.PROGRESSIVE
                : fieldOrder == 1 ? Interlacing.TOP_FIELD_FIRST : Interlacing.BOTTOM_FIELD_FIRST;
        writeFields(writer, video.interlacing().orElse(scan));
        writeAspect(writer, video.sampleAspectRatio().orElse(SQUARE));
        writer.end();
    }

    /**
     * Returns the compressor name a QuickTime sample entry carries for a ProRes or DNxHD sample
     * entry type.
     *
     * @param format the sample entry type
     * @return the name, at most 31 ASCII characters
     */
    private static String compressorName(int format) {
        return switch (format) {
            case BoxTypes.APCO -> "ProRes 422 Proxy";
            case BoxTypes.APCS -> "ProRes 422 LT";
            case BoxTypes.APCN -> "ProRes 422";
            case BoxTypes.APCH -> "ProRes 422 HQ";
            case BoxTypes.AP4H -> "ProRes 4444";
            case BoxTypes.AP4X -> "ProRes 4444 XQ";
            case BoxTypes.AVDH -> "DNxHR";
            default -> "DNxHD";
        };
    }

    /**
     * Writes a text sample entry: {@code tx3g} with the track configuration (the default 3GPP
     * description when there is none), or {@code wvtt} with a {@code vttC} box holding the
     * WebVTT header (a bare {@code WEBVTT} when there is none).
     *
     * @param writer the destination
     * @param track the text track
     */
    private static void writeText(BoxWriter writer, Track track) {
        var config = track.codecConfig().filter(bytes -> bytes.length > 0).orElse(null);
        if (track.codec() == Codec.MOV_TEXT) {
            writer.begin(SampleEntry.TX3G);
            writer.zeros(6);
            writer.u16(1);
            writer.bytes(config != null ? config : TimedText.defaultConfig());
            writer.end();
            return;
        }
        var header = WebVttText.header(config == null ? null : new String(config, StandardCharsets.UTF_8));
        writer.begin(SampleEntry.WVTT);
        writer.zeros(6);
        writer.u16(1);
        writer.begin(SampleEntry.VTT_CONFIG);
        writer.bytes(header.getBytes(StandardCharsets.UTF_8));
        writer.end();
        writer.end();
    }

    /**
     * Writes an XML subtitle sample entry ({@code stpp}, ISO/IEC 14496-30 clause 6) with the
     * track configuration, or the TTML namespace alone when there is none.
     *
     * @param writer the destination
     * @param track the TTML track
     */
    private static void writeTtml(BoxWriter writer, Track track) {
        writer.begin(SampleEntry.STPP);
        writer.zeros(6);
        writer.u16(1);
        writer.bytes(track.codecConfig().filter(bytes -> bytes.length > 0).orElseGet(TtmlEntries::defaultConfig));
        writer.end();
    }

    /**
     * Writes an audio sample entry, ISO/IEC 14496-12 clause 12.2.3, version 0.
     *
     * @param writer the destination
     * @param plan the track layout
     * @param audio the track
     * @param config the codec configuration of the entry
     */
    private static void writeAudio(BoxWriter writer, Mp4Muxer.TrackPlan plan, AudioTrack audio, byte[] config) {
        var channels = audio.layout().map(layout -> layout.channels()).orElse(0);
        var sampleRate = audio.sampleRate();
        var format = BoxTypes.MP4A;
        switch (audio.codec()) {
            case AAC -> {
                var aac = AudioConfigs.parseAac(config);
                if (channels == 0) {
                    channels = aac.channels();
                }
                if (sampleRate == 0) {
                    sampleRate = aac.sampleRate();
                }
            }
            case MP3, MP2 -> {
                var header = plan.track.firstBytes == null ? null
                        : AudioConfigs.parseMpegHeader(plan.track.firstBytes, 0, plan.track.firstBytes.length);
                if (sampleRate == 0 && header != null) {
                    sampleRate = header.sampleRate();
                }
                if (channels == 0 && header != null) {
                    channels = ((plan.track.firstBytes[3] >> 6) & 3) == 3 ? 1 : 2;
                }
            }
            case OPUS -> {
                format = BoxTypes.OPUS;
                channels = AudioConfigs.parseOpusHead(config).channels();
                sampleRate = 48000;
            }
            case ALAC -> {
                format = BoxTypes.ALAC;
                config = AudioConfigs.alacConfig(config);
                var alac = AudioConfigs.parseAlac(config);
                channels = alac.channels() > 0 ? alac.channels() : channels;
                sampleRate = alac.sampleRate() > 0 ? alac.sampleRate() : sampleRate;
            }
            case FLAC -> {
                format = BoxTypes.FLAC;
                var flac = AudioConfigs.parseFlac(config);
                channels = flac.channels();
                sampleRate = flac.sampleRate();
            }
            case AC3, EAC3 -> {
                var enhanced = audio.codec() == Codec.EAC3;
                format = enhanced ? BoxTypes.EC3 : BoxTypes.AC3;
                var first = plan.track.firstBytes;
                var frame = first == null ? null : Ac3Configs.parse(first, 0, first.length);
                var stream = frame == null ? null : Ac3Configs.describe(first, 0, first.length);
                config = stream == null ? null : enhanced ? Ac3Configs.dec3(first, 0, first.length) : Ac3Configs.dac3(frame);
                if (config == null) {
                    config = Ac3Configs.declared(enhanced, sampleRate, audio.layout().orElse(null));
                } else {
                    channels = stream.layout().channels();
                    sampleRate = frame.sampleRate();
                }
            }
            case AMR_NB, AMR_WB -> {
                var wideband = audio.codec() == Codec.AMR_WB;
                format = wideband ? BoxTypes.SAWB : BoxTypes.SAMR;
                channels = 1;
                sampleRate = wideband ? 16000 : 8000;
                config = AmrConfigs.damr(wideband, plan.track.amrModes);
            }
            default -> throw new IllegalStateException("unsupported codec " + audio.codec());
        }
        writer.begin(format);
        writer.zeros(6);
        writer.u16(1);
        writer.zeros(8);
        writer.u16(Math.max(channels, 1));
        writer.u16(audio.codec() == Codec.ALAC && audio.bitsPerSample() > 0 ? audio.bitsPerSample() : 16);
        writer.u32(0);
        writer.u32(sampleRate > 0 && sampleRate <= 0xFFFF ? (long) sampleRate << 16 : 0);
        switch (audio.codec()) {
            case AAC, MP3, MP2 -> {
                var objectType = audio.codec() == Codec.AAC ? EsDescriptor.MPEG4_AUDIO
                        : sampleRate >= 32000 || sampleRate == 0 ? EsDescriptor.MPEG1_AUDIO : EsDescriptor.MPEG2_AUDIO;
                var descriptor = new EsDescriptor(objectType, EsDescriptor.AUDIO_STREAM, maxSampleSize(plan),
                        maxBitrate(plan), averageBitrate(plan), audio.codec() == Codec.AAC ? config : null);
                writer.beginFull(BoxTypes.ESDS, 0, 0);
                descriptor.write(writer, plan.track.number);
                writer.end();
            }
            case OPUS -> {
                writer.begin(BoxTypes.DOPS);
                writer.bytes(AudioConfigs.dopsFromOpusHead(config));
                writer.end();
            }
            case ALAC -> {
                writer.beginFull(BoxTypes.ALAC, 0, 0);
                writer.bytes(config);
                writer.end();
            }
            case FLAC -> {
                writer.beginFull(BoxTypes.DFLA, 0, 0);
                var blocks = AudioConfigs.flacContainerConfig(config);
                writer.bytes(Arrays.copyOfRange(blocks, 4, blocks.length));
                writer.end();
            }
            case AMR_NB, AMR_WB -> {
                writer.begin(BoxTypes.DAMR);
                writer.bytes(config);
                writer.end();
            }
            case AC3, EAC3 -> {
                writer.begin(audio.codec() == Codec.EAC3 ? BoxTypes.DEC3 : BoxTypes.DAC3);
                writer.bytes(config);
                writer.end();
            }
            default -> {
            }
        }
        writer.end();
    }

    /**
     * Returns the largest sample of a track.
     *
     * @param plan the track layout
     * @return the size in bytes, capped to 24 bits
     */
    private static int maxSampleSize(Mp4Muxer.TrackPlan plan) {
        var max = 0;
        for (var index = 0; index < plan.track.count; index++) {
            max = Math.max(max, plan.track.sizes[index]);
        }
        return Math.min(max, 0xFFFFFF);
    }

    /**
     * Returns the average bit rate of a track.
     *
     * @param plan the track layout
     * @return the rate in bits per second, or {@code 0} for an empty or instantaneous track
     */
    private static long averageBitrate(Mp4Muxer.TrackPlan plan) {
        if (plan.mediaDuration <= 0) {
            return 0;
        }
        var bytes = 0L;
        for (var index = 0; index < plan.track.count; index++) {
            bytes += plan.track.sizes[index];
        }
        return Math.min(0xFFFFFFFFL, bytes * 8 * plan.track.timescale / plan.mediaDuration);
    }

    /**
     * Returns the peak bit rate of a track over any one-second window of decoding time.
     *
     * @param plan the track layout
     * @return the rate in bits per second
     */
    private static long maxBitrate(Mp4Muxer.TrackPlan plan) {
        var track = plan.track;
        var window = track.timescale;
        var best = 0L;
        var sum = 0L;
        var start = 0;
        for (var end = 0; end < track.count; end++) {
            sum += track.sizes[end];
            while (track.dts[end] - track.dts[start] >= window) {
                sum -= track.sizes[start++];
            }
            best = Math.max(best, sum);
        }
        return Math.min(0xFFFFFFFFL, best * 8);
    }
}
