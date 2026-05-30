# compiler/ — the `hexana` JVMCI compiler (v1 scaffold)

A custom [JVMCI](https://openjdk.org/jeps/243) compiler named `hexana` that HotSpot selects
via `-Djvmci.Compiler=hexana`. **This scaffold installs no code — it safely bails every
method**, so the VM keeps running each method in the interpreter / C1 and stays stable. It
proves the end-to-end plumbing and is the foundation for later targeting a specific hot method.

## Status: plumbing proven

`compiler/run-hexana.sh smoke` produces:

```
[hexana] selected as the JVMCI compiler (v1 scaffold; bails every method)
[hexana] compileMethod bail: org.jetbrains.hexana.jvmci.Smoke.work(long, int) (entryBCI=8)
[hexana] compileMethod bail: java.lang.String.hashCode (entryBCI=-1)
...
```

i.e. HotSpot selects our factory and routes top-tier (tier-4) compile requests to it; we bail
each one with `HotSpotCompilationRequestResult.failure(reason, /*retryable=*/false)`.

## How it's wired (the parts that bite)

- **Discovery is via `JVMCIServiceLocator`, not a plain factory service.** HotSpot's JVMCI does
  NOT read `META-INF/services/jdk.vm.ci.runtime.JVMCICompilerFactory` off the classpath. It
  loads `JVMCIServiceLocator`s (`META-INF/services/jdk.vm.ci.services.JVMCIServiceLocator`) and
  asks each for the factory. `HexanaServiceLocator` is the load-bearing registration.
- **Classpath (unnamed module), not a named module.** A named module with
  `provides jdk.vm.ci.runtime.JVMCICompilerFactory …` fails boot-layer resolution
  (`does not read a module that exports jdk.vm.ci.runtime`) because `--add-exports` doesn't
  satisfy the `provides` check. So `hexana` ships as a plain jar on the classpath with
  `--add-exports …=ALL-UNNAMED`.
- **`jdk.vm.ci.*` is exported only to Graal upstream**, so both compile and run need
  `--add-exports jdk.internal.vm.ci/jdk.vm.ci.{runtime,services,code,hotspot,meta}=ALL-UNNAMED`
  plus `--add-modules jdk.internal.vm.ci`.
- **Compiled with `source/target`, not `--release`** — the release flag uses `ct.sym`, which
  omits internal modules like `jdk.internal.vm.ci` (see `pom.xml`; the inherited
  `maven.compiler.release` is blanked for this module).

## Build & run

```sh
export JAVA_HOME=/Users/minamoto/ws/github/jbr/build/macosx-aarch64-server-release/jdk   # the JVMCI JBR
mvn -pl compiler -am package          # builds compiler/target/hexana-compiler-*.jar

compiler/run-hexana.sh smoke          # self-contained workload; prints [hexana] lines
compiler/run-hexana.sh bench          # benchmark launcher under hexana (see caveat below)
```

Run flags (see `run-hexana.sh`): `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI
-XX:+UseJVMCICompiler -Djvmci.Compiler=hexana -XX:+EagerJVMCI`, the `--add-exports`/`--add-modules`
set above, and the jar on `-cp`.

## Caveats

- **With `-XX:+UseJVMCICompiler` and no Graal, JVMCI is the ONLY top tier.** Bailing every
  method caps everything at **C1** (no C2) — fine for plumbing, NOT a representative benchmark
  configuration.
- **`benchmarks.jar` forks per `@Fork`; those forks do NOT inherit these flags.** `run-hexana.sh
  bench` exercises hexana only in the launcher JVM. To run the actual benchmark *under* hexana,
  add the JVMCI flags + `--add-exports` to the benchmark classes' `@Fork(jvmArgs = …)`.
- Set `-Dhexana.target=<Class.method>` to tag matching compile requests in the log (a hook for
  the eventual "compile only this method" step). The scaffold still bails everything.

## Next step (the hard part)

Actually *installing* optimized code for a target method means building a `HotSpotCompiledNmethod`
and calling `HotSpotCodeCacheProvider.installCode(...)`. That additionally needs the
`jdk.vm.ci.code.site`, `jdk.vm.ci.hotspot.aarch64`, and `jdk.vm.ci.aarch64` exports, and — far
harder — a correct `totalFrameSize`, deopt rescue slot, `DebugInfo`/`BytecodeFrame` at every
safepoint, oop maps, exception table, safepoint poll, stack bang, and the JDK17+ nmethod entry
barrier. Get any wrong and it's a delayed SIGSEGV / heap corruption. Start on a deliberately
trivial leaf method (no safepoints, no oops). See `../README.md` § Workflow.