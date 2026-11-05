(ns cot.sweep-test
  "Self-tests of the sweep engine: it passes on a compliant app and fails where it must
  (each drift breaks one thing)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [cot.discovery :as discovery]
            [cot.request :as request]
            [cot.schema :as schema]
            [cot.sweep :as sweep]
            [org.httpkit.server :as http]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]))

;; -----------------------------------------------------------------------------
;; App under test

(def ^:dynamic *drift* #{})

(defn- json-response
  ([status body] (json-response status body "application/json"))
  ([status body content-type]
   {:status  status
    :headers {"Content-Type" content-type}
    :body    (json/write-str body)}))

(def things [{:id 1, :name "one"} {:id 2, :name "two"}])

(defn- authorized? [req]
  (= "key" (get-in req [:headers "x-api-key"])))

(defn- handler [{:keys [uri request-method] :as req}]
  (cond
    (*drift* :route-missing)
    {:status 404, :headers {"Content-Type" "text/html"}, :body "<h1>Not found</h1>"}

    (and (= uri "/uploads") (= request-method :post))
    (let [{:strs [file comment]} (:multipart-params req)]
      (if (and (map? file) (:tempfile file))
        (json-response 201 (cond-> {:name        (:filename file)
                                    :size        (.length ^java.io.File (:tempfile file))
                                    :contentType (:content-type file)}
                             comment (assoc :comment comment)))
        (json-response 400 {:error "file part missing"})))

    (not (authorized? req))
    (json-response 401 {:error "missing or invalid key"})

    (= uri "/things")
    (cond
      (*drift* :undocumented-status) (json-response 500 {:error "boom"})
      (*drift* :schema-mismatch)     (json-response 200 [{:id "1"}])
      (*drift* :wrong-content-type)  (json-response 200 things "text/plain")
      :else                          (json-response 200 things))

    (str/starts-with? uri "/things/")
    (if-let [thing (first (filter #(= (str (:id %)) (subs uri 8)) things))]
      (json-response 200 thing)
      (json-response 404 {:error "unknown thing"}))

    (str/starts-with? uri "/places/")
    (if (*drift* :place-as-json-shape)
      ;; a JSON-shaped place sent as geo+json: valid for the first media, not this one
      (json-response 200 {:id "p1", :name "Paris"} "application/geo+json")
      (json-response 200 {:type       "Feature"
                          :geometry   {:type "Point", :coordinates [2.35 48.85]}
                          :properties {:name "Paris"}}
                     "application/geo+json"))

    (= uri "/exports/things.csv")
    ;; answers on an async channel: only a real server can drive it
    (http/as-channel req {:on-open (fn [ch]
                                     (http/send! ch {:status  200
                                                     :headers {"Content-Type" "text/csv"}
                                                     :body    "id,name\n1,one\n"}))})

    (= uri "/secure")
    (if (= "mine" (get-in req [:headers "x-profile"]))
      (json-response 200 {:profile "mine"})
      (json-response 401 {:error "foreign profile"}))

    :else
    {:status 404, :headers {"Content-Type" "text/html"}, :body "<h1>Not found</h1>"}))

(def app (-> handler wrap-params wrap-multipart-params))

(def csv-bytes (.getBytes "id,name\n1,one\n" "UTF-8"))

(def config
  {:spec           "fixtures/sweep.yaml"
   :app            app
   :inputs         {'API_KEY "key"}
   :auth           {"x-api-key" 'API_KEY, "x-profile" "mine"}
   :profile-header "x-profile"
   :resolvers      {"/things/{id}" {:from [:get "/things"]}}
   :bodies         {[:post "/uploads"] {"file"    {:value        csv-bytes
                                                   :filename     "things.csv"
                                                   :content-type "text/csv"}
                                        "comment" "from the sweep"}}
   :transport      {[:get "/exports/things.csv"] :served}
   :generators     #{:happy :auth}})

;; -----------------------------------------------------------------------------
;; Capturing clojure.test reports

(defn- capture
  "Reports of `f` run with clojure.test reporting captured, not printed."
  [f]
  (let [reports (atom [])]
    (binding [t/report #(swap! reports conj (assoc % :contexts t/*testing-contexts*))]
      (f))
    @reports))

(defn- failures [reports]
  (filter #(#{:fail :error} (:type %)) reports))

(defn- messages [reports]
  (map #(str (:message %) " " (when (= :error (:type %)) (:actual %))) (failures reports)))

(defn- fails-with?
  "True when the reports hold at least one failure whose message matches `re`."
  [reports re]
  (boolean (some #(re-find re %) (messages reports))))

(defn- run-with [drift config]
  (capture #(binding [*drift* drift] (sweep/run config))))

;; -----------------------------------------------------------------------------
;; Passing sweep

(deftest compliant-app-passes-test
  (let [reports (run-with #{} config)]
    (is (empty? (messages reports)) (str/join "\n" (messages reports)))
    (is (< 15 (count (filter #(= :pass (:type %)) reports)))
        "the sweep asserts on statuses, content-types and bodies")))

(deftest discovery-memoises-ids-test
  (let [ctx (sweep/make-context config)]
    (is (= 1 (sweep/id ctx "/things/{id}")))
    (is (= {"/things/{id}" 1} @(:ids ctx)))))

;; -----------------------------------------------------------------------------
;; The engine fails where it must

(deftest undocumented-status-fails-test
  (testing "outside the documented 2xx"
    (is (fails-with? (run-with #{:undocumented-status} config)
                     #"GET /things: status 500 not in \(200\)")))
  (testing "expected, but documented nowhere"
    (let [reports (capture #(sweep/check-response
                             (sweep/make-context config)
                             (request/find-op (:ops (sweep/make-context config))
                                              [:get "/things"])
                             {:status 418, :headers {"Content-Type" "application/json"}
                              :body "{}"}
                             #{418}))]
      (is (fails-with? reports #"GET /things: status 418 is not documented")))))

(deftest schema-mismatch-fails-test
  (is (fails-with? (run-with #{:schema-mismatch} config)
                   #"GET /things \[200\] response does not match schema \(2 errors\)")))

(deftest wrong-content-type-fails-test
  (is (fails-with? (run-with #{:wrong-content-type} config)
                   #"GET /things: content-type text/plain not in")))

(deftest route-missing-fails-test
  (let [reports (run-with #{:route-missing} config)]
    (is (fails-with? reports #"GET /things: route missing \(spec/route drift\)"))
    (is (fails-with? reports #"could not discover id for /things/\{id\}"))))

(deftest custom-route-missing-predicate-test
  (let [reports (run-with #{:route-missing}
                          (assoc config :route-missing? (constantly false)))]
    (is (not (fails-with? reports #"route missing")))
    (is (fails-with? reports #"GET /things: status 404 not in \(200\)"))))

(deftest coverage-fails-on-stale-skip-and-untested-op-test
  (let [reports (capture #(let [ctx (sweep/make-context
                                     (assoc config
                                            :skip {[:get "/gone"]   "removed"
                                                   [:get "/things"] "slow"}
                                            :scenario-ops #{[:post "/nowhere"]}))]
                            (sweep/check-coverage ctx)))]
    (is (fails-with? reports #"skip/scenario entries not in scope: \[\"GET /gone\" \"POST /nowhere\"\]"))
    (is (fails-with? reports #"operations neither exercised nor skipped: .*GET /things/\{id\}"))
    (is (not (fails-with? reports #"neither exercised.*\"GET /things\"")))))

(deftest live-only-skips-unless-live-test
  (let [cfg     (assoc config :live-only {[:get "/secure"] "real identity provider"})
        offline (sweep/make-context cfg)
        live    (sweep/make-context (assoc cfg :live? true))]
    (is (= "live only: real identity provider" (get (:skip offline) [:get "/secure"])))
    (is (not (contains? (:skip live) [:get "/secure"])))))

;; -----------------------------------------------------------------------------
;; Media types

(deftest match-media-test
  (let [documented ["application/json" "application/geo+json"]]
    (is (= "application/geo+json"
           (schema/match-media documented "application/geo+json; charset=utf-8")))
    (is (= "application/json" (schema/match-media documented "Application/JSON")))
    (is (nil? (schema/match-media documented "application/jsonx")))
    (is (nil? (schema/match-media documented nil)))
    (is (= "application/*" (schema/match-media ["text/csv" "application/*"]
                                               "application/json")))
    (is (= "application/json" (schema/match-media ["*/*" "application/json"]
                                                  "application/json"))
        "an exact match wins over an earlier wildcard")))

(deftest validation-media-test
  (let [info {:media   ["application/json" "application/geo+json" "text/csv"]
              :schemas #{"application/json" "application/geo+json"}}]
    (is (= "application/geo+json" (sweep/validation-media info "application/geo+json")))
    (is (= "application/json" (sweep/validation-media info "application/json")))
    (is (= "application/json" (sweep/validation-media info "application/vnd.x+json"))
        "no documented match: the first JSON one")
    (is (nil? (sweep/validation-media info "text/csv")))
    (is (nil? (sweep/validation-media (update info :schemas disj "application/geo+json")
                                      "application/geo+json"))
        "a matching media type without a schema is not validated against another one")))

(deftest response-validated-against-its-media-type-test
  (testing "a GeoJSON body passes against the geo+json schema, not the first JSON one"
    (let [reports (capture #(sweep/exercise! (sweep/make-context config)
                                             [:get "/places/{id}"]))]
      (is (empty? (messages reports)) (str/join "\n" (messages reports)))))
  (testing "a body valid for application/json but sent as geo+json fails"
    (let [reports (capture #(binding [*drift* #{:place-as-json-shape}]
                              (sweep/exercise! (sweep/make-context config)
                                               [:get "/places/{id}"])))]
      (is (fails-with? reports
                       #"GET /places/\{id\} \[200 application/geo\+json\] response does not match schema")))))

;; -----------------------------------------------------------------------------
;; OpenAPI 3.0 nullable next to a type-less anyOf

(defn- oas30-app [req]
  (json-response 200 {:v nil}))

(deftest oas30-nullable-any-of-test
  (let [ctx (sweep/make-context {:spec "fixtures/sweep-oas30.yaml", :app oas30-app})]
    (testing "networknt's 3.0 dialect honours nullable: true without a type"
      (let [reports (capture #(sweep/exercise! ctx [:get "/loose"]))]
        (is (empty? (messages reports)) (str/join "\n" (messages reports)))))
    (testing "the same anyOf without nullable rejects null"
      (is (fails-with? (capture #(sweep/exercise! ctx [:get "/strict"]))
                       #"GET /strict \[200\] response does not match schema")))))

;; -----------------------------------------------------------------------------
;; Requests

(deftest multipart-request-test
  (let [ctx (sweep/make-context config)
        op  (request/find-op (:ops ctx) [:post "/uploads"])]
    (testing "the body media type comes from requestBody.content"
      (is (= "multipart/form-data" (:media (request/derive-request ctx op)))))
    (testing "a binary part reaches the app as a file upload"
      (let [req    (request/build-request op (request/derive-request ctx op))
            seen   (atom nil)
            _      ((wrap-multipart-params #(reset! seen (:multipart-params %))) req)
            upload (get @seen "file")]
        (is (str/starts-with? (get-in req [:headers "content-type"]) "multipart/form-data"))
        (is (= "things.csv" (:filename upload)))
        (is (= "text/csv" (:content-type upload)))
        (is (= (seq csv-bytes) (seq (java.nio.file.Files/readAllBytes
                                     (.toPath ^java.io.File (:tempfile upload))))))
        (is (= "from the sweep" (get @seen "comment")))))
    (testing "exercise! with :multipart, the documented response checked"
      (let [file     (doto (java.io.File/createTempFile "cot" ".csv")
                       (.deleteOnExit)
                       (spit "id\n1\n"))
            response (atom nil)
            reports  (capture #(reset! response
                                       (sweep/exercise! ctx [:post "/uploads"]
                                                        {:multipart {"file" {:file file
                                                                             :content-type "text/csv"}}})))]
        (is (empty? (messages reports)) (str/join "\n" (messages reports)))
        (is (= {:name (.getName file), :size 5, :contentType "text/csv"}
               (sweep/json-body @response)))))))

(deftest json-request-media-test
  (let [doc  (:doc (schema/load-spec "fixtures/sweep.yaml"))
        op   {:method :post, :path "/x"
              :operation {:requestBody {:content {:text/plain       {:example "t"}
                                                  :application/geo+json {:example {:type "Feature"}}}}}}
        ctx  {:spec {:doc doc}}
        body (request/request-body ctx op)
        req  (request/build-request op body)]
    (is (= {:body {:type "Feature"}, :media "application/geo+json"} body)
        "JSON media types are preferred")
    (is (= "application/geo+json" (get-in req [:headers "content-type"])))
    (is (= {"type" "Feature"} (json/read-str (slurp (:body req)))))))

(deftest param-derivation-test
  (let [ctx (sweep/make-context (assoc config :params {:limit 3}))
        op  (request/find-op (:ops ctx) [:get "/things"])]
    (is (= {"limit" 3} (:query (request/derive-request ctx op))) "override wins")
    (is (= {"limit" 10} (:query (request/derive-request (sweep/make-context config) op)))
        "optional param sent with its example")
    (is (= {"x-api-key" "key", "x-profile" "mine"}
           (:headers (request/derive-request ctx op)))
        "auth headers on a secured operation")
    (is (= {} (:headers (request/derive-request
                         ctx (request/find-op (:ops ctx) [:post "/uploads"]))))
        "no auth headers on security: []")))

(deftest missing-param-value-fails-test
  (let [reports (capture #(sweep/exercise! (sweep/make-context (dissoc config :resolvers))
                                           [:get "/things/{id}"]))]
    (is (fails-with? reports #"GET /things/\{id\}: no value for param id in GET /things/\{id\}"))))

;; -----------------------------------------------------------------------------
;; Transports and discovery

(deftest served-transport-test
  (let [ctx (sweep/make-context config)]
    (testing "an async-channel route passes on the served transport"
      (let [reports (capture #(sweep/exercise! ctx [:get "/exports/things.csv"]))]
        (is (empty? (messages reports)) (str/join "\n" (messages reports)))))
    (testing "the in-process transport can't drive it"
      (let [reports (capture #(sweep/exercise! (sweep/make-context (dissoc config :transport))
                                               [:get "/exports/things.csv"]))]
        (is (seq (failures reports)))))))

(deftest json-pointer-test
  (is (= 1 ((discovery/json-pointer "/0/id") "[{\"id\":1}]")))
  (is (= "x" ((discovery/json-pointer "/a~1b/c") "{\"a/b\":{\"c\":\"x\"}}")))
  (is (nil? ((discovery/json-pointer "/9/id") "[{\"id\":1}]")))
  (is (= [1] ((discovery/json-pointer "") "[1]"))))

(deftest file-spec-loading-test
  (let [spec (schema/load-spec "test/fixtures/sweep.yaml")]
    (is (str/starts-with? (:iri spec) "file:"))
    (is (empty? (schema/validate spec "/components/schemas/Thing" "{\"id\":1,\"name\":\"a\"}")))
    (is (= 1 (count (schema/validate spec "/components/schemas/Thing" "{\"id\":1}"))))
    (is (re-find #"not valid JSON"
                 (first (schema/validate spec "/components/schemas/Thing" "<html>"))))))

;; -----------------------------------------------------------------------------
;; defsweep

(sweep/defsweep defsweep-smoke config)

(deftest defsweep-defines-one-test-test
  (is (fn? (:test (meta #'defsweep-smoke))))
  (let [reports (capture (:test (meta #'defsweep-smoke)))]
    (is (empty? (messages reports)) (str/join "\n" (messages reports)))
    (is (= #{"sweep" "auth" "coverage"}
           (set (keep (comp last :contexts) reports)))
        "one testing block per step")))
