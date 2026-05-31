package org.jetbrains.hexana.jvmci;

import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.CompilationRequest;
import jdk.vm.ci.code.CompilationRequestResult;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.hotspot.HotSpotCodeCacheProvider;
import jdk.vm.ci.hotspot.HotSpotCompilationRequest;
import jdk.vm.ci.hotspot.HotSpotCompilationRequestResult;
import jdk.vm.ci.hotspot.HotSpotCompiledCode;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.runtime.JVMCI;
import jdk.vm.ci.runtime.JVMCIBackend;
import jdk.vm.ci.runtime.JVMCICompiler;

import org.jetbrains.hexana.jvmci.asm.AArch64Asm;
import org.jetbrains.hexana.jvmci.asm.HexanaVMConfig;

/**
 * The {@code hexana} JVMCI compiler. It installs <em>real machine code</em> for one method —
 * {@code org.jetbrains.hexana.interp.Interpreter.run(int[], long[], long[])} — by
 * partial-evaluating it against the fixed program (see {@link Specializer}); every other method is
 * safely bailed (kept in the interpreter / C1).
 *
 * <p>The fixed program is read at compile time from a compilation-final oracle
 * ({@code org.jetbrains.hexana.interp.ProgramOracle.code/consts}) via JVMCI constant reflection —
 * the raw-JVMCI analogue of how Truffle reads {@code @CompilationFinal} AST state. The installed
 * code begins with an identity guard on {@code code}/{@code consts}; if a caller ever passes a
 * different program, the guard traps and HotSpot deoptimizes back into the interpreter, so the
 * installation is correct for all callers, not just the benchmark.
 */
public final class HexanaCompiler implements JVMCICompiler {

    private static final String TARGET_HOLDER = "org.jetbrains.hexana.interp.Interpreter";
    private static final String TARGET_NAME = "run";
    private static final String TARGET_DESC = "([I[J[J)J";
    private static final String ORACLE_CLASS = "org.jetbrains.hexana.interp.ProgramOracle";

    private volatile boolean providersReady;
    private MetaAccessProvider metaAccess;
    private CodeCacheProvider codeCache;
    private ConstantReflectionProvider constantReflection;
    private HexanaVMConfig config;

    private synchronized void ensureProviders() {
        if (providersReady) {
            return;
        }
        JVMCIBackend backend = JVMCI.getRuntime().getHostJVMCIBackend();
        metaAccess = backend.getMetaAccess();
        codeCache = backend.getCodeCache();
        constantReflection = backend.getConstantReflection();
        config = new HexanaVMConfig(HotSpotJVMCIRuntime.runtime().getConfigStore(), codeCache.getTarget().arch);
        providersReady = true;
    }

    @Override
    public CompilationRequestResult compileMethod(CompilationRequest request) {
        final ResolvedJavaMethod method = request.getMethod();
        if (isTarget(method) && request.getEntryBCI() == JVMCICompiler.INVOCATION_ENTRY_BCI) {
            try {
                return installSpecialized(request, method);
            } catch (Throwable t) {
                // Any failure -> bail safely; the method stays in the interpreter / C1.
                System.err.println("[hexana] specialization failed for " + method.format("%H.%n%p")
                        + " -> bailing: " + t);
                return HotSpotCompilationRequestResult.failure("hexana: " + t, false);
            }
        }
        return HotSpotCompilationRequestResult.failure("hexana: not a target", false);
    }

    private boolean isTarget(ResolvedJavaMethod m) {
        return TARGET_NAME.equals(m.getName())
                && TARGET_HOLDER.equals(m.getDeclaringClass().toJavaName())
                && TARGET_DESC.equals(m.getSignature().toMethodDescriptor());
    }

    private CompilationRequestResult installSpecialized(CompilationRequest request, ResolvedJavaMethod method)
            throws ClassNotFoundException {
        ensureProviders();

        // Read the fixed program from the compilation-final oracle.
        ResolvedJavaType oracle = metaAccess.lookupJavaType(Class.forName(ORACLE_CLASS));
        ResolvedJavaField fCode = null;
        ResolvedJavaField fConsts = null;
        for (ResolvedJavaField f : oracle.getStaticFields()) {
            if ("code".equals(f.getName())) {
                fCode = f;
            } else if ("consts".equals(f.getName())) {
                fConsts = f;
            }
        }
        if (fCode == null || fConsts == null) {
            return HotSpotCompilationRequestResult.failure("hexana: oracle fields missing", false);
        }
        JavaConstant codeArray = constantReflection.readFieldValue(fCode, null);
        JavaConstant constsArray = constantReflection.readFieldValue(fConsts, null);
        if (codeArray == null || codeArray.isNull() || constsArray == null || constsArray.isNull()) {
            // Program not published yet (compile raced ahead of @Setup); bail, retry later.
            return HotSpotCompilationRequestResult.failure("hexana: program oracle not populated", true);
        }

        AArch64Asm asm = new AArch64Asm(codeCache, config);
        asm.emitPrologue();
        asm.emitNmethodEntryBarrier(); // required for a default JVMCI install on JDK17+
        Specializer.emit(asm, metaAccess, constantReflection, method, codeArray, constsArray);
        asm.emitEpilogue();

        int id = (request instanceof HotSpotCompilationRequest hr) ? hr.getId() : 0;
        HotSpotCompiledCode code = asm.finish((HotSpotResolvedJavaMethod) method, id, request.getEntryBCI());
        InstalledCode installed = ((HotSpotCodeCacheProvider) codeCache).installCode(method, code, null, null, true);
        System.err.println("[hexana] INSTALLED specialized Interpreter.run @ 0x"
                + Long.toHexString(installed.getStart()));
        return HotSpotCompilationRequestResult.success(0);
    }
}