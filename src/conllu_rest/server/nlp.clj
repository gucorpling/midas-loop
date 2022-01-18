(ns conllu-rest.server.nlp
  (:require [clj-http.client :as client]))

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
  (-> (->HttpProbDistProvider {:url "http://localhost"})
      (predict-prob-dists "qwe"))

  (->HttpProbDistProvider {:url "http://localhost"})

  (let [{:keys [status body] :as resp} (client/get "http://localhost:80")]
    (keys resp)
    )

  (spit "/tmp/tmp" (client/get "http://localhost:80"))

  )