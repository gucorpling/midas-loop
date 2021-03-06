(ns midas-loop.server.middleware
  (:require [clojure.tools.logging :as log]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.middleware.flash :refer [wrap-flash]]
            [ring.adapter.undertow.middleware.session :refer [wrap-session]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [muuntaja.middleware :refer [wrap-format wrap-params]]
            [muuntaja.core :as m]
            [luminus-transit.time :as time]
            [ring.util.response :refer :all]
            [midas-loop.server.config :refer [env]]
            [midas-loop.env :refer [defaults]]
            [midas-loop.common :refer [error-response]]
            [jumblerg.middleware.cors :refer [wrap-cors]]))

(def muuntaja-instance
  (m/create
    (-> m/default-options
        (update-in
          [:formats "application/transit+json" :decoder-opts]
          (partial merge time/time-deserialization-handlers))
        (update-in
          [:formats "application/transit+json" :encoder-opts]
          (partial merge time/time-serialization-handlers)))))

(defn include-database
  [handler k xtdb-node]
  (fn [request]
    (handler (assoc request k xtdb-node))))

(defn wrap-internal-error [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable t
        (log/error t (.getMessage t))
        (error-response {:status  500
                         :title   "Something very bad has happened!"
                         :message "Let Luke know."})))))

(defn wrap-base [node token-node handler]
  (let [wrap-cors* (fn [handler pats]
                     (apply wrap-cors handler pats))
        cors-patterns (concat [#".*localhost.*"] (map re-pattern (:cors-patterns env)))]
    (-> ((:middleware defaults) handler)
        (wrap-cors* cors-patterns)
        (include-database :node node)
        (include-database :token-node token-node)
        wrap-flash
        (wrap-defaults
          (-> site-defaults
              (assoc-in [:security :anti-forgery] false)
              (dissoc :session)))
        wrap-internal-error)))

(defn wrap-csrf [handler]
  (wrap-anti-forgery
    handler
    {:error-response
     (error-response
       {:status 403
        :title "Invalid anti-forgery token"})}))

(defn wrap-formats [handler]
  (let [wrapped (-> handler wrap-params (wrap-format muuntaja-instance))]
    (fn [request]
      ;; disable wrap-formats for websockets
      ;; since they're not compatible with this middleware
      ((if (:websocket? request) handler wrapped) request))))
