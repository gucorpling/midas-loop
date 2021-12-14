(ns conllu-rest.routes.conllu.token
  (:require [ring.util.http-response :refer :all]
            [conllu-rest.common :as common :refer [error-response nyi-response]]
            [conllu-rest.routes.conllu.common :as cc]
            [conllu-rest.xtdb.easy :as cxe]))


(defn token-routes []
  ["/token"
   ["/id/:id"
    {:get {:summary    "Produce JSON representation of a token"
           :parameters {:path {:id uuid?}}
           :handler    cc/get-handler}}]])

