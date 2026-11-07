package com.github.auties00.smalt.testkit.jit;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts, repeats and meters the calls of Vector API kernels that the {@link KernelMeterAgent} instruments in the
 * equivalence tests a {@link KernelGuardChild} runs.
 *
 * <p>Every instrumented call site runs {@code begin}, then {@code mark}, the kernel call and {@code end}, and calls
 * the kernel again while {@code end} returns {@code true}. Only calls whose receiver (or, for a static method, whose
 * owner) is one of the {@linkplain #kernels(Set) kernel classes} take part; a call on a scalar twin runs once,
 * unmetered. The allocation of a metered call is the difference of the thread's allocated-bytes counter read in
 * {@code mark}, the last action before the kernel call, and in {@code end}, the first action after it, so it counts
 * exactly what the kernel allocated. The mode decides what a metered call does:
 * <ul>
 *   <li>{@link Mode#COUNT}: counts the calls of each kernel entry point;</li>
 *   <li>{@link Mode#WARM}: repeats each call until its entry point has been called about {@link #target()} times in
 *       the round, restoring the receiver and the arguments ({@link KernelState}) before each repetition, so that the
 *       test sees the result of a single call;</li>
 *   <li>{@link Mode#MEASURE}: records each call's allocation.</li>
 * </ul>
 * The driver is single-threaded: calls from any other thread run once, unmetered.
 */
public final class KernelMeter {
    /** What a metered call does. */
    public enum Mode {
        /** Calls run once, unmetered and uncounted. */
        OFF,
        /** Calls run once and are counted per entry point. */
        COUNT,
        /** Calls are repeated until their entry point reaches its target count. */
        WARM,
        /** Calls run once and their allocation is recorded. */
        MEASURE
    }

    /** A call site: the test method calling a kernel, and the method it names. */
    public record Site(String caller, int line, String owner, String name, String descriptor, boolean isStatic) {
        /** {@return the site as {@code Caller.method(File:line) -> owner.name}} */
        public String describe() {
            return caller + ":" + line + " -> " + owner.substring(owner.lastIndexOf('.') + 1) + "." + name + descriptor;
        }
    }

    /** The calls of one kernel entry point. */
    public static final class Entry {
        private final String kernel;
        private final String method;
        private final String declaring;
        private boolean skipped;
        private long counted;
        private long calls;
        private long allocatingCalls;
        private long bytes;
        private long maxBytes;
        private String firstAllocatingSite;
        private String unrestorable;
        private int warmTarget;
        private long warmCalls;

        private Entry(String kernel, String method, String declaring) {
            this.kernel = kernel;
            this.method = method;
            this.declaring = declaring;
        }

        /** {@return the kernel class's binary name} */
        public String kernel() {
            return kernel;
        }

        /** {@return the method name and descriptor} */
        public String method() {
            return method;
        }

        /** {@return the binary name of the class declaring the method the calls run, the kernel or a superclass} */
        public String declaring() {
            return declaring;
        }

        /** {@return whether the calls are not metered: the method needs vectors the platform does not have} */
        public boolean skipped() {
            return skipped;
        }

        /** {@return the number of calls the counting round made} */
        public long counted() {
            return counted;
        }

        /** {@return the number of measured calls} */
        public long calls() {
            return calls;
        }

        /** {@return the number of measured calls that allocated} */
        public long allocatingCalls() {
            return allocatingCalls;
        }

        /** {@return the bytes the measured calls allocated} */
        public long bytes() {
            return bytes;
        }

        /** {@return the largest allocation of a measured call, in bytes} */
        public long maxBytes() {
            return maxBytes;
        }

        /** {@return the site of the first measured call that allocated, or {@code null}} */
        public String firstAllocatingSite() {
            return firstAllocatingSite;
        }

        /** {@return why a call could not be repeated, or {@code null} when every call could} */
        public String unrestorable() {
            return unrestorable;
        }

        /** {@return the calls the warm-up rounds made, repetitions included} */
        public long warmCalls() {
            return warmCalls;
        }
    }

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final List<Site> SITES = new ArrayList<>();
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<String, String> DECLARING = new HashMap<>();

    private static volatile Mode mode = Mode.OFF;
    private static volatile Set<String> kernels = Set.of();
    private static volatile Set<String> unmetered = Set.of();
    private static Thread driver;
    private static int target = 12_000;
    private static int maxRepetitions = 20_000;
    private static int multiplier = 1;

    private static long siteCalls;
    private static Entry current;
    private static String currentSite;
    private static KernelState state;
    private static int repetitions;
    private static long start;

    private KernelMeter() {
        throw new AssertionError();
    }

    /**
     * Registers a call site; the agent calls this once per instrumented invoke.
     *
     * @param site the call site
     * @return the site's id, the argument the instrumented code passes to {@code begin}
     */
    public static synchronized int register(Site site) {
        SITES.add(site);
        return SITES.size() - 1;
    }

    /**
     * Sets the binary names of the classes whose calls are metered.
     *
     * @param names the kernel classes
     */
    public static void kernels(Set<String> names) {
        kernels = Set.copyOf(names);
    }

    /**
     * Sets the calls that are not metered: methods, as {@code binary.Class.name(descriptor)} of the declaring class,
     * and every call on a kernel class, as {@code class binary.Class}.
     *
     * @param methods the methods and classes
     */
    public static void unmetered(Set<String> methods) {
        unmetered = Set.copyOf(methods);
    }

    /**
     * Sets the mode and the thread whose calls are metered.
     *
     * @param newMode the mode
     */
    public static void mode(Mode newMode) {
        driver = Thread.currentThread();
        mode = newMode;
        current = null;
        state = null;
        repetitions = 0;
    }

    /** {@return the calls per entry point and warm-up round the repetitions aim at} */
    public static int target() {
        return target;
    }

    /**
     * Sets the calls per entry point and warm-up round the repetitions aim at, and the most repetitions of one call.
     *
     * @param calls the target, at least 1
     * @param cap the most repetitions of one call, at least 1
     */
    public static void target(int calls, int cap) {
        target = calls;
        maxRepetitions = cap;
    }

    /**
     * Sets the factor the warm-up repetitions are multiplied by, for the extra warm-up rounds that bring seldom-called
     * helpers to C2.
     *
     * @param factor the factor, at least 1
     */
    public static void multiplier(int factor) {
        multiplier = factor;
    }

    /** Clears the measurements of every entry point, before a measured round. */
    public static void clearMeasurements() {
        for (var entry : ENTRIES.values()) {
            entry.calls = 0;
            entry.allocatingCalls = 0;
            entry.bytes = 0;
            entry.maxBytes = 0;
            entry.firstAllocatingSite = null;
        }
    }

    /** {@return the calls the instrumented sites made on the driver thread, kernel or not, metered or not} */
    public static long siteCalls() {
        return siteCalls;
    }

    /** {@return the entry points seen, in order of first call} */
    public static List<Entry> entries() {
        return List.copyOf(ENTRIES.values());
    }

    /**
     * Starts a call at an instrumented site: decides whether it is metered and, in {@link Mode#WARM}, how often it
     * repeats, and captures the state to restore before each repetition.
     *
     * @param receiver the receiver, or {@code null} for a static method
     * @param arguments the arguments, primitives boxed
     * @param siteId the site's id
     */
    public static void begin(Object receiver, Object[] arguments, int siteId) {
        current = null;
        state = null;
        repetitions = 0;
        var active = mode;
        if (active == Mode.OFF || Thread.currentThread() != driver) {
            return;
        }
        siteCalls++;
        var site = SITES.get(siteId);
        var kernel = receiver == null ? site.owner() : receiver.getClass().getName();
        if (!kernels.contains(kernel)) {
            return;
        }
        var entry = ENTRIES.get(kernel + "." + site.name() + site.descriptor());
        if (entry == null) {
            var declaring = receiver == null ? kernel : declaring(receiver.getClass(), site);
            if (declaring == null || !declaring.startsWith("com.github.auties00.smalt.")) {
                // a method only the JDK declares (getClass, hashCode, toString) runs no kernel code
                return;
            }
            entry = new Entry(kernel, site.name() + site.descriptor(), declaring);
            entry.skipped = unmetered.contains(entry.declaring + "." + entry.method)
                    || unmetered.contains("class " + kernel);
            ENTRIES.put(kernel + "." + site.name() + site.descriptor(), entry);
        }
        if (entry.skipped) {
            return;
        }
        current = entry;
        currentSite = site.describe();
        switch (active) {
            case COUNT -> entry.counted++;
            case WARM -> {
                // the extra rounds also repeat every call at least 8 times the factor, so that a helper only rare
                // calls reach gets calls of its own
                var floor = multiplier > 1 ? 8L * multiplier : 1L;
                var wanted = entry.counted == 0 ? 1 : (int) Math.min((long) maxRepetitions * multiplier,
                        Math.max(floor, ((long) target * multiplier + entry.counted - 1) / entry.counted));
                if (wanted > 1) {
                    var captured = KernelState.capture(receiver, arguments);
                    if (captured.restorable()) {
                        state = captured;
                        repetitions = wanted - 1;
                    } else if (entry.unrestorable == null) {
                        entry.unrestorable = currentSite + ": " + captured.reason();
                    }
                }
                entry.warmTarget = wanted;
            }
            default -> {
            }
        }
    }

    /**
     * Returns the binary name of the type declaring the method a virtual call at a site runs: the nearest superclass
     * declaring it, else the interface whose default method it is, or {@code null} if none is found.
     */
    private static String declaring(Class<?> type, Site site) {
        var key = type.getName() + "#" + site.name() + site.descriptor();
        var known = DECLARING.get(key);
        if (known != null) {
            return known.isEmpty() ? null : known;
        }
        String found = null;
        for (var owner = type; owner != null && found == null; owner = owner.getSuperclass()) {
            found = declares(owner, site) ? owner.getName() : null;
        }
        for (var owner = type; owner != null && found == null; owner = owner.getSuperclass()) {
            found = defaultMethod(owner, site);
        }
        DECLARING.put(key, found == null ? "" : found);
        return found;
    }

    private static String defaultMethod(Class<?> type, Site site) {
        for (var face : type.getInterfaces()) {
            if (declares(face, site)) {
                return face.getName();
            }
            var inherited = defaultMethod(face, site);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private static boolean declares(Class<?> type, Site site) {
        for (var method : type.getDeclaredMethods()) {
            if (method.getName().equals(site.name()) && !java.lang.reflect.Modifier.isAbstract(method.getModifiers())
                    && java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .descriptorString().equals(site.descriptor())) {
                return true;
            }
        }
        return false;
    }

    /** Reads the allocation counter; the instrumented code calls this just before the kernel. */
    public static void mark() {
        if (current != null) {
            start = THREADS.getCurrentThreadAllocatedBytes();
        }
    }

    /**
     * Ends a kernel call: records its allocation and decides whether to call again.
     *
     * @return {@code true} if the site must call the kernel again (the state has been restored)
     */
    public static boolean end() {
        var entry = current;
        if (entry == null) {
            return false;
        }
        var allocated = THREADS.getCurrentThreadAllocatedBytes() - start;
        if (mode == Mode.MEASURE) {
            entry.calls++;
            if (allocated != 0) {
                entry.allocatingCalls++;
                entry.bytes += allocated;
                entry.maxBytes = Math.max(entry.maxBytes, allocated);
                if (entry.firstAllocatingSite == null) {
                    entry.firstAllocatingSite = currentSite;
                }
            }
        } else if (mode == Mode.WARM) {
            entry.warmCalls++;
            if (repetitions > 0) {
                repetitions--;
                state.restore();
                return true;
            }
        }
        current = null;
        state = null;
        return false;
    }
}
