(ns example.core-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s :refer-macros [deftest testing]]
            [cot.generator :refer [deftestgen]]
            [example.core :refer [app]]))

;; (deftestgen app inputs "openapi.yaml")

(deftestgen app
  {[:get "/status"] {}
   [:get "/secure"] {:headers {:token "Bearer token"}}}
  "openapi.yaml")

#_
(app (mock/request :get
                   "/secure"
                   {:headers {:token "Bearer token"}}))

;; (deftest test-simple
;;   (testing "simple"
;;     (is (= true true))))

;; (deftestgen app
;;   {[:get "/status"] {}
;;    [:get "/items"]  {}
;;    [:get "/items/{id}"] {:params {:id 0}}}
;;   "openapi.yaml")








