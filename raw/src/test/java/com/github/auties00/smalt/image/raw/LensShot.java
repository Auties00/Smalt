package com.github.auties00.smalt.image.raw;

import java.util.Random;

/**
 * Makes the parts of a synthetic Canon compact's CR3 file: photosites of a textured scene shot through a known lens, and
 * the maker note table that records the lens.
 */
public final class LensShot {
    private LensShot() {
        throw new AssertionError();
    }

    /**
     * A band-limited random scene, on a scale of 0 to 255.
     */
    public static final class Scene {
        private final int cell;
        private final float[] grid;
        private final int columns;
        private final int rows;

        public Scene(long seed, int width, int height) {
            this(seed, width, height, 11);
        }

        public Scene(long seed, int width, int height, int cell) {
            this.cell = cell;
            columns = width / cell + 8;
            rows = height / cell + 8;
            grid = new float[columns * rows];
            var random = new Random(seed);
            for (var index = 0; index < grid.length; index++) {
                grid[index] = 30 + random.nextFloat() * 190;
            }
        }

        public double at(double x, double y) {
            var gx = Math.max(0, Math.min(columns - 1.001, x / cell + 3));
            var gy = Math.max(0, Math.min(rows - 1.001, y / cell + 3));
            var column = (int) gx;
            var row = (int) gy;
            var fx = gx - column;
            var fy = gy - row;
            var at = row * columns + column;
            return (1 - fy) * ((1 - fx) * grid[at] + fx * grid[at + 1])
                    + fy * ((1 - fx) * grid[at + columns] + fx * grid[at + columns + 1]);
        }
    }

    /**
     * {@return an even polynomial}
     *
     * @param k the coefficients, of ascending powers
     * @param u the variable
     */
    public static double polynomial(double[] k, double u) {
        var value = 0.0;
        for (var term = k.length - 1; term >= 0; term--) {
            value = value * u * u + k[term];
        }
        return value;
    }

    private static double outputRadius(double source, double corner, double[] k, double[] aberration) {
        var out = source;
        for (var iteration = 0; iteration < 40; iteration++) {
            var u = out / corner;
            out += (source - out * polynomial(k, u) * (1 + polynomial(aberration, u))) * 0.8;
        }
        return out;
    }

    /**
     * {@return the RGGB photosites of a scene shot through a lens, with the preview's tone undone}
     *
     * @param scene the scene, in pixels of the writer's default crop
     * @param writer the file's writer, for its size, inset and levels
     * @param k the distortion, as coefficients of the source radius over the output radius
     * @param red the factor of the red plane relative to green, less 1
     * @param blue the factor of the blue plane relative to green, less 1
     */
    public static int[] photosites(Scene scene, Cr3Writer writer, double[] k, double[] red, double[] blue) {
        var width = writer.width;
        var height = writer.height;
        var cropWidth = width - 2 * writer.inset;
        var cropHeight = height - 2 * writer.inset;
        var centerX = writer.inset + cropWidth / 2.0 - 0.5;
        var centerY = writer.inset + cropHeight / 2.0 - 0.5;
        var corner = Math.hypot(width / 2.0, height / 2.0);
        var none = new double[1];
        var samples = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var color = (y & 1) == 0 ? (x & 1) == 0 ? 0 : 1 : (x & 1) == 0 ? 1 : 2;
                var dx = x - centerX;
                var dy = y - centerY;
                var source = Math.hypot(dx, dy);
                var aberration = color == 0 ? red : color == 2 ? blue : none;
                var out = source == 0 ? 0 : outputRadius(source, corner, k, aberration) / source;
                var tone = scene.at(dx * out + cropWidth / 2.0 - 0.5, dy * out + cropHeight / 2.0 - 0.5) / 255;
                samples[y * width + x] = (int) Math.round(writer.black + tone * tone * (writer.white - writer.black));
            }
        }
        return samples;
    }

    /**
     * {@return the bytes of a CR3 lens correction tag of a lens}
     *
     * @param writer the file's writer, for its size and inset
     * @param k the distortion, as coefficients of the source radius over the output radius
     * @param red the factor of the red plane relative to green, less 1
     * @param blue the factor of the blue plane relative to green, less 1
     */
    public static byte[] table(Cr3Writer writer, double[] k, double[] red, double[] blue) {
        var cropWidth = writer.width - 2 * writer.inset;
        var cropHeight = writer.height - 2 * writer.inset;
        var reach = Math.hypot(cropWidth / 2.0, cropHeight / 2.0);
        var corner = Math.hypot(writer.width / 2.0, writer.height / 2.0);
        var words = new int[667];
        words[0] = 0x3040;
        words[1] = 1334;
        words[64] = 61;
        words[65] = 17;
        words[66] = 17;
        for (var knot = 0; knot < 17; knot++) {
            var u = reach * knot / 16 / corner;
            words[64 + 233 + knot] = (int) Math.round(16384 + 524288 * polynomial(red, u));
            words[64 + 256 + knot] = (int) Math.round(16384 + 524288 * polynomial(blue, u));
            words[64 + 279 + knot] = knot * 93;
            words[64 + 302 + knot] = (int) Math.round(16384 * (1 - (1 - polynomial(k, u)) / 0.8));
        }
        var bytes = new byte[words.length * 2];
        for (var word = 0; word < words.length; word++) {
            bytes[2 * word] = (byte) words[word];
            bytes[2 * word + 1] = (byte) (words[word] >> 8);
        }
        return bytes;
    }

    /**
     * {@return a small writer of a file whose lens correction is recorded}
     */
    public static Cr3Writer behaviorFile() {
        var writer = new Cr3Writer();
        writer.width = 640;
        writer.height = 448;
        writer.model = "Canon PowerShot G7 X Mark III";
        var scene = new Scene(21, writer.width, writer.height, 4);
        var k = new double[]{0.99, -0.14, 0.05, -0.01};
        writer.samples = photosites(scene, writer, k, new double[1], new double[1]);
        writer.lensTable = table(writer, k, new double[1], new double[1]);
        return writer;
    }
}
