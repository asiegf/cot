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
