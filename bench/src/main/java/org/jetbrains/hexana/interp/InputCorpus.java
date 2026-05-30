package org.jetbrains.hexana.interp;

import java.util.Random;

/**
 * Generates the corpus of per-evaluation inputs. Pure in-memory random {@code long} words (no
 * disk, no I/O) so the benchmark stays CPU-bound and the interpreter dispatch loop is the only
 * hot thing. Reproducible from a fixed seed.
 */
public final class InputCorpus {

    private InputCorpus() {
    }

    /** {@code size} input vectors, each {@code arity} random longs, deterministic for {@code seed}. */
    public static long[][] generate(int size, long seed, int arity) {
        final Random rnd = new Random(seed);
        final long[][] corpus = new long[size][];
        for (int i = 0; i < size; i++) {
            final long[] in = new long[arity];
            for (int j = 0; j < arity; j++) {
                in[j] = rnd.nextLong();
            }
            corpus[i] = in;
        }
        return corpus;
    }
}