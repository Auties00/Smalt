package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGrid;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifStills;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Describes what reconstructing the primary image of an AVIF file takes: the coded items of its
 * color and alpha planes, the grids that place them, and whether the color is premultiplied by
 * the alpha.
 *
 * <p>The primary item is either a coded {@code av01} item or a {@code grid} whose inputs are
 * coded items. The alpha plane is the first auxiliary item that references the primary item with
 * {@code auxl}, carries the alpha URN in its {@code auxC} property and can be decoded (a coded
 * item or a grid of them, with data and no unsupported essential property); when the primary item
 * is a grid without such an item, an alpha plane made of one auxiliary item per tile is used if
 * every tile has one. An alpha item that cannot be decoded is ignored, as libavif ignores it.
 *
 * @param primary the primary item
 * @param grid the color grid, or {@code null} for a single coded item
 * @param colorTiles the coded color items, in row-major grid order
 * @param config the {@code av1C} configuration shared by the color items
 * @param alphaGrid the alpha grid, or {@code null} for a single coded alpha item or none
 * @param alphaTiles the coded alpha items, empty when the image has no alpha plane
 * @param alphaItem the alpha item that references the primary item, or {@code null} when
 * the alpha plane is absent or made of per-tile items
 * @param premultiplied whether the color samples are premultiplied by the alpha
 * @implNote This implementation follows the item selection of libavif's {@code avifDecoderReset},
 * {@code avifMetaFindAlphaItem} and {@code avifDecoderGenerateImageTiles}
 * ({@code src/read.c}, BSD 2-clause).
 */
record StillPlan(HeifItem primary, HeifGrid grid, List<HeifItem> colorTiles, HeifProperty.Av1Configuration config,
                 HeifGrid alphaGrid, List<HeifItem> alphaTiles, HeifItem alphaItem, boolean premultiplied) {
    /**
     * The property types this decoder implements; an item with another essential property is not
     * decodable.
     */
    static final Set<String> SUPPORTED_PROPERTIES = Set.of("ispe", "pixi", "av1C", "colr", "pasp", "clap", "irot",
            "imir", "auxC", "a1op", "lsel", "a1lx", "clli", "mdcv");

    /**
     * Plans the reconstruction of the primary image.
     *
     * @param source the file
     * @param file the parsed file
     * @param limits the bounds of the grid payloads
     * @return the plan
     * @throws MediaException.Malformed if there is no primary item or the items are inconsistent
     * @throws MediaException.Unsupported if the primary item is not AV1-coded or carries an
     * essential property this decoder does not implement
     */
    static StillPlan of(MediaSource source, HeifFile file, MediaLimits limits) {
        var primary = file.primaryItem();
        if (primary == null) {
            throw new MediaException.Malformed("avif: the file has no primary item");
        }
        requireSupported(primary);
        HeifGrid grid = null;
        List<HeifItem> tiles;
        switch (primary.type()) {
            case "av01" -> tiles = List.of(primary);
            case "grid" -> {
                grid = HeifStills.readGrid("avif", source, file, primary, limits);
                tiles = inputs(file, primary, grid);
            }
            default -> throw new MediaException.Unsupported("avif", "the primary item is coded as '" + primary.type()
                    + "', not AV1");
        }
        var config = configOf(tiles);
        HeifGrid alphaGrid = null;
        List<HeifItem> alphaTiles = List.of();
        HeifItem alphaItem = null;
        for (var candidate : HeifStills.alphaCandidates(file, primary, SUPPORTED_PROPERTIES)) {
            if (!isUsableAlpha(candidate)) {
                continue;
            }
            try {
                if (candidate.type().equals("grid")) {
                    var candidateGrid = HeifStills.readGrid("avif", source, file, candidate, limits);
                    var candidateTiles = inputs(file, candidate, candidateGrid);
                    configOf(candidateTiles);
                    alphaGrid = candidateGrid;
                    alphaTiles = candidateTiles;
                } else {
                    configOf(List.of(candidate));
                    alphaTiles = List.of(candidate);
                }
            } catch (MediaException.Unsupported exception) {
                continue;
            }
            alphaItem = candidate;
            break;
        }
        if (alphaItem == null && grid != null) {
            var perTile = new ArrayList<HeifItem>(tiles.size());
            for (var tile : tiles) {
                HeifItem found = null;
                for (var candidate : HeifStills.alphaCandidates(file, tile, SUPPORTED_PROPERTIES)) {
                    if (candidate.type().equals("av01") && isUsableAlpha(candidate)) {
                        found = candidate;
                        break;
                    }
                }
                if (found == null) {
                    perTile = null;
                    break;
                }
                perTile.add(found);
            }
            if (perTile != null) {
                configOf(perTile);
                alphaGrid = grid;
                alphaTiles = List.copyOf(perTile);
            }
        }
        var premultiplied = alphaItem != null && primary.references("prem", alphaItem.id());
        return new StillPlan(primary, grid, tiles, config, alphaGrid, alphaTiles, alphaItem, premultiplied);
    }

    /**
     * Returns whether the image has an alpha plane.
     *
     * @return {@code true} when alpha items were found
     */
    boolean hasAlpha() {
        return !alphaTiles.isEmpty();
    }

    /**
     * Rejects an item with an essential property this decoder does not implement.
     *
     * @param item the item to check
     * @throws MediaException.Unsupported if such a property is associated
     */
    static void requireSupported(HeifItem item) {
        var unsupported = item.unsupportedEssential(SUPPORTED_PROPERTIES);
        if (unsupported != null) {
            throw new MediaException.Unsupported("avif", "item " + item.id() + " has the essential property '"
                    + unsupported + "'");
        }
    }

    /**
     * Returns whether an alpha candidate holds data this decoder can use.
     *
     * @param item an alpha candidate of {@link HeifStills#alphaCandidates}
     * @return {@code true} for a located, non-empty {@code av01} or {@code grid} item
     */
    private static boolean isUsableAlpha(HeifItem item) {
        return item.isLocated() && item.size() > 0 && (item.type().equals("av01") || item.type().equals("grid"));
    }

    /**
     * Resolves the input items of a grid.
     *
     * @param file the parsed file
     * @param item the grid item
     * @param grid the parsed grid, which gives the number of inputs
     * @return the coded input items in row-major order
     * @throws MediaException.Malformed if the inputs do not match the grid or are not coded
     * AV1 items with data
     * @throws MediaException.Unsupported if an input carries an unsupported essential property
     */
    private static List<HeifItem> inputs(HeifFile file, HeifItem item, HeifGrid grid) {
        var ids = item.references("dimg");
        if (ids.length != grid.tiles()) {
            throw new MediaException.Malformed("avif: grid item " + item.id() + " has " + ids.length + " inputs for "
                    + grid.tiles() + " tiles");
        }
        var tiles = new ArrayList<HeifItem>(ids.length);
        for (var id : ids) {
            var tile = file.item(id);
            if (tile == null || !tile.type().equals("av01") || !tile.isLocated() || tile.size() == 0) {
                throw new MediaException.Malformed("avif: input " + id + " of grid item " + item.id()
                        + " is not a coded AV1 item with data");
            }
            requireSupported(tile);
            tiles.add(tile);
        }
        return List.copyOf(tiles);
    }

    /**
     * Returns the {@code av1C} configuration shared by coded items.
     *
     * @param items the coded items
     * @return the configuration of the first item
     * @throws MediaException.Malformed if an item has no configuration or the configurations
     * differ in profile, level, tier, bit depth or chroma
     * layout, which MIAF clause 7.3.11.4.1 forbids
     */
    private static HeifProperty.Av1Configuration configOf(List<HeifItem> items) {
        HeifProperty.Av1Configuration first = null;
        for (var item : items) {
            var config = item.property(HeifProperty.Av1Configuration.class);
            if (config == null) {
                throw new MediaException.Malformed("avif: item " + item.id() + " has no av1C property");
            }
            if (first == null) {
                first = config;
            } else if (config.profile() != first.profile() || config.level() != first.level()
                    || config.tier() != first.tier() || config.bitDepth() != first.bitDepth()
                    || config.monochrome() != first.monochrome() || config.subsamplingX() != first.subsamplingX()
                    || config.subsamplingY() != first.subsamplingY()
                    || config.chromaSamplePosition() != first.chromaSamplePosition()) {
                throw new MediaException.Malformed("avif: the tiles of a grid have different av1C configurations");
            }
        }
        return first;
    }

    /**
     * Returns the operating point an item selects.
     *
     * @param item the coded item
     * @return the {@code a1op} operating point, or {@code 0}
     */
    static int operatingPoint(HeifItem item) {
        var selector = item.property(HeifProperty.OperatingPointSelector.class);
        return selector == null ? 0 : selector.operatingPoint();
    }

    /**
     * Returns the spatial layer an item selects.
     *
     * @param item the coded item
     * @return the {@code lsel} layer, or {@link HeifProperty.LayerSelector#ALL_LAYERS}
     */
    static int layer(HeifItem item) {
        var selector = item.property(HeifProperty.LayerSelector.class);
        return selector == null ? HeifProperty.LayerSelector.ALL_LAYERS : selector.layer();
    }
}
