package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Parses the structure of a HEIF file (ISO/IEC 23008-12, the container of AVIF and HEIC): the
 * {@code ftyp} brands, the items of the file-level {@code meta} box and the location of the
 * {@code moov} box of image sequences.
 *
 * <p>{@link #parse(MediaSource, MediaLimits)} walks the top-level boxes with positional reads,
 * reads the {@code meta} box into memory and resolves its {@code hdlr} (which must be
 * {@code pict}), {@code pitm}, {@code iinf}, {@code iloc} (versions 0 to 2, every construction
 * method), {@code iprp} ({@code ipco} and every {@code ipma}), {@code iref} (versions 0 and 1)
 * and {@code idat} children into {@link HeifItem}s, its {@code grpl} child into
 * {@link EntityGroup}s and the {@code dref} of its {@code dinf} child into the data references of
 * the items stored in other files. It stops as soon as the boxes the brands
 * require are seen: the {@code meta} box for the {@code avif}, {@code mif1} and HEIC brands and
 * the {@code moov} box for the sequence brands ({@code avis}, {@code msf1}, {@code heis}). Item
 * data is read on demand with {@link #readItem(MediaSource, HeifItem, MediaLimits)}.
 *
 * <p>Every size is checked before it is used: the {@code meta} box and every item read against
 * {@link MediaLimits#maxBytes()}, every child box and field against its parent, and the number
 * of top-level boxes against {@value #MAX_TOP_LEVEL_BOXES}. Structural errors raise
 * {@link MediaException.Malformed}; the parser never follows references, so cyclic
 * {@code iref} graphs cannot make it loop, and {@link #readItem} follows the
 * {@code iloc} references of construction method {@code 2} only {@value #MAX_ITEM_DEPTH} deep.
 * The rules follow ISO/IEC 14496-12 clause 8.11, ISO/IEC 23008-12 and MIAF (ISO/IEC 23000-22).
 * Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows libavif's {@code src/read.c} (BSD 2-clause) for the
 * container rules it enforces: the uniqueness of the {@code meta} children, increasing {@code ipma}
 * item identifiers, the properties that must or must not be marked essential, a single {@code dimg}
 * reference box per item, and the {@code ftyp} ordering. It is more lenient than libavif in two
 * places: {@code infe} entries of versions 0 and 1 are skipped rather than rejected, and strings
 * without a terminator end at their box.
 */
public final class HeifFile {
    /**
     * The content type of {@code mime} items that hold an XMP packet.
     */
    public static final String XMP_TYPE = "application/rdf+xml";

    /**
     * The most top-level boxes walked before the parse gives up.
     */
    public static final int MAX_TOP_LEVEL_BOXES = 1 << 16;

    /**
     * The most nested items {@link #readItem} follows through construction method {@code 2}.
     */
    public static final int MAX_ITEM_DEPTH = 8;

    /**
     * The largest {@code ftyp} box accepted.
     */
    private static final int MAX_FTYP = 1 << 12;

    /**
     * The property types that must be marked essential when associated (AVIF clause 2.3.2.1.1,
     * HEIF clause 6.5.11.1, MIAF clause 7.3.9).
     */
    private static final Set<String> ESSENTIAL_TYPES = Set.of("a1op", "lsel", "clap", "irot", "imir");

    /**
     * The brands whose files keep their images in a file-level {@code meta} box.
     */
    private static final Set<String> ITEM_BRANDS = Set.of("avif", "avio", "mif1", "mif2", "heic", "heix",
            "heim", "heis", "hevc", "hevx", "miaf", "avci", "jpeg", "j2ki");

    /**
     * The brands whose files hold an image sequence in a {@code moov} box.
     */
    private static final Set<String> SEQUENCE_BRANDS = Set.of("avis", "msf1", "hevs", "hevm", "avcs", "jpgs", "j2is");

    /**
     * The major brand.
     */
    private final String majorBrand;

    /**
     * The major brand followed by the compatible brands.
     */
    private final List<String> brands;

    /**
     * The primary item identifier, or {@code 0} when the file declares none.
     */
    private final int primaryItemId;

    /**
     * The items by identifier, in {@code iinf} order.
     */
    private final Map<Integer, HeifItem> items;

    /**
     * The payload of the {@code idat} box, empty when absent.
     */
    private final byte[] idat;

    /**
     * Whether the file has a file-level {@code meta} box.
     */
    private final boolean hasMeta;

    /**
     * The entity groups of the {@code grpl} box, in file order.
     */
    private final List<EntityGroup> groups;

    /**
     * The file offset of the {@code moov} box payload, or {@code -1} when absent.
     */
    private final long movieOffset;

    /**
     * The size of the {@code moov} box payload.
     */
    private final long movieSize;

    /**
     * The {@code dinf} box of the {@code meta} box, never moved, or {@code null} when it has none; its
     * {@code dref} box is parsed when an item stored elsewhere is read, so that a damaged one fails only those.
     */
    private final BoxCursor dataInformation;

    /**
     * The resolver of the files those entries name.
     */
    private final DataReferenceResolver resolver;

    /**
     * Constructs the parsed file.
     *
     * @param majorBrand the major brand
     * @param brands the major brand followed by the compatible brands
     * @param primaryItemId the primary item identifier, or {@code 0}
     * @param items the items by identifier
     * @param idat the {@code idat} payload
     * @param groups the entity groups
     * @param hasMeta whether a {@code meta} box was found
     * @param movieOffset the offset of the {@code moov} payload, or {@code -1}
     * @param movieSize the size of the {@code moov} payload
     * @param dataInformation the {@code dinf} box of the {@code meta} box, or {@code null}
     * @param resolver the resolver of the files those entries name
     */
    private HeifFile(String majorBrand, List<String> brands, int primaryItemId, Map<Integer, HeifItem> items,
                     byte[] idat, List<EntityGroup> groups, boolean hasMeta, long movieOffset, long movieSize,
                     BoxCursor dataInformation, DataReferenceResolver resolver) {
        this.majorBrand = majorBrand;
        this.brands = brands;
        this.primaryItemId = primaryItemId;
        this.items = items;
        this.idat = idat;
        this.groups = groups;
        this.hasMeta = hasMeta;
        this.movieOffset = movieOffset;
        this.movieSize = movieSize;
        this.dataInformation = dataInformation;
        this.resolver = resolver;
    }

    /**
     * Parses the structure of a HEIF file, enforcing every container rule.
     *
     * @param source the file
     * @param limits the bounds of the {@code meta} box
     * @return the parsed file
     * @throws MediaException.Malformed if the file is not a valid HEIF file
     * @throws MediaException.LimitExceeded if the {@code meta} box exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #parse(MediaSource, MediaLimits, boolean)
     */
    public static HeifFile parse(MediaSource source, MediaLimits limits) {
        return parse(source, limits, true);
    }

    /**
     * Parses the structure of a HEIF file.
     *
     * <p>A strict parse enforces every container rule this class lists. A lenient parse accepts
     * two deviations that HEIF writers produce and that do not make a file ambiguous: transformative
     * properties ({@code clap}, {@code irot}, {@code imir}) and AV1 selectors that are not marked
     * essential (and an essential {@code a1lx}), and a {@code dimg} reference that names the same
     * input twice, which an image overlay may legitimately do. A lenient parse also never rejects a
     * file for its {@code grpl} box, which no image needs to be shown: it reads the first such box
     * only, accepts repeated group identifiers and entities, and keeps the groups before the first
     * truncated one.
     *
     * @param source the file
     * @param limits the bounds of the {@code meta} box
     * @param strict whether to enforce the rules on essential properties, repeated inputs and the
     * entity groups
     * @return the parsed file
     * @throws MediaException.Malformed if the file is not a valid HEIF file
     * @throws MediaException.LimitExceeded if the {@code meta} box exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static HeifFile parse(MediaSource source, MediaLimits limits, boolean strict) {
        return parse(source, limits, strict, DataReferenceResolver.none());
    }

    /**
     * Parses the structure of a HEIF file whose items may be stored in other files, which the data reference box
     * of its {@code meta} box names.
     *
     * <p>The parse is the one of {@link #parse(MediaSource, MediaLimits, boolean)}; {@link #readItem} asks the
     * resolver for the file of an item stored elsewhere and closes it once the item is read.
     *
     * @param source the file
     * @param limits the bounds of the {@code meta} box
     * @param strict whether to enforce the rules on essential properties, repeated inputs and the
     * entity groups
     * @param resolver the resolver of the files the data references name
     * @return the parsed file
     * @throws MediaException.Malformed if the file is not a valid HEIF file
     * @throws MediaException.LimitExceeded if the {@code meta} box exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static HeifFile parse(MediaSource source, MediaLimits limits, boolean strict, DataReferenceResolver resolver) {
        var header = new byte[16];
        var size = source.size();
        long position = 0;
        String majorBrand = null;
        List<String> brands = List.of();
        byte[] meta = null;
        long movieOffset = -1;
        long movieSize = 0;
        var needsMeta = false;
        var needsMovie = false;
        for (var count = 0; ; count++) {
            if (count == MAX_TOP_LEVEL_BOXES) {
                throw new MediaException.Malformed("heif: more than " + MAX_TOP_LEVEL_BOXES + " top-level boxes");
            }
            var read = readHeader(source, position, header);
            if (read <= 0) {
                break;
            }
            if (read < 8) {
                if (majorBrand == null) {
                    throw new MediaException.Malformed("heif: truncated box header at offset " + position);
                }
                break;
            }
            long boxSize = ByteArrays.u32be(header, 0);
            var type = FourCc.toString(ByteArrays.i32be(header, 4));
            var headerSize = 8;
            if (boxSize == 1) {
                if (read < 16) {
                    throw new MediaException.Malformed("heif: truncated box header at offset " + position);
                }
                boxSize = (ByteArrays.u32be(header, 8) << 32) | ByteArrays.u32be(header, 12);
                headerSize = 16;
            } else if (boxSize == 0) {
                if (size < 0) {
                    throw new MediaException.Unsupported("heif", "a box that extends to the end of a source of unknown size");
                }
                boxSize = size - position;
            }
            if (boxSize < headerSize) {
                throw new MediaException.Malformed("heif: box '" + type + "' at offset " + position + " has size " + boxSize);
            }
            var payloadOffset = position + headerSize;
            var payloadSize = boxSize - headerSize;
            if (majorBrand == null) {
                if (!type.equals("ftyp")) {
                    throw new MediaException.Malformed("heif: the file does not start with a ftyp box");
                }
                if (payloadSize < 8 || payloadSize > MAX_FTYP) {
                    throw new MediaException.Malformed("heif: ftyp box of size " + boxSize);
                }
                var ftyp = new byte[(int) payloadSize];
                source.readFully(payloadOffset, ftyp, 0, ftyp.length);
                var parsed = new ArrayList<String>();
                majorBrand = new String(ftyp, 0, 4, StandardCharsets.ISO_8859_1);
                parsed.add(majorBrand);
                for (var offset = 8; offset + 4 <= ftyp.length; offset += 4) {
                    parsed.add(new String(ftyp, offset, 4, StandardCharsets.ISO_8859_1));
                }
                brands = List.copyOf(parsed);
                for (var brand : brands) {
                    needsMeta |= ITEM_BRANDS.contains(brand);
                    needsMovie |= SEQUENCE_BRANDS.contains(brand);
                }
            } else if (type.equals("ftyp")) {
                throw new MediaException.Malformed("heif: duplicate ftyp box");
            } else if (type.equals("meta")) {
                if (meta != null) {
                    throw new MediaException.Malformed("heif: duplicate file-level meta box");
                }
                meta = readPayload(source, size, payloadOffset, payloadSize, limits);
            } else if (type.equals("moov")) {
                if (movieOffset >= 0) {
                    throw new MediaException.Malformed("heif: duplicate moov box");
                }
                movieOffset = payloadOffset;
                movieSize = payloadSize;
            }
            if ((!needsMeta || meta != null) && (!needsMovie || movieOffset >= 0) && (meta != null || movieOffset >= 0)) {
                break;
            }
            if (Long.MAX_VALUE - position < boxSize) {
                throw new MediaException.Malformed("heif: box '" + type + "' overflows the file offset");
            }
            position += boxSize;
        }
        if (majorBrand == null) {
            throw new MediaException.Malformed("heif: empty input");
        }
        if (meta == null) {
            return new HeifFile(majorBrand, brands, 0, Map.of(), new byte[0], List.of(), false, movieOffset,
                    movieSize, null, resolver);
        }
        return parseMeta(majorBrand, brands, meta, movieOffset, movieSize, strict, resolver);
    }

    /**
     * Reads up to sixteen bytes of a box header.
     *
     * @param source the file
     * @param position the offset of the header
     * @param header the destination of sixteen bytes
     * @return the number of bytes read, {@code -1} at the end of the source
     */
    private static int readHeader(MediaSource source, long position, byte[] header) {
        var done = 0;
        while (done < header.length) {
            var read = source.read(position + done, header, done, header.length - done);
            if (read <= 0) {
                return done == 0 ? -1 : done;
            }
            done += read;
        }
        return done;
    }

    /**
     * Reads the payload of a top-level box.
     *
     * <p>The payload must end within the source. When the size of the source is known, a payload
     * that runs past its end is rejected before any memory is reserved for it; when it is not, the
     * payload is read in chunks that grow with the bytes actually delivered, so a declared size
     * never reserves more memory than twice the data the source holds.
     *
     * @param source the file
     * @param size the size of the source, or a negative value when unknown
     * @param offset the offset of the payload
     * @param length the declared size of the payload
     * @param limits the bound of the allocation
     * @return the payload
     * @throws MediaException.LimitExceeded if {@code length} exceeds the allocation limit
     * @throws MediaException.Malformed if the source ends before the payload does
     */
    private static byte[] readPayload(MediaSource source, long size, long offset, long length, MediaLimits limits) {
        LimitChecks.checkAllocation(limits, length);
        if (size >= 0) {
            if (length > size - offset) {
                throw new MediaException.Malformed("heif: the meta box at offset " + offset + " declares "
                        + length + " bytes but the file ends after " + Math.max(0, size - offset));
            }
            var payload = new byte[(int) length];
            source.readFully(offset, payload, 0, payload.length);
            return payload;
        }
        var payload = new byte[(int) Math.min(length, 1 << 16)];
        var done = 0;
        while (done < length) {
            if (done == payload.length) {
                payload = Arrays.copyOf(payload, (int) Math.min(length, 2L * payload.length));
            }
            var read = source.read(offset + done, payload, done, payload.length - done);
            if (read <= 0) {
                throw new MediaException.Malformed("heif: the meta box at offset " + offset + " declares "
                        + length + " bytes but the file ends after " + done);
            }
            done += read;
        }
        return payload;
    }

    /**
     * Parses the file-level {@code meta} box.
     *
     * @param majorBrand the major brand
     * @param brands the major brand followed by the compatible brands
     * @param meta the {@code meta} payload
     * @param movieOffset the offset of the {@code moov} payload, or {@code -1}
     * @param movieSize the size of the {@code moov} payload
     * @param strict whether to enforce the rules on essential properties and repeated inputs
     * @param resolver the resolver of the files the data references name
     * @return the parsed file
     */
    private static HeifFile parseMeta(String majorBrand, List<String> brands, byte[] meta, long movieOffset,
                                      long movieSize, boolean strict, DataReferenceResolver resolver) {
        var box = BoxCursor.strict(meta, FourCc.of("meta"));
        if ((box.fullBoxHeader() >>> 24) != 0) {
            throw new MediaException.Malformed("heif: unsupported meta box version");
        }
        if (!box.hasBox()) {
            throw new MediaException.Malformed("heif: the meta box is empty");
        }
        var hdlr = box.nextBox();
        if (!FourCc.toString(hdlr.type()).equals("hdlr")) {
            throw new MediaException.Malformed("heif: the meta box does not start with a hdlr box");
        }
        hdlr.fullBoxHeader();
        hdlr.u32();
        var handler = FourCc.toString(hdlr.fourCc());
        if (!handler.equals("pict")) {
            throw new MediaException.Malformed("heif: the meta handler is '" + handler + "', not 'pict'");
        }
        BoxCursor pitm = null;
        BoxCursor iinf = null;
        BoxCursor iloc = null;
        BoxCursor iprp = null;
        BoxCursor iref = null;
        BoxCursor idat = null;
        BoxCursor grpl = null;
        BoxCursor dinf = null;
        while (box.hasBox()) {
            var child = box.nextBox();
            var type = FourCc.toString(child.type());
            switch (type) {
                case "hdlr" -> throw new MediaException.Malformed("heif: duplicate hdlr box");
                case "pitm" -> pitm = unique(pitm, child);
                case "iinf" -> iinf = unique(iinf, child);
                case "iloc" -> iloc = unique(iloc, child);
                case "iprp" -> iprp = unique(iprp, child);
                case "iref" -> iref = unique(iref, child);
                case "idat" -> idat = unique(idat, child);
                case "grpl" -> grpl = strict ? unique(grpl, child) : grpl == null ? child : grpl;
                case "dinf" -> dinf = dinf == null ? child : dinf;
                default -> {
                }
            }
        }
        var items = new LinkedHashMap<Integer, HeifItem>();
        if (iinf != null) {
            parseItemInfo(iinf, items);
        }
        if (iloc != null) {
            parseItemLocations(iloc, items);
        }
        if (iprp != null) {
            parseItemProperties(iprp, items, strict);
        }
        if (iref != null) {
            parseItemReferences(iref, items, strict);
        }
        var primary = 0;
        if (pitm != null) {
            var version = pitm.fullBoxHeader() >>> 24;
            primary = version == 0 ? pitm.u16() : (int) pitm.u32();
        }
        return new HeifFile(majorBrand, brands, primary, Collections.unmodifiableMap(items),
                idat == null ? new byte[0] : idat.rest(), grpl == null ? List.of() : parseGroups(grpl, strict), true,
                movieOffset, movieSize, dinf, resolver);
    }

    /**
     * Parses the groups list box (ISO/IEC 14496-12 clause 8.18.3): one {@code EntityToGroupBox} per
     * child, whose group-specific fields after the entity identifiers are skipped.
     *
     * <p>A lenient parse keeps the groups before the first truncated one and accepts repeated group
     * identifiers and entities.
     *
     * @param grpl the cursor over the {@code grpl} payload
     * @param strict whether a defect rejects the file
     * @return the groups in file order
     * @throws MediaException.Malformed in a strict parse, if a group is truncated, declares more
     * entities than its box holds, names an entity twice or reuses the identifier of another group
     */
    private static List<EntityGroup> parseGroups(BoxCursor grpl, boolean strict) {
        var groups = new ArrayList<EntityGroup>();
        try {
            parseGroups(grpl, strict, groups);
        } catch (MediaException.Malformed exception) {
            if (strict) {
                throw exception;
            }
        }
        return List.copyOf(groups);
    }

    /**
     * Parses the groups of a groups list box into a list, which holds the groups parsed before a
     * failure.
     *
     * @param grpl the cursor over the {@code grpl} payload
     * @param strict whether repeated group identifiers and entities are rejected
     * @param groups receives the groups in file order
     * @throws MediaException.Malformed if a group is truncated or declares more entities than its
     * box holds, or, in a strict parse, names an entity twice or reuses the identifier of another
     * group
     */
    private static void parseGroups(BoxCursor grpl, boolean strict, List<EntityGroup> groups) {
        var groupIds = new HashSet<Long>();
        while (grpl.hasBox()) {
            var group = grpl.nextBox();
            var type = FourCc.toString(group.type());
            group.fullBoxHeader();
            var groupId = group.u32();
            var count = group.u32();
            if (count > group.remaining() / 4) {
                throw new MediaException.Malformed("heif: entity group '" + type + "' declares " + count
                        + " entities in " + group.remaining() + " bytes");
            }
            if (!groupIds.add(groupId) && strict) {
                throw new MediaException.Malformed("heif: two entity groups have the identifier " + groupId);
            }
            var entities = new int[(int) count];
            var distinct = new HashSet<Long>();
            for (var i = 0; i < entities.length; i++) {
                var entity = group.u32();
                if (!distinct.add(entity) && strict) {
                    throw new MediaException.Malformed("heif: entity group " + groupId + " names entity " + entity
                            + " twice");
                }
                entities[i] = entity > Integer.MAX_VALUE ? -1 : (int) entity;
            }
            groups.add(new EntityGroup(type, groupId, entities));
        }
    }

    /**
     * Describes one entity group of the {@code grpl} box.
     *
     * @param type the grouping type, for example {@code "altr"} for alternatives
     * @param id the group identifier
     * @param entities the item or track identifiers of the group in box order, {@code -1} for an
     * identifier beyond the largest {@code int}
     */
    public record EntityGroup(String type, long id, int[] entities) {
    }

    /**
     * Returns a child box that may occur at most once.
     *
     * @param previous the box already seen, or {@code null}
     * @param child the box just read
     * @return {@code child}
     * @throws MediaException.Malformed if {@code previous} is not {@code null}
     */
    private static BoxCursor unique(BoxCursor previous, BoxCursor child) {
        if (previous != null) {
            throw new MediaException.Malformed("heif: duplicate " + FourCc.toString(child.type()) + " box in meta");
        }
        return child;
    }

    /**
     * Reads an item identifier of 16 or 32 bits, rejecting {@code 0}.
     *
     * @param box the cursor
     * @param wide whether the identifier has 32 bits
     * @return the identifier
     */
    private static int itemId(BoxCursor box, boolean wide) {
        var id = wide ? box.u32() : box.u16();
        if (id == 0 || id > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("heif: invalid item identifier " + id + " in box '"
                    + FourCc.toString(box.type()) + "'");
        }
        return (int) id;
    }

    /**
     * Parses the item information box and creates the items.
     *
     * @param iinf the cursor over the {@code iinf} payload
     * @param items receives the items
     */
    private static void parseItemInfo(BoxCursor iinf, Map<Integer, HeifItem> items) {
        var version = iinf.fullBoxHeader() >>> 24;
        long count = switch (version) {
            case 0 -> iinf.u16();
            case 1 -> iinf.u32();
            default -> throw new MediaException.Malformed("heif: unsupported iinf version " + version);
        };
        for (long i = 0; i < count; i++) {
            var infe = iinf.nextBox();
            if (!FourCc.toString(infe.type()).equals("infe")) {
                throw new MediaException.Malformed("heif: the iinf box holds a '" + FourCc.toString(infe.type()) + "' box");
            }
            var header = infe.fullBoxHeader();
            var infeVersion = header >>> 24;
            if (infeVersion < 2) {
                continue;
            }
            if (infeVersion > 3) {
                throw new MediaException.Malformed("heif: unsupported infe version " + infeVersion);
            }
            var id = itemId(infe, infeVersion == 3);
            infe.u16();
            var type = FourCc.toString(infe.fourCc());
            infe.string();
            var contentType = type.equals("mime") ? infe.string() : "";
            var contentEncoding = type.equals("mime") ? infe.string() : "";
            if (items.containsKey(id)) {
                throw new MediaException.Malformed("heif: duplicate item " + id);
            }
            // flag 1 of the infe box marks a hidden image item (ISO/IEC 23008-12)
            items.put(id, new HeifItem(id, type, contentType, contentEncoding, (header & 1) != 0));
        }
    }

    /**
     * Parses the item location box.
     *
     * @param iloc the cursor over the {@code iloc} payload
     * @param items the items to locate
     */
    private static void parseItemLocations(BoxCursor iloc, Map<Integer, HeifItem> items) {
        var version = iloc.fullBoxHeader() >>> 24;
        if (version > 2) {
            throw new MediaException.Malformed("heif: unsupported iloc version " + version);
        }
        var sizes = iloc.u16();
        var offsetSize = sizes >> 12;
        var lengthSize = (sizes >> 8) & 15;
        var baseOffsetSize = (sizes >> 4) & 15;
        var indexSize = version == 0 ? 0 : sizes & 15;
        for (var size : new int[]{offsetSize, lengthSize, baseOffsetSize, indexSize}) {
            if (size != 0 && size != 4 && size != 8) {
                throw new MediaException.Malformed("heif: invalid iloc field size " + size);
            }
        }
        long count = version < 2 ? iloc.u16() : iloc.u32();
        for (long i = 0; i < count; i++) {
            var id = itemId(iloc, version == 2);
            var method = 0;
            if (version >= 1) {
                var field = iloc.u16();
                if ((field >> 4) != 0) {
                    throw new MediaException.Malformed("heif: non-zero reserved bits in iloc");
                }
                method = field & 15;
                if (method > 2) {
                    throw new MediaException.Malformed("heif: unsupported iloc construction method " + method);
                }
            }
            var dataReference = iloc.u16();
            var baseOffset = iloc.uN(baseOffsetSize);
            var extents = iloc.u16();
            var offsets = new long[extents];
            var lengths = new long[extents];
            var references = new int[extents];
            for (var e = 0; e < extents; e++) {
                var index = indexSize == 0 ? 1 : iloc.uN(indexSize);
                var offset = iloc.uN(offsetSize);
                var length = iloc.uN(lengthSize);
                if (baseOffset < 0 || offset < 0 || length < 0 || offset > Long.MAX_VALUE - baseOffset) {
                    throw new MediaException.Malformed("heif: item " + id + " has an extent beyond 2^63");
                }
                offsets[e] = baseOffset + offset;
                lengths[e] = length;
                references[e] = (int) Math.min(index, Integer.MAX_VALUE);
            }
            var item = items.get(id);
            if (item == null) {
                continue;
            }
            if (item.located) {
                throw new MediaException.Malformed("heif: item " + id + " is located twice");
            }
            item.located = true;
            item.constructionMethod = method;
            item.dataReferenceIndex = dataReference;
            item.extentOffsets = offsets;
            item.extentLengths = lengths;
            item.extentReferences = references;
        }
    }

    /**
     * Parses the item properties box: the property container and every association box.
     *
     * @param iprp the cursor over the {@code iprp} payload
     * @param items the items to associate properties with
     * @param strict whether to enforce the rules on essential properties
     */
    private static void parseItemProperties(BoxCursor iprp, Map<Integer, HeifItem> items, boolean strict) {
        if (!iprp.hasBox()) {
            throw new MediaException.Malformed("heif: the iprp box is empty");
        }
        var ipco = iprp.nextBox();
        if (!FourCc.toString(ipco.type()).equals("ipco")) {
            throw new MediaException.Malformed("heif: the iprp box does not start with an ipco box");
        }
        var properties = new ArrayList<HeifProperty>();
        while (ipco.hasBox()) {
            properties.add(PropertyParser.parse(ipco.nextBox()));
        }
        var seenVersions = new HashSet<Integer>();
        while (iprp.hasBox()) {
            var ipma = iprp.nextBox();
            if (!FourCc.toString(ipma.type()).equals("ipma")) {
                throw new MediaException.Malformed("heif: the iprp box holds a '" + FourCc.toString(ipma.type()) + "' box");
            }
            var versionAndFlags = ipma.fullBoxHeader();
            if (!seenVersions.add(versionAndFlags)) {
                throw new MediaException.Malformed("heif: two ipma boxes share a version and flags");
            }
            var version = versionAndFlags >>> 24;
            var wideIndex = (versionAndFlags & 1) != 0;
            var count = ipma.u32();
            var previous = 0L;
            for (long i = 0; i < count; i++) {
                var id = itemId(ipma, version >= 1);
                if (id <= previous) {
                    throw new MediaException.Malformed("heif: ipma item identifiers are not increasing");
                }
                previous = id;
                var item = items.get(id);
                if (item != null) {
                    if (item.associated) {
                        throw new MediaException.Malformed("heif: item " + id + " has two ipma entries");
                    }
                    item.associated = true;
                }
                var associations = ipma.u8();
                for (var a = 0; a < associations; a++) {
                    var value = wideIndex ? ipma.u16() : ipma.u8();
                    var essential = (value & (wideIndex ? 0x8000 : 0x80)) != 0;
                    var index = value & (wideIndex ? 0x7FFF : 0x7F);
                    if (index == 0) {
                        if (essential) {
                            throw new MediaException.Malformed("heif: property index 0 is marked essential");
                        }
                        continue;
                    }
                    if (index > properties.size()) {
                        throw new MediaException.Malformed("heif: item " + id + " references property " + index
                                + " of " + properties.size());
                    }
                    var property = properties.get(index - 1);
                    if (strict && essential && property.type().equals("a1lx")) {
                        throw new MediaException.Malformed("heif: the a1lx property is marked essential");
                    }
                    if (strict && !essential && ESSENTIAL_TYPES.contains(property.type())) {
                        throw new MediaException.Malformed("heif: the " + property.type() + " property is not marked essential");
                    }
                    if (item != null) {
                        if (essential) {
                            item.essential.set(item.properties.size());
                        }
                        item.properties.add(property);
                    }
                }
            }
        }
    }

    /**
     * Parses the item reference box.
     *
     * @param iref the cursor over the {@code iref} payload
     * @param items the items that make the references
     * @param strict whether to reject a {@code dimg} reference that names an input twice
     */
    private static void parseItemReferences(BoxCursor iref, Map<Integer, HeifItem> items, boolean strict) {
        var version = iref.fullBoxHeader() >>> 24;
        if (version > 1) {
            return;
        }
        var derived = new HashSet<Integer>();
        while (iref.hasBox()) {
            var reference = iref.nextBox();
            var type = FourCc.toString(reference.type());
            var from = itemId(reference, version == 1);
            var count = reference.u16();
            var targets = new int[count];
            for (var i = 0; i < count; i++) {
                targets[i] = itemId(reference, version == 1);
            }
            if (type.equals("dimg")) {
                if (!derived.add(from)) {
                    throw new MediaException.Malformed("heif: item " + from + " has two dimg reference boxes");
                }
                var distinct = new HashSet<Integer>();
                for (var target : targets) {
                    if (strict && !distinct.add(target)) {
                        throw new MediaException.Malformed("heif: item " + from + " references input " + target + " twice");
                    }
                }
            }
            var item = items.get(from);
            if (item != null) {
                item.addReferences(type, targets);
            }
        }
    }

    /**
     * Returns the major brand.
     *
     * @return the four-character brand
     */
    public String majorBrand() {
        return majorBrand;
    }

    /**
     * Returns the brands of the {@code ftyp} box.
     *
     * @return the major brand followed by the compatible brands
     */
    public List<String> brands() {
        return brands;
    }

    /**
     * Returns whether the {@code ftyp} box lists a brand, as major or compatible brand.
     *
     * @param brand the four-character brand
     * @return {@code true} when listed
     */
    public boolean hasBrand(String brand) {
        return brands.contains(brand);
    }

    /**
     * Returns whether the file has a file-level {@code meta} box.
     *
     * @return {@code true} when the items of the file were parsed
     */
    public boolean hasMeta() {
        return hasMeta;
    }

    /**
     * Returns the primary item.
     *
     * @return the item named by {@code pitm}, or {@code null} when there is none or it names no
     * item
     */
    public HeifItem primaryItem() {
        return primaryItemId == 0 ? null : items.get(primaryItemId);
    }

    /**
     * Returns an item.
     *
     * @param id the identifier
     * @return the item, or {@code null} when absent
     */
    public HeifItem item(int id) {
        return items.get(id);
    }

    /**
     * Returns every item in {@code iinf} order.
     *
     * @return the items, unmodifiable
     */
    public List<HeifItem> items() {
        return List.copyOf(items.values());
    }

    /**
     * Returns the entity groups of the {@code grpl} box.
     *
     * @return the groups in file order, unmodifiable
     */
    public List<EntityGroup> entityGroups() {
        return groups;
    }

    /**
     * Returns the items the file prefers to an item: the items listed before it in the alternatives
     * group ({@code altr}) that holds it, most preferred first (ISO/IEC 14496-12 clause 8.18.3.3).
     *
     * <p>Only the first {@code altr} group that lists the item counts, since an entity belongs to
     * one such group at most.
     *
     * @param id the item identifier
     * @return the preferred items, empty when the item is in no alternatives group or is listed
     * first
     */
    public List<HeifItem> preferredAlternatives(int id) {
        for (var group : groups) {
            if (!group.type().equals("altr")) {
                continue;
            }
            var preferred = new ArrayList<HeifItem>();
            for (var entity : group.entities()) {
                if (entity == id) {
                    return List.copyOf(preferred);
                }
                var item = items.get(entity);
                if (item != null) {
                    preferred.add(item);
                }
            }
        }
        return List.of();
    }

    /**
     * Returns whether the file has a {@code moov} box.
     *
     * @return {@code true} for files that hold an image sequence
     */
    public boolean hasMovie() {
        return movieOffset >= 0;
    }

    /**
     * Returns the file offset of the {@code moov} payload.
     *
     * @return the offset, or {@code -1} when the file has no {@code moov} box
     */
    public long movieOffset() {
        return movieOffset;
    }

    /**
     * Returns the size of the {@code moov} payload.
     *
     * @return the size in bytes, {@code 0} when absent
     */
    public long movieSize() {
        return movieSize;
    }

    /**
     * Returns the items that describe another item with a content description reference
     * ({@code cdsc}), such as its EXIF and XMP metadata.
     *
     * @param described the described item identifier
     * @return the describing items in {@code iinf} order
     */
    public List<HeifItem> descriptionsOf(int described) {
        var found = new ArrayList<HeifItem>();
        for (var item : items.values()) {
            if (item.references("cdsc", described)) {
                found.add(item);
            }
        }
        return found;
    }

    /**
     * Returns the auxiliary items that reference an item with {@code auxl}.
     *
     * @param main the main item identifier
     * @return the auxiliary items in {@code iinf} order
     */
    public List<HeifItem> auxiliariesOf(int main) {
        var found = new ArrayList<HeifItem>();
        for (var item : items.values()) {
            if (item.references("auxl", main)) {
                found.add(item);
            }
        }
        return found;
    }

    /**
     * Returns the thumbnail items of an item: the items that reference it with {@code thmb}.
     *
     * @param master the master item identifier
     * @return the thumbnail items in {@code iinf} order
     */
    public List<HeifItem> thumbnailsOf(int master) {
        var found = new ArrayList<HeifItem>();
        for (var item : items.values()) {
            if (item.references("thmb", master)) {
                found.add(item);
            }
        }
        return found;
    }

    /**
     * Reads the data of an item, concatenating its extents.
     *
     * @param source the file
     * @param item an item of this file
     * @param limits the bound of the item size
     * @return the data
     * @throws MediaException.Malformed if the item has no location or an extent lies outside the
     * file, the {@code idat} box or the referenced item
     * @throws MediaException.Unsupported if the item is stored in another file
     * @throws MediaException.LimitExceeded if the item exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public byte[] readItem(MediaSource source, HeifItem item, MediaLimits limits) {
        return readItem(source, item, limits, 0);
    }

    /**
     * Reads the data of an item, following construction method {@code 2} to a bounded depth.
     *
     * @param source the file
     * @param item the item to read
     * @param limits the bound of the item size
     * @param depth the number of items already being read on the way to this one
     * @return the data
     */
    private byte[] readItem(MediaSource source, HeifItem item, MediaLimits limits, int depth) {
        if (depth >= MAX_ITEM_DEPTH) {
            throw new MediaException.Malformed("heif: item " + item.id() + " is constructed from items nested deeper than "
                    + MAX_ITEM_DEPTH);
        }
        if (!item.located) {
            throw new MediaException.Malformed("heif: item " + item.id() + " has no location");
        }
        if (item.constructionMethod == 0 && item.dataReferenceIndex != 0) {
            return readReferencedItem(source, item, limits);
        }
        var total = item.size();
        LimitChecks.checkAllocation(limits, total);
        checkExtents(source, item);
        var data = new byte[(int) total];
        var position = 0;
        byte[] referenced = null;
        var referencedIndex = -1;
        for (var e = 0; e < item.extentOffsets.length; e++) {
            var offset = item.extentOffsets[e];
            var length = (int) item.extentLengths[e];
            switch (item.constructionMethod) {
                case 0 -> source.readFully(offset, data, position, length);
                case 1 -> copyRange(idat, offset, data, position, length, item, "idat");
                default -> {
                    var index = item.extentReferences[e];
                    if (index != referencedIndex) {
                        var targets = item.references("iloc");
                        if (index < 1 || index > targets.length) {
                            throw new MediaException.Malformed("heif: item " + item.id() + " has no iloc reference " + index);
                        }
                        var target = items.get(targets[index - 1]);
                        if (target == null) {
                            throw new MediaException.Malformed("heif: item " + item.id() + " is built from missing item "
                                    + targets[index - 1]);
                        }
                        referenced = readItem(source, target, limits, depth + 1);
                        referencedIndex = index;
                    }
                    copyRange(referenced, offset, data, position, length, item, "the referenced item");
                }
            }
            position += length;
        }
        return data;
    }

    /**
     * Reads an item of construction method {@code 0} whose data reference index is not {@code 0}: from the file
     * when the entry is self-contained, from the file the resolver supplies otherwise.
     *
     * @param source the file
     * @param item the item to read
     * @param limits the bound of the item size
     * @return the data
     * @throws MediaException.Malformed if the index names no entry of the {@code dref} box
     * @throws MediaException.Unsupported if the item is stored in a file the resolver does not supply
     */
    private byte[] readReferencedItem(MediaSource source, HeifItem item, MediaLimits limits) {
        var index = item.dataReferenceIndex;
        var dataReferences = dataReferences();
        if (index > dataReferences.size()) {
            throw new MediaException.Malformed("heif: item " + item.id() + " names data reference " + index
                    + " of a dref box of " + dataReferences.size() + " entries");
        }
        try (var files = new DataReferences(resolver)) {
            var from = files.open(dataReferences.get(index - 1), "heif", "item " + item.id());
            var data = from == null ? source : from;
            var total = item.size();
            LimitChecks.checkAllocation(limits, total);
            checkExtents(data, item);
            var bytes = new byte[(int) total];
            var position = 0;
            for (var e = 0; e < item.extentOffsets.length; e++) {
                var length = (int) item.extentLengths[e];
                data.readFully(item.extentOffsets[e], bytes, position, length);
                position += length;
            }
            return bytes;
        }
    }

    /**
     * Parses the entries of the {@code dref} box of the {@code dinf} box of the {@code meta} box.
     *
     * @return the entries, empty when the {@code meta} box has no {@code dinf} or its {@code dinf} no {@code dref}
     * @throws MediaException.Malformed if the {@code dref} box is damaged
     */
    private List<DataReferences.Entry> dataReferences() {
        if (dataInformation == null) {
            return List.of();
        }
        var dinf = dataInformation.duplicate();
        while (dinf.hasBox()) {
            var child = dinf.nextBox();
            if (FourCc.toString(child.type()).equals("dref")) {
                return DataReferences.parse(child);
            }
        }
        return List.of();
    }

    /**
     * Checks, before the item data is allocated, that every extent lies inside the source it
     * reads: the file when its size is known, the {@code idat} box, or the declared size of the
     * referenced item.
     *
     * @param source the file
     * @param item the item to check
     * @throws MediaException.Malformed if an extent lies outside its source
     */
    private void checkExtents(MediaSource source, HeifItem item) {
        var targets = item.constructionMethod == 2 ? item.references("iloc") : null;
        for (var e = 0; e < item.extentOffsets.length; e++) {
            long size;
            if (item.constructionMethod == 0) {
                size = source.size();
            } else if (item.constructionMethod == 1) {
                size = idat.length;
            } else {
                var index = item.extentReferences[e];
                var target = index >= 1 && index <= targets.length ? items.get(targets[index - 1]) : null;
                size = target == null ? -1 : target.size();
            }
            var offset = item.extentOffsets[e];
            var length = item.extentLengths[e];
            if (size >= 0 && (offset > size || length > size - offset)) {
                throw new MediaException.Malformed("heif: an extent of item " + item.id() + " lies outside its source");
            }
        }
    }

    /**
     * Copies an extent out of a buffer.
     *
     * @param buffer the buffer holding the extent
     * @param offset the offset of the extent in the buffer
     * @param target the item data
     * @param at the index in {@code target} of the extent
     * @param length the extent length
     * @param item the item, for error messages
     * @param what the name of the buffer, for error messages
     */
    private static void copyRange(byte[] buffer, long offset, byte[] target, int at, int length, HeifItem item, String what) {
        if (offset < 0 || offset > buffer.length || length > buffer.length - offset) {
            throw new MediaException.Malformed("heif: an extent of item " + item.id() + " lies outside " + what);
        }
        System.arraycopy(buffer, (int) offset, target, at, length);
    }

    /**
     * Reads the XMP packet that describes an image item, or the first input of a derived item
     * when the item itself has none.
     *
     * <p>The packet is the data of the first located {@code mime} item of content type
     * {@value #XMP_TYPE} that describes the item ({@code cdsc} reference), with its content
     * encoding undone: none, {@code deflate} (zlib, or raw deflate without a zlib header) or
     * {@code gzip}. Metadata is not needed to show the image, so a packet in another encoding, or
     * one that is corrupt or larger than the limits allow, is left out.
     *
     * @param source the file
     * @param item the image item
     * @param limits the bounds of the item read and of the inflated packet
     * @return the packet bytes, or {@code null} when there is none
     * @throws MediaException.Io if reading the source fails
     */
    public byte[] xmpOf(MediaSource source, HeifItem item, MediaLimits limits) {
        var current = item;
        for (var depth = 0; current != null && depth <= MAX_ITEM_DEPTH; depth++) {
            for (var description : descriptionsOf(current.id())) {
                if (!description.type().equals("mime") || !description.isLocated()
                        || !description.contentType().toLowerCase(Locale.ROOT).startsWith(XMP_TYPE)) {
                    continue;
                }
                try {
                    return decodeContent(readItem(source, description, limits), description.contentEncoding(), limits);
                } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded exception) {
                    return null;
                }
            }
            var inputs = current.references("dimg");
            current = inputs.length == 0 ? null : item(inputs[0]);
        }
        return null;
    }

    /**
     * Undoes the content encoding of the data of a {@code mime} item.
     *
     * @param data the stored data
     * @param encoding the {@code content_encoding}, the empty string for none
     * @param limits the bound of the decoded size
     * @return the decoded data
     * @throws MediaException.Unsupported if the encoding is not none, {@code deflate} or {@code gzip}
     * @throws MediaException.Malformed if the compressed data is corrupt or truncated
     * @throws MediaException.LimitExceeded if the decoded data exceeds the limits
     */
    static byte[] decodeContent(byte[] data, String encoding, MediaLimits limits) {
        return switch (encoding.trim().toLowerCase(Locale.ROOT)) {
            case "", "identity" -> data;
            case "deflate" -> {
                // HTTP deflate is zlib (RFC 1950); some writers store raw deflate (RFC 1951)
                var zlib = data.length >= 2 && (data[0] & 0x0F) == 8 && (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) % 31 == 0;
                yield inflate(data, 0, data.length, !zlib, limits);
            }
            case "gzip", "x-gzip" -> inflate(data, gzipHeaderLength(data), data.length, true, limits);
            default -> throw new MediaException.Unsupported("heif", "content encoding '" + encoding + "'");
        };
    }

    /**
     * Returns the length of the header of a gzip member (RFC 1952 section 2.3).
     *
     * @param data the gzip data
     * @return the offset of the deflate stream
     * @throws MediaException.Malformed if the header is invalid or truncated
     */
    private static int gzipHeaderLength(byte[] data) {
        if (data.length < 10 || (data[0] & 0xFF) != 0x1F || (data[1] & 0xFF) != 0x8B || data[2] != 8) {
            throw new MediaException.Malformed("heif: invalid gzip header");
        }
        var flags = data[3] & 0xFF;
        var offset = 10;
        if ((flags & 4) != 0) {
            if (offset + 2 > data.length) {
                throw new MediaException.Malformed("heif: truncated gzip header");
            }
            offset += 2 + ((data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8);
        }
        for (var bit = 8; bit <= 16; bit <<= 1) {
            if ((flags & bit) != 0) {
                while (offset < data.length && data[offset] != 0) {
                    offset++;
                }
                offset++;
            }
        }
        if ((flags & 2) != 0) {
            offset += 2;
        }
        if (offset > data.length) {
            throw new MediaException.Malformed("heif: truncated gzip header");
        }
        return offset;
    }

    /**
     * Inflates a deflate stream, bounded by the allocation limit.
     *
     * @param data the compressed data
     * @param offset the first compressed byte
     * @param end the end of the compressed bytes
     * @param raw whether the stream is raw deflate rather than zlib
     * @param limits the bound of the inflated size
     * @return the inflated bytes
     */
    private static byte[] inflate(byte[] data, int offset, int end, boolean raw, MediaLimits limits) {
        var inflater = new Inflater(raw);
        try {
            inflater.setInput(data, offset, end - offset);
            var bound = Math.min(limits.maxBytes(), Integer.MAX_VALUE - 16L);
            var output = new byte[(int) Math.min(bound, Math.max(64, Math.min((end - offset) * 4L, 1 << 20)))];
            var size = 0;
            while (!inflater.finished()) {
                if (size == output.length) {
                    var grown = Math.min((long) output.length * 2, bound);
                    if (grown <= output.length) {
                        // a full output is accepted only when the stream ends without another byte
                        if (inflater.inflate(new byte[1]) == 0 && inflater.finished()) {
                            break;
                        }
                        throw new MediaException.LimitExceeded("heif: the inflated item exceeds " + limits.maxBytes()
                                + " bytes");
                    }
                    output = Arrays.copyOf(output, (int) grown);
                }
                var produced = inflater.inflate(output, size, output.length - size);
                size += produced;
                if (produced == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new MediaException.Malformed("heif: the compressed item is truncated");
                }
            }
            return Arrays.copyOf(output, size);
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed("heif: the compressed item is corrupt", exception);
        } finally {
            inflater.end();
        }
    }

    /**
     * Extracts the TIFF structure of an {@code Exif} item.
     *
     * <p>The item starts with the 32-bit offset of the TIFF header from the end of that field
     * (ISO/IEC 23008-12 annex A.2.1). When that offset does not lead to a TIFF header, an item
     * that starts with a TIFF header itself (as early HEIF writers stored it) is taken whole, and
     * otherwise the first TIFF header after the field is taken.
     *
     * @param payload the item data
     * @return the TIFF structure starting with its byte-order mark, or {@code null} when the item
     * holds none
     */
    public static byte[] exifTiff(byte[] payload) {
        if (payload.length < 8) {
            return null;
        }
        var declared = ByteArrays.u32be(payload, 0);
        if (declared <= payload.length - 8L && isTiffHeader(payload, 4 + (int) declared)) {
            return Arrays.copyOfRange(payload, 4 + (int) declared, payload.length);
        }
        if (isTiffHeader(payload, 0)) {
            return payload.clone();
        }
        for (var offset = 4; offset + 4 <= payload.length; offset++) {
            if (isTiffHeader(payload, offset)) {
                return Arrays.copyOfRange(payload, offset, payload.length);
            }
        }
        return null;
    }

    /**
     * Returns whether a TIFF byte-order mark and magic number start at an offset.
     *
     * @param data the array
     * @param offset the offset, at most {@code data.length - 4}
     * @return {@code true} for {@code II*\0} or {@code MM\0*}
     */
    private static boolean isTiffHeader(byte[] data, int offset) {
        return (data[offset] == 'I' && data[offset + 1] == 'I' && data[offset + 2] == 42 && data[offset + 3] == 0)
                || (data[offset] == 'M' && data[offset + 1] == 'M' && data[offset + 2] == 0 && data[offset + 3] == 42);
    }
}
