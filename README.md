# obfuscator

Orchestration layer for function-preserving binary obfuscation: submit a compiled
binary, get back a semantically equivalent one that is harder to analyse and
reconstruct.

Spring Boot REST service. The obfuscation engine itself is not implemented yet —
see "Status" below.

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

The response carries the transformed artifact base64-encoded, its SHA-256, the
steps that actually ran, and a functionality status.

```bash
curl -F "file=@target/App.class" -F "format=JVM_CLASS" \
     http://localhost:8080/api/v1/obfuscations
```

Rejected requests return RFC 9457 problem details with status 422.

## Steps

| step | scope | what it does |
|------|-------|--------------|
| `method-renaming` | `JVM_CLASS` | Replaces private method names with `a`, `b`, `c`, … |

`method-renaming` accepts a `keepMethods` option: a comma-separated list of
names to preserve, optionally scoped as `com.example.Owner#method`. Use it for
anything resolved reflectively at runtime or bound from outside the class, such
as JNI registration or Java serialization. The response reports which methods
were kept.

Class names, field names, and all non-private methods are left unchanged.

## Status

Implemented:

- **Method renaming**, plus a structural verifier that confirms the output is
  valid bytecode and differs from the input only in method names. This is what
  allows a result to report `functionalityStatus: VERIFIED`.

Not implemented:

- **Renaming anything beyond private methods.** Safe per-class renaming stops at
  private methods: a public or protected method can be called from another class
  or override a supertype method, and a per-class pass cannot rewrite those call
  sites. Widening this needs whole-program analysis across a JAR.
- **Class-name and field-name renaming.**
- **Other transformations** (string encryption, control-flow flattening, …).

Behaviour verification is currently structural, not empirical: it proves the
rewrite changed nothing but names, and unit tests execute original and
obfuscated classes side by side. It does not yet run a subject program before
and after to compare observable behaviour.

See `AGENTS.md` for architecture and conventions.