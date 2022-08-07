(ns midas-loop.server.nlp.common
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [xtdb.api :as xt]
            [midas-loop.xtdb.easy :as cxe]))

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

;; TODO:
;; - Support document-level processing for faster processing on import by making a new protocol
;; - Make services also set the canonical annotation unless it is gold
;; - Allow disabling processing during import

;; Protocols --------------------------------------------------------------------------------
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
(s/def ::anno-type #{:sentence :xpos :upos :head-deprel :deprel})
(s/def ::type #{:http})
(s/def ::http-config (s/keys :req-un [::url ::type]))
;; Maybe extend with other methods in the future
(s/def ::config (s/and (s/keys :req-un [::type])
                       (s/or :http ::http-config)))

;; Coordination functions --------------------------------------------------------------------------------
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

;; Writing probas --------------------------------------------------------------------------------
;; Get the XTDB entity that will bear a particular annotation identified by `key`
(defmulti probas-entity (fn [node key id] key))
(defmethod probas-entity :default [node key id]
  (let [db (xt/db node)
        token (xt/entity db id)]
    (xt/entity db ((keyword "token" (namespace key)) token))))
(defmethod probas-entity :sentence/probas [node key id]
  (let [db (xt/db node)]
    (xt/entity db id)))

;; If we get
(defmulti maybe-replace-annotation (fn [anno key] key))
(defmethod maybe-replace-annotation :default [anno key]
  (let [value-key (keyword (namespace key) "value")
        probas (key anno)
        gold? (= ((keyword (namespace key) "quality") anno) "gold")
        [best-label _] (and probas (apply (partial max-key second) probas))
        existing-label (value-key anno)]
    (cond gold?
          (do
            ;; (log/info "Annotation has gold status, skipping.")
            anno)

          (or (nil? probas) (empty? probas))
          anno

          (= existing-label best-label)
          (do
            ;; (log/info "Existing label matches NLP service label.")
            anno)

          :else
          (do
            ;; (log/info (str "Replacing existing label " existing-label " with best one from NLP service: " best-label))
            (assoc anno value-key best-label)))))

(defmethod maybe-replace-annotation :sentence/probas [anno key]
  ;; TODO: should do sentence splitting, actually
  anno)

(cxe/deftx -write-probas [node key token-probas-pairs]
  (let [tx (mapv (fn [[{:token/keys [id]} probas]]
                   (let [anno (probas-entity node key id)]
                     (-> anno
                         (assoc key probas)
                         (maybe-replace-annotation key)
                         cxe/put*)))
                 token-probas-pairs)]
    tx))

(defn write-probas [node key token-probas-pairs]
  (when-not (#{:sentence/probas :xpos/probas :upos/probas :head/probas :deprel/probas} key)
    (throw (ex-info "Invalid probas key:" {:key key})))
  (-write-probas node key token-probas-pairs))
