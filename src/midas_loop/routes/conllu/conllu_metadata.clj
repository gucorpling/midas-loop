(ns midas-loop.routes.conllu.conllu-metadata
  (:require [ring.util.http-response :refer :all]
            [midas-loop.common :as common :refer [error-response nyi-response]]
            [midas-loop.routes.conllu.common :as cc]
            [midas-loop.xtdb.easy :as cxe]))

(defn conllu-metadata-routes []
  ["/conllu-metadata"
   ["/id/:id"
    {:get {:summary    "Produce JSON representation of a conllu metadata line"
           :parameters {:path {:id uuid?}}
           :handler    (cc/get-handler :conllu-metadata/id)}}]])
