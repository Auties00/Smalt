package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.Native;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Calls the C library of a 64-bit Linux system through the Foreign Function and Memory API.
 *
 * <p>Every function that reports failures through {@code errno} is linked with
 * {@link Linker.Option#captureCallState(String...)}, so the value is read from this instance's
 * capture segment right after the call instead of from a thread-local the JVM may have
 * overwritten. An instance is not thread-safe: it must be used by one thread at a time, with a
 * happens-before edge between users (as {@link Thread#start()} and {@link Thread#join()} give).
 *
 * <p>The constants are the values of the Linux user-space ABI shared by x86_64, aarch64 and
 * riscv64 (the generic {@code asm-generic} headers: {@code fcntl.h}, {@code errno-base.h},
 * {@code mman-common.h}, {@code poll.h}, and {@code linux/ipc.h}, {@code linux/time.h},
 * {@code linux/eventfd.h}).
 */
final class Posix {
    /**
     * The {@code O_RDWR} open flag.
     */
    static final int O_RDWR = 0x2;

    /**
     * The {@code O_NONBLOCK} open flag, octal {@code 04000}.
     */
    static final int O_NONBLOCK = 0x800;

    /**
     * The {@code O_CLOEXEC} open flag, octal {@code 02000000}.
     */
    static final int O_CLOEXEC = 0x80000;

    /**
     * The {@code ENOENT} error: no such file or directory.
     */
    static final int ENOENT = 2;

    /**
     * The {@code EINTR} error: the call was interrupted by a signal.
     */
    static final int EINTR = 4;

    /**
     * The {@code EIO} error: an input/output error.
     */
    static final int EIO = 5;

    /**
     * The {@code ENXIO} error: no such device or address.
     */
    static final int ENXIO = 6;

    /**
     * The {@code EAGAIN} error: no data is available on a non-blocking descriptor.
     */
    static final int EAGAIN = 11;

    /**
     * The {@code ENOMEM} error: out of memory.
     */
    static final int ENOMEM = 12;

    /**
     * The {@code EACCES} error: permission denied.
     */
    static final int EACCES = 13;

    /**
     * The {@code EBUSY} error: the device or resource is busy.
     */
    static final int EBUSY = 16;

    /**
     * The {@code ENODEV} error: no such device.
     */
    static final int ENODEV = 19;

    /**
     * The {@code EINVAL} error: an invalid argument, also the end of a V4L2 enumeration.
     */
    static final int EINVAL = 22;

    /**
     * The {@code ENOTTY} error: the descriptor does not support the request.
     */
    static final int ENOTTY = 25;

    /**
     * The {@code ECONNREFUSED} error number.
     */
    static final int ECONNREFUSED = 111;

    /**
     * The {@code AF_UNIX} address family.
     */
    static final int AF_UNIX = 1;

    /**
     * The {@code SOCK_STREAM} socket type with {@code SOCK_CLOEXEC}.
     */
    static final int SOCK_STREAM_CLOEXEC = 1 | 0x80000;

    /**
     * The {@code SOL_SOCKET} level of a control message.
     */
    static final int SOL_SOCKET = 1;

    /**
     * The {@code SCM_RIGHTS} control message, which carries descriptors.
     */
    static final int SCM_RIGHTS = 1;

    /**
     * The {@code MSG_CMSG_CLOEXEC} flag of {@code recvmsg}, which marks received descriptors close-on-exec.
     */
    static final int MSG_CMSG_CLOEXEC = 0x40000000;

    /**
     * The {@code MSG_NOSIGNAL} flag of {@code sendmsg}.
     */
    static final int MSG_NOSIGNAL = 0x4000;

    /**
     * The layout of {@code struct sockaddr_un}.
     */
    static final StructLayout SOCKADDR_UN = MemoryLayout.structLayout(
            JAVA_SHORT.withName("sun_family"),
            MemoryLayout.sequenceLayout(108, JAVA_BYTE).withName("sun_path")).withName("sockaddr_un");

    /**
     * The layout of {@code struct iovec}.
     */
    static final StructLayout IOVEC = MemoryLayout.structLayout(
            ADDRESS.withName("iov_base"),
            JAVA_LONG.withName("iov_len")).withName("iovec");

    /**
     * The layout of {@code struct msghdr}.
     */
    static final StructLayout MSGHDR = MemoryLayout.structLayout(
            ADDRESS.withName("msg_name"),
            JAVA_INT.withName("msg_namelen"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("msg_iov"),
            JAVA_LONG.withName("msg_iovlen"),
            ADDRESS.withName("msg_control"),
            JAVA_LONG.withName("msg_controllen"),
            JAVA_INT.withName("msg_flags"),
            MemoryLayout.paddingLayout(4)).withName("msghdr");

    /**
     * The layout of {@code struct cmsghdr}, whose data follows at {@link #CMSG_DATA}.
     */
    static final StructLayout CMSGHDR = MemoryLayout.structLayout(
            JAVA_LONG.withName("cmsg_len"),
            JAVA_INT.withName("cmsg_level"),
            JAVA_INT.withName("cmsg_type")).withName("cmsghdr");

    /**
     * The offset of a control message's data, {@code CMSG_DATA}: the header rounded up to 8 bytes.
     */
    static final long CMSG_DATA = 16;

    /**
     * The {@code PROT_READ} protection of {@code mmap}.
     */
    static final int PROT_READ = 0x1;

    /**
     * The {@code PROT_WRITE} protection of {@code mmap}.
     */
    static final int PROT_WRITE = 0x2;

    /**
     * The {@code MAP_SHARED} flag of {@code mmap}.
     */
    static final int MAP_SHARED = 0x1;

    /**
     * The {@code POLLIN} event: data is available.
     */
    static final short POLLIN = 0x1;

    /**
     * The {@code POLLERR} event: an error condition.
     */
    static final short POLLERR = 0x8;

    /**
     * The {@code POLLHUP} event: the peer hung up.
     */
    static final short POLLHUP = 0x10;

    /**
     * The {@code POLLNVAL} event: the descriptor is not open.
     */
    static final short POLLNVAL = 0x20;

    /**
     * The {@code CLOCK_MONOTONIC} clock identifier.
     */
    static final int CLOCK_MONOTONIC = 1;

    /**
     * The {@code IPC_PRIVATE} key of {@code shmget}.
     */
    static final int IPC_PRIVATE = 0;

    /**
     * The {@code IPC_CREAT} flag of {@code shmget}, octal {@code 01000}.
     */
    static final int IPC_CREAT = 0x200;

    /**
     * The {@code IPC_RMID} command of {@code shmctl}.
     */
    static final int IPC_RMID = 0;

    /**
     * The layout of {@code struct pollfd}: {@code int fd; short events; short revents;}.
     */
    static final StructLayout POLLFD = MemoryLayout.structLayout(
            JAVA_INT.withName("fd"),
            JAVA_SHORT.withName("events"),
            JAVA_SHORT.withName("revents")).withName("pollfd");

    /**
     * The layout of {@code struct timespec} on LP64: two {@code long} fields.
     */
    static final StructLayout TIMESPEC = MemoryLayout.structLayout(
            JAVA_LONG.withName("tv_sec"),
            JAVA_LONG.withName("tv_nsec")).withName("timespec");

    /**
     * The offset of {@code pollfd.events}.
     */
    static final long POLLFD_EVENTS = POLLFD.byteOffset(MemoryLayout.PathElement.groupElement("events"));

    /**
     * The offset of {@code pollfd.revents}.
     */
    static final long POLLFD_REVENTS = POLLFD.byteOffset(MemoryLayout.PathElement.groupElement("revents"));

    /**
     * The captured {@code errno} of the last call.
     */
    private final MemorySegment state;

    /**
     * The scratch {@code struct timespec} of {@link #monotonicNanos()}.
     */
    private final MemorySegment timespec;

    /**
     * Allocates the per-instance call state.
     *
     * @throws IllegalCallerException if the module is denied native access
     */
    Posix() {
        var arena = Arena.ofAuto();
        this.state = arena.allocate(Functions.STATE);
        this.timespec = arena.allocate(TIMESPEC);
    }

    /**
     * Returns the {@code errno} captured by the last failed call of this instance.
     *
     * @return the error number
     */
    int errno() {
        return (int) Functions.ERRNO.get(state, 0L);
    }

    /**
     * Opens a file.
     *
     * @param flags the {@code O_*} flags
     * @return the descriptor, or {@code -1} with {@link #errno()} set
     */
    int open(String path, int flags) {
        try (var arena = Arena.ofConfined()) {
            var name = arena.allocateFrom(path);
            while (true) {
                var result = (int) Functions.OPEN.invokeExact(state, name, flags, 0);
                if (result >= 0 || errno() != EINTR) {
                    return result;
                }
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Closes a descriptor, ignoring failures.
     *
     * @param fd the descriptor; ignored when negative
     */
    void close(int fd) {
        if (fd < 0) {
            return;
        }
        try {
            var ignored = (int) Functions.CLOSE.invokeExact(state, fd);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Issues an {@code ioctl} whose argument is a pointer, retrying when a signal interrupts it.
     *
     * @param fd the descriptor
     * @param request the request number
     * @param argument the argument structure
     * @return {@code 0} on success, otherwise the positive {@code errno}
     */
    int ioctl(int fd, long request, MemorySegment argument) {
        try {
            while (true) {
                var result = (int) Functions.IOCTL.invokeExact(state, fd, request, argument);
                if (result != -1) {
                    return 0;
                }
                var error = errno();
                if (error != EINTR) {
                    return error;
                }
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Maps a shared, readable and writable region of a descriptor.
     *
     * @param fd the descriptor
     * @param length the length in bytes; positive
     * @param offset the offset in the file
     * @return the mapped segment of {@code length} bytes, or {@code null} with {@link #errno()}
     * set
     */
    MemorySegment mmap(int fd, long length, long offset) {
        try {
            var address = (MemorySegment) Functions.MMAP.invokeExact(state, MemorySegment.NULL, length,
                    PROT_READ | PROT_WRITE, MAP_SHARED, fd, offset);
            if (address.address() == -1L) {
                return null;
            }
            return address.reinterpret(length);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Unmaps a region returned by {@link #mmap(int, long, long)}, ignoring failures.
     */
    void munmap(MemorySegment region) {
        try {
            var ignored = (int) Functions.MUNMAP.invokeExact(state, region, region.byteSize());
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Waits for events on descriptors.
     *
     * @param fds the array of {@code struct pollfd}
     * @param count the number of entries
     * @param timeout the timeout in milliseconds, negative to wait forever
     * @return the number of ready descriptors, {@code 0} on timeout or interruption by a signal,
     * or {@code -1} with {@link #errno()} set
     */
    int poll(MemorySegment fds, int count, int timeout) {
        try {
            var result = (int) Functions.POLL.invokeExact(state, fds, (long) count, timeout);
            return result == -1 && errno() == EINTR ? 0 : result;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Creates a non-blocking, close-on-exec event counter.
     *
     * @return the descriptor, or {@code -1} with {@link #errno()} set
     */
    int eventfd() {
        try {
            return (int) Functions.EVENTFD.invokeExact(state, 0, O_NONBLOCK | O_CLOEXEC);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Adds one to an event counter, waking every {@code poll} on it.
     *
     * @param fd the event counter
     */
    void signal(int fd) {
        try (var arena = Arena.ofConfined()) {
            var one = arena.allocate(JAVA_LONG);
            one.set(JAVA_LONG, 0, 1L);
            var ignored = (long) Functions.WRITE.invokeExact(state, fd, one, 8L);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads the monotonic clock.
     *
     * @return the time in nanoseconds since an unspecified origin
     */
    long monotonicNanos() {
        try {
            var ignored = (int) Functions.CLOCK_GETTIME.invokeExact(CLOCK_MONOTONIC, timespec);
            return timespec.get(JAVA_LONG, 0) * 1_000_000_000L + timespec.get(JAVA_LONG, 8);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Creates a private System V shared memory segment.
     *
     * @param size the size in bytes; positive
     * @return the segment identifier, or {@code -1} with {@link #errno()} set
     */
    int shmget(long size) {
        try {
            return (int) Functions.SHMGET.invokeExact(state, IPC_PRIVATE, size, IPC_CREAT | 0600);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Attaches a System V shared memory segment.
     *
     * @param id the segment identifier
     * @param size the size of the segment in bytes
     * @return the attached segment, or {@code null} with {@link #errno()} set
     */
    MemorySegment shmat(int id, long size) {
        try {
            var address = (MemorySegment) Functions.SHMAT.invokeExact(state, id, MemorySegment.NULL, 0);
            if (address.address() == -1L) {
                return null;
            }
            return address.reinterpret(size);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Detaches a System V shared memory segment, ignoring failures.
     *
     * @param segment the attached segment
     */
    void shmdt(MemorySegment segment) {
        try {
            var ignored = (int) Functions.SHMDT.invokeExact(state, segment);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Marks a System V shared memory segment for removal once its last user detaches, ignoring
     * failures.
     *
     * @param id the segment identifier
     */
    void shmRemove(int id) {
        try {
            var ignored = (int) Functions.SHMCTL.invokeExact(state, id, IPC_RMID, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Connects a stream socket to a Unix domain socket.
     *
     * @param path the socket's path; a path starting with a NUL byte names an abstract socket
     * @return the descriptor, or {@code -1} with {@link #errno()} set
     */
    int connectUnix(byte[] path) {
        if (path.length >= 108) {
            return -1;
        }
        try (var arena = Arena.ofConfined()) {
            var fd = (int) Functions.SOCKET.invokeExact(state, AF_UNIX, SOCK_STREAM_CLOEXEC, 0);
            if (fd < 0) {
                return -1;
            }
            var address = arena.allocate(SOCKADDR_UN);
            address.set(JAVA_SHORT, 0, (short) AF_UNIX);
            MemorySegment.copy(path, 0, address, JAVA_BYTE, 2, path.length);
            var length = 2 + path.length + (path.length > 0 && path[0] == 0 ? 0 : 1);
            while (true) {
                var result = (int) Functions.CONNECT.invokeExact(state, fd, address, length);
                if (result == 0) {
                    return fd;
                }
                if (errno() != EINTR) {
                    var error = errno();
                    close(fd);
                    Functions.ERRNO.set(state, 0L, error);
                    return -1;
                }
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Sends bytes on a socket, retrying when a signal interrupts the call.
     *
     * @param fd the socket
     * @param data the bytes
     * @param length the number of bytes
     * @return the number of bytes sent, or {@code -1} with {@link #errno()} set
     */
    long send(int fd, MemorySegment data, long length) {
        try {
            while (true) {
                var result = (long) Functions.SEND.invokeExact(state, fd, data, length, MSG_NOSIGNAL);
                if (result >= 0 || errno() != EINTR) {
                    return result;
                }
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Receives bytes and descriptors from a socket, retrying when a signal interrupts the call.
     *
     * @param fd the socket
     * @param message a {@link #MSGHDR} whose buffers the caller set
     * @return the number of bytes received, {@code 0} at the end of the stream, or {@code -1} with
     * {@link #errno()} set
     */
    long recvmsg(int fd, MemorySegment message) {
        try {
            while (true) {
                var result = (long) Functions.RECVMSG.invokeExact(state, fd, message, MSG_CMSG_CLOEXEC);
                if (result >= 0 || errno() != EINTR) {
                    return result;
                }
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the real user identifier of the process.
     *
     * @return the user identifier
     */
    long uid() {
        try {
            return Integer.toUnsignedLong((int) Functions.GETUID.invokeExact());
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Describes an error number.
     *
     * <p>The description is computed without calling the C library, so it is available on every
     * operating system (the tests describe the errors of simulated devices).
     *
     * @param errno the error number
     * @return the symbolic name and meaning of the common errors, for example
     * {@code "EBUSY (device or resource busy)"}, or {@code "errno N"} for the others
     */
    static String describe(int errno) {
        return switch (errno) {
            case ENOENT -> "ENOENT (no such file or directory)";
            case EINTR -> "EINTR (interrupted system call)";
            case EIO -> "EIO (input/output error)";
            case ENXIO -> "ENXIO (no such device or address)";
            case EAGAIN -> "EAGAIN (resource temporarily unavailable)";
            case ENOMEM -> "ENOMEM (cannot allocate memory)";
            case EACCES -> "EACCES (permission denied)";
            case EBUSY -> "EBUSY (device or resource busy)";
            case ENODEV -> "ENODEV (no such device)";
            case EINVAL -> "EINVAL (invalid argument)";
            case ENOTTY -> "ENOTTY (inappropriate ioctl for device)";
            case ECONNREFUSED -> "ECONNREFUSED (connection refused)";
            default -> "errno " + errno;
        };
    }

    /**
     * Builds the I/O failure of a call.
     *
     * @param what the operation that failed
     * @param errno the error number
     * @return the failure
     */
    static IOException failure(String what, int errno) {
        return new IOException(what + " failed: " + describe(errno));
    }

    /**
     * Holds the downcall handles, linked on first use so that loading {@link Posix} on another
     * operating system links nothing.
     */
    private static final class Functions {
        /**
         * The layout of the captured call state.
         */
        static final StructLayout STATE = Linker.Option.captureStateLayout();

        /**
         * Reads {@code errno} from the captured call state.
         */
        static final VarHandle ERRNO = STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

        /**
         * {@code int open(const char *path, int flags, ...)}, called with a zero mode.
         */
        static final MethodHandle OPEN = link("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), true, 2);

        /**
         * {@code int close(int fd)}.
         */
        static final MethodHandle CLOSE = link("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT), true, -1);

        /**
         * {@code int ioctl(int fd, unsigned long request, ...)}, called with one pointer.
         */
        static final MethodHandle IOCTL = link("ioctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), true, 2);

        /**
         * {@code void *mmap(void *address, size_t length, int prot, int flags, int fd, off_t offset)}.
         */
        static final MethodHandle MMAP = link("mmap",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG), true, -1);

        /**
         * {@code int munmap(void *address, size_t length)}.
         */
        static final MethodHandle MUNMAP = link("munmap", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG), true, -1);

        /**
         * {@code int poll(struct pollfd *fds, nfds_t count, int timeout)}.
         */
        static final MethodHandle POLL = link("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), true, -1);

        /**
         * {@code int eventfd(unsigned int initial, int flags)}.
         */
        static final MethodHandle EVENTFD = link("eventfd", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), true, -1);

        /**
         * {@code ssize_t write(int fd, const void *buffer, size_t count)}.
         */
        static final MethodHandle WRITE = link("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), true, -1);

        /**
         * {@code int clock_gettime(clockid_t clock, struct timespec *time)}.
         */
        static final MethodHandle CLOCK_GETTIME = link("clock_gettime", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS), false, -1);

        /**
         * {@code int shmget(key_t key, size_t size, int flags)}.
         */
        static final MethodHandle SHMGET = link("shmget", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT), true, -1);

        /**
         * {@code void *shmat(int id, const void *address, int flags)}.
         */
        static final MethodHandle SHMAT = link("shmat", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT), true, -1);

        /**
         * {@code int shmdt(const void *address)}.
         */
        static final MethodHandle SHMDT = link("shmdt", FunctionDescriptor.of(JAVA_INT, ADDRESS), true, -1);

        /**
         * {@code int shmctl(int id, int command, struct shmid_ds *buffer)}.
         */
        static final MethodHandle SHMCTL = link("shmctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), true, -1);


        /**
         * {@code int socket(int domain, int type, int protocol)}.
         */
        static final MethodHandle SOCKET = link("socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), true, -1);

        /**
         * {@code int connect(int fd, const struct sockaddr *address, socklen_t length)}.
         */
        static final MethodHandle CONNECT = link("connect", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), true, -1);

        /**
         * {@code ssize_t send(int fd, const void *buffer, size_t length, int flags)}.
         */
        static final MethodHandle SEND = link("send", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), true, -1);

        /**
         * {@code ssize_t recvmsg(int fd, struct msghdr *message, int flags)}.
         */
        static final MethodHandle RECVMSG = link("recvmsg", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT), true, -1);

        /**
         * {@code uid_t getuid(void)}.
         */
        static final MethodHandle GETUID = link("getuid", FunctionDescriptor.of(JAVA_INT), false, -1);

        /**
         * Prevents instantiation.
         */
        private Functions() {
            throw new AssertionError();
        }

        /**
         * Links a function of the C library.
         *
         * @param name the symbol
         * @param descriptor the signature
         * @param captureErrno whether to capture {@code errno}, which prepends the capture segment
         * to the handle's parameters
         * @param firstVariadic the index of the first variadic parameter, or {@code -1}
         * @return the handle
         * @throws UnsatisfiedLinkError if the C library lacks the symbol
         */
        private static MethodHandle link(String name, FunctionDescriptor descriptor, boolean captureErrno, int firstVariadic) {
            var options = new ArrayList<Linker.Option>(2);
            if (captureErrno) {
                options.add(Linker.Option.captureCallState("errno"));
            }
            if (firstVariadic >= 0) {
                options.add(Linker.Option.firstVariadicArg(firstVariadic));
            }
            return Native.function(Native.LINKER.defaultLookup(), name, descriptor, options.toArray(Linker.Option[]::new));
        }
    }
}
