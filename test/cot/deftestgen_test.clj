(ns cot.deftestgen-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [cot.generator :refer [deftestgen]]))

(def spec-path
  (.getPath (io/resource "fixtures/openapi.yaml")))

(def inputs
  {[:get "/status"]   {}
   [:get "/items"]    {:params {:limit 10}}
   [:get "/empty-items"] {}
   [:get "/featured-items"] {}
   [:get "/profile"]  {}
   [:get "/profiles"] {}})

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
     :body (json/write-str [{:id 1
                             :name "item-1"
                             :status "active"
                             :address {:city "NYC"}}])}

    "/empty-items"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str [])}

    "/featured-items"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str [{:id 11
                             :name "featured"
                             :status "active"}])}

    "/profile"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:owner {:id 7
                                    :name "owner"
                                    :address {:city "Paris"}
                                    :related [{:id 8 :name "child"}]}})}

    "/profiles"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str [{:owner {:id 9 :name "a" :address {:city "Tokyo"}}}
                            {:owner {:id 10 :name "b"}}])}

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
    (is (contains? names 'test-get-status-inputs))
    (is (contains? names 'test-get-items-inputs))
    (is (contains? names 'test-get-empty-items-inputs))
    (is (contains? names 'test-get-featured-items-inputs))
    (is (contains? names 'test-get-profile-inputs))
    (is (contains? names 'test-get-profiles-inputs))
    (is (not (contains? names 'test-get-items-id-inputs)))))

(deftest nested-ref-validation
  (testing "nested $ref in a component property resolves through the registry"
    (is (true?  (s/valid? :cot.schema/Item {:id 1 :address {:city "NYC"}})))
    (is (false? (s/valid? :cot.schema/Item {:id 1 :address {:city 42}}))))
  (testing "self-referential array items validate"
    (is (true? (s/valid? :cot.schema/Item
                         {:id 1 :related [{:id 2} {:id 3 :related [{:id 4}]}]})))))

(deftest inline-response-nested-ref-validation
  (testing "inline response object: its scoped owner spec checks nested values"
    (is (true?  (s/valid? (s/keys :req-un [:cot.schema.response.get.profile.200/owner])
                          {:owner {:id 1 :address {:city "NYC"}}})))
    (is (false? (s/valid? (s/keys :req-un [:cot.schema.response.get.profile.200/owner])
                          {:owner {:id "not-an-int"}})))
    (is (false? (s/valid? (s/keys :req-un [:cot.schema.response.get.profile.200/owner])
                          {:owner {:id 1 :address {:city 99}}}))))
  (testing "array of inline objects: each item is validated through the nested ref"
    (let [items-spec (s/coll-of
                      (s/keys :req-un [:cot.schema.response.get.profiles.200.item/owner])
                      :min-count 1)]
      (is (true?  (s/valid? items-spec [{:owner {:id 1}}])))
      (is (false? (s/valid? items-spec [{:owner {:id "bad"}}]))))))
