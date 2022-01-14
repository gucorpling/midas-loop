(ns conllu-rest.xtdb.queries.token
  (:require [conllu-rest.xtdb.queries :as cxq :refer [write-error write-ok]]
            [xtdb.api :as xt]
            [conllu-rest.xtdb.easy :as cxe]))

(defn put
  "Given a map like :lemma/value and :lemma/id, write the new value"
  [node m id-keyword]
  (locking node
    (let [entity (cxe/entity node (id-keyword m))]
      (cond (nil? entity)
            (write-error (str "Entity does not exist: " m))

            (not (id-keyword entity))
            (write-error (str "Entity is not a " (namespace id-keyword) ": " m))

            :else
            (let [tx (cxe/put* (merge entity (select-keys m [id-keyword (keyword (namespace id-keyword) "value")])))]
              (if (cxe/submit-tx-sync node tx)
                (write-ok)
                (write-error "Failed to put entity")))))))

(defn delete-assoc
  "Only for use with MISC and FEATS--other columns should use put with nil value"
  [node id id-keyword]
  (locking node
    (let [entity (cxe/entity node id)]
      (cond (nil? entity)
            (write-error (str "Annotation does not exist: " id))

            (not (id-keyword entity))
            (write-error (str "Entity is not a " (namespace id-keyword) ": " entity))

            :else
            (let [base-tx (cxq/delete** node {id-keyword id :xt/id id})
                  remove-join-tx (cxq/unlink-in-to-many** node id (keyword "token" (namespace id-keyword)))
                  final-tx (reduce into [base-tx remove-join-tx])]
              (if (cxe/submit-tx-sync node final-tx)
                (write-ok)
                (write-error "Deletion failed")))))))
