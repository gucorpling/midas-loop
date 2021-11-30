(ns conllu-rest.xtdb.serialization
  (:require [conllu-rest.xtdb :refer [xtdb-node]]
            [xtdb.api :as xt]
            [conllu-rest.xtdb.easy :as cxe]))

(defn serialize-atomic [node token name]
  (or (->> token ((keyword "token" name)) (cxe/entity node) ((keyword name "value")))
      "_"))

(defn serialize-assoc
  ([node token name]
   (serialize-assoc node token name "="))
  ([node token name kv-sep]
   (let [kv-records (map #(cxe/entity node %) ((keyword "token" name) token))
         k-key (keyword name "key")
         v-key (keyword name "value")
         kv-strings (map #(str (k-key %) kv-sep (v-key %)) kv-records)]
     (if (empty? kv-strings)
       "_"
       (clojure.string/join "|" kv-strings)))))

(defn serialize-token
  ([node id-map token-id] (serialize-token node id-map (StringBuilder.) token-id))
  ([node id-map sb token-id]
   (println id-map)
   (let [token (cxe/entity node token-id)]
     ;; ID
     (.append sb (id-map (:token/id token)))
     (.append sb "\t")
     ;; FORM
     (.append sb (serialize-atomic node token "form"))
     (.append sb "\t")
     ;; LEMMA
     (.append sb (serialize-atomic node token "lemma"))
     (.append sb "\t")
     ;; XPOS
     (.append sb (serialize-atomic node token "xpos"))
     (.append sb "\t")
     ;; UPOS
     (.append sb (serialize-atomic node token "upos"))
     (.append sb "\t")
     ;; MORPH
     (.append sb (serialize-assoc node token "morph"))
     (.append sb "\t")
     ;; HEAD
     (.append sb (serialize-atomic node token "head"))
     (.append sb "\t")
     ;; DEPREL
     (.append sb (serialize-atomic node token "deprel"))
     (.append sb "\t")
     ;; DEPS
     (.append sb (serialize-assoc node token "deps" ":"))
     (.append sb "\t")
     ;; DEPS
     (.append sb (serialize-assoc node token "misc"))
     (.append sb "\n"))
   ))

(defn serialize-conllu-metadata
  ([node conllu-metadata-id] (serialize-conllu-metadata node (StringBuilder.) conllu-metadata-id))
  ([node sb conllu-metadata-id]
   (let [{:conllu-metadata/keys [key value]} (xt/entity (xt/db node) conllu-metadata-id)]
     (.append sb "# ")
     (.append sb key)
     (.append sb " = ")
     (.append sb value)
     (.append sb "\n"))))

(defn resolve-ids [node token-ids]
  (let [tokens (map #(cxe/entity node %) token-ids)]
    (println tokens)
    (loop [index 1
           id-map {}
           {:token/keys [token-type id subtokens] :as token} (first tokens)
           tail (rest tokens)
           consec-empty 0]
      (if (nil? token)
        id-map
        (case token-type
          :super
          (recur index
                 (assoc id-map id (str index "-" (+ index (dec (count subtokens)))))
                 (first tail)
                 (rest tail)
                 0)
          :empty
          (recur
            index
            (assoc id-map id (str (dec index) "." (inc consec-empty)))
            (first tail)
            (rest tail)
            (inc consec-empty))
          ;; default
          (recur
            (inc index)
            (assoc id-map id index)
            (first tail)
            (rest tail)
            0))))))

(defn serialize-sentence
  ([node sentence-id] (serialize-sentence node (StringBuilder.) sentence-id))
  ([node sb sentence-id]
   (let [{:sentence/keys [conllu-metadata tokens]} (xt/entity (xt/db node) sentence-id)
         id-map (resolve-ids node tokens)]
     (doall (map #(serialize-conllu-metadata node sb %) conllu-metadata))
     (doall (map #(serialize-token node id-map sb %) tokens))
     (.append sb "\n"))))

(defn serialize-document
  "Starting from a document, write the serialized CoNLL-U to a StringBuilder and return it."
  ([node doc-id] (serialize-document node (StringBuilder.) doc-id))
  ([node sb doc-id]
   (let [{:document/keys [name sentences]} (xt/entity (xt/db node) doc-id)]

     (doall (map #(serialize-sentence node sb %) sentences))
     (str sb))))

(comment
  (def doc-id
    #uuid "db0d194f-c6a4-4f93-90c1-f8ca55932544"
    )

  (spit "/tmp/bar" (serialize-document xtdb-node doc-id))

  )