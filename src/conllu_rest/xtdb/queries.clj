(ns conllu-rest.xtdb.queries
  (:require [clojure.tools.logging :as log]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.common :as common]
            [xtdb.api :as xt])
  (:import (java.util UUID)))

;; pulls
(defn- get-typed-id [m]
  (let [typed-id (ffirst (filter (fn [[k _]] (and (= (name k) "id")
                                                  (not= (namespace k) "xt")))
                                 m))]
    (when (nil? typed-id)
      (log/error "Unknown entity type from map:" m))
    typed-id))

(defn- get-pull-fragment [tid]
  (case tid
    :document/id [:document/id
                  :document/name
                  {:document/sentences (get-pull-fragment :sentence/id)}]
    :sentence/id [:sentence/id
                  {:sentence/conllu-metadata (get-pull-fragment :conllu-metadata/id)}
                  {:sentence/tokens (get-pull-fragment :token/id)}]
    :conllu-metadata/id [:conllu-metadata/id
                         :conllu-metadata/key
                         :conllu-metadata/value]
    ;; tokens
    :token/id [:token/id
               :token/token-type
               :token/subtokens
               {:token/form (get-pull-fragment :form/id)}
               {:token/lemma (get-pull-fragment :lemma/id)}
               {:token/upos (get-pull-fragment :upos/id)}
               {:token/xpos (get-pull-fragment :xpos/id)}
               {:token/feats (get-pull-fragment :feats/id)}
               {:token/head (get-pull-fragment :head/id)}
               {:token/deprel (get-pull-fragment :deprel/id)}
               {:token/deps (get-pull-fragment :deps/id)}
               {:token/misc (get-pull-fragment :misc/id)}]
    :form/id [:form/id :form/value]
    :lemma/id [:lemma/id :lemma/value]
    :upos/id [:upos/id :upos/value]
    :xpos/id [:xpos/id :xpos/value]
    :feats/id [:feats/id :feats/key :feats/value]
    :head/id [:head/id :head/value]
    :deprel/id [:deprel/id :deprel/value]
    :deps/id [:deps/id :deps/key :deps/value]
    :misc/id [:misc/id :misc/key :misc/value]))

(defn pull
  [node m]
  (xt/pull (xt/db node) (get-pull-fragment (get-typed-id m)) (:xt/id m)))

;; helpers
(defn write-error [msg]
  {:status :error :msg msg})

(defn write-ok
  ([]
   {:status :ok})
  ([msg]
   {:status :ok :msg msg}))

;; arbitrary queries --------------------------------------------------------------------------------
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

(defn parent
  "Reach a node's parent id via a specified attribute, where ?p -attr-> ?c"
  [node attr id]
  (ffirst (xt/q (xt/db node)
                {:find  '[?p]
                 :where [['?p attr '?c]]
                 :in    '[?c]}
                id)))

(defn sentence-from-token [node id]
  (parent node :sentence/tokens id))

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
            (swap! txs conj (cxe/put* (assoc (cxe/entity node (:token/id token)) :token/deps @new-deps)))))))
    @txs))

(defn split-sentence
  "Split an existing sentence at a given token, including the token in the new sentence to the right and
  keeping the existing sentence's record for the tokens to the left. Error conditions:

  - Token doesn't exist
  - Sentence doesn't exist for token
  - Document doesn't exist for token
  - Token is already the beginning of a sentence"
  [node token-id]
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
          (write-error "Internal XTDB error"))))))

(defn merge-sentence-right
  "Merge a given sentence, removing the record of the sentence to the right and merging its tokens with the sentence
  on the left. Error conditions:

  - Document doesn't exist for sentence
  - No sentence in the document exists to the right"
  [node sentence-id]
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
          (write-error "Internal XTDB error"))))))

(defn merge-sentence-left
  [node sentence-id]
  (let [document-id (parent node :document/sentences sentence-id)
        {:document/keys [sentences]} (cxe/entity node document-id)]
    (cond
      (= sentence-id (first sentences))
      (write-error (str "No sentence to merge that precedes: " sentence-id))

      :else
      (let [other-sentence-id (get sentences (dec (find-index sentences sentence-id)))]
        (merge-sentence-right node other-sentence-id)))))