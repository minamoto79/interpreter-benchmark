package org.jetbrains.hexana.jvmci;

import jdk.vm.ci.runtime.JVMCICompilerFactory;
import jdk.vm.ci.services.JVMCIServiceLocator;

/**
 * JVMCI service locator — the discovery mechanism HotSpot's JVMCI actually uses (the same one
 * Graal registers through). HotSpot loads {@link JVMCIServiceLocator}s via its internal
 * {@code Services.load}, then asks each for a {@link JVMCICompilerFactory}; a plain
 * {@code META-INF/services/jdk.vm.ci.runtime.JVMCICompilerFactory} on the classpath is NOT
 * consulted, so this locator is the load-bearing registration.
 *
 * <p>Registered via {@code META-INF/services/jdk.vm.ci.services.JVMCIServiceLocator}.
 */
public final class HexanaServiceLocator extends JVMCIServiceLocator {

    private static final HexanaCompilerFactory FACTORY = new HexanaCompilerFactory();

    @Override
    public <S> S getProvider(Class<S> service) {
        if (service == JVMCICompilerFactory.class) {
            return service.cast(FACTORY);
        }
        return null;
    }
}