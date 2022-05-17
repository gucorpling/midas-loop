(ns midas-loop.server.nlp.listen
  "For integration with XTDB's event listener"
  (:require [midas-loop.xtdb.queries :as queries]
            [midas-loop.xtdb.queries.document :as cxqd]
            [midas-loop.server.nlp.common :as nlpc]
            [clojure.tools.logging :as log]
            [xtdb.api :as xt])
  (:refer-clojure :exclude [ident?]))

(defn- ident? [[id-type id]]
  (and (= (name id-type) "id")
       (not= (namespace id-type) "xt")))

(defn- squeeze-ident [data]
  (when (coll? data)
    (first (filter ident? data))))

(defn- sentence-ids-from-tx-general [node tx-ops]
  (->> tx-ops
       (map (fn [[op data]]
              (when (= op :xtdb.api/put)
                (let [[id-type id :as ident] (squeeze-ident data)]
                  (when (some? ident)
                    (when-let [sid (queries/get-sentence-id node id-type id)]
                      sid))))))
       (filter some?)
       set))

(defn- sentence-ids-from-tx-creation [node tx-ops]
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

(defn notify-agents [node agent-map sentence-ids]
  (doseq [[anno-type agent] agent-map]
    (let [already-queued (nlpc/get-sentence-ids-to-process node anno-type)]
      (doseq [sentence-id sentence-ids]
        (when-not (already-queued sentence-id)
          (log/info "Notifying agent" (get-in @agent [:config :anno-type]) "of change to" sentence-id)
          (nlpc/submit-job node anno-type sentence-id)
          (send-off agent nlpc/predict-prob-dists node sentence-id))))))

(defn xtdb-listen
  "Called after a transaction is processed. Examines the transaction that was just committed, and
  infers affected sentence IDs from them. Writes the sentence IDs to a durable queue and notifies
  NLP agents that they need to be processed."
  [node agent-map {:xtdb.api/keys [tx-ops] :as event}]
  ;; Get affected sentence IDs by inspecting puts
  ;; TODO: this seems to work for now, but may be subtly wrong/not work in the future
  (let [sentence-ids (if (document-creation-transaction? tx-ops)
                       (sentence-ids-from-tx-creation node tx-ops)
                       (sentence-ids-from-tx-general node tx-ops))]
    (when (seq sentence-ids)
      (log/info "Processed transaction. Affected sentence ids: " sentence-ids))

    ;; Calculate stats if there are no agents, otherwise agents are responsible
    (if (empty? agent-map)
      (doseq [sentence-id sentence-ids]
        (let [document-id (ffirst (xt/q (xt/db node)
                                        {:find  ['?d]
                                         :where '[[?d :document/sentences ?s]]
                                         :in    ['?s]}
                                        sentence-id))]
          (cxqd/calculate-stats node document-id)))
      (notify-agents node agent-map sentence-ids))))

(defn assign-jobs [node agent-map]
  (doseq [[anno-type agent] agent-map]
    (let [sentence-ids (nlpc/get-sentence-ids-to-process node anno-type)]
      (when sentence-ids
        (log/info "Found " (count sentence-ids) " incomplete jobs for" anno-type ".")
        (Thread/sleep 2000)
        (doseq [sentence-id sentence-ids]
          (send-off agent nlpc/predict-prob-dists node sentence-id))))))
