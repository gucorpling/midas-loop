(ns conllu-rest.routes.conllu.document
  (:require [clojure.spec.alpha :as s]
            [ring.util.http-response :refer :all]
            [conllu-rest.common :as common :refer [error-response nyi-response]]
            [conllu-rest.routes.conllu.common :as cc]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.xtdb.queries.document :as cxqd]
            [conllu-rest.xtdb.queries.diff :as diff]
            [conllu-rest.xtdb.serialization :as serial]
            [spec-tools.data-spec :as ds]
            [xtdb.api :as xt]
            [xtdb.query]))

(def ^:private sort-map
  {"name-inc"      '[?dn :asc]
   "name-dec"      '[?dn :desc]
   "xpos-gold-inc" '[?xgr :asc]
   "xpos-gold-dec" '[?xgr :desc]
   "upos-gold-inc" '[?ugr :asc]
   "upos-gold-dec" '[?ugr :desc]
   "head-gold-inc" '[?hgr :asc]
   "head-gold-dec" '[?hgr :desc]})

(defn document-query [{:keys [node] :as req}]
  (let [{:keys [limit offset order-by]} (-> req :parameters :query)]
    (let [offset (or (and (int? offset) (>= offset 0) offset) 0)
          sort-set (-> sort-map keys set)]
      (cond (not (and (some? limit) (int? limit) (> limit 0)))
            (bad-request (str "Limit must be an int greater than 0, but got " limit))

            (not (and (int? offset) (>= offset 0)))
            (bad-request (str "Offset must be a non-negative integer: " offset))

            (not (sort-set order-by))
            (bad-request (str "order-by parameter must be one of the following: " sort-set))

            :else
            (let [query {:find     '[?d ?dn ?tc ?sc ?xgr ?ugr ?hgr]
                         :where    '[[?d :document/id]
                                     [?d :document/name ?dn]
                                     [?d :document/sentences ?s]
                                     [?d :document/*sentence-count ?sc]
                                     [?d :document/*token-count ?tc]
                                     [?d :document/*xpos-gold-rate ?xgr]
                                     [?d :document/*upos-gold-rate ?ugr]
                                     [?d :document/*head-gold-rate ?hgr]]
                         :order-by [(sort-map order-by)]
                         :limit    limit
                         :offset   offset}
                  count-query {:find  '[(count ?d)]
                               :where '[[?d :document/id]]}]
              (ok {:docs  (mapv (fn [[id name scount tcount xgr ugr hgr :as vals]]
                                  {:id             id
                                   :name           name
                                   :sentence_count scount
                                   :token_count    tcount
                                   :xpos_gold_rate xgr
                                   :upos_gold_rate ugr
                                   :head_gold_rate hgr})
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
           :parameters {:query {:offset   int?
                                :limit    int?
                                :order-by (ds/maybe (s/spec (-> sort-map keys set)))}}
           :handler    document-query}}]
   ["/id/:id"
    {:get    {:summary    (str "Produce representation of a document. Use \"format\" query param to get "
                               "either json or conllu output.")
              :parameters {:path  {:id uuid?}
                           :query {:format (ds/maybe (s/spec #{"conllu" "json"}))}}
              :handler    get-handler}
     :delete {:summary    "Delete a document and all its contents"
              :parameters {:path {:id uuid?}}
              :handler    delete-document}}]
   ["/diff"
    {:post {:summary    (str "Provide old and new CoNLL-U strings for a doc and tell the server to apply the changes.")
            :parameters {:body {:old-conllu string?
                                :new-conllu string?
                                :id         string?}}
            :handler    (fn [{{:keys [old-conllu new-conllu id]} :body-params node :node :as req}]
                          (if-let [document-id (common/parse-uuid id)]
                            (let [status (diff/apply-annotation-diff node document-id old-conllu new-conllu)]
                              (if status
                                (ok)
                                (bad-request (str "Failed to apply diff. Ensure the following:"
                                                  "\n- Sentence and token boundaries are identical"
                                                  "\n- Token forms are identical"
                                                  "\n- DEPS has not changed"))))
                            (bad-request "Document ID must be a valid java.util.UUID")))}}]])
