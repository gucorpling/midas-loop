(ns conllu-rest.xtdb-diff-test
  "Integration tests covering "
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [xtdb.api :as xt]
            [conllu-rest.common-test :refer [sample-string token-by-form]]
            [conllu-rest.server.xtdb :refer []]
            [conllu-rest.conllu-parser :as parser]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.xtdb.serialization :as cxs]
            [conllu-rest.xtdb.creation :as cxc]
            [conllu-rest.xtdb.queries :as cxq]
            [conllu-rest.xtdb.queries.diff :as cxqd]))

(def ^:dynamic node nil)

(use-fixtures
  :each
  (fn [f]
    (binding [node (xt/start-node {})]
      (cxe/install-tx-fns! node)
      (f))))


(defn modify [cstr ops]
  (let [node (xt/start-node {})
        parsed (parser/parse-conllu-string cstr)
        new-parsed (reduce (fn [parsed [tok-num k v]]
                             (if (#{:feats :misc} k)
                               (if (nil? (second v))
                                 (update-in parsed [0 :tokens tok-num k] dissoc (first v))
                                 (update-in parsed [0 :tokens tok-num k] assoc (first v) (second v)))
                               (update-in parsed [0 :tokens tok-num] assoc k v)))
                           parsed
                           ops)
        _ (cxc/create-document node new-parsed)
        doc-id (:document/id (cxe/find-entity node [[:document/id '_]]))]
    (println doc-id)
    (cxs/serialize-document node doc-id)))

(defn setup [conllu-string]
  (cxc/create-document node (parser/parse-conllu-string conllu-string))
  (:document/id (cxe/find-entity node [[:document/id '_]])))

(def two-sentence-minimal
"
# newdoc id = AMALGUM_bio_cartagena
1	Juan	Juan	PROPN	NNP	Number=Sing	0	root	0:root	_

1	de	de	PROPN	NNP	Number=Sing	0	root	0:root	_
2	true	true	ADJ	JJ	Degree=Pos	1	amod	1:amod	_
")

(def two-sentence-minimal-head
"
# newdoc id = AMALGUM_bio_cartagena
1	Juan	Juan	PROPN	NNP	Number=Sing	0	root	0:root	_

1	de	de	PROPN	NNP	Number=Sing	2	amod	2:amod	_
2	true	true	ADJ	JJ	Degree=Pos	0	root	0:root	_
")

(def two-sentence-minimal-combined-sentences
"
# newdoc id = AMALGUM_bio_cartagena
1	Juan	Juan	PROPN	NNP	Number=Sing	0	root	0:root	_
2	de	de	PROPN	NNP	Number=Sing	0	root	0:root	_
3	true	true	ADJ	JJ	Degree=Pos	2	amod	2:amod	_
")

(def two-sentence-minimal-combined-tokens
"
# newdoc id = AMALGUM_bio_cartagena
1	Juan	Juan	PROPN	NNP	Number=Sing	0	root	0:root	_

1	detrue	detrue	PROPN	NNP	Number=Sing	0	root	0:root	_
")

(def minimal
"
# newdoc id = AMALGUM_bio_cartagena
1	Juan	Juan	PROPN	NNP	Number=Sing	0	root	0:root	_
")

(def minimal-lemma-change
"
# newdoc id = AMALGUM_bio_cartagena
1	Juan	juan	PROPN	NNP	Number=Sing	0	root	0:root	_
")

(def minimal-assoc-front
"
# newdoc id = AMALGUM_bio_cartagena
1	Juan	Juan	PROPN	NNP	Car=Far|Number=Sing	0	root	0:root	_
")

(def minimal-assoc-replace
"
# newdoc id = AMALGUM_bio_cartagena
1	Juan	Juan	PROPN	NNP	Frog=Bog	0	root	0:root	_
")

(def minimal-multi
"
# newdoc id = AMALGUM_bio_cartagena
1	juan	juan	NOUN	CD	Number=Sing|Foo=Bar	0	root	0:root	_
")

(def sentence
"
# newdoc id = GUM_bio_galois-sample
# sent_id = GUM_bio_galois-11
# s_type = decl
# text = The true motives behind the duel are obscure.
1	The	the	DET	DT	Definite=Def|PronType=Art	3	det	3:det	_
2	true	true	ADJ	JJ	Degree=Pos	3	amod	3:amod	_
3	motives	motive	NOUN	NNS	Number=Plur	8	nsubj	8:nsubj	_
4	behind	behind	ADP	IN	_	6	case	6:case	_
5	the	the	DET	DT	Definite=Def|PronType=Art	6	det	6:det	_
6	duel	duel	NOUN	NN	Number=Sing	3	nmod	3:nmod:behind	_
7	are	be	AUX	VBP	Mood=Ind|Number=Plur|Person=3|Tense=Pres|VerbForm=Fin	8	cop	8:cop	_
8	obscure	obscure	ADJ	JJ	Degree=Pos	0	root	0:root	_
9	.	.	PUNCT	.	_	8	punct	8:punct	_
")

(defn same? [expected-conllu doc-id]
  (= (str/trim expected-conllu) (str/trim (cxs/serialize-document node doc-id))))

(deftest simple-annotation-diff-rejections
  (let [doc-id (setup two-sentence-minimal)]
    (testing "Sentence break change results in rejection"
      (is (= false (cxqd/apply-annotation-diff node doc-id two-sentence-minimal
                                               two-sentence-minimal-combined-sentences))))
    (testing "Tokenization change results in rejection"
      (is (= false (cxqd/apply-annotation-diff node doc-id two-sentence-minimal
                                               two-sentence-minimal-combined-tokens))))))

(deftest simple-annotation-diff-lemma
  (let [doc-id (setup minimal)]
    (testing "Simple diff on lemma"
      (is (= true (cxqd/apply-annotation-diff node doc-id minimal minimal-lemma-change)))
      (is (same? minimal-lemma-change doc-id)))))

(deftest simple-annotation-diff-assoc-front
  (let [doc-id (setup minimal)]
    (testing "Simple diff on feats, assoc in front"
      (is (= true (cxqd/apply-annotation-diff node doc-id minimal minimal-assoc-front)))
      (is (same? minimal-assoc-front doc-id)))))

(deftest simple-annotation-diff-assoc-replace
  (let [doc-id (setup minimal)]
    (testing "Simple diff on feats, assoc in replace"
      (is (= true (cxqd/apply-annotation-diff node doc-id minimal minimal-assoc-replace)))
      (is (same? minimal-assoc-replace doc-id)))))

(deftest simple-annotation-diff-change-head
  (let [doc-id (setup two-sentence-minimal)]
    (testing "Simple diff on feats, assoc in replace"
      (is (= true (cxqd/apply-annotation-diff node doc-id two-sentence-minimal two-sentence-minimal-head)))
      (is (same? two-sentence-minimal-head doc-id)))))

(deftest simple-annotation-diff-multiple
  (let [doc-id (setup minimal)]
    (testing "Multiple changes on same tokens"
      (is (= true (cxqd/apply-annotation-diff node doc-id minimal minimal-multi)))
      (is (same? minimal-multi doc-id)))))
#_(modify minimal [[0 :lemma "JUAN"]
                 [0 :feats ["FOO" "BAR"]]
                 [0 :feats ["Number"]]])

