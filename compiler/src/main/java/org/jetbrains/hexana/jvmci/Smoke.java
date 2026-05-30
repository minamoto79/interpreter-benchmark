package org.jetbrains.hexana.jvmci;

/**
 * Tiny self-contained workload to exercise the JIT so {@code hexana} receives compile
 * requests — a plumbing smoke test for the scaffold. Run via compiler/run-hexana.sh.
 *
 * <p>Expected stderr: one {@code [hexana] selected …} line at startup (from
 * {@code onSelection}, with {@code -XX:+EagerJVMCI}), then {@code [hexana] compileMethod bail …}
 * lines as {@link #work} and friends get hot — proving HotSpot routes top-tier compiles here.
 */
public final class Smoke {

    public static void main(String[] args) {
        System.err.println("[hexana-smoke] warming up ~3s; watch for [hexana] lines on stderr");
        long acc = 0;
        final long end = System.nanoTime() + 3_000_000_000L;
        long iters = 0;
        while (System.nanoTime() < end) {
            acc = work(acc, (int) (iters & 0xffff));
            iters++;
        }
        System.err.println("[hexana-smoke] done after " + iters + " batches, acc=" + acc);
    }

    private static long work(long x, int seed) {
        long a = x ^ seed;
        for (int i = 0; i < 20_000; i++) {
            a = a * 1099511628211L + i;
            a ^= (a >>> 23);
        }
        return a;
    }
}