(ns cot.transport
  "How a ring request reaches the app under test.

  - `:ring` (default): in-process handler call;
  - `:served`: a throwaway http-kit server on port 0 and the http-kit client, for routes
    answering over an async channel (e.g. CSV streams), which an in-process call can't
    drive. http-kit is an optional dependency, loaded when this transport is used."
  (:require [clojure.string :as str]))

(defprotocol Transport
  (send! [transport request] "Send a ring request, return a ring response."))

(defn body->string
  "Response body as a string (nil when absent)."
  [body]
  (cond
    (nil? body)        nil
    (string? body)     body
    (sequential? body) (apply str body)
    :else              (slurp body)))

(defn header
  "Value of header `k` (case-insensitive) in a ring request or response."
  [message k]
  (let [k (str/lower-case (name k))]
    (some (fn [[h v]] (when (= k (str/lower-case (name h))) v)) (:headers message))))

(defn ring-transport [app]
  (reify Transport
    (send! [_ request] (app request))))

(defn- http-kit [sym]
  (or (try (requiring-resolve sym) (catch java.io.FileNotFoundException _ nil))
      (throw (ex-info (str "The :served transport needs http-kit on the classpath "
                           "(add http-kit/http-kit to your test dependencies)")
                      {:missing sym}))))

(defn served-transport [app]
  (reify Transport
    (send! [_ {:keys [request-method uri query-string headers body]}]
      (let [run-server  (http-kit 'org.httpkit.server/run-server)
            server-port (http-kit 'org.httpkit.server/server-port)
            server-stop (http-kit 'org.httpkit.server/server-stop!)
            request     (http-kit 'org.httpkit.client/request)
            srv         (run-server app {:port 0, :legacy-return-value? false})]
        (try
          (let [{:keys [status headers body error]}
                @(request {:method  request-method
                           :url     (str "http://localhost:" (server-port srv)
                                         uri (when query-string (str "?" query-string)))
                           :headers (dissoc headers "host" "content-length")
                           :body    body
                           :as      :text
                           :timeout 60000})]
            (when error (throw error))
            {:status status, :headers (update-keys headers name), :body body})
          (finally
            (server-stop srv)))))))

(defn transport
  "Transport from a keyword (`:ring`, `:served`) or a `Transport` instance."
  [app t]
  (cond
    (satisfies? Transport t) t
    (= :served t)            (served-transport app)
    (or (nil? t) (= :ring t)) (ring-transport app)
    :else (throw (ex-info (str "Unknown transport " (pr-str t)
                               " (expected :ring, :served or a cot.transport/Transport)")
                          {:transport t}))))
