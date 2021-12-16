(ns conllu-rest.routes.conllu.common
  (:require [clojure.walk :refer [postwalk]]
            [ring.util.http-response :refer :all]
            [conllu-rest.xtdb.queries :as cxr]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.common :as common])
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

(defn get-handler [{:keys [path-params node] :as request}]
  (let [id (:id path-params)]
    (if-let [uuid (common/parse-uuid id)]
      (let [result (cxe/entity node uuid)]
        (if (nil? result)
          (not-found)
          (ok* node result)))
      (bad-request "ID must be a valid java.util.UUID"))))
