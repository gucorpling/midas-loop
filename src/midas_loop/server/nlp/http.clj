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
            [midas-loop.xtdb.queries.document :as cxqd]))

(defn parse-response [data sentence-id token-count]
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

(defn validate [{:sentence/keys [tokens id] :as sentence} data]
  (if (nil? sentence)
    {:status :dne}
    (let [tokens (filterv #(not= :super (:token/token-type %)) tokens)]
      (if-let [parsed-data (parse-response data id (count tokens))]
        {:status :ok :data parsed-data}
        {:status :bad-data}))))

(cxe/deftx -write-probas [node key token-probas-pairs]
  (let [db (xt/db node)
        anno-type (namespace key)
        tx (mapv (fn [[{:token/keys [id]} probas]]
                   ;; todo: actually need to fetch the anno record
                   (let [token (xt/entity db id)
                         anno (xt/entity db ((keyword "token" anno-type) token))]
                     (-> anno
                         (assoc key probas)
                         cxe/put*)))
                 token-probas-pairs)]
    tx))

(defn write-probas [node key token-probas-pairs]
  (when-not (#{:xpos/probas :upos/probas :head/probas} key)
    (throw (ex-info "Invalid probas key:" {:key key})))
  (-write-probas node key token-probas-pairs))

(def ^:dynamic *retry-wait-period* (or (:nlp-retry-wait-period-ms env) 10000))
(defn get-probas
  "Attempt to contact an NLP service, defensively dealing with request failures and bad data"
  [node url anno-type sentence-id]
  (letfn [(retry [ex try-count http-context]
            (log/info "Attempt to contact" url "failed, retrying...")
            (Thread/sleep *retry-wait-period*)
            true)]
    (loop []
      (let [{:keys [status body]}
            (try
              ;; Parse the body manually later
              (binding [client/*current-middleware* (filterv #(not= % client/wrap-output-coercion) client/default-middleware)]
                (client/post url {:body          (.toString (serialization/serialize-sentence node sentence-id))
                                  :content-type  "text/plain; charset=utf-8"
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
                    {:keys [status data]} (validate sentence body)]
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
                  (let [pairs (partition 2 (interleave tokens (data "probabilities")))
                        probas-key (keyword (name anno-type) "probas")]
                    (write-probas node probas-key pairs)))))))))

(defrecord HttpProbDistProvider [config]
  SentenceLevelProbDistProvider
  (predict-prob-dists
    [this node sentence-id]
    (let [{:keys [url anno-type]} config]
      (if-let [sentence (cxe/entity node sentence-id)]
        (let [document-id (ffirst (xt/q (xt/db node)
                                        {:find  ['?d]
                                         :where '[[?d :document/sentences ?s]]
                                         :in    ['?s]}
                                        sentence-id))
              start (System/currentTimeMillis)
              _ (get-probas node url anno-type sentence-id)
              end (System/currentTimeMillis)
              _ (complete-job node anno-type sentence-id)
              remaining (count (get-sentence-ids-to-process node anno-type))]
          (log/info (str "Completed " anno-type " job. " remaining " remaining."
                         " Est. time remaining: " (format "%.2f" (/ (* remaining (- end start)) 1000.)) " seconds."))
          (cxqd/calculate-stats node document-id)
          this)
        (do
          (log/info (str "Sentence " sentence-id " appears to have been deleted before it was able to be processed."
                         " Marking as completed."))
          (complete-job node anno-type sentence-id)
          this)))))