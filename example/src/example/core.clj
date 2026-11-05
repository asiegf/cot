(ns example.core
  (:require [cot.parser :as parser]
            [cot.generator :as gen]
            [clojure.spec.alpha :as spec]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]))

(def items  [{:id 0, :name "numero-uno", :status "active"}])
(def featured-items [{:id 0, :name "numero-uno"}])
(def items' [{:id "0", :label "wrong..."}])
(def expected-profile "USER")
(def places [{:id "paris", :name "Paris", :coordinates [2.35 48.85]}])

(defn- json-response
  ([status body] (json-response status body "application/json"))
  ([status body content-type]
   {:status  status
    :headers {"Content-Type" content-type}
    :body    (json/write-str body)}))


(defn find-item [id]
  (first (filter #(= (:id %) id) featured-items)))

(defroutes app
  (GET "/status" _
    {:status  200
     :headers {"Content-Type" "html/text"}})
  (GET "/secure" req
    (let [auth    (get-in req [:headers "authorization"])
          profile (get-in req [:headers "profile"])]
      (if (and auth
               (str/starts-with? auth "Bearer ")
               (= expected-profile profile))
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    (json/write-str {:ok true :message (str "authorized as " profile)})}
        {:status  401
         :headers {"Content-Type" "application/json"}
         :body    (json/write-str {:ok false :message "missing or invalid credentials"})})))
  (GET "/items"
      []
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (json/write-str items) ;; => matches spec
       ;; :body    (json/write-str items') ;; => does NOT match spec
       })
  (GET "/featured-items"
      []
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (json/write-str featured-items)})
  (GET "/items/:id"
      req
      (if-not (get-in req [:headers "token"])
        {:status  401
         :headers {"Content-Type" "application/json"}
         :body    (json/write-str {:error "missing token header"})}
        (let [item (find-item (Integer/parseInt (get-in req [:route-params :id])))]
          (if (and item (= "def" (get-in req [:params "mode"])))
            {:status  200
             :headers {"Content-Type" "application/json"}
             :body    (json/write-str item)}
            {:status 404
             :headers {"Content-Type" "application/json"}
             :body (json/write-str {:error "Item not found"})}))))
  (GET "/places" []
    (json-response 200 (mapv #(select-keys % [:id :name]) places)))
  (GET "/places/:id" [id]
    (if-let [{:keys [coordinates] :as place} (first (filter #(= id (:id %)) places))]
      ;; GeoJSON, one of the two media types documented for 200
      (json-response 200 {:type       "Feature"
                          :geometry   {:type "Point", :coordinates coordinates}
                          :properties (select-keys place [:id :name])}
                     "application/geo+json")
      (json-response 404 {:error "unknown place"})))
  (POST "/uploads" req
    (let [{:strs [file comment]} (:multipart-params req)]
      (if (:tempfile file)
        (json-response 201 (cond-> {:name        (:filename file)
                                    :size        (.length ^java.io.File (:tempfile file))
                                    :contentType (:content-type file)}
                             comment (assoc :comment comment)))
        (json-response 400 {:error "file part missing"}))))
  (GET "/exports/items.csv" req
    ;; answers over an async channel: only a real server (served transport) drives it
    (http/as-channel req {:on-open (fn [ch]
                                     (http/send! ch {:status  200
                                                     :headers {"Content-Type" "text/csv"}
                                                     :body    (str "id,name\n"
                                                                   (str/join "\n" (map #(str (:id %) "," (:name %)) items)))}))}))
  (route/not-found "<h1>Page not found</h1>"))

(def app (-> app wrap-params wrap-multipart-params))

(defonce server (atom nil))

(defn start-server [port]
  (reset! server (http/run-server app {:port port}))
  (println (str "Server started on port " port)))

(defn stop-server []
  (when-let [s @server]
    (s :timeout 100)
    (reset! server nil)))

(defn -main [& args]
  (start-server (or (some-> args first Integer/parseInt) 8080)))
