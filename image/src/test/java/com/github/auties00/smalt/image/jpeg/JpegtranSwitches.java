package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.ops.Region;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps {@code jpegtran} command lines to {@link JpegTransformOptions}, so the transform tests can run the same
 * switches through both. A drop image is named by a {@code drop:} key registered here, or by the name of a
 * 12-bit vector of {@code cjpeg12/}.
 */
final class JpegtranSwitches {
    // jpegtran's crop geometry: <width>[f|r]x<height>[f|r]+<x>+<y>
    private static final Pattern GEOMETRY = Pattern.compile("(\\d+)([fFrR]?)[xX](\\d+)([fFrR]?)\\+(\\d+)\\+(\\d+)");

    private static final Map<String, byte[]> DROPS = new ConcurrentHashMap<>();

    private JpegtranSwitches() {
    }

    static String drop(String name, byte[] image) {
        var key = "drop:" + name;
        DROPS.put(key, image);
        return key;
    }

    static byte[] dropImage(String key) {
        if (key.startsWith("drop:")) {
            var image = DROPS.get(key);
            if (image == null) {
                throw new AssertionError("unregistered drop image " + key);
            }
            return image;
        }
        return JpegTransformsTest.twelveBit(key);
    }

    static JpegTransformOptions options(List<String> switches) {
        var builder = JpegTransformOptions.jpegtran().toBuilder();
        for (var i = 0; i < switches.size(); i++) {
            switch (switches.get(i)) {
                case "-optimize" -> builder.optimizeHuffman(true);
                case "-progressive" -> builder.progressive(true);
                case "-arithmetic" -> builder.arithmetic(true);
                case "-trim" -> builder.trim(true).requantizeDrop(true);
                case "-perfect" -> builder.perfect(true);
                case "-grayscale" -> builder.grayscale(true);
                case "-transpose" -> builder.transform(Orientation.TRANSPOSE);
                case "-transverse" -> builder.transform(Orientation.TRANSVERSE);
                case "-rotate" -> builder.transform(Orientation.ofRotation(Integer.parseInt(switches.get(++i)), false));
                case "-flip" -> builder.transform(switches.get(++i).equals("horizontal")
                        ? Orientation.FLIP_HORIZONTAL : Orientation.FLIP_VERTICAL);
                case "-restart" -> {
                    var value = switches.get(++i);
                    if (value.endsWith("B") || value.endsWith("b")) {
                        builder.restartInterval(Integer.parseInt(value.substring(0, value.length() - 1)));
                    } else {
                        builder.restartRows(Integer.parseInt(value));
                    }
                }
                case "-copy" -> builder.markerCopy(switch (switches.get(++i)) {
                    case "all" -> JpegMarkerCopy.ALL;
                    case "none" -> JpegMarkerCopy.NONE;
                    case "icc" -> JpegMarkerCopy.ICC;
                    default -> JpegMarkerCopy.COMMENTS;
                });
                case "-crop" -> {
                    var geometry = geometry(switches.get(++i));
                    builder.crop(region(geometry));
                    var widthFlag = Character.toLowerCase(geometry.group(2).isEmpty() ? ' ' : geometry.group(2).charAt(0));
                    var heightFlag = Character.toLowerCase(geometry.group(4).isEmpty() ? ' ' : geometry.group(4).charAt(0));
                    builder.exactCropWidth(widthFlag == 'f').exactCropHeight(heightFlag == 'f').fill(fill(widthFlag));
                }
                case "-wipe" -> {
                    var geometry = geometry(switches.get(++i));
                    builder.wipe(region(geometry));
                    var widthFlag = Character.toLowerCase(geometry.group(2).isEmpty() ? ' ' : geometry.group(2).charAt(0));
                    builder.fill(fill(widthFlag));
                }
                case "-drop" -> {
                    var offset = switches.get(++i).substring(1).split("\\+");
                    builder.drop(dropImage(switches.get(++i)), Integer.parseInt(offset[0]), Integer.parseInt(offset[1]));
                }
                default -> throw new AssertionError(switches.get(i));
            }
        }
        return builder.build();
    }

    private static Matcher geometry(String spec) {
        var matcher = GEOMETRY.matcher(spec);
        if (!matcher.matches()) {
            throw new AssertionError("unsupported geometry " + spec);
        }
        return matcher;
    }

    private static Region region(Matcher geometry) {
        return new Region(Integer.parseInt(geometry.group(5)), Integer.parseInt(geometry.group(6)),
                Integer.parseInt(geometry.group(1)), Integer.parseInt(geometry.group(3)));
    }

    private static JpegFill fill(char flag) {
        return switch (flag) {
            case 'f' -> JpegFill.FLAT;
            case 'r' -> JpegFill.REFLECT;
            default -> JpegFill.GRAY;
        };
    }
}
