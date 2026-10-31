package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.Native;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Binds the parts of the PulseAudio client library this backend calls: the threaded main loop and
 * context of {@code libpulse.so.0}, which list the server's sinks and sources and report their
 * changes, and the blocking streams of {@code libpulse-simple.so.0}.
 *
 * <p>Both libraries speak the PulseAudio protocol, which PulseAudio itself and PipeWire's
 * {@code pipewire-pulse} server answer. The structure layouts are those of a 64-bit Linux (LP64)
 * process; only the leading fields of {@code pa_sink_info}, {@code pa_source_info} and
 * {@code pa_server_info}, which every release since 0.9.15 shares, are read. The libraries are
 * looked up by name on first use; without them {@link #available()} is {@code false}.
 */
@SuppressWarnings("restricted")
final class Pulse {
    /**
     * The {@code PA_CONTEXT_NOAUTOSPAWN} flag: never start a server.
     */
    static final int CONTEXT_NOAUTOSPAWN = 0x1;

    /**
     * The {@code PA_CONTEXT_READY} state.
     */
    static final int CONTEXT_READY = 4;

    /**
     * The {@code PA_CONTEXT_FAILED} state.
     */
    static final int CONTEXT_FAILED = 5;

    /**
     * The {@code PA_CONTEXT_TERMINATED} state.
     */
    static final int CONTEXT_TERMINATED = 6;

    /**
     * The {@code PA_OPERATION_RUNNING} state.
     */
    static final int OPERATION_RUNNING = 0;

    /**
     * The {@code PA_SUBSCRIPTION_MASK_SINK}, {@code _SOURCE}, {@code _SERVER} and {@code _CARD} bits.
     */
    static final int SUBSCRIPTION_MASK = 0x0001 | 0x0002 | 0x0080 | 0x0200;

    /**
     * The {@code PA_STREAM_PLAYBACK} direction of a simple stream.
     */
    static final int STREAM_PLAYBACK = 1;

    /**
     * The {@code PA_STREAM_RECORD} direction of a simple stream.
     */
    static final int STREAM_RECORD = 2;

    /**
     * The {@code PA_SAMPLE_FLOAT32LE} sample format.
     */
    static final int SAMPLE_FLOAT32LE = 5;

    /**
     * The name of the monitor of the server's default sink.
     */
    static final String DEFAULT_MONITOR = "@DEFAULT_MONITOR@";

    /**
     * The layout of {@code pa_sample_spec}.
     */
    static final StructLayout SAMPLE_SPEC = MemoryLayout.structLayout(
            JAVA_INT.withName("format"),
            JAVA_INT.withName("rate"),
            JAVA_BYTE.withName("channels"),
            MemoryLayout.paddingLayout(3)).withName("pa_sample_spec");

    /**
     * The layout of {@code pa_buffer_attr}.
     */
    static final StructLayout BUFFER_ATTR = MemoryLayout.structLayout(
            JAVA_INT.withName("maxlength"),
            JAVA_INT.withName("tlength"),
            JAVA_INT.withName("prebuf"),
            JAVA_INT.withName("minreq"),
            JAVA_INT.withName("fragsize")).withName("pa_buffer_attr");

    /**
     * The layout of {@code pa_channel_map}.
     */
    static final StructLayout CHANNEL_MAP = MemoryLayout.structLayout(
            JAVA_BYTE.withName("channels"),
            MemoryLayout.paddingLayout(3),
            MemoryLayout.sequenceLayout(32, JAVA_INT).withName("map")).withName("pa_channel_map");

    /**
     * The layout of {@code pa_cvolume}.
     */
    static final StructLayout CVOLUME = MemoryLayout.structLayout(
            JAVA_BYTE.withName("channels"),
            MemoryLayout.paddingLayout(3),
            MemoryLayout.sequenceLayout(32, JAVA_INT).withName("values")).withName("pa_cvolume");

    /**
     * The layout of the leading fields of {@code pa_sink_info} and {@code pa_source_info}, which
     * differ only in the names of {@code monitor_source} ({@code monitor_of_sink}) and
     * {@code monitor_source_name} ({@code monitor_of_sink_name}).
     */
    static final StructLayout DEVICE_INFO = MemoryLayout.structLayout(
            ADDRESS.withName("name"),
            JAVA_INT.withName("index"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("description"),
            SAMPLE_SPEC.withName("sample_spec"),
            CHANNEL_MAP.withName("channel_map"),
            JAVA_INT.withName("owner_module"),
            CVOLUME.withName("volume"),
            JAVA_INT.withName("mute"),
            JAVA_INT.withName("monitor"),
            ADDRESS.withName("monitor_name"),
            JAVA_LONG.withName("latency"),
            ADDRESS.withName("driver"),
            JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("proplist")).withName("pa_sink_info");

    /**
     * The offset of {@code pa_sink_info.name}.
     */
    static final long INFO_NAME = DEVICE_INFO.byteOffset(groupElement("name"));

    /**
     * The offset of {@code pa_sink_info.description}.
     */
    static final long INFO_DESCRIPTION = DEVICE_INFO.byteOffset(groupElement("description"));

    /**
     * The offset of {@code pa_sink_info.sample_spec.rate}.
     */
    static final long INFO_RATE = DEVICE_INFO.byteOffset(groupElement("sample_spec"), groupElement("rate"));

    /**
     * The offset of {@code pa_sink_info.sample_spec.channels}.
     */
    static final long INFO_CHANNELS = DEVICE_INFO.byteOffset(groupElement("sample_spec"), groupElement("channels"));

    /**
     * The offset of {@code pa_sink_info.monitor_source_name} and {@code pa_source_info.monitor_of_sink_name}.
     */
    static final long INFO_MONITOR_NAME = DEVICE_INFO.byteOffset(groupElement("monitor_name"));

    /**
     * The offset of {@code pa_sink_info.proplist}.
     */
    static final long INFO_PROPLIST = DEVICE_INFO.byteOffset(groupElement("proplist"));

    /**
     * The layout of the leading fields of {@code pa_server_info}.
     */
    static final StructLayout SERVER_INFO = MemoryLayout.structLayout(
            ADDRESS.withName("user_name"),
            ADDRESS.withName("host_name"),
            ADDRESS.withName("server_version"),
            ADDRESS.withName("server_name"),
            SAMPLE_SPEC.withName("sample_spec"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("default_sink_name"),
            ADDRESS.withName("default_source_name")).withName("pa_server_info");

    /**
     * The offset of {@code pa_server_info.server_name}.
     */
    static final long SERVER_NAME = SERVER_INFO.byteOffset(groupElement("server_name"));

    /**
     * The offset of {@code pa_server_info.default_sink_name}.
     */
    static final long SERVER_DEFAULT_SINK = SERVER_INFO.byteOffset(groupElement("default_sink_name"));

    /**
     * The offset of {@code pa_server_info.default_source_name}.
     */
    static final long SERVER_DEFAULT_SOURCE = SERVER_INFO.byteOffset(groupElement("default_source_name"));

    /**
     * The descriptor of {@code pa_context_notify_cb_t}: {@code void (pa_context *, void *)}.
     */
    static final FunctionDescriptor NOTIFY_CALLBACK = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

    /**
     * The descriptor of {@code pa_sink_info_cb_t} and {@code pa_source_info_cb_t}:
     * {@code void (pa_context *, const pa_sink_info *, int eol, void *)}.
     */
    static final FunctionDescriptor INFO_CALLBACK = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS);

    /**
     * The descriptor of {@code pa_server_info_cb_t}: {@code void (pa_context *, const pa_server_info *, void *)}.
     */
    static final FunctionDescriptor SERVER_CALLBACK = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);

    /**
     * The descriptor of {@code pa_context_subscribe_cb_t}:
     * {@code void (pa_context *, pa_subscription_event_type_t, uint32_t idx, void *)}.
     */
    static final FunctionDescriptor SUBSCRIBE_CALLBACK = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    /**
     * The descriptor of {@code pa_context_success_cb_t}: {@code void (pa_context *, int success, void *)}.
     */
    static final FunctionDescriptor SUCCESS_CALLBACK = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);

    private static final Functions FUNCTIONS = Functions.load();

    private Pulse() {
        throw new AssertionError();
    }

    /**
     * The bound functions, or {@code null} when a library is missing.
     */
    private record Functions(MethodHandle mainloopNew, MethodHandle mainloopStart, MethodHandle mainloopStop,
                             MethodHandle mainloopFree, MethodHandle mainloopLock, MethodHandle mainloopUnlock,
                             MethodHandle mainloopWait, MethodHandle mainloopSignal, MethodHandle mainloopApi,
                             MethodHandle contextNew, MethodHandle contextStateCallback, MethodHandle contextConnect,
                             MethodHandle contextState, MethodHandle contextDisconnect, MethodHandle contextUnref,
                             MethodHandle sinkList, MethodHandle sourceList, MethodHandle serverInfo,
                             MethodHandle subscribe, MethodHandle subscribeCallback, MethodHandle operationState,
                             MethodHandle operationUnref, MethodHandle proplistGets, MethodHandle strerror,
                             MethodHandle simpleNew, MethodHandle simpleRead, MethodHandle simpleWrite,
                             MethodHandle simpleLatency, MethodHandle simpleFree) {
        static Functions load() {
            var pulse = Native.library("libpulse.so.0").orElse(null);
            var simple = Native.library("libpulse-simple.so.0").orElse(null);
            if (pulse == null || simple == null) {
                return null;
            }
            try {
                return new Functions(
                        Native.function(pulse, "pa_threaded_mainloop_new", FunctionDescriptor.of(ADDRESS)),
                        Native.function(pulse, "pa_threaded_mainloop_start", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(pulse, "pa_threaded_mainloop_stop", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(pulse, "pa_threaded_mainloop_free", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(pulse, "pa_threaded_mainloop_lock", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(pulse, "pa_threaded_mainloop_unlock", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(pulse, "pa_threaded_mainloop_wait", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(pulse, "pa_threaded_mainloop_signal", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)),
                        Native.function(pulse, "pa_threaded_mainloop_get_api", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                        Native.function(pulse, "pa_context_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(pulse, "pa_context_set_state_callback", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(pulse, "pa_context_connect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)),
                        Native.function(pulse, "pa_context_get_state", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(pulse, "pa_context_disconnect", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(pulse, "pa_context_unref", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(pulse, "pa_context_get_sink_info_list", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(pulse, "pa_context_get_source_info_list", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(pulse, "pa_context_get_server_info", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(pulse, "pa_context_subscribe", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(pulse, "pa_context_set_subscribe_callback", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(pulse, "pa_operation_get_state", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(pulse, "pa_operation_unref", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(pulse, "pa_proplist_gets", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(pulse, "pa_strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT)),
                        Native.function(simple, "pa_simple_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS,
                                ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(simple, "pa_simple_read", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)),
                        Native.function(simple, "pa_simple_write", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)),
                        Native.function(simple, "pa_simple_get_latency", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS)),
                        Native.function(simple, "pa_simple_free", FunctionDescriptor.ofVoid(ADDRESS)));
            } catch (RuntimeException | UnsatisfiedLinkError exception) {
                return null;
            }
        }
    }

    /**
     * Returns whether both libraries were found.
     *
     * @return whether the PulseAudio protocol can be spoken
     */
    static boolean available() {
        return FUNCTIONS != null;
    }

    /**
     * Returns the bound functions.
     *
     * @throws MediaException.Unsupported if a library is missing
     */
    private static Functions functions() {
        if (FUNCTIONS == null) {
            throw new MediaException.Unsupported("pulseaudio", "libpulse.so.0 or libpulse-simple.so.0 is not installed");
        }
        return FUNCTIONS;
    }

    /**
     * Calls {@code pa_threaded_mainloop_new}.
     *
     * @return the main loop, or {@link MemorySegment#NULL}
     */
    static MemorySegment mainloopNew() {
        try {
            return (MemorySegment) functions().mainloopNew.invokeExact();
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_threaded_mainloop_start}.
     *
     * @return {@code 0}, or a negative error
     */
    static int mainloopStart(MemorySegment mainloop) {
        try {
            return (int) functions().mainloopStart.invokeExact(mainloop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_threaded_mainloop_stop}; never call it from the loop's thread or with the lock held.
     */
    static void mainloopStop(MemorySegment mainloop) {
        try {
            functions().mainloopStop.invokeExact(mainloop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_threaded_mainloop_free}.
     */
    static void mainloopFree(MemorySegment mainloop) {
        try {
            functions().mainloopFree.invokeExact(mainloop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_threaded_mainloop_lock}.
     */
    static void lock(MemorySegment mainloop) {
        try {
            functions().mainloopLock.invokeExact(mainloop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_threaded_mainloop_unlock}.
     */
    static void unlock(MemorySegment mainloop) {
        try {
            functions().mainloopUnlock.invokeExact(mainloop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_threaded_mainloop_wait}, which releases the lock until a callback signals.
     */
    static void await(MemorySegment mainloop) {
        try {
            functions().mainloopWait.invokeExact(mainloop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_threaded_mainloop_signal} without waiting for an accept.
     */
    static void signal(MemorySegment mainloop) {
        try {
            functions().mainloopSignal.invokeExact(mainloop, 0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_threaded_mainloop_get_api}.
     *
     * @return the {@code pa_mainloop_api}
     */
    static MemorySegment api(MemorySegment mainloop) {
        try {
            return (MemorySegment) functions().mainloopApi.invokeExact(mainloop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_context_new}.
     *
     * @param name the application name
     * @return the context, or {@link MemorySegment#NULL}
     */
    static MemorySegment contextNew(MemorySegment api, String name) {
        try (var arena = Arena.ofConfined()) {
            return (MemorySegment) functions().contextNew.invokeExact(api, arena.allocateFrom(name));
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_context_set_state_callback}.
     *
     * @param callback the {@link #NOTIFY_CALLBACK} stub
     */
    static void setStateCallback(MemorySegment context, MemorySegment callback) {
        try {
            functions().contextStateCallback.invokeExact(context, callback, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_context_connect} to the default server without spawning one.
     *
     * @return {@code 0}, or a negative error
     */
    static int connect(MemorySegment context) {
        try {
            return (int) functions().contextConnect.invokeExact(context, MemorySegment.NULL, CONTEXT_NOAUTOSPAWN, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_context_get_state}.
     *
     * @return the state
     */
    static int state(MemorySegment context) {
        try {
            return (int) functions().contextState.invokeExact(context);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_context_disconnect} and {@code pa_context_unref}.
     */
    static void contextFree(MemorySegment context) {
        try {
            functions().contextStateCallback.invokeExact(context, MemorySegment.NULL, MemorySegment.NULL);
            functions().contextDisconnect.invokeExact(context);
            functions().contextUnref.invokeExact(context);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_context_get_sink_info_list}.
     *
     * @param callback the {@link #INFO_CALLBACK} stub
     * @return the operation, or {@link MemorySegment#NULL}
     */
    static MemorySegment sinks(MemorySegment context, MemorySegment callback) {
        try {
            return (MemorySegment) functions().sinkList.invokeExact(context, callback, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_context_get_source_info_list}.
     *
     * @param callback the {@link #INFO_CALLBACK} stub
     * @return the operation, or {@link MemorySegment#NULL}
     */
    static MemorySegment sources(MemorySegment context, MemorySegment callback) {
        try {
            return (MemorySegment) functions().sourceList.invokeExact(context, callback, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_context_get_server_info}.
     *
     * @param callback the {@link #SERVER_CALLBACK} stub
     * @return the operation, or {@link MemorySegment#NULL}
     */
    static MemorySegment serverInfo(MemorySegment context, MemorySegment callback) {
        try {
            return (MemorySegment) functions().serverInfo.invokeExact(context, callback, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_context_set_subscribe_callback} and {@code pa_context_subscribe} for the
     * sinks, sources, server and cards.
     *
     * @param callback the {@link #SUBSCRIBE_CALLBACK} stub
     * @param done the {@link #SUCCESS_CALLBACK} stub called when the server accepted the subscription
     * @return the operation, or {@link MemorySegment#NULL}
     */
    static MemorySegment subscribe(MemorySegment context, MemorySegment callback, MemorySegment done) {
        try {
            functions().subscribeCallback.invokeExact(context, callback, MemorySegment.NULL);
            return (MemorySegment) functions().subscribe.invokeExact(context, SUBSCRIPTION_MASK, done, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_operation_get_state}.
     *
     * @return the state
     */
    static int operationState(MemorySegment operation) {
        try {
            return (int) functions().operationState.invokeExact(operation);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_operation_unref}.
     */
    static void operationUnref(MemorySegment operation) {
        try {
            functions().operationUnref.invokeExact(operation);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_proplist_gets}.
     *
     * @param proplist the property list, possibly {@link MemorySegment#NULL}
     * @param key the property name, such as {@code alsa.card}
     * @return the value, or {@code null}
     */
    static String property(MemorySegment proplist, String key) {
        if (proplist.equals(MemorySegment.NULL)) {
            return null;
        }
        try (var arena = Arena.ofConfined()) {
            return string((MemorySegment) functions().proplistGets.invokeExact(proplist, arena.allocateFrom(key)));
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Opens a blocking stream with {@code pa_simple_new}, in 32-bit float samples.
     *
     * @param direction {@link #STREAM_PLAYBACK} or {@link #STREAM_RECORD}
     * @param device the sink or source name, or {@code null} for the default
     * @param streamName the name the server shows for the stream
     * @param rate the sample rate
     * @param channels the channel count
     * @param bufferBytes the target length of a playback buffer or the fragment size of a recording
     * @return the stream
     * @throws MediaException.Io if the server refuses the stream
     */
    static MemorySegment simpleNew(int direction, String device, String streamName, int rate, int channels, int bufferBytes) {
        try (var arena = Arena.ofConfined()) {
            var spec = arena.allocate(SAMPLE_SPEC);
            spec.set(JAVA_INT, SAMPLE_SPEC.byteOffset(groupElement("format")), SAMPLE_FLOAT32LE);
            spec.set(JAVA_INT, SAMPLE_SPEC.byteOffset(groupElement("rate")), rate);
            spec.set(JAVA_BYTE, SAMPLE_SPEC.byteOffset(groupElement("channels")), (byte) channels);
            var attributes = arena.allocate(BUFFER_ATTR);
            attributes.fill((byte) -1);
            if (direction == STREAM_PLAYBACK) {
                attributes.set(JAVA_INT, BUFFER_ATTR.byteOffset(groupElement("tlength")), bufferBytes);
            } else {
                attributes.set(JAVA_INT, BUFFER_ATTR.byteOffset(groupElement("fragsize")), bufferBytes);
            }
            var error = arena.allocate(JAVA_INT);
            MemorySegment name = device == null ? MemorySegment.NULL : arena.allocateFrom(device);
            var stream = (MemorySegment) functions().simpleNew.invokeExact(MemorySegment.NULL, arena.allocateFrom("Smalt"),
                    direction, name, arena.allocateFrom(streamName), spec, MemorySegment.NULL, attributes, error);
            if (stream.equals(MemorySegment.NULL)) {
                throw failure("cannot open the PulseAudio " + (direction == STREAM_PLAYBACK ? "sink " : "source ")
                        + (device == null ? "(default)" : device), error.get(JAVA_INT, 0));
            }
            return stream;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_simple_read}, which blocks until the buffer is full.
     *
     * @param error a 4-byte slot receiving the error
     * @return {@code 0}, or a negative value with the error in {@code error}
     */
    static int read(MemorySegment stream, MemorySegment buffer, long bytes, MemorySegment error) {
        try {
            return (int) functions().simpleRead.invokeExact(stream, buffer, bytes, error);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_simple_write}, which blocks until the server took the samples.
     *
     * @param error a 4-byte slot receiving the error
     * @return {@code 0}, or a negative value with the error in {@code error}
     */
    static int write(MemorySegment stream, MemorySegment buffer, long bytes, MemorySegment error) {
        try {
            return (int) functions().simpleWrite.invokeExact(stream, buffer, bytes, error);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_simple_get_latency}.
     *
     * @param error a 4-byte slot receiving the error
     * @return the latency in microseconds, or {@code -1}
     */
    static long latency(MemorySegment stream, MemorySegment error) {
        try {
            return (long) functions().simpleLatency.invokeExact(stream, error);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pa_simple_free}.
     */
    static void simpleFree(MemorySegment stream) {
        try {
            functions().simpleFree.invokeExact(stream);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Describes a PulseAudio error code.
     *
     * @param error the error code
     * @return the server's message
     */
    static String describe(int error) {
        try {
            var message = string((MemorySegment) functions().strerror.invokeExact(error));
            return message == null ? "PulseAudio error " + error : message + " (PulseAudio error " + error + ")";
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Builds the failure of a request.
     *
     * @param what what failed
     * @param error the error code
     * @return the failure
     */
    static MediaException.Io failure(String what, int error) {
        var cause = new IOException(what + ": " + describe(error));
        return new MediaException.Io(cause.getMessage(), cause);
    }

    /**
     * Reads a NUL-terminated UTF-8 string.
     *
     * @param pointer the string, possibly {@link MemorySegment#NULL}
     * @return the string, or {@code null}
     */
    static String string(MemorySegment pointer) {
        return pointer.equals(MemorySegment.NULL) ? null : pointer.reinterpret(Integer.MAX_VALUE).getString(0);
    }
}
