# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and the project follows Semantic Versioning.

## Unreleased

## 2026.1.0 - 2026-11-06

First tagged release. Coordinates: `io.github.asiegf/cot` (git dependency).

### Added
- Query parameters are serialized per their OpenAPI `style` / `explode` (`form`,
  `spaceDelimited`, `pipeDelimited`, `deepObject`); `content: application/json`
  parameters are sent as JSON. `:query-style :json` keeps the previous behaviour
  (arrays and objects as JSON text).
- `cot.sweep`: spec-driven sweep of every in-scope operation (phase 1 of
  `plans/sweep-engine.md`), with `cot.schema`, `cot.inputs`, `cot.request`,
  `cot.discovery` and `cot.transport`. `defsweep` defines one `deftest` per
  sweep with `testing` blocks.
- Responses are validated against the schema of the documented media type
  matching their `Content-Type` (exact, then wildcard), falling back to the
  first JSON one only when none matches; `application/json` and
  `application/geo+json` alternatives on one status are told apart.
- `multipart/form-data` request bodies, including binary parts.
- `:served` transport (http-kit, optional dependency) for async-channel routes.
- OpenAPI 3.0 `nullable: true` next to a type-less `anyOf` is honoured by the
  networknt 3.0 dialect (`null` accepted; the same `anyOf` without `nullable`
  rejects it). Checked by a self-test, no workaround involved.
- Initial library setup.
- Ordered validation cases with duplicate endpoint coverage, explicit expected
  statuses, and runtime placeholder values loaded from EDN or `#env`.

### Changed
- The sweep sends array and object query values the OpenAPI way by default
  (`?ids=a&ids=b`), no longer as JSON text; set `:query-style :json` for the old
  behaviour.
- The auth sweep is marked experimental, and its foreign-profile case only runs when
  `:profile-header` is set (it defaulted to `"profile"`).
- `ring/ring-mock` 0.4.0 -> 0.6.2 (multipart); new dependency
  `com.networknt/json-schema-validator` 2.0.0.
- `read-runtime-inputs` and `resolve-runtime-values` moved to `cot.inputs`;
  `cot.generator` keeps them as aliases.
- Generated property specs are scoped by their owning component or response,
  preventing unrelated schemas with common property names from overwriting
  each other's validation rules.
- Multiple `deftestgen` invocations can coexist in one namespace. Validation
  var names identify independent generated-test groups, including distinct
  test names and group-specific reload functions.
- `deftestgen` now favors ordered validation vectors and accepts an optional
  runtime-input EDN path as its fourth argument.
- Request inputs use `:params` and actual HTTP `:headers`; security values are
  supplied as headers rather than through a separate `:security` input.
- Invalid validation targets are reported as named failing tests so remaining
  validation cases still run.
