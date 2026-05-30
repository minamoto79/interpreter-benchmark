package org.jetbrains.hexana.jvmci;

import jdk.vm.ci.runtime.JVMCICompiler;
import jdk.vm.ci.runtime.JVMCICompilerFactory;
import jdk.vm.ci.runtime.JVMCIRuntime;

/**
 * Registers the {@code hexana} JVMCI compiler. HotSpot selects it when launched with
 * {@code -Djvmci.Compiler=hexana} by matching {@link #getCompilerName()} against the
 * factories discovered via the module's {@code provides} declaration.
 */
public final class HexanaCompilerFactory implements JVMCICompilerFactory {

    /** Must equal the {@code -Djvmci.Compiler=…} value. */
    @Override
    public String getCompilerName() {
        return "hexana";
    }

    /** Called once when HotSpot picks this factory — handy to confirm selection at startup. */
    @Override
    public void onSelection() {
        System.err.println("[hexana] selected as the JVMCI compiler (v1 scaffold; bails every method)");
    }

    @Override
    public JVMCICompiler createCompiler(JVMCIRuntime runtime) {
        return new HexanaCompiler();
    }
}