(ns cot.sweep
  "Spec-driven sweep: enumerates the operations of an OpenAPI spec, derives their inputs,
  runs them against the app and checks the responses against the spec.

  Operations are covered either by `sweep!` (happy path, inputs derived automatically)
  or by a scenario calling `exercise!` (mutations, explicit inputs). `check-coverage`
  fails on any in-scope operation that is neither exercised nor skipped with a reason.

  Config keys (`make-context`):
   :spec           classpath resource or file path of the OpenAPI document
   :app            ring handler (or var)
   :inputs         EDN path (or map) of runtime placeholders, `#env` supported
   :scope          predicate on spec paths (default: all), see `under`
   :auth           header map sent to secured operations, may hold placeholders
   :params         param-name (keyword) -> value, may hold placeholders
   :op-params      [method path] -> param-name -> value
   :resolvers      path prefix -> resolver, see `cot.discovery`
   :bodies         [method path] -> body, or fn of ctx returning it
   :scenarios      fns of ctx run after the happy sweep (call `exercise!`)
   :scenario-ops   set of [method path] left to scenarios (not swept)
   :expect         [method path] -> the status the sweep expects (default: any 2xx)
   :skip           [method path] -> reason
   :live-only      [method path] -> reason; skipped unless `:live?` is true
   :transport      :ring (default), :served, a `cot.transport/Transport`, or a map
                   [method path] -> one of those
   :route-missing? predicate on a response (default: 404 with an empty or non-JSON
                   body), reported as route drift
   :generators     #{:happy :auth} subset run by `run` (default #{:happy})
   :profile-header header of the auth sweep's foreign-profile case (default \"profile\")"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :as t :refer [is testing]]
            [cot.discovery :as discovery]
            [cot.inputs :as inputs]
            [cot.parser :as parser]
            [cot.request :as request]
            [cot.schema :as schema]
            [cot.transport :as transport]))

(def op-label request/op-label)
(def build-request request/build-request)
(def derive-request request/derive-request)
(def body->string transport/body->string)
(def json-pointer discovery/json-pointer)
(def resolve-id discovery/resolve-id)

(defn under
  "Scope predicate: path equals or is below one of `prefixes`."
  [& prefixes]
  (fn [path]
    (boolean (some #(or (= path %) (str/starts-with? path (str % "/"))) prefixes))))

(defn id
  "Discovered value of the trailing `{param}` of `prefix` (e.g. \"/plots/{id}\")."
  [ctx prefix]
  (let [param (second (re-find #"\{([^}]+)\}$" prefix))]
    (or (discovery/resolve-id ctx prefix param)
        (throw (ex-info (str "no resolver for " prefix) {:prefix prefix})))))

(defn json-body
  "Parsed JSON body of a response (keyword keys), nil when not JSON."
  [response]
  (try (json/read-str (body->string (:body response)) :key-fn keyword)
       (catch Exception _ nil)))

;; -----------------------------------------------------------------------------
;; Context

(defn default-route-missing?
  "A 404 with an empty or non-JSON body: the router's catch-all, not the operation."
  [response]
  (and (= 404 (:status response))
       (let [body (body->string (:body response))]
         (or (str/blank? body)
             (try (json/read-str body) false (catch Exception _ true))))))

(defn- transport-for [app transport]
  (let [default (transport/transport app (when-not (map? transport) transport))
        per-op  (when (map? transport) (update-vals transport #(transport/transport app %)))]
    (fn [k] (get per-op k default))))

(defn make-context
  "Context of a sweep from `config` (see the namespace doc)."
  [{:keys [spec app inputs scope auth params op-params skip live-only live?
           scenario-ops transport route-missing?]
    :or   {scope (constantly true)}
    :as   config}]
  (let [spec    (schema/load-spec spec)
        inputs  (inputs/load-inputs inputs)
        resolve #(inputs/resolve-runtime-values % inputs)]
    (assoc config
           :spec           spec
           :ops            (parser/operations (:doc spec) scope)
           ;; resolvers may read list endpoints outside the scope
           :all-ops        (parser/operations (:doc spec))
           :auth           (resolve auth)
           :params         (resolve params)
           :op-params      (resolve op-params)
           :inputs         inputs
           :skip           (merge skip
                                  (when-not live?
                                    (update-vals live-only #(str "live only: " %))))
           :scenario-ops   (set scenario-ops)
           :route-missing? (or route-missing? default-route-missing?)
           :discover       discovery/resolve-id
           :transport-for  (transport-for app transport)
           :ids            (atom {})
           :exercised      (atom #{}))))

;; -----------------------------------------------------------------------------
;; Responses

(defn- response-pointer
  "JSON pointer of the response object, following a top-level $ref."
  [{:keys [method path-kw operation]} status-kw]
  (let [response (get-in operation [:responses status-kw])]
    (if-let [ref (:$ref response)]
      (subs ref 1)
      (str "/paths/" (schema/pointer-escape path-kw) "/" (name method)
           "/responses/" (schema/pointer-escape status-kw)))))

(defn- status-key
  "200 for a numeric status, \"2XX\" for a range, \"default\"; nil otherwise."
  [status-kw]
  (let [s (parser/kname status-kw)]
    (cond
      (parse-long s)                  (parse-long s)
      (re-matches #"(?i)[1-5]xx" s)   (str/upper-case s)
      (= "default" s)                 s)))

(defn documented-responses
  "status (int, \"2XX\" or \"default\") -> `{:pointer :media [media...] :schemas #{media}}`."
  [{:keys [doc]} {:keys [operation] :as op}]
  (into {}
        (for [[status-kw response] (:responses operation)
              :let [k (status-key status-kw)]
              :when k]
          (let [content (:content (parser/follow-ref doc response))]
            [k {:pointer (response-pointer op status-kw)
                :media   (mapv parser/kname (keys content))
                :schemas (set (for [[m obj] content :when (:schema obj)]
                                (parser/kname m)))}]))))

(defn response-for
  "Documented response of `status`: exact, then its range (2XX), then `default`."
  [documented status]
  (or (get documented status)
      (get documented (str (quot status 100) "XX"))
      (get documented "default")))

(defn validation-media
  "The documented media type whose schema validates a response with `content-type`:
   the one matching the content-type, else (no match) the first JSON one with a
   schema. nil when there is nothing to validate."
  [{:keys [media schemas]} content-type]
  (when (schema/json-media? content-type)
    (if-let [matched (schema/match-media media content-type)]
      (when (contains? schemas matched) matched)
      (first (filter #(and (schema/json-media? %) (contains? schemas %)) media)))))

(defn- snippet [body]
  (some-> body (subs 0 (min 300 (count body)))))

(defn check-response
  "Assert `response` matches the documented responses of `op`. `allowed` is the set of
   acceptable statuses (default: the documented 2xx). Returns the response, its body
   read into a string."
  ([ctx op response] (check-response ctx op response nil))
  ([ctx op response allowed]
   (let [response   (update response :body body->string)
         label      (op-label (request/op-key op))
         documented (documented-responses (:spec ctx) op)
         status     (:status response)
         body       (:body response)
         range-2xx? (contains? documented "2XX")
         doc-2xx    (set (filter #(and (int? %) (<= 200 % 299)) (keys documented)))
         allowed?   (if allowed
                      (contains? allowed status)
                      (or (contains? doc-2xx status)
                          (and range-2xx? (int? status) (<= 200 status 299))))
         info       (when (int? status) (response-for documented status))]
     (cond
       ((:route-missing? ctx default-route-missing?) response)
       (is false (str label ": route missing (spec/route drift)"))

       (not allowed?)
       (is false (format "%s: status %s not in %s. body: %s"
                         label status
                         (if allowed
                           (sort allowed)
                           (cond-> (sort doc-2xx) range-2xx? (concat ["2XX"])))
                         (snippet body)))

       (nil? info)
       (is false (format "%s: status %s is not documented (documented: %s). body: %s"
                         label status (vec (keys documented)) (snippet body)))

       :else
       (let [{:keys [media pointer]} info
             ct     (transport/header response "content-type")
             target (validation-media info ct)]
         (when (seq media)
           (is (schema/match-media media ct)
               (format "%s: content-type %s not in %s" label ct (set media))))
         (when (and target body)
           (let [errors (schema/validate (:spec ctx)
                                         (str pointer "/content/"
                                              (schema/pointer-escape target) "/schema")
                                         body)
                 n      (count errors)]
             ;; asserting on the count keeps clojure.test from printing every error
             (is (zero? n)
                 (format "%s [%s%s] response does not match schema (%d errors):\n  %s"
                         label status
                         (if (< 1 (count media)) (str " " target) "")
                         n (str/join "\n  " (schema/summarize-errors errors))))))))
     response)))

;; -----------------------------------------------------------------------------
;; Running operations

(defn send!
  "Send `request` for operation `k` through its transport."
  [ctx k request]
  (transport/send! ((:transport-for ctx) k) request))

(defn exercise!
  "Run operation `k` ([method path]) with `given` inputs (`:path-params :query :headers
   :body :media :multipart`, see `cot.request/derive-request`), check the response and
   record `k` as exercised. `:expect` is the expected status or set of statuses
   (default: any documented 2xx). Returns the response (body as a string), or nil when
   the request could not be built."
  ([ctx k] (exercise! ctx k {}))
  ([ctx k {:keys [expect] :as given}]
   (testing (op-label k)
     (try
       (let [op       (request/find-op (:ops ctx) k)
             request  (build-request op (derive-request ctx op (dissoc given :expect)))
             _        (swap! (:exercised ctx) conj k)
             response (send! ctx k request)]
         (check-response ctx op response (cond (set? expect) expect
                                               (some? expect) #{expect})))
       (catch clojure.lang.ExceptionInfo e
         (is false (str (op-label k) ": " (ex-message e) " " (pr-str (ex-data e))))
         nil)))))

(defn sweep!
  "Run every in-scope operation that is neither skipped nor left to a scenario, on its
  happy path with derived inputs."
  [ctx]
  (doseq [op (:ops ctx)
          :let [k (request/op-key op)]
          :when (not (or (contains? (:skip ctx) k)
                         (contains? (:scenario-ops ctx) k)))]
    (exercise! ctx k (if-let [status (get-in ctx [:expect k])] {:expect status} {}))))

(defn check-coverage
  "Spec operations in scope = exercised + skipped; skip and scenario entries must name
   in-scope operations."
  [ctx]
  (let [in-spec    (set (map request/op-key (:ops ctx)))
        skipped    (set (keys (:skip ctx)))
        untested   (remove (into @(:exercised ctx) skipped) in-spec)
        stale-skip (distinct (remove in-spec (concat skipped
                                                     (keys (:live-only ctx))
                                                     (:scenario-ops ctx))))]
    (is (empty? untested)
        (str "operations neither exercised nor skipped: " (mapv op-label untested)))
    (is (empty? stale-skip)
        (str "skip/scenario entries not in scope: " (mapv op-label stale-skip)))))

;; -----------------------------------------------------------------------------
;; Auth sweep (phase 1 port; replaced by the securitySchemes matrix in phase 3)

(defn- secured-by-header?
  "True when the security requirements of `op` include an apiKey scheme sent in
   `header`."
  [{:keys [doc]} op header]
  (let [schemes (get-in doc [:components :securitySchemes])]
    (some (fn [requirement]
            (some #(= (str/lower-case header)
                      (some-> (get schemes %) :name str/lower-case))
                  (keys requirement)))
          (parser/security-requirements doc (:operation op)))))

(defn auth-sweep!
  "Every secured operation must reject a missing credential and, when it is secured by
  the profile header, a foreign profile, with a documented 400/401/403. Middleware
  rejects these before any handler runs, so this is safe for mutations too."
  [ctx]
  (let [doc (:doc (:spec ctx))]
    (doseq [op (:ops ctx)
            :when (seq (parser/security-requirements doc (:operation op)))
            :let [k (request/op-key op)
                  ;; typed path values reach the auth middleware, not a router 404; the
                  ;; documented example first, as routes may constrain string ids
                  path-params (into {} (for [{:keys [schema] :as p} (:params op)
                                             :when (= "path" (:in p))]
                                         [(:name p)
                                          (cond
                                            (some? (:example p))      (:example p)
                                            (some? (:example schema)) (:example schema)
                                            (#{"integer" "number"} (:type schema)) 0
                                            :else "contract-sweep")]))
                  query (into {} (for [p (:params op)
                                       :when (= "query" (:in p))
                                       :let [v (get (:params ctx) (keyword (:name p)))]
                                       :when (some? v)]
                                   [(:name p) v]))
                  documented (set (keys (documented-responses (:spec ctx) op)))
                  allowed    (set (filter #{400 401 403} documented))
                  profile-header (:profile-header ctx "profile")]]
      (testing (op-label k)
        (is (seq allowed) (str (op-label k) ": no 400/401/403 documented"))
        (doseq [[case headers] (cond-> {"no credentials" {}}
                                 (secured-by-header? (:spec ctx) op profile-header)
                                 (assoc "foreign profile"
                                        (assoc (:auth ctx) profile-header
                                               "cot-unknown-profile")))]
          (testing case
            (check-response ctx op
                            (send! ctx k (build-request op {:path-params path-params
                                                            :query       query
                                                            :headers     headers}))
                            allowed)))))))

;; -----------------------------------------------------------------------------
;; Entry points

(defn run
  "Standard sweep body: the happy sweep, then the scenarios (`:scenarios` of the config,
  then `scenarios`), the auth sweep when `:generators` has `:auth`, and coverage.
  Returns the context."
  [config & scenarios]
  (let [ctx        (make-context config)
        generators (:generators ctx #{:happy})]
    (when (contains? generators :happy)
      (testing "sweep" (sweep! ctx)))
    (doseq [scenario (concat (:scenarios ctx) scenarios)]
      (scenario ctx))
    (when (contains? generators :auth)
      (testing "auth" (auth-sweep! ctx)))
    (testing "coverage" (check-coverage ctx))
    ctx))

(defmacro defsweep
  "Define one deftest `name` running the sweep of `config` (see `run`), with a
  `testing` block per step. `config` is evaluated when the test runs, so redefining it
  needs no reload."
  [name config & scenarios]
  `(t/deftest ~name (run ~config ~@scenarios)))
