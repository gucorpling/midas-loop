(ns conllu-rest.xtdb
  "Integration tests covering "
  (:require [clojure.test :refer :all]
            [xtdb.api :as xt]
            [conllu-rest.server.xtdb :refer []]
            [conllu-rest.conllu-parser :as parser]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.xtdb.serialization :as cxs]
            [conllu-rest.xtdb.creation :as cxc]
            [conllu-rest.xtdb.queries :as cxq]))

(def ^:dynamic node nil)

(use-fixtures
  :each
  (fn [f]
    (binding [node (xt/start-node {})]
      (cxe/install-tx-fns! node)
      (f))))

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

# sent_id = AMALGUM_bio_cartagena-3
# s_type = decl
# text = Cartagena frequently argued with Magellan during the voyage and questioned his authority.
1	Cartagena	Cartagena	PROPN	NNP	Number=Sing	3	nsubj	3:nsubj|10:nsubj	Discourse=ROOT:6|Entity=(person-1)
2	frequently	frequently	ADV	RB	Degree=Pos	3	advmod	3:advmod	_
3	argued	argue	VERB	VBD	Mood=Ind|Number=Sing|Person=3|Tense=Past|VerbForm=Fin	0	root	0:root	_
4	with	with	ADP	IN	_	5	case	5:case	_
5	Magellan	Magellan	PROPN	NNP	Number=Sing	3	obl	3:obl:with	Entity=(person-6)
6	during	during	ADP	IN	_	8	case	8:case	_
7	the	the	DET	DT	Definite=Def|PronType=Art	8	det	8:det	Entity=(event-10
8	voyage	voyage	NOUN	NN	Number=Sing	3	obl	3:obl:during	Entity=event-10)
9	and	and	CCONJ	CC	_	10	cc	10:cc	Discourse=joint:7->6
10	questioned	question	VERB	VBD	Mood=Ind|Number=Sing|Person=3|Tense=Past|VerbForm=Fin	3	conj	3:conj:and	_
11	his	his	PRON	PRP$	Gender=Masc|Number=Sing|Person=3|Poss=Yes|PronType=Prs	12	nmod:poss	12:nmod:poss	Entity=(abstract-11(person-6)
12	authority	authority	NOUN	NN	Number=Sing	10	obj	10:obj	Entity=abstract-11)|SpaceAfter=No
13	.	.	PUNCT	.	_	3	punct	3:punct	_
")

(deftest read-write
  (let [parsed-data (parser/parse-conllu-string data)]
    (testing "CoNLL-U can be read"
      (cxc/create-document node parsed-data)
      (is (= 1 (count (cxe/find-entities node {:document/id '_}))))
      (is (= 19 (count (cxe/find-entities node {:token/id '_})))))

    (testing "Round-trip data looks the same in the trivial case (no changes)"
      (let [doc-id (:document/id (first (cxe/find-entities node {:document/id '_})))]
        (is (= (clojure.string/trim data)
               (clojure.string/trim (cxs/serialize-document node doc-id)))))
      )))