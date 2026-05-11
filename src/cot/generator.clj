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

(defn- safe-name
  [x]
  (if (keyword? x) (name x) (str x)))

(defn- scoped-property-keyword
  [scope prop-name]
  (keyword (:property-ns scope) (safe-name prop-name)))

(defn- child-scope
  [scope prop-name]
  (update scope :property-ns str "." (safe-name prop-name)))

(defn- component-scope
  [schema-name]
  {:property-ns (str "cot.schema." (safe-name schema-name))})

(defn- component-spec-keyword
  [schema-name]
  (keyword "cot.schema" (safe-name schema-name)))

(defn schema->spec
  "Convert an OpenAPI schema to a spec form."
  ([schema]
   (schema->spec schema {:property-ns "cot.schema"}))
  ([schema scope]
   (case (:type schema)
     "object"
     (let [required (set (map keyword (:required schema)))
           prop-keys (keys (:properties schema))
           req-keys (filter required prop-keys)
           opt-keys (remove required prop-keys)]
       `(s/keys ~@(when (seq req-keys)
                    [:req-un (mapv #(scoped-property-keyword scope %) req-keys)])
                ~@(when (seq opt-keys)
                    [:opt-un (mapv #(scoped-property-keyword scope %) opt-keys)])))

     "array"
     `(s/coll-of ~(schema->spec (:items schema) scope) :min-count 1)

     (openapi-type->predicate schema))))

(defn- generate-property-spec-defs
  [schema scope]
  (case (:type schema)
    "object"
    (mapcat (fn [[prop-name prop-schema]]
              (let [prop-scope (child-scope scope prop-name)]
                (cons `(s/def ~(scoped-property-keyword scope prop-name)
                         ~(schema->spec prop-schema prop-scope))
                      (generate-property-spec-defs prop-schema prop-scope))))
            (:properties schema))

    "array"
    (generate-property-spec-defs (:items schema) scope)

    []))

(defn- generate-schema-spec-defs
  [spec-kw scope schema]
  (concat
   (generate-property-spec-defs schema scope)
   [`(s/def ~spec-kw
       ~(schema->spec schema scope))]))

(defn generate-spec-defs
  "Generate spec definitions for all properties in a schema."
  [schema-name schema]
  (generate-schema-spec-defs
   (component-spec-keyword schema-name)
   (component-scope schema-name)
   schema))

(declare generate-response-spec-defs)

(defn generate-all-specs
  "Generate spec definitions for all component schemas in an OpenAPI spec."
  [openapi-spec]
  (concat
   (mapcat (fn [[schema-name schema]]
             (generate-spec-defs schema-name schema))
           (get-schemas openapi-spec))
   (mapcat (fn [{:keys [path method operation]}]
             (generate-response-spec-defs method path operation))
           (parser/get-operations openapi-spec))))

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

(defn- operation-scope-name
  [method path]
  (-> (str (name method) "-" (keyword->path-str path) "-200-response")
      (str/replace #"\{|\}" "")
      (str/replace #"[^A-Za-z0-9]+" "-")
      (str/replace #"(^-)|(-$)" "")))

(defn- response-spec-keyword*
  [method path]
  (keyword "cot.response" (operation-scope-name method path)))

(defn- response-item-spec-keyword
  [method path]
  (keyword "cot.response" (str (operation-scope-name method path) "-item")))

(defn- response-scope
  [method path]
  {:property-ns (str "cot.response." (operation-scope-name method path))})

(defn- response-item-scope
  [method path]
  {:property-ns (str "cot.response." (operation-scope-name method path) ".item")})

(defn- inline-schema?
  [schema]
  (and schema (not (:$ref schema))))

(defn generate-response-spec-defs
  "Generate spec definitions for inline 200 JSON response schemas."
  [method path operation]
  (let [schema (get-in operation
                       [:responses :200 :content :application/json :schema])]
    (cond
      (not (inline-schema? schema))
      []

      (and (= "array" (:type schema)) (get-in schema [:items :$ref]))
      []

      (= "array" (:type schema))
      (generate-schema-spec-defs
       (response-item-spec-keyword method path)
       (response-item-scope method path)
       (:items schema))

      :else
      (generate-schema-spec-defs
       (response-spec-keyword* method path)
       (response-scope method path)
       schema))))

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

(defn- json-response?
  "Check if the operation returns application/json."
  [operation]
  (some? (get-in operation [:responses :200 :content :application/json])))

(defn- response-spec-keyword
  "Get the spec keyword for the response schema of an operation.
   If it's a ref, return the schema name; if it's an inline array,
   return a generated keyword."
  ([spec operation]
   (response-spec-keyword spec operation nil nil))
  ([spec operation method path]
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
            (keyword "cot.schema"))

       (and (= "array" (:type schema)) method path)
       (response-item-spec-keyword method path)

       (and (inline-schema? schema) method path)
       (response-spec-keyword* method path)))))

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
        spec-kw    (when json? (response-spec-keyword spec operation method path))
        input-sym   (gensym "input")
        params-sym  (gensym "params")
        headers-sym (gensym "headers")
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
               ~request-path-sym (path-template->request-path
                                  ~path-str ~params-sym)
               ~request-sym (let [query-params# (into {} (keep (fn [[k# v#]]
                                                                (when-not (contains? ~path-params k#)
                                                                  [(keyword (name k#))
                                                                   v#]))
                                                              ~params-sym))
                                   qs# (str/join "&" (map (fn [[k# v#]] (str (name k#) "=" v#)) query-params#))]
                              (reduce (fn [r# [k# v#]]
                                        (mock/header r# (name k#) v#))
                                      (cond-> (mock/request ~method ~request-path-sym)
                                        (seq query-params#) (assoc :params query-params#)
                                        (seq qs#) (mock/query-string qs#))
                                      ~headers-sym))
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
