package com.github.auties00.smalt.testkit.jit;

import java.lang.classfile.ClassModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the kernel methods that need vectors of 256 bits or more and do not test {@code Simd.WIDE} themselves: a
 * method reading {@code SPECIES_256}, {@code SPECIES_512}, {@code S_256_BIT} or {@code S_512_BIT} (or a static field
 * its class initializes from one, unless the initializer also reads the platform's width), loading or storing through
 * an index map (a gather or a scatter), or calling such a method of the same classes. Such a kernel is selected only
 * where {@code Simd.WIDE} holds, which the kernel lint checks; where it does not, the JVM runs those methods on the Java
 * fallback of the Vector API, so the guard does not meter them there.
 */
final class WideMethods {
    private static final Set<String> WIDE_CONSTANTS = Set.of("SPECIES_256", "SPECIES_512", "S_256_BIT", "S_512_BIT");
    private static final String SIMD = "com/github/auties00/smalt/internal/simd/Simd";

    private WideMethods() {
        throw new AssertionError();
    }

    /**
     * Returns the wide methods of some classes.
     *
     * @param models the classes
     * @return the methods, as {@code binary.Class.name(descriptor)}
     */
    static Set<String> of(List<ClassModel> models) {
        var wideFields = new HashSet<String>();
        // a field can be initialized from a field of a class initialized later: repeat until stable
        for (var round = 0; round < 4; round++) {
            for (var model : models) {
                fields(model, wideFields);
            }
        }
        var direct = new HashMap<String, Boolean>();
        var guarded = new HashSet<String>();
        var calls = new HashMap<String, Set<String>>();
        for (var model : models) {
            var owner = model.thisClass().asInternalName();
            for (var method : model.methods()) {
                var key = owner + "." + method.methodName().stringValue() + method.methodType().stringValue();
                var wide = false;
                var callees = new HashSet<String>();
                for (var element : method.code().map(code -> (Iterable<java.lang.classfile.CodeElement>) code)
                        .orElse(List.of())) {
                    if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETSTATIC) {
                        if (wideConstant(field) || wideFields.contains(key(field))) {
                            wide = true;
                        }
                        if (field.owner().asInternalName().equals(SIMD) && field.name().equalsString("WIDE")) {
                            guarded.add(key);
                        }
                    } else if (element instanceof InvokeInstruction invoke) {
                        if (gathers(invoke)) {
                            wide = true;
                        }
                        callees.add(invoke.owner().asInternalName() + "." + invoke.name().stringValue()
                                + invoke.typeSymbol().descriptorString());
                    }
                }
                direct.put(key, wide);
                calls.put(key, callees);
            }
        }
        var wide = new TreeSet<String>();
        var memo = new HashMap<String, Boolean>();
        for (var method : direct.keySet()) {
            if (wide(method, direct, guarded, calls, memo)) {
                var paren = method.indexOf('(');
                var dot = method.lastIndexOf('.', paren);
                wide.add(method.substring(0, dot).replace('/', '.') + method.substring(dot));
            }
        }
        return Set.copyOf(wide);
    }

    /**
     * Returns the kernel classes the library creates only where {@code Simd.WIDE} holds: every method creating one
     * reads {@code Simd.WIDE}, counting class initializers and the methods the library calls, but not a static method
     * nothing in the library calls (a factory for tests).
     *
     * @param library every main class of the module
     * @param kernels the binary names of the kernel classes, nested classes included
     * @return the gated kernel classes, as binary names
     */
    static Set<String> gated(List<ClassModel> library, Set<String> kernels) {
        var callers = new HashMap<String, Set<String>>();
        var guards = new HashSet<String>();
        var creators = new HashMap<String, Set<String>>();
        var statics = new HashSet<String>();
        for (var model : library) {
            var owner = model.thisClass().asInternalName();
            for (var method : model.methods()) {
                var key = owner + "." + method.methodName().stringValue() + method.methodType().stringValue();
                if (method.flags().has(java.lang.reflect.AccessFlag.STATIC)
                        && !method.methodName().equalsString("<clinit>")) {
                    statics.add(key);
                }
                for (var element : method.code().map(code -> (Iterable<java.lang.classfile.CodeElement>) code)
                        .orElse(List.of())) {
                    switch (element) {
                        case FieldInstruction field when field.opcode() == Opcode.GETSTATIC
                                && field.owner().asInternalName().equals(SIMD) && field.name().equalsString("WIDE") ->
                                guards.add(key);
                        case InvokeInstruction invoke -> callers.computeIfAbsent(invoke.owner().asInternalName() + "."
                                + invoke.name().stringValue() + invoke.typeSymbol().descriptorString(),
                                ignored -> new HashSet<>()).add(key);
                        case java.lang.classfile.instruction.NewObjectInstruction created -> {
                            var type = created.className().asInternalName().replace('/', '.');
                            if (kernels.contains(type)) {
                                creators.computeIfAbsent(type, ignored -> new HashSet<>()).add(key);
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        var gated = new TreeSet<String>();
        var memo = new HashMap<String, Boolean>();
        creators.forEach((type, sites) -> {
            var counted = sites.stream().filter(site -> !statics.contains(site) || callers.containsKey(site)).toList();
            if (!counted.isEmpty() && counted.stream().allMatch(site -> guarded(site, guards, callers, memo))) {
                gated.add(type);
            }
        });
        return Set.copyOf(gated);
    }

    /** Returns whether a method runs only where {@code Simd.WIDE} holds: it reads it, or all its callers do. */
    private static boolean guarded(String method, Set<String> guards, Map<String, Set<String>> callers,
                                   Map<String, Boolean> memo) {
        var known = memo.get(method);
        if (known != null) {
            return known;
        }
        // optimistic for recursion: a cycle of callers is guarded when its other callers are
        memo.put(method, true);
        var result = guards.contains(method);
        if (!result) {
            var calling = callers.getOrDefault(method, Set.of());
            result = !calling.isEmpty() && calling.stream().allMatch(caller -> guarded(caller, guards, callers, memo));
        }
        memo.put(method, result);
        return result;
    }

    private static boolean wide(String method, Map<String, Boolean> direct, Set<String> guarded,
                                Map<String, Set<String>> calls, Map<String, Boolean> memo) {
        var known = memo.get(method);
        if (known != null) {
            return known;
        }
        memo.put(method, false);
        var result = false;
        if (!guarded.contains(method)) {
            result = direct.get(method);
            for (var callee : calls.getOrDefault(method, Set.of())) {
                if (!result && direct.containsKey(callee)) {
                    result = wide(callee, direct, guarded, calls, memo);
                }
            }
        }
        memo.put(method, result);
        return result;
    }

    /** Adds the static fields a class initializer assigns from a wide species and nothing that adapts to the width. */
    private static void fields(ClassModel model, Set<String> wideFields) {
        for (var method : model.methods()) {
            if (!method.methodName().equalsString("<clinit>") || method.code().isEmpty()) {
                continue;
            }
            boolean wide = false;
            boolean adaptive = false;
            for (var element : method.code().get()) {
                if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETSTATIC) {
                    wide |= wideConstant(field) || wideFields.contains(key(field));
                    adaptive |= field.name().equalsString("SPECIES_PREFERRED")
                            || (field.owner().asInternalName().equals(SIMD) && field.name().equalsString("WIDE"));
                } else if (element instanceof InvokeInstruction invoke) {
                    var name = invoke.name().stringValue();
                    adaptive |= name.equals("vectorBitSize") || name.equals("preferredBits");
                } else if (element instanceof FieldInstruction field && field.opcode() == Opcode.PUTSTATIC) {
                    if (wide && !adaptive) {
                        wideFields.add(key(field));
                    }
                    wide = false;
                    adaptive = false;
                }
            }
        }
    }

    /** Returns whether a call is a gather or a scatter: a load or store through an {@code int[]} index map. */
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

    private static String key(FieldInstruction field) {
        return field.owner().asInternalName() + "." + field.name().stringValue();
    }
}
