(ns conllu-rest.xtdb.serialization
  (:require [xtdb.api :as xt]
            [conllu-rest.xtdb.easy :as cxe]))

(defn serialize-atomic
  ([node token name]
   (serialize-atomic node token name identity))
  ([node token name val-xform]
   (or (->> token ((keyword "token" name)) (cxe/entity node) ((keyword name "value")) val-xform)
       "_")))

(defn serialize-assoc
  ([node token name]
   (serialize-assoc node token name "=" identity))
  ([node token name kv-sep key-xform]
   (let [kv-records (map #(cxe/entity node %) ((keyword "token" name) token))
         k-key (keyword name "key")
         v-key (keyword name "value")
         kv-strings (map (fn [v]
                           (let [k (k-key v)
                                 v (v-key v)]
                             (if (or (nil? k)
                                     (nil? v)
                                     (and (string? k) (empty? k)))
                               nil
                               (str (key-xform k) kv-sep v))))
                         kv-records)
         kv-strings (remove nil? kv-strings)]
     (if (empty? kv-strings)
       "_"
       (clojure.string/join "|" kv-strings)))))

(defn serialize-token
  ([node id-map token-id] (serialize-token node id-map (StringBuilder.) token-id))
  ([node id-map sb token-id]
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
     (.append sb (serialize-atomic node token "upos"))
     (.append sb "\t")
     ;; UPOS
     (.append sb (serialize-atomic node token "xpos"))
     (.append sb "\t")
     ;; MORPH
     (.append sb (serialize-assoc node token "feats"))
     (.append sb "\t")
     ;; HEAD
     (.append sb (serialize-atomic node token "head" #(id-map %)))
     (.append sb "\t")
     ;; DEPREL
     (.append sb (serialize-atomic node token "deprel"))
     (.append sb "\t")
     ;; DEPS
     (.append sb (serialize-assoc node token "deps" ":" #(id-map %)))
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
    (loop [index 1
           id-map {:root "0"}
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
