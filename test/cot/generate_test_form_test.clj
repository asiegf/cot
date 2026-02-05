(ns cot.generate-test-form-test
  (:require [clojure.test :refer :all]
            [cot.generator :as gen]))

(defn- form-contains?
  [form sym]
  (boolean
   (some #(= sym %)
         (tree-seq coll? seq form))))

(deftest generate-test-form-array-response-test
  (let [operation {:parameters [{:in "query" :name "limit"}]
                   :responses {:200 {:content {:application/json
                                               {:schema {:type "array"
                                                         :items {:$ref "#/components/schemas/Item"}}}}}}
                   }
        form (gen/generate-test-form {} 'handler 'inputs :get "/items" operation)]
    (is (form-contains? form 'clojure.data.json/read-str))
    (is (form-contains? form 'clojure.spec.alpha/valid?))
    (is (form-contains? form 'clojure.core/every?))
    (is (form-contains? form 'ring.mock.request/query-string))))

(deftest generate-test-form-object-response-test
  (let [operation {:responses {:200 {:content {:application/json
                                               {:schema {:$ref "#/components/schemas/Status"}}}}}}
        form (gen/generate-test-form {} 'handler 'inputs :get "/status" operation)]
    (is (form-contains? form 'clojure.data.json/read-str))
    (is (form-contains? form 'clojure.spec.alpha/valid?))
    (is (not (form-contains? form 'clojure.core/every?)))))
