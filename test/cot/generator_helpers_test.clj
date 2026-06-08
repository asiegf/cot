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
  (is (= '(clojure.spec.alpha/coll-of clojure.core/string?)
         (gen/schema->spec {:type "array"
                            :items {:type "string"}}))))

(deftest schema->spec-array-min-items-test
  (is (= '(clojure.spec.alpha/coll-of clojure.core/string? :min-count 1)
         (gen/schema->spec {:type "array"
                            :minItems 1
                            :items {:type "string"}}))))

(deftest schema->spec-array-max-items-test
  (is (= '(clojure.spec.alpha/coll-of clojure.core/string? :max-count 2)
         (gen/schema->spec {:type "array"
                            :maxItems 2
                            :items {:type "string"}}))))

(deftest schema->spec-array-bounds-test
  (is (= '(clojure.spec.alpha/coll-of clojure.core/string? :min-count 1 :max-count 2)
         (gen/schema->spec {:type "array"
                            :minItems 1
                            :maxItems 2
                            :items {:type "string"}}))))

(deftest schema->spec-enum-test
  (testing "string enum returns a contains?-based predicate"
    (is (= '(clojure.core/partial clojure.core/contains? #{"active" "inactive"})
           (gen/schema->spec {:type "string"
                              :enum ["active" "inactive"]}))))
  (testing "integer enum returns a contains?-based predicate"
    (is (= '(clojure.core/partial clojure.core/contains? #{1 2 3})
           (gen/schema->spec {:type "integer"
                              :enum [1 2 3]}))))
  (testing "enum without type returns a contains?-based predicate"
    (is (= '(clojure.core/partial clojure.core/contains? #{"a" "b"})
           (gen/schema->spec {:enum ["a" "b"]}))))
  (testing "enum predicate preserves truthy values"
    (let [spec-form (gen/schema->spec {:type "string" :enum ["active" "inactive"]})
          enum-spec (eval spec-form)]
      (is (true? (s/valid? enum-spec "active")))
      (is (false? (s/valid? enum-spec "unknown")))))
  (testing "enum predicate accepts false enum values"
    (let [spec-form (gen/schema->spec {:type "boolean" :enum [true false]})
          enum-spec (eval spec-form)]
      (is (true? (s/valid? enum-spec false)))
      (is (true? (s/valid? enum-spec true)))
      (is (false? (s/valid? enum-spec nil)))))
  (testing "enum predicate accepts nil/null enum values"
    (let [spec-form (gen/schema->spec {:enum ["none" nil]})
          enum-spec (eval spec-form)]
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

(deftest response-spec-test
  (testing "top-level $ref returns the cot.schema keyword"
    (let [op {:responses {:200 {:content {:application/json
                                          {:schema {:$ref "#/components/schemas/Item"}}}}}}]
      (is (= :cot.schema/Item
             (#'gen/response-spec nil op)))))
  (testing "array of $ref reduces to the item keyword"
    (let [op {:responses {:200 {:content {:application/json
                                          {:schema {:type "array"
                                                    :items {:$ref "#/components/schemas/Item"}}}}}}}]
      (is (= '(clojure.spec.alpha/coll-of :cot.schema/Item)
             (#'gen/response-spec nil op)))
      ))
  (testing "array response preserves minItems and maxItems"
    (let [op {:responses {:200 {:content {:application/json
                                          {:schema {:type "array"
                                                    :minItems 1
                                                    :maxItems 2
                                                    :items {:$ref "#/components/schemas/Item"}}}}}}}]
      (is (= '(clojure.spec.alpha/coll-of :cot.schema/Item :min-count 1 :max-count 2)
             (#'gen/response-spec nil op)))))
  (testing "inline response object with a nested $ref property produces an s/keys form"
    (let [op {:responses {:200 {:content {:application/json
                                          {:schema {:type "object"
                                                    :required ["owner"]
                                                    :properties {:owner {:$ref "#/components/schemas/Item"}}}}}}}}]
      (is (= '(clojure.spec.alpha/keys :req-un [:cot.schema/owner])
             (#'gen/response-spec nil op))))))

(deftest schema->spec-ref-test
  (is (= :cot.schema/Item
         (gen/schema->spec {:$ref "#/components/schemas/Item"}))))

(deftest schema->spec-array-of-ref-test
  (is (= '(clojure.spec.alpha/coll-of :cot.schema/Item)
         (gen/schema->spec {:type "array"
                            :items {:$ref "#/components/schemas/Item"}}))))

(deftest schema->spec-array-of-ref-with-bounds-test
  (is (= '(clojure.spec.alpha/coll-of :cot.schema/Item :min-count 1 :max-count 2)
         (gen/schema->spec {:type "array"
                            :minItems 1
                            :maxItems 2
                            :items {:$ref "#/components/schemas/Item"}}))))

(deftest schema->spec-object-with-ref-property-test
  (let [schema {:type "object"
                :required ["address"]
                :properties {:address {:$ref "#/components/schemas/Address"}
                             :notes   {:type "string"}}}]
    (is (= '(clojure.spec.alpha/keys
             :req-un [:cot.schema/address]
             :opt-un [:cot.schema/notes])
           (gen/schema->spec schema)))))
