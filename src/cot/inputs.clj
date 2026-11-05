(ns cot.inputs
  "Runtime inputs: EDN files of placeholder values (with `#env`), and their substitution
  into quoted configuration."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]))

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

(defn load-inputs
  "Runtime inputs given as an EDN path, an already-read map, or nil."
  [inputs]
  (cond
    (nil? inputs) {}
    (map? inputs) inputs
    :else         (read-runtime-inputs inputs)))
