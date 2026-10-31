package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Binds the parts of {@code libpipewire-0.3.so.0} a capture stream calls: the thread loop, the
 * context connected to a portal's descriptor, and the stream with its events and buffers.
 *
 * <p>The structure layouts are those of a 64-bit Linux (LP64) process: {@code spa_hook} and the
 * stream events, which the library reads, and the leading fields of {@code pw_buffer},
 * {@code spa_buffer}, {@code spa_data} and {@code spa_chunk}, which it fills; every PipeWire 0.3 and 1.x
 * release shares them. The library is looked up by name on first use; without it
 * {@link #available()} is {@code false}.
 */
@SuppressWarnings("restricted")
final class PipeWire {
    /**
     * The {@code PW_DIRECTION_INPUT} direction of a consumer.
     */
    static final int DIRECTION_INPUT = 0;

    /**
     * The {@code PW_STREAM_FLAG_AUTOCONNECT} and {@code PW_STREAM_FLAG_MAP_BUFFERS} flags.
     */
    static final int FLAGS_AUTOCONNECT_MAP = 1 | 4;

    /**
     * The {@code PW_STREAM_STATE_ERROR} state.
     */
    static final int STATE_ERROR = -1;

    /**
     * The {@code PW_STREAM_STATE_UNCONNECTED} state.
     */
    static final int STATE_UNCONNECTED = 0;

    /**
     * The {@code PW_STREAM_STATE_STREAMING} state.
     */
    static final int STATE_STREAMING = 3;

    /**
     * The {@code SPA_DATA_MemPtr}, {@code SPA_DATA_MemFd} and {@code SPA_DATA_DmaBuf} data types.
     */
    static final int DATA_MEM_PTR = 1, DATA_MEM_FD = 2, DATA_DMA_BUF = 3;

    /**
     * The {@code SPA_CHUNK_FLAG_CORRUPTED} flag.
     */
    static final int CHUNK_CORRUPTED = 1;

    /**
     * The layout of {@code struct spa_hook}: a list link, the callbacks, {@code removed} and
     * {@code priv}; the library owns its content.
     */
    static final StructLayout HOOK = MemoryLayout.structLayout(
            ADDRESS.withName("next"),
            ADDRESS.withName("prev"),
            ADDRESS.withName("funcs"),
            ADDRESS.withName("data"),
            ADDRESS.withName("removed"),
            ADDRESS.withName("priv")).withName("spa_hook");

    /**
     * The layout of {@code struct pw_stream_events} up to {@code trigger_done}.
     */
    static final StructLayout STREAM_EVENTS = MemoryLayout.structLayout(
            JAVA_INT.withName("version"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("destroy"),
            ADDRESS.withName("state_changed"),
            ADDRESS.withName("control_info"),
            ADDRESS.withName("io_changed"),
            ADDRESS.withName("param_changed"),
            ADDRESS.withName("add_buffer"),
            ADDRESS.withName("remove_buffer"),
            ADDRESS.withName("process"),
            ADDRESS.withName("drained"),
            ADDRESS.withName("command"),
            ADDRESS.withName("trigger_done")).withName("pw_stream_events");

    /**
     * The offset of {@code pw_stream_events.state_changed}.
     */
    static final long EVENTS_STATE_CHANGED = STREAM_EVENTS.byteOffset(groupElement("state_changed"));

    /**
     * The offset of {@code pw_stream_events.param_changed}.
     */
    static final long EVENTS_PARAM_CHANGED = STREAM_EVENTS.byteOffset(groupElement("param_changed"));

    /**
     * The offset of {@code pw_stream_events.process}.
     */
    static final long EVENTS_PROCESS = STREAM_EVENTS.byteOffset(groupElement("process"));

    /**
     * The layout of the leading fields of {@code struct spa_buffer}.
     */
    static final StructLayout SPA_BUFFER = MemoryLayout.structLayout(
            JAVA_INT.withName("n_metas"),
            JAVA_INT.withName("n_datas"),
            ADDRESS.withName("metas"),
            ADDRESS.withName("datas")).withName("spa_buffer");

    /**
     * The offset of {@code spa_buffer.n_datas}.
     */
    static final long BUFFER_N_DATAS = SPA_BUFFER.byteOffset(groupElement("n_datas"));

    /**
     * The offset of {@code spa_buffer.datas}.
     */
    static final long BUFFER_DATAS = SPA_BUFFER.byteOffset(groupElement("datas"));

    /**
     * The layout of {@code struct spa_data}.
     */
    static final StructLayout SPA_DATA = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            JAVA_INT.withName("flags"),
            JAVA_LONG.withName("fd"),
            JAVA_INT.withName("mapoffset"),
            JAVA_INT.withName("maxsize"),
            ADDRESS.withName("data"),
            ADDRESS.withName("chunk")).withName("spa_data");

    /**
     * The offset of {@code spa_data.type}.
     */
    static final long DATA_TYPE = SPA_DATA.byteOffset(groupElement("type"));

    /**
     * The offset of {@code spa_data.maxsize}.
     */
    static final long DATA_MAXSIZE = SPA_DATA.byteOffset(groupElement("maxsize"));

    /**
     * The offset of {@code spa_data.data}.
     */
    static final long DATA_DATA = SPA_DATA.byteOffset(groupElement("data"));

    /**
     * The offset of {@code spa_data.chunk}.
     */
    static final long DATA_CHUNK = SPA_DATA.byteOffset(groupElement("chunk"));

    /**
     * The layout of {@code struct spa_chunk}.
     */
    static final StructLayout SPA_CHUNK = MemoryLayout.structLayout(
            JAVA_INT.withName("offset"),
            JAVA_INT.withName("size"),
            JAVA_INT.withName("stride"),
            JAVA_INT.withName("flags")).withName("spa_chunk");

    /**
     * The descriptor of the {@code state_changed} event:
     * {@code void (void *data, enum pw_stream_state old, enum pw_stream_state state, const char *error)}.
     */
    static final FunctionDescriptor STATE_CHANGED = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    /**
     * The descriptor of the {@code param_changed} event:
     * {@code void (void *data, uint32_t id, const struct spa_pod *param)}.
     */
    static final FunctionDescriptor PARAM_CHANGED = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);

    /**
     * The descriptor of the {@code process} event: {@code void (void *data)}.
     */
    static final FunctionDescriptor PROCESS = FunctionDescriptor.ofVoid(ADDRESS);

    private static final Functions FUNCTIONS = Functions.load();

    private PipeWire() {
        throw new AssertionError();
    }

    /**
     * The bound functions, or {@code null} when the library is missing.
     */
    private record Functions(MethodHandle init, MethodHandle loopNew, MethodHandle loopStart, MethodHandle loopStop,
                             MethodHandle loopDestroy, MethodHandle loopLock, MethodHandle loopUnlock,
                             MethodHandle loopTimedWait, MethodHandle loopSignal, MethodHandle loopGetLoop,
                             MethodHandle contextNew, MethodHandle contextDestroy, MethodHandle connectFd,
                             MethodHandle propertiesNewString, MethodHandle streamNew, MethodHandle streamAddListener,
                             MethodHandle streamConnect, MethodHandle streamDisconnect, MethodHandle streamDestroy,
                             MethodHandle dequeue, MethodHandle queue) {
        static Functions load() {
            var library = Native.library("libpipewire-0.3.so.0").orElse(null);
            if (library == null) {
                return null;
            }
            try {
                var functions = new Functions(
                        Native.function(library, "pw_init", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                        Native.function(library, "pw_thread_loop_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(library, "pw_thread_loop_start", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(library, "pw_thread_loop_stop", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(library, "pw_thread_loop_destroy", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(library, "pw_thread_loop_lock", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(library, "pw_thread_loop_unlock", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(library, "pw_thread_loop_timed_wait", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
                        Native.function(library, "pw_thread_loop_signal", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN)),
                        Native.function(library, "pw_thread_loop_get_loop", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                        Native.function(library, "pw_context_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG)),
                        Native.function(library, "pw_context_destroy", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(library, "pw_context_connect_fd", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG)),
                        Native.function(library, "pw_properties_new_string", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                        Native.function(library, "pw_stream_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(library, "pw_stream_add_listener", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(library, "pw_stream_connect", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
                                JAVA_INT, ADDRESS, JAVA_INT)),
                        Native.function(library, "pw_stream_disconnect", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(library, "pw_stream_destroy", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(library, "pw_stream_dequeue_buffer", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                        Native.function(library, "pw_stream_queue_buffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)));
                functions.init.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
                return functions;
            } catch (Throwable failure) {
                return null;
            }
        }
    }

    /**
     * Returns whether the library was found.
     *
     * @return whether PipeWire streams can be opened
     */
    static boolean available() {
        return FUNCTIONS != null;
    }

    /**
     * Returns the bound functions.
     *
     * @throws MediaException.Unsupported if the library is missing
     */
    private static Functions functions() {
        if (FUNCTIONS == null) {
            throw new MediaException.Unsupported("pipewire", "libpipewire-0.3.so.0 is not installed");
        }
        return FUNCTIONS;
    }

    /**
     * Calls {@code pw_thread_loop_new}.
     *
     * @param name the loop thread's name
     * @return the loop, or {@link MemorySegment#NULL}
     */
    static MemorySegment loopNew(String name) {
        try (var arena = Arena.ofConfined()) {
            return (MemorySegment) functions().loopNew.invokeExact(arena.allocateFrom(name), MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_thread_loop_start}.
     *
     * @return {@code 0}, or a negative error
     */
    static int loopStart(MemorySegment loop) {
        try {
            return (int) functions().loopStart.invokeExact(loop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_thread_loop_stop}, which waits for the loop thread; never call it with the lock held.
     */
    static void loopStop(MemorySegment loop) {
        try {
            functions().loopStop.invokeExact(loop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_thread_loop_destroy}.
     */
    static void loopDestroy(MemorySegment loop) {
        try {
            functions().loopDestroy.invokeExact(loop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_thread_loop_lock}.
     */
    static void lock(MemorySegment loop) {
        try {
            functions().loopLock.invokeExact(loop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_thread_loop_unlock}.
     */
    static void unlock(MemorySegment loop) {
        try {
            functions().loopUnlock.invokeExact(loop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_thread_loop_timed_wait}, which releases the lock until a signal or the timeout.
     *
     * @param seconds the longest wait
     * @return {@code 0}, or a negative error such as {@code -ETIMEDOUT}
     */
    static int timedWait(MemorySegment loop, int seconds) {
        try {
            return (int) functions().loopTimedWait.invokeExact(loop, seconds);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_thread_loop_signal} without waiting for an accept.
     */
    static void signal(MemorySegment loop) {
        try {
            functions().loopSignal.invokeExact(loop, false);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_thread_loop_get_loop}.
     *
     * @return the {@code pw_loop}
     */
    static MemorySegment loopOf(MemorySegment loop) {
        try {
            return (MemorySegment) functions().loopGetLoop.invokeExact(loop);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_context_new} without properties.
     *
     * @return the context, or {@link MemorySegment#NULL}
     */
    static MemorySegment contextNew(MemorySegment loop) {
        try {
            return (MemorySegment) functions().contextNew.invokeExact(loop, MemorySegment.NULL, 0L);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_context_destroy}, which also disconnects its cores.
     */
    static void contextDestroy(MemorySegment context) {
        try {
            functions().contextDestroy.invokeExact(context);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_context_connect_fd}, which takes the descriptor over.
     *
     * @return the core, or {@link MemorySegment#NULL}
     */
    static MemorySegment connect(MemorySegment context, int fd) {
        try {
            return (MemorySegment) functions().connectFd.invokeExact(context, fd, MemorySegment.NULL, 0L);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_stream_new} with properties parsed by {@code pw_properties_new_string}.
     *
     * @param properties the properties, {@code key=value} pairs separated by spaces
     * @return the stream, or {@link MemorySegment#NULL}
     */
    static MemorySegment streamNew(MemorySegment core, String name, String properties) {
        try (var arena = Arena.ofConfined()) {
            var parsed = (MemorySegment) functions().propertiesNewString.invokeExact(arena.allocateFrom(properties));
            return (MemorySegment) functions().streamNew.invokeExact(core, arena.allocateFrom(name), parsed);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_stream_add_listener}.
     *
     * @param hook a zeroed {@link #HOOK}, alive as long as the stream
     * @param events the {@link #STREAM_EVENTS}, alive as long as the stream
     */
    static void addListener(MemorySegment stream, MemorySegment hook, MemorySegment events) {
        try {
            functions().streamAddListener.invokeExact(stream, hook, events, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_stream_connect} as a consumer of a node, the buffers mapped.
     *
     * @param target the node id
     * @param params an array of one pod pointer
     * @return {@code 0}, or a negative error
     */
    static int connectStream(MemorySegment stream, long target, MemorySegment params) {
        try {
            return (int) functions().streamConnect.invokeExact(stream, DIRECTION_INPUT, (int) target, FLAGS_AUTOCONNECT_MAP,
                    params, 1);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_stream_disconnect} and {@code pw_stream_destroy}.
     */
    static void streamDestroy(MemorySegment stream) {
        try {
            var ignored = (int) functions().streamDisconnect.invokeExact(stream);
            functions().streamDestroy.invokeExact(stream);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_stream_dequeue_buffer}.
     *
     * @return the {@code pw_buffer}, or {@link MemorySegment#NULL} when none is filled
     */
    static MemorySegment dequeue(MemorySegment stream) {
        try {
            return (MemorySegment) functions().dequeue.invokeExact(stream);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code pw_stream_queue_buffer}.
     */
    static void queue(MemorySegment stream, MemorySegment buffer) {
        try {
            var ignored = (int) functions().queue.invokeExact(stream, buffer);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }
}
