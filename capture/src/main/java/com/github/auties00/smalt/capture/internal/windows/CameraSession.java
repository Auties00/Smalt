package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CameraCaptureSession;
import com.github.auties00.smalt.capture.CameraControls;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.internal.FrameExchange;
import com.github.auties00.smalt.capture.internal.Pictures;
import com.github.auties00.smalt.capture.internal.PumpThread;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Captures a camera with a Media Foundation source reader.
 *
 * <p>The session owns a platform thread that joins the COM multithreaded apartment, starts Media
 * Foundation, opens the camera's media source by its symbolic link, wraps it in a source reader,
 * selects the native media type that serves the requested mode (see
 * {@link NativeType#choose(java.util.List, CaptureMode, NativeType)}) and then reads samples
 * synchronously, converting each into a picture of the session's {@link FrameExchange}. All Media
 * Foundation objects are created, used and released on that thread, except
 * {@code IMFSourceReader::Flush}, which {@link #close()} calls from the closing thread to cancel a
 * pending read.
 *
 * <p>Frames are stamped with the sample times the camera reports, anchored to the session's
 * clock at the first frame, so the device's cadence survives scheduling jitter; when the camera's
 * clock and the session's drift apart by more than half a second the anchor is reset. Frames that
 * fail to convert (a corrupt Motion-JPEG frame, a buffer smaller than its media type) are
 * skipped; after {@value #MAX_BAD_FRAMES} consecutive failures the session fails.
 */
final class CameraSession implements CameraCaptureSession {
    /**
     * The longest wait for the capture thread to open the camera.
     */
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(15);

    /**
     * The longest wait for the capture thread to stop in {@link #close()}.
     */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The number of consecutive unconvertible frames after which the session fails.
     */
    private static final int MAX_BAD_FRAMES = 60;

    /**
     * The largest distance, in microseconds, between a frame's sample time and its arrival
     * before the timestamp anchor is reset.
     */
    private static final long MAX_DRIFT_MICROS = 500_000;

    /**
     * The camera.
     */
    private final CaptureDevice.Camera device;

    /**
     * The capture thread's state.
     */
    private final Worker worker;

    /**
     * The capture thread.
     */
    private final PumpThread pump;

    /**
     * Whether {@link #close()} was called.
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a session over an opened capture thread.
     *
     * @param device the camera
     * @param worker the capture thread's state
     * @param pump the capture thread
     */
    private CameraSession(CaptureDevice.Camera device, Worker worker, PumpThread pump) {
        this.device = device;
        this.worker = worker;
        this.pump = pump;
    }

    /**
     * Opens a camera.
     *
     * @param mode the requested mode, or {@code null} for the driver's default
     * @return the session
     * @throws MediaException.Io if the camera cannot be opened
     * @throws MediaException.Unsupported if Media Foundation is missing or the camera cannot
     * capture in {@code mode}
     */
    static CameraCaptureSession open(CaptureDevice.Camera camera, CaptureMode mode) {
        if (!MediaFoundation.available()) {
            throw new MediaException.Unsupported("media foundation", "Media Foundation is not installed on this system");
        }
        var pump = new PumpThread("smalt-camera-capture", Thread.NORM_PRIORITY);
        var worker = new Worker(camera, mode, pump);
        pump.start(worker);
        pump.awaitOpen(OPEN_TIMEOUT, "the camera " + camera.name(), worker::abandon);
        return new CameraSession(camera, worker, pump);
    }

    @Override
    public CaptureDevice.Camera device() {
        return device;
    }

    @Override
    public int width() {
        return worker.exchange.width();
    }

    @Override
    public int height() {
        return worker.exchange.height();
    }

    @Override
    public Frame read() throws InterruptedException {
        return worker.exchange.take();
    }

    @Override
    public CameraControls controls() {
        return worker.controls;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.exchange.close();
        worker.cancel();
        pump.join(CLOSE_TIMEOUT);
    }

    /**
     * Runs the capture on the session's thread and holds its native state.
     */
    private static final class Worker implements Runnable {
        /**
         * The camera.
         */
        private final CaptureDevice.Camera camera;

        /**
         * The requested mode, or {@code null}.
         */
        private final CaptureMode mode;

        /**
         * The capture thread, told when the camera is open.
         */
        private final PumpThread pump;

        /**
         * Guards {@link #reader} between the capture thread and {@link #cancel()}.
         */
        private final Object readerLock = new Object();

        /**
         * Whether the opener gave up waiting, so the thread must release the camera as soon as
         * it is open.
         */
        private volatile boolean abandoned;

        /**
         * The controls of the camera, set before the pump is told the camera is open.
         */
        private volatile DirectShowControls controls;

        /**
         * The exchange of the session's pictures, set before the pump is told the camera is open.
         */
        private volatile FrameExchange exchange;

        /**
         * The source reader, or {@link MemorySegment#NULL} once released.
         */
        private MemorySegment reader = MemorySegment.NULL;

        /**
         * The camera's media source.
         */
        private MemorySegment source = MemorySegment.NULL;

        /**
         * The converter of the current media type.
         */
        private CameraConverter converter;

        /**
         * The picture frames of a media type whose size differs from the session's are converted
         * into before rescaling, or {@code null}.
         */
        private YuvPicture resized;

        /**
         * The {@link System#nanoTime()} at which the session opened.
         */
        private long start;

        /**
         * The sample time, in 100-nanosecond units, of the frame the timestamps are anchored to.
         */
        private long anchorSampleTime = Long.MIN_VALUE;

        /**
         * The session timestamp, in microseconds, of the anchor frame.
         */
        private long anchorMicros;

        /**
         * The number of consecutive frames that failed to convert.
         */
        private int badFrames;

        /**
         * Creates the state of a capture.
         *
         * @param mode the requested mode, or {@code null} for the driver's default
         * @param pump the capture thread
         */
        Worker(CaptureDevice.Camera camera, CaptureMode mode, PumpThread pump) {
            this.camera = camera;
            this.mode = mode;
            this.pump = pump;
        }

        /**
         * Makes the capture thread release the camera once it is open.
         */
        private void abandon() {
            abandoned = true;
            var pending = exchange;
            if (pending != null) {
                pending.close();
            }
            cancel();
        }

        /**
         * Cancels a pending read of the capture thread.
         */
        void cancel() {
            synchronized (readerLock) {
                if (!reader.equals(MemorySegment.NULL)) {
                    Com.call(reader, MediaFoundation.READER_FLUSH, MediaFoundation.ALL_STREAMS);
                }
            }
        }

        @Override
        public void run() {
            var initialized = false;
            var started = false;
            try (var arena = Arena.ofConfined()) {
                try {
                    initialized = Com.initialize();
                    MediaFoundation.startup();
                    started = true;
                    openCamera(arena);
                } catch (MediaException exception) {
                    pump.failOpen(exception);
                    return;
                } catch (RuntimeException exception) {
                    var message = "cannot open the camera " + camera.name();
                    pump.failOpen(new MediaException.Io(message, new IOException(message, exception)));
                    return;
                } finally {
                    pump.markOpen();
                }
                if (abandoned) {
                    return;
                }
                capture(arena);
            } catch (MediaException exception) {
                exchange.fail(exception);
            } catch (RuntimeException exception) {
                var message = "the camera " + camera.name() + " failed";
                exchange.fail(new MediaException.Io(message, new IOException(message, exception)));
            } finally {
                releaseCamera();
                if (started) {
                    MediaFoundation.shutdown();
                }
                if (initialized) {
                    Com.uninitialize();
                }
            }
        }

        /**
         * Opens the camera, chooses its media type and creates the exchange.
         *
         * @param arena the arena of the capture thread
         * @throws MediaException.Io if a Media Foundation call fails
         * @throws MediaException.Unsupported if no media type serves the requested mode
         */
        private void openCamera(Arena arena) {
            source = CameraDevices.openSource(camera.id(), arena);
            controls = DirectShowControls.open(source);
            var created = MediaFoundation.createSourceReader(source, MemorySegment.NULL, arena.allocate(ADDRESS));
            synchronized (readerLock) {
                reader = created;
            }
            Com.check(Com.call(reader, MediaFoundation.READER_SET_STREAM_SELECTION, MediaFoundation.ALL_STREAMS, 0),
                    "IMFSourceReader::SetStreamSelection");
            Com.check(Com.call(reader, MediaFoundation.READER_SET_STREAM_SELECTION, MediaFoundation.FIRST_VIDEO_STREAM, 1),
                    "IMFSourceReader::SetStreamSelection");
            var types = CameraDevices.nativeTypes(reader, arena);
            var chosen = NativeType.choose(types, mode, CameraDevices.currentType(reader, arena));
            if (chosen == null) {
                throw new MediaException.Unsupported("capture mode", mode == null
                        ? "the camera " + camera.name() + " offers no supported pixel format"
                        : "the camera " + camera.name() + " cannot capture " + mode.width() + "x" + mode.height()
                          + " at " + mode.frameRate() + " frames per second");
            }
            if (chosen.width() < 2 || chosen.height() < 2) {
                throw new MediaException.Unsupported("capture mode", "the camera " + camera.name() + " delivers "
                        + chosen.width() + "x" + chosen.height() + " frames");
            }
            var slot = arena.allocate(ADDRESS);
            Com.check(Com.call(reader, MediaFoundation.READER_GET_NATIVE_MEDIA_TYPE, MediaFoundation.FIRST_VIDEO_STREAM,
                    chosen.index(), slot), "IMFSourceReader::GetNativeMediaType");
            var type = slot.get(ADDRESS, 0);
            try {
                Com.check(Com.call(reader, MediaFoundation.READER_SET_CURRENT_MEDIA_TYPE, MediaFoundation.FIRST_VIDEO_STREAM,
                        MemorySegment.NULL, type), "IMFSourceReader::SetCurrentMediaType");
            } finally {
                Com.release(type);
            }
            converter = new CameraConverter(chosen);
            LimitChecks.checkPicture(MediaLimits.DEFAULT, converter.width(), converter.height());
            exchange = new FrameExchange(converter.width(), converter.height(), chosen.color());
            start = System.nanoTime();
        }

        /**
         * Reads and delivers samples until the session closes or the camera stops.
         *
         * @param arena the arena of the capture thread
         */
        private void capture(Arena arena) {
            var stream = arena.allocate(JAVA_INT);
            var flags = arena.allocate(JAVA_INT);
            var time = arena.allocate(JAVA_LONG);
            var sampleSlot = arena.allocate(ADDRESS);
            var buffers = new Buffers(arena);
            while (!exchange.isStopped()) {
                flags.set(JAVA_INT, 0, 0);
                sampleSlot.set(ADDRESS, 0, MemorySegment.NULL);
                var hr = Com.call(reader, MediaFoundation.READER_READ_SAMPLE, MediaFoundation.FIRST_VIDEO_STREAM, 0,
                        stream, flags, time, sampleSlot);
                var sample = sampleSlot.get(ADDRESS, 0);
                try {
                    if (exchange.isStopped()) {
                        return;
                    }
                    Com.check(hr, "IMFSourceReader::ReadSample");
                    var status = flags.get(JAVA_INT, 0);
                    if ((status & MediaFoundation.READER_ERROR) != 0) {
                        throw Com.failure("the camera " + camera.name() + " reported an error", hr);
                    }
                    if ((status & MediaFoundation.READER_CURRENT_TYPE_CHANGED) != 0) {
                        var current = CameraDevices.currentType(reader, arena);
                        if (current != null) {
                            converter = new CameraConverter(current);
                        }
                    }
                    if (!sample.equals(MemorySegment.NULL)) {
                        deliver(sample, time.get(JAVA_LONG, 0), buffers);
                    }
                    if ((status & MediaFoundation.READER_END_OF_STREAM) != 0) {
                        exchange.finish();
                        return;
                    }
                } finally {
                    Com.release(sample);
                }
            }
        }

        /**
         * Converts and publishes one sample.
         *
         * @param sample the {@code IMFSample}
         * @param sampleTime the sample time in 100-nanosecond units
         * @param buffers the buffer access helper of the capture thread
         * @throws MediaException.Io if too many consecutive frames failed to convert
         */
        private void deliver(MemorySegment sample, long sampleTime, Buffers buffers) {
            var out = exchange.acquire();
            var sameSize = converter.width() == out.width() && converter.height() == out.height();
            YuvPicture target;
            if (sameSize) {
                target = out;
            } else {
                resized = Pictures.reuse(resized, converter.width(), converter.height(), out.color());
                target = resized;
            }
            try {
                buffers.convert(sample, converter, target);
            } catch (MediaException | IndexOutOfBoundsException exception) {
                if (++badFrames >= MAX_BAD_FRAMES) {
                    var message = "the camera " + camera.name() + " delivers frames that cannot be converted";
                    throw new MediaException.Io(message, new IOException(message, exception));
                }
                return;
            }
            badFrames = 0;
            if (!sameSize) {
                Pictures.fit(target, out);
            }
            exchange.publish(out, timestamp(sampleTime));
        }

        /**
         * Computes the session timestamp of a frame.
         *
         * @param sampleTime the sample time in 100-nanosecond units
         * @return the timestamp in microseconds
         */
        private long timestamp(long sampleTime) {
            var arrival = (System.nanoTime() - start) / 1_000;
            if (anchorSampleTime != Long.MIN_VALUE) {
                var fromSample = anchorMicros + (sampleTime - anchorSampleTime) / 10;
                if (Math.abs(fromSample - arrival) <= MAX_DRIFT_MICROS) {
                    return fromSample;
                }
            }
            anchorSampleTime = sampleTime;
            anchorMicros = arrival;
            return arrival;
        }

        /**
         * Releases the reader and the media source.
         */
        private void releaseCamera() {
            var opened = controls;
            if (opened != null) {
                opened.close();
            }
            synchronized (readerLock) {
                Com.release(reader);
                reader = MemorySegment.NULL;
            }
            if (!source.equals(MemorySegment.NULL)) {
                Com.call(source, MediaFoundation.SOURCE_SHUTDOWN);
                Com.release(source);
                source = MemorySegment.NULL;
            }
        }
    }

    /**
     * Locks the buffers of samples on the capture thread and hands their memory to a converter.
     */
    private static final class Buffers {
        /**
         * A pointer-sized slot receiving interface pointers.
         */
        private final MemorySegment slot;

        /**
         * A pointer-sized slot receiving the first scan line or the buffer's address.
         */
        private final MemorySegment scanline;

        /**
         * A pointer-sized slot receiving the buffer's start.
         */
        private final MemorySegment bufferStart;

        /**
         * A 32-bit slot receiving the pitch.
         */
        private final MemorySegment pitch;

        /**
         * A 32-bit slot receiving a length.
         */
        private final MemorySegment length;

        /**
         * A 32-bit slot receiving a second length or count.
         */
        private final MemorySegment count;

        /**
         * Allocates the slots.
         *
         * @param arena the arena of the capture thread
         */
        Buffers(Arena arena) {
            this.slot = arena.allocate(ADDRESS);
            this.scanline = arena.allocate(ADDRESS);
            this.bufferStart = arena.allocate(ADDRESS);
            this.pitch = arena.allocate(JAVA_INT);
            this.length = arena.allocate(JAVA_INT);
            this.count = arena.allocate(JAVA_INT);
        }

        /**
         * Converts the image of a sample.
         *
         * @param sample the {@code IMFSample}
         * @param converter the converter of the sample's media type
         * @param out the picture receiving the frame
         * @throws MediaException if the sample cannot be read or decoded
         * @throws IndexOutOfBoundsException if the buffer is smaller than its media type requires
         */
        void convert(MemorySegment sample, CameraConverter converter, YuvPicture out) {
            Com.check(Com.call(sample, MediaFoundation.SAMPLE_GET_BUFFER_COUNT, count), "IMFSample::GetBufferCount");
            int hr;
            if (count.get(JAVA_INT, 0) == 1) {
                hr = Com.call(sample, MediaFoundation.SAMPLE_GET_BUFFER_BY_INDEX, 0, slot);
            } else {
                hr = Com.call(sample, MediaFoundation.SAMPLE_CONVERT_TO_CONTIGUOUS_BUFFER, slot);
            }
            Com.check(hr, "IMFSample::GetBufferByIndex");
            var buffer = slot.get(ADDRESS, 0);
            try {
                if (converter.type().format() == CameraFormat.MJPG) {
                    convertContiguous(buffer, converter, out);
                    return;
                }
                var buffer2 = Com.query(buffer, MediaFoundation.IID_2D_BUFFER2, slot);
                if (!buffer2.equals(MemorySegment.NULL)) {
                    try {
                        convertLocked2(buffer2, converter, out);
                    } finally {
                        Com.release(buffer2);
                    }
                    return;
                }
                var buffer1 = Com.query(buffer, MediaFoundation.IID_2D_BUFFER, slot);
                if (!buffer1.equals(MemorySegment.NULL)) {
                    try {
                        convertLocked(buffer1, converter, out);
                    } finally {
                        Com.release(buffer1);
                    }
                    return;
                }
                convertContiguous(buffer, converter, out);
            } finally {
                Com.release(buffer);
            }
        }

        /**
         * Converts a buffer through {@code IMF2DBuffer2::Lock2DSize}, which reports the buffer's
         * bounds.
         *
         * @param buffer the {@code IMF2DBuffer2}
         * @param out the picture receiving the frame
         */
        private void convertLocked2(MemorySegment buffer, CameraConverter converter, YuvPicture out) {
            Com.check(Com.call(buffer, MediaFoundation.BUFFER_2D_LOCK_SIZE, MediaFoundation.LOCK_READ, scanline, pitch,
                    bufferStart, length), "IMF2DBuffer2::Lock2DSize");
            try {
                var start = bufferStart.get(ADDRESS, 0);
                var memory = start.reinterpret(Integer.toUnsignedLong(length.get(JAVA_INT, 0)));
                var origin = scanline.get(ADDRESS, 0).address() - start.address();
                converter.convert(memory, origin, pitch.get(JAVA_INT, 0), out);
            } finally {
                Com.call(buffer, MediaFoundation.BUFFER_2D_UNLOCK);
            }
        }

        /**
         * Converts a buffer through {@code IMF2DBuffer::Lock2D}, whose bounds follow from the
         * pitch and the media type.
         *
         * @param buffer the {@code IMF2DBuffer}
         * @param out the picture receiving the frame
         */
        private void convertLocked(MemorySegment buffer, CameraConverter converter, YuvPicture out) {
            Com.check(Com.call(buffer, MediaFoundation.BUFFER_2D_LOCK, scanline, pitch), "IMF2DBuffer::Lock2D");
            try {
                var first = scanline.get(ADDRESS, 0);
                long stride = pitch.get(JAVA_INT, 0);
                var extent = converter.extent(Math.abs(stride));
                var start = stride >= 0 ? first.address() : first.address() + stride * (converter.type().height() - 1L);
                var memory = MemorySegment.ofAddress(start).reinterpret(extent);
                converter.convert(memory, first.address() - start, stride, out);
            } finally {
                Com.call(buffer, MediaFoundation.BUFFER_2D_UNLOCK);
            }
        }

        /**
         * Converts a buffer through {@code IMFMediaBuffer::Lock}, laid out with the type's default
         * stride.
         *
         * @param buffer the {@code IMFMediaBuffer}
         * @param out the picture receiving the frame
         */
        private void convertContiguous(MemorySegment buffer, CameraConverter converter, YuvPicture out) {
            Com.check(Com.call(buffer, MediaFoundation.BUFFER_LOCK, scanline, count, length), "IMFMediaBuffer::Lock");
            try {
                var memory = scanline.get(ADDRESS, 0).reinterpret(Integer.toUnsignedLong(length.get(JAVA_INT, 0)));
                if (converter.type().format() == CameraFormat.MJPG) {
                    converter.convertJpeg(memory, memory.byteSize(), out);
                    return;
                }
                var stride = converter.contiguousPitch();
                var origin = stride >= 0 ? 0 : -stride * (converter.type().height() - 1L);
                converter.convert(memory, origin, stride, out);
            } finally {
                Com.call(buffer, MediaFoundation.BUFFER_UNLOCK);
            }
        }
    }
}
