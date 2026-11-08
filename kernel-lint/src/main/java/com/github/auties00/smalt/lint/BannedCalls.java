package com.github.auties00.smalt.lint;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds, in a method that carries a {@code KernelLintSuppress}, the calls the build's forbidden-apis signatures ban:
 * the Vector API's convenience methods ({@link Rule#VECTOR_CONVENIENCE}), {@code reinterpretAs*}
 * ({@link Rule#VECTOR_REINTERPRET}), the Vector API outside the {@code Vector*} classes ({@link Rule#VECTOR_CONFINED})
 * and the transcendental {@code java.lang.Math} methods ({@link Rule#STRICT_MATH}).
 *
 * <p>forbidden-apis skips every ban in a method carrying the annotation, whatever rules it names; this lint reports
 * the bans the annotation does not name, so that a suppression accepts only the rules it lists, and reports a
 * suppression of a ban the method does not break as stale. Methods without the annotation are left to forbidden-apis.
 * The sets below are the methods of {@code tools/lint/forbidden-vector.txt} and {@code tools/lint/forbidden-math.txt}
 * ({@code KernelLintTest} checks that they agree).
 */
final class BannedCalls {
    /** The vector classes whose convenience methods are banned. */
    static final Set<String> VECTOR_CLASSES = Set.of("jdk/incubator/vector/Vector", "jdk/incubator/vector/ByteVector",
            "jdk/incubator/vector/ShortVector", "jdk/incubator/vector/IntVector", "jdk/incubator/vector/LongVector",
            "jdk/incubator/vector/FloatVector", "jdk/incubator/vector/DoubleVector");

    /** The banned convenience methods of the vector classes. */
    static final Set<String> CONVENIENCE = Set.of("abs", "add", "and", "div", "eq", "fma", "lt", "max", "min", "mul",
            "neg", "not", "or", "pow", "sqrt", "sub");

    /** The banned transcendental methods of {@code java.lang.Math}. */
    static final Set<String> TRANSCENDENTAL = Set.of("sin", "cos", "tan", "asin", "acos", "atan", "atan2", "exp",
            "log", "log10", "log1p", "expm1", "pow", "cbrt", "sinh", "cosh", "tanh", "hypot");

    private static final String VECTOR_PACKAGE = "jdk/incubator/vector/";

    private BannedCalls() {
        throw new AssertionError();
    }

    /**
     * Returns the banned calls of a method.
     *
     * @param owner the class declaring the method
     * @param method the method
     * @return the findings, one per banned instruction
     */
    static List<Finding> check(ClassNode owner, MethodNode method) {
        var findings = new ArrayList<Finding>();
        var name = owner.name.replace('/', '.');
        var confined = !owner.name.substring(owner.name.lastIndexOf('/') + 1).startsWith("Vector");
        var line = -1;
        var first = true;
        for (var instruction : method.instructions) {
            if (instruction instanceof LineNumberNode number) {
                line = number.line;
                if (first && confined && method.desc.contains("L" + VECTOR_PACKAGE)) {
                    findings.add(new Finding(Rule.VECTOR_CONFINED, name, method.name, owner.sourceFile, line,
                            "(" + method.desc + ")"));
                }
                first = false;
                continue;
            }
            if (instruction instanceof MethodInsnNode call) {
                if (VECTOR_CLASSES.contains(call.owner) && CONVENIENCE.contains(call.name)) {
                    findings.add(new Finding(Rule.VECTOR_CONVENIENCE, name, method.name, owner.sourceFile, line,
                            "(" + simple(call.owner) + "." + call.name + ")"));
                } else if (call.owner.startsWith(VECTOR_PACKAGE) && call.name.startsWith("reinterpretAs")) {
                    findings.add(new Finding(Rule.VECTOR_REINTERPRET, name, method.name, owner.sourceFile, line,
                            "(" + simple(call.owner) + "." + call.name + ")"));
                } else if (call.owner.equals("java/lang/Math") && TRANSCENDENTAL.contains(call.name)) {
                    findings.add(new Finding(Rule.STRICT_MATH, name, method.name, owner.sourceFile, line,
                            "(Math." + call.name + ")"));
                }
            }
            if (confined && namesVectorApi(instruction)) {
                findings.add(new Finding(Rule.VECTOR_CONFINED, name, method.name, owner.sourceFile, line, ""));
            }
        }
        return findings;
    }

    /** Returns whether an instruction names a type of {@code jdk.incubator.vector}. */
    private static boolean namesVectorApi(Object instruction) {
        return switch (instruction) {
            case MethodInsnNode call -> call.owner.startsWith(VECTOR_PACKAGE) || call.desc.contains("L" + VECTOR_PACKAGE);
            case FieldInsnNode field -> field.owner.startsWith(VECTOR_PACKAGE) || field.desc.contains("L" + VECTOR_PACKAGE);
            case TypeInsnNode type -> type.desc.contains(VECTOR_PACKAGE);
            case MultiANewArrayInsnNode array -> array.desc.contains(VECTOR_PACKAGE);
            case InvokeDynamicInsnNode dynamic -> dynamic.desc.contains("L" + VECTOR_PACKAGE);
            case LdcInsnNode constant -> constant.cst instanceof Type type && type.getDescriptor().contains(VECTOR_PACKAGE);
            default -> false;
        };
    }

    private static String simple(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
