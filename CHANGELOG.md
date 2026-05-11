# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and the project follows Semantic Versioning.

## Unreleased
### Changed
- Generated response specs now preserve OpenAPI array cardinality: arrays allow zero items by default, while `minItems` and `maxItems` are translated to spec count bounds when present.
- Generated schema specs now validate `enum` values against their declared allowed set.
- Generated response validation now handles inline schemas, including nested inline object/array schemas and nested `$ref` usage.
- Generated requests now accept security inputs separately from headers and apply supported OpenAPI security schemes to mock Ring requests.

### Added
- Initial library setup.
