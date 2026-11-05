(ns cot.parser
  "Parser for OpenAPI 3.x YAML specifications."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(defn parse-file
  "Parse an OpenAPI YAML file and return it as a Clojure map."
  [path]
  (-> path slurp yaml/parse-string))

(defn parse-string
  "Parse an OpenAPI YAML string and return it as a Clojure map."
  [s]
  (yaml/parse-string s))

(defn- ref->path
  "Convert a $ref string like '#/components/schemas/Item' to a path vector."
  [ref-str]
  (when (str/starts-with? ref-str "#/")
    (->> (str/split (subs ref-str 2) #"/")
         (mapv keyword))))

(defn resolve-ref
  "Resolve a $ref reference within the spec."
  [spec ref-str]
  (when-let [path (ref->path ref-str)]
    (get-in spec path)))

(defn resolve-refs
  "Recursively resolve all $ref references in a value."
  [spec value]
  (cond
    (and (map? value) (contains? value :$ref))
    (resolve-refs spec (resolve-ref spec (:$ref value)))

    (map? value)
    (into {} (map (fn [[k v]] [k (resolve-refs spec v)]) value))

    (sequential? value)
    (mapv #(resolve-refs spec %) value)

    :else value))

(defn get-paths
  "Extract all paths from the spec."
  [spec]
  (:paths spec))

(defn get-operations
  "Extract all operations as a flat sequence of maps with :path, :method, and :operation."
  [spec]
  (for [[path methods] (:paths spec)
        [method operation] methods
        :when (#{:get :post :put :patch :delete :head :options} method)]
    {:path path
     :method method
     :operation operation}))

(defn follow-ref
  "`value` with its top-level `$ref` chain followed (nested refs left as they are, so
   recursive schemas are safe)."
  [spec value]
  (loop [v value, seen #{}]
    (if-let [ref (and (map? v) (:$ref v))]
      (if (contains? seen ref)
        (throw (ex-info (str "circular $ref " ref) {:ref ref}))
        (recur (resolve-ref spec ref) (conj seen ref)))
      v)))

(defn kname
  "Original string of a key produced by clj-yaml (:/a/{id}, :200, :application/json)."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(def http-methods #{:get :post :put :patch :delete :head :options})

(defn operation-parameters
  "Path-level and operation-level parameters of an operation, the parameter and its
   schema dereferenced.
   An operation-level parameter overrides the path-level one with the same name and
   location."
  [spec path-item operation]
  (->> (concat (:parameters path-item) (:parameters operation))
       (map (fn [p] (let [p (follow-ref spec p)]
                      (cond-> p (:schema p) (update :schema (partial follow-ref spec))))))
       (reduce (fn [m p] (assoc m [(:name p) (:in p)] p)) {})
       vals))

(defn security-requirements
  "Effective security requirements of an operation: its own `security`, else the
   document-level one. A seq of alternatives (OR), each a map scheme-key -> scopes (AND).
   `[]` means explicitly unsecured."
  [spec operation]
  (if (contains? operation :security)
    (:security operation)
    (:security spec)))

(defn operations
  "Every `{:method :path :path-kw :operation :params}` of the spec whose path satisfies
   `scope` (a predicate on the path string)."
  ([spec] (operations spec (constantly true)))
  ([spec scope]
   (for [[path-kw item] (:paths spec)
         :let  [path (kname path-kw)]
         :when (scope path)
         [method op] item
         :when (http-methods method)]
     {:method    method
      :path      path
      :path-kw   path-kw
      :operation op
      :params    (operation-parameters spec item op)})))

(defn get-schemas
  "Extract component schemas from the spec."
  [spec]
  (get-in spec [:components :schemas]))

(defn get-response-schema
  "Get the resolved response schema for a given operation and status code."
  [spec operation status-code]
  (when-let [schema (get-in operation
                            [:responses status-code :content :application/json :schema])]
    (resolve-refs spec schema)))
