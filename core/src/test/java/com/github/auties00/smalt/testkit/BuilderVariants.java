package com.github.auties00.smalt.testkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the one-setting variants of an options type from its builder, for {@link ValueContract}.
 *
 * <p>For every public one-argument setter of the builder whose name is also an accessor of the built value, the
 * variant sets that setting to a value other than the base's: the opposite boolean, another enum constant, or a
 * nearby number, trying the candidates in turn until the builder accepts one. Settings of other types, and those the
 * builder rejects every candidate of, are left out.
 */
public final class BuilderVariants {
    private BuilderVariants() {
        throw new AssertionError();
    }

    /**
     * Returns the variants of a base value, each differing from it in one setting.
     *
     * @param base supplies a builder holding the base settings, a new one per call
     * @param build builds the value
     * @param minimum the fewest variants expected, so that a renamed accessor is noticed
     * @param <B> the builder type
     * @param <T> the value type
     * @return the variants
     */
    public static <B, T> List<T> of(Supplier<B> base, Function<B, T> build, int minimum) {
        var reference = build.apply(base.get());
        var builderType = base.get().getClass();
        var variants = new ArrayList<T>();
        var names = new ArrayList<String>();
        for (var setter : builderType.getMethods()) {
            if (Modifier.isStatic(setter.getModifiers()) || setter.getParameterCount() != 1
                    || !setter.getReturnType().isAssignableFrom(builderType) || names.contains(setter.getName())) {
                continue;
            }
            var getter = accessor(reference.getClass(), setter.getName());
            if (getter == null) {
                continue;
            }
            var current = unwrap(invoke(getter, reference));
            for (var candidate : candidates(setter.getParameterTypes()[0], current)) {
                try {
                    var builder = base.get();
                    setter.invoke(builder, candidate);
                    var variant = build.apply(builder);
                    if (Objects.equals(unwrap(invoke(getter, variant)), current)) {
                        continue;
                    }
                    variants.add(variant);
                    names.add(setter.getName());
                    break;
                } catch (InvocationTargetException | RuntimeException rejected) {
                    // the builder refuses this value or this combination; the next candidate may do
                } catch (IllegalAccessException exception) {
                    throw new AssertionError(exception);
                }
            }
        }
        assertTrue(variants.size() >= minimum, "only the settings " + names + " made variants");
        return variants;
    }

    private static Method accessor(Class<?> type, String name) {
        try {
            var method = type.getMethod(name);
            return method.getReturnType() == void.class ? null : method;
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object unwrap(Object value) {
        return switch (value) {
            case Optional<?> optional -> optional.orElse(null);
            case OptionalInt optional -> optional.isPresent() ? optional.getAsInt() : null;
            case OptionalLong optional -> optional.isPresent() ? optional.getAsLong() : null;
            case OptionalDouble optional -> optional.isPresent() ? optional.getAsDouble() : null;
            case null, default -> value;
        };
    }

    private static List<Object> candidates(Class<?> type, Object current) {
        var result = new ArrayList<Object>();
        if (type == boolean.class || type == Boolean.class) {
            result.add(current instanceof Boolean flag ? !flag : Boolean.TRUE);
            result.add(Boolean.FALSE);
        } else if (type == int.class || type == Integer.class) {
            var value = current instanceof Number number ? number.intValue() : 0;
            for (var delta : new int[]{1, -1, 2, -2, 8, 16, 100, 1000}) {
                result.add(value + delta);
            }
            for (var fixed : new int[]{1, 2, 3, 4, 8, 16, 32, 64, 1000, 64_000}) {
                result.add(fixed);
            }
        } else if (type == long.class || type == Long.class) {
            var value = current instanceof Number number ? number.longValue() : 0L;
            for (var delta : new long[]{1, -1, 1000, 1_000_000}) {
                result.add(value + delta);
            }
        } else if (type == double.class || type == Double.class) {
            var value = current instanceof Number number ? number.doubleValue() : 0.0;
            for (var candidate : new double[]{value * 0.5, value + 0.25, value - 0.25, 0.5, 1.5, 2.0}) {
                result.add(candidate);
            }
        } else if (type.isEnum()) {
            for (var constant : type.getEnumConstants()) {
                if (!constant.equals(current)) {
                    result.add(constant);
                }
            }
        }
        return result;
    }
}
