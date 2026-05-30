package org.jetbrains.hexana.jvmci;

import jdk.vm.ci.code.CompilationRequest;
import jdk.vm.ci.code.CompilationRequestResult;
import jdk.vm.ci.hotspot.HotSpotCompilationRequestResult;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.runtime.JVMCICompiler;

/**
 * v1 scaffold: a JVMCI compiler that installs NO code and safely bails on every method.
 *
 * <p>Returning {@link HotSpotCompilationRequestResult#failure(String, boolean)} (rather than
 * installing an nmethod) is the documented safe path: HotSpot records the top-tier (tier-4)
 * compile as failed for that method and keeps it in the interpreter / C1, and the VM stays
 * stable. {@code retryable = false} prevents the method from being re-queued endlessly.
 *
 * <p>Caveat baked into this build: with {@code -XX:+UseJVMCICompiler} and no Graal, JVMCI is the
 * ONLY top tier, so bailing every method caps everything at C1 (no C2). That's fine for proving
 * the plumbing and as a foundation; it is NOT a representative benchmark configuration.
 *
 * <p>Next step (not done here): match a target method in {@link #compileMethod} and actually
 * install code via {@code HotSpotCodeCacheProvider.installCode(...)} — which additionally
 * requires the {@code jdk.vm.ci.code.site}, {@code jdk.vm.ci.hotspot.aarch64} and
 * {@code jdk.vm.ci.aarch64} exports plus correct frame size, deopt/scope info, oop maps,
 * exception tables, safepoint poll, stack bang, and the JDK17+ nmethod entry barrier.
 */
public final class HexanaCompiler implements JVMCICompiler {

    /**
     * If set (e.g. {@code -Dhexana.target=org.jetbrains.hexana.bench.SpanProcessor.process}),
     * only requests whose method matches are logged distinctly — a hook for the eventual
     * "compile only this method" step. The scaffold still bails everything.
     */
    private static final String TARGET = System.getProperty("hexana.target", "");

    @Override
    public CompilationRequestResult compileMethod(CompilationRequest request) {
        final ResolvedJavaMethod method = request.getMethod();
        final String name = method.format("%H.%n%p");
        final boolean isTarget = !TARGET.isEmpty() && name.contains(TARGET);

        // Logging proves HotSpot is actually routing top-tier compiles to us.
        System.err.println("[hexana] compileMethod bail"
                + (isTarget ? " [TARGET]" : "")
                + ": " + name + " (entryBCI=" + request.getEntryBCI() + ")");

        return HotSpotCompilationRequestResult.failure("hexana scaffold: not compiling", false);
    }
}