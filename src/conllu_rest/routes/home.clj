(ns conllu-rest.routes.home
  (:require [conllu-rest.layout :as layout]
            [clojure.java.io :as io]
            [conllu-rest.server.middleware :as middleware]
            [ring.util.response]
            [ring.util.http-response :as response]))

(defn home-page [request]
  (layout/render request "public/index.html"))

(defn home-routes []
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/" {:get home-page}]])
