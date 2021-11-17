(ns conllu-rest.xtdb.creation
  "Contains functions for creating XTDB records for conllu input. Note the following important joins:

  :document/sentences
    :sentence/conllu-metadata
    :sentence/tokens
      :token/feats
      :token/deps
      :token/misc"
  (:require [conllu-rest.xtdb.easy :as cxe])
  (:import (java.util UUID)))

;; TODO:
;; - IDs should be uuids, not human-readable strings
;; - "id" keyword should be removed from tokens--it's inferrable. But need a supertoken/empty token/token distinguisher

(def atomic-fields #{:id :form :lemma :upos :xpos :head :deprel})
(def associative-fields #{:feats :misc :deps})

(def document-name-key (or (System/getProperty "document-name-key" "meta::id")))
(def document-id-key (or (System/getProperty "document-id-key" "newdoc id")))

(defn resolve-from-metadata
  "Attempt to read a piece of metadata from the first sentence in the document.
  Throw if the key doesn't exist."
  [document key]
  (if-let [value (get (-> document first :metadata) key)]
    value
    (throw (ex-info (str "Attempted to upload document, but required metadata key \"" key "\" was not found")
                    {:document document
                     :key      key}))))

(defn create-conllu-metadata [[key value]]
  (let [conllu-metadata-id (UUID/randomUUID)]
    [(cxe/put* (cxe/create-record
                 "conllu-metadata"
                 conllu-metadata-id
                 {:conllu-metadata/key   key
                  :conllu-metadata/value value}))]))

(defn create-associative [name [key value]]
  (let [associative-id (UUID/randomUUID)]
    [(cxe/put* (cxe/create-record
                 name
                 associative-id
                 {(keyword name "key")   key
                  (keyword name "value") value}))]))

(defn create-token
  "Create a token record for the 10 standard conllu columns.
  Columns are differentiated by whether they're atomic or associative--feats, misc, and deps are associative.
  In the associative case, another record is created for each key-value pair, and a join is added at
  :token/deps, :token/feats, or :token/misc.

  NOTE: :token/id is the same as the internal database ID (:xt/id). It is NOT the same thing as the `id`
  conllu column, which is not represented in the database (it is generated when conllu is serialized)."
  [token]
  ;; Todo: implement token-type based on value of :id
  (let [token-id (UUID/randomUUID)
        ;; todo: maybe refactor to rely on `associative-fields`
        feats-txs (reduce into (map #(create-associative "feats" %) (:feats token)))
        feats-ids (mapv (comp :feats/id second) feats-txs)
        deps-txs (reduce into (map #(create-associative "deps" %) (:deps token)))
        deps-ids (mapv (comp :deps/id second) deps-txs)
        misc-txs (reduce into (map #(create-associative "misc" %) (:misc token)))
        misc-ids (mapv (comp :misc/id second) misc-txs)
        token (cxe/create-record "token" token-id (merge {:token/feats feats-ids
                                                          :token/deps  deps-ids
                                                          :token/misc  misc-ids
                                                          :token/id    token-id}
                                                         (into {} (mapv (fn [[k v]] [(keyword "token" (name k)) v])
                                                                        (select-keys token (disj atomic-fields :id))))))
        token-tx (cxe/put* token)]
    (reduce into [token-tx] [feats-txs deps-txs misc-txs])))

(defn- sentence-ids-from-txs
  "each item in a sentence tx looks like [[:xtdb.api/put {:sentence/id :foo, ...}] ...]
  this function takes a seq of sentence-txs and returns a seq of their ids"
  [sentence-txs]
  (mapv (comp :sentence/id second first) sentence-txs))

(defn create-sentence
  "Returns a giant transaction vector for this sentence and the sentence's tokens"
  [{:keys [tokens metadata]}]
  (let [sentence-id (UUID/randomUUID)
        conllu-metadata-txs (reduce into (map #(create-conllu-metadata %) metadata))
        conllu-metadata-ids (mapv (comp :conllu-metadata/id second) conllu-metadata-txs)
        token-txs (reduce into (map create-token tokens))
        token-ids (mapv (comp :token/id second) token-txs)
        sentence (cxe/create-record "sentence"
                                    sentence-id
                                    {:sentence/conllu-metadata conllu-metadata-ids
                                     :sentence/tokens          token-ids})
        sentence-tx (cxe/put* sentence)]
    (reduce into [sentence-tx] [conllu-metadata-txs token-txs])))

;; TODO check if IDs already exist, or use IDs directly
(defn create-document [xtdb-node document]
  (let [document-id (UUID/randomUUID)
        ;; First: read document name from metadata
        document-name (resolve-from-metadata document document-name-key)
        ;; Next, set up sentence transactions and note their IDs
        sentence-txs (map create-sentence document)
        sentence-ids (sentence-ids-from-txs sentence-txs)
        ;; join the document to the sentence ids
        document (cxe/create-record "document" document-id {:document/name      document-name
                                                            :document/sentences sentence-ids})
        document-tx (cxe/put* document)
        final-tx (reduce into [document-tx] sentence-txs)]

    (cxe/submit-tx-sync xtdb-node final-tx)))


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
1	Juan	Juan	PROPN	NNP	Number=Sing	0	root	0:root	Discourse=preparation:1->6|Entity=(person-1
2	de	de	PROPN	NNP	Number=Sing	1	flat	1:flat	_
3	Cartagena	Cartagena	PROPN	NNP	Number=Sing	1	flat	1:flat	Entity=person-1)

")

  (def xs (conllu-rest.conllu/parse-conllu-string data))

  (ffirst xs)


  (require '[conllu-rest.xtdb :refer [xtdb-node]])

  (first xs)

  (create-document xtdb-node xs)




  xs
  (:metadata (first xs)))
