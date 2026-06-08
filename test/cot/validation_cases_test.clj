(ns cot.validation-cases-test
  (:require [clojure.test :refer [deftest is]]
            [cot.generator :as gen]))

(deftest normalize-ordered-validation-cases-test
  (let [[default-case status-case explicit-case]
        (gen/normalize-validations
         '[["/status"]
           ["/status" 203]
           [:post "/items" {:params {:id ITEM_ID}} 201]])]
    (is (= {:case-index 1
            :method :get
            :path "/status"
            :input {}
            :expected-status 200
            :legacy? false}
           default-case))
    (is (= 203 (:expected-status status-case)))
    (is (= {} (:input status-case)))
    (is (= :post (:method explicit-case)))
    (is (= 201 (:expected-status explicit-case)))
    (is (= '{:params {:id ITEM_ID}} (:input explicit-case)))))

(deftest duplicate-cases-have-distinct-test-names-test
  (let [cases (gen/normalize-validations
               '[["/status"]
                 ["/status"]])]
    (is (= ['test-get-status-200-case-1
            'test-get-status-200-case-2]
           (mapv gen/validation-case->test-name cases)))))

(deftest legacy-map-remains-supported-test
  (let [[case] (gen/normalize-validations
                {[:get "/status"] {}})]
    (is (:legacy? case))
    (is (= 'test-get-status
           (gen/validation-case->test-name case)))))

(deftest validation-inputs-must-use-param-and-header-maps-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"headers must be a map"
                        (gen/normalize-validations
                         '[["/status" {:headers false}]])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"only :params and :headers"
                        (gen/normalize-validations
                         '[["/status" {:security {:token TOKEN}}]]))))

(deftest runtime-placeholders-resolve-recursively-test
  (is (= {:params {:id 42}
          :headers {:Authorization "Bearer secret"}}
         (gen/resolve-runtime-values
          '{:params {:id ITEM_ID}
            :headers {:Authorization AUTHORIZATION}}
          '{ITEM_ID 42
            AUTHORIZATION "Bearer secret"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"placeholder is missing"
                        (gen/resolve-runtime-values
                         '{:params {:id ITEM_ID}}
                         {}))))

(deftest runtime-input-edn-test
  (let [file (java.io.File/createTempFile "cot-runtime-inputs" ".edn")]
    (try
      (spit file "{ITEM_ID 42, MODE \"full\"}")
      (is (= '{ITEM_ID 42, MODE "full"}
             (gen/read-runtime-inputs (.getPath file))))
      (finally
        (.delete file)))))

(deftest runtime-input-edn-must-contain-map-test
  (let [file (java.io.File/createTempFile "cot-runtime-inputs" ".edn")]
    (try
      (spit file "[1 2 3]")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"must contain a map"
                            (gen/read-runtime-inputs (.getPath file))))
      (finally
        (.delete file)))))

(deftest runtime-input-env-must-exist-test
  (let [file     (java.io.File/createTempFile "cot-runtime-env" ".edn")
        env-name (str "COT_MISSING_" (random-uuid))]
    (try
      (spit file (str "{TOKEN #env \"" env-name "\"}"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"environment variable is missing"
                            (gen/read-runtime-inputs (.getPath file))))
      (finally
        (.delete file)))))

(deftest validation-status-must-be-documented-test
  (let [case (first (gen/normalize-validations
                     '[[:get "/secure" {} 401]]))]
    (is (= {:responses {:401 {:description "unauthorized"}}}
           (gen/validate-validation-operation
            {:responses {:401 {:description "unauthorized"}}}
            case)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected status is absent"
                          (gen/validate-validation-operation
                           {:responses {:200 {}}}
                           case)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"operation is absent"
                          (gen/validate-validation-operation nil case)))))

(deftest invalid-validation-generates-failing-test-form
  (let [case  (first (gen/normalize-validations
                      '[["/status" 203]]))
        error (ex-info "Expected status is absent from OpenAPI operation"
                       {:documented-statuses [:200]})
        form  (gen/generate-invalid-validation-test-form case error)]
    (is (= 'test-get-status-203-case-1 (second form)))
    (is (some #(= {:method :get
                   :path "/status"
                   :expected-status 203}
                  %)
              (tree-seq coll? seq form)))
    (is (some #(= {:validation-error
                   "Expected status is absent from OpenAPI operation"
                   :documented-statuses [:200]}
                  %)
              (tree-seq coll? seq form)))))
