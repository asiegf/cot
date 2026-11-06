# COT

**Status:** Experimental. APIs and behavior may change without notice.

COT is a Clojure library that generates `clojure.test` checks and `clojure.spec` definitions from an OpenAPI 3.x YAML file. It is designed to validate handler responses against the spec with minimal setup.

**Install**

As a git dependency (versions are tagged `YYYY.MINOR.PATCH`, see `CHANGELOG.md`):

```clojure
{:deps {io.github.asiegf/cot {:git/tag "2026.1.0" :git/sha "@SHA@"}}}
```

If you are developing locally:

```clojure
{:deps {io.github.asiegf/cot {:local/root "/absolute/path/to/cot"}}}
```

`cot.sweep` validates with `com.networknt/json-schema-validator`, which depends on
Jackson. If your project pins another Jackson version (through cheshire, jsonista,
...), check the resolved one with `clj -X:deps tree` and, on a conflict, pin
`com.fasterxml.jackson.core/jackson-databind` explicitly. The validator logs through
SLF4J: without a provider on the classpath, SLF4J prints a one-time "No SLF4J
providers were found" warning, harmless; add a provider (e.g.
`org.slf4j/slf4j-nop`) to silence it.

**Usage**

Given a Ring handler and an OpenAPI YAML file:

```clojure
(ns my.api-test
  (:require [cot.generator :refer [deftestgen]]
            [my.api :refer [app]]))

(def validation
  '[["/items"]
    [:get "/items/{id}"
     {:params {:id ITEM_ID}
      :headers {:Authorization AUTHORIZATION}}]
    [:get "/items/{id}"
     {:params {:id OTHER_ITEM_ID}
      :headers {:Authorization AUTHORIZATION}}
     404]])

(deftestgen app validation "openapi.yaml" "runtime-inputs.edn")
```

Multiple `deftestgen` forms MAY coexist in one namespace. The validation var
names identify independent test groups, allowing public and authenticated
cases to target the same operation:

```clojure
(deftestgen app public openapi inputs)
(deftestgen app with-creds openapi inputs)
```

Each group exposes its own reload function, such as `reload-public-tests!` and
`reload-with-creds-tests!`. `reload-tests!` remains an alias for the most
recently declared group.

The ordered validation vector accepts these case forms:

```clojure
["/items"]                                  ; GET, no input, expect 200
["/items" {:params {:limit 10}}]            ; GET, expect 200
[:post "/items" {:headers {:token TOKEN}}]  ; explicit method, expect 200
[:get "/items/{id}" {:params {:id ID}} 404] ; explicit method and status
```

Cases remain in declaration order. The vector can contain the same endpoint
more than once, including cases with different inputs or expected statuses.
The method defaults to `:get` and the expected status defaults to `200`.
Expected statuses MUST be declared for the operation in the OpenAPI document;
an undocumented expected status or unknown operation generates a distinct
failing `clojure.test` instead of preventing the remaining cases from running.

Input maps accept only `:params` and `:headers`, and both values MUST be maps
when present. Path parameters are substituted into the URL, remaining
`:params` entries are sent as query parameters, and every `:headers` entry is
sent as an HTTP header.

**Runtime Inputs**

Quote the validation vector when it contains placeholder symbols. Pass an
optional runtime-input EDN file as the fourth argument to `deftestgen`:

```clojure
(deftestgen app validation "openapi.yaml" "runtime-inputs.edn")
```

The EDN file maps placeholder symbols to literal values:

```clojure
{ITEM_ID 42
 AUTHORIZATION "Bearer local-token"}
```

For secrets supplied by the environment, use the `#env` tagged literal:

```clojure
{ITEM_ID 42
 AUTHORIZATION #env "API_AUTHORIZATION"}
```

Runtime inputs are loaded when generated tests run. Placeholder resolution is
recursive within `:params` and `:headers`. A missing placeholder, missing
runtime-input file, or unset environment variable fails clearly instead of
sending an incomplete request. Keep non-secret, stable test values as EDN
literals; use `#env` only for values that must be supplied externally.

The legacy `{[method path] input-map}` form remains supported for compatibility,
but it cannot represent duplicate cases and is not recommended for new tests.
See `example/inputs.edn` for a runnable file that mixes literal values with
`PROFILE #env "USER"`. The example `/secure` handler requires the received
profile header to equal `USER`, and a second case verifies that a different
profile is rejected.

**Validation Notes**

- Paths MUST match OpenAPI paths, for example `"/items/{id}"`.
- Only listed cases generate tests.
- A declared JSON response schema is validated for the expected status.
- Responses without a declared JSON schema are status-checked only.
- Array `minItems` and `maxItems` bounds are enforced when present.
- Schema properties with `enum` constraints are validated against their allowed values.

**Sweep (spec-driven)**

`cot.sweep` runs *every* operation of a spec instead of a hand-written list.
Inputs are derived from the spec (overrides, then ids discovered at runtime,
then `example` / `default` / first `enum`); responses are checked for a
documented status, a documented content-type, and a body valid against the
JSON Schema of the media type matching the response's `Content-Type`
(networknt validator, OpenAPI 3.0 or 3.1 dialect from the `openapi` field).
Coverage fails on any in-scope operation neither exercised nor skipped with a
reason. Design and phases: `plans/sweep-engine.md`.

```clojure
(ns my.api-sweep-test
  (:require [cot.sweep :as cot :refer [defsweep]]
            [my.api :refer [app]]))

(def config
  {:spec       "openapi.yaml"                 ; classpath resource or file path
   :app        #'app
   :inputs     "test/inputs.edn"              ; placeholders, #env
   :scope      (cot/under "/items" "/places") ; default: every path
   :auth       {"Authorization" 'AUTHORIZATION}
   :params     {:limit 10}
   :op-params  {[:get "/items/{id}"] {:id 'ITEM_ID}}
   :resolvers  {"/places/{id}" {:from [:get "/places"] :extract "/0/id"}}
   :bodies     {[:post "/uploads"] {"file" {:file "test/sample.csv"
                                            :content-type "text/csv"}}}
   :transport  {[:get "/exports/items.csv"] :served}
   :query-style :openapi                      ; or :json, see below
   :scenarios  [lifecycle]                    ; fns of ctx calling cot/exercise!
   :scenario-ops #{[:delete "/items/{id}"]}   ; left to scenarios, not swept
   :skip       {[:get "/legends"] "calls a third-party service"}
   :generators #{:happy}})                    ; :auth is experimental

(defsweep api-sweep config)
```

- `defsweep` defines one `deftest` with a `testing` block per step (`sweep`,
  then scenarios, `auth`, `coverage`). The config is evaluated when the test
  runs, so no reload function is needed.
- `cot/run`, `cot/exercise!`, `cot/check-response`, `cot/check-coverage`,
  `cot/auth-sweep!` are public for projects composing tests differently.
- Request bodies take their media type from `requestBody.content` (JSON ones
  first). A `multipart/form-data` body is a map of parts: strings, `File`s,
  byte arrays, `{:file path}` or `{:value bytes :filename .. :content-type ..}`.
  Scenarios can pass `:multipart parts` to `exercise!`.
- Query parameters follow their `style` / `explode` (OpenAPI defaults: `form`,
  exploded, so `?ids=a&ids=b`, `?x=1&y=2` for an object); `spaceDelimited`,
  `pipeDelimited`, `deepObject` and `explode: false` are supported, and a parameter
  declared with `content: application/json` is sent as JSON. `:query-style :json`
  sends every array or object value as JSON text instead (`?ids=["a","b"]`), for APIs
  that parse their query that way without declaring it.
- The auth sweep (`:generators #{:happy :auth}`, `auth-sweep!`) is
  **experimental** and will be replaced by a matrix derived from `securitySchemes`
  (plan phase 3). It checks that each secured operation rejects a request without
  credentials with a documented 400/401/403; with `:profile-header` set, operations
  secured by an apiKey in that header must also reject a foreign value of it.
- Transports: `:ring` (in-process, default) and `:served` (throwaway http-kit
  server, for async-channel routes). `:served` needs `http-kit/http-kit` on
  the test classpath.
- A 404 with an empty or non-JSON body is reported as "route missing"
  (override with `:route-missing?`).
- `deftestgen` is unchanged; both can live side by side (see `example/`).

**Testing (Consumer Project)**

Add a minimal `:test` alias to your `deps.edn`:

```clojure
{:aliases
 {:test {:extra-paths ["test"]
         :extra-deps {io.github.cognitect-labs/test-runner {:git/tag "v0.5.1"
                                                            :git/sha "dfb30dd"}}
         :exec-fn cognitect.test-runner.api/test
         :exec-args {:dirs ["test"]}}}}
```

Run:

```sh
clj -X:test
```
