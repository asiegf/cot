(ns cot.deftestgen-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [cot.generator :refer [deftestgen]]))

(def spec-path
  (.getPath (io/resource "fixtures/openapi.yaml")))

(def inputs
  {[:get "/status"] {}
   [:get "/items"] {:limit 10}})

(defn handler
  [req]
  (case (:uri req)
    "/status"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:ok true :message "up"})}

    "/items"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str [{:id 1 :name "item-1"}])}

    {:status 404
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:error "not found"})}))

(deftestgen handler inputs spec-path)

(defn- test-var-names
  []
  (->> (ns-interns (find-ns 'cot.deftestgen-test))
       (filter (fn [[_ v]] (:test (meta v))))
       (map first)
       set))

(deftest deftestgen-defines-expected-tests
  (let [names (test-var-names)]
    (is (contains? names 'test-get-status))
    (is (contains? names 'test-get-items))
    (is (not (contains? names 'test-get-items-id)))))
