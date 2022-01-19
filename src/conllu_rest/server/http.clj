(ns conllu-rest.server.http
  (:require [mount.core :as mount]
            [luminus.http-server :as http]
            [conllu-rest.server.config :refer [env]]
            [conllu-rest.server.xtdb :refer [xtdb-node]]
            [conllu-rest.server.tokens :refer [xtdb-token-node]]
            [conllu-rest.server.handler :as handler]))

(mount/defstate ^{:on-reload :noop} http-server
                :start
                (http/start
                  (-> env
                      (update :io-threads #(or % (* 2 (.availableProcessors (Runtime/getRuntime)))))
                      (assoc :handler (handler/app xtdb-node xtdb-token-node))
                      (update :port #(or (-> env :options :port) %))
                      (select-keys [:handler :host :port])))
                :stop
                (http/stop http-server))
