package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.container.internal.TimecodeTags;
import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;

/**
 * Edits the iTunes metadata of an MP4, M4A or MOV file.
 *
 * <p>Tags live in {@code moov/udta/meta/ilst}: one item per key, each holding {@code data} atoms
 * (UTF-8 text, the binary forms of {@code trkn}, {@code disk}, {@code cpil} and {@code gnre}, or
 * the big-endian integers of {@link #INTEGER_ATOMS} and of any other atom the file stores as a
 * signed or unsigned integer, read and written as decimal text), cover art in {@code covr} and
 * free-form items in {@code ----} atoms named by {@code mean} and {@code name}. Items with another
 * binary payload or a non-Apple {@code mean} are opaque and kept.
 *
 * <p>QuickTime keyed metadata ({@link Mp4KeyedMetadata}), a {@code meta} box of handler {@code mdta} in
 * {@code moov} or in its {@code udta}, is read under its key names after the {@code ilst} items, which take
 * precedence for a key both hold. A rewrite stores a key in the keyed metadata boxes that held it (and in the
 * {@code ilst} too when that held it as well), and a reverse-DNS key no box of the file holds, such as
 * {@code com.apple.quicktime.make}, in the file's first keyed metadata box or in a new one at the end of
 * {@code moov}. The 3GPP location information box ({@code loci}, {@link LocationInformation}) of {@code udta} is read
 * as the key {@code loci}, its coordinates in ISO 6709 form, and kept while they are unchanged.
 *
 * <p>The XMP packet is read from a top-level {@code uuid} box of type {@code BE7ACFCB-97A9-42E8-9C71-999491E3AFAC}
 * or, failing one, from a {@code udta/XMP_} atom (XMP Specification Part 3). It is kept where it is while it is
 * unchanged; a new packet goes where the file kept its packet, or for a file without one in a {@code uuid} box after
 * {@code moov}, or in a {@code udta/XMP_} atom for a QuickTime file (major brand {@code qt  } or no {@code ftyp}). A
 * new packet whose {@code uuid} box fits the old one takes its place, the rest of the old box becoming a zeroed
 * {@code free} box, so the media data stays where it is; otherwise the new box follows {@code moov}, and the old one
 * joins the space of {@code moov} when it follows it or becomes a zeroed {@code free} box of its size.
 *
 * <p>Chapters are written twice: as a Nero {@code chpl} box in {@code udta}, which stores start
 * times only, and as a QuickTime chapter track ({@link Mp4ChapterTrack}) that every audio and
 * video track references through {@code tref/chap}, whose samples go in an {@code mdat} box of
 * their own placed right before {@code moov}. Reading prefers {@code chpl}, unless the chapter
 * track completes the chapters or titles a full {@code chpl} cut
 * ({@link Mp4ChapterTrack#supersedes(List, List)}). Chapter tracks are
 * kept while the chapters are unchanged and replaced otherwise; the {@code mdat} holding only
 * the samples of a replaced chapter track is reused as free space.
 *
 * <p>The track tags of every track but the chapter tracks come from the track's {@code udta}: its
 * {@code name} box as the title, its {@code kind} boxes of the DASH role scheme and the enabled
 * flag of its {@code tkhd} as the dispositions ({@link Mp4TrackMetadata}), the items of its
 * iTunes {@code meta} and its QuickTime text atoms as the other tags, and after them the keyed metadata boxes of the
 * {@code trak} and of its {@code udta}, which the {@code udta} takes precedence over; the track identifier is the
 * {@code track_ID}, or the next free identifier for a track whose {@code track_ID} is not
 * positive or repeats an earlier one. A track whose tags change gets a new {@code udta} with those
 * boxes (its other children kept) and the enabled flag its dispositions give; the first video and
 * the first audio track stay enabled when no track of their kind would be. Its keys are routed as those of the movie:
 * to the keyed metadata boxes that held them (and to the {@code udta} too when that held them as well), a reverse-DNS
 * key neither holds to its first keyed metadata box or a new {@code trak/meta} box, the others to the {@code udta}.
 *
 * <p>The {@code timecode} key is read from the first QuickTime timecode track ({@link Mp4TimecodeTrack}) that gives a
 * timecode, which takes precedence over a free-form {@code timecode} item, and written as one, except in a CMAF track
 * file, which carries one track, where it stays an item. A changed timecode replaces the file's timecode track by a
 * new one counted at the frame rate of the first video track (its sample in the {@code mdat} written before
 * {@code moov}, the track keeping its identifier, place and tag boxes), removes the other timecode tracks and makes
 * every video track reference it; a timecode track whose sample is in a movie fragment instead gets the new frame
 * number written into that sample and its drop-frame flag set, at its own rate. A removed timecode removes the
 * timecode tracks and their references; the track fragments ({@code traf}) and random access boxes ({@code tfra})
 * of one that movie fragments name become {@code free} boxes of their size, so no offset moves (a following track
 * fragment that counts its data from the end of theirs gets the data offsets of its track runs corrected). An unchanged
 * timecode, or one the file holds as an item only, is kept as it is.
 *
 * <p>The rewrite builds a new {@code moov}. When it fits the old {@code moov} plus the
 * {@code free} boxes that follow it, the remainder becomes a {@code free} box and nothing else
 * moves; when {@code moov} is the last box of the file it is rewritten in place at any size.
 * Otherwise the data after the old {@code moov} shifts, and every absolute offset that points
 * past it is corrected: the chunk offsets ({@code stco}, {@code co64}) inside the new
 * {@code moov}, where a {@code stco} box whose offsets no longer fit 32 bits becomes a
 * {@code co64} box, the explicit base data offsets of movie fragments ({@code tfhd}) and the
 * fragment offsets of the random access index ({@code tfra}).
 *
 * <p>A file whose movie is a compressed movie box ({@code !mov}, ISO/IEC 14496-12 clause 8.20) is read
 * from the decompressed movie, and a rewrite replaces the compressed box with an uncompressed
 * {@code moov}. The offsets of such a file count in the file with its compressed boxes
 * uncompressed, so the explicit offsets of the movie fragments after a rewritten compressed movie
 * move by what the rewrite and the compression together changed; a compressed movie fragment ({@code !mof}) stating
 * explicit base data offsets is decompressed, corrected and compressed again in a box of its old size, its gzip
 * header comment taking up what the compression saved. A QuickTime
 * compressed movie ({@code moov} holding a {@code cmov} atom) is read from its decompressed movie as well, and a
 * rewrite stores that movie uncompressed; its offsets count in the file as it is.
 */
final class Mp4TagFile implements TagFile {
    /**
     * The largest number of top-level boxes read.
     */
    private static final int MAX_TOP_LEVEL = 1 << 16;

    /**
     * The first character of the QuickTime and iTunes text atoms, {@code 0xA9}.
     */
    private static final char COPYRIGHT_SIGN = (char) 0xA9;

    /**
     * The {@code mean} of iTunes free-form items.
     */
    private static final String APPLE_MEAN = "com.apple.iTunes";

    /**
     * The data type of UTF-8 text.
     */
    private static final int TYPE_UTF8 = 1;

    /**
     * The data type of UTF-16 text.
     */
    private static final int TYPE_UTF16 = 2;

    /**
     * The data type of JPEG pictures.
     */
    private static final int TYPE_JPEG = 13;

    /**
     * The data type of PNG pictures.
     */
    private static final int TYPE_PNG = 14;

    /**
     * The data type of signed big-endian integers.
     */
    private static final int TYPE_INTEGER = 21;

    /**
     * The data type of unsigned big-endian integers.
     */
    private static final int TYPE_UNSIGNED = 22;

    /**
     * The data type of BMP pictures.
     */
    private static final int TYPE_BMP = 27;

    /**
     * The largest value a 32-bit field holds.
     */
    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * The type of a compressed movie box (ISO/IEC 14496-12 clause 8.20).
     */
    private static final String COMPRESSED_MOVIE = "!mov";

    /**
     * The extended type of the {@code uuid} box holding XMP, {@code BE7ACFCB-97A9-42E8-9C71-999491E3AFAC}.
     */
    private static final byte[] XMP_UUID = {(byte) 0xBE, 0x7A, (byte) 0xCF, (byte) 0xCB, (byte) 0x97, (byte) 0xA9, 0x42,
            (byte) 0xE8, (byte) 0x9C, 0x71, (byte) 0x99, (byte) 0x94, (byte) 0x91, (byte) 0xE3, (byte) 0xAF, (byte) 0xAC};

    /**
     * The QuickTime user data atom holding XMP.
     */
    private static final String XMP_ATOM = "XMP_";

    /**
     * The key the coordinates of the 3GPP location information box are read under.
     */
    private static final String LOCI = "loci";

    /**
     * The key of the tag stored as a QuickTime timecode track.
     */
    private static final String TIMECODE = "timecode";

    /**
     * The type of a compressed movie fragment box (ISO/IEC 14496-12 clause 8.20).
     */
    private static final String COMPRESSED_FRAGMENT = "!mof";

    /**
     * The compressed box types, each standing for the box its payload decompresses to.
     */
    private static final Set<String> COMPRESSED_TYPES = Set.of(COMPRESSED_MOVIE, COMPRESSED_FRAGMENT, "!six", "!ssx");

    /**
     * The gzip header flag of a comment field (RFC 1952 section 2.3.1).
     */
    private static final int GZIP_COMMENT = 0x10;

    /**
     * The largest movie fragment box read into memory to locate a sample or correct its offsets.
     */
    private static final long MAX_FRAGMENT = 64L << 20;

    /**
     * The {@code tfhd} flag of an explicit base data offset (ISO/IEC 14496-12 clause 8.8.7).
     */
    private static final int TFHD_BASE_DATA_OFFSET = 0x1;

    /**
     * The {@code tfhd} flag that makes the start of the movie fragment the base data offset.
     */
    private static final int TFHD_DEFAULT_BASE_IS_MOOF = 0x20000;

    /**
     * The {@code trun} flag of a data offset relative to the base data offset (ISO/IEC 14496-12 clause 8.8.8).
     */
    private static final int TRUN_DATA_OFFSET = 0x1;

    /**
     * The width in bytes of the iTunes atoms that hold a signed integer: tempo ({@code tmpo}),
     * content rating ({@code rtng}), media kind ({@code stik}), gapless playback ({@code pgap}),
     * HD video ({@code hdvd}), TV season and episode ({@code tvsn}, {@code tves}), the store
     * identifiers ({@code plID}, {@code cnID}, {@code atID}, {@code geID}, {@code sfID},
     * {@code cmID}), account kind ({@code akID}) and show movement ({@code shwm}).
     */
    static final Map<String, Integer> INTEGER_ATOMS = Map.ofEntries(Map.entry("tmpo", 2), Map.entry("rtng", 1),
            Map.entry("stik", 1), Map.entry("pgap", 1), Map.entry("hdvd", 1), Map.entry("tvsn", 4),
            Map.entry("tves", 4), Map.entry("plID", 8), Map.entry("cnID", 4), Map.entry("atID", 4),
            Map.entry("geID", 4), Map.entry("sfID", 4), Map.entry("cmID", 4), Map.entry("akID", 1),
            Map.entry("shwm", 1));

    /**
     * The integer atoms that hold a flag, which also take {@code true}, {@code yes}, {@code false} and
     * {@code no}.
     */
    private static final Set<String> FLAG_ATOMS = Set.of("pgap", "shwm");

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The top-level boxes as offset, size and header size, in file order.
     */
    private final List<long[]> topLevel;

    /**
     * The types of the top-level boxes, parallel to {@link #topLevel}.
     */
    private final List<String> topLevelTypes;

    /**
     * The index of {@code moov} in {@link #topLevel}.
     */
    private final int moovIndex;

    /**
     * The bytes of {@code moov}, header included.
     */
    private final byte[] moov;

    /**
     * The bytes the compressed movie box saved against the {@code moov} it holds, or {@code 0} when the
     * movie box is not compressed: the offsets of the movie fragments count in the file with that box
     * uncompressed, so they move back by this much when the box is rewritten.
     */
    private final long movieSaved;

    /**
     * The identifiers of the QuickTime chapter text tracks.
     */
    private final Set<Long> chapterTracks;

    /**
     * The decoded tags.
     */
    private final MediaTags tags;

    /**
     * The opaque {@code ilst} items, each a complete box.
     */
    private final List<byte[]> opaqueItems;

    /**
     * The integer form of the atoms the file stores as integers, by atom code.
     */
    private final Map<String, IntegerForm> integerForms;

    /**
     * The tracks whose tags are read and written, in movie order, by {@code track_ID}.
     */
    private final Map<Long, TrackInfo> trackInfo = new LinkedHashMap<>();

    /**
     * The keyed metadata boxes, those of {@code moov} first.
     */
    private final List<KeyedSlot> keyedSlots = new ArrayList<>();

    /**
     * The indexes in {@link #keyedSlots} of the boxes holding each decoded key, by normalized key in lower case.
     */
    private final Map<String, Set<Integer>> keyedKeys = new HashMap<>();

    /**
     * The normalized keys in lower case that the {@code ilst} items and the QuickTime text atoms of {@code udta}
     * hold.
     */
    private final Set<String> ilstKeys = new HashSet<>();

    /**
     * The index in {@link #topLevel} of the {@code uuid} box holding XMP, or {@code -1}.
     */
    private final int xmpIndex;

    /**
     * Whether the file is a QuickTime movie: major brand {@code qt  }, or no {@code ftyp} box.
     */
    private final boolean quickTime;

    /**
     * Whether the file is a CMAF track file (compatible brand {@code cmfc} or {@code cmf2}), which carries one track,
     * so that its timecode stays an iTunes item.
     */
    private final boolean cmaf;

    /**
     * The QuickTime timecode tracks, in movie order; empty for a CMAF track file.
     */
    private final List<TimecodeInfo> timecodeTracks = new ArrayList<>();

    /**
     * The index in {@link #timecodeTracks} of the first track that gives a timecode, or {@code -1}.
     */
    private int timecodeTarget = -1;

    /**
     * The free-form {@code timecode} items of the {@code ilst}, each a complete box, which a timecode track
     * overrides: kept while the timecode is unchanged.
     */
    private final List<byte[]> timecodeItems = new ArrayList<>();

    /**
     * The uncompressed sizes of the compressed top-level boxes, by index in {@link #topLevel}.
     */
    private final Map<Integer, Long> uncompressedSizes = new HashMap<>();

    /**
     * The coordinates of the first decodable {@code loci} box of {@code udta}, or {@code null}.
     */
    private String lociValue;

    /**
     * The XMP packet, or {@code null}.
     */
    private XmpPacket xmp;

    /**
     * Parses the top-level boxes and the metadata of {@code moov}.
     *
     * @param source the file to parse; not closed
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the box structure is corrupt or {@code moov} is missing
     */
    Mp4TagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var size = source.size();
        var boxes = new ArrayList<long[]>();
        var types = new ArrayList<String>();
        var position = 0L;
        var moovAt = -1;
        var compressedAt = -1;
        var xmpBoxes = new ArrayList<Integer>();
        var brand = (String) null;
        var compatibleCmaf = false;
        while (size - position >= 8) {
            if (boxes.size() >= MAX_TOP_LEVEL) {
                throw new MediaException.Malformed("more than " + MAX_TOP_LEVEL + " top-level MP4 boxes");
            }
            var header = TagIo.read(source, position, Math.min(16, size - position), limits);
            long boxSize = ByteArrays.u32be(header, 0);
            if (boxSize == 1) {
                if (header.length < 16) {
                    throw new MediaException.Malformed("truncated 64-bit MP4 box size at offset " + position);
                }
                boxSize = ByteArrays.i64be(header, 8);
                if (boxSize < 16) {
                    throw new MediaException.Malformed("invalid 64-bit MP4 box size at offset " + position);
                }
            } else if (boxSize == 0) {
                boxSize = size - position;
            } else if (boxSize < 8) {
                throw new MediaException.Malformed("invalid MP4 box size " + boxSize + " at offset " + position);
            }
            if (boxSize > size - position) {
                boxSize = size - position;
            }
            var type = Mp4Boxes.type(header, 4);
            var headerSize = ByteArrays.u32be(header, 0) == 1 ? 16 : 8;
            if (type.equals("moov") && moovAt < 0) {
                moovAt = boxes.size();
            } else if (type.equals(COMPRESSED_MOVIE) && compressedAt < 0) {
                compressedAt = boxes.size();
            } else if (type.equals("uuid") && boxSize >= headerSize + 16
                    && Arrays.equals(TagIo.read(source, position + headerSize, 16, limits), XMP_UUID)) {
                xmpBoxes.add(boxes.size());
            } else if (type.equals("ftyp") && boxes.isEmpty() && boxSize >= headerSize + 4) {
                var brands = TagIo.read(source, position + headerSize, Math.min(boxSize - headerSize, 1024), limits);
                brand = Mp4Boxes.type(brands, 0);
                for (var at = 8; at + 4 <= brands.length; at += 4) {
                    var compatible = Mp4Boxes.type(brands, at);
                    compatibleCmaf |= compatible.equals("cmfc") || compatible.equals("cmf2");
                }
            }
            boxes.add(new long[]{position, boxSize, headerSize});
            types.add(type);
            position += boxSize;
        }
        if (moovAt < 0 && compressedAt < 0) {
            throw new MediaException.Malformed("MP4 file without a moov box");
        }
        this.topLevel = List.copyOf(boxes);
        this.topLevelTypes = List.copyOf(types);
        this.moovIndex = moovAt >= 0 ? moovAt : compressedAt;
        var moovBox = boxes.get(moovIndex);
        this.moov = moovAt >= 0 ? quickTimeMovie(TagIo.read(source, moovBox[0], moovBox[1], limits))
                : decompressMovie(moovBox);
        this.movieSaved = moovAt >= 0 ? 0 : moov.length - moovBox[1];
        this.chapterTracks = findChapterTracks();
        this.quickTime = brand == null || brand.equals("qt  ");
        this.cmaf = compatibleCmaf;
        if (!cmaf) {
            findTimecodeTracks();
        }
        var xmpAt = -1;
        for (var candidate : xmpBoxes) {
            if (readXmpBox(boxes.get(candidate))) {
                xmpAt = candidate;
                break;
            }
        }
        this.xmpIndex = xmpAt;
        var opaque = new ArrayList<byte[]>();
        this.integerForms = new HashMap<>();
        var decoded = decode(opaque);
        this.opaqueItems = List.copyOf(opaque);
        decodeTracks();
        var withTracks = decoded.toBuilder();
        for (var info : trackInfo.values()) {
            withTracks.track(info.tags());
        }
        if (timecodeTarget >= 0) {
            withTracks.set(TIMECODE, timecodeTracks.get(timecodeTarget).timecode().toString());
        }
        this.tags = withTracks.build();
    }

    /**
     * Reads the packet of the top-level XMP {@code uuid} box into {@link #xmp}.
     *
     * @param box the offset, size and header size of the box
     * @return {@code true} when the box holds a packet, {@code false} when it does not parse as one
     * @throws MediaException.LimitExceeded if the box is larger than the byte limit
     */
    private boolean readXmpBox(long[] box) {
        var start = box[0] + box[2] + 16;
        try {
            xmp = XmpPacket.parse(TagIo.read(source, start, box[0] + box[1] - start, limits));
            return true;
        } catch (MediaException.Malformed exception) {
            return false;
        }
    }

    /**
     * Describes a QuickTime timecode track.
     *
     * @param trackId the {@code track_ID}
     * @param fragmented whether its first sample is in a movie fragment rather than in the sample tables of
     * {@code moov}
     * @param fields the fields of its {@code tmcd} sample entry after the data reference index, or {@code null} when
     * its first sample entry is not a readable {@code tmcd} entry
     * @param sampleOffset the file offset of its first sample, or {@code -1} when it cannot be located
     * @param timecode the timecode it gives, or {@code null} when it gives none
     */
    private record TimecodeInfo(long trackId, boolean fragmented, byte[] fields, long sampleOffset, Timecode timecode) {
    }

    /**
     * Collects the QuickTime timecode tracks (handler {@code tmcd}) and the timecode the first readable one gives,
     * as the demuxer reads it.
     */
    private void findTimecodeTracks() {
        for (var trak : Mp4Boxes.children(moov, moovPayload(), moov.length)) {
            if (!trak.type().equals("trak") || !handlerType(trak).equals(Mp4TimecodeTrack.TYPE)) {
                continue;
            }
            var id = trackId(moov, trak);
            var stbl = sampleTable(trak);
            byte[] fields = null;
            var count = 0L;
            var size = 0L;
            var offset = -1L;
            if (stbl != null) {
                var tables = Mp4Boxes.children(moov, stbl.payload(), stbl.end());
                var stsd = Mp4Boxes.find(tables, "stsd");
                if (stsd != null && stsd.end() - stsd.payload() >= 8) {
                    var entries = Mp4Boxes.children(moov, stsd.payload() + 8, stsd.end());
                    if (!entries.isEmpty() && entries.getFirst().type().equals(Mp4TimecodeTrack.TYPE)
                            && entries.getFirst().end() - entries.getFirst().payload()
                            >= 8 + Mp4TimecodeTrack.ENTRY_FIELDS) {
                        var start = entries.getFirst().payload() + 8;
                        fields = Arrays.copyOfRange(moov, start, start + Mp4TimecodeTrack.ENTRY_FIELDS);
                    }
                }
                var stsz = Mp4Boxes.find(tables, "stsz");
                if (stsz != null && stsz.end() - stsz.payload() >= 12) {
                    size = ByteArrays.u32be(moov, stsz.payload() + 4);
                    count = ByteArrays.u32be(moov, stsz.payload() + 8);
                    if (size == 0 && count > 0 && stsz.end() - stsz.payload() >= 16) {
                        size = ByteArrays.u32be(moov, stsz.payload() + 12);
                    }
                }
            }
            var fragmented = count == 0;
            if (fragmented) {
                var located = fragmentSample(id);
                if (located != null) {
                    offset = located[0];
                    size = located[1];
                }
            } else {
                var chunks = chunkOffsets(trak);
                offset = chunks.isEmpty() ? -1 : chunks.getFirst();
            }
            Timecode timecode = null;
            if (fields != null && offset >= 0 && size >= Mp4TimecodeTrack.SAMPLE_SIZE && size <= 64
                    && offset + size <= source.size()) {
                timecode = Mp4TimecodeTrack.read(fields, TagIo.read(source, offset, size, limits));
            }
            if (timecode != null && timecodeTarget < 0) {
                timecodeTarget = timecodeTracks.size();
            }
            timecodeTracks.add(new TimecodeInfo(id, fragmented, fields, offset, timecode));
        }
    }

    /**
     * Returns whether an {@code ilst} item is a free-form item of the {@code timecode} key.
     *
     * @param item the item box
     * @return {@code true} for such an item
     */
    private boolean isTimecodeItem(Mp4Boxes.Box item) {
        if (!item.type().equals("----")) {
            return false;
        }
        var scratch = MediaTags.builder();
        return decodeItem(item, scratch, new HashSet<>()) && scratch.build().contains(TIMECODE);
    }

    /**
     * Locates the first sample of a track whose samples are in movie fragments (ISO/IEC 14496-12 clause 8.8): the
     * first sample of the first track run of the first track fragment that names the track.
     *
     * @param trackId the {@code track_ID}
     * @return the file offset and the size of the sample, or {@code null} when no fragment names the track, the
     * sample lies in a compressed box or its offset falls outside the file
     */
    private long[] fragmentSample(long trackId) {
        var virtual = 0L;
        for (var index = 0; index < topLevel.size(); index++) {
            var box = topLevel.get(index);
            var type = topLevelTypes.get(index);
            byte[] fragment = null;
            var payload = 0;
            if (type.equals("moof") && box[1] <= MAX_FRAGMENT) {
                fragment = TagIo.read(source, box[0], box[1], limits);
                payload = (int) box[2];
            } else if (type.equals(COMPRESSED_FRAGMENT)) {
                fragment = inflate(TagIo.read(source, box[0] + box[2], box[1] - box[2], limits));
            }
            if (fragment != null) {
                var found = firstFragmentSample(fragment, payload, virtual, trackId);
                if (found != null) {
                    var real = realOffset(found[0]);
                    return real < 0 || real + found[1] > source.size() ? null : new long[]{real, found[1]};
                }
            }
            virtual += virtualSize(index);
        }
        return null;
    }

    /**
     * Finds the first sample of a track in a movie fragment.
     *
     * @param fragment the bytes of the fragment
     * @param from the index of its first child
     * @param start the offset of the fragment in the file with its compressed boxes uncompressed
     * @param trackId the {@code track_ID}
     * @return the offset of the sample in the file with its compressed boxes uncompressed and its size, or
     * {@code null} when the fragment holds no sample of the track
     */
    private long[] firstFragmentSample(byte[] fragment, int from, long start, long trackId) {
        for (var layout : trafLayouts(fragment, from, start)) {
            if (layout.trackId() == trackId && layout.firstOffset() >= 0) {
                return new long[]{layout.firstOffset(), layout.firstSize()};
            }
            if (layout.end() < 0) {
                return null;
            }
        }
        return null;
    }

    /**
     * Describes where the sample data of a track fragment lies.
     *
     * @param traf the {@code traf} box
     * @param tfhdFlags the flags of its {@code tfhd}
     * @param trackId the {@code track_ID} its {@code tfhd} names
     * @param base its base data offset
     * @param firstOffset the offset of its first sample, or {@code -1} when it has none
     * @param firstSize the size of its first sample, or {@code -1} when it has none
     * @param end the offset after the data of its last sample, or {@code -1} when a track run is truncated
     */
    private record TrafLayout(Mp4Boxes.Box traf, long tfhdFlags, long trackId, long base, long firstOffset,
                              long firstSize, long end) {
        /**
         * {@return whether the base data offset is the end of the data of the previous track fragment}
         */
        boolean chained() {
            return (tfhdFlags & (TFHD_BASE_DATA_OFFSET | TFHD_DEFAULT_BASE_IS_MOOF)) == 0;
        }
    }

    /**
     * Lays out the sample data of the track fragments of a movie fragment (ISO/IEC 14496-12 clause 8.8.7.1): the base
     * data offset of a track fragment is the explicit one of its {@code tfhd}, else the start of the movie fragment
     * for the first track fragment or with {@code default-base-is-moof}, else the end of the data of the previous
     * track fragment; a track run states its data offset relative to that base or follows the previous run.
     *
     * @param fragment the bytes of the fragment
     * @param from the index of its first child
     * @param start the offset of the fragment in the file with its compressed boxes uncompressed
     * @return the layouts of its track fragments with a readable {@code tfhd}, in order, the last one ending at
     * {@code -1} when a track run is truncated
     */
    private List<TrafLayout> trafLayouts(byte[] fragment, int from, long start) {
        var result = new ArrayList<TrafLayout>();
        var previousEnd = -1L;
        for (var traf : Mp4Boxes.children(fragment, from, fragment.length)) {
            if (!traf.type().equals("traf")) {
                continue;
            }
            var children = Mp4Boxes.children(fragment, traf.payload(), traf.end());
            var tfhd = Mp4Boxes.find(children, "tfhd");
            if (tfhd == null || tfhd.end() - tfhd.payload() < 8) {
                continue;
            }
            var flags = ByteArrays.u32be(fragment, tfhd.payload()) & 0xFFFFFF;
            var id = ByteArrays.u32be(fragment, tfhd.payload() + 4);
            var at = tfhd.payload() + 8;
            var base = (flags & TFHD_DEFAULT_BASE_IS_MOOF) != 0 || previousEnd < 0 ? start : previousEnd;
            if ((flags & TFHD_BASE_DATA_OFFSET) != 0 && at + 8 <= tfhd.end()) {
                base = ByteArrays.i64be(fragment, at);
                at += 8;
            }
            at += (flags & 2) != 0 ? 4 : 0;
            at += (flags & 8) != 0 ? 4 : 0;
            var defaultSize = (flags & 0x10) != 0 && at + 4 <= tfhd.end() ? ByteArrays.u32be(fragment, at)
                    : trexDefaultSize(id);
            var position = base;
            var firstOffset = -1L;
            var firstSize = -1L;
            var truncated = false;
            for (var trun : children) {
                if (!trun.type().equals("trun") || trun.end() - trun.payload() < 8) {
                    continue;
                }
                var runFlags = ByteArrays.u32be(fragment, trun.payload()) & 0xFFFFFF;
                var count = ByteArrays.u32be(fragment, trun.payload() + 4);
                var cursor = trun.payload() + 8;
                if ((runFlags & TRUN_DATA_OFFSET) != 0) {
                    if (cursor + 4 > trun.end()) {
                        break;
                    }
                    position = base + ByteArrays.i32be(fragment, cursor);
                    cursor += 4;
                }
                cursor += (runFlags & 4) != 0 ? 4 : 0;
                var entry = Long.bitCount(runFlags & 0xF00) * 4;
                if (entry > 0 && count > (trun.end() - cursor) / entry) {
                    truncated = true;
                    break;
                }
                if ((runFlags & 0x200) == 0) {
                    if (count > 0 && firstOffset < 0) {
                        firstOffset = position;
                        firstSize = defaultSize;
                    }
                    try {
                        position = Math.addExact(position, Math.multiplyExact(count, defaultSize));
                    } catch (ArithmeticException exception) {
                        truncated = true;
                        break;
                    }
                    continue;
                }
                var sizeAt = (runFlags & 0x100) != 0 ? 4 : 0;
                for (var sample = 0L; sample < count; sample++) {
                    var size = ByteArrays.u32be(fragment, cursor + sizeAt);
                    if (firstOffset < 0) {
                        firstOffset = position;
                        firstSize = size;
                    }
                    position += size;
                    cursor += entry;
                }
            }
            result.add(new TrafLayout(traf, flags, id, base, firstOffset, firstSize, truncated ? -1 : position));
            if (truncated) {
                return result;
            }
            previousEnd = position;
        }
        return result;
    }

    /**
     * Returns the default sample size the track extends box of a track gives.
     *
     * @param trackId the {@code track_ID}
     * @return the size, or {@code 0} without such a box
     */
    private long trexDefaultSize(long trackId) {
        var mvex = Mp4Boxes.find(Mp4Boxes.children(moov, moovPayload(), moov.length), "mvex");
        if (mvex == null) {
            return 0;
        }
        for (var trex : Mp4Boxes.children(moov, mvex.payload(), mvex.end())) {
            if (trex.type().equals("trex") && trex.end() - trex.payload() >= 24
                    && ByteArrays.u32be(moov, trex.payload() + 4) == trackId) {
                return ByteArrays.u32be(moov, trex.payload() + 16);
            }
        }
        return 0;
    }

    /**
     * Returns the size a top-level box has in the file with its compressed boxes uncompressed, which the explicit
     * offsets of movie fragments count in.
     *
     * @param index the index of the box in {@link #topLevel}
     * @return the size
     * @throws MediaException.Malformed if a compressed box is not a gzip stream
     */
    private long virtualSize(int index) {
        var box = topLevel.get(index);
        if (index == moovIndex) {
            return box[1] + movieSaved;
        }
        if (!COMPRESSED_TYPES.contains(topLevelTypes.get(index))) {
            return box[1];
        }
        return uncompressedSizes.computeIfAbsent(index, _ -> {
            var payload = inflate(TagIo.read(source, box[0] + box[2], box[1] - box[2], limits)).length;
            return payload + (payload + 8L > U32_MAX ? 16L : 8L);
        });
    }

    /**
     * Maps an offset of the file with its compressed boxes uncompressed to the offset in the file as it is.
     *
     * @param virtual the offset
     * @return the file offset, or {@code -1} when it falls inside a compressed box or past the last box
     */
    private long realOffset(long virtual) {
        var position = 0L;
        for (var index = 0; index < topLevel.size(); index++) {
            var size = virtualSize(index);
            if (virtual < position + size) {
                return COMPRESSED_TYPES.contains(topLevelTypes.get(index)) ? -1 : topLevel.get(index)[0] + virtual - position;
            }
            position += size;
        }
        return -1;
    }

    /**
     * {@return the sample table box of a track, or {@code null} when it has none}
     *
     * @param trak the {@code trak} box
     */
    private Mp4Boxes.Box sampleTable(Mp4Boxes.Box trak) {
        var mdia = Mp4Boxes.find(Mp4Boxes.children(moov, trak.payload(), trak.end()), "mdia");
        var minf = mdia == null ? null : Mp4Boxes.find(Mp4Boxes.children(moov, mdia.payload(), mdia.end()), "minf");
        return minf == null ? null : Mp4Boxes.find(Mp4Boxes.children(moov, minf.payload(), minf.end()), "stbl");
    }

    /**
     * Describes a keyed metadata box.
     *
     * @param box the {@code meta} box, in {@link #moov}
     * @param metadata its parsed items
     */
    private record KeyedSlot(Mp4Boxes.Box box, Mp4KeyedMetadata metadata) {
    }

    /**
     * Decodes the track tags of every track but the chapter tracks.
     */
    private void decodeTracks() {
        var used = new HashSet<Long>();
        var traks = new ArrayList<Mp4Boxes.Box>();
        var ids = new ArrayList<Long>();
        for (var child : Mp4Boxes.children(moov, moovPayload(), moov.length)) {
            if (child.type().equals("trak")) {
                var fileId = trackId(moov, child);
                traks.add(child);
                ids.add(fileId > 0 && fileId <= Integer.MAX_VALUE && used.add(fileId) ? fileId : 0);
            }
        }
        var next = 1L;
        for (var index = 0; index < traks.size(); index++) {
            var trak = traks.get(index);
            var fileId = trackId(moov, trak);
            long publicId = ids.get(index);
            if (publicId == 0) {
                while (used.contains(next)) {
                    next++;
                }
                publicId = next;
                used.add(next);
            }
            if (fileId < 0 || chapterTracks.contains(fileId) || trackInfo.containsKey(fileId)) {
                continue;
            }
            trackInfo.put(fileId, decodeTrack(trak, (int) publicId));
        }
    }

    /**
     * Decodes the track tags of one track.
     *
     * @param trak the {@code trak} box
     * @param publicId the identifier the demuxer gives the track
     * @return the track
     */
    private TrackInfo decodeTrack(Mp4Boxes.Box trak, int publicId) {
        var children = Mp4Boxes.children(moov, trak.payload(), trak.end());
        var tkhd = Mp4Boxes.find(children, "tkhd");
        var enabled = tkhd == null || tkhd.size() < 12 || (moov[tkhd.payload() + 3] & Mp4TrackMetadata.ENABLED) != 0;
        var disposition = EnumSet.noneOf(TrackDisposition.class);
        if (enabled) {
            disposition.add(TrackDisposition.DEFAULT);
        }
        var builder = MediaTags.builder();
        var opaque = new ArrayList<byte[]>();
        var udta = Mp4Boxes.find(children, "udta");
        var keys = new HashSet<String>();
        var slots = new ArrayList<KeyedSlot>();
        for (var child : children) {
            if (child.type().equals("meta") && Mp4KeyedMetadata.isKeyed(moov, child)) {
                slots.add(new KeyedSlot(child, Mp4KeyedMetadata.parse(moov, child)));
            }
        }
        if (udta != null) {
            var forms = new HashMap<>(integerForms);
            for (var child : Mp4Boxes.children(moov, udta.payload(), udta.end())) {
                switch (child.type()) {
                    case "name" -> {
                        var name = new String(moov, child.payload(), child.end() - child.payload(),
                                StandardCharsets.UTF_8);
                        var end = name.indexOf('\0');
                        name = (end >= 0 ? name.substring(0, end) : name).strip();
                        if (!name.isEmpty() && keys.add(StandardTag.TITLE.key())) {
                            builder.add(StandardTag.TITLE.key(), name);
                        }
                    }
                    case "kind" -> {
                        var strings = kindStrings(child);
                        if (strings != null) {
                            disposition.addAll(Mp4TrackMetadata.dispositions(strings[0], strings[1]));
                        }
                    }
                    case "meta" -> {
                        if (Mp4KeyedMetadata.isKeyed(moov, child)) {
                            slots.add(new KeyedSlot(child, Mp4KeyedMetadata.parse(moov, child)));
                        } else if (isItunesMeta(child)) {
                            var ilst = Mp4Boxes.find(Mp4Boxes.children(moov, metaChildren(child), child.end()), "ilst");
                            if (ilst != null) {
                                for (var item : Mp4Boxes.children(moov, ilst.payload(), ilst.end())) {
                                    if (item.type().equals("covr") || !decodeItem(item, builder, keys)) {
                                        opaque.add(Arrays.copyOfRange(moov, item.offset(), item.end()));
                                    }
                                }
                            }
                        }
                    }
                    default -> {
                        var text = quickTimeText(child);
                        if (text != null) {
                            var key = keyOf(child.type());
                            if (!text.isBlank() && keys.add(StandardTag.normalize(key).toLowerCase(Locale.ROOT))) {
                                builder.add(key, text);
                            }
                        }
                    }
                }
            }
            integerForms.clear();
            integerForms.putAll(forms);
        }
        var udtaKeys = Set.copyOf(keys);
        var keyedKeys = new HashMap<String, Set<Integer>>();
        mergeKeyed(slots, keyedKeys, builder, keys);
        var kind = switch (handlerType(trak)) {
            case "soun" -> TrackKind.AUDIO;
            case "vide" -> TrackKind.VIDEO;
            default -> TrackKind.DATA;
        };
        var built = builder.build();
        return new TrackInfo(new TrackTags(publicId, built.toBuilder().pictures(List.of()).build(), disposition), kind,
                List.copyOf(opaque), List.copyOf(slots), keyedKeys, udtaKeys);
    }

    /**
     * Decodes the scheme and value of a {@code kind} box.
     *
     * @param kind the box
     * @return the scheme URI and the value, or {@code null} when they are not terminated
     */
    private String[] kindStrings(Mp4Boxes.Box kind) {
        var start = kind.payload() + 4;
        var schemeEnd = start;
        while (schemeEnd < kind.end() && moov[schemeEnd] != 0) {
            schemeEnd++;
        }
        if (schemeEnd >= kind.end()) {
            return null;
        }
        var valueEnd = schemeEnd + 1;
        while (valueEnd < kind.end() && moov[valueEnd] != 0) {
            valueEnd++;
        }
        return new String[]{new String(moov, start, schemeEnd - start, StandardCharsets.UTF_8),
                new String(moov, schemeEnd + 1, valueEnd - schemeEnd - 1, StandardCharsets.UTF_8)};
    }

    /**
     * Returns whether a {@code kind} box stores a disposition: a role of the DASH role scheme that
     * {@link Mp4TrackMetadata} maps, which a rewrite of the track's tags replaces. Other {@code kind}
     * boxes, such as the {@code main} role or another scheme, are kept.
     *
     * @param kind the box
     * @return {@code true} for a role that names a disposition
     */
    private boolean isRoleKind(Mp4Boxes.Box kind) {
        var strings = kindStrings(kind);
        return strings != null && !Mp4TrackMetadata.dispositions(strings[0], strings[1]).isEmpty();
    }

    /**
     * Describes a track whose tags are read and written.
     *
     * @param tags the track tags as read
     * @param kind the kind of the track, from its handler
     * @param opaque the {@code ilst} items of its {@code udta} that are not decoded, each a complete box
     * @param keyed its keyed metadata boxes, those of the {@code trak} first, then those of its {@code udta}
     * @param keyedKeys the indexes in {@code keyed} of the boxes holding each decoded key, by normalized key in
     * lower case
     * @param udtaKeys the normalized keys in lower case that the {@code name} box, the iTunes items and the
     * QuickTime text atoms of its {@code udta} hold
     */
    private record TrackInfo(TrackTags tags, TrackKind kind, List<byte[]> opaque, List<KeyedSlot> keyed,
                             Map<String, Set<Integer>> keyedKeys, Set<String> udtaKeys) {
    }

    /**
     * Returns the movie a {@code moov} box holds: the box itself, or the movie its QuickTime compressed movie atom
     * ({@code cmov}) holds, whose offsets count in the file as it is, so that a rewrite stores it uncompressed.
     *
     * @param movie the {@code moov} box, header included
     * @return the {@code moov} box to read and rewrite
     * @throws MediaException.Malformed if the compressed movie is damaged
     * @throws MediaException.Unsupported if the movie is compressed with another algorithm than zlib
     * @throws MediaException.LimitExceeded if the uncompressed movie is larger than the byte limit
     */
    private byte[] quickTimeMovie(byte[] movie) {
        var header = ByteArrays.u32be(movie, 0) == 1 ? 16 : 8;
        var cmov = QuickTimeCompressedMovie.find(BoxCursor.of(Arrays.copyOfRange(movie, header, movie.length),
                QuickTimeCompressedMovie.CMOV));
        return cmov == null ? movie : QuickTimeCompressedMovie.inflate(cmov, limits);
    }

    /**
     * Reads the movie of a compressed movie box ({@code !mov}, ISO/IEC 14496-12 clause 8.20), a gzip stream of the
     * payload of a {@code moov} box, as an uncompressed {@code moov} box that replaces it when the tags are
     * written. The chunk offsets of the compressed movie count in the file with the movie box uncompressed, so the
     * offsets past it are moved to where the data lies in the file as it is.
     *
     * @param box the offset, size and header size of the compressed movie box
     * @return the {@code moov} box
     * @throws MediaException.Malformed if the payload is not a gzip stream or a chunk offset is out of range
     * @throws MediaException.LimitExceeded if the movie box is larger than the byte limit
     */
    private byte[] decompressMovie(long[] box) {
        var payload = inflate(TagIo.read(source, box[0] + box[2], box[1] - box[2], limits));
        var wide = payload.length + 8L > U32_MAX;
        var header = wide ? 16 : 8;
        var movie = new byte[header + payload.length];
        if (wide) {
            ByteArrays.put32be(movie, 0, 1);
            ByteArrays.put64be(movie, 8, movie.length);
        } else {
            ByteArrays.put32be(movie, 0, movie.length);
        }
        movie[4] = 'm';
        movie[5] = 'o';
        movie[6] = 'o';
        movie[7] = 'v';
        System.arraycopy(payload, 0, movie, header, payload.length);
        var saved = movie.length - box[1];
        var end = box[0] + movie.length;
        for (var trak : Mp4Boxes.children(movie, header, movie.length)) {
            if (trak.type().equals("trak")) {
                relocateChunks(movie, trak, saved, end);
            }
        }
        return movie;
    }

    /**
     * Decompresses the payload of a compressed box, a gzip stream, within the byte limit.
     *
     * @param compressed the payload of the compressed box
     * @return the payload of the box it replaces
     * @throws MediaException.Malformed if the payload is not a gzip stream
     * @throws MediaException.LimitExceeded if the decompressed payload exceeds the byte limit
     */
    private byte[] inflate(byte[] compressed) {
        var payload = new ByteArrayOutputStream((int) Math.min(compressed.length * 4L, 1 << 20));
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            var buffer = new byte[8192];
            for (var read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                LimitChecks.checkAllocation(limits, payload.size() + read + 16L);
                payload.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw new MediaException.Malformed("the compressed box is not a gzip stream: " + exception.getMessage());
        }
        return payload.toByteArray();
    }

    /**
     * Moves back, in place, the chunk offsets of a track that point at or after an offset.
     *
     * @param movie the {@code moov} box
     * @param trak the {@code trak} box
     * @param shift the distance to move the offsets back by
     * @param threshold the first offset that moves
     * @throws MediaException.Malformed if a moved offset is negative
     */
    private static void relocateChunks(byte[] movie, Mp4Boxes.Box trak, long shift, long threshold) {
        var mdia = Mp4Boxes.find(Mp4Boxes.children(movie, trak.payload(), trak.end()), "mdia");
        var minf = mdia == null ? null : Mp4Boxes.find(Mp4Boxes.children(movie, mdia.payload(), mdia.end()), "minf");
        var stbl = minf == null ? null : Mp4Boxes.find(Mp4Boxes.children(movie, minf.payload(), minf.end()), "stbl");
        if (stbl == null) {
            return;
        }
        for (var table : Mp4Boxes.children(movie, stbl.payload(), stbl.end())) {
            var wide = table.type().equals("co64");
            if (!wide && !table.type().equals("stco") || table.end() - table.payload() < 8) {
                continue;
            }
            var count = ByteArrays.u32be(movie, table.payload() + 4);
            var entry = wide ? 8 : 4;
            var position = table.payload() + 8;
            for (var i = 0L; i < count && table.end() - position >= entry; i++) {
                var offset = wide ? ByteArrays.i64be(movie, position) : ByteArrays.u32be(movie, position);
                if (Long.compareUnsigned(offset, threshold) >= 0) {
                    if (offset - shift < 0 || !wide && offset - shift > U32_MAX) {
                        throw new MediaException.Malformed("chunk offset " + offset + " does not fit the file with the"
                                + " compressed movie box");
                    }
                    if (wide) {
                        ByteArrays.put64be(movie, position, offset - shift);
                    } else {
                        ByteArrays.put32be(movie, position, offset - shift);
                    }
                }
                position += entry;
            }
        }
    }

    /**
     * The encoding of an integer atom.
     *
     * @param type the data type, {@value #TYPE_INTEGER} (signed) or {@value #TYPE_UNSIGNED}, or
     * {@value #TYPE_UTF8} for an atom of {@link #INTEGER_ATOMS} the file also stores as text
     * @param width the width in bytes: 1, 2, 3, 4 or 8
     */
    record IntegerForm(int type, int width) {
    }

    /**
     * {@return the index of the payload of {@code moov}}
     */
    private int moovPayload() {
        return ByteArrays.u32be(moov, 0) == 1 ? 16 : 8;
    }

    /**
     * Decodes the {@code ilst} items, the {@code chpl} chapters and the QuickTime text atoms of
     * {@code udta}.
     *
     * @param opaque receives the opaque items
     * @return the tags
     */
    private MediaTags decode(List<byte[]> opaque) {
        var builder = MediaTags.builder();
        var children = Mp4Boxes.children(moov, moovPayload(), moov.length);
        var udta = Mp4Boxes.find(children, "udta");
        var udtaChildren = udta == null ? List.<Mp4Boxes.Box>of() : Mp4Boxes.children(moov, udta.payload(), udta.end());
        Mp4Boxes.Box meta = null;
        for (var child : udtaChildren) {
            if (child.type().equals("meta") && isItunesMeta(child)) {
                meta = child;
                break;
            }
        }
        var keys = new HashSet<String>();
        if (meta != null) {
            var ilst = Mp4Boxes.find(Mp4Boxes.children(moov, metaChildren(meta), meta.end()), "ilst");
            if (ilst != null) {
                for (var item : Mp4Boxes.children(moov, ilst.payload(), ilst.end())) {
                    if (timecodeTarget >= 0 && isTimecodeItem(item)) {
                        timecodeItems.add(Arrays.copyOfRange(moov, item.offset(), item.end()));
                    } else if (!decodeItem(item, builder, keys)) {
                        opaque.add(Arrays.copyOfRange(moov, item.offset(), item.end()));
                    }
                }
            }
        }
        for (var child : udtaChildren) {
            var text = quickTimeText(child);
            if (text != null) {
                var key = keyOf(child.type());
                if (!text.isBlank() && keys.add(StandardTag.normalize(key).toLowerCase(Locale.ROOT))) {
                    builder.add(key, text);
                }
            }
        }
        ilstKeys.addAll(keys);
        for (var child : udtaChildren) {
            if (child.type().equals(LocationInformation.TYPE) && lociValue == null) {
                lociValue = LocationInformation.coordinates(moov, child.payload(), child.end());
            }
        }
        if (lociValue != null && keys.add(LOCI)) {
            builder.add(LOCI, lociValue);
        }
        decodeKeyed(children, udtaChildren, builder, keys);
        if (xmp == null) {
            var atom = Mp4Boxes.find(udtaChildren, XMP_ATOM);
            if (atom != null) {
                try {
                    xmp = XmpPacket.parse(Arrays.copyOfRange(moov, atom.payload(), atom.end()));
                } catch (MediaException.Malformed ignored) {
                    // an atom that holds no packet is kept as it is
                }
            }
        }
        if (xmp != null) {
            builder.xmp(xmp);
        }
        var chpl = Mp4Boxes.find(udtaChildren, "chpl");
        if (chpl != null) {
            var nero = decodeChapters(chpl);
            if (!chapterTracks.isEmpty() && Mp4ChapterTrack.mayBeCut(nero)) {
                var track = trackChapters();
                if (Mp4ChapterTrack.supersedes(track, nero)) {
                    nero = track;
                }
            }
            builder.chapters(nero);
        } else {
            builder.chapters(trackChapters());
        }
        return builder.build();
    }

    /**
     * Decodes the keyed metadata boxes of {@code moov} and of its {@code udta}.
     *
     * @param children the children of {@code moov}
     * @param udtaChildren the children of {@code udta}
     * @param builder receives the values of the keys not read before
     * @param keys the normalized keys in lower case read so far, which receives those added
     */
    private void decodeKeyed(List<Mp4Boxes.Box> children, List<Mp4Boxes.Box> udtaChildren, MediaTags.Builder builder,
                             Set<String> keys) {
        for (var level : List.of(children, udtaChildren)) {
            for (var child : level) {
                if (child.type().equals("meta") && Mp4KeyedMetadata.isKeyed(moov, child)) {
                    keyedSlots.add(new KeyedSlot(child, Mp4KeyedMetadata.parse(moov, child)));
                }
            }
        }
        mergeKeyed(keyedSlots, keyedKeys, builder, keys);
    }

    /**
     * Adds the decoded items of keyed metadata boxes to tags.
     *
     * @param slots the keyed metadata boxes, in reading order
     * @param keyedKeys receives the indexes in {@code slots} of the boxes holding each decoded key, by normalized key
     * in lower case
     * @param builder receives the values of the keys not read before
     * @param keys the normalized keys in lower case read so far, which receives those added
     */
    private static void mergeKeyed(List<KeyedSlot> slots, Map<String, Set<Integer>> keyedKeys,
                                   MediaTags.Builder builder, Set<String> keys) {
        for (var index = 0; index < slots.size(); index++) {
            var added = new HashSet<String>();
            for (var item : slots.get(index).metadata().items()) {
                if (!item.isDecoded()) {
                    continue;
                }
                var lower = StandardTag.normalize(item.key()).toLowerCase(Locale.ROOT);
                keyedKeys.computeIfAbsent(lower, _ -> new HashSet<>()).add(index);
                if (!keys.contains(lower) || added.contains(lower)) {
                    for (var value : item.values()) {
                        builder.add(item.key(), value);
                    }
                    added.add(lower);
                }
            }
            keys.addAll(added);
        }
    }

    /**
     * Returns the index of a keyed metadata box.
     *
     * @param box a {@code meta} box of {@link #moov}
     * @return the index in {@link #keyedSlots}, or {@code -1}
     */
    private int keyedSlot(Mp4Boxes.Box box) {
        return slotOf(keyedSlots, box);
    }

    /**
     * Returns the index of a keyed metadata box in a list of them.
     *
     * @param slots the keyed metadata boxes
     * @param box a {@code meta} box of {@link #moov}
     * @return the index in {@code slots}, or {@code -1}
     */
    private static int slotOf(List<KeyedSlot> slots, Mp4Boxes.Box box) {
        for (var index = 0; index < slots.size(); index++) {
            if (slots.get(index).box().offset() == box.offset()) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Reads the chapters of the QuickTime chapter text tracks through the demuxer.
     *
     * @return the chapters, empty when the file has no chapter track or it cannot be read
     */
    private List<Chapter> trackChapters() {
        if (chapterTracks.isEmpty()) {
            return List.of();
        }
        try (var demuxer = Containers.openDemuxer(source)) {
            return demuxer.chapters();
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (MediaException exception) {
            return List.of();
        }
    }

    /**
     * Returns the duration the demuxer computes, for files whose {@code mvhd} records none
     * (fragmented files).
     *
     * @return the duration in microseconds, or {@code -1} when unknown
     */
    private long demuxedDuration() {
        try (var demuxer = Containers.openDemuxer(source)) {
            return demuxer.durationMicros();
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (MediaException exception) {
            return -1;
        }
    }

    /**
     * Decodes a QuickTime text atom of {@code udta}: a copyright-sign code holding a 16-bit
     * length, a 16-bit language and the text.
     *
     * <p>Such atoms are read as tags and replaced by the {@code ilst} on rewrite.
     *
     * @param child the {@code udta} child
     * @return the text, or {@code null} when the child is not such an atom
     */
    private String quickTimeText(Mp4Boxes.Box child) {
        if (child.type().charAt(0) != COPYRIGHT_SIGN || child.size() < 12) {
            return null;
        }
        var length = ByteArrays.u16be(moov, child.payload());
        if (length > child.end() - child.payload() - 4) {
            return null;
        }
        return new String(moov, child.payload() + 4, length, StandardCharsets.UTF_8);
    }

    /**
     * Returns whether a {@code meta} box is the iTunes ({@code mdir}) kind.
     *
     * @param meta the box
     * @return {@code true} when its handler is {@code mdir} or it has no handler
     */
    private boolean isItunesMeta(Mp4Boxes.Box meta) {
        var hdlr = Mp4Boxes.find(Mp4Boxes.children(moov, metaChildren(meta), meta.end()), "hdlr");
        return hdlr == null || hdlr.size() < 20 || Mp4Boxes.type(moov, hdlr.payload() + 8).equals("mdir");
    }

    /**
     * Returns the index of the first child of a {@code meta} box, which is a full box in MP4 and
     * a plain container in QuickTime.
     *
     * @param meta the box
     * @return the index
     */
    private int metaChildren(Mp4Boxes.Box meta) {
        var payload = meta.payload();
        if (meta.end() - payload >= 8 && Mp4Boxes.type(moov, payload + 4).equals("hdlr")) {
            return payload;
        }
        return Math.min(meta.end(), payload + 4);
    }

    /**
     * Decodes one {@code ilst} item.
     *
     * @param item the item box
     * @param builder receives the decoded values
     * @param keys the lower-case keys decoded so far
     * @return {@code true} when the item was decoded, {@code false} when it is opaque
     */
    private boolean decodeItem(Mp4Boxes.Box item, MediaTags.Builder builder, Set<String> keys) {
        var code = item.type();
        var parts = Mp4Boxes.children(moov, item.payload(), item.end());
        String mean = null;
        String name = null;
        var data = new ArrayList<Mp4Boxes.Box>();
        for (var part : parts) {
            switch (part.type()) {
                case "mean" -> mean = text(part, 4);
                case "name" -> name = text(part, 4);
                case "data" -> {
                    if (part.size() >= 16) {
                        data.add(part);
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        if (data.isEmpty()) {
            return false;
        }
        if (code.equals("covr")) {
            var pictures = new ArrayList<AttachedPicture>();
            for (var part : data) {
                var type = dataType(part);
                var mediaType = switch (type) {
                    case TYPE_JPEG -> "image/jpeg";
                    case TYPE_PNG -> "image/png";
                    case TYPE_BMP -> "image/bmp";
                    case 0 -> "";
                    default -> null;
                };
                if (mediaType == null) {
                    return false;
                }
                var bytes = Arrays.copyOfRange(moov, part.payload() + 8, part.end());
                var picture = AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, mediaType, "", bytes);
                if (mediaType.isEmpty()) {
                    picture = AttachedPicture.of(AttachedPicture.Type.FRONT_COVER,
                            picture.format().map(ImageFormat::mimeType).orElse(""), "", bytes);
                }
                pictures.add(picture);
            }
            for (var picture : pictures) {
                builder.addPicture(picture);
            }
            return true;
        }
        String key;
        if (code.equals("----")) {
            if (name == null || !APPLE_MEAN.equals(mean)) {
                return false;
            }
            key = name;
        } else if (mean != null || name != null) {
            return false;
        } else {
            key = keyOf(code);
        }
        var values = new ArrayList<String>();
        for (var part : data) {
            var value = decodeValue(code, part);
            if (value == null) {
                return false;
            }
            values.add(value);
        }
        var added = false;
        for (var value : values) {
            if (!value.isEmpty()) {
                try {
                    builder.add(key, value);
                    added = true;
                } catch (IllegalArgumentException exception) {
                    return false;
                }
            }
        }
        if (added) {
            keys.add(StandardTag.normalize(key).toLowerCase(Locale.ROOT));
        }
        return true;
    }

    /**
     * Returns the key an atom code is read as.
     *
     * @param code the atom code
     * @return the standard key the atom stores, or the code itself
     */
    private static String keyOf(String code) {
        for (var tag : StandardTag.values()) {
            if (tag.mp4Atom().isPresent() && tag.mp4Atom().get().equals(code)) {
                return tag.key();
            }
        }
        return code;
    }

    /**
     * Returns the data type of a {@code data} atom.
     *
     * @param data the atom
     * @return the 24-bit type indicator
     */
    private int dataType(Mp4Boxes.Box data) {
        return (int) (ByteArrays.u32be(moov, data.payload()) & 0xFFFFFF);
    }

    /**
     * Decodes the value of a {@code data} atom.
     *
     * @param code the item code
     * @param data the atom
     * @return the value as text, or {@code null} when the atom has a form text cannot hold
     */
    private String decodeValue(String code, Mp4Boxes.Box data) {
        var type = dataType(data);
        var start = data.payload() + 8;
        var length = data.end() - start;
        switch (code) {
            case "trkn", "disk" -> {
                if (length < 6) {
                    return null;
                }
                var number = ByteArrays.u16be(moov, start + 2);
                var total = ByteArrays.u16be(moov, start + 4);
                return total > 0 ? number + "/" + total : Integer.toString(number);
            }
            case "gnre" -> {
                if (length < 2) {
                    return null;
                }
                var name = Id3Genres.name(ByteArrays.u16be(moov, start) - 1);
                return name != null ? name : null;
            }
            case "cpil" -> {
                if (length < 1) {
                    return null;
                }
                return moov[start + length - 1] != 0 ? "1" : "0";
            }
            default -> {
            }
        }
        var integer = length == 1 || length == 2 || length == 3 || length == 4 || length == 8;
        var implicit = INTEGER_ATOMS.containsKey(code) && type == 0;
        if (integer && (implicit || type == TYPE_INTEGER || type == TYPE_UNSIGNED) && !code.equals("----")) {
            var form = new IntegerForm(type == TYPE_UNSIGNED ? TYPE_UNSIGNED : TYPE_INTEGER, length);
            integerForms.putIfAbsent(code, form);
            return integerText(start, length, type == TYPE_UNSIGNED);
        }
        if (INTEGER_ATOMS.containsKey(code) && (type == TYPE_UTF8 || type == TYPE_UTF16)) {
            integerForms.put(code, new IntegerForm(TYPE_UTF8, INTEGER_ATOMS.get(code)));
        }
        return switch (type) {
            case TYPE_UTF8 -> new String(moov, start, length, StandardCharsets.UTF_8);
            case TYPE_UTF16 -> new String(moov, start, length & ~1, StandardCharsets.UTF_16BE);
            default -> null;
        };
    }

    /**
     * Decodes a big-endian integer of {@code moov} as decimal text.
     *
     * @param start the index of the first byte
     * @param length the width in bytes, from 1 to 8
     * @param unsigned whether the value is unsigned rather than two's complement
     * @return the decimal text
     */
    private String integerText(int start, int length, boolean unsigned) {
        var value = 0L;
        for (var index = 0; index < length; index++) {
            value = (value << 8) | (moov[start + index] & 0xFF);
        }
        if (unsigned) {
            return Long.toUnsignedString(value);
        }
        var unused = 64 - 8 * length;
        return Long.toString(value << unused >> unused);
    }

    /**
     * Decodes the text of a full box ({@code mean}, {@code name}).
     *
     * @param box the full box holding the text
     * @param skip the bytes to skip after the header
     * @return the text
     */
    private String text(Mp4Boxes.Box box, int skip) {
        var start = Math.min(box.end(), box.payload() + skip);
        return new String(moov, start, box.end() - start, StandardCharsets.UTF_8);
    }

    /**
     * Decodes a Nero chapter list.
     *
     * @param chpl the box
     * @return the chapters, each ending where the next starts and the last at the movie end
     */
    private List<Chapter> decodeChapters(Mp4Boxes.Box chpl) {
        var position = chpl.payload();
        var end = chpl.end();
        if (end - position < 5) {
            return List.of();
        }
        var version = moov[position] & 0xFF;
        position += 4;
        if (version != 0) {
            position += 4;
        }
        if (position >= end) {
            return List.of();
        }
        var count = moov[position++] & 0xFF;
        var starts = new ArrayList<Long>();
        var titles = new ArrayList<String>();
        for (var index = 0; index < count && end - position >= 9; index++) {
            var start = ByteArrays.i64be(moov, position) / 10;
            var length = moov[position + 8] & 0xFF;
            position += 9;
            if (length > end - position) {
                break;
            }
            starts.add(start);
            titles.add(new String(moov, position, length, StandardCharsets.UTF_8));
            position += length;
        }
        var duration = durationMicros();
        if (duration <= 0 && !starts.isEmpty()) {
            duration = demuxedDuration();
        }
        var result = new ArrayList<Chapter>();
        for (var index = 0; index < starts.size(); index++) {
            var start = Math.max(0, starts.get(index));
            var stop = index + 1 < starts.size() ? starts.get(index + 1) : Math.max(start, duration);
            var title = titles.get(index);
            result.add(Chapter.of(start, Math.max(start, stop), title.isEmpty() ? null : title));
        }
        return result;
    }

    /**
     * {@return the movie duration from {@code mvhd}, in microseconds, or {@code -1} when unknown}
     */
    private long durationMicros() {
        var duration = movieDurationUnits();
        if (duration < 0) {
            return -1;
        }
        var timescale = movieTimescale();
        return Math.multiplyHigh(duration, 1_000_000L) != 0 ? -1 : duration * 1_000_000L / timescale;
    }

    /**
     * {@return the movie duration from {@code mvhd}, in units of its timescale, or {@code -1} when unknown}
     */
    private long movieDurationUnits() {
        var mvhd = Mp4Boxes.find(Mp4Boxes.children(moov, moovPayload(), moov.length), "mvhd");
        if (mvhd == null || mvhd.size() < 32) {
            return -1;
        }
        var position = mvhd.payload();
        var version = moov[position] & 0xFF;
        long timescale;
        long duration;
        if (version == 1) {
            if (mvhd.size() < 44) {
                return -1;
            }
            timescale = ByteArrays.u32be(moov, position + 20);
            duration = ByteArrays.i64be(moov, position + 24);
        } else {
            timescale = ByteArrays.u32be(moov, position + 12);
            duration = ByteArrays.u32be(moov, position + 16);
        }
        return timescale == 0 || duration < 0 || duration == 0xFFFFFFFFL ? -1 : duration;
    }

    @Override
    public MediaTags read() {
        return tags;
    }

    @Override
    public EditPlan plan(MediaTags newTags, TagWriteOptions options) {
        var rewrite = new Rewrite(newTags, options);
        var moovBox = topLevel.get(moovIndex);
        var slotStart = moovBox[0];
        var slotEnd = moovBox[0] + moovBox[1];
        if (moovIndex > 0 && reclaimable(moovIndex - 1, rewrite.unreferenced())) {
            slotStart = topLevel.get(moovIndex - 1)[0];
        }
        var blank = rewrite.xmpChanged ? xmpIndex : -1;
        var next = moovIndex + 1;
        while (next < topLevel.size() && (isFree(topLevelTypes.get(next)) || reclaimable(next, rewrite.unreferenced())
                || next == blank)) {
            slotEnd = topLevel.get(next)[0] + topLevel.get(next)[1];
            next++;
        }
        var blanked = blank > moovIndex && blank < next ? null : blank >= 0 ? topLevel.get(blank) : null;
        var replacement = blanked == null ? null : rewrite.replaceXmp(blanked[1]);
        rewrite.place(slotStart);
        var base = rewrite.build(0, Long.MAX_VALUE);
        var last = next == topLevel.size();
        var fileSize = source.size();
        var slot = slotEnd - slotStart;
        var splices = new ArrayList<Splice>();
        if (blanked != null) {
            splices.add(new Splice(blanked[0], blanked[1], replacement));
        }
        var sample = rewrite.samplePatch();
        if (sample != null) {
            if (sample.offset() < slotEnd && sample.offset() + sample.length() > slotStart) {
                throw new MediaException.Unsupported("mp4", "the sample of the timecode track lies in the movie box");
            }
            splices.add(sample);
        }
        var neutralized = rewrite.neutralized;
        splices.addAll(fragmentSplices(0, moovIndex, 0, neutralized));
        var plan = EditPlan.builder();
        copyRange(plan, 0, slotStart, splices);
        if (base.length == slot || last || base.length + 8 <= slot) {
            plan.bytes(base);
            if (base.length != slot && !last) {
                plan.bytes(Mp4Boxes.free(slot - base.length));
            }
            if (!last && movieSaved != 0) {
                splices.addAll(fragmentSplices(next, topLevel.size(), -movieSaved, neutralized));
                plan.transform(new Shift(slotEnd, fileSize - slotEnd, -movieSaved, limits, splices));
                return plan.build();
            }
        } else {
            var padded = options.padding() >= 8 ? options.padding() : 0;
            var delta = base.length + padded - slot;
            var shifted = rewrite.build(delta, slotEnd);
            for (var attempt = 0; shifted.length + padded - slot != delta; attempt++) {
                if (attempt > 64) {
                    throw new IllegalStateException("chunk offset promotion did not converge");
                }
                delta = shifted.length + padded - slot;
                shifted = rewrite.build(delta, slotEnd);
            }
            plan.bytes(shifted);
            if (padded > 0) {
                plan.bytes(Mp4Boxes.free(padded));
            }
            if (fileSize > slotEnd) {
                splices.addAll(fragmentSplices(next, topLevel.size(), delta - movieSaved, neutralized));
                plan.transform(new Shift(slotEnd, fileSize - slotEnd, delta - movieSaved, limits, splices));
            }
            return plan.build();
        }
        if (!last) {
            splices.addAll(fragmentSplices(next, topLevel.size(), 0, neutralized));
            copyRange(plan, slotEnd, fileSize, splices);
        }
        return plan.build();
    }

    /**
     * A range of the file written as other bytes.
     *
     * @param offset the offset of the range
     * @param length the length of the range
     * @param bytes the bytes written in its place; as long as the range, except for a recompressed fragment
     */
    private record Splice(long offset, long length, byte[] bytes) {
    }

    /**
     * Copies a range of the file, writing the splices that lie in it in place of their ranges.
     *
     * @param plan receives the pieces
     * @param start the offset of the first copied byte
     * @param end the offset after the last copied byte
     * @param splices the splices, which lie wholly inside or wholly outside the range
     */
    private static void copyRange(EditPlan.Builder plan, long start, long end, List<Splice> splices) {
        var position = start;
        for (var splice : sorted(splices)) {
            if (splice.offset() < start || splice.offset() + splice.length() > end) {
                continue;
            }
            plan.copy(position, splice.offset() - position);
            plan.bytes(splice.bytes());
            position = splice.offset() + splice.length();
        }
        plan.copy(position, end - position);
    }

    /**
     * {@return splices in file order}
     *
     * @param splices the splices, which do not overlap
     */
    private static List<Splice> sorted(List<Splice> splices) {
        var result = new ArrayList<>(splices);
        result.sort((first, second) -> Long.compare(first.offset(), second.offset()));
        return result;
    }

    /**
     * Builds the splices that correct the movie fragments of a range of top-level boxes: the track fragments
     * ({@code traf}) of removed tracks, and their track fragment random access boxes ({@code tfra}), become
     * {@code free} boxes of the same size, and the compressed movie fragments ({@code !mof}) that hold such a track
     * fragment or, when {@code delta} is not {@code 0}, an explicit base data offset are decompressed, corrected and
     * compressed again. The explicit offsets count in the file with its compressed boxes uncompressed, which the
     * recompressed size does not change; a recompressed fragment keeps the size of the old one, its gzip header
     * carrying a comment that fills the space, unless it no longer fits.
     *
     * @param from the index of the first top-level box
     * @param to the index after the last top-level box
     * @param delta the distance the offsets of the data after the movie box move by, in the file with its compressed
     * boxes uncompressed; {@code 0} for the boxes before it
     * @param neutralized the identifiers of the removed tracks that movie fragments name
     * @return the splices, each replacing a whole compressed fragment or a box inside a fragment
     * @throws MediaException.Malformed if a compressed fragment is not a gzip stream
     * @throws MediaException.Unsupported if a recompressed fragment outgrows the old one and precedes the movie box
     * or media data the movie box addresses, or a movie fragment is too large to correct
     * @throws MediaException.LimitExceeded if a decompressed fragment exceeds the byte limit
     */
    private List<Splice> fragmentSplices(int from, int to, long delta, Set<Long> neutralized) {
        var result = new ArrayList<Splice>();
        var virtual = 0L;
        if (!neutralized.isEmpty()) {
            for (var index = 0; index < from; index++) {
                virtual += virtualSize(index);
            }
        }
        for (var index = from; index < to; index++) {
            var type = topLevelTypes.get(index);
            var box = topLevel.get(index);
            var boxStart = virtual;
            if (!neutralized.isEmpty()) {
                virtual += virtualSize(index);
            }
            if (!neutralized.isEmpty() && (type.equals("moof") || type.equals("mfra"))) {
                if (box[1] > MAX_FRAGMENT) {
                    throw new MediaException.Unsupported("mp4", "a movie fragment of " + box[1] + " bytes names a"
                            + " removed track");
                }
                var bytes = TagIo.read(source, box[0], box[1], limits);
                var ranges = type.equals("moof") ? neutralizeFragments(bytes, (int) box[2], boxStart, neutralized)
                        : neutralizeIndexes(bytes, (int) box[2], neutralized);
                for (var range : ranges) {
                    result.add(new Splice(box[0] + range[0], range[1],
                            Arrays.copyOfRange(bytes, range[0], range[0] + range[1])));
                }
                continue;
            }
            if (!type.equals(COMPRESSED_FRAGMENT) || delta == 0 && neutralized.isEmpty()) {
                continue;
            }
            var fragment = inflate(TagIo.read(source, box[0] + box[2], box[1] - box[2], limits));
            var changed = !neutralizeFragments(fragment, 0, boxStart, neutralized).isEmpty();
            changed |= delta != 0 && patchFragment(fragment, 0, fragment.length, 0, delta);
            if (!changed) {
                continue;
            }
            var payload = gzip(fragment, box[1] - box[2]);
            var out = new ByteWriter(payload.length + 16);
            if (box[2] == 16 || payload.length + 8L > U32_MAX) {
                out.u32be(1);
                out.fourCc(COMPRESSED_FRAGMENT);
                out.u64be(payload.length + 16L);
            } else {
                out.u32be(payload.length + 8L);
                out.fourCc(COMPRESSED_FRAGMENT);
            }
            out.bytes(payload);
            var rebuilt = out.toByteArray();
            if (rebuilt.length > box[1]) {
                if (index < moovIndex) {
                    // TODO: write a grown compressed fragment that precedes the movie box (no offset that counts in
                    // the file with its compressed boxes uncompressed moves); the samples of new chapter and timecode
                    // tracks are placed at offsets of the file as it is, which a compressed box before the movie box
                    // makes differ
                    throw new MediaException.Unsupported("mp4 compressed movie fragments", "a recompressed movie"
                            + " fragment outgrows its box before the movie box");
                }
                checkNoChunksAfter(box[0]);
            }
            result.add(new Splice(box[0], box[1], rebuilt));
        }
        return result;
    }

    /**
     * Turns the track fragments ({@code traf}) of removed tracks in a movie fragment into {@code free} boxes of the
     * same size, in place. A {@code free} box may stand in any box (ISO/IEC 14496-12 clause 8.1.2) and readers skip
     * it, so the offsets of the file stay as they are. A kept track fragment whose base data offset is the end of the
     * data of the previous track fragment would count from an earlier one once that is gone, so the data offsets of
     * its track runs grow by the difference.
     *
     * @param fragment the bytes of the {@code moof} box, or the payload of a compressed fragment
     * @param from the index of the first child
     * @param start the offset of the fragment in the file with its compressed boxes uncompressed
     * @param tracks the identifiers of the removed tracks
     * @return the offset in {@code fragment} and the size of each changed box: the turned track fragments and the
     * corrected track runs
     * @throws MediaException.Unsupported if a kept track fragment that follows a turned one counts from the end of
     * its data and its first track run states no data offset, or a data offset no longer fits 32 bits, or a truncated
     * track run hides where the track fragments after a turned one start
     */
    private List<int[]> neutralizeFragments(byte[] fragment, int from, long start, Set<Long> tracks) {
        var result = new ArrayList<int[]>();
        if (tracks.isEmpty()) {
            return result;
        }
        var layouts = trafLayouts(fragment, from, start);
        if (!layouts.isEmpty() && layouts.getLast().end() < 0) {
            var named = false;
            var after = false;
            for (var traf : Mp4Boxes.children(fragment, from, fragment.length)) {
                if (!traf.type().equals("traf")) {
                    continue;
                }
                after |= traf.offset() > layouts.getLast().traf().offset();
                var tfhd = Mp4Boxes.find(Mp4Boxes.children(fragment, traf.payload(), traf.end()), "tfhd");
                named |= tfhd != null && tfhd.end() - tfhd.payload() >= 8
                        && tracks.contains(ByteArrays.u32be(fragment, tfhd.payload() + 4));
            }
            if (named && after) {
                throw new MediaException.Unsupported("mp4", "a truncated track run hides where the track fragments"
                        + " around the removed timecode track's fragment start");
            }
        }
        var keptEnd = -1L;
        var removedBefore = false;
        for (var layout : layouts) {
            if (tracks.contains(layout.trackId())) {
                result.add(free(fragment, layout.traf()));
                removedBefore = true;
                continue;
            }
            if (removedBefore && layout.chained()) {
                var shift = layout.base() - (keptEnd < 0 ? start : keptEnd);
                if (shift != 0) {
                    result.addAll(shiftRuns(fragment, layout.traf(), shift));
                }
            }
            keptEnd = layout.end();
        }
        return result;
    }

    /**
     * Adds a distance to the data offsets of the track runs of a track fragment, in place.
     *
     * @param fragment the bytes holding the track fragment
     * @param traf the {@code traf} box
     * @param shift the distance
     * @return the offset in {@code fragment} and the size of each changed {@code trun} box
     * @throws MediaException.Unsupported if a track run that starts the data of the track fragment states no data
     * offset, or an offset no longer fits 32 bits
     */
    private static List<int[]> shiftRuns(byte[] fragment, Mp4Boxes.Box traf, long shift) {
        var result = new ArrayList<int[]>();
        var first = true;
        for (var trun : Mp4Boxes.children(fragment, traf.payload(), traf.end())) {
            if (!trun.type().equals("trun") || trun.end() - trun.payload() < 8) {
                continue;
            }
            var hasOffset = (ByteArrays.u32be(fragment, trun.payload()) & TRUN_DATA_OFFSET) != 0
                    && trun.end() - trun.payload() >= 12;
            if (!hasOffset) {
                if (first) {
                    throw new MediaException.Unsupported("mp4", "a track fragment after the removed timecode track"
                            + " counts its data from the end of that track's data without a data offset");
                }
                continue;
            }
            first = false;
            var at = trun.payload() + 8;
            var offset = ByteArrays.i32be(fragment, at) + shift;
            if (offset < Integer.MIN_VALUE || offset > Integer.MAX_VALUE) {
                throw new MediaException.Unsupported("mp4", "a track run data offset no longer fits 32 bits once the"
                        + " removed timecode track's fragment is gone");
            }
            ByteArrays.put32be(fragment, at, (int) offset);
            result.add(new int[]{trun.offset(), trun.size()});
        }
        return result;
    }

    /**
     * Turns the track fragment random access boxes ({@code tfra}) of removed tracks in a movie fragment random access
     * box into {@code free} boxes of the same size, in place.
     *
     * @param mfra the bytes of the {@code mfra} box
     * @param from the index of the first child
     * @param tracks the identifiers of the removed tracks
     * @return the offset in {@code mfra} and the size of each turned box
     */
    private static List<int[]> neutralizeIndexes(byte[] mfra, int from, Set<Long> tracks) {
        var result = new ArrayList<int[]>();
        for (var child : Mp4Boxes.children(mfra, from, mfra.length)) {
            if (child.type().equals("tfra") && child.end() - child.payload() >= 8
                    && tracks.contains(ByteArrays.u32be(mfra, child.payload() + 4))) {
                result.add(free(mfra, child));
            }
        }
        return result;
    }

    /**
     * Turns a box into a {@code free} box of the same size, in place.
     *
     * @param bytes the bytes holding the box
     * @param box the box to turn, inside {@code bytes}
     * @return the offset in {@code bytes} and the size of the box
     */
    private static int[] free(byte[] bytes, Mp4Boxes.Box box) {
        System.arraycopy("free".getBytes(StandardCharsets.ISO_8859_1), 0, bytes, box.offset() + 4, 4);
        Arrays.fill(bytes, box.payload(), box.end(), (byte) 0);
        return new int[]{box.offset(), box.size()};
    }

    /**
     * Rejects a recompressed fragment that grows before media data the sample tables of {@code moov} address, whose
     * chunk offsets it would move.
     *
     * @param offset the offset of the fragment
     * @throws MediaException.Unsupported if a chunk offset points past the fragment
     */
    private void checkNoChunksAfter(long offset) {
        for (var trak : Mp4Boxes.children(moov, moovPayload(), moov.length)) {
            if (trak.type().equals("trak")) {
                for (var chunk : chunkOffsets(trak)) {
                    if (chunk > offset) {
                        throw new MediaException.Unsupported("mp4 compressed movie fragments", "a recompressed movie"
                                + " fragment outgrows its box before media data the movie box addresses");
                    }
                }
            }
        }
    }

    /**
     * Compresses a payload as a gzip stream (RFC 1952) of at least a given length: the first deflate stream of the
     * levels and strategies tried that fits the length, or the shortest, and a header comment of spaces that fills
     * what remains.
     *
     * @param payload the bytes to compress
     * @param length the length to reach
     * @return the gzip stream
     */
    static byte[] gzip(byte[] payload, long length) {
        byte[] deflated = null;
        var buffer = new byte[8192];
        for (var strategy : new int[]{Deflater.DEFAULT_STRATEGY, Deflater.FILTERED}) {
            for (var level : new int[]{9, 6, 8, 7, 5, 4, 3, 2, 1}) {
                var candidate = deflate(payload, level, strategy, buffer);
                if (deflated == null || candidate.length < deflated.length) {
                    deflated = candidate;
                }
                if (18L + deflated.length <= length) {
                    return gzipStream(payload, deflated, length);
                }
            }
        }
        return gzipStream(payload, deflated, length);
    }

    /**
     * Deflates a payload (RFC 1951).
     *
     * @param payload the bytes to compress
     * @param level the zlib compression level
     * @param strategy the zlib strategy
     * @param buffer scratch space
     * @return the raw deflate stream
     */
    private static byte[] deflate(byte[] payload, int level, int strategy, byte[] buffer) {
        var deflater = new Deflater(level, true);
        var out = new ByteArrayOutputStream(payload.length / 2 + 64);
        try {
            deflater.setStrategy(strategy);
            deflater.setInput(payload);
            deflater.finish();
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    /**
     * Frames a deflate stream as a gzip stream, padded through a header comment.
     *
     * @param payload the uncompressed bytes
     * @param deflated their deflate stream
     * @param length the length to reach
     * @return the gzip stream
     */
    private static byte[] gzipStream(byte[] payload, byte[] deflated, long length) {
        var crc = new CRC32();
        crc.update(payload);
        var plain = 10L + deflated.length + 8;
        var pad = length > plain ? length - plain : 0;
        var out = new ByteWriter((int) (plain + pad));
        out.u8(0x1F);
        out.u8(0x8B);
        out.u8(8);
        out.u8(pad > 0 ? GZIP_COMMENT : 0);
        out.u32be(0);
        out.u8(2);
        out.u8(255);
        if (pad > 0) {
            for (var index = 1L; index < pad; index++) {
                out.u8(' ');
            }
            out.u8(0);
        }
        out.bytes(deflated);
        out.u32le(crc.getValue());
        out.u32le(payload.length & U32_MAX);
        return out.toByteArray();
    }

    /**
     * Moves the explicit base data offsets of the {@code tfhd} boxes of a movie fragment, in place.
     *
     * @param box the fragment bytes
     * @param from the index of the first child
     * @param to the end of the children
     * @param depth the nesting depth
     * @param delta the distance to move the offsets by
     * @return whether a {@code tfhd} box states an explicit base data offset
     */
    private static boolean patchFragment(byte[] box, int from, int to, int depth, long delta) {
        if (depth > 2) {
            return false;
        }
        var patched = false;
        for (var child : Mp4Boxes.children(box, from, to)) {
            if (child.type().equals("traf")) {
                patched |= patchFragment(box, child.payload(), child.end(), depth + 1, delta);
            } else if (child.type().equals("tfhd") && child.size() >= 24) {
                var flags = ByteArrays.u32be(box, child.payload()) & 0xFFFFFF;
                if ((flags & 1) != 0) {
                    var at = child.payload() + 8;
                    ByteArrays.put64be(box, at, ByteArrays.i64be(box, at) + delta);
                    patched = true;
                }
            }
        }
        return patched;
    }

    /**
     * Returns whether a top-level box is an {@code mdat} that holds samples of the tracks a rewrite removes
     * (replaced chapter and timecode tracks) and of no other track, so it can be reused.
     *
     * @param index the index of the box in {@link #topLevel}
     * @param removed the identifiers of the removed tracks
     * @return {@code true} for such a box
     */
    private boolean reclaimable(int index, Set<Long> removed) {
        if (removed.isEmpty() || !topLevelTypes.get(index).equals("mdat")) {
            return false;
        }
        var start = topLevel.get(index)[0];
        var end = start + topLevel.get(index)[1];
        var holdsRemoved = false;
        for (var trak : Mp4Boxes.children(moov, moovPayload(), moov.length)) {
            if (!trak.type().equals("trak")) {
                continue;
            }
            var dropped = removed.contains(trackId(moov, trak));
            for (var offset : chunkOffsets(trak)) {
                var inside = offset >= start && offset < end;
                if (inside && !dropped) {
                    return false;
                }
                holdsRemoved |= inside;
            }
        }
        return holdsRemoved;
    }

    /**
     * Collects the chunk offsets of a track.
     *
     * @param trak the {@code trak} box
     * @return the offsets of every {@code stco} and {@code co64} box of its sample table
     */
    private List<Long> chunkOffsets(Mp4Boxes.Box trak) {
        var result = new ArrayList<Long>();
        var mdia = Mp4Boxes.find(Mp4Boxes.children(moov, trak.payload(), trak.end()), "mdia");
        var minf = mdia == null ? null : Mp4Boxes.find(Mp4Boxes.children(moov, mdia.payload(), mdia.end()), "minf");
        var stbl = minf == null ? null : Mp4Boxes.find(Mp4Boxes.children(moov, minf.payload(), minf.end()), "stbl");
        if (stbl == null) {
            return result;
        }
        for (var table : Mp4Boxes.children(moov, stbl.payload(), stbl.end())) {
            var wide = table.type().equals("co64");
            if (!wide && !table.type().equals("stco") || table.end() - table.payload() < 8) {
                continue;
            }
            var count = ByteArrays.u32be(moov, table.payload() + 4);
            var entry = wide ? 8 : 4;
            var position = table.payload() + 8;
            for (var i = 0L; i < count && table.end() - position >= entry; i++) {
                result.add(wide ? ByteArrays.i64be(moov, position) : ByteArrays.u32be(moov, position));
                position += entry;
            }
        }
        return result;
    }

    /**
     * Returns whether a top-level box type is free space.
     *
     * @param type a four-character box type
     * @return {@code true} for {@code free} and {@code skip}
     */
    private static boolean isFree(String type) {
        return type.equals("free") || type.equals("skip");
    }

    /**
     * Returns the handler type of a track.
     *
     * @param trak the {@code trak} box
     * @return the four-character handler type, or an empty string when the track has no readable
     * {@code hdlr}
     */
    private String handlerType(Mp4Boxes.Box trak) {
        var mdia = Mp4Boxes.find(Mp4Boxes.children(moov, trak.payload(), trak.end()), "mdia");
        var hdlr = mdia == null ? null : Mp4Boxes.find(Mp4Boxes.children(moov, mdia.payload(), mdia.end()), "hdlr");
        return hdlr == null || hdlr.size() < 20 ? "" : Mp4Boxes.type(moov, hdlr.payload() + 8);
    }

    /**
     * Builds the boxes that replace the old {@code moov} for one set of tags: the {@code mdat} of the samples of a
     * new chapter track and of a new timecode track, when there are any, followed by the new {@code moov}.
     */
    private final class Rewrite {
        /**
         * The new {@code udta}.
         */
        private final byte[] udta;

        /**
         * Whether the chapter tracks of the file are removed.
         */
        private final boolean dropping;

        /**
         * The identifiers of the tracks whose {@code trak} and {@code trex} boxes the rewrite removes: the chapter
         * tracks, when the chapters change, the timecode tracks no movie fragment names, when the timecode changes,
         * and every timecode track, when the timecode is removed. When a movie fragment or fragment random access box
         * is too large to rewrite, a chapter track whose samples are in fragments stays, without the chapter
         * references that made it one, since a track fragment must name a track of the movie (ISO/IEC 14496-12
         * clause 8.8.7).
         */
        private final Set<Long> removed;

        /**
         * The removed tracks that movie fragments name, whose track fragment boxes, and track fragment random access
         * boxes, become {@code free} boxes of the same size, so no offset of the file moves.
         */
        private final Set<Long> neutralized;

        /**
         * The new chapter track, or {@code null} when none is written.
         */
        private final Mp4ChapterTrack chapterTrack;

        /**
         * The identifier of the new chapter track.
         */
        private final long chapterId;

        /**
         * The largest identifier of a track the new {@code moov} keeps, or of any track of a fragmented file, whose
         * fragments may still name the removed ones.
         */
        private final long largestTrackId;

        /**
         * The identifiers of the tracks that reference the new chapter track.
         */
        private final Set<Long> referencing;

        /**
         * The new timecode track, or {@code null} when none is written.
         */
        private final Mp4TimecodeTrack timecodeTrack;

        /**
         * The identifier of the new timecode track.
         */
        private final long timecodeId;

        /**
         * The identifier of the timecode track whose place in {@code moov} and identifier the new timecode track
         * takes, or {@code -1} when it follows the other tracks.
         */
        private final long replacedTimecode;

        /**
         * The timecode written into the first sample of the file's timecode track in place, or {@code null}.
         */
        private final Timecode patchedTimecode;

        /**
         * The timecode tracks the video tracks reference: the new one, or {@code -1} for those they already do.
         */
        private final long timecodeReference;

        /**
         * The new tags of the tracks whose tags change, by {@code track_ID}.
         */
        private final Map<Long, TrackTags> changedTracks = new HashMap<>();

        /**
         * Whether each track whose tags change is enabled, by {@code track_ID}.
         */
        private final Map<Long, Boolean> enabledTracks = new HashMap<>();

        /**
         * Whether the opaque {@code ilst} items of a rebuilt track {@code udta} are kept.
         */
        private final boolean keepOpaque;

        /**
         * Whether the XMP packet changes.
         */
        private final boolean xmpChanged;

        /**
         * The top-level {@code uuid} box of the new XMP packet, written after {@code moov}, or an empty array (also
         * once {@link #replaceXmp(long)} has placed it in the old box).
         */
        private byte[] xmpBox;

        /**
         * The rebuilt keyed metadata boxes, parallel to {@link #keyedSlots}; an empty array removes one.
         */
        private final byte[][] keyedBoxes;

        /**
         * The new keyed metadata box written at the end of {@code moov}, or an empty array.
         */
        private final byte[] freshKeyed;

        /**
         * The {@code mdat} holding the samples of the new chapter and timecode tracks, or an empty array.
         */
        private byte[] auxiliaryData = new byte[0];

        /**
         * The file offset of the samples of the new chapter track.
         */
        private long chapterOffset;

        /**
         * The file offset of the sample of the new timecode track.
         */
        private long timecodeOffset;

        /**
         * Prepares the rewrite.
         *
         * @param newTags the tags
         * @param options the write options
         * @throws MediaException.Unsupported if an integer atom holds a value it cannot store, the timecode is no
         * time address the file's timecode track or video can count
         */
        Rewrite(MediaTags newTags, TagWriteOptions options) {
            this.keepOpaque = options.preserveUnknown();
            var children = Mp4Boxes.children(moov, moovPayload(), moov.length);
            var fragmented = Mp4Boxes.find(children, "mvex") != null;
            var fragmentNamed = fragmented ? fragmentTracks() : Set.<Long>of();
            var timecodeText = cmaf ? null : newTags.get(TIMECODE).map(String::strip).orElse(null);
            var target = timecodeTarget >= 0 ? timecodeTracks.get(timecodeTarget) : null;
            var removedTimecodes = new HashSet<Long>();
            Mp4TimecodeTrack layout = null;
            var replaced = -1L;
            Timecode patched = null;
            var asItem = false;
            var keepItems = false;
            if (target != null && timecodeText == null) {
                for (var info : timecodeTracks) {
                    removedTimecodes.add(info.trackId());
                }
            } else if (target != null && sameTimecode(timecodeText, target)) {
                keepItems = true;
            } else if (target != null && (fragmentNamed.contains(target.trackId()) || target.fragmented())) {
                patched = timecodeAt(timecodeText, target);
            } else if (target != null || timecodeText != null
                    && !timecodeText.equals(tags.get(TIMECODE).map(String::strip).orElse(null))) {
                layout = timecodeLayout(timecodeText);
                for (var info : timecodeTracks) {
                    if (info != target && !fragmentNamed.contains(info.trackId()) && !info.fragmented()) {
                        removedTimecodes.add(info.trackId());
                    }
                }
                replaced = target == null ? -1 : target.trackId();
            } else {
                asItem = true;
            }
            this.timecodeTrack = layout;
            this.replacedTimecode = replaced;
            this.patchedTimecode = patched;
            var routed = asItem ? newTags : newTags.toBuilder().remove(TIMECODE).build();
            var routing = route(routed);
            this.keyedBoxes = new byte[keyedSlots.size()][];
            for (var index = 0; index < keyedSlots.size(); index++) {
                var slot = keyedSlots.get(index);
                keyedBoxes[index] = slot.metadata().rebuild(moov, slot.box(), routing.keyed().get(index), keepOpaque);
            }
            this.freshKeyed = routing.fresh().isEmpty() ? new byte[0]
                    : Mp4KeyedMetadata.create(routing.fresh());
            this.xmpChanged = !Objects.equals(newTags.xmp().orElse(null), xmp);
            var packet = xmpChanged ? newTags.xmp().map(XmpPacket::toBytes).orElse(null) : null;
            var inUuid = packet != null && (xmpIndex >= 0 || xmp == null && !quickTime);
            this.xmpBox = inUuid ? Mp4Boxes.box("uuid", concat(XMP_UUID, packet)) : new byte[0];
            this.udta = buildUdta(Mp4Boxes.find(children, "udta"), routing, newTags.chapters(), keyedBoxes, xmpChanged,
                    inUuid ? null : packet, keepItems ? timecodeItems : List.of(), options);
            var kinds = new ArrayList<TrackKind>();
            var effective = new ArrayList<TrackTags>();
            var fileIds = new ArrayList<Long>();
            for (var info : trackInfo.entrySet()) {
                var replacedTags = newTags.track(info.getValue().tags().trackId());
                if (replacedTags.isPresent() && !replacedTags.get().equals(info.getValue().tags())) {
                    changedTracks.put(info.getKey(), replacedTags.get());
                }
                kinds.add(info.getValue().kind());
                effective.add(replacedTags.orElse(info.getValue().tags()));
                fileIds.add(info.getKey());
            }
            var enabled = Mp4TrackMetadata.enabled(kinds, effective);
            for (var index = 0; index < fileIds.size(); index++) {
                if (changedTracks.containsKey(fileIds.get(index))) {
                    enabledTracks.put(fileIds.get(index), enabled[index]);
                }
            }
            var chapters = newTags.chapters();
            var keep = !chapterTracks.isEmpty() && chapters.equals(tags.chapters());
            this.dropping = !chapterTracks.isEmpty() && !keep;
            var dropped = new HashSet<Long>(removedTimecodes);
            if (dropping) {
                var oversized = oversizedFragments();
                for (var id : chapterTracks) {
                    if (!oversized || !fragmentNamed.contains(id)) {
                        dropped.add(id);
                    }
                }
            }
            this.removed = Set.copyOf(dropped);
            var named = new HashSet<Long>(dropped);
            named.retainAll(fragmentNamed);
            this.neutralized = Set.copyOf(named);
            var largest = 0L;
            var largestAll = 0L;
            var media = new ArrayList<Long>();
            var first = -1L;
            for (var child : children) {
                if (!child.type().equals("trak")) {
                    continue;
                }
                var id = trackId(moov, child);
                largestAll = Math.max(largestAll, id);
                if (removed.contains(id)) {
                    continue;
                }
                largest = Math.max(largest, id);
                if (first < 0) {
                    first = id;
                }
                var handler = handlerType(child);
                if (handler.equals("soun") || handler.equals("vide")) {
                    media.add(id);
                }
            }
            if (media.isEmpty() && first >= 0) {
                media.add(first);
            }
            this.referencing = Set.copyOf(media);
            this.largestTrackId = fragmented ? largestAll : largest;
            if (fragmented) {
                // fragments may still name the identifiers of removed tracks
                var next = nextTrackId(Mp4Boxes.find(children, "mvhd"));
                this.chapterId = Math.max(largestAll + 1, next == U32_MAX ? 0 : next);
            } else {
                this.chapterId = largest + 1;
            }
            if (!chapters.isEmpty() && !keep && !referencing.isEmpty() && chapterId <= U32_MAX) {
                var end = durationMicros();
                if (end <= 0) {
                    end = demuxedDuration();
                }
                this.chapterTrack = Mp4ChapterTrack.of(chapters, end);
            } else {
                this.chapterTrack = null;
            }
            if (layout == null) {
                this.timecodeId = -1;
            } else if (replaced >= 0) {
                this.timecodeId = replaced;
            } else {
                this.timecodeId = chapterTrack != null ? chapterId + 1 : chapterId;
                if (timecodeId > U32_MAX) {
                    throw new MediaException.Unsupported("mp4", "no track identifier is left for a timecode track");
                }
            }
            this.timecodeReference = layout != null ? timecodeId : -1;
        }

        /**
         * Returns whether a timecode tag holds the timecode a timecode track gives.
         *
         * @param text the tag value, or {@code null}
         * @param track the timecode track
         * @return {@code true} when the value is the track's address at its rate
         */
        private boolean sameTimecode(String text, TimecodeInfo track) {
            if (text == null) {
                return false;
            }
            try {
                return Timecode.parse(text, track.timecode().framesPerSecond()).equals(track.timecode());
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        /**
         * Parses a timecode tag at the rate of an existing timecode track.
         *
         * @param text the tag value
         * @param track the timecode track
         * @return the timecode
         * @throws MediaException.Unsupported if the value is no valid address at the track's rate, or the sample of
         * the track cannot be located
         */
        private Timecode timecodeAt(String text, TimecodeInfo track) {
            if (track.sampleOffset() < 0) {
                throw new MediaException.Unsupported("mp4", "the sample of the timecode track cannot be located");
            }
            var nominal = Mp4TimecodeTrack.nominalRate(track.fields());
            try {
                var timecode = Timecode.parse(text, nominal);
                if (timecode.dropFrame() && nominal % 30 != 0) {
                    throw new IllegalArgumentException("a drop-frame timecode needs a multiple of 30 frames per second");
                }
                return timecode;
            } catch (IllegalArgumentException exception) {
                throw new MediaException.Unsupported("mp4", "the timecode track counts " + nominal + " frames per"
                        + " second, so \"" + text + "\" is no address it holds: " + exception.getMessage());
            }
        }

        /**
         * Lays out a new timecode track, counted at the frame rate of the first video track.
         *
         * @param text the tag value
         * @return the layout
         * @throws MediaException.Unsupported if the value is no valid address at that rate, or there is no video
         * track with a frame rate and the address is not a drop-frame one
         */
        private Mp4TimecodeTrack timecodeLayout(String text) {
            Rational rate = null;
            try (var demuxer = Containers.openDemuxer(source)) {
                var video = TimecodeTags.firstVideo(demuxer.tracks());
                rate = video == null ? null : video.frameRate().orElse(null);
            } catch (MediaException.Io exception) {
                throw exception;
            } catch (MediaException exception) {
                // a file the demuxer cannot read counts at no video rate
            }
            try {
                return Mp4TimecodeTrack.of(text, rate);
            } catch (IllegalArgumentException exception) {
                throw new MediaException.Unsupported("mp4", "a QuickTime timecode track holds a time address at the"
                        + " frame rate of the video: " + exception.getMessage());
            }
        }

        /**
         * {@return the identifiers of the tracks whose old samples the rewrite no longer references}
         */
        Set<Long> unreferenced() {
            if (replacedTimecode < 0) {
                return removed;
            }
            var result = new HashSet<>(removed);
            result.add(replacedTimecode);
            return result;
        }

        /**
         * {@return the splice of the first sample of the file's timecode track that holds the new timecode, or
         * {@code null} when the timecode is not written in place}
         */
        Splice samplePatch() {
            if (patchedTimecode == null) {
                return null;
            }
            var target = timecodeTracks.get(timecodeTarget);
            return new Splice(target.sampleOffset(), Mp4TimecodeTrack.SAMPLE_SIZE,
                    Mp4TimecodeTrack.sample(patchedTimecode));
        }

        /**
         * Returns the bytes that take the place of the file's old XMP {@code uuid} box: the new {@code uuid} box
         * followed by a zeroed {@code free} box of the rest when it fits, which then is no longer written after
         * {@code moov}, or a zeroed {@code free} box of the old size.
         *
         * @param size the size of the old box
         * @return the bytes, {@code size} long
         */
        byte[] replaceXmp(long size) {
            if (xmpBox.length == 0 || xmpBox.length != size && xmpBox.length + 8 > size) {
                return Mp4Boxes.free(size);
            }
            var out = new ByteWriter((int) size);
            out.bytes(xmpBox);
            if (xmpBox.length < size) {
                out.bytes(Mp4Boxes.free(size - xmpBox.length));
            }
            xmpBox = new byte[0];
            return out.toByteArray();
        }

        /**
         * Places the samples of the new chapter and timecode tracks at the start of the space the rewrite fills.
         *
         * @param slotStart the file offset where the rewritten boxes start
         */
        void place(long slotStart) {
            if (chapterTrack == null && timecodeTrack == null) {
                return;
            }
            var data = new ByteWriter(64);
            if (chapterTrack != null) {
                data.bytes(chapterTrack.data());
            }
            chapterOffset = slotStart + 8;
            timecodeOffset = chapterOffset + data.size();
            if (timecodeTrack != null) {
                data.bytes(timecodeTrack.sample());
            }
            auxiliaryData = Mp4Boxes.box("mdat", data.toByteArray());
        }

        /**
         * Builds the rewritten boxes, shifting the chunk offsets that point at or after
         * {@code threshold} by {@code delta}.
         *
         * @param delta the shift of the data after the old {@code moov}
         * @param threshold the first old offset that moves
         * @return the {@code mdat} of the new chapter and timecode samples, when there is one, and the new
         * {@code moov}
         */
        byte[] build(long delta, long threshold) {
            var children = Mp4Boxes.children(moov, moovPayload(), moov.length);
            var lastTrak = -1;
            for (var index = 0; index < children.size(); index++) {
                if (children.get(index).type().equals("trak")) {
                    lastTrak = index;
                }
            }
            var out = new ByteWriter(moov.length + auxiliaryData.length + 4096);
            out.bytes(auxiliaryData);
            var body = new ByteWriter(moov.length + 4096);
            var udtaWritten = false;
            var timescale = movieTimescale();
            var appendedTimecode = timecodeTrack != null && replacedTimecode < 0;
            for (var index = 0; index < children.size(); index++) {
                var child = children.get(index);
                switch (child.type()) {
                    case "udta" -> {
                        body.bytes(udta);
                        udtaWritten = true;
                    }
                    case "trak" -> {
                        var id = trackId(moov, child);
                        if (timecodeTrack != null && id == replacedTimecode) {
                            body.bytes(timecodeTrak(child, timescale));
                        } else if (!removed.contains(id)) {
                            body.bytes(rebuildTrack(child, delta, threshold,
                                    chapterTrack != null && referencing.contains(id) ? chapterId : -1,
                                    handlerType(child).equals("vide") ? timecodeReference : -1));
                        }
                    }
                    case "mvhd" -> body.bytes(withNextTrackId(child));
                    case "mvex" -> body.bytes(chapterTrack != null || appendedTimecode || !removed.isEmpty()
                            ? withExtends(child) : copy(child));
                    case "meta" -> {
                        var slot = keyedSlot(child);
                        body.bytes(slot >= 0 ? keyedBoxes[slot] : copy(child));
                    }
                    default -> body.bytes(moov, child.offset(), child.size());
                }
                if (index == lastTrak) {
                    if (chapterTrack != null) {
                        body.bytes(chapterTrack.trak(chapterId, timescale, chapterOffset,
                                chapterOffset + chapterTrack.dataLength() > U32_MAX));
                    }
                    if (appendedTimecode) {
                        body.bytes(timecodeTrak(null, timescale));
                    }
                }
            }
            if (!udtaWritten && udta.length > 8) {
                body.bytes(udta);
            }
            body.bytes(freshKeyed);
            Mp4Boxes.box(out, "moov", body.toByteArray());
            out.bytes(xmpBox);
            return out.toByteArray();
        }

        /**
         * Builds the {@code trak} box of the new timecode track, with the tag boxes of the track it replaces.
         *
         * @param old the {@code trak} box of the replaced timecode track, or {@code null}
         * @param timescale the movie timescale
         * @return the box
         */
        private byte[] timecodeTrak(Mp4Boxes.Box old, long timescale) {
            var trak = timecodeTrack.trak(timecodeId, timescale, movieDuration(timescale), timecodeOffset,
                    timecodeOffset + Mp4TimecodeTrack.SAMPLE_SIZE > U32_MAX);
            if (old == null) {
                return trak;
            }
            var id = trackId(moov, old);
            var described = changedTracks.get(id);
            var info = trackInfo.get(id);
            var routed = described == null || info == null ? null : routeTrack(info, described, keepOpaque);
            var extra = new ByteWriter(256);
            var udtaWritten = false;
            for (var child : Mp4Boxes.children(moov, old.payload(), old.end())) {
                if (child.type().equals("meta")) {
                    var slot = info == null ? -1 : slotOf(info.keyed(), child);
                    extra.bytes(routed != null && slot >= 0 ? routed.keyed()[slot] : copy(child));
                } else if (child.type().equals("udta") && !udtaWritten) {
                    extra.bytes(routed == null ? copy(child) : trackUserData(child, routed.tags(),
                            keepOpaque ? info.opaque() : List.of(), info, routed));
                    udtaWritten = true;
                }
            }
            if (routed != null && !udtaWritten) {
                extra.bytes(trackUserData(null, routed.tags(), List.of(), info, routed));
            }
            if (routed != null) {
                extra.bytes(routed.fresh());
            }
            if (extra.size() == 0) {
                return trak;
            }
            return Mp4Boxes.box("trak", concat(Arrays.copyOfRange(trak, 8, trak.length), extra.toByteArray()));
        }

        /**
         * {@return the movie duration in units of the movie timescale, from {@code mvhd} or, for a fragmented
         * file whose {@code mvhd} records none, from the demuxer}
         *
         * @param timescale the movie timescale
         */
        private long movieDuration(long timescale) {
            var units = movieDurationUnits();
            if (units > 0) {
                return units;
            }
            var micros = demuxedDuration();
            if (micros <= 0) {
                return 0;
            }
            try {
                return new Rational(1, 1_000_000).rescale(micros, new Rational(1, timescale));
            } catch (ArithmeticException exception) {
                return U32_MAX;
            }
        }

        /**
         * {@return a copy of a child of {@code moov}}
         *
         * @param box the child
         */
        private byte[] copy(Mp4Boxes.Box box) {
            return Arrays.copyOfRange(moov, box.offset(), box.end());
        }

        /**
         * Copies {@code mvhd} with a {@code next_track_ID} past every track of the new {@code moov}, the new chapter
         * and timecode tracks included; a value already past them, or the all-ones value that asks a writer to search
         * for a free identifier, is kept.
         *
         * @param mvhd the box
         * @return the box
         */
        private byte[] withNextTrackId(Mp4Boxes.Box mvhd) {
            var box = copy(mvhd);
            var at = mvhd.header() + nextTrackIdIndex(box[mvhd.header()]);
            var newest = Math.max(largestTrackId,
                    Math.max(chapterTrack != null ? chapterId : 0, timecodeTrack != null ? timecodeId : 0));
            if (at + 4 <= box.length) {
                var nextId = ByteArrays.u32be(box, at);
                if (nextId != U32_MAX && nextId <= newest) {
                    ByteArrays.put32be(box, at, newest + 1);
                }
            }
            return box;
        }

        /**
         * Copies {@code mvex} without the track extends boxes of the removed tracks and with one for each new
         * chapter or timecode track that follows the other tracks.
         *
         * @param mvex the box
         * @return the box
         */
        private byte[] withExtends(Mp4Boxes.Box mvex) {
            var out = new ByteWriter(mvex.size() + 64);
            for (var child : Mp4Boxes.children(moov, mvex.payload(), mvex.end())) {
                if (child.type().equals("trex") && child.payload() + 8 <= child.end()
                        && removed.contains(ByteArrays.u32be(moov, child.payload() + 4))) {
                    continue;
                }
                out.bytes(moov, child.offset(), child.size());
            }
            if (chapterTrack != null) {
                trackExtends(out, chapterId);
            }
            if (timecodeTrack != null && replacedTimecode < 0) {
                trackExtends(out, timecodeId);
            }
            return Mp4Boxes.box("mvex", out.toByteArray());
        }

        /**
         * Writes the track extends box of a new track whose samples are all in {@code moov}.
         *
         * @param out the destination
         * @param trackId the identifier of the track
         */
        private static void trackExtends(ByteWriter out, long trackId) {
            var trex = new ByteWriter(24);
            trex.u32be(0);
            trex.u32be(trackId);
            trex.u32be(1);
            trex.u32be(0);
            trex.u32be(0);
            trex.u32be(0);
            Mp4Boxes.box(out, "trex", trex.toByteArray());
        }

        /**
         * Rebuilds a track: its chunk offsets shifted, its references to removed chapter and timecode tracks
         * dropped, references to the new chapter and timecode tracks added, its tags rewritten and, for the timecode
         * track written in place, its drop-frame flag set.
         *
         * @param trak the {@code trak} box
         * @param delta the shift of the data after the old {@code moov}
         * @param threshold the first old offset that moves
         * @param reference the identifier of the chapter track to reference, or {@code -1}
         * @param timecode the identifier of the timecode track to reference, or {@code -1}
         * @return the box
         */
        private byte[] rebuildTrack(Mp4Boxes.Box trak, long delta, long threshold, long reference, long timecode) {
            var id = trackId(moov, trak);
            var described = changedTracks.get(id);
            var patched = patchedTimecode != null && id == timecodeTracks.get(timecodeTarget).trackId();
            var children = Mp4Boxes.children(moov, trak.payload(), trak.end());
            var tref = Mp4Boxes.find(children, "tref");
            var dropsReferences = tref != null && !removed.isEmpty();
            if (delta == 0 && !dropping && reference < 0 && timecode < 0 && described == null && !patched
                    && !dropsReferences) {
                return copy(trak);
            }
            var info = trackInfo.get(id);
            var routed = described == null || info == null ? null : routeTrack(info, described, keepOpaque);
            var out = new ByteWriter(trak.size() + 64);
            var udtaWritten = false;
            for (var child : children) {
                switch (child.type()) {
                    case "tref" -> out.bytes(references(child, reference, timecode));
                    case "mdia" -> {
                        var mdia = delta != 0
                                ? Mp4Boxes.box("mdia", relocate(moov, child.payload(), child.end(), delta, threshold, 0))
                                : copy(child);
                        if (patched) {
                            setDropFrame(mdia, patchedTimecode.dropFrame());
                        }
                        out.bytes(mdia);
                    }
                    case "tkhd" -> {
                        var tkhd = copy(child);
                        if (described != null && child.size() >= child.header() + 4) {
                            var at = child.header() + 3;
                            tkhd[at] = (byte) (enabledTracks.get(id) ? tkhd[at] | Mp4TrackMetadata.ENABLED
                                    : tkhd[at] & ~Mp4TrackMetadata.ENABLED);
                        }
                        out.bytes(tkhd);
                    }
                    case "meta" -> {
                        var slot = info == null ? -1 : slotOf(info.keyed(), child);
                        out.bytes(routed != null && slot >= 0 ? routed.keyed()[slot] : copy(child));
                    }
                    case "udta" -> {
                        if (routed == null) {
                            out.bytes(moov, child.offset(), child.size());
                        } else if (!udtaWritten) {
                            out.bytes(trackUserData(child, routed.tags(), keepOpaque ? info.opaque() : List.of(), info,
                                    routed));
                            udtaWritten = true;
                        }
                    }
                    default -> out.bytes(moov, child.offset(), child.size());
                }
                if (child.type().equals("tkhd") && tref == null && (reference >= 0 || timecode >= 0)) {
                    var references = new ByteWriter(32);
                    if (reference >= 0) {
                        references.bytes(Mp4ChapterTrack.chapReference(reference));
                    }
                    if (timecode >= 0) {
                        references.bytes(Mp4TimecodeTrack.reference(timecode));
                    }
                    Mp4Boxes.box(out, "tref", references.toByteArray());
                }
            }
            if (routed != null && !udtaWritten) {
                out.bytes(trackUserData(null, routed.tags(), List.of(), info, routed));
            }
            if (routed != null) {
                out.bytes(routed.fresh());
            }
            return Mp4Boxes.box("trak", out.toByteArray());
        }

        /**
         * Rebuilds the {@code udta} of a track with new tags: its {@code name} box, the {@code kind} boxes
         * of the roles that name dispositions, its iTunes {@code meta} box and its QuickTime text atoms
         * replaced, its keyed metadata boxes rebuilt, its other children kept.
         *
         * @param old the old {@code udta}, or {@code null}
         * @param described the new tags its boxes store
         * @param opaque the opaque {@code ilst} items of the old {@code meta}
         * @param info the track, or {@code null}
         * @param routed where the new tags of the track go, or {@code null}
         * @return the box, or an empty array when nothing remains in it
         */
        private byte[] trackUserData(Mp4Boxes.Box old, TrackTags described, List<byte[]> opaque, TrackInfo info,
                                     TrackRouting routed) {
            var out = new ByteWriter(256);
            if (old != null) {
                for (var child : Mp4Boxes.children(moov, old.payload(), old.end())) {
                    var slot = info == null || !child.type().equals("meta") ? -1 : slotOf(info.keyed(), child);
                    if (slot >= 0) {
                        out.bytes(routed != null ? routed.keyed()[slot] : copy(child));
                        continue;
                    }
                    var replaced = switch (child.type()) {
                        case "name" -> true;
                        case "kind" -> isRoleKind(child);
                        case "meta" -> isItunesMeta(child);
                        default -> quickTimeText(child) != null;
                    };
                    if (!replaced) {
                        out.bytes(moov, child.offset(), child.size());
                    }
                }
            }
            out.bytes(Mp4TrackMetadata.userDataChildren(described, opaque));
            return out.size() == 0 ? new byte[0] : Mp4Boxes.box("udta", out.toByteArray());
        }

        /**
         * Rebuilds a {@code tref} box: its {@code chap} references are dropped with the chapter tracks or replaced
         * by the reference to the new chapter track, its {@code tmcd} references lose the removed timecode tracks
         * and gain the new one.
         *
         * @param tref the box
         * @param reference the identifier of the chapter track to reference, or {@code -1}
         * @param timecode the identifier of the timecode track to reference, or {@code -1}
         * @return the box, or an empty array when no reference remains
         */
        private byte[] references(Mp4Boxes.Box tref, long reference, long timecode) {
            var out = new ByteWriter(tref.size() + 24);
            var timecodeWritten = false;
            for (var child : Mp4Boxes.children(moov, tref.payload(), tref.end())) {
                if (child.type().equals(Mp4TimecodeTrack.TYPE)) {
                    var ids = new ByteWriter(child.size());
                    for (var at = child.payload(); at + 4 <= child.end(); at += 4) {
                        var id = ByteArrays.u32be(moov, at);
                        if (!removed.contains(id)) {
                            ids.u32be(id);
                            timecodeWritten |= id == timecode;
                        }
                    }
                    if (timecode >= 0 && !timecodeWritten) {
                        ids.u32be(timecode);
                        timecodeWritten = true;
                    }
                    if (ids.size() > 0) {
                        Mp4Boxes.box(out, Mp4TimecodeTrack.TYPE, ids.toByteArray());
                    }
                } else if (!child.type().equals("chap") || !dropping && reference < 0) {
                    out.bytes(moov, child.offset(), child.size());
                }
            }
            if (reference >= 0) {
                out.bytes(Mp4ChapterTrack.chapReference(reference));
            }
            if (timecode >= 0 && !timecodeWritten) {
                out.bytes(Mp4TimecodeTrack.reference(timecode));
            }
            return out.size() == 0 ? new byte[0] : Mp4Boxes.box("tref", out.toByteArray());
        }
    }

    /**
     * Where the new tags of a track go.
     *
     * @param tags the tags its {@code udta} stores
     * @param keyed the rebuilt keyed metadata boxes, parallel to the track's; an empty array removes one
     * @param fresh the new keyed metadata box at the end of its {@code trak}, or an empty array
     */
    private record TrackRouting(TrackTags tags, byte[][] keyed, byte[] fresh) {
    }

    /**
     * Assigns the new tags of a track to its boxes: a key goes to the keyed metadata boxes that held it (and to its
     * {@code udta} too when that held it as well), a reverse-DNS key neither holds to its first keyed metadata box or
     * a new one at the end of the {@code trak}, and every other key, the title always, to its {@code udta}.
     *
     * @param info the track
     * @param described the new tags of the track
     * @param keepOpaque whether the opaque keyed metadata items of keys whose values are unchanged are kept
     * @return where the tags go
     */
    private TrackRouting routeTrack(TrackInfo info, TrackTags described, boolean keepOpaque) {
        var grouped = new LinkedHashMap<String, List<String>>();
        for (var entry : described.tags().entries()) {
            grouped.computeIfAbsent(entry.key(), _ -> new ArrayList<>()).add(entry.value());
        }
        var keyed = new ArrayList<Map<String, List<String>>>();
        for (var ignored : info.keyed()) {
            keyed.add(new LinkedHashMap<>());
        }
        var fresh = new LinkedHashMap<String, List<String>>();
        var toUdta = new HashSet<String>();
        var title = StandardTag.TITLE.key().toLowerCase(Locale.ROOT);
        for (var group : grouped.entrySet()) {
            var lower = group.getKey().toLowerCase(Locale.ROOT);
            var holders = info.keyedKeys().get(lower);
            if (holders != null) {
                for (var holder : holders) {
                    keyed.get(holder).put(group.getKey(), group.getValue());
                }
                if (info.udtaKeys().contains(lower) || lower.equals(title)) {
                    toUdta.add(lower);
                }
            } else if (!info.udtaKeys().contains(lower) && !lower.equals(title)
                    && Mp4KeyedMetadata.isReverseDns(group.getKey())) {
                (info.keyed().isEmpty() ? fresh : keyed.getFirst()).put(group.getKey(), group.getValue());
            } else {
                toUdta.add(lower);
            }
        }
        var udtaTags = MediaTags.builder();
        for (var entry : described.tags().entries()) {
            if (toUdta.contains(entry.key().toLowerCase(Locale.ROOT))) {
                udtaTags.add(entry.key(), entry.value());
            }
        }
        var boxes = new byte[info.keyed().size()][];
        for (var index = 0; index < boxes.length; index++) {
            var slot = info.keyed().get(index);
            boxes[index] = slot.metadata().rebuild(moov, slot.box(), keyed.get(index), keepOpaque);
        }
        return new TrackRouting(described.withTags(udtaTags.build()), boxes,
                fresh.isEmpty() ? new byte[0] : Mp4KeyedMetadata.create(fresh));
    }

    /**
     * Sets or clears the drop-frame flag of the {@code tmcd} sample entry of a track's media box, in place.
     *
     * @param mdia the {@code mdia} box
     * @param dropFrame whether the timecode counts drop-frame
     */
    private static void setDropFrame(byte[] mdia, boolean dropFrame) {
        var header = ByteArrays.u32be(mdia, 0) == 1 ? 16 : 8;
        var minf = Mp4Boxes.find(Mp4Boxes.children(mdia, header, mdia.length), "minf");
        var stbl = minf == null ? null : Mp4Boxes.find(Mp4Boxes.children(mdia, minf.payload(), minf.end()), "stbl");
        var stsd = stbl == null ? null : Mp4Boxes.find(Mp4Boxes.children(mdia, stbl.payload(), stbl.end()), "stsd");
        if (stsd == null || stsd.end() - stsd.payload() < 8) {
            return;
        }
        var entries = Mp4Boxes.children(mdia, stsd.payload() + 8, stsd.end());
        if (entries.isEmpty() || entries.getFirst().end() - entries.getFirst().payload()
                < 8 + Mp4TimecodeTrack.ENTRY_FIELDS) {
            return;
        }
        var at = entries.getFirst().payload() + 12;
        var flags = ByteArrays.u32be(mdia, at);
        ByteArrays.put32be(mdia, at, dropFrame ? flags | Mp4TimecodeTrack.FLAG_DROP_FRAME
                : flags & ~Mp4TimecodeTrack.FLAG_DROP_FRAME);
    }

    /**
     * Returns the index of {@code next_track_ID} in the payload of {@code mvhd}.
     *
     * @param version the first byte of the payload, the box version
     * @return the index
     */
    private static int nextTrackIdIndex(byte version) {
        return (version & 0xFF) == 1 ? 108 : 96;
    }

    /**
     * Reads {@code next_track_ID} from {@code mvhd}.
     *
     * @param mvhd the box, or {@code null}
     * @return the identifier, or {@code 0} when the box is missing or too short
     */
    private long nextTrackId(Mp4Boxes.Box mvhd) {
        if (mvhd == null || mvhd.size() < mvhd.header() + 4) {
            return 0;
        }
        var at = mvhd.payload() + nextTrackIdIndex(moov[mvhd.payload()]);
        return at + 4 <= mvhd.end() ? ByteArrays.u32be(moov, at) : 0;
    }

    /**
     * {@return the movie timescale from {@code mvhd}, or 1000 when it has none}
     */
    private long movieTimescale() {
        var mvhd = Mp4Boxes.find(Mp4Boxes.children(moov, moovPayload(), moov.length), "mvhd");
        if (mvhd == null || mvhd.size() < 32) {
            return 1000;
        }
        var position = mvhd.payload();
        var timescale = ByteArrays.u32be(moov, position + ((moov[position] & 0xFF) == 1 ? 20 : 12));
        return timescale == 0 ? 1000 : timescale;
    }

    /**
     * Returns the identifier of a track from its {@code tkhd}.
     *
     * @param data the bytes holding the track
     * @param trak the {@code trak} box
     * @return the track identifier, or {@code -1} when it has no readable {@code tkhd}
     */
    private static long trackId(byte[] data, Mp4Boxes.Box trak) {
        var tkhd = Mp4Boxes.find(Mp4Boxes.children(data, trak.payload(), trak.end()), "tkhd");
        if (tkhd == null || tkhd.size() < 24) {
            return -1;
        }
        var version = data[tkhd.payload()] & 0xFF;
        var at = tkhd.payload() + (version == 1 ? 20 : 12);
        return at + 4 <= tkhd.end() ? ByteArrays.u32be(data, at) : -1;
    }

    /**
     * Returns the track identifiers the track fragment headers of the top-level movie fragments, compressed ones
     * included, name.
     *
     * @return the identifiers
     */
    private Set<Long> fragmentTracks() {
        var ids = new HashSet<Long>();
        for (var index = 0; index < topLevel.size(); index++) {
            var box = topLevel.get(index);
            byte[] moof;
            int header;
            if (topLevelTypes.get(index).equals("moof")) {
                moof = TagIo.read(source, box[0], box[1], limits);
                header = (int) box[2];
            } else if (topLevelTypes.get(index).equals(COMPRESSED_FRAGMENT)) {
                moof = inflate(TagIo.read(source, box[0] + box[2], box[1] - box[2], limits));
                header = 0;
            } else {
                continue;
            }
            for (var traf : Mp4Boxes.children(moof, header, moof.length)) {
                if (!traf.type().equals("traf")) {
                    continue;
                }
                var tfhd = Mp4Boxes.find(Mp4Boxes.children(moof, traf.payload(), traf.end()), "tfhd");
                if (tfhd != null && tfhd.payload() + 8 <= tfhd.end()) {
                    ids.add(ByteArrays.u32be(moof, tfhd.payload() + 4));
                }
            }
        }
        return ids;
    }

    /**
     * Returns whether a top-level movie fragment or fragment random access box is larger than the fragments a
     * rewrite reads into memory to turn the track fragments of removed tracks into {@code free} boxes.
     *
     * @return whether such a box exists
     */
    private boolean oversizedFragments() {
        for (var index = 0; index < topLevel.size(); index++) {
            var type = topLevelTypes.get(index);
            if ((type.equals("moof") || type.equals("mfra")) && topLevel.get(index)[1] > MAX_FRAGMENT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects the tracks that other tracks reference as QuickTime chapter tracks
     * ({@code tref/chap}).
     *
     * @return the referenced track identifiers
     */
    private Set<Long> findChapterTracks() {
        var result = new HashSet<Long>();
        for (var trak : Mp4Boxes.children(moov, moovPayload(), moov.length)) {
            if (!trak.type().equals("trak")) {
                continue;
            }
            var tref = Mp4Boxes.find(Mp4Boxes.children(moov, trak.payload(), trak.end()), "tref");
            if (tref == null) {
                continue;
            }
            for (var reference : Mp4Boxes.children(moov, tref.payload(), tref.end())) {
                if (reference.type().equals("chap")) {
                    for (var at = reference.payload(); at + 4 <= reference.end(); at += 4) {
                        result.add(ByteArrays.u32be(moov, at));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Rebuilds the children of a box of a track's media, shifting the chunk offsets of every
     * {@code stco} and {@code co64} box below it; a {@code stco} box with an offset the shift takes
     * past 32 bits becomes a {@code co64} box.
     *
     * @param data the bytes holding the boxes
     * @param start the index of the first child
     * @param end the end of the children
     * @param delta the shift
     * @param threshold the first old offset that moves
     * @param depth the nesting depth
     * @return the rebuilt children
     * @throws MediaException.Malformed if a shifted offset is negative
     */
    static byte[] relocate(byte[] data, int start, int end, long delta, long threshold, int depth) {
        var out = new ByteWriter(end - start + 64);
        for (var child : Mp4Boxes.children(data, start, end)) {
            switch (child.type()) {
                case "minf", "stbl" -> {
                    if (depth < 8) {
                        Mp4Boxes.box(out, child.type(), relocate(data, child.payload(), child.end(), delta, threshold,
                                depth + 1));
                    } else {
                        out.bytes(data, child.offset(), child.size());
                    }
                }
                case "stco", "co64" -> out.bytes(relocateTable(data, child, delta, threshold));
                default -> out.bytes(data, child.offset(), child.size());
            }
        }
        return out.toByteArray();
    }

    /**
     * Shifts the offsets of one {@code stco} or {@code co64} box.
     *
     * @param data the bytes holding the box
     * @param table the box
     * @param delta the shift
     * @param threshold the first old offset that moves
     * @return the shifted box, a {@code co64} box when a {@code stco} offset no longer fits 32 bits
     * @throws MediaException.Malformed if a shifted offset is negative
     */
    private static byte[] relocateTable(byte[] data, Mp4Boxes.Box table, long delta, long threshold) {
        var wide = table.type().equals("co64");
        var position = table.payload() + 4;
        if (table.end() - position < 4) {
            return Arrays.copyOfRange(data, table.offset(), table.end());
        }
        var entry = wide ? 8 : 4;
        var count = (int) Math.min(ByteArrays.u32be(data, position), (table.end() - position - 4) / entry);
        position += 4;
        var offsets = new long[count];
        var promote = false;
        for (var i = 0; i < count; i++) {
            var offset = wide ? ByteArrays.i64be(data, position) : ByteArrays.u32be(data, position);
            if (offset >= threshold) {
                offset += delta;
                if (offset < 0) {
                    throw new MediaException.Malformed("chunk offset shifted before the start of the file");
                }
            }
            offsets[i] = offset;
            promote |= !wide && offset > U32_MAX;
            position += entry;
        }
        var out = new ByteWriter(8 + count * (wide || promote ? 8 : 4));
        out.bytes(data, table.payload(), 4);
        out.u32be(count);
        for (var offset : offsets) {
            if (wide || promote) {
                out.u64be(offset);
            } else {
                out.u32be(offset);
            }
        }
        return Mp4Boxes.box(wide || promote ? "co64" : "stco", out.toByteArray());
    }

    /**
     * Assigns the entries of new tags to the places that store them.
     *
     * @param newTags the tags
     * @return where each entry goes
     * @throws MediaException.Unsupported if a value of the {@code loci} key is not ISO 6709 coordinates in range
     */
    private Routing route(MediaTags newTags) {
        var grouped = new LinkedHashMap<String, List<String>>();
        for (var entry : newTags.entries()) {
            grouped.computeIfAbsent(entry.key(), _ -> new ArrayList<>()).add(entry.value());
        }
        var keyed = new ArrayList<Map<String, List<String>>>();
        for (var ignored : keyedSlots) {
            keyed.add(new LinkedHashMap<>());
        }
        var fresh = new LinkedHashMap<String, List<String>>();
        var toIlst = new HashSet<String>();
        List<String> loci = List.of();
        for (var group : grouped.entrySet()) {
            var key = group.getKey();
            var lower = key.toLowerCase(Locale.ROOT);
            var holders = keyedKeys.get(lower);
            if (lower.equals(LOCI)) {
                loci = List.copyOf(group.getValue());
                for (var value : loci) {
                    try {
                        LocationInformation.parse(value);
                    } catch (IllegalArgumentException exception) {
                        throw new MediaException.Unsupported("mp4", "the 3GPP loci box holds ISO 6709 coordinates, "
                                + exception.getMessage());
                    }
                }
            } else if (holders != null) {
                for (var holder : holders) {
                    keyed.get(holder).put(key, group.getValue());
                }
                if (ilstKeys.contains(lower)) {
                    toIlst.add(lower);
                }
            } else if (!ilstKeys.contains(lower) && Mp4KeyedMetadata.isReverseDns(key)) {
                (keyedSlots.isEmpty() ? fresh : keyed.getFirst()).put(key, group.getValue());
            } else {
                toIlst.add(lower);
            }
        }
        var ilst = MediaTags.builder().pictures(newTags.pictures());
        for (var entry : newTags.entries()) {
            if (toIlst.contains(entry.key().toLowerCase(Locale.ROOT))) {
                ilst.add(entry.key(), entry.value());
            }
        }
        return new Routing(ilst.build(), loci, keyed, fresh);
    }

    /**
     * Where the entries of new tags go.
     *
     * @param ilst the entries and pictures of the {@code ilst}
     * @param loci the coordinates of the {@code loci} boxes
     * @param keyed the values of each keyed metadata box, parallel to {@link #keyedSlots}, by key in order
     * @param fresh the values of a new keyed metadata box, by key in order
     */
    private record Routing(MediaTags ilst, List<String> loci, List<Map<String, List<String>>> keyed,
                           Map<String, List<String>> fresh) {
    }

    /**
     * Builds the new {@code udta}.
     *
     * @param old the old {@code udta}, or {@code null}
     * @param routing where the entries go
     * @param chapters the chapters of the Nero {@code chpl} box
     * @param keyedBoxes the rebuilt keyed metadata boxes, parallel to {@link #keyedSlots}
     * @param xmpChanged whether the XMP packet changes
     * @param xmpData the new XMP packet to store in an {@code XMP_} atom, or {@code null}
     * @param kept the {@code ilst} items kept whatever the options, each a complete box
     * @param options the write options
     * @return the box
     * @throws MediaException.Unsupported if an integer atom holds a value it cannot store
     */
    private byte[] buildUdta(Mp4Boxes.Box old, Routing routing, List<Chapter> chapters, byte[][] keyedBoxes,
                             boolean xmpChanged, byte[] xmpData, List<byte[]> kept, TagWriteOptions options) {
        var written = new HashSet<String>();
        var items = new ArrayList<byte[]>(options.preserveUnknown() ? opaqueItems : List.of());
        items.addAll(kept);
        var ilst = ilst(routing.ilst(), items, integerForms, written);
        var out = new ByteWriter(ilst.length + 256);
        var metaWritten = false;
        var replaceLoci = !routing.loci().equals(lociValue == null ? List.of() : List.of(lociValue));
        var lociWritten = false;
        Mp4Boxes.Box lociTemplate = null;
        var xmpWritten = false;
        if (old != null) {
            for (var child : Mp4Boxes.children(moov, old.payload(), old.end())) {
                if (child.type().equals(LocationInformation.TYPE) && lociTemplate == null
                        && LocationInformation.coordinates(moov, child.payload(), child.end()) != null) {
                    lociTemplate = child;
                }
            }
            for (var child : Mp4Boxes.children(moov, old.payload(), old.end())) {
                switch (child.type()) {
                    case "meta" -> {
                        var slot = keyedSlot(child);
                        if (slot >= 0) {
                            out.bytes(keyedBoxes[slot]);
                        } else if (isItunesMeta(child)) {
                            out.bytes(buildMeta(child, ilst));
                            metaWritten = true;
                        } else {
                            out.bytes(moov, child.offset(), child.size());
                        }
                    }
                    case "chpl" -> {
                    }
                    case LocationInformation.TYPE -> {
                        if (!replaceLoci) {
                            out.bytes(moov, child.offset(), child.size());
                        } else if (!lociWritten) {
                            out.bytes(lociBoxes(routing.loci(), lociTemplate));
                            lociWritten = true;
                        }
                    }
                    case XMP_ATOM -> {
                        if (!xmpChanged) {
                            out.bytes(moov, child.offset(), child.size());
                        } else if (xmpData != null && !xmpWritten) {
                            Mp4Boxes.box(out, XMP_ATOM, xmpData);
                            xmpWritten = true;
                        }
                    }
                    default -> {
                        if (quickTimeText(child) == null) {
                            out.bytes(moov, child.offset(), child.size());
                        }
                    }
                }
            }
        }
        if (!metaWritten && ilst.length > 8) {
            out.bytes(buildMeta(null, ilst));
        }
        if (replaceLoci && !lociWritten) {
            out.bytes(lociBoxes(routing.loci(), lociTemplate));
        }
        if (xmpData != null && !xmpWritten) {
            Mp4Boxes.box(out, XMP_ATOM, xmpData);
        }
        if (!chapters.isEmpty()) {
            out.bytes(chapterBox(chapters));
        }
        return Mp4Boxes.box("udta", out.toByteArray());
    }

    /**
     * Builds the {@code loci} boxes of coordinates.
     *
     * @param coordinates the ISO 6709 coordinates, one box each
     * @param template the old box whose other fields the new boxes keep, or {@code null}
     * @return the boxes
     */
    private byte[] lociBoxes(List<String> coordinates, Mp4Boxes.Box template) {
        var out = new ByteWriter(64 * coordinates.size());
        for (var value : coordinates) {
            out.bytes(template == null ? LocationInformation.box(null, 0, 0, value)
                    : LocationInformation.box(moov, template.payload(), template.end(), value));
        }
        return out.toByteArray();
    }

    /**
     * Encodes the boxes the MP4 muxers write at the end of {@code moov}: a {@code udta} box holding an iTunes
     * {@code meta} box with the entries and pictures, the {@code loci} boxes of the {@code loci} key, an
     * {@code XMP_} atom of the XMP packet in a QuickTime movie and a Nero {@code chpl} box of the chapters, then a
     * keyed metadata {@code meta} box of the reverse-DNS keys.
     *
     * @param tags the tags to store
     * @param quickTime whether the movie is a QuickTime movie, whose keyed metadata box is a plain atom and whose XMP
     * packet goes in {@code udta}
     * @return the boxes, or an empty array when there is nothing to store
     * @throws MediaException.Unsupported if a value of an iTunes integer atom is not an integer the atom holds, or a
     * value of the {@code loci} key is not ISO 6709 coordinates in range
     */
    static byte[] movieBoxes(MediaTags tags, boolean quickTime) {
        var grouped = new LinkedHashMap<String, List<String>>();
        for (var entry : tags.entries()) {
            grouped.computeIfAbsent(entry.key(), _ -> new ArrayList<>()).add(entry.value());
        }
        var ilstTags = MediaTags.builder().pictures(tags.pictures());
        var keyed = new LinkedHashMap<String, List<String>>();
        var loci = new ByteWriter(64);
        for (var group : grouped.entrySet()) {
            if (group.getKey().equalsIgnoreCase(LOCI)) {
                for (var value : group.getValue()) {
                    try {
                        loci.bytes(LocationInformation.box(null, 0, 0, value));
                    } catch (IllegalArgumentException exception) {
                        throw new MediaException.Unsupported("mp4", "the 3GPP loci box holds ISO 6709 coordinates, "
                                + exception.getMessage());
                    }
                }
            } else if (Mp4KeyedMetadata.isReverseDns(group.getKey())) {
                keyed.put(group.getKey(), group.getValue());
            } else {
                for (var value : group.getValue()) {
                    ilstTags.add(group.getKey(), value);
                }
            }
        }
        var ilstOnly = ilstTags.build();
        var userData = new ByteWriter(4096);
        if (!ilstOnly.entries().isEmpty() || !ilstOnly.pictures().isEmpty()) {
            var meta = new ByteWriter(4096);
            meta.u32be(0);
            meta.bytes(handler());
            meta.bytes(ilst(ilstOnly, List.of(), Map.of(), new HashSet<>()));
            Mp4Boxes.box(userData, "meta", meta.toByteArray());
        }
        userData.bytes(loci.toByteArray());
        if (quickTime && tags.xmp().isPresent()) {
            Mp4Boxes.box(userData, XMP_ATOM, tags.xmp().get().toBytes());
        }
        if (!tags.chapters().isEmpty()) {
            userData.bytes(chapterBox(tags.chapters()));
        }
        var out = new ByteWriter(userData.size() + 512);
        if (userData.size() > 0) {
            Mp4Boxes.box(out, "udta", userData.toByteArray());
        }
        if (!keyed.isEmpty()) {
            out.bytes(Mp4KeyedMetadata.create(keyed));
        }
        return out.toByteArray();
    }

    /**
     * Encodes the top-level XMP {@code uuid} box of the tags' XMP packet.
     *
     * @param tags the tags whose packet is encoded
     * @return the box, or an empty array without a packet
     */
    static byte[] xmpBox(MediaTags tags) {
        return tags.xmp().map(packet -> Mp4Boxes.box("uuid", concat(XMP_UUID, packet.toBytes()))).orElse(new byte[0]);
    }

    /**
     * Builds a {@code meta} box around an {@code ilst}.
     *
     * @param old the old {@code meta}, whose handler and other children are kept, or {@code null}
     * @param ilst the new {@code ilst}
     * @return the box
     */
    private byte[] buildMeta(Mp4Boxes.Box old, byte[] ilst) {
        var out = new ByteWriter(ilst.length + 64);
        if (old == null) {
            out.u32be(0);
            out.bytes(handler());
            out.bytes(ilst);
            return Mp4Boxes.box("meta", out.toByteArray());
        }
        var childrenStart = metaChildren(old);
        out.bytes(moov, old.payload(), childrenStart - old.payload());
        var hasHandler = false;
        for (var child : Mp4Boxes.children(moov, childrenStart, old.end())) {
            switch (child.type()) {
                case "ilst", "free", "skip" -> {
                }
                case "hdlr" -> {
                    hasHandler = true;
                    out.bytes(moov, child.offset(), child.size());
                }
                default -> out.bytes(moov, child.offset(), child.size());
            }
        }
        if (!hasHandler) {
            out.bytes(handler());
        }
        out.bytes(ilst);
        return Mp4Boxes.box("meta", out.toByteArray());
    }

    /**
     * {@return the {@code hdlr} box of an iTunes {@code meta}}
     */
    static byte[] handler() {
        var payload = new byte[25];
        payload[8] = 'm';
        payload[9] = 'd';
        payload[10] = 'i';
        payload[11] = 'r';
        payload[12] = 'a';
        payload[13] = 'p';
        payload[14] = 'p';
        payload[15] = 'l';
        return Mp4Boxes.box("hdlr", payload);
    }

    /**
     * Builds an {@code ilst}.
     *
     * <p>The atoms of {@code forms} are written in their form, widened to the width of
     * {@link #INTEGER_ATOMS} when the value needs it, the other atoms of {@link #INTEGER_ATOMS} as
     * signed integers of their width. A value that does not fit the form of an atom outside
     * {@link #INTEGER_ATOMS}, or that is not an integer for an atom of {@link #INTEGER_ATOMS} the
     * file stores as text, is written as UTF-8 text.
     *
     * @param newTags the tags
     * @param opaque the opaque items to keep, each a complete box
     * @param forms the integer form of other atoms, by atom code
     * @param written receives the lower-case keys written
     * @return the box
     * @throws MediaException.Unsupported if a value of an atom of {@link #INTEGER_ATOMS} the file
     * does not store as text is not an integer its form holds
     */
    static byte[] ilst(MediaTags newTags, List<byte[]> opaque, Map<String, IntegerForm> forms, Set<String> written) {
        var grouped = new LinkedHashMap<String, List<String>>();
        for (var entry : newTags.entries()) {
            grouped.computeIfAbsent(entry.key(), _ -> new ArrayList<>()).add(entry.value());
        }
        var out = new ByteWriter(4096);
        var codes = new HashSet<String>();
        for (var group : grouped.entrySet()) {
            var key = group.getKey();
            var values = group.getValue();
            var standard = StandardTag.fromName(key);
            Optional<String> atom = standard.isPresent() ? standard.get().mp4Atom() : isAtomCode(key) ? Optional.of(key) : Optional.empty();
            if (atom.isPresent()) {
                var code = atom.get();
                var item = new ByteWriter(64);
                switch (code) {
                    case "trkn", "disk" -> {
                        var parts = values.getFirst().split("/", 2);
                        var number = parseShort(parts[0]);
                        var total = parts.length > 1 ? parseShort(parts[1]) : 0;
                        var payload = new byte[code.equals("trkn") ? 8 : 6];
                        payload[2] = (byte) (number >>> 8);
                        payload[3] = (byte) number;
                        payload[4] = (byte) (total >>> 8);
                        payload[5] = (byte) total;
                        dataAtom(item, 0, payload);
                    }
                    case "cpil" -> dataAtom(item, TYPE_INTEGER, new byte[]{(byte) (isTrue(values.getFirst()) ? 1 : 0)});
                    default -> {
                        var known = INTEGER_ATOMS.get(code);
                        var form = forms.get(code);
                        var textual = form != null && form.type() == TYPE_UTF8;
                        if ((form == null || textual) && known != null) {
                            form = new IntegerForm(TYPE_INTEGER, known);
                        }
                        for (var value : values) {
                            var used = form;
                            var integer = used == null ? null : encodeInteger(code, value, used);
                            if (integer == null && known != null && used.width() < known) {
                                used = new IntegerForm(used.type(), known);
                                integer = encodeInteger(code, value, used);
                            }
                            if (integer == null && known != null && !textual) {
                                throw new MediaException.Unsupported("mp4", "the MP4 " + code + " atom holds "
                                        + (used.type() == TYPE_UNSIGNED ? "an unsigned" : "a signed") + " "
                                        + 8 * used.width() + "-bit integer, not \"" + value + "\"");
                            }
                            if (integer != null) {
                                dataAtom(item, used.type(), integer);
                            } else {
                                dataAtom(item, TYPE_UTF8, value.getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
                Mp4Boxes.box(out, code, item.toByteArray());
                codes.add(code);
            } else {
                var name = standard.isPresent() ? standard.get().mp4FreeformName() : key;
                var item = new ByteWriter(64);
                Mp4Boxes.box(item, "mean", concat(new byte[4], APPLE_MEAN.getBytes(StandardCharsets.UTF_8)));
                Mp4Boxes.box(item, "name", concat(new byte[4], name.getBytes(StandardCharsets.UTF_8)));
                for (var value : values) {
                    dataAtom(item, TYPE_UTF8, value.getBytes(StandardCharsets.UTF_8));
                }
                Mp4Boxes.box(out, "----", item.toByteArray());
            }
            written.add(key.toLowerCase(Locale.ROOT));
        }
        if (!newTags.pictures().isEmpty()) {
            var item = new ByteWriter(4096);
            for (var picture : newTags.pictures()) {
                var format = picture.format();
                var type = format.isEmpty() ? 0 : switch (format.get()) {
                    case JPEG -> TYPE_JPEG;
                    case PNG -> TYPE_PNG;
                    case BMP -> TYPE_BMP;
                    default -> 0;
                };
                dataAtom(item, type, picture.data());
            }
            Mp4Boxes.box(out, "covr", item.toByteArray());
            codes.add("covr");
        }
        for (var item : opaque) {
            var code = Mp4Boxes.type(item, 4);
            if (!codes.contains(code) || code.equals("----")) {
                out.bytes(item);
            }
        }
        return Mp4Boxes.box("ilst", out.toByteArray());
    }

    /**
     * Returns whether a free key names an {@code ilst} atom rather than a free-form item.
     *
     * @param key a free-form item key
     * @return {@code true} for four characters starting with a lower-case letter or the copyright sign
     */
    private static boolean isAtomCode(String key) {
        if (key.length() != 4) {
            return false;
        }
        var first = key.charAt(0);
        if (first != COPYRIGHT_SIGN && (first < 'a' || first > 'z')) {
            return false;
        }
        for (var i = 0; i < 4; i++) {
            if (key.charAt(i) > 0xFF) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a number for a {@code trkn} or {@code disk} item.
     *
     * @param text the decimal text to parse
     * @return the number clamped to 16 bits, or {@code 0} when not a number
     */
    private static int parseShort(String text) {
        try {
            return Math.max(0, Math.min(0xFFFF, Integer.parseInt(text.trim())));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * Encodes the value of an integer atom.
     *
     * @param code the atom code
     * @param value the decimal text, or for the flags of {@link #FLAG_ATOMS} also {@code true},
     * {@code yes}, {@code false} or {@code no}
     * @param form the encoding
     * @return the big-endian bytes, or {@code null} when the value is not an integer the form holds
     */
    private static byte[] encodeInteger(String code, String value, IntegerForm form) {
        var text = value.trim().toLowerCase(Locale.ROOT);
        long number;
        if (FLAG_ATOMS.contains(code) && (text.equals("true") || text.equals("yes"))) {
            number = 1;
        } else if (FLAG_ATOMS.contains(code) && (text.equals("false") || text.equals("no"))) {
            number = 0;
        } else {
            try {
                number = form.type() == TYPE_UNSIGNED ? Long.parseUnsignedLong(text) : Long.parseLong(text);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        var bits = 8 * form.width();
        if (bits < 64) {
            var fits = form.type() == TYPE_UNSIGNED ? number >>> bits == 0
                    : number >= -1L << (bits - 1) && number <= ~(-1L << (bits - 1));
            if (!fits) {
                return null;
            }
        }
        var bytes = new byte[form.width()];
        for (var index = bytes.length - 1; index >= 0; index--) {
            bytes[index] = (byte) number;
            number >>>= 8;
        }
        return bytes;
    }

    /**
     * Returns whether a flag value is true.
     *
     * @param text the flag text, such as {@code "1"} or {@code "true"}
     * @return {@code true} for {@code "1"}, {@code "true"} and {@code "yes"}
     */
    private static boolean isTrue(String text) {
        var value = text.trim().toLowerCase(Locale.ROOT);
        return value.equals("1") || value.equals("true") || value.equals("yes");
    }

    /**
     * Writes a {@code data} atom.
     *
     * @param out the destination
     * @param type the data type
     * @param payload the value
     */
    private static void dataAtom(ByteWriter out, int type, byte[] payload) {
        var body = new byte[8 + payload.length];
        ByteArrays.put32be(body, 0, type);
        System.arraycopy(payload, 0, body, 8, payload.length);
        Mp4Boxes.box(out, "data", body);
    }

    /**
     * Concatenates two arrays.
     *
     * @param first the bytes that come first
     * @param second the bytes appended after {@code first}
     * @return the concatenation
     */
    private static byte[] concat(byte[] first, byte[] second) {
        var result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /**
     * Builds a Nero chapter list.
     *
     * @param chapters the chapters, at most {@value Mp4ChapterTrack#MAX_NERO_CHAPTERS} of which are kept
     * @return the box
     */
    static byte[] chapterBox(List<Chapter> chapters) {
        var out = new ByteWriter(16 + chapters.size() * 32);
        out.u8(1);
        out.u24be(0);
        out.u32be(0);
        var count = Math.min(Mp4ChapterTrack.MAX_NERO_CHAPTERS, chapters.size());
        out.u8(count);
        for (var index = 0; index < count; index++) {
            var chapter = chapters.get(index);
            out.u64be(chapter.startMicros() * 10);
            var title = truncate(chapter.title().orElse(""));
            out.u8(title.length);
            out.bytes(title);
        }
        return Mp4Boxes.box("chpl", out.toByteArray());
    }

    /**
     * Encodes a chapter title in UTF-8, cut at a character boundary to fit 255 bytes.
     *
     * @param title the chapter title to encode
     * @return the bytes
     */
    private static byte[] truncate(String title) {
        var bytes = title.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 255) {
            return bytes;
        }
        var end = 255;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return Arrays.copyOf(bytes, end);
    }

    /**
     * Copies the boxes after the old {@code moov}, correcting the absolute offsets of movie
     * fragments and of the fragment random access index.
     */
    private static final class Shift extends EditPlan.Transform {
        /**
         * The offset of the first copied byte.
         */
        private final long start;

        /**
         * The number of copied bytes.
         */
        private final long length;

        /**
         * The shift of the copied bytes.
         */
        private final long delta;

        /**
         * The resource bounds.
         */
        private final MediaLimits limits;

        /**
         * The splices of the copied range, in file order.
         */
        private final List<Splice> splices;

        /**
         * Constructs the transformation.
         *
         * @param start the offset of the first copied byte
         * @param length the number of copied bytes
         * @param delta the shift
         * @param limits the resource bounds
         * @param splices the ranges written as other bytes: whole top-level boxes, which may change size, or ranges
         * inside a box, which keep theirs; those outside the copied range are ignored
         */
        Shift(long start, long length, long delta, MediaLimits limits, List<Splice> splices) {
            this.start = start;
            this.length = length;
            this.delta = delta;
            this.limits = limits;
            var inside = new ArrayList<Splice>();
            for (var splice : sorted(splices)) {
                if (splice.offset() >= start && splice.offset() + splice.length() <= start + length) {
                    inside.add(splice);
                }
            }
            this.splices = List.copyOf(inside);
        }

        @Override
        public long length() {
            var result = length;
            for (var splice : splices) {
                result += splice.bytes().length - splice.length();
            }
            return result;
        }

        @Override
        public void writeTo(MediaSource source, MediaSink sink, byte[] buffer) {
            var position = start;
            var end = start + length;
            while (position < end) {
                if (end - position < 8) {
                    copySpliced(source, sink, position, end - position, buffer);
                    return;
                }
                var header = TagIo.read(source, position, Math.min(16, end - position), limits);
                long size = ByteArrays.u32be(header, 0);
                if (size == 1 && header.length == 16) {
                    size = ByteArrays.i64be(header, 8);
                } else if (size == 0) {
                    size = end - position;
                }
                if (size < 8 || size > end - position) {
                    copySpliced(source, sink, position, end - position, buffer);
                    return;
                }
                var type = Mp4Boxes.type(header, 4);
                var whole = spliceAt(position, size);
                if (whole != null) {
                    sink.write(whole.bytes(), 0, whole.bytes().length);
                } else if ((type.equals("moof") || type.equals("mfra")) && size <= MAX_FRAGMENT) {
                    var box = TagIo.read(source, position, size, limits);
                    if (type.equals("moof")) {
                        patchFragment(box, 8, box.length, 0, delta);
                    } else {
                        patchIndex(box);
                    }
                    for (var splice : splices) {
                        if (splice.offset() >= position && splice.offset() + splice.length() <= position + size) {
                            System.arraycopy(splice.bytes(), 0, box, (int) (splice.offset() - position),
                                    splice.bytes().length);
                        }
                    }
                    sink.write(box, 0, box.length);
                } else {
                    copySpliced(source, sink, position, size, buffer);
                }
                position += size;
            }
        }

        /**
         * {@return the splice that replaces a whole box, or {@code null}}
         *
         * @param position the offset of the box
         * @param size the size of the box
         */
        private Splice spliceAt(long position, long size) {
            for (var splice : splices) {
                if (splice.offset() == position && splice.length() == size) {
                    return splice;
                }
            }
            return null;
        }

        /**
         * Copies a range, writing the splices that lie inside it in place of their bytes.
         *
         * @param source the file
         * @param sink the destination
         * @param from the offset of the first copied byte
         * @param count the number of bytes
         * @param buffer the copy buffer
         */
        private void copySpliced(MediaSource source, MediaSink sink, long from, long count, byte[] buffer) {
            var position = from;
            for (var splice : splices) {
                if (splice.offset() < position || splice.offset() + splice.length() > from + count) {
                    continue;
                }
                EditPlan.copy(source, sink, position, splice.offset() - position, buffer);
                sink.write(splice.bytes(), 0, splice.bytes().length);
                position = splice.offset() + splice.length();
            }
            EditPlan.copy(source, sink, position, from + count - position, buffer);
        }

        /**
         * Shifts the fragment offsets of the {@code tfra} boxes of a random access index.
         *
         * @param box the {@code mfra} bytes
         */
        private void patchIndex(byte[] box) {
            for (var child : Mp4Boxes.children(box, 8, box.length)) {
                if (!child.type().equals("tfra") || child.size() < 24) {
                    continue;
                }
                var position = child.payload();
                var version = box[position] & 0xFF;
                var sizes = (int) ByteArrays.u32be(box, position + 8);
                var count = ByteArrays.u32be(box, position + 12);
                position += 16;
                var timeSize = version == 1 ? 8 : 4;
                var entry = 2 * timeSize + ((sizes >>> 4) & 3) + 1 + ((sizes >>> 2) & 3) + 1 + (sizes & 3) + 1;
                for (var i = 0L; i < count && child.end() - position >= entry; i++) {
                    var at = position + timeSize;
                    if (version == 1) {
                        ByteArrays.put64be(box, at, ByteArrays.i64be(box, at) + delta);
                    } else {
                        ByteArrays.put32be(box, at, ByteArrays.u32be(box, at) + delta);
                    }
                    position += entry;
                }
            }
        }
    }
}
