# COT Roadmap

This file captures a sensible roadmap for a Clojure library that generates tests from OpenAPI specs.

**Vision**
Turn an OpenAPI spec into runnable, maintainable `clojure.test` checks that validate real handlers and keep API behavior aligned with the spec.

**Current Capabilities**
- Parse OpenAPI 3.x YAML.
- Generate `clojure.spec` definitions for component schemas.
- Generate ordered `clojure.test` cases, including repeated cases for the same operation.
- Validate declared expected response statuses and their JSON schemas.
- Build mock Ring requests with path, query, and header inputs.
- Resolve runtime input placeholders from EDN literals or environment variables.
- Support the legacy operation-to-input map form for compatibility.
- Reload tests from the REPL via `reload-tests!`.

**Expected In The Near Term**
- Support more response content types.
- Support request bodies and cookies in generated tests.
- Better schema coverage: `enum`, `format`, `nullable`, `min/max`, `pattern`, `allOf/oneOf/anyOf`.
- Resolve `$ref` in more places, including request and response bodies.
- Generated or richer fixture-based test inputs.
- Clearer test output when validation fails.

**Expected In The Medium Term**
- CLI entry point for generating tests and specs outside the REPL.
- Optional snapshotting of generated tests to files.
- Integration with common runners (Kaocha) and CI-friendly output.
- Support OpenAPI 3.1 and JSON Schema features.
- Pluggable request builders to support different Ring stacks and middleware.

**Nice To Have**
- Property-based testing helpers and generators.
- Auto-suggest inputs from examples in the OpenAPI spec.
- Partial updates when the spec changes (watch mode).
- Documentation site with guided examples.

**Non-Goals (For Now)**
- Mock server generation.
- Full SDK/client generation.
- Server-side routing or handler scaffolding.

**Contributions**
If you want to contribute, focus on one capability at a time, include tests, and keep the generated output deterministic.
