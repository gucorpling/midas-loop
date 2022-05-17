(ns midas-loop.common
  (:import (java.util UUID)))

(defn parse-uuid [s]
  (try (UUID/fromString s)
       (catch IllegalArgumentException _
         nil)))

(defn error-response [error-details]
  {:status (:status error-details)
   :body   (:body error-details)})

(defn nyi-response [request]
  {:status 501
   :body   "Not implemented"})
