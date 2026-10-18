package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Grabs a rectangle of an X11 root window and converts it to BT.709 limited-range 4:2:0.
 *
 * <p>The grabber owns a private connection to the display. When the server supports MIT-SHM, the
 * pixels are copied by the server into a System V shared memory segment attached to both
 * processes ({@code XShmCreateImage}, {@code XShmAttach}, {@code XShmGetImage}); the segment is
 * marked for removal as soon as the server attached it, so it cannot outlive the process. When
 * shared memory is unavailable (a remote server, or a failed attach) every grab transfers the
 * pixels over the connection with {@code XGetImage}. The image must be a 32-bit
 * {@code ZPixmap} with one byte per channel ({@link RgbLayout}), which every TrueColor visual of
 * depth 24 or 32 provides.
 *
 * <p>A grab that fails because the rectangle left the root window (the screen was unplugged or
 * the resolution reduced) or because the connection was lost reports that the screen no longer
 * exists; other failures raise {@link MediaException.Io}.
 */
final class X11ScreenGrabber implements ScreenGrabber {
    /**
     * The {@code LSBFirst} byte order of an X11 image.
     */
    static final int LSB_FIRST = 0;

    /**
     * The {@code MSBFirst} byte order of an X11 image.
     */
    static final int MSB_FIRST = 1;

    /**
     * The bindings.
     */
    private final X11 x11;

    /**
     * The private display connection.
     */
    private final MemorySegment display;

    /**
     * The root window.
     */
    private final long root;

    /**
     * The left edge of the grabbed rectangle.
     */
    private final int x;

    /**
     * The top edge of the grabbed rectangle.
     */
    private final int y;

    /**
     * The width of the grabbed rectangle and of the pictures, even.
     */
    private final int width;

    /**
     * The height of the grabbed rectangle and of the pictures, even.
     */
    private final int height;

    /**
     * The shared memory image, or {@code null} when grabbing with {@code XGetImage}.
     */
    private final SharedImage shared;

    /**
     * The row stride of the grabbed pixels.
     */
    private final int bytesPerLine;

    /**
     * The positions of the color samples in a pixel.
     */
    private final RgbLayout layout;

    /**
     * The grabbed pixels, copied out of native memory.
     */
    private final byte[] pixels;

    /**
     * The picture the pixels are converted into.
     */
    private final YuvPicture target;

    private final ConversionKernel kernel = ConversionKernel.INSTANCE;

    /**
     * Whether the grabber is closed.
     */
    private boolean closed;

    /**
     * Assembles a grabber over an open display.
     *
     * @param x11 the bindings
     * @param root the root window
     * @param x the left edge
     * @param y the top edge
     * @param width the even width
     * @param height the even height
     * @param shared the shared memory image, or {@code null}
     * @param bytesPerLine the row stride of the grabbed pixels
     * @param layout the pixel layout
     */
    private X11ScreenGrabber(X11 x11, MemorySegment display, long root, int x, int y, int width, int height,
                             SharedImage shared, int bytesPerLine, RgbLayout layout) {
        this.x11 = x11;
        this.display = display;
        this.root = root;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.shared = shared;
        this.bytesPerLine = bytesPerLine;
        this.layout = layout;
        this.pixels = new byte[Math.multiplyExact(bytesPerLine, height)];
        this.target = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, YuvCoefficients.BT709_LIMITED.color());
    }

    /**
     * Opens a grabber for a screen.
     *
     * @param x11 the bindings
     * @return the grabber
     * @throws MediaException.Io if the X server cannot be reached or the screen lies
     * outside the root window
     * @throws MediaException.Unsupported if the screen's pixel format is not a 32-bit TrueColor
     * format or the screen is smaller than 2x2
     */
    static X11ScreenGrabber open(X11 x11, CaptureDevice.Screen screen) {
        var display = x11.openDisplay();
        if (display.equals(MemorySegment.NULL)) {
            throw new MediaException.Io("cannot connect to the X server",
                    new IOException("XOpenDisplay(\"" + System.getenv("DISPLAY") + "\") failed"));
        }
        X11ErrorTrap.register(x11, display);
        SharedImage shared = null;
        try {
            var number = x11.defaultScreen(display);
            var root = x11.rootWindow(display, number);
            var left = Math.max(screen.x(), 0);
            var top = Math.max(screen.y(), 0);
            var right = (int) Math.min((long) screen.x() + screen.width(), x11.displayWidth(display, number));
            var bottom = (int) Math.min((long) screen.y() + screen.height(), x11.displayHeight(display, number));
            if (right <= left || bottom <= top) {
                throw new MediaException.Io("the screen " + screen.id() + " is not on the display",
                        new IOException("rectangle outside the root window"));
            }
            var width = (right - left) & ~1;
            var height = (bottom - top) & ~1;
            if (width < 2 || height < 2) {
                throw new MediaException.Unsupported("screen size", "the screen " + screen.id() + " is smaller than 2x2 pixels");
            }
            LimitChecks.checkPicture(MediaLimits.DEFAULT, width, height);
            if (x11.shmQueryExtension(display)) {
                shared = SharedImage.create(x11, display, number, width, height);
            }
            MemorySegment image;
            if (shared != null) {
                image = shared.image();
            } else {
                image = x11.getImage(display, root, left, top, width, height);
                X11ErrorTrap.takeError(display);
                if (image.equals(MemorySegment.NULL)) {
                    throw new MediaException.Io("cannot grab the screen " + screen.id(), new IOException("XGetImage failed"));
                }
            }
            var header = image.reinterpret(X11.IMAGE.byteSize());
            var layout = layoutOf(header.get(JAVA_INT, X11.IMAGE_BITS_PER_PIXEL), header.get(JAVA_INT, X11.IMAGE_BYTE_ORDER),
                    header.get(JAVA_LONG, X11.IMAGE_RED_MASK), header.get(JAVA_LONG, X11.IMAGE_GREEN_MASK),
                    header.get(JAVA_LONG, X11.IMAGE_BLUE_MASK));
            var bytesPerLine = header.get(JAVA_INT, X11.IMAGE_BYTES_PER_LINE);
            if (shared == null) {
                x11.destroyImage(image);
            }
            if (layout == null) {
                throw new MediaException.Unsupported("x11 visual", "the screen's pixel format is not a 32-bit TrueColor format");
            }
            if (bytesPerLine < 4 * width) {
                throw new MediaException.Io("the X server returned a malformed image",
                        new IOException("bytes_per_line " + bytesPerLine + " for " + width + " pixels"));
            }
            LimitChecks.checkAllocation(MediaLimits.DEFAULT, (long) bytesPerLine * height);
            return new X11ScreenGrabber(x11, display, root, left, top, width, height, shared, bytesPerLine, layout);
        } catch (RuntimeException | Error failure) {
            if (shared != null) {
                shared.close(x11, display);
            }
            x11.closeDisplay(display);
            X11ErrorTrap.unregister(display);
            throw failure;
        }
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public YuvPicture grab() {
        if (closed || X11ErrorTrap.broken(display)) {
            return null;
        }
        if (shared != null) {
            var grabbed = x11.shmGetImage(display, root, shared.image(), x, y);
            var error = X11ErrorTrap.takeError(display);
            if (!grabbed || error != 0) {
                return failed("XShmGetImage", error);
            }
            MemorySegment.copy(shared.memory(), JAVA_BYTE, 0, pixels, 0, pixels.length);
        } else {
            var image = x11.getImage(display, root, x, y, width, height);
            var error = X11ErrorTrap.takeError(display);
            if (image.equals(MemorySegment.NULL)) {
                return failed("XGetImage", error);
            }
            try {
                var header = image.reinterpret(X11.IMAGE.byteSize());
                if (header.get(JAVA_INT, X11.IMAGE_BYTES_PER_LINE) != bytesPerLine) {
                    throw new MediaException.Io("the screen's pixel format changed",
                            new IOException("bytes_per_line changed to " + header.get(JAVA_INT, X11.IMAGE_BYTES_PER_LINE)));
                }
                var data = MemorySegment.ofAddress(header.get(JAVA_LONG, X11.IMAGE_DATA)).reinterpret(pixels.length);
                MemorySegment.copy(data, JAVA_BYTE, 0, pixels, 0, pixels.length);
            } finally {
                x11.destroyImage(image);
            }
        }
        convert();
        return target;
    }

    /**
     * Converts the grabbed pixels into the target picture.
     */
    private void convert() {
        var y = target.y();
        var u = target.u();
        var v = target.v();
        var chromaWidth = width / 2;
        var coefficients = YuvCoefficients.BT709_LIMITED;
        for (var row = 0; row < height; row += 2) {
            var upper = row * bytesPerLine;
            var lower = upper + bytesPerLine;
            kernel.rgbToLuma(pixels, upper, width, layout, coefficients, y, row * width);
            kernel.rgbToLuma(pixels, lower, width, layout, coefficients, y, (row + 1) * width);
            kernel.rgbToChroma(pixels, upper, lower, chromaWidth, layout, coefficients, u, v, (row / 2) * chromaWidth);
        }
    }

    /**
     * Derives the layout of an X11 image's pixels.
     *
     * @param bitsPerPixel the image's {@code bits_per_pixel}
     * @param byteOrder the image's {@code byte_order}, {@link #LSB_FIRST} or {@link #MSB_FIRST}
     * @param redMask the image's {@code red_mask}
     * @param greenMask the image's {@code green_mask}
     * @param blueMask the image's {@code blue_mask}
     * @return the layout, or {@code null} unless the image has 32 bits per pixel, a known byte order
     * and masks that each select one distinct whole byte
     */
    static RgbLayout layoutOf(int bitsPerPixel, int byteOrder, long redMask, long greenMask, long blueMask) {
        if (byteOrder != LSB_FIRST && byteOrder != MSB_FIRST) {
            return null;
        }
        return RgbLayout.fromMasks(bitsPerPixel, byteOrder == LSB_FIRST, redMask, greenMask, blueMask);
    }

    /**
     * Decides what a failed grab means.
     *
     * @param request the failed request
     * @param error the recorded X error code, or {@code 0}
     * @return {@code null} when the screen no longer exists
     * @throws MediaException.Io when the screen still exists
     */
    private YuvPicture failed(String request, int error) {
        if (X11ErrorTrap.broken(display)) {
            return null;
        }
        var size = x11.windowSize(display, root);
        X11ErrorTrap.takeError(display);
        if (size == null || (long) x + width > size[0] || (long) y + height > size[1]) {
            return null;
        }
        throw new MediaException.Io("cannot grab the screen", new IOException(request + " failed with X error " + error));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (shared != null) {
            shared.close(x11, display);
        }
        x11.closeDisplay(display);
        X11ErrorTrap.unregister(display);
    }

    /**
     * Holds a shared memory image: the {@code XImage}, its {@code XShmSegmentInfo} and the
     * attached segment.
     *
     * @param arena the arena of the segment information
     * @param info the {@code XShmSegmentInfo}
     * @param memory the attached segment
     */
    private record SharedImage(Arena arena, MemorySegment info, MemorySegment image, MemorySegment memory) {
        /**
         * Creates a shared memory image and attaches its segment to the server.
         *
         * @param x11 the bindings
         * @param screen the screen number
         * @return the image, or {@code null} when shared memory cannot be used
         */
        static SharedImage create(X11 x11, MemorySegment display, int screen, int width, int height) {
            var arena = Arena.ofShared();
            var info = arena.allocate(X11.SHM_SEGMENT_INFO);
            var image = x11.shmCreateImage(display, x11.defaultVisual(display, screen), x11.defaultDepth(display, screen),
                    info, width, height);
            if (image.equals(MemorySegment.NULL)) {
                arena.close();
                return null;
            }
            var header = image.reinterpret(X11.IMAGE.byteSize());
            var size = (long) header.get(JAVA_INT, X11.IMAGE_BYTES_PER_LINE) * height;
            var posix = new Posix();
            var id = size <= 0 ? -1 : posix.shmget(size);
            if (id < 0) {
                x11.destroyImage(image);
                arena.close();
                return null;
            }
            var memory = posix.shmat(id, size);
            if (memory == null) {
                posix.shmRemove(id);
                x11.destroyImage(image);
                arena.close();
                return null;
            }
            info.set(JAVA_INT, X11.SHM_ID, id);
            info.set(JAVA_LONG, X11.SHM_ADDRESS, memory.address());
            info.set(JAVA_INT, X11.SHM_READ_ONLY, 0);
            header.set(JAVA_LONG, X11.IMAGE_DATA, memory.address());
            X11ErrorTrap.takeError(display);
            var attached = x11.shmAttach(display, info);
            x11.sync(display);
            var error = X11ErrorTrap.takeError(display);
            posix.shmRemove(id);
            if (!attached || error != 0) {
                posix.shmdt(memory);
                x11.destroyImage(image);
                arena.close();
                return null;
            }
            return new SharedImage(arena, info, image, memory);
        }

        /**
         * Detaches the segment from the server and this process and frees the image.
         *
         * @param x11 the bindings
         */
        void close(X11 x11, MemorySegment display) {
            x11.shmDetach(display, info);
            x11.sync(display);
            X11ErrorTrap.takeError(display);
            x11.destroyImage(image);
            new Posix().shmdt(memory);
            arena.close();
        }
    }
}
