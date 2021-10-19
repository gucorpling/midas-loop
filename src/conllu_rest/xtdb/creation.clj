(ns conllu-rest.xtdb.creation
  (:require [conllu-rest.xtdb.easy :as cxe]
            )
  (:import (java.util UUID)))

(def document-name-key (or (System/getProperty "document-name-key" "meta::id")))
(def document-id-key (or (System/getProperty "document-id-key" "newdoc id")))

(defn resolve-from-metadata [document key]
  (if-let [name (get (-> document first :metadata) key)]
    name
    (throw (ex-info (str "Attempted to upload document, but required metadata key \"" key "\" was not found")
                    {:document document
                     :key      key}))))

(defn create-conllu-metadata [sentence-id key value]
  (let [conllu-metadata-id (str sentence-id "-conllu_metadata:" key)]
    [(cxe/put* (cxe/create-record
                 "conllu-metadata"
                 conllu-metadata-id
                 {:conllu-metadata/key   key
                  :conllu-metadata/value value}))]))

(def atomic-fields #{:id :form :lemma :upos :xpos :head :deprel})
(def associative-fields #{:feats :misc :deps})

(defn create-associative [name sentence-id [key value]]
  (let [associative-id (str sentence-id "-" name ":" key)]
    [(cxe/put* (cxe/create-record name associative-id {(keyword name "key")   key
                                                       (keyword name "value") value}))]))

(defn create-token
  [sentence-id token]

  (let [token-id (str sentence-id "-token:" (:id token))
        feats-txs (reduce into (map (partial create-associative "feats" sentence-id) (:feats token)))
        feats-ids (mapv (comp :feats/id second) feats-txs)
        deps-txs (reduce into (map (partial create-associative "deps" sentence-id) (:deps token)))
        deps-ids (mapv (comp :deps/id second) deps-txs)
        misc-txs (reduce into (map (partial create-associative "misc" sentence-id) (:misc token)))
        misc-ids (mapv (comp :misc/id second) misc-txs)
        token (cxe/create-record "token" token-id (merge {:token/feats feats-ids
                                                          :token/deps  deps-ids
                                                          :token/misc  misc-ids}
                                                         (select-keys token atomic-fields)))
        token-tx (cxe/put* token)]
    (reduce into [token-tx] [feats-txs deps-txs misc-txs])))

(defn create-sentence [document-id order {:keys [tokens metadata]}]
  (let [sentence-id (str document-id "-sentence:" (inc order))
        conllu-metadata-txs (reduce into (map #(create-conllu-metadata sentence-id (first %) (second %)) metadata))
        conllu-metadata-ids (mapv (comp :conllu-metadata/id second) conllu-metadata-txs)
        token-txs (reduce into (map (partial create-token sentence-id) tokens))
        token-ids (mapv (comp :token/id second) token-txs)
        sentence (cxe/create-record "sentence"
                                    sentence-id
                                    {:sentence/conllu-metadata conllu-metadata-ids
                                     :sentence/tokens          token-ids})
        sentence-tx (cxe/put* sentence)]
    (reduce into [sentence-tx] [conllu-metadata-txs token-txs])))

;; TODO check if IDs already exist, or use IDs directly
(defn create-document [xtdb-node document]
  (let [document-id (resolve-from-metadata document document-id-key)
        document-name (resolve-from-metadata document document-name-key)
        sentence-txs (map-indexed (partial create-sentence document-id) document)
        sentence-ids (mapv (comp :sentence/id second first) sentence-txs)
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
