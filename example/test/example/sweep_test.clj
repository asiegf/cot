(ns example.sweep-test
  "Spec-driven sweep of the example API: every operation of openapi.yaml is exercised
  on its happy path, secured ones are also called without credentials, and coverage
  fails on any operation left out."
  (:require [clojure.java.io :as io]
            [cot.sweep :refer [defsweep]]
            [example.core :refer [app]]))

(defn- example-path [filename]
  (if (.exists (io/file filename))
    filename
    (str "example/" filename)))

(def config
  {:spec       (example-path "openapi.yaml")
   :app        #'app
   :inputs     (example-path "inputs.edn")
   ;; sent to every secured operation; placeholders come from inputs.edn
   :auth       {"Authorization" 'AUTHORIZATION
                "profile"       "USER"
                "token"         'TOKEN}
   :op-params  {[:get "/items/{id}"] {:id 'ITEM_ID}}
   ;; /places/{id} takes the id of the first place listed by GET /places
   :resolvers  {"/places/{id}" {:from [:get "/places"] :extract "/0/id"}}
   ;; multipart body, with a binary part
   :bodies     {[:post "/uploads"] {"file"    {:value        (.getBytes "id,name\n0,a\n")
                                               :filename     "items.csv"
                                               :content-type "text/csv"}
                                    "comment" "from the sweep"}}
   ;; answers over an http-kit async channel
   :transport  {[:get "/exports/items.csv"] :served}
   :generators #{:happy :auth}})

(defsweep example-sweep config)
