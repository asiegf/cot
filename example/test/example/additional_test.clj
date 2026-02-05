(ns example.additional-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s :refer-macros [deftest testing]]
            [cot.generator :refer [deftestgen]]
            [example.core :refer [app]]))

;; (deftestgen app inputs "openapi.yaml")

;; (deftestgen app {[:get "/status"] {}} "openapi.yaml")

(deftest test-simple
  (testing "simple"
    (is (= true true))))



;; (deftestgen app
;;   {[:get "/status"] {}
;;    [:get "/items"]  {}
;;    [:get "/items/{id}"] {:id 0}}
;;   "openapi.yaml")








