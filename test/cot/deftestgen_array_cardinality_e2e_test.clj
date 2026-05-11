(ns cot.deftestgen-array-cardinality-e2e-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest is testing]]
            [cot.generator :refer [deftestgen]]))

(def spec-path
  (.getPath (io/resource "fixtures/openapi.yaml")))

(def inputs
  {[:get "/empty-items"]    {}
   [:get "/explicit-empty-items"] {}
   [:get "/required-items"] {}
   [:get "/featured-items"] {}})

(defn handler
  [req]
  (case (:uri req)
    "/empty-items"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str [])}

    "/explicit-empty-items"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str [])}

    "/required-items"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str [])}

    "/featured-items"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str [{:id 1 :name "a"}
                            {:id 2 :name "b"}])}

    {:status 404
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:error "not found"})}))

(deftestgen handler inputs spec-path)

(def ^:private generated-tests
  (into {}
        (for [sym ['test-get-empty-items
                   'test-get-explicit-empty-items
                   'test-get-required-items
                   'test-get-featured-items]
              :let [v (ns-resolve *ns* sym)]
              :when v]
          [sym {:var v
                :test (:test (meta v))}])))

(doseq [{:keys [var]} (vals generated-tests)]
  (alter-meta! var dissoc :test))

(defn- generated-test-var [sym]
  (:var (get generated-tests sym)))

(defn- run-generated-test [sym]
  (let [{:keys [var test]} (get generated-tests sym)
        test-name (or (:name (meta var)) sym)]
    (binding [t/*report-counters* (ref t/*initial-report-counters*)
              t/*test-out*         (java.io.StringWriter.)]
      (if test
        (try
          (test)
          (catch Throwable e
            (t/report {:type :error
                       :message (str "Uncaught exception in generated test " test-name)
                       :expected nil
                       :actual e})))
        (t/report {:type :error
                   :message (str "Generated test var was not captured: " test-name)
                   :expected "captured generated test function"
                   :actual nil}))
      @t/*report-counters*)))

(deftest unbounded-array-response-rejects-empty-collection
  (testing "generated tests should retain the compatibility default that arrays without minItems are non-empty"
    (let [counts (run-generated-test 'test-get-empty-items)]
      (is (pos? (+ (:fail counts) (:error counts)))
          (str "Expected generated test to fail for empty unbounded array response, got "
               counts)))))

(deftest explicit-zero-min-items-allows-empty-collection
  (testing "generated tests should accept empty arrays when minItems explicitly permits them"
    (let [counts (run-generated-test 'test-get-explicit-empty-items)]
      (is (zero? (+ (:fail counts) (:error counts)))
          (str "Expected generated test to pass for empty minItems: 0 array response, got "
               counts)))))

(deftest generated-test-exceptions-report-as-errors
  (testing "generated test helper exceptions should increment clojure.test error counters"
    (let [counts (with-redefs [generated-tests {'test-get-empty-items
                                                {:var (generated-test-var 'test-get-empty-items)
                                                 :test (fn []
                                                         (throw (ex-info "boom" {})))}}]
                   (run-generated-test 'test-get-empty-items))]
      (is (pos? (:error counts))
          (str "Expected generated test exception to increment error counter, got "
               counts)))))

(deftest min-items-constraint-fails-empty-collection
  (testing "generated tests should reject empty arrays when minItems is present"
    (let [counts (run-generated-test 'test-get-required-items)]
      (is (pos? (+ (:fail counts) (:error counts)))
          (str "Expected generated test to fail for empty minItems-constrained array response, got "
               counts)))))

(deftest max-items-constraint-fails-oversized-collection
  (testing "generated tests should reject arrays that exceed maxItems"
    (let [counts (run-generated-test 'test-get-featured-items)]
      (is (pos? (+ (:fail counts) (:error counts)))
          (str "Expected generated test to fail for oversized maxItems-constrained array response, got "
               counts)))))
