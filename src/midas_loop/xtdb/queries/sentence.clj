(ns midas-loop.xtdb.queries.sentence
  (:require [midas-loop.xtdb.queries :as cxq :refer [write-error write-ok]]
            [xtdb.api :as xt]
            [midas-loop.xtdb.easy :as cxe])
  (:import (java.util UUID)))

(defn sentence-from-token [node id]
  (cxq/parent node :sentence/tokens id))

(defn remove-after [seq elt]
  (vec (take-while #(not= % elt) seq)))

(defn take-from [seq elt]
  (vec (drop-while #(not= % elt) seq)))

(defn find-index [seq elt]
  (reduce-kv
    (fn [_ k v]
      (if (= v elt)
        (reduced k)))
    nil
    seq))

(defn insert-after [seq target-elt added-elt]
  (let [idx (find-index seq target-elt)]
    (if (nil? idx)
      seq
      (let [[before after] (split-at (inc idx) seq)]
        (vec (concat before [added-elt] after))))))

(defn split-sentence
  "Split an existing sentence at a given token, including the token in the new sentence to the right and
  keeping the existing sentence's record for the tokens to the left. Error conditions:

  - Token doesn't exist
  - Sentence doesn't exist for token
  - Document doesn't exist for token
  - Token is already the beginning of a sentence"
  [node token-id]
  (locking node
    (let [sentence-id (sentence-from-token node token-id)
          {:sentence/keys [tokens] :as sentence} (cxe/entity node sentence-id)
          document-id (cxq/parent node :document/sentences sentence-id)]
      (cond
        (seq (cxe/find-entities node [[:token/subtokens token-id]]))
        (write-error (str "Cannot split a sentence on a subtoken of a super token: " token-id))

        (nil? (cxe/entity node token-id))
        (write-error (str "Token doesn't exist: " token-id))

        (nil? sentence-id)
        (write-error (str "Sentence not found for token: " token-id))

        (nil? document-id)
        (write-error (str "Document not found for token: " token-id))

        (= (first tokens) token-id)
        (write-error (str "Token " token-id " is already at the beginning of a sentence."))

        :else
        (let [left-tokens (remove-after tokens token-id)
              right-tokens (take-from tokens token-id)
              first-right-token (cxe/entity node token-id)
              updated-sentence (-> sentence
                                   (assoc :sentence/tokens left-tokens))
              new-sentence-id (UUID/randomUUID)
              new-sentence-record (-> sentence
                                      (assoc :sentence/tokens right-tokens)
                                      (assoc :sentence/id new-sentence-id)
                                      (assoc :xt/id new-sentence-id)
                                      ;; TODO: we want to be more careful than this
                                      (dissoc :sentence/conllu-metadata))
              updated-document (-> (cxe/entity node document-id)
                                   (update :document/sentences insert-after sentence-id new-sentence-id))
              txs [(cxe/put* updated-sentence)
                   (cxe/put* new-sentence-record)
                   (cxe/put* updated-document)
                   (cxe/put* (assoc first-right-token :sentence/quality "gold"))]
              txs (concat txs (cxq/remove-invalid-deps** node left-tokens) (cxq/remove-invalid-deps** node right-tokens))]
          (if (cxe/submit-tx-sync node txs)
            (assoc (write-ok) :new-sentence-id new-sentence-id)
            (write-error "Internal XTDB error")))))))

(defn merge-sentence-right
  "Merge a given sentence, removing the record of the sentence to the right and merging its tokens with the sentence
  on the left. Error conditions:

  - Document doesn't exist for sentence
  - No sentence in the document exists to the right"
  [node sentence-id]
  (locking node
    (let [document-id (cxq/parent node :document/sentences sentence-id)
          {:document/keys [sentences] :as document} (cxe/entity node document-id)]
      (cond
        (nil? (cxe/entity node sentence-id))
        (write-error (str "Sentence not found: " sentence-id))

        (nil? document)
        (write-error (str "Document not found for sentence: " sentence-id))

        (= sentence-id (last sentences))
        (write-error (str "No sentence to merge that follows sentence: " sentence-id))

        :else
        (let [{right-token-ids :sentence/tokens} (cxe/entity node sentence-id)
              first-right-token (cxe/entity node (first right-token-ids))
              other-sentence-id (get sentences (inc (find-index sentences sentence-id)))
              other-sentence (cxe/entity node other-sentence-id)
              new-sentence-list (vec (remove #(= % other-sentence-id) sentences))
              updated-document-record (-> document
                                          (assoc :document/sentences new-sentence-list))
              updated-sentence-record (-> (cxe/entity node sentence-id)
                                          (update :sentence/tokens into (:sentence/tokens other-sentence)))
              ;; TODO: keep any meta from the other sentence?
              txs [(cxe/put* updated-document-record)
                   (cxe/put* updated-sentence-record)
                   (cxe/delete* other-sentence-id)
                   (cxe/put* (assoc first-right-token :sentence/quality "gold"))]]

          (if (cxe/submit-tx-sync node txs)
            (write-ok)
            (write-error "Internal XTDB error")))))))

(defn merge-sentence-left
  [node sentence-id]
  (locking node
    (let [document-id (cxq/parent node :document/sentences sentence-id)
          {:document/keys [sentences]} (cxe/entity node document-id)]
      (cond
        (= sentence-id (first sentences))
        (write-error (str "No sentence to merge that precedes: " sentence-id))

        :else
        (let [other-sentence-id (get sentences (dec (find-index sentences sentence-id)))]
          (merge-sentence-right node other-sentence-id))))))

(defn delete [node sentence-id]
  (locking node
    (cond (nil? (cxe/entity node sentence-id))
          (write-error (str "Sentence does not exist: " sentence-id))

          :else
          (let [base-tx (cxq/delete** node {:sentence/id sentence-id :xt/id sentence-id})
                remove-join-tx (cxq/unlink-in-to-many** node sentence-id :document/sentences)
                final-tx (reduce into [base-tx remove-join-tx])]
            (if (cxe/submit-tx-sync node final-tx)
              (write-ok)
              (write-error "Deletion failed"))))))
