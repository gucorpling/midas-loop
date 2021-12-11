(ns conllu-rest.server.middleware
  (:require [clojure.tools.logging :as log]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.middleware.flash :refer [wrap-flash]]
            [ring.adapter.undertow.middleware.session :refer [wrap-session]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [muuntaja.middleware :refer [wrap-format wrap-params]]
            [conllu-rest.server.config :refer [env]]
            [conllu-rest.env :refer [defaults]]
            [conllu-rest.common :refer [error-response]]
            [conllu-rest.server.xtdb :refer [xtdb-node]]
            [conllu-rest.server.tokens :refer [xtdb-token-node]]))


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

(defn wrap-base [handler]
  (-> ((:middleware defaults) handler)
      (include-database :node xtdb-node)
      (include-database :token-node xtdb-token-node)
      wrap-flash
      (wrap-session {})
      (wrap-defaults
        (-> site-defaults
            (assoc-in [:security :anti-forgery] false)
            (dissoc :session)))
      wrap-internal-error))
