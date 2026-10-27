# Current distribution correction

During the independent release review, the user explicitly approved Gson 2.15.0 and its pinned error_prone_annotations dependency for desktop JSON. The client now bundles these two artifacts. Older claims below that the desktop has zero external dependencies are historical and superseded. Server code/JDBC/PDF/QR remain excluded from the client. The bundled OpenJDK runtime includes its legal notices under runtime/legal.

# Third-Party Software Notices and Licenses

Smart VRMS adheres strictly to a zero-unreviewed-dependencies contract. All client desktop modules (`contracts`, `application`, `desktop`) use pure OpenJDK 17 with **zero third-party dependencies**.

All external libraries are confined strictly to the backend `server` module and have been reviewed and approved per architectural decision logs.

---

## Summary of Approved Third-Party Libraries

| Group / Artifact | Version | Scope / Module | License | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `com.google.code.gson:gson` | 2.15.0 | `server` | Apache-2.0 | RFC 8259 strict JSON serialization and deserialization for Supabase Auth, REST payloads, and outbox event schemas. |
| `org.postgresql:postgresql` | 42.7.14 | `server` | BSD-2-Clause | SCRAM-SHA-256 authenticated PostgreSQL JDBC driver for connection pooling, RLS transaction-local settings, and relational persistence. |
| `com.google.errorprone:error_prone_annotations` | 2.50.0 | `server` | Apache-2.0 | Compile-time code analysis annotations for correctness. |
| `org.checkerframework:checker-qual` | 3.55.1 | `server` | MIT | Nullability and type safety checker qualifiers. |
| `org.apache.pdfbox:pdfbox` | 3.0.3 | `server` | Apache-2.0 | Approved in Stage 6 for PDF/A compliant tax invoice, receipt, and contract generation (FR-FIN-003). |
| `com.google.zxing:core` | 3.5.3 | `server` | Apache-2.0 | Approved in Stage 6 for dynamic NPCI-compliant UPI QR code generation (FR-FIN-002). |

---

## Client Bundle Isolation Verification

The desktop installer and distribution bundle (`dist/package-win64/SmartVRMS/app/`) contains strictly:
- `contracts-0.1.0-SNAPSHOT.jar`
- `application-0.1.0-SNAPSHOT.jar`
- `desktop-0.1.0-SNAPSHOT.jar`
- `gson-2.15.0.jar`
- `error_prone_annotations-2.50.0.jar`
- `SmartVRMS.cfg`

**Zero server JARs, zero PostgreSQL JDBC drivers, zero PDFBox/ZXing libraries, zero private keys, and zero database credentials or Auth-admin secrets are distributed in the desktop client bundle.**

---

## License Texts

### Apache License 2.0
Used by:
- Google Gson 2.15.0
- Google Error Prone Annotations 2.50.0
- Apache PDFBox 3.0.3
- Google ZXing Core 3.5.3

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

### BSD 2-Clause License
Used by:
- PostgreSQL JDBC Driver 42.7.14

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### MIT License
Used by:
- Checker Framework Qualifiers 3.55.1

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
