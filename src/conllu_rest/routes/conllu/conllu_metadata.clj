(ns conllu-rest.routes.conllu.conllu-metadata
  (:require [ring.util.http-response :refer :all]
            [conllu-rest.common :as common :refer [error-response nyi-response]]
            [conllu-rest.routes.conllu.common :as cc]
            [conllu-rest.xtdb.easy :as cxe]))

(defn conllu-metadata-routes []
  ["/conllu-metadata"
   ["/id/:id"
    {:get {:summary    "Produce JSON representation of a conllu metadata line"
           :parameters {:path {:id uuid?}}
           :handler    cc/get-handler}}]])
