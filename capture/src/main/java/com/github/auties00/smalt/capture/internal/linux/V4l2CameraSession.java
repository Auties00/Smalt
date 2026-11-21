package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CameraCaptureSession;
import com.github.auties00.smalt.capture.CameraControls;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Captures a Video4Linux2 camera with memory-mapped streaming I/O.
 *
 * <p>Opening negotiates the format ({@code VIDIOC_S_FMT}) and frame rate ({@code VIDIOC_S_PARM}),
 * allocates {@value #REQUESTED_BUFFERS} driver buffers ({@code VIDIOC_REQBUFS}), maps them
 * ({@code VIDIOC_QUERYBUF}, {@code mmap}, one mapping per memory plane on a multi-planar device),
 * queues them ({@code VIDIOC_QBUF}) and starts streaming ({@code VIDIOC_STREAMON}). A dedicated
 * platform thread then waits in {@code poll} on the device and on an {@code eventfd}, dequeues every
 * filled buffer ({@code VIDIOC_DQBUF} on the non-blocking descriptor), assembles the newest valid
 * one into a {@link RawFrameExchange} through the {@link V4l2FrameLayout} and requeues them all at
 * once, so the driver is never starved by a slow reader and a reader always receives the newest
 * frame. With alternating fields every buffer is assembled, and a frame is published once its top
 * and bottom fields of one sequence number have arrived, in either order. {@link #read()} converts
 * that frame to 4:2:0 on the reader's thread and waits without holding a native call, so it may
 * run on a virtual thread.
 *
 * <p>Frames flagged {@code V4L2_BUF_FLAG_ERROR}, empty frames and frames that do not convert are
 * skipped; {@value #MAX_UNUSABLE_FRAMES} unusable frames in a row fail the read. A device that
 * disappears ({@code ENODEV}, or {@code EIO} from a queue the driver shut down) ends the stream:
 * {@link #read()} then returns {@code null}. Timestamps come from the driver when they are
 * {@code CLOCK_MONOTONIC}, from the dequeue time otherwise, relative to the moment streaming
 * started and strictly increasing; a woven frame has the timestamp of its first field.
 *
 * <p>{@link #close()} wakes the thread through the {@code eventfd}, waits for it to finish and
 * then stops streaming, unmaps the buffers and closes the descriptors, so the camera can be
 * reopened as soon as it returns.
 */
final class V4l2CameraSession implements CameraCaptureSession {
    /**
     * The number of driver buffers requested.
     */
    private static final int REQUESTED_BUFFERS = 4;

    /**
     * The largest number of driver buffers accepted, {@code VIDEO_MAX_FRAME}.
     */
    private static final int MAX_BUFFERS = 32;

    /**
     * The number of consecutive unusable frames after which a read fails.
     */
    private static final int MAX_UNUSABLE_FRAMES = 90;

    /**
     * The outcome of a drain that published a frame.
     */
    private static final int PUBLISHED = 0;

    /**
     * The outcome of a drain that found no usable frame.
     */
    private static final int NOTHING = 1;

    /**
     * The outcome of a drain that found the device gone.
     */
    private static final int STOPPED = 2;

    /**
     * The camera.
     */
    private final CaptureDevice.Camera camera;

    /**
     * The C library bindings of the capture thread.
     */
    private final Posix posix;

    /**
     * The device descriptor.
     */
    private final int fd;

    /**
     * The buffer type, single-planar or multi-planar.
     */
    private final int bufferType;

    /**
     * The event counter that wakes the capture thread.
     */
    private final int wakeup;

    /**
     * The mapped memory planes of the driver buffers, by buffer index and plane.
     */
    private final MemorySegment[][] buffers;

    /**
     * The layout of the negotiated frames.
     */
    private final V4l2FrameLayout layout;

    /**
     * The converter of the negotiated frames.
     */
    private final FrameConverter converter;

    /**
     * The controls of the camera, closed before the device.
     */
    private final V4l2Controls controls;

    /**
     * The hand-off of frames from the capture thread to the reader.
     */
    private final RawFrameExchange exchange;

    /**
     * The monotonic time streaming started, in microseconds.
     */
    private final long originMicros;

    /**
     * The capture thread.
     */
    private final Thread pump;

    /**
     * Serializes readers.
     */
    private final ReentrantLock readLock = new ReentrantLock();

    /**
     * Whether the session is closed.
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * The number of consecutive unusable frames; guarded by {@link #readLock}.
     */
    private int unusable;

    /**
     * The timestamp of the last published frame; accessed only by the capture thread.
     */
    private long lastTimestamp = -1;

    /**
     * The sequence number of the fields being woven, or {@code -1}; accessed only by the capture
     * thread.
     */
    private long fieldSequence = -1;

    /**
     * The fields of {@link #fieldSequence} assembled so far, bit 0 top and bit 1 bottom; accessed
     * only by the capture thread.
     */
    private int fieldsSeen;

    /**
     * The timestamp of the first field of {@link #fieldSequence}; accessed only by the capture
     * thread.
     */
    private long fieldTimestamp;

    /**
     * The data offset of every memory plane of the dequeued buffer; accessed only by the capture
     * thread.
     */
    private final long[] dataOffset;

    /**
     * The bytes used in every memory plane of the dequeued buffer; accessed only by the capture
     * thread.
     */
    private final long[] used;

    /**
     * The data offsets of the newest buffer of a drain; accessed only by the capture thread.
     */
    private final long[] newestOffset;

    /**
     * The bytes used of the newest buffer of a drain; accessed only by the capture thread.
     */
    private final long[] newestUsed;

    /**
     * Starts the capture thread over a streaming device.
     *
     * @param posix the C library bindings, handed over to the capture thread
     * @param fd the device descriptor
     * @param bufferType the buffer type
     * @param wakeup the event counter
     * @param buffers the mapped, queued driver buffers
     * @param negotiated the layout and converter of the negotiated frames
     * @param originMicros the monotonic time streaming started, in microseconds
     */
    private V4l2CameraSession(CaptureDevice.Camera camera, Posix posix, int fd, int bufferType, int wakeup,
                              MemorySegment[][] buffers, V4l2Formats.Negotiated negotiated, long originMicros,
                              V4l2Controls controls) {
        this.camera = camera;
        this.posix = posix;
        this.fd = fd;
        this.bufferType = bufferType;
        this.wakeup = wakeup;
        this.buffers = buffers;
        this.layout = negotiated.layout();
        this.converter = negotiated.converter();
        this.originMicros = originMicros;
        this.controls = controls;
        var planes = buffers[0].length;
        this.dataOffset = new long[planes];
        this.used = new long[planes];
        this.newestOffset = new long[planes];
        this.newestUsed = new long[planes];
        var capacity = (long) layout.frameBytes();
        if (layout.format().kind() == V4l2PixelFormat.Kind.JPEG) {
            for (var buffer : buffers) {
                capacity = Math.max(capacity, buffer[0].byteSize());
            }
        }
        this.exchange = new RawFrameExchange((int) capacity);
        this.pump = Thread.ofPlatform().daemon().name("smalt-v4l2-" + camera.id()).unstarted(this::pump);
        pump.start();
    }

    /**
     * Opens a camera, letting the driver choose the field order.
     *
     * @param mode the mode, or {@code null} for the default mode of {@link V4l2Formats#select}
     * @return the session
     * @throws MediaException.Io if the camera cannot be opened, is in use, or fails
     * @throws MediaException.Unsupported if the camera cannot capture {@code mode}
     */
    static V4l2CameraSession open(CaptureDevice.Camera camera, CaptureMode mode) {
        return open(camera, mode, null, V4l2.FIELD_ANY);
    }

    /**
     * Opens a camera in a pixel format and field order.
     *
     * @param mode the mode, or {@code null} for the default mode of {@link V4l2Formats#select}
     * @param format the pixel format, or {@code null} for the one {@link V4l2Formats#select} prefers
     * @param field the requested {@code field}, {@link V4l2#FIELD_ANY} to let the driver choose
     * @return the session
     * @throws MediaException.Io if the camera cannot be opened, is in use, or fails
     * @throws MediaException.Unsupported if the camera cannot capture {@code mode} in {@code format}
     */
    static V4l2CameraSession open(CaptureDevice.Camera camera, CaptureMode mode, V4l2PixelFormat format, int field) {
        var path = V4l2Cameras.nodePath(camera.id());
        var posix = new Posix();
        var fd = posix.open(path, Posix.O_RDWR | Posix.O_NONBLOCK | Posix.O_CLOEXEC);
        if (fd < 0) {
            throw V4l2Formats.failure("open " + path, posix.errno());
        }
        var wakeup = -1;
        var type = V4l2.BUF_TYPE_VIDEO_CAPTURE;
        MemorySegment[][] buffers = null;
        try {
            V4l2Device requests = (request, argument) -> posix.ioctl(fd, request, argument);
            var capability = V4l2Formats.query(requests);
            if (!capability.canCapture()) {
                throw new MediaException.Unsupported("v4l2 device", path + " is not a streaming capture device");
            }
            var device = capability.device(requests);
            type = device.bufferType();
            var offer = V4l2Formats.select(device, mode);
            if (format != null && format != offer.format()) {
                offer = new V4l2Formats.Offer(format, offer.width(), offer.height(), offer.rate());
            }
            var negotiated = V4l2Formats.negotiate(device, offer, ConversionKernel.INSTANCE, field);
            V4l2Formats.requestRate(device, offer.rate());
            var planes = device.multiPlanar() ? offer.format().memoryPlanes() : 1;
            buffers = new MemorySegment[allocateBuffers(device)][planes];
            for (var index = 0; index < buffers.length; index++) {
                var locations = locate(device, index, planes);
                for (var plane = 0; plane < planes; plane++) {
                    var location = locations[plane];
                    var required = negotiated.layout().requiredBytes(plane);
                    if (location.length() < required) {
                        throw new MediaException.Io("the driver allocated too small a buffer", new IOException("buffer of "
                                + location.length() + " bytes for a plane of " + required + " bytes"));
                    }
                    buffers[index][plane] = posix.mmap(fd, location.length(), location.offset());
                    if (buffers[index][plane] == null) {
                        throw V4l2Formats.failure("mmap", posix.errno());
                    }
                }
            }
            for (var index = 0; index < buffers.length; index++) {
                var error = queue(device, index, planes);
                if (error != 0) {
                    throw V4l2Formats.failure("VIDIOC_QBUF", error);
                }
            }
            wakeup = posix.eventfd();
            if (wakeup < 0) {
                throw V4l2Formats.failure("eventfd", posix.errno());
            }
            var origin = posix.monotonicNanos() / 1_000;
            var error = stream(device, V4l2.VIDIOC_STREAMON);
            if (error != 0) {
                throw V4l2Formats.failure("VIDIOC_STREAMON", error);
            }
            return new V4l2CameraSession(camera, posix, fd, type, wakeup, buffers, negotiated, origin,
                    V4l2Controls.query(device));
        } catch (RuntimeException | Error failure) {
            release(posix, fd, type, wakeup, buffers);
            throw failure;
        }
    }

    /**
     * Requests the driver buffers.
     *
     * @return the number of allocated buffers
     * @throws MediaException.Io if the request fails or allocates no buffer
     */
    private static int allocateBuffers(V4l2Device device) {
        try (var arena = Arena.ofConfined()) {
            var request = arena.allocate(V4l2.REQUESTBUFFERS);
            request.set(JAVA_INT, V4l2.REQBUFS_COUNT, REQUESTED_BUFFERS);
            request.set(JAVA_INT, V4l2.REQBUFS_TYPE, device.bufferType());
            request.set(JAVA_INT, V4l2.REQBUFS_MEMORY, V4l2.MEMORY_MMAP);
            var error = device.ioctl(V4l2.VIDIOC_REQBUFS, request);
            if (error != 0) {
                throw V4l2Formats.failure(error == Posix.EBUSY ? "VIDIOC_REQBUFS (the camera is in use)" : "VIDIOC_REQBUFS", error);
            }
            var count = request.get(JAVA_INT, V4l2.REQBUFS_COUNT);
            if (count < 1 || count > MAX_BUFFERS) {
                throw new MediaException.Io("the driver allocated " + count + " buffers",
                        new IOException("VIDIOC_REQBUFS returned " + count + " buffers"));
            }
            return count;
        }
    }

    /**
     * Queries where the memory planes of a driver buffer are mapped and how long they are.
     *
     * @param index the buffer index
     * @param planes the number of memory planes
     * @return the location of each memory plane
     * @throws MediaException.Io if the query fails or a length is not usable
     */
    private static BufferLocation[] locate(V4l2Device device, int index, int planes) {
        try (var arena = Arena.ofConfined()) {
            var buffer = describe(arena, device, index, planes);
            var error = device.ioctl(V4l2.VIDIOC_QUERYBUF, buffer);
            if (error != 0) {
                throw V4l2Formats.failure("VIDIOC_QUERYBUF", error);
            }
            var locations = new BufferLocation[planes];
            if (!device.multiPlanar()) {
                locations[0] = location(index, Integer.toUnsignedLong(buffer.get(JAVA_INT, V4l2.BUFFER_OFFSET)),
                        Integer.toUnsignedLong(buffer.get(JAVA_INT, V4l2.BUFFER_LENGTH)));
                return locations;
            }
            if (buffer.get(JAVA_INT, V4l2.BUFFER_LENGTH) != planes) {
                throw new MediaException.Io("the driver described a buffer of another plane count",
                        new IOException("buffer " + index + " has " + buffer.get(JAVA_INT, V4l2.BUFFER_LENGTH) + " planes"));
            }
            var array = MemorySegment.ofAddress(buffer.get(JAVA_LONG, V4l2.BUFFER_PLANES)).reinterpret(planes * V4l2.PLANE.byteSize());
            for (var plane = 0; plane < planes; plane++) {
                var base = plane * V4l2.PLANE.byteSize();
                locations[plane] = location(index, Integer.toUnsignedLong(array.get(JAVA_INT, base + V4l2.PLANE_MEM_OFFSET)),
                        Integer.toUnsignedLong(array.get(JAVA_INT, base + V4l2.PLANE_LENGTH)));
            }
            return locations;
        }
    }

    /**
     * Validates the location of a memory plane.
     *
     * @param index the buffer index
     * @param offset the offset to pass to {@code mmap}
     * @param length the length in bytes
     * @return the location
     * @throws MediaException.Io if the length is not usable
     */
    private static BufferLocation location(int index, long offset, long length) {
        if (length == 0 || length > Math.min(MediaLimits.DEFAULT.maxBytes(), Integer.MAX_VALUE - 16)) {
            throw new MediaException.Io("the driver allocated an unusable buffer",
                    new IOException("buffer " + index + " of " + length + " bytes"));
        }
        return new BufferLocation(offset, length);
    }

    /**
     * Queues a driver buffer.
     *
     * @param index the buffer index
     * @param planes the number of memory planes
     * @return {@code 0}, or the {@code errno} of {@code VIDIOC_QBUF}
     */
    private static int queue(V4l2Device device, int index, int planes) {
        try (var arena = Arena.ofConfined()) {
            return device.ioctl(V4l2.VIDIOC_QBUF, describe(arena, device, index, planes));
        }
    }

    /**
     * Starts or stops streaming.
     *
     * @param request {@link V4l2#VIDIOC_STREAMON} or {@link V4l2#VIDIOC_STREAMOFF}
     * @return {@code 0}, or the {@code errno}
     */
    private static int stream(V4l2Device device, long request) {
        try (var arena = Arena.ofConfined()) {
            var type = arena.allocate(JAVA_INT);
            type.set(JAVA_INT, 0, device.bufferType());
            return device.ioctl(request, type);
        }
    }

    /**
     * Allocates a {@code struct v4l2_buffer} naming a memory-mapped capture buffer, with its plane
     * array on a multi-planar device.
     *
     * @param index the buffer index
     * @param planes the number of memory planes
     * @return the structure
     */
    private static MemorySegment describe(Arena arena, V4l2Device device, int index, int planes) {
        var buffer = arena.allocate(V4l2.BUFFER);
        var array = device.multiPlanar() ? arena.allocate(V4l2.PLANE, planes) : null;
        prepare(buffer, array, device.bufferType(), index, planes);
        return buffer;
    }

    /**
     * Clears a {@code struct v4l2_buffer} and names a memory-mapped capture buffer in it.
     *
     * @param buffer the structure
     * @param array the {@code v4l2_plane} array of a multi-planar buffer, or {@code null}
     * @param type the buffer type
     * @param index the buffer index
     * @param planes the number of memory planes
     */
    private static void prepare(MemorySegment buffer, MemorySegment array, int type, int index, int planes) {
        buffer.fill((byte) 0);
        buffer.set(JAVA_INT, V4l2.BUFFER_INDEX, index);
        buffer.set(JAVA_INT, V4l2.BUFFER_TYPE, type);
        buffer.set(JAVA_INT, V4l2.BUFFER_MEMORY, V4l2.MEMORY_MMAP);
        if (array != null) {
            array.fill((byte) 0);
            buffer.set(JAVA_LONG, V4l2.BUFFER_PLANES, array.address());
            buffer.set(JAVA_INT, V4l2.BUFFER_LENGTH, planes);
        }
    }

    /**
     * Stops streaming and releases every resource of a session, ignoring failures.
     *
     * @param posix the C library bindings
     * @param fd the device descriptor
     * @param type the buffer type
     * @param wakeup the event counter, or {@code -1}
     * @param buffers the mapped buffers, possibly partially filled, or {@code null}
     */
    private static void release(Posix posix, int fd, int type, int wakeup, MemorySegment[][] buffers) {
        stream(V4l2Device.of((request, argument) -> posix.ioctl(fd, request, argument), type), V4l2.VIDIOC_STREAMOFF);
        if (buffers != null) {
            for (var buffer : buffers) {
                for (var plane : buffer) {
                    if (plane != null) {
                        posix.munmap(plane);
                    }
                }
            }
        }
        posix.close(wakeup);
        posix.close(fd);
    }

    @Override
    public CaptureDevice.Camera device() {
        return camera;
    }

    @Override
    public int width() {
        return converter.outputWidth();
    }

    @Override
    public int height() {
        return converter.outputHeight();
    }

    @Override
    public Frame read() throws InterruptedException {
        readLock.lockInterruptibly();
        try {
            while (true) {
                var raw = exchange.take();
                if (raw == null) {
                    return null;
                }
                var picture = converter.convert(raw.data(), raw.length());
                if (picture != null) {
                    unusable = 0;
                    return new Frame(picture, raw.timestampMicros());
                }
                if (++unusable >= MAX_UNUSABLE_FRAMES) {
                    var cause = new IOException("the camera delivered " + unusable + " consecutive frames that could not be converted");
                    throw new MediaException.Io(cause.getMessage(), cause);
                }
            }
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public CameraControls controls() {
        return controls;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        exchange.close();
        controls.close();
        var control = new Posix();
        control.signal(wakeup);
        var interrupted = false;
        while (true) {
            try {
                pump.join();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        release(control, fd, bufferType, wakeup, buffers);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs the capture thread until the session closes or the device stops.
     */
    private void pump() {
        try (var arena = Arena.ofConfined()) {
            var descriptors = arena.allocate(Posix.POLLFD, 2);
            var stride = Posix.POLLFD.byteSize();
            descriptors.set(JAVA_INT, 0, fd);
            descriptors.set(JAVA_SHORT, Posix.POLLFD_EVENTS, Posix.POLLIN);
            descriptors.set(JAVA_INT, stride, wakeup);
            descriptors.set(JAVA_SHORT, stride + Posix.POLLFD_EVENTS, Posix.POLLIN);
            var planes = buffers[0].length;
            var multiPlanar = bufferType == V4l2.BUF_TYPE_VIDEO_CAPTURE_MPLANE;
            var buffer = arena.allocate(V4l2.BUFFER);
            var bufferPlanes = multiPlanar ? arena.allocate(V4l2.PLANE, planes) : null;
            var requeued = arena.allocate(V4l2.BUFFER);
            var requeuedPlanes = multiPlanar ? arena.allocate(V4l2.PLANE, planes) : null;
            while (!closed.get()) {
                descriptors.set(JAVA_SHORT, Posix.POLLFD_REVENTS, (short) 0);
                descriptors.set(JAVA_SHORT, stride + Posix.POLLFD_REVENTS, (short) 0);
                if (posix.poll(descriptors, 2, -1) < 0) {
                    exchange.finish(Posix.failure("poll", posix.errno()));
                    return;
                }
                if (closed.get() || descriptors.get(JAVA_SHORT, stride + Posix.POLLFD_REVENTS) != 0) {
                    return;
                }
                var events = descriptors.get(JAVA_SHORT, Posix.POLLFD_REVENTS);
                if (events == 0) {
                    continue;
                }
                var outcome = layout.alternate()
                        ? drainFields(buffer, bufferPlanes, requeued, requeuedPlanes)
                        : drain(buffer, bufferPlanes, requeued, requeuedPlanes);
                if (outcome == STOPPED
                        || (outcome == NOTHING && (events & (Posix.POLLERR | Posix.POLLHUP | Posix.POLLNVAL)) != 0)) {
                    exchange.finish(null);
                    return;
                }
            }
        } catch (MediaException.Io failure) {
            exchange.finish(failure.getCause());
        } catch (Throwable unexpected) {
            exchange.finish(new IOException("the capture thread failed", unexpected));
        }
    }

    /**
     * Dequeues a filled buffer and records the data of its memory planes in {@link #dataOffset}
     * and {@link #used}.
     *
     * @param buffer a scratch {@code struct v4l2_buffer}
     * @param array its {@code v4l2_plane} array, or {@code null} on a single-planar device
     * @return the buffer index, {@code -1} when no buffer is filled, {@code -2} when the device
     * is gone, or {@code -3 - index} for a buffer that holds no usable data
     * @throws MediaException.Io if the request fails for another reason than the device leaving
     */
    private int dequeue(MemorySegment buffer, MemorySegment array) {
        var planes = buffers[0].length;
        prepare(buffer, array, bufferType, 0, planes);
        var error = posix.ioctl(fd, V4l2.VIDIOC_DQBUF, buffer);
        if (error == Posix.EAGAIN) {
            return -1;
        }
        if (gone(error)) {
            return -2;
        }
        if (error != 0) {
            throw V4l2Formats.failure("VIDIOC_DQBUF", error);
        }
        var index = buffer.get(JAVA_INT, V4l2.BUFFER_INDEX);
        if (index < 0 || index >= buffers.length) {
            throw new MediaException.Io("the driver returned an unknown buffer",
                    new IOException("VIDIOC_DQBUF returned buffer " + index + " of " + buffers.length));
        }
        var usable = (buffer.get(JAVA_INT, V4l2.BUFFER_FLAGS) & V4l2.BUF_FLAG_ERROR) == 0;
        for (var plane = 0; plane < planes; plane++) {
            if (array == null) {
                dataOffset[plane] = 0;
                used[plane] = Integer.toUnsignedLong(buffer.get(JAVA_INT, V4l2.BUFFER_BYTESUSED));
            } else {
                var base = plane * V4l2.PLANE.byteSize();
                dataOffset[plane] = Integer.toUnsignedLong(array.get(JAVA_INT, base + V4l2.PLANE_DATA_OFFSET));
                used[plane] = Integer.toUnsignedLong(array.get(JAVA_INT, base + V4l2.PLANE_BYTESUSED));
            }
            usable &= used[plane] > dataOffset[plane] && used[plane] <= buffers[index][plane].byteSize();
        }
        return usable ? index : -3 - index;
    }

    /**
     * Returns the timestamp of the buffer last dequeued into a structure.
     *
     * @param buffer the structure
     * @return the timestamp in microseconds of the monotonic clock
     */
    private long timestamp(MemorySegment buffer) {
        return (buffer.get(JAVA_INT, V4l2.BUFFER_FLAGS) & V4l2.BUF_FLAG_TIMESTAMP_MASK) == V4l2.BUF_FLAG_TIMESTAMP_MONOTONIC
                ? buffer.get(JAVA_LONG, V4l2.BUFFER_TV_SEC) * 1_000_000L + buffer.get(JAVA_LONG, V4l2.BUFFER_TV_USEC)
                : posix.monotonicNanos() / 1_000;
    }

    /**
     * Dequeues every filled buffer, publishes the newest valid frame and requeues them all.
     *
     * @param buffer a scratch {@code struct v4l2_buffer} for the dequeues
     * @param array its {@code v4l2_plane} array, or {@code null}
     * @param requeued a scratch {@code struct v4l2_buffer} for the requeues
     * @param requeuedArray its {@code v4l2_plane} array, or {@code null}
     * @return {@link #PUBLISHED}, {@link #NOTHING} or {@link #STOPPED}
     * @throws MediaException.Io if a request fails for another reason than the device leaving
     */
    private int drain(MemorySegment buffer, MemorySegment array, MemorySegment requeued, MemorySegment requeuedArray) {
        var newest = -1;
        var newestTimestamp = 0L;
        var frame = exchange.producerFrame();
        for (var attempt = 0; attempt < 2 * buffers.length; attempt++) {
            var index = dequeue(buffer, array);
            if (index == -1) {
                break;
            }
            if (index == -2) {
                return STOPPED;
            }
            if (index < 0) {
                if (requeue(requeued, requeuedArray, -3 - index) != 0) {
                    return STOPPED;
                }
                continue;
            }
            if (newest >= 0 && requeue(requeued, requeuedArray, newest) != 0) {
                return STOPPED;
            }
            newest = index;
            newestTimestamp = timestamp(buffer);
            System.arraycopy(dataOffset, 0, newestOffset, 0, dataOffset.length);
            System.arraycopy(used, 0, newestUsed, 0, used.length);
        }
        if (newest < 0) {
            return NOTHING;
        }
        var length = layout.copy(buffers[newest], newestOffset, newestUsed, false, frame.data());
        if (requeue(requeued, requeuedArray, newest) != 0) {
            return STOPPED;
        }
        publish(frame, Math.max(length, 0), newestTimestamp);
        return PUBLISHED;
    }

    /**
     * Dequeues every filled buffer of alternating fields, weaves each into the frame of its
     * sequence number and publishes the frames whose two fields arrived.
     *
     * @param buffer a scratch {@code struct v4l2_buffer} for the dequeues
     * @param array its {@code v4l2_plane} array, or {@code null}
     * @param requeued a scratch {@code struct v4l2_buffer} for the requeues
     * @param requeuedArray its {@code v4l2_plane} array, or {@code null}
     * @return {@link #PUBLISHED}, {@link #NOTHING} or {@link #STOPPED}
     * @throws MediaException.Io if a request fails for another reason than the device leaving
     */
    private int drainFields(MemorySegment buffer, MemorySegment array, MemorySegment requeued, MemorySegment requeuedArray) {
        var outcome = NOTHING;
        for (var attempt = 0; attempt < 2 * buffers.length; attempt++) {
            var index = dequeue(buffer, array);
            if (index == -1) {
                break;
            }
            if (index == -2) {
                return STOPPED;
            }
            if (index >= 0) {
                var field = buffer.get(JAVA_INT, V4l2.BUFFER_FIELD);
                var sequence = Integer.toUnsignedLong(buffer.get(JAVA_INT, V4l2.BUFFER_SEQUENCE));
                if (field == V4l2.FIELD_TOP || field == V4l2.FIELD_BOTTOM) {
                    if (sequence != fieldSequence) {
                        fieldSequence = sequence;
                        fieldsSeen = 0;
                        fieldTimestamp = timestamp(buffer);
                    }
                    var frame = exchange.producerFrame();
                    var bottom = field == V4l2.FIELD_BOTTOM;
                    if (layout.copy(buffers[index], dataOffset, used, bottom, frame.data()) < 0) {
                        fieldSequence = -1;
                    } else {
                        fieldsSeen |= bottom ? 2 : 1;
                        if (fieldsSeen == 3) {
                            publish(frame, layout.frameBytes(), fieldTimestamp);
                            fieldSequence = -1;
                            outcome = PUBLISHED;
                        }
                    }
                }
            } else {
                index = -3 - index;
            }
            if (requeue(requeued, requeuedArray, index) != 0) {
                return STOPPED;
            }
        }
        return outcome;
    }

    /**
     * Publishes the producer frame with a monotonic timestamp relative to the start of streaming.
     *
     * @param frame the producer frame
     * @param length the number of bytes of the frame
     * @param timestamp the driver or dequeue timestamp in microseconds
     */
    private void publish(RawFrameExchange.RawFrame frame, int length, long timestamp) {
        var relative = Math.max(Math.max(timestamp - originMicros, 0), lastTimestamp + 1);
        lastTimestamp = relative;
        frame.set(length, relative);
        exchange.publish();
    }

    /**
     * Requeues a buffer from the capture thread.
     *
     * @param scratch a scratch {@code struct v4l2_buffer}
     * @param array its {@code v4l2_plane} array, or {@code null}
     * @param index the buffer index
     * @return {@code 0} on success, non-zero when the device is gone
     * @throws MediaException.Io if the request fails for another reason
     */
    private int requeue(MemorySegment scratch, MemorySegment array, int index) {
        prepare(scratch, array, bufferType, index, buffers[0].length);
        var error = posix.ioctl(fd, V4l2.VIDIOC_QBUF, scratch);
        if (error == 0 || gone(error)) {
            return error;
        }
        throw V4l2Formats.failure("VIDIOC_QBUF", error);
    }

    /**
     * Returns whether an error means the device stopped: it was unplugged, or its driver shut the
     * queue down.
     *
     * @param error the {@code errno}
     * @return whether the stream ended
     */
    private static boolean gone(int error) {
        return error == Posix.ENODEV || error == Posix.EIO || error == Posix.ENXIO;
    }

    /**
     * Locates a memory plane of a driver buffer.
     *
     * @param offset the offset to pass to {@code mmap}
     * @param length the length in bytes
     */
    private record BufferLocation(long offset, long length) {
    }
}
