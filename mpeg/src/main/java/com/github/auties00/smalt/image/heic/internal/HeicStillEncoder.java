package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.heic.HeicEncoderOptions;
import com.github.auties00.smalt.internal.color.YcgcoR;
import com.github.auties00.smalt.internal.heif.EncodeImage;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifWriter;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoder;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderOptions;
import com.github.auties00.smalt.video.hevc.encoder.HevcPacketFormat;
import com.github.auties00.smalt.video.hevc.encoder.HevcProfile;
import com.github.auties00.smalt.video.hevc.encoder.HevcRateControl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes a still picture as a HEIC file: prepares its samples, codes the color and alpha images (or the images
 * of a grid) as HEVC still pictures and lays out the file's items, properties and references with
 * {@link HeifWriter}.
 *
 * <p>Every image is 4:2:0. An image of odd width or height is coded one sample wider or taller, the last column
 * or row repeated, and an essential clean aperture ({@code clap}) property crops it back; the tiles of a grid
 * are even, and the grid's output size crops their canvas instead. The alpha image is a 4:2:0 picture whose luma
 * holds the alpha samples, with neutral chroma and full-range samples, whose {@code auxC} property names the
 * HEVC alpha type {@code urn:mpeg:hevc:2015:auxid:1}.
 */
public final class HeicStillEncoder {
    /**
     * The side of grid images for pictures larger than HEVC level 5.1 allows.
     */
    static final int AUTO_GRID_TILE = 512;

    /**
     * The largest picture HEVC level 5.1 allows, in luma samples ({@code MaxLumaPs}).
     */
    static final long LEVEL_5_1_PICTURE = 8_912_896L;

    /**
     * The longest side HEVC level 5.1 allows, {@code sqrt(8 * MaxLumaPs)}.
     */
    static final int LEVEL_5_1_SIDE = 8444;

    /**
     * Prevents instantiation.
     */
    private HeicStillEncoder() {
        throw new AssertionError();
    }

    /**
     * One coded HEVC picture.
     *
     * @param data the length-prefixed NAL units of the picture
     * @param hvcC the {@code HEVCDecoderConfigurationRecord} of the picture
     * @param width the reconstructed width, even
     * @param height the reconstructed height, even
     */
    record Coded(byte[] data, byte[] hvcC, int width, int height) {
    }

    /**
     * Returns the HEVC quantizer of a quality.
     *
     * @param quality the quality, from 0 to 100
     * @return the quantizer, from 0 to 51
     */
    static int quantizer(int quality) {
        return ((100 - quality) * 51 + 50) / 100;
    }

    /**
     * Returns the coded bit depth of a picture.
     *
     * @param picture the picture to code, in any representation
     * @param options the encoder settings
     * @return 8 or 10
     */
    static int bitDepth(Picture picture, HeicEncoderOptions options) {
        if (options.bitDepth().isPresent()) {
            return options.bitDepth().getAsInt();
        }
        return switch (picture) {
            case RgbaPicture _, YuvPicture _ -> 8;
            case Rgba16Picture _ -> 10;
            case Yuv16Picture yuv -> yuv.bitDepth() <= 8 ? 8 : 10;
            case ChannelPicture channels -> channels.bitDepth() <= 8 ? 8 : 10;
        };
    }

    /**
     * Prepares a picture: 4:2:0 at the coded depth, RGB converted with the options' color description.
     *
     * @param picture the picture to code, in any representation
     * @param options the encoder settings
     * @return the prepared image
     * @throws MediaException.Unsupported if the picture's color description needs 4:4:4 chroma
     */
    static EncodeImage prepare(Picture picture, HeicEncoderOptions options) {
        var image = EncodeImage.of("heic", picture, ChromaFormat.YUV420, bitDepth(picture, options),
                options.color(), false, options.premultipliedAlpha());
        var matrix = image.color().matrix();
        if (matrix == MatrixCoefficients.IDENTITY || YcgcoR.applies(matrix)) {
            throw new MediaException.Unsupported("heic", "the " + matrix + " matrix needs 4:4:4 chroma");
        }
        return image;
    }

    /**
     * Returns the color description an alpha image signals: full range, everything else unspecified.
     *
     * @return the description
     */
    static ColorInfo alphaColor() {
        return new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.FULL, ColorPrimaries.UNSPECIFIED,
                TransferCharacteristics.UNSPECIFIED);
    }

    /**
     * Returns the 4:2:0 picture of a prepared image, or of its alpha plane with neutral chroma.
     *
     * @param image the prepared image, 4:2:0
     * @param alpha whether to return the alpha plane's picture
     * @return the picture, at the image's depth
     */
    static Yuv16Picture picture(EncodeImage image, boolean alpha) {
        var builder = Yuv16Picture.builder().size(image.width(), image.height()).chromaFormat(ChromaFormat.YUV420)
                .bitDepth(image.bitDepth());
        var cw = image.chromaWidth();
        if (alpha) {
            var neutral = new short[cw * image.chromaHeight()];
            Arrays.fill(neutral, (short) (1 << (image.bitDepth() - 1)));
            return builder.color(alphaColor()).luma(image.alpha(), image.width()).chroma(neutral, neutral, cw)
                    .build();
        }
        return builder.color(image.color()).luma(image.planes()[0], image.width())
                .chroma(image.planes()[1], image.planes()[2], cw).build();
    }

    /**
     * Returns the HEVC settings of a still image.
     *
     * @param depth the bit depth, 8 or 10
     * @param quality the quality, from 0 to 100
     * @param color the color description the stream signals
     * @param threads the encoder threads, from 1 to 64
     * @param options the encoder settings
     * @return the settings
     */
    static HevcEncoderOptions stillOptions(int depth, int quality, ColorInfo color, int threads,
                                           HeicEncoderOptions options) {
        return HevcEncoderOptions.builder()
                .profile(depth == 8 ? HevcProfile.MAIN_STILL_PICTURE : HevcProfile.MAIN_10_STILL_PICTURE)
                .bitDepth(depth).rateControl(HevcRateControl.CONSTANT_QUALITY).quality(quantizer(quality))
                .speed(options.speed()).color(color).threads(threads).packetFormat(HevcPacketFormat.LENGTH_PREFIXED)
                .limits(options.limits()).bFrames(0).build();
    }

    /**
     * Codes one picture as an HEVC still picture.
     *
     * @param picture the 4:2:0 picture
     * @param settings the HEVC settings
     * @return the coded picture
     */
    static Coded code(Yuv16Picture picture, HevcEncoderOptions settings) {
        var packets = new ArrayList<Packet>(1);
        try (var encoder = new HevcEncoder(picture.width(), picture.height(), settings)) {
            encoder.encode(new VideoFrame(picture, 0, 1, true), packets::add);
            encoder.flush(packets::add);
            return new Coded(packets.getFirst().data(), encoder.codecConfig().orElseThrow(),
                    (picture.width() + 1) & ~1, (picture.height() + 1) & ~1);
        }
    }

    /**
     * Returns the image grid of a picture.
     *
     * @param width the picture width
     * @param height the picture height
     * @param options the encoder settings
     * @return the side of the grid images, or 0 for a single image
     */
    static int gridTile(int width, int height, HeicEncoderOptions options) {
        if (options.gridTileSize().isPresent()) {
            return options.gridTileSize().getAsInt();
        }
        var large = (long) width * height > LEVEL_5_1_PICTURE || width > LEVEL_5_1_SIDE || height > LEVEL_5_1_SIDE;
        return large ? AUTO_GRID_TILE : 0;
    }

    /**
     * Checks that a file can hold the image grids of a picture: at most 256 images per side, and few enough
     * images for the file's items, counting the grid items and the Exif and XMP items.
     *
     * @param cols the images per row
     * @param rows the images per column
     * @param alpha whether the picture has an alpha grid besides its color grid
     * @throws MediaException.LimitExceeded if the grid does not fit
     */
    static void checkGrid(int cols, int rows, boolean alpha) {
        if (cols > 256 || rows > 256) {
            throw new MediaException.LimitExceeded("heic: an image grid holds at most 256 images per side, "
                    + "not " + cols + "x" + rows);
        }
        var grids = alpha ? 2 : 1;
        if ((long) cols * rows * grids + grids + 2 > HeifWriter.MAX_ITEMS) {
            throw new MediaException.LimitExceeded("heic: a file holds at most " + HeifWriter.MAX_ITEMS
                    + " items, too few for " + grids + " grids of " + cols + "x" + rows + " images");
        }
    }

    /**
     * Returns the threads of the shared pool one picture may use.
     *
     * @param options the encoder settings
     * @return the thread count, from 1 to 64
     */
    static int threads(HeicEncoderOptions options) {
        return Math.clamp(options.threads() == 0 ? ParallelBands.THREADS : options.threads(), 1, 64);
    }

    /**
     * Encodes a picture as a HEIC file.
     *
     * @param picture the picture, in any representation
     * @param options the encoder settings
     * @return the file
     * @throws MediaException.LimitExceeded if the grid needs more than 256 images per side, the file would hold
     * more than {@value HeifWriter#MAX_ITEMS} items, or the file would exceed 2 GiB
     * @throws MediaException.Unsupported if the picture's color description cannot be coded
     */
    public static byte[] encode(Picture picture, HeicEncoderOptions options) {
        var image = prepare(picture, options);
        var depth = image.bitDepth();
        var tileSize = gridTile(image.width(), image.height(), options);
        var cols = 1;
        var rows = 1;
        var threads = threads(options);
        List<Coded> colorImages;
        List<Coded> alphaImages = List.of();
        var color = stillOptions(depth, options.quality(), image.color(), threads, options);
        var alphaSettings = stillOptions(depth, options.alphaQuality(), alphaColor(), threads, options);
        if (tileSize > 0) {
            cols = (image.width() + tileSize - 1) / tileSize;
            rows = (image.height() + tileSize - 1) / tileSize;
            checkGrid(cols, rows, image.alpha() != null);
            var count = cols * rows;
            var tiles = new Coded[count];
            var alphaTiles = image.alpha() == null ? null : new Coded[count];
            var tileColor = color.toBuilder().threads(1).build();
            var tileAlpha = alphaSettings.toBuilder().threads(1).build();
            var columns = cols;
            var bands = Math.min(threads, count);
            ParallelBands.run(bands, band -> {
                for (var i = ParallelBands.start(band, bands, count); i < ParallelBands.start(band + 1, bands, count);
                     i++) {
                    var tile = image.tile(i % columns * tileSize, i / columns * tileSize, tileSize);
                    tiles[i] = code(picture(tile, false), tileColor);
                    if (alphaTiles != null) {
                        alphaTiles[i] = code(picture(tile, true), tileAlpha);
                    }
                }
            });
            colorImages = List.of(tiles);
            if (alphaTiles != null) {
                alphaImages = List.of(alphaTiles);
            }
        } else {
            colorImages = List.of(code(picture(image, false), color));
            if (image.alpha() != null) {
                alphaImages = List.of(code(picture(image, true), alphaSettings));
            }
        }
        var writer = new HeifWriter("heic", depth == 8 ? "heic" : "heix", brands(depth));
        var colorProps = new ArrayList<HeifWriter.Property>();
        colorProps.addAll(HeifWriter.colorProperties(image.color(), options.colorProfile().orElse(null)));
        colorProps.addAll(HeifWriter.orientation(options.orientation()));
        int colorId;
        var alphaId = 0;
        if (tileSize > 0) {
            var tileIds = new int[colorImages.size()];
            for (var i = 0; i < tileIds.length; i++) {
                tileIds[i] = addImage(writer, colorImages.get(i), true, List.of());
            }
            var props = new ArrayList<HeifWriter.Property>();
            props.add(HeifWriter.ispe(image.width(), image.height()));
            props.add(HeifWriter.pixi(depth, 3));
            props.addAll(colorProps);
            colorId = writer.addIdatItem("grid", HeifWriter.grid(rows, cols, image.width(), image.height()),
                    props.toArray(HeifWriter.Property[]::new));
            writer.reference("dimg", colorId, tileIds);
            if (!alphaImages.isEmpty()) {
                var alphaTiles = new int[alphaImages.size()];
                for (var i = 0; i < alphaTiles.length; i++) {
                    alphaTiles[i] = addImage(writer, alphaImages.get(i), true, List.of());
                }
                alphaId = writer.addIdatItem("grid", HeifWriter.grid(rows, cols, image.width(), image.height()),
                        HeifWriter.ispe(image.width(), image.height()), HeifWriter.pixi(depth, 3),
                        HeifWriter.auxC(HeifProperty.AuxiliaryType.HEVC_ALPHA_URN));
                writer.reference("dimg", alphaId, alphaTiles);
            }
        } else {
            colorId = addImage(writer, colorImages.getFirst(), false, colorProps, image.width(), image.height());
            if (!alphaImages.isEmpty()) {
                alphaId = addImage(writer, alphaImages.getFirst(), false,
                        List.of(HeifWriter.auxC(HeifProperty.AuxiliaryType.HEVC_ALPHA_URN)), image.width(),
                        image.height());
            }
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
     * Returns the compatible brands of a still image file.
     *
     * @param depth the coded bit depth
     * @return the brands
     */
    static List<String> brands(int depth) {
        return List.of("mif1", depth == 8 ? "heic" : "heix", "miaf");
    }

    /**
     * Adds a coded image item whose reconstruction is the coded size.
     *
     * @param writer the file
     * @param coded the coded image
     * @param hidden whether the item is hidden
     * @param properties the properties after the descriptive ones of the coded image
     * @return the item identifier
     */
    private static int addImage(HeifWriter writer, Coded coded, boolean hidden, List<HeifWriter.Property> properties) {
        return addImage(writer, coded, hidden, properties, coded.width(), coded.height());
    }

    /**
     * Adds a coded image item with the properties {@link #imageProperties} returns.
     *
     * @param writer the file
     * @param coded the coded image
     * @param hidden whether the item is hidden
     * @param properties the descriptive properties after the codec configuration, then the transformative ones
     * @param width the displayed width
     * @param height the displayed height
     * @return the item identifier
     */
    static int addImage(HeifWriter writer, Coded coded, boolean hidden, List<HeifWriter.Property> properties,
                        int width, int height) {
        return writer.addItem("hvc1", coded.data(), hidden,
                imageProperties(coded.hvcC(), coded.width(), coded.height(), properties, width, height));
    }

    /**
     * Returns the properties of a coded image item: its spatial extents, pixel information and {@code hvcC}
     * property, the given descriptive properties, a clean aperture when the displayed size is smaller than the
     * coded one, and the given rotation and mirroring, in that order.
     *
     * @param hvcC the {@code HEVCDecoderConfigurationRecord} of the image
     * @param codedWidth the reconstructed width
     * @param codedHeight the reconstructed height
     * @param properties the descriptive properties after the codec configuration, then the transformative ones
     * @param width the displayed width
     * @param height the displayed height
     * @return the properties
     */
    static HeifWriter.Property[] imageProperties(byte[] hvcC, int codedWidth, int codedHeight,
                                                 List<HeifWriter.Property> properties, int width, int height) {
        var props = new ArrayList<HeifWriter.Property>();
        props.add(HeifWriter.ispe(codedWidth, codedHeight));
        props.add(HeifWriter.pixi(depth(hvcC), 3));
        props.add(HeifWriter.configuration("hvcC", hvcC));
        var transformative = new ArrayList<HeifWriter.Property>();
        for (var property : properties) {
            (isTransformative(property) ? transformative : props).add(property);
        }
        if (width != codedWidth || height != codedHeight) {
            props.add(HeifWriter.clap(width, height, codedWidth, codedHeight));
        }
        props.addAll(transformative);
        return props.toArray(HeifWriter.Property[]::new);
    }

    /**
     * Returns whether a property transforms the reconstructed image: a rotation or a mirroring.
     *
     * @param property the serialized property box
     * @return {@code true} for {@code irot} and {@code imir}
     */
    private static boolean isTransformative(HeifWriter.Property property) {
        var box = property.box();
        var type = new String(box, 4, 4, StandardCharsets.US_ASCII);
        return type.equals("irot") || type.equals("imir");
    }

    /**
     * Returns the luma bit depth of an {@code HEVCDecoderConfigurationRecord}.
     *
     * @param hvcC the record
     * @return the depth
     */
    static int depth(byte[] hvcC) {
        return (hvcC[17] & 7) + 8;
    }
}
