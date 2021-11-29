(ns conllu-rest.conllu
  "Wrapper for Python conllu library"
  (:require [libpython-clj.python :refer [py. py.. py.-] :as py]
            [libpython-clj.require :refer [require-python]]
            [clojure.string :as string])

  )

(def python-executable-path (or (System/getProperty "python-executable-path") "/usr/bin/python3"))
(def python-library-path (or (System/getProperty "python-library-path") "/usr/lib/x86_64-linux-gnu/libpython3.8.a"))
(py/initialize! :python-executable python-executable-path
                :library-path python-library-path)

(require-python '[conllu :as conllu])
(require-python '[builtins :as python])

(defn parse-conllu-string-with-python [conllu-string]
  (mapv (fn [token-list]
          {:tokens        (mapv (fn [token]
                                  (-> (into {} (into {} (map (fn [[k v]] [(keyword k) v]) (python/dict token))))
                                      (update :feats #(try (into {} (python/dict %)) (catch Exception e {})))
                                      (update :deps #(try (into {} (python/dict %)) (catch Exception e {})))
                                      (update :misc #(try (into {} (python/dict %)) (catch Exception e {})))
                                      (update :id #(if (= :pyobject (type %)) (into [] (python/list %)) %))))
                                (vec (python/list token-list)))
           :metadata      (into {} (python/dict (py.- token-list "metadata")))
           :python-object token-list})
        (conllu/parse conllu-string)))

;; Native implementation
(defn metadata-line? [line]
  (= \# (get (string/trim line) 0)))

(defn parse-metadata-line [line]
  (let [line (string/trim line)
        index (string/index-of line "=")]
    ;; Be forgiving and just fail silently if we can't find a "="
    (when (not (or (nil? index) (= (inc index) (count line))))
      (let [key (string/trim (subs line 1 index))
            value (string/trim (subs line (inc index)))]
        [key value]))))

(def super-token-re #"(\d+)(-)(\d+)")
(def empty-token-re #"(\d+)(\.)(\d+)")

(defn get-groups [re v]
  (let [matcher (re-matcher re v)]
    (when (re-find matcher)
      (let [[_ n1 sep n2] (re-groups matcher)]
        [(Long/parseLong n1) sep (Long/parseLong n2)]))))

(defn parse-id
  [v]
  (let [v (string/trim v)
        groups (or (get-groups super-token-re v)
                   (get-groups empty-token-re v))]
    (if groups
      groups
      (Long/parseLong v))))

(defn parse-atomic [v] (if (= v "_") nil v))

(defn parse-assoc
  ([v] (parse-assoc "=" v))
  ([kv-sep v]
   (if (= v "_")
     []
     (let [kv-pairs (string/split v #"\|")]
       (into {}
             (for [pair kv-pairs]
               (let [index (string/index-of pair kv-sep)
                     key (subs pair 0 index)
                     value (subs pair (inc index))]
                 [key value])))))))

(defn parse-token-line [line]
  (let [cols (string/split (string/trim line) #"\t")]
    (when-not (= (count cols) 10)
      (throw (ex-info (str "Expected 10 columns in token, found " (count cols)) {:actual (count cols)
                                                                                 :cols   cols})))
    {:id     (parse-id (get cols 0))
     :form   (get cols 1)
     :lemma  (get cols 2)
     :upos   (parse-atomic (get cols 3))
     :xpos   (parse-atomic (get cols 4))
     :feats  (parse-assoc (get cols 5))
     :head   (parse-atomic (get cols 6))
     :deprel (parse-atomic (get cols 7))
     :deps   (parse-assoc ":" (get cols 8))
     :misc   (parse-assoc (get cols 9))}))

(defn parse-sentence-lines [lines]
  (let [lines (string/split-lines lines)
        metadata-lines (filter metadata-line? lines)
        token-lines (filter #(not (metadata-line? %)) lines)]
    {:metadata (into {} (map parse-metadata-line metadata-lines))
     :tokens   (into [] (map parse-token-line token-lines))}))

(defn parse-conllu-string [conllu-string]
  (map parse-sentence-lines (string/split (string/trim conllu-string) #"\n\n")))

(comment

  (def data "# s_type = decl
# sent_id = AMALGUM_bio_bacon-4
# text = He and his band were relentlessly pursued thereafter .
1	He	he	PRON	PRP	Case=Nom|Gender=Masc|Number=Sing|Person=3|PronType=Prs	7	nsubj:pass	7:nsubj:pass	Discourse=joint:13->3|Entity=(person-1)
2	and	and	CCONJ	CC	_	4	cc	4:cc	_
3	his	his	PRON	PRP$	Gender=Masc|Number=Sing|Person=3|Poss=Yes|PronType=Prs	4	nmod:poss	4:nmod:poss	Entity=(organization-6(person-1)
4	band	band	NOUN	NN	Number=Sing	1	conj	1:conj:and|7:nsubj:pass	Entity=organization-6)
5	were	be	AUX	VBD	Mood=Ind|Number=Plur|Person=3|Tense=Past|VerbForm=Fin	7	aux:pass	7:aux:pass	_
6	relentlessly	relentlessly	ADV	RB	Degree=Pos	7	advmod	7:advmod	_
7	pursued	pursue	VERB	VBN	Tense=Past|VerbForm=Part|Voice=Pass	0	root	0:root	_
8	thereafter	thereafter	ADV	RB	Degree=Pos	7	advmod	7:advmod	_
9	.	.	PUNCT	.	_	7	punct	7:punct	_

")

  (def xs (parse-conllu-string-with-python data))


  (def xs (parse-conllu-string data))

  (first (:tokens (first xs)))

  (type (get (first (:tokens (first xs))) "feats"))

  xs

  )
