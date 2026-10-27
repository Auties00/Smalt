package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.PageGroup;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the master images of a HEIF file as pages, each decoded by the {@link HeifImageCodec} of its own coding.
 *
 * <p>Page 0 is the image the file shows, as the decoder of its family decodes it: its primary item (or the
 * alternative its alternatives group prefers), or the first frame of its image sequence when the decoder shows the
 * sequence. The other pages are the master images of the file (ISO/IEC 23008-12) in item order: every image item
 * that is not hidden, not a thumbnail ({@code thmb}), not an auxiliary image ({@code auxl}), not an input of a
 * grid, an identity derivation, an overlay or a sample transform, not a tone map, and not shown by page 0. The items
 * of an alternatives group ({@code altr}, ISO/IEC 14496-12 clause 8.18.3.3) are alternatives of one image, so they
 * make a single page: the first of them, in group order, that a codec at hand reconstructs, or the first of them
 * when none does; the group holding the primary item is page 0. An item several alternatives groups list belongs
 * to the first of them, as {@link HeifFile#preferredAlternatives(int)} counts it. An image of a coding no artifact
 * at hand decodes is still a page, whose probe and read raise {@link MediaException.Unsupported}. The other entity
 * groups are reported by {@link #groups()} with the pages of their entities in group order; a stereo pair
 * ({@code ster}) only when both views are pages. Image sequences other than the one page 0 shows are not pages.
 *
 * <p>Instances are not thread-safe.
 */
public final class HeifPages implements PageReader {
    /**
     * The derived image types whose inputs are parts of them rather than images of their own.
     */
    private static final Set<String> COMPOSING_TYPES = Set.of("grid", "iden", "iovl", "sato");

    /**
     * The derived image types, whose items need not declare a size of their own.
     */
    private static final Set<String> DERIVED_TYPES = Set.of("grid", "iden", "iovl", "sato", "tmap");

    /**
     * The file.
     */
    private final MediaSource source;

    /**
     * The parsed file.
     */
    private final HeifFile file;

    /**
     * The decoding options of every page.
     */
    private final ImageDecoderOptions options;

    /**
     * The codec of the image the file shows, which decodes page 0.
     */
    private final HeifImageCodec shown;

    /**
     * The item of each page after page 0.
     */
    private final List<HeifItem> items;

    /**
     * The groups of pages.
     */
    private final List<PageGroup> groups;

    /**
     * Constructs a reader.
     *
     * @param source the file
     * @param file the parsed file
     * @param options the decoding options of every page
     * @param shown the codec of the image the file shows
     * @param items the item of each page after page 0
     * @param groups the groups of pages
     */
    private HeifPages(MediaSource source, HeifFile file, ImageDecoderOptions options, HeifImageCodec shown,
                      List<HeifItem> items, List<PageGroup> groups) {
        this.source = source;
        this.file = file;
        this.options = options;
        this.shown = shown;
        this.items = items;
        this.groups = groups;
    }

    /**
     * Opens the pages of a HEIF file.
     *
     * @param source the file
     * @param options the decoding options of every page
     * @param family the family of the decoder opening the file, which shows it when the coding of its image is
     * unknown
     * @return the reader
     * @throws MediaException.Malformed if the file structure is invalid
     * @throws MediaException.Unsupported if the family of the image the file shows has no codec at hand
     * @throws MediaException.LimitExceeded if a declared size or the page count exceeds the limits
     */
    public static PageReader open(MediaSource source, ImageDecoderOptions options, ImageFormat family) {
        var limits = options.limits();
        var file = HeifFile.parse(source, limits, false, options.dataReferences());
        var format = HeifImageCodec.formatOf(source, file, limits);
        var shown = HeifImageCodec.require(format == null ? family : format);
        var alternatives = new HashMap<Integer, HeifFile.EntityGroup>();
        for (var group : file.entityGroups()) {
            if (group.type().equals("altr")) {
                for (var entity : group.entities()) {
                    alternatives.putIfAbsent(entity, group);
                }
            }
        }
        var page = new HashMap<Integer, Integer>();
        var primary = file.primaryItem();
        if (primary != null && !shown.showsSequence(file)) {
            page.put(primary.id(), 0);
            var group = alternatives.get(primary.id());
            if (group != null) {
                for (var entity : group.entities()) {
                    if (alternatives.get(entity) == group) {
                        page.putIfAbsent(entity, 0);
                    }
                }
            }
        }
        var inputs = new HashSet<Integer>();
        for (var item : file.items()) {
            if (COMPOSING_TYPES.contains(item.type())) {
                for (var input : item.references("dimg")) {
                    inputs.add(input);
                }
            }
        }
        var masters = new LinkedHashSet<Integer>();
        for (var item : file.items()) {
            if (isMaster(item) && !inputs.contains(item.id())) {
                masters.add(item.id());
            }
        }
        var items = new ArrayList<HeifItem>();
        var decodable = new HashMap<Integer, Boolean>();
        for (var id : masters) {
            if (page.containsKey(id)) {
                continue;
            }
            var group = alternatives.get(id);
            var chosen = group == null ? id
                    : choose(source, file, group, alternatives, masters, page, decodable, options);
            items.add(file.item(chosen));
            var index = items.size();
            page.put(chosen, index);
            if (group != null) {
                for (var entity : group.entities()) {
                    if (masters.contains(entity) && alternatives.get(entity) == group) {
                        page.putIfAbsent(entity, index);
                    }
                }
            }
        }
        LimitChecks.checkFrames(limits, items.size() + 1L);
        return new HeifPages(source, file, options, shown, List.copyOf(items), groups(file, page));
    }

    /**
     * Returns whether an item is a master image candidate: an image item that is not hidden, not a thumbnail, not
     * an auxiliary image and not a tone map.
     *
     * @param item the item to classify
     * @return {@code true} for such an item
     */
    private static boolean isMaster(HeifItem item) {
        var image = HeifImageCodec.formatOf(item.type()) != null || DERIVED_TYPES.contains(item.type())
                || item.property(HeifProperty.SpatialExtent.class) != null;
        return image && !item.isHidden() && !item.type().equals("tmap") && item.references("thmb").length == 0
                && item.references("auxl").length == 0;
    }

    /**
     * Chooses the page of an alternatives group.
     *
     * @param source the file
     * @param file the parsed file
     * @param group the alternatives group
     * @param alternatives the alternatives group each entity belongs to, the first that lists it
     * @param masters the master image candidates
     * @param page the page of each item already shown, by item identifier
     * @param decodable the decodability already found, by item identifier
     * @param options the decoding options
     * @return the first candidate of the group that belongs to it, is not yet shown and that a codec at hand
     * reconstructs, or else its first such candidate
     */
    private static int choose(MediaSource source, HeifFile file, HeifFile.EntityGroup group,
                              Map<Integer, HeifFile.EntityGroup> alternatives, Set<Integer> masters,
                              Map<Integer, Integer> page, Map<Integer, Boolean> decodable,
                              ImageDecoderOptions options) {
        var first = -1;
        for (var entity : group.entities()) {
            if (!masters.contains(entity) || page.containsKey(entity) || alternatives.get(entity) != group) {
                continue;
            }
            if (first < 0) {
                first = entity;
            }
            var item = file.item(entity);
            if (decodable.computeIfAbsent(entity, _ -> decodable(source, file, item, options))) {
                return entity;
            }
        }
        return first;
    }

    /**
     * Returns whether a codec at hand reconstructs an item.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item
     * @param options the decoding options
     * @return {@code true} when the codec of its family is present and accepts it
     * @throws MediaException.Io if reading the source fails
     */
    private static boolean decodable(MediaSource source, HeifFile file, HeifItem item, ImageDecoderOptions options) {
        var format = HeifImageCodec.formatOf(file, item);
        var codec = format == null ? null : HeifImageCodec.find(format);
        if (codec == null) {
            return false;
        }
        try {
            codec.requireDecodable(source, file, item, options.limits());
            return true;
        } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded exception) {
            return false;
        }
    }

    /**
     * Lists the groups of pages, every entity group but the alternatives groups.
     *
     * @param file the parsed file
     * @param page the page of each item that is shown, by item identifier
     * @return the groups that hold at least one page, a stereo pair only when it holds two
     */
    private static List<PageGroup> groups(HeifFile file, Map<Integer, Integer> page) {
        var result = new ArrayList<PageGroup>();
        for (var group : file.entityGroups()) {
            if (group.type().equals("altr")) {
                continue;
            }
            var pages = new LinkedHashSet<Integer>();
            for (var entity : group.entities()) {
                var index = page.get(entity);
                if (index != null) {
                    pages.add(index);
                }
            }
            var stereo = group.type().equals("ster");
            if (stereo ? group.entities().length == 2 && pages.size() == 2 : !pages.isEmpty()) {
                result.add(new PageGroup(group.type(), List.copyOf(pages)));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public int pageCount() {
        return items.size() + 1;
    }

    @Override
    public ImageInfo probe(int index) {
        Objects.checkIndex(index, pageCount());
        var info = index == 0 ? shown.probe(source, options.limits())
                : codecOf(index).probe(source, file, items.get(index - 1), options.limits());
        return new ImageInfo(info.format(), info.width(), info.height(), info.hasAlpha(), false, pageCount(),
                info.orientation());
    }

    @Override
    public DecodedImage read(int index) {
        Objects.checkIndex(index, pageCount());
        if (index == 0) {
            return shown.decode(source, options);
        }
        return codecOf(index).decode(source, file, items.get(index - 1), options);
    }

    @Override
    public List<PageGroup> groups() {
        return groups;
    }

    /**
     * Returns the codec of a page after page 0.
     *
     * @param index the page index, at least 1
     * @return the codec of the family of its item
     * @throws MediaException.Unsupported if the item is of no known coding or its family has no codec at hand
     */
    private HeifImageCodec codecOf(int index) {
        var item = items.get(index - 1);
        var format = HeifImageCodec.formatOf(file, item);
        if (format == null) {
            throw new MediaException.Unsupported("heif", "item " + item.id() + " is coded as '" + item.type() + "'");
        }
        return HeifImageCodec.require(format);
    }
}
