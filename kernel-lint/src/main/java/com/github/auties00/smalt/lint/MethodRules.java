package com.github.auties00.smalt.lint;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks one method of a class that uses the Vector API against the per-method rules: {@link Rule#VECTOR_HELPER},
 * {@link Rule#VECTOR_OPERATOR}, {@link Rule#VECTOR_SHIFT_COUNT}, {@link Rule#VECTOR_SCALAR_OPERAND},
 * {@link Rule#KERNEL_SIZE}, {@link Rule#EXACT_RECEIVER} and {@link Rule#CARRIED_RECEIVER}.
 */
final class MethodRules {
    /** The shift and rotate operators, the only ones that may take a scalar operand (an int count). */
    static final Set<String> SHIFTS = Set.of("LSHL", "LSHR", "ASHR", "ROL", "ROR");

    /** The Vector API types whose instances are vectors to C2: values of these types must not cross a call. */
    static final Set<String> VECTOR_TYPES = Set.of("Vector", "ByteVector", "ShortVector", "IntVector", "LongVector",
            "FloatVector", "DoubleVector", "VectorMask", "VectorShuffle");

    private MethodRules() {
        throw new AssertionError();
    }

    /**
     * Checks a method.
     *
     * @param owner the class
     * @param method the method
     * @param bytes the method's bytecode length
     * @return the findings
     * @throws AnalyzerException if the bytecode is malformed
     */
    static List<Finding> check(ClassNode owner, MethodNode method, int bytes) throws AnalyzerException {
        var findings = new ArrayList<Finding>();
        var name = owner.name.replace('/', '.');
        var firstLine = firstLine(method);
        var helperTypes = initializerOnly(owner, method) ? List.<String>of() : vectorTypes(method.desc);
        if (!helperTypes.isEmpty()) {
            findings.add(new Finding(Rule.VECTOR_HELPER, name, method.name, owner.sourceFile, firstLine,
                    "(" + String.join(", ", helperTypes) + ")"));
        }
        if (method.instructions.size() == 0) {
            return findings;
        }
        var callsVectors = false;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.startsWith("jdk/incubator/vector/")) {
                callsVectors = true;
                break;
            }
        }
        if (!callsVectors) {
            return findings;
        }
        // The class initializer and its private helpers run once, interpreted: only the size rule applies there.
        var initializer = method.name.equals("<clinit>") || initializerOnly(owner, method);
        if (bytes > KernelLint.MAX_KERNEL_BYTES) {
            findings.add(new Finding(Rule.KERNEL_SIZE, name, method.name, owner.sourceFile, firstLine,
                    "(" + bytes + " bytes, at most " + KernelLint.MAX_KERNEL_BYTES + ")"));
        }
        var frames = Exactness.analyze(owner.name, method);
        var line = -1;
        for (var index = 0; index < method.instructions.size(); index++) {
            var instruction = method.instructions.get(index);
            if (instruction instanceof LineNumberNode number) {
                line = number.line;
                continue;
            }
            if (!(instruction instanceof MethodInsnNode call) || !call.owner.startsWith("jdk/incubator/vector/")
                    || frames[index] == null) {
                continue;
            }
            var frame = frames[index];
            var arguments = Type.getArgumentTypes(call.desc);
            var simple = call.owner.substring(call.owner.lastIndexOf('/') + 1);
            var where = simple + "." + call.name;
            var top = frame.getStackSize();
            var operator = arguments.length >= 1 && arguments[0].getDescriptor()
                    .startsWith("Ljdk/incubator/vector/VectorOperators$") ? frame.getStack(top - arguments.length) : null;
            var operatorName = operator == null ? null : operator.operator();
            if (operator != null && operatorName == null) {
                findings.add(new Finding(Rule.VECTOR_OPERATOR, name, method.name, owner.sourceFile, line, where));
            }
            if (call.getOpcode() == Opcodes.INVOKESTATIC || !VECTOR_TYPES.contains(simple)) {
                continue;
            }
            var scalar = arguments.length >= 2 && arguments[1].getSort() <= Type.DOUBLE;
            if (call.name.equals("lanewise") && arguments.length >= 2 && operatorName != null) {
                if (!scalar && SHIFTS.contains(operatorName)) {
                    findings.add(new Finding(Rule.VECTOR_SHIFT_COUNT, name, method.name, owner.sourceFile, line,
                            where + "(" + operatorName + ")"));
                } else if (scalar && !SHIFTS.contains(operatorName)) {
                    findings.add(new Finding(Rule.VECTOR_SCALAR_OPERAND, name, method.name, owner.sourceFile, line,
                            where + "(" + operatorName + ")"));
                }
            } else if ((call.name.equals("compare") && scalar)
                    || (call.name.equals("blend") && arguments.length >= 1 && arguments[0].getSort() <= Type.DOUBLE)) {
                findings.add(new Finding(Rule.VECTOR_SCALAR_OPERAND, name, method.name, owner.sourceFile, line, where));
            }
            var receiver = frame.getStack(top - arguments.length - 1);
            if (initializer) {
                continue;
            }
            if (!receiver.exact()) {
                findings.add(new Finding(Rule.EXACT_RECEIVER, name, method.name, owner.sourceFile, line, where));
            } else if (receiver.carried()) {
                findings.add(new Finding(Rule.CARRIED_RECEIVER, name, method.name, owner.sourceFile, line, where));
            }
        }
        return findings;
    }

    /**
     * Returns whether a method is a private helper of the class initializer: private, static, and called only by the
     * class initializer or by other such helpers. It runs while the class initializes, to build a constant, so it may
     * take or return a vector.
     */
    private static boolean initializerOnly(ClassNode owner, MethodNode method) {
        return initializerOnly(owner, method, new java.util.HashSet<>());
    }

    private static boolean initializerOnly(ClassNode owner, MethodNode method, Set<MethodNode> visiting) {
        if ((method.access & Opcodes.ACC_PRIVATE) == 0 || (method.access & Opcodes.ACC_STATIC) == 0
                || !visiting.add(method)) {
            return false;
        }
        var called = false;
        for (var caller : owner.methods) {
            for (var instruction : caller.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(owner.name)
                        && call.name.equals(method.name) && call.desc.equals(method.desc)) {
                    if (!caller.name.equals("<clinit>") && !initializerOnly(owner, caller, visiting)) {
                        return false;
                    }
                    called = true;
                }
            }
        }
        return called;
    }

    /** Returns the vector, mask and shuffle types a method descriptor names. */
    private static List<String> vectorTypes(String descriptor) {
        var types = new ArrayList<String>();
        var all = new ArrayList<>(List.of(Type.getArgumentTypes(descriptor)));
        all.add(Type.getReturnType(descriptor));
        for (var type : all) {
            var element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
            if (element.getSort() == Type.OBJECT && element.getInternalName().startsWith("jdk/incubator/vector/")) {
                var simple = element.getInternalName().substring("jdk/incubator/vector/".length());
                if (VECTOR_TYPES.contains(simple)) {
                    types.add(simple + (type.getSort() == Type.ARRAY ? "[]" : ""));
                }
            }
        }
        return types;
    }

    private static int firstLine(MethodNode method) {
        for (var instruction : method.instructions) {
            if (instruction instanceof LineNumberNode number) {
                return number.line;
            }
        }
        return -1;
    }
}
