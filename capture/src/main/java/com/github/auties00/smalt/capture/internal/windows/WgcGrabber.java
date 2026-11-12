package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.image.YuvPicture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Captures a window with Windows Graphics Capture.
 *
 * <p>The grabber creates a Direct3D 11 device, wraps it as a WinRT device, creates a capture item for the window
 * ({@code IGraphicsCaptureItemInterop::CreateForWindow}), a free-threaded frame pool of BGRA frames and a capture
 * session, and starts it. The system composes the window into the pool's frames itself, also where other windows cover
 * it, and draws the pointer when the session asks for it ({@code IsCursorCaptureEnabled}; a system without that switch,
 * before Windows 10 version 2004, always draws it, so a capture that leaves the pointer out falls back); the yellow
 * capture border is switched off where the system allows it. Each grab takes the newest frame, if any, copies it into a
 * staging texture and converts its visible part ({@link WindowImage}). The pool is recreated at the window's new size
 * when the window is resized. A minimized window delivers no frame and repeats the last picture; a destroyed window
 * ends the capture.
 *
 * <p>The grabber must be created and used on a thread in the multithreaded apartment.
 */
final class WgcGrabber implements ScreenGrabber {
    /**
     * The number of frames the pool holds.
     */
    private static final int BUFFERS = 2;

    private final MemorySegment window;

    private final Arena arena;

    private final MemorySegment slot;

    private final MemorySegment slot2;

    private final MemorySegment size;

    private final MemorySegment textureDesc;

    private final MemorySegment mapped;

    private final BgraConverter converter = new BgraConverter();

    private final WindowImage image;

    private final int openWidth;

    private final int openHeight;

    private MemorySegment device = MemorySegment.NULL;

    private MemorySegment context = MemorySegment.NULL;

    private MemorySegment winrtDevice = MemorySegment.NULL;

    private MemorySegment item = MemorySegment.NULL;

    private MemorySegment pool = MemorySegment.NULL;

    private MemorySegment session = MemorySegment.NULL;

    private MemorySegment staging = MemorySegment.NULL;

    private int stagingWidth;

    private int stagingHeight;

    private int poolWidth;

    private int poolHeight;

    /**
     * Whether the pointer setting cannot be applied, because the session cannot leave the pointer out.
     */
    private boolean pointerUnsupported;

    /**
     * Opens a capture of a window.
     *
     * @param window the window handle
     * @throws MediaException.Unsupported if the system does not support Windows Graphics Capture
     * @throws MediaException.Io if a step of the setup fails
     */
    WgcGrabber(MemorySegment window) {
        if (!GraphicsCapture.supported()) {
            throw new MediaException.Unsupported("window capture", "Windows Graphics Capture is not supported on this system");
        }
        this.window = window;
        this.arena = Arena.ofConfined();
        try {
            this.slot = arena.allocate(ADDRESS);
            this.slot2 = arena.allocate(ADDRESS);
            this.size = arena.allocate(GraphicsCapture.SIZE);
            this.textureDesc = arena.allocate(Dxgi.TEXTURE2D_DESC_SIZE, 4);
            this.mapped = arena.allocate(Dxgi.MAPPED_SUBRESOURCE_SIZE, 8);
            this.image = new WindowImage(window, arena);
            createDevice();
            createItem();
            Com.check(Com.call(item, GraphicsCapture.ITEM_GET_SIZE, size), "IGraphicsCaptureItem::get_Size");
            var itemWidth = size.get(JAVA_INT, 0);
            var itemHeight = size.get(JAVA_INT, 4);
            if (itemWidth < 1 || itemHeight < 1) {
                throw Com.failure("the window has no size", 0);
            }
            var visible = image.visibleSize();
            var usable = visible != null && visible[0] >= 2 && visible[1] >= 2;
            this.openWidth = usable ? visible[0] : itemWidth;
            this.openHeight = usable ? visible[1] : itemHeight;
            createPool(itemWidth, itemHeight);
            Com.check(Com.call(pool, GraphicsCapture.POOL_CREATE_CAPTURE_SESSION, item, slot),
                    "IDirect3D11CaptureFramePool::CreateCaptureSession");
            session = slot.get(ADDRESS, 0);
            var session3 = Com.query(session, GraphicsCapture.IID_SESSION3, slot);
            if (!session3.equals(MemorySegment.NULL)) {
                GraphicsCapture.putBoolean(session3, GraphicsCapture.SESSION3_PUT_BORDER, false);
                Com.release(session3);
            }
            Com.check(Com.call(session, GraphicsCapture.SESSION_START_CAPTURE), "IGraphicsCaptureSession::StartCapture");
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /**
     * Creates the Direct3D device and its WinRT wrapper.
     */
    private void createDevice() {
        var featureLevel = arena.allocate(JAVA_INT);
        Com.check(Dxgi.createDefaultDevice(slot, featureLevel, slot2), "D3D11CreateDevice");
        device = slot.get(ADDRESS, 0);
        context = slot2.get(ADDRESS, 0);
        var dxgiDevice = Com.query(device, GraphicsCapture.IID_DXGI_DEVICE, slot);
        if (dxgiDevice.equals(MemorySegment.NULL)) {
            throw Com.failure("the Direct3D device has no DXGI device", Com.E_NOINTERFACE);
        }
        try {
            Com.check(GraphicsCapture.wrapDevice(dxgiDevice, slot), "CreateDirect3D11DeviceFromDXGIDevice");
        } finally {
            Com.release(dxgiDevice);
        }
        var inspectable = slot.get(ADDRESS, 0);
        try {
            winrtDevice = Com.query(inspectable, GraphicsCapture.IID_DIRECT3D_DEVICE, slot);
        } finally {
            Com.release(inspectable);
        }
        if (winrtDevice.equals(MemorySegment.NULL)) {
            throw Com.failure("the WinRT device has no IDirect3DDevice", Com.E_NOINTERFACE);
        }
    }

    /**
     * Creates the capture item of the window.
     */
    private void createItem() {
        Com.check(GraphicsCapture.factory(GraphicsCapture.ITEM_CLASS, GraphicsCapture.IID_ITEM_INTEROP, slot),
                "RoGetActivationFactory(GraphicsCaptureItem)");
        var interop = slot.get(ADDRESS, 0);
        try {
            Com.check(Com.call(interop, GraphicsCapture.INTEROP_CREATE_FOR_WINDOW, window, GraphicsCapture.IID_ITEM, slot),
                    "IGraphicsCaptureItemInterop::CreateForWindow");
            item = slot.get(ADDRESS, 0);
        } finally {
            Com.release(interop);
        }
    }

    /**
     * Creates the free-threaded frame pool.
     */
    private void createPool(int width, int height) {
        Com.check(GraphicsCapture.factory(GraphicsCapture.POOL_CLASS, GraphicsCapture.IID_POOL_STATICS2, slot),
                "RoGetActivationFactory(Direct3D11CaptureFramePool)");
        var statics = slot.get(ADDRESS, 0);
        try {
            Com.check(GraphicsCapture.createPool(statics, winrtDevice, BUFFERS, width, height, slot),
                    "IDirect3D11CaptureFramePoolStatics2::CreateFreeThreaded");
            pool = slot.get(ADDRESS, 0);
            poolWidth = width;
            poolHeight = height;
        } finally {
            Com.release(statics);
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
        var session2 = Com.query(session, GraphicsCapture.IID_SESSION2, slot2);
        if (!session2.equals(MemorySegment.NULL)) {
            GraphicsCapture.putBoolean(session2, GraphicsCapture.SESSION2_PUT_CURSOR, cursor);
            Com.release(session2);
        }
        pointerUnsupported = !cursor && session2.equals(MemorySegment.NULL);
    }

    @Override
    public Result grab(YuvPicture out) {
        if (!TopWindows.exists(window)) {
            return Result.ENDED;
        }
        if (pointerUnsupported) {
            return Result.FALLBACK;
        }
        slot.set(ADDRESS, 0, MemorySegment.NULL);
        if (Com.call(pool, GraphicsCapture.POOL_TRY_GET_NEXT_FRAME, slot) < 0) {
            return Result.FALLBACK;
        }
        var frame = slot.get(ADDRESS, 0);
        if (frame.equals(MemorySegment.NULL)) {
            return Result.UNCHANGED;
        }
        try {
            if (Com.call(frame, GraphicsCapture.FRAME_GET_CONTENT_SIZE, size) < 0) {
                return Result.UNCHANGED;
            }
            var contentWidth = size.get(JAVA_INT, 0);
            var contentHeight = size.get(JAVA_INT, 4);
            var result = copy(frame, contentWidth, contentHeight, out);
            if (contentWidth > 0 && contentHeight > 0 && (contentWidth != poolWidth || contentHeight != poolHeight)) {
                if (GraphicsCapture.recreatePool(pool, winrtDevice, BUFFERS, contentWidth, contentHeight) >= 0) {
                    poolWidth = contentWidth;
                    poolHeight = contentHeight;
                }
            }
            return result;
        } finally {
            GraphicsCapture.close(frame);
            Com.release(frame);
        }
    }

    /**
     * Copies a frame's surface through a staging texture and converts its visible part.
     */
    private Result copy(MemorySegment frame, int contentWidth, int contentHeight, YuvPicture out) {
        if (Com.call(frame, GraphicsCapture.FRAME_GET_SURFACE, slot) < 0) {
            return Result.UNCHANGED;
        }
        var surface = slot.get(ADDRESS, 0);
        var access = MemorySegment.NULL;
        var texture = MemorySegment.NULL;
        try {
            access = Com.query(surface, GraphicsCapture.IID_DXGI_ACCESS, slot);
            if (access.equals(MemorySegment.NULL)) {
                return Result.FALLBACK;
            }
            slot2.set(ADDRESS, 0, MemorySegment.NULL);
            if (Com.call(access, GraphicsCapture.ACCESS_GET_INTERFACE, Dxgi.IID_TEXTURE2D, slot2) < 0) {
                return Result.FALLBACK;
            }
            texture = slot2.get(ADDRESS, 0);
            Com.callVoid(texture, Dxgi.TEXTURE2D_GET_DESC, textureDesc);
            var textureWidth = textureDesc.get(JAVA_INT, 0);
            var textureHeight = textureDesc.get(JAVA_INT, 4);
            prepareStaging(textureWidth, textureHeight);
            Com.callVoid(context, Dxgi.CONTEXT_COPY_RESOURCE, staging, texture);
            var width = Math.min(textureWidth, contentWidth);
            var height = Math.min(textureHeight, contentHeight);
            var crop = image.crop(width, height);
            if (crop == null) {
                return Result.UNCHANGED;
            }
            var hr = Com.call(context, Dxgi.CONTEXT_MAP, staging, 0, Dxgi.MAP_READ, 0, mapped);
            if (hr < 0) {
                return Result.UNCHANGED;
            }
            try {
                var pitch = mapped.get(JAVA_INT, Dxgi.MAPPED_SUBRESOURCE_ROW_PITCH);
                var data = mapped.get(ADDRESS, 0).reinterpret((long) pitch * stagingHeight);
                return converter.convert(data, 0, pitch, width, height, 0, null, crop, out) ? Result.CHANGED : Result.UNCHANGED;
            } finally {
                Com.callVoid(context, Dxgi.CONTEXT_UNMAP, staging, 0);
            }
        } finally {
            Com.release(texture);
            Com.release(access);
            Com.release(surface);
        }
    }

    /**
     * Creates the staging texture of a size, keeping the current one when it matches.
     */
    private void prepareStaging(int width, int height) {
        if (!staging.equals(MemorySegment.NULL) && width == stagingWidth && height == stagingHeight) {
            return;
        }
        Com.release(staging);
        staging = MemorySegment.NULL;
        textureDesc.set(JAVA_INT, 8, 1);
        textureDesc.set(JAVA_INT, 12, 1);
        textureDesc.set(JAVA_INT, 16, Dxgi.FORMAT_B8G8R8A8_UNORM);
        textureDesc.set(JAVA_INT, 20, 1);
        textureDesc.set(JAVA_INT, 24, 0);
        textureDesc.set(JAVA_INT, 28, Dxgi.USAGE_STAGING);
        textureDesc.set(JAVA_INT, 32, 0);
        textureDesc.set(JAVA_INT, 36, Dxgi.CPU_ACCESS_READ);
        textureDesc.set(JAVA_INT, 40, 0);
        Com.check(Com.call(device, Dxgi.DEVICE_CREATE_TEXTURE2D, textureDesc, MemorySegment.NULL, slot2),
                "ID3D11Device::CreateTexture2D");
        staging = slot2.get(ADDRESS, 0);
        stagingWidth = width;
        stagingHeight = height;
    }

    @Override
    public void close() {
        GraphicsCapture.close(session);
        Com.release(session);
        session = MemorySegment.NULL;
        GraphicsCapture.close(pool);
        Com.release(pool);
        pool = MemorySegment.NULL;
        Com.release(item);
        item = MemorySegment.NULL;
        Com.release(staging);
        staging = MemorySegment.NULL;
        Com.release(winrtDevice);
        winrtDevice = MemorySegment.NULL;
        Com.release(context);
        context = MemorySegment.NULL;
        Com.release(device);
        device = MemorySegment.NULL;
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
