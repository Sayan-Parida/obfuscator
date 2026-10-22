# AGENTS.md

Java 17 / Spring Boot 4.1.1 / Maven. Three modules in one reactor — there is no
Gradle. `obfuscator-core` is the Spring-free engine, `obfuscator-web` is the HTTP
API, `obfuscator-cli` is the shaded command-line tool. See `README.md` for user
documentation.

## Commands

```bash
mvn -B clean package        # reactor: unit tests + shaded CLI + web JAR
mvn -B clean verify         # adds failsafe packaged-JAR integration tests
mvn -B -pl obfuscator-cli -am package   # CLI only (also builds core)
mvn -B -pl obfuscator-core test         # one module's unit tests
mvn -B -pl obfuscator-web -am package   # build the web JAR (then: java -jar obfuscator-web/target/obfuscator-web.jar)
mvn -B -Dtest=StepRegistryTests test    # single test class (surefire)
mvn -B -Dit.test=PackagedCliIT verify   # single integration test (failsafe)
```

`-pl <module> -am` is required whenever the module depends on a sibling: Maven
will not resolve `obfuscator-core` from the reactor without it.

- **No `mvnw` wrapper exists.** Use the system `mvn`.
- **No lint, formatter, or static-analysis plugin is configured.** There is no
  `checkstyle`/`spotless`/`pmd` step and no `lint` goal to run. Do not add one
  uninvited, and do not claim a change is "linted". Keep the existing formatting
  style (4-space indent, 120-col-ish lines) by hand.
- No database, message broker, or external service is required to build or test.
  Tests run fully offline against an in-process context.

## Toolchain quirk

Two JDKs are installed: `java` on `PATH` is **17.0.20**, but Maven's own runtime
is **JDK 21.0.12**, so builds and `spring-boot:run` execute on 21.
`pom.xml` sets `<java.version>17</java.version>`, which makes the compiler emit
`release 17`. Keep the `java.version` property as the single source of truth —
do not hardcode a source/target level in plugin config.

## Spring Boot 4 breaking changes already hit here

- **`@AutoConfigureMockMvc` does not exist in Boot 4.** Neither does the package
  `org.springframework.boot.test.autoconfigure.web.servlet`;
  `spring-boot-test-autoconfigure` is now a ~37-entry aggregator that no longer
  ships MockMvc auto-configuration. Build MockMvc by hand instead — see the
  `@BeforeEach` in `ObfuscationControllerTests` using
  `MockMvcBuilders.webAppContextSetup(context)`.
- **Jackson 3, so `com.fasterxml.jackson.*` is gone.** Do not reach for
  `ObjectMapper` in tests; assert on `sha256`/`jsonPath` instead.
- Spring Framework 7 / Tomcat 11 underlie the starter; expect Servlet 6.1 and
  Jakarta EE APIs (`jakarta.*`, not `javax.*`).

## ASM (`org.ow2.asm`, 9.10.1)

Version is pinned in `pom.xml` as `${asm.version}` because `spring-boot-dependencies`
does not manage `org.ow2.asm`. Spring ships a **repackaged ASM** under
`org.springframework.asm`; never use it.

- `asm-commons` holds `ClassRemapper`/`SimpleRemapper`, `asm-util` holds
  `CheckClassAdapter`, `asm-analysis` holds `Analyzer`/`SimpleVerifier`.
  `asm-tree` *is* a declared dependency: the renamer still streams, but
  `ClassStructureVerifier` parses the result into a `ClassNode` to run the
  data-flow analyzer, and `asm-analysis` needs `asm-tree` types anyway.
- `SimpleVerifier` in ASM 9 has **no `getCommonSuperClass`** (that hook exists
  only on `ClassWriter`). Hierarchy questions go through the `protected`
  `getClass(Type)`/`isAssignableFrom`/`isInterface` methods. Do not invent a
  superclass hook; it will not compile.
- **`SimpleRemapper` keys methods and fields differently.** `mapMethodName`
  looks up `owner + "." + name + descriptor`, `mapFieldName` looks up
  `owner + "." + name`. A method key without the descriptor silently matches
  nothing: fields get renamed, methods keep their names, and nothing throws.
  See `MethodRenaming.key(...)`.
- Because that failure is silent, `MethodRenaming.confirmApplied(...)` re-reads
  the output and throws if the planned rename count is not reflected. Keep that
  check if the remapper is ever replaced.

## Test fixtures

`TestClasses.compile(...)` compiles Java source with the system Java compiler at
`--release 17`, so fixtures are always real class files rather than committed
binaries. Pass extra sources as varargs when a fixture needs a supertype.

`TestClasses` lives in core's test sources and is shared through the
`maven-jar-plugin` `test-jar` goal. `obfuscator-web` and `obfuscator-cli` depend
on `obfuscator-core` with `<type>test-jar</type>` and test scope; do not copy the
helper. CLI tests split in-process command tests (surefire) from real-process
tests of the shaded JAR (failsafe `PackagedCliIT`).

`ClassStructureVerifierTests.rejectsBytecodeWithAnInconsistentStack` builds a
deliberately broken class with `ClassWriter` + a `POP` on an empty stack, because
byte-level corruption usually fails at parse time instead of reaching
`CheckClassAdapter`. `dataFlowPassesRealisticClassShapes` is the counterweight:
it runs the verifier over ordinary javac output (subclassing, interfaces,
switch, catch, generics) so the data-flow pass cannot become a false-failure
generator. `MethodRenamingRuntimeCompatibilityTests` extends the real-JVM
approach of `JarRuntimeEquivalenceTests` to private constructors and
serialization hooks.

`JarRuntimeEquivalenceTests` extracts the original and obfuscated fixture JARs to
temp directories and runs them with a real JVM (`java.home/bin/java` via
`ProcessBuilder`), comparing stdout. That is the only test that proves runtime
equivalence; every other check is structural.

## Testing a multipart endpoint locally

Windows PowerShell 5.1 is the shell here and its `Invoke-WebRequest` has **no
`-Form` parameter**, so the usual snippet silently fails. Use `curl.exe`:

```powershell
curl.exe -F "file=@C:\path\to\App.jar" -F "format=JAR" http://localhost:8080/api/v1/obfuscations
```

Note the response embeds the whole artifact as base64 — parse it with
`ConvertFrom-Json` instead of dumping it raw.

## Architecture

Three Maven modules with one-way dependencies; packages stay `com.obfuscator.*`
in every module.

| module | role | rule |
|--------|------|------|
| `obfuscator-core` | `domain` model/contracts, `engine` steps, `application` orchestration | **no Spring imports, no stereotypes.** Usable from a plain `main`; guarded by `CoreIsolationTests`. |
| `obfuscator-web` | `api` controllers/DTOs, `ObfuscatorApplication`, `application.ObfuscationProperties`, `config.EngineConfiguration` | Spring lives only here. Controllers contain no business logic. |
| `obfuscator-cli` | `cli.*` Picocli commands | calls `ObfuscationService` directly; never HTTP, never Spring. |

Inside core keep dependencies pointing inward: `application` → `engine` →
`domain`. The web module wires core beans in `EngineConfiguration`; the CLI
constructs the same objects itself.

`EngineConfiguration` exists because the core classes carry no annotations:
`StepRegistry` and `ObfuscationService` take a plain list/long, and
`MethodRenamingStep` takes the archive caps as constructor arguments. Do not
re-introduce `@Component`/`@Service`/`@Autowired` in core to avoid the config.

## Engine status — read before writing engine code

`TransformationStep` (`domain/TransformationStep.java`) is the contract.
`MethodRenamingStep` (`engine/jvm/`) is the only implementation, and it
supports `JVM_CLASS` and `JAR`. Consequences:

- A `JVM_CLASS` or `JAR` request with no `steps` parameter now runs
  `method-renaming`; other formats still resolve to an empty pipeline.
- For a `JAR`, the step delegates to `JarArchive`, which applies the same
  renaming to every `.class` entry while copying everything else through.
- `StepRegistry` filters by `supports()` format and orders by `order()`; an
  empty `steps` list means "all registered steps", not "no steps".
- Functionality status is set by the step, via
  `TransformationContext.report(...)`, and `ObfuscationService` returns it.
  A pipeline that runs nothing stays `UNVERIFIED`.

**Steps log themselves.** A step calls `context.log().record(...)`; the service
must not. It used to, which double-counted every applied step. There is a
regression test for it in `ObfuscationServiceTests`.

**Behaviour preservation is the product's core constraint.** A transformation
that changes observable behaviour is a defect, so new steps must be
format-scoped via `supports()` and should come with the evidence that the output
still behaves like the input.

**Why renaming stops at private methods.** A public or protected method can be
called from another class or override a supertype method; a per-class pass
cannot rewrite those call sites or keep dispatch intact, so renaming them would
break behaviour. Widening this requires whole-program analysis. Do not "fix" the
private-only restriction without that analysis — the restriction *is* the
soundness argument.

**Some private methods are off limits too.** `MethodRenaming` must never rename
`<init>`/`<clinit>` (the JVM rejects a class whose constructor is not called
`<init>`), `native` methods (their names are bound to external code), or the
serialization hooks `readObject`/`writeObject`/`readObjectNoData`/`readResolve`/
`writeReplace` (the JDK looks them up by exact name and descriptor). These rules
are encoded in `JVM_SPECIAL_NAMES` / `SERIALIZATION_HOOKS` and both are covered
by selector tests plus real-JVM tests in `MethodRenamingRuntimeCompatibilityTests`.

**Verification is a gate, not a formality.** `ClassStructureVerifier` runs
`CheckClassAdapter` plus an ASM `Analyzer`/`SimpleVerifier` data-flow pass. For a
single class a `FAILED` result throws `ObfuscationException` (422); for a JAR the
entry is left unchanged and reported as skipped. `VERIFIED` means "valid
bytecode, only method names differ" — it does not mean the class was executed.

Keep the engine Spring-free: `MethodRenaming`, `NameAllocator`, `KeepNames`,
`ClassStructureVerifier`, `JarArchive`, `StepRegistry` and `ObfuscationService`
must not import Spring. `MethodRenamingStep` is also plain: it has a no-arg
constructor using `JarArchive`'s defaults and a `(long, long)` constructor for
the caps. Only `obfuscator-web`'s `EngineConfiguration` creates beans.
`CoreIsolationTests` asserts Spring is absent from core's classpath.

## CLI — read before changing the command line

`obfuscator-cli` is the primary delivery: a shaded JAR at
`target/obfuscator-cli.jar` with `com.obfuscator.cli.ObfuscatorCli` as
`Main-Class`. It uses Picocli (pinned as `${picocli.version}`, not managed by the
Boot BOM) and calls the core directly.

- **Exit codes are a contract:** 0 success, 1 failure, 2 usage (Picocli), 3
  unsupported input, 4 output refused. Keep `ExitCodes` the single source.
- **Output safety:** refuse output equal to the input (normalise, then
  `Files.isSameFile` when the target exists), refuse a directory, and require
  `--force` to replace an existing file. Write to a temp file in the target
  directory and move it into place only after the pipeline succeeded, so a failed
  run leaves no partial output.
- **Read the input under the cap.** `readCapped` stops as soon as
  `--max-input-bytes` is crossed; the service re-checks. Do not `readAllBytes()`.
- **Format comes from the extension** (`.class` → `JVM_CLASS`, `.jar` → `JAR`,
  case-insensitive). Anything else is exit 3; never claim native/DEX support.
- **Summary honesty:** print input/output sizes, step ids and per-step log
  entries, plus `FunctionalityReport.status()`/`detail()`. `VERIFIED` must not be
  presented as proof of runtime behaviour; its detail already says the runtime
  was not executed.
- **Unverified is not failure.** A produced artifact with `UNVERIFIED` exits 0
  with a warning.

Spring Boot's parent POM ships a `maven-shade-plugin` execution for Boot layouts.
The CLI POM must override its `<transformers>`/`<filters>` lists with
`combine.self="override"` (and set `createDependencyReducedPom` false); without
that, Maven merges the Boot transformer list into ours and the build fails with
`Cannot find 'resource' in ... ManifestResourceTransformer`.

Packaged-JAR tests are failsafe (`*IT.java`) because the shaded JAR only exists
after `package`. `PackagedCliIT` runs it in a fresh JVM, checks that no Spring or
web classes leaked in, and compares original/obfuscated stdout.

## JAR handling — read before touching archive code

`JarArchive` (`engine/jvm/JarArchive.java`) is Spring-free and rewrites a JAR
entry by entry. Rules that are easy to get wrong:

- **A ZIP signature is not proof of integrity.** `requireReadableZip(...)`
  checks the local-file-header magic *and* that an end-of-central-directory
  record exists near the tail, so a truncated archive is rejected instead of
  being silently mangled. `ZipInputStream` alone reads a truncated archive from
  its local headers and never notices the missing central directory.
- **Duplicate entry names are rejected.** `ZipOutputStream` refuses them, so a
  duplicate would otherwise surface as a write failure part-way through.
- **Expansion is capped while reading, never after.** `readEntry(...)` increments
  a per-entry total and the archive-wide total as bytes arrive, throwing
  `ObfuscationException` (422) as soon as either limit is crossed. Do not
  `readAllBytes()` an entry or trust `ZipEntry.getSize()`: it is `-1` for
  streamed entries and can be falsified by a crafted local header. The caps are
  materialised in a `byte[]`, so `JarArchive` rejects values above
  `Integer.MAX_VALUE - 8` instead of risking an overflow.
- **Signed JARs are rejected** (any `META-INF/*.SF|.RSA|.DSA|.EC`,
  case-insensitive) because rewriting any entry invalidates the signature.
- **Per-entry failure isolation.** A `.class` entry that cannot be transformed
  keeps its original bytes and is reported `skipped`; the archive is still
  produced. `Outcome.skipped` is distinct from `changed=false`, which means the
  class legitimately had nothing to rename.
- **`STORED` entries must carry size and CRC** when written to a
  `JarOutputStream`, or `putNextEntry` throws. `write(...)` sets them.
- **Status is aggregated, never assumed.** The step reports `VERIFIED` only when
  at least one class changed and none were skipped; otherwise `UNVERIFIED`, with
  the skipped entry names in the detail.
- **`keepMethods` is validated in `KeepNames`, not the callers.** Both web
  endpoints accept a repeatable `keepMethods` multipart param, which
  `joinKeepRules(...)` joins into the `keepMethods` option; the CLI does the same
  with repeated `--keep` values. `KeepNames.parse` rejects malformed rules
  (whitespace inside a rule, more than one `#`, or an empty owner/method) by
  throwing `ObfuscationException`, which surfaces as a 422 in HTTP and as exit 1
  in the CLI. The step contract still supports setting rules directly via
  options.

## Invariants that are easy to break

- `Artifact` is a record wrapping `byte[]`, so `equals()` compares the array by
  **identity** and two artifacts with equal bytes are not equal. Use `sha256()` or
  `hasSameContentAs(...)`. `bytes()` and the constructor both defensively copy —
  keep it that way, since binaries are large and untrusted input.
- Input size is capped consistently: `obfuscator.max-input-bytes` (web property,
  passed to the Spring-free `ObfuscationService`, which re-checks),
  `spring.servlet.multipart.max-file-size` (container-level, rejects earlier
  with 413) and `spring.servlet.multipart.max-request-size`, which must stay a
  little above the file cap for multipart framing. The first two are 64 MB and
  the request cap is 65 MB in `application.yml`. The CLI has the same service
  check plus its own capped read (`--max-input-bytes`, default
  `ObfuscationService.DEFAULT_MAX_INPUT_BYTES`).
- JAR decompression is capped separately, on the actual inflated bytes:
  `obfuscator.max-archive-entry-bytes` (32 MB) and
  `obfuscator.max-archive-expanded-bytes` (256 MB). `EngineConfiguration` reads
  the web properties and the CLI reads its flags; both pass them to
  `MethodRenamingStep` → `JarArchive`. The engine must not import
  `application` config classes, and the defaults live on `JarArchive`.
- `ObfuscationResult` carries the per-step log entries in addition to
  `appliedSteps`; the CLI prints them, the HTTP DTO does not expose them.
- Reject bad input by throwing `ObfuscationException`; `ApiExceptionHandler`
  turns it into a 422 problem detail. Do not return ad-hoc error bodies.
  `MultipartException` (which covers `MaxUploadSizeExceededException`, including
  Tomcat's file/request limits as mapped by Spring) becomes a 413 problem detail
  from the same advice, so both endpoints answer oversized uploads consistently.
- Transport is multipart in / base64 JSON out. Keep that asymmetry in mind before
  adding streaming endpoints.