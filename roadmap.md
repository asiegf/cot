# COT Roadmap

This document outlines the current direction for COT, a Clojure library for generating `clojure.test` checks and `clojure.spec` definitions from OpenAPI specifications.

The roadmap is intended to help users understand what COT supports today, what is planned for the first public release, and where the project is likely to go next. It is directional rather than a strict promise.

**Vision**
Turn an OpenAPI spec into runnable, maintainable tests that validate real handlers and help keep API behavior aligned with the contract.

**What COT Supports Today**
- Parsing OpenAPI 3.x YAML files.
- Generating `clojure.spec` definitions from component schemas.
- Generating `clojure.test` tests for operations explicitly listed in an inputs map.
- Validating `200` `application/json` responses against generated specs.
- Validating array response schemas as zero-or-more items by default, with `minItems` and `maxItems` enforced when present.
- Validating inline `200` `application/json` response object and array schemas, including nested inline properties and nested component `$ref` targets.
- Building mock Ring requests with path parameters, query parameters, and headers.
- Reloading generated tests from the REPL via `reload-tests!`.

**What `0.1.0` Is Meant To Deliver**

The first release is intended to establish a clear, documented, and reliable narrow scope rather than cover the full OpenAPI feature set.

For `0.1.0`, the project should provide:
- A stable initial public API around the current core workflow.
- Clear installation and usage documentation for consumers.
- Published package coordinates and a documented versioning approach.
- A reproducible release process for tagging, changelog updates, and artifact publishing.
- Continuous integration that runs the test suite on every push and pull request.
- Basic repository hygiene, including ignoring local development artifacts and cutting releases from a clean worktree.
- A `0.1.0` changelog entry that describes the initial supported feature set.

Before `0.1.0`, the project should also either address or clearly document the current limitations that may surprise users:
- Response validation currently focuses on status `200` with `application/json` content.
- Inline response schema validation is supported for the current JSON response path, but broader OpenAPI schema features such as `nullable`, `format`, numeric/string bounds, `pattern`, `allOf`, `oneOf`, and `anyOf` remain limited.
- Generated property specs can collide when different schemas reuse the same property name.

Release-critical test coverage for `0.1.0` should include:
- Empty arrays versus `minItems` and `maxItems`.
- Inline object and array response schemas.
- Nested inline and `$ref`-resolved schemas.
- Spec-name and property-name collisions across schemas.

**Planned After `0.1.0`**
- Support additional status codes and response content types.
- Support request bodies, headers, and cookies more comprehensively in generated tests.
- Improve schema coverage for features such as `format`, `nullable`, `min/max`, `pattern`, `allOf`, `oneOf`, and `anyOf`.
- Resolve `$ref` in more places, including request and response bodies.
- Improve validation failure output.
- Add configurable defaults and richer test input options, such as fixtures or generators.

**Longer-Term Direction**
- A CLI entry point for generating tests and specs outside the REPL.
- Optional snapshotting of generated tests to files.
- Integration with additional test runners such as Kaocha.
- Broader OpenAPI 3.1 and JSON Schema support.
- Pluggable request builders for different Ring stacks and middleware.

**Ideas That May Come Later**
- Property-based testing helpers and generators.
- Suggested inputs derived from OpenAPI examples.
- Partial regeneration or watch-mode workflows when the spec changes.
- More comprehensive documentation and guided examples.

**Non-Goals For Now**
- Mock server generation.
- Full SDK or client generation.
- Server-side routing or handler scaffolding.

**Contributing**
Contributions are welcome. The most useful contributions for this stage of the project are focused improvements with tests, clear behavior changes, and deterministic generated output.
