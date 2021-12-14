(ns conllu-rest.xtdb.creation
  "Contains functions for creating XTDB records for conllu input. Note the following important joins:

  :document/sentences
    :sentence/conllu-metadata
    :sentence/tokens
      :token/token-type
      :token/form
      ...
      :token/misc"
  (:require [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.conllu-parser :refer [parse-conllu-string]]
            [xtdb.api :as xt]
            [clojure.tools.logging :as log])
  (:import (java.util UUID)))

;; TODO:
;; These are all standard CoNLL-U EXCEPT for :token-type. We do not want to store :id
;; because it could be invalidated by edits to tokenization. Rather, we will store id type
;; (:token, :super, :empty)
(def atomic-fields #{:token-type :form :lemma :upos :xpos :head :deprel})
(def associative-fields #{:feats :misc :deps})

(def document-name-key (or (System/getProperty "document-name-key" "newdoc id")))

(defn resolve-from-metadata
  "Attempt to read a piece of metadata from the first sentence in the document.
  Throw if the key doesn't exist."
  [document key]
  (if-let [value (get (into {} (-> document first :metadata)) key)]
    value
    (throw (ex-info (str "Attempted to upload document, but required metadata key \"" key "\" was not found")
                    {:document document
                     :key      key}))))

;; transaction builder helpers --------------------------------------------------------------------------------
(defn build-conllu-metadata [[key value]]
  (let [conllu-metadata-id (UUID/randomUUID)]
    [(cxe/put* (cxe/create-record
                 "conllu-metadata"
                 conllu-metadata-id
                 {:conllu-metadata/key   key
                  :conllu-metadata/value value}))]))

(defn build-associative
  ([token name]
   (build-associative token name identity))
  ([token name key-xform]
   (let [name-kwd (keyword name)
         txs (reduce into (map (fn [[key value]]
                                 (let [associative-id (UUID/randomUUID)]
                                   [(cxe/put* (cxe/create-record
                                                name
                                                associative-id
                                                {(keyword name "key")   (key-xform key)
                                                 (keyword name "value") value}))]))
                               (name-kwd token)))
         ids (mapv (comp (keyword name "id") second) txs)]
     [txs ids])

   ))

(defn build-atomic
  ([token name]
   (build-atomic token name identity))
  ([token name field-xform]
   (let [name-kwd (keyword name)
         field-value (field-xform (name-kwd token))
         txs (let [atomic-id (UUID/randomUUID)]
               [(cxe/put* (cxe/create-record
                            name
                            atomic-id
                            {(keyword name "value") field-value}))])
         ids (mapv (comp (keyword name "id") second) txs)]
     [txs ids])))

(defn determine-token-type [{:keys [id]}]
  (cond (and (coll? id) (= (second id) "-")) :super
        (and (coll? id) (= (second id) ".")) :empty
        :else :token))

(defn add-subtokens
  "Assumes that supertokens are only ever used for normal tokens, and therefore have an ID like 11-12 or 13-16"
  [token token-id-map [start _ end]]
  (let [subtoken-orig-ids (range start (inc end))]
    (assoc token :token/subtokens (mapv token-id-map subtoken-orig-ids))))

(defn build-token
  "Create a token record for 9 of the 10 standard conllu columns. ID is the exception, and is replaced by
  \"token-type\" since records should be resilient to tokenization changes. IDs are instead inferred at export time.
  Columns are differentiated by whether they're atomic or associative--feats, misc, and deps are associative.
  Each column has an associated key on the token record which is a join--a to-one join in the atomic case, and a
  to-many join in the associative case (MISC, FEATS, DEPS)

  NOTE: :token/id is the same as the internal database ID (:xt/id). It is NOT the same thing as the `id`
  conllu column, which is not represented in the database (it is generated when conllu is serialized)."
  [token-id-map token]
  ;; Todo: add validations if needed
  (let [token-type (determine-token-type token)
        orig-id (:id token)
        new-id (get token-id-map orig-id)
        token (-> token (assoc :token-type token-type) (dissoc :id))
        ;; todo: maybe refactor to rely on `associative-fields`
        [form-txs [form-id]] (build-atomic token "form")
        [lemma-txs [lemma-id]] (build-atomic token "lemma")
        [upos-txs [upos-id]] (build-atomic token "upos")
        [xpos-txs [xpos-id]] (build-atomic token "xpos")
        [feats-txs feats-ids] (build-associative token "feats")
        [head-txs [head-id]] (build-atomic token "head" #(token-id-map %))
        [deprel-txs [deprel-id]] (build-atomic token "deprel")
        [deps-txs deps-ids] (build-associative token "deps" #(token-id-map %))
        [misc-txs misc-ids] (build-associative token "misc")
        token (cxe/create-record
                "token"
                new-id
                (cond-> {:token/token-type token-type
                         :token/form       form-id
                         :token/lemma      lemma-id
                         :token/upos       upos-id
                         :token/xpos       xpos-id
                         :token/feats      feats-ids
                         :token/head       head-id
                         :token/deprel     deprel-id
                         :token/deps       deps-ids
                         :token/misc       misc-ids}
                        (= token-type :super) (add-subtokens token-id-map orig-id)))
        token-tx (cxe/put* token)]
    (reduce into
            [token-tx]
            [form-txs
             lemma-txs
             upos-txs
             xpos-txs
             feats-txs
             head-txs
             deprel-txs
             deps-txs
             misc-txs])))

(defn- sentence-ids-from-txs
  "each item in a sentence tx looks like [[:xtdb.api/put {:sentence/id :foo, ...}] ...]
  this function takes a seq of sentence-txs and returns a seq of their ids"
  [sentence-txs]
  (mapv (comp :sentence/id second first) sentence-txs))

(defn build-sentence
  "Returns a giant transaction vector for this sentence and the sentence's tokens"
  [{:keys [tokens metadata]}]
  (let [sentence-id (UUID/randomUUID)
        ;; supertokens need to have the ID of subsequent tokens ready--generate IDs here to facilitate this
        token-id-map (into {0 :root} (map (fn [{:keys [id]}] [id (UUID/randomUUID)]) tokens))
        conllu-metadata-txs (reduce into (map #(build-conllu-metadata %) metadata))
        conllu-metadata-ids (mapv (comp :conllu-metadata/id second) conllu-metadata-txs)
        token-txs (reduce into (map #(build-token token-id-map %) tokens))
        token-ids (mapv (comp :token/id second) (filter (comp :token/id second) token-txs))
        sentence (cxe/create-record "sentence"
                                    sentence-id
                                    {:sentence/conllu-metadata conllu-metadata-ids
                                     :sentence/tokens          token-ids})
        sentence-tx (cxe/put* sentence)]
    (reduce into [sentence-tx] [conllu-metadata-txs token-txs])))

;; TODO check if IDs already exist, or use IDs directly
(defn build-document [document]
  (let [document-id (UUID/randomUUID)
        ;; First: read document name from metadata
        document-name (resolve-from-metadata document document-name-key)
        ;; Next, set up sentence transactions and note their IDs
        sentence-txs (map build-sentence document)
        sentence-ids (sentence-ids-from-txs sentence-txs)
        ;; join the document to the sentence ids
        document (cxe/create-record "document" document-id {:document/name      document-name
                                                            :document/sentences sentence-ids})
        document-tx (cxe/put* document)
        final-tx (reduce into [document-tx] sentence-txs)]

    final-tx))

(defn create-document
  "Call build-document and use its output to submit to a xtdb node"
  [xtdb-node document]
  (cxe/submit-tx-sync xtdb-node (build-document document)))

(defn ingest-conllu-file
  "Given a system filepath, ingest it by reading it, parsing it, calling build-document on it,
  and submitting it to xtdb. Note that this does not call xt/await-tx on the transaction, meaning
  there are no guarantees about whether the changes will be indexed."
  [xtdb-node filepath]
  (let [parsed (->> filepath
                    slurp
                    parse-conllu-string)
        transactions (build-document parsed)]
    (xt/submit-tx xtdb-node transactions)
    (log/info "Processed" (-> parsed first :metadata (->> (into {})) (get "newdoc id")))))

(defn ingest-conllu-files
  "Call ingest-conllu-file on a seq of filepaths."
  [xtdb-node filepaths]
  (doall (pmap (partial ingest-conllu-file xtdb-node) filepaths)))


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

  xs

  (ffirst xs)

  (require '[conllu-rest.xtdb :refer [xtdb-node]])

  (first xs)

  (create-document xtdb-node xs)

  xs
  (:metadata (first xs)))


(comment

  (require '[conllu-rest.server.xtdb :refer [xtdb-node]])

  (doseq [genre ["bio" "fiction" "news" "academic" "interview" "voyage" "whow"]]
    (let [path (str "amalgum/amalgum/" genre "/dep")
          filenames (seq (.list (clojure.java.io/file path)))
          filepaths (sort (map #(str path "/" %) filenames))]

      (ingest-conllu-files xtdb-node filepaths)
      ))

  (xt/q (xt/db xtdb-node)
        '{:find    [(count ?d)]
          :where   [[?d :token/id]]
          :timeout 3000000})

  )




