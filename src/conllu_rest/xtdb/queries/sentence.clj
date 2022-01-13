(ns conllu-rest.xtdb.queries.sentence
  (:require [conllu-rest.xtdb.queries :refer [write-error write-ok find-index parent]]
            [xtdb.api :as xt]
            [conllu-rest.xtdb.easy :as cxe]))

(defn sentence-from-token [node id]
  (parent node :sentence/tokens id))

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

(defn remove-invalid-deps
  "Checks head, deprel, and deps and removes any entries that point to tokens which are not in token-ids.
  Useful for post-processing a sentence split. Returns a transaction vector--no side effects.."
  [node token-ids]
  (let [tokens (map #(xt/pull (xt/db node)
                              [:token/id
                               {:token/head [:head/value :head/id]}
                               {:token/deprel [:deprel/value :deprel/id]}
                               {:token/deps [:deps/key :deps/value :deps/id]}]
                              %)
                    token-ids)
        txs (atom [])]
    (doseq [token tokens]
      (let [token-ids (conj (set token-ids) :root)
            {head-id :head/id head-value :head/value} (:token/head token)
            {deprel-id :deprel/id} (:token/deprel token)]
        (when-not (token-ids head-value)
          (swap! txs conj (cxe/put* (assoc (cxe/entity node head-id) :head/value nil)))
          (swap! txs conj (cxe/put* (assoc (cxe/entity node deprel-id) :deprel/value nil))))

        (let [orig-deps (:token/deps token)
              new-deps (atom orig-deps)]
          (doseq [{deps-id :deps/id head-id :deps/key :as dep} (:token/deps token)]
            (when-not (token-ids head-id)
              (swap! txs conj (cxe/delete* deps-id))
              (swap! new-deps #(filterv (fn [e] (not= (:deps/id e) deps-id)) %))))
          (when-not (= @new-deps orig-deps)
            (swap! txs conj (cxe/put* (assoc (cxe/entity node (:token/id token)) :token/deps (mapv :deps/id @new-deps))))))))
    @txs))

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
          document-id (parent node :document/sentences sentence-id)]
      (cond
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
                   (cxe/put* updated-document)]
              txs (concat txs (remove-invalid-deps node left-tokens) (remove-invalid-deps node right-tokens))]
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
    (let [document-id (parent node :document/sentences sentence-id)
          {:document/keys [sentences] :as document} (cxe/entity node document-id)]
      (cond
        (nil? (cxe/entity node sentence-id))
        (write-error (str "Sentence not found: " sentence-id))

        (nil? document)
        (write-error (str "Document not found for sentence: " sentence-id))

        (= sentence-id (last sentences))
        (write-error (str "No sentence to merge that follows sentence: " sentence-id))

        :else
        (let [other-sentence-id (get sentences (inc (find-index sentences sentence-id)))
              other-sentence (cxe/entity node other-sentence-id)
              new-sentence-list (vec (remove #(= % other-sentence-id) sentences))
              updated-document-record (-> document
                                          (assoc :document/sentences new-sentence-list))
              updated-sentence-record (-> (cxe/entity node sentence-id)
                                          (update :sentence/tokens into (:sentence/tokens other-sentence)))
              ;; TODO: keep any meta from the other sentence?
              txs [(cxe/put* updated-document-record)
                   (cxe/put* updated-sentence-record)
                   (cxe/delete* other-sentence-id)]]

          (if (cxe/submit-tx-sync node txs)
            (write-ok)
            (write-error "Internal XTDB error")))))))

(defn merge-sentence-left
  [node sentence-id]
  (locking node
    (let [document-id (parent node :document/sentences sentence-id)
          {:document/keys [sentences]} (cxe/entity node document-id)]
      (cond
        (= sentence-id (first sentences))
        (write-error (str "No sentence to merge that precedes: " sentence-id))

        :else
        (let [other-sentence-id (get sentences (dec (find-index sentences sentence-id)))]
          (merge-sentence-right node other-sentence-id))))))