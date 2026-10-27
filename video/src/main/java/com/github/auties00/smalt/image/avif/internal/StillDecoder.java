package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGeometry;
import com.github.auties00.smalt.internal.heif.HeifGrid;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifRegions;
import com.github.auties00.smalt.internal.heif.HeifStill;
import com.github.auties00.smalt.internal.heif.HeifStills;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reconstructs a coded image item of an AVIF file from a {@link StillPlan}: decodes its coded
 * items, stitches the tiles of its grids, adds its alpha plane and applies its clean aperture.
 *
 * <p>The tiles of a grid decode in parallel on the module's helper threads, each helper with its
 * own AV1 decoding state and each tile written straight into its region of the picture; a single
 * coded item, and a single alpha item after it, decode with the AV1 decoder's own frame-level
 * parallelism. Neither changes the samples. Alpha of another size or bit depth than the color
 * is resampled and rescaled to it.
 */
final class StillDecoder {
    /**
     * The format name of failure messages.
     */
    private static final String FORMAT = "avif";

    /**
     * Receives one decoded tile.
     */
    @FunctionalInterface
    private interface TileSink {
        /**
         * Copies a decoded tile into the picture.
         *
         * @param index the tile index in row-major order
         * @param frame the decoded frame, released by the caller after this returns
         */
        void accept(int index, Av1Picture frame);
    }

    /**
     * Prevents instantiation.
     */
    private StillDecoder() {
        throw new AssertionError();
    }

    /**
     * Reconstructs a coded image item and applies its clean aperture.
     *
     * @param source the file
     * @param file the parsed file
     * @param plan the reconstruction plan
     * @param limits the bounds of every item and picture
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @return the image
     * @throws MediaException.Malformed if an item is corrupt or the items do not fit together
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    static HeifStill decode(MediaSource source, HeifFile file, StillPlan plan, MediaLimits limits,
                            boolean highBitDepth) {
        return decode(source, file, plan, limits, highBitDepth, true, null);
    }

    /**
     * Reconstructs a region of a coded image item after its clean aperture.
     *
     * <p>The picture equals the region of the picture {@link #decode(MediaSource, HeifFile, StillPlan, MediaLimits,
     * boolean)} returns, as {@link ImageOps#crop(Picture, Region)} crops it. A grid decodes only the grid images
     * that intersect the region, and the alpha images that cover them, and the limits bound those rather than the
     * whole grid; a single coded item decodes whole and is cropped.
     *
     * @param source the file
     * @param file the parsed file
     * @param plan the reconstruction plan
     * @param limits the bounds of every item and picture
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @param region the region, inside the image after the clean aperture
     * @return the image, whose picture holds the region
     * @throws MediaException.Malformed if an item is corrupt or the items do not fit together
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    static HeifStill decode(MediaSource source, HeifFile file, StillPlan plan, MediaLimits limits,
                            boolean highBitDepth, Region region) {
        return decode(source, file, plan, limits, highBitDepth, true, region);
    }

    /**
     * Reconstructs a coded image item before its transformative properties, as a derived image
     * takes it as an input.
     *
     * @param source the file
     * @param file the parsed file
     * @param plan the reconstruction plan
     * @param limits the bounds of every item and picture
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @return the picture, uncropped
     * @throws MediaException.Malformed if an item is corrupt or the items do not fit together
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    static Picture reconstruct(MediaSource source, HeifFile file, StillPlan plan, MediaLimits limits,
                               boolean highBitDepth) {
        return decode(source, file, plan, limits, highBitDepth, false, null).picture();
    }

    /**
     * Reconstructs a coded image item, optionally applying its clean aperture.
     *
     * @param source the file
     * @param file the parsed file
     * @param plan the reconstruction plan
     * @param limits the bounds of every item and picture
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @param crop whether to apply the clean aperture
     * @param region the region to decode, inside the image after the clean aperture, or {@code null} for the whole
     * image
     * @return the image
     */
    private static HeifStill decode(MediaSource source, HeifFile file, StillPlan plan, MediaLimits limits,
                                    boolean highBitDepth, boolean crop, Region region) {
        Canvas canvas;
        HeifRegions.Window window = null;
        var fullWidth = 0;
        var fullHeight = 0;
        SequenceHeader sequence;
        MasteringDisplay mastering;
        ContentLightLevel lightLevel;
        if (plan.grid() == null) {
            var item = plan.colorTiles().getFirst();
            var payload = file.readItem(source, item, limits);
            var decoder = new ItemDecoder(limits, true);
            var frame = decoder.decodeItem(plan.config(), payload, StillPlan.operatingPoint(item), StillPlan.layer(item));
            try {
                checkExtent(item, frame);
                var matrix = colorInfo(plan.item(), frame.seqHdr, null, null).matrix();
                canvas = new Canvas(limits, frame.w, frame.h, SequenceHeader.chromaFormat(frame.layout), frame.bpc,
                        frame.bpc > 8 && (highBitDepth || Colors.reducesToRgb(matrix, frame.bpc)), plan.hasAlpha());
                canvas.writeColor(frame, 0, 0, frame.w, frame.h, true);
                sequence = frame.seqHdr;
            } finally {
                frame.release();
            }
            mastering = decoder.masteringDisplay();
            lightLevel = decoder.contentLightLevel();
        } else {
            var grid = plan.grid();
            var config = plan.config();
            var format = config.monochrome() ? ChromaFormat.MONOCHROME
                    : config.subsamplingX() ? (config.subsamplingY() ? ChromaFormat.YUV420 : ChromaFormat.YUV422)
                    : ChromaFormat.YUV444;
            var width = HeifStills.checkedSize(FORMAT, grid.outputWidth());
            var height = HeifStills.checkedSize(FORMAT, grid.outputHeight());
            fullWidth = width;
            fullHeight = height;
            if (region == null) {
                LimitChecks.checkPicture(limits, width, height);
            }
            var extent = plan.colorTiles().getFirst().property(HeifProperty.SpatialExtent.class);
            if (extent == null) {
                throw new MediaException.Malformed("avif: grid tile has no ispe property");
            }
            var tileWidth = HeifStills.checkedSize(FORMAT, extent.width());
            var tileHeight = HeifStills.checkedSize(FORMAT, extent.height());
            grid.validate(tileWidth, tileHeight, format.shiftX() > 0, format.shiftY() > 0);
            var area = region == null ? new int[]{0, 0, width, height}
                    : HeifRegions.canvasArea(region, HeifGeometry.resolve(plan.item().properties(), width, height),
                    width, height);
            window = HeifRegions.window(area, width, height, tileWidth, tileHeight);
            LimitChecks.checkPicture(limits, window.width(), window.height());
            var depth = config.bitDepth();
            var nclx = plan.item().property(HeifProperty.ColorNclx.class);
            var wide = depth > 8 && (highBitDepth
                    || nclx != null && Colors.reducesToRgb(MatrixCoefficients.of(nclx.matrix()), depth));
            var first = new SequenceHeader[1];
            var hdr = new Object[2];
            canvas = decodeGrid(source, file, plan, limits, format, width, height, tileWidth, tileHeight, wide, first,
                    hdr, window);
            if (!wide && nclx == null
                    && Colors.reducesToRgb(colorInfo(plan.item(), first[0], null, null).matrix(), depth)) {
                // without an nclx box the matrix comes from the tiles, known only once they are decoded
                canvas = decodeGrid(source, file, plan, limits, format, width, height, tileWidth, tileHeight, true,
                        first, hdr, window);
            }
            sequence = first[0];
            mastering = (MasteringDisplay) hdr[0];
            lightLevel = (ContentLightLevel) hdr[1];
        }
        if (window == null) {
            fullWidth = canvas.width;
            fullHeight = canvas.height;
        }
        if (plan.hasAlpha()) {
            decodeAlpha(source, file, plan, limits, canvas, window, fullWidth, fullHeight);
        }
        var color = colorInfo(plan.item(), sequence, mastering, lightLevel);
        var picture = canvas.toPicture(color);
        var geometry = HeifGeometry.resolve(plan.item().properties(), fullWidth, fullHeight);
        if (region != null) {
            picture = HeifRegions.crop(picture, window == null ? 0 : window.x(), window == null ? 0 : window.y(),
                    geometry, fullWidth, fullHeight, region);
        } else if (crop && geometry.crops(fullWidth, fullHeight)) {
            picture = HeifPictures.crop(picture, geometry.x(), geometry.y(), geometry.width(), geometry.height());
        }
        if (plan.premultiplied()) {
            picture = HeifPictures.unpremultiply(picture);
        }
        if (!highBitDepth) {
            picture = Colors.toEightBit(picture);
        }
        return new HeifStill(picture, fullWidth, fullHeight, geometry);
    }

    /**
     * Decodes the color tiles of a grid into a new canvas.
     *
     * @param source the file
     * @param file the parsed file
     * @param plan the reconstruction plan, with a grid
     * @param limits the bounds of every item and picture
     * @param format the chroma layout of the tiles
     * @param width the output width of the grid
     * @param height the output height of the grid
     * @param tileWidth the width of every tile
     * @param tileHeight the height of every tile
     * @param wide whether the canvas keeps a source depth above 8 bits
     * @param first receives the sequence header of the first tile
     * @param hdr receives the mastering display and content light level of the tiles
     * @param window the grid images to decode
     * @return the canvas of the window
     * @throws MediaException.Malformed if a tile is corrupt or does not match the grid
     * @throws MediaException.LimitExceeded if the canvas exceeds the limits
     */
    private static Canvas decodeGrid(MediaSource source, HeifFile file, StillPlan plan, MediaLimits limits,
                                     ChromaFormat format, int width, int height, int tileWidth, int tileHeight,
                                     boolean wide, SequenceHeader[] first, Object[] hdr, HeifRegions.Window window) {
        var grid = plan.grid();
        var target = new Canvas(limits, window.width(), window.height(), format, plan.config().bitDepth(), wide,
                plan.hasAlpha());
        // the first grid image supplies the sequence header and the HDR metadata, so it decodes even outside
        decodeTiles(source, file, plan.colorTiles(), indices(plan.colorTiles().size(), grid.columns(), window, true),
                limits, (index, frame) -> {
                    checkTile(frame, tileWidth, tileHeight, target);
                    if (index == 0) {
                        first[0] = frame.seqHdr;
                    }
                    if (!window.holds(index, grid.columns())) {
                        return;
                    }
                    var x = (index % grid.columns()) * tileWidth;
                    var y = (index / grid.columns()) * tileHeight;
                    target.writeColor(frame, x - window.x(), y - window.y(), Math.min(tileWidth, width - x),
                            Math.min(tileHeight, height - y), false);
                }, hdr);
        return target;
    }

    /**
     * Lists the grid images of a window, in row-major order.
     *
     * @param count the number of grid images
     * @param columns the number of grid columns
     * @param window the grid images to list
     * @param first whether the first grid image is listed whether or not the window holds it
     * @return the indices
     */
    private static List<Integer> indices(int count, int columns, HeifRegions.Window window, boolean first) {
        var indices = new ArrayList<Integer>();
        for (var index = 0; index < count; index++) {
            if ((first && index == 0) || window.holds(index, columns)) {
                indices.add(index);
            }
        }
        return indices;
    }

    /**
     * Checks that a coded item of a single layer decodes to the size its {@code ispe} property declares, the size a
     * region of it is taken from, as a grid tile must ({@link #checkTile}). An item that selects an operating point
     * or a layer may decode to a layer smaller than its {@code ispe}, and an item without the property declares
     * nothing; neither is checked.
     *
     * @param item the coded item
     * @param frame the decoded picture
     * @throws MediaException.Malformed if the sizes differ
     */
    private static void checkExtent(HeifItem item, Av1Picture frame) {
        var extent = item.property(HeifProperty.SpatialExtent.class);
        if (extent == null || item.property(HeifProperty.OperatingPointSelector.class) != null
                || item.property(HeifProperty.LayerSelector.class) != null) {
            return;
        }
        if (frame.w != extent.width() || frame.h != extent.height()) {
            throw new MediaException.Malformed("avif: item " + item.id() + " decodes to " + frame.w + "x" + frame.h
                    + ", not the " + extent.width() + "x" + extent.height() + " its ispe property declares");
        }
    }

    /**
     * Checks that a decoded tile matches the tile size and the picture's layout and depth.
     *
     * @param frame the decoded tile
     * @param tileWidth the tile width
     * @param tileHeight the tile height
     * @param canvas the picture
     */
    private static void checkTile(Av1Picture frame, int tileWidth, int tileHeight,
                                  Canvas canvas) {
        if (frame.w != tileWidth || frame.h != tileHeight || frame.bpc != canvas.bitDepth
                || SequenceHeader.chromaFormat(frame.layout) != canvas.format) {
            throw new MediaException.Malformed("avif: a grid tile decodes to " + frame.w + "x" + frame.h + " at "
                    + frame.bpc + " bits, not the " + tileWidth + "x" + tileHeight + " tile its grid declares");
        }
    }

    /**
     * Decodes the alpha items into the alpha plane of a picture.
     *
     * <p>Alpha of another bit depth than the color is rescaled to it, and an alpha image of
     * another size than the region it covers (the picture for one alpha item or an alpha grid, a
     * color tile for an alpha item per tile) is resampled to that size with
     * {@link HeifPictures#resampleAlpha}.
     *
     * <p>With a window of a grid, only the alpha images that cover the window decode, except that a single alpha
     * item, and an alpha grid of another size than the color, decode whole and are resampled to the size of the
     * color grid before the window is cut out of them.
     *
     * @param source the file
     * @param file the parsed file
     * @param plan the reconstruction plan
     * @param limits the bounds
     * @param canvas the picture, of the window when there is one
     * @param window the window of the color grid the canvas holds, or {@code null} when it holds the whole image
     * @param width the width of the whole image
     * @param height the height of the whole image
     */
    private static void decodeAlpha(MediaSource source, HeifFile file, StillPlan plan, MediaLimits limits,
                                    Canvas canvas, HeifRegions.Window window, int width, int height) {
        var partial = window != null && (window.width() != width || window.height() != height);
        var alphaGrid = plan.alphaGrid();
        if (alphaGrid == null) {
            var frame = decodeSingle(source, file, plan.alphaTiles().getFirst(), limits);
            try {
                if (partial) {
                    writeAlphaWindow(canvas, frame, window, width, height, limits);
                } else {
                    writeAlpha(canvas, frame, 0, 0, canvas.width, canvas.height);
                }
            } finally {
                frame.release();
            }
            return;
        }
        if (plan.alphaItem() == null) {
            var extent = plan.colorTiles().getFirst().property(HeifProperty.SpatialExtent.class);
            var tileWidth = HeifStills.checkedSize(FORMAT, extent.width());
            var tileHeight = HeifStills.checkedSize(FORMAT, extent.height());
            var all = window == null ? HeifRegions.window(new int[]{0, 0, width, height}, width, height, tileWidth,
                    tileHeight) : window;
            decodeTiles(source, file, plan.alphaTiles(), indices(plan.alphaTiles().size(), alphaGrid.columns(), all,
                    false), limits, (index, frame) -> {
                var x = (index % alphaGrid.columns()) * tileWidth;
                var y = (index / alphaGrid.columns()) * tileHeight;
                writeAlpha(canvas, frame, x - all.x(), y - all.y(), tileWidth, tileHeight);
            }, new Object[2]);
            return;
        }
        var alphaWidth = HeifStills.checkedSize(FORMAT, alphaGrid.outputWidth());
        var alphaHeight = HeifStills.checkedSize(FORMAT, alphaGrid.outputHeight());
        var extent = plan.alphaTiles().getFirst().property(HeifProperty.SpatialExtent.class);
        if (extent == null) {
            throw new MediaException.Malformed("avif: alpha grid tile has no ispe property");
        }
        var tileWidth = HeifStills.checkedSize(FORMAT, extent.width());
        var tileHeight = HeifStills.checkedSize(FORMAT, extent.height());
        alphaGrid.validate(tileWidth, tileHeight, false, false);
        if (alphaWidth == width && alphaHeight == height) {
            var left = window == null ? 0 : window.x();
            var top = window == null ? 0 : window.y();
            var right = left + canvas.width;
            var bottom = top + canvas.height;
            var covering = HeifRegions.window(new int[]{left, top, right, bottom}, width, height, tileWidth,
                    tileHeight);
            decodeTiles(source, file, plan.alphaTiles(), indices(plan.alphaTiles().size(), alphaGrid.columns(),
                    covering, false), limits, (index, frame) -> {
                checkAlphaTile(frame, tileWidth, tileHeight);
                var x = (index % alphaGrid.columns()) * tileWidth;
                var y = (index / alphaGrid.columns()) * tileHeight;
                var x0 = Math.max(x, left);
                var y0 = Math.max(y, top);
                var x1 = Math.min(Math.min(x + tileWidth, width), right);
                var y1 = Math.min(Math.min(y + tileHeight, height), bottom);
                if (x0 < x1 && y0 < y1) {
                    canvas.writeAlpha(frame, x0 - x, y0 - y, x0 - left, y0 - top, x1 - x0, y1 - y0,
                            !frame.seqHdr.colorRange);
                }
            }, new Object[2]);
            return;
        }
        var config = plan.alphaTiles().getFirst().property(HeifProperty.Av1Configuration.class);
        var depth = config.bitDepth();
        var plane = new Canvas(limits, alphaWidth, alphaHeight, ChromaFormat.MONOCHROME, depth, depth > 8, false);
        var limited = new boolean[1];
        decodeTiles(source, file, plan.alphaTiles(), indices(plan.alphaTiles().size(), alphaGrid.columns(),
                HeifRegions.window(new int[]{0, 0, alphaWidth, alphaHeight}, alphaWidth, alphaHeight, tileWidth,
                        tileHeight), false), limits, (index, frame) -> {
            checkAlphaTile(frame, tileWidth, tileHeight);
            if (frame.bpc != depth) {
                throw new MediaException.Malformed("avif: an alpha grid tile decodes at " + frame.bpc + " bits, not the "
                        + depth + " bits of its configuration");
            }
            var x = (index % alphaGrid.columns()) * tileWidth;
            var y = (index / alphaGrid.columns()) * tileHeight;
            plane.writeColor(frame, x, y, Math.min(tileWidth, alphaWidth - x), Math.min(tileHeight, alphaHeight - y),
                    false);
            if (index == 0) {
                limited[0] = !frame.seqHdr.colorRange;
            }
        }, new Object[2]);
        if (partial) {
            // the alpha is resampled to the size of the whole image, which the limits then bound
            checkResampled(limits, width, height);
        }
        var resampled = HeifPictures.resampleAlpha(plane.toPicture(ColorInfo.JPEG), width, height);
        if (partial) {
            resampled = ImageOps.crop(resampled, window.x(), window.y(), canvas.width, canvas.height);
        }
        canvas.writeAlpha(resampled, 0, 0, canvas.width, canvas.height, limited[0]);
    }

    /**
     * Checks an alpha plane resampled to the size of the whole image against the limits, before a window is cut out
     * of it.
     *
     * @param limits the bounds
     * @param width the width of the whole image
     * @param height the height of the whole image
     * @throws MediaException.LimitExceeded if the plane exceeds the limits
     */
    private static void checkResampled(MediaLimits limits, int width, int height) {
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkAllocation(limits, (long) width * height * 2);
    }

    /**
     * Writes the part of a decoded single alpha frame covering a window of the color grid, resampling the frame to
     * the size of the whole image first when its size differs.
     *
     * @param canvas the picture of the window
     * @param frame the decoded alpha frame
     * @param window the grid images of the color the canvas holds
     * @param width the width of the whole image
     * @param height the height of the whole image
     * @param limits the bounds of the resampled alpha plane
     * @throws MediaException.LimitExceeded if the alpha plane resampled to the whole image exceeds the limits
     */
    private static void writeAlphaWindow(Canvas canvas, Av1Picture frame, HeifRegions.Window window, int width,
                                         int height, MediaLimits limits) {
        var limited = !frame.seqHdr.colorRange;
        if (frame.w == width && frame.h == height) {
            canvas.writeAlpha(frame, window.x(), window.y(), 0, 0, canvas.width, canvas.height, limited);
            return;
        }
        checkResampled(limits, width, height);
        var luma = HeifPictures.lumaPicture(frame.data[0], 0, frame.stride[0], frame.w, frame.h, frame.bpc);
        var resampled = HeifPictures.resampleAlpha(luma, width, height);
        canvas.writeAlpha(ImageOps.crop(resampled, window.x(), window.y(), canvas.width, canvas.height), 0, 0,
                canvas.width, canvas.height, limited);
    }

    /**
     * Writes a decoded alpha frame over a region of the picture, resampling it to the region
     * size when its size differs.
     *
     * @param canvas the picture
     * @param frame the decoded alpha frame
     * @param x the left edge of the region
     * @param y the top edge of the region
     * @param width the region width before the picture edge crops it
     * @param height the region height before the picture edge crops it
     */
    private static void writeAlpha(Canvas canvas, Av1Picture frame, int x, int y, int width, int height) {
        var w = Math.min(width, canvas.width - x);
        var h = Math.min(height, canvas.height - y);
        var limited = !frame.seqHdr.colorRange;
        if (frame.w == width && frame.h == height) {
            canvas.writeAlpha(frame, x, y, w, h, limited);
            return;
        }
        var luma = HeifPictures.lumaPicture(frame.data[0], 0, frame.stride[0], frame.w, frame.h, frame.bpc);
        canvas.writeAlpha(HeifPictures.resampleAlpha(luma, width, height), x, y, w, h, limited);
    }

    /**
     * Decodes one coded item on its own decoder.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the coded item
     * @param limits the bounds
     * @return the decoded frame, which the caller releases
     */
    private static Av1Picture decodeSingle(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits) {
        var payload = file.readItem(source, item, limits);
        return new ItemDecoder(limits, true).decodeItem(item.property(HeifProperty.Av1Configuration.class), payload,
                StillPlan.operatingPoint(item), StillPlan.layer(item));
    }

    /**
     * Checks that a decoded tile of an alpha grid has the tile size its grid declares.
     *
     * @param frame the decoded alpha tile
     * @param width the tile width
     * @param height the tile height
     * @throws MediaException.Malformed if the sizes differ
     */
    private static void checkAlphaTile(Av1Picture frame, int width, int height) {
        if (frame.w != width || frame.h != height) {
            throw new MediaException.Malformed("avif: an alpha grid tile decodes to " + frame.w + "x" + frame.h
                    + ", not the " + width + "x" + height + " tile its grid declares");
        }
    }

    /**
     * Decodes the tiles of a grid, in parallel when helper threads are free.
     *
     * @param source the file
     * @param file the parsed file
     * @param tiles the coded tile items in row-major order
     * @param indices the indices of the tiles to decode
     * @param limits the bounds
     * @param sink receives every decoded tile
     * @param hdr receives the mastering display and the light level of the metadata OBUs of the
     * first tile, at indexes 0 and 1
     */
    private static void decodeTiles(MediaSource source, HeifFile file, List<HeifItem> tiles, List<Integer> indices,
                                    MediaLimits limits, TileSink sink, Object[] hdr) {
        var count = indices.size();
        var workers = Math.min(count, ParallelBands.enabled() ? 1 + ParallelBands.available() : 1);
        var next = new AtomicInteger();
        var tasks = new ArrayList<Callable<Void>>(workers);
        for (var w = 0; w < workers; w++) {
            tasks.add(() -> {
                var decoder = new ItemDecoder(limits, true);
                for (var k = next.getAndIncrement(); k < count; k = next.getAndIncrement()) {
                    var index = (int) indices.get(k);
                    var item = tiles.get(index);
                    var payload = file.readItem(source, item, limits);
                    var frame = decoder.decodeItem(item.property(HeifProperty.Av1Configuration.class), payload,
                            StillPlan.operatingPoint(item), StillPlan.layer(item));
                    try {
                        sink.accept(index, frame);
                    } finally {
                        frame.release();
                    }
                    if (index == 0) {
                        hdr[0] = decoder.masteringDisplay();
                        hdr[1] = decoder.contentLightLevel();
                    }
                    ParallelBands.checkpoint();
                }
                decoder.close();
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
     * Builds the color description of the image.
     *
     * <p>The {@code nclx} color box of the primary item takes precedence over the sequence
     * header of the first coded item (ISO/IEC 14496-12 clause 12.1.5.1); the {@code clli} and
     * {@code mdcv} properties take precedence over the metadata OBUs.
     *
     * @param primary the primary item
     * @param sequence the sequence header of the first color item
     * @param mastering the mastering display of the metadata OBUs, or {@code null}
     * @param lightLevel the light level of the metadata OBUs, or {@code null}
     * @return the description
     */
    static ColorInfo colorInfo(HeifItem primary, SequenceHeader sequence, MasteringDisplay mastering,
                               ContentLightLevel lightLevel) {
        var nclx = primary.property(HeifProperty.ColorNclx.class);
        var lightProperty = primary.property(HeifProperty.ContentLightLevel.class);
        if (lightProperty != null) {
            lightLevel = new ContentLightLevel(lightProperty.maxContentLightLevel(),
                    lightProperty.maxPictureAverageLightLevel());
        }
        var masteringProperty = primary.property(HeifProperty.MasteringDisplay.class);
        if (masteringProperty != null) {
            mastering = HeifPictures.masteringDisplay(masteringProperty);
        }
        return Colors.colorInfo(nclx, sequence, mastering, lightLevel);
    }
}
