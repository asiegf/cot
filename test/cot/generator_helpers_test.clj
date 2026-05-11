(ns cot.generator-helpers-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
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

(deftest schema->spec-enum-test
  (testing "string enum emits a contains?-based predicate"
    (is (= '(clojure.core/partial clojure.core/contains? #{"active" "inactive"})
           (gen/schema->spec {:type "string"
                              :enum ["active" "inactive"]}))))
  (testing "integer enum emits a contains?-based predicate"
    (is (= '(clojure.core/partial clojure.core/contains? #{1 2 3})
           (gen/schema->spec {:type "integer"
                              :enum [1 2 3]}))))
  (testing "enum without type emits a contains?-based predicate"
    (is (= '(clojure.core/partial clojure.core/contains? #{"a" "b"})
           (gen/schema->spec {:enum ["a" "b"]}))))
  (testing "enum predicate preserves truthy values"
    (let [enum-spec (eval (gen/schema->spec {:type "string"
                                             :enum ["active" "inactive"]}))]
      (is (true? (s/valid? enum-spec "active")))
      (is (false? (s/valid? enum-spec "unknown")))))
  (testing "enum predicate accepts false enum values"
    (let [enum-spec (eval (gen/schema->spec {:type "boolean"
                                             :enum [true false]}))]
      (is (true? (s/valid? enum-spec false)))
      (is (true? (s/valid? enum-spec true)))
      (is (false? (s/valid? enum-spec nil)))))
  (testing "enum predicate accepts nil/null enum values"
    (let [enum-spec (eval (gen/schema->spec {:enum ["none" nil]}))]
      (is (true? (s/valid? enum-spec nil)))
      (is (true? (s/valid? enum-spec "none")))
      (is (false? (s/valid? enum-spec "other"))))))

(deftest generate-spec-defs-enum-false-and-null-test
  (let [openapi-spec {:components
                      {:schemas
                       {:EnumRegression
                        {:type "object"
                         :required ["enumFalseRegression"
                                    "enumNullRegression"]
                         :properties
                         {:enumFalseRegression {:type "boolean"
                                                :enum [true false]}
                          :enumNullRegression  {:enum ["none" nil]}}}}}}]
    (doseq [spec-form (gen/generate-all-specs openapi-spec)]
      (eval spec-form))
    (is (true? (s/valid? :cot.schema/enumFalseRegression false)))
    (is (true? (s/valid? :cot.schema/enumFalseRegression true)))
    (is (false? (s/valid? :cot.schema/enumFalseRegression nil)))
    (is (true? (s/valid? :cot.schema/enumNullRegression nil)))
    (is (true? (s/valid? :cot.schema/enumNullRegression "none")))
    (is (false? (s/valid? :cot.schema/enumNullRegression "other")))
    (is (true? (s/valid? :cot.schema/EnumRegression
                         {:enumFalseRegression false
                          :enumNullRegression nil})))))

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
