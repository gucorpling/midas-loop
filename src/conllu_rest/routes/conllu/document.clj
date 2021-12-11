(ns conllu-rest.routes.conllu.document
  (:require [ring.util.http-response :refer :all]
            [conllu-rest.common :as common :refer [error-response nyi-response]]
            [conllu-rest.routes.conllu.common :as cc]
            [conllu-rest.xtdb.easy :as cxe]))

(defn document-routes []
  ["/document"
   ["/:id"
    {:get {:summary    "Produce JSON representation of a document"
           :parameters {:path {:id uuid?}}
           :handler    cc/get-handler}}]])
