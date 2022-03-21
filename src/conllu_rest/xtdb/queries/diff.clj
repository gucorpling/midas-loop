(ns conllu-rest.xtdb.queries.diff
  (:require [xtdb.api :as xt]
            [clojure.string :as clj-str]
            [editscript.core :as e]
            [editscript.edit :refer [get-edits]]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.xtdb.serialization :as cxs]
            [conllu-rest.xtdb.creation :as cxc]
            [conllu-rest.xtdb.queries :as cxq]
            [conllu-rest.server.tokens :as tok]
            [conllu-rest.conllu-parser :as cp]
            [clojure.walk :as walk])
  (:import (java.text Normalizer$Form Normalizer)))

(defn- filter-diff
  "Filter out all diff bits with a UUID as the value, except when it's a :head/value.
  This will include spurious ops for heads that in fact have not changed. We will deal with
  them later."
  [diff]
  (vec (remove (fn [[path op v]]
                 (or (and (uuid? v)
                          (not= (last path) :head/value)))
                 #_(and (keyword? (-> path last))
                        (= (-> path last name) "id")
                        (= op :r)))
               diff)))

(defn get-id-map [doc-old doc-new]
  (letfn [(doc->ids [doc]
            (mapcat (fn [sentence]
                      (for [token (:sentence/tokens sentence)]
                        (:token/id token)))
                    (:document/sentences doc)))]
    (let [old-ids (doc->ids doc-old)
          new-ids (doc->ids doc-new)]
      (when (= (count new-ids) (count old-ids))
        (into {:root :root} (map vector new-ids old-ids))))))

(defn fix-head-values [diff doc-old doc-new]
  (let [id-map (get-id-map doc-old doc-new)]
    (mapv (fn [[path op v :as diff-item]]
            (if (and (= (last path) :head/value)
                     (= op :r))
              [path op (id-map v)]
              diff-item))
          diff)))

(defn get-diff
  "Produce an editscript diff for a document given an old and new conllu string representing it."
  [node document-id new-parsed]
  (let [tmp-node (xt/start-node {})
        new-tx (cxc/build-document new-parsed)
        new-id (-> new-tx first second :document/id)
        spec-db (xt/with-tx (xt/db tmp-node) new-tx)
        doc-old (cxq/pull2 (xt/db node) :document/id document-id)
        doc-new (cxq/pull2 spec-db :document/id new-id)
        raw-diff (e/diff doc-old doc-new)
        filtered-diff (filter-diff (get-edits raw-diff))
        corrected-diff (fix-head-values filtered-diff doc-old doc-new)]
    corrected-diff))

(defn insert-into-vector
  "Insert an item `n` into a position `i` in a vector `v`"
  [v i n]
  (into (conj (subvec v 0 i) n)
        (subvec v i)))

(defn drop-from-vector [v i]
  (into (subvec v 0 i)
        (subvec v (inc i))))

(defn follow-path [doc-tree path]
  (loop [tree doc-tree
         [step & tail] path]
    (cond (nil? step)
          tree

          (keyword? step)
          (recur (step tree) tail)

          :else
          (recur (nth tree step) tail))))

(defn txs->entity [txs id]
  (some-> (filter (fn [[op data]]
                    (and (= op :xtdb.api/put) (= (:xt/id data) id)))
                  txs)
          first
          second))

;; Sentence break diffing ----------------------------------------------------------------------------------------------
;; NYI

;; Annotation diffing --------------------------------------------------------------------------------------------------
(defmulti transduce-diff-item (fn [node diff txs doc-tree [path op val]] op))

(defmethod transduce-diff-item :default [node diff txs doc-tree [path op val]]
  (throw (ex-info "Unknown op type!" {:op op})))

(defmethod transduce-diff-item :+ [node diff txs doc-tree [path _ val]]
  ;; For something like:
  ;;    [[:document/sentences 0 :sentence/tokens 1 :token/feats 1]
  ;;     :+
  ;;     #:feats{:id #uuid"bbc976df-8bc3-402a-b6a7-11daaf25739b", :key "Number", :value "Sing"}]
  ;; We need to create the new record and join it to its parent
  (let [token-path (vec (drop-last 2 path))
        token (follow-path doc-tree token-path)
        token-id (:token/id token)
        ;; set up tx
        join-key (last (butlast path))
        ;; try to read from latest tx first
        parent-entity (or (txs->entity txs token-id) (cxe/entity node token-id))
        ;; get the id kwd from the fragment but
        [child-id-keyword _] (cxq/subtree->ident val)
        child-entity (cxe/create-record (namespace child-id-keyword) (dissoc val child-id-keyword))
        new-parent-entity (update parent-entity join-key insert-into-vector (last path) (child-id-keyword child-entity))]
    [(cxe/put* child-entity)
     (cxe/put* new-parent-entity)]))

(defmethod transduce-diff-item :- [node diff txs doc-tree [path _]]
  ;; For something like: [[:document/sentences 0 :sentence/tokens 1 :token/feats 0] :-]
  (let [token-path (vec (drop-last 2 path))
        token (follow-path doc-tree token-path)
        token-id (:token/id token)
        child (follow-path doc-tree path)
        [_ child-id] (cxq/subtree->ident child)
        ;; set up tx
        join-key (last (butlast path))
        ;; try to read from latest tx first
        parent-entity (or (txs->entity txs token-id) (cxe/entity node token-id))
        new-parent-entity (update parent-entity join-key drop-from-vector (last path))]
    [(cxe/delete* child-id)
     (cxe/put* new-parent-entity)]))

(defmethod transduce-diff-item :r [node diff txs doc-tree [path _ val]]
  ;; For something like:
  ;; [[:document/sentences 0 :sentence/tokens 3 :token/lemma :lemma/value] :r "foo"]
  ;; [[:document/sentences 0 :sentence/tokens 3 :token/feats 0 :feats/key] :r "foo"]
  ;; [[:document/sentences 0 :sentence/tokens 3 :token/feats 0] :r {:feats/id ... :feats/key ... :feats/value ...}]
  (if (keyword? (last path))
    (let [child (follow-path doc-tree (butlast path))
          [_ child-id] (cxq/subtree->ident child)
          child-entity (cxe/entity node child-id)
          new-child-entity (assoc child-entity (last path) val)]
      [(cxe/put* new-child-entity)])
    (let [child (follow-path doc-tree path)
          [_ child-id] (cxq/subtree->ident child)
          child-entity (cxe/entity node child-id)
          new-child-entity (merge child-entity (into {} (remove #(= "id" (some-> % first name)) val)))]
      [(cxe/put* new-child-entity)])))

(defn diff->tx [node doc-tree diff]
  "Given a diff produced by get-diff against a document, transduce the sequence into
  an XTDB tx (= a sequence of tx ops) that will enact the diff on the document."
  (loop [txs []
         tree doc-tree
         [head-op & tail] diff]
    (if (nil? head-op)
      txs
      ;; We need to update the tree as we go, since editscript diffs are defined relative to the
      ;; state after each previous operation has been applied.
      (recur
        (into txs (transduce-diff-item node diff txs doc-tree head-op))
        (e/patch tree (e/edits->script [head-op]))
        tail))))

(defn try-parse [conllu]
  (try
    (cp/parse-conllu-string conllu)
    (catch Exception e
      e)))

(defn valid-annotation-diff? [diff]
  (every? (fn [[path & _ :as delt]]
            (let [[elt-3rd-last elt-2nd-last elt-last] (take-last 3 path)]
              (and
                (not (and (keyword? (last path))
                          (or (= (namespace (last path)) "deps")
                              (= (last path) :token/deps))))
                (or
                  ;; Change to atomic col -- an :r op
                  (and (keyword? elt-2nd-last)
                       (= "token" (namespace elt-2nd-last))
                       (keyword? elt-last))

                  ;; Deletion or addition or replacement of an associative col -- a :+ or :- or :r op
                  (and (keyword? elt-2nd-last)
                       (= "token" (namespace elt-2nd-last))
                       (integer? elt-last))

                  ;; Value change inside associative col -- an :r op
                  (and (keyword? elt-3rd-last)
                       (= "token" (namespace elt-3rd-last))
                       (integer? elt-2nd-last)
                       (keyword? elt-last))))))
          diff))

(defn same-sentence-lengths? [parsed1 parsed2]
  (let [sentences-1 (map :tokens parsed1)
        sentences-2 (map :tokens parsed2)
        sentence-pairs (map (fn [s1 s2] [s1 s2])
                            sentences-1
                            sentences-2)]
    (and (= (count sentences-1) (count sentences-2))
         (every? (fn [[s1 s2]] (= (count s1) (count s2))) sentence-pairs))))

(defn same-token-forms? [parsed1 parsed2]
  (let [sentences-1 (map :tokens parsed1)
        sentences-2 (map :tokens parsed2)
        sentence-pairs (map (fn [s1 s2] [s1 s2])
                            sentences-1
                            sentences-2)]
    (and (same-sentence-lengths? parsed1 parsed2)
         (every? (fn [[s1 s2]]
                   (let [form-pairs (map (fn [t1 t2] [(:form t1) (:form t2)])
                                         s1 s2)]
                     (every? (fn [[t1 t2]]
                               (= t1 t2))
                             form-pairs)))
                 sentence-pairs))))

(defn diff-lines [a b]
  (let [expected-lines (map seq (clj-str/split-lines a))
        actual-lines (map seq (clj-str/split-lines b))]
    (remove nil?
            (for [[a b] (map vector expected-lines actual-lines)]
              (when-not (= a b)
                [a b])))))

(defn normalize-conllu-str [s]
  (Normalizer/normalize (clj-str/trim (.replace s "\r\n", "\n")) Normalizer$Form/NFD))

(cxe/deftx apply-annotation-diff [node document-id old-conllu new-conllu]
  (let [doc-tree (cxq/pull2 node :document/id document-id)
        current-conllu (normalize-conllu-str (cxs/serialize-document node document-id))
        old-conllu (normalize-conllu-str old-conllu)
        new-conllu (normalize-conllu-str new-conllu)
        old-parsed (try-parse old-conllu)
        new-parsed (try-parse new-conllu)]
    (cond (not= current-conllu old-conllu)
          (throw (ex-info (str "Old CoNLL-U string does not match current. Current:\n"
                               current-conllu
                               "\n\nSubmitted:\n"
                               old-conllu
                               "\n\nDiff:\n"
                               (clj-str/join "\n" (map str (diff-lines current-conllu old-conllu))))
                          {:submitted old-conllu :actual current-conllu}))

          (instance? Exception old-parsed)
          (throw old-parsed)

          (instance? Exception new-parsed)
          (throw new-parsed)

          (not (same-sentence-lengths? old-parsed new-parsed))
          (throw (ex-info "Sentence and/or token counts do not match." {:old old-conllu :new new-conllu}))

          ;; (not (same-token-forms? old-parsed new-parsed))
          ;; (throw (ex-info "Token forms do not match." {:old old-conllu :new new-conllu}))

          ;; TODO: would be nice to abort if we detect the new-conllu doesn't match the actual
          ;; (this would indicate a bug in our code)
          :else
          (let [diff (get-diff node document-id new-parsed)]
            (if (valid-annotation-diff? diff)
              (diff->tx node doc-tree diff)
              (throw (ex-info "Invalid annotation diff" {:diff diff})))))))

(comment
  ;; example diff:
  [[[:document/sentences 0 :sentence/tokens 1 :token/feats 0] :-]
   [[:document/sentences 0 :sentence/tokens 1 :token/feats 1]
    :+
    #:feats{:id #uuid"bbc976df-8bc3-402a-b6a7-11daaf25739b", :key "Number", :value "Sing"}]
   [[:document/sentences 0 :sentence/tokens 3 :token/lemma :lemma/value] :r "FOo"]]

  )