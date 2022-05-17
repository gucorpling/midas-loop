(ns midas-loop.common-test
  (:require [clojure.test :refer :all]
            [xtdb.api :as xt]
            [midas-loop.server.middleware :refer [muuntaja-instance]]
            [muuntaja.core :as m]))

(defn token-by-form [node form]
  (ffirst (xt/q (xt/db node) {:find  '[?t]
                              :where ['[?t :token/form ?f]
                                      ['?f :form/value form]]})))

(defn parse-json [body]
  (m/decode muuntaja-instance "application/json" body))

(def sample-string "
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
1-2	Juande	Juande	_	_	_	_	_	_	_
1	Juan	Juan	PROPN	NNP	Number=Sing	0	root	_	Discourse=preparation:1->6|Entity=(person-1
2	de	de	PROPN	NNP	Number=Sing	1	flat	_	_
2.1	foo	foo	_	_	_	_	_	_	_
2.2	bar	bar	_	_	_	_	_	_	_
3	Cartagena	Cartagena	PROPN	NNP	Number=Sing	1	flat	_	Entity=person-1)

# sent_id = AMALGUM_bio_cartagena-3
# s_type = decl
# text = Cartagena frequently argued with Magellan during the voyage and questioned his authority.
1	Cartagena	Cartagena	PROPN	NNP	Number=Sing	3	nsubj	_	Discourse=ROOT:6|Entity=(person-1)
2	frequently	frequently	ADV	RB	Degree=Pos	3	advmod	_	_
3	argued	argue	VERB	VBD	Mood=Ind|Number=Sing|Person=3|Tense=Past|VerbForm=Fin	0	root	_	_
4	with	with	ADP	IN	_	5	case	_	_
5	Magellan	Magellan	PROPN	NNP	Number=Sing	3	obl	_	Entity=(person-6)
6	during	during	ADP	IN	_	8	case	_	_
7	the	the	DET	DT	Definite=Def|PronType=Art	8	det	_	Entity=(event-10
8	voyage	voyage	NOUN	NN	Number=Sing	3	obl	_	Entity=event-10)
9	and	and	CCONJ	CC	_	10	cc	_	Discourse=joint:7->6
10	questioned	question	VERB	VBD	Mood=Ind|Number=Sing|Person=3|Tense=Past|VerbForm=Fin	3	conj	_	_
11	his	his	PRON	PRP$	Gender=Masc|Number=Sing|Person=3|Poss=Yes|PronType=Prs	12	nmod:poss	_	Entity=(abstract-11(person-6)
12	authority	authority	NOUN	NN	Number=Sing	10	obj	_	Entity=abstract-11)|SpaceAfter=No
13	.	.	PUNCT	.	_	3	punct	_	_
")
