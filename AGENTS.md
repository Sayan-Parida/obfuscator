# AGENTS.md

Java 17 / Spring Boot 4.1.1 / Maven. Single module — there is no reactor and no
Gradle. See `README.md` for the API.

## Commands

```bash
mvn test                    # compile + all tests
mvn -Dtest=StepRegistryTests test    # single test class
mvn -Dtest=StepRegistryTests#ordersStepsByDeclaredOrder test   # single method
mvn spring-boot:run         # dev server on :8080
```

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
  `asm-tree` is not needed for the streaming `ClassReader` → `ClassRemapper`
  pipeline.
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

`ClassStructureVerifierTests.rejectsBytecodeWithAnInconsistentStack` builds a
deliberately broken class with `ClassWriter` + a `POP` on an empty stack, because
byte-level corruption usually fails at parse time instead of reaching
`CheckClassAdapter`.

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

Layered, wired by component scan from the `com.obfuscator` root package. Keep
dependencies pointing inward: `api` → `application` → `engine` → `domain`.

| package       | role                | rule                                                                 |
|---------------|---------------------|----------------------------------------------------------------------|
| `domain`      | model + contracts   | **must not import Spring.** No `@Component`, no `ApplicationContext`. |
| `engine`      | step registry + future step impls | implements `domain.TransformationStep` as beans |
| `application` | orchestration        | owns the pipeline loop and request validation                    |
| `api`         | HTTP                | controllers/DTOs only; no business logic                          |

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

Keep the engine Spring-free: `MethodRenaming`, `NameAllocator`, `KeepNames`,
`ClassStructureVerifier`, and `JarArchive` must not import Spring. Only the thin
`*Step` adapters carry `@Component`, and they construct the collaborators in
their no-arg constructor rather than being component-scanned.

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
- **`keepMethods` is not exposed over HTTP.** The controller passes `Map.of()`
  for options (`ObfuscationController`), so a REST caller cannot set keep rules.
  The step contract supports it and tests cover it.

## Invariants that are easy to break

- `Artifact` is a record wrapping `byte[]`, so `equals()` compares the array by
  **identity** and two artifacts with equal bytes are not equal. Use `sha256()` or
  `hasSameContentAs(...)`. `bytes()` and the constructor both defensively copy —
  keep it that way, since binaries are large and untrusted input.
- Input size is capped in **two** places that must stay in sync:
  `obfuscator.max-input-bytes` (domain check in `ObfuscationService`) and
  `spring.servlet.multipart.max-file-size` (container-level, rejects earlier).
  Both are 64 MB in `application.yml`.
- Reject bad input by throwing `ObfuscationException`; `ApiExceptionHandler`
  turns it into a 422 problem detail. Do not return ad-hoc error bodies.
- Transport is multipart in / base64 JSON out. Keep that asymmetry in mind before
  adding streaming endpoints.