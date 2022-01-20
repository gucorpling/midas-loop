(ns conllu-rest.server.nlp.http
  (:require [clj-http.client :as client]
            [clojure.tools.logging :as log]
            [conllu-rest.xtdb.serialization :as serialization]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.server.nlp.common :refer [SentenceLevelProbDistProvider complete-job get-sentence-ids-to-process]]))

(def ^:dynamic *retry-wait-period* 10000)
(defn get-probas
  "Attempt to contact an NLP service, defensively dealing with request failures and bad data"
  [node url sentence-id]
  (letfn [(retry [ex try-count http-context]
            (log/info "Attempt to contact" url "failed, retrying...")
            (Thread/sleep *retry-wait-period*)
            true)]
    (loop []
      (let [{:keys [status body]}
            (try
              (client/post url {:body          (.toString (serialization/serialize-sentence node sentence-id))
                                :content-type  "text/plain; charset=utf-8"
                                :retry-handler retry})
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
              (log/info body)

              )))))

(defrecord HttpProbDistProvider [config]
  SentenceLevelProbDistProvider
  (predict-prob-dists
    [this node sentence-id]
    (let [{:keys [url anno-type]} config]
      (if-let [sentence (cxe/entity node sentence-id)]
        (let [start (System/currentTimeMillis)
              resp (get-probas node url sentence-id)
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