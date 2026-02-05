# COT

**Status:** Experimental. APIs and behavior may change without notice.

COT is a Clojure library that generates `clojure.test` checks and `clojure.spec` definitions from an OpenAPI 3.x YAML file. It is designed to validate handler responses against the spec with minimal setup.

**Install**

If you are developing locally:

```clojure
{:deps {cot/cot {:local/root "/absolute/path/to/cot"}}}
```

If you are consuming from a Git repo:

```clojure
{:deps {cot/cot {:git/url "https://github.com/your-org/cot"
                :git/sha "PUT_SHA_HERE"}}}
```

When published, replace the dependency with the Maven coordinates and version.

**Usage**

Given a Ring handler and an OpenAPI YAML file:

```clojure
(ns my.api-test
  (:require [clojure.test :refer :all]
            [cot.generator :refer [deftestgen]]
            [my.api :refer [app]]))

(def inputs
  {[:get "/status"] {}
   [:get "/items"] {}
   [:get "/items/{id}"] {:id 0}})

(deftestgen app inputs "openapi.yaml")
```

What this does:
- Parses the OpenAPI file.
- Generates `clojure.spec` definitions for component schemas.
- Generates `clojure.test` tests for only the `[method path]` keys present in `inputs`.
- Validates `200` JSON responses against the corresponding schema.

**Notes**
- Paths should match OpenAPI paths (e.g. `"/items/{id}"`).
- Only endpoints listed in `inputs` get tests.
- Current validation focuses on `application/json` responses with status `200`.

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
