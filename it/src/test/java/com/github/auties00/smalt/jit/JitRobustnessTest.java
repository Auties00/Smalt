package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the Vector API kernels of the video codecs, WebP, capture, the image operations, the
 * JPEG, PNG and GIF codecs and the audio codecs and processing against the ways the JIT compilers
 * leave Vector API operations on their allocating Java fallback: in some JVMs, and more often in
 * multi-threaded and mixed-codec ones, a kernel that is too large, inlined before it was profiled,
 * or written with the shared convenience methods ends up allocating tens of megabytes per frame
 * for as long as the process lives.
 *
 * <p>{@link Bytecode} checks the source rules statically on the compiled classes, deterministically:
 * per-shape operations only (no convenience method such as {@code add} or {@code fma}, no scalar
 * operand except a shift's count, which must be an int rather than a vector of counts), no
 * vector-typed helpers, no {@code reinterpretAs} methods, an exact receiver for every
 * {@code intoArray} and scalar-operand {@code lanewise} (see {@link Receivers}), in the packages
 * that comply an exact receiver for every call on a vector or mask carried into or around a loop,
 * no kernel over 2000 bytecodes, and no use of 256-bit or wider vectors outside code that runs only
 * where {@code Simd.WIDE} holds (see {@link Widths}). {@link Runtime} runs representative
 * workloads in fresh JVMs ({@link JitWorkload}), several at once so that the compilers are busy as
 * in a real application, and requires every workload's steady-state allocation per frame to
 * settle under its bound and no {@code COMPILE SKIPPED} line for a method of this module. The
 * video bounds are about ten times the allocation of a healthy run at the workloads' 640x360 size
 * (a decoder's per-frame bookkeeping, an encoder's packets) and far below a failing one (hundreds
 * of kilobytes to megabytes); the audio bounds are three to ten times a healthy run's few hundred
 * bytes per packet (nothing for the resampler and the voice processor), against megabytes per
 * packet with the intrinsics disabled; the WebP bounds, and the still-image bounds (1.1 to 1.2
 * times), sit above the images'
 * own buffers, which are the same on every run (about 34 MB per frame of the 512x512 animated
 * sticker, against 44 to 135 MB when a VP8 kernel runs its fallback; 15.5 MB per JPEG send
 * against 1.9 GB). Every bound sits below the allocation of a kernel still running on the first
 * compilation tier, so a workload settles only once its kernels are compiled by C2. A child JVM
 * that fails is run once more and fails the test only if the second one fails too, since the
 * compilers' decisions vary from JVM to JVM. The workloads decoding streams made by ffmpeg (x264
 * with B-frames, progressive, MBAFF, 10-bit and 4:2:2 MBAFF, x265 HEVC, libvpx VP8 and VP9 with inter frames, libaom
 * AV1 with tiles, LAME MP3) run only when ffmpeg is installed. The AV1 decoder runs in the
 * decoders' child and the VP9 encoder in the
 * encoders' child, but neither runs in the child mixing every workload, whose compile queue their
 * kernels would overload; the AV1 encoder, whose seldom-called decoder kernels compile late, runs
 * in a child of its own, and the real-time configurations of a call (the H.264 real-time encoder
 * and single-threaded decoder, VP8, AV1 and HEVC) in another. On x86 the children run with 256-bit
 * vectors; {@code -Djit.childArgs=-XX:MaxVectorSize=16} gives them the 128-bit vectors of ARM NEON,
 * where the aarch64 CI jobs run the guard natively.
 */
@DisplayName("JIT robustness of the Vector API kernels")
class JitRobustnessTest {
    private static final List<String> GUARDED_PACKAGES = List.of("video/h264/", "video/hevc/", "video/mpeg4/", "video/vp8/", "video/vp9/", "video/av1/", "image/webp/",
            "capture/", "image/ops/", "image/color/", "image/jpeg/", "image/jp2/", "image/png/", "image/gif/", "image/raw/", "internal/quantize/", "internal/plane/", "audio/",
            "video/intra/", "video/prores/", "video/dnxhd/");
    /**
     * The packages whose kernels must also not call a vector or mask carried into or around a loop
     * without casting it again (see {@link Receivers}); a package joins once it complies.
     */
    private static final List<String> CARRIED_PACKAGES = List.of("audio/", "video/mpeg4/", "image/webp/", "capture/",
            "video/h264/", "video/hevc/", "video/vp8/", "video/vp9/", "video/av1/", "image/ops/", "image/color/",
            "internal/quantize/", "internal/plane/");
    private static final Set<String> CONVENIENCE = Set.of("add", "sub", "mul", "div", "min", "max", "and", "or", "not",
            "neg", "abs", "eq", "lt", "fma", "sqrt", "pow");
    private static final Set<String> SHIFTS = Set.of("LSHL", "LSHR", "ASHR", "ROL", "ROR");
    private static final Set<String> VECTOR_TYPES = Set.of("ByteVector", "ShortVector", "IntVector", "LongVector",
            "FloatVector", "DoubleVector", "Vector");
    private static final int MAX_KERNEL_BYTES = 2000;
    private static final List<Path> CLASSES = classRoots();

    /**
     * Returns the class roots ({@code com/github/auties00/smalt} inside each class directory or jar) the
     * lint scans: the {@code jit.classes} system property's entries, or else every main class directory and
     * jar of the test class path, so the guard covers every Smalt artifact.
     */
    private static List<Path> classRoots() {
        var entries = System.getProperty("jit.classes", childClassPath());
        var roots = new ArrayList<Path>();
        for (var entry : entries.split(java.io.File.pathSeparator)) {
            var path = Path.of(entry);
            var name = path.getFileName() == null ? "" : path.getFileName().toString();
            if (name.equals("test-classes") || name.endsWith("-tests.jar")) {
                continue;
            }
            try {
                if (Files.isDirectory(path)) {
                    var root = path.resolve("com").resolve("github").resolve("auties00").resolve("smalt");
                    if (Files.isDirectory(root)) {
                        roots.add(root);
                    }
                } else if (name.endsWith(".jar") && Files.isRegularFile(path)) {
                    var root = java.nio.file.FileSystems.newFileSystem(path).getPath("/com/github/auties00/smalt");
                    if (Files.isDirectory(root)) {
                        roots.add(root);
                    }
                }
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }
        return List.copyOf(roots);
    }

    /** Returns the class path of the child JVMs: the {@code jit.classpath} property, else the test class path. */
    private static String childClassPath() {
        return System.getProperty("jit.classpath",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
    }

    /** Walks every class root. */
    private static Stream<Path> walk(List<Path> roots) throws IOException {
        var streams = new ArrayList<Stream<Path>>();
        for (var root : roots) {
            streams.add(Files.walk(root));
        }
        return streams.stream().flatMap(stream -> stream).onClose(() -> streams.forEach(Stream::close));
    }

    /** Returns a class file's path relative to the class root that holds it. */
    private static Path relativize(Path file) {
        for (var root : CLASSES) {
            if (file.getFileSystem() == root.getFileSystem() && file.startsWith(root)) {
                return root.relativize(file);
            }
        }
        throw new IllegalArgumentException("not under a class root: " + file);
    }

    @Nested
    @DisplayName("bytecode")
    class Bytecode {
        @Test
        @DisplayName("vector kernels use per-shape operations, vector operands, int shift counts and no vector-typed helpers")
        void rules() throws IOException {
            var violations = new ArrayList<String>();
            for (var model : vectorClasses()) {
                for (var method : model.methods()) {
                    violations.addAll(check(model, method));
                }
            }
            assertEquals(List.of(), violations, "Vector API rule violations (see the class javadoc of "
                    + "video.h264.encoder.internal.VectorPixelKernels)");
        }

        @Test
        @DisplayName("kernels use 256-bit or wider vectors only where Simd.WIDE holds")
        void wideSpecies() throws IOException {
            var models = new ArrayList<ClassModel>();
            try (Stream<Path> files = walk(CLASSES)) {
                for (var file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    models.add(ClassFile.of().parse(Files.readAllBytes(file)));
                }
            }
            var widths = new Widths(models);
            assertTrue(widths.wideMethods() >= 50, "found only " + widths.wideMethods()
                    + " methods using 256-bit species under " + CLASSES);
            assertEquals(List.of(), widths.unguarded(), "methods using 256-bit or wider vectors that can run where "
                    + "the platform's vectors are narrower (see Simd.WIDE)");
        }

        private static List<ClassModel> vectorClasses() throws IOException {
            var models = new ArrayList<ClassModel>();
            try (Stream<Path> files = walk(CLASSES)) {
                for (var file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    var relative = relativize(file).toString().replace('\\', '/');
                    if (GUARDED_PACKAGES.stream().noneMatch(relative::startsWith)) {
                        continue;
                    }
                    var model = ClassFile.of().parse(Files.readAllBytes(file));
                    var usesVectors = false;
                    for (var entry : model.constantPool()) {
                        if (entry instanceof ClassEntry type && type.asInternalName().startsWith("jdk/incubator/vector/")) {
                            usesVectors = true;
                        }
                    }
                    if (usesVectors) {
                        models.add(model);
                    }
                }
            }
            assertTrue(models.size() >= 10, "found only " + models.size() + " vector classes under " + CLASSES);
            return models;
        }

        private static List<String> check(ClassModel model, MethodModel method) {
            var violations = new ArrayList<String>();
            var name = model.thisClass().asInternalName().substring(model.thisClass().asInternalName().lastIndexOf('/') + 1)
                    + "." + method.methodName().stringValue();
            var descriptor = method.methodType().stringValue();
            for (var type : VECTOR_TYPES) {
                if (descriptor.contains("Ljdk/incubator/vector/" + type + ";")) {
                    violations.add(name + " takes or returns a " + type);
                }
            }
            var code = method.findAttribute(Attributes.code());
            if (code.isEmpty()) {
                return violations;
            }
            var usesVectors = false;
            // The operators loaded and not yet consumed: a call taking an operator consumes the most recent one,
            // since an operator is loaded just before the operands of its own call.
            var operators = new ArrayList<String>();
            for (var element : code.get()) {
                if (element instanceof FieldInstruction field
                        && field.owner().asInternalName().equals("jdk/incubator/vector/VectorOperators")) {
                    operators.add(field.name().stringValue());
                }
                if (!(element instanceof InvokeInstruction invoke)) {
                    continue;
                }
                var owner = invoke.owner().asInternalName();
                if (!owner.startsWith("jdk/incubator/vector/")) {
                    continue;
                }
                usesVectors = true;
                var parameters = invoke.typeSymbol().parameterList();
                var operator = !parameters.isEmpty() && !operators.isEmpty()
                        && parameters.getFirst().descriptorString().startsWith("Ljdk/incubator/vector/VectorOperators$")
                        ? operators.removeLast() : "";
                var simpleOwner = owner.substring(owner.lastIndexOf('/') + 1);
                if (!VECTOR_TYPES.contains(simpleOwner)) {
                    continue;
                }
                var called = invoke.name().stringValue();
                var scalarOperand = parameters.size() >= 2 && parameters.get(1).isPrimitive();
                if (called.startsWith("reinterpretAs")) {
                    violations.add(name + " calls " + simpleOwner + "." + called + ", which dispatches through a "
                            + "profile every vector shape shares; use reinterpretShape");
                } else if (CONVENIENCE.contains(called)) {
                    violations.add(name + " calls the convenience method " + simpleOwner + "." + called);
                } else if (called.equals("lanewise") && parameters.size() >= 2 && !scalarOperand
                        && SHIFTS.contains(operator)) {
                    violations.add(name + " shifts by a vector of counts in " + simpleOwner + ".lanewise(" + operator
                            + "); pass an int count");
                } else if ((called.equals("lanewise") && scalarOperand && !SHIFTS.contains(operator))
                        || (called.equals("compare") && scalarOperand)
                        || (called.equals("blend") && !parameters.isEmpty() && parameters.get(0).isPrimitive())) {
                    violations.add(name + " passes a scalar operand to " + simpleOwner + "." + called);
                }
            }
            if (usesVectors && code.get().codeLength() > MAX_KERNEL_BYTES) {
                violations.add(name + " has " + code.get().codeLength() + " bytecodes, over " + MAX_KERNEL_BYTES);
            }
            if (usesVectors) {
                var relative = model.thisClass().asInternalName().substring("com/github/auties00/smalt/".length());
                var carried = CARRIED_PACKAGES.stream().anyMatch(relative::startsWith);
                for (var bci : Receivers.inexact(code.get(), carried)) {
                    violations.add(name + " calls " + bci);
                }
            }
            return violations;
        }
    }

    /**
     * Finds the methods that use a 256-bit or wider species and can run where the platform's vectors
     * are narrower, where the JIT compilers leave every operation of that species on its allocating
     * Java fallback.
     *
     * <p>Gathers and scatters (loads and stores through an index map) count as wide too: they compile only
     * on hardware with gather instructions, and every platform with 256-bit vectors has them (AVX2,
     * SVE), while ARM NEON does not.
     *
     * <p>A method uses a wide species when it reads {@code SPECIES_256}, {@code SPECIES_512},
     * {@code S_256_BIT} or {@code S_512_BIT}, or a static field initialized from one of them; a field
     * whose initializer also depends on the platform's width (it reads {@code SPECIES_PREFERRED},
     * {@code Simd.WIDE}, {@code vectorBitSize}, {@code length} or {@code preferredBits}, or another
     * such field) adapts to the platform and does not count. Such a method is guarded when it reads
     * {@code Simd.WIDE} (or a static boolean initialized from it) itself, when every method calling
     * it is guarded, or when it is an instance method of a class that only guarded methods create; a
     * class initializer counts as guarded, since the constants it builds cost nothing per call. A
     * method creating a class counts as guarded the same way; a static method that nothing in the
     * library calls is a factory for tests and does not count as a creator.
     */
    private static final class Widths {
        private static final Set<String> WIDE_CONSTANTS = Set.of("SPECIES_256", "SPECIES_512", "S_256_BIT",
                "S_512_BIT");
        private static final String SIMD = "com/github/auties00/smalt/internal/simd/Simd";
        private static final String LIBRARY = "com/github/auties00/smalt/";

        private final java.util.Map<String, ClassModel> classes = new java.util.HashMap<>();
        private final java.util.Map<String, MethodModel> methods = new java.util.HashMap<>();
        private final java.util.Map<String, String> owners = new java.util.HashMap<>();
        private final Set<String> wideFields = new java.util.HashSet<>();
        private final Set<String> adaptiveFields = new java.util.HashSet<>();
        private final Set<String> guardFields = new java.util.HashSet<>();
        private final java.util.Map<String, Set<String>> callers = new java.util.HashMap<>();
        private final java.util.Map<String, Set<String>> creators = new java.util.HashMap<>();
        private final java.util.Map<String, Set<String>> wideUses = new java.util.TreeMap<>();
        private final Set<String> guards = new java.util.HashSet<>();
        private final java.util.Map<String, Boolean> safe = new java.util.HashMap<>();
        private final java.util.Map<String, Boolean> selecting = new java.util.HashMap<>();
        private final java.util.Map<String, Boolean> gated = new java.util.HashMap<>();

        Widths(List<ClassModel> models) {
            for (var model : models) {
                var owner = model.thisClass().asInternalName();
                classes.put(owner, model);
                for (var method : model.methods()) {
                    var key = key(owner, method.methodName().stringValue(), method.methodType().stringValue());
                    methods.put(key, method);
                    owners.put(key, owner);
                }
            }
            // A field can be initialized from a field of a class initialized later: repeat until stable.
            for (var round = 0; round < 4; round++) {
                for (var model : models) {
                    classifyFields(model);
                }
            }
            for (var model : models) {
                var owner = model.thisClass().asInternalName();
                for (var method : model.methods()) {
                    scan(owner, method);
                }
            }
        }

        /** Classifies the static fields a class initializer assigns, one assignment at a time. */
        private void classifyFields(ClassModel model) {
            for (var method : model.methods()) {
                if (!method.methodName().equalsString("<clinit>") || method.code().isEmpty()) {
                    continue;
                }
                boolean wide = false, adaptive = false, guard = false;
                for (var element : method.code().get()) {
                    if (element instanceof FieldInstruction field
                            && field.opcode() == java.lang.classfile.Opcode.GETSTATIC) {
                        var name = key(field);
                        if (wideConstant(field) || wideFields.contains(name)) {
                            wide = true;
                        }
                        if (field.name().equalsString("SPECIES_PREFERRED") || adaptiveFields.contains(name)) {
                            adaptive = true;
                        }
                        if (simdWide(field) || guardFields.contains(name)) {
                            adaptive = true;
                            guard = true;
                        }
                    } else if (element instanceof InvokeInstruction invoke) {
                        var name = invoke.name().stringValue();
                        if (name.equals("vectorBitSize") || name.equals("length") || name.equals("preferredBits")) {
                            adaptive = true;
                        }
                    } else if (element instanceof FieldInstruction field
                            && field.opcode() == java.lang.classfile.Opcode.PUTSTATIC) {
                        var name = key(field);
                        if (guard && field.typeSymbol().descriptorString().equals("Z")) {
                            guardFields.add(name);
                        }
                        if (adaptive) {
                            adaptiveFields.add(name);
                            wideFields.remove(name);
                        } else if (wide && !adaptiveFields.contains(name)) {
                            wideFields.add(name);
                        }
                        wide = false;
                        adaptive = false;
                        guard = false;
                    }
                }
            }
        }

        /** Records a method's wide species, guard, calls and object creations. */
        private void scan(String owner, MethodModel method) {
            var self = key(owner, method.methodName().stringValue(), method.methodType().stringValue());
            if (method.code().isEmpty()) {
                return;
            }
            var uses = new java.util.TreeSet<String>();
            for (var element : method.code().get()) {
                switch (element) {
                    case FieldInstruction field when field.opcode() == java.lang.classfile.Opcode.GETSTATIC -> {
                        if (wideConstant(field)) {
                            uses.add(simple(field.owner().asInternalName()) + "." + field.name().stringValue());
                        } else if (wideFields.contains(key(field))) {
                            uses.add(field.name().stringValue());
                        }
                        if (simdWide(field) || guardFields.contains(key(field))) {
                            guards.add(self);
                        }
                    }
                    case InvokeInstruction invoke when gathers(invoke) ->
                            uses.add(simple(invoke.owner().asInternalName()) + "." + invoke.name().stringValue()
                                    + " with an index map");
                    case InvokeInstruction invoke when invoke.owner().asInternalName().startsWith(LIBRARY) -> {
                        var callee = resolve(invoke.owner().asInternalName(), invoke.name().stringValue(),
                                invoke.typeSymbol().descriptorString());
                        if (callee != null) {
                            callers.computeIfAbsent(callee, ignored -> new java.util.HashSet<>()).add(self);
                        }
                    }
                    case java.lang.classfile.instruction.InvokeDynamicInstruction indy -> {
                        for (var argument : indy.bootstrapArgs()) {
                            if (argument instanceof java.lang.constant.DirectMethodHandleDesc handle
                                    && handle.owner().descriptorString().startsWith("L" + LIBRARY)) {
                                var target = handle.owner().descriptorString();
                                var callee = resolve(target.substring(1, target.length() - 1), handle.methodName(),
                                        handle.lookupDescriptor());
                                if (callee != null) {
                                    callers.computeIfAbsent(callee, ignored -> new java.util.HashSet<>()).add(self);
                                }
                            }
                        }
                    }
                    case java.lang.classfile.instruction.NewObjectInstruction created
                            when created.className().asInternalName().startsWith(LIBRARY) ->
                            creators.computeIfAbsent(created.className().asInternalName(),
                                    ignored -> new java.util.HashSet<>()).add(self);
                    default -> {
                    }
                }
            }
            if (!uses.isEmpty() && !method.methodName().equalsString("<clinit>")) {
                wideUses.put(self, uses);
            }
        }

        /** Returns the number of methods using a wide species. */
        int wideMethods() {
            return wideUses.size();
        }

        /** Returns the unguarded methods using a wide species, with the species they use. */
        List<String> unguarded() {
            var found = new ArrayList<String>();
            wideUses.forEach((method, uses) -> {
                if (!safe(method)) {
                    found.add(simple(owners.get(method)) + "." + method.substring(method.indexOf('.') + 1,
                            method.indexOf('(')) + " uses " + uses);
                }
            });
            return found;
        }

        /** Returns whether a method using a wide species runs only where {@code Simd.WIDE} holds. */
        private boolean safe(String method) {
            return guarded(method, true);
        }

        /**
         * Returns whether a method runs only where {@code Simd.WIDE} holds: it reads the guard, every
         * caller is guarded, or it is an instance method of a gated class; with {@code initializers},
         * a class initializer counts as guarded too.
         */
        private boolean guarded(String method, boolean initializers) {
            var memo = initializers ? safe : selecting;
            var known = memo.get(method);
            if (known != null) {
                return known;
            }
            // Optimistic for recursion: a cycle of callers is guarded when its other callers are.
            memo.put(method, true);
            var result = guards.contains(method) || (initializers && method.contains(".<clinit>("));
            if (!result) {
                var calling = callers.getOrDefault(method, Set.of());
                result = !calling.isEmpty() && calling.stream().allMatch(caller -> guarded(caller, initializers));
            }
            if (!result) {
                var model = methods.get(method);
                result = model != null && !model.flags().has(java.lang.reflect.AccessFlag.STATIC)
                        && gated(owners.get(method));
            }
            memo.put(method, result);
            return result;
        }

        /** Returns whether a class is created only where {@code Simd.WIDE} holds. */
        private boolean gated(String owner) {
            var known = gated.get(owner);
            if (known != null) {
                return known;
            }
            gated.put(owner, false);
            var result = true;
            var sites = creators.getOrDefault(owner, Set.of());
            for (var site : sites) {
                var model = methods.get(site);
                var testFactory = model != null && model.flags().has(java.lang.reflect.AccessFlag.STATIC)
                        && !model.methodName().equalsString("<clinit>") && !callers.containsKey(site);
                if (!testFactory && !guarded(site, false)) {
                    result = false;
                    break;
                }
            }
            gated.put(owner, result);
            return result;
        }

        /** Returns the key of the method a call reaches, looking up the superclasses, or {@code null}. */
        private String resolve(String owner, String name, String descriptor) {
            var type = owner;
            while (type != null && classes.containsKey(type)) {
                var candidate = key(type, name, descriptor);
                if (methods.containsKey(candidate)) {
                    return candidate;
                }
                type = classes.get(type).superclass().map(entry -> entry.asInternalName()).orElse(null);
            }
            return null;
        }

        /** Returns whether a call is a gather or scatter: a load or store through an {@code int[]} index map. */
        private static boolean gathers(InvokeInstruction invoke) {
            var name = invoke.name().stringValue();
            if (!invoke.owner().asInternalName().startsWith("jdk/incubator/vector/")
                    || !(name.equals("fromArray") || name.equals("intoArray"))) {
                return false;
            }
            var parameters = invoke.typeSymbol().parameterList();
            for (var i = 2; i < parameters.size(); i++) {
                if (parameters.get(i).descriptorString().equals("[I")
                        && parameters.get(i - 1).descriptorString().equals("I")) {
                    return true;
                }
            }
            return false;
        }

        private static boolean wideConstant(FieldInstruction field) {
            return field.owner().asInternalName().startsWith("jdk/incubator/vector/")
                    && WIDE_CONSTANTS.contains(field.name().stringValue());
        }

        private static boolean simdWide(FieldInstruction field) {
            return field.owner().asInternalName().equals(SIMD) && field.name().equalsString("WIDE");
        }

        private static String key(FieldInstruction field) {
            return field.owner().asInternalName() + "." + field.name().stringValue();
        }

        private static String key(String owner, String name, String descriptor) {
            return owner + "." + name + descriptor;
        }

        private static String simple(String owner) {
            return owner.substring(owner.lastIndexOf('/') + 1);
        }
    }

    /**
     * Finds the calls of Vector API methods that need an exact receiver and may not get one.
     *
     * <p>{@code intoArray} and the scalar-operand {@code lanewise} are final in the abstract vector
     * classes and call abstract methods on the vector itself; unless the JIT compiler knows the
     * vector's concrete class, it dispatches them through a type profile that every vector shape of
     * the program shares, and in a program using three or more shapes it cannot inline them, so the
     * vector is boxed. A value has a known class when a per-shape method returned it (their
     * declared return type is the final vector class), when it is a constant, or when a
     * {@code Class.cast} through a constant {@code Class} produced it: the image kernels cast
     * loads and conversions with {@code INT_VECTOR.cast(v)}, where the static final
     * {@code INT_VECTOR} holds {@code species.vectorType()}, a final class, so the compiler knows the
     * exact class from the cast alone, without a profile; a conversion, a reinterpretation or a load
     * returns the abstract type. A vector or mask carried into a loop, or around it from one
     * iteration to the next, needs an exact receiver for every call: the compiler parses a loop
     * before its back edges, so the value reaches the loop's calls through a merge typed with the
     * declared abstract class whatever its stores were, and without a profile (one compiled before
     * profiling, or without {@code UseTypeProfile}) the call stays virtual and the vector boxed;
     * kernels cast such a value again where it receives a call. The analysis runs the method's
     * bytecode once in order over a symbolic stack, taking each local variable's most recent store
     * and treating a load as carried when it is inside a loop and that store precedes the loop's
     * head, or after a loop and that store is inside it, which matches the straight-line kernels and
     * their counted loops.
     */
    private static final class Receivers {
        private static final Set<String> EXACT = Set.of("lanewise", "blend", "rearrange", "selectFrom", "slice",
                "unslice", "compress", "expand", "addIndex", "withLane");

        private static final Set<String> RECEIVERS = Set.of("ByteVector", "ShortVector", "IntVector", "LongVector",
                "FloatVector", "DoubleVector", "Vector", "VectorMask", "VectorShuffle");

        private record Value(boolean wide, boolean exact, boolean carried) {
            Value(boolean wide, boolean exact) {
                this(wide, exact, false);
            }
        }

        private static final Value NARROW = new Value(false, false);
        private static final Value WIDE = new Value(true, false);

        /** Returns the loops of a method as [head, back edge] element indexes, from its backward branches. */
        private static List<int[]> loops(java.lang.classfile.attribute.CodeAttribute code) {
            var labels = new java.util.HashMap<java.lang.classfile.Label, Integer>();
            var index = 0;
            for (var element : code) {
                if (element instanceof java.lang.classfile.instruction.LabelTarget target) {
                    labels.put(target.label(), index);
                }
                index++;
            }
            var loops = new ArrayList<int[]>();
            index = 0;
            for (var element : code) {
                if (element instanceof java.lang.classfile.instruction.BranchInstruction branch) {
                    var head = labels.get(branch.target());
                    if (head != null && head <= index) {
                        loops.add(new int[]{head, index});
                    }
                }
                index++;
            }
            return loops;
        }

        private static List<String> inexact(java.lang.classfile.attribute.CodeAttribute code, boolean checkCarried) {
            var found = new ArrayList<String>();
            var stack = new ArrayList<Value>();
            var locals = new java.util.HashMap<Integer, Boolean>();
            var stores = new java.util.HashMap<Integer, Integer>();
            var loops = loops(code);
            var position = -1;
            var snapshots = new java.util.HashMap<java.lang.classfile.Label, List<Value>>();
            for (var handler : code.exceptionHandlers()) {
                snapshots.put(handler.handler(), List.of(NARROW));
            }
            var reachable = true;
            for (var element : code) {
                position++;
                if (element instanceof java.lang.classfile.instruction.LabelTarget target) {
                    var snapshot = snapshots.get(target.label());
                    if (!reachable && snapshot != null) {
                        stack = new ArrayList<>(snapshot);
                    }
                    reachable = true;
                    continue;
                }
                if (!(element instanceof java.lang.classfile.Instruction instruction) || !reachable) {
                    continue;
                }
                switch (instruction) {
                    case java.lang.classfile.instruction.LoadInstruction load -> {
                        if (load.typeKind() == java.lang.classfile.TypeKind.REFERENCE) {
                            var stored = stores.getOrDefault(load.slot(), -1);
                            var at = position;
                            var carried = loops.stream().anyMatch(loop -> (loop[0] <= at && at <= loop[1] && stored < loop[0])
                                    || (at > loop[1] && loop[0] <= stored && stored <= loop[1]));
                            stack.add(new Value(false, !carried && locals.getOrDefault(load.slot(), false), carried));
                        } else {
                            stack.add(value(load.typeKind()));
                        }
                    }
                    case java.lang.classfile.instruction.StoreInstruction store -> {
                        var value = pop(stack);
                        if (store.typeKind() == java.lang.classfile.TypeKind.REFERENCE) {
                            locals.put(store.slot(), value.exact());
                            stores.put(store.slot(), position);
                        }
                    }
                    case java.lang.classfile.instruction.ConstantInstruction constant ->
                            stack.add(value(constant.typeKind()));
                    case FieldInstruction field -> {
                        var type = field.typeSymbol();
                        switch (field.opcode()) {
                            case GETSTATIC -> stack.add(new Value(wide(type),
                                    type.descriptorString().startsWith("Ljdk/incubator/vector/")
                                            || type.descriptorString().equals("Ljava/lang/Class;")));
                            case GETFIELD -> {
                                pop(stack);
                                stack.add(new Value(wide(type), false));
                            }
                            case PUTSTATIC -> pop(stack);
                            default -> {
                                pop(stack);
                                pop(stack);
                            }
                        }
                    }
                    case InvokeInstruction invoke -> {
                        var type = invoke.typeSymbol();
                        for (var i = 0; i < type.parameterCount(); i++) {
                            pop(stack);
                        }
                        var vector = invoke.owner().asInternalName().startsWith("jdk/incubator/vector/");
                        var name = invoke.name().stringValue();
                        var exactResult = false;
                        if (invoke.opcode() != java.lang.classfile.Opcode.INVOKESTATIC) {
                            var receiver = pop(stack);
                            var parameters = type.parameterList();
                            var shared = name.equals("intoArray")
                                    || (name.equals("lanewise") && parameters.size() >= 2 && parameters.get(1).isPrimitive());
                            var owner = invoke.owner().asInternalName().substring(invoke.owner().asInternalName().lastIndexOf('/') + 1);
                            if (vector && shared && !receiver.exact()) {
                                found.add(owner + "." + name + " (" + type.descriptorString() + ") on a vector that is not the "
                                        + "result of a per-shape method or a constant");
                            } else if (checkCarried && vector && receiver.carried() && RECEIVERS.contains(owner)) {
                                found.add(owner + "." + name + " (" + type.descriptorString() + ") on a value carried into "
                                        + "or around a loop without casting it again");
                            }
                            exactResult = (vector && EXACT.contains(name))
                                    || (invoke.owner().asInternalName().equals("java/lang/Class") && name.equals("cast")
                                    && receiver.exact());
                        }
                        if (!type.returnType().descriptorString().equals("V")) {
                            stack.add(new Value(wide(type.returnType()), exactResult));
                        }
                    }
                    case java.lang.classfile.instruction.InvokeDynamicInstruction indy -> {
                        for (var i = 0; i < indy.typeSymbol().parameterCount(); i++) {
                            pop(stack);
                        }
                        if (!indy.typeSymbol().returnType().descriptorString().equals("V")) {
                            stack.add(new Value(wide(indy.typeSymbol().returnType()), false));
                        }
                    }
                    case java.lang.classfile.instruction.OperatorInstruction operator -> {
                        var opcode = operator.opcode();
                        var unary = switch (opcode) {
                            case INEG, LNEG, FNEG, DNEG, ARRAYLENGTH -> true;
                            default -> false;
                        };
                        pop(stack);
                        if (!unary) {
                            pop(stack);
                        }
                        var comparison = switch (opcode) {
                            case LCMP, FCMPL, FCMPG, DCMPL, DCMPG, ARRAYLENGTH -> true;
                            default -> false;
                        };
                        stack.add(comparison ? NARROW : value(operator.typeKind()));
                    }
                    case java.lang.classfile.instruction.ConvertInstruction convert -> {
                        pop(stack);
                        stack.add(value(convert.toType()));
                    }
                    case java.lang.classfile.instruction.TypeCheckInstruction check -> {
                        if (check.opcode() == java.lang.classfile.Opcode.INSTANCEOF) {
                            pop(stack);
                            stack.add(NARROW);
                        }
                    }
                    case java.lang.classfile.instruction.StackInstruction op -> shuffle(stack, op.opcode());
                    case java.lang.classfile.instruction.ArrayLoadInstruction load -> {
                        pop(stack);
                        pop(stack);
                        stack.add(value(load.typeKind()));
                    }
                    case java.lang.classfile.instruction.ArrayStoreInstruction ignored -> {
                        pop(stack);
                        pop(stack);
                        pop(stack);
                    }
                    case java.lang.classfile.instruction.NewObjectInstruction ignored -> stack.add(NARROW);
                    case java.lang.classfile.instruction.NewPrimitiveArrayInstruction ignored -> {
                        pop(stack);
                        stack.add(NARROW);
                    }
                    case java.lang.classfile.instruction.NewReferenceArrayInstruction ignored -> {
                        pop(stack);
                        stack.add(NARROW);
                    }
                    case java.lang.classfile.instruction.NewMultiArrayInstruction multi -> {
                        for (var i = 0; i < multi.dimensions(); i++) {
                            pop(stack);
                        }
                        stack.add(NARROW);
                    }
                    case java.lang.classfile.instruction.BranchInstruction branch -> {
                        switch (branch.opcode()) {
                            case GOTO, GOTO_W -> reachable = false;
                            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, IFNULL, IFNONNULL -> pop(stack);
                            default -> {
                                pop(stack);
                                pop(stack);
                            }
                        }
                        snapshots.putIfAbsent(branch.target(), List.copyOf(stack));
                    }
                    case java.lang.classfile.instruction.LookupSwitchInstruction lookup -> {
                        pop(stack);
                        snapshots.putIfAbsent(lookup.defaultTarget(), List.copyOf(stack));
                        for (var entry : lookup.cases()) {
                            snapshots.putIfAbsent(entry.target(), List.copyOf(stack));
                        }
                        reachable = false;
                    }
                    case java.lang.classfile.instruction.TableSwitchInstruction table -> {
                        pop(stack);
                        snapshots.putIfAbsent(table.defaultTarget(), List.copyOf(stack));
                        for (var entry : table.cases()) {
                            snapshots.putIfAbsent(entry.target(), List.copyOf(stack));
                        }
                        reachable = false;
                    }
                    case java.lang.classfile.instruction.ReturnInstruction ignored -> reachable = false;
                    case java.lang.classfile.instruction.ThrowInstruction ignored -> reachable = false;
                    case java.lang.classfile.instruction.MonitorInstruction ignored -> pop(stack);
                    default -> {
                    }
                }
            }
            return found;
        }

        private static Value value(java.lang.classfile.TypeKind kind) {
            return kind == java.lang.classfile.TypeKind.LONG || kind == java.lang.classfile.TypeKind.DOUBLE ? WIDE : NARROW;
        }

        private static boolean wide(java.lang.constant.ClassDesc type) {
            return type.descriptorString().equals("J") || type.descriptorString().equals("D");
        }

        private static Value pop(List<Value> stack) {
            return stack.isEmpty() ? NARROW : stack.removeLast();
        }

        /** Applies a pop, dup or swap instruction, whose effect depends on the categories of the values. */
        private static void shuffle(List<Value> stack, java.lang.classfile.Opcode opcode) {
            switch (opcode) {
                case POP -> pop(stack);
                case POP2 -> {
                    if (!pop(stack).wide()) {
                        pop(stack);
                    }
                }
                case DUP -> stack.add(stack.isEmpty() ? NARROW : stack.getLast());
                case DUP_X1 -> {
                    var a = pop(stack);
                    var b = pop(stack);
                    stack.addAll(List.of(a, b, a));
                }
                case DUP_X2 -> {
                    var a = pop(stack);
                    var b = pop(stack);
                    if (b.wide()) {
                        stack.addAll(List.of(a, b, a));
                    } else {
                        var c = pop(stack);
                        stack.addAll(List.of(a, c, b, a));
                    }
                }
                case DUP2 -> {
                    var a = pop(stack);
                    if (a.wide()) {
                        stack.addAll(List.of(a, a));
                    } else {
                        var b = pop(stack);
                        stack.addAll(List.of(b, a, b, a));
                    }
                }
                case DUP2_X1 -> {
                    var a = pop(stack);
                    if (a.wide()) {
                        var b = pop(stack);
                        stack.addAll(List.of(a, b, a));
                    } else {
                        var b = pop(stack);
                        var c = pop(stack);
                        stack.addAll(List.of(b, a, c, b, a));
                    }
                }
                case DUP2_X2 -> {
                    var a = pop(stack);
                    var b = a.wide() ? null : pop(stack);
                    var c = pop(stack);
                    var d = c.wide() ? null : pop(stack);
                    var top = b == null ? List.of(a) : List.of(b, a);
                    var below = d == null ? List.of(c) : List.of(d, c);
                    stack.addAll(top);
                    stack.addAll(below);
                    stack.addAll(top);
                }
                case SWAP -> {
                    var a = pop(stack);
                    var b = pop(stack);
                    stack.addAll(List.of(a, b));
                }
                default -> {
                }
            }
        }
    }

    @Nested
    @DisplayName("runtime")
    class Runtime {
        // 60 s settles a child of about twenty workloads on a loaded 8-core machine; larger children
        // get proportionally longer, since each round runs every workload once
        private static final Duration DEADLINE = Duration.ofSeconds(60);

        /** The settling time per workload; a child gets the larger of this times its workload count and {@link #DEADLINE}. */
        private static final Duration PER_WORKLOAD = Duration.ofSeconds(3);

        @Test
        @DisplayName("video, audio, image and capture workloads settle to their allocation bounds in fresh JVMs")
        void settles() throws Exception {
            var decode = new ArrayList<>(List.of("h264dec:threads=1:bound=32768", "proresdec:bound=65536",
                    "dnxhddec:bound=65536"));
            var ffmpeg = Oracle.find(Oracle.Tool.FFMPEG).isPresent();
            if (ffmpeg) {
                decode.add("h264dec:threads=2:bound=65536:input=" + x264Stream());
                decode.add("h264dec:threads=1:bound=65536:input=" + x264InterlacedStream());
                decode.add("h264dec:threads=1:bound=65536:input=" + x264HighBitDepthStream());
                decode.add("h264dec:threads=1:bound=65536:input=" + x264Chroma422Stream());
                decode.add("vp8dec:bound=32768:input=" + libvpxStream());
                decode.add("vp9dec:bound=16384:input=" + libvpxVp9Stream());
                decode.add("hevcdec:threads=2:bound=65536:input=" + x265Stream());
            }
            var encode = new ArrayList<>(List.of("h264enc:speed=BALANCED:profile=main:threads=2:bound=65536",
                    "h264enc:speed=FASTEST:profile=baseline:threads=1:bound=65536",
                    "vp8enc:speed=6:threads=1:bound=16384", "vp8enc:speed=8:threads=2:resilient=false:bound=16384",
                    "proresenc:bound=2097152", "dnxhdenc:bound=4194304"));
            var images = List.of("webpenc:bound=23068672", "webpdec:bound=720896", "capture:bound=4096",
                    "webpanim:concurrent=2:frames=6:bound=41943040");
            var audio = new ArrayList<>(List.of("opusvoice:bound=2048", "opuscall:bound=2048", "aacenc:bound=2048",
                    "ac3:bound=4096",
                    "flacenc:bound=24576", "mp3enc:bound=4096",
                    "resample:bound=1024", "voice:bound=512", "amrnb:bound=4096", "amrwb:bound=4096", "heaac:bound=4096"));
            if (ffmpeg) {
                audio.add("mp3dec:bound=4096:input=" + lameStream());
            }
            var stills = List.of("jpegsend:bound=17825792", "pngdec:bound=4456448", "gifdec:bound=98304",
                    "convert:bound=3670016", "effects:bound=8388608");
            var mixed = new ArrayList<String>();
            mixed.addAll(decode);
            if (ffmpeg) {
                // The AV1 decoder's many kernels would push the mixed child past its compile budget.
                decode.add("av1dec:bound=49152:input=" + libaomStream());
            }
            mixed.addAll(encode);
            // The VP9 encoder's kernels, its own and the VP9 decoder's it reuses, would push the mixed
            // child past its compile budget.
            encode.addAll(List.of("vp9enc:speed=6:threads=1:bound=16384",
                    "vp9enc:speed=8:threads=2:resilient=false:bound=16384", "vp9enc:speed=5:threads=1:layers=3:bound=16384"));
            // The AV1 encoder runs in a child of its own: it reuses the AV1 decoder's kernels, and some
            // of them (the 4-point ADST, the 8x4 DCT) run seldom enough that sharing the encoders'
            // child leaves them on the first compilation tier past the deadline.
            var encodeAv1 = List.of("av1enc:speed=FASTER:threads=1:bound=65536",
                    "av1enc:speed=BALANCED:threads=2:bound=65536");
            mixed.addAll(images);
            mixed.addAll(audio);
            mixed.addAll(stills);
            // The codecs of a call: the H.264 encoder in its real-time preset and the single-threaded
            // decoders.
            var realtimeVideo = new ArrayList<>(List.of("h264dec:threads=1:bound=32768",
                    "h264enc:rt=true:threads=2:bound=65536",
                    "vp8enc:speed=6:threads=2:bound=16384"));
            if (ffmpeg) {
                realtimeVideo.add("vp8dec:bound=32768:input=" + libvpxStream());
                realtimeVideo.add("av1dec:bound=49152:input=" + libaomStream());
                realtimeVideo.add("hevcdec:threads=1:bound=65536:input=" + x265Stream());
            }
            realtimeVideo.add("av1enc:speed=FASTER:threads=1:bound=65536");
            var children = List.of(decode, encode, encodeAv1, images, audio, stills, mixed, realtimeVideo);
            var parallelism = Math.max(1, Math.min(children.size(), java.lang.Runtime.getRuntime().availableProcessors() / 2));
            var failures = new ArrayList<String>();
            try (var pool = Executors.newFixedThreadPool(parallelism)) {
                var futures = new ArrayList<Future<String>>();
                for (var i = 0; i < children.size(); i++) {
                    var index = i;
                    futures.add(pool.submit((Callable<String>) () -> runTwice("child-" + index, children.get(index))));
                }
                for (var future : futures) {
                    try {
                        var failure = future.get();
                        if (failure != null) {
                            failures.add(failure);
                        }
                    } catch (ExecutionException exception) {
                        failures.add(String.valueOf(exception.getCause()));
                    }
                }
            }
            if (!failures.isEmpty()) {
                fail(String.join("\n\n", failures));
            }
        }

        /**
         * Runs one child JVM, and a second one if the first fails; returns a description of both
         * failures, or null. A kernel whose compilation fails in a small fraction of JVMs fails
         * the guard only when two JVMs in a row hit it, while a kernel that fails in most JVMs
         * still fails it nearly always.
         */
        private static String runTwice(String name, List<String> workloads) throws Exception {
            var first = run(name, workloads);
            if (first == null) {
                return null;
            }
            var second = run(name + "-retry", workloads);
            return second == null ? null : first + "\n\nretried in a fresh JVM:\n" + second;
        }

        private static String x264Stream() {
            return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                    List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                            "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-profile:v", "high", "-f", "h264"))
                    .toAbsolutePath().toString();
        }

        private static String x264InterlacedStream() {
            return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                    List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                            "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-profile:v", "high",
                            "-x264-params", "interlaced=1:tff=1", "-f", "h264"))
                    .toAbsolutePath().toString();
        }

        private static String x264HighBitDepthStream() {
            return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                    List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p10le", "-t", "2",
                            "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-f", "h264"))
                    .toAbsolutePath().toString();
        }

        private static String x264Chroma422Stream() {
            return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                    List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv422p", "-t", "2",
                            "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-x264-params",
                            "interlaced=1:tff=1", "-f", "h264"))
                    .toAbsolutePath().toString();
        }

        private static String x265Stream() {
            return Fixtures.ffmpeg("hevc", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                    List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                            "-c:v", "libx265", "-preset", "medium", "-crf", "26", "-x265-params", "log-level=error",
                            "-f", "hevc"))
                    .toAbsolutePath().toString();
        }

        private static String libvpxStream() {
            return Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                    List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                            "-c:v", "libvpx", "-b:v", "1M", "-f", "ivf"))
                    .toAbsolutePath().toString();
        }

        private static String libaomStream() {
            return Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                    List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                            "-c:v", "libaom-av1", "-usage", "realtime", "-cpu-used", "8", "-b:v", "1M", "-tiles", "2x1",
                            "-f", "ivf"))
                    .toAbsolutePath().toString();
        }

        private static String libvpxVp9Stream() {
            return Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i", "mandelbrot=size=640x360:rate=30",
                            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30"),
                    List.of("-filter_complex", "[0][1]blend=all_mode=average,format=yuv420p", "-t", "2",
                            "-c:v", "libvpx-vp9", "-b:v", "1M", "-g", "10", "-tile-columns", "1", "-cpu-used", "4", "-f",
                            "ivf"))
                    .toAbsolutePath().toString();
        }

        private static String lameStream() {
            return Fixtures.ffmpeg("mp3", List.of("-f", "lavfi", "-i", "aevalsrc=exprs='0.25*sin(2*PI*(220+55*floor(mod(t*2\\,4)))*t)"
                                    + "*exp(-4*mod(t\\,0.5))+0.15*sin(2*PI*(330+110*floor(mod(t*3\\,5)))*t)|0.2*sin(2*PI*(277+55"
                                    + "*floor(mod(t*2\\,4)))*t)+0.1*sin(2*PI*550*t)*exp(-8*mod(t\\,0.25))':s=44100:d=10",
                            "-f", "lavfi", "-i", "anoisesrc=color=pink:seed=7:amplitude=0.05:sample_rate=44100:duration=10"),
                    List.of("-filter_complex", "[0][1]amix=inputs=2:normalize=0", "-c:a", "libmp3lame", "-b:a", "128k"))
                    .toAbsolutePath().toString();
        }

        /** Runs one child JVM and returns a description of its failure, or null. */
        private static String run(String name, List<String> workloads) throws Exception {
            var log = Path.of("target", "jit-guard", name + ".log").toAbsolutePath();
            var compilations = Path.of("target", "jit-guard", name + "-compilations.log").toAbsolutePath();
            Files.createDirectories(log.getParent());
            var deadline = DEADLINE.compareTo(PER_WORKLOAD.multipliedBy(workloads.size())) >= 0
                    ? DEADLINE : PER_WORKLOAD.multipliedBy(workloads.size());
            // The deadlines are measured on 8 cores: fewer cores leave fewer compiler threads for the same queue
            var cores = java.lang.Runtime.getRuntime().availableProcessors();
            if (cores < 8) {
                deadline = deadline.multipliedBy(8).dividedBy(cores);
            }
            // The compilation log goes to its own file: written to the same stream, the VM's lines
            // can land in the middle of the workload's result lines.
            var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--add-modules", "jdk.incubator.vector", "-Xmx256m", "-XX:+UseSerialGC", "-XX:+PrintCompilation",
                    "-XX:+DisplayVMOutputToStderr"));
            // jit.childArgs, comma separated, adds JVM options to the children, for example
            // -XX:+UnlockDiagnosticVMOptions,-XX:+PrintIntrinsics to see which intrinsics fail
            for (var option : System.getProperty("jit.childArgs", "").split(",")) {
                if (!option.isBlank()) {
                    command.add(option.strip());
                }
            }
            command.addAll(List.of("-cp", childClassPath(),
                    JitWorkload.class.getName(), "seconds=" + deadline.toSeconds()));
            command.addAll(workloads);
            var process = new ProcessBuilder(command).redirectOutput(log.toFile()).redirectError(compilations.toFile())
                    .start();
            if (!process.waitFor(deadline.toSeconds() + 60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return name + " did not finish; see " + log + " and " + compilations;
            }
            var lines = Files.readAllLines(log);
            // A JVMTI agent attaching (a debugger, a JFR recording) invalidates compilations in
            // flight, and so does a class loading while a caller that assumed another one was the
            // only subclass compiles; the compiler retries them, so those lines say nothing about
            // the kernels.
            var skipped = Files.readAllLines(compilations).stream()
                    .filter(line -> line.contains("COMPILE SKIPPED") && line.contains("com.github.auties00.smalt")
                            && !line.contains("Jvmti state change") && !line.contains("concurrent class loading"))
                    .toList();
            var passed = lines.stream().anyMatch(line -> line.startsWith("PASS"));
            if (passed && skipped.isEmpty()) {
                return null;
            }
            var rounds = lines.stream().filter(line -> line.startsWith("ROUND")).toList();
            var recent = rounds.subList(Math.max(0, rounds.size() - 3 * workloads.size()), rounds.size());
            // Everything on the error stream that is not a compilation line: a crash's stack trace.
            var errors = Files.readAllLines(compilations).stream()
                    .filter(line -> !line.isBlank() && !Character.isDigit(line.strip().charAt(0)))
                    .toList();
            return name + (passed ? " settled" : " did not settle under its bounds within " + deadline.toSeconds()
                    + " s (exit code " + process.exitValue() + ")")
                    + (skipped.isEmpty() ? "" : "; compilations skipped:\n" + String.join("\n", skipped))
                    + "\nlast rounds:\n" + String.join("\n", recent)
                    + (rounds.isEmpty() ? "\nerror output:\n" + String.join("\n", errors.subList(Math.max(0, errors.size() - 20), errors.size())) : "")
                    + "\nlogs: " + log + ", " + compilations;
        }
    }
}
