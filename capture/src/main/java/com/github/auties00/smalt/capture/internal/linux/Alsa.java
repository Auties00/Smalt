package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;
import com.github.auties00.smalt.capture.internal.Native;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Calls the ALSA library ({@code libasound.so.2}) through the Foreign Function and Memory API.
 *
 * <p>The library is loaded once, on first use, by its soname; when it is missing the binding is
 * absent and the backend lists no device. The functions and constants are those of
 * {@code alsa/pcm.h}, {@code alsa/control.h} and {@code alsa/error.h} of alsa-lib 1.x on the
 * LP64 Linux ABI, where {@code snd_pcm_uframes_t} and {@code snd_pcm_sframes_t} are 64-bit. ALSA
 * functions report failures as negative {@code errno} values. A PCM handle is used by one thread
 * at a time.
 */
@SuppressWarnings("restricted")
final class Alsa {
    /**
     * {@code SND_PCM_STREAM_PLAYBACK}.
     */
    static final int STREAM_PLAYBACK = 0;

    /**
     * {@code SND_PCM_STREAM_CAPTURE}.
     */
    static final int STREAM_CAPTURE = 1;

    /**
     * {@code SND_PCM_NONBLOCK}.
     */
    static final int NONBLOCK = 0x1;

    /**
     * {@code SND_PCM_ACCESS_RW_INTERLEAVED}.
     */
    static final int ACCESS_RW_INTERLEAVED = 3;

    /**
     * {@code SND_PCM_FORMAT_S16_LE}.
     */
    static final int FORMAT_S16_LE = 2;

    /**
     * {@code SND_PCM_FORMAT_S32_LE}.
     */
    static final int FORMAT_S32_LE = 10;

    /**
     * {@code SND_PCM_FORMAT_FLOAT_LE}.
     */
    static final int FORMAT_FLOAT_LE = 14;

    /**
     * {@code SND_PCM_FORMAT_S24_3LE}: 24-bit samples packed in three bytes.
     */
    static final int FORMAT_S24_3LE = 32;

    /**
     * {@code -EINTR}.
     */
    static final int EINTR = -4;

    /**
     * {@code -EAGAIN}.
     */
    static final int EAGAIN = -11;

    /**
     * {@code -EBUSY}.
     */
    static final int EBUSY = -16;

    /**
     * {@code -ENODEV}.
     */
    static final int ENODEV = -19;

    /**
     * {@code -EBADFD}: the PCM is in a bad state, as after its device disappeared.
     */
    static final int EBADFD = -77;

    /**
     * The bindings, or {@code null} when the library is missing.
     */
    private static final Functions FUNCTIONS = Functions.load();

    /**
     * Prevents instantiation.
     */
    private Alsa() {
        throw new AssertionError();
    }

    /**
     * Binds the library's functions.
     *
     * @param nameHint {@code int snd_device_name_hint(int, const char*, void***)}
     * @param getHint {@code char *snd_device_name_get_hint(const void*, const char*)}
     * @param freeHint {@code int snd_device_name_free_hint(void**)}
     * @param open {@code int snd_pcm_open(snd_pcm_t**, const char*, snd_pcm_stream_t, int)}
     * @param close {@code int snd_pcm_close(snd_pcm_t*)}
     * @param setParams {@code int snd_pcm_set_params(snd_pcm_t*, snd_pcm_format_t,
     * snd_pcm_access_t, unsigned, unsigned, int, unsigned)}
     * @param getParams {@code int snd_pcm_get_params(snd_pcm_t*, snd_pcm_uframes_t*,
     * snd_pcm_uframes_t*)}
     * @param readi {@code snd_pcm_sframes_t snd_pcm_readi(snd_pcm_t*, void*, snd_pcm_uframes_t)}
     * @param writei {@code snd_pcm_sframes_t snd_pcm_writei(snd_pcm_t*, const void*,
     * snd_pcm_uframes_t)}
     * @param delay {@code int snd_pcm_delay(snd_pcm_t*, snd_pcm_sframes_t*)}
     * @param availUpdate {@code snd_pcm_sframes_t snd_pcm_avail_update(snd_pcm_t*)}
     * @param await {@code int snd_pcm_wait(snd_pcm_t*, int)}
     * @param recover {@code int snd_pcm_recover(snd_pcm_t*, int, int)}
     * @param start {@code int snd_pcm_start(snd_pcm_t*)}
     * @param drop {@code int snd_pcm_drop(snd_pcm_t*)}
     * @param strerror {@code const char *snd_strerror(int)}
     * @param hwParamsMalloc {@code int snd_pcm_hw_params_malloc(snd_pcm_hw_params_t**)}
     * @param hwParamsFree {@code void snd_pcm_hw_params_free(snd_pcm_hw_params_t*)}
     * @param hwParamsAny {@code int snd_pcm_hw_params_any(snd_pcm_t*, snd_pcm_hw_params_t*)}
     * @param testFormat {@code int snd_pcm_hw_params_test_format(snd_pcm_t*,
     * snd_pcm_hw_params_t*, snd_pcm_format_t)}
     * @param testChannels {@code int snd_pcm_hw_params_test_channels(snd_pcm_t*,
     * snd_pcm_hw_params_t*, unsigned)}
     * @param testRate {@code int snd_pcm_hw_params_test_rate(snd_pcm_t*,
     * snd_pcm_hw_params_t*, unsigned, int)}
     * @param free the C library's {@code void free(void*)}
     */
    private record Functions(MethodHandle nameHint, MethodHandle getHint, MethodHandle freeHint, MethodHandle open,
                             MethodHandle close, MethodHandle setParams, MethodHandle getParams, MethodHandle readi,
                             MethodHandle writei, MethodHandle delay, MethodHandle availUpdate, MethodHandle await,
                             MethodHandle recover, MethodHandle start, MethodHandle drop, MethodHandle strerror,
                             MethodHandle hwParamsMalloc, MethodHandle hwParamsFree, MethodHandle hwParamsAny,
                             MethodHandle testFormat, MethodHandle testChannels, MethodHandle testRate, MethodHandle free) {
        /**
         * Loads the library and binds the functions.
         *
         * @return the bindings, or {@code null} when the library or a function is missing
         */
        static Functions load() {
            var library = Native.library("libasound.so.2").orElse(null);
            if (library == null) {
                return null;
            }
            try {
                return new Functions(
                        Native.function(library, "snd_device_name_hint", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(library, "snd_device_name_get_hint", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(library, "snd_device_name_free_hint", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(library, "snd_pcm_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)),
                        Native.function(library, "snd_pcm_close", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(library, "snd_pcm_set_params", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)),
                        Native.function(library, "snd_pcm_get_params", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(library, "snd_pcm_readi", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG)),
                        Native.function(library, "snd_pcm_writei", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG)),
                        Native.function(library, "snd_pcm_delay", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(library, "snd_pcm_avail_update", FunctionDescriptor.of(JAVA_LONG, ADDRESS)),
                        Native.function(library, "snd_pcm_wait", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
                        Native.function(library, "snd_pcm_recover", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)),
                        Native.function(library, "snd_pcm_start", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(library, "snd_pcm_drop", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(library, "snd_strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT)),
                        Native.function(library, "snd_pcm_hw_params_malloc", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(library, "snd_pcm_hw_params_free", FunctionDescriptor.ofVoid(ADDRESS)),
                        Native.function(library, "snd_pcm_hw_params_any", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(library, "snd_pcm_hw_params_test_format", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
                        Native.function(library, "snd_pcm_hw_params_test_channels", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
                        Native.function(library, "snd_pcm_hw_params_test_rate", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)),
                        Native.function(Native.LINKER.defaultLookup(), "free", FunctionDescriptor.ofVoid(ADDRESS)));
            } catch (RuntimeException | UnsatisfiedLinkError exception) {
                return null;
            }
        }

        }

    /**
     * Returns whether the library was loaded.
     *
     * @return whether ALSA is available
     */
    static boolean available() {
        return FUNCTIONS != null;
    }

    /**
     * Returns the ALSA format of a sample encoding.
     *
     * @param format the encoding
     * @return the {@code SND_PCM_FORMAT_*} constant
     */
    static int format(AudioSampleFormat format) {
        return switch (format) {
            case PCM_S16 -> FORMAT_S16_LE;
            case PCM_S24 -> FORMAT_S24_3LE;
            case PCM_S32 -> FORMAT_S32_LE;
            case FLOAT32 -> FORMAT_FLOAT_LE;
        };
    }

    /**
     * Lists the PCM device hints.
     *
     * @param arena the arena to allocate in
     * @return the {@code NULL}-terminated hint array, which {@link #freeHints(MemorySegment)}
     * releases; {@link MemorySegment#NULL} on failure
     */
    static MemorySegment hints(Arena arena) {
        var slot = arena.allocate(ADDRESS);
        try {
            var result = (int) FUNCTIONS.nameHint().invokeExact(-1, arena.allocateFrom("pcm"), slot);
            return result < 0 ? MemorySegment.NULL : slot.get(ADDRESS, 0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads a field of a device hint.
     *
     * @param field {@code "NAME"}, {@code "DESC"} or {@code "IOID"}
     * @param arena the arena to allocate in
     * @return the value, or {@code null} when absent
     */
    static String hint(MemorySegment hint, String field, Arena arena) {
        try {
            var value = (MemorySegment) FUNCTIONS.getHint().invokeExact(hint, arena.allocateFrom(field));
            if (value.equals(MemorySegment.NULL)) {
                return null;
            }
            try {
                return value.reinterpret(Long.MAX_VALUE).getString(0);
            } finally {
                FUNCTIONS.free().invokeExact(value);
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Frees a hint array.
     *
     * @param hints the array
     */
    static void freeHints(MemorySegment hints) {
        try {
            var ignored = (int) FUNCTIONS.freeHint().invokeExact(hints);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Opens a PCM.
     *
     * @param name the device name
     * @param stream {@link #STREAM_PLAYBACK} or {@link #STREAM_CAPTURE}
     * @param mode the open mode flags
     * @param arena the arena to allocate in
     * @param result receives the handle
     * @return {@code 0} or a negative error
     */
    static int open(String name, int stream, int mode, Arena arena, MemorySegment result) {
        try {
            return (int) FUNCTIONS.open().invokeExact(result, arena.allocateFrom(name), stream, mode);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Closes a PCM; {@link MemorySegment#NULL} is ignored.
     *
     * @param pcm the handle
     */
    static void close(MemorySegment pcm) {
        if (pcm.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            var ignored = (int) FUNCTIONS.close().invokeExact(pcm);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Sets the hardware and software parameters of a PCM in one call.
     *
     * @param pcm the handle
     * @param format the {@code SND_PCM_FORMAT_*}
     * @param channels the channel count
     * @param rate the sampling rate
     * @param resample whether alsa-lib may resample
     * @param latencyMicros the buffer to ask for, in microseconds
     * @return {@code 0} or a negative error
     */
    static int setParams(MemorySegment pcm, int format, int channels, int rate, boolean resample, int latencyMicros) {
        try {
            return (int) FUNCTIONS.setParams().invokeExact(pcm, format, ACCESS_RW_INTERLEAVED, channels, rate, resample ? 1 : 0, latencyMicros);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads the buffer and period sizes of a configured PCM.
     *
     * @param pcm the handle
     * @param sizes receives the buffer size then the period size, two 64-bit values
     * @return {@code 0} or a negative error
     */
    static int getParams(MemorySegment pcm, MemorySegment sizes) {
        try {
            return (int) FUNCTIONS.getParams().invokeExact(pcm, sizes, sizes.asSlice(8));
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads interleaved frames.
     *
     * @param pcm the handle
     * @param buffer receives the frames
     * @param frames the largest number of frames
     * @return the frames read, or a negative error
     */
    static long readi(MemorySegment pcm, MemorySegment buffer, long frames) {
        try {
            return (long) FUNCTIONS.readi().invokeExact(pcm, buffer, frames);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Writes interleaved frames.
     *
     * @param pcm the handle
     * @param buffer the frames
     * @param frames the number of frames
     * @return the frames written, or a negative error
     */
    static long writei(MemorySegment pcm, MemorySegment buffer, long frames) {
        try {
            return (long) FUNCTIONS.writei().invokeExact(pcm, buffer, frames);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the delay of a PCM: for playback the frames until a frame written now is heard, for
     * capture the frames captured and not yet read.
     *
     * @param pcm the handle
     * @param scratch an 8-byte scratch segment
     * @return the delay in frames, or {@code -1} when unavailable
     */
    static long delay(MemorySegment pcm, MemorySegment scratch) {
        try {
            var result = (int) FUNCTIONS.delay().invokeExact(pcm, scratch);
            return result < 0 ? -1 : scratch.get(JAVA_LONG, 0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the frames that can be written or read without blocking.
     *
     * @param pcm the handle
     * @return the frames, or a negative error
     */
    static long availUpdate(MemorySegment pcm) {
        try {
            return (long) FUNCTIONS.availUpdate().invokeExact(pcm);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Waits until a PCM is ready.
     *
     * @param pcm the handle
     * @param timeoutMillis the longest wait in milliseconds
     * @return {@code 1} when ready, {@code 0} on timeout, or a negative error
     */
    static int await(MemorySegment pcm, int timeoutMillis) {
        try {
            return (int) FUNCTIONS.await().invokeExact(pcm, timeoutMillis);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Recovers a PCM from an overrun, underrun or suspend.
     *
     * @param pcm the handle
     * @param error the negative error to recover from
     * @return {@code 0} when recovered, or the error
     */
    static int recover(MemorySegment pcm, int error) {
        try {
            return (int) FUNCTIONS.recover().invokeExact(pcm, error, 1);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Starts a PCM.
     *
     * @param pcm the handle
     * @return {@code 0} or a negative error
     */
    static int start(MemorySegment pcm) {
        try {
            return (int) FUNCTIONS.start().invokeExact(pcm);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Stops a PCM, discarding pending frames.
     *
     * @param pcm the handle
     */
    static void drop(MemorySegment pcm) {
        try {
            var ignored = (int) FUNCTIONS.drop().invokeExact(pcm);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Describes an ALSA error.
     *
     * @param error the negative error
     * @return the description
     */
    static String describe(int error) {
        try {
            var text = (MemorySegment) FUNCTIONS.strerror().invokeExact(error);
            return text.equals(MemorySegment.NULL) ? "error " + error : text.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Allocates a hardware parameter space filled with every configuration a PCM supports.
     *
     * @param pcm the handle
     * @param arena the arena to allocate in
     * @return the parameters, which {@link #freeHwParams(MemorySegment)} releases; or
     * {@link MemorySegment#NULL} on failure
     */
    static MemorySegment hwParams(MemorySegment pcm, Arena arena) {
        try {
            var slot = arena.allocate(ADDRESS);
            if ((int) FUNCTIONS.hwParamsMalloc().invokeExact(slot) < 0) {
                return MemorySegment.NULL;
            }
            var params = slot.get(ADDRESS, 0);
            if ((int) FUNCTIONS.hwParamsAny().invokeExact(pcm, params) < 0) {
                FUNCTIONS.hwParamsFree().invokeExact(params);
                return MemorySegment.NULL;
            }
            return params;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Frees a hardware parameter space.
     *
     * @param params the parameters
     */
    static void freeHwParams(MemorySegment params) {
        try {
            FUNCTIONS.hwParamsFree().invokeExact(params);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns whether a PCM supports a sample format.
     *
     * @param pcm the handle
     * @param params its parameter space
     * @param format the {@code SND_PCM_FORMAT_*}
     * @return whether it does
     */
    static boolean supportsFormat(MemorySegment pcm, MemorySegment params, int format) {
        try {
            return (int) FUNCTIONS.testFormat().invokeExact(pcm, params, format) == 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns whether a PCM supports a channel count.
     *
     * @param pcm the handle
     * @param params its parameter space
     * @param channels the channel count
     * @return whether it does
     */
    static boolean supportsChannels(MemorySegment pcm, MemorySegment params, int channels) {
        try {
            return (int) FUNCTIONS.testChannels().invokeExact(pcm, params, channels) == 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns whether a PCM supports a sampling rate exactly.
     *
     * @param pcm the handle
     * @param params its parameter space
     * @return whether it does
     */
    static boolean supportsRate(MemorySegment pcm, MemorySegment params, int rate) {
        try {
            return (int) FUNCTIONS.testRate().invokeExact(pcm, params, rate, 0) == 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Builds the failure raised for a failed call.
     *
     * @param message the description of the failure
     * @param error the negative error
     * @return the exception
     */
    static MediaException.Io failure(String message, int error) {
        var detail = message + ": " + describe(error) + " (" + error + ")";
        return new MediaException.Io(detail, new IOException(detail));
    }

}
