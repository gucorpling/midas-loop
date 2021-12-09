(ns conllu-rest.common
  (:import (java.util UUID)))

(defn uuid-string? [s]
  (try (do (UUID/fromString s) true)
       (catch IllegalArgumentException _
         false)))

(defn error-response [error-details]
  {:status (:status error-details)
   :body   error-details})

(defn nyi-response [request]
  {:status 501
   :body   "Not implemented"})
