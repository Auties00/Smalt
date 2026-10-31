package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.capture.internal.Pictures;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Grabs an X11 window and converts it to BT.709 limited-range 4:2:0.
 *
 * <p>When the server offers Composite 0.2 or later the window is redirected to off-screen storage
 * ({@code XCompositeRedirectWindow}, automatic mode) and its pixmap ({@code XCompositeNameWindowPixmap}) is
 * read, so the image is complete also where other windows cover it; without Composite the window itself is
 * read, and covered parts hold whatever the server returns. Every grab reads the window's attributes: a
 * window that was destroyed ends the capture, one that is not viewable (minimized, on another desktop)
 * repeats the last picture, and one that changed size has its pixmap named again and its image rescaled to
 * the size the session opened with. The pointer is drawn as {@link X11ScreenGrabber} draws it.
 *
 * @implNote This implementation reads the pixels with {@code XGetImage} on every grab rather than through
 * MIT-SHM, because the pixmap of a redirected window changes with the window's size.
 */
final class X11WindowGrabber implements ScreenGrabber {
    private final X11Windowing windowing;

    private final X11 x11;

    private final MemorySegment display;

    private final long root;

    private final long window;

    private final String name;

    /**
     * The region of the options, or {@code null} for the whole window.
     */
    private final ScreenCaptureOptions.Region region;

    private final boolean composite;

    private final X11Cursor cursor;

    private final Arena arena;

    /**
     * The attributes read at every grab.
     */
    private final MemorySegment attributes;

    /**
     * The scratch memory of the coordinate translations.
     */
    private final MemorySegment translation;

    /**
     * The picture delivered, of the size the session opened with.
     */
    private final YuvPicture target;

    private final ConversionKernel kernel = ConversionKernel.INSTANCE;

    /**
     * The picture a differently sized image is converted into before rescaling.
     */
    private YuvPicture scratch;

    /**
     * The grabbed pixels, grown as needed.
     */
    private byte[] pixels = new byte[0];

    /**
     * The grabbed pixels expanded to 4 bytes when their format is not converted in place, grown as
     * needed.
     */
    private byte[] expanded = new byte[0];

    /**
     * The named pixmap of the window, or {@code 0}.
     */
    private long pixmap;

    private int pixmapWidth;

    private int pixmapHeight;

    /**
     * Whether a picture was grabbed yet.
     */
    private boolean grabbed;

    private boolean closed;

    private X11WindowGrabber(X11Windowing windowing, MemorySegment display, long root, long window, String name,
                             ScreenCaptureOptions options, boolean composite, X11Cursor cursor, Arena arena,
                             MemorySegment attributes, CaptureArea area) {
        this.windowing = windowing;
        this.x11 = windowing.x11();
        this.display = display;
        this.root = root;
        this.window = window;
        this.name = name;
        this.region = options.region().orElse(null);
        this.composite = composite;
        this.cursor = cursor;
        this.arena = arena;
        this.attributes = attributes;
        this.translation = arena.allocate(X11Windowing.TRANSLATION_SIZE, 8);
        this.target = YuvPicture.allocate(area.width(), area.height(), ChromaFormat.YUV420, false,
                YuvCoefficients.BT709_LIMITED.color());
    }

    /**
     * Opens a grabber for a window.
     *
     * @param windowing the bindings
     * @param window the window to grab, as listed
     * @param options the region and pointer settings
     * @return the grabber
     * @throws MediaException.Io if the X server cannot be reached or the window no longer exists
     * @throws MediaException.Unsupported if the window or the part of the region on it is smaller than 2x2
     */
    static X11WindowGrabber open(X11Windowing windowing, CaptureDevice.Window window, ScreenCaptureOptions options) {
        long id;
        try {
            id = Long.parseUnsignedLong(window.id());
        } catch (NumberFormatException exception) {
            throw new MediaException.Io("the window " + window.name() + " is not an X11 window",
                    new IOException("invalid window id " + window.id()));
        }
        var x11 = windowing.x11();
        var display = x11.openDisplay();
        if (display.equals(MemorySegment.NULL)) {
            throw new MediaException.Io("cannot connect to the X server",
                    new IOException("XOpenDisplay(\"" + System.getenv("DISPLAY") + "\") failed"));
        }
        X11ErrorTrap.register(x11, display);
        var arena = Arena.ofShared();
        var redirected = false;
        try {
            var attributes = arena.allocate(X11Windowing.WINDOW_ATTRIBUTES);
            var root = x11.rootWindow(display, x11.defaultScreen(display));
            var found = windowing.attributes(display, id, attributes);
            if (X11ErrorTrap.takeError(display) != 0 || !found) {
                throw gone(window.name());
            }
            var width = attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_WIDTH);
            var height = attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_HEIGHT);
            var area = CaptureArea.of(options, width, height, "the window " + window.name());
            LimitChecks.checkPicture(MediaLimits.DEFAULT, area.width(), area.height());
            var composite = windowing.hasComposite(display);
            if (composite) {
                windowing.redirect(display, id);
                x11.sync(display);
                if (X11ErrorTrap.takeError(display) != 0) {
                    composite = false;
                } else {
                    redirected = true;
                }
            }
            var cursor = options.cursor() && windowing.hasFixes(display) ? new X11Cursor(windowing, display) : null;
            return new X11WindowGrabber(windowing, display, root, id, window.name(), options, composite, cursor, arena,
                    attributes, area);
        } catch (RuntimeException | Error failure) {
            if (redirected) {
                windowing.unredirect(display, id);
                x11.sync(display);
            }
            arena.close();
            x11.closeDisplay(display);
            X11ErrorTrap.unregister(display);
            throw failure;
        }
    }

    /**
     * Returns the failure of a window that no longer exists.
     */
    private static MediaException.Io gone(String name) {
        return new MediaException.Io("the window " + name + " no longer exists", new IOException("BadWindow"));
    }

    /**
     * Returns whether the grabber reads the redirected window's pixmap.
     *
     * @return whether Composite is in use
     */
    boolean composited() {
        return composite;
    }

    @Override
    public int width() {
        return target.width();
    }

    @Override
    public int height() {
        return target.height();
    }

    @Override
    public YuvPicture grab() {
        if (closed || X11ErrorTrap.broken(display)) {
            return null;
        }
        var ok = windowing.attributes(display, window, attributes);
        if (X11ErrorTrap.takeError(display) != 0 || !ok) {
            return null;
        }
        if (attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_MAP_STATE) != X11Windowing.IS_VIEWABLE) {
            return grabbed ? target : blank();
        }
        var width = attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_WIDTH);
        var height = attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_HEIGHT);
        var border = attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_BORDER);
        var left = 0;
        var top = 0;
        var right = width;
        var bottom = height;
        if (region != null) {
            left = region.x();
            top = region.y();
            right = (int) Math.min((long) left + region.width(), width);
            bottom = (int) Math.min((long) top + region.height(), height);
        }
        var areaWidth = (right - left) & ~1;
        var areaHeight = (bottom - top) & ~1;
        if (right - left < 2 || bottom - top < 2) {
            return grabbed ? target : blank();
        }
        long drawable = window;
        var originX = left;
        var originY = top;
        if (composite) {
            if (pixmap == 0 || pixmapWidth != width || pixmapHeight != height) {
                releasePixmap();
                pixmap = windowing.nameWindowPixmap(display, window);
                x11.sync(display);
                if (X11ErrorTrap.takeError(display) != 0) {
                    pixmap = 0;
                    return grabbed ? target : blank();
                }
                pixmapWidth = width;
                pixmapHeight = height;
            }
            drawable = pixmap;
            originX += border;
            originY += border;
        }
        var image = x11.getImage(display, drawable, originX, originY, areaWidth, areaHeight);
        var error = X11ErrorTrap.takeError(display);
        if (image.equals(MemorySegment.NULL) || error != 0) {
            if (!image.equals(MemorySegment.NULL)) {
                x11.destroyImage(image);
            }
            return grabbed ? target : blank();
        }
        int bytesPerLine;
        X11PixelFormat format;
        try {
            var header = image.reinterpret(X11.IMAGE.byteSize());
            bytesPerLine = header.get(JAVA_INT, X11.IMAGE_BYTES_PER_LINE);
            format = format(header);
            if (format == null) {
                throw new MediaException.Unsupported("x11 visual", "the window's pixel format is not a TrueColor format of 16,"
                        + " 24 or 32 bits per pixel");
            }
            if (bytesPerLine < (long) format.bytesPerPixel() * areaWidth) {
                throw new MediaException.Io("the X server returned a malformed image",
                        new IOException("bytes_per_line " + bytesPerLine + " for " + areaWidth + " pixels"));
            }
            var size = Math.multiplyExact(bytesPerLine, areaHeight);
            if (pixels.length < size) {
                LimitChecks.checkAllocation(MediaLimits.DEFAULT, size);
                pixels = new byte[size];
            }
            var data = MemorySegment.ofAddress(header.get(JAVA_LONG, X11.IMAGE_DATA)).reinterpret(size);
            MemorySegment.copy(data, JAVA_BYTE, 0, pixels, 0, size);
        } finally {
            x11.destroyImage(image);
        }
        var source = pixels;
        var layout = format.direct();
        if (layout == null) {
            var size = Math.multiplyExact(4 * areaWidth, areaHeight);
            if (expanded.length < size) {
                LimitChecks.checkAllocation(MediaLimits.DEFAULT, size);
                expanded = new byte[size];
            }
            format.expand(pixels, bytesPerLine, areaWidth, areaHeight, expanded);
            source = expanded;
            bytesPerLine = 4 * areaWidth;
            layout = RgbLayout.BGRX;
        }
        if (cursor != null) {
            var translated = windowing.origin(display, window, root, translation);
            X11ErrorTrap.takeError(display);
            if (translated) {
                cursor.draw(source, bytesPerLine, layout, translation.get(JAVA_INT, 0) + left,
                        translation.get(JAVA_INT, 4) + top, areaWidth, areaHeight);
            }
        }
        var out = target;
        if (areaWidth != target.width() || areaHeight != target.height()) {
            scratch = Pictures.reuse(scratch, areaWidth, areaHeight, target.color());
            out = scratch;
        }
        convert(source, bytesPerLine, layout, out);
        if (out != target) {
            Pictures.fit(out, target);
        }
        grabbed = true;
        return target;
    }

    /**
     * Returns the target cleared to black, for a window that has shown nothing yet.
     */
    private YuvPicture blank() {
        Arrays.fill(target.y(), (byte) 16);
        Arrays.fill(target.u(), (byte) 128);
        Arrays.fill(target.v(), (byte) 128);
        return target;
    }

    /**
     * Derives the pixel format of a grabbed image, taking the masks from the window's visual when the
     * image has none, as an image of a pixmap does.
     */
    private X11PixelFormat format(MemorySegment header) {
        var red = header.get(JAVA_LONG, X11.IMAGE_RED_MASK);
        var green = header.get(JAVA_LONG, X11.IMAGE_GREEN_MASK);
        var blue = header.get(JAVA_LONG, X11.IMAGE_BLUE_MASK);
        if (red == 0 && green == 0 && blue == 0) {
            var masks = X11Windows.masks(attributes.get(JAVA_LONG, X11Windowing.ATTRIBUTES_VISUAL));
            if (masks == null) {
                return null;
            }
            red = masks[0];
            green = masks[1];
            blue = masks[2];
        }
        return X11PixelFormat.of(header.get(JAVA_INT, X11.IMAGE_BITS_PER_PIXEL),
                header.get(JAVA_INT, X11.IMAGE_BYTE_ORDER), red, green, blue);
    }

    /**
     * Converts grabbed pixels into a picture of their size.
     */
    private void convert(byte[] pixels, int bytesPerLine, RgbLayout layout, YuvPicture out) {
        var width = out.width();
        var height = out.height();
        var coefficients = YuvCoefficients.BT709_LIMITED;
        for (var row = 0; row < height; row += 2) {
            var upper = row * bytesPerLine;
            var lower = upper + bytesPerLine;
            kernel.rgbToLuma(pixels, upper, width, layout, coefficients, out.y(), row * out.yStride());
            kernel.rgbToLuma(pixels, lower, width, layout, coefficients, out.y(), (row + 1) * out.yStride());
            kernel.rgbToChroma(pixels, upper, lower, width / 2, layout, coefficients, out.u(), out.v(),
                    (row / 2) * out.uvStride());
        }
    }

    /**
     * Frees the named pixmap, if any.
     */
    private void releasePixmap() {
        if (pixmap != 0) {
            windowing.freePixmap(display, pixmap);
            pixmap = 0;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!X11ErrorTrap.broken(display)) {
            releasePixmap();
            if (composite) {
                windowing.unredirect(display, window);
            }
            x11.sync(display);
            X11ErrorTrap.takeError(display);
        }
        x11.closeDisplay(display);
        X11ErrorTrap.unregister(display);
        arena.close();
    }

    @Override
    public String toString() {
        return "X11WindowGrabber[" + name + ", composite=" + composite + "]";
    }
}
