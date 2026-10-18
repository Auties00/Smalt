package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Captures a monitor with GDI: a {@code BitBlt} of its rectangle of the virtual screen into a
 * device-independent bitmap, with the cursor drawn on top.
 *
 * <p>GDI works where desktop duplication does not (older drivers, some remote sessions, more
 * concurrent captures than duplication allows) but copies the whole rectangle on every frame, so
 * it serves as the fallback. The monitor's rectangle is re-read once a second; when it moves the
 * copy follows it, when its size changes the bitmap is recreated and the frames are rescaled to
 * the session's size, and when the monitor disappears for ten seconds the grabber reports
 * {@link Result#ENDED}.
 */
final class GdiGrabber implements ScreenGrabber {
    /**
     * The interval between checks of the monitor's rectangle.
     */
    private static final long BOUNDS_INTERVAL_NANOS = 1_000_000_000L;

    /**
     * How long the monitor may be missing before the grabber reports {@link Result#ENDED}.
     */
    private static final long MISSING_LIMIT_NANOS = 10_000_000_000L;

    /**
     * The GDI device name of the monitor.
     */
    private final String deviceName;

    /**
     * The arena of the structures that live as long as the grabber.
     */
    private final Arena arena;

    /**
     * The {@code CURSORINFO} receiving the cursor.
     */
    private final MemorySegment cursorInfo;

    /**
     * The {@code ICONINFO} receiving the cursor's hot spot.
     */
    private final MemorySegment iconInfo;

    /**
     * The {@code BITMAPINFO} describing the bitmap.
     */
    private final MemorySegment bitmapInfo;

    /**
     * A pointer-sized slot receiving the bitmap's pixel address.
     */
    private final MemorySegment slot;

    /**
     * The converter of the captured pixels.
     */
    private final BgraConverter converter = new BgraConverter();

    /**
     * The width of the monitor when the grabber was opened.
     */
    private final int openWidth;

    /**
     * The height of the monitor when the grabber was opened.
     */
    private final int openHeight;

    /**
     * The device context of the virtual screen.
     */
    private MemorySegment screenDc = MemorySegment.NULL;

    /**
     * The memory device context holding the bitmap.
     */
    private MemorySegment memoryDc = MemorySegment.NULL;

    /**
     * The device-independent bitmap receiving the copies.
     */
    private MemorySegment bitmap = MemorySegment.NULL;

    /**
     * The object the bitmap replaced in {@link #memoryDc}.
     */
    private MemorySegment replaced = MemorySegment.NULL;

    /**
     * The bitmap's pixels.
     */
    private MemorySegment pixels = MemorySegment.NULL;

    /**
     * The left edge of the monitor on the virtual screen.
     */
    private int x;

    /**
     * The top edge of the monitor on the virtual screen.
     */
    private int y;

    /**
     * The width of the bitmap.
     */
    private int width;

    /**
     * The height of the bitmap.
     */
    private int height;

    /**
     * The time of the next check of the monitor's rectangle.
     */
    private long nextBoundsCheck;

    /**
     * When the monitor was first found missing, or {@code -1} while it exists.
     */
    private long missingSince = -1;

    /**
     * The cursor whose hot spot is cached.
     */
    private MemorySegment cursor = MemorySegment.NULL;

    /**
     * The horizontal hot spot of {@link #cursor}.
     */
    private int hotSpotX;

    /**
     * The vertical hot spot of {@link #cursor}.
     */
    private int hotSpotY;

    /**
     * Opens a grabber for a monitor.
     *
     * @param deviceName the monitor's GDI device name
     * @param fallback the rectangle to capture when the device has no readable mode, or
     * {@code null} to fail instead
     * @throws MediaException.Io if GDI is unavailable, the device has no readable mode and no
     * fallback is given, or the bitmap cannot be created
     */
    GdiGrabber(String deviceName, Displays.Bounds fallback) {
        if (!Gdi.available()) {
            throw Com.failure("GDI is not available", 0x80004005);
        }
        this.deviceName = deviceName;
        this.arena = Arena.ofConfined();
        try {
            this.cursorInfo = arena.allocate(Gdi.CURSOR_INFO_SIZE, 8);
            this.iconInfo = arena.allocate(Gdi.ICON_INFO_SIZE, 8);
            this.bitmapInfo = arena.allocate(Gdi.BITMAP_INFO_SIZE, 4);
            this.slot = arena.allocate(ADDRESS);
            Displays.Bounds bounds;
            try (var temporary = Arena.ofConfined()) {
                bounds = Displays.gdiBounds(deviceName, temporary);
            }
            if (bounds == null) {
                if (fallback == null) {
                    throw Com.failure("no screen is named " + deviceName, 0);
                }
                bounds = fallback;
            }
            this.openWidth = bounds.width();
            this.openHeight = bounds.height();
            this.x = bounds.x();
            this.y = bounds.y();
            this.screenDc = Gdi.screenDc();
            if (screenDc.equals(MemorySegment.NULL)) {
                throw Com.failure("GetDC failed", 0);
            }
            this.memoryDc = Gdi.createCompatibleDc(screenDc);
            if (memoryDc.equals(MemorySegment.NULL)) {
                throw Com.failure("CreateCompatibleDC failed", 0);
            }
            createBitmap(bounds.width(), bounds.height());
            this.nextBoundsCheck = System.nanoTime() + BOUNDS_INTERVAL_NANOS;
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /**
     * Replaces the bitmap with one of a new size.
     *
     * @throws MediaException.Io if the bitmap cannot be created
     */
    private void createBitmap(int width, int height) {
        releaseBitmap();
        bitmapInfo.fill((byte) 0);
        bitmapInfo.set(JAVA_INT, 0, 40);
        bitmapInfo.set(JAVA_INT, 4, width);
        bitmapInfo.set(JAVA_INT, 8, -height);
        bitmapInfo.set(JAVA_SHORT, 12, (short) 1);
        bitmapInfo.set(JAVA_SHORT, 14, (short) 32);
        slot.set(ADDRESS, 0, MemorySegment.NULL);
        bitmap = Gdi.createDibSection(memoryDc, bitmapInfo, slot);
        if (bitmap.equals(MemorySegment.NULL) || slot.get(ADDRESS, 0).equals(MemorySegment.NULL)) {
            bitmap = MemorySegment.NULL;
            throw Com.failure("CreateDIBSection failed", 0);
        }
        pixels = slot.get(ADDRESS, 0).reinterpret(4L * width * height);
        replaced = Gdi.selectObject(memoryDc, bitmap);
        this.width = width;
        this.height = height;
    }

    /**
     * Deselects and deletes the bitmap.
     */
    private void releaseBitmap() {
        if (!bitmap.equals(MemorySegment.NULL)) {
            Gdi.selectObject(memoryDc, replaced);
            Gdi.deleteObject(bitmap);
            bitmap = MemorySegment.NULL;
            pixels = MemorySegment.NULL;
        }
    }

    @Override
    public int width() {
        return openWidth;
    }

    @Override
    public int height() {
        return openHeight;
    }

    @Override
    public Result grab(YuvPicture out) {
        var now = System.nanoTime();
        if (now - nextBoundsCheck >= 0) {
            nextBoundsCheck = now + BOUNDS_INTERVAL_NANOS;
            Displays.Bounds bounds;
            try (var temporary = Arena.ofConfined()) {
                bounds = Displays.gdiBounds(deviceName, temporary);
            }
            if (bounds == null) {
                if (missingSince < 0) {
                    missingSince = now;
                }
                return now - missingSince > MISSING_LIMIT_NANOS ? Result.ENDED : Result.UNCHANGED;
            }
            missingSince = -1;
            x = bounds.x();
            y = bounds.y();
            if (bounds.width() != width || bounds.height() != height) {
                createBitmap(bounds.width(), bounds.height());
            }
        } else if (missingSince >= 0) {
            return Result.UNCHANGED;
        }
        if (!Gdi.copyScreen(memoryDc, width, height, screenDc, x, y)) {
            return Result.UNCHANGED;
        }
        drawCursor();
        Gdi.flush();
        return converter.convert(pixels, 0, 4L * width, width, height, 0, null, out) ? Result.CHANGED : Result.UNCHANGED;
    }

    /**
     * Draws the cursor into the bitmap when it is showing.
     */
    private void drawCursor() {
        cursorInfo.fill((byte) 0);
        cursorInfo.set(JAVA_INT, 0, Gdi.CURSOR_INFO_SIZE);
        if (!Gdi.cursorInfo(cursorInfo) || (cursorInfo.get(JAVA_INT, 4) & Gdi.CURSOR_SHOWING) == 0) {
            return;
        }
        var handle = cursorInfo.get(ADDRESS, 8);
        if (handle.equals(MemorySegment.NULL)) {
            return;
        }
        if (!handle.equals(cursor)) {
            iconInfo.fill((byte) 0);
            if (!Gdi.iconInfo(handle, iconInfo)) {
                return;
            }
            hotSpotX = iconInfo.get(JAVA_INT, 4);
            hotSpotY = iconInfo.get(JAVA_INT, 8);
            Gdi.deleteObject(iconInfo.get(ADDRESS, 16));
            Gdi.deleteObject(iconInfo.get(ADDRESS, 24));
            cursor = handle;
        }
        var left = cursorInfo.get(JAVA_INT, 16) - hotSpotX - x;
        var top = cursorInfo.get(JAVA_INT, 20) - hotSpotY - y;
        Gdi.drawCursor(memoryDc, left, top, handle);
    }

    @Override
    public void close() {
        releaseBitmap();
        Gdi.deleteDc(memoryDc);
        memoryDc = MemorySegment.NULL;
        if (!screenDc.equals(MemorySegment.NULL)) {
            Gdi.releaseScreenDc(screenDc);
            screenDc = MemorySegment.NULL;
        }
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
