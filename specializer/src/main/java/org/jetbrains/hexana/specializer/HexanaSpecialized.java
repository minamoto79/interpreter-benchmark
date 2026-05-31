package org.jetbrains.hexana.specializer;

import org.jetbrains.hexana.interp.BenchState;
import org.jetbrains.hexana.interp.ProgramBuilder;

import java.util.Arrays;

/**
 * The specialization the agent installs for {@code Interpreter.run}: a guard ({@link #matches})
 * plus the partial-evaluated computation ({@link #eval}) for the fixed mixing program. The
 * injected fast path in {@code run} calls these; C2 then JITs them to the specialization ceiling.
 *
 * <p>{@link #eval} is the partial evaluation of {@code ProgramBuilder.mixing(ROUNDS, ARITY)} —
 * dispatch and operand stack gone, constants baked, input indices folded. (Deriving this from an
 * arbitrary program is the "compiler"; the stack-machine maps 1:1 to JVM bytecode, so generating
 * it via ASM is the natural next increment. Here it's the known PE output, kept in sync with the
 * benchmark via {@link BenchState} constants and checked against the interpreter at runtime.)
 */
public final class HexanaSpecialized {

    private HexanaSpecialized() {
    }

    /** The program this agent specializes for (the benchmark's fixed mixing program). */
    static final int[] KNOWN_CODE = ProgramBuilder.mixing(BenchState.ROUNDS, BenchState.ARITY).code();

    /** Identity inline cache: content-match a program array once, then compare by reference. */
    private static volatile int[] cached;

    /** True iff {@code code} is the program we can specialize. O(n) once, then O(1). */
    public static boolean matches(int[] code) {
        if (code == cached) {
            return true;
        }
        if (Arrays.equals(code, KNOWN_CODE)) {
            cached = code;
            return true;
        }
        return false;
    }

    /** Partial-evaluated form of the known program. ARITY is a power of two, so {@code r % ARITY == r & (ARITY-1)}. */
    public static long eval(long[] input) {
        final int mask = BenchState.ARITY - 1;
        long acc = ProgramBuilder.SEED_C;
        for (int r = 0; r < BenchState.ROUNDS; r++) {
            acc = acc + input[r & mask];
            acc = acc * ProgramBuilder.PRIME1;
            acc = acc ^ (acc >>> 27);
            acc = acc * ProgramBuilder.PRIME2;
            acc = acc ^ (acc >>> 31);
        }
        return acc;
    }
}