(ns cot.request
  "Input derivation and request building for spec operations.

  Parameter values, by priority: override (`:params`, then `:op-params` of the
  operation) -> id discovered at runtime (path params) -> `example` / `default` / first
  `enum` -> failure naming the parameter. Optional parameters are only sent when the
  spec gives an explicit example.

  Request bodies: fixture (`:bodies`, a value or a fn of ctx) -> `example` of the request
  media type -> failure when the body is required. The media type comes from
  `requestBody.content` (JSON ones first); a `multipart/form-data` body is a map of
  parts (see `build-request`)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cot.parser :as parser]
            [cot.schema :as schema]
            [ring.mock.request :as mock]
            [ring.util.codec :refer [url-encode]]))

(defn op-label [[method path]]
  (str (str/upper-case (name method)) " " path))

(defn op-key [op]
  [(:method op) (:path op)])

(defn find-op
  "Operation `[method path]` among `ops`, or an ex-info naming it."
  [ops [method path]]
  (or (first (filter #(and (= method (:method %)) (= path (:path %))) ops))
      (throw (ex-info (str "operation not in scope: " (op-label [method path])) {}))))

(def multipart "multipart/form-data")

(defn- multipart? [media]
  (= multipart (schema/media-essence media)))

;; -----------------------------------------------------------------------------
;; Parameters

(defn- schema-value
  [{:keys [example default enum]}]
  (cond
    (some? example) example
    (some? default) default
    (seq enum)      (first enum)))

(defn- discovered [ctx op param-name]
  (when-let [discover (:discover ctx)]
    (discover ctx (:path op) param-name)))

(defn param-value
  "Value for parameter `p` of `op`, or ::none when an optional parameter has no example."
  [ctx op {:keys [name in required schema example]}]
  (let [k         (keyword name)
        overrides (merge (:params ctx) (get-in ctx [:op-params (op-key op)]))]
    (cond
      (contains? overrides k) (get overrides k)

      (and (= "path" in) (discovered ctx op name))
      (discovered ctx op name)

      ;; optional params are only sent when the spec gives an explicit example
      (not required)
      (if-some [v (if (some? example) example (:example schema))] v ::none)

      (some? (if (some? example) example (schema-value schema)))
      (if (some? example) example (schema-value schema))

      :else
      (throw (ex-info (format "no value for param %s in %s" name (op-label (op-key op)))
                      {:param name})))))

;; -----------------------------------------------------------------------------
;; Bodies

(defn request-media
  "`[media-type media-object]` of the request body of `op`, JSON media types first,
   document order otherwise."
  [doc op]
  (let [body (parser/follow-ref doc (get-in op [:operation :requestBody]))]
    (->> (:content body)
         (map (fn [[k v]] [(parser/kname k) v]))
         (sort-by (fn [[media _]] (if (schema/json-media? media) 0 1))))))

(defn- media-example [doc media-object]
  (if (contains? media-object :example)
    (:example media-object)
    (some->> (:examples media-object) vals first (parser/follow-ref doc) :value)))

(defn request-body
  "`{:body :media}` for `op`: the `:bodies` fixture sent as the first documented media
   type, else the first media type with an example. nil when there is no body to send;
   an ex-info when a required body can't be derived."
  [ctx {:keys [operation] :as op}]
  (let [doc     (:doc (:spec ctx))
        media   (request-media doc op)
        fixture (get-in ctx [:bodies (op-key op)] ::none)]
    (cond
      (not= ::none fixture)
      {:body  (if (fn? fixture) (fixture ctx) fixture)
       :media (ffirst media)}

      :else
      (or (some (fn [[m obj]]
                  (let [example (media-example doc obj)]
                    (when (some? example) {:body example, :media m})))
                media)
          (when (:required (parser/follow-ref doc (:requestBody operation)))
            (throw (ex-info (format "no request body for %s" (op-label (op-key op)))
                            {:media (mapv first media)})))))))

;; -----------------------------------------------------------------------------
;; Derivation

(defn- by-location [values location]
  (into {} (for [[p v] values :when (= location (:in p))] [(:name p) v])))

(defn- secured? [ctx op]
  (not= [] (parser/security-requirements (:doc (:spec ctx)) (:operation op))))

(defn derive-request
  "Request inputs `{:path-params :query :headers :body :media}` for `op`.
   `given` (same keys, plus `:multipart`, a map of parts) wins over derived values;
   a given `:body` or `:multipart` replaces the derived body. `:auth` headers are sent
   unless the operation is explicitly unsecured (`security: []`)."
  ([ctx op] (derive-request ctx op {}))
  ([ctx op given]
   (let [given-names (into #{} (map name)
                           (concat (keys (:path-params given))
                                   (keys (:query given))))
         given-headers (into #{} (map (comp str/lower-case name)) (keys (:headers given)))
         values (for [p (:params op)
                      :when (not (if (= "header" (:in p))
                                   (contains? given-headers (str/lower-case (:name p)))
                                   (contains? given-names (:name p))))
                      :let [v (param-value ctx op p)]
                      :when (not= ::none v)]
                  [p v])
         body (cond
                (contains? given :multipart)
                {:body (:multipart given), :media multipart}

                (contains? given :body)
                {:body  (:body given)
                 :media (or (:media given) (ffirst (request-media (:doc (:spec ctx)) op)))}

                :else
                (request-body ctx op))]
     {:path-params (merge (by-location values "path") (:path-params given))
      :query       (merge (by-location values "query") (:query given))
      :headers     (merge (by-location values "header")
                          (when (secured? ctx op) (:auth ctx))
                          (:headers given))
      :body        (:body body)
      :media       (:media body)})))

;; -----------------------------------------------------------------------------
;; Building

(defn- ->query-value [v]
  (cond
    (string? v) v
    (coll? v)   (json/write-str v)
    :else       (str v)))

(defn- multipart-part
  "A part for ring-mock: a string, File, byte array, `{:file path-or-File}` or ring-mock's
   own `{:value ...}` map, each with optional `:filename` and `:content-type`. Other maps
   and collections are sent as JSON parts, other scalars as strings."
  [v]
  (cond
    (and (map? v) (contains? v :file))
    (let [f (io/file (:file v))]
      (merge {:value f, :filename (.getName f)} (dissoc v :file)))

    (and (map? v) (contains? v :value)) v
    (or (string? v) (bytes? v) (instance? java.io.File v)) v
    (coll? v) {:value (json/write-str v), :content-type "application/json"}
    :else     (str v)))

(defn- with-body [request media body]
  (cond
    (nil? body)
    request

    (multipart? media)
    (mock/multipart-body request (update-vals body multipart-part))

    (or (nil? media) (schema/json-media? media))
    (cond-> (mock/json-body request body)
      (and media (not= "application/json" (schema/media-essence media)))
      (mock/content-type media))

    (map? body)
    (cond-> (mock/body request body)
      (not= "application/x-www-form-urlencoded" (schema/media-essence media))
      (mock/content-type media))

    :else
    (-> (mock/body request body) (mock/content-type media))))

(defn build-request
  "Ring mock request for `op` (`:method`, `:path`) from inputs
   `{:path-params :query :headers :body :media :multipart}`. `:multipart` is a map of
   parts (see `multipart-part`), the same as `:body` with `:media
   \"multipart/form-data\"`."
  [{:keys [method path]} {:keys [path-params query headers body media multipart]}]
  (let [uri (reduce (fn [p [k v]] (str/replace p (str "{" (name k) "}") (url-encode (str v))))
                    path path-params)
        [body media] (if (some? multipart) [multipart "multipart/form-data"] [body media])]
    (cond-> (mock/request method uri)
      (seq query) (mock/query-string (update-vals query ->query-value))
      true        (#(reduce (fn [r [k v]] (mock/header r k v)) % headers))
      true        (with-body media body))))
