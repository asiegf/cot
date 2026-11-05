(ns cot.discovery
  "Runtime discovery of path parameter values (ids of existing resources).

  `:resolvers` maps a path prefix ending with the parameter (`\"/plots/{id}\"`) to either
  a fn of ctx returning the value, or `{:from [method path] :extract e}`: the `:from`
  operation is called (derived inputs, 200 expected) and `e` extracts the value from its
  body. `e` is a fn of the body string or a JSON pointer string; it defaults to the
  `\"/0/<param>\"` pointer. Values are memoised for the whole run (one ctx)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cot.request :as request]
            [cot.transport :as transport]))

(defn- unescape-token [t]
  (-> t (str/replace "~1" "/") (str/replace "~0" "~")))

(defn json-pointer
  "Extractor: the value at JSON `pointer` (RFC 6901) of a JSON body, nil when absent or
   null. Scalars come back as Clojure values, objects and arrays as parsed data."
  [pointer]
  (let [tokens (when-not (str/blank? pointer)
                 (map unescape-token (rest (str/split pointer #"/" -1))))]
    (fn [body]
      (when-not (str/blank? body)
        (reduce (fn [node token]
                  (cond
                    (map? node)        (get node token)
                    (sequential? node) (when-let [i (parse-long token)] (get node i))
                    :else              (reduced nil)))
                (json/read-str body)
                tokens)))))

(defn- extractor [e param]
  (cond
    (nil? e)    (json-pointer (str "/0/" param))
    (string? e) (json-pointer e)
    :else       e))

(defn resolver-for
  "`[prefix resolver]` of the longest prefix of `path` ending with `{param}`."
  [ctx path param]
  (->> (:resolvers ctx)
       (filter (fn [[prefix _]] (and (str/starts-with? path prefix)
                                     (str/ends-with? prefix (str "{" param "}")))))
       (sort-by (comp - count key))
       first))

(defn call
  "Run `op` with derived inputs through its transport, unchecked."
  [ctx op]
  (transport/send! ((:transport-for ctx) (request/op-key op))
                   (request/build-request op (request/derive-request ctx op))))

(defn resolve-id
  "Discovered value of `param` for `path`, nil when no resolver covers it."
  [ctx path param]
  (when-let [[prefix resolver] (resolver-for ctx path param)]
    (if-some [v (get @(:ids ctx) prefix)]
      v
      (let [v (if (fn? resolver)
                (resolver ctx)
                (let [{:keys [from extract]} resolver
                      response (call ctx (request/find-op (:all-ops ctx) from))
                      body     (transport/body->string (:body response))
                      v        (when (= 200 (:status response))
                                 ((extractor extract param) body))]
                  (if (and (some? v) (not= "" v))
                    v
                    (throw (ex-info (format "could not discover %s for %s from %s (status %s)"
                                            param prefix (request/op-label from)
                                            (:status response))
                                    {:body (some-> body (subs 0 (min 300 (count body))))})))))]
        (swap! (:ids ctx) assoc prefix v)
        v))))
