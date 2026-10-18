package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGeometry;
import com.github.auties00.smalt.internal.heif.HeifGrid;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
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
 * Reconstructs the primary image of an AVIF file from a {@link StillPlan}: decodes its coded
 * items, stitches the tiles of its grids, adds its alpha plane and applies its clean aperture.
 *
 * <p>The tiles of a grid decode in parallel on the module's helper threads, each helper with its
 * own AV1 decoding state and each tile written straight into its region of the picture; a single
 * coded item, and a single alpha item after it, decode with the AV1 decoder's own frame-level
 * parallelism. Neither changes the samples.
 */
public final class StillDecoder {
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
     * Reconstructs the primary image.
     *
     * @param source the file
     * @param file the parsed file
     * @param plan the reconstruction plan
     * @param limits the bounds of every item and picture
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @return the image
     * @throws MediaException.Malformed if an item is corrupt or the items do not fit together
     * @throws MediaException.Unsupported if the alpha plane differs from the color in size or
     * depth
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static HeifStill decode(MediaSource source, HeifFile file, StillPlan plan, MediaLimits limits,
                                boolean highBitDepth) {
        Canvas canvas;
        SequenceHeader sequence;
        MasteringDisplay mastering;
        ContentLightLevel lightLevel;
        if (plan.grid() == null) {
            var item = plan.colorTiles().getFirst();
            var payload = file.readItem(source, item, limits);
            var decoder = new ItemDecoder(limits, true);
            var frame = decoder.decodeItem(plan.config(), payload, StillPlan.operatingPoint(item), StillPlan.layer(item));
            try {
                canvas = new Canvas(limits, frame.w, frame.h, SequenceHeader.chromaFormat(frame.layout), frame.bpc,
                        highBitDepth && frame.bpc > 8, plan.hasAlpha());
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
            LimitChecks.checkPicture(limits, width, height);
            var extent = plan.colorTiles().getFirst().property(HeifProperty.SpatialExtent.class);
            if (extent == null) {
                throw new MediaException.Malformed("avif: grid tile has no ispe property");
            }
            var tileWidth = HeifStills.checkedSize(FORMAT, extent.width());
            var tileHeight = HeifStills.checkedSize(FORMAT, extent.height());
            grid.validate(tileWidth, tileHeight, format.shiftX() > 0, format.shiftY() > 0);
            var target = new Canvas(limits, width, height, format, config.bitDepth(), highBitDepth && config.bitDepth() > 8,
                    plan.hasAlpha());
            var first = new SequenceHeader[1];
            var hdr = new Object[2];
            decodeTiles(source, file, plan.colorTiles(), limits, (index, frame) -> {
                checkTile(frame, tileWidth, tileHeight, target);
                var x = (index % grid.columns()) * tileWidth;
                var y = (index / grid.columns()) * tileHeight;
                target.writeColor(frame, x, y, Math.min(tileWidth, width - x), Math.min(tileHeight, height - y), false);
                if (index == 0) {
                    first[0] = frame.seqHdr;
                }
            }, hdr);
            canvas = target;
            sequence = first[0];
            mastering = (MasteringDisplay) hdr[0];
            lightLevel = (ContentLightLevel) hdr[1];
        }
        if (plan.hasAlpha()) {
            decodeAlpha(source, file, plan, limits, canvas);
        }
        var color = colorInfo(plan.primary(), sequence, mastering, lightLevel);
        var picture = canvas.toPicture(color);
        var geometry = HeifGeometry.resolve(plan.primary().properties(), canvas.width, canvas.height);
        if (geometry.crops(canvas.width, canvas.height)) {
            picture = HeifPictures.crop(picture, geometry.x(), geometry.y(), geometry.width(), geometry.height());
        }
        if (plan.premultiplied()) {
            picture = HeifPictures.unpremultiply(picture);
        }
        return new HeifStill(picture, canvas.width, canvas.height, geometry);
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
     * @param source the file
     * @param file the parsed file
     * @param plan the reconstruction plan
     * @param limits the bounds
     * @param canvas the picture
     */
    private static void decodeAlpha(MediaSource source, HeifFile file, StillPlan plan, MediaLimits limits, Canvas canvas) {
        var alphaGrid = plan.alphaGrid();
        if (alphaGrid == null) {
            var frame = decodeSingle(source, file, plan.alphaTiles().getFirst(), limits);
            try {
                checkAlpha(frame, canvas.width, canvas.height, canvas);
                canvas.writeAlpha(frame, 0, 0, canvas.width, canvas.height, !frame.seqHdr.colorRange);
            } finally {
                frame.release();
            }
            return;
        }
        if (HeifStills.checkedSize(FORMAT, alphaGrid.outputWidth()) != canvas.width || HeifStills.checkedSize(FORMAT, alphaGrid.outputHeight()) != canvas.height) {
            throw new MediaException.Unsupported("avif", "the alpha grid is " + alphaGrid.outputWidth() + "x"
                    + alphaGrid.outputHeight() + ", not the " + canvas.width + "x" + canvas.height + " of the color");
        }
        var extent = plan.alphaTiles().getFirst().property(HeifProperty.SpatialExtent.class);
        if (extent == null) {
            throw new MediaException.Malformed("avif: alpha grid tile has no ispe property");
        }
        var tileWidth = HeifStills.checkedSize(FORMAT, extent.width());
        var tileHeight = HeifStills.checkedSize(FORMAT, extent.height());
        alphaGrid.validate(tileWidth, tileHeight, false, false);
        decodeTiles(source, file, plan.alphaTiles(), limits, (index, frame) -> {
            checkAlpha(frame, tileWidth, tileHeight, canvas);
            var x = (index % alphaGrid.columns()) * tileWidth;
            var y = (index / alphaGrid.columns()) * tileHeight;
            canvas.writeAlpha(frame, x, y, Math.min(tileWidth, canvas.width - x), Math.min(tileHeight, canvas.height - y),
                    !frame.seqHdr.colorRange);
        }, new Object[2]);
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
    private static Av1Picture decodeSingle(MediaSource source, HeifFile file,
                                                                                     HeifItem item, MediaLimits limits) {
        var payload = file.readItem(source, item, limits);
        return new ItemDecoder(limits, true).decodeItem(item.property(HeifProperty.Av1Configuration.class), payload,
                StillPlan.operatingPoint(item), StillPlan.layer(item));
    }

    /**
     * Checks that a decoded alpha frame has the expected size and the color's bit depth.
     *
     * @param frame the decoded alpha frame
     * @param width the expected width
     * @param height the expected height
     * @param canvas the picture
     * @throws MediaException.Unsupported if the alpha plane would need resampling
     */
    private static void checkAlpha(Av1Picture frame, int width, int height,
                                   Canvas canvas) {
        if (frame.w != width || frame.h != height || frame.bpc != canvas.bitDepth) {
            // TODO: resample an alpha plane whose size differs from the color, as HEIF 6.9.1 allows
            throw new MediaException.Unsupported("avif", "the alpha plane is " + frame.w + "x" + frame.h + " at "
                    + frame.bpc + " bits, not " + width + "x" + height + " at " + canvas.bitDepth + " bits");
        }
    }

    /**
     * Decodes the tiles of a grid, in parallel when helper threads are free.
     *
     * @param source the file
     * @param file the parsed file
     * @param tiles the coded tile items in row-major order
     * @param limits the bounds
     * @param sink receives every decoded tile
     * @param hdr receives the mastering display and the light level of the metadata OBUs of the
     * first tile, at indexes 0 and 1
     */
    private static void decodeTiles(MediaSource source, HeifFile file, List<HeifItem> tiles, MediaLimits limits,
                                    TileSink sink, Object[] hdr) {
        var count = tiles.size();
        var workers = Math.min(count, ParallelBands.enabled() ? 1 + ParallelBands.available() : 1);
        var next = new AtomicInteger();
        var tasks = new ArrayList<Callable<Void>>(workers);
        for (var w = 0; w < workers; w++) {
            tasks.add(() -> {
                var decoder = new ItemDecoder(limits, true);
                for (var index = next.getAndIncrement(); index < count; index = next.getAndIncrement()) {
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
