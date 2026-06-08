# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and the project follows Semantic Versioning.

## Unreleased
### Added
- Initial library setup.
- Ordered validation cases with duplicate endpoint coverage, explicit expected
  statuses, and runtime placeholder values loaded from EDN or `#env`.

### Changed
- `deftestgen` now favors ordered validation vectors and accepts an optional
  runtime-input EDN path as its fourth argument.
- Request inputs use `:params` and actual HTTP `:headers`; security values are
  supplied as headers rather than through a separate `:security` input.
- Invalid validation targets are reported as named failing tests so remaining
  validation cases still run.
