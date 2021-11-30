(ns conllu-rest.xtdb
  (:require [xtdb.api :as xt]
            [mount.core :refer [defstate]]
            [conllu-rest.xtdb.easy :refer [install-tx-fns!]]
            [conllu-rest.config :refer [env]]
            [clojure.java.io :as io])
  (:import [xtdb.api IXtdb]))

(defn ^IXtdb start-lmdb-node [{:keys [db-dir http-server-port]}]
  (let [dirf #(str db-dir "/" %)]
    (xt/start-node
      (-> {:xtdb/tx-log         {:kv-store {:xtdb/module `xtdb.lmdb/->kv-store, :db-dir (dirf "tx-log")}}
           :xtdb/document-store {:kv-store {:xtdb/module `xtdb.lmdb/->kv-store, :db-dir (dirf "docs")}}
           :xtdb/index-store    {:kv-store {:xtdb/module `xtdb.lmdb/->kv-store, :db-dir (dirf "indexes")}}}
          (cond-> http-server-port (assoc :xtdb.http-server/server {:port http-server-port}))))))

(defn start-main-lmdb-node []
  (start-lmdb-node {:db-dir           (-> env ::config :main-db-dir)
                    :http-server-port (-> env ::config :http-server-port)}))

(defn start-session-lmdb-node []
  (start-lmdb-node {:db-dir (-> env ::config :session-db-dir)}))

(defstate xtdb-node
  :start (let [node (start-main-lmdb-node)]
           (install-tx-fns! node)
           ;; TODO: keep going with this for websocket sync
           ;; (setup-listener! node)
           node)
  :stop (.close xtdb-node))

(defstate xtdb-session-node
  :start (let [node (start-session-lmdb-node)]
           node)
  :stop (.close xtdb-session-node))