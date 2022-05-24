(ns midas-loop.server.nlp.setup
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [midas-loop.server.nlp.common :as nlpc]
            [midas-loop.server.nlp.http :refer [->HttpProbDistProvider]]))

;; Config parsing --------------------------------------------------------------------------------
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
