package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.image.YuvPicture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Captures a window with {@code PrintWindow}, asking the window to draw itself into a device-independent
 * bitmap, with the pointer drawn on top.
 *
 * <p>{@code PrintWindow} with {@code PW_RENDERFULLCONTENT} also draws windows rendered with DirectComposition
 * and windows covered by others, but runs the window's own painting on every frame, so it serves as the
 * fallback of {@link WgcGrabber}. The bitmap follows the window rectangle, recreated when the window is
 * resized; a minimized window repeats the last picture, and a destroyed one ends the capture.
 */
final class PrintWindowGrabber implements ScreenGrabber {
    private final MemorySegment window;

    private final Arena arena;

    private final MemorySegment rect;

    private final MemorySegment bitmapInfo;

    private final MemorySegment slot;

    private final MemorySegment cursorInfo;

    private final MemorySegment iconInfo;

    private final BgraConverter converter = new BgraConverter();

    private final WindowImage image;

    private final int openWidth;

    private final int openHeight;

    private boolean cursorShown = true;

    private MemorySegment screenDc = MemorySegment.NULL;

    private MemorySegment memoryDc = MemorySegment.NULL;

    private MemorySegment bitmap = MemorySegment.NULL;

    private MemorySegment replaced = MemorySegment.NULL;

    private MemorySegment pixels = MemorySegment.NULL;

    private int width;

    private int height;

    /**
     * The cursor whose hot spot is cached.
     */
    private MemorySegment cursor = MemorySegment.NULL;

    private int hotSpotX;

    private int hotSpotY;

    /**
     * Opens a grabber for a window.
     *
     * @param window the window handle
     * @throws com.github.auties00.smalt.MediaException.Io if GDI is unavailable, the window cannot be read
     * or the bitmap cannot be created
     */
    PrintWindowGrabber(MemorySegment window) {
        if (!Gdi.available() || !TopWindows.available()) {
            throw Com.failure("GDI is not available", 0x80004005);
        }
        this.window = window;
        this.arena = Arena.ofConfined();
        try {
            this.rect = arena.allocate(JAVA_INT, 4);
            this.bitmapInfo = arena.allocate(Gdi.BITMAP_INFO_SIZE, 4);
            this.slot = arena.allocate(ADDRESS);
            this.cursorInfo = arena.allocate(Gdi.CURSOR_INFO_SIZE, 8);
            this.iconInfo = arena.allocate(Gdi.ICON_INFO_SIZE, 8);
            this.image = new WindowImage(window, arena);
            var visible = image.visibleSize();
            if (visible == null || !TopWindows.windowRect(window, rect)) {
                throw Com.failure("cannot read the bounds of the window", 0);
            }
            this.openWidth = visible[0];
            this.openHeight = visible[1];
            this.screenDc = Gdi.screenDc();
            if (screenDc.equals(MemorySegment.NULL)) {
                throw Com.failure("GetDC failed", 0);
            }
            this.memoryDc = Gdi.createCompatibleDc(screenDc);
            if (memoryDc.equals(MemorySegment.NULL)) {
                throw Com.failure("CreateCompatibleDC failed", 0);
            }
            createBitmap(rectWidth(), rectHeight());
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    private int rectWidth() {
        return Math.max(1, rect.getAtIndex(JAVA_INT, 2) - rect.getAtIndex(JAVA_INT, 0));
    }

    private int rectHeight() {
        return Math.max(1, rect.getAtIndex(JAVA_INT, 3) - rect.getAtIndex(JAVA_INT, 1));
    }

    /**
     * Replaces the bitmap with one of a new size.
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
    public void configure(CaptureArea area, boolean cursor) {
        image.area(area == null || area.covers(openWidth, openHeight) ? null : area);
        this.cursorShown = cursor;
    }

    @Override
    public Result grab(YuvPicture out) {
        if (!TopWindows.exists(window)) {
            return Result.ENDED;
        }
        if (TopWindows.minimized(window) || !TopWindows.windowRect(window, rect)) {
            return Result.UNCHANGED;
        }
        if (rectWidth() != width || rectHeight() != height) {
            createBitmap(rectWidth(), rectHeight());
        }
        pixels.fill((byte) 0);
        if (!TopWindows.print(window, memoryDc)) {
            return Result.UNCHANGED;
        }
        if (cursorShown) {
            drawCursor();
        }
        Gdi.flush();
        var crop = image.crop(width, height);
        if (crop == null) {
            return Result.UNCHANGED;
        }
        return converter.convert(pixels, 0, 4L * width, width, height, 0, null, crop, out) ? Result.CHANGED : Result.UNCHANGED;
    }

    /**
     * Draws the pointer into the bitmap when it is showing.
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
        var left = cursorInfo.get(JAVA_INT, 16) - hotSpotX - rect.getAtIndex(JAVA_INT, 0);
        var top = cursorInfo.get(JAVA_INT, 20) - hotSpotY - rect.getAtIndex(JAVA_INT, 1);
        Gdi.drawCursor(memoryDc, left, top, handle);
    }

    @Override
    public void close() {
        if (!memoryDc.equals(MemorySegment.NULL)) {
            releaseBitmap();
            Gdi.deleteDc(memoryDc);
            memoryDc = MemorySegment.NULL;
        }
        if (!screenDc.equals(MemorySegment.NULL)) {
            Gdi.releaseScreenDc(screenDc);
            screenDc = MemorySegment.NULL;
        }
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
