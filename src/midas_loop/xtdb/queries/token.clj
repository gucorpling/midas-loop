(ns midas-loop.xtdb.queries.token
  (:require [midas-loop.xtdb.queries :as cxq :refer [write-error write-ok]]
            [xtdb.api :as xt]
            [midas-loop.xtdb.easy :as cxe]
            [midas-loop.common :as common])
  (:import (java.util UUID)))

(defn put
  "Given a map like :lemma/value and :lemma/id, write the new value"
  [node m id-keyword]
  (locking node
    (let [entity (cxe/entity node (id-keyword m))]
      (cond (nil? entity)
            (write-error (str "Entity does not exist: " m))

            (not (id-keyword entity))
            (write-error (str "Entity is not a " (namespace id-keyword) ": " m))

            :else
            (let [gold-key (keyword (namespace id-keyword) "quality")
                  tx [(cxe/put* (merge entity
                                       (select-keys m [id-keyword (keyword (namespace id-keyword) "value")])
                                       {gold-key "gold"}))]]
              (if (cxe/submit-tx-sync node tx)
                (write-ok)
                (write-error "Failed to put entity")))))))

(defn delete-assoc
  "Only for use with MISC and FEATS--other columns should use put with nil value"
  [node id id-keyword]
  (locking node
    (let [entity (cxe/entity node id)]
      (cond (nil? entity)
            (write-error (str "Annotation does not exist: " id))

            (not (id-keyword entity))
            (write-error (str "Entity is not a " (namespace id-keyword) ": " entity))

            :else
            (let [base-tx (cxq/delete** node {id-keyword id :xt/id id})
                  remove-join-tx (cxq/unlink-in-to-many** node id (keyword "token" (namespace id-keyword)))
                  final-tx (reduce into [base-tx remove-join-tx])]
              (if (cxe/submit-tx-sync node final-tx)
                (write-ok)
                (write-error "Deletion failed")))))))

(defn create-assoc
  "Only for use with MISC and FEATS--other columns should use put with nil value"
  [node token-id id-keyword key value]
  (locking node
    (let [colname (namespace id-keyword)
          token (cxq/pull node {:xt/id token-id :token/id token-id})
          join-keyword (keyword "token" colname)
          siblings (join-keyword token)
          key-keyword (keyword colname "key")]
      (cond (or (not (string? key)) (empty? key))
            (write-error (str "Key must be a non-empty string:" key))

            (not (string? value))
            (write-error (str "Value must be a string:" key))

            (nil? token)
            (write-error (str "Token doesn't exist: " token-id))

            (some #(= (key-keyword %) key) siblings)
            (write-error (str "Token already has a record in " colname " with key " key))

            :else
            (let [{:xt/keys [id] :as record} (cxe/create-record colname {(keyword colname "key")   key
                                                                         (keyword colname "value") value})
                  base-tx [(cxe/put* record)]
                  add-join-tx (cxq/link-in-to-many** node id token-id (keyword "token" colname))
                  final-tx (reduce into [base-tx add-join-tx])]
              (if (cxe/submit-tx-sync node final-tx)
                (write-ok {:id id})
                (write-error "Creation failed")))))))

(defn get-head-deps-tx [node head-id old-val new-val]
  (let [token-id (cxq/parent node :token/head head-id)
        {:token/keys [deps]} (cxq/pull node {:token/id token-id :xt/id token-id})
        matching-deps-record (some->> (first (filter #(= old-val (:deps/key %)) deps))
                                      :deps/id
                                      (cxe/entity node))]
    (if (nil? matching-deps-record)
      ;; Create a new one
      (let [{:deps/keys [id] :as record} (cxe/create-record "deps" {:deps/key   new-val
                                                                    :deps/value nil})]
        (into (cxq/link-in-to-many** node id token-id :token/deps) [(cxe/put* record)]))
      ;; Update the existing one
      [(cxe/put* (merge matching-deps-record {:deps/key new-val}))])))

(defn put-head
  "update HEAD and also deep DEPS in sync"
  [node {:head/keys [id value] :as m}]
  (locking node
    (let [entity (cxe/entity node id)
          parsed-value (cond (= value "root")
                             :root
                             (string? value)
                             (common/parse-uuid value)
                             :else
                             value)
          target-entity (cxe/entity node parsed-value)
          real-target? (uuid? parsed-value)]
      (cond (and (string? value) (nil? parsed-value))
            (write-error (str "Bad UUID: " value))

            (nil? entity)
            (write-error (str "Entity does not exist: " entity))

            (not (:head/id entity))
            (write-error (str "Entity is not a head: " entity))

            (and real-target? (nil? target-entity))
            (write-error (str "Token to serve as target of new head value does not exist: " target-entity))

            (and real-target? (not (:token/id target-entity)))
            (write-error (str "Target of new head value must be a valid token: " target-entity))

            (and real-target? (not (#{:token :empty} (:token/token-type target-entity))))
            (write-error (str "Head token must be a regular or empty token (not a supertoken): " target-entity))

            (and (not real-target?) (not (or (= parsed-value nil) (= parsed-value :root))))
            (write-error (str "New value for head annotation must be either a valid token, \"root\", or null; got: " parsed-value))

            :else
            (let [tx [(cxe/put* (merge entity {:head/id id :head/value parsed-value :head/quality "gold"}))]
                  deps-tx (get-head-deps-tx node id (:head/value entity) parsed-value)]
              (if (cxe/submit-tx-sync node (reduce into [tx deps-tx]))
                (write-ok)
                (write-error "Failed to put entity")))))))


(defn get-deprel-deps-tx [node token-id head-id old-val new-val]
  (let [{:token/keys [deps]} (cxq/pull node {:token/id token-id :xt/id token-id})
        matching-deps-record (some->> (first (filter #(and (= old-val (:deps/value %))
                                                           (= head-id (:deps/key %)))
                                                     deps))
                                      :deps/id
                                      (cxe/entity node))]
    (if (nil? matching-deps-record)
      ;; Create a new one
      (let [{:deps/keys [id] :as record} (cxe/create-record "deps" {:deps/key   nil
                                                                    :deps/value new-val})]
        (into (cxq/link-in-to-many** node id token-id :token/deps)
              [(cxe/put* record)]))
      ;; Update the existing one
      [(cxe/put* (merge matching-deps-record {:deps/value new-val}))])))

(defn put-deprel
  "update DEPREL and also deep DEPS in sync"
  [node {:deprel/keys [id value] :as m}]
  (locking node
    (let [deprel-record (cxe/entity node id)
          token-id (cxq/parent node :token/deprel id)
          head-id (:head/value (cxe/entity node (:token/head (cxe/entity node token-id))))]
      (cond (not (or (string? value) (nil? value)))
            (write-error (str "Value needs to be nil or a string: " value))

            (nil? deprel-record)
            (write-error (str "Deprel record does not exist: " deprel-record))

            :else
            (let [tx [(cxe/put* (merge deprel-record {:deprel/id id :deprel/value value :deprel/quality "gold"}))]
                  deps-tx (get-deprel-deps-tx node token-id head-id (:deprel/value deprel-record) value)]

              (if (cxe/submit-tx-sync node (reduce into [tx deps-tx]))
                (write-ok)
                (write-error "Failed to put entity")))))))

(comment
  (put-head midas-loop.server.xtdb/xtdb-node {:head/id #uuid"1bed472a-12fd-4570-973e-938981f80dda" :head/value :root})

  (put-deprel midas-loop.server.xtdb/xtdb-node {:deprel/id #uuid"9bfc4a78-afb1-4b25-a37c-8b8232d750f8" :deprel/value "orphan"})

  )