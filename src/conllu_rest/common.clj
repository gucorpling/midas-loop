(ns conllu-rest.common)

(defn error-response [error-details]
  {:status (:status error-details)
   :body   error-details})
