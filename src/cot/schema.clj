(ns cot.schema
  "OpenAPI documents as JSON-Schema registries (networknt json-schema-validator).

  Schemas are validated at their JSON pointer inside the document, so `$ref`, `nullable`,
  `oneOf`, `allOf` and `format` behave as the document's OpenAPI version says: the
  OpenAPI 3.0 dialect for `openapi: 3.0.x`, the 3.1 dialect otherwise."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cot.parser :as parser])
  (:import [com.networknt.schema InputFormat Schema SchemaLocation SchemaRegistry
            SchemaRegistry$Builder]
           [com.networknt.schema.dialect Dialects]
           [java.util.function Consumer]))

;; networknt warns about every non-schema keyword of an OpenAPI document
(System/setProperty "org.slf4j.simpleLogger.log.com.networknt" "error")

(defn pointer-escape
  "JSON-pointer token of a document key (`~` and `/` escaped)."
  [k]
  (-> (parser/kname k) (str/replace "~" "~0") (str/replace "/" "~1")))

(defn- dialect [doc]
  (if (str/starts-with? (str (:openapi doc)) "3.0")
    (Dialects/getOpenApi30)
    (Dialects/getOpenApi31)))

(defn- source->location
  "[iri text] of a spec given as a classpath resource or a file path."
  [source]
  (if-let [resource (io/resource source)]
    [(str "classpath:" source) (slurp resource)]
    (let [file (io/file source)]
      (when-not (.isFile file)
        (throw (ex-info (str "OpenAPI spec not found on the classpath nor as a file: " source)
                        {:spec source})))
      [(str (.toURI (.getCanonicalFile file))) (slurp file)])))

(defn load-spec
  "Load an OpenAPI document from a classpath resource or a file path (YAML or JSON).
   Returns `{:source :iri :doc :registry}`; `:doc` is the `cot.parser` map."
  [source]
  (let [[iri text] (source->location source)
        doc        (parser/parse-string text)
        ;; networknt only fetches classpath: IRIs by itself; files are mapped
        registry   (SchemaRegistry/withDefaultDialect
                    (dialect doc)
                    (reify Consumer
                      (accept [_ builder]
                        (when-not (str/starts-with? iri "classpath:")
                          (.schemas ^SchemaRegistry$Builder builder {iri text})))))]
    {:source   source
     :iri      iri
     :doc      doc
     :registry registry
     :schemas  (atom {})}))

(defn schema-at
  "Compiled schema at `pointer` (e.g. \"/components/schemas/Item\"), cached per spec."
  ^Schema [{:keys [iri registry schemas]} pointer]
  (or (get @schemas pointer)
      (let [schema (.getSchema ^SchemaRegistry registry
                               (SchemaLocation/of (str iri "#" pointer)))]
        (swap! schemas assoc pointer schema)
        schema)))

(defn validate
  "Seq of validation error strings of the JSON text `body` against the schema at
   `pointer` (empty when valid). A body that isn't JSON is one error."
  [spec pointer ^String body]
  (try
    (map str (.validate (schema-at spec pointer) body InputFormat/JSON))
    (catch Exception e
      (if (instance? com.fasterxml.jackson.core.JacksonException (ex-cause e))
        [(str "body is not valid JSON: " (ex-message (ex-cause e)))]
        (throw e)))))

(defn summarize-errors
  "Errors grouped by pattern (array indices collapsed), most frequent first, at most 20."
  [errors]
  (->> errors
       (map #(str/replace % #"/\d+(?=/)" "/*"))
       frequencies
       (sort-by (comp - val))
       (take 20)
       (map (fn [[e n]] (if (= 1 n) e (format "%s (x%d)" e n))))))

;; -----------------------------------------------------------------------------
;; Media types

(defn media-essence
  "Lower-cased `type/subtype` of a media type or content-type header, nil when blank."
  [media]
  (some-> media (str/split #";") first str/trim str/lower-case not-empty))

(defn json-media?
  "True for application/json and any +json or json-ish media type."
  [media]
  (boolean (some-> (media-essence media) (str/includes? "json"))))

(defn- media-matches? [documented essence]
  (let [d (media-essence documented)]
    (or (= d essence)
        (= d "*/*")
        (and (str/ends-with? d "/*")
             (str/starts-with? essence (subs d 0 (dec (count d))))))))

(defn match-media
  "The documented media type (string) a response `content-type` belongs to: an exact
   match first, then a wildcard range (`application/*`, `*/*`). nil when none matches."
  [documented content-type]
  (when-let [essence (media-essence content-type)]
    (or (some #(when (= essence (media-essence %)) %) documented)
        (some #(when (media-matches? % essence) %) documented))))
