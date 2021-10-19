(ns conllu-rest.middleware
  (:require [clojure.tools.logging :as log]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.middleware.flash :refer [wrap-flash]]
            [ring.adapter.undertow.middleware.session :refer [wrap-session]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [muuntaja.middleware :refer [wrap-format wrap-params]]
            [conllu-rest.config :refer [env]]
            [conllu-rest.env :refer [defaults]]
            [conllu-rest.common :refer [error-response]]))

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
      wrap-flash
      (wrap-session {})
      (wrap-defaults
        (-> site-defaults
            (assoc-in [:security :anti-forgery] false)
            (dissoc :session)))
      wrap-internal-error))
