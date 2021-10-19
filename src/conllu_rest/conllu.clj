(ns conllu-rest.conllu
  "Wrapper for Python conllu library"
  (:require [libpython-clj.python :refer [py. py.. py.-] :as py]
            [libpython-clj.require :refer [require-python]])

  )

(def python-executable-path (or (System/getProperty "python-executable-path") "/usr/bin/python3"))
(def python-library-path (or (System/getProperty "python-library-path") "/usr/lib/x86_64-linux-gnu/libpython3.8.a"))
(py/initialize! :python-executable python-executable-path
                :library-path python-library-path)

(require-python '[conllu :as conllu])
(require-python '[builtins :as python])

(defn parse-conllu-string [conllu-string]
  (mapv (fn [token-list]
          {:tokens        (mapv (fn [token]
                                  (-> (into {} (into {} (map (fn [[k v]] [(keyword k) v]) (python/dict token))) )
                                      (update :feats #(try (into {} (python/dict %)) (catch Exception e {})))
                                      (update :deps #(try (into {} (python/dict %)) (catch Exception e {})))
                                      (update :misc #(try (into {} (python/dict %)) (catch Exception e {})))))
                                (vec (python/list token-list)))
           :metadata      (into {} (python/dict (py.- token-list "metadata")))
           :python-object token-list})
        (conllu/parse conllu-string)))

(comment
  (def data "
# text = The quick brown fox jumps over the lazy dog.
1   The     the    DET    DT   Definite=Def|PronType=Art   4   det     _   _
2   quick   quick  ADJ    JJ   Degree=Pos                  4   amod    _   _
3   brown   brown  ADJ    JJ   Degree=Pos                  4   amod    _   _
4   fox     fox    NOUN   NN   Number=Sing                 5   nsubj   _   _
5   jumps   jump   VERB   VBZ  Mood=Ind|Number=Sing|Person=3|Tense=Pres|VerbForm=Fin   0   root    _   _
6   over    over   ADP    IN   _                           9   case    _   _
7   the     the    DET    DT   Definite=Def|PronType=Art   9   det     _   _
8   lazy    lazy   ADJ    JJ   Degree=Pos                  9   amod    _   _
9   dog     dog    NOUN   NN   Number=Sing                 5   nmod    _   SpaceAfter=No
10  .       .      PUNCT  .    _                           5   punct   _   _

")

  (def xs (parse-conllu-string data))

  (first (:tokens (first xs)))

  (type (get (first (:tokens (first xs))) "feats"))

  xs

  )
