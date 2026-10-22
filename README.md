# obfuscator

Orchestration layer for function-preserving binary obfuscation: submit a compiled
binary, get back a semantically equivalent one that is harder to analyse and
reconstruct.

Spring Boot REST service. See "Status" below for what is implemented.

## Build and run

```bash
mvn test              # compile + run all tests
mvn spring-boot:run   # dev server on http://localhost:8080
```

## API

`POST /api/v1/obfuscations` — multipart request:

| part / param | required | default    | notes                        |
|--------------|----------|------------|------------------------------|
| `file`       | yes      | –          | the binary to obfuscate      |
| `format`     | no       | `JVM_CLASS`| `JVM_CLASS`, `JAR`, `NATIVE` |
| `steps`      | no       | empty      | repeatable step id           |
| `keepMethods`| no       | empty      | repeatable rules preserving private methods (`Name` or `Owner#method`) |

The response carries the transformed artifact base64-encoded, its SHA-256, the
steps that actually ran, and a functionality status.

```bash
curl -F "file=@target/App.class" -F "format=JVM_CLASS" \
     http://localhost:8080/api/v1/obfuscations

curl -F "file=@target/App.jar" -F "format=JAR" \
     http://localhost:8080/api/v1/obfuscations
```

`POST /api/v1/obfuscations/download` — same parts and params, but the response
body is the obfuscated artifact itself (no base64) with a filename in
`Content-Disposition`. The headers that would otherwise be lost with a binary
body are still reported: `X-Applied-Steps`, `X-Functionality-Status` and
`X-Sha256` (matching the body bytes). Content types: `application/java-archive`
for JARs, `application/java-vm` for class files, `application/octet-stream`
otherwise.

```bash
curl -F "file=@target/App.jar" -F "format=JAR" \
     http://localhost:8080/api/v1/obfuscations/download -o App-obfuscated.jar
```

Rejected requests return RFC 9457 problem details with status 422.

## Steps

| step | scope | what it does |
|------|-------|--------------|
| `method-renaming` | `JVM_CLASS`, `JAR` | Replaces private method names with `a`, `b`, `c`, … |

For a `JAR`, every `.class` entry is transformed and everything else — the
manifest, resources, `META-INF/services`, nested package directories — is copied
through byte-for-byte. Entry names, timestamps and compression methods are
preserved, and multi-release class entries under `META-INF/versions` are
transformed like any other class.

`method-renaming` accepts a `keepMethods` option: a comma-separated list of
names to preserve, optionally scoped as `com.example.Owner#method`. Use it for
anything resolved reflectively at runtime. The response reports which methods
were kept.

Class names, field names, and all non-private methods are left unchanged.

## Status

Implemented:

- **Method renaming** for single class files and whole JARs, plus a structural
  verifier that confirms the output is valid bytecode and differs from the input
  only in method names. This is what allows a result to report
  `functionalityStatus: VERIFIED`.
- **Per-entry failure isolation.** A class that cannot be transformed keeps its
  original bytes and is reported as skipped, so one bad entry never corrupts the
  archive.
- **Signed JAR rejection**, duplicate-entry rejection, and explicit rejection of
  truncated archives that lack a central directory.

Not implemented:

- **Renaming anything beyond private methods.** Safe renaming stops at private
  methods: a public or protected method can be called from another class or
  override a supertype method, and the transformation cannot rewrite those call
  sites. Widening this needs whole-program analysis.
- **Class-name and field-name renaming.**
- **Other transformations** (string encryption, control-flow flattening, …).

### Limitations

- **Reflection by name.** Private method names are mangled, so anything that
  resolves them reflectively — `getDeclaredMethod("computeTotal")`,
  `MethodHandles`, `setAccessible` on a private member — breaks. Pass those
  names as `keepMethods` (a repeatable request parameter, `Name` or
  `Owner#method`). This is the main behavioural risk.
- **`VERIFIED` is structural, not empirical.** It proves valid bytecode and that
  only names changed. Runtime equivalence is proven by a test that executes the
  original and obfuscated JARs side by side, not by the API response.
- **Signed JARs are rejected** because rewriting any entry invalidates the
  signature.
- **Response payload.** The whole artifact is base64-encoded in the JSON
  response, so large JARs make large responses.

See `AGENTS.md` for architecture and conventions.