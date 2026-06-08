# COT

**Status:** Experimental. APIs and behavior may change without notice.

COT is a Clojure library that generates `clojure.test` checks and `clojure.spec` definitions from an OpenAPI 3.x YAML file. It is designed to validate handler responses against the spec with minimal setup.

**Install**

If you are developing locally:

```clojure
{:deps {asiegf/cot {:local/root "/absolute/path/to/cot"}}}
```

If you are consuming from a Git repo:

```clojure
{:deps {asiegf/cot {:git/url "https://github.com/asiegf/cot" :git/sha "e046164514bed2e5dde34c34911ecc2a5479ab46"}}}
```

When published, replace the dependency with the Maven coordinates and version.

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
- Array `minItems` and `maxItems` bounds are enforced when present.
- Schema properties with `enum` constraints are validated against their allowed values.

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
