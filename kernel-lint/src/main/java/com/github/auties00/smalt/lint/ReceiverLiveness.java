package com.github.auties00.smalt.lint;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks {@link Rule#LOOP_RECEIVER}: an instance method whose receiver is still read after one of its loops that call
 * the Vector API, so that the receiver is live across the loop's safepoint poll.
 *
 * <p>The receiver is live at a loop's back edge when a read of local 0 is reachable from it in the control flow graph
 * (fall-through, jumps, switches and exception handlers), which includes a read in the loop's own body and a read
 * after the loop, such as a call of a scalar tail through an instance field. Each loop is reported once, at the line
 * of the read nearest to its back edge.
 */
final class ReceiverLiveness {
    private ReceiverLiveness() {
        throw new AssertionError();
    }

    /**
     * Checks a method.
     *
     * @param owner the binary name of the class
     * @param file the source file name, or {@code null}
     * @param method the method
     * @return the findings
     */
    static List<Finding> check(String owner, String file, MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) != 0 || method.name.equals("<init>")
                || method.instructions.size() == 0) {
            return List.of();
        }
        var instructions = method.instructions.toArray();
        for (var instruction : instructions) {
            if (instruction instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == 0) {
                // Local 0 no longer holds the receiver after such a store, which javac never emits.
                return List.of();
            }
        }
        var lines = new LinkedHashSet<Integer>();
        var findings = new ArrayList<Finding>();
        for (var index = 0; index < instructions.length; index++) {
            if (!(instructions[index] instanceof JumpInsnNode jump)) {
                continue;
            }
            var head = method.instructions.indexOf(jump.label);
            if (head > index || !callsVectors(instructions, head, index)) {
                continue;
            }
            var read = nearestReceiverRead(method, instructions, index);
            if (read >= 0 && lines.add(line(instructions, read))) {
                findings.add(new Finding(Rule.LOOP_RECEIVER, owner, method.name, file, line(instructions, read), ""));
            }
        }
        return findings;
    }

    /** Returns whether the instructions in {@code [from, to]} call the Vector API. */
    private static boolean callsVectors(AbstractInsnNode[] instructions, int from, int to) {
        for (var index = from; index <= to; index++) {
            if (instructions[index] instanceof MethodInsnNode call && call.owner.startsWith("jdk/incubator/vector/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the index of the read of local 0 that a breadth-first walk of the control flow graph from an
     * instruction meets first, or {@code -1} if none is reachable.
     */
    private static int nearestReceiverRead(MethodNode method, AbstractInsnNode[] instructions, int from) {
        var seen = new boolean[instructions.length];
        var queue = new ArrayDeque<Integer>();
        for (var next : successors(method, instructions, from)) {
            if (!seen[next]) {
                seen[next] = true;
                queue.add(next);
            }
        }
        while (!queue.isEmpty()) {
            var index = queue.poll();
            if (instructions[index] instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                    && load.var == 0) {
                return index;
            }
            for (var next : successors(method, instructions, index)) {
                if (!seen[next]) {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        return -1;
    }

    /** Returns the instructions control can reach directly from an instruction. */
    private static Set<Integer> successors(MethodNode method, AbstractInsnNode[] instructions, int index) {
        var next = new LinkedHashSet<Integer>();
        var instruction = instructions[index];
        var opcode = instruction.getOpcode();
        switch (instruction) {
            case JumpInsnNode jump -> {
                next.add(method.instructions.indexOf(jump.label));
                if (opcode != Opcodes.GOTO && index + 1 < instructions.length) {
                    next.add(index + 1);
                }
            }
            case TableSwitchInsnNode table -> {
                next.add(method.instructions.indexOf(table.dflt));
                for (LabelNode label : table.labels) {
                    next.add(method.instructions.indexOf(label));
                }
            }
            case LookupSwitchInsnNode lookup -> {
                next.add(method.instructions.indexOf(lookup.dflt));
                for (LabelNode label : lookup.labels) {
                    next.add(method.instructions.indexOf(label));
                }
            }
            default -> {
                var terminal = (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW;
                if (!terminal && index + 1 < instructions.length) {
                    next.add(index + 1);
                }
            }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            var start = method.instructions.indexOf(block.start);
            var end = method.instructions.indexOf(block.end);
            if (index >= start && index < end) {
                next.add(method.instructions.indexOf(block.handler));
            }
        }
        return next;
    }

    /** Returns the source line of an instruction, or {@code -1} if unknown. */
    private static int line(AbstractInsnNode[] instructions, int index) {
        for (var at = index; at >= 0; at--) {
            if (instructions[at] instanceof LineNumberNode number) {
                return number.line;
            }
        }
        return -1;
    }
}
