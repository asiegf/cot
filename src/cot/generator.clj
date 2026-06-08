(ns cot.generator
  (:require [cot.parser :as parser :refer [get-schemas]]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :as t]
            [clojure.walk :as walk]
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

(defn- ref->spec-kw
  "Convert a JSON-Pointer-style $ref string to a cot.schema keyword."
  [ref-str]
  (->> (str/split ref-str #"/") last (keyword "cot.schema")))

(defn- array-count-opts
  "Translate OpenAPI array cardinality keywords to s/coll-of options."
  [schema]
  (vec
   (concat
    (when (contains? schema :minItems)
      [:min-count (:minItems schema)])
    (when (contains? schema :maxItems)
      [:max-count (:maxItems schema)]))))

(defn schema->spec
  "Convert an OpenAPI schema to a spec form or registered-spec keyword.
   A `$ref` is returned as `:cot.schema/<SchemaName>`, which spec resolves
   through its registry at check time — so refs are handled the same way
   whether they appear in a component, a property, an array `items`, or
   an inline request/response schema."
  [schema]
  (cond
    (:$ref schema) (ref->spec-kw (:$ref schema))
    (:enum schema) (list `partial `contains? (set (:enum schema)))
    :else
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
      `(s/coll-of ~(schema->spec (:items schema))
                  ~@(array-count-opts schema))

      (openapi-type->predicate schema))))

(defn- schema-prop-defs
  "Recursively collect `s/def` forms for every property of an object
   schema and every nested inline object/array schema. Skips `$ref`
   nodes — their targets are registered as component schemas."
  [schema]
  (cond
    (:$ref schema) nil
    (= "object" (:type schema))
    (concat
     (for [[prop-name prop-schema] (:properties schema)]
       `(s/def ~(keyword "cot.schema" (name prop-name))
          ~(schema->spec prop-schema)))
     (mapcat schema-prop-defs (vals (:properties schema))))
    (= "array" (:type schema))
    (schema-prop-defs (:items schema))
    :else nil))

(defn generate-spec-defs
  "Generate spec definitions for a component schema: `s/def` forms for
   every (recursively-nested) property, then a top-level def for the
   component itself."
  [schema-name schema]
  (concat
   (schema-prop-defs schema)
   [`(s/def ~(keyword "cot.schema" (name schema-name))
       ~(schema->spec schema))]))

(defn- json-response-schemas
  "Return every application/json response schema across all operations."
  [openapi-spec]
  (for [[_ methods] (:paths openapi-spec)
        [method operation] methods
        :when (#{:get :post :put :patch :delete :head :options} method)
        [_ response] (:responses operation)
        :let [schema (get-in response [:content :application/json :schema])]
        :when schema]
    schema))

(defn generate-all-specs
  "Generate spec definitions for every component schema and every inline
   object property encountered in JSON response schemas.

   Component specs are forward-declared with `any?` first so property
   `$ref`s between components resolve regardless of iteration order.
   Inline response schemas are walked afterwards so their properties
   (e.g. the `owner` field of an inline `/profile` response) become
   registered specs rather than silently-ignored `:req-un` keywords."
  [openapi-spec]
  (let [schemas    (get-schemas openapi-spec)
        op-schemas (json-response-schemas openapi-spec)]
    (concat
     (for [[schema-name _] schemas]
       `(s/def ~(keyword "cot.schema" (name schema-name)) any?))
     (mapcat (fn [[schema-name schema]]
               (generate-spec-defs schema-name schema))
             schemas)
     (mapcat schema-prop-defs op-schemas))))

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

(defn validation-case->test-name
  [{:keys [method path expected-status case-index legacy?]}]
  (if legacy?
    (operation->test-name method path)
    (symbol (str (operation->test-name method path)
                 "-" expected-status "-case-" case-index))))

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

(defn status-key
  [status]
  (keyword (str status)))

(defn- response-spec
  "Return a spec form or keyword for the response schema of an operation.
   Returns nil when no JSON schema is declared."
  ([_spec operation]
   (response-spec _spec operation 200))
  ([_spec operation status]
   (when-let [schema (get-in operation
                             [:responses (status-key status)
                              :content :application/json :schema])]
     (schema->spec schema))))

(defn- validate-input-map
  [input]
  (when-not (map? input)
    (throw (ex-info "Validation case input must be a map" {:input input})))
  (when-let [unsupported (seq (remove #{:params :headers} (keys input)))]
    (throw (ex-info "Validation case input supports only :params and :headers"
                    {:unsupported-keys unsupported :input input})))
  (doseq [k [:params :headers]
          :let [value (get input k)]
          :when (some? value)]
    (when-not (map? value)
      (throw (ex-info (str (name k) " must be a map")
                      {:key k :value value}))))
  input)

(defn normalize-validation-case
  "Normalize one ordered validation vector to a test case map."
  [case-index validation-case]
  (when-not (vector? validation-case)
    (throw (ex-info "Validation cases must be vectors"
                    {:case-index case-index :validation-case validation-case})))
  (let [[method path input expected-status]
        (case (count validation-case)
          1 [:get (nth validation-case 0) {} 200]
          2 (if (keyword? (nth validation-case 0))
              [(nth validation-case 0) (nth validation-case 1) {} 200]
              (if (integer? (nth validation-case 1))
                [:get (nth validation-case 0) {} (nth validation-case 1)]
                [:get (nth validation-case 0) (nth validation-case 1) 200]))
          3 (if (keyword? (nth validation-case 0))
              [(nth validation-case 0) (nth validation-case 1)
               (nth validation-case 2) 200]
              [:get (nth validation-case 0) (nth validation-case 1)
               (nth validation-case 2)])
          4 validation-case
          (throw (ex-info "Validation cases must have one to four elements"
                          {:case-index case-index
                           :validation-case validation-case})))]
    (when-not (keyword? method)
      (throw (ex-info "Validation case method must be a keyword"
                      {:case-index case-index :method method})))
    (when-not (string? path)
      (throw (ex-info "Validation case path must be a string"
                      {:case-index case-index :path path})))
    (when-not (integer? expected-status)
      (throw (ex-info "Validation case expected status must be an integer"
                      {:case-index case-index
                       :expected-status expected-status})))
    {:case-index case-index
     :method method
     :path path
     :input (validate-input-map (or input {}))
     :expected-status expected-status
     :legacy? false}))

(defn normalize-validations
  "Normalize ordered validation vectors or the legacy input map."
  [validations]
  (cond
    (map? validations)
    (mapv (fn [case-index [[method path] input]]
            (assoc (normalize-validation-case case-index
                                              [method path input 200])
                   :legacy? true))
          (range 1 (inc (count validations)))
          validations)

    (sequential? validations)
    (mapv normalize-validation-case
          (range 1 (inc (count validations)))
          validations)

    :else
    (throw (ex-info "Validations must be an ordered sequence or legacy map"
                    {:validations validations}))))

(defn read-runtime-inputs
  "Read runtime placeholder values from EDN. #env resolves environment variables."
  [path]
  (if path
    (let [runtime-inputs
          (edn/read-string
           {:readers {'env (fn [name]
                             (or (System/getenv (str name))
                                 (throw
                                  (ex-info "Required environment variable is missing"
                                           {:environment-variable (str name)}))))}}
           (slurp path))]
      (when-not (map? runtime-inputs)
        (throw (ex-info "Runtime input EDN must contain a map"
                        {:path path
                         :runtime-inputs runtime-inputs})))
      runtime-inputs)
    {}))

(defn resolve-runtime-values
  "Recursively replace symbol placeholders with values from runtime inputs."
  [value runtime-inputs]
  (walk/postwalk
   (fn [item]
     (if (symbol? item)
       (if (contains? runtime-inputs item)
         (get runtime-inputs item)
         (throw (ex-info "Runtime input placeholder is missing"
                         {:placeholder item})))
       item))
   value))

(defn validate-validation-operation
  "Return an OpenAPI operation after validating a normalized case against it."
  [operation {:keys [method path expected-status]}]
  (when-not operation
    (throw (ex-info "Validation case operation is absent from OpenAPI"
                    {:method method :path path})))
  (when-not (contains? (:responses operation) (status-key expected-status))
    (throw (ex-info "Expected status is absent from OpenAPI operation"
                    {:method method
                     :path path
                     :expected-status expected-status
                     :documented-statuses (vec (keys (:responses operation)))})))
  operation)

(defn generate-invalid-validation-test-form
  "Generate a failing test for a validation case that cannot target OpenAPI."
  [validation-case error]
  (let [{:keys [method path expected-status]} validation-case
        expected {:method method
                  :path path
                  :expected-status expected-status}
        actual   (merge {:validation-error (ex-message error)}
                        (ex-data error))]
    `(t/deftest ~(validation-case->test-name validation-case)
       (t/do-report {:type :fail
                     :message ~(ex-message error)
                     :expected ~expected
                     :actual ~actual}))))

(defn- path-param-names
  "Extract path parameter names from a path template string as a set of keywords.
   Input: \"/items/{id}\"
   Output: #{:id}"
  [path-str]
  (->> (re-seq #"\{(\w+)\}" path-str)
       (map (comp keyword second))
       set))

(defn generate-test-form
  "Generate a deftest form for a normalized validation case."
  [spec handler-sym validation-case operation runtime-input-path]
  (let [{:keys [method path input expected-status]} validation-case
        path-str   (keyword->path-str path)
        path-params (path-param-names path-str)
        spec-form  (response-spec spec operation expected-status)
        input-sym   (gensym "input")
        params-sym  (gensym "params")
        headers-sym (gensym "headers")
        request-path-sym (gensym "request-path")
        request-sym  (gensym "request")
        response-sym (gensym "response")
        body-sym     (gensym "body")]
    `(t/deftest ~(validation-case->test-name validation-case)
       (t/testing ~(format "%s %s returns valid response"
                           (str/upper-case (name method))
                           path-str)
         (let [~input-sym   (resolve-runtime-values
                             '~input
                             (read-runtime-inputs ~runtime-input-path))
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
               ~@(when spec-form
                   [body-sym `(json/read-str (:body ~response-sym)
                                             :key-fn keyword)])]
           (t/is (= ~expected-status (:status ~response-sym)))
           ~(when spec-form
              `(t/is (s/valid? ~spec-form ~body-sym)
                     (s/explain-str ~spec-form ~body-sym))))))))

(defn clear-tests!
  "Remove all deftest vars from the given namespace."
  [ns]
  (doseq [[sym v] (ns-interns ns)
          :when (:test (meta v))]
    (ns-unmap ns sym)))

(defmacro deftestgen
  "Generate clojure.test tests for ordered validation cases.

   The preferred validation form is an ordered sequence of vectors. The legacy
   map form remains supported for compatibility. An optional runtime EDN path
   resolves symbol placeholders when each generated test executes."
  ([handler-sym validations-sym spec-path]
   `(deftestgen ~handler-sym ~validations-sym ~spec-path nil))
  ([handler-sym validations-sym spec-path runtime-input-path]
   (let [target-ns (ns-name *ns*)]
     `(do
        (defn ~'reload-tests! []
          (clear-tests! (find-ns '~target-ns))
          (let [spec# (parser/parse-file ~spec-path)
                validations# (normalize-validations ~validations-sym)
                runtime-input-path# ~runtime-input-path
                operations# (into {}
                                  (map (fn [{:keys [~'path ~'method ~'operation]}]
                                         [[~'method (keyword->path-str ~'path)]
                                          ~'operation]))
                                  (parser/get-operations spec#))]
            (doseq [spec-form# (generate-all-specs spec#)]
              (eval spec-form#))
            (doseq [{:keys [~'method ~'path ~'expected-status]
                     :as validation-case#} validations#]
              (try
                (let [operation# (validate-validation-operation
                                  (get operations# [~'method ~'path])
                                  validation-case#)]
                  (eval (generate-test-form spec# '~handler-sym validation-case#
                                            operation# runtime-input-path#)))
                (catch clojure.lang.ExceptionInfo error#
                  (eval (generate-invalid-validation-test-form
                         validation-case# error#)))))))
        (~'reload-tests!)))))
