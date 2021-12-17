(ns conllu-rest.routes.conllu.document
  (:require [ring.util.http-response :refer :all]
            [conllu-rest.common :as common :refer [error-response nyi-response]]
            [conllu-rest.routes.conllu.common :as cc]
            [conllu-rest.xtdb.easy :as cxe]
            [clojure.tools.logging :as log]
            [xtdb.api :as xt]))

(defn document-query [{:keys [node] :as req}]
  (let [{:keys [limit offset]} (-> req :parameters :query)]
    (let [offset (or (and (int? offset) (>= offset 0) offset) 0)]
      (cond (not (and (some? limit) (int? limit) (<= limit 100) (> limit 0)))
            (bad-request (str "Limit must be an int between 1 and 100, but got " limit))

            (not (and (int? offset) (>= offset 0)))
            (bad-request (str "Offset must be a non-negative integer: " offset))

            :else
            (let [query {:find     '[?d ?dn]
                         :where    '[[?d :document/id]
                                     [?d :document/name ?dn]]
                         :order-by '[[?dn :desc]]
                         :limit    limit
                         :offset   offset}
                  count-query {:find  '[(count ?d)]
                               :where '[[?d :document/id]]}]
              (ok {:docs  (mapv (fn [[id name]] {:id id :name name})
                                (xt/q (xt/db node) query))
                   :total (ffirst (xt/q (xt/db node) count-query))}))))))

(defn document-routes []
  ["/document"
   [""
    {:get {:summary    "Fetch a page's worth of docs (at \"docs\") and a total count of docs (at \"total\")"
           :parameters {:query {:offset int?
                                :limit  int?}}
           :handler    document-query}}]
   ["/id/:id"
    {:get {:summary    "Produce JSON representation of a document"
           :parameters {:path {:id uuid?}}
           :handler    cc/get-handler}}]

   ])
