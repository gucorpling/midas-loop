(ns user
  "Userspace functions you can run by default in your local REPL."
  (:require [clojure.tools.namespace.repl :as tools-ns]
            [conllu-rest.server.config :refer [env]]
            [clojure.pprint]
            [clojure.spec.alpha :as s]
            [expound.alpha :as expound]
            [mount.core :as mount]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.core :refer [start-app]]))

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(add-tap (bound-fn* clojure.pprint/pprint))

(defn start
  "Starts application.
  You'll usually want to run this on startup."
  []
  (def node conllu-rest.server.xtdb/xtdb-node)
  (mount/start-without #'conllu-rest.core/repl-server))

(defn stop
  "Stops application."
  []
  (mount/stop-except #'conllu-rest.core/repl-server))

(defn restart
  "Restarts application."
  []
  (stop)
  (tools-ns/refresh :after 'user/start))


