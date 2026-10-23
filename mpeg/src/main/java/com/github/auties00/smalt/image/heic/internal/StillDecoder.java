package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifCanvas;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGeometry;
import com.github.auties00.smalt.internal.heif.HeifGrid;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifOverlay;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifStill;
import com.github.auties00.smalt.internal.heif.HeifStills;
import com.github.auties00.smalt.internal.heif.HeifUncompressed;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reconstructs an image item of a HEIC file: decodes its coded items, derives its derived
 * images, adds its alpha plane and applies its clean aperture.
 *
 * <p>The reconstructed image of an item (ISO/IEC 23008-12 clause 6.6) is:
 * <ul>
 *   <li>for a coded item, its decoded picture: an HEVC-coded ({@code hvc1}) or AVC-coded
 *       ({@code avc1}) item after the items it is predicted from ({@code pred} references) are
 *       decoded, a JPEG ({@code jpeg}), JPEG 2000 ({@code j2k1}) or uncompressed ({@code unci})
 *       item as {@link PictureItems} decodes it, with the alpha plane it carries itself;</li>
 *   <li>for an image grid ({@code grid}), its input images placed edge to edge and cropped to the
 *       output size. Inputs that are coded items without transformative properties, the grids of
 *       cameras, decode in parallel on the module's shared helper threads, each written straight
 *       into its region of the picture; other inputs are reconstructed one after the other. When
 *       an input is packed RGBA (an overlay or a premultiplied image), the grid is packed RGBA
 *       too, its planar inputs converted;</li>
 *   <li>for an identity derivation ({@code iden}), its input image;</li>
 *   <li>for an image overlay ({@code iovl}), its input images placed over a canvas filled with
 *       the fill color, each over the ones before it, as straight-alpha RGBA (16-bit when a
 *       source of more than 8 bits keeps its depth, 8-bit otherwise): an opaque input replaces
 *       the pixels it covers and an input with alpha is blended over them.</li>
 * </ul>
 * The input images of a derived image are the reconstructed images of the referenced items with
 * their own transformative properties applied, cropped and turned upright. An item with an alpha
 * auxiliary image ({@code auxl} reference, alpha {@code auxC} URN) gets its alpha plane from it,
 * and a grid without one gets it from the alpha auxiliary images of its tiles when every tile has
 * one. An alpha image of another size than its color image is resampled to it with
 * {@link HeifPictures#resampleAlpha}, and alpha of another bit depth is rescaled to the color
 * depth (see {@link HeifCanvas}); color premultiplied by the alpha ({@code prem} reference) is
 * divided back into a straight-alpha RGBA picture. The other auxiliary images (depth maps, mattes, gain
 * maps) are not merged; they reconstruct as items of their own.
 *
 * <p>Derived images nest at most {@value #MAX_DEPTH} deep, and the coded pictures decoded for one
 * image total at most four times the pixel limit; a file that goes beyond either is rejected, so a
 * small file cannot make the decoder loop or decode the same picture without end.
 */
public final class StillDecoder {
    /**
     * The property types this decoder implements; an item with another essential property is not
     * decodable.
     */
    static final Set<String> SUPPORTED_PROPERTIES = Set.of("ispe", "pixi", "hvcC", "avcC", "jpgC", "j2kH", "uncC",
            "cmpd", "cmpC", "cloc", "colr", "pasp", "clap", "irot", "imir", "auxC", "clli", "mdcv", "rref");

    /**
     * The types of the coded items this decoder decodes.
     */
    static final Set<String> CODED_TYPES = Set.of("hvc1", "avc1", "jpeg", "j2k1", "unci");

    /**
     * The item reference types this decoder follows when an {@code rref} property requires them.
     */
    private static final Set<String> SUPPORTED_REFERENCES = Set.of("pred", "dimg");

    /**
     * The deepest nesting of derived images followed.
     */
    static final int MAX_DEPTH = 8;

    /**
     * The format name, for messages.
     */
    private static final String FORMAT = "heic";

    /**
     * The smallest number of samples a decoded picture is charged against the decoding budget.
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
     * The samples left to decode before the file is rejected.
     */
    private final AtomicLong budget;

    /**
     * The decoders of the coded items reconstructed on the calling thread.
     */
    private final Decoders decoders;

    /**
     * The items being reconstructed, to reject a derivation that references itself.
     */
    private final Set<Integer> inProgress = new HashSet<>();

    /**
     * Constructs the reconstruction of one image.
     *
     * @param source the file
     * @param file the parsed file
     * @param limits the bounds of every item and picture
     */
    private StillDecoder(MediaSource source, HeifFile file, MediaLimits limits) {
        this.source = source;
        this.file = file;
        this.limits = limits;
        this.budget = new AtomicLong(limits.maxPixels() > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : limits.maxPixels() * 4);
        this.decoders = new Decoders(ParallelBands.THREADS);
    }

    /**
     * Reconstructs an image item and applies its clean aperture.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the item to show, usually the primary item
     * @param limits the bounds of every item and picture
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @return the image
     * @throws MediaException.Malformed if an item is corrupt or the items do not fit together
     * @throws MediaException.Unsupported if an item uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static HeifStill decode(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits,
                                boolean highBitDepth) {
        var reconstruction = new StillDecoder(source, file, limits);
        var picture = reconstruction.reconstruct(item, highBitDepth, 0);
        var width = picture.width();
        var height = picture.height();
        var geometry = HeifGeometry.resolve(item.properties(), width, height);
        if (geometry.crops(width, height)) {
            picture = HeifPictures.crop(picture, geometry.x(), geometry.y(), geometry.width(), geometry.height());
        }
        return new HeifStill(picture, width, height, geometry);
    }

    /**
     * Rejects an item with an essential property this decoder does not implement.
     *
     * @param item the item to check
     * @throws MediaException.Unsupported if such a property is associated, or an {@code rref}
     * property requires references this decoder does not follow
     */
    static void requireSupported(HeifItem item) {
        var unsupported = item.unsupportedEssential(SUPPORTED_PROPERTIES);
        if (unsupported != null) {
            throw new MediaException.Unsupported(FORMAT, "item " + item.id() + " has the essential property '"
                    + unsupported + "'");
        }
        var required = item.property(HeifProperty.RequiredReferenceTypes.class);
        if (required != null) {
            for (var type : required.referenceTypes()) {
                if (!SUPPORTED_REFERENCES.contains(type)) {
                    throw new MediaException.Unsupported(FORMAT, "item " + item.id() + " requires '" + type
                            + "' references");
                }
            }
        }
    }

    /**
     * Rejects an item this decoder cannot reconstruct: an item of another type than a coded item
     * of {@link #CODED_TYPES}, a grid, an identity derivation or an overlay, or one with an
     * essential property this decoder does not implement.
     *
     * @param item the item to check
     * @throws MediaException.Unsupported if the item cannot be reconstructed
     */
    static void requireDecodable(HeifItem item) {
        if (!CODED_TYPES.contains(item.type()) && !isDerived(item)) {
            throw new MediaException.Unsupported(FORMAT, "item " + item.id() + " is coded as '" + item.type() + "'");
        }
        requireSupported(item);
    }

    /**
     * {@return whether an item is a derived image this decoder reconstructs: a grid, an identity
     * derivation or an overlay}
     *
     * @param item the item to test
     */
    private static boolean isDerived(HeifItem item) {
        return switch (item.type()) {
            case "grid", "iden", "iovl" -> true;
            default -> false;
        };
    }

    /**
     * {@return whether an item is coded with a video codec, as NAL units}
     *
     * @param item the coded item to test
     */
    private static boolean isNal(HeifItem item) {
        return item.type().equals("hvc1") || item.type().equals("avc1");
    }

    /**
     * Returns whether a coded item carries alpha of its own: an uncompressed item with an alpha
     * component, or a JPEG 2000 item whose channel definition declares an opacity channel.
     *
     * @param item the item to test, of any type
     * @return {@code true} for such an item
     */
    static boolean hasOwnAlpha(HeifItem item) {
        if (HeifUncompressed.hasAlpha(item)) {
            return true;
        }
        var header = item.property(HeifProperty.Jpeg2000Header.class);
        return item.type().equals("j2k1") && header != null
                && header.opacity() != HeifProperty.Jpeg2000Header.NO_OPACITY;
    }

    /**
     * Returns the alpha auxiliary image of an item.
     *
     * @param file the parsed file
     * @param item the item whose alpha is wanted
     * @return the first auxiliary item with the alpha URN that is coded, a grid or an identity
     * derivation and has only supported essential properties, or {@code null}
     */
    static HeifItem alphaOf(HeifFile file, HeifItem item) {
        for (var candidate : HeifStills.alphaCandidates(file, item, SUPPORTED_PROPERTIES)) {
            if (candidate.type().equals("iden")) {
                return candidate;
            }
            if ((candidate.type().equals("grid") || CODED_TYPES.contains(candidate.type())) && candidate.isLocated()
                    && candidate.size() > 0) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns whether an item has a transformative property.
     *
     * @param item the item to test
     * @return {@code true} when a {@code clap}, {@code irot} or {@code imir} property is associated
     */
    private static boolean transforms(HeifItem item) {
        return item.property(HeifProperty.CleanAperture.class) != null
                || item.property(HeifProperty.Rotation.class) != null
                || item.property(HeifProperty.Mirror.class) != null;
    }

    /**
     * Returns whether an item is a coded item with data and without transformative properties,
     * which a grid can decode straight into its canvas.
     *
     * @param item the item, or {@code null}
     * @return {@code true} for such an item
     */
    private static boolean isPlainCoded(HeifItem item) {
        return item != null && CODED_TYPES.contains(item.type()) && item.isLocated() && item.size() > 0
                && !transforms(item);
    }

    /**
     * Reconstructs the image of an item, before its own transformative properties.
     *
     * @param item the item to reconstruct
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param depth the number of derived images being reconstructed on the way to this one
     * @return the picture: planar, or RGBA for an overlay and for premultiplied alpha
     */
    private Picture reconstruct(HeifItem item, boolean wide, int depth) {
        if (depth > MAX_DEPTH) {
            throw new MediaException.Malformed("heic: derived images are nested deeper than " + MAX_DEPTH);
        }
        if (!inProgress.add(item.id())) {
            throw new MediaException.Malformed("heic: item " + item.id() + " is derived from itself");
        }
        try {
            requireDecodable(item);
            return switch (item.type()) {
                case "grid" -> grid(item, wide, depth);
                case "iden" -> identity(item, wide, depth);
                case "iovl" -> overlay(item, wide, depth);
                default -> coded(item, wide, depth);
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
     * Reconstructs a coded item and its alpha plane.
     *
     * @param item the coded item
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param depth the nesting depth of the item
     * @return the picture
     */
    private Picture coded(HeifItem item, boolean wide, int depth) {
        var alpha = alphaOf(file, item);
        var frame = decodeCoded(decoders, item);
        var own = frame.alpha();
        var canvas = frame.canvas(limits, frame.width(), frame.height(), wide, alpha != null || own != null);
        var color = Colors.of(frame, item);
        canvas.writeColor(frame, 0, 0, canvas.width(), canvas.height(), true);
        if (own != null) {
            canvas.writeAlpha(own, 0, 0, canvas.width(), canvas.height(), false);
        }
        var premultiplied = alpha == null ? frame.premultiplied() : item.references("prem", alpha.id());
        if (alpha != null) {
            attachAlpha(canvas, alpha, depth);
        }
        var picture = canvas.toPicture(color);
        return premultiplied ? HeifPictures.unpremultiply(picture) : picture;
    }

    /**
     * Decodes a coded item, after the items it is predicted from when it is coded with a video
     * codec.
     *
     * @param decoders the decoders to use
     * @param item the coded item
     * @return the decoded picture, valid until the next call on {@code decoders}
     */
    private CodedFrame decodeCoded(Decoders decoders, HeifItem item) {
        if (!isNal(item)) {
            var frame = PictureItems.decode(source, file, item, limits);
            charge((long) frame.width() * frame.height());
            return frame;
        }
        var avc = item.type().equals("avc1");
        var chain = new ArrayList<HeifItem>();
        collectChain(item, chain, new HashSet<>(), 0);
        var configurations = new ArrayList<byte[]>(chain.size());
        var payloads = new ArrayList<byte[]>(chain.size());
        for (var coded : chain) {
            byte[] record;
            if (avc) {
                var config = coded.property(HeifProperty.AvcConfiguration.class);
                if (config == null) {
                    throw new MediaException.Malformed("heic: item " + coded.id() + " has no avcC property");
                }
                record = config.record();
            } else {
                var config = coded.property(HeifProperty.HevcConfiguration.class);
                if (config == null) {
                    throw new MediaException.Malformed("heic: item " + coded.id() + " has no hvcC property");
                }
                record = config.record();
            }
            if (!coded.isLocated()) {
                throw new MediaException.Malformed("heic: item " + coded.id() + " has no data");
            }
            configurations.add(record);
            payloads.add(file.readItem(source, coded, limits));
        }
        var frame = decoders.nal(avc).decodeItem(configurations, payloads);
        charge((long) frame.width() * frame.height() * chain.size());
        return frame;
    }

    /**
     * Holds the decoders of one thread, created when first needed.
     */
    private final class Decoders {
        /**
         * The most pictures a decoder decodes concurrently.
         */
        private final int threads;

        /**
         * The HEVC decoder, or {@code null}.
         */
        private ItemDecoder hevc;

        /**
         * The AVC decoder, or {@code null}.
         */
        private ItemDecoder avc;

        /**
         * Constructs the decoders of a thread.
         *
         * @param threads the most pictures a decoder decodes concurrently
         */
        private Decoders(int threads) {
            this.threads = threads;
        }

        /**
         * {@return the decoder of a video codec}
         *
         * @param isAvc whether the codec is AVC rather than HEVC
         */
        private ItemDecoder nal(boolean isAvc) {
            if (isAvc) {
                if (avc == null) {
                    avc = ItemDecoder.of(true, limits, threads);
                }
                return avc;
            }
            if (hevc == null) {
                hevc = ItemDecoder.of(false, limits, threads);
            }
            return hevc;
        }
    }

    /**
     * Lists a coded item after the items it is predicted from, in decoding order.
     *
     * @param item the coded item
     * @param chain receives the items
     * @param visited the items already listed or being listed
     * @param depth the length of the prediction chain so far
     * @throws MediaException.Malformed if the chain is too deep, cyclic or names an item that is
     * not coded with the codec of {@code item}
     */
    private void collectChain(HeifItem item, List<HeifItem> chain, Set<Integer> visited, int depth) {
        if (depth > MAX_DEPTH) {
            throw new MediaException.Malformed("heic: item " + item.id() + " is predicted through more than "
                    + MAX_DEPTH + " items");
        }
        if (!visited.add(item.id())) {
            throw new MediaException.Malformed("heic: item " + item.id() + " is predicted from itself");
        }
        for (var id : item.references("pred")) {
            var reference = file.item(id);
            if (reference == null || !reference.type().equals(item.type())) {
                throw new MediaException.Malformed("heic: item " + item.id() + " is predicted from item " + id
                        + ", which is not coded as '" + item.type() + "'");
            }
            if (!chain.contains(reference)) {
                collectChain(reference, chain, visited, depth + 1);
            }
        }
        chain.add(item);
    }

    /**
     * Charges decoded samples against the budget of the image.
     *
     * @param samples the samples decoded
     * @throws MediaException.LimitExceeded if the budget is exhausted
     */
    private void charge(long samples) {
        if (budget.addAndGet(-Math.max(samples, MIN_CHARGE)) < 0) {
            throw new MediaException.LimitExceeded("heic: the image decodes more than four times the picture limit");
        }
    }

    /**
     * Decodes the alpha auxiliary image of an item into the alpha plane of its canvas, resampling
     * it to the canvas size when its size differs.
     *
     * @param canvas the canvas of the item, which has an alpha plane
     * @param alpha the alpha item
     * @param depth the nesting depth of the item
     */
    private void attachAlpha(HeifCanvas canvas, HeifItem alpha, int depth) {
        if (isPlainCoded(alpha) && alpha.references("pred").length == 0) {
            var frame = decodeCoded(decoders, alpha);
            var limited = frame.limitedAlpha(alpha.property(HeifProperty.ColorNclx.class));
            if (frame.width() == canvas.width() && frame.height() == canvas.height()) {
                canvas.writeAlpha(frame, 0, 0, canvas.width(), canvas.height(), limited);
            } else {
                canvas.writeAlpha(HeifPictures.resampleAlpha(frame.lumaPicture(), canvas.width(), canvas.height()),
                        limited);
            }
            return;
        }
        var picture = output(alpha, true, depth + 1);
        var limited = switch (picture) {
            case RgbaPicture _, Rgba16Picture _ -> false;
            default -> HeifPictures.colorOf(picture).range() == ColorRange.LIMITED;
        };
        canvas.writeAlpha(HeifPictures.resampleAlpha(picture, canvas.width(), canvas.height()), limited);
    }

    /**
     * Reconstructs an image grid.
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
            throw new MediaException.Malformed("heic: grid item " + item.id() + " has " + ids.length + " inputs for "
                    + grid.tiles() + " tiles");
        }
        var width = HeifStills.checkedSize(FORMAT, grid.outputWidth());
        var height = HeifStills.checkedSize(FORMAT, grid.outputHeight());
        LimitChecks.checkPicture(limits, width, height);
        var tiles = new ArrayList<HeifItem>(ids.length);
        var plain = true;
        for (var id : ids) {
            var tile = file.item(id);
            if (tile == null) {
                throw new MediaException.Malformed("heic: input " + id + " of grid item " + item.id() + " is missing");
            }
            plain &= isPlainCoded(tile) && tile.references("pred").length == 0 && !premultiplied(tile);
            tiles.add(tile);
        }
        var alpha = alphaOf(file, item);
        Picture picture;
        if (plain) {
            picture = codedGrid(item, grid, tiles, alpha, width, height, wide, depth);
        } else {
            picture = derivedGrid(item, grid, tiles, alpha, width, height, wide, depth);
        }
        return alpha != null && item.references("prem", alpha.id()) ? HeifPictures.unpremultiply(picture) : picture;
    }

    /**
     * Reconstructs a grid of coded tiles, decoding them in parallel straight into the canvas.
     *
     * @param item the grid item
     * @param grid the parsed grid payload
     * @param tiles the coded tiles in row-major order
     * @param alpha the alpha item of the grid, or {@code null}
     * @param width the output width
     * @param height the output height
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param depth the nesting depth of the item
     * @return the picture
     */
    private Picture codedGrid(HeifItem item, HeifGrid grid, List<HeifItem> tiles, HeifItem alpha, int width, int height,
                              boolean wide, int depth) {
        for (var tile : tiles) {
            requireSupported(tile);
        }
        var extent = tiles.getFirst().property(HeifProperty.SpatialExtent.class);
        if (extent == null) {
            throw new MediaException.Malformed("heic: grid tile " + tiles.getFirst().id() + " has no ispe property");
        }
        var tileWidth = HeifStills.checkedSize(FORMAT, extent.width());
        var tileHeight = HeifStills.checkedSize(FORMAT, extent.height());
        grid.validateCoverage(tileWidth, tileHeight);
        List<HeifItem> tileAlphas = null;
        if (alpha == null) {
            var found = new ArrayList<HeifItem>(tiles.size());
            for (var tile : tiles) {
                var tileAlpha = alphaOf(file, tile);
                if (!isPlainCoded(tileAlpha)) {
                    found = null;
                    break;
                }
                found.add(tileAlpha);
            }
            tileAlphas = found;
        }
        var ownAlpha = false;
        for (var tile : tiles) {
            ownAlpha |= hasOwnAlpha(tile);
        }
        var hasAlpha = alpha != null || tileAlphas != null || ownAlpha;
        var state = new GridState();
        var perTileAlpha = tileAlphas;
        var fillOpaque = ownAlpha && tileAlphas == null;
        decodeTiles(tiles, (index, tileDecoders, frame) -> {
            if (frame.width() != tileWidth || frame.height() != tileHeight) {
                throw new MediaException.Malformed("heic: a grid tile decodes to " + frame.width() + "x"
                        + frame.height() + ", not the " + tileWidth + "x" + tileHeight + " its grid declares");
            }
            var canvas = state.canvas(frame, width, height, wide, hasAlpha);
            var x = (index % grid.columns()) * tileWidth;
            var y = (index / grid.columns()) * tileHeight;
            if ((x & ((1 << canvas.format().shiftX()) - 1)) != 0 || (y & ((1 << canvas.format().shiftY()) - 1)) != 0) {
                throw new MediaException.Malformed("heic: grid tiles of " + tileWidth + "x" + tileHeight
                        + " split the subsampled chroma");
            }
            var w = Math.min(tileWidth, width - x);
            var h = Math.min(tileHeight, height - y);
            canvas.writeColor(frame, x, y, w, h, false);
            if (index == 0) {
                state.color = Colors.of(frame, tiles.getFirst());
            }
            var own = frame.alpha();
            if (own != null && perTileAlpha == null) {
                canvas.writeAlpha(own, x, y, w, h, false);
            } else if (fillOpaque) {
                canvas.fillAlpha(x, y, w, h);
            }
            if (perTileAlpha != null) {
                var tileAlpha = perTileAlpha.get(index);
                var alphaFrame = decodeCoded(tileDecoders, tileAlpha);
                var limited = alphaFrame.limitedAlpha(tileAlpha.property(HeifProperty.ColorNclx.class));
                if (alphaFrame.width() == tileWidth && alphaFrame.height() == tileHeight) {
                    canvas.writeAlpha(alphaFrame, x, y, w, h, limited);
                } else {
                    canvas.writeAlpha(HeifPictures.resampleAlpha(alphaFrame.lumaPicture(), tileWidth, tileHeight), x,
                            y, w, h, limited);
                }
            }
        });
        var canvas = state.canvas;
        if (alpha != null) {
            attachAlpha(canvas, alpha, depth);
        }
        return canvas.toPicture(Colors.override(state.color, item));
    }

    /**
     * Holds the canvas of a grid of coded tiles, allocated by the first tile decoded.
     */
    private final class GridState {
        /**
         * Guards the allocation of the canvas.
         */
        private final Object lock = new Object();

        /**
         * The canvas, or {@code null} before the first tile is decoded.
         */
        private volatile HeifCanvas canvas;

        /**
         * The color description of the first tile.
         */
        private volatile ColorInfo color;

        /**
         * Returns the canvas, allocating it for the layout and depth of the first decoded tile.
         *
         * @param frame a decoded tile
         * @param width the output width
         * @param height the output height
         * @param wide whether a source of more than 8 bits keeps its depth
         * @param hasAlpha whether the canvas needs an alpha plane
         * @return the canvas
         * @throws MediaException.Malformed if the tile differs from the others in layout or depth
         */
        private HeifCanvas canvas(CodedFrame frame, int width, int height, boolean wide, boolean hasAlpha) {
            var current = canvas;
            if (current == null) {
                synchronized (lock) {
                    current = canvas;
                    if (current == null) {
                        current = frame.canvas(limits, width, height, wide, hasAlpha);
                        canvas = current;
                    }
                }
            }
            if (!frame.fits(current)) {
                throw new MediaException.Malformed("heic: the tiles of a grid differ in chroma layout or bit depth");
            }
            return current;
        }
    }

    /**
     * Receives one decoded tile.
     */
    @FunctionalInterface
    private interface TileSink {
        /**
         * Copies a decoded tile into the canvas.
         *
         * @param index the tile index in row-major order
         * @param decoders the decoders of the calling thread, free for further items
         * @param frame the decoded tile, valid until the next call on {@code decoders}
         */
        void accept(int index, Decoders decoders, CodedFrame frame);
    }

    /**
     * Decodes the tiles of a grid, in parallel when helper threads are free.
     *
     * @param tiles the coded tile items in row-major order
     * @param sink receives every decoded tile
     */
    private void decodeTiles(List<HeifItem> tiles, TileSink sink) {
        var count = tiles.size();
        var workers = Math.min(count, ParallelBands.enabled() ? 1 + ParallelBands.available() : 1);
        var next = new AtomicInteger();
        var tasks = new ArrayList<Callable<Void>>(workers);
        for (var w = 0; w < workers; w++) {
            tasks.add(() -> {
                var tileDecoders = new Decoders(1);
                for (var index = next.getAndIncrement(); index < count; index = next.getAndIncrement()) {
                    var frame = decodeCoded(tileDecoders, tiles.get(index));
                    sink.accept(index, tileDecoders, frame);
                    ParallelBands.checkpoint();
                }
                return null;
            });
        }
        if (workers == 1) {
            try {
                tasks.getFirst().call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return;
        }
        ParallelBands.invokeAll(tasks);
    }

    /**
     * Reconstructs a grid whose inputs are derived images or carry transformative properties, one
     * input after the other.
     *
     * <p>Planar inputs are stitched into a planar picture. When an input is reconstructed as packed
     * RGBA, an overlay or a premultiplied image, the grid is a packed straight-alpha RGBA picture
     * instead: each planar input is converted to RGBA on its own, with the grid's color properties
     * applied, each input replaces the pixels it covers, and the picture is 16-bit when
     * {@code wide} and an input has more than 8 bits.
     *
     * @param item the grid item
     * @param grid the parsed grid payload
     * @param tiles the input items in row-major order
     * @param alpha the alpha item of the grid, or {@code null}
     * @param width the output width
     * @param height the output height
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param depth the nesting depth of the item
     * @return the picture
     */
    private Picture derivedGrid(HeifItem item, HeifGrid grid, List<HeifItem> tiles, HeifItem alpha, int width,
                                int height, boolean wide, int depth) {
        var packedGrid = false;
        for (var tile : tiles) {
            packedGrid |= reconstructsPacked(tile, depth + 1);
        }
        HeifCanvas canvas = null;
        Picture packed = null;
        if (packedGrid) {
            LimitChecks.checkAllocation(limits, (wide ? 12L : 4L) * width * height);
            packed = RgbaPicture.allocate(width, height, true);
        }
        ColorInfo color = null;
        int tileWidth = 0;
        int tileHeight = 0;
        for (var index = 0; index < tiles.size(); index++) {
            var picture = output(tiles.get(index), wide, depth + 1);
            if (index == 0) {
                tileWidth = picture.width();
                tileHeight = picture.height();
                grid.validateCoverage(tileWidth, tileHeight);
                color = Colors.override(HeifPictures.colorOf(picture), item);
            } else if (picture.width() != tileWidth || picture.height() != tileHeight) {
                throw new MediaException.Malformed("heic: the inputs of grid item " + item.id() + " differ in size");
            }
            var x = (index % grid.columns()) * tileWidth;
            var y = (index / grid.columns()) * tileHeight;
            var w = Math.min(tileWidth, width - x);
            var h = Math.min(tileHeight, height - y);
            if (packed != null) {
                var input = HeifPictures.isPacked(picture) ? picture
                        : HeifPictures.withColor(picture, Colors.override(HeifPictures.colorOf(picture), item));
                packed = HeifPictures.place(packed, input, x, y, w, h);
                continue;
            }
            if (canvas == null) {
                var format = switch (picture) {
                    case YuvPicture yuv -> yuv.chromaFormat();
                    case Yuv16Picture yuv -> yuv.chromaFormat();
                    default -> throw new MediaException.Malformed("heic: grid item " + item.id()
                            + " mixes planar and packed inputs");
                };
                canvas = new HeifCanvas(FORMAT, limits, width, height, format, picture.bitDepth(),
                        picture instanceof Yuv16Picture, alpha != null);
            }
            if ((x & ((1 << canvas.format().shiftX()) - 1)) != 0 || (y & ((1 << canvas.format().shiftY()) - 1)) != 0) {
                throw new MediaException.Malformed("heic: grid inputs of " + tileWidth + "x" + tileHeight
                        + " split the subsampled chroma");
            }
            canvas.writeColor(picture, x, y, w, h);
        }
        if (canvas != null) {
            if (alpha != null) {
                attachAlpha(canvas, alpha, depth);
            }
            return canvas.toPicture(color);
        }
        if (alpha != null) {
            var deep = packed instanceof Rgba16Picture;
            var plane = new HeifCanvas(FORMAT, limits, width, height, ChromaFormat.MONOCHROME, deep ? 16 : 8, deep, true);
            attachAlpha(plane, alpha, depth);
            HeifPictures.replaceAlpha(packed, plane.toPicture(ColorInfo.JPEG));
        }
        return packed;
    }

    /**
     * Returns whether the reconstructed image of an item is packed RGBA, as {@link #reconstruct}
     * makes it: an overlay, an image whose color is premultiplied by its alpha, or an identity
     * derivation or a grid of such an image.
     *
     * @param item the item, or {@code null}
     * @param depth the nesting depth of the item
     * @return {@code true} for an item reconstructed as packed RGBA; {@code false} for a missing
     * item and beyond the nesting limit, where reconstruction fails anyway
     */
    private boolean reconstructsPacked(HeifItem item, int depth) {
        if (item == null || depth > MAX_DEPTH) {
            return false;
        }
        return switch (item.type()) {
            case "iovl" -> true;
            case "hvc1", "avc1", "jpeg", "j2k1", "unci" -> premultiplied(item);
            case "iden" -> {
                var ids = item.references("dimg");
                yield ids.length == 1 && reconstructsPacked(file.item(ids[0]), depth + 1);
            }
            case "grid" -> {
                if (premultiplied(item)) {
                    yield true;
                }
                for (var id : item.references("dimg")) {
                    if (reconstructsPacked(file.item(id), depth + 1)) {
                        yield true;
                    }
                }
                yield false;
            }
            default -> false;
        };
    }

    /**
     * {@return whether the color of an item is premultiplied by its alpha auxiliary image, or by
     * the alpha it carries itself when it has no alpha auxiliary image}
     *
     * @param item the coded item or grid
     */
    private boolean premultiplied(HeifItem item) {
        var alpha = alphaOf(file, item);
        if (alpha != null) {
            return item.references("prem", alpha.id());
        }
        var header = item.property(HeifProperty.Jpeg2000Header.class);
        return item.type().equals("j2k1") && header != null
                && header.opacity() == HeifProperty.Jpeg2000Header.PREMULTIPLIED_OPACITY;
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
            throw new MediaException.Malformed("heic: identity item " + item.id() + " has " + ids.length + " inputs");
        }
        var input = file.item(ids[0]);
        if (input == null) {
            throw new MediaException.Malformed("heic: the input of identity item " + item.id() + " is missing");
        }
        return output(input, wide, depth + 1);
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
            throw new MediaException.Malformed("heic: overlay item " + item.id() + " has no input");
        }
        if (item.size() > 20 + 8L * ids.length) {
            throw new MediaException.Malformed("heic: overlay item " + item.id() + " holds " + item.size() + " bytes");
        }
        var overlay = HeifOverlay.parse(file.readItem(source, item, limits), ids.length);
        var width = HeifStills.checkedSize(FORMAT, overlay.outputWidth());
        var height = HeifStills.checkedSize(FORMAT, overlay.outputHeight());
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkAllocation(limits, (wide ? 12L : 4L) * width * height);
        var composition = overlay.compose(width, height, wide);
        for (var i = 0; i < ids.length; i++) {
            var input = file.item(ids[i]);
            if (input == null) {
                throw new MediaException.Malformed("heic: input " + ids[i] + " of overlay item " + item.id() + " is missing");
            }
            composition.draw(output(input, wide, depth + 1), overlay.offsetsX()[i], overlay.offsetsY()[i]);
        }
        return composition.result();
    }
}
