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

;; TODO: deprel, head, and deps columns need to be changed when this happens
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
      (let [updated-sentence (-> sentence
                                         (assoc :sentence/tokens (remove-after tokens token-id)))
            new-sentence-id (UUID/randomUUID)
            new-sentence-record (-> sentence
                                    (assoc :sentence/tokens (take-from tokens token-id))
                                    (assoc :sentence/id new-sentence-id)
                                    (assoc :xt/id new-sentence-id)
                                    ;; TODO: we want to be more careful than this
                                    (dissoc :sentence/conllu-metadata))
            updated-document (-> (cxe/entity node document-id)
                                         (update :document/sentences insert-after sentence-id new-sentence-id))
            txs [(cxe/put* updated-sentence)
                 (cxe/put* new-sentence-record)
                 (cxe/put* updated-document)]]
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
            new-sentence-list (remove #(= % other-sentence-id) sentences)
            updated-document-record (-> document
                                         (assoc :document/sentences new-sentence-list))
            updated-sentence-record (-> (cxe/entity node sentence-id)
                                         (update :sentence/tokens concat (:sentence/tokens other-sentence)))
            ;; TODO: keep any meta from the other sentence?
            txs [(cxe/put* updated-document-record)
                 (cxe/put* updated-sentence-record)
                 (cxe/delete* other-sentence-id)]]

        (if (cxe/submit-tx-sync node txs)
          (write-ok)
          (write-error "Internal XTDB error"))))))

(comment
  (pull
    conllu-rest.server.xtdb/xtdb-node
    {:document/id #uuid"fe704604-90f9-4d42-bc68-9b8832c0ebf0" #_#_:token/id "62f9fe8e-7e25-4e94-bee8-b2c0eeea83f2"
     :xt/id       #uuid"fe704604-90f9-4d42-bc68-9b8832c0ebf0"}
    )


  (cxe/entity
    conllu-rest.server.xtdb/xtdb-node
    #uuid"fe704604-90f9-4d42-bc68-9b8832c0ebf0")

  (get-pull-fragment :token/id)

  )

(comment
  (def node (xtdb.api/start-node {}))

  (def data "
# meta::id = AMALGUM_bio_cartagena
# meta::title = Juan de Cartagena
# meta::shortTitle = cartagena
# meta::type = bio
# meta::dateCollected = 2019-11-05
# meta::dateCreated = 2012-11-03
# meta::dateModified = 2019-10-01
# meta::sourceURL = https://en.wikipedia.org/wiki/Juan_de_Cartagena
# meta::speakerList = none
# meta::speakerCount = 0
# newdoc id = AMALGUM_bio_cartagena
# sent_id = AMALGUM_bio_cartagena-1
# s_type = frag
# text = Juan de Cartagena
# newpar = head (1 s)
1-2	Juan	Juan	_	_	_	_	_	_	_
1	Juan	Juan	PROPN	NNP	Number=Sing	0	root	0:root	Discourse=preparation:1->6|Entity=(person-1
2	de	de	PROPN	NNP	Number=Sing	1	flat	1:flat	_
2.1	foo	foo	_	_	_	_	_	_	_
2.2	bar	bar	_	_	_	_	_	_	_
3	Cartagena	Cartagena	PROPN	NNP	Number=Sing	1	flat	1:flat	Entity=person-1)
")

  (def xs (conllu-rest.conllu-parser/parse-conllu-string data))

  (ffirst xs)

  (require '[conllu-rest.server.xtdb :refer [xtdb-node]])

  (pull xtdb-node {:xt/id #uuid "2dc17a1c-6038-4ce5-8af1-7c2b0252a01b"
                   :sentence/id #uuid "2dc17a1c-6038-4ce5-8af1-7c2b0252a01b"})

  (cxe/entity xtdb-node #uuid "08231967-9165-48ff-925b-4c2e3c72ed34")

  (first xs)

  (conllu-rest.xtdb.creation/create-document node xs)

  (cxe/q node {:find '[?t ?fv] :where ['[?t :token/id]
                                       '[?t :token/form ?f]
                                       '[?f :form/value ?fv]]})

  (split-sentence node #uuid"dcc68d93-7e09-47e3-8e96-98eb8c1d25b1")

  xs
  (:metadata (first xs)))