(ns conllu-rest.xtdb.queries.document
  (:require [conllu-rest.xtdb.queries :as cxq :refer [write-error write-ok]]
            [xtdb.api :as xt]
            [xtdb.query]
            [conllu-rest.xtdb.easy :as cxe]
            [clojure.tools.logging :as log]))

(defn delete [node document-id]
  (locking node
    (cond (nil? (cxe/entity node document-id))
          (write-error (str "Document does not exist: " document-id))

          :else
          (let [tx (cxq/delete** node {:document/id document-id :xt/id document-id})]
            (if (cxe/submit-tx-sync node tx)
              (write-ok)
              (write-error "Deletion failed"))))))

(defmethod xtdb.query/aggregate 'count-contents [_]
  (fn
    ([] 0)
    ([acc] acc)
    ([acc x]
     (cond (nil? x) acc
           (coll? x) (+ acc (count x))
           :else (+ acc 1)))))

(defn calculate-stats [node document-id]
  (let [query {:find  '[(count ?s) (count-contents ?t)
                        (count-contents ?xpos-gold)
                        (count-contents ?upos-gold)
                        (count-contents ?head-gold)]
               :where '[[?d :document/id ?id]
                        [?d :document/sentences ?s]
                        ;; subquery: find tokens
                        [(q {:find  [?t],
                             :where [[?s :sentence/tokens ?t]]
                             :in    [?s]} ?s)
                         ?t]

                        ;; subquery: find amount of gold xpos
                        [(q {:find  [?xpos]
                             :where [[?s :sentence/tokens ?t]
                                     [?t :token/xpos ?xpos]
                                     [?xpos :xpos/quality "gold"]]
                             :in    [?s]}
                            ?s)
                         ?xpos-gold]

                        ;; subquery: find amount of gold upos
                        [(q {:find  [?upos]
                             :where [[?d :document/sentences ?s]
                                     [?s :sentence/tokens ?t]
                                     [?t :token/upos ?upos]
                                     [?upos :upos/quality "gold"]]
                             :in    [?d]}
                            ?d)
                         ?upos-gold]

                        ;; subquery: find amount of gold head
                        [(q {:find  [?head]
                             :where [[?d :document/sentences ?s]
                                     [?s :sentence/tokens ?t]
                                     [?t :token/head ?head]
                                     [?head :head/quality "gold"]]
                             :in    [?d]}
                            ?d)
                         ?head-gold]]
               :in    '[?id]}]
    (let [res (xt/q (xt/db node) query document-id)
          [scount tcount xgr ugr hgr] (first res)
          stats {:document/*sentence-count scount
                 :document/*token-count    tcount
                 :document/*xpos-gold-rate (/ xgr tcount)
                 :document/*upos-gold-rate (/ ugr tcount)
                 :document/*head-gold-rate (/ hgr tcount)}]
      (when-not (= 1 (count res))
        (throw (ex-info "ID produced a result set that did not have exactly one member!" {:document-id document-id})))
      (log/info (str "Recalculated stats for " document-id ": " stats))
      (cxe/put node (merge (cxe/entity node document-id) stats)))))