(ns example.core
  (:require [cot.parser :as parser]
            [cot.generator :as gen]
            [clojure.spec.alpha :as spec]
            [clojure.data.json :as json]
            [org.httpkit.server :as http]
            [compojure.core :refer :all]
            [compojure.route :as route]))


(def items  [{:id 0, :name "numero-uno"}])
(def items' [{:id "0", :label "wrong..."}])


(defn find-item [id]
  (first (filter #(= (:id %) id) items)))

(defroutes app
  (GET "/status" _
    {:status  200
     :headers {"Content-Type" "html/text"}})
  (GET "/secure" req
    (do (println req)
      (if (get-in req [:headers "token"])
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    (json/write-str {:ok true :message "authorized"})}
        {:status  401
         :headers {"Content-Type" "application/json"}
         :body    (json/write-str {:ok false :message "missing token in header"})})))
  (GET "/items"
       []
       {:status  200
        :headers {"Content-Type" "application/json"}
        :body    (json/write-str items)     ;; => matches spec
        ;; :body    (json/write-str items') ;; => does NOT match spec
        })
  (GET "/items/:id"
       [id]
       (let [item (find-item (Integer/parseInt id))]
         (if item
           {:status  200
            :headers {"Content-Type" "application/json"}
            :body    (json/write-str item)}
           {:status 404
            :headers {"Content-Type" "application/json"}
            :body (json/write-str {:error "Item not found"})})))
  (route/not-found "<h1>Page not found</h1>"))

(defonce server (atom nil))

(defn start-server [port]
  (reset! server (http/run-server #'app {:port port}))
  (println (str "Server started on port " port)))

(defn stop-server []
  (when-let [s @server]
    (s :timeout 100)
    (reset! server nil)))

(defn -main [& args]
  (start-server (or (some-> args first Integer/parseInt) 8080)))

