(ns conllu-rest.routes.conllu.document
  (:require [clojure.spec.alpha :as s]
            [ring.util.http-response :refer :all]
            [conllu-rest.common :as common :refer [error-response nyi-response]]
            [conllu-rest.routes.conllu.common :as cc]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.xtdb.queries.document :as cxqd]
            [conllu-rest.xtdb.serialization :as serial]
            [spec-tools.data-spec :as ds]
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

(defn get-handler [{:keys [query-params path-params node] :as request}]
  (let [id (:id path-params)
        conllu? (= (query-params "format") "conllu")]
    (if-let [uuid (common/parse-uuid id)]
      (let [record (cxe/entity node uuid)]
        (cond (nil? record)
              (not-found)

              (not (:document/id record))
              (bad-request (str "Node with ID " uuid " exists but is not a document."))

              conllu?
              (let [conllu-string (serial/serialize-document node uuid)]
                (-> conllu-string
                    ok
                    (header "Content-Type" "application/x-conllu")))

              :else
              (let [result (cxe/entity node uuid)]
                (if (nil? result)
                  (not-found)
                  (cc/ok* node result)))))
      (bad-request "ID must be a valid java.util.UUID"))))

(defn delete-document [{:keys [path-params node] :as request}]
  (let [document-id (:id path-params)]
    (if-let [document-id (common/parse-uuid document-id)]
      (let [{:keys [status msg]} (cxqd/delete node document-id)]
        (if (= status :ok)
          (ok)
          (bad-request msg)))
      (bad-request "Document ID must be a valid java.util.UUID"))))

(defn document-routes []
  ["/document"
   [""
    {:get {:summary    "Fetch a page's worth of docs (at \"docs\") and a total count of docs (at \"total\")"
           :parameters {:query {:offset int?
                                :limit  int?}}
           :handler    document-query}}]
   ["/id/:id"
    {:get    {:summary    (str "Produce representation of a document. Use \"format\" query param to get "
                               "either json or conllu output.")
              :parameters {:path  {:id uuid?}
                           :query {:format (ds/maybe (s/spec #{"conllu" "json"}))}}
              :handler    get-handler}
     :delete {:summary    "Delete a document and all its contents"
              :parameters {:path {:id uuid?}}
              :handler    delete-document}}]])
