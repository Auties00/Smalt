package com.github.auties00.smalt.internal.tags;

/**
 * Defines the EBML and Matroska element identifiers the Matroska demuxer, muxer and tag editor
 * use, from RFC 8794 (EBML) and RFC 9559 (Matroska).
 *
 * <p>Each identifier keeps its length marker bits, exactly as it is stored in the file.
 */
public final class EbmlIds {
    /**
     * The {@code EBML header} element.
     */
    public static final int EBML = 0x1A45DFA3;

    /**
     * The {@code EBMLVersion} element.
     */
    public static final int EBML_VERSION = 0x4286;

    /**
     * The {@code EBMLReadVersion} element.
     */
    public static final int EBML_READ_VERSION = 0x42F7;

    /**
     * The {@code EBMLMaxIDLength} element.
     */
    public static final int EBML_MAX_ID_LENGTH = 0x42F2;

    /**
     * The {@code EBMLMaxSizeLength} element.
     */
    public static final int EBML_MAX_SIZE_LENGTH = 0x42F3;

    /**
     * The {@code DocType} element.
     */
    public static final int DOC_TYPE = 0x4282;

    /**
     * The {@code DocTypeVersion} element.
     */
    public static final int DOC_TYPE_VERSION = 0x4287;

    /**
     * The {@code DocTypeReadVersion} element.
     */
    public static final int DOC_TYPE_READ_VERSION = 0x4285;

    /**
     * The {@code Void} element.
     */
    public static final int VOID = 0xEC;

    /**
     * The {@code CRC-32} element.
     */
    public static final int CRC32 = 0xBF;

    /**
     * The {@code Segment} element.
     */
    public static final int SEGMENT = 0x18538067;

    /**
     * The {@code SeekHead} element.
     */
    public static final int SEEK_HEAD = 0x114D9B74;

    /**
     * The {@code Seek} element.
     */
    public static final int SEEK = 0x4DBB;

    /**
     * The {@code SeekID} element.
     */
    public static final int SEEK_ID = 0x53AB;

    /**
     * The {@code SeekPosition} element.
     */
    public static final int SEEK_POSITION = 0x53AC;

    /**
     * The {@code Info} element.
     */
    public static final int INFO = 0x1549A966;

    /**
     * The {@code TimestampScale} element.
     */
    public static final int TIMESTAMP_SCALE = 0x2AD7B1;

    /**
     * The {@code Duration} element.
     */
    public static final int DURATION = 0x4489;

    /**
     * The {@code DateUTC} element.
     */
    public static final int DATE_UTC = 0x4461;

    /**
     * The {@code Title} element.
     */
    public static final int TITLE = 0x7BA9;

    /**
     * The {@code MuxingApp} element.
     */
    public static final int MUXING_APP = 0x4D80;

    /**
     * The {@code WritingApp} element.
     */
    public static final int WRITING_APP = 0x5741;

    /**
     * The {@code Cluster} element.
     */
    public static final int CLUSTER = 0x1F43B675;

    /**
     * The {@code Timestamp} element.
     */
    public static final int TIMESTAMP = 0xE7;

    /**
     * The {@code SimpleBlock} element.
     */
    public static final int SIMPLE_BLOCK = 0xA3;

    /**
     * The {@code BlockGroup} element.
     */
    public static final int BLOCK_GROUP = 0xA0;

    /**
     * The {@code Block} element.
     */
    public static final int BLOCK = 0xA1;

    /**
     * The {@code BlockAdditions} element.
     */
    public static final int BLOCK_ADDITIONS = 0x75A1;

    /**
     * The {@code BlockMore} element.
     */
    public static final int BLOCK_MORE = 0xA6;

    /**
     * The {@code BlockAddID} element.
     */
    public static final int BLOCK_ADD_ID = 0xEE;

    /**
     * The {@code BlockAdditional} element.
     */
    public static final int BLOCK_ADDITIONAL = 0xA5;

    /**
     * The {@code BlockDuration} element.
     */
    public static final int BLOCK_DURATION = 0x9B;

    /**
     * The {@code ReferenceBlock} element.
     */
    public static final int REFERENCE_BLOCK = 0xFB;

    /**
     * The {@code DiscardPadding} element.
     */
    public static final int DISCARD_PADDING = 0x75A2;

    /**
     * The {@code Tracks} element.
     */
    public static final int TRACKS = 0x1654AE6B;

    /**
     * The {@code TrackEntry} element.
     */
    public static final int TRACK_ENTRY = 0xAE;

    /**
     * The {@code TrackNumber} element.
     */
    public static final int TRACK_NUMBER = 0xD7;

    /**
     * The {@code TrackUID} element.
     */
    public static final int TRACK_UID = 0x73C5;

    /**
     * The {@code TrackType} element.
     */
    public static final int TRACK_TYPE = 0x83;

    /**
     * The {@code FlagEnabled} element.
     */
    public static final int FLAG_ENABLED = 0xB9;

    /**
     * The {@code FlagDefault} element.
     */
    public static final int FLAG_DEFAULT = 0x88;

    /**
     * The {@code FlagForced} element.
     */
    public static final int FLAG_FORCED = 0x55AA;

    /**
     * The {@code FlagHearingImpaired} element.
     */
    public static final int FLAG_HEARING_IMPAIRED = 0x55AB;

    /**
     * The {@code FlagVisualImpaired} element.
     */
    public static final int FLAG_VISUAL_IMPAIRED = 0x55AC;

    /**
     * The {@code FlagTextDescriptions} element.
     */
    public static final int FLAG_TEXT_DESCRIPTIONS = 0x55AD;

    /**
     * The {@code FlagOriginal} element.
     */
    public static final int FLAG_ORIGINAL = 0x55AE;

    /**
     * The {@code FlagCommentary} element.
     */
    public static final int FLAG_COMMENTARY = 0x55AF;

    /**
     * The {@code FlagLacing} element.
     */
    public static final int FLAG_LACING = 0x9C;

    /**
     * The {@code DefaultDuration} element.
     */
    public static final int DEFAULT_DURATION = 0x23E383;

    /**
     * The {@code Name} element.
     */
    public static final int NAME = 0x536E;

    /**
     * The {@code Language} element.
     */
    public static final int LANGUAGE = 0x22B59C;

    /**
     * The {@code LanguageBCP47} element.
     */
    public static final int LANGUAGE_BCP47 = 0x22B59D;

    /**
     * The {@code CodecID} element.
     */
    public static final int CODEC_ID = 0x86;

    /**
     * The {@code CodecPrivate} element.
     */
    public static final int CODEC_PRIVATE = 0x63A2;

    /**
     * The {@code MaxBlockAdditionID} element, the largest {@code BlockAddID} of a track.
     */
    public static final int MAX_BLOCK_ADDITION_ID = 0x55EE;

    /**
     * The {@code BlockAdditionMapping} element.
     */
    public static final int BLOCK_ADDITION_MAPPING = 0x41E4;

    /**
     * The {@code BlockAddIDValue} element.
     */
    public static final int BLOCK_ADD_ID_VALUE = 0x41F0;

    /**
     * The {@code BlockAddIDType} element.
     */
    public static final int BLOCK_ADD_ID_TYPE = 0x41E7;

    /**
     * The {@code CodecDelay} element.
     */
    public static final int CODEC_DELAY = 0x56AA;

    /**
     * The {@code SeekPreRoll} element.
     */
    public static final int SEEK_PRE_ROLL = 0x56BB;

    /**
     * The {@code Video} element.
     */
    public static final int VIDEO = 0xE0;

    /**
     * The {@code PixelWidth} element.
     */
    public static final int PIXEL_WIDTH = 0xB0;

    /**
     * The {@code PixelHeight} element.
     */
    public static final int PIXEL_HEIGHT = 0xBA;

    /**
     * The {@code FlagInterlaced} element.
     */
    public static final int FLAG_INTERLACED = 0x9A;

    /**
     * The {@code FieldOrder} element.
     */
    public static final int FIELD_ORDER = 0x9D;

    /**
     * The FourCC of the pixel layout of uncompressed video ({@code ColourSpace}).
     */
    public static final int COLOUR_SPACE = 0x2EB524;

    /**
     * The {@code PixelCropBottom} element.
     */
    public static final int PIXEL_CROP_BOTTOM = 0x54AA;

    /**
     * The {@code PixelCropTop} element.
     */
    public static final int PIXEL_CROP_TOP = 0x54BB;

    /**
     * The {@code PixelCropLeft} element.
     */
    public static final int PIXEL_CROP_LEFT = 0x54CC;

    /**
     * The {@code PixelCropRight} element.
     */
    public static final int PIXEL_CROP_RIGHT = 0x54DD;

    /**
     * The {@code DisplayWidth} element.
     */
    public static final int DISPLAY_WIDTH = 0x54B0;

    /**
     * The {@code DisplayHeight} element.
     */
    public static final int DISPLAY_HEIGHT = 0x54BA;

    /**
     * The {@code DisplayUnit} element.
     */
    public static final int DISPLAY_UNIT = 0x54B2;

    /**
     * The {@code Color} element.
     */
    public static final int COLOR = 0x55B0;

    /**
     * The {@code MatrixCoefficients} element.
     */
    public static final int MATRIX_COEFFICIENTS = 0x55B1;

    /**
     * The {@code Range} element.
     */
    public static final int RANGE = 0x55B9;

    /**
     * The {@code TransferCharacteristics} element.
     */
    public static final int TRANSFER_CHARACTERISTICS = 0x55BA;

    /**
     * The {@code Primaries} element.
     */
    public static final int PRIMARIES = 0x55BB;

    /**
     * The {@code ChromaSitingHorz} element.
     */
    public static final int CHROMA_SITING_HORZ = 0x55B7;

    /**
     * The {@code ChromaSitingVert} element.
     */
    public static final int CHROMA_SITING_VERT = 0x55B8;

    /**
     * The {@code MaxCLL} element.
     */
    public static final int MAX_CLL = 0x55BC;

    /**
     * The {@code MaxFALL} element.
     */
    public static final int MAX_FALL = 0x55BD;

    /**
     * The {@code MasteringMetadata} element.
     */
    public static final int MASTERING_METADATA = 0x55D0;

    /**
     * The {@code PrimaryRChromaticityX} element.
     */
    public static final int PRIMARY_R_CHROMATICITY_X = 0x55D1;

    /**
     * The {@code PrimaryRChromaticityY} element.
     */
    public static final int PRIMARY_R_CHROMATICITY_Y = 0x55D2;

    /**
     * The {@code PrimaryGChromaticityX} element.
     */
    public static final int PRIMARY_G_CHROMATICITY_X = 0x55D3;

    /**
     * The {@code PrimaryGChromaticityY} element.
     */
    public static final int PRIMARY_G_CHROMATICITY_Y = 0x55D4;

    /**
     * The {@code PrimaryBChromaticityX} element.
     */
    public static final int PRIMARY_B_CHROMATICITY_X = 0x55D5;

    /**
     * The {@code PrimaryBChromaticityY} element.
     */
    public static final int PRIMARY_B_CHROMATICITY_Y = 0x55D6;

    /**
     * The {@code WhitePointChromaticityX} element.
     */
    public static final int WHITE_POINT_CHROMATICITY_X = 0x55D7;

    /**
     * The {@code WhitePointChromaticityY} element.
     */
    public static final int WHITE_POINT_CHROMATICITY_Y = 0x55D8;

    /**
     * The {@code LuminanceMax} element.
     */
    public static final int LUMINANCE_MAX = 0x55D9;

    /**
     * The {@code LuminanceMin} element.
     */
    public static final int LUMINANCE_MIN = 0x55DA;

    /**
     * The {@code Audio} element.
     */
    public static final int AUDIO = 0xE1;

    /**
     * The {@code SamplingFrequency} element.
     */
    public static final int SAMPLING_FREQUENCY = 0xB5;

    /**
     * The {@code OutputSamplingFrequency} element.
     */
    public static final int OUTPUT_SAMPLING_FREQUENCY = 0x78B5;

    /**
     * The {@code Channels} element.
     */
    public static final int CHANNELS = 0x9F;

    /**
     * The {@code BitDepth} element.
     */
    public static final int BIT_DEPTH = 0x6264;

    /**
     * The {@code ContentEncodings} element.
     */
    public static final int CONTENT_ENCODINGS = 0x6D80;

    /**
     * The {@code ContentEncoding} element.
     */
    public static final int CONTENT_ENCODING = 0x6240;

    /**
     * The {@code ContentEncodingOrder} element.
     */
    public static final int CONTENT_ENCODING_ORDER = 0x5031;

    /**
     * The {@code ContentEncodingScope} element.
     */
    public static final int CONTENT_ENCODING_SCOPE = 0x5032;

    /**
     * The {@code ContentEncodingType} element.
     */
    public static final int CONTENT_ENCODING_TYPE = 0x5033;

    /**
     * The {@code ContentCompression} element.
     */
    public static final int CONTENT_COMPRESSION = 0x5034;

    /**
     * The {@code ContentCompAlgo} element.
     */
    public static final int CONTENT_COMP_ALGO = 0x4254;

    /**
     * The {@code ContentCompSettings} element.
     */
    public static final int CONTENT_COMP_SETTINGS = 0x4255;

    /**
     * The {@code ContentEncryption} element.
     */
    public static final int CONTENT_ENCRYPTION = 0x5035;

    /**
     * The {@code Projection} element of {@code Video}.
     */
    public static final int PROJECTION = 0x7670;

    /**
     * The {@code ProjectionPoseRoll} element of {@code Projection}: a counter-clockwise rotation in
     * degrees.
     */
    public static final int PROJECTION_POSE_ROLL = 0x7675;

    /**
     * The {@code ProjectionType} element of {@code Projection}.
     */
    public static final int PROJECTION_TYPE = 0x7671;

    /**
     * The {@code ProjectionPrivate} element of {@code Projection}.
     */
    public static final int PROJECTION_PRIVATE = 0x7672;

    /**
     * The {@code ProjectionPoseYaw} element of {@code Projection}, in degrees.
     */
    public static final int PROJECTION_POSE_YAW = 0x7673;

    /**
     * The {@code ProjectionPosePitch} element of {@code Projection}, in degrees.
     */
    public static final int PROJECTION_POSE_PITCH = 0x7674;

    /**
     * The {@code StereoMode} element of {@code Video}.
     */
    public static final int STEREO_MODE = 0x53B8;

    /**
     * The {@code Cues} element.
     */
    public static final int CUES = 0x1C53BB6B;

    /**
     * The {@code CuePoint} element of {@code Cues}.
     */
    public static final int CUE_POINT = 0xBB;

    /**
     * The {@code CueTime} element of {@code CuePoint}.
     */
    public static final int CUE_TIME = 0xB3;

    /**
     * The {@code CueTrackPositions} element of {@code CuePoint}.
     */
    public static final int CUE_TRACK_POSITIONS = 0xB7;

    /**
     * The {@code CueTrack} element of {@code CueTrackPositions}.
     */
    public static final int CUE_TRACK = 0xF7;

    /**
     * The {@code CueClusterPosition} element of {@code CueTrackPositions}.
     */
    public static final int CUE_CLUSTER_POSITION = 0xF1;

    /**
     * The {@code Chapters} element.
     */
    public static final int CHAPTERS = 0x1043A770;

    /**
     * The {@code EditionEntry} element of {@code Chapters}.
     */
    public static final int EDITION_ENTRY = 0x45B9;

    /**
     * The {@code EditionUID} element.
     */
    public static final int EDITION_UID = 0x45BC;

    /**
     * The {@code EditionFlagDefault} element of {@code EditionEntry}.
     */
    public static final int EDITION_FLAG_DEFAULT = 0x45DB;

    /**
     * The {@code ChapterAtom} element of {@code EditionEntry}.
     */
    public static final int CHAPTER_ATOM = 0xB6;

    /**
     * The {@code ChapterUID} element.
     */
    public static final int CHAPTER_UID = 0x73C4;

    /**
     * The {@code ChapterTimeStart} element of {@code ChapterAtom}, in nanoseconds.
     */
    public static final int CHAPTER_TIME_START = 0x91;

    /**
     * The {@code ChapterTimeEnd} element of {@code ChapterAtom}, in nanoseconds.
     */
    public static final int CHAPTER_TIME_END = 0x92;

    /**
     * The {@code ChapterFlagHidden} element of {@code ChapterAtom}.
     */
    public static final int CHAPTER_FLAG_HIDDEN = 0x98;

    /**
     * The {@code ChapterDisplay} element of {@code ChapterAtom}.
     */
    public static final int CHAPTER_DISPLAY = 0x80;

    /**
     * The {@code ChapString} element of {@code ChapterDisplay}.
     */
    public static final int CHAP_STRING = 0x85;

    /**
     * The {@code ChapLanguage} element.
     */
    public static final int CHAP_LANGUAGE = 0x437C;

    /**
     * The {@code ChapLanguageBCP47} element of {@code ChapterDisplay}.
     */
    public static final int CHAP_LANGUAGE_BCP47 = 0x437D;

    /**
     * The {@code EditionFlagHidden} element of {@code EditionEntry}.
     */
    public static final int EDITION_FLAG_HIDDEN = 0x45BD;

    /**
     * The {@code EditionFlagOrdered} element of {@code EditionEntry}.
     */
    public static final int EDITION_FLAG_ORDERED = 0x45DD;

    /**
     * The {@code EditionDisplay} element of {@code EditionEntry}.
     */
    public static final int EDITION_DISPLAY = 0x4520;

    /**
     * The {@code EditionString} element of {@code EditionDisplay}.
     */
    public static final int EDITION_STRING = 0x4521;

    /**
     * The {@code EditionLanguageIETF} element of {@code EditionDisplay}.
     */
    public static final int EDITION_LANGUAGE_IETF = 0x45E4;

    /**
     * The {@code Attachments} element.
     */
    public static final int ATTACHMENTS = 0x1941A469;

    /**
     * The {@code AttachedFile} element of {@code Attachments}.
     */
    public static final int ATTACHED_FILE = 0x61A7;

    /**
     * The {@code FileDescription} element of {@code AttachedFile}.
     */
    public static final int FILE_DESCRIPTION = 0x467E;

    /**
     * The {@code FileName} element of {@code AttachedFile}.
     */
    public static final int FILE_NAME = 0x466E;

    /**
     * The {@code FileMediaType} element of {@code AttachedFile}.
     */
    public static final int FILE_MEDIA_TYPE = 0x4660;

    /**
     * The {@code FileData} element of {@code AttachedFile}.
     */
    public static final int FILE_DATA = 0x465C;

    /**
     * The {@code FileUID} element.
     */
    public static final int FILE_UID = 0x46AE;

    /**
     * The {@code Tags} element.
     */
    public static final int TAGS = 0x1254C367;

    /**
     * The {@code Tag} element.
     */
    public static final int TAG = 0x7373;

    /**
     * The {@code Targets} element.
     */
    public static final int TARGETS = 0x63C0;

    /**
     * The {@code TargetTypeValue} element.
     */
    public static final int TARGET_TYPE_VALUE = 0x68CA;

    /**
     * The {@code TargetType} element.
     */
    public static final int TARGET_TYPE = 0x63CA;

    /**
     * The {@code TagTrackUID} element.
     */
    public static final int TAG_TRACK_UID = 0x63C5;

    /**
     * The {@code TagEditionUID} element.
     */
    public static final int TAG_EDITION_UID = 0x63C9;

    /**
     * The {@code TagChapterUID} element.
     */
    public static final int TAG_CHAPTER_UID = 0x63C4;

    /**
     * The {@code TagAttachmentUID} element.
     */
    public static final int TAG_ATTACHMENT_UID = 0x63C6;

    /**
     * The {@code SimpleTag} element.
     */
    public static final int SIMPLE_TAG = 0x67C8;

    /**
     * The {@code TagName} element.
     */
    public static final int TAG_NAME = 0x45A3;

    /**
     * The {@code TagLanguage} element.
     */
    public static final int TAG_LANGUAGE = 0x447A;

    /**
     * The {@code TagString} element.
     */
    public static final int TAG_STRING = 0x4487;
    private EbmlIds() {
        throw new AssertionError();
    }

    /**
     * Returns whether an identifier names a top-level element: a child of the {@code Segment}, or
     * the start of another EBML document.
     *
     * <p>An unknown-size {@code Cluster} ends where such an element starts.
     *
     * @param id the element identifier
     * @return {@code true} for a top-level identifier
     */
    public static boolean isTopLevel(long id) {
        return id == CLUSTER || id == SEEK_HEAD || id == INFO || id == TRACKS || id == CUES || id == CHAPTERS
                || id == ATTACHMENTS || id == TAGS || id == EBML || id == SEGMENT;
    }
}
