package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.image.YuvPicture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Captures a monitor with the DXGI desktop duplication API.
 *
 * <p>The grabber creates a Direct3D 11 device on the adapter the monitor is connected to and
 * duplicates the monitor's output, asking for 32-bit BGRA frames. Each {@link #grab(YuvPicture)}
 * polls the duplication without waiting: when the desktop image changed it is copied on the GPU
 * into a CPU-readable staging texture, which keeps the last image, and converted; when only the
 * pointer moved or changed shape the staging texture is converted again with the pointer at its
 * new place; when nothing changed the grab reports {@link Result#UNCHANGED} and costs one call.
 *
 * <p>The first frame of a duplication carries no desktop image (only the pointer), and a static
 * desktop produces no frame at all, so until the duplication delivers its first image the
 * grabber copies the monitor with {@link GdiGrabber} instead. A duplication invalidated by a
 * desktop switch, a mode change or a full-screen application ({@code DXGI_ERROR_ACCESS_LOST}) is
 * recreated on the next grab; while it cannot be recreated (the secure desktop of a UAC prompt or
 * the lock screen) the previous image stands, and a lost device is recreated with a growing
 * delay. When the system refuses the duplication itself the grabber reports
 * {@link Result#FALLBACK}, and when the monitor has been missing for ten seconds
 * {@link Result#ENDED}.
 *
 * @implNote This implementation prefers {@code IDXGIOutput5::DuplicateOutput1} with
 * {@code DXGI_FORMAT_B8G8R8A8_UNORM} as the only supported format, so the system
 * converts high-color and HDR desktops to 8-bit BGRA itself, and falls back to
 * {@code IDXGIOutput1::DuplicateOutput} before Windows 10 1703.
 */
final class DuplicationGrabber implements ScreenGrabber {
    /**
     * The first delay before recreating a lost device.
     */
    private static final long RETRY_MIN_NANOS = 100_000_000L;

    /**
     * The longest delay before recreating a lost device.
     */
    private static final long RETRY_MAX_NANOS = 2_000_000_000L;

    /**
     * How long the monitor may be missing before the grabber reports {@link Result#ENDED}.
     */
    private static final long MISSING_LIMIT_NANOS = 10_000_000_000L;

    /**
     * The largest pointer shape accepted, in bytes.
     */
    private static final int MAX_SHAPE_BYTES = 16 << 20;

    /**
     * The {@code DXGI_ERROR_MODE_CHANGE_IN_PROGRESS} result.
     */
    private static final int ERROR_MODE_CHANGE_IN_PROGRESS = 0x887A0025;

    /**
     * The {@code DXGI_ERROR_SESSION_DISCONNECTED} result.
     */
    private static final int ERROR_SESSION_DISCONNECTED = 0x887A0028;

    /**
     * The GDI device name of the monitor.
     */
    private final String deviceName;

    /**
     * The placement of the monitor when the grabber was opened.
     */
    private final Displays.Bounds bounds;

    /**
     * The arena of the structures that live as long as the grabber.
     */
    private final Arena arena;

    /**
     * A pointer-sized slot receiving interface pointers.
     */
    private final MemorySegment slot;

    /**
     * A second pointer-sized slot receiving interface pointers.
     */
    private final MemorySegment slot2;

    /**
     * A 32-bit slot receiving the feature level of the device.
     */
    private final MemorySegment featureLevel;

    /**
     * The {@code DXGI_OUTDUPL_FRAME_INFO} of the last acquired frame.
     */
    private final MemorySegment frameInfo;

    /**
     * The {@code DXGI_OUTDUPL_DESC} of the duplication.
     */
    private final MemorySegment duplicationDesc;

    /**
     * The {@code D3D11_TEXTURE2D_DESC} of textures.
     */
    private final MemorySegment textureDesc;

    /**
     * The {@code D3D11_MAPPED_SUBRESOURCE} of the mapped staging texture.
     */
    private final MemorySegment mapped;

    /**
     * The {@code DXGI_OUTDUPL_POINTER_SHAPE_INFO} of the last pointer shape.
     */
    private final MemorySegment shapeInfo;

    /**
     * A 32-bit slot receiving the size a pointer shape needs.
     */
    private final MemorySegment shapeRequired;

    /**
     * The one-element format list of {@code DuplicateOutput1}.
     */
    private final MemorySegment formats;

    /**
     * The composer of the pointer.
     */
    private final PointerOverlay pointer = new PointerOverlay();

    /**
     * The converter of the staging texture.
     */
    private final BgraConverter converter = new BgraConverter();

    /**
     * The arena of {@link #shapeBuffer}, replaced when the buffer grows.
     */
    private Arena shapeArena;

    /**
     * The buffer receiving pointer shapes.
     */
    private MemorySegment shapeBuffer;

    /**
     * The captured area, or {@code null} for the whole monitor.
     */
    private CaptureArea area;

    /**
     * Whether the pointer is drawn.
     */
    private boolean cursorShown = true;

    /**
     * The GDI grabber copying the monitor until the duplication delivers an image, or
     * {@code null}.
     */
    private GdiGrabber seed;

    /**
     * The {@code IDXGIFactory1}.
     */
    private MemorySegment factory = MemorySegment.NULL;

    /**
     * The {@code IDXGIAdapter1} the monitor is connected to.
     */
    private MemorySegment adapter = MemorySegment.NULL;

    /**
     * The {@code IDXGIOutput} of the monitor.
     */
    private MemorySegment output = MemorySegment.NULL;

    /**
     * The {@code ID3D11Device}.
     */
    private MemorySegment device = MemorySegment.NULL;

    /**
     * The immediate {@code ID3D11DeviceContext}.
     */
    private MemorySegment context = MemorySegment.NULL;

    /**
     * The {@code IDXGIOutputDuplication}.
     */
    private MemorySegment duplication = MemorySegment.NULL;

    /**
     * The CPU-readable {@code ID3D11Texture2D} holding the last desktop image.
     */
    private MemorySegment staging = MemorySegment.NULL;

    /**
     * The width of {@link #staging}.
     */
    private int stagingWidth;

    /**
     * The height of {@link #staging}.
     */
    private int stagingHeight;

    /**
     * The format of {@link #staging}.
     */
    private int stagingFormat;

    /**
     * The {@code DXGI_MODE_ROTATION} of the duplicated image.
     */
    private int rotation;

    /**
     * Whether {@link #staging} holds an image of the current duplication.
     */
    private boolean image;

    /**
     * Whether the next grab copies the monitor with GDI because the duplication has no image
     * yet.
     */
    private boolean seedPending;

    /**
     * The time before which a lost duplication is not recreated.
     */
    private long retryAt;

    /**
     * The current delay between recreation attempts.
     */
    private long retryDelay = RETRY_MIN_NANOS;

    /**
     * When the monitor was first found missing, or {@code -1} while it exists.
     */
    private long missingSince = -1;

    /**
     * Opens a grabber for a monitor.
     *
     * @param deviceName the monitor's GDI device name
     * @throws MediaException.Io if DXGI or Direct3D 11 fail, or no DXGI output has that name
     * @throws MediaException.Unsupported if the system refuses to duplicate the monitor
     */
    DuplicationGrabber(String deviceName) {
        this.deviceName = deviceName;
        this.arena = Arena.ofConfined();
        try {
            this.slot = arena.allocate(ADDRESS);
            this.slot2 = arena.allocate(ADDRESS);
            this.featureLevel = arena.allocate(JAVA_INT);
            this.frameInfo = arena.allocate(Dxgi.FRAME_INFO_SIZE, 8);
            this.duplicationDesc = arena.allocate(Dxgi.DUPLICATION_DESC_SIZE, 4);
            this.textureDesc = arena.allocate(Dxgi.TEXTURE2D_DESC_SIZE, 4);
            this.mapped = arena.allocate(Dxgi.MAPPED_SUBRESOURCE_SIZE, 8);
            this.shapeInfo = arena.allocate(Dxgi.POINTER_SHAPE_INFO_SIZE, 4);
            this.shapeRequired = arena.allocate(JAVA_INT);
            this.formats = arena.allocate(JAVA_INT);
            formats.set(JAVA_INT, 0, Dxgi.FORMAT_B8G8R8A8_UNORM);
            var found = start();
            if (found == null) {
                throw Com.failure("no desktop duplication output is named " + deviceName, Dxgi.ERROR_NOT_FOUND);
            }
            this.bounds = found;
            var hr = duplicate();
            if (hr < 0 && !recoverable(hr)) {
                throw new MediaException.Unsupported("desktop duplication",
                        "the system refused to duplicate " + deviceName + String.format(" (0x%08X)", hr));
            }
            if (hr < 0) {
                retryAt = System.nanoTime() + retryDelay;
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /**
     * Returns whether a failure to duplicate is expected to pass: the secure desktop, a mode
     * change or a disconnected session.
     *
     * @param hr the {@code HRESULT}
     * @return whether duplicating again later may succeed
     */
    private static boolean recoverable(int hr) {
        return hr == Com.E_ACCESSDENIED || hr == Dxgi.ERROR_ACCESS_LOST || hr == ERROR_MODE_CHANGE_IN_PROGRESS
                || hr == ERROR_SESSION_DISCONNECTED;
    }

    /**
     * Creates the factory, finds the monitor's output and creates the device on its adapter.
     *
     * @return the monitor's placement, or {@code null} when no attached output has its name
     * @throws MediaException.Io if a DXGI or Direct3D call fails
     */
    private Displays.Bounds start() {
        factory = Dxgi.createFactory(slot);
        Displays.Output found;
        try (var temporary = Arena.ofConfined()) {
            found = Displays.find(factory, deviceName, temporary);
        }
        if (found == null) {
            return null;
        }
        adapter = found.adapter();
        output = found.output();
        slot.set(ADDRESS, 0, MemorySegment.NULL);
        slot2.set(ADDRESS, 0, MemorySegment.NULL);
        Com.check(Dxgi.createDevice(adapter, slot, featureLevel, slot2), "D3D11CreateDevice");
        device = slot.get(ADDRESS, 0);
        context = slot2.get(ADDRESS, 0);
        return found.bounds();
    }

    /**
     * Duplicates the output.
     *
     * @return the {@code HRESULT} of the duplication
     */
    private int duplicate() {
        var hr = Com.E_NOINTERFACE;
        var output5 = Com.query(output, Dxgi.IID_OUTPUT5, slot);
        if (!output5.equals(MemorySegment.NULL)) {
            hr = Com.call(output5, Dxgi.OUTPUT5_DUPLICATE_OUTPUT1, device, 0, 1, formats, slot2);
            Com.release(output5);
        }
        if (hr < 0 && hr != Com.E_ACCESSDENIED) {
            var output1 = Com.query(output, Dxgi.IID_OUTPUT1, slot);
            if (output1.equals(MemorySegment.NULL)) {
                return hr;
            }
            hr = Com.call(output1, Dxgi.OUTPUT1_DUPLICATE_OUTPUT, device, slot2);
            Com.release(output1);
        }
        if (hr >= 0) {
            duplication = slot2.get(ADDRESS, 0);
            Com.callVoid(duplication, Dxgi.DUPLICATION_GET_DESC, duplicationDesc);
            rotation = duplicationDesc.get(JAVA_INT, Dxgi.DUPLICATION_DESC_ROTATION);
            image = false;
            seedPending = true;
        }
        return hr;
    }

    @Override
    public int width() {
        return bounds.width();
    }

    @Override
    public int height() {
        return bounds.height();
    }

    @Override
    public void configure(CaptureArea area, boolean cursor) {
        this.area = area;
        this.cursorShown = cursor;
        if (seed != null) {
            seed.configure(area, cursor);
        }
    }

    @Override
    public Result grab(YuvPicture out) {
        var now = System.nanoTime();
        if (duplication.equals(MemorySegment.NULL)) {
            if (now - retryAt < 0) {
                return Result.UNCHANGED;
            }
            var outcome = restart(now);
            if (outcome != null) {
                return outcome;
            }
        }
        frameInfo.fill((byte) 0);
        slot.set(ADDRESS, 0, MemorySegment.NULL);
        var hr = Com.call(duplication, Dxgi.DUPLICATION_ACQUIRE_NEXT_FRAME, 0, frameInfo, slot);
        if (hr == Dxgi.ERROR_WAIT_TIMEOUT) {
            return !image && seedPending ? seed(out) : Result.UNCHANGED;
        }
        if (hr < 0) {
            lose(hr, now);
            return Result.UNCHANGED;
        }
        var imageChanged = false;
        var pointerChanged = false;
        var resource = slot.get(ADDRESS, 0);
        try {
            if (frameInfo.get(JAVA_LONG, Dxgi.FRAME_INFO_LAST_PRESENT) != 0) {
                if (!copyImage(resource)) {
                    return Result.FALLBACK;
                }
                imageChanged = true;
            }
            if (frameInfo.get(JAVA_LONG, Dxgi.FRAME_INFO_LAST_MOUSE_UPDATE) != 0) {
                pointer.position(frameInfo.get(JAVA_INT, Dxgi.FRAME_INFO_POINTER_X),
                        frameInfo.get(JAVA_INT, Dxgi.FRAME_INFO_POINTER_Y),
                        frameInfo.get(JAVA_INT, Dxgi.FRAME_INFO_POINTER_VISIBLE) != 0);
                pointerChanged = true;
            }
            var shapeSize = frameInfo.get(JAVA_INT, Dxgi.FRAME_INFO_POINTER_SHAPE_SIZE);
            if (shapeSize > 0 && readShape(shapeSize)) {
                pointerChanged = true;
            }
        } finally {
            Com.release(resource);
            Com.call(duplication, Dxgi.DUPLICATION_RELEASE_FRAME);
        }
        if (!image) {
            return seed(out);
        }
        if (!imageChanged && !pointerChanged) {
            return Result.UNCHANGED;
        }
        return convert(out);
    }

    /**
     * Recreates a lost duplication, or the whole device when it was lost too.
     *
     * @param now the current time
     * @return {@code null} when the duplication was recreated, otherwise the outcome of the grab
     */
    private Result restart(long now) {
        try {
            if (device.equals(MemorySegment.NULL)) {
                release();
                if (start() == null) {
                    release();
                    if (missingSince < 0) {
                        missingSince = now;
                    }
                    if (now - missingSince > MISSING_LIMIT_NANOS) {
                        return Result.ENDED;
                    }
                    schedule(now);
                    return Result.UNCHANGED;
                }
                missingSince = -1;
            }
            var hr = duplicate();
            if (hr >= 0) {
                retryDelay = RETRY_MIN_NANOS;
                return null;
            }
            if (hr == Dxgi.ERROR_UNSUPPORTED || hr == Dxgi.ERROR_NOT_CURRENTLY_AVAILABLE) {
                return Result.FALLBACK;
            }
            if (!recoverable(hr)) {
                release();
            }
        } catch (MediaException exception) {
            release();
        }
        schedule(now);
        return Result.UNCHANGED;
    }

    /**
     * Schedules the next recreation attempt, doubling the delay up to its limit.
     *
     * @param now the current time
     */
    private void schedule(long now) {
        retryAt = now + retryDelay;
        retryDelay = Math.min(RETRY_MAX_NANOS, retryDelay * 2);
    }

    /**
     * Drops a duplication that failed, and the device too unless only the duplication was lost.
     *
     * @param hr the failure
     * @param now the current time
     */
    private void lose(int hr, long now) {
        Com.release(duplication);
        duplication = MemorySegment.NULL;
        image = false;
        if (hr == Dxgi.ERROR_ACCESS_LOST) {
            retryAt = now;
        } else {
            release();
            schedule(now);
        }
    }

    /**
     * Copies the desktop image of the acquired frame into the staging texture.
     *
     * @param resource the frame's {@code IDXGIResource}
     * @return whether the image was copied; {@code false} when its format is not 8-bit BGRA
     */
    private boolean copyImage(MemorySegment resource) {
        var texture = Com.query(resource, Dxgi.IID_TEXTURE2D, slot2);
        if (texture.equals(MemorySegment.NULL)) {
            return false;
        }
        try {
            Com.callVoid(texture, Dxgi.TEXTURE2D_GET_DESC, textureDesc);
            var width = textureDesc.get(JAVA_INT, 0);
            var height = textureDesc.get(JAVA_INT, 4);
            var format = textureDesc.get(JAVA_INT, 16);
            if (format != Dxgi.FORMAT_B8G8R8A8_UNORM && format != Dxgi.FORMAT_B8G8R8A8_UNORM_SRGB) {
                return false;
            }
            if (staging.equals(MemorySegment.NULL) || width != stagingWidth || height != stagingHeight || format != stagingFormat) {
                Com.release(staging);
                staging = MemorySegment.NULL;
                textureDesc.set(JAVA_INT, 8, 1);
                textureDesc.set(JAVA_INT, 12, 1);
                textureDesc.set(JAVA_INT, 20, 1);
                textureDesc.set(JAVA_INT, 24, 0);
                textureDesc.set(JAVA_INT, 28, Dxgi.USAGE_STAGING);
                textureDesc.set(JAVA_INT, 32, 0);
                textureDesc.set(JAVA_INT, 36, Dxgi.CPU_ACCESS_READ);
                textureDesc.set(JAVA_INT, 40, 0);
                Com.check(Com.call(device, Dxgi.DEVICE_CREATE_TEXTURE2D, textureDesc, MemorySegment.NULL, slot), "ID3D11Device::CreateTexture2D");
                staging = slot.get(ADDRESS, 0);
                stagingWidth = width;
                stagingHeight = height;
                stagingFormat = format;
            }
            Com.callVoid(context, Dxgi.CONTEXT_COPY_RESOURCE, staging, texture);
            image = true;
            return true;
        } finally {
            Com.release(texture);
        }
    }

    /**
     * Reads the pointer shape of the acquired frame.
     *
     * @param size the size of the shape in bytes
     * @return whether the shape was read
     */
    private boolean readShape(int size) {
        if (size > MAX_SHAPE_BYTES) {
            return false;
        }
        if (shapeBuffer == null || shapeBuffer.byteSize() < size) {
            if (shapeArena != null) {
                shapeArena.close();
            }
            shapeArena = Arena.ofConfined();
            shapeBuffer = shapeArena.allocate(size, 8);
        }
        var hr = Com.call(duplication, Dxgi.DUPLICATION_GET_FRAME_POINTER_SHAPE, (int) shapeBuffer.byteSize(),
                shapeBuffer, shapeRequired, shapeInfo);
        if (hr < 0) {
            return false;
        }
        pointer.shape(shapeBuffer.asSlice(0, Math.min(size, shapeBuffer.byteSize())), shapeInfo.get(JAVA_INT, 0),
                shapeInfo.get(JAVA_INT, 4), shapeInfo.get(JAVA_INT, 8), shapeInfo.get(JAVA_INT, 12));
        return true;
    }

    /**
     * Converts the last desktop image again, whether or not the desktop changed.
     *
     * <p>No session calls this method: it is the seam through which the Windows capture test measures
     * the per-frame cost of a desktop that changes on every frame (mapping the staging texture, reading
     * it and converting it).
     *
     * @param out the picture receiving the image
     * @return {@link Result#CHANGED}, or {@link Result#UNCHANGED} while the duplication has not
     * delivered an image
     */
    Result redraw(YuvPicture out) {
        return image ? convert(out) : Result.UNCHANGED;
    }

    /**
     * Converts the staging texture.
     *
     * @param out the picture receiving the image
     * @return the outcome
     */
    private Result convert(YuvPicture out) {
        var hr = Com.call(context, Dxgi.CONTEXT_MAP, staging, 0, Dxgi.MAP_READ, 0, mapped);
        if (hr < 0) {
            lose(hr, System.nanoTime());
            return Result.UNCHANGED;
        }
        try {
            var pitch = mapped.get(JAVA_INT, Dxgi.MAPPED_SUBRESOURCE_ROW_PITCH);
            var data = mapped.get(ADDRESS, 0).reinterpret((long) pitch * stagingHeight);
            return converter.convert(data, 0, pitch, stagingWidth, stagingHeight, rotation, cursorShown ? pointer : null,
                    area, out)
                    ? Result.CHANGED : Result.UNCHANGED;
        } finally {
            Com.callVoid(context, Dxgi.CONTEXT_UNMAP, staging, 0);
        }
    }

    /**
     * Copies the monitor with GDI while the duplication has no image.
     *
     * @param out the picture receiving the image
     * @return the outcome
     */
    private Result seed(YuvPicture out) {
        seedPending = false;
        if (seed == null) {
            try {
                seed = new GdiGrabber(deviceName, bounds);
                seed.configure(area, cursorShown);
            } catch (MediaException exception) {
                return Result.UNCHANGED;
            }
        }
        return seed.grab(out) == Result.CHANGED ? Result.CHANGED : Result.UNCHANGED;
    }

    /**
     * Releases every DXGI and Direct3D object.
     */
    private void release() {
        Com.release(staging);
        staging = MemorySegment.NULL;
        Com.release(duplication);
        duplication = MemorySegment.NULL;
        Com.release(context);
        context = MemorySegment.NULL;
        Com.release(device);
        device = MemorySegment.NULL;
        Com.release(output);
        output = MemorySegment.NULL;
        Com.release(adapter);
        adapter = MemorySegment.NULL;
        Com.release(factory);
        factory = MemorySegment.NULL;
        image = false;
    }

    @Override
    public void close() {
        release();
        if (seed != null) {
            seed.close();
            seed = null;
        }
        if (shapeArena != null) {
            shapeArena.close();
            shapeArena = null;
        }
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
