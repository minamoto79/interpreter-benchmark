package org.jetbrains.hexana.interp;

/**
 * The partial-evaluated form of {@link Interpreter#run} for the FIXED program produced by
 * {@code ProgramBuilder.mixing(16, 4)} — i.e. exactly the code a specializing JVMCI compiler
 * would emit once it knows the program is constant.
 *
 * <p>Every interpreter overhead is gone: no opcode dispatch, no operand stack, no program/pc
 * walk. The rounds are a constant-trip loop (C2 unrolls it), the input index {@code r % 4} is
 * folded to {@code r & 3}, and the constants are compile-time literals. This is the **ceiling**
 * the JVMCI compiler aims to reach; measuring it (vs. the generic interpreter) tells us whether
 * the compiler is worth building.
 *
 * <p>NOT called by the application in the real experiment — the whole point is that the JVMCI
 * compiler delivers this transparently for an unmodified app that still calls {@code run}. Here
 * it's a measurement proxy, kept honest by {@code CorrectnessTest} (it must equal both the
 * interpreter and {@code ProgramBuilder.reference} on every input).
 *
 * <p>Specialized to {@code BenchState.ROUNDS == 16}, {@code BenchState.ARITY == 4}; if those
 * change, the test will fail until this is regenerated (specialization is, by definition, tied
 * to specific parameters).
 */
public final class Specialized {

    private Specialized() {
    }

    public static long eval(long[] input) {
        long acc = ProgramBuilder.SEED_C;
        for (int r = 0; r < 16; r++) {            // constant trip count -> unrolled by C2
            acc = acc + input[r & 3];             // arity 4: r % 4 == r & 3
            acc = acc * ProgramBuilder.PRIME1;
            acc = acc ^ (acc >>> 27);
            acc = acc * ProgramBuilder.PRIME2;
            acc = acc ^ (acc >>> 31);
        }
        return acc;
    }
}