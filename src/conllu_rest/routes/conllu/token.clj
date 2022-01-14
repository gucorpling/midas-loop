(ns conllu-rest.routes.conllu.token
  (:require [ring.util.http-response :refer :all]
            [conllu-rest.common :as common :refer [error-response nyi-response]]
            [conllu-rest.routes.conllu.common :as cc]
            [conllu-rest.xtdb.easy :as cxe]))


(def nop (constantly nil))

(defn token-routes []
  ["/token"
   ["/id/:id"
    {:get    {:summary    "Produce JSON representation of a token"
              :parameters {:path {:id uuid?}}
              :handler    (cc/get-handler :token/id)}
     :delete {:summary    "Delete a token"
              :parameters {:path {:id uuid?}}
              :handler    nop}}]])

(defn atomic-routes [colname]
  [(str "/" colname)
   ["/id/:id"
    {:get    {:summary    (str "Produce JSON representation of a " colname)
              :parameters {:path {:id uuid?}}
              :handler    (cc/get-handler (keyword colname "id"))}
     ;; Note: this doesn't actually delete the XTDB record--rather, it nils out its value
     :delete {:summary    (str "Delete a " colname)
              :parameters {:path {:id uuid?}}
              :handler    nop}
     :put    {:summary    (str "Update a " colname ". Pass a JSON as body with a \"value\" key.")
              :parameters {:path {:id uuid?}
                           :body {:value string?}}
              :handler    nop}}]])
