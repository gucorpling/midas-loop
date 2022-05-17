(ns midas-loop.routes.conllu.common
  (:require [clojure.walk :refer [postwalk]]
            [ring.util.http-response :refer :all]
            [midas-loop.xtdb.queries :as cxr]
            [midas-loop.xtdb.easy :as cxe]
            [midas-loop.common :as common])
  (:import (java.util UUID)))


(defn remove-namespaces [m]
  (postwalk
    (fn [v]
      (if (keyword? v)
        (keyword (name v))
        v))
    m))

(defn ok*
  "Like ring.util.http-response/ok, but applies some common postprocessing"
  [node m]
  (let [m (cxr/pull node m)
        m (remove-namespaces m)]
    (ok m)))

(defn get-handler [id-keyword]
  (fn [{:keys [path-params node] :as request}]
    (let [id (:id path-params)]
      (if-let [uuid (common/parse-uuid id)]
        (let [result (cxe/entity node uuid)]
          (cond (nil? result)
                (not-found)

                (not (id-keyword result))
                (bad-request (str "Entity with id " uuid " exists but is not a " (namespace id-keyword)))

                :else
                (ok* node result)))
        (bad-request "ID must be a valid java.util.UUID")))))
