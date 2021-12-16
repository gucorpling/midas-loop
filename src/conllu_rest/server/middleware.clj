(ns conllu-rest.server.middleware
  (:require [clojure.tools.logging :as log]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.middleware.flash :refer [wrap-flash]]
            [ring.adapter.undertow.middleware.session :refer [wrap-session]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [muuntaja.middleware :refer [wrap-format wrap-params]]
            [muuntaja.core :as m]
            [luminus-transit.time :as time]
            [ring.util.response :refer :all]
            [conllu-rest.server.config :refer [env]]
            [conllu-rest.env :refer [defaults]]
            [conllu-rest.common :refer [error-response]]
            [conllu-rest.server.xtdb :refer [xtdb-node]]
            [conllu-rest.server.tokens :refer [xtdb-token-node]]
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
    (-> (handler (assoc request k xtdb-node))
        (header "Access-Control-Allow-Origin" "*"))))

(defn wrap-internal-error [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable t
        (log/error t (.getMessage t))
        (error-response {:status  500
                         :title   "Something very bad has happened!"
                         :message "Let Luke know."})))))

(defn wrap-base [handler]
  (let [wrap-cors* (fn [handler pats]
                     (apply wrap-cors handler pats))
        cors-patterns (concat [#".*localhost.*"] (map re-pattern (:cors-patterns env)))]
    (-> ((:middleware defaults) handler)
        (wrap-cors* cors-patterns)
        (include-database :node xtdb-node)
        (include-database :token-node xtdb-token-node)
        wrap-flash
        (wrap-session {})
        (wrap-defaults
          (-> site-defaults
              ;;(assoc-in [:security :anti-forgery] false)
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
