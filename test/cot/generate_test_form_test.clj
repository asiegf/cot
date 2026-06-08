(ns cot.generate-test-form-test
  (:require [clojure.test :refer [deftest is]]
            [cot.generator :as gen]))

(defn- form-contains?
  [form sym]
  (boolean
   (some #(= sym %)
         (tree-seq coll? seq form))))

(defn- validation-case
  [path input]
  {:case-index 1
   :method :get
   :path path
   :input input
   :expected-status 200
   :legacy? false})

(deftest generate-test-form-array-response-test
  (let [operation {:parameters [{:in "query" :name "limit"}]
                   :responses {:200 {:content {:application/json
                                               {:schema {:type "array"
                                                         :minItems 1
                                                         :items {:$ref "#/components/schemas/Item"}}}}}}
                   }
        form (gen/generate-test-form {}
                                     'handler
                                     (validation-case "/items" {})
                                     operation
                                     nil)]
    (is (form-contains? form 'clojure.data.json/read-str))
    (is (form-contains? form 'clojure.spec.alpha/valid?))
    (is (not (form-contains? form 'clojure.core/every?)))
    (is (form-contains? form 'ring.mock.request/query-string))))

(deftest generate-test-form-object-response-test
  (let [operation {:responses {:200 {:content {:application/json
                                               {:schema {:$ref "#/components/schemas/Status"}}}}}}
        form (gen/generate-test-form {}
                                     'handler
                                     (validation-case "/status" {})
                                     operation
                                     nil)]
    (is (form-contains? form 'clojure.data.json/read-str))
    (is (form-contains? form 'clojure.spec.alpha/valid?))
    (is (not (form-contains? form 'clojure.core/every?)))))

(deftest generate-test-form-header-params-test
  (let [operation {:parameters [{:in "header" :name "Authorization"}
                                {:in "header" :name "X-Request-Id"}]
                   :responses {:200 {:content {:application/json
                                               {:schema {:$ref "#/components/schemas/Status"}}}}}}
        form (gen/generate-test-form {}
                                     'handler
                                     (validation-case "/secure"
                                                      {:headers {:Authorization "token"}})
                                     operation
                                     nil)]
    (is (form-contains? form 'ring.mock.request/header))
    (is (form-contains? form 'clojure.core/reduce))))

(deftest generate-test-form-uses-expected-status-test
  (let [operation {:responses {:401 {:description "unauthorized"}}}
        case      {:case-index 2
                   :method :get
                   :path "/secure"
                   :input {}
                   :expected-status 401
                   :legacy? false}
        form      (gen/generate-test-form {} 'handler case operation nil)]
    (is (= 'test-get-secure-401-case-2 (second form)))
    (is (some #(= 401 %)
              (tree-seq coll? seq form)))))
