(ns midas-loop.xtdb.queries
  "Reads and writes. Note that all of our writes, for now, use the (locking ...) macro from clojure.core
  as a cheap way to avoid concurrent write issues."
  (:require [clojure.tools.logging :as log]
            [midas-loop.xtdb.easy :as cxe]
            [midas-loop.common :as common]
            [xtdb.api :as xt]
            [clojure.walk :refer [postwalk]])
  (:import (java.util UUID)
           (xtdb.api PXtdbDatasource)))

;; pulls --------------------------------------------------------------------------------
(defn- get-typed-id [m]
  (let [typed-id (ffirst (filter (fn [[k _]] (and (= (name k) "id")
                                                  (not= (namespace k) "xt")))
                                 m))]
    (when (nil? typed-id)
      (log/error "Unknown entity type from map:" m))
    typed-id))

(defn- get-pull-fragment [tid]
  (case tid
    :document/id [:document/id
                  :document/name
                  {:document/sentences (get-pull-fragment :sentence/id)}]
    :sentence/id [:sentence/id
                  {:sentence/conllu-metadata (get-pull-fragment :conllu-metadata/id)}
                  {:sentence/tokens (get-pull-fragment :token/id)}]
    :conllu-metadata/id [:conllu-metadata/id
                         :conllu-metadata/key
                         :conllu-metadata/value]
    ;; tokens
    :token/id [:token/id
               :token/token-type
               :token/subtokens
               :sentence/probas
               :sentence/quality
               {:token/form (get-pull-fragment :form/id)}
               {:token/lemma (get-pull-fragment :lemma/id)}
               {:token/upos (get-pull-fragment :upos/id)}
               {:token/xpos (get-pull-fragment :xpos/id)}
               {:token/feats (get-pull-fragment :feats/id)}
               {:token/head (get-pull-fragment :head/id)}
               {:token/deprel (get-pull-fragment :deprel/id)}
               {:token/deps (get-pull-fragment :deps/id)}
               {:token/misc (get-pull-fragment :misc/id)}]
    :form/id [:form/id :form/value]
    :lemma/id [:lemma/id :lemma/value :lemma/quality]
    :upos/id [:upos/id :upos/value :upos/probas :upos/quality]
    :xpos/id [:xpos/id :xpos/value :xpos/probas :xpos/quality]
    :feats/id [:feats/id :feats/key :feats/value :feats/quality]
    :head/id [:head/id :head/value :head/probas :head/quality]
    :deprel/id [:deprel/id :deprel/value :deprel/quality]
    :deps/id [:deps/id :deps/key :deps/value :deps/quality]
    :misc/id [:misc/id :misc/key :misc/value :misc/quality]))

(defn pull
  "Given a node and a map like {:token/id ::id}, return a complete map of everything on that
  entity as well as everything \"under\" it."
  [node-or-db m]
  (let [db (if (instance? PXtdbDatasource node-or-db) node-or-db (xt/db node-or-db))]
    (when (xt/entity db (:xt/id m))
      (xt/pull db (get-pull-fragment (get-typed-id m)) (:xt/id m)))))

(defn pull2 [node-or-db id-keyword id] (pull node-or-db {id-keyword id :xt/id id}))

(defn subtree->ident [tree-fragment]
  (first (filter #(= "id" (-> % first name)) tree-fragment)))

;; sentence id lookup --------------------------------------------------------------------------------
(defn- keys-in
  "Returns a sequence of all key paths in a given map using DFS walk.
  From: https://dnaeon.github.io/clojure-map-ks-paths/"
  [m]
  (letfn [(children [node]
            (let [v (get-in m node)]
              (if (map? v)
                (map (fn [x] (conj node x)) (keys v))
                [])))
          (branch? [node] (-> (children node) seq boolean))]
    (->> (keys m)
         (map vector)
         (mapcat #(tree-seq branch? children %)))))

(defn- get-map-pull-fragment [x]
  (->> x
       get-pull-fragment
       (postwalk (fn [x]
                   (if (vector? x)
                     (apply array-map (flatten (map (fn [v]
                                                      (if (keyword? v)
                                                        [v v]
                                                        [(first (keys v)) v]))
                                                    x)))
                     x)))))

;; Stores k-v pairs, where k is an ID type that is stored below or at the sentence level, and
;; v is a path of join attributes that take you from the sentence to the id type. Example:
;; {:conllu-metadata/id [:sentence/conllu-metadata :conllu-metadata/id]}
(def ^:private sentence-paths
  (->> (get-map-pull-fragment :sentence/id)
       keys-in
       (filter #(and (= "id" (-> % last name))
                     (let [[a b] (take-last 2 %)]
                       (not= a b))))
       (map (fn [v] [(last v) v]))
       (reduce conj {})))

(defn get-sentence-id
  "Find the sentence ID for a child, e.g. :form/id ::form"
  [node id-type id]
  (if (nil? (sentence-paths id-type))
    nil
    (let [attrs (sentence-paths id-type)
          query-symbols (concat ['?s] (map #(symbol (str "?" %)) (range (dec (count attrs)))) ['?input])
          symbol-pairs (partition 2 1 query-symbols)
          triples (mapv (fn [attr [v1 v2]] [v1 attr v2])
                        attrs
                        symbol-pairs)
          query {:find  ['?s]
                 :where triples
                 :in ['?input]}]
      (ffirst (xt/q (xt/db node) query id)))))

;; helpers --------------------------------------------------------------------------------
(defn write-error [msg]
  {:status :error :msg msg})

(defn write-ok
  ([]
   {:status :ok})
  ([msg]
   {:status :ok :msg msg}))

(defn parent
  "Reach a node's parent id via a specified attribute, where ?p -attr-> ?c"
  [node attr id]
  (ffirst (xt/q (xt/db node)
                {:find  '[?p]
                 :where [['?p attr '?c]]
                 :in    '[?c]}
                id)))

;; delete function --------------------------------------------------------------------------
(defn unlink-in-to-many**
  "Given an entity ID and a parent attribute which contains the entity ID in a vector, return a tx that updates
  the parent to exclude the entity ID in the parent attribute."
  [node id pattr]
  (let [parent-entity (cxe/entity node (parent node pattr id))]
    (-> parent-entity
        (update pattr (fn [ids] (filterv #(not= % id) ids)))
        cxe/put*
        vector)))

(defn link-in-to-many**
  "Given an entity ID and a parent attribute which contains the entity ID in a vector, return a tx that updates
  the parent to exclude the entity ID in the parent attribute."
  [node id parent-id pattr]
  (let [parent-entity (cxe/entity node parent-id)]
    (-> parent-entity
        (update pattr conj id)
        cxe/put*
        vector)))

(defn- get-delete-ids [node m]
  (let [data (pull node m)
        ids (atom [])]
    (postwalk (fn [x]
                (if-not (map? x)
                  x
                  (let [db-id (->> x
                                   (filter (fn [[k _]]
                                             (and (= (name k) "id")
                                                  (not= (namespace k) "xt"))))
                                   first
                                   last)]
                    (swap! ids conj db-id))))
              data)
    @ids))

(defn delete**
  "Given an entity document, delete its record and all of its child records.
  This will NOT remove its ID from any joins or enforce any other constraints--these should be
  handled in more specific delete functions."
  [node m]
  (let [tid (get-typed-id m)
        ids (get-delete-ids node m)
        tx (mapv #(cxe/delete* %) ids)]
    (when-not (#{:document/id :sentence/id :conllu-metadata/id :token/id :feats/id :misc/id} tid)
      (throw (ex-info "Do not call `delete` on atomic fields. Put a nil value instead." m)))

    tx))

;; utils --------------------------------------------------------------------------------
(defn remove-invalid-deps**
  "Checks head, deprel, and deps and removes any entries that point to tokens which are not in token-ids.
  Useful for post-processing a sentence split. Returns a transaction vector--no side effects.."
  [node token-ids]
  (let [tokens (map #(xt/pull (xt/db node)
                              [:token/id
                               {:token/head [:head/value :head/id]}
                               {:token/deprel [:deprel/value :deprel/id]}
                               {:token/deps [:deps/key :deps/value :deps/id]}]
                              %)
                    token-ids)
        txs (atom [])]
    (doseq [token tokens]
      (let [token-ids (conj (set token-ids) :root)
            {head-id :head/id head-value :head/value} (:token/head token)
            {deprel-id :deprel/id} (:token/deprel token)]
        (when-not (token-ids head-value)
          (swap! txs conj (cxe/put* (assoc (cxe/entity node head-id) :head/value nil)))
          (swap! txs conj (cxe/put* (assoc (cxe/entity node deprel-id) :deprel/value nil))))

        (let [orig-deps (:token/deps token)
              new-deps (atom orig-deps)]
          (doseq [{deps-id :deps/id head-id :deps/key :as dep} (:token/deps token)]
            (when-not (token-ids head-id)
              (swap! txs conj (cxe/delete* deps-id))
              (swap! new-deps #(filterv (fn [e] (not= (:deps/id e) deps-id)) %))))
          (when-not (= @new-deps orig-deps)
            (swap! txs conj (cxe/put* (assoc (cxe/entity node (:token/id token)) :token/deps (mapv :deps/id @new-deps))))))))
    @txs))
