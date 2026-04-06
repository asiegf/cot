(ns example.core-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s :refer-macros [deftest testing]]
            [cot.generator :refer [deftestgen]]
            [example.core :refer [app]]))

(deftestgen app
  {[:get "/status"]     {}
   [:get "/items"]      {}
   [:get "/items/{id}"] {:params  {:id 0, :mode "def"}
                         :headers {:token "Bearer token"}}
   [:get "/secure"]     {:headers {:token "Bearer token"}}}
  "openapi.yaml")





