(ns conllu-rest.xtdb.queries.diff
  (:require [xtdb.api :as xt]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.xtdb.serialization :as cxs]
            [conllu-rest.xtdb.creation :as cxc]
            [conllu-rest.xtdb.queries :as cxq]
            [conllu-rest.server.tokens :as tok]
            [conllu-rest.conllu-parser :as cp]
            [editscript.core :as e]
            [editscript.edit :refer [get-edits]]))

(defn- filter-diff
  [diff]
  (println diff)
  (println (type diff))
  (vec (remove (fn [[path op v]]
                 (uuid? v)
                 #_(and (keyword? (-> path last))
                      (= (-> path last name) "id")
                      (= op :r)))
               diff)))

(defn get-diff
  "Produce an editscript diff for a document given an old and new conllu string representing it.
  If the current state of the document does not match the state represented in old-conllu, reject
  the inputs by returning a map with {:status :invalid}. Returns {:status :valid :diff ...} on success"
  [node {:document/keys [id] :as doc-tree} old-conllu new-conllu]
  (let [old-tx (cxc/build-document (cp/parse-conllu-string old-conllu))
        new-tx (cxc/build-document (cp/parse-conllu-string new-conllu))
        ;; first tx will be putting the document
        old-id (-> old-tx first second :document/id)
        new-id (-> new-tx first second :document/id)
        db (xt/db node)
        spec-db (xt/with-tx db (into old-tx new-tx))
        doc-sent (cxq/pull2 db :document/id id)
        doc-new (cxq/pull2 spec-db :document/id new-id)
        diff (e/diff doc-tree doc-new)]
    (filter-diff (get-edits diff))))


(defmulti transduce-diff-item (fn [[path op val]] op))

(defmethod transduce-diff-item :+ [[path _ val]]
  )

(defn diff->tx [node document-id diff]
  "Given a diff produced by get-diff against a document, transduce the sequence into
  an XTDB tx (= a sequence of tx ops) that will enact the diff on the document."


  )

(cxe/deftx apply-annotation-diff [node document-id old-conllu new-conllu]
  (let [doc-tree (cxq/pull2 node :document/id document-id)]
    ))


(comment
  ;; example diff:
  [[[:document/sentences 0 :sentence/tokens 1 :token/feats 0] :-]
   [[:document/sentences 0 :sentence/tokens 1 :token/feats 1]
    :+
    #:feats{:id #uuid"bbc976df-8bc3-402a-b6a7-11daaf25739b", :key "Number", :value "Sing"}]
   [[:document/sentences 0 :sentence/tokens 3 :token/lemma :lemma/value] :r "FOo"]]

  )