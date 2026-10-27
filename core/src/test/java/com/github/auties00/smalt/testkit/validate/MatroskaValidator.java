package com.github.auties00.smalt.testkit.validate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Checks a Matroska or WebM file against EBML (RFC 8794) and Matroska (RFC 9559), independently of Smalt's demuxer:
 * variable-size integers, element sizes that nest, unknown sizes only where allowed, the EBML header, the elements
 * each master requires and the ones that must be unique, track numbers and UIDs, the codec ids of WebM, every block's
 * track and lacing, cluster timestamps, the positions the SeekHead and the Cues point at, and CRC-32 elements.
 */
public final class MatroskaValidator {
    /** The element types of the schema. */
    private enum Type {
        MASTER, UINT, INT, FLOAT, STRING, UTF8, BINARY, DATE
    }

    /**
     * An element of the schema.
     *
     * @param name the element name
     * @param type the value type
     * @param parent the id of the parent element, or 0 for a top-level or global element
     */
    private record Element(String name, Type type, long parent) {
    }

    private static final long EBML = 0x1A45DFA3L;
    private static final long SEGMENT = 0x18538067L;
    private static final long SEEK_HEAD = 0x114D9B74L;
    private static final long INFO = 0x1549A966L;
    private static final long TRACKS = 0x1654AE6BL;
    private static final long CLUSTER = 0x1F43B675L;
    private static final long CUES = 0x1C53BB6BL;
    private static final long CHAPTERS = 0x1043A770L;
    private static final long TAGS = 0x1254C367L;
    private static final long ATTACHMENTS = 0x1941A469L;
    private static final long VOID = 0xECL;
    private static final long CRC = 0xBFL;
    private static final long TRACK_ENTRY = 0xAEL;
    private static final long VIDEO = 0xE0L;
    private static final long AUDIO = 0xE1L;
    private static final long COLOUR = 0x55B0L;
    private static final long BLOCK_GROUP = 0xA0L;
    private static final long CUE_POINT = 0xBBL;
    private static final long CUE_TRACK_POSITIONS = 0xB7L;
    private static final long SEEK = 0x4DBBL;

    /** The schema, by element id. */
    private static final Map<Long, Element> SCHEMA = new HashMap<>();

    /** The codec ids WebM allows. */
    private static final Set<String> WEBM_CODECS = Set.of("V_VP8", "V_VP9", "V_AV1", "A_VORBIS", "A_OPUS",
            "S_TEXT/WEBVTT", "D_WEBVTT/SUBTITLES", "D_WEBVTT/CAPTIONS", "D_WEBVTT/DESCRIPTIONS", "D_WEBVTT/METADATA");

    static {
        define(EBML, "EBML", Type.MASTER, 0);
        define(0x4286, "EBMLVersion", Type.UINT, EBML);
        define(0x42F7, "EBMLReadVersion", Type.UINT, EBML);
        define(0x42F2, "EBMLMaxIDLength", Type.UINT, EBML);
        define(0x42F3, "EBMLMaxSizeLength", Type.UINT, EBML);
        define(0x4282, "DocType", Type.STRING, EBML);
        define(0x4287, "DocTypeVersion", Type.UINT, EBML);
        define(0x4285, "DocTypeReadVersion", Type.UINT, EBML);
        define(0x4281, "DocTypeExtension", Type.MASTER, EBML);
        define(SEGMENT, "Segment", Type.MASTER, 0);
        define(SEEK_HEAD, "SeekHead", Type.MASTER, SEGMENT);
        define(SEEK, "Seek", Type.MASTER, SEEK_HEAD);
        define(0x53AB, "SeekID", Type.BINARY, SEEK);
        define(0x53AC, "SeekPosition", Type.UINT, SEEK);
        define(INFO, "Info", Type.MASTER, SEGMENT);
        define(0x73A4, "SegmentUUID", Type.BINARY, INFO);
        define(0x7384, "SegmentFilename", Type.UTF8, INFO);
        define(0x3CB923, "PrevUUID", Type.BINARY, INFO);
        define(0x3EB923, "NextUUID", Type.BINARY, INFO);
        define(0x4444, "SegmentFamily", Type.BINARY, INFO);
        define(0x6924, "ChapterTranslate", Type.MASTER, INFO);
        define(0x2AD7B1, "TimestampScale", Type.UINT, INFO);
        define(0x4489, "Duration", Type.FLOAT, INFO);
        define(0x4461, "DateUTC", Type.DATE, INFO);
        define(0x7BA9, "Title", Type.UTF8, INFO);
        define(0x4D80, "MuxingApp", Type.UTF8, INFO);
        define(0x5741, "WritingApp", Type.UTF8, INFO);
        define(CLUSTER, "Cluster", Type.MASTER, SEGMENT);
        define(0xE7, "Timestamp", Type.UINT, CLUSTER);
        define(0xA7, "Position", Type.UINT, CLUSTER);
        define(0xAB, "PrevSize", Type.UINT, CLUSTER);
        define(0xA3, "SimpleBlock", Type.BINARY, CLUSTER);
        define(BLOCK_GROUP, "BlockGroup", Type.MASTER, CLUSTER);
        define(0xA1, "Block", Type.BINARY, BLOCK_GROUP);
        define(0x75A1, "BlockAdditions", Type.MASTER, BLOCK_GROUP);
        define(0xA6, "BlockMore", Type.MASTER, 0x75A1);
        define(0xA5, "BlockAdditional", Type.BINARY, 0xA6);
        define(0xEE, "BlockAddID", Type.UINT, 0xA6);
        define(0x9B, "BlockDuration", Type.UINT, BLOCK_GROUP);
        define(0xFA, "ReferencePriority", Type.UINT, BLOCK_GROUP);
        define(0xFB, "ReferenceBlock", Type.INT, BLOCK_GROUP);
        define(0xA4, "CodecState", Type.BINARY, BLOCK_GROUP);
        define(0x75A2, "DiscardPadding", Type.INT, BLOCK_GROUP);
        define(TRACKS, "Tracks", Type.MASTER, SEGMENT);
        define(TRACK_ENTRY, "TrackEntry", Type.MASTER, TRACKS);
        define(0xD7, "TrackNumber", Type.UINT, TRACK_ENTRY);
        define(0x73C5, "TrackUID", Type.UINT, TRACK_ENTRY);
        define(0x83, "TrackType", Type.UINT, TRACK_ENTRY);
        define(0xB9, "FlagEnabled", Type.UINT, TRACK_ENTRY);
        define(0x88, "FlagDefault", Type.UINT, TRACK_ENTRY);
        define(0x55AA, "FlagForced", Type.UINT, TRACK_ENTRY);
        define(0x55AB, "FlagHearingImpaired", Type.UINT, TRACK_ENTRY);
        define(0x55AC, "FlagVisualImpaired", Type.UINT, TRACK_ENTRY);
        define(0x55AD, "FlagTextDescriptions", Type.UINT, TRACK_ENTRY);
        define(0x55AE, "FlagOriginal", Type.UINT, TRACK_ENTRY);
        define(0x55AF, "FlagCommentary", Type.UINT, TRACK_ENTRY);
        define(0x9C, "FlagLacing", Type.UINT, TRACK_ENTRY);
        define(0x6DE7, "MinCache", Type.UINT, TRACK_ENTRY);
        define(0x6DF8, "MaxCache", Type.UINT, TRACK_ENTRY);
        define(0x23E383, "DefaultDuration", Type.UINT, TRACK_ENTRY);
        define(0x234E7A, "DefaultDecodedFieldDuration", Type.UINT, TRACK_ENTRY);
        define(0x23314F, "TrackTimestampScale", Type.FLOAT, TRACK_ENTRY);
        define(0x55EE, "MaxBlockAdditionID", Type.UINT, TRACK_ENTRY);
        define(0x41E4, "BlockAdditionMapping", Type.MASTER, TRACK_ENTRY);
        define(0x41F0, "BlockAddIDValue", Type.UINT, 0x41E4);
        define(0x41A4, "BlockAddIDName", Type.STRING, 0x41E4);
        define(0x41E7, "BlockAddIDType", Type.UINT, 0x41E4);
        define(0x41ED, "BlockAddIDExtraData", Type.BINARY, 0x41E4);
        define(0x536E, "Name", Type.UTF8, TRACK_ENTRY);
        define(0x22B59C, "Language", Type.STRING, TRACK_ENTRY);
        define(0x22B59D, "LanguageBCP47", Type.STRING, TRACK_ENTRY);
        define(0x86, "CodecID", Type.STRING, TRACK_ENTRY);
        define(0x63A2, "CodecPrivate", Type.BINARY, TRACK_ENTRY);
        define(0x258688, "CodecName", Type.UTF8, TRACK_ENTRY);
        define(0x7446, "AttachmentLink", Type.UINT, TRACK_ENTRY);
        define(0xAA, "CodecDecodeAll", Type.UINT, TRACK_ENTRY);
        define(0x6FAB, "TrackOverlay", Type.UINT, TRACK_ENTRY);
        define(0x56AA, "CodecDelay", Type.UINT, TRACK_ENTRY);
        define(0x56BB, "SeekPreRoll", Type.UINT, TRACK_ENTRY);
        define(0x6624, "TrackTranslate", Type.MASTER, TRACK_ENTRY);
        define(VIDEO, "Video", Type.MASTER, TRACK_ENTRY);
        define(0x9A, "FlagInterlaced", Type.UINT, VIDEO);
        define(0x9D, "FieldOrder", Type.UINT, VIDEO);
        define(0x53B8, "StereoMode", Type.UINT, VIDEO);
        define(0x53C0, "AlphaMode", Type.UINT, VIDEO);
        define(0xB0, "PixelWidth", Type.UINT, VIDEO);
        define(0xBA, "PixelHeight", Type.UINT, VIDEO);
        define(0x54AA, "PixelCropBottom", Type.UINT, VIDEO);
        define(0x54BB, "PixelCropTop", Type.UINT, VIDEO);
        define(0x54CC, "PixelCropLeft", Type.UINT, VIDEO);
        define(0x54DD, "PixelCropRight", Type.UINT, VIDEO);
        define(0x54B0, "DisplayWidth", Type.UINT, VIDEO);
        define(0x54BA, "DisplayHeight", Type.UINT, VIDEO);
        define(0x54B2, "DisplayUnit", Type.UINT, VIDEO);
        define(0x54B3, "AspectRatioType", Type.UINT, VIDEO);
        define(0x2EB524, "UncompressedFourCC", Type.BINARY, VIDEO);
        define(0x2FB523, "GammaValue", Type.FLOAT, VIDEO);
        define(0x2383E3, "FrameRate", Type.FLOAT, VIDEO);
        define(COLOUR, "Colour", Type.MASTER, VIDEO);
        define(0x55B1, "MatrixCoefficients", Type.UINT, COLOUR);
        define(0x55B2, "BitsPerChannel", Type.UINT, COLOUR);
        define(0x55B3, "ChromaSubsamplingHorz", Type.UINT, COLOUR);
        define(0x55B4, "ChromaSubsamplingVert", Type.UINT, COLOUR);
        define(0x55B5, "CbSubsamplingHorz", Type.UINT, COLOUR);
        define(0x55B6, "CbSubsamplingVert", Type.UINT, COLOUR);
        define(0x55B7, "ChromaSitingHorz", Type.UINT, COLOUR);
        define(0x55B8, "ChromaSitingVert", Type.UINT, COLOUR);
        define(0x55B9, "Range", Type.UINT, COLOUR);
        define(0x55BA, "TransferCharacteristics", Type.UINT, COLOUR);
        define(0x55BB, "Primaries", Type.UINT, COLOUR);
        define(0x55BC, "MaxCLL", Type.UINT, COLOUR);
        define(0x55BD, "MaxFALL", Type.UINT, COLOUR);
        define(0x55D0, "MasteringMetadata", Type.MASTER, COLOUR);
        for (var id = 0x55D1; id <= 0x55DA; id++) {
            define(id, "MasteringMetadata " + Long.toHexString(id), Type.FLOAT, 0x55D0);
        }
        define(0x7670, "Projection", Type.MASTER, VIDEO);
        define(0x7671, "ProjectionType", Type.UINT, 0x7670);
        define(0x7672, "ProjectionPrivate", Type.BINARY, 0x7670);
        define(0x7673, "ProjectionPoseYaw", Type.FLOAT, 0x7670);
        define(0x7674, "ProjectionPosePitch", Type.FLOAT, 0x7670);
        define(0x7675, "ProjectionPoseRoll", Type.FLOAT, 0x7670);
        define(AUDIO, "Audio", Type.MASTER, TRACK_ENTRY);
        define(0xB5, "SamplingFrequency", Type.FLOAT, AUDIO);
        define(0x78B5, "OutputSamplingFrequency", Type.FLOAT, AUDIO);
        define(0x9F, "Channels", Type.UINT, AUDIO);
        define(0x6264, "BitDepth", Type.UINT, AUDIO);
        define(0x52F1, "Emphasis", Type.UINT, AUDIO);
        define(0x6D80, "ContentEncodings", Type.MASTER, TRACK_ENTRY);
        define(0x6240, "ContentEncoding", Type.MASTER, 0x6D80);
        define(0x5031, "ContentEncodingOrder", Type.UINT, 0x6240);
        define(0x5032, "ContentEncodingScope", Type.UINT, 0x6240);
        define(0x5033, "ContentEncodingType", Type.UINT, 0x6240);
        define(0x5034, "ContentCompression", Type.MASTER, 0x6240);
        define(0x4254, "ContentCompAlgo", Type.UINT, 0x5034);
        define(0x4255, "ContentCompSettings", Type.BINARY, 0x5034);
        define(CUES, "Cues", Type.MASTER, SEGMENT);
        define(CUE_POINT, "CuePoint", Type.MASTER, CUES);
        define(0xB3, "CueTime", Type.UINT, CUE_POINT);
        define(CUE_TRACK_POSITIONS, "CueTrackPositions", Type.MASTER, CUE_POINT);
        define(0xF7, "CueTrack", Type.UINT, CUE_TRACK_POSITIONS);
        define(0xF1, "CueClusterPosition", Type.UINT, CUE_TRACK_POSITIONS);
        define(0xF0, "CueRelativePosition", Type.UINT, CUE_TRACK_POSITIONS);
        define(0xB2, "CueDuration", Type.UINT, CUE_TRACK_POSITIONS);
        define(0x5378, "CueBlockNumber", Type.UINT, CUE_TRACK_POSITIONS);
        define(0xEA, "CueCodecState", Type.UINT, CUE_TRACK_POSITIONS);
        define(0xDB, "CueReference", Type.MASTER, CUE_TRACK_POSITIONS);
        define(0x96, "CueRefTime", Type.UINT, 0xDB);
        define(ATTACHMENTS, "Attachments", Type.MASTER, SEGMENT);
        define(0x61A7, "AttachedFile", Type.MASTER, ATTACHMENTS);
        define(0x467E, "FileDescription", Type.UTF8, 0x61A7);
        define(0x466E, "FileName", Type.UTF8, 0x61A7);
        define(0x4660, "FileMediaType", Type.STRING, 0x61A7);
        define(0x465C, "FileData", Type.BINARY, 0x61A7);
        define(0x46AE, "FileUID", Type.UINT, 0x61A7);
        define(CHAPTERS, "Chapters", Type.MASTER, SEGMENT);
        define(0x45B9, "EditionEntry", Type.MASTER, CHAPTERS);
        define(0x45BC, "EditionUID", Type.UINT, 0x45B9);
        define(0x45BD, "EditionFlagHidden", Type.UINT, 0x45B9);
        define(0x45DB, "EditionFlagDefault", Type.UINT, 0x45B9);
        define(0x45DD, "EditionFlagOrdered", Type.UINT, 0x45B9);
        define(0x4520, "EditionDisplay", Type.MASTER, 0x45B9);
        define(0x4521, "EditionString", Type.UTF8, 0x4520);
        define(0x45E4, "EditionLanguageIETF", Type.STRING, 0x4520);
        define(0xB6, "ChapterAtom", Type.MASTER, -1);
        define(0x73C4, "ChapterUID", Type.UINT, 0xB6);
        define(0x5654, "ChapterStringUID", Type.UTF8, 0xB6);
        define(0x91, "ChapterTimeStart", Type.UINT, 0xB6);
        define(0x92, "ChapterTimeEnd", Type.UINT, 0xB6);
        define(0x98, "ChapterFlagHidden", Type.UINT, 0xB6);
        define(0x4598, "ChapterFlagEnabled", Type.UINT, 0xB6);
        define(0x6E67, "ChapterSegmentUUID", Type.BINARY, 0xB6);
        define(0x4588, "ChapterSkipType", Type.UINT, 0xB6);
        define(0x6EBC, "ChapterSegmentEditionUID", Type.UINT, 0xB6);
        define(0x63C3, "ChapterPhysicalEquiv", Type.UINT, 0xB6);
        define(0x8F, "ChapterTrack", Type.MASTER, 0xB6);
        define(0x89, "ChapterTrackUID", Type.UINT, 0x8F);
        define(0x80, "ChapterDisplay", Type.MASTER, 0xB6);
        define(0x85, "ChapString", Type.UTF8, 0x80);
        define(0x437C, "ChapLanguage", Type.STRING, 0x80);
        define(0x437D, "ChapLanguageBCP47", Type.STRING, 0x80);
        define(0x437E, "ChapCountry", Type.STRING, 0x80);
        define(TAGS, "Tags", Type.MASTER, SEGMENT);
        define(0x7373, "Tag", Type.MASTER, TAGS);
        define(0x63C0, "Targets", Type.MASTER, 0x7373);
        define(0x68CA, "TargetTypeValue", Type.UINT, 0x63C0);
        define(0x63CA, "TargetType", Type.STRING, 0x63C0);
        define(0x63C5, "TagTrackUID", Type.UINT, 0x63C0);
        define(0x63C9, "TagEditionUID", Type.UINT, 0x63C0);
        define(0x63C4, "TagChapterUID", Type.UINT, 0x63C0);
        define(0x63C6, "TagAttachmentUID", Type.UINT, 0x63C0);
        define(0x67C8, "SimpleTag", Type.MASTER, -1);
        define(0x45A3, "TagName", Type.UTF8, 0x67C8);
        define(0x447A, "TagLanguage", Type.STRING, 0x67C8);
        define(0x447B, "TagLanguageBCP47", Type.STRING, 0x67C8);
        define(0x4484, "TagDefault", Type.UINT, 0x67C8);
        define(0x4487, "TagString", Type.UTF8, 0x67C8);
        define(0x4485, "TagBinary", Type.BINARY, 0x67C8);
        define(VOID, "Void", Type.BINARY, 0);
        define(CRC, "CRC-32", Type.BINARY, 0);
    }

    private static void define(long id, String name, Type type, long parent) {
        SCHEMA.put(id, new Element(name, type, parent));
    }

    /**
     * A parsed element.
     *
     * @param id the element id
     * @param offset the offset of the id's first byte
     * @param dataOffset the offset of the data's first byte
     * @param dataSize the data size, or -1 for an unknown size
     * @param end the offset just past the element
     * @param children the child elements of a master
     */
    private record Node(long id, long offset, long dataOffset, long dataSize, long end, List<Node> children) {
        Optional<Node> child(long childId) {
            return children.stream().filter(node -> node.id == childId).findFirst();
        }

        List<Node> all(long childId) {
            return children.stream().filter(node -> node.id == childId).toList();
        }
    }

    /**
     * What validation learned of a file.
     *
     * @param live whether the Segment has an unknown size, as a live stream written without seeking back has
     * @param media whether a track is a video or an audio track, which a seekable file indexes with Cues
     */
    public record Summary(boolean live, boolean media) {
    }

    private final byte[] data;
    private final Findings findings;
    private boolean media;

    private MatroskaValidator(byte[] data, Findings findings) {
        this.data = data;
        this.findings = findings;
    }

    /**
     * Validates a file.
     *
     * @param data the file
     * @param findings receives the problems
     * @return what validation learned of the file
     */
    public static Summary validate(byte[] data, Findings findings) {
        var validator = new MatroskaValidator(data, findings);
        var live = validator.run();
        return new Summary(live, validator.media);
    }

    private boolean run() {
        var top = parse(0, data.length, 0, "");
        if (top.isEmpty() || top.getFirst().id() != EBML) {
            findings.add("@0", "the file does not start with an EBML header");
            return false;
        }
        var docType = header(top.getFirst());
        var segments = top.stream().filter(node -> node.id() == SEGMENT).toList();
        findings.require(segments.size() == 1, "/", segments.size() + " segments, one expected");
        for (var node : top) {
            findings.require(node.id() == EBML || node.id() == SEGMENT || node.id() == VOID, "@" + node.offset(),
                    "element 0x" + Long.toHexString(node.id()) + " at the top level");
        }
        if (segments.isEmpty()) {
            return false;
        }
        var segment = segments.getFirst();
        segment(segment, docType);
        return segment.dataSize() < 0;
    }

    private String header(Node ebml) {
        var where = "/EBML";
        var version = uint(ebml, 0x4286, 1);
        var readVersion = uint(ebml, 0x42F7, 1);
        var maxId = uint(ebml, 0x42F2, 4);
        var maxSize = uint(ebml, 0x42F3, 8);
        findings.require(version == 1 && readVersion == 1, where, "EBML version " + version + "/" + readVersion);
        findings.require(maxId == 4, where, "EBMLMaxIDLength " + maxId + ", Matroska requires 4");
        findings.require(maxSize >= 1 && maxSize <= 8, where, "EBMLMaxSizeLength " + maxSize);
        var docType = ebml.child(0x4282).map(this::string).orElse("matroska");
        findings.require(docType.equals("matroska") || docType.equals("webm"), where, "DocType " + docType);
        var docVersion = uint(ebml, 0x4287, 1);
        var docReadVersion = uint(ebml, 0x4285, 1);
        findings.require(docReadVersion >= 1 && docVersion >= docReadVersion, where, "DocTypeVersion "
                + docVersion + " below DocTypeReadVersion " + docReadVersion);
        return docType;
    }

    private void segment(Node segment, String docType) {
        var where = "/Segment";
        for (var id : List.of(INFO, TRACKS, CUES, CHAPTERS, TAGS, ATTACHMENTS)) {
            findings.require(segment.all(id).size() <= 1, where, segment.all(id).size() + " "
                    + SCHEMA.get(id).name() + " elements, at most one allowed");
        }
        findings.require(segment.all(SEEK_HEAD).size() <= 2, where, "more than two SeekHead elements");
        var info = segment.child(INFO);
        findings.require(info.isPresent(), where, "no Info");
        info.ifPresent(node -> {
            findings.require(uint(node, 0x2AD7B1, 1_000_000) > 0, where + "/Info", "TimestampScale 0");
            node.child(0x4489).ifPresent(duration -> findings.require(number(duration) > 0, where + "/Info",
                    "Duration " + number(duration)));
            findings.require(node.child(0x4D80).isPresent() && node.child(0x5741).isPresent(), where + "/Info",
                    "MuxingApp and WritingApp are mandatory");
            node.child(0x73A4).ifPresent(uuid -> findings.require(uuid.dataSize() == 16, where + "/Info",
                    "a SegmentUUID of " + uuid.dataSize() + " bytes"));
        });
        var tracks = new HashMap<Long, Long>();
        var tracksNode = segment.child(TRACKS);
        findings.require(tracksNode.isPresent() || segment.child(CLUSTER).isEmpty(), where,
                "Clusters but no Tracks");
        tracksNode.ifPresent(node -> tracks(node, docType, tracks));
        var clusters = segment.all(CLUSTER);
        var previous = -1L;
        for (var cluster : clusters) {
            previous = cluster(cluster, tracks, previous);
        }
        var base = segment.dataOffset();
        for (var seekHead : segment.all(SEEK_HEAD)) {
            for (var seek : seekHead.all(SEEK)) {
                var id = seek.child(0x53AB).map(this::binaryId).orElse(-1L);
                var position = seek.child(0x53AC).map(this::uint).orElse(-1L);
                if (id < 0 || position < 0) {
                    findings.add(where + "/SeekHead@" + seek.offset(), "a Seek without SeekID or SeekPosition");
                    continue;
                }
                var target = elementAt(segment, base + position);
                findings.require(target != null && target.id() == id, where + "/SeekHead@" + seek.offset(),
                        "SeekPosition " + position + " does not point at element 0x" + Long.toHexString(id));
            }
        }
        segment.child(CUES).ifPresent(cues -> cues(cues, segment, tracks));
        media = tracks.containsValue(1L) || tracks.containsValue(2L);
        // RFC 9559 recommends Cues for seeking; a seekable writer of video or audio has no reason to omit them
        if (segment.dataSize() >= 0 && !clusters.isEmpty() && media) {
            findings.require(segment.child(CUES).isPresent() || clusters.size() == 1, where,
                    "a Segment of known size with " + clusters.size() + " clusters of video or audio but no Cues");
        }
    }

    private void tracks(Node tracksNode, String docType, Map<Long, Long> tracks) {
        var where = "/Segment/Tracks";
        var uids = new HashSet<Long>();
        findings.require(!tracksNode.all(TRACK_ENTRY).isEmpty(), where, "no TrackEntry");
        for (var entry : tracksNode.all(TRACK_ENTRY)) {
            var number = uint(entry, 0xD7, 0);
            var uid = uint(entry, 0x73C5, 0);
            var type = uint(entry, 0x83, 0);
            var at = where + "/TrackEntry[" + number + "]";
            findings.require(number >= 1, at, "TrackNumber missing or 0");
            findings.require(uid != 0, at, "TrackUID missing or 0");
            findings.require(tracks.put(number, type) == null, at, "duplicate TrackNumber");
            findings.require(uids.add(uid), at, "duplicate TrackUID");
            findings.require(Set.of(1L, 2L, 3L, 16L, 17L, 18L, 32L, 33L).contains(type), at, "TrackType " + type);
            var codec = entry.child(0x86).map(this::string);
            findings.require(codec.isPresent(), at, "no CodecID");
            if (docType.equals("webm") && codec.isPresent()) {
                findings.require(WEBM_CODECS.contains(codec.get()), at, "codec " + codec.get() + " in WebM");
            }
            if (type == 1) {
                var video = entry.child(VIDEO);
                findings.require(video.isPresent(), at, "a video track without Video");
                video.ifPresent(node -> {
                    var width = uint(node, 0xB0, 0);
                    var height = uint(node, 0xBA, 0);
                    findings.require(width > 0 && height > 0, at + "/Video", "PixelWidth and PixelHeight " + width
                            + "x" + height);
                    var cropWidth = width - uint(node, 0x54CC, 0) - uint(node, 0x54DD, 0);
                    var cropHeight = height - uint(node, 0x54AA, 0) - uint(node, 0x54BB, 0);
                    findings.require(cropWidth > 0 && cropHeight > 0, at + "/Video", "the crop leaves no picture");
                    node.child(0x54B0).ifPresent(w -> findings.require(uint(w) > 0, at + "/Video", "DisplayWidth 0"));
                    node.child(0x54BA).ifPresent(h -> findings.require(uint(h) > 0, at + "/Video",
                            "DisplayHeight 0"));
                });
            } else if (type == 2) {
                var audio = entry.child(AUDIO);
                findings.require(audio.isPresent(), at, "an audio track without Audio");
                audio.ifPresent(node -> {
                    var rate = node.child(0xB5).map(this::number).orElse(8000.0);
                    findings.require(rate > 0, at + "/Audio", "SamplingFrequency " + rate);
                    findings.require(uint(node, 0x9F, 1) >= 1, at + "/Audio", "Channels 0");
                });
            }
            entry.child(0x23E383).ifPresent(duration -> findings.require(uint(duration) > 0, at,
                    "DefaultDuration 0"));
        }
    }

    private long cluster(Node cluster, Map<Long, Long> tracks, long previous) {
        var where = "/Segment/Cluster@" + cluster.offset();
        var timestamp = cluster.child(0xE7);
        if (timestamp.isEmpty()) {
            findings.add(where, "no Timestamp");
            return previous;
        }
        var time = uint(timestamp.get());
        findings.require(cluster.all(0xE7).size() == 1, where, "more than one Timestamp");
        for (var child : cluster.children()) {
            if (child.id() == 0xA3) {
                block(child, where + "/SimpleBlock@" + child.offset(), tracks, time, true);
            } else if (child.id() == BLOCK_GROUP) {
                var blocks = child.all(0xA1);
                findings.require(blocks.size() == 1, where + "/BlockGroup@" + child.offset(), blocks.size()
                        + " Block elements, one required");
                for (var block : blocks) {
                    block(block, where + "/BlockGroup/Block@" + block.offset(), tracks, time, false);
                }
            }
        }
        cluster.child(0xAB).ifPresent(prevSize -> findings.require(previous >= 0, where,
                "PrevSize in the first Cluster"));
        return cluster.offset();
    }

    private void block(Node block, String where, Map<Long, Long> tracks, long clusterTime, boolean simple) {
        var position = block.dataOffset();
        var end = block.end();
        var track = vint(position, end, false);
        if (track == null) {
            findings.add(where, "a malformed track number");
            return;
        }
        findings.require(tracks.containsKey(track[0]), where, "track " + track[0] + " is not in Tracks");
        position += track[1];
        if (end - position < 3) {
            findings.add(where, "shorter than its header");
            return;
        }
        var relative = (short) IsoBmff.u16(data, position);
        findings.require(clusterTime + relative >= 0, where, "a negative timestamp");
        var flags = data[(int) position + 2] & 0xFF;
        if (!simple) {
            findings.require((flags & 0x80) == 0, where, "the keyframe bit is set in a Block");
        }
        position += 3;
        var lacing = (flags >> 1) & 3;
        if (lacing == 0) {
            return;
        }
        if (end - position < 1) {
            findings.add(where, "no lace count");
            return;
        }
        var frames = (data[(int) position++] & 0xFF) + 1;
        var sizes = new long[frames];
        var total = 0L;
        if (lacing == 1) {
            for (var index = 0; index < frames - 1; index++) {
                var size = 0L;
                int b;
                do {
                    if (position >= end) {
                        findings.add(where, "Xiph lace sizes run past the block");
                        return;
                    }
                    b = data[(int) position++] & 0xFF;
                    size += b;
                } while (b == 255);
                sizes[index] = size;
                total += size;
            }
        } else if (lacing == 3) {
            for (var index = 0; index < frames - 1; index++) {
                var value = vint(position, end, false);
                if (value == null) {
                    findings.add(where, "a malformed EBML lace size");
                    return;
                }
                position += value[1];
                var size = value[0];
                if (index > 0) {
                    var bias = (1L << (7 * value[1] - 1)) - 1;
                    size = sizes[index - 1] + value[0] - bias;
                }
                sizes[index] = size;
                if (size < 0) {
                    findings.add(where, "a negative EBML lace size");
                    return;
                }
                total += size;
            }
        } else {
            var remaining = end - position;
            findings.require(remaining % frames == 0, where, remaining + " bytes do not split into " + frames
                    + " fixed-size frames");
            return;
        }
        findings.require(end - position - total > 0, where, "the last laced frame is empty or the sizes exceed the"
                + " block");
    }

    private void cues(Node cues, Node segment, Map<Long, Long> tracks) {
        var where = "/Segment/Cues";
        var previous = -1L;
        findings.require(!cues.all(CUE_POINT).isEmpty(), where, "no CuePoint");
        for (var point : cues.all(CUE_POINT)) {
            var at = where + "/CuePoint@" + point.offset();
            var time = point.child(0xB3).map(this::uint).orElse(-1L);
            findings.require(time >= 0, at, "no CueTime");
            findings.require(time >= previous, at, "CueTime " + time + " before " + previous);
            previous = Math.max(previous, time);
            var positions = point.all(CUE_TRACK_POSITIONS);
            findings.require(!positions.isEmpty(), at, "no CueTrackPositions");
            for (var position : positions) {
                var track = uint(position, 0xF7, 0);
                findings.require(tracks.containsKey(track), at, "CueTrack " + track + " is not in Tracks");
                var clusterPosition = position.child(0xF1).map(this::uint).orElse(-1L);
                var cluster = clusterPosition >= 0 ? elementAt(segment, segment.dataOffset() + clusterPosition) : null;
                if (cluster == null || cluster.id() != CLUSTER) {
                    findings.add(at, "CueClusterPosition " + clusterPosition + " does not point at a Cluster");
                    continue;
                }
                var relative = position.child(0xF0).map(this::uint);
                if (relative.isPresent()) {
                    var target = cluster.dataOffset() + relative.get();
                    var block = cluster.children().stream().filter(node -> node.offset() == target).findFirst();
                    findings.require(block.isPresent() && (block.get().id() == 0xA3 || block.get().id() == BLOCK_GROUP),
                            at, "CueRelativePosition " + relative.get() + " does not point at a block");
                }
            }
        }
    }

    private Node elementAt(Node segment, long offset) {
        for (var child : segment.children()) {
            if (child.offset() == offset) {
                return child;
            }
        }
        return null;
    }

    private List<Node> parse(long from, long to, long parentId, String path) {
        var nodes = new ArrayList<Node>();
        var position = from;
        while (position < to) {
            var id = vint(position, to, true);
            if (id == null) {
                findings.add(path + "@" + position, "a malformed element id");
                break;
            }
            var sizeAt = position + id[1];
            var size = vint(sizeAt, to, false);
            if (size == null) {
                findings.add(path + "@" + position, "a malformed element size");
                break;
            }
            var element = SCHEMA.get(id[0]);
            var name = element != null ? element.name() : "0x" + Long.toHexString(id[0]);
            var where = path + "/" + name + "@" + position;
            var dataOffset = sizeAt + size[1];
            var unknown = size[0] == (1L << (7 * size[1])) - 1;
            long dataSize = unknown ? -1 : size[0];
            if (unknown && id[0] != SEGMENT && id[0] != CLUSTER) {
                findings.add(where, "an unknown size, which only Segment and Cluster may have");
            }
            if (element != null && element.parent() > 0 && parentId != element.parent()
                    && !(element.parent() == -1)) {
                findings.add(where, "inside 0x" + Long.toHexString(parentId) + ", not inside its parent");
            }
            long end;
            if (unknown) {
                end = unknownEnd(id[0], dataOffset, to);
            } else {
                end = dataOffset + dataSize;
                if (end > to) {
                    findings.add(where, "size " + dataSize + " runs past its parent (" + (to - dataOffset)
                            + " bytes left)");
                    break;
                }
            }
            List<Node> children = List.of();
            if (element != null && element.type() == Type.MASTER) {
                children = parse(dataOffset, end, id[0], path + "/" + name);
                crc(children, dataOffset, end, where);
            } else if (element != null) {
                value(element, dataOffset, end - dataOffset, where);
            }
            nodes.add(new Node(id[0], position, dataOffset, dataSize, end, children));
            position = end;
        }
        return nodes;
    }

    /** Returns where an element of unknown size ends: at the first element that cannot be its child. */
    private long unknownEnd(long id, long from, long to) {
        var position = from;
        while (position < to) {
            var child = vint(position, to, true);
            var size = child == null ? null : vint(position + child[1], to, false);
            if (child == null || size == null) {
                return to;
            }
            var element = SCHEMA.get(child[0]);
            var level1 = child[0] == CLUSTER || child[0] == CUES || child[0] == TAGS || child[0] == CHAPTERS
                    || child[0] == ATTACHMENTS || child[0] == SEEK_HEAD || child[0] == INFO || child[0] == TRACKS;
            if (id == CLUSTER && (level1 || child[0] == SEGMENT || child[0] == EBML)) {
                return position;
            }
            if (id == SEGMENT && (child[0] == SEGMENT || child[0] == EBML)) {
                return position;
            }
            var unknown = size[0] == (1L << (7 * size[1])) - 1;
            if (unknown) {
                position = unknownEnd(child[0], position + child[1] + size[1], to);
            } else {
                position += child[1] + size[1] + size[0];
            }
            if (element == null && position > to) {
                return to;
            }
        }
        return Math.min(position, to);
    }

    private void crc(List<Node> children, long from, long to, String where) {
        for (var index = 0; index < children.size(); index++) {
            var child = children.get(index);
            if (child.id() != CRC) {
                continue;
            }
            findings.require(index == 0, where, "CRC-32 is not the first child");
            if (child.dataSize() != 4) {
                findings.add(where, "a CRC-32 of " + child.dataSize() + " bytes");
                continue;
            }
            var crc = new CRC32();
            crc.update(data, (int) child.end(), (int) (to - child.end()));
            var stored = (data[(int) child.dataOffset()] & 0xFFL) | (data[(int) child.dataOffset() + 1] & 0xFFL) << 8
                    | (data[(int) child.dataOffset() + 2] & 0xFFL) << 16
                    | (data[(int) child.dataOffset() + 3] & 0xFFL) << 24;
            findings.require(crc.getValue() == stored, where, "CRC-32 mismatch");
        }
    }

    private void value(Element element, long offset, long size, String where) {
        switch (element.type()) {
            case UINT, INT -> findings.require(size <= 8, where, "an integer of " + size + " bytes");
            case FLOAT -> findings.require(size == 0 || size == 4 || size == 8, where, "a float of " + size
                    + " bytes");
            case DATE -> findings.require(size == 0 || size == 8, where, "a date of " + size + " bytes");
            case STRING -> {
                for (var i = 0; i < size; i++) {
                    var c = data[(int) (offset + i)] & 0xFF;
                    if (c == 0) {
                        for (var rest = i; rest < size; rest++) {
                            findings.require(data[(int) (offset + rest)] == 0, where, "characters after the padding");
                        }
                        break;
                    }
                    if (c < 0x20 || c > 0x7E) {
                        findings.add(where, "a string character 0x" + Integer.toHexString(c) + " outside printable"
                                + " ASCII");
                        break;
                    }
                }
            }
            case UTF8 -> {
                var decoder = StandardCharsets.UTF_8.newDecoder();
                try {
                    var length = (int) size;
                    while (length > 0 && data[(int) offset + length - 1] == 0) {
                        length--;
                    }
                    decoder.decode(java.nio.ByteBuffer.wrap(data, (int) offset, length));
                } catch (java.nio.charset.CharacterCodingException exception) {
                    findings.add(where, "a UTF-8 string that does not decode");
                }
            }
            default -> {
            }
        }
    }

    /**
     * Reads a variable-size integer.
     *
     * @return the value and its length in bytes, or {@code null} when malformed
     */
    private long[] vint(long position, long end, boolean id) {
        if (position >= end) {
            return null;
        }
        var first = data[(int) position] & 0xFF;
        if (first == 0) {
            return null;
        }
        var length = Integer.numberOfLeadingZeros(first) - 23;
        if (position + length > end || id && length > 4) {
            return null;
        }
        long value = id ? first : first & (0xFF >> length);
        for (var i = 1; i < length; i++) {
            value = value << 8 | data[(int) position + i] & 0xFF;
        }
        if (id) {
            var bits = 7 * length;
            var payload = value & ((1L << bits) - 1);
            // all ones is reserved; Matroska's ChapterDisplay predates the rule against all zeros (0x80)
            if (payload == (1L << bits) - 1 || payload == 0 && length > 1) {
                return null;
            }
        }
        return new long[]{value, length};
    }

    private long uint(Node parent, long id, long fallback) {
        return parent.child(id).map(this::uint).orElse(fallback);
    }

    private long uint(Node node) {
        var value = 0L;
        for (var i = 0; i < node.dataSize(); i++) {
            value = value << 8 | data[(int) node.dataOffset() + i] & 0xFF;
        }
        return value;
    }

    private long binaryId(Node node) {
        return uint(node);
    }

    private double number(Node node) {
        if (node.dataSize() == 4) {
            return Float.intBitsToFloat((int) uint(node));
        }
        if (node.dataSize() == 8) {
            return Double.longBitsToDouble(uint(node));
        }
        return 0;
    }

    private String string(Node node) {
        var text = new String(data, (int) node.dataOffset(), (int) node.dataSize(), StandardCharsets.UTF_8);
        var nul = text.indexOf('\0');
        return nul >= 0 ? text.substring(0, nul) : text;
    }
}
