(ns midas-loop.server.nlp.http
  (:require [clj-http.client :as client]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [midas-loop.xtdb.serialization :as serialization]
            [midas-loop.xtdb.easy :as cxe]
            [midas-loop.server.config :refer [env]]
            [midas-loop.server.nlp.common :refer [SentenceLevelProbDistProvider complete-job get-sentence-ids-to-process]]
            [midas-loop.xtdb.queries :as cxq]
            [xtdb.api :as xt]
            [midas-loop.common :as common]
            [midas-loop.xtdb.queries.document :as cxqd]
            [midas-loop.server.nlp.common :as nlpc]))

(declare get-probas validate parse-response)


;; timing helpers
(def time-estimate-memory (or (System/getProperty "time-estimate-memory") 20))
(def timing-atoms (atom {}))
(defn record-time [anno-type ms]
  (swap! timing-atoms (fn [a]
                        (update a anno-type (fn [times]
                                              (take time-estimate-memory
                                                    (conj times ms)))))))
(defn time-estimate [anno-type]
  (let [times (vec (anno-type @timing-atoms))]
    (if (empty? times)
      nil
      (float (/ (apply + times)
                (count times)
                1000)))))

(defrecord HttpProbDistProvider [config]
  SentenceLevelProbDistProvider
  (predict-prob-dists
    [this node sentence-id]
    (let [start-time (System/currentTimeMillis)
          {:keys [url anno-type]} config]
      (if-let [sentence (cxe/entity node sentence-id)]
        (let [document-id (ffirst (xt/q (xt/db node)
                                        {:find  ['?d]
                                         :where '[[?d :document/sentences ?s]]
                                         :in    ['?s]}
                                        sentence-id))
              _ (get-probas node url anno-type sentence-id)
              _ (complete-job node anno-type sentence-id)
              remaining (count (get-sentence-ids-to-process node anno-type))]
          (cxqd/calculate-stats node document-id)
          (let [run-time (- (System/currentTimeMillis) start-time)]
            (record-time anno-type run-time)
            (log/info
              (str "Completed " anno-type " job in " (format "%.2f" (/ (float run-time) 1000)) "s. "
                   remaining " jobs remaining."
                   " Est. time remaining: " (format "%.2f" (* remaining (time-estimate anno-type))) " seconds.")))
          this)
        (do
          (log/info (str "Sentence " sentence-id " appears to have been deleted before it was able to be processed."
                         " Marking as completed."))
          (complete-job node anno-type sentence-id)
          this)))))

(defn parse-response
  "Process a response returned to midas loop from an NLP service."
  [data sentence-id token-count]
  (when-let [parsed (try (json/parse-string data)
                         (catch Exception e
                           (log/error "NLP service responded with malformed JSON:" e)))]
    (let [token-probas (parsed "probabilities")]
      (cond (nil? token-probas)
            (log/error "The JSON response must have a top-level \"probabilities\" key.")

            (not= token-count (count token-probas) token-count)
            (log/error "Expected" token-count "probas for" sentence-id "but found" (count token-probas)
                       ". Make sure that you are yielding probas for normal tokens and ellipsis tokens, but not supertokens.")

            (not (every? (fn [probas] (every? #(and (= (count %) 2) (string? (first %)) (number? (second %))) probas))
                         token-probas))
            (log/error "\"probabilities\" key must be a list of list of lists: for each token, there should be"
                       "a list of pairs where each pair has a label and its probability")

            :else
            ;; If the keys, i.e. the labels, are UUID-y, then parse them as UUIDs
            (update parsed "probabilities" update-vals
                    (fn [v]
                      (update-keys v
                                   (fn [v] (if-let [id (common/parse-uuid v)]
                                             id
                                             v)))))))))

(defn filter-relevant-tokens [anno-type tokens]
  (if (= :sentence anno-type)
    (filterv #(= :token (:token/token-type %)) tokens)
    (filterv #(not= :super (:token/token-type %)) tokens)))

(defn validate
  "Given an annotation type, a sentence, and the data we got back from a service, produce a :status where:
  - :dne means the sentence does not exist
  - :bad-data means something was wrong about the data we got back from the service
  - :ok means success"
  [anno-type {:sentence/keys [tokens id] :as sentence} data]
  (if (nil? sentence)
    {:status :dne}
    ;; Sentence split probas: only for plain tokens
    ;; Other probas: for all but supertokens
    (let [tokens (filter-relevant-tokens anno-type tokens)]
      (if-let [parsed-data (parse-response data id (count tokens))]
        {:status :ok :data parsed-data}
        {:status :bad-data}))))

(def ^:dynamic *retry-wait-period* (or (:nlp-retry-wait-period-ms env) 10000))
(defn get-probas
  "Attempt to contact an NLP service, defensively dealing with request failures and bad data"
  [node url anno-type sentence-id]
  (letfn [(retry [ex try-count http-context]
            (log/info "Attempt to contact" url "failed, retrying...")
            (Thread/sleep *retry-wait-period*)
            true)]
    (loop []
      (let [document-id (cxq/parent node :document/sentences sentence-id)
            {:document/keys [sentences]} (cxe/entity node document-id)
            sentence-index (loop [[head & rest] sentences
                                  i 0]
                             (cond
                               ;; don't need a base case since we know it's in here
                               ;; (nil? head) ...
                               (= sentence-id head) i
                               :else (recur rest (inc i))))
            {:keys [status body]}
            (try
              ;; Parse the body manually later
              (binding [client/*current-middleware* (filterv #(not= % client/wrap-output-coercion) client/default-middleware)]
                (client/post url {:form-params   {:conllu         (.toString (serialization/serialize-sentence node sentence-id))
                                                  :json           (cxq/pull2 node :sentence/id sentence-id)
                                                  :full_conllu    (.toString (serialization/serialize-document node document-id))
                                                  :sentence_index sentence-index}
                                  :content-type  :json
                                  :retry-handler retry}))
              (catch Exception e
                (do
                  (log/error "Exception thrown while attempting to contact NLP service:" e)
                  (log/info "Retrying...")
                  (Thread/sleep *retry-wait-period*)
                  nil)))]
        (cond (nil? status)
              (recur)

              (not= 200 status)
              (do
                (log/info "Service at" url "gave non-200 response code" status "-- retrying...")
                (Thread/sleep *retry-wait-period*)
                (recur))

              :else
              (let [{:sentence/keys [tokens] :as sentence} (cxq/pull2 node :sentence/id sentence-id)
                    {:keys [status data]} (validate anno-type sentence body)]
                (case status
                  :dne
                  (do (log/info "Sentence" sentence-id "doesn't exist! Considering job complete.")
                      nil)
                  :bad-data
                  (do
                    (Thread/sleep *retry-wait-period*)
                    (log/info "Retrying...")
                    (recur))
                  ;; success: write em!
                  (let [tokens (filter-relevant-tokens anno-type tokens)
                        pairs (partition 2 (interleave tokens (data "probabilities")))
                        probas-key (keyword (name anno-type) "probas")]
                    (nlpc/write-probas node probas-key pairs)))))))))
