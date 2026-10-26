package com.github.auties00.smalt.lint;

import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Finds the methods that use a 256-bit or wider species and can run where the platform's vectors are narrower,
 * where the JIT compilers leave every operation of that species on its allocating Java fallback
 * ({@link Rule#WIDE_SPECIES}).
 *
 * <p>Gathers and scatters (loads and stores through an index map) count as wide too: they compile only on hardware
 * with gather instructions, and every platform with 256-bit vectors has them (AVX2, SVE), while ARM NEON does not.
 *
 * <p>A method uses a wide species when it reads {@code SPECIES_256}, {@code SPECIES_512}, {@code S_256_BIT} or
 * {@code S_512_BIT}, or a static field initialized from one of them; a field whose initializer also depends on the
 * platform's width (it reads {@code SPECIES_PREFERRED}, {@code Simd.WIDE}, {@code vectorBitSize}, {@code length} or
 * {@code preferredBits}, or another such field) adapts to the platform and does not count. Such a method is guarded
 * when it reads {@code Simd.WIDE} (or a static boolean initialized from it) itself, when every method calling it is
 * guarded, or when it is an instance method of a class that only guarded methods create; a class initializer counts
 * as guarded, since the constants it builds cost nothing per call. A method creating a class counts as guarded the
 * same way; a static method that nothing in the library calls is a factory for tests and does not count as a creator.
 */
final class WideSpecies {
    private static final Set<String> WIDE_CONSTANTS = Set.of("SPECIES_256", "SPECIES_512", "S_256_BIT", "S_512_BIT");
    private static final String SIMD = "com/github/auties00/smalt/internal/simd/Simd";
    private static final String LIBRARY = "com/github/auties00/smalt/";

    private final Map<String, ClassModel> classes = new HashMap<>();
    private final Map<String, MethodModel> methods = new HashMap<>();
    private final Map<String, String> owners = new HashMap<>();
    private final Map<String, Integer> lines = new HashMap<>();
    private final Set<String> wideFields = new HashSet<>();
    private final Set<String> adaptiveFields = new HashSet<>();
    private final Set<String> guardFields = new HashSet<>();
    private final Map<String, Set<String>> callers = new HashMap<>();
    private final Map<String, Set<String>> creators = new HashMap<>();
    private final Map<String, Set<String>> wideUses = new TreeMap<>();
    private final Set<String> guards = new HashSet<>();
    private final Map<String, Boolean> safe = new HashMap<>();
    private final Map<String, Boolean> selecting = new HashMap<>();
    private final Map<String, Boolean> gated = new HashMap<>();

    /**
     * Analyzes a whole library.
     *
     * @param models every class of the library
     */
    WideSpecies(List<ClassModel> models) {
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

    /** {@return the number of methods using a wide species, a floor the lint checks so that a filter bug fails} */
    int wideMethods() {
        return wideUses.size();
    }

    /** {@return the unguarded methods using a wide species} */
    List<Finding> findings() {
        var found = new ArrayList<Finding>();
        wideUses.forEach((method, uses) -> {
            if (!guarded(method, true)) {
                var owner = owners.get(method);
                var source = classes.get(owner).findAttribute(java.lang.classfile.Attributes.sourceFile())
                        .map(SourceFileAttribute::sourceFile).map(entry -> entry.stringValue()).orElse(null);
                var name = methods.get(method).methodName().stringValue();
                found.add(new Finding(Rule.WIDE_SPECIES, owner.replace('/', '.'), name, source,
                        lines.getOrDefault(method, -1), "(" + String.join(", ", uses) + ")"));
            }
        });
        return found;
    }

    /** Classifies the static fields a class initializer assigns, one assignment at a time. */
    private void classifyFields(ClassModel model) {
        for (var method : model.methods()) {
            if (!method.methodName().equalsString("<clinit>") || method.code().isEmpty()) {
                continue;
            }
            boolean wide = false;
            boolean adaptive = false;
            boolean guard = false;
            for (var element : method.code().get()) {
                if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETSTATIC) {
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
                } else if (element instanceof FieldInstruction field && field.opcode() == Opcode.PUTSTATIC) {
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
        var uses = new TreeSet<String>();
        var line = -1;
        var firstUse = -1;
        for (var element : method.code().get()) {
            switch (element) {
                case LineNumber number -> line = number.line();
                case FieldInstruction field when field.opcode() == Opcode.GETSTATIC -> {
                    if (wideConstant(field)) {
                        uses.add(simple(field.owner().asInternalName()) + "." + field.name().stringValue());
                        firstUse = firstUse < 0 ? line : firstUse;
                    } else if (wideFields.contains(key(field))) {
                        uses.add(field.name().stringValue());
                        firstUse = firstUse < 0 ? line : firstUse;
                    }
                    if (simdWide(field) || guardFields.contains(key(field))) {
                        guards.add(self);
                    }
                }
                case InvokeInstruction invoke when gathers(invoke) -> {
                    uses.add(simple(invoke.owner().asInternalName()) + "." + invoke.name().stringValue()
                            + " with an index map");
                    firstUse = firstUse < 0 ? line : firstUse;
                }
                case InvokeInstruction invoke when invoke.owner().asInternalName().startsWith(LIBRARY) -> {
                    var callee = resolve(invoke.owner().asInternalName(), invoke.name().stringValue(),
                            invoke.typeSymbol().descriptorString());
                    if (callee != null) {
                        callers.computeIfAbsent(callee, ignored -> new HashSet<>()).add(self);
                    }
                }
                case InvokeDynamicInstruction dynamic -> {
                    for (var argument : dynamic.bootstrapArgs()) {
                        if (argument instanceof DirectMethodHandleDesc handle
                                && handle.owner().descriptorString().startsWith("L" + LIBRARY)) {
                            var target = handle.owner().descriptorString();
                            var callee = resolve(target.substring(1, target.length() - 1), handle.methodName(),
                                    handle.lookupDescriptor());
                            if (callee != null) {
                                callers.computeIfAbsent(callee, ignored -> new HashSet<>()).add(self);
                            }
                        }
                    }
                }
                case NewObjectInstruction created when created.className().asInternalName().startsWith(LIBRARY) ->
                        creators.computeIfAbsent(created.className().asInternalName(), ignored -> new HashSet<>())
                                .add(self);
                default -> {
                }
            }
        }
        if (!uses.isEmpty() && !method.methodName().equalsString("<clinit>")) {
            wideUses.put(self, uses);
            lines.put(self, firstUse);
        }
    }

    /**
     * Returns whether a method runs only where {@code Simd.WIDE} holds: it reads the guard, every caller is guarded,
     * or it is an instance method of a gated class; with {@code initializers}, a class initializer counts as guarded
     * too.
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
            result = model != null && !model.flags().has(AccessFlag.STATIC) && gated(owners.get(method));
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
        for (var site : creators.getOrDefault(owner, Set.of())) {
            var model = methods.get(site);
            var testFactory = model != null && model.flags().has(AccessFlag.STATIC)
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
