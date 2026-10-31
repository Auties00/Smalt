package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.capture.internal.FrameExchange;
import com.github.auties00.smalt.capture.internal.FramePacing;
import com.github.auties00.smalt.capture.internal.Native;
import com.github.auties00.smalt.capture.internal.Pictures;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Captures a screen of a Wayland session through the screen cast portal and PipeWire.
 *
 * <p>Opening starts a cast ({@link ScreenCastPortal}), during which the desktop may ask the user which
 * monitor to share, connects to PipeWire with the descriptor the portal returned, and connects a
 * consumer stream to the shared node, offering raw video in the 4-byte R'G'B' layouts at up to the
 * requested rate. The producer's buffers arrive in shared memory, mapped by PipeWire
 * ({@code PW_STREAM_FLAG_MAP_BUFFERS}); every buffer is converted to BT.709 limited-range 4:2:0 on
 * the PipeWire loop's thread and handed to the reader through a {@link FrameExchange}, which repeats
 * the newest frame at the requested rate while the screen does not change (screen casts send frames
 * only on damage). Opening waits until the format is negotiated, so the session's size is the size
 * of the shared monitor, or of the part of the options' region on it; a later change of the
 * monitor's size is scaled to that size. The pointer is drawn when the options ask for it and the
 * portal can embed it.
 *
 * <p>The cast ends when the compositor stops the stream (the monitor was unplugged, or the user
 * revoked the share): {@link #read()} then returns {@code null}.
 */
final class PortalScreenSession implements VideoCaptureSession {
    /**
     * The highest frame rate requested.
     */
    private static final int MAX_RATE = 1_000;

    /**
     * How long the format negotiation may take.
     */
    private static final int FORMAT_TIMEOUT_SECONDS = 15;

    /**
     * The {@code spa_video_format} ids offered, preferred first: {@code BGRx}, {@code BGRA},
     * {@code RGBx}, {@code RGBA}, {@code xRGB}, {@code ARGB}, {@code xBGR}, {@code ABGR}.
     */
    static final List<Integer> FORMATS = List.of(8, 12, 7, 11, 9, 13, 10, 14);

    /**
     * The device.
     */
    private final CaptureDevice device;

    /**
     * The cast, closed after the stream.
     */
    private final ScreenCastPortal.Cast cast;

    /**
     * The options.
     */
    private final ScreenCaptureOptions options;

    /**
     * The arena of the stream's callbacks, hook and parameters, closed after the loop stopped.
     */
    private final Arena arena = Arena.ofShared();

    /**
     * The PipeWire thread loop.
     */
    private final MemorySegment loop;

    /**
     * The PipeWire context.
     */
    private MemorySegment context = MemorySegment.NULL;

    /**
     * The consumer stream.
     */
    private MemorySegment stream = MemorySegment.NULL;

    /**
     * The row kernel.
     */
    private final ConversionKernel kernel = ConversionKernel.INSTANCE;

    /**
     * The negotiated format; written by the loop's thread with its lock held.
     */
    private volatile SpaPod.VideoFormat format;

    /**
     * The stream's last state; written by the loop's thread.
     */
    private volatile int state = PipeWire.STATE_UNCONNECTED;

    /**
     * The error of a failed stream, or {@code null}; written by the loop's thread.
     */
    private volatile String error;

    /**
     * Whether the stream reached the streaming state.
     */
    private volatile boolean streamed;

    /**
     * The exchange, or {@code null} until the format is known.
     */
    private volatile FrameExchange exchange;

    /**
     * The area of the frames captured at the negotiated size; used by the loop's thread.
     */
    private CaptureArea area;

    /**
     * The pixels of the last buffer, copied out of shared memory; used by the loop's thread.
     */
    private byte[] pixels = new byte[0];

    /**
     * The picture a frame of another size than the session's is converted into; used by the loop's
     * thread.
     */
    private YuvPicture scratch;

    /**
     * Whether the session is closed; guarded by {@link #lock}.
     */
    private boolean closed;

    /**
     * Serializes the close.
     */
    private final Object lock = new Object();

    /**
     * Creates the loop of a cast.
     */
    private PortalScreenSession(CaptureDevice device, ScreenCastPortal.Cast cast, ScreenCaptureOptions options) {
        this.device = device;
        this.cast = cast;
        this.options = options;
        this.loop = PipeWire.loopNew("smalt-pipewire");
        if (loop.equals(MemorySegment.NULL)) {
            throw new MediaException.Io("cannot create a PipeWire loop", new IOException("pw_thread_loop_new failed"));
        }
    }

    /**
     * Opens a capture of a screen through the portal.
     *
     * @param device the screen or window the session reports
     * @param sourceType {@link ScreenCastPortal#SOURCE_MONITOR} or {@link ScreenCastPortal#SOURCE_WINDOW}
     * @param options the rate, region and pointer settings
     * @return the session
     * @throws MediaException.Unsupported if PipeWire, the session bus or the portal is missing, or the
     * producer offers no format this session converts
     * @throws MediaException.Io if the user cancels the share or the stream fails
     */
    static PortalScreenSession open(CaptureDevice device, long sourceType, ScreenCaptureOptions options) {
        if (!PipeWire.available()) {
            throw new MediaException.Unsupported("screen cast", "Wayland screen capture needs libpipewire-0.3.so.0");
        }
        var cast = ScreenCastPortal.start(sourceType, options.cursor());
        PortalScreenSession session = null;
        try {
            session = new PortalScreenSession(device, cast, options);
            session.connect();
            return session;
        } catch (RuntimeException | Error failure) {
            if (session != null) {
                session.close();
            } else {
                new Posix().close(cast.pipewire());
                cast.close();
            }
            throw failure;
        }
    }

    /**
     * Connects the stream and waits for the format.
     */
    private void connect() {
        var lookup = MethodHandles.lookup();
        MemorySegment events;
        try {
            var linker = Native.LINKER;
            events = arena.allocate(PipeWire.STREAM_EVENTS);
            events.set(ADDRESS, PipeWire.EVENTS_STATE_CHANGED, linker.upcallStub(lookup.findVirtual(PortalScreenSession.class,
                    "onState", MethodType.methodType(void.class, MemorySegment.class, int.class, int.class, MemorySegment.class))
                    .bindTo(this), PipeWire.STATE_CHANGED, arena));
            events.set(ADDRESS, PipeWire.EVENTS_PARAM_CHANGED, linker.upcallStub(lookup.findVirtual(PortalScreenSession.class,
                    "onParam", MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class))
                    .bindTo(this), PipeWire.PARAM_CHANGED, arena));
            events.set(ADDRESS, PipeWire.EVENTS_PROCESS, linker.upcallStub(lookup.findVirtual(PortalScreenSession.class,
                    "onProcess", MethodType.methodType(void.class, MemorySegment.class)).bindTo(this), PipeWire.PROCESS, arena));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        var rate = FramePacing.atMost(options.frameRate(), MAX_RATE);
        var fps = (int) Math.max(1, Math.min(MAX_RATE, Math.ceil(rate.toDouble())));
        var pod = SpaPod.videoEnumFormat(FORMATS, cast.stream().width(), cast.stream().height(), fps);
        var podSegment = arena.allocate(pod.length, 8);
        MemorySegment.copy(pod, 0, podSegment, JAVA_BYTE, 0, pod.length);
        var params = arena.allocate(ADDRESS);
        params.set(ADDRESS, 0, podSegment);
        var hook = arena.allocate(PipeWire.HOOK);
        PipeWire.lock(loop);
        try {
            context = PipeWire.contextNew(PipeWire.loopOf(loop));
            if (context.equals(MemorySegment.NULL)) {
                new Posix().close(cast.pipewire());
                throw new MediaException.Io("cannot create a PipeWire context", new IOException("pw_context_new failed"));
            }
            var core = PipeWire.connect(context, cast.pipewire());
            if (core.equals(MemorySegment.NULL)) {
                throw new MediaException.Io("cannot connect to PipeWire through the portal", new IOException("pw_context_connect_fd failed"));
            }
            stream = PipeWire.streamNew(core, "smalt-screen", "media.type=Video media.category=Capture media.role=Screen");
            if (stream.equals(MemorySegment.NULL)) {
                throw new MediaException.Io("cannot create a PipeWire stream", new IOException("pw_stream_new failed"));
            }
            PipeWire.addListener(stream, hook, events);
            var connected = PipeWire.connectStream(stream, cast.stream().node(), params);
            if (connected < 0) {
                throw new MediaException.Io("cannot connect to the shared PipeWire node", new IOException("pw_stream_connect returned " + connected));
            }
            if (PipeWire.loopStart(loop) < 0) {
                throw new MediaException.Io("cannot start the PipeWire loop", new IOException("pw_thread_loop_start failed"));
            }
            var deadline = System.nanoTime() + Duration.ofSeconds(FORMAT_TIMEOUT_SECONDS).toNanos();
            while (format == null && state != PipeWire.STATE_ERROR) {
                var left = (deadline - System.nanoTime()) / 1_000_000_000L;
                if (left <= 0) {
                    break;
                }
                PipeWire.timedWait(loop, (int) Math.max(1, left));
            }
            var negotiated = format;
            if (negotiated == null) {
                throw new MediaException.Io("the shared screen did not negotiate a format", new IOException(
                        state == PipeWire.STATE_ERROR ? "the stream failed: " + error : "no format within " + FORMAT_TIMEOUT_SECONDS + " seconds"));
            }
            // TODO: offer and read linear DMA-BUF buffers, for compositors that share no shared-memory buffers
            if (layout(negotiated.format()) == null || negotiated.modifier()) {
                throw new MediaException.Unsupported("screen cast format", "the compositor chose the video format "
                        + negotiated.format() + (negotiated.modifier() ? " in DMA-BUF memory" : ""));
            }
            LimitChecks.checkPicture(MediaLimits.DEFAULT, negotiated.width(), negotiated.height());
            area = CaptureArea.of(options, negotiated.width(), negotiated.height(), "the shared screen");
            exchange = new FrameExchange(area.width(), area.height(), ColorInfo.BT709_LIMITED, FramePacing.periodNanos(rate),
                    System::nanoTime, System.nanoTime());
        } finally {
            PipeWire.unlock(loop);
        }
    }

    /**
     * Returns the layout of a {@code spa_video_format} of 4-byte pixels.
     *
     * @param format the format id
     * @return the layout, or {@code null} for the formats this session does not convert
     */
    static RgbLayout layout(int format) {
        return switch (format) {
            case 7, 11 -> new RgbLayout(0, 1, 2);
            case 8, 12 -> RgbLayout.BGRX;
            case 9, 13 -> new RgbLayout(1, 2, 3);
            case 10, 14 -> new RgbLayout(3, 2, 1);
            default -> null;
        };
    }

    /**
     * Receives a stream state change on the loop's thread.
     */
    private void onState(MemorySegment data, int old, int current, MemorySegment message) {
        try {
            state = current;
            if (current == PipeWire.STATE_ERROR) {
                error = message.equals(MemorySegment.NULL) ? "unknown error" : message.reinterpret(4096).getString(0);
                var target = exchange;
                if (target != null) {
                    target.fail(new MediaException.Io("the screen cast failed", new IOException(error)));
                }
            } else if (current == PipeWire.STATE_STREAMING) {
                streamed = true;
            } else if (current == PipeWire.STATE_UNCONNECTED && streamed) {
                var target = exchange;
                if (target != null) {
                    target.finish();
                }
            }
            PipeWire.signal(loop);
        } catch (Throwable ignored) {
            // a failure must not unwind into the native loop
        }
    }

    /**
     * Receives a negotiated parameter on the loop's thread.
     */
    private void onParam(MemorySegment data, int id, MemorySegment param) {
        try {
            if (id == SpaPod.PARAM_FORMAT && !param.equals(MemorySegment.NULL)) {
                var parsed = SpaPod.readVideoFormat(param.reinterpret(64 * 1024 + 8));
                if (parsed != null) {
                    format = parsed;
                }
            }
            PipeWire.signal(loop);
        } catch (Throwable ignored) {
            // a malformed parameter leaves the format unknown; the open times out
        }
    }

    /**
     * Converts the newest filled buffer on the loop's thread and returns every buffer.
     */
    private void onProcess(MemorySegment data) {
        try {
            MemorySegment newest = MemorySegment.NULL;
            while (true) {
                var buffer = PipeWire.dequeue(stream);
                if (buffer.equals(MemorySegment.NULL)) {
                    break;
                }
                if (!newest.equals(MemorySegment.NULL)) {
                    PipeWire.queue(stream, newest);
                }
                newest = buffer;
            }
            if (newest.equals(MemorySegment.NULL)) {
                return;
            }
            try {
                convert(newest);
            } finally {
                PipeWire.queue(stream, newest);
            }
        } catch (Throwable failure) {
            var target = exchange;
            if (target != null) {
                target.fail(failure instanceof MediaException media ? media
                        : new MediaException.Io("the screen cast failed", new IOException("the frame conversion failed", failure)));
            }
        }
    }

    /**
     * Converts one buffer into the exchange.
     *
     * @param buffer the {@code pw_buffer}
     */
    private void convert(MemorySegment buffer) {
        var target = exchange;
        var current = format;
        if (target == null || current == null) {
            return;
        }
        var spa = buffer.reinterpret(8).get(ADDRESS, 0).reinterpret(PipeWire.SPA_BUFFER.byteSize());
        if (spa.get(JAVA_INT, PipeWire.BUFFER_N_DATAS) < 1) {
            return;
        }
        var datum = spa.get(ADDRESS, PipeWire.BUFFER_DATAS).reinterpret(PipeWire.SPA_DATA.byteSize());
        var type = datum.get(JAVA_INT, PipeWire.DATA_TYPE);
        var base = datum.get(ADDRESS, PipeWire.DATA_DATA);
        if (base.equals(MemorySegment.NULL) || (type != PipeWire.DATA_MEM_PTR && type != PipeWire.DATA_MEM_FD)) {
            return;
        }
        var chunk = datum.get(ADDRESS, PipeWire.DATA_CHUNK).reinterpret(PipeWire.SPA_CHUNK.byteSize());
        if ((chunk.get(JAVA_INT, 12) & PipeWire.CHUNK_CORRUPTED) != 0) {
            return;
        }
        var maxSize = Integer.toUnsignedLong(datum.get(JAVA_INT, PipeWire.DATA_MAXSIZE));
        var offset = Integer.toUnsignedLong(chunk.get(JAVA_INT, 0)) % Math.max(1, maxSize);
        var size = Integer.toUnsignedLong(chunk.get(JAVA_INT, 4));
        var width = current.width();
        var height = current.height();
        var stride = chunk.get(JAVA_INT, 8);
        if (stride <= 0) {
            stride = 4 * width;
        }
        var needed = (long) stride * (height - 1) + 4L * width;
        if (stride < 4L * width || size < needed || offset + needed > maxSize) {
            return;
        }
        var bytes = (int) needed;
        if (pixels.length < bytes) {
            LimitChecks.checkAllocation(MediaLimits.DEFAULT, bytes);
            pixels = new byte[bytes];
        }
        MemorySegment.copy(base.reinterpret(maxSize), JAVA_BYTE, offset, pixels, 0, bytes);
        var layout = layout(current.format());
        var picture = target.acquire();
        var region = area;
        if (region == null || width < region.x() + region.width() || height < region.y() + region.height()) {
            region = CaptureArea.of(options, width, height, "the shared screen");
        }
        var out = picture;
        if (region.width() != picture.width() || region.height() != picture.height()) {
            scratch = Pictures.reuse(scratch, region.width(), region.height(), picture.color());
            out = scratch;
        }
        var coefficients = YuvCoefficients.BT709_LIMITED;
        var chromaWidth = region.width() / 2;
        for (var row = 0; row < region.height(); row += 2) {
            var upper = (region.y() + row) * stride + 4 * region.x();
            var lower = upper + stride;
            kernel.rgbToLuma(pixels, upper, region.width(), layout, coefficients, out.y(), row * out.yStride());
            kernel.rgbToLuma(pixels, lower, region.width(), layout, coefficients, out.y(), (row + 1) * out.yStride());
            kernel.rgbToChroma(pixels, upper, lower, chromaWidth, layout, coefficients, out.u(), out.v(),
                    (row / 2) * out.uvStride());
        }
        if (out != picture) {
            Pictures.fit(out, picture);
        }
        target.publish(picture, target.micros(System.nanoTime()));
    }

    @Override
    public CaptureDevice device() {
        return device;
    }

    @Override
    public int width() {
        return exchange.width();
    }

    @Override
    public int height() {
        return exchange.height();
    }

    /**
     * Returns whether the pointer is drawn into the frames.
     *
     * @return whether the portal embeds it
     */
    boolean cursorEmbedded() {
        return cast.cursorEmbedded();
    }

    /**
     * Returns the shared stream the portal reported.
     *
     * @return the stream
     */
    ScreenCastPortal.Stream shared() {
        return cast.stream();
    }

    @Override
    public Frame read() throws InterruptedException {
        var current = exchange;
        return current == null ? null : current.take();
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        var current = exchange;
        if (current != null) {
            current.close();
        }
        PipeWire.loopStop(loop);
        if (!stream.equals(MemorySegment.NULL)) {
            PipeWire.streamDestroy(stream);
        }
        if (!context.equals(MemorySegment.NULL)) {
            PipeWire.contextDestroy(context);
        }
        PipeWire.loopDestroy(loop);
        arena.close();
        cast.close();
    }
}
