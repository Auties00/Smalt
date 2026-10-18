package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.VideoCaptureSession;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Captures a Video4Linux2 camera with memory-mapped streaming I/O.
 *
 * <p>Opening negotiates the format ({@code VIDIOC_S_FMT}) and frame rate ({@code VIDIOC_S_PARM}),
 * allocates {@value #REQUESTED_BUFFERS} driver buffers ({@code VIDIOC_REQBUFS}), maps them
 * ({@code VIDIOC_QUERYBUF}, {@code mmap}), queues them ({@code VIDIOC_QBUF}) and starts streaming
 * ({@code VIDIOC_STREAMON}). A dedicated platform thread then waits in {@code poll} on the device
 * and on an {@code eventfd}, dequeues every filled buffer ({@code VIDIOC_DQBUF} on the
 * non-blocking descriptor), copies the newest valid one into a {@link RawFrameExchange} and requeues
 * them all at once, so the driver is never starved by a slow reader and a reader always receives
 * the newest frame. {@link #read()} converts that frame to 4:2:0 on the reader's thread and waits
 * without holding a native call, so it may run on a virtual thread.
 *
 * <p>Frames flagged {@code V4L2_BUF_FLAG_ERROR}, empty frames and frames that do not convert are
 * skipped; {@value #MAX_UNUSABLE_FRAMES} unusable frames in a row fail the read. A device that
 * disappears ({@code ENODEV}, or {@code EIO} from a queue the driver shut down) ends the stream:
 * {@link #read()} then returns {@code null}. Timestamps come from the driver when they are
 * {@code CLOCK_MONOTONIC}, from the dequeue time otherwise, relative to the moment streaming
 * started and strictly increasing.
 *
 * <p>{@link #close()} wakes the thread through the {@code eventfd}, waits for it to finish and
 * then stops streaming, unmaps the buffers and closes the descriptors, so the camera can be
 * reopened as soon as it returns.
 */
final class V4l2CameraSession implements VideoCaptureSession {
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
     * The event counter that wakes the capture thread.
     */
    private final int wakeup;

    /**
     * The mapped driver buffers, by index.
     */
    private final MemorySegment[] buffers;

    /**
     * The converter of the negotiated frames.
     */
    private final FrameConverter converter;

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
     * Starts the capture thread over a streaming device.
     *
     * @param posix the C library bindings, handed over to the capture thread
     * @param fd the device descriptor
     * @param wakeup the event counter
     * @param buffers the mapped, queued driver buffers
     * @param converter the converter of the negotiated frames
     * @param originMicros the monotonic time streaming started, in microseconds
     */
    private V4l2CameraSession(CaptureDevice.Camera camera, Posix posix, int fd, int wakeup, MemorySegment[] buffers,
                              FrameConverter converter, long originMicros) {
        this.camera = camera;
        this.posix = posix;
        this.fd = fd;
        this.wakeup = wakeup;
        this.buffers = buffers;
        this.converter = converter;
        this.originMicros = originMicros;
        var capacity = 0L;
        for (var buffer : buffers) {
            capacity = Math.max(capacity, buffer.byteSize());
        }
        this.exchange = new RawFrameExchange((int) capacity);
        this.pump = Thread.ofPlatform().daemon().name("smalt-v4l2-" + camera.id()).unstarted(this::pump);
        pump.start();
    }

    /**
     * Opens a camera.
     *
     * @param mode the mode, or {@code null} for the default mode of {@link V4l2Formats#select}
     * @return the session
     * @throws MediaException.Io if the camera cannot be opened, is in use, or fails
     * @throws MediaException.Unsupported if the camera cannot capture {@code mode}
     */
    static V4l2CameraSession open(CaptureDevice.Camera camera, CaptureMode mode) {
        var path = V4l2Cameras.nodePath(camera.id());
        var posix = new Posix();
        var fd = posix.open(path, Posix.O_RDWR | Posix.O_NONBLOCK | Posix.O_CLOEXEC);
        if (fd < 0) {
            throw V4l2Formats.failure("open " + path, posix.errno());
        }
        var wakeup = -1;
        MemorySegment[] buffers = null;
        try {
            V4l2Device device = (request, argument) -> posix.ioctl(fd, request, argument);
            var capability = V4l2Formats.query(device);
            if (!capability.canCapture()) {
                throw new MediaException.Unsupported("v4l2 device", path + " is not a single-planar streaming capture device");
            }
            var offer = V4l2Formats.select(device, mode);
            var converter = V4l2Formats.negotiate(device, offer, ConversionKernel.INSTANCE);
            V4l2Formats.requestRate(device, offer.rate());
            buffers = allocateBuffers(device);
            for (var index = 0; index < buffers.length; index++) {
                var location = locate(device, index);
                if (location.length() < converter.requiredBytes()) {
                    throw new MediaException.Io("the driver allocated too small a buffer", new IOException("buffer of "
                            + location.length() + " bytes for frames of " + converter.requiredBytes() + " bytes"));
                }
                buffers[index] = posix.mmap(fd, location.length(), location.offset());
                if (buffers[index] == null) {
                    throw V4l2Formats.failure("mmap", posix.errno());
                }
            }
            for (var index = 0; index < buffers.length; index++) {
                var error = queue(device, index);
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
            return new V4l2CameraSession(camera, posix, fd, wakeup, buffers, converter, origin);
        } catch (RuntimeException | Error failure) {
            release(posix, fd, wakeup, buffers);
            throw failure;
        }
    }

    /**
     * Requests the driver buffers.
     *
     * @return an array with one empty slot per allocated buffer
     * @throws MediaException.Io if the request fails or allocates no buffer
     */
    private static MemorySegment[] allocateBuffers(V4l2Device device) {
        try (var arena = Arena.ofConfined()) {
            var request = arena.allocate(V4l2.REQUESTBUFFERS);
            request.set(JAVA_INT, V4l2.REQBUFS_COUNT, REQUESTED_BUFFERS);
            request.set(JAVA_INT, V4l2.REQBUFS_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE);
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
            return new MemorySegment[count];
        }
    }

    /**
     * Queries where a driver buffer is mapped and how long it is.
     *
     * @param index the buffer index
     * @return the buffer's location
     * @throws MediaException.Io if the query fails or the length is not usable
     */
    private static BufferLocation locate(V4l2Device device, int index) {
        try (var arena = Arena.ofConfined()) {
            var buffer = describe(arena, index);
            var error = device.ioctl(V4l2.VIDIOC_QUERYBUF, buffer);
            if (error != 0) {
                throw V4l2Formats.failure("VIDIOC_QUERYBUF", error);
            }
            var length = Integer.toUnsignedLong(buffer.get(JAVA_INT, V4l2.BUFFER_LENGTH));
            if (length == 0 || length > Math.min(MediaLimits.DEFAULT.maxBytes(), Integer.MAX_VALUE - 16)) {
                throw new MediaException.Io("the driver allocated an unusable buffer",
                        new IOException("buffer " + index + " of " + length + " bytes"));
            }
            return new BufferLocation(Integer.toUnsignedLong(buffer.get(JAVA_INT, V4l2.BUFFER_OFFSET)), length);
        }
    }

    /**
     * Queues a driver buffer.
     *
     * @param index the buffer index
     * @return {@code 0}, or the {@code errno} of {@code VIDIOC_QBUF}
     */
    private static int queue(V4l2Device device, int index) {
        try (var arena = Arena.ofConfined()) {
            return device.ioctl(V4l2.VIDIOC_QBUF, describe(arena, index));
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
            type.set(JAVA_INT, 0, V4l2.BUF_TYPE_VIDEO_CAPTURE);
            return device.ioctl(request, type);
        }
    }

    /**
     * Allocates a {@code struct v4l2_buffer} naming a memory-mapped capture buffer.
     *
     * @param index the buffer index
     * @return the structure
     */
    private static MemorySegment describe(Arena arena, int index) {
        var buffer = arena.allocate(V4l2.BUFFER);
        buffer.set(JAVA_INT, V4l2.BUFFER_INDEX, index);
        buffer.set(JAVA_INT, V4l2.BUFFER_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE);
        buffer.set(JAVA_INT, V4l2.BUFFER_MEMORY, V4l2.MEMORY_MMAP);
        return buffer;
    }

    /**
     * Stops streaming and releases every resource of a session, ignoring failures.
     *
     * @param posix the C library bindings
     * @param fd the device descriptor
     * @param wakeup the event counter, or {@code -1}
     * @param buffers the mapped buffers, possibly partially filled, or {@code null}
     */
    private static void release(Posix posix, int fd, int wakeup, MemorySegment[] buffers) {
        stream((request, argument) -> posix.ioctl(fd, request, argument), V4l2.VIDIOC_STREAMOFF);
        if (buffers != null) {
            for (var buffer : buffers) {
                if (buffer != null) {
                    posix.munmap(buffer);
                }
            }
        }
        posix.close(wakeup);
        posix.close(fd);
    }

    @Override
    public CaptureDevice device() {
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
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        exchange.close();
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
        release(control, fd, wakeup, buffers);
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
            var buffer = arena.allocate(V4l2.BUFFER);
            var requeued = arena.allocate(V4l2.BUFFER);
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
                var outcome = drain(buffer, requeued);
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
     * Dequeues every filled buffer, publishes the newest valid frame and requeues them all.
     *
     * @param buffer a scratch {@code struct v4l2_buffer} for the dequeues
     * @param requeued a scratch {@code struct v4l2_buffer} for the requeues
     * @return {@link #PUBLISHED}, {@link #NOTHING} or {@link #STOPPED}
     * @throws MediaException.Io if a request fails for another reason than the device leaving
     */
    private int drain(MemorySegment buffer, MemorySegment requeued) {
        var newest = -1;
        var newestLength = 0;
        var newestTimestamp = 0L;
        for (var attempt = 0; attempt < 2 * buffers.length; attempt++) {
            buffer.fill((byte) 0);
            buffer.set(JAVA_INT, V4l2.BUFFER_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE);
            buffer.set(JAVA_INT, V4l2.BUFFER_MEMORY, V4l2.MEMORY_MMAP);
            var error = posix.ioctl(fd, V4l2.VIDIOC_DQBUF, buffer);
            if (error == Posix.EAGAIN) {
                break;
            }
            if (gone(error)) {
                return STOPPED;
            }
            if (error != 0) {
                throw V4l2Formats.failure("VIDIOC_DQBUF", error);
            }
            var index = buffer.get(JAVA_INT, V4l2.BUFFER_INDEX);
            if (index < 0 || index >= buffers.length) {
                throw new MediaException.Io("the driver returned an unknown buffer",
                        new IOException("VIDIOC_DQBUF returned buffer " + index + " of " + buffers.length));
            }
            var flags = buffer.get(JAVA_INT, V4l2.BUFFER_FLAGS);
            var used = Integer.toUnsignedLong(buffer.get(JAVA_INT, V4l2.BUFFER_BYTESUSED));
            if ((flags & V4l2.BUF_FLAG_ERROR) != 0 || used == 0 || used > buffers[index].byteSize()) {
                if (requeue(requeued, index) != 0) {
                    return STOPPED;
                }
                continue;
            }
            if (newest >= 0 && requeue(requeued, newest) != 0) {
                return STOPPED;
            }
            newest = index;
            newestLength = (int) used;
            newestTimestamp = (flags & V4l2.BUF_FLAG_TIMESTAMP_MASK) == V4l2.BUF_FLAG_TIMESTAMP_MONOTONIC
                    ? buffer.get(JAVA_LONG, V4l2.BUFFER_TV_SEC) * 1_000_000L + buffer.get(JAVA_LONG, V4l2.BUFFER_TV_USEC)
                    : posix.monotonicNanos() / 1_000;
        }
        if (newest < 0) {
            return NOTHING;
        }
        var frame = exchange.producerFrame();
        MemorySegment.copy(buffers[newest], JAVA_BYTE, 0, frame.data(), 0, newestLength);
        if (requeue(requeued, newest) != 0) {
            return STOPPED;
        }
        var timestamp = Math.max(Math.max(newestTimestamp - originMicros, 0), lastTimestamp + 1);
        lastTimestamp = timestamp;
        frame.set(newestLength, timestamp);
        exchange.publish();
        return PUBLISHED;
    }

    /**
     * Requeues a buffer from the capture thread.
     *
     * @param scratch a scratch {@code struct v4l2_buffer}
     * @param index the buffer index
     * @return {@code 0} on success, non-zero when the device is gone
     * @throws MediaException.Io if the request fails for another reason
     */
    private int requeue(MemorySegment scratch, int index) {
        scratch.fill((byte) 0);
        scratch.set(JAVA_INT, V4l2.BUFFER_INDEX, index);
        scratch.set(JAVA_INT, V4l2.BUFFER_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE);
        scratch.set(JAVA_INT, V4l2.BUFFER_MEMORY, V4l2.MEMORY_MMAP);
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
     * Locates a driver buffer.
     *
     * @param offset the offset to pass to {@code mmap}
     * @param length the length in bytes
     */
    private record BufferLocation(long offset, long length) {
    }
}
