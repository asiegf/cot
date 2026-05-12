(ns cot.generator
  (:require [cot.parser :as parser :refer [parse-file resolve-ref get-schemas]]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :as t]
            [ring.mock.request :as mock]))

(defn openapi-type->predicate
  "Convert an OpenAPI type to a Clojure predicate symbol."
  [schema]
  (case (:type schema)
    "string"  `string?
    "integer" `int?
    "number"  `number?
    "boolean" `boolean?
    "array"   `sequential?
    "object"  `map?
    `any?))

(defn schema->spec
  "Convert an OpenAPI schema to a spec form."
  [schema]
  (case (:type schema)
    "object"
    (let [required (set (map keyword (:required schema)))
          prop-keys (keys (:properties schema))
          req-keys (filter required prop-keys)
          opt-keys (remove required prop-keys)
          ->spec-kw #(keyword "cot.schema" (name %))]
      `(s/keys ~@(when (seq req-keys) [:req-un (mapv ->spec-kw req-keys)])
               ~@(when (seq opt-keys) [:opt-un (mapv ->spec-kw opt-keys)])))

    "array"
    `(s/coll-of ~(schema->spec (:items schema)) :min-count 1)

    (openapi-type->predicate schema)))

(defn generate-spec-defs
  "Generate spec definitions for all properties in a schema."
  [schema-name schema]
  (concat
   (for [[prop-name prop-schema] (:properties schema)]
     `(s/def ~(keyword "cot.schema" (name prop-name))
        ~(schema->spec prop-schema)))
   [`(s/def ~(keyword "cot.schema" (name schema-name))
       ~(schema->spec schema))]))

(defn generate-all-specs
  "Generate spec definitions for all component schemas in an OpenAPI spec."
  [openapi-spec]
  (mapcat (fn [[schema-name schema]]
            (generate-spec-defs schema-name schema))
          (get-schemas openapi-spec)))

;; -----------------------------------------------------------------------------
;; Test generation helpers
;; -----------------------------------------------------------------------------

(defn keyword->path-str
  "Convert a keyword representing an OpenAPI path to a string path.
   clj-yaml creates keywords like :/items/{id} with empty string namespace
   and name 'items/{id}' for paths starting with /."
  [path-kw]
  (if (keyword? path-kw)
    (let [ns (namespace path-kw)
          nm (name path-kw)]
      (cond
        ;; Empty namespace means path started with /
        (= ns "") (str "/" nm)
        ;; Non-empty namespace (unlikely for OpenAPI paths)
        ns (str "/" ns "/" nm)
        ;; No namespace, name might or might not have leading /
        (str/starts-with? nm "/") nm
        :else (str "/" nm)))
    (let [s (str path-kw)]
      (if (str/starts-with? s "/") s (str "/" s)))))

(defn path-template->request-path
  "Convert OpenAPI path template to actual path by substituting params.
   Input: \"/items/{id}\", {:id 0}
   Output: \"/items/0\""
  [path-template params]
  (reduce (fn [path [k v]]
            (str/replace path (str "{" (name k) "}") (str v)))
          (if (keyword? path-template)
            (keyword->path-str path-template)
            (str path-template))
          params))

(defn operation->test-name
  "Generate test name symbol from method and path.
   Input: :get, \"/items/{id}\"
   Output: test-get-items-id"
  [method path]
  (symbol (str "test-" (name method) "-"
               (-> (keyword->path-str path)
                   (str/replace #"\{|\}" "")
                   (str/replace #"/" "-")
                   (str/replace #"^-" "")))))

(defn extract-params-by-location
  "Separate operation parameters by their 'in' field.
   Returns map like {:path [:id], :query [:limit :offset], :header [:Authorization]}"
  [operation]
  (reduce (fn [acc param]
            (update acc
                    (keyword (:in param))
                    (fnil conj [])
                    (keyword (:name param))))
          {}
          (:parameters operation)))

(defn- plain-map
  [m]
  (into {} (map (fn [[k v]]
                  [k (if (map? v) (plain-map v) v)])
                m)))

(defn- operation-security-requirements
  [spec operation]
  (some->> (if (contains? operation :security)
             (:security operation)
             (:security spec))
           (mapv plain-map)))

(defn- security-schemes
  [spec]
  (plain-map (get-in spec [:components :securitySchemes] {})))

(defn- credential-for
  [security-input scheme-name]
  (let [plain-name (name scheme-name)
        candidates [scheme-name
                    (keyword plain-name)
                    plain-name]]
    (some #(when (contains? security-input %) (get security-input %))
          candidates)))

(defn- bearer-token
  [credential]
  (let [token (if (map? credential)
                (or (:token credential) (:value credential))
                credential)
        token-str (str token)]
    (if (str/starts-with? (str/lower-case token-str) "bearer ")
      token-str
      (str "Bearer " token-str))))

(defn- api-key-value
  [credential]
  (if (map? credential)
    (or (:value credential) (:token credential) (:api-key credential))
    credential))

(defn- requirement-satisfiable?
  [schemes security-input requirement]
  (every? (fn [[scheme-name _scopes]]
            (and (contains? schemes scheme-name)
                 (some? (credential-for security-input scheme-name))))
          requirement))

(defn- select-security-requirement
  [schemes security-input requirements]
  (some (fn [requirement]
          (when (requirement-satisfiable? schemes security-input requirement)
            requirement))
        requirements))

(defn- scheme-request-values
  [scheme credential]
  (case (:type scheme)
    "apiKey"
    (let [value (api-key-value credential)]
      (case (:in scheme)
        "header" {:headers {(keyword (:name scheme)) value}}
        "query"  {:query-params {(keyword (:name scheme)) value}}
        {:headers {} :query-params {}}))

    "http"
    (if (= "bearer" (str/lower-case (or (:scheme scheme) "")))
      {:headers {:Authorization (bearer-token credential)}}
      {:headers {} :query-params {}})

    {:headers {} :query-params {}}))

(defn security-request-values
  "Return generated request headers/query params for the first satisfiable
   OpenAPI security requirement. Security requirements are OR alternatives;
   schemes within one requirement are ANDed."
  [schemes requirements security-input]
  (if-let [requirement (select-security-requirement schemes
                                                    security-input
                                                    requirements)]
    (reduce (fn [acc [scheme-name _scopes]]
              (let [credential (credential-for security-input scheme-name)
                    request-values (scheme-request-values (get schemes scheme-name)
                                                          credential)]
                (-> acc
                    (update :headers merge (:headers request-values))
                    (update :query-params merge (:query-params request-values)))))
            {:headers {} :query-params {}}
            requirement)
    {:headers {} :query-params {}}))

(defn- json-response?
  "Check if the operation returns application/json."
  [operation]
  (some? (get-in operation [:responses :200 :content :application/json])))

(defn- response-spec-keyword
  "Get the spec keyword for the response schema of an operation.
   If it's a ref, return the schema name; if it's an inline array,
   return a generated keyword."
  [spec operation]
  (let [schema (get-in operation
                       [:responses :200 :content :application/json :schema])]
    (cond
      (:$ref schema)
      (->> (str/split (:$ref schema) #"/")
           last
           (keyword "cot.schema"))

      (and (= "array" (:type schema)) (get-in schema [:items :$ref]))
      (->> (str/split (get-in schema [:items :$ref]) #"/")
           last
           (keyword "cot.schema")))))

(defn- array-response?
  "Check if the response schema is an array type."
  [operation]
  (= "array"
     (get-in operation
             [:responses :200 :content :application/json :schema :type])))

(defn- path-param-names
  "Extract path parameter names from a path template string as a set of keywords.
   Input: \"/items/{id}\"
   Output: #{:id}"
  [path-str]
  (->> (re-seq #"\{(\w+)\}" path-str)
       (map (comp keyword second))
       set))

(defn generate-test-form
  "Generate a deftest form for a single operation."
  [spec handler-sym inputs-sym method path operation]
  (let [path-str   (keyword->path-str path)
        path-params (path-param-names path-str)
        json?      (json-response? operation)
        spec-kw    (when json? (response-spec-keyword spec operation))
        input-sym   (gensym "input")
        params-sym  (gensym "params")
        headers-sym (gensym "headers")
        security-values-sym (gensym "security-values")
        request-path-sym (gensym "request-path")
        request-sym  (gensym "request")
        response-sym (gensym "response")
        body-sym     (gensym "body")]
    `(t/deftest ~(operation->test-name method path)
       (t/testing ~(format "%s %s returns valid response"
                           (str/upper-case (name method))
                           path-str)
         (let [~input-sym   (get ~inputs-sym [~method ~path-str] {})
               ~params-sym  (:params ~input-sym {})
               ~headers-sym (:headers ~input-sym {})
               ~security-values-sym (security-request-values
                                     ~(security-schemes spec)
                                     ~(operation-security-requirements spec operation)
                                     (:security ~input-sym {}))
               ~request-path-sym (path-template->request-path
                                  ~path-str ~params-sym)
               ~request-sym (let [query-params# (merge
                                                  (:query-params ~security-values-sym)
                                                  (into {} (keep (fn [[k# v#]]
                                                                   (when-not (contains? ~path-params k#)
                                                                     [(keyword (name k#))
                                                                      v#]))
                                                                 ~params-sym)))
                                   qs# (str/join "&" (map (fn [[k# v#]] (str (name k#) "=" v#)) query-params#))]
                              (reduce (fn [r# [k# v#]]
                                        (mock/header r# (if (keyword? k#) (name k#) (str k#)) v#))
                                      (cond-> (mock/request ~method ~request-path-sym)
                                        (seq query-params#) (assoc :params query-params#)
                                        (seq qs#) (mock/query-string qs#))
                                      (merge (:headers ~security-values-sym)
                                             ~headers-sym)))
               ~response-sym (~handler-sym ~request-sym)
               ~@(when json?
                   [body-sym `(json/read-str (:body ~response-sym)
                                             :key-fn keyword)])]
           (t/is (= 200 (:status ~response-sym)))
           ~(when spec-kw
              (if (array-response? operation)
                `(do
                   (t/is (seq ~body-sym) "Response array should not be empty")
                   (t/is (every? #(s/valid? ~spec-kw %) ~body-sym)
                         (str "Array items invalid: "
                              (s/explain-str ~spec-kw (first ~body-sym)))))
                `(t/is (s/valid? ~spec-kw ~body-sym)
                       (s/explain-str ~spec-kw ~body-sym)))))))))

(defn clear-tests!
  "Remove all deftest vars from the given namespace."
  [ns]
  (doseq [[sym v] (ns-interns ns)
          :when (:test (meta v))]
    (ns-unmap ns sym)))

(defmacro deftestgen
  "Generate clojure.test tests for endpoints defined in the inputs map.

   handler-sym: Symbol referring to the Ring handler function
   inputs-sym: Symbol referring to a map of [method path] -> input
              Only endpoints with keys in this map will have tests generated.
              Each input value is a map with two optional keys:
                :params  — map of parameter values; path params are substituted
                           into the URL, all remaining params are forwarded as
                           query string
                :headers — map of header values to send with the request
                :security — map of OpenAPI security scheme names to credentials;
                            the first satisfiable security requirement is used
   spec-path: Path to the OpenAPI YAML file

   Also generates a `reload-tests!` function that can be called from the REPL
   to reload tests after modifying the OpenAPI spec file or inputs."
  [handler-sym inputs-sym spec-path]
  (let [target-ns (ns-name *ns*)]
    `(do
       (defn ~'reload-tests! []
         ;; Clear existing tests first
         (clear-tests! (find-ns '~target-ns))
         (let [spec# (parser/parse-file ~spec-path)
               inputs# ~inputs-sym
               input-keys# (set (keys inputs#))]
           ;; Register specs
           (doseq [spec-form# (generate-all-specs spec#)]
             (eval spec-form#))
           ;; Define tests
           (doseq [{:keys [~'path ~'method ~'operation]} (parser/get-operations spec#)
                   :let [path-str# (keyword->path-str ~'path)]
                   :when (contains? input-keys# [~'method path-str#])]
             (eval (generate-test-form spec# '~handler-sym '~inputs-sym
                                       ~'method ~'path ~'operation)))))
       ;; Initial load
       (~'reload-tests!))))
