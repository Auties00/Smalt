package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifCanvas;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGeometry;
import com.github.auties00.smalt.internal.heif.HeifImageCodec;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifOverlay;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifSampleTransform;
import com.github.auties00.smalt.internal.heif.HeifStill;
import com.github.auties00.smalt.internal.heif.HeifStills;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconstructs an image item of an AVIF file, derived images included (ISO/IEC 23008-12 clause
 * 6.6.2 and AVIF 1.2 clause 4.2.3).
 *
 * <p>The reconstructed image of an item is:
 * <ul>
 *   <li>for a coded item ({@code av01}) or a grid of coded items, the image {@link StillDecoder}
 *       reconstructs from its {@link StillPlan};</li>
 *   <li>for a grid with a derived input, its input images placed edge to edge and cropped to the
 *       output size: a planar picture when every input is planar without alpha, otherwise a
 *       packed straight-alpha RGBA picture into which each input is converted on its own with the
 *       grid's color properties applied;</li>
 *   <li>for an identity derivation ({@code iden}), its input image;</li>
 *   <li>for an image overlay ({@code iovl}), its input images placed over a canvas filled with
 *       the fill color, each over the ones before it, as straight-alpha RGBA (16-bit when a
 *       source of more than 8 bits keeps its depth and an input has more than 8 bits);</li>
 *   <li>for a sample transform ({@code sato}), its expression evaluated over the samples of its
 *       input images, each reconstructed at its own depth, as a planar picture of the depth of
 *       the item's {@code pixi} property (see {@link HeifSampleTransform}).</li>
 * </ul>
 * The input images of a derived image are the reconstructed images of the referenced items with
 * their own transformative properties applied, cropped and turned upright. A grid gets its alpha
 * plane from its alpha auxiliary image, resampled to its size and rescaled to its depth; color
 * premultiplied by that alpha ({@code prem} reference) is divided back into straight-alpha RGBA.
 *
 * <p>Derived images nest at most {@value #MAX_DEPTH} deep, and the pictures decoded and composed
 * for one image total at most four times the pixel limit; a file that goes beyond either is
 * rejected, so a small file cannot make the decoder loop or decode the same picture without end. An
 * input coded with HEVC, AVC, JPEG, JPEG 2000 or as uncompressed samples is reconstructed by the HEIC
 * {@link HeifImageCodec}, its nesting counted on from the derived image, and raises
 * {@link MediaException.Unsupported} when no artifact provides it.
 * Instances are not thread-safe; each reconstructs one image.
 */
final class Reconstruction {
    /**
     * The deepest nesting of derived images followed.
     */
    static final int MAX_DEPTH = 8;

    /**
     * The format name of failure messages.
     */
    private static final String FORMAT = "avif";

    /**
     * The smallest number of samples a picture is charged against the budget.
     */
    private static final int MIN_CHARGE = 64 * 64;

    /**
     * The file.
     */
    private final MediaSource source;

    /**
     * The parsed file.
     */
    private final HeifFile file;

    /**
     * The bounds of every item and picture.
     */
    private final MediaLimits limits;

    /**
     * The items being reconstructed, to reject a derivation that references itself.
     */
    private final Set<Integer> inProgress = new HashSet<>();

    /**
     * The samples left to decode and compose before the file is rejected.
     */
    private long budget;

    /**
     * Constructs the reconstruction of one image.
     *
     * @param source the file
     * @param file the parsed file
     * @param limits the bounds of every item and picture
     */
    private Reconstruction(MediaSource source, HeifFile file, MediaLimits limits) {
        this.source = source;
        this.file = file;
        this.limits = limits;
        this.budget = limits.maxPixels() > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : limits.maxPixels() * 4;
    }

    /**
     * Reconstructs an image item and applies its clean aperture.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the item to show
     * @param limits the bounds of every item and picture
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @return the image
     * @throws MediaException.Malformed if an item is corrupt or the items do not fit together
     * @throws MediaException.Unsupported if an item uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    static HeifStill decode(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits,
                            boolean highBitDepth) {
        if (StillPlan.isCoded(file, item)) {
            return StillDecoder.decode(source, file, StillPlan.of(source, file, item, limits), limits, highBitDepth);
        }
        var picture = new Reconstruction(source, file, limits).reconstruct(item, highBitDepth, 0);
        var width = picture.width();
        var height = picture.height();
        var geometry = HeifGeometry.resolve(item.properties(), width, height);
        if (geometry.crops(width, height)) {
            picture = HeifPictures.crop(picture, geometry.x(), geometry.y(), geometry.width(), geometry.height());
        }
        return new HeifStill(picture, width, height, geometry);
    }

    /**
     * Reconstructs an item as the input of a derived image of a HEIC file: its image before its own transformative
     * properties.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item
     * @param limits the bounds of every item and picture
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @param depth the number of derived images reconstructed on the way to the item
     * @return the picture
     * @throws MediaException.Malformed if an item is corrupt or nested deeper than {@value #MAX_DEPTH}
     * @throws MediaException.Unsupported if an item uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    static Picture reconstructInput(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits,
                                    boolean highBitDepth, int depth) {
        return new Reconstruction(source, file, limits).reconstruct(item, highBitDepth, depth);
    }

    /**
     * Checks, without decoding pixels, that an item is one this decoder reconstructs.
     *
     * <p>Every item of the derivation is of a known type and has only supported essential
     * properties, and every sample transform parses; the coded items are checked as
     * {@link StillPlan#of} checks them.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item checked
     * @param limits the bounds of the payloads read
     * @throws MediaException.Malformed if an item is missing or inconsistent
     * @throws MediaException.Unsupported if an item uses a feature this decoder lacks
     */
    static void requireDecodable(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits) {
        requireDecodable(source, file, item, limits, 0, new HashSet<>(), new HashSet<>());
    }

    /**
     * Checks an item at a nesting depth, each item once.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item checked
     * @param limits the bounds of the payloads read
     * @param depth the nesting depth of the item
     * @param path the items being checked on the way to this one
     * @param checked the items already checked
     */
    private static void requireDecodable(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits,
                                         int depth, Set<Integer> path, Set<Integer> checked) {
        if (depth > MAX_DEPTH) {
            throw new MediaException.Malformed("avif: derived images are nested deeper than " + MAX_DEPTH);
        }
        if (!path.add(item.id())) {
            throw new MediaException.Malformed("avif: item " + item.id() + " is derived from itself");
        }
        if (checked.add(item.id())) {
            if (HeifImageCodec.formatOf(item.type()) == ImageFormat.HEIC) {
                HeifImageCodec.require(ImageFormat.HEIC).requireDecodable(source, file, item, limits);
            } else if (StillPlan.isCoded(file, item)) {
                StillPlan.of(source, file, item, limits);
            } else {
                StillPlan.requireSupported(item);
                switch (item.type()) {
                    case "grid", "iden", "iovl" -> {
                    }
                    case "sato" -> sampleTransformOf(source, file, item, limits);
                    default -> throw new MediaException.Unsupported(FORMAT, "item " + item.id() + " is coded as '"
                            + item.type() + "', not AV1");
                }
                for (var id : item.references("dimg")) {
                    var input = file.item(id);
                    if (input == null) {
                        throw new MediaException.Malformed("avif: input " + id + " of item " + item.id()
                                + " is missing");
                    }
                    requireDecodable(source, file, input, limits, depth + 1, path, checked);
                }
            }
        }
        path.remove(item.id());
    }

    /**
     * Returns the size an image item declares, before its transformative properties.
     *
     * <p>The size is that of the payload of a grid, or of its {@code ispe} property; without one,
     * that of the payload of an overlay, or of the first input of an identity derivation or a
     * sample transform.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item
     * @param limits the bounds of the payloads read
     * @return the width and the height
     * @throws MediaException.Malformed if the item declares no valid size
     */
    static int[] declaredSize(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits) {
        var current = item;
        for (var depth = 0; depth <= MAX_DEPTH; depth++) {
            long width;
            long height;
            var extent = current.property(HeifProperty.SpatialExtent.class);
            var inputs = current.references("dimg");
            if (current.type().equals("grid")) {
                var grid = HeifStills.readGrid(FORMAT, source, file, current, limits);
                width = grid.outputWidth();
                height = grid.outputHeight();
            } else if (extent != null) {
                width = extent.width();
                height = extent.height();
            } else if (current.type().equals("iovl") && current.size() <= 20 + 8L * inputs.length) {
                var overlay = HeifOverlay.parse(file.readItem(source, current, limits), inputs.length);
                width = overlay.outputWidth();
                height = overlay.outputHeight();
            } else if ((current.type().equals("iden") || current.type().equals("sato")) && inputs.length > 0
                    && file.item(inputs[0]) != null) {
                current = file.item(inputs[0]);
                continue;
            } else {
                throw new MediaException.Malformed("avif: item " + current.id() + " has no ispe property");
            }
            if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("avif: invalid image size " + width + "x" + height);
            }
            return new int[]{(int) width, (int) height};
        }
        throw new MediaException.Malformed("avif: derived images are nested deeper than " + MAX_DEPTH);
    }

    /**
     * Returns whether the reconstructed image of an item has an alpha plane.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the image item
     * @param limits the bounds of the payloads read
     * @return {@code true} when the image or one of the images it is derived from has alpha, or
     * for an overlay
     */
    static boolean hasAlpha(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits) {
        return hasAlpha(source, file, item, limits, 0, new HashMap<>());
    }

    /**
     * Returns whether an item at a nesting depth has an alpha plane.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the item, or {@code null}
     * @param limits the bounds of the payloads read
     * @param depth the nesting depth of the item
     * @param known the answers already found, by item identifier
     * @return whether the image has alpha
     */
    private static boolean hasAlpha(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits, int depth,
                                    Map<Integer, Boolean> known) {
        if (item == null || depth > MAX_DEPTH) {
            return false;
        }
        var answer = known.get(item.id());
        if (answer != null) {
            return answer;
        }
        boolean result;
        var inputs = item.references("dimg");
        if (StillPlan.isCoded(file, item)) {
            result = StillPlan.of(source, file, item, limits).hasAlpha();
        } else {
            result = switch (item.type()) {
                case "iovl" -> true;
                case "iden", "sato" -> inputs.length > 0
                        && hasAlpha(source, file, file.item(inputs[0]), limits, depth + 1, known);
                case "grid" -> {
                    if (alphaOf(file, item) != null) {
                        yield true;
                    }
                    for (var id : inputs) {
                        if (hasAlpha(source, file, file.item(id), limits, depth + 1, known)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                default -> false;
            };
        }
        known.put(item.id(), result);
        return result;
    }

    /**
     * Reconstructs the image of an item, before its own transformative properties.
     *
     * @param item the image item, or {@code null}
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param depth the number of derived images reconstructed on the way to this one
     * @return the picture: planar, or packed RGBA for an overlay, a premultiplied image and a
     * grid of such images
     */
    private Picture reconstruct(HeifItem item, boolean wide, int depth) {
        if (depth > MAX_DEPTH) {
            throw new MediaException.Malformed("avif: derived images are nested deeper than " + MAX_DEPTH);
        }
        if (!inProgress.add(item.id())) {
            throw new MediaException.Malformed("avif: item " + item.id() + " is derived from itself");
        }
        try {
            if (HeifImageCodec.formatOf(item.type()) == ImageFormat.HEIC) {
                var picture = HeifImageCodec.require(ImageFormat.HEIC).reconstructInput(source, file, item, limits,
                        wide, depth + 1);
                charge((long) picture.width() * picture.height());
                return picture;
            }
            StillPlan.requireSupported(item);
            if (StillPlan.isCoded(file, item)) {
                return coded(item, wide);
            }
            return switch (item.type()) {
                case "grid" -> grid(item, wide, depth);
                case "iden" -> identity(item, wide, depth);
                case "iovl" -> overlay(item, wide, depth);
                case "sato" -> sampleTransform(item, wide, depth);
                default -> throw new MediaException.Unsupported(FORMAT, "item " + item.id() + " is coded as '"
                        + item.type() + "', not AV1");
            };
        } finally {
            inProgress.remove(item.id());
        }
    }

    /**
     * Reconstructs the output image of an item: its reconstructed image cropped and turned
     * upright by its transformative properties, as derived images take their inputs.
     *
     * @param item the input item
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param depth the nesting depth of the item
     * @return the picture
     */
    private Picture output(HeifItem item, boolean wide, int depth) {
        var picture = reconstruct(item, wide, depth);
        var geometry = HeifGeometry.resolve(item.properties(), picture.width(), picture.height());
        if (geometry.crops(picture.width(), picture.height())) {
            picture = HeifPictures.crop(picture, geometry.x(), geometry.y(), geometry.width(), geometry.height());
        }
        return ImageOps.orient(picture, geometry.orientation());
    }

    /**
     * Returns an input of a derived image.
     *
     * @param item the derived item
     * @param id the identifier of the input
     * @return the input item
     * @throws MediaException.Malformed if the item does not exist
     */
    private HeifItem input(HeifItem item, int id) {
        var input = file.item(id);
        if (input == null) {
            throw new MediaException.Malformed("avif: input " + id + " of item " + item.id() + " is missing");
        }
        return input;
    }

    /**
     * Charges samples against the budget of the image.
     *
     * @param samples the samples decoded or composed
     * @throws MediaException.LimitExceeded if the budget is exhausted
     */
    private void charge(long samples) {
        budget -= Math.max(samples, MIN_CHARGE);
        if (budget < 0) {
            throw new MediaException.LimitExceeded("avif: the image decodes more than four times the picture limit");
        }
    }

    /**
     * Reconstructs a coded item or a grid of coded items.
     *
     * @param item the image item reconstructed
     * @param wide whether a source of more than 8 bits keeps its depth
     * @return the picture
     */
    private Picture coded(HeifItem item, boolean wide) {
        var plan = StillPlan.of(source, file, item, limits);
        var picture = StillDecoder.reconstruct(source, file, plan, limits, wide);
        charge((long) picture.width() * picture.height() * (plan.hasAlpha() ? 2 : 1));
        return picture;
    }

    /**
     * Returns the alpha auxiliary image of a derived grid.
     *
     * @param file the parsed file
     * @param item the grid item
     * @return the first alpha auxiliary item that is coded, a grid or an identity derivation, has
     * data when coded and only supported essential properties, or {@code null}
     */
    private static HeifItem alphaOf(HeifFile file, HeifItem item) {
        // TODO: attach an alpha auxiliary image coded with another codec through the HEIC HeifImageCodec
        for (var candidate : HeifStills.alphaCandidates(file, item, StillPlan.SUPPORTED_PROPERTIES)) {
            switch (candidate.type()) {
                case "av01", "grid" -> {
                    if (candidate.isLocated() && candidate.size() > 0) {
                        return candidate;
                    }
                }
                case "iden" -> {
                    return candidate;
                }
                default -> {
                }
            }
        }
        return null;
    }

    /**
     * Decodes an alpha auxiliary image into the alpha plane of a canvas, resampling it to the
     * canvas size when its size differs.
     *
     * @param canvas the canvas, which has an alpha plane
     * @param alpha the alpha item
     * @param depth the nesting depth of the item the alpha belongs to
     */
    private void attachAlpha(HeifCanvas canvas, HeifItem alpha, int depth) {
        var picture = output(alpha, true, depth + 1);
        var limited = !HeifPictures.isPacked(picture) && HeifPictures.colorOf(picture).range() == ColorRange.LIMITED;
        canvas.writeAlpha(HeifPictures.resampleAlpha(picture, canvas.width(), canvas.height()), limited);
    }

    /**
     * Reconstructs a grid with a derived input, one input after the other.
     *
     * @param item the grid item
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param depth the nesting depth of the item
     * @return the picture
     */
    private Picture grid(HeifItem item, boolean wide, int depth) {
        var grid = HeifStills.readGrid(FORMAT, source, file, item, limits);
        var ids = item.references("dimg");
        if (ids.length != grid.tiles()) {
            throw new MediaException.Malformed("avif: grid item " + item.id() + " has " + ids.length + " inputs for "
                    + grid.tiles() + " tiles");
        }
        var width = HeifStills.checkedSize(FORMAT, grid.outputWidth());
        var height = HeifStills.checkedSize(FORMAT, grid.outputHeight());
        LimitChecks.checkPicture(limits, width, height);
        charge((long) width * height);
        var alpha = alphaOf(file, item);
        var pictures = new ArrayList<Picture>(ids.length);
        var packed = false;
        for (var index = 0; index < ids.length; index++) {
            var picture = output(input(item, ids[index]), wide, depth + 1);
            if (index == 0) {
                grid.validateCoverage(picture.width(), picture.height());
            } else if (picture.width() != pictures.getFirst().width()
                    || picture.height() != pictures.getFirst().height()) {
                throw new MediaException.Malformed("avif: the inputs of grid item " + item.id() + " differ in size");
            }
            packed |= HeifPictures.isPacked(picture) || alpha == null && picture.hasAlpha();
            pictures.add(picture);
        }
        var tileWidth = pictures.getFirst().width();
        var tileHeight = pictures.getFirst().height();
        Picture result;
        if (packed) {
            LimitChecks.checkAllocation(limits, (wide ? 12L : 4L) * width * height);
            Picture canvas = RgbaPicture.allocate(width, height, true);
            for (var index = 0; index < pictures.size(); index++) {
                var picture = pictures.get(index);
                var x = (index % grid.columns()) * tileWidth;
                var y = (index / grid.columns()) * tileHeight;
                var input = HeifPictures.isPacked(picture) ? picture
                        : HeifPictures.withColor(picture, Colors.override(HeifPictures.colorOf(picture), item));
                canvas = HeifPictures.place(canvas, input, x, y, Math.min(tileWidth, width - x),
                        Math.min(tileHeight, height - y));
            }
            if (alpha != null) {
                var deep = canvas instanceof Rgba16Picture;
                var plane = new HeifCanvas(FORMAT, limits, width, height, ChromaFormat.MONOCHROME, deep ? 16 : 8, deep,
                        true);
                attachAlpha(plane, alpha, depth);
                HeifPictures.replaceAlpha(canvas, plane.toPicture(ColorInfo.JPEG));
            }
            result = canvas;
        } else {
            var first = pictures.getFirst();
            var format = switch (first) {
                case YuvPicture yuv -> yuv.chromaFormat();
                case Yuv16Picture yuv -> yuv.chromaFormat();
                default -> throw new MediaException.Malformed("avif: grid item " + item.id() + " has a packed input");
            };
            var canvas = new HeifCanvas(FORMAT, limits, width, height, format, first.bitDepth(),
                    first instanceof Yuv16Picture, alpha != null);
            for (var index = 0; index < pictures.size(); index++) {
                var x = (index % grid.columns()) * tileWidth;
                var y = (index / grid.columns()) * tileHeight;
                if ((x & ((1 << format.shiftX()) - 1)) != 0 || (y & ((1 << format.shiftY()) - 1)) != 0) {
                    throw new MediaException.Malformed("avif: grid inputs of " + tileWidth + "x" + tileHeight
                            + " split the subsampled chroma");
                }
                canvas.writeColor(pictures.get(index), x, y, Math.min(tileWidth, width - x),
                        Math.min(tileHeight, height - y));
            }
            if (alpha != null) {
                attachAlpha(canvas, alpha, depth);
            }
            result = canvas.toPicture(Colors.override(HeifPictures.colorOf(first), item));
        }
        return alpha != null && item.references("prem", alpha.id()) ? HeifPictures.unpremultiply(result) : result;
    }

    /**
     * Reconstructs an identity derivation.
     *
     * @param item the {@code iden} item
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param depth the nesting depth of the item
     * @return the output image of its input
     * @throws MediaException.Malformed if the item does not have exactly one input
     */
    private Picture identity(HeifItem item, boolean wide, int depth) {
        var ids = item.references("dimg");
        if (ids.length != 1) {
            throw new MediaException.Malformed("avif: identity item " + item.id() + " has " + ids.length + " inputs");
        }
        return output(input(item, ids[0]), wide, depth + 1);
    }

    /**
     * Reconstructs an image overlay.
     *
     * @param item the {@code iovl} item
     * @param wide whether inputs of more than 8 bits keep their depth, composing at 16 bits
     * @param depth the nesting depth of the item
     * @return the composed RGBA picture: 16-bit when {@code wide} and an input has more than 8 bits
     */
    private Picture overlay(HeifItem item, boolean wide, int depth) {
        var ids = item.references("dimg");
        if (ids.length == 0) {
            throw new MediaException.Malformed("avif: overlay item " + item.id() + " has no input");
        }
        if (item.size() > 20 + 8L * ids.length) {
            throw new MediaException.Malformed("avif: overlay item " + item.id() + " holds " + item.size() + " bytes");
        }
        var overlay = HeifOverlay.parse(file.readItem(source, item, limits), ids.length);
        var width = HeifStills.checkedSize(FORMAT, overlay.outputWidth());
        var height = HeifStills.checkedSize(FORMAT, overlay.outputHeight());
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkAllocation(limits, (wide ? 12L : 4L) * width * height);
        charge((long) width * height);
        var composition = overlay.compose(width, height, wide);
        for (var i = 0; i < ids.length; i++) {
            composition.draw(output(input(item, ids[i]), wide, depth + 1), overlay.offsetsX()[i],
                    overlay.offsetsY()[i]);
        }
        return composition.result();
    }

    /**
     * Parses the expression of a sample transform item.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the {@code sato} item
     * @param limits the bounds of the payload read
     * @return the transform
     * @throws MediaException.Malformed if the item has no data, too much data, no input or more
     * than {@value HeifSampleTransform#MAX_INPUTS}, or an invalid expression
     * @throws MediaException.Unsupported if the expression has a version or a token this decoder
     * does not know
     */
    private static HeifSampleTransform sampleTransformOf(MediaSource source, HeifFile file, HeifItem item,
                                                         MediaLimits limits) {
        var inputs = item.references("dimg").length;
        if (!item.isLocated() || item.size() == 0) {
            throw new MediaException.Malformed("avif: sample transform item " + item.id() + " has no data");
        }
        if (item.size() > HeifSampleTransform.MAX_PAYLOAD) {
            throw new MediaException.Malformed("avif: sample transform item " + item.id() + " holds " + item.size()
                    + " bytes");
        }
        return HeifSampleTransform.parse(file.readItem(source, item, limits), inputs);
    }

    /**
     * Returns the bit depth of a sample transform's output.
     *
     * @param item the {@code sato} item
     * @return the depth of its {@code pixi} property
     * @throws MediaException.Malformed if the item has no {@code pixi} property or its channels
     * differ in depth
     * @throws MediaException.Unsupported if the depth is outside 8 to 16
     */
    private static int sampleTransformDepth(HeifItem item) {
        var pixi = item.property(HeifProperty.PixelInformation.class);
        if (pixi == null || pixi.depths().length == 0) {
            throw new MediaException.Malformed("avif: sample transform item " + item.id() + " has no pixi property");
        }
        var depth = pixi.depths()[0];
        for (var channel : pixi.depths()) {
            if (channel != depth) {
                throw new MediaException.Malformed("avif: the channels of sample transform item " + item.id()
                        + " differ in depth");
            }
        }
        if (depth < 8 || depth > 16) {
            throw new MediaException.Unsupported(FORMAT, "a sample transform to " + depth + " bits");
        }
        return depth;
    }

    /**
     * Reconstructs a sample transform.
     *
     * @param item the {@code sato} item
     * @param wide whether the output of more than 8 bits keeps its depth; the inputs always keep
     * theirs
     * @param depth the nesting depth of the item
     * @return the planar picture
     * @throws MediaException.Malformed if the inputs do not fit the item
     * @throws MediaException.Unsupported if an input is packed or the inputs differ in alpha
     */
    private Picture sampleTransform(HeifItem item, boolean wide, int depth) {
        var transform = sampleTransformOf(source, file, item, limits);
        var bits = sampleTransformDepth(item);
        var ids = item.references("dimg");
        var inputs = new ArrayList<Picture>(ids.length);
        for (var id : ids) {
            inputs.add(output(input(item, id), true, depth + 1));
        }
        var first = inputs.getFirst();
        var extent = item.property(HeifProperty.SpatialExtent.class);
        if (extent != null && (extent.width() != first.width() || extent.height() != first.height())) {
            throw new MediaException.Malformed("avif: sample transform item " + item.id() + " is " + extent.width()
                    + "x" + extent.height() + " over inputs of " + first.width() + "x" + first.height());
        }
        LimitChecks.checkAllocation(limits, 2L * first.width() * first.height() * (first.hasAlpha() ? 4 : 3));
        charge((long) first.width() * first.height());
        var nclx = item.property(HeifProperty.ColorNclx.class);
        var color = Colors.override(HeifPictures.colorOf(first), item);
        var result = transform.apply(List.copyOf(inputs), bits, color, nclx != null && !nclx.fullRange());
        return wide ? result : Colors.toEightBit(result);
    }
}
