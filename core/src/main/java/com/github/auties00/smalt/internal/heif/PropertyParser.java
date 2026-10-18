package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.FourCc;

/**
 * Parses the item property boxes of an {@code ipco} box into {@link HeifProperty} values.
 */
final class PropertyParser {
    private PropertyParser() {
        throw new AssertionError();
    }

    /**
     * Parses one property box.
     *
     * @param box the cursor over the property's payload
     * @return the property; boxes this parser does not understand become {@link HeifProperty.Unknown}
     * @throws MediaException.Malformed if a known property is truncated or holds reserved values
     */
    static HeifProperty parse(BoxCursor box) {
        var type = FourCc.toString(box.type());
        return switch (type) {
            case "ispe" -> {
                box.fullBoxHeader();
                yield new HeifProperty.SpatialExtent(box.u32(), box.u32());
            }
            case "pixi" -> {
                box.fullBoxHeader();
                var channels = box.u8();
                var depths = new int[channels];
                for (var i = 0; i < channels; i++) {
                    depths[i] = box.u8();
                }
                yield new HeifProperty.PixelInformation(depths);
            }
            case "av1C" -> HeifProperty.Av1Configuration.parse(box);
            case "hvcC" -> new HeifProperty.HevcConfiguration(box.rest());
            case "colr" -> parseColor(box);
            case "pasp" -> new HeifProperty.PixelAspectRatio(box.u32(), box.u32());
            case "clap" -> new HeifProperty.CleanAperture(box.s32(), box.s32(), box.s32(), box.s32(), box.s32(), box.s32(),
                    box.s32(), box.s32());
            case "irot" -> new HeifProperty.Rotation(box.u8() & 3);
            case "imir" -> new HeifProperty.Mirror(box.u8() & 1);
            case "auxC", "auxi" -> {
                box.fullBoxHeader();
                var urn = box.string();
                yield new HeifProperty.AuxiliaryType(type, urn, box.rest());
            }
            case "a1op" -> new HeifProperty.OperatingPointSelector(box.u8());
            case "lsel" -> new HeifProperty.LayerSelector(box.u16());
            case "a1lx" -> {
                var large = (box.u8() & 1) != 0;
                var sizes = new long[3];
                for (var i = 0; i < 3; i++) {
                    sizes[i] = large ? box.u32() : box.u16();
                }
                yield new HeifProperty.LayeredImageIndexing(sizes);
            }
            case "clli" -> new HeifProperty.ContentLightLevel(box.u16(), box.u16());
            case "rref" -> {
                box.fullBoxHeader();
                var count = box.u8();
                var types = new java.util.ArrayList<String>(count);
                for (var i = 0; i < count; i++) {
                    types.add(FourCc.toString(box.fourCc()));
                }
                yield new HeifProperty.RequiredReferenceTypes(java.util.List.copyOf(types));
            }
            case "mdcv" -> {
                var primaries = new int[6];
                for (var i = 0; i < 6; i++) {
                    primaries[i] = box.u16();
                }
                yield new HeifProperty.MasteringDisplay(primaries, box.u16(), box.u16(), box.u32(), box.u32());
            }
            default -> new HeifProperty.Unknown(type);
        };
    }

    /**
     * Parses a color information box ({@code colr}).
     *
     * @param box the cursor over the payload
     * @return the nclx description, the ICC profile, or {@link HeifProperty.ColorOther} for other
     * color types
     */
    private static HeifProperty parseColor(BoxCursor box) {
        var colorType = FourCc.toString(box.fourCc());
        return switch (colorType) {
            case "nclx" -> {
                var primaries = box.u16();
                var transfer = box.u16();
                var matrix = box.u16();
                var fullRange = (box.u8() & 0x80) != 0;
                yield new HeifProperty.ColorNclx(primaries, transfer, matrix, fullRange);
            }
            case "rICC", "prof" -> {
                if (box.remaining() == 0) {
                    throw new MediaException.Malformed("heif: colr box holds an empty ICC profile");
                }
                yield new HeifProperty.ColorIcc(box.rest());
            }
            default -> new HeifProperty.ColorOther(colorType);
        };
    }
}
