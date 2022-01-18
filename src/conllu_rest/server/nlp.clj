(ns conllu-rest.server.nlp
  (:require [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [conllu-rest.server.config :refer [env]]
            [conllu-rest.xtdb.queries :as queries]
            [clojure.tools.logging :as log]))

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


;; Listener --------------------------------------------------------------------------------
(defn- ident? [[id-type id]]
  (and (= (name id-type) "id")
       (not= (namespace id-type) "xt")))

(defn- squeeze-ident [data]
  (first (filter ident? data)))

(defn- get-sentence-ids-general [node tx-ops]
  (->> tx-ops
       (map (fn [[op data]]
              (when (= op :xtdb.api/put)
                (let [[id-type id :as ident] (squeeze-ident data)]
                  (when (some? ident)
                    (when-let [sid (queries/get-sentence-id node id-type id)]
                      sid))))))
       (filter some?)
       set))

(defn- get-sentence-ids-creation [node tx-ops]
  (log/info "Assuming that the latest tx was for the creation of a new document.")
  (->> tx-ops
       (filter (fn [[op data]] (= :sentence/id (some-> data squeeze-ident first))))
       (map (fn [[op data]] (:sentence/id data)))
       set))

(defn- document-creation-transaction?
  "Assume that a transaction is putting a document if its first tx op is a put of a map with :document/id"
  [tx-ops]
  (and (some-> tx-ops
               first
               second
               :document/id)
       (> (count tx-ops) 2)))

(defn xtdb-listen [node {:xtdb.api/keys [tx-ops] :as event}]
  ;; Get affected sentence IDs by inspecting puts
  (let [sentence-ids (if (document-creation-transaction? tx-ops)
                       (get-sentence-ids-creation node tx-ops)
                       (get-sentence-ids-general node tx-ops))]
    (log/info "Processed transaction. Affected sentence ids: " sentence-ids)
    sentence-ids))

;; Services --------------------------------------------------------------------------------
(defn- valid-url? [s]
  (try
    (do (io/as-url s)
        true)
    (catch Exception _ false)))

(s/def ::url valid-url?)
(s/def ::type #{:sentence :xpos :upos :head-deprel})
(s/def ::config (s/keys :req-un [::url ::type]))

(defn parse-configs [service-configs]
  (doall (reduce (fn [cmap {:keys [type url] :as config}]
                   (cond (not (s/valid? ::url url))
                         (do (log/error "Invalid URL in config: " url ". Config was not loaded and will be ignored.")
                             cmap)

                         (not (s/valid? ::type type))
                         (do (log/error (str "Invalid type in config: " type ". "
                                             "Type must be one of [:sentence, :xpos, :upos, :head-deprel]. "
                                             "Config was not loaded and will be ignored."))
                             cmap)

                         :else
                         (if (type cmap)
                           (do (log/error (str "There is already a config for type " type ", but found another: "
                                               config ". Keeping the existing config and ignoring this one."))
                               cmap)
                           (assoc cmap type config))))
                 {}
                 service-configs)))

(defprotocol SentenceLevelProbDistProvider
  "A SentenceLevelProbDistProvider is backed by an NLP model that can take an input sentence and
  provide a probability distribution for each instance of the kind of annotation that it handles
  in the sentence."
  (predict-prob-dists [this sentence]))

(defrecord HttpProbDistProvider [config]
  SentenceLevelProbDistProvider
  (predict-prob-dists [this sentence]
    (let [{:keys [url]} config]
      [sentence url])))


(comment
  (do
    (require '[conllu-rest.server.xtdb :refer [xtdb-node]])
    (require '[conllu-rest.xtdb.easy :as cxe]))

  (-> (->HttpProbDistProvider {:url "http://localhost:5555"})
      (predict-prob-dists "qwe"))

  (->HttpProbDistProvider {:url "http://localhost"})

  (s/conform ::config {:type :upos :url "http://localhost:80"})


  (def sent-id)

  (let [{:keys [status body] :as resp} (client/get "http://localhost:5555")]
    (keys resp)
    )

  (spit "/tmp/tmp" (client/get "http://localhost:80"))

  (:nlp-services env)

  (parse-configs [{:type :xpos :url "http://localhost:5959"}
                  {:type :xpos :url "http://localhost:6969"}])

  )