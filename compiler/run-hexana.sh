#!/usr/bin/env bash
# Run a workload with the `hexana` JVMCI compiler as the top-tier JIT (v1 scaffold).
#
# Default: runs the self-contained Smoke workload (compiler/.../Smoke.java) to prove the
# plumbing — you should see "[hexana] selected …" at startup and "[hexana] compileMethod
# bail …" lines as methods get hot. Pass `bench` to instead run the benchmark launcher.
#
# hexana is deployed as a CLASSPATH (unnamed-module) service: jdk.vm.ci.* is exported to
# ALL-UNNAMED via --add-exports, the factory is found through META-INF/services. (A named
# module fails the boot-layer `provides` validation because --add-exports doesn't satisfy it.)
#
# Prereqs: `mvn package` has built compiler/target/hexana-compiler-*.jar (and bench for `bench`).
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-/Users/minamoto/ws/github/jbr/build/macosx-aarch64-server-release/jdk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPILER_JAR="$ROOT/compiler/target/hexana-compiler-0.1.0-SNAPSHOT.jar"

if [[ ! -f "$COMPILER_JAR" ]]; then
    echo "missing $COMPILER_JAR — run 'mvn -pl compiler -am package' first" >&2
    exit 1
fi

# jdk.vm.ci.* is exported only to Graal upstream; force-export to the unnamed module (classpath).
# --add-modules resolves the internal JVMCI module so our classpath code can reach it.
ACCESS=(
    --add-modules jdk.internal.vm.ci
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.services=ALL-UNNAMED
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.code=ALL-UNNAMED
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED
)

# JVMCI selection: EnableJVMCI on, UseJVMCICompiler makes JVMCI the top tier, jvmci.Compiler
# picks our factory by name, EagerJVMCI initialises it at startup so onSelection() prints early.
JVMCI=(
    -XX:+UnlockExperimentalVMOptions
    -XX:+EnableJVMCI
    -XX:+UseJVMCICompiler
    -Djvmci.Compiler=hexana
    -XX:+EagerJVMCI
)

mode="${1:-smoke}"
case "$mode" in
    smoke)
        exec "$JAVA_HOME/bin/java" \
            -cp "$COMPILER_JAR" \
            "${ACCESS[@]}" "${JVMCI[@]}" \
            org.jetbrains.hexana.jvmci.Smoke
        ;;
    bench)
        # NOTE: benchmarks.jar forks per @Fork; those forked JVMs do NOT inherit these flags.
        # This exercises hexana only in the launcher JVM. To run the actual benchmark under
        # hexana, add the JVMCI flags + --add-exports to the @Fork jvmArgs (a v1 follow-up).
        BENCH_JAR="$ROOT/bench/target/benchmarks.jar"
        exec "$JAVA_HOME/bin/java" \
            -cp "$BENCH_JAR:$COMPILER_JAR" \
            "${ACCESS[@]}" "${JVMCI[@]}" \
            org.jetbrains.hexana.interp.ResultsWriter "${@:2}"
        ;;
    *)
        echo "usage: $0 [smoke|bench]" >&2
        exit 2
        ;;
esac