package com.github.auties00.smalt.lint;

/**
 * The rules of the kernel lint, each with a stable id that findings, suppressions and the baseline name.
 *
 * <p>The rules state what C2 needs to compile a Vector API operation to vector instructions instead of the
 * allocating Java fallback; {@code internal.simd.Simd} in smalt-core documents them for kernel authors. The bans on
 * convenience methods ({@code vector-convenience}), on {@code reinterpretAs*} ({@code vector-reinterpret}), on
 * {@code jdk.incubator.vector} outside {@code Vector*} classes ({@code vector-confined}) and on the transcendental
 * {@code Math} methods ({@code strict-math}) are forbidden-apis signatures ({@code tools/lint/forbidden-vector.txt},
 * {@code tools/lint/forbidden-math.txt}); this lint checks them only in the methods carrying a
 * {@code KernelLintSuppress}, which forbidden-apis skips whole ({@link BannedCalls}).
 */
public enum Rule {
    /** A method takes or returns a vector, a mask or a shuffle. */
    VECTOR_HELPER("vector-helper", "takes or returns a vector, mask or shuffle, whose class C2 knows only if the call is "
            + "inlined", "pass arrays and offsets, or inline the helper"),
    /** An operation's operator is not a {@code VectorOperators} constant. */
    VECTOR_OPERATOR("vector-operator", "passes an operator that is not a VectorOperators constant, so C2 cannot "
            + "intrinsify the operation", "pass VectorOperators.X at the call"),
    /** A shift by a vector of counts. */
    VECTOR_SHIFT_COUNT("vector-shift-count", "shifts by a vector of counts", "pass an int count"),
    /** A scalar operand to an operation other than a shift. */
    VECTOR_SCALAR_OPERAND("vector-scalar-operand", "passes a scalar operand, which takes a path whose profile every "
            + "shape shares", "broadcast the operand to a vector first"),
    /** A method calling the Vector API has more than {@value KernelLint#MAX_KERNEL_BYTES} bytes of bytecode. */
    KERNEL_SIZE("kernel-size", "has more bytecode than C2 inlines the Vector API into reliably",
            "split the method; keep the parts that run per vector together"),
    /** A call on a vector, mask or shuffle whose concrete class C2 cannot know. */
    EXACT_RECEIVER("exact-receiver", "calls a vector method on a value whose concrete class is not known",
            "cast it through the shape's class constant first, as in INTS.cast(v)"),
    /** A call on a vector, mask or shuffle carried into or around a loop. */
    CARRIED_RECEIVER("carried-receiver", "calls a vector method on a value carried into or around a loop, which "
            + "reaches the loop's calls through a merge typed with its abstract class",
            "cast it again inside the loop before the call"),
    /** A 256-bit or wider species, a gather or a scatter where {@code Simd.WIDE} may not hold. */
    WIDE_SPECIES("wide-species", "uses 256-bit or wider vectors, a gather or a scatter where the platform's vectors "
            + "may be narrower", "select the method or its kernel only where Simd.WIDE holds"),
    /** A Vector API convenience method, in a method whose suppression does not name this rule. */
    VECTOR_CONVENIENCE("vector-convenience", "calls a convenience method, which reaches lanewise through a type profile "
            + "every vector shape shares", "call lanewise(VectorOperators.X, ...) directly"),
    /** A {@code reinterpretAs*} call, in a method whose suppression does not name this rule. */
    VECTOR_REINTERPRET("vector-reinterpret", "calls reinterpretAs*, which dispatches through a profile every vector "
            + "shape shares", "use reinterpretShape(species, 0)"),
    /** A {@code jdk.incubator.vector} type outside a {@code Vector*} class, in a suppressed method. */
    VECTOR_CONFINED("vector-confined", "names a jdk.incubator.vector type outside a Vector* class, which fails to link "
            + "where the module is absent", "move the code into a Vector* class"),
    /** A transcendental {@code java.lang.Math} method, in a method whose suppression does not name this rule. */
    STRICT_MATH("strict-math", "calls a Math method whose result may depend on the platform",
            "call the StrictMath method of the same name"),
    /** A suppression without a reason, or naming an unknown rule. */
    BAD_SUPPRESSION("bad-suppression", "carries a KernelLintSuppress that names no known rule or gives no reason",
            "name a rule id and say why the rule does not apply"),
    /** A suppression that suppresses nothing. */
    STALE_SUPPRESSION("stale-suppression", "suppresses a rule it does not break", "remove the suppression"),
    /** A baseline row that allows more findings than there are. */
    STALE_BASELINE("stale-baseline", "is allowed more findings by the baseline than it has",
            "lower the row with --update-baseline");

    private final String id;
    private final String problem;
    private final String fix;

    Rule(String id, String problem, String fix) {
        this.id = id;
        this.problem = problem;
        this.fix = fix;
    }

    /** {@return the stable id} */
    public String id() {
        return id;
    }

    /** {@return what a finding of this rule means} */
    public String problem() {
        return problem;
    }

    /** {@return how to fix a finding} */
    public String fix() {
        return fix;
    }

    /**
     * Returns the rule with an id.
     *
     * @param id the id
     * @return the rule, or {@code null} if no rule has the id
     */
    public static Rule of(String id) {
        for (var rule : values()) {
            if (rule.id.equals(id)) {
                return rule;
            }
        }
        return null;
    }
}
