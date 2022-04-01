(ns conllu-rest.server.nlp.common
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
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
  (or (:sentences (cxe/entity node (job-id anno-type))) #{}))
(defn- valid-url? [s]
  (try
    (do (io/as-url s)
        true)
    (catch Exception _ false)))

(defprotocol SentenceLevelProbDistProvider
  "A SentenceLevelProbDistProvider is backed by an NLP model that can take an input sentence and
  provide a probability distribution for each instance of the kind of annotation that it handles
  in the sentence."
  ;; Must make a call to cxqd/calculate-stats in completion
  (predict-prob-dists [this node sentence]))

(s/def ::url valid-url?)
(s/def ::anno-type #{:sentence :xpos :upos :head-deprel})
(s/def ::type #{:http})
(s/def ::http-config (s/keys :req-un [::url ::type]))
;; Maybe extend with other methods in the future
(s/def ::config (s/and (s/keys :req-un [::type]) (s/or :http ::http-config)))
