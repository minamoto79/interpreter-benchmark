package org.jetbrains.hexana.interp;

import java.util.Arrays;

/**
 * Builds the fixed program the benchmark interprets, plus the canonical reference computation
 * the {@code CorrectnessTest} checks against. Keeping the emitted program and its reference in
 * one file makes drift between them obvious.
 *
 * <p>The program is a murmur3-finalizer-style mixing kernel: {@code rounds} rounds, each folding
 * one input word into an accumulator. It's pure long arithmetic — CPU-bound, deterministic, and
 * large enough (~16 ops/round) that the interpreter dispatch loop dominates.
 *
 * <p>The program is FIXED across the whole benchmark run; only the per-evaluation inputs vary.
 * That fixedness is exactly what a specializing JVMCI compiler can exploit (partial-evaluate the
 * dispatch loop against this program), and what C2 cannot.
 */
public final class ProgramBuilder {

    public static final long SEED_C = 0x9E3779B97F4A7C15L;
    public static final long PRIME1 = 0xff51afd7ed558ccdL;
    public static final long PRIME2 = 0xc4ceb9fe1a85ec53L;
    private static final int SHIFT_A = 27;
    private static final int SHIFT_B = 31;

    /** A compiled program: opcode stream, constant pool, and the input arity / round count it assumes. */
    public record Program(int[] code, long[] consts, int arity, int rounds) {
    }

    private ProgramBuilder() {
    }

    /** Emit the {@code rounds}-round mixing program over {@code arity} input words. */
    public static Program mixing(int rounds, int arity) {
        final long[] consts = {SEED_C, PRIME1, PRIME2};
        final IntList c = new IntList();
        c.add(Opcodes.PUSH_CONST, 0);                 // acc = SEED_C
        for (int r = 0; r < rounds; r++) {
            c.add(Opcodes.LOAD_INPUT, r % arity);     // acc = acc + input[r % arity]
            c.add(Opcodes.ADD);
            c.add(Opcodes.PUSH_CONST, 1);             // acc = acc * PRIME1
            c.add(Opcodes.MUL);
            c.add(Opcodes.DUP);                       // acc = acc ^ (acc >>> 27)
            c.add(Opcodes.SHR, SHIFT_A);
            c.add(Opcodes.XOR);
            c.add(Opcodes.PUSH_CONST, 2);             // acc = acc * PRIME2
            c.add(Opcodes.MUL);
            c.add(Opcodes.DUP);                       // acc = acc ^ (acc >>> 31)
            c.add(Opcodes.SHR, SHIFT_B);
            c.add(Opcodes.XOR);
        }
        c.add(Opcodes.RET);
        return new Program(c.toArray(), consts, arity, rounds);
    }

    /** Canonical reference — must match {@link #mixing} exactly. Used as the test oracle. */
    public static long reference(long[] consts, long[] input, int rounds, int arity) {
        long acc = consts[0];
        for (int r = 0; r < rounds; r++) {
            acc = acc + input[r % arity];
            acc = acc * consts[1];
            acc = acc ^ (acc >>> SHIFT_A);
            acc = acc * consts[2];
            acc = acc ^ (acc >>> SHIFT_B);
        }
        return acc;
    }

    /** Minimal growable int buffer; {@code add} takes an opcode plus zero or more inline operands. */
    private static final class IntList {
        private int[] a = new int[64];
        private int n = 0;

        void add(int... xs) {
            for (final int x : xs) {
                if (n == a.length) {
                    a = Arrays.copyOf(a, n * 2);
                }
                a[n++] = x;
            }
        }

        int[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }
}