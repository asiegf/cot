(ns cot.deftestgen-inline-nested-ref-e2e-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest is testing]]
            [cot.generator :refer [deftestgen]]))

(def spec-path
  (.getPath (io/resource "fixtures/openapi.yaml")))

(def inputs
  {[:get "/profile"]  {}
   [:get "/profiles"] {}})

(defn handler
  [req]
  (case (:uri req)
    "/profile"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:owner {:id "bad-id"
                                    :address {:city "Paris"}}})}

    "/profiles"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str [{:owner {:id "bad-id"
                                     :address {:city "Paris"}}}])}

    {:status 404
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:error "not found"})}))

(deftestgen handler inputs spec-path)

(def ^:private generated-tests
  (into {}
        (for [sym ['test-get-profile 'test-get-profiles]
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

(deftest inline-object-response-with-nested-ref-should-fail-invalid-data
  (testing "generated tests should reject invalid nested data inside inline object responses"
    (let [counts (run-generated-test 'test-get-profile)]
      (is (pos? (+ (:fail counts) (:error counts)))
          (str "Expected generated test to fail for invalid nested inline object response, got "
               counts)))))

(deftest generated-test-exceptions-report-as-errors
  (testing "generated test helper exceptions should increment clojure.test error counters"
    (let [counts (with-redefs [generated-tests {'test-get-profile
                                                {:var (generated-test-var 'test-get-profile)
                                                 :test (fn []
                                                         (throw (ex-info "boom" {})))}}]
                   (run-generated-test 'test-get-profile))]
      (is (pos? (:error counts))
          (str "Expected generated test exception to increment error counter, got "
               counts)))))

(deftest inline-array-response-with-nested-ref-should-fail-invalid-data
  (testing "generated tests should reject invalid nested data inside inline array responses"
    (let [counts (run-generated-test 'test-get-profiles)]
      (is (pos? (+ (:fail counts) (:error counts)))
          (str "Expected generated test to fail for invalid nested inline array response, got "
               counts)))))
