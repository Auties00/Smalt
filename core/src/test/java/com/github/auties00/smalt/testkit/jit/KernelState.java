package com.github.auties00.smalt.testkit.jit;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures the mutable state a kernel call can change, so that a repeated call starts from the same state.
 *
 * <p>The state is everything reachable from the receiver and the arguments: arrays, and the instance fields of
 * Smalt's own classes (records included, whose fields are final but whose arrays are not). Immutable values
 * (strings, boxed primitives, enum constants, classes, Vector API species and vectors) are shared. A reachable
 * object of any other class may hide state that cannot be restored, so a capture that meets one is not
 * {@linkplain #restorable() restorable} and the call is not repeated.
 */
final class KernelState {
    private static final String SMALT = "com.github.auties00.smalt.";

    private final List<Runnable> restores = new ArrayList<>();
    private final Map<Object, Boolean> seen = new IdentityHashMap<>();
    private String reason;

    private KernelState() {
    }

    /**
     * Captures the state reachable from a call's receiver and arguments.
     *
     * @param receiver the receiver, or {@code null}
     * @param arguments the arguments
     * @return the captured state
     */
    static KernelState capture(Object receiver, Object[] arguments) {
        var state = new KernelState();
        state.visit(receiver);
        for (var argument : arguments) {
            state.visit(argument);
        }
        return state;
    }

    /** {@return whether every reachable object could be captured} */
    boolean restorable() {
        return reason == null;
    }

    /** {@return why the state cannot be restored, or {@code null}} */
    String reason() {
        return reason;
    }

    /** Writes the captured values back into the objects they came from. */
    void restore() {
        for (var restore : restores) {
            restore.run();
        }
    }

    private void visit(Object value) {
        if (value == null || reason != null || seen.put(value, Boolean.TRUE) != null || immutable(value)) {
            return;
        }
        var type = value.getClass();
        if (type.isArray()) {
            visitArray(value, type);
            return;
        }
        if (!type.getName().startsWith(SMALT)) {
            reason = "reaches a " + type.getName();
            return;
        }
        for (var owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            for (var field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                visitField(value, field);
            }
        }
    }

    private void visitArray(Object array, Class<?> type) {
        var length = Array.getLength(array);
        if (type.getComponentType().isPrimitive()) {
            var copy = Array.newInstance(type.getComponentType(), length);
            System.arraycopy(array, 0, copy, 0, length);
            restores.add(() -> System.arraycopy(copy, 0, array, 0, length));
            return;
        }
        var elements = (Object[]) array;
        var copy = elements.clone();
        restores.add(() -> System.arraycopy(copy, 0, elements, 0, length));
        for (var element : copy) {
            visit(element);
        }
    }

    private void visitField(Object owner, Field field) {
        try {
            field.setAccessible(true);
        } catch (RuntimeException exception) {
            reason = "cannot read " + field;
            return;
        }
        try {
            var value = field.get(owner);
            var settable = !Modifier.isFinal(field.getModifiers());
            if (field.getType().isPrimitive()) {
                if (settable) {
                    restores.add(() -> set(field, owner, value));
                }
                return;
            }
            if (settable) {
                restores.add(() -> set(field, owner, value));
            }
            visit(value);
        } catch (IllegalAccessException exception) {
            reason = "cannot read " + field;
        }
    }

    private static void set(Field field, Object owner, Object value) {
        try {
            field.set(owner, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean immutable(Object value) {
        if (value instanceof String || value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof Float || value instanceof Double || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?> || value instanceof Class<?>) {
            return true;
        }
        var name = value.getClass().getName();
        return name.startsWith("jdk.incubator.vector.") || name.startsWith("jdk.internal.vm.vector.");
    }
}
