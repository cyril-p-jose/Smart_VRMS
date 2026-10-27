# Stage 2 dependency approval request

**Approved by the user on 25 October 2026 and added to the server build.** The earlier review below is retained. Downloaded hashes match; actual resolved dependency tree is recorded in `docs/evidence/stage2-auth/`. Do not request this approval again.

Reviewed 25 October 2026. **Not added to any POM or installed.** Official Maven POMs and JAR bytes were inspected over HTTPS; JARs stayed in memory and were not executed. Sizes and checksums below were measured, not estimates.

| Artifact | Purpose / why the JDK is insufficient | License | JAR bytes |
|---|---|---|---:|
| com.google.code.gson:gson:2.15.0 | Parse and serialize Supabase Auth/API JSON using strict explicit DTOs. Java 17 has HTTP support but no general JSON codec. | Apache 2.0 | 316396 |
| org.postgresql:postgresql:42.7.14 | Server-only PostgreSQL JDBC driver for transactions/RLS context. The JDK supplies JDBC interfaces, not a PostgreSQL protocol driver. | BSD 2-Clause | 1221004 |
| com.google.errorprone:error_prone_annotations:2.50.0 | Gson's declared compile annotation dependency; no Error Prone compiler/plugin is proposed. | Apache 2.0 | 20252 |
| org.checkerframework:checker-qual:3.55.1 | pgJDBC's declared runtime qualifier annotations; no Checker Framework processor is proposed. | MIT | 241885 |

Total four JARs: **1,799,537 bytes**, about 1.72 MiB before packaging. Base class files in all four artifacts are Java 8 bytecode (major 52), compatible with the Java 17 baseline. Actual application integration tests must still pass after approval.

Gson 2.15.0 supersedes the unapproved 2.14.0 planning candidate. The current release includes stricter Unicode/number parsing fixes and restricts InetAddress parsing to numeric addresses. Gson is in maintenance mode (bug fixes, limited new features), not an officially supported Google product. Use strict parsing, byte/depth limits, allowlisted DTOs, explicit ISO date adapters and disabled unsafe construction. Do not deserialize arbitrary polymorphic classes or use JSON to trust role metadata. [Project and requirements](https://github.com/google/gson), [release changes](https://github.com/google/gson/releases/tag/gson-parent-2.15.0).

pgJDBC 42.7.14 is the current Java 8+ driver. Its security page documents an authentication-property enforcement issue fixed in this version. Use trusted server configuration, parameter binding, TLS `verify-full`, connection/time limits and a restricted database role. No user-provided JDBC URLs, socket factories or authentication plugins. [Official download](https://jdbc.postgresql.org/download/), [security notices](https://jdbc.postgresql.org/security/).

The Gson project advisory page showed no published advisories at review; that is not a guarantee of no vulnerabilities. Annotation dependencies contain type metadata rather than a new networking service. License and base-bytecode checks were performed on their exact artifacts. No comprehensive independent vulnerability scan or PGP signature verification is claimed. Recheck notices and resolve the exact dependency tree before the first connected build, then preserve its checksums and notices. [Gson advisories](https://github.com/google/gson/security/advisories).

POM review: Gson brings error_prone_annotations; pgJDBC brings checker-qual. Upstream test-only JUnit/Guava/Truth dependencies are not consumer dependencies. pgJDBC's optional waffle-jna is excluded from the proposed scope. The two annotation artifacts have no additional consumer runtime dependencies. No FlatLaf, PDF, JUnit, framework or native authentication library is requested here.

## Recorded SHA256

```text
gson-2.15.0.jar
96D3DD843DC2911DE01CDABCD50064CB9E860353102A0A78C96FD833D396FC7D
postgresql-42.7.14.jar
73914527305A40CCE504B0D3D90B23CAF565136912D607AE8AE7C5895512332C
error_prone_annotations-2.50.0.jar
4667724877F1D37A689202DA191E23EFA7657C62EEF93CCDAC406ECCFE5CDD0A
checker-qual-3.55.1.jar
857658C8BDCBFF794949A8D9922F88D63EE3751C203C7CD7D21EA97F9E745288
```

These hashes identify the inspected HTTPS artifacts; they are not independent publisher signature verification. Sources are Maven Central under `https://repo.maven.apache.org/maven2/` using the exact coordinates above.

The foundational dependency policy requires your explicit approval. Reply: **Approve DEP-02: Gson 2.15.0, pgJDBC 42.7.14, error_prone_annotations 2.50.0 and checker-qual 3.55.1.** This authorizes only these libraries for Stage 2, not installation of new tools or deployment.
