(ns conllu-rest.server.xtdb
  (:require [xtdb.api :as xt]
            [mount.core :refer [defstate]]
            [conllu-rest.xtdb.easy :refer [install-tx-fns!]]
            [conllu-rest.server.config :refer [env]]
            [conllu-rest.server.nlp :as nlp]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [conllu-rest.xtdb.queries :as queries])
  (:refer-clojure :exclude [ident?])
  (:import [xtdb.api IXtdb]))

;; NLP listening
(defn- ident? [[id-type id]]
  (and (= (name id-type) "id")
       (not= (namespace id-type) "xt")))

(defn- squeeze-ident [data]
  (when (coll? data)
    (first (filter ident? data))))

(defn- get-sentence-ids-general [node tx-ops]
  (->> tx-ops
       (map (fn [[op data]]
              (when (= op :xtdb.api/put)
                (let [[id-type id :as ident] (squeeze-ident data)]
                  (when (some? ident)
                    (when-let [sid (queries/get-sentence-id node id-type id)]
                      sid))))))
       (filter some?)
       set))

(defn- get-sentence-ids-creation [node tx-ops]
  (log/info "Assuming that the latest tx was for the creation of a new document.")
  (->> tx-ops
       (filter (fn [[op data]] (= :sentence/id (some-> data squeeze-ident first))))
       (map (fn [[op data]] (:sentence/id data)))
       set))

(defn- document-creation-transaction?
  "Assume that a transaction is putting a document if its first tx op is a put of a map with :document/id
  and it's over 100 tx-ops"
  [tx-ops]
  (and (some-> tx-ops
               first
               second
               :document/id)
       (> (count tx-ops) 100)))

(defn xtdb-listen
  "Called after a transaction is processed. Examines the transaction that was just committed, and
  infers affected sentence IDs from them. Writes the sentence IDs to a durable queue and notifies
  NLP agents that they need to be processed."
  [node {:xtdb.api/keys [tx-ops] :as event}]
  ;; Get affected sentence IDs by inspecting puts
  (let [sentence-ids (if (document-creation-transaction? tx-ops)
                       (get-sentence-ids-creation node tx-ops)
                       (get-sentence-ids-general node tx-ops))]
    (when (seq sentence-ids)
      (log/info "Processed transaction. Affected sentence ids: " sentence-ids))
    (doseq [[anno-type agent] nlp/agent-map]
      (doseq [sentence-id sentence-ids]
        (log/info "Notifying agent" (get-in agent [:config :anno-type]) "of change to" sentence-id)
        (nlp/submit-job node anno-type sentence-id)
        (send-off agent nlp/predict-prob-dists node sentence-id)))))

(defn assign-jobs [node agent-map]
  (doseq [[anno-type agent] agent-map]
    (let [sentence-ids (nlp/get-sentence-ids-to-process node anno-type)]
      (when sentence-ids
        (log/info "Found " (count sentence-ids) " incomplete jobs for " anno-type ".")
        (doseq [sentence-id sentence-ids]
          (send-off agent nlp/predict-prob-dists node sentence-id))))))

;; Proper XTDB stuff --------------------------------------------------------------------------------
(defn ^IXtdb start-standalone-xtdb-node [{:keys [db-dir http-server-port]}]
  (let [dirf #(str db-dir "/" %)]
    (xt/start-node
      (-> {:xtdb/tx-log         {:kv-store {:xtdb/module `xtdb.rocksdb/->kv-store, :db-dir (dirf "tx-log")}}
           :xtdb/document-store {:kv-store {:xtdb/module `xtdb.rocksdb/->kv-store, :db-dir (dirf "docs")}}
           :xtdb/index-store    {:kv-store {:xtdb/module `xtdb.rocksdb/->kv-store, :db-dir (dirf "indexes")}}}
          (cond-> http-server-port (assoc :xtdb.http-server/server {:port http-server-port}))))))

(defn start-main-node []
  (start-standalone-xtdb-node {:db-dir           (-> env ::config :main-db-dir)
                               :http-server-port (-> env ::config :http-server-port)}))


(defstate xtdb-node
  :start (let [node (start-main-node)
               f (partial xtdb-listen node)]
           (install-tx-fns! node)
           (xt/listen node {::xt/event-type ::xt/indexed-tx, :with-tx-ops? true} f)
           (assign-jobs node nlp/agent-map)
           node)
  :stop (.close xtdb-node))