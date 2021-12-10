(ns conllu-rest.routes.conllu.token
  (:require [ring.util.http-response :refer :all]
            [conllu-rest.common :as common :refer [error-response nyi-response]]
            [spec-tools.core :as st]
            [conllu-rest.xtdb.easy :as cxe])
  (:import (java.util UUID)))


(defn get-token [{:keys [path-params xtdb] :as request}]
  (let [id (:id path-params)]
    (if-not (common/uuid-string? id)
      (bad-request "ID must be a valid java.util.UUID")
      (let [id (UUID/fromString id)
            result (cxe/entity xtdb id)]
        (println result)
        (println "WOW")
        (ok result)
        ))))


(defn token-routes []
  ["/token"
   ["/:id"
    {:get {:summary    "Produce JSON representation of a token"
           :parameters {:path {:id uuid?}}
           :handler    get-token}}]

   ])

