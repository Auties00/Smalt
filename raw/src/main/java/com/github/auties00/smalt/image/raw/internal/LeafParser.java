package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses a Leaf MOS file: a TIFF file whose first directory holds the sensor data as one grayscale image,
 * uncompressed or lossless JPEG (compression 99), and whose tag 34310 holds Leaf's metadata as nested packets.
 *
 * <p>Each packet is the four bytes {@code PKTS}, a 32-bit version, a 40-byte name padded with zeros, a 32-bit
 * big-endian length and that many bytes of data, which are either further packets or a value: numbers in decimal
 * text, separated by line feeds and ended by a zero byte. The parser reads the color of each position of the 2x2
 * filter array from {@code CaptProf_mosaic_pattern} (0 blue, 1 red, 2 and 3 green, in row order), the as-shot
 * levels of a neutral surface from {@code NeutObj_neutrals} (a reference level, then red, green and blue), and the
 * rotation of the picture from {@code ImgProf_rotation_angle}; {@code JPEG_preview_data} holds the camera's JPEG
 * preview. The camera's name is the XMP {@code tiff:Model} up to its serial number in parentheses.
 *
 * <p>When the fourth number of {@code CaptProf_image_fields} is not zero, the sensor was read as two fields, one
 * from its top edge down and one from its bottom edge up, and the stored rows alternate between them: stored row
 * {@code 2k} is row {@code k} of the picture and stored row {@code 2k + 1} is row {@code height - 1 - k}.
 *
 * @implNote This implementation's filter array, as-shot neutral, orientation and white level were checked against
 * Adobe DNG Converter, run as a black box, on a CC0 sample file, whose photosites it reproduces exactly. The
 * white level of a model is not in the file, so {@link #WHITE_OF_MODEL} keeps the levels of the models checked;
 * other models take the largest value of their bit depth. The meaning of the image fields was derived from the
 * CC0 samples: the AFi-II 12 sample, whose fourth field number is 1, renders its JPEG preview's scene only with
 * its rows taken in that order, and the Aptus 22 samples, whose fourth number is 0, store the rows in order.
 */
final class LeafParser {
    /**
     * The tag holding Leaf's metadata packets.
     */
    static final int PACKETS = 34310;

    /**
     * The largest metadata packet tree read, in bytes.
     */
    private static final int MAX_PACKETS = 16 << 20;

    /**
     * The largest packet value kept, in bytes; longer values are previews and histograms.
     */
    private static final int MAX_VALUE = 4096;

    /**
     * The name of the packet holding the JPEG preview.
     */
    private static final String PREVIEW = "JPEG_preview_data";

    /**
     * The key under which {@link #packets(Ifd)} gives the index of the preview packet's data within the tag's
     * value and its length, separated by a space; it cannot collide with a packet name, which holds no space.
     */
    static final String PREVIEW_PLACE = "preview place";

    /**
     * The deepest nesting of packets followed.
     */
    private static final int MAX_DEPTH = 16;

    /**
     * The index, within {@code CaptProf_image_fields}, of the number that says the sensor was read as two fields.
     */
    private static final int TWO_FIELDS = 3;

    /**
     * The white levels of the models checked on CC0 samples: the level Adobe DNG Converter gives the Aptus 22, and
     * the saturation level of the AFi-II 12's photosites, 14-bit values in a 16-bit frame, which the converter does
     * not read.
     */
    private static final Map<String, Integer> WHITE_OF_MODEL = Map.of("Aptus 22", 15000, "AFi-II 12", 16383);

    /**
     * Prevents instantiation.
     */
    private LeafParser() {
        throw new AssertionError();
    }

    /**
     * Returns whether a first directory carries Leaf's metadata packets.
     *
     * @param first the first directory
     * @return whether the file is a Leaf file
     */
    static boolean matches(Ifd first) {
        if (!first.has(PACKETS) || first.valueCount(PACKETS) < 4) {
            return false;
        }
        var head = new byte[4];
        var read = first.reader().readAvailable(first.valuePosition(PACKETS), head, 0, 4);
        return read == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 'T' && head[3] == 'S';
    }

    /**
     * Parses the file.
     *
     * @param reader the TIFF structure
     * @param file the description to fill
     */
    static void parse(TiffReader reader, RawFile file) {
        var tree = new TiffTree(reader);
        tree.describe(file);
        var first = tree.first;
        var values = packets(first);
        var preview = numbers(values.get(PREVIEW_PLACE));
        if (preview != null && preview.length == 2) {
            TiffTree.addPreview(file, reader, first.valuePosition(PACKETS) + (long) preview[0], (long) preview[1]);
        }
        file.make = "Leaf";
        file.model = model(first);
        file.orientation = switch (integer(values.get("ImgProf_rotation_angle"), 0)) {
            case 90 -> 6;
            case 180 -> 3;
            case 270 -> 8;
            default -> 1;
        };
        var sensor = new Sensor();
        try {
            sensor.reader = TiffSamples.describe(first, sensor);
        } catch (MediaException.Unsupported exception) {
            file.unsupported = exception.getMessage();
            return;
        }
        if (sensor.samplesPerPixel != 1) {
            file.unsupported = "Leaf sensor data of " + sensor.samplesPerPixel + " samples per pixel is not supported";
            return;
        }
        sensor.cfa = cfa(values.get("CaptProf_mosaic_pattern"));
        if (sensor.cfa == null) {
            file.unsupported = "the Leaf file has no readable mosaic pattern";
            return;
        }
        var white = file.model == null ? null : WHITE_OF_MODEL.get(file.model);
        sensor.white = new double[]{white != null ? white : (1 << sensor.bitsPerSample) - 1};
        var fields = numbers(values.get("CaptProf_image_fields"));
        if (fields != null && fields.length > TWO_FIELDS && fields[TWO_FIELDS] != 0) {
            sensor.reader = unfolding(sensor.reader);
        }
        var neutrals = numbers(values.get("NeutObj_neutrals"));
        if (neutrals != null && neutrals.length >= 4 && neutrals[1] > 0 && neutrals[2] > 0 && neutrals[3] > 0) {
            file.color.asShotNeutral = new double[]{neutrals[1] / neutrals[2], 1, neutrals[3] / neutrals[2]};
        } else {
            sensor.estimateBalance = true;
        }
        file.sensor = sensor;
    }

    /**
     * Returns a reader that puts the rows of a sensor read as two fields, which the stored data alternates, in
     * picture order.
     *
     * @param stored the reader of the rows as stored
     * @return the reader of the rows in picture order
     */
    private static Sensor.Reader unfolding(Sensor.Reader stored) {
        return new Sensor.Reader() {
            @Override
            public void read(SensorPlane plane, MediaLimits limits) {
                stored.read(plane, limits);
                unfold(plane);
            }

            @Override
            public void read(SensorPlane plane, MediaLimits limits, boolean corrections) {
                stored.read(plane, limits, corrections);
                unfold(plane);
            }
        };
    }

    /**
     * Moves stored row {@code 2k} of a plane to row {@code k} and stored row {@code 2k + 1} to row
     * {@code height - 1 - k}, in place, following the cycles of that permutation.
     *
     * @param plane the plane, of the sensor's stored size
     */
    static void unfold(SensorPlane plane) {
        var height = plane.height;
        var rowLength = plane.width * plane.samplesPerPixel;
        Object samples = plane.integers != null ? plane.integers : plane.wide != null ? plane.wide : plane.floats;
        Object held = newRow(samples, rowLength);
        Object displaced = newRow(samples, rowLength);
        var placed = new boolean[height];
        for (var start = 0; start < height; start++) {
            if (placed[start]) {
                continue;
            }
            System.arraycopy(samples, start * rowLength, held, 0, rowLength);
            var row = start;
            do {
                var target = (row & 1) == 0 ? row >> 1 : height - 1 - (row >> 1);
                System.arraycopy(samples, target * rowLength, displaced, 0, rowLength);
                System.arraycopy(held, 0, samples, target * rowLength, rowLength);
                var swap = held;
                held = displaced;
                displaced = swap;
                placed[target] = true;
                row = target;
            } while (row != start);
        }
    }

    /**
     * {@return an array of the samples' type holding one row}
     *
     * @param samples the plane's samples: a {@code short[]}, {@code int[]} or {@code float[]}
     * @param length the samples of a row
     */
    private static Object newRow(Object samples, int length) {
        return switch (samples) {
            case short[] _ -> new short[length];
            case int[] _ -> new int[length];
            default -> new float[length];
        };
    }

    /**
     * Reads the values of the metadata packets, by name; the first packet of a name wins.
     *
     * @param first the first directory
     * @return the values, as text without their ending zero byte
     */
    static Map<String, String> packets(Ifd first) {
        var values = new HashMap<String, String>();
        var count = first.valueCount(PACKETS);
        if (count > MAX_PACKETS) {
            throw new MediaException.LimitExceeded("Leaf metadata of " + count + " bytes");
        }
        var data = new byte[(int) count];
        var read = first.reader().readAvailable(first.valuePosition(PACKETS), data, 0, data.length);
        walk(data, 0, read, 0, values);
        return values;
    }

    /**
     * Reads the packets of a range, descending into those that hold packets.
     *
     * @param data the packet bytes
     * @param start the index of the first packet
     * @param end the index after the range
     * @param depth the nesting depth of the range
     * @param values the values read, by name, and the place of the preview packet's data as
     * {@link #PREVIEW_PLACE}, its index and length in decimal
     */
    private static void walk(byte[] data, int start, int end, int depth, Map<String, String> values) {
        var position = start;
        while (position + 52 <= end && isPacket(data, position)) {
            var nameEnd = position + 8;
            while (nameEnd < position + 48 && data[nameEnd] != 0) {
                nameEnd++;
            }
            var name = new String(data, position + 8, nameEnd - position - 8, StandardCharsets.ISO_8859_1);
            var length = ByteArrays.u32(data, position + 48, false);
            var body = position + 52;
            if (length > end - body) {
                return;
            }
            var size = (int) length;
            if (size >= 52 && isPacket(data, body)) {
                if (depth < MAX_DEPTH) {
                    walk(data, body, body + size, depth + 1, values);
                }
            } else if (name.equals(PREVIEW)) {
                values.putIfAbsent(PREVIEW_PLACE, body + " " + size);
            } else if (size <= MAX_VALUE) {
                var valueEnd = body;
                while (valueEnd < body + size && data[valueEnd] != 0) {
                    valueEnd++;
                }
                values.putIfAbsent(name, new String(data, body, valueEnd - body, StandardCharsets.ISO_8859_1));
            }
            position = body + size;
        }
    }

    /**
     * Returns whether a packet starts at an index.
     *
     * @param data the packet bytes
     * @param at the index
     * @return whether the bytes there are {@code PKTS}
     */
    private static boolean isPacket(byte[] data, int at) {
        return data[at] == 'P' && data[at + 1] == 'K' && data[at + 2] == 'T' && data[at + 3] == 'S';
    }

    /**
     * Reads the camera's name from the XMP packet's {@code tiff:Model}, up to the serial number in parentheses and
     * without the leading maker name.
     *
     * @param first the first directory
     * @return the name, or {@code null} without one
     */
    private static String model(Ifd first) {
        var xmp = first.bytes(700);
        if (xmp == null) {
            return null;
        }
        var text = new String(xmp, StandardCharsets.UTF_8);
        var open = text.indexOf("<tiff:Model>");
        var close = open < 0 ? -1 : text.indexOf("</tiff:Model>", open);
        if (close < 0) {
            return null;
        }
        var model = text.substring(open + "<tiff:Model>".length(), close);
        var serial = model.indexOf('(');
        if (serial >= 0) {
            model = model.substring(0, serial);
        }
        model = model.strip();
        if (model.startsWith("Leaf ")) {
            model = model.substring("Leaf ".length()).strip();
        }
        return model.isEmpty() ? null : model;
    }

    /**
     * Reads the 2x2 filter array from the mosaic pattern's four color codes.
     *
     * @param pattern the pattern value
     * @return the filter array, or {@code null} when the value is not four valid codes with one red and one blue
     */
    static CfaPattern cfa(String pattern) {
        var codes = numbers(pattern);
        if (codes == null || codes.length != 4) {
            return null;
        }
        var letters = new StringBuilder();
        for (var code : codes) {
            letters.append(switch ((int) code) {
                case 0 -> 'B';
                case 1 -> 'R';
                case 2, 3 -> 'G';
                default -> '?';
            });
        }
        var text = letters.toString();
        if (text.indexOf('?') >= 0 || text.chars().filter(c -> c == 'R').count() != 1
                || text.chars().filter(c -> c == 'B').count() != 1 || codes[0] != (int) codes[0]) {
            return null;
        }
        return CfaPattern.bayer(text);
    }

    /**
     * Parses a value of numbers separated by line feeds.
     *
     * @param value the value, or {@code null}
     * @return the numbers, or {@code null} when the value is absent or holds something else
     */
    static double[] numbers(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var parts = value.strip().split("\\s+");
        var numbers = new double[parts.length];
        for (var index = 0; index < parts.length; index++) {
            try {
                numbers[index] = Double.parseDouble(parts[index]);
            } catch (NumberFormatException exception) {
                return null;
            }
            if (!Double.isFinite(numbers[index])) {
                return null;
            }
        }
        return numbers;
    }

    /**
     * Parses a value holding one integer.
     *
     * @param value the value, or {@code null}
     * @param fallback the result when the value is absent or not one integer
     * @return the integer
     */
    private static int integer(String value, int fallback) {
        var numbers = numbers(value);
        return numbers != null && numbers.length == 1 && numbers[0] == (int) numbers[0] ? (int) numbers[0] : fallback;
    }
}
