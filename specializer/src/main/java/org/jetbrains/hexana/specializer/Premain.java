package org.jetbrains.hexana.specializer;

import java.lang.instrument.Instrumentation;

/**
 * Agent entry point. Registers the {@link RunTransformer} before {@code main} runs, so
 * {@code Interpreter} is specialized the first time it's loaded. Attach with
 * {@code -javaagent:hexana-specializer.jar}.
 */
public final class Premain {

    private Premain() {
    }

    public static void premain(String args, Instrumentation inst) {
        System.err.println("[hexana-spec] agent loaded; will specialize Interpreter.run for the known program");
        inst.addTransformer(new RunTransformer());
    }
}