# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and the project follows Semantic Versioning.

## Unreleased
### Added
- Initial library setup.
- Ordered validation cases with duplicate endpoint coverage, explicit expected
  statuses, and runtime placeholder values loaded from EDN or `#env`.

### Changed
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
