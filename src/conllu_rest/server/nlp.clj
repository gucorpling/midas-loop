(ns conllu-rest.server.nlp
  (:require [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [conllu-rest.server.config :refer [env]]
            [conllu-rest.xtdb.serialization :as serialization]
            [clojure.tools.logging :as log]
            [mount.core :as mount]
            [conllu-rest.xtdb.easy :as cxe]))

;; some inspo: https://stackoverflow.com/questions/14673108/asynchronous-job-queue-for-web-service-in-clojure
;; Gameplan here.
;;
;; - On startup, read configs, which declare services:
;;   {:url "http://localhost:8080", :type :head}
;; - Create an agent for each config that can ping the service for results and make all the appropriate
;;   changes in the DB
;; - Make a listener function for xtdb that will:
;;   1. Inspect all items in the transaction and determine the sentence ID that is related to it
;;   2. Pool those sentence IDs into a set
;;   3. Queue a re-process with the NLP agent
(defn job-id [anno-type] (keyword (name anno-type) "jobs"))
(cxe/deftx submit-job [node anno-type sentence-id]
  (let [{:keys [sentences] :as current} (or (cxe/entity node (job-id anno-type))
                                            {:xt/id (job-id anno-type) :sentences #{}})
        new (assoc current :sentences (conj sentences sentence-id))]
    [(cxe/put* new)]))

(cxe/deftx complete-job [node anno-type sentence-id]
  (let [{:keys [sentences] :as current} (cxe/entity node (job-id anno-type))
        new (assoc current :sentences (disj sentences sentence-id))]
    [(cxe/put* new)]))

(defn get-sentence-ids-to-process [node anno-type]
  (:sentences (cxe/entity node (job-id anno-type))))

;; Services --------------------------------------------------------------------------------
(defprotocol SentenceLevelProbDistProvider
  "A SentenceLevelProbDistProvider is backed by an NLP model that can take an input sentence and
  provide a probability distribution for each instance of the kind of annotation that it handles
  in the sentence."
  (predict-prob-dists [this node sentence]))

(defn- retry [url ex try-count http-context]
  (log/info "Attempt to contact" url "failed, retrying...")
  (Thread/sleep 10000)
  true)

(defrecord HttpProbDistProvider [config]
  SentenceLevelProbDistProvider
  (predict-prob-dists [this node sentence-id]
    (let [{:keys [url anno-type]} config]
      (if-let [sentence (cxe/entity node sentence-id)]
        ;; TODO: the work!
        (let [start (System/currentTimeMillis)
              resp (client/post url {:body          (.toString (serialization/serialize-sentence node sentence-id))
                                     :content-type  "text/plain; charset=utf-8"
                                     :retry-handler (partial retry url)})
              end (System/currentTimeMillis)
              _ (complete-job node anno-type sentence-id)
              remaining (count (get-sentence-ids-to-process node anno-type))]
          (println resp)
          (log/info (str "Completed " anno-type " job. " remaining " remaining."
                         " Est. time remaining: " (format "%.2f" (/ (* remaining (- end start)) 1000.)) " seconds."))
          this)
        (do
          (log/info (str "Sentence " sentence-id " appears to have been deleted before it was able to be processed."
                         " Marking as completed."))
          (complete-job node anno-type sentence-id)
          this)))))

;; Configs
(defn- valid-url? [s]
  (try
    (do (io/as-url s)
        true)
    (catch Exception _ false)))

(s/def ::url valid-url?)
(s/def ::anno-type #{:sentence :xpos :upos :head-deprel})
(s/def ::type #{:http})
(s/def ::http-config (s/keys :req-un [::url ::type]))
;; Maybe extend with other methods in the future
(s/def ::config (s/and (s/keys :req-un [::type]) (s/or :http ::http-config)))

(defn parse-configs [service-configs]
  (doall
    (reduce
      (fn [cmap {:keys [anno-type url] :as config}]
        (cond (not (s/valid? ::config config))
              (do (log/error (str "Invalid config: " config ". Ignoring and continuing on. Error message:\n"
                                  (s/explain-str ::config config)))
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