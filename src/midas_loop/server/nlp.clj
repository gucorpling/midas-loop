(ns midas-loop.server.nlp
  (:require [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [mount.core :as mount]
            [midas-loop.xtdb.serialization :as serialization]
            [midas-loop.server.config :refer [env]]
            [midas-loop.server.nlp.http :refer [->HttpProbDistProvider]]
            [midas-loop.server.nlp.common :as nlpc]))

;; Services --------------------------------------------------------------------------------
(defn parse-configs [service-configs]
  (doall
    (reduce
      (fn [cmap {:keys [anno-type url] :as config}]
        (cond (not (s/valid? ::nlpc/config config))
              (do (log/error (str "Invalid config: " config ". Ignoring and continuing on. Error message:\n"
                                  (s/explain-str ::nlpc/config config)))
                  cmap)

              :else
              (if (anno-type cmap)
                (do (log/error (str "There is already a config for :anno-type " anno-type ", but found another: "
                                    config ". Keeping the existing config and ignoring this one."))
                    cmap)
                (assoc cmap anno-type config))))
      {}
      service-configs)))

(defn create-agent-map [service-configs]
  (into {} (mapv (fn [[_ {:keys [anno-type url] :as config}]]
                   (let [agent (agent (->HttpProbDistProvider config) :error-handler (fn [this ex] (log/error ex)))]
                     [anno-type agent]))
                 service-configs)))

(mount/defstate agent-map
  :start
  (->> (:nlp-services env)
       parse-configs
       create-agent-map))