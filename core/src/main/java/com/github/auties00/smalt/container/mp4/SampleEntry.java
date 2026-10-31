package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.internal.RawVideoTags;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.container.internal.SphericalBoxes;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.DtsConfigs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.internal.codec.DolbyVisionConfigs;
import com.github.auties00.smalt.internal.codec.Jp2Frames;
import com.github.auties00.smalt.internal.header.OpusHeader;
import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.internal.heif.DataReferences;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.subtitle.QuickTimeText;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DolbyVision;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.SphericalVideo;
import com.github.auties00.smalt.track.StereoMode;
import com.github.auties00.smalt.track.TrackKind;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Describes one sample description of a track: the codec, its configuration, and the picture or
 * sound parameters the sample entry declares.
 *
 * <p>{@link #parseAll(BoxCursor, int, MediaLimits, List, DataReferences, List, List)} reads the {@code stsd} box.
 * Visual entries follow ISO/IEC 14496-12 clause 12.1.3, audio entries clause 12.2.3 together with
 * the QuickTime sound description versions 1 and 2, and codec-specific child boxes follow their bindings (ISO/IEC
 * 14496-14 {@code esds}, 14496-15 {@code avcC}/{@code hvcC}, the Opus, FLAC, VP and AV1 ISO base
 * media encapsulations, 3GPP TS 26.244 for AMR and H.263, ISO/IEC 23003-5 for PCM, and Dolby's "Dolby Vision
 * Streams Within the ISO Base Media File Format" for the {@code dvh1}, {@code dvhe}, {@code dva1}, {@code dvav}
 * and {@code dav1} entries, which are their base codec's, and for the {@code dvcC}, {@code dvvC} and
 * {@code dvwC} boxes any visual entry may hold).
 *
 * @param format the sample entry type
 * @param codec the identified codec
 * @param config the codec configuration in the form
 * {@link com.github.auties00.smalt.track.Track#codecConfig()} documents, or {@code null} when there is
 * none
 * @param width the coded width of a visual entry, or {@code 0}
 * @param height the coded height of a visual entry, or {@code 0}
 * @param color the color description of a visual entry ({@code colr}, with the {@code mdcv} and
 * {@code clli} metadata when present; unspecified matrix, primaries and transfer in the limited
 * range when only {@code mdcv} or {@code clli} is present), or {@code null} without any of them
 * @param sampleAspect the sample aspect ratio of a visual entry ({@code pasp}), or {@code null} without a valid
 * {@code pasp} box
 * @param interlacing the scan of a visual entry (QuickTime {@code fiel}), or {@code null} without a {@code fiel} box
 * @param spherical the spherical projection of a visual entry (Spherical Video V2 {@code sv3d}), or {@code null}
 * without a valid {@code sv3d} box
 * @param stereoMode the stereo mode of a visual entry (Spherical Video V2 {@code st3d}), or {@code null} without a
 * valid {@code st3d} box
 * @param dolbyVision the Dolby Vision configuration of an H.264, HEVC or AV1 entry ({@code dvcC}, {@code dvvC} or
 * {@code dvwC}), or {@code null} without a valid one
 * @param channels the channel count of an audio entry, or {@code 0}
 * @param sampleRate the sampling rate of an audio entry in hertz, or {@code 0}
 * @param layout the declared channel layout of an audio entry, or {@code null}
 * @param bitsPerSample the sample size of a PCM entry, or {@code 0}
 * @param frameBytes the size of one PCM frame (one sample of every channel), or {@code 0}
 * @param protection the protection of an encrypted entry ({@code encv}, {@code enca}), whose {@code format} is then
 * the type of the clear entry, or {@code null} for a clear entry
 */
record SampleEntry(int format, Codec codec, byte[] config, int width, int height, ColorInfo color,
                   Rational sampleAspect, Interlacing interlacing, SphericalVideo spherical, StereoMode stereoMode,
                   DolbyVision dolbyVision, int channels, int sampleRate, ChannelLayout layout, int bitsPerSample,
                   int frameBytes, TrackProtection protection) {
    /**
     * The size of the fields of a visual sample entry that precede its child boxes.
     */
    private static final int VISUAL_HEADER = 78;

    /**
     * The size of the fields of an audio sample entry that precede its child boxes.
     */
    private static final int AUDIO_HEADER = 28;

    /**
     * The QuickTime {@code kAudioChannelLayoutTag_UseChannelDescriptions} tag.
     */
    private static final int LAYOUT_USE_DESCRIPTIONS = 0;

    /**
     * The QuickTime {@code kAudioChannelLayoutTag_UseChannelBitmap} tag.
     */
    private static final int LAYOUT_USE_BITMAP = 1 << 16;

    /**
     * The 3GPP timed text sample entry type ({@code tx3g}).
     */
    static final int TX3G = 0x74783367;

    /**
     * The WebVTT sample entry type ({@code wvtt}).
     */
    static final int WVTT = 0x77767474;

    /**
     * The XML subtitle sample entry type of TTML ({@code stpp}).
     */
    static final int STPP = 0x73747070;

    /**
     * The QuickTime text media sample entry type ({@code text}).
     */
    static final int TEXT = 0x74657874;

    /**
     * The WebVTT configuration box type ({@code vttC}).
     */
    static final int VTT_CONFIG = 0x76747443;

    /**
     * The MPEG-4 systems sample entry type ({@code mp4s}), which carries DVD subpictures with object type
     * {@code 0xE0}.
     */
    static final int MP4S = 0x6D703473;

    /**
     * The QuickTime CEA-608 closed caption sample entry type ({@code c608}).
     */
    static final int C608 = 0x63363038;

    /**
     * The QuickTime CEA-708 closed caption sample entry type ({@code c708}).
     */
    static final int C708 = 0x63373038;

    /**
     * The {@code objectTypeIndication} of DVD subpictures.
     */
    static final int VOBSUB_OBJECT_TYPE = 0xE0;

    /**
     * The speakers of the ISO/IEC 23091-3 (formerly 23001-8) {@code OutputChannelPosition} codes 0
     * to 31, as used by the {@code chnl} box: the surround pair (Ls, Rs) and the side surround pairs
     * are the side speakers, the rear surround pair (Lsr, Rsr) the back speakers; codes 30 and 31
     * have no speaker.
     */
    private static final Speaker[] ISO_POSITIONS = {
            Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
            Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER,
            Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.BACK_CENTER, Speaker.SURROUND_DIRECT_LEFT,
            Speaker.SURROUND_DIRECT_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.WIDE_LEFT,
            Speaker.WIDE_RIGHT, Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT, Speaker.TOP_FRONT_CENTER,
            Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_RIGHT, Speaker.TOP_BACK_CENTER, Speaker.TOP_SIDE_LEFT,
            Speaker.TOP_SIDE_RIGHT, Speaker.TOP_CENTER, Speaker.LOW_FREQUENCY_2, Speaker.BOTTOM_FRONT_LEFT,
            Speaker.BOTTOM_FRONT_RIGHT, Speaker.BOTTOM_FRONT_CENTER, Speaker.UNKNOWN, Speaker.UNKNOWN
    };

    /**
     * The largest number of sample descriptions read from one {@code stsd} box; a sample that
     * references a later one, like any sample description index out of range, uses the first.
     */
    static final int MAX_DESCRIPTIONS = 1024;

    /**
     * Parses every sample description of an {@code stsd} box, up to {@value #MAX_DESCRIPTIONS}.
     *
     * <p>The first description must parse: its failure is thrown. A later description that fails
     * leaves {@code null} at its index and the failure at the same index of {@code errors}, so that
     * only the samples using it fail.
     *
     * @param stsd the payload of the {@code stsd} box
     * @param handler the track's handler type
     * @param limits the limits bounding picture sizes and channel counts
     * @param references the data references of the track, or {@code null} when the track has no {@code dref}
     * @param external the opener of the files the data references name
     * @param errors receives the failure of each description, {@code null} for one that parsed
     * @param sources receives the source holding the samples of each description, {@code null} for the file
     * itself or for a description that failed
     * @return the descriptions, in the order of their one-based sample description index
     * @throws MediaException.Malformed if the box holds no entry or the first is truncated
     * @throws MediaException.Unsupported if the first entry is encrypted with a scheme other than the Common
     * Encryption ones or stored in another file that no resolver supplies
     * @throws MediaException.LimitExceeded if the first entry's channel count exceeds the limits
     */
    static SampleEntry[] parseAll(BoxCursor stsd, int handler, MediaLimits limits,
                                  List<DataReferences.Entry> references, DataReferences external,
                                  List<MediaException> errors, List<MediaSource> sources) {
        stsd.fullBoxHeader();
        var count = stsd.u32();
        if (count == 0 || !stsd.hasBox()) {
            throw new MediaException.Malformed("stsd holds no sample description");
        }
        var kind = switch (handler) {
            case BoxTypes.VIDE, BoxTypes.PICT, BoxTypes.AUXV -> TrackKind.VIDEO;
            case BoxTypes.SOUN -> TrackKind.AUDIO;
            default -> TrackKind.DATA;
        };
        var entries = new ArrayList<SampleEntry>();
        while (entries.size() < Math.min(count, MAX_DESCRIPTIONS)) {
            BoxCursor entry;
            try {
                if (!stsd.hasBox()) {
                    break;
                }
                entry = stsd.nextBox();
            } catch (MediaException.Malformed exception) {
                if (entries.isEmpty()) {
                    throw exception;
                }
                break;
            }
            try {
                var source = dataSource(entry, references, external);
                var description = switch (kind) {
                    case VIDEO -> parseVisual(entry.type(), entry);
                    case AUDIO -> parseAudio(entry.type(), entry, limits);
                    case DATA -> parseData(entry.type(), entry);
                };
                entries.add(description);
                errors.add(null);
                sources.add(source);
            } catch (MediaException exception) {
                if (entries.isEmpty()) {
                    throw exception;
                }
                entries.add(null);
                errors.add(exception);
                sources.add(null);
            }
        }
        return entries.toArray(new SampleEntry[0]);
    }

    /**
     * Returns the source holding the samples of a sample entry, through the data reference the entry names.
     *
     * <p>An index of {@code 0} or past the last entry of the {@code dref} box, as some writers leave it, names
     * the file itself.
     *
     * @param entry a cursor over the entry payload, not moved
     * @param references the data references of the track, or {@code null}
     * @param external the opener of the referenced files
     * @return the source, or {@code null} when the samples are in the file itself
     * @throws MediaException.Unsupported if the samples are stored in another file that no resolver supplies
     */
    private static MediaSource dataSource(BoxCursor entry, List<DataReferences.Entry> references,
                                          DataReferences external) {
        if (references == null || entry.remaining() < 8) {
            return null;
        }
        var cursor = entry.duplicate();
        cursor.skip(6);
        var index = cursor.u16();
        if (index < 1 || index > references.size()) {
            return null;
        }
        return external.open(references.get(index - 1), "mp4 external data reference",
                "the samples of a '" + FourCc.printable(entry.type()) + "' description");
    }

    /**
     * Returns whether the samples of another description of the same track can follow the
     * samples of this one, signaled to a decoder by at most a new codec configuration.
     *
     * <p>The codec must be the same; an audio description without a codec configuration must
     * also keep the sampling rate and channel count, and a description with a configuration
     * cannot be followed by one without.
     *
     * @param other the other description
     * @return {@code true} when a decoder of this description can continue with {@code other}
     */
    boolean canSwitchTo(SampleEntry other) {
        if (other.codec != codec) {
            return false;
        }
        if (other.config == null) {
            return config == null && (codec.kind() != TrackKind.AUDIO
                    || (other.sampleRate == sampleRate && other.channels == channels && other.frameBytes == frameBytes));
        }
        return true;
    }

    /**
     * Returns a copy of this description with another codec.
     *
     * @param replacement the codec
     * @return the modified description
     */
    SampleEntry withCodec(Codec replacement) {
        return new SampleEntry(format, replacement, config, width, height, color, sampleAspect, interlacing, spherical,
                stereoMode, dolbyVision, channels, sampleRate, layout, bitsPerSample, frameBytes, protection);
    }

    /**
     * Returns a copy of this description with another codec configuration.
     *
     * @param replacement the configuration, or {@code null} for none
     * @return the modified description
     */
    SampleEntry withConfig(byte[] replacement) {
        return new SampleEntry(format, codec, replacement, width, height, color, sampleAspect, interlacing, spherical,
                stereoMode, dolbyVision, channels, sampleRate, layout, bitsPerSample, frameBytes, protection);
    }

    /**
     * Returns whether the samples of an H.264 track with this description are reconstructed implicitly
     * from the tracks it references (ISO/IEC 14496-15): an {@code svc1}, {@code mvc1} or {@code mvc3}
     * entry, whose access units are the time-aligned samples of the referenced tracks followed by its own.
     *
     * @return {@code true} for implicit reconstruction, {@code false} when extractors name the data
     * taken from other tracks
     */
    boolean implicitReconstruction() {
        return format == BoxTypes.SVC1 || format == BoxTypes.MVC1 || format == BoxTypes.MVC3;
    }

    /**
     * Returns the kind of track this entry describes.
     *
     * @return the kind implied by the codec
     */
    TrackKind kind() {
        return codec.kind();
    }

    /**
     * Parses a visual sample entry.
     *
     * @param format the entry type
     * @param payload the entry payload
     * @return the description
     */
    private static SampleEntry parseVisual(int format, BoxCursor payload) {
        if (payload.remaining() < VISUAL_HEADER) {
            throw new MediaException.Malformed("visual sample entry '" + FourCc.printable(format) + "' is truncated");
        }
        payload.skip(24);
        var width = payload.u16();
        var height = payload.u16();
        payload.skip(46);
        var depth = payload.u16();
        var colorTableId = payload.s16();
        var indexed = depth == 1 || depth == 2 || depth == 4 || depth == 8;
        var colorTable = colorTableId == 0 && indexed ? parseColorTable(payload) : null;
        var sinfs = new ArrayList<BoxCursor>();
        byte[] avcC = null, hvcC = null, lhvC = null, vvcC = null, vpcC = null, av1C = null, jp2h = null, glbl = null,
                dvc1 = null;
        var layerConfigs = new ArrayList<byte[]>();
        EsDescriptor esds = null;
        ColorInfo color = null;
        MasteringDisplay mastering = null;
        ContentLightLevel lightLevel = null;
        Rational sampleAspect = null;
        Interlacing interlacing = null;
        SphericalVideo spherical = null;
        StereoMode stereoMode = null;
        DolbyVision dolbyVision = null;
        ByteArrayOutputStream avid = null;
        for (var box : children(payload)) {
            var body = box;
            switch (box.type()) {
                case BoxTypes.AVCC -> avcC = body.rest();
                case BoxTypes.SVCC, BoxTypes.MVCC -> layerConfigs.add(body.rest());
                case BoxTypes.HVCC -> hvcC = body.rest();
                case BoxTypes.LHVC -> lhvC = body.rest();
                case BoxTypes.VVCC -> {
                    if (body.remaining() >= 4 && body.fullBoxHeader() == 0) {
                        vvcC = body.rest();
                    }
                }
                case BoxTypes.VPCC -> {
                    if (body.remaining() >= 4) {
                        body.skip(4);
                        vpcC = body.rest();
                    }
                }
                case BoxTypes.AV1C -> av1C = body.rest();
                case BoxTypes.GLBL -> glbl = body.rest();
                case BoxTypes.DVC1 -> dvc1 = body.rest();
                case BoxTypes.JP2H -> jp2h = withHeader(BoxTypes.JP2H, body.rest());
                case BoxTypes.ESDS -> esds = parseEsds(body);
                case BoxTypes.COLR -> {
                    if (color == null) {
                        color = parseColor(body);
                    }
                }
                case BoxTypes.MDCV -> mastering = parseMastering(body);
                case BoxTypes.CLLI -> lightLevel = parseLightLevel(body);
                case BoxTypes.PASP -> sampleAspect = parseAspect(body);
                case BoxTypes.FIEL -> interlacing = parseFields(body);
                case BoxTypes.ST3D -> {
                    if (body.remaining() >= 5 && body.fullBoxHeader() >>> 24 == 0) {
                        stereoMode = SphericalBoxes.stereoMode(body.u8());
                    }
                }
                case BoxTypes.SV3D -> spherical = parseSpherical(body);
                case BoxTypes.DVCC, BoxTypes.DVVC, BoxTypes.DVWC -> {
                    if (dolbyVision == null) {
                        var record = body.rest();
                        dolbyVision = DolbyVisionConfigs.parseRecord(record, 0, record.length);
                    }
                }
                case BoxTypes.SINF -> sinfs.add(body);
                default -> {
                    if (CodecConfigs.isAvidExtension(box.type())) {
                        if (avid == null) {
                            avid = new ByteArrayOutputStream();
                        }
                        var rest = body.rest();
                        avid.writeBytes(fourCc(rest.length + 8));
                        avid.writeBytes(fourCc(box.type()));
                        avid.writeBytes(rest);
                    }
                }
            }
        }
        TrackProtection protection = null;
        if (format == BoxTypes.ENCV) {
            protection = TrackProtection.parse(sinfs, format);
            format = protection.originalFormat();
        }
        if (color == null && (mastering != null || lightLevel != null)) {
            color = new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.LIMITED, ColorPrimaries.UNSPECIFIED,
                    TransferCharacteristics.UNSPECIFIED);
        }
        if (color != null) {
            color = color.withMasteringDisplay(mastering).withContentLightLevel(lightLevel);
        }
        Codec codec;
        byte[] config = null;
        switch (format) {
            case BoxTypes.AVC1, BoxTypes.AVC2, BoxTypes.AVC3, BoxTypes.AVC4, BoxTypes.SVC1, BoxTypes.SVC2,
                 BoxTypes.MVC1, BoxTypes.MVC2, BoxTypes.MVC3, BoxTypes.MVC4, BoxTypes.DVA1, BoxTypes.DVAV -> {
                codec = Codec.H264;
                config = layerConfigs.isEmpty() ? avcC : NalRecords.mergeAvc(avcC, layerConfigs);
            }
            case BoxTypes.HVC1, BoxTypes.HEV1, BoxTypes.DVH1, BoxTypes.DVHE -> {
                codec = Codec.HEVC;
                // the parameter sets of the other layers of a multi-layer track join the base layer's
                config = hvcC != null && lhvC != null ? NalRecords.appendLayeredHevc(hvcC, lhvC) : hvcC;
            }
            case BoxTypes.VVC1, BoxTypes.VVI1 -> {
                codec = Codec.VVC;
                config = vvcC;
            }
            case BoxTypes.VP08 -> {
                codec = Codec.VP8;
                config = vpcC;
            }
            case BoxTypes.VP09 -> {
                codec = Codec.VP9;
                config = vpcC;
            }
            case BoxTypes.AV01, BoxTypes.DAV1 -> {
                codec = Codec.AV1;
                config = av1C;
            }
            case BoxTypes.MP4V -> {
                var objectType = esds == null ? 0x20 : esds.objectType();
                codec = switch (objectType) {
                    case 0x21 -> Codec.H264;
                    case 0x6C -> Codec.MJPEG;
                    case 0x6E -> Codec.JPEG2000;
                    case 0x20 -> Codec.MPEG4_PART2;
                    case 0x60, 0x61, 0x62, 0x63, 0x64, 0x65 -> Codec.MPEG2_VIDEO;
                    case 0x6A -> Codec.MPEG1_VIDEO;
                    default -> Codec.UNKNOWN_VIDEO;
                };
                config = esds == null ? null : esds.specificInfo();
            }
            case BoxTypes.VC_1 -> {
                codec = Codec.VC1;
                config = vc1Config(dvc1);
            }
            case BoxTypes.S263, BoxTypes.H263 -> codec = Codec.H263;
            case BoxTypes.JPEG, BoxTypes.MJPA, BoxTypes.MJPB -> codec = Codec.MJPEG;
            case BoxTypes.MJP2 -> {
                codec = Codec.JPEG2000;
                config = Jp2Frames.isHeader(jp2h) ? jp2h : null;
            }
            case BoxTypes.APCH, BoxTypes.APCN, BoxTypes.APCS, BoxTypes.APCO, BoxTypes.AP4H, BoxTypes.AP4X -> {
                codec = Codec.PRORES;
                config = fourCc(format);
            }
            case BoxTypes.FFV1 -> {
                codec = Codec.FFV1;
                config = glbl;
            }
            case BoxTypes.AVDN, BoxTypes.AVDH -> {
                codec = Codec.DNXHD;
                var extensions = avid == null ? new byte[0] : avid.toByteArray();
                config = Arrays.copyOf(fourCc(format), 4 + extensions.length);
                System.arraycopy(extensions, 0, config, 4, extensions.length);
            }
            default -> {
                var raw = RawVideoTags.sampleEntry(FourCc.printable(format), depth, colorTable);
                var mpegVideo = mpegVideo(format);
                if (mpegVideo != null) {
                    codec = mpegVideo;
                    config = esds == null ? null : esds.specificInfo();
                } else if (raw != null) {
                    codec = Codec.RAW_VIDEO;
                    config = raw.toCodecConfig();
                    if (color == null && format == BoxTypes.J420) {
                        color = ColorInfo.BT601_LIMITED.withRange(ColorRange.FULL);
                    }
                } else {
                    // TODO: ISO/IEC 23001-17 uncompressed video ('uncv' with its 'uncC' and 'cmpd' boxes)
                    codec = Codec.UNKNOWN_VIDEO;
                }
            }
        }
        return new SampleEntry(format, codec, config, width, height, color, sampleAspect, interlacing, spherical,
                stereoMode, codec == Codec.HEVC || codec == Codec.H264 || codec == Codec.AV1 ? dolbyVision : null, 0,
                0, null, 0, 0, protection);
    }

    /**
     * Returns the VC-1 configuration a {@code dvc1} box carries (SMPTE RP 2025 section 8): after the byte of profile
     * and level, the {@code STRUCT_C} of the simple and main profiles (profile 0 and 4), or, for the advanced profile
     * (12), the sequence header and entry point that follow the 6 bytes of flags and frame rate, from their first
     * start code.
     *
     * @param dvc1 the box payload, or {@code null}
     * @return the configuration, or {@code null} when there is none
     */
    static byte[] vc1Config(byte[] dvc1) {
        if (dvc1 == null || dvc1.length == 0) {
            return null;
        }
        var profile = (dvc1[0] & 0xFF) >>> 4;
        if (profile == 0 || profile == 4) {
            return dvc1.length >= 5 ? Arrays.copyOfRange(dvc1, 1, 5) : null;
        }
        for (var index = 1; index + 3 < dvc1.length; index++) {
            if (dvc1[index] == 0 && dvc1[index + 1] == 0 && dvc1[index + 2] == 1 && (dvc1[index + 3] & 0xFF) == 0x0F) {
                return Arrays.copyOfRange(dvc1, index, dvc1.length);
            }
        }
        return null;
    }

    /**
     * Returns the codec of a QuickTime sample entry type of MPEG-1 or MPEG-2 video: {@code m1v }, {@code m1v1},
     * {@code mp1v} and {@code mpeg} for MPEG-1; {@code m2v1}, {@code mp2v}, the HDV types {@code hdv1} to {@code hdv9}
     * and {@code hdva}, the XDCAM HD types {@code xdv1} to {@code xdvf}, {@code xdhd} and {@code xdh2}, the XDCAM
     * HD422 types {@code xd51} to {@code xd5f}, the IMX (D-10) types {@code mx3n}, {@code mx3p}, {@code mx4n},
     * {@code mx4p}, {@code mx5n} and {@code mx5p}, Avid's {@code AVmp} and Matrox's {@code mmes} and {@code MMES}
     * for MPEG-2.
     *
     * @param format the sample entry type
     * @return the codec, or {@code null} for another type
     */
    static Codec mpegVideo(int format) {
        var name = FourCc.printable(format);
        return switch (name) {
            case "m1v ", "m1v1", "mp1v", "mpeg" -> Codec.MPEG1_VIDEO;
            case "m2v1", "mp2v", "hdva", "xdhd", "xdh2", "mx3n", "mx3p", "mx4n", "mx4p", "mx5n", "mx5p", "AVmp", "mmes",
                 "MMES" -> Codec.MPEG2_VIDEO;
            default -> {
                var family = name.length() == 4 ? name.substring(0, 3) : "";
                var last = name.length() == 4 ? name.charAt(3) : ' ';
                if (family.equals("hdv") && last >= '1' && last <= '9'
                        || (family.equals("xdv") || family.equals("xd5"))
                        && (last >= '1' && last <= '9' || last >= 'a' && last <= 'f')) {
                    yield Codec.MPEG2_VIDEO;
                }
                yield null;
            }
        };
    }

    /**
     * Parses a Spherical Video V2 {@code sv3d} box: the metadata source of its {@code svhd} box and the pose and
     * projection of its {@code proj} box.
     *
     * @param body the box payload
     * @return the projection, or {@code null} when the box has no {@code proj} box with a known projection, or a
     * pose out of range
     */
    private static SphericalVideo parseSpherical(BoxCursor body) {
        var source = "";
        SphericalVideo.Projection projection = null;
        var pose = new int[3];
        for (var child : children(body)) {
            if (child.type() == BoxTypes.SVHD && child.remaining() >= 4 && child.fullBoxHeader() >>> 24 == 0) {
                var bytes = child.rest();
                var length = 0;
                while (length < bytes.length && bytes[length] != 0) {
                    length++;
                }
                source = new String(bytes, 0, length, StandardCharsets.UTF_8);
            } else if (child.type() == BoxTypes.PROJ) {
                for (var part : children(child)) {
                    var type = switch (part.type()) {
                        case BoxTypes.EQUI -> SphericalBoxes.EQUIRECTANGULAR;
                        case BoxTypes.CBMP -> SphericalBoxes.CUBEMAP;
                        case BoxTypes.MSHP -> SphericalBoxes.MESH;
                        default -> 0;
                    };
                    if (part.type() == BoxTypes.PRHD && part.remaining() >= 16 && part.fullBoxHeader() >>> 24 == 0) {
                        pose[0] = part.s32();
                        pose[1] = part.s32();
                        pose[2] = part.s32();
                    } else if (type != 0 && projection == null) {
                        var bytes = part.rest();
                        projection = SphericalBoxes.parse(type, bytes, 0, bytes.length);
                    }
                }
            }
        }
        return SphericalBoxes.spherical(projection, SphericalBoxes.degrees(pose[0]), SphericalBoxes.degrees(pose[1]),
                SphericalBoxes.degrees(pose[2]), source);
    }

    /**
     * Parses the QuickTime color table that follows a visual sample entry of 1, 2, 4 or 8-bit indices whose color
     * table identifier is zero: a seed, flags, the number of entries minus one, then each entry's index and 16-bit
     * red, green and blue.
     *
     * @param payload the entry payload, positioned at the table; left after it
     * @return the palette as {@code 0xAARRGGBB}, opaque, indexed by position, or {@code null} for a truncated table
     */
    private static int[] parseColorTable(BoxCursor payload) {
        if (payload.remaining() < 8) {
            return null;
        }
        payload.skip(6);
        var count = payload.u16() + 1;
        if (count > 256 || payload.remaining() < 8L * count) {
            return null;
        }
        var palette = new int[count];
        for (var i = 0; i < count; i++) {
            payload.skip(2);
            var red = payload.u16() >>> 8;
            var green = payload.u16() >>> 8;
            var blue = payload.u16() >>> 8;
            palette[i] = 0xFF000000 | red << 16 | green << 8 | blue;
        }
        return palette;
    }

    /**
     * Rebuilds a box from its type and contents.
     *
     * @param type the box type
     * @param contents the box contents
     * @return the box, with a 32-bit size field
     */
    private static byte[] withHeader(int type, byte[] contents) {
        var box = new byte[8 + contents.length];
        var size = box.length;
        box[0] = (byte) (size >>> 24);
        box[1] = (byte) (size >>> 16);
        box[2] = (byte) (size >>> 8);
        box[3] = (byte) size;
        box[4] = (byte) (type >>> 24);
        box[5] = (byte) (type >>> 16);
        box[6] = (byte) (type >>> 8);
        box[7] = (byte) type;
        System.arraycopy(contents, 0, box, 8, contents.length);
        return box;
    }

    /**
     * Returns a sample entry type as four ASCII bytes, the codec configuration of ProRes tracks and
     * the start of that of DNxHD tracks, or a 32-bit size as four big-endian bytes.
     *
     * @param format the sample entry type
     * @return the four bytes, most significant first
     */
    private static byte[] fourCc(int format) {
        return new byte[]{(byte) (format >>> 24), (byte) (format >>> 16), (byte) (format >>> 8), (byte) format};
    }

    /**
     * Parses an {@code esds} box leniently.
     *
     * @param body the box payload
     * @return the descriptor, or {@code null} when it cannot be parsed
     */
    private static EsDescriptor parseEsds(BoxCursor body) {
        try {
            return EsDescriptor.parse(body);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Parses a {@code pasp} box (ISO/IEC 14496-12 clause 12.1.4).
     *
     * @param body the box payload
     * @return {@code hSpacing / vSpacing} in lowest terms, or {@code null} for a truncated box or a zero spacing
     */
    private static Rational parseAspect(BoxCursor body) {
        if (body.remaining() < 8) {
            return null;
        }
        var horizontal = body.u32();
        var vertical = body.u32();
        return horizontal > 0 && vertical > 0 ? new Rational(horizontal, vertical).reduce() : null;
    }

    /**
     * Parses a QuickTime {@code fiel} box: a field count of 1 (progressive) or 2, then the field
     * order of an interlaced entry.
     *
     * @param body the box payload
     * @return the scan, or {@code null} for a truncated box or another field count
     */
    private static Interlacing parseFields(BoxCursor body) {
        if (body.remaining() < 2) {
            return null;
        }
        var count = body.u8();
        var order = body.u8();
        return switch (count) {
            case 1 -> Interlacing.PROGRESSIVE;
            case 2 -> order == 0 ? Interlacing.INTERLACED : Interlacing.ofCode(order);
            default -> null;
        };
    }

    /**
     * Parses a {@code colr} box.
     *
     * @param body the box payload
     * @return the color description, or {@code null} for an ICC profile or a truncated box
     */
    private static ColorInfo parseColor(BoxCursor body) {
        if (body.remaining() < 10) {
            return null;
        }
        var type = body.s32();
        if (type != BoxTypes.NCLX && type != BoxTypes.NCLC) {
            return null;
        }
        var primaries = body.u16();
        var transfer = body.u16();
        var matrix = body.u16();
        var full = type == BoxTypes.NCLX && body.remaining() >= 1 && (body.u8() & 0x80) != 0;
        return new ColorInfo(MatrixCoefficients.of(matrix), full ? ColorRange.FULL : ColorRange.LIMITED,
                ColorPrimaries.of(primaries), TransferCharacteristics.of(transfer));
    }

    /**
     * Parses an {@code mdcv} box (ISO/IEC 23001-8), whose primaries are stored in the order green,
     * blue, red as in the H.265 SEI message, in units of 0.00002, and whose luminances are in
     * units of 0.0001 candelas per square meter.
     *
     * @param body the box payload
     * @return the mastering display, or {@code null} for a truncated box
     */
    private static MasteringDisplay parseMastering(BoxCursor body) {
        if (body.remaining() < 24) {
            return null;
        }
        var green = new Chromaticity(body.u16() * 0.00002, body.u16() * 0.00002);
        var blue = new Chromaticity(body.u16() * 0.00002, body.u16() * 0.00002);
        var red = new Chromaticity(body.u16() * 0.00002, body.u16() * 0.00002);
        var white = new Chromaticity(body.u16() * 0.00002, body.u16() * 0.00002);
        var max = body.u32() * 0.0001;
        var min = body.u32() * 0.0001;
        return new MasteringDisplay(red, green, blue, white, max, min);
    }

    /**
     * Parses a {@code clli} box.
     *
     * @param body the box payload
     * @return the content light level, or {@code null} for a truncated box
     */
    private static ContentLightLevel parseLightLevel(BoxCursor body) {
        if (body.remaining() < 4) {
            return null;
        }
        return new ContentLightLevel(body.u16(), body.u16());
    }

    /**
     * Parses an audio sample entry, including the QuickTime sound description versions 1 and 2.
     *
     * @param format the entry type
     * @param payload the entry payload
     * @param limits the limits bounding the channel count
     * @return the description
     */
    private static SampleEntry parseAudio(int format, BoxCursor payload, MediaLimits limits) {
        if (payload.remaining() < AUDIO_HEADER) {
            throw new MediaException.Malformed("audio sample entry '" + FourCc.printable(format) + "' is truncated");
        }
        payload.skip(8);
        var version = payload.u16();
        payload.skip(6);
        var channels = payload.u16();
        var sampleSize = payload.u16();
        payload.skip(4);
        var sampleRate = (int) (payload.u32() >>> 16);
        var bytesPerFrame = 0;
        var lpcmFlags = -1;
        if (version == 1 && payload.remaining() >= 16 && !looksLikeBox(payload)) {
            payload.skip(8);
            bytesPerFrame = (int) Math.min(payload.u32(), Integer.MAX_VALUE);
            payload.skip(4);
        } else if (version == 2 && payload.remaining() >= 36) {
            payload.skip(4);
            var rate = Double.longBitsToDouble(payload.u64());
            sampleRate = rate > 0 && rate < Integer.MAX_VALUE ? (int) Math.round(rate) : 0;
            channels = (int) Math.min(payload.u32(), Integer.MAX_VALUE);
            payload.skip(4);
            sampleSize = (int) Math.min(payload.u32(), 1024);
            lpcmFlags = payload.s32();
            bytesPerFrame = (int) Math.min(payload.u32(), Integer.MAX_VALUE);
            payload.skip(4);
        }
        var audio = new AudioChildren();
        audio.read(payload, 0);
        TrackProtection protection = null;
        if (format == BoxTypes.ENCA) {
            protection = TrackProtection.parse(audio.sinfs, format);
            format = protection.originalFormat();
        }
        if (audio.sampleRate > 0) {
            sampleRate = audio.sampleRate;
        }
        Codec codec = Codec.UNKNOWN_AUDIO;
        byte[] config = null;
        var bits = 0;
        var blockBytes = 0;
        switch (format) {
            case BoxTypes.MP4A -> {
                if (audio.esds == null) {
                    codec = Codec.AAC;
                } else {
                    codec = switch (audio.esds.objectType()) {
                        case 0x40, 0x66, 0x67, 0x68 -> Codec.AAC;
                        case EsDescriptor.MPEG2_AUDIO, EsDescriptor.MPEG1_AUDIO -> Codec.MP3;
                        case 0xA5 -> Codec.AC3;
                        case 0xA6 -> Codec.EAC3;
                        case 0xA9, 0xAA, 0xAB, 0xAC -> Codec.DTS;
                        case 0xAD -> Codec.OPUS;
                        case 0xDD -> Codec.VORBIS;
                        default -> Codec.UNKNOWN_AUDIO;
                    };
                    if (codec == Codec.AAC || codec == Codec.VORBIS) {
                        config = audio.esds.specificInfo();
                    }
                    if (codec == Codec.AAC && config != null && dstChannels(config) > 0) {
                        codec = Codec.DST;
                        channels = dstChannels(config);
                        bits = 1;
                        var rate = dstSampleRate(config);
                        if (rate > 0) {
                            sampleRate = rate;
                        }
                    }
                }
                if (codec == Codec.AAC && config != null) {
                    try {
                        var aac = AudioConfigs.parseAac(config);
                        if (aac.channels() > 0) {
                            channels = aac.channels();
                        }
                        if (audio.layout == null) {
                            audio.layout = aac.layout();
                        }
                        if (sampleRate == 0) {
                            sampleRate = aac.outputRate();
                        }
                    } catch (MediaException.Malformed ignored) {
                        // an unparsable configuration leaves the entry's own values in place
                    }
                }
            }
            case BoxTypes.MP3, BoxTypes.MS_MP3 -> codec = Codec.MP3;
            case BoxTypes.OPUS -> {
                codec = Codec.OPUS;
                if (audio.dops != null) {
                    config = AudioConfigs.opusHeadFromDops(audio.dops);
                    channels = config[9] & 0xFF;
                    var ambisonic = OpusHeader.ambisonicLayout(channels, config[18] & 0xFF);
                    if (ambisonic != null) {
                        audio.layout = ambisonic;
                    }
                }
                sampleRate = 48000;
            }
            case BoxTypes.FLAC -> {
                codec = Codec.FLAC;
                if (audio.dfla != null) {
                    config = new byte[4 + audio.dfla.length];
                    System.arraycopy(AudioConfigs.FLAC_MARKER, 0, config, 0, 4);
                    System.arraycopy(audio.dfla, 0, config, 4, audio.dfla.length);
                    try {
                        var flac = AudioConfigs.parseFlac(config);
                        sampleRate = flac.sampleRate();
                        channels = flac.channels();
                        bits = flac.bitDepth();
                    } catch (MediaException.Malformed ignored) {
                        // a missing STREAMINFO leaves the entry's own values in place
                    }
                }
            }
            case BoxTypes.ALAC -> {
                codec = Codec.ALAC;
                if (audio.alac != null) {
                    config = audio.alac;
                    var alac = AudioConfigs.parseAlac(config);
                    if (alac.sampleRate() > 0) {
                        sampleRate = alac.sampleRate();
                    }
                    if (alac.channels() > 0) {
                        channels = alac.channels();
                    }
                    bits = alac.bitDepth();
                }
            }
            case BoxTypes.SAMR -> {
                codec = Codec.AMR_NB;
                sampleRate = 8000;
                channels = 1;
            }
            case BoxTypes.SAWB -> {
                codec = Codec.AMR_WB;
                sampleRate = 16000;
                channels = 1;
            }
            case BoxTypes.AC3, BoxTypes.EC3 -> {
                var enhanced = format == BoxTypes.EC3;
                codec = enhanced ? Codec.EAC3 : Codec.AC3;
                var stream = enhanced ? Ac3Configs.parseDec3(audio.dec3) : Ac3Configs.parseDac3(audio.dac3);
                if (stream != null) {
                    channels = stream.layout().channels();
                    if (stream.sampleRate() > 0) {
                        sampleRate = stream.sampleRate();
                    }
                    if (audio.layout == null) {
                        audio.layout = stream.layout();
                    }
                }
            }
            case BoxTypes.DTSC, BoxTypes.DTSH, BoxTypes.DTSL, BoxTypes.DTSE -> {
                codec = Codec.DTS;
                if (audio.ddts != null && audio.ddts.length >= 20) {
                    var rate = (audio.ddts[0] & 0xFF) << 24 | (audio.ddts[1] & 0xFF) << 16 | (audio.ddts[2] & 0xFF) << 8
                            | audio.ddts[3] & 0xFF;
                    if (rate > 0) {
                        sampleRate = rate;
                    }
                    var layout = DtsConfigs.layoutOfMask((audio.ddts[17] & 0xFF) << 8 | audio.ddts[18] & 0xFF);
                    if (layout != null) {
                        channels = layout.channels();
                        if (audio.layout == null) {
                            audio.layout = layout;
                        }
                    }
                }
            }
            case BoxTypes.TWOS -> codec = pcm(sampleSize, audio.littleEndian, true, false);
            case BoxTypes.SOWT -> codec = pcm(sampleSize, true, true, false);
            case BoxTypes.RAW, BoxTypes.NONE -> codec = sampleSize <= 8 ? Codec.PCM_U8 : pcm(sampleSize, false, true, false);
            case BoxTypes.IN24 -> codec = pcm(24, audio.littleEndian, true, false);
            case BoxTypes.IN32 -> codec = pcm(32, audio.littleEndian, true, false);
            case BoxTypes.FL32 -> codec = pcm(32, audio.littleEndian, true, true);
            case BoxTypes.FL64 -> codec = pcm(64, audio.littleEndian, true, true);
            case BoxTypes.ULAW -> codec = Codec.PCM_MULAW;
            case BoxTypes.ALAW -> codec = Codec.PCM_ALAW;
            case BoxTypes.IMA4 -> {
                codec = Codec.ADPCM_IMA_QT;
                bits = 4;
            }
            case BoxTypes.AGSM -> codec = Codec.GSM;
            case BoxTypes.MAC3 -> codec = Codec.MACE3;
            case BoxTypes.MAC6 -> codec = Codec.MACE6;
            case BoxTypes.MS_IMA_ADPCM, BoxTypes.MS_ADPCM -> {
                codec = format == BoxTypes.MS_ADPCM ? Codec.ADPCM_MS : Codec.ADPCM_IMA_WAV;
                bits = 4;
                if (audio.waveFormat != null && audio.waveFormat.length >= 14) {
                    var wave = WaveFormat.parse(audio.waveFormat, audio.waveFormat.length, false);
                    blockBytes = wave.blockAlign();
                    if (codec == Codec.ADPCM_MS) {
                        config = WaveFormat.msAdpcmConfig(wave.extra());
                    }
                }
            }
            case BoxTypes.LPCM -> {
                if (lpcmFlags >= 0) {
                    var isFloat = (lpcmFlags & 1) != 0;
                    var bigEndian = (lpcmFlags & 2) != 0;
                    var signed = (lpcmFlags & 4) != 0;
                    codec = pcm(sampleSize, !bigEndian, signed || isFloat, isFloat);
                }
            }
            case BoxTypes.IPCM -> {
                if (audio.pcmSize > 0) {
                    codec = pcm(audio.pcmSize, audio.pcmLittleEndian, true, false);
                }
            }
            case BoxTypes.FPCM -> {
                if (audio.pcmSize > 0) {
                    codec = pcm(audio.pcmSize, audio.pcmLittleEndian, true, true);
                }
            }
            default -> {
            }
        }
        if (codec.isPcm()) {
            bits = codec.pcmBitsPerSample().orElse(0);
        }
        if (channels > 0) {
            LimitChecks.checkChannels(limits, channels);
        }
        var layout = audio.spatial != null && audio.spatial.channels() == channels ? audio.spatial : audio.layout;
        if (layout != null && layout.channels() != channels) {
            layout = null;
        }
        if (layout == null && channels > 0) {
            layout = ChannelLayout.defaultFor(channels);
        }
        var frameBytes = codec.isPcm() && channels > 0 ? channels * Math.max(1, bits / 8) : blockBytes;
        if (frameBytes == 0 && bytesPerFrame > 0 && codec.isPcm()) {
            frameBytes = bytesPerFrame;
        }
        return new SampleEntry(format, codec, config, 0, 0, null, null, null, null, null, null, channels, sampleRate,
                layout, bits, frameBytes, protection);
    }

    /**
     * Returns whether the bytes at the cursor position look like a box header.
     *
     * @param payload the cursor, not moved
     * @return {@code true} when a plausible size and printable type follow
     */
    private static boolean looksLikeBox(BoxCursor payload) {
        var data = payload.data();
        var position = payload.position();
        var size = ((data[position] & 0xFFL) << 24) | ((data[position + 1] & 0xFF) << 16)
                | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
        var type = ((data[position + 4] & 0xFF) << 24) | ((data[position + 5] & 0xFF) << 16)
                | ((data[position + 6] & 0xFF) << 8) | (data[position + 7] & 0xFF);
        return size >= 8 && size <= payload.remaining() && BoxTypes.isPlausible(type);
    }

    /**
     * Returns the PCM codec for a sample format.
     *
     * @param bits the sample size in bits
     * @param littleEndian whether multi-byte samples are little-endian
     * @param signed whether integer samples are signed
     * @param isFloat whether samples are IEEE floats
     * @return the codec, or {@link Codec#UNKNOWN_AUDIO} for an unsupported combination
     */
    private static Codec pcm(int bits, boolean littleEndian, boolean signed, boolean isFloat) {
        if (isFloat) {
            return switch (bits) {
                case 32 -> littleEndian ? Codec.PCM_F32LE : Codec.PCM_F32BE;
                case 64 -> littleEndian ? Codec.PCM_F64LE : Codec.PCM_F64BE;
                default -> Codec.UNKNOWN_AUDIO;
            };
        }
        return switch (bits) {
            case 8 -> signed ? Codec.PCM_S8 : Codec.PCM_U8;
            case 16 -> littleEndian ? Codec.PCM_S16LE : Codec.PCM_S16BE;
            case 24 -> littleEndian ? Codec.PCM_S24LE : Codec.PCM_S24BE;
            case 32 -> littleEndian ? Codec.PCM_S32LE : Codec.PCM_S32BE;
            default -> Codec.UNKNOWN_AUDIO;
        };
    }

    /**
     * Parses a sample entry of a track that is neither video nor audio.
     *
     * <p>A 3GPP timed text entry ({@code tx3g}) is {@link Codec#MOV_TEXT}, and so is a QuickTime text
     * entry ({@code text}), its description converted to a {@code tx3g} configuration (the demuxer
     * converts its samples); a WebVTT entry
     * ({@code wvtt}, ISO/IEC 14496-30) is {@link Codec#WEBVTT} whose configuration is the text of
     * its {@code vttC} box, and an XML subtitle entry ({@code stpp}) whose namespace, schema
     * location and auxiliary MIME type fields are well formed is {@link Codec#TTML}; an MPEG-4 systems
     * entry ({@code mp4s}) whose {@code esds} declares object type {@code 0xE0} is {@link Codec#DVD_SUBTITLE}, its
     * configuration the decoder specific information (the 16 palette entries); a QuickTime closed caption entry is
     * {@link Codec#CEA_608} ({@code c608}) or {@link Codec#CEA_708} ({@code c708}), without a configuration (the
     * demuxer converts its samples); every other entry is {@link Codec#DATA}.
     *
     * @param format the entry type
     * @param payload the entry payload
     * @return the description, whose configuration is the entry payload after the common sample
     * entry fields (the WebVTT header for a WebVTT entry)
     */
    private static SampleEntry parseData(int format, BoxCursor payload) {
        byte[] config = null;
        var codec = switch (format) {
            case TX3G, TEXT -> Codec.MOV_TEXT;
            case WVTT -> Codec.WEBVTT;
            case STPP -> Codec.TTML;
            case C608 -> Codec.CEA_608;
            case C708 -> Codec.CEA_708;
            default -> Codec.DATA;
        };
        if (codec == Codec.CEA_608 || codec == Codec.CEA_708) {
            return new SampleEntry(format, codec, null, 0, 0, null, null, null, null, null, null, 0, 0, null, 0, 0,
                    null);
        }
        if (payload.remaining() > 8) {
            payload.skip(8);
            if (format == MP4S) {
                for (var child : children(payload)) {
                    if (child.type() == BoxTypes.ESDS) {
                        var esds = parseEsds(child);
                        if (esds != null && esds.objectType() == VOBSUB_OBJECT_TYPE) {
                            codec = Codec.DVD_SUBTITLE;
                            config = esds.specificInfo();
                        }
                    }
                }
                if (codec != Codec.DVD_SUBTITLE) {
                    config = null;
                }
                return new SampleEntry(format, codec, config, 0, 0, null, null, null, null, null, null, 0, 0, null, 0,
                        0, null);
            }
            if (codec == Codec.WEBVTT) {
                for (var child : children(payload)) {
                    if (child.type() == VTT_CONFIG) {
                        config = child.rest();
                    }
                }
            } else if (format == TEXT) {
                config = QuickTimeText.config(payload.rest());
            } else {
                config = payload.rest();
            }
        }
        if (codec == Codec.TTML && (config == null || TtmlEntries.problem(config) != null)) {
            codec = Codec.DATA;
        }
        return new SampleEntry(format, codec, config, 0, 0, null, null, null, null, null, null, 0, 0, null, 0, 0,
                null);
    }

    /**
     * Returns the child boxes after the fixed fields of a sample entry, stopping quietly at the
     * first malformed child.
     *
     * @param payload the cursor positioned after the fixed fields
     * @return the children in order
     */
    private static List<BoxCursor> children(BoxCursor payload) {
        var result = new ArrayList<BoxCursor>();
        try {
            while (payload.hasBox()) {
                result.add(payload.nextBox());
            }
        } catch (MediaException.Malformed ignored) {
            // trailing bytes that are not boxes carry nothing this parser needs
        }
        return result;
    }

    /**
     * Collects the codec-specific children of an audio sample entry, descending into the
     * QuickTime {@code wave} atom.
     */
    private static final class AudioChildren {
        /**
         * The deepest {@code wave} nesting followed.
         */
        private static final int MAX_DEPTH = 2;

        /**
         * The elementary stream descriptor, or {@code null}.
         */
        private EsDescriptor esds;

        /**
         * The {@code dOps} payload, or {@code null}.
         */
        private byte[] dops;

        /**
         * The {@code dfLa} payload after its full box header, or {@code null}.
         */
        private byte[] dfla;

        /**
         * The {@code dac3} payload, or {@code null}.
         */
        private byte[] dac3;

        /**
         * The {@code dec3} payload, or {@code null}.
         */
        private byte[] dec3;

        /**
         * The {@code ddts} payload, or {@code null}.
         */
        private byte[] ddts;

        /**
         * The {@code ALACSpecificConfig}, or {@code null}.
         */
        private byte[] alac;

        /**
         * Whether an {@code enda} atom declares little-endian samples.
         */
        private boolean littleEndian;

        /**
         * The sample size declared by a {@code pcmC} box, or {@code 0}.
         */
        private int pcmSize;

        /**
         * Whether a {@code pcmC} box declares little-endian samples.
         */
        private boolean pcmLittleEndian;

        /**
         * The sampling rate declared by an {@code srat} box, or {@code 0}; it replaces the rate of
         * the entry's 16.16 field, which cannot hold rates above 65535 Hz.
         */
        private int sampleRate;

        /**
         * The channel layout declared by a {@code chan} or {@code chnl} box, or {@code null}.
         */
        private ChannelLayout layout;

        /**
         * The ambisonic layout declared by an {@code SA3D} box, or {@code null}.
         */
        private ChannelLayout spatial;

        /**
         * The protection scheme information boxes.
         */
        private final List<BoxCursor> sinfs = new ArrayList<>();

        /**
         * The {@code WAVEFORMATEX} of an IMA or Microsoft ADPCM entry, from its {@code wave} atom, or
         * {@code null}.
         */
        private byte[] waveFormat;

        /**
         * Reads the children at the cursor position.
         *
         * @param payload the cursor
         * @param depth the current {@code wave} nesting
         */
        private void read(BoxCursor payload, int depth) {
            for (var box : children(payload)) {
                var body = box;
                try {
                    switch (box.type()) {
                        case BoxTypes.ESDS -> esds = parseEsds(body);
                        case BoxTypes.DOPS -> dops = body.rest();
                        case BoxTypes.DAC3 -> dac3 = body.rest();
                        case BoxTypes.DEC3 -> dec3 = body.rest();
                        case BoxTypes.DDTS -> ddts = body.rest();
                        case BoxTypes.DFLA -> {
                            body.fullBoxHeader();
                            dfla = body.rest();
                        }
                        case BoxTypes.ALAC -> {
                            var cookie = body.rest();
                            if (cookie.length >= AudioConfigs.ALAC_CONFIG_LENGTH) {
                                alac = cookie.length >= 4 + AudioConfigs.ALAC_CONFIG_LENGTH
                                        ? Arrays.copyOfRange(cookie, 4, 4 + AudioConfigs.ALAC_CONFIG_LENGTH)
                                        : Arrays.copyOf(cookie, AudioConfigs.ALAC_CONFIG_LENGTH);
                            }
                        }
                        case BoxTypes.ENDA -> littleEndian = body.remaining() >= 2 && body.u16() != 0;
                        case BoxTypes.PCMC -> {
                            body.fullBoxHeader();
                            pcmLittleEndian = (body.u8() & 1) != 0;
                            pcmSize = body.u8();
                        }
                        case BoxTypes.WAVE -> {
                            if (depth < MAX_DEPTH) {
                                read(body, depth + 1);
                            }
                        }
                        case BoxTypes.CHAN -> layout = parseChan(body);
                        case BoxTypes.CHNL -> layout = parseChnl(body);
                        case BoxTypes.SA3D -> spatial = SpatialAudioBox.parse(body);
                        case BoxTypes.SINF -> sinfs.add(body);
                        case BoxTypes.MS_IMA_ADPCM, BoxTypes.MS_ADPCM -> waveFormat = body.rest();
                        default -> {
                            if (box.type() == FourCc.of("srat")) {
                                body.fullBoxHeader();
                                sampleRate = (int) Math.min(body.u32(), Integer.MAX_VALUE);
                            }
                        }
                    }
                } catch (MediaException.Malformed ignored) {
                    // a damaged optional child leaves the other children usable
                }
            }
        }
    }

    /**
     * Parses a QuickTime {@code chan} atom.
     *
     * @param body the atom payload
     * @return the layout, or {@code null} when it is not one this parser maps
     */
    private static ChannelLayout parseChan(BoxCursor body) {
        body.fullBoxHeader();
        var tag = body.s32();
        var bitmap = body.s32();
        var descriptions = body.u32();
        var speakers = new ArrayList<Speaker>();
        if (tag == LAYOUT_USE_DESCRIPTIONS) {
            if (descriptions > 64 || descriptions * 20 > body.remaining()) {
                return null;
            }
            for (var index = 0; index < descriptions; index++) {
                var label = body.s32();
                body.skip(16);
                speakers.add(quickTimeLabel(label));
            }
        } else if (tag == LAYOUT_USE_BITMAP) {
            var speakerValues = Speaker.values();
            for (var bit = 0; bit < 18; bit++) {
                if ((bitmap & (1 << bit)) != 0) {
                    speakers.add(speakerValues[bit]);
                }
            }
        } else {
            // TODO: the higher-order ambisonics layout tags (ACN with SN3D or N3D scaling) and channel labels, once
            // their values are confirmed against a specification or a sample file
            return switch (tag >>> 16) {
                case 100 -> ChannelLayout.MONO;
                case 101, 102 -> ChannelLayout.STEREO;
                case 113 -> new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER));
                case 117 -> new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                        Speaker.BACK_LEFT, Speaker.BACK_RIGHT));
                case 121 -> ChannelLayout.SURROUND_5_1;
                case 128 -> new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                        Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT));
                default -> null;
            };
        }
        return speakers.isEmpty() ? null : new ChannelLayout(speakers);
    }

    /**
     * Maps a QuickTime channel label to a speaker.
     *
     * @param label the {@code AudioChannelLabel}
     * @return the speaker, or {@link Speaker#UNKNOWN}
     */
    private static Speaker quickTimeLabel(int label) {
        return switch (label) {
            case 1 -> Speaker.FRONT_LEFT;
            case 2 -> Speaker.FRONT_RIGHT;
            case 3 -> Speaker.FRONT_CENTER;
            case 4 -> Speaker.LOW_FREQUENCY;
            case 5, 33 -> Speaker.BACK_LEFT;
            case 6, 34 -> Speaker.BACK_RIGHT;
            case 7 -> Speaker.FRONT_LEFT_OF_CENTER;
            case 8 -> Speaker.FRONT_RIGHT_OF_CENTER;
            case 9 -> Speaker.BACK_CENTER;
            case 10 -> Speaker.SIDE_LEFT;
            case 11 -> Speaker.SIDE_RIGHT;
            case 12 -> Speaker.TOP_CENTER;
            case 13 -> Speaker.TOP_FRONT_LEFT;
            case 14 -> Speaker.TOP_FRONT_CENTER;
            case 15 -> Speaker.TOP_FRONT_RIGHT;
            case 16 -> Speaker.TOP_BACK_LEFT;
            case 17 -> Speaker.TOP_BACK_CENTER;
            case 18 -> Speaker.TOP_BACK_RIGHT;
            case 35 -> Speaker.WIDE_LEFT;
            case 36 -> Speaker.WIDE_RIGHT;
            case 37 -> Speaker.LOW_FREQUENCY_2;
            case 49 -> Speaker.TOP_SIDE_LEFT;
            case 51 -> Speaker.TOP_SIDE_RIGHT;
            case 52 -> Speaker.TOP_BACK_LEFT;
            case 53 -> Speaker.TOP_BACK_CENTER;
            case 54 -> Speaker.TOP_BACK_RIGHT;
            case 55 -> Speaker.SIDE_LEFT;
            case 56 -> Speaker.SIDE_RIGHT;
            case 57 -> Speaker.BOTTOM_FRONT_LEFT;
            case 58 -> Speaker.BOTTOM_FRONT_RIGHT;
            case 59 -> Speaker.BOTTOM_FRONT_CENTER;
            default -> Speaker.UNKNOWN;
        };
    }

    /**
     * Parses an ISO {@code chnl} box (version 0, channel-structured streams only).
     *
     * @param body the box payload
     * @return the layout, or {@code null} when it is not one this parser maps
     */
    private static ChannelLayout parseChnl(BoxCursor body) {
        var version = body.fullBoxHeader() >>> 24;
        if (version != 0) {
            return null;
        }
        var structure = body.u8();
        if ((structure & 1) == 0) {
            return null;
        }
        var defined = body.u8();
        if (defined != 0) {
            return switch (defined) {
                case 1 -> ChannelLayout.MONO;
                case 2 -> ChannelLayout.STEREO;
                case 6 -> ChannelLayout.SURROUND_5_1;
                default -> null;
            };
        }
        var speakers = new ArrayList<Speaker>();
        while (body.remaining() > 0 && speakers.size() < 64) {
            var position = body.u8();
            if (position == 126) {
                body.skip(3);
                speakers.add(Speaker.UNKNOWN);
            } else {
                speakers.add(position < ISO_POSITIONS.length ? ISO_POSITIONS[position] : Speaker.UNKNOWN);
            }
        }
        return speakers.isEmpty() ? null : new ChannelLayout(speakers);
    }

    /**
     * Returns the channel count of a DST {@code AudioSpecificConfig} (audio object type 35), or 0 for any other
     * configuration.
     */
    private static int dstChannels(byte[] config) {
        var fields = dstFields(config);
        return fields == null ? 0 : Math.max(fields[1], 1);
    }

    /**
     * Returns the sampling frequency a DST {@code AudioSpecificConfig} states explicitly, or 0.
     */
    private static int dstSampleRate(byte[] config) {
        var fields = dstFields(config);
        return fields == null ? 0 : fields[0];
    }

    /**
     * Parses the leading fields of a DST {@code AudioSpecificConfig}: the explicit sampling frequency (0 when coded
     * by index) and {@code N_Channels}, or returns {@code null} for another audio object type.
     */
    private static int[] dstFields(byte[] config) {
        var bit = 0;
        if (config.length < 4) {
            return null;
        }
        var type = bitsOf(config, bit, 5);
        bit += 5;
        if (type == 31) {
            type = 32 + bitsOf(config, bit, 6);
            bit += 6;
        }
        if (type != 35) {
            return null;
        }
        var index = bitsOf(config, bit, 4);
        bit += 4;
        var frequency = 0;
        if (index == 15) {
            frequency = bitsOf(config, bit, 24);
            bit += 24;
        }
        bit += 4 + 1;
        if (bit + 14 > config.length * 8) {
            return null;
        }
        return new int[]{frequency, bitsOf(config, bit, 14)};
    }

    private static int bitsOf(byte[] data, int start, int count) {
        var value = 0;
        for (var i = 0; i < count; i++) {
            var at = start + i;
            if (at >>> 3 >= data.length) {
                return 0;
            }
            value = value << 1 | ((data[at >>> 3] >>> (7 - (at & 7))) & 1);
        }
        return value;
    }
}
