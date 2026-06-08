(ns example.core-test
  (:require [clojure.java.io :as io]
            [cot.generator :refer [deftestgen]]
            [example.core :refer [app]]))

(defn- example-path [filename]
  (if (.exists (io/file filename))
    filename
    (str "example/" filename)))

(def openapi-spec-path (example-path "openapi.yaml"))
(def runtime-inputs-path (example-path "inputs.edn"))

;; Quote the cases so placeholder symbols are resolved from inputs.edn
;; when each generated test runs, rather than when this namespace is compiled.
(def validation
  '[["/status" 200] ;; explicit expected status
    ;; ["/xyz" {} 200] ;; uncomment to demonstrate a test-level failure
    ["/status"]
    ["/items"] ;; validates every returned item against components.schemas.Item
    ["/featured-items"] ;; also validates the declared array bounds
    [:get "/items/{id}"
     {:params {:id ITEM_ID, :mode MODE}}
     401]
    [:get "/items/{id}"
     {:params {:id ITEM_ID, :mode MODE}
      :headers {:token TOKEN}}
     200]
    ["/secure"
     {:headers {:Authorization AUTHORIZATION
                :profile PROFILE}}]
    ["/secure"
     {:headers {:Authorization AUTHORIZATION
                :profile "not-the-env-profile"}}
     401]])

(deftestgen
  app
  validation
  openapi-spec-path
  runtime-inputs-path)
