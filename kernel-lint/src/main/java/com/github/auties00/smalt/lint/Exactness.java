package com.github.auties00.smalt.lint;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes, for every instruction of a method, whether each value on the operand stack and in the locals has a
 * concrete class C2 knows ("exact"), whether it reaches the instruction through a loop head ("carried"), and which
 * {@code VectorOperators} constant it is, if any.
 *
 * <p>The analysis is a fixpoint over the control-flow graph (ASM's {@link Analyzer}) with a join at every merge: a
 * value is exact only if it is exact on every incoming path, so an exact value on one arm of a branch and an inexact
 * one on the other give an inexact value. Exact are the values of static final fields of a Vector API type or of type
 * {@code Class} (C2 folds them to constants of a final class), the results of {@code Class.cast} through an exact
 * {@code Class}, and the results of the operations of {@link #PER_SHAPE}, which every concrete vector class overrides
 * with its own final class as the return type. Every other value, a load, a conversion, a parameter or a field read,
 * has only its declared abstract type. A value is carried when it is live at a loop head (the target of a backward
 * jump): C2 parses a loop before its back edges, so such a value reaches the loop's calls through a phi typed with the
 * declared class, whatever its stores were.
 */
final class Exactness {
    /** The vector operations whose result has the receiver's concrete shape class. */
    static final Set<String> PER_SHAPE = Set.of("lanewise", "blend", "rearrange", "selectFrom", "slice", "unslice",
            "compress", "expand", "addIndex", "withLane", "compare", "and", "or", "xor", "not", "andNot", "eq",
            "indexInRange");

    private Exactness() {
        throw new AssertionError();
    }

    /**
     * An abstract value.
     *
     * @param size the number of slots, 1 or 2
     * @param exact whether C2 knows the value's concrete class
     * @param carried whether the value is live at a loop head
     * @param operator the {@code VectorOperators} constant the value is, or {@code null}
     */
    record V(int size, boolean exact, boolean carried, String operator) implements Value {
        @Override
        public int getSize() {
            return size;
        }
    }

    private static final V ONE = new V(1, false, false, null);
    private static final V TWO = new V(2, false, false, null);

    /**
     * Analyzes a method.
     *
     * @param owner the internal name of the class
     * @param method the method
     * @return the frame before each instruction, {@code null} for unreachable instructions
     * @throws AnalyzerException if the bytecode is malformed
     */
    static Frame<V>[] analyze(String owner, MethodNode method) throws AnalyzerException {
        return new HeadAnalyzer(new Semantics(), loopHeads(method)).analyze(owner, method);
    }

    /** An analyzer whose frames know whether they are a loop head when they merge. */
    private static final class HeadAnalyzer extends Analyzer<V> {
        private final Semantics semantics;
        private final Set<Integer> heads;
        private final Map<Frame<?>, Integer> indexes = new IdentityHashMap<>();

        HeadAnalyzer(Semantics semantics, Set<Integer> heads) {
            super(semantics);
            this.semantics = semantics;
            this.heads = heads;
        }

        @Override
        protected Frame<V> newFrame(int locals, int stack) {
            return new HeadFrame(this, locals, stack);
        }

        @Override
        protected Frame<V> newFrame(Frame<? extends V> frame) {
            var copy = new HeadFrame(this, frame.getLocals(), frame.getMaxStackSize());
            copy.init(frame);
            return copy;
        }

        /** Returns the instruction index of a frame of the analysis, found once by identity. */
        private int indexOf(Frame<?> frame) {
            return indexes.computeIfAbsent(frame, ignored -> {
                var frames = getFrames();
                for (var index = 0; index < frames.length; index++) {
                    if (frames[index] == frame) {
                        return index;
                    }
                }
                return -1;
            });
        }
    }

    /**
     * A frame that marks the values it merges as carried when it is a loop head. The analyzer calls the merge of the
     * frame at a jump's target before it records the edge, so the frame finds its own index instead.
     */
    private static final class HeadFrame extends Frame<V> {
        private final HeadAnalyzer analyzer;

        HeadFrame(HeadAnalyzer analyzer, int locals, int stack) {
            super(locals, stack);
            this.analyzer = analyzer;
        }

        @Override
        public boolean merge(Frame<? extends V> frame, Interpreter<V> interpreter) throws AnalyzerException {
            analyzer.semantics.loopHead = analyzer.heads.contains(analyzer.indexOf(this));
            try {
                return super.merge(frame, interpreter);
            } finally {
                analyzer.semantics.loopHead = false;
            }
        }
    }

    /** Returns the instruction indexes that a backward jump or switch targets. */
    private static Set<Integer> loopHeads(MethodNode method) {
        var heads = new HashSet<Integer>();
        var instructions = method.instructions;
        for (var index = 0; index < instructions.size(); index++) {
            var instruction = instructions.get(index);
            var targets = new ArrayList<LabelNode>();
            switch (instruction) {
                case JumpInsnNode jump -> targets.add(jump.label);
                case TableSwitchInsnNode table -> {
                    targets.add(table.dflt);
                    targets.addAll(table.labels);
                }
                case LookupSwitchInsnNode lookup -> {
                    targets.add(lookup.dflt);
                    targets.addAll(lookup.labels);
                }
                default -> {
                }
            }
            for (var target : targets) {
                var at = instructions.indexOf(target);
                if (at <= index) {
                    heads.add(at);
                }
            }
        }
        return heads;
    }

    /** The abstract semantics of each instruction. */
    private static final class Semantics extends Interpreter<V> {
        private boolean loopHead;

        Semantics() {
            super(Opcodes.ASM9);
        }

        @Override
        public V newValue(Type type) {
            if (type == null) {
                return ONE;
            }
            if (type == Type.VOID_TYPE) {
                return null;
            }
            return type.getSize() == 2 ? TWO : ONE;
        }

        @Override
        public V newOperation(AbstractInsnNode instruction) {
            return switch (instruction.getOpcode()) {
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> TWO;
                case Opcodes.LDC -> {
                    var constant = ((LdcInsnNode) instruction).cst;
                    yield constant instanceof Long || constant instanceof Double ? TWO : ONE;
                }
                case Opcodes.GETSTATIC -> {
                    var field = (FieldInsnNode) instruction;
                    var type = Type.getType(field.desc);
                    if (field.owner.equals("jdk/incubator/vector/VectorOperators")) {
                        yield new V(1, false, false, field.name);
                    }
                    var exact = field.desc.startsWith("Ljdk/incubator/vector/") || field.desc.equals("Ljava/lang/Class;");
                    yield new V(type.getSize(), exact, false, null);
                }
                default -> ONE;
            };
        }

        @Override
        public V copyOperation(AbstractInsnNode instruction, V value) {
            return value;
        }

        @Override
        public V unaryOperation(AbstractInsnNode instruction, V value) {
            return switch (instruction.getOpcode()) {
                case Opcodes.CHECKCAST -> value;
                case Opcodes.GETFIELD -> newValue(Type.getType(((FieldInsnNode) instruction).desc));
                case Opcodes.I2L, Opcodes.I2D, Opcodes.L2D, Opcodes.F2L, Opcodes.F2D, Opcodes.D2L, Opcodes.LNEG,
                     Opcodes.DNEG -> TWO;
                case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                     Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN,
                     Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.PUTSTATIC, Opcodes.ATHROW, Opcodes.MONITORENTER,
                     Opcodes.MONITOREXIT, Opcodes.IFNULL, Opcodes.IFNONNULL -> null;
                default -> ONE;
            };
        }

        @Override
        public V binaryOperation(AbstractInsnNode instruction, V first, V second) {
            var opcode = instruction.getOpcode();
            return switch (opcode) {
                case Opcodes.LALOAD, Opcodes.DALOAD, Opcodes.LADD, Opcodes.DADD, Opcodes.LSUB, Opcodes.DSUB,
                     Opcodes.LMUL, Opcodes.DMUL, Opcodes.LDIV, Opcodes.DDIV, Opcodes.LREM, Opcodes.DREM,
                     Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR -> TWO;
                case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                     Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.PUTFIELD -> null;
                default -> ONE;
            };
        }

        @Override
        public V ternaryOperation(AbstractInsnNode instruction, V first, V second, V third) {
            return null;
        }

        @Override
        public V naryOperation(AbstractInsnNode instruction, List<? extends V> values) {
            var returned = switch (instruction) {
                case MethodInsnNode call -> Type.getReturnType(call.desc);
                case InvokeDynamicInsnNode dynamic -> Type.getReturnType(dynamic.desc);
                default -> Type.getType(Object.class);
            };
            if (returned == Type.VOID_TYPE) {
                return null;
            }
            if (instruction instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC) {
                var receiver = values.getFirst();
                var exact = (call.owner.startsWith("jdk/incubator/vector/") && PER_SHAPE.contains(call.name))
                        || (call.owner.equals("java/lang/Class") && call.name.equals("cast") && receiver.exact());
                return new V(returned.getSize(), exact, false, null);
            }
            return newValue(returned);
        }

        @Override
        public void returnOperation(AbstractInsnNode instruction, V value, V expected) {
        }

        @Override
        public V merge(V first, V second) {
            var operator = first.operator() != null && first.operator().equals(second.operator()) ? first.operator() : null;
            var merged = new V(Math.min(first.size(), second.size()), first.exact() && second.exact(),
                    first.carried() || second.carried() || loopHead, operator);
            return merged.equals(first) ? first : merged;
        }
    }
}
