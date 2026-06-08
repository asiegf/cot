(ns cot.deftestgen-multiple-invocations-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cot.generator :refer [deftestgen]]))

(def spec-path
  (.getPath (io/resource "fixtures/openapi.yaml")))

(def public
  '[["/status"]])

(def with-creds
  '[["/status"]])

(defn handler
  [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:ok true :message "up"})})

(deftestgen handler public spec-path nil)
(deftestgen handler with-creds spec-path nil)

(deftest multiple-deftestgen-invocations-coexist
  (let [test-vars (ns-interns 'cot.deftestgen-multiple-invocations-test)]
    (is (contains? test-vars 'test-get-status-200-case-1-public))
    (is (contains? test-vars 'test-get-status-200-case-1-with-creds))
    (is (contains? test-vars 'reload-public-tests!))
    (is (contains? test-vars 'reload-with-creds-tests!))))

(deftest reloading-one-group-preserves-the-other
  ((ns-resolve 'cot.deftestgen-multiple-invocations-test
               'reload-public-tests!))
  (is (contains? (ns-interns 'cot.deftestgen-multiple-invocations-test)
                 'test-get-status-200-case-1-with-creds)))
