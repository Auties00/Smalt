/* Ports libavif (src/write.c: avifEncoderAddImage, avifEncoderFinish and avifSetTileConfiguration). Copyright
   2019 Joe Drago; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.avif.AvifEncoderOptions;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.internal.heif.EncodeImage;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifWriter;
import com.github.auties00.smalt.video.av1.internal.DequantTables;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.encoder.StillFrameEncoder;
import com.github.auties00.smalt.video.av1.internal.encoder.StillSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes a still picture as an AVIF file: prepares its samples, codes the color and alpha
 * images (or the images of a grid) with the intra-only AV1 encoder and lays out the file's items,
 * properties and references.
 *
 * @implNote This implementation follows libavif's {@code avifEncoderAddImage} and
 * {@code avifEncoderFinish} ({@code src/write.c}, BSD-2): the quality to quantizer
 * mapping {@code ((100 - quality) * 63 + 50) / 100}, the property order of each item,
 * and the {@code auxl}, {@code prem}, {@code dimg} and {@code cdsc} references.
 */
public final class AvifStillEncoder {
    /**
     * The side of grid images for pictures larger than {@link #GRID_THRESHOLD}.
     */
    static final int AUTO_GRID_TILE = 4096;

    /**
     * The largest side coded as a single image when the options leave the grid automatic.
     */
    static final int GRID_THRESHOLD = 8192;

    /**
     * Prevents instantiation.
     */
    private AvifStillEncoder() {
        throw new AssertionError();
    }

    /**
     * One coded AV1 image: its OBUs and its codec configuration record.
     *
     * @param data the sequence header OBU and frame OBU
     * @param av1C the codec configuration record
     * @param profile the AV1 profile
     * @param level the {@code seq_level_idx}
     */
    record Coded(byte[] data, byte[] av1C, int profile, int level) {
    }

    /**
     * Returns the automatic tiling of an image: as many tiles of at least 512 by 512 samples as
     * fit, at most eight, split into more columns than rows for landscape images.
     *
     * @implNote This implementation follows libavif's {@code avifSetTileConfiguration} without
     * its cap by the thread count, so the coded image does not depend on the threads.
     * @param width the image width
     * @param height the image height
     * @return the base-2 logarithm of the tile columns in bits 8 and up, of the tile rows in the
     * low byte
     */
    static int tiling(int width, int height) {
        var minArea = 512L * 512;
        var tiles = Math.min(8, (int) (((long) width * height + minArea - 1) / minArea));
        var log2 = 31 - Integer.numberOfLeadingZeros(tiles);
        int cols;
        int rows;
        if (width >= height) {
            rows = log2 / 2;
            cols = log2 - rows;
        } else {
            cols = log2 / 2;
            rows = log2 - cols;
        }
        return cols << 8 | rows;
    }

    /**
     * Codes one prepared image as an AV1 key frame.
     *
     * @param image the prepared image, or one tile or the alpha image of it
     * @param quality the quality, 0 to 100
     * @param options the encoder settings
     * @param sequence whether the image is a frame of an image sequence, coded with a full
     * sequence header instead of the reduced still picture header
     * @return the coded image
     */
    static Coded code(EncodeImage image, int quality, AvifEncoderOptions options, boolean sequence) {
        var tiles = tiling(image.width(), image.height());
        var settings = StillSettings.of(DequantTables.qualityToQindex(quality), options.speed())
                .tiles(options.tileColumnsLog2().orElse(tiles >> 8), options.tileRowsLog2().orElse(tiles & 0xFF))
                .threads(options.threads()).reducedHeader(!sequence);
        try (var encoder = new StillFrameEncoder(image.width(), image.height(), SequenceHeader.layoutOf(image.format()),
                image.bitDepth(), image.color(), settings, options.limits())) {
            var strides = new int[]{image.width(), image.chromaWidth(), image.chromaWidth()};
            var data = encoder.encode(image.planes(), strides);
            var seq = encoder.sequenceHeader();
            return new Coded(data, encoder.codecConfigurationRecord(), seq.profile,
                    (seq.majorLevel[0] - 2) * 4 + seq.minorLevel[0]);
        }
    }

    /**
     * Encodes a picture as an AVIF file.
     *
     * @param picture the picture, in any representation
     * @param options the encoder settings
     * @return the file
     */
    public static byte[] encode(Picture picture, AvifEncoderOptions options) {
        return encode(picture, options, null, null);
    }

    /**
     * Encodes a picture as an AVIF file, with a gain map.
     *
     * <p>The gain map is a hidden {@code av01} item, monochrome for a gray gain map image and 4:4:4 with the
     * identity matrix otherwise, both full range, with the orientation of the color item; a {@code tmap} item
     * with the gain map metadata and the {@code ispe} and alternate color properties, derived from the color item
     * and the gain map item in that order, is preferred to the color item by an {@code altr} group, and the file
     * gains the {@code tmap} brand. The color item stays the primary item, so that readers without gain map
     * support show the base image.
     *
     * @param picture the picture, in any representation
     * @param options the encoder settings of the picture
     * @param gainMap the gain map, or {@code null} for none
     * @param gainMapOptions the encoder settings of the gain map image, or {@code null} without a gain map
     * @return the file
     */
    public static byte[] encode(Picture picture, AvifEncoderOptions options, GainMap gainMap,
                                AvifEncoderOptions gainMapOptions) {
        var image = EncodeImage.of("avif", picture, options.chromaFormat().orElse(null), options.bitDepth().orElse(0),
                options.color(), options.isLossless(), options.premultipliedAlpha());
        var tileSize = options.gridTileSize().orElse(
                image.width() > GRID_THRESHOLD || image.height() > GRID_THRESHOLD ? AUTO_GRID_TILE : 0);
        var colorImages = new ArrayList<Coded>();
        var alphaImages = new ArrayList<Coded>();
        var cols = 1;
        var rows = 1;
        if (tileSize > 0) {
            cols = (image.width() + tileSize - 1) / tileSize;
            rows = (image.height() + tileSize - 1) / tileSize;
            if (cols > 256 || rows > 256) {
                throw new MediaException.LimitExceeded("avif: an image grid holds at most 256 images per side, "
                        + "not " + cols + "x" + rows);
            }
            for (var r = 0; r < rows; r++) {
                for (var c = 0; c < cols; c++) {
                    var tile = image.tile(c * tileSize, r * tileSize, tileSize);
                    colorImages.add(code(tile, options.quality(), options, false));
                    if (image.alpha() != null) {
                        alphaImages.add(code(tile.alphaImage(), options.alphaQuality(), options, false));
                    }
                }
            }
        } else {
            colorImages.add(code(image, options.quality(), options, false));
            if (image.alpha() != null) {
                alphaImages.add(code(image.alphaImage(), options.alphaQuality(), options, false));
            }
        }
        var brands = brands(colorImages, alphaImages);
        if (gainMap != null) {
            brands.add("tmap");
        }
        var writer = new HeifWriter("avif", "avif", brands);
        var depth = image.bitDepth();
        var channels = image.format() == ChromaFormat.MONOCHROME ? 1 : 3;
        var colorProps = new ArrayList<HeifWriter.Property>();
        colorProps.add(HeifWriter.ispe(image.width(), image.height()));
        colorProps.add(HeifWriter.pixi(depth, channels));
        colorProps.addAll(colorProperties(image.color(), options));
        colorProps.addAll(HeifWriter.orientation(options.orientation()));
        var tileW = tileSize > 0 ? tileSize : image.width();
        var tileH = tileSize > 0 ? tileSize : image.height();
        int colorId;
        int alphaId = 0;
        if (tileSize > 0) {
            var tileIds = new int[colorImages.size()];
            for (var i = 0; i < tileIds.length; i++) {
                var coded = colorImages.get(i);
                tileIds[i] = writer.addItem("av01", coded.data(), true, av1C(coded.av1C()),
                        HeifWriter.ispe(tileW, tileH), HeifWriter.pixi(depth, channels));
            }
            colorId = writer.addIdatItem("grid", HeifWriter.grid(rows, cols, image.width(), image.height()),
                    colorProps.toArray(HeifWriter.Property[]::new));
            writer.reference("dimg", colorId, tileIds);
            if (!alphaImages.isEmpty()) {
                var alphaTiles = new int[alphaImages.size()];
                for (var i = 0; i < alphaTiles.length; i++) {
                    var coded = alphaImages.get(i);
                    alphaTiles[i] = writer.addItem("av01", coded.data(), true, av1C(coded.av1C()),
                            HeifWriter.ispe(tileW, tileH), HeifWriter.pixi(depth, 1));
                }
                alphaId = writer.addIdatItem("grid", HeifWriter.grid(rows, cols, image.width(), image.height()),
                        HeifWriter.ispe(image.width(), image.height()), HeifWriter.pixi(depth, 1),
                        HeifWriter.auxC(HeifProperty.AuxiliaryType.ALPHA_URN));
                writer.reference("dimg", alphaId, alphaTiles);
            }
        } else {
            var coded = colorImages.getFirst();
            var props = new ArrayList<HeifWriter.Property>();
            props.add(colorProps.get(0));
            props.add(colorProps.get(1));
            props.add(av1C(coded.av1C()));
            props.addAll(colorProps.subList(2, colorProps.size()));
            colorId = writer.addItem("av01", coded.data(), false, props.toArray(HeifWriter.Property[]::new));
            if (!alphaImages.isEmpty()) {
                var alpha = alphaImages.getFirst();
                alphaId = writer.addItem("av01", alpha.data(), false, HeifWriter.ispe(image.width(), image.height()),
                        HeifWriter.pixi(depth, 1), av1C(alpha.av1C()), HeifWriter.auxC(HeifProperty.AuxiliaryType.ALPHA_URN));
            }
        }
        if (gainMap != null) {
            addGainMap(writer, colorId, image.width(), image.height(), options, gainMap, gainMapOptions);
        }
        writer.primary(colorId);
        if (alphaId != 0) {
            writer.reference("auxl", alphaId, colorId);
            if (options.premultipliedAlpha()) {
                writer.reference("prem", colorId, alphaId);
            }
        }
        writer.addMetadata(colorId, options.exif().orElse(null), options.xmp().orElse(null));
        return writer.write();
    }

    /**
     * Adds the gain map item, the {@code tmap} item that derives the alternate rendition, and their
     * {@code altr} group.
     *
     * @param writer the file
     * @param colorId the color item
     * @param width the width of the color image
     * @param height the height of the color image
     * @param options the encoder settings of the picture
     * @param gainMap the gain map
     * @param gainMapOptions the encoder settings of the gain map image
     */
    private static void addGainMap(HeifWriter writer, int colorId, int width, int height, AvifEncoderOptions options,
                                   GainMap gainMap, AvifEncoderOptions gainMapOptions) {
        var picture = gainMap.image();
        var gray = switch (picture) {
            case YuvPicture yuv -> yuv.chromaFormat() == ChromaFormat.MONOCHROME;
            case Yuv16Picture yuv -> yuv.chromaFormat() == ChromaFormat.MONOCHROME;
            case ChannelPicture channels -> channels.layout().colorModel() == ColorModel.GRAY;
            default -> false;
        };
        var color = new ColorInfo(gray ? MatrixCoefficients.BT601 : MatrixCoefficients.IDENTITY, ColorRange.FULL,
                ColorPrimaries.UNSPECIFIED, TransferCharacteristics.UNSPECIFIED);
        var image = EncodeImage.of("avif", picture, gray ? ChromaFormat.MONOCHROME : ChromaFormat.YUV444,
                gainMapOptions.bitDepth().orElse(0), color, gainMapOptions.isLossless(), false);
        var coded = code(image, gainMapOptions.quality(), gainMapOptions, false);
        var signaled = image.color();
        var gainMapProps = new ArrayList<HeifWriter.Property>();
        gainMapProps.add(HeifWriter.ispe(image.width(), image.height()));
        gainMapProps.add(HeifWriter.pixi(image.bitDepth(), gray ? 1 : 3));
        gainMapProps.add(av1C(coded.av1C()));
        gainMapProps.add(HeifWriter.nclx(signaled.primaries().code(), signaled.transfer().code(),
                signaled.matrix().code(), signaled.range() == ColorRange.FULL));
        // the orientation goes on both inputs of the tmap item and not on the tmap item itself, the layout
        // libavif writes and the only one it accepts
        gainMapProps.addAll(HeifWriter.orientation(options.orientation()));
        var gainMapId = writer.addItem("av01", coded.data(), true, gainMapProps.toArray(HeifWriter.Property[]::new));
        var properties = new ArrayList<HeifWriter.Property>();
        properties.add(HeifWriter.ispe(width, height));
        gainMap.alternateProfile().ifPresent(profile -> {
            var info = profile.colorInfo();
            if (info.isPresent()) {
                var cicp = info.get();
                properties.add(HeifWriter.nclx(cicp.primaries().code(), cicp.transfer().code(), cicp.matrix().code(),
                        cicp.range() == ColorRange.FULL));
            } else {
                properties.add(HeifWriter.icc(profile.toByteArray()));
            }
        });
        gainMap.alternateLightLevel().ifPresent(level -> properties.add(HeifWriter.clli(
                level.maxContentLightLevel(), level.maxFrameAverageLightLevel())));
        var metadata = gainMap.metadata().toByteArray();
        var payload = new byte[metadata.length + 1];
        System.arraycopy(metadata, 0, payload, 1, metadata.length);
        var toneMapId = writer.addIdatItem("tmap", payload, properties.toArray(HeifWriter.Property[]::new));
        writer.reference("dimg", toneMapId, colorId, gainMapId);
        writer.alternatives(toneMapId, colorId);
    }

    /**
     * Returns the compatible brands of a file: the AVIF and MIAF brands and the AVIF profile
     * brand the coded images conform to, if any.
     *
     * @param color the coded color images
     * @param alpha the coded alpha images
     * @return the brands
     */
    static List<String> brands(List<Coded> color, List<Coded> alpha) {
        var brands = new ArrayList<>(List.of("avif", "mif1", "miaf"));
        var baseline = true;
        var advanced = true;
        for (var list : List.of(color, alpha)) {
            for (var coded : list) {
                baseline &= coded.profile() == 0 && coded.level() <= 13;
                advanced &= coded.profile() <= 1 && coded.level() <= 16;
            }
        }
        if (baseline) {
            brands.add("MA1B");
        } else if (advanced) {
            brands.add("MA1A");
        }
        return brands;
    }

    /**
     * Returns an AV1 codec configuration property.
     *
     * @param record the {@code av1C} record
     * @return the property, essential
     */
    static HeifWriter.Property av1C(byte[] record) {
        return HeifWriter.configuration("av1C", record);
    }

    /**
     * Returns the color properties of the color item: the {@code nclx} description, the ICC
     * profile, and the high dynamic range metadata.
     *
     * @param color the color description of the samples
     * @param options the encoder settings, whose ICC profile is added
     * @return the properties
     */
    static List<HeifWriter.Property> colorProperties(ColorInfo color, AvifEncoderOptions options) {
        return HeifWriter.colorProperties(color, options.colorProfile().orElse(null));
    }

}
