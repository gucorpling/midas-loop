(ns conllu-rest.xtdb.queries.document
  (:require [conllu-rest.xtdb.queries :as cxq :refer [write-error write-ok]]
            [xtdb.api :as xt]
            [conllu-rest.xtdb.easy :as cxe]))

(defn delete [node document-id]
  (locking node
    (cond (nil? (cxe/entity node document-id))
          (write-error (str "Document does not exist: " document-id))

          :else
          (let [tx (cxq/delete** node {:document/id document-id :xt/id document-id})]
            (if (cxe/submit-tx-sync node tx)
              (write-ok)
              (write-error "Deletion failed"))))))
