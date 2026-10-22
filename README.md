# obfuscator

Function-preserving JVM bytecode obfuscation. Give it a compiled `.class` file or
`.jar`; get back one that is harder to analyse and behaves the same. The primary
interface is a command-line tool; a Spring Boot HTTP API is also available.

Only JVM artifacts are supported. There is no native-binary, Android DEX or APK
support.

## Modules

| module | contents | depends on |
|--------|----------|------------|
| `obfuscator-core` | domain model, step registry, ASM transformations, verification, orchestration | ASM only (no Spring) |
| `obfuscator-web` | Spring Boot HTTP API and configuration | core |
| `obfuscator-cli` | Picocli command line, packaged as a self-contained JAR | core, Picocli |

The core is framework-free, so the CLI and the web application run the same
pipeline. The CLI does not use HTTP and needs no server.

## Build

Requires JDK 17+ and Maven. Tests need no network, database, or running server.

```bash
mvn -B clean package            # build the shaded CLI + web JAR, run unit tests
mvn -B clean verify             # also run the packaged-JAR integration tests
mvn -B -pl obfuscator-cli -am package   # CLI only
```

The result is `obfuscator-cli/target/obfuscator-cli.jar`. It is shaded: Java,
ASM and Picocli are inside, and the manifest names the entry point.

```bash
java -jar obfuscator-cli/target/obfuscator-cli.jar --help
java -jar obfuscator-cli/target/obfuscator-cli.jar --version
```

## Command line

```
Usage: obfuscator obfuscate [-fhV] [--max-archive-entry-bytes=<bytes>]
                            [--max-archive-expanded-bytes=<bytes>]
                            [--max-input-bytes=<bytes>] -o=<path>
                            [-k=<Name|Owner#method>]... [--steps=<id>[,<id>]...]
                            <input>
```

| option | required | default | meaning |
|--------|----------|---------|---------|
| `<input>` | yes | – | `.class` file or `.jar` archive |
| `-o`, `--output <path>` | yes | – | where to write the result; parent directories are created |
| `-k`, `--keep <rule>` | no | – | preserve a private method name; repeatable. `Name` or `Owner#method` |
| `--steps <id>` | no | all applicable | comma-separated transformation ids |
| `-f`, `--force` | no | off | replace an existing output file |
| `--max-input-bytes <bytes>` | no | 64 MB | reject larger inputs |
| `--max-archive-entry-bytes <bytes>` | no | 32 MB | maximum inflated size of one JAR entry |
| `--max-archive-expanded-bytes <bytes>` | no | 256 MB | maximum total inflated size of a JAR |
| `-h`, `--help`, `-V`, `--version` | no | – | usage and build version |

Exit codes are stable:

| code | meaning |
|------|---------|
| 0 | artifact written (possibly with an `UNVERIFIED` warning) |
| 1 | input, obfuscation, verification or I/O failure; no output was written |
| 2 | command-line usage error |
| 3 | unsupported input format (only `.class` and `.jar`) |
| 4 | write refused: output is the input, is a directory, or exists without `--force` |

The input file is never overwritten. Output is written to a temporary file and
moved into place only after obfuscation and verification succeed, so a failure
leaves no partial artifact. When the output exists, `--force` is required; the
input path is still protected by a same-file check.

### Examples

```bash
# one class file
java -jar obfuscator-cli/target/obfuscator-cli.jar \
  obfuscate build/classes/com/example/App.class \
  -o build/obfuscated/com/example/App.class

# a whole JAR, preserving methods that are looked up reflectively
java -jar obfuscator-cli/target/obfuscator-cli.jar \
  obfuscate App.jar -o App-obfuscated.jar \
  --keep com.example.Owner#secret --keep readResolve

# overwrite an existing result, with a smaller input cap
java -jar obfuscator-cli/target/obfuscator-cli.jar \
  obfuscate App.jar -o App-obfuscated.jar --force --max-input-bytes 16777216
```

On success the CLI prints a summary to stdout: input and output paths, formats
and sizes, the steps that ran, each step's detail, and the functionality status.
Errors go to stderr. `VERIFIED` means *structurally* verified — see
[Verification](#verification).

## Steps

| step | scope | what it does |
|------|-------|--------------|
| `method-renaming` | `JVM_CLASS`, `JAR` | Replaces private method names with `a`, `b`, `c`, … |

For a `JAR`, every `.class` entry is transformed and everything else — the
manifest, resources, `META-INF/services`, nested package directories — is copied
through byte-for-byte. Entry names, timestamps and compression methods are
preserved, and multi-release class entries under `META-INF/versions` are
transformed like any other class.

`--keep` (or the `keepMethods` option in the API) preserves names that are
resolved reflectively at runtime. The summary reports how many methods were
renamed and kept.

Class names, field names, and all non-private methods are left unchanged. So are
a few private methods that are referenced by the JVM itself rather than by the
class's own bytecode:

- constructors (`<init>`) and static initialisers (`<clinit>`): renaming these
  stops the JVM from loading the class at all;
- `native` methods: the name binds the native implementation and cannot be
  rewritten from Java bytecode;
- serialization hooks (`readObject`, `writeObject`, `readObjectNoData`,
  `readResolve`, `writeReplace` with their exact descriptors): the JDK looks
  these up by name, so renaming them silently changes serialization behaviour.

## Verification

`method-renaming` re-reads its own output and runs ASM's class-file checks plus a
data-flow (stack and local-variable type) analysis. `VERIFIED` means the bytecode
is valid and the only difference from the input is method names. It does **not**
mean the class was executed: runtime behaviour is never tested per request. The
test suite executes original and obfuscated fixtures in a real JVM side by side;
that is the evidence for runtime equivalence, not the status field.

A single class that fails verification is rejected and no output is written. For
a JAR, the failing entry keeps its original bytes and is reported as skipped, so
one bad entry cannot corrupt the archive; the archive is then reported
`UNVERIFIED`. An `UNVERIFIED` result is still written (with a warning), because
it may simply mean there was nothing to rename.

## Limits

| limit | CLI option | web property | default |
|-------|------------|--------------|---------|
| input artifact | `--max-input-bytes` | `obfuscator.max-input-bytes` | 64 MB |
| JAR entry, uncompressed | `--max-archive-entry-bytes` | `obfuscator.max-archive-entry-bytes` | 32 MB |
| JAR archive, uncompressed total | `--max-archive-expanded-bytes` | `obfuscator.max-archive-expanded-bytes` | 256 MB |
| HTTP upload, per file | – | `spring.servlet.multipart.max-file-size` | 64 MB |
| HTTP upload, whole request | – | `spring.servlet.multipart.max-request-size` | 65 MB |

The archive caps are enforced on the bytes actually inflated while reading each
entry, not on the size metadata in ZIP headers, so a small highly compressible
archive is rejected instead of expanding into memory. Signed JARs, duplicate
entry names and truncated archives without a central directory are rejected.

For HTTP, exceeding an archive or input cap returns 422 problem details; an
upload that exceeds the servlet limit returns 413 with the same shape.

## HTTP API

Build and run the web module:

```bash
mvn -B -pl obfuscator-web -am package
java -jar obfuscator-web/target/obfuscator-web.jar
```

`POST /api/v1/obfuscations` — multipart request:

| part / param | required | default | notes |
|--------------|----------|---------|-------|
| `file` | yes | – | the artifact to obfuscate |
| `format` | no | `JVM_CLASS` | `JVM_CLASS`, `JAR`, `NATIVE` |
| `steps` | no | empty | repeatable step id; empty means all applicable |
| `keepMethods` | no | empty | repeatable rules (`Name` or `Owner#method`) |

The response carries the transformed artifact base64-encoded, its SHA-256, the
steps that ran, and a functionality status.

```bash
curl -F "file=@App.class" -F "format=JVM_CLASS" \
     http://localhost:8080/api/v1/obfuscations

curl -F "file=@App.jar" -F "format=JAR" \
     http://localhost:8080/api/v1/obfuscations
```

`POST /api/v1/obfuscations/download` streams the artifact itself instead of
base64 JSON, with `X-Applied-Steps`, `X-Functionality-Status` and `X-Sha256`
headers and a filename in `Content-Disposition`.

```bash
curl -F "file=@App.jar" -F "format=JAR" \
     http://localhost:8080/api/v1/obfuscations/download -o App-obfuscated.jar
```

On Windows PowerShell 5.1 use `curl.exe` (not the `Invoke-WebRequest` alias,
which has no `-Form` parameter).

## Status

Implemented:

- **Method renaming** for single class files and whole JARs, with structural
  verification and per-entry failure isolation.
- **CLI** with safe output handling, format detection, keep rules, step
  selection, size limits, stable exit codes, and a packaged-JAR integration test
  that executes the original and obfuscated artifact in real JVMs.
- **HTTP API** with the same pipeline, plus 413/422 problem details.
- **Signed JAR rejection**, duplicate-entry rejection, and rejection of truncated
  archives that lack a central directory.

Not implemented:

- **Renaming anything beyond private methods.** Safe renaming stops at private
  methods: a public or protected method can be called from another class or
  override a supertype method, and the transformation cannot rewrite those call
  sites. Widening this needs whole-program analysis.
- **Class-name and field-name renaming.**
- **Other transformations** (string encryption, control-flow flattening, …).
- **Native binaries and Android DEX/APK.**

### Limitations

- **Reflection by name.** Private method names are mangled, so anything that
  resolves them reflectively — `getDeclaredMethod("computeTotal")`,
  `MethodHandles`, `setAccessible` on a private member — breaks. Pass those
  names with `--keep` (or `keepMethods`). This is the main behavioural risk.
- **Whole artifacts are held in memory.** Input, output and (for JARs) inflated
  entries are materialised as byte arrays, bounded by the limits above.
- **`VERIFIED` is structural, not empirical** (see above).
- **Signed JARs are rejected** because rewriting any entry invalidates the
  signature.
- **HTTP response payload.** The JSON endpoint base64-encodes the whole artifact;
  use the download endpoint or the CLI for large JARs.

See `AGENTS.md` for architecture, conventions and engine internals.
