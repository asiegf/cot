# Plan: a spec-driven sweep engine in cot

Status: draft for review, 2026-10-30. The engine was first prototyped in a consumer
project's test support (~500 lines) on top of `cot.parser` and
`cot.generator/read-runtime-inputs`. Nothing in cot has changed yet.

## 1. Goal

From an OpenAPI document, a ring handler and a small config, cot runs **every
operation on every documented response status** and reports, per operation and status,
whether it was exercised, skipped with a reason, or missing. Valid paths (2xx) and error
paths (4xx, and 5xx where triggerable) are both covered. Drift is reported, never
silently tolerated.

Today `deftestgen` checks only the cases written by hand (method, path, inputs, expected
status). In the prototype, 15 hand-written cases covered ~15 of ~100 operations; the
sweep that replaced them found 54 drifts on its first green run.

## 2. What the prototype engine does (baseline to port)

- **Spec loading**: YAML via `cot.parser`, one JSON-Schema registry per spec (networknt
  `json-schema-validator` 2.0.0), OpenAPI 3.0 or 3.1 dialect from the `openapi` field.
  Responses are validated at their JSON pointer in the document, so `$ref`, `nullable`,
  `oneOf`, `allOf` and `format` behave as the spec says.
- **Operations**: all `paths × methods`, path- and operation-level parameters merged,
  filtered by a scope predicate on the path.
- **Input derivation**, per parameter: override (global, then per operation) → id
  discovered at runtime → `example` / `default` / first `enum` → failure naming the
  parameter. Optional parameters are sent only when the spec gives an example.
- **Request bodies**: fixture (value or fn of ctx) → media type `example` → failure if
  required. JSON and multipart (ring-mock 0.6).
- **Id discovery**: resolvers `path-prefix → {:from [method path] :column "id"}` or a
  fn; values memoised for the run.
- **Checks**: status in the documented 2xx (or the expected one); content-type in the
  documented media types; body valid against the schema; a catch-all 404 reported as
  "route missing"; errors grouped by pattern with array indices collapsed.
- **Coverage**: in-scope operations = exercised ∪ skipped; stale skips fail too.
- **Scenarios**: plain fns calling `exercise!` with explicit inputs, for mutations and
  ordered lifecycles (ingest → read → patch → delete), cleanup left to the project.
- **Auth sweep**: for each secured operation, no credentials and a foreign profile must
  get a documented 400/401/403 with a valid body. The foreign profile case only applies
  when the operation's security uses the profile header.
- **Transports**: in-process ring call by default; operations in `:served` run through
  a throwaway http-kit server (async-channel routes such as CSV streams).
- **Skips**: `:skip {[method path] reason}`, `:live-only` (skipped unless a system
  property is set).

Project-specific parts MUST stay in the consumer project: custom id extractors,
database restore helpers, stubs of third-party services, scopes.

## 3. What's missing to cover error paths

1. **Coverage is per operation, not per status.** An operation with 200, 400, 401, 404
   and 409 documented counts as covered once its 200 passes.
2. **No negative inputs.** 400 and 422 are only hit by accident.
3. **404 is never targeted.** No "unknown id" derivation.
4. **Auth matrix is fixed** to two cases and one profile header name.
5. **The requests we send are not validated.** A "valid" request that isn't (wrong
   body shape) produces a 400 that looks like server drift; a "negative" request that is
   actually valid proves nothing.
6. **Media type choice**: the engine validates against the first JSON-ish media type,
   not the one matching the response's content-type (wrong for `application/json` vs
   `application/geo+json` alternatives).
7. **Reverse drift** (routes absent from the spec) is invisible.

## 4. Design

### 4.1 Coverage unit: (operation, status)

The engine MUST track `[method path status]` triples. For each in-scope operation and
each documented status, the outcome is one of:

- `:exercised`: at least one request got that status and passed all checks;
- `:skipped`: listed in `:skip` with a reason (key `[method path]` skips every status,
  `[method path status]` one status);
- `:untriggerable`: statuses in `:untriggerable` (default `#{500 502 503 504}`), which
  are reported but don't fail;
- `:missing`: anything else. A missing triple MUST fail the coverage test.

A response with an undocumented status MUST fail, whatever the case expected.

The coverage result SHOULD also be written as data (`target/cot/coverage.edn`, an
operation × status matrix) for CI dashboards and review.

### 4.2 Case generation

Each case is `{:op [method path] :expect status-or-class :request {...} :why string}`.
Generators, each MAY be turned off in the config:

| Generator | Targets | Derivation |
|---|---|---|
| `:happy` | documented 2xx | §2 input derivation (today's sweep) |
| `:missing-required` | 400/422 | drop one required param / body property at a time |
| `:wrong-type` | 400/422 | `"cot-not-a-number"` for numbers, `[]` for strings, ... |
| `:out-of-range` | 400/422 | enum value + `-cot`, `maximum + 1`, `maxLength + 1`, pattern breaker |
| `:unknown-id` | 404 | path param value that can't exist (`:unknown` per param, else derived from its schema: random UUID, max int, `cot-missing-<n>`) |
| `:auth` | 401/403 (and 400 when documented for missing credentials) | §4.3 |

Rules:

- A negative case MUST change exactly one thing from a valid request, so a failure names
  its cause.
- Negative cases accept any documented 4xx in the target class unless the config pins
  one (`:expect {[method path :missing-required] 422}`).
- The engine MUST validate its own requests against the spec before sending them:
  happy requests MUST be valid, negative ones MUST be invalid for the stated reason.
  A violation is a harness error, reported apart from server drift.
- Negative cases on unsafe methods (POST, PUT, PATCH, DELETE) MUST be opt-in
  (`:negative-mutations true`). If the server wrongly accepts the request, it mutates
  data; projects enabling them SHOULD run against a disposable database.
- 409, 429 and business-rule 4xx are not derivable; they come from scenarios or skips.

### 4.3 Auth matrix from `securitySchemes`

From the operation's security requirements (OR of ANDs, with the spec-level default),
for each requirement alternative and each apiKey/http scheme in it:

- `missing`: the scheme's credential removed (all others valid);
- `invalid`: a malformed or unknown value;
- `foreign`: a valid credential of another principal, when the config provides one
  (`:principals {:other {...headers}}`), e.g. another profile or tenant.

`security: []` operations get no auth cases. Header names come from the schemes, not
from the config (no more `:profile-header`).

### 4.4 Checks

Per response:

- status documented for the operation (always), and in the case's expected set;
- content-type matches a documented media type of that status;
- body validated against the schema **of the media type matching the content-type**,
  falling back to the first JSON one;
- route-missing detection through a config predicate (`:route-missing?`, default: 404
  with an empty or non-JSON body), reported as "route missing (spec/route drift)";
- optional: documented response headers present.

Failure messages keep today's format: `METHOD path [status] ...`, grouped errors,
first 300 chars of an unexpected body.

### 4.5 Reverse drift (optional)

`:routes` MAY be a fn returning the app's `[method path-template]` pairs (projects using
reitit can list them; compojure projects can provide a static list). Routes absent from
the spec are reported, as a separate test so it can be adopted later.

### 4.6 Transports

A `Transport` protocol: `(send! t request) → ring response`.

- `ring` (default): in-process handler call with a ring-mock request;
- `served`: throwaway http-kit server on port 0, http-kit client, response headers
  normalised to strings (for async-channel routes);
- `remote`: base URL of a running server (staging smoke runs; mutating cases disabled
  unless explicitly allowed).

Per-operation transport through `:transport {[method path] :served}`.

### 4.7 Public API

Data-first, with a thin macro:

```clojure
(def config
  {:spec      "openapi.yaml"              ; classpath resource or file
   :app       #'app
   :inputs    "test/inputs.edn"           ; runtime placeholders, #env
   :scope     (cot/under "/orders" "/items")
   :auth      {"token" 'TOKEN "profile" 'PROFILE}
   :principals {:other {"token" 'TOKEN "profile" "OtherProfile"}}
   :params    {:region 'REGION}
   :op-params {[:get "/search"] {:q "abc"}}
   :resolvers {"/items/{id}" {:from [:get "/items"] :extract (cot/json-pointer "/0/id")}}
   :bodies    {[:post "/items/export"] (fn [ctx] [(cot/id ctx "/items/{id}")])}
   :scenarios [#'lifecycle]               ; fns of ctx calling cot/exercise!
   :generators #{:happy :missing-required :wrong-type :unknown-id :auth}
   :skip      {[:get "/rates"] "calls a third-party service"
               [:post "/items" 429] "rate limit not reachable locally"}})

(cot/defsweep items config)
;; => deftests items-contract (happy + scenarios), items-errors, items-auth,
;;    items-coverage, and reload-items-sweep! for the REPL
```

`cot/run`, `cot/exercise!`, `cot/check-response`, `cot/coverage` stay public for
projects that compose tests differently.

### 4.8 Namespaces

| Namespace | Content |
|---|---|
| `cot.parser` | unchanged, plus parameter merging and security resolution |
| `cot.schema` | registry per spec, dialect choice, `validate`, error grouping |
| `cot.inputs` | runtime inputs (moved from `cot.generator`, alias kept) |
| `cot.request` | input derivation, body choice, request building, request validation |
| `cot.cases` | generators of §4.2-4.3 |
| `cot.discovery` | resolvers, extractors (`json-pointer`, fn) |
| `cot.transport` | §4.6 |
| `cot.sweep` | context, `exercise!`, `run`, coverage, report, `defsweep` |
| `cot.generator` | `deftestgen`, unchanged at first (§6 phase 5) |

### 4.9 Dependencies

- `com.networknt/json-schema-validator` (pulls Jackson). cot is a test-time library, so
  the weight is acceptable; it's also the only validator found with OpenAPI 3.0
  `nullable` and 3.1 dialects.
- `ring/ring-mock` 0.4.0 → 0.6.x (multipart).
- `http-kit` only for the `served` and `remote` transports: an optional dependency,
  required lazily (`requiring-resolve`) with a clear error if absent.

## 5. Testing cot itself

- Extend `example/` with routes for every status class (400 on bad param, 401/403,
  404 on unknown id, 409, an async route) and deliberate drifts behind a flag.
- Self-tests asserting the engine **fails** where it must: undocumented status, schema
  mismatch, wrong content-type, route missing, missing (op, status) triple, stale skip,
  happy request invalid against the spec, negative request accidentally valid.
- Golden coverage matrix for the example (`coverage.edn` compared to a fixture).
- Keep `clj -X:test` and `clj -X:test-integration` green at each phase.

## 6. Phases

1. **Port** the prototype engine as is (§2) into the namespaces of §4.8, with the
   per-media-type validation fix (§3.6). The prototype shrinks to a re-export; its run
   MUST give the same failures and assertion count (parity check).
2. **Status-level coverage** (§4.1), skips by status, `coverage.edn`.
3. **Request self-validation** (§3.5), then the `:auth` matrix (§4.3).
4. **Negative generators** (§4.2): `:missing-required`, `:wrong-type`, `:unknown-id`,
   then `:out-of-range`; mutations opt-in.
5. **`deftestgen` on the engine**: hand-written cases become `:cases` of a sweep config;
   the clojure.spec validation path is deprecated (kept one release, then removed).
6. **Transports** `remote`, reverse drift (§4.5), CLI entry point (`clj -X cot/sweep`).

Each phase is a bead; phases 1-2 touch the same hot zone and run sequentially.

## 7. Consumer projects after the move

Stays in the consumer project: scopes, base configs and fixtures, the scenarios, custom
id extractors (passed as `:extract`), database restore helpers, stubs of third-party
services, guards on the test environment. Hand-written `deftestgen` tests can be
retired once the sweep is green.

## 8. Open questions for the review

1. Keep `deftestgen` as a front-end of the engine (phase 5), or freeze it and document
   the sweep as the successor?
2. Negative mutations: opt-in per config, or only behind a "disposable database"
   assertion the project provides (e.g. a local-database check)?
3. Should an `:untriggerable` status still need listing per operation, so 5xx coverage
   is a conscious choice?
4. Generator output for `:wrong-type` and `:out-of-range`: fixed sentinel values
   (deterministic, readable failures) or test.check generators (wider, needs seeds)?
5. Macro (`defsweep`) generating several deftests, or one deftest per scope with
   `testing` blocks (the prototype's shape)? Several deftests run and fail separately
   but need the reload-function dance `deftestgen` already has.
6. OpenAPI 3.1 `type: [string, "null"]` vs 3.0 `nullable`: any normalisation, or rely on
   the dialect only?
