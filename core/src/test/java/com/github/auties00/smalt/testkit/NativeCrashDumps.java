package com.github.auties00.smalt.testkit;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;

/**
 * Writes a minidump of a Windows test JVM that a native exception on a thread the JVM did not create is about to
 * end, a crash the JVM reports nowhere: no hs_err file, and on the hosted runners no Windows Error Reporting dump.
 *
 * <p>The filter is the process's unhandled exception filter ({@code SetUnhandledExceptionFilter}), which Windows
 * calls only for an exception no handler took, at the base frame of the faulting thread; the JVM's own handlers see
 * its threads' exceptions first, so the filter costs nothing until such a crash. The faulting thread cannot run
 * Java (an upcall from it ends the process silently), so the filter is a few x64 instructions written into
 * executable memory: they store the {@code EXCEPTION_POINTERS} and the thread id, signal an event and wait, for at
 * most a minute, for a watcher thread of the JVM, which writes {@code native-crash-<pid>-<tid>.dmp}
 * ({@code MiniDumpWriteDump} from another thread, as its documentation advises: every thread's stack, the module
 * list and the memory the stacks reference) and {@code native-crash-<pid>-<tid>.txt} (exception code, address and
 * the module holding it), prints the text to standard error and releases the faulting thread, which hands the
 * exception to the filter it replaced. Only the first crash is written.
 *
 * <p>A crash that Windows cannot hand to any handler (a thread whose stack is unusable, a fail-fast exit) reaches
 * no filter. For those, a vectored exception handler, a few more generated instructions that run before the JVM's
 * own handler, records every exception the process raises (code, thread id, address, stack pointer and the
 * address an access violation touched) into a ring of {@value #RING_ENTRIES} entries in
 * {@code exceptions-<pid>.bin}, a file mapped into memory, whose content the system writes to disk even when the
 * process dies; a thread rewrites {@code modules-<pid>.txt} (base, size and path of every loaded module) when the
 * module list changes, and a shutdown hook writes {@code exit-<pid>.txt}, so a ring without it is that of a JVM
 * that died. {@code .github/scripts/native-exceptions.py} prints such a ring. The ring's header is the 32-bit count
 * of exceptions recorded, the entries follow at offset {@value #RING_HEADER}, {@value #RING_ENTRY} bytes each:
 * code and thread id (32 bits each), address, stack pointer and second exception parameter (64 bits each), little
 * endian.
 *
 * <p>Test JVMs install it through {@link NativeCrashDumpsListener} when the environment variable
 * {@value #ENVIRONMENT} (or the system property {@value #PROPERTY}) names the directory. It needs Windows on x64.
 * The class uses only the JDK, so that a child JVM can run {@link #main(String...)} from the test kit's classes
 * alone.
 */
public final class NativeCrashDumps {
    /**
     * The environment variable naming the dump directory.
     */
    public static final String ENVIRONMENT = "SMALT_TEST_NATIVE_CRASH_DUMPS";

    /**
     * The system property naming the dump directory, which takes precedence over {@link #ENVIRONMENT}.
     */
    public static final String PROPERTY = "smalt.test.nativeCrashDumps";

    /**
     * {@code MiniDumpWithDataSegs | MiniDumpWithHandleData | MiniDumpWithUnloadedModules |
     * MiniDumpWithIndirectlyReferencedMemory | MiniDumpWithProcessThreadData | MiniDumpWithFullMemoryInfo |
     * MiniDumpWithThreadInfo}: the stacks and what they point to, without the whole Java heap.
     */
    private static final int DUMP_TYPE = 0x1 | 0x4 | 0x20 | 0x40 | 0x100 | 0x800 | 0x1000;

    /**
     * How long the faulting thread waits for the dump, in milliseconds.
     */
    private static final int DUMP_WAIT_MILLIS = 60_000;

    /**
     * The offset, in the filter's page, of the slot receiving the {@code EXCEPTION_POINTERS} address.
     */
    public static final int POINTERS_SLOT = 512;

    /**
     * The offset, in the filter's page, of the slot receiving the faulting thread's id.
     */
    public static final int THREAD_SLOT = 520;

    /**
     * The number of entries of the exception ring, a power of two.
     */
    public static final int RING_ENTRIES = 1024;

    /**
     * The size of the exception ring's header, in bytes.
     */
    public static final int RING_HEADER = 64;

    /**
     * The size of an entry of the exception ring, in bytes.
     */
    public static final int RING_ENTRY = 32;

    /**
     * The offset, in the generated code's page, of the vectored exception handler.
     */
    private static final int RECORDER_OFFSET = 1024;

    /**
     * How often the module list is checked, in milliseconds.
     */
    private static final long MODULES_PERIOD_MILLIS = 1_000;

    /**
     * The lock guarding the installation.
     */
    private static final Object LOCK = new Object();

    /**
     * Whether the filter is installed; guarded by {@link #LOCK}.
     */
    private static boolean installed;

    private NativeCrashDumps() {
        throw new AssertionError();
    }

    /**
     * {@return the dump directory this JVM is configured with, or {@code null} if none}
     */
    static Path configured() {
        var configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ENVIRONMENT);
        }
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    /**
     * {@return whether the filter is installed in this JVM}
     */
    public static boolean installed() {
        synchronized (LOCK) {
            return installed;
        }
    }

    /**
     * {@return whether this JVM runs on Windows on x64, where the filter can be installed}
     */
    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
               && System.getProperty("os.arch", "").equals("amd64");
    }

    /**
     * Installs the filter and starts its watcher thread; does nothing if it is installed already.
     *
     * @param target the directory receiving the dumps, created if missing
     * @throws IOException if the directory cannot be created
     * @throws UnsupportedOperationException if the system is not Windows on x64
     */
    public static void install(Path target) throws IOException {
        if (!supported()) {
            throw new UnsupportedOperationException("native crash dumps need Windows on x64");
        }
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            var directory = Files.createDirectories(target.toAbsolutePath());
            var functions = Functions.link();
            try {
                var crashed = (MemorySegment) functions.createEvent().invokeExact(MemorySegment.NULL, 1, 0,
                        MemorySegment.NULL);
                var released = (MemorySegment) functions.createEvent().invokeExact(MemorySegment.NULL, 1, 0,
                        MemorySegment.NULL);
                // MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE
                var page = ((MemorySegment) functions.virtualAlloc().invokeExact(MemorySegment.NULL, 4096L, 0x3000,
                        0x40)).reinterpret(4096);
                if (crashed.equals(MemorySegment.NULL) || released.equals(MemorySegment.NULL)
                    || page.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("cannot allocate the filter");
                }
                var replaced = (MemorySegment) functions.setFilter().invokeExact(MemorySegment.NULL);
                var code = filterCode(page.address(), functions.threadIdAddress(), functions.setEventAddress(),
                        functions.waitAddress(), crashed.address(), released.address(), replaced.address());
                MemorySegment.copy(code, 0, page, JAVA_BYTE, 0, code.length);
                var ignored = (int) functions.flushInstructionCache().invokeExact(MemorySegment.ofAddress(-1L), page,
                        (long) code.length);
                var pid = ProcessHandle.current().pid();
                var ring = mapRing(functions, directory.resolve("exceptions-" + pid + ".bin"));
                var recorder = recorderCode(ring.address());
                MemorySegment.copy(recorder, 0, page, JAVA_BYTE, RECORDER_OFFSET, recorder.length);
                ignored = (int) functions.flushInstructionCache().invokeExact(MemorySegment.ofAddress(-1L), page, 4096L);
                var handler = (MemorySegment) functions.addVectoredHandler().invokeExact(1,
                        page.asSlice(RECORDER_OFFSET));
                if (handler.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("AddVectoredExceptionHandler failed");
                }
                var exit = directory.resolve("exit-" + pid + ".txt");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        Files.writeString(exit, "exited" + System.lineSeparator());
                    } catch (IOException exception) {
                        // the ring then reads as that of a JVM that died
                    }
                }, "smalt-native-crash-exit"));
                var modules = new ModuleList(functions, directory.resolve("modules-" + pid + ".txt"));
                modules.write();
                Thread.ofPlatform().daemon().name("smalt-native-crash-modules").start(modules);
                var watcher = new Watcher(functions, directory, page, crashed, released);
                Thread.ofPlatform().daemon().name("smalt-native-crash-dumps").start(watcher);
                var previous = (MemorySegment) functions.setFilter().invokeExact(page);
                if (!previous.equals(MemorySegment.NULL)) {
                    // another filter was installed in between: the generated code chains to the one read before
                    var restore = (MemorySegment) functions.setFilter().invokeExact(previous);
                    throw new IllegalStateException("the unhandled exception filter changed while installing");
                }
            } catch (RuntimeException | Error exception) {
                throw exception;
            } catch (Throwable throwable) {
                throw new IllegalStateException(throwable);
            }
            installed = true;
        }
    }

    /**
     * Assembles the filter: stores the {@code EXCEPTION_POINTERS} and the thread id into the page, signals
     * {@code crashed}, waits for {@code released} and returns what the replaced filter returns, or
     * {@code EXCEPTION_CONTINUE_SEARCH} if there is none.
     *
     * @param page the address of the page holding the code and its slots
     * @param threadId the address of {@code GetCurrentThreadId}
     * @param setEvent the address of {@code SetEvent}
     * @param wait the address of {@code WaitForSingleObject}
     * @param crashed the event the code signals
     * @param released the event the code waits for
     * @param replaced the filter to chain to, or {@code 0}
     * @return the machine code
     */
    public static byte[] filterCode(long page, long threadId, long setEvent, long wait, long crashed, long released,
                                    long replaced) {
        var code = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        code.put(new byte[]{0x48, (byte) 0x83, (byte) 0xEC, 0x28});                  // sub rsp, 0x28
        code.put(new byte[]{0x48, (byte) 0xB8}).putLong(page + POINTERS_SLOT);      // mov rax, slot
        code.put(new byte[]{0x48, (byte) 0x89, 0x08});                              // mov [rax], rcx
        code.put(new byte[]{0x48, (byte) 0xB8}).putLong(threadId); // mov rax, GetCurrentThreadId
        code.put(new byte[]{(byte) 0xFF, (byte) 0xD0});                             // call rax
        code.put(new byte[]{0x48, (byte) 0xBA}).putLong(page + THREAD_SLOT);       // mov rdx, slot
        code.put(new byte[]{(byte) 0x89, 0x02});                                    // mov [rdx], eax
        code.put(new byte[]{0x48, (byte) 0xB9}).putLong(crashed);                   // mov rcx, crashed
        code.put(new byte[]{0x48, (byte) 0xB8}).putLong(setEvent); // mov rax, SetEvent
        code.put(new byte[]{(byte) 0xFF, (byte) 0xD0});                             // call rax
        code.put(new byte[]{0x48, (byte) 0xB9}).putLong(released);                  // mov rcx, released
        code.put((byte) 0xBA).putInt(DUMP_WAIT_MILLIS);                             // mov edx, timeout
        code.put(new byte[]{0x48, (byte) 0xB8}).putLong(wait);   // mov rax, WaitForSingleObject
        code.put(new byte[]{(byte) 0xFF, (byte) 0xD0});                             // call rax
        code.put(new byte[]{0x48, (byte) 0xB8}).putLong(page + POINTERS_SLOT);      // mov rax, slot
        code.put(new byte[]{0x48, (byte) 0x8B, 0x08});                              // mov rcx, [rax]
        code.put(new byte[]{0x48, (byte) 0xB8}).putLong(replaced);                  // mov rax, replaced
        code.put(new byte[]{0x48, (byte) 0x85, (byte) 0xC0});                       // test rax, rax
        code.put(new byte[]{0x74, 0x04});                                           // jz +4
        code.put(new byte[]{(byte) 0xFF, (byte) 0xD0});                             // call rax
        code.put(new byte[]{(byte) 0xEB, 0x02});                                    // jmp +2
        code.put(new byte[]{0x31, (byte) 0xC0});                                    // xor eax, eax
        code.put(new byte[]{0x48, (byte) 0x83, (byte) 0xC4, 0x28});                  // add rsp, 0x28
        code.put((byte) 0xC3);                                                      // ret
        var bytes = new byte[code.position()];
        code.flip().get(bytes);
        return bytes;
    }

    /**
     * Creates the exception ring's file and maps it for the life of the process.
     *
     * @param functions the linked functions
     * @param file the ring's file
     * @return the mapped ring
     * @throws Throwable if a call fails
     */
    private static MemorySegment mapRing(Functions functions, Path file) throws Throwable {
        var size = RING_HEADER + (long) RING_ENTRIES * RING_ENTRY;
        try (var arena = Arena.ofConfined()) {
            var path = arena.allocateFrom(file.toString(), StandardCharsets.UTF_16LE);
            // GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL
            var handle = (MemorySegment) functions.createFile().invokeExact(path, 0xC0000000, 1, MemorySegment.NULL, 2,
                    0x80, MemorySegment.NULL);
            if (handle.address() == -1L) {
                throw new IllegalStateException("cannot create " + file);
            }
            // PAGE_READWRITE
            var mapping = (MemorySegment) functions.createFileMapping().invokeExact(handle, MemorySegment.NULL, 0x04,
                    0, (int) size, MemorySegment.NULL);
            if (mapping.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("cannot map " + file);
            }
            // FILE_MAP_WRITE
            var view = (MemorySegment) functions.mapView().invokeExact(mapping, 0x2, 0, 0, size);
            if (view.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("cannot map " + file);
            }
            return view.reinterpret(size);
        }
    }

    /**
     * Assembles the vectored exception handler: claims the next entry of the ring with an atomic increment of its
     * count and writes the exception's code, the thread id (from the thread environment block), the exception
     * address, the stack pointer of the context and the second exception parameter into it, then returns
     * {@code EXCEPTION_CONTINUE_SEARCH}. It uses no stack and calls nothing.
     *
     * @param ring the address of the mapped ring
     * @return the machine code
     */
    public static byte[] recorderCode(long ring) {
        var code = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        code.put(new byte[]{0x4C, (byte) 0x8B, 0x11});                              // mov r10, [rcx]
        code.put(new byte[]{0x4C, (byte) 0x8B, 0x59, 0x08});                        // mov r11, [rcx+8]
        code.put(new byte[]{0x48, (byte) 0xB8}).putLong(ring);                      // mov rax, ring
        code.put((byte) 0xBA).putInt(1);                                            // mov edx, 1
        code.put(new byte[]{(byte) 0xF0, 0x0F, (byte) 0xC1, 0x10});                 // lock xadd [rax], edx
        code.put(new byte[]{(byte) 0x81, (byte) 0xE2}).putInt(RING_ENTRIES - 1);    // and edx, entries - 1
        code.put(new byte[]{(byte) 0xC1, (byte) 0xE2, 0x05});                       // shl edx, 5
        code.put(new byte[]{0x48, (byte) 0x8D, 0x44, 0x10, RING_HEADER});           // lea rax, [rax+rdx+header]
        code.put(new byte[]{0x45, (byte) 0x8B, 0x02});                              // mov r8d, [r10]
        code.put(new byte[]{0x44, (byte) 0x89, 0x00});                              // mov [rax], r8d
        code.put(new byte[]{0x65, 0x44, (byte) 0x8B, 0x04, 0x25}).putInt(0x48);     // mov r8d, gs:[0x48]
        code.put(new byte[]{0x44, (byte) 0x89, 0x40, 0x04});                        // mov [rax+4], r8d
        code.put(new byte[]{0x4D, (byte) 0x8B, 0x42, 0x10});                        // mov r8, [r10+0x10]
        code.put(new byte[]{0x4C, (byte) 0x89, 0x40, 0x08});                        // mov [rax+8], r8
        code.put(new byte[]{0x4D, (byte) 0x8B, (byte) 0x83}).putInt(0x98);          // mov r8, [r11+0x98]
        code.put(new byte[]{0x4C, (byte) 0x89, 0x40, 0x10});                        // mov [rax+16], r8
        code.put(new byte[]{0x4D, (byte) 0x8B, 0x42, 0x28});                        // mov r8, [r10+0x28]
        code.put(new byte[]{0x4C, (byte) 0x89, 0x40, 0x18});                        // mov [rax+24], r8
        code.put(new byte[]{0x31, (byte) 0xC0});                                    // xor eax, eax
        code.put((byte) 0xC3);                                                      // ret
        var bytes = new byte[code.position()];
        code.flip().get(bytes);
        return bytes;
    }

    /**
     * Rewrites the module list file whenever the number of loaded modules changes.
     *
     * @param functions the linked functions
     * @param file the module list file
     */
    private record ModuleList(Functions functions, Path file) implements Runnable {
        @Override
        public void run() {
            var count = -1;
            while (true) {
                try {
                    Thread.sleep(MODULES_PERIOD_MILLIS);
                    count = write(count);
                } catch (InterruptedException exception) {
                    return;
                } catch (Throwable throwable) {
                    // the list stays as last written
                }
            }
        }

        /**
         * Writes the list unconditionally.
         *
         * @throws Throwable if a call fails
         */
        void write() throws Throwable {
            write(-1);
        }

        /**
         * Writes the list if the number of modules differs from a count.
         *
         * @param previous the count of the last list written, or {@code -1}
         * @return the count of the modules now loaded
         * @throws Throwable if a call fails
         */
        private int write(int previous) throws Throwable {
            try (var arena = Arena.ofConfined()) {
                var process = MemorySegment.ofAddress(-1L);
                var handles = arena.allocate(ADDRESS, 1024);
                var needed = arena.allocate(JAVA_INT);
                var ok = (int) functions.enumModules().invokeExact(process, handles, (int) handles.byteSize(), needed);
                if (ok == 0) {
                    return previous;
                }
                var count = (int) Math.min(1024, needed.get(JAVA_INT, 0) / ADDRESS.byteSize());
                if (count == previous) {
                    return count;
                }
                var lines = new StringBuilder();
                // MODULEINFO: lpBaseOfDll, SizeOfImage, EntryPoint
                var information = arena.allocate(24, 8);
                var name = arena.allocate(2L * 1024);
                for (var i = 0; i < count; i++) {
                    var module = handles.getAtIndex(ADDRESS, i);
                    var found = (int) functions.moduleInformation().invokeExact(process, module, information, 24);
                    var length = (int) functions.moduleName().invokeExact(module, name, 1024);
                    var path = new String(name.asSlice(0, 2L * length).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
                    lines.append(String.format(Locale.ROOT, "%016x %08x %s%n", module.address(),
                            found != 0 ? information.get(JAVA_INT, 8) : 0, path));
                }
                var temporary = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temporary, lines);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return count;
            }
        }
    }

    /**
     * {@return the files a crash on a thread writes, the dump and the text}
     *
     * @param directory the dump directory
     * @param pid the process id
     * @param tid the thread id
     */
    public static Path[] files(Path directory, long pid, long tid) {
        var stem = "native-crash-" + pid + "-" + tid;
        return new Path[]{directory.resolve(stem + ".dmp"), directory.resolve(stem + ".txt")};
    }

    /**
     * The JVM thread that writes the dump of a crash the filter signals.
     *
     * @param functions the linked functions
     * @param directory the dump directory
     * @param page the filter's page, holding the slots
     * @param crashed the event the filter signals
     * @param released the event that releases the faulting thread
     */
    private record Watcher(Functions functions, Path directory, MemorySegment page, MemorySegment crashed,
                           MemorySegment released) implements Runnable {
        @Override
        public void run() {
            try {
                // INFINITE
                var ignored = (int) functions.waitForObject().invokeExact(crashed, -1);
            } catch (Throwable throwable) {
                return;
            }
            try {
                write();
            } catch (Throwable throwable) {
                System.err.println("native crash: the dump failed: " + throwable);
            } finally {
                try {
                    var ignored = (int) functions.setEvent().invokeExact(released);
                } catch (Throwable ignored) {
                    // the faulting thread stops waiting after its timeout
                }
            }
        }

        /**
         * Writes the dump and the text of the crash.
         *
         * @throws Throwable if a call fails
         */
        private void write() throws Throwable {
            var f = functions;
            var pid = ProcessHandle.current().pid();
            var tid = page.get(JAVA_INT, THREAD_SLOT);
            var pointers = page.get(JAVA_LONG, POINTERS_SLOT);
            var both = files(directory, pid, Integer.toUnsignedLong(tid));
            var record = MemorySegment.ofAddress(pointers).reinterpret(16).get(ADDRESS, 0).reinterpret(152);
            var code = record.get(JAVA_INT, 0);
            var address = record.get(JAVA_LONG, 16);
            var parameters = record.get(JAVA_INT, 24);
            var text = new StringBuilder();
            text.append(String.format(Locale.ROOT, "native crash: exception 0x%08x at 0x%016x on thread %d of process %d",
                    code, address, Integer.toUnsignedLong(tid), pid));
            if (code == 0xC0000005 && parameters >= 2) {
                var kind = record.get(JAVA_LONG, 32);
                text.append(String.format(Locale.ROOT, ", %s 0x%016x",
                        kind == 0 ? "reading" : kind == 1 ? "writing" : "executing", record.get(JAVA_LONG, 40)));
            }
            try (var arena = Arena.ofConfined()) {
                text.append(", in ").append(module(arena, address));
                var path = arena.allocateFrom(both[0].toString(), StandardCharsets.UTF_16LE);
                // GENERIC_WRITE, no sharing, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL
                var file = (MemorySegment) f.createFile().invokeExact(path, 0x40000000, 0, MemorySegment.NULL, 2,
                        0x80, MemorySegment.NULL);
                if (file.address() == -1L) {
                    text.append("; the dump file could not be created");
                } else {
                    // MINIDUMP_EXCEPTION_INFORMATION is packed to 4 bytes: ThreadId, ExceptionPointers, ClientPointers
                    var information = arena.allocate(16, 4);
                    information.set(JAVA_INT, 0, tid);
                    information.set(JAVA_LONG_UNALIGNED, 4, pointers);
                    information.set(JAVA_INT, 12, 0);
                    var written = (int) f.writeDump().invokeExact(MemorySegment.ofAddress(-1L), (int) pid, file,
                            DUMP_TYPE, information, MemorySegment.NULL, MemorySegment.NULL);
                    var ignored = (int) f.closeHandle().invokeExact(file);
                    text.append(written != 0 ? "; dump " + both[0] : "; MiniDumpWriteDump failed");
                }
            }
            var line = text.toString();
            Files.writeString(both[1], line + System.lineSeparator());
            System.err.println(line);
            System.err.flush();
        }

        /**
         * {@return the path of the module holding an address and the offset in it, or {@code "no module"}}
         */
        private String module(Arena arena, long address) throws Throwable {
            var handle = arena.allocate(ADDRESS);
            // GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT
            var found = (int) functions.moduleHandle().invokeExact(0x4 | 0x2, MemorySegment.ofAddress(address), handle);
            if (found == 0) {
                return "no module";
            }
            var name = arena.allocate(2L * 1024);
            var length = (int) functions.moduleName().invokeExact(handle.get(ADDRESS, 0), name, 1024);
            var module = new String(name.asSlice(0, 2L * length).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
            return module + "+0x" + Long.toHexString(address - handle.get(ADDRESS, 0).address());
        }
    }

    /**
     * The linked Windows functions, and the addresses the generated filter calls.
     */
    record Functions(MethodHandle setFilter, MethodHandle createEvent, MethodHandle setEvent,
                     MethodHandle waitForObject, MethodHandle virtualAlloc, MethodHandle flushInstructionCache,
                     MethodHandle createFile, MethodHandle closeHandle, MethodHandle writeDump,
                     MethodHandle moduleHandle, MethodHandle moduleName, MethodHandle addVectoredHandler,
                     MethodHandle createFileMapping, MethodHandle mapView, MethodHandle enumModules,
                     MethodHandle moduleInformation, long threadIdAddress, long setEventAddress, long waitAddress) {
        /**
         * Links the functions.
         *
         * @return the functions
         */
        static Functions link() {
            var linker = Linker.nativeLinker();
            var arena = Arena.global();
            var kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            var dbghelp = SymbolLookup.libraryLookup("dbghelp", arena);
            var setEvent = kernel32.findOrThrow("SetEvent");
            var wait = kernel32.findOrThrow("WaitForSingleObject");
            return new Functions(
                    linker.downcallHandle(kernel32.findOrThrow("SetUnhandledExceptionFilter"),
                            FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    linker.downcallHandle(kernel32.findOrThrow("CreateEventW"),
                            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)),
                    linker.downcallHandle(setEvent, FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                    linker.downcallHandle(wait, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
                    linker.downcallHandle(kernel32.findOrThrow("VirtualAlloc"),
                            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT)),
                    linker.downcallHandle(kernel32.findOrThrow("FlushInstructionCache"),
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG)),
                    linker.downcallHandle(kernel32.findOrThrow("CreateFileW"), FunctionDescriptor.of(ADDRESS, ADDRESS,
                            JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)),
                    linker.downcallHandle(kernel32.findOrThrow("CloseHandle"), FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                    linker.downcallHandle(dbghelp.findOrThrow("MiniDumpWriteDump"), FunctionDescriptor.of(JAVA_INT,
                            ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
                    linker.downcallHandle(kernel32.findOrThrow("GetModuleHandleExW"),
                            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
                    linker.downcallHandle(kernel32.findOrThrow("GetModuleFileNameW"),
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
                    linker.downcallHandle(kernel32.findOrThrow("AddVectoredExceptionHandler"),
                            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS)),
                    linker.downcallHandle(kernel32.findOrThrow("CreateFileMappingW"),
                            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)),
                    linker.downcallHandle(kernel32.findOrThrow("MapViewOfFile"),
                            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)),
                    linker.downcallHandle(kernel32.findOrThrow("K32EnumProcessModules"),
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)),
                    linker.downcallHandle(kernel32.findOrThrow("K32GetModuleInformation"),
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT)),
                    kernel32.findOrThrow("GetCurrentThreadId").address(), setEvent.address(), wait.address());
        }
    }

    /**
     * Crashes a JVM on purpose, for the test of the filter: installs it with the directory given as the first
     * argument, then starts a native thread whose start address is not code ({@code 0x10}), which ends the process
     * with an access violation. With {@code --exit-filter} instead of a directory, the process filter is
     * {@code ExitProcess}, which ends the process with the low 32 bits of the {@code EXCEPTION_POINTERS} address.
     *
     * @param arguments the dump directory, or {@code --exit-filter}
     * @throws Throwable if the setup fails
     */
    public static void main(String... arguments) throws Throwable {
        var linker = Linker.nativeLinker();
        var kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        if (arguments[0].equals("--exit-filter")) {
            var setFilter = linker.downcallHandle(kernel32.findOrThrow("SetUnhandledExceptionFilter"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS));
            var ignored = (MemorySegment) setFilter.invokeExact(kernel32.findOrThrow("ExitProcess"));
        } else {
            install(Path.of(arguments[0]));
        }
        var createThread = linker.downcallHandle(kernel32.findOrThrow("CreateThread"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        var thread = (MemorySegment) createThread.invokeExact(MemorySegment.NULL, 0L, MemorySegment.ofAddress(0x10),
                MemorySegment.NULL, 0, MemorySegment.NULL);
        if (thread.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("CreateThread failed");
        }
        Thread.sleep(30_000);
        System.exit(3);
    }
}
