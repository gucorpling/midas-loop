(ns midas-loop.server.handler
  (:require [midas-loop.server.middleware :as middleware]
            [midas-loop.common :refer [error-response]]
            [midas-loop.routes.services :refer [service-routes]]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring :as ring]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.webjars :refer [wrap-webjars]]
            [midas-loop.env :refer [defaults]]
            [mount.core :as mount]))

(mount/defstate init-app
  :start ((or (:init defaults) (fn [])))
  :stop ((or (:stop defaults) (fn []))))

(mount/defstate app-routes
  :start
  (ring/ring-handler
    (ring/router
      [(service-routes)])
    (ring/routes
      (swagger-ui/create-swagger-ui-handler
        {:path   "/swagger-ui"
         :url    "/api/swagger.json"
         :config {:validator-url nil}})
      ;; TODO: extend via config to allow serving different static resources
      (ring/create-resource-handler
        {:path "/"})
      (wrap-content-type
        (wrap-webjars (constantly nil)))
      (ring/create-default-handler
        {:not-found
         (constantly (error-response {:status 404, :body "404 - Page not found"}))
         :method-not-allowed
         (constantly (error-response {:status 405, :body "405 - Not allowed"}))
         :not-acceptable
         (constantly (error-response {:status 406, :body "406 - Not acceptable"}))}))))

(defn app [node token-node]
  (middleware/wrap-base node token-node #'app-routes))
