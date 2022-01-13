(ns conllu-rest.xtdb.queries
  "Reads and writes. Note that all of our writes, for now, use the (locking ...) macro from clojure.core
  as a cheap way to avoid concurrent write issues."
  (:require [clojure.tools.logging :as log]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.common :as common]
            [xtdb.api :as xt]
            [clojure.walk :refer [postwalk]])
  (:import (java.util UUID)))

;; pulls
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
    :lemma/id [:lemma/id :lemma/value]
    :upos/id [:upos/id :upos/value]
    :xpos/id [:xpos/id :xpos/value]
    :feats/id [:feats/id :feats/key :feats/value]
    :head/id [:head/id :head/value]
    :deprel/id [:deprel/id :deprel/value]
    :deps/id [:deps/id :deps/key :deps/value]
    :misc/id [:misc/id :misc/key :misc/value]))

(defn pull
  "Given a node and a map like {:token/id ::id}, return a complete map of everything on that
  entity as well as everything \"under\" it."
  [node m]
  (xt/pull (xt/db node) (get-pull-fragment (get-typed-id m)) (:xt/id m)))

;; helpers
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
(defn unlink-in-to-many
  "Given an entity ID and a parent attribute which contains the entity ID in a vector, return a tx that updates
  the parent to exclude the entity ID in the parent attribute."
  [node id pattr]
  (let [parent-entity (cxe/entity node (parent node pattr id))]
    (-> parent-entity
        (update pattr (fn [ids] (filterv #(not= % id) ids)))
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
