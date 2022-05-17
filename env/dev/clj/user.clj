(ns user
  "Userspace functions you can run by default in your local REPL."
  (:require [clojure.tools.namespace.repl :as tools-ns]
            [midas-loop.server.config :as config]
            [midas-loop.server.xtdb :as xtdb]
            [midas-loop.server.repl]
            [clojure.pprint]
            [clojure.spec.alpha :as s]
            [expound.alpha :as expound]
            [mount.core :as mount]
            [midas-loop.xtdb.easy :as cxe]
            [xtdb.api :as xt]
            [midas-loop.core :refer [start-app]]))

(tools-ns/set-refresh-dirs "src")

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(add-tap (bound-fn* clojure.pprint/pprint))

(defn start
  "Starts application.
  You'll usually want to run this on startup."
  []
  (let [result (mount/start-without #'midas-loop.server.repl/repl-server)]
    (def node xtdb/xtdb-node)
    result))

(defn stop
  "Stops application."
  []
  (mount/stop-except #'midas-loop.server.repl/repl-server))

(defn restart
  "Restarts application."
  []
  (stop)
  (tools-ns/refresh :after 'user/start))


