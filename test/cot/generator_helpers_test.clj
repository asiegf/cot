(ns cot.generator-helpers-test
  (:require [clojure.test :refer :all]
            [cot.generator :as gen]))

(deftest openapi-type->predicate-test
  (is (= 'clojure.core/string? (gen/openapi-type->predicate {:type "string"})))
  (is (= 'clojure.core/int? (gen/openapi-type->predicate {:type "integer"})))
  (is (= 'clojure.core/number? (gen/openapi-type->predicate {:type "number"})))
  (is (= 'clojure.core/boolean? (gen/openapi-type->predicate {:type "boolean"})))
  (is (= 'clojure.core/sequential? (gen/openapi-type->predicate {:type "array"})))
  (is (= 'clojure.core/map? (gen/openapi-type->predicate {:type "object"})))
  (is (= 'clojure.core/any? (gen/openapi-type->predicate {:type "unknown"}))))

(deftest schema->spec-object-test
  (let [schema {:type "object"
                :required ["id"]
                :properties {:id {:type "integer"}
                             :name {:type "string"}}}]
    (is (= '(clojure.spec.alpha/keys
             :req-un [:cot.schema/id]
             :opt-un [:cot.schema/name])
           (gen/schema->spec schema)))))

(deftest schema->spec-array-test
  (is (= '(clojure.spec.alpha/coll-of clojure.core/string? :min-count 1)
         (gen/schema->spec {:type "array"
                            :items {:type "string"}}))))

(deftest keyword->path-str-test
  (is (= "/items/{id}"
         (gen/keyword->path-str (keyword "" "items/{id}"))))
  (is (= "/items"
         (gen/keyword->path-str :items)))
  (is (= "/items"
         (gen/keyword->path-str "/items"))))

(deftest path-template->request-path-test
  (is (= "/items/0"
         (gen/path-template->request-path "/items/{id}" {:id 0}))))

(deftest operation->test-name-test
  (is (= 'test-get-items-id
         (gen/operation->test-name :get "/items/{id}"))))

(deftest response-spec-keyword-test
  (let [op {:responses {:200 {:content {:application/json
                                        {:schema {:$ref "#/components/schemas/Item"}}}}}}]
    (is (= :cot.schema/Item
           (#'gen/response-spec-keyword nil op))))
  (let [op {:responses {:200 {:content {:application/json
                                        {:schema {:type "array"
                                                  :items {:$ref "#/components/schemas/Item"}}}}}}}]
    (is (= :cot.schema/Item
           (#'gen/response-spec-keyword nil op)))
    (is (true? (#'gen/array-response? op)))))
