(ns midas-loop.server.nlp
  (:require [clojure.tools.logging :as log]
            [mount.core :as mount]
            [midas-loop.server.config :refer [env]]
            [midas-loop.server.nlp.setup :as nlps]))

;; Services --------------------------------------------------------------------------------
(mount/defstate agent-map
  :start
  (->> (:nlp-services env)
       nlps/parse-configs
       nlps/create-agent-map))