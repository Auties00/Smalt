package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawDemosaic;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Renders the sensor image of a raw file into a picture: reads the samples, applies the opcode
 * lists, linearizes, demosaics, white balances and maps the colors, tile by tile across the
 * shared thread pool.
 *
 * <p>Tiles are independent (demosaicing reads past a tile's edges where it needs to, see
 * {@link MenonDemosaic#MARGIN}), so the output never depends on how the tiles are scheduled.
 */
public final class Renderer {
    /**
     * The width of a tile.
     */
    private static final int TILE_WIDTH = 512;

    /**
     * The height of a tile.
     */
    private static final int TILE_HEIGHT = 128;

    /**
     * The color kernel.
     */
    private static final ColorKernel KERNEL = ColorKernels.COLOR;

    /**
     * Prevents instantiation.
     */
    private Renderer() {
        throw new AssertionError();
    }

    /**
     * Reads the samples of a raw file's sensor and linearizes them, applying opcode lists 1 and 2
     * when asked.
     *
     * @param file the raw file
     * @param opcodes whether the opcode lists are applied
     * @return the linear image of the active area
     * @throws MediaException.Unsupported if the file's sensor data cannot be decoded
     */
    static LinearImage linearize(RawFile file, boolean opcodes) {
        var sensor = file.sensor;
        if (sensor == null) {
            throw new MediaException.Unsupported(file.format.extension(), file.unsupported != null ? file.unsupported
                    : "the " + file.format.extension() + " sensor data cannot be decoded");
        }
        var limits = file.limits;
        var plane = new SensorPlane(sensor.width, sensor.height, sensor.samplesPerPixel, sensor.floating, limits);
        sensor.reader.read(plane, limits);
        if (opcodes && sensor.opcodes1 != null) {
            Opcodes.applyToSensor(sensor, plane, sensor.opcodes1);
        }
        var image = LinearImage.of(sensor, plane, limits);
        if (opcodes && sensor.opcodes2 != null) {
            Opcodes.applyToLinear(image, sensor.opcodes2, Opcodes.bounds(sensor, image.width, image.height, 1));
        }
        return image;
    }

    /**
     * Returns the window of a linear image the output covers: the default crop, or the whole
     * active area.
     *
     * @param sensor the sensor description
     * @param image the linear image
     * @param crop whether the default crop applies
     * @return the left, top, width and height
     */
    static int[] window(Sensor sensor, LinearImage image, boolean crop) {
        return window(sensor, image.width, image.height, crop, false);
    }

    /**
     * Returns the window of an active area of a given size the output covers: the default crop, or
     * the whole active area, within the rectangle the opcode lists trim the image to when they
     * apply.
     *
     * @param sensor the sensor description
     * @param width the active area's width
     * @param height the active area's height
     * @param crop whether the default crop applies
     * @param opcodes whether the opcode lists apply
     * @return the left, top, width and height
     */
    static int[] window(Sensor sensor, int width, int height, boolean crop, boolean opcodes) {
        int[] window;
        if (!crop || sensor.defaultCrop == null) {
            window = new int[]{0, 0, width, height};
        } else {
            var c = sensor.defaultCrop;
            var x = (int) Math.clamp(Math.round(c[0]), 0, Math.max(0, width - 1));
            var y = (int) Math.clamp(Math.round(c[1]), 0, Math.max(0, height - 1));
            var w = (int) Math.clamp(Math.round(c[2]), 1, Math.max(1, width - x));
            var h = (int) Math.clamp(Math.round(c[3]), 1, Math.max(1, height - y));
            window = new int[]{x, y, w, h};
        }
        int[] bounds = null;
        if (opcodes) {
            try {
                bounds = Opcodes.bounds(sensor, width, height, 3);
            } catch (MediaException.Malformed exception) {
                // a corrupt opcode list fails when it is applied, not when the window is computed
            }
        }
        if (bounds != null) {
            var left = Math.max(window[0], bounds[0]);
            var top = Math.max(window[1], bounds[1]);
            var right = Math.min(window[0] + window[2], bounds[2]);
            var bottom = Math.min(window[1] + window[3], bounds[3]);
            if (right > left && bottom > top) {
                window = new int[]{left, top, right - left, bottom - top};
            }
        }
        return window;
    }

    /**
     * Returns the size a window renders at, after the default scale.
     *
     * @param sensor the sensor description
     * @param width the window width
     * @param height the window height
     * @return the width and height
     */
    static int[] scaledSize(Sensor sensor, int width, int height) {
        var smaller = Math.min(sensor.scaleH, sensor.scaleV);
        if (sensor.scaleH == sensor.scaleV || !(smaller > 0)) {
            return new int[]{width, height};
        }
        var scaledWidth = Math.max(1, Math.round(width * sensor.scaleH / smaller));
        var scaledHeight = Math.max(1, Math.round(height * sensor.scaleV / smaller));
        return new int[]{(int) Math.min(scaledWidth, Integer.MAX_VALUE), (int) Math.min(scaledHeight, Integer.MAX_VALUE)};
    }

    /**
     * Renders a raw file.
     *
     * @param file the raw file
     * @param options the rendering options
     * @param halfSize whether to render one pixel per 2x2 block of photosites
     * @return the picture, already scaled to square pixels
     * @throws MediaException.Unsupported if the file's sensor data cannot be decoded or rendered
     */
    public static Picture render(RawFile file, RawDecoderOptions options, boolean halfSize) {
        var image = linearize(file, options.opcodes());
        var sensor = file.sensor;
        var window = window(sensor, image.width, image.height, options.defaultCrop(), options.opcodes());
        PreviewBalance.apply(file, image, window(sensor, image, true));
        var monochrome = image.samplesPerPixel == 1 && image.cfa == null;
        if (image.samplesPerPixel != 1 && image.samplesPerPixel != 3) {
            throw new MediaException.Unsupported(file.format.extension(), image.samplesPerPixel
                    + "-channel linear raw images are not supported");
        }
        var color = CameraColor.of(file.color, options.whiteBalance(), options.colorMatrix().orElse(null));
        var four = image.cfa != null && !image.cfa.isRgb() ? fourColor(file, image, color) : null;
        var pipeline = new ColorPipeline(four != null ? color.projected() : color, file.color, options, monochrome);
        var bayer = image.cfa != null && image.cfa.isBayer();
        var half = halfSize && bayer && window[2] >= 2 && window[3] >= 2;
        var outWidth = half ? window[2] / 2 : window[2];
        var outHeight = half ? window[3] / 2 : window[3];
        LimitChecks.checkPicture(file.limits, outWidth, outHeight);
        var high = options.highBitDepth();
        LimitChecks.checkAllocation(file.limits, (long) outWidth * outHeight * (high ? 8 : 4));
        var target = high ? Rgba16Picture.allocate(outWidth, outHeight, false)
                : (Picture) RgbaPicture.allocate(outWidth, outHeight, false);
        float[][] rgb = null;
        if (options.opcodes() && sensor.opcodes3 != null && !half) {
            rgb = demosaicAll(image, new int[]{0, 0, image.width, image.height}, options.demosaic(), four);
            Opcodes.applyToRgb(rgb, image.width, image.height, sensor.opcodes3,
                    Opcodes.bounds(sensor, image.width, image.height, 2));
        }
        var full = rgb;
        var tilesDown = (outHeight + TILE_HEIGHT - 1) / TILE_HEIGHT;
        var bands = ParallelBands.bands(tilesDown, (long) outWidth * outHeight * 16);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, tilesDown);
            var last = ParallelBands.start(band + 1, bands, tilesDown);
            var tile = new Tile(image, options.demosaic(), pipeline, four);
            for (var tileRow = first; tileRow < last; tileRow++) {
                var y = tileRow * TILE_HEIGHT;
                var rows = Math.min(TILE_HEIGHT, outHeight - y);
                for (var x = 0; x < outWidth; x += TILE_WIDTH) {
                    var columns = Math.min(TILE_WIDTH, outWidth - x);
                    if (full != null) {
                        tile.copy(full, image.width, window[0] + x, window[1] + y, columns, rows);
                    } else if (half) {
                        tile.halfSize(window[0] + 2 * x, window[1] + 2 * y, columns, rows);
                    } else {
                        tile.demosaic(window[0] + x, window[1] + y, columns, rows);
                    }
                    tile.output(target, x, y, columns, rows);
                }
                ParallelBands.checkpoint();
            }
        });
        var picture = target;
        var size = scaledSize(sensor, outWidth, outHeight);
        if (size[0] != outWidth || size[1] != outHeight) {
            LimitChecks.checkPicture(file.limits, size[0], size[1]);
            picture = ImageOps.resize(picture, size[0], size[1], ResizeFilter.BICUBIC);
        }
        return picture;
    }

    /**
     * Prepares the rendering of a four-color image: checks that the sensor's four colors and the
     * color description agree and returns the matrix that maps the image's camera values, white
     * balanced and clipped, to the three channels the rest of the rendering works in.
     *
     * @param file the raw file
     * @param image the linear image, its color filter array not of red, green and blue
     * @param color the camera color mapping
     * @return the {@code 3 x 4} projection followed by the four white balance multipliers
     * @throws MediaException.Unsupported if the file cannot be rendered, as
     * {@link #fourColorProblem(RawFile)} tells
     */
    private static float[] fourColor(RawFile file, LinearImage image, CameraColor color) {
        var problem = fourColorProblem(file);
        if (problem != null || image.colors != 4 || color.channels() != 4) {
            throw new MediaException.Unsupported(file.format.extension(), problem != null ? problem
                    : "the four-color sensor has no color matrices for its four colors");
        }
        var projection = color.projection();
        var result = new float[16];
        for (var index = 0; index < 12; index++) {
            result[index] = (float) projection[index];
        }
        for (var index = 0; index < 4; index++) {
            result[12 + index] = (float) (1 / color.neutral[index]);
        }
        return result;
    }

    /**
     * Returns why a file of a sensor whose color filter array is not of red, green and blue
     * cannot be rendered: its colors must be four, each of its own color plane, and the file
     * must give color matrices for four colors.
     *
     * @param file the raw file
     * @return the reason, or {@code null} when the file can be rendered or its color filter array
     * is of red, green and blue
     */
    static String fourColorProblem(RawFile file) {
        var sensor = file.sensor;
        if (sensor == null || sensor.cfa == null || sensor.samplesPerPixel != 1 || sensor.cfa.isRgb()) {
            return null;
        }
        var seen = new boolean[4];
        for (var color : sensor.cfa.colors()) {
            var plane = sensor.planeOfColor[color.code()];
            if (plane < 0 || plane > 3) {
                return "color filter arrays of other colors than red, green and blue need four color planes";
            }
            seen[plane] = true;
        }
        for (var plane : seen) {
            if (!plane) {
                return "color filter arrays of other colors than red, green and blue need four color planes";
            }
        }
        if (file.color.channels() != 4) {
            return "the four-color sensor has no color matrices for its four colors";
        }
        return null;
    }

    /**
     * Demosaics a whole window into three planes, for the opcodes that need the full image.
     *
     * @param image the linear image
     * @param window the left, top, width and height of the area to demosaic
     * @param algorithm the demosaicing algorithm
     * @param four the projection of a four-color image, from {@link #fourColor}, or
     * {@code null}
     * @return the red, green and blue planes
     */
    private static float[][] demosaicAll(LinearImage image, int[] window, RawDemosaic algorithm, float[] four) {
        var width = window[2];
        var height = window[3];
        var planes = new float[3][width * height];
        var tilesDown = (height + TILE_HEIGHT - 1) / TILE_HEIGHT;
        var bands = ParallelBands.bands(tilesDown, (long) width * height * 8);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, tilesDown);
            var last = ParallelBands.start(band + 1, bands, tilesDown);
            var tile = new Tile(image, algorithm, null, four);
            for (var tileRow = first; tileRow < last; tileRow++) {
                var y = tileRow * TILE_HEIGHT;
                var rows = Math.min(TILE_HEIGHT, height - y);
                for (var x = 0; x < width; x += TILE_WIDTH) {
                    var columns = Math.min(TILE_WIDTH, width - x);
                    tile.demosaic(window[0] + x, window[1] + y, columns, rows);
                    for (var row = 0; row < rows; row++) {
                        var target = (y + row) * width + x;
                        System.arraycopy(tile.red, row * columns, planes[0], target, columns);
                        System.arraycopy(tile.green, row * columns, planes[1], target, columns);
                        System.arraycopy(tile.blue, row * columns, planes[2], target, columns);
                    }
                }
            }
        });
        return planes;
    }

    /**
     * Holds the working buffers of one thread.
     */
    private static final class Tile {
        /**
         * The red values of the tile.
         */
        final float[] red = new float[TILE_WIDTH * TILE_HEIGHT];

        /**
         * The green values of the tile.
         */
        final float[] green = new float[TILE_WIDTH * TILE_HEIGHT];

        /**
         * The blue values of the tile.
         */
        final float[] blue = new float[TILE_WIDTH * TILE_HEIGHT];

        /**
         * The image.
         */
        private final LinearImage image;

        /**
         * The demosaicing algorithm.
         */
        private final RawDemosaic algorithm;

        /**
         * The color pipeline, or {@code null} when only demosaicing.
         */
        private final ColorPipeline pipeline;

        /**
         * The projection of a four-color image, from {@link #fourColor}, or {@code null}.
         */
        private final float[] four;

        /**
         * The Menon demosaicer, created on first use.
         */
        private MenonDemosaic menon;

        /**
         * The stencil demosaicer, created on first use.
         */
        private PatternDemosaic pattern;

        /**
         * Constructs the buffers of one thread.
         *
         * @param image the linear image the tile reads
         * @param algorithm the demosaicing algorithm
         * @param pipeline the color pipeline, or {@code null}
         * @param four the projection of a four-color image, or {@code null}
         */
        Tile(LinearImage image, RawDemosaic algorithm, ColorPipeline pipeline, float[] four) {
            this.image = image;
            this.algorithm = algorithm;
            this.pipeline = pipeline;
            this.four = four;
        }

        /**
         * Demosaics a window of the image into the tile.
         *
         * @param x0 the first column
         * @param y0 the first row
         * @param width the width of the window in pixels
         * @param height the height of the window in pixels
         */
        void demosaic(int x0, int y0, int width, int height) {
            var data = image.data;
            if (image.samplesPerPixel == 3) {
                for (var row = 0; row < height; row++) {
                    var source = ((y0 + row) * image.width + x0) * 3;
                    for (var column = 0; column < width; column++) {
                        var at = row * width + column;
                        red[at] = data[source + 3 * column];
                        green[at] = data[source + 3 * column + 1];
                        blue[at] = data[source + 3 * column + 2];
                    }
                }
                return;
            }
            if (image.cfa == null) {
                for (var row = 0; row < height; row++) {
                    var source = (y0 + row) * image.width + x0;
                    System.arraycopy(data, source, red, row * width, width);
                    System.arraycopy(data, source, green, row * width, width);
                    System.arraycopy(data, source, blue, row * width, width);
                }
                return;
            }
            var small = image.width < 16 || image.height < 16;
            if (algorithm == RawDemosaic.MENON && image.cfa.isBayer() && !small) {
                if (menon == null) {
                    menon = new MenonDemosaic();
                }
                menon.run(image, x0, y0, width, height, red, green, blue);
                return;
            }
            if (pattern == null) {
                pattern = new PatternDemosaic(image);
            }
            if (four != null) {
                pattern.projected(image, x0, y0, width, height, red, green, blue, four);
            } else if (algorithm == RawDemosaic.MENON && !image.cfa.isBayer()) {
                pattern.colorDifference(image, x0, y0, width, height, red, green, blue);
            } else if (image.cfa.isBayer()) {
                bayer(x0, y0, width, height);
            } else {
                pattern.bilinear(image, x0, y0, width, height, red, green, blue);
            }
        }

        /**
         * Interpolates a window of a Bayer image bilinearly: interior photosites with the
         * {@link BayerKernel}, those on the image's outer row and column with the stencils.
         *
         * @param x0 the first column
         * @param y0 the first row
         * @param width the width of the window in pixels
         * @param height the height of the window in pixels
         */
        private void bayer(int x0, int y0, int width, int height) {
            var w = image.width;
            var h = image.height;
            var start = Math.max(x0, 1);
            var end = Math.min(x0 + width, w - 1);
            for (var row = 0; row < height; row++) {
                var y = y0 + row;
                var line = row * width;
                if (y < 1 || y >= h - 1 || end - start < 2) {
                    for (var column = 0; column < width; column++) {
                        pattern.pixel(image, x0 + column, y, red, green, blue, line + column);
                    }
                    continue;
                }
                for (var x = x0; x < start; x++) {
                    pattern.pixel(image, x, y, red, green, blue, line + x - x0);
                }
                var first = image.plane(y, start);
                var second = image.plane(y, start + 1);
                var pair = first == 0 ? BayerKernel.RED_GREEN : first == 2 ? BayerKernel.BLUE_GREEN
                        : second == 0 ? BayerKernel.GREEN_RED : BayerKernel.GREEN_BLUE;
                ColorKernels.BAYER.row(image.data, (y - 1) * w + start, y * w + start, (y + 1) * w + start, end - start,
                        pair, red, green, blue, line + start - x0);
                for (var x = end; x < x0 + width; x++) {
                    pattern.pixel(image, x, y, red, green, blue, line + x - x0);
                }
            }
        }

        /**
         * Renders a window at half size: each output pixel from one 2x2 Bayer quad, its two
         * greens averaged.
         *
         * @param x0 the first column of the source window
         * @param y0 the first row of the source window
         * @param width the output width
         * @param height the output height
         */
        void halfSize(int x0, int y0, int width, int height) {
            var data = image.data;
            var w = image.width;
            for (var row = 0; row < height; row++) {
                var y = y0 + 2 * row;
                for (var column = 0; column < width; column++) {
                    var x = x0 + 2 * column;
                    var at = row * width + column;
                    var r = 0f;
                    var g = 0f;
                    var b = 0f;
                    for (var dy = 0; dy < 2; dy++) {
                        for (var dx = 0; dx < 2; dx++) {
                            var value = data[(y + dy) * w + x + dx];
                            switch (image.plane(y + dy, x + dx)) {
                                case 0 -> r += value;
                                case 1 -> g += value;
                                default -> b += value;
                            }
                        }
                    }
                    red[at] = r;
                    green[at] = g * 0.5f;
                    blue[at] = b;
                }
            }
        }

        /**
         * Copies a window of full planes into the tile.
         *
         * @param planes the red, green and blue planes
         * @param stride the width of the planes
         * @param x0 the first column
         * @param y0 the first row
         * @param width the width of the window in pixels
         * @param height the height of the window in pixels
         */
        void copy(float[][] planes, int stride, int x0, int y0, int width, int height) {
            for (var row = 0; row < height; row++) {
                var source = (y0 + row) * stride + x0;
                System.arraycopy(planes[0], source, red, row * width, width);
                System.arraycopy(planes[1], source, green, row * width, width);
                System.arraycopy(planes[2], source, blue, row * width, width);
            }
        }

        /**
         * Maps the tile's camera values to output pixels and stores them in the picture.
         *
         * @param picture the output picture, with 8 or 16 bits per sample
         * @param x0 the first output column
         * @param y0 the first output row
         * @param width the width of the window in output pixels
         * @param height the height of the window in output pixels
         */
        void output(Picture picture, int x0, int y0, int width, int height) {
            var count = width * height;
            pipeline.balance(red, green, blue, count);
            var high = picture instanceof Rgba16Picture;
            var scale = high ? Transfer.SCALE_16 : Transfer.SCALE_8;
            if (pipeline.profiled) {
                pipeline.profile(red, green, blue, count);
                KERNEL.scale(red, green, blue, count, scale);
            } else {
                KERNEL.transform(red, green, blue, count, pipeline.matrix, scale);
            }
            if (high) {
                var table = Transfer.table16(pipeline.space);
                var out = (Rgba16Picture) picture;
                var pixels = out.pixels();
                for (var row = 0; row < height; row++) {
                    var target = (y0 + row) * out.stride() + x0 * 4;
                    var source = row * width;
                    for (var column = 0; column < width; column++) {
                        var at = source + column;
                        pixels[target] = table[(int) (red[at] + 0.5f)];
                        pixels[target + 1] = table[(int) (green[at] + 0.5f)];
                        pixels[target + 2] = table[(int) (blue[at] + 0.5f)];
                        pixels[target + 3] = (short) 0xFFFF;
                        target += 4;
                    }
                }
            } else {
                var table = Transfer.table8(pipeline.space);
                var out = (RgbaPicture) picture;
                var pixels = out.pixels();
                for (var row = 0; row < height; row++) {
                    var target = (y0 + row) * out.stride() + x0 * 4;
                    var source = row * width;
                    for (var column = 0; column < width; column++) {
                        var at = source + column;
                        pixels[target] = table[(int) (red[at] + 0.5f)];
                        pixels[target + 1] = table[(int) (green[at] + 0.5f)];
                        pixels[target + 2] = table[(int) (blue[at] + 0.5f)];
                        pixels[target + 3] = (byte) 0xFF;
                        target += 4;
                    }
                }
            }
        }
    }
}
