(ns conllu-rest.xtdb.scratch
  (:require [xtdb.api :as xt]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.xtdb.serialization :as cxs]
            [conllu-rest.xtdb.creation :as cxc]
            [conllu-rest.xtdb.queries :as cxq]))


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

(comment
  (def node (xtdb.api/start-node {}))

  (def xs (conllu-rest.conllu-parser/parse-conllu-string data))

  (require '[conllu-rest.xtdb :refer [xtdb-node]])

  (cxc/create-document node xs)

  (def doc-id (ffirst (xt/q (xt/db node) {:find '[?d] :where [['?d :document/id]]})))

  (spit "/tmp/bar" (conllu-rest.xtdb.serialization/serialize-document node doc-id))

  )


(comment
  ;; upload into real db
  (doseq [genre ["bio" "fiction" "news" "academic" "interview" "voyage" "whow"]]
    (let [path (str "amalgum/amalgum/" genre "/dep")
          filenames (seq (.list (clojure.java.io/file path)))
          filepaths (sort (map #(str path "/" %) filenames))]

      (cxc/ingest-conllu-files xtdb-node filepaths)
      ))
  )

(comment
  ;; tokens
  (require '[conllu-rest.server.tokens :refer [xtdb-token-node create-token]])
  (create-token xtdb-token-node {:name "Luke" :email "lukegessler@gmail.com"})

  )

