(ns conllu-rest.routes.conllu.common
  (:require [clojure.walk :refer [postwalk]]
            [ring.util.http-response :refer :all]
            [conllu-rest.xtdb.reads :as cxr]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.common :as common])
  (:import (java.util UUID)))


(defn- remove-namespaces [m]
  (postwalk
    (fn [v]
      (if (keyword? v)
        (keyword (name v))
        v))
    m))

(comment
  (remove-namespaces {:a/b "foo" :b/qwe [1 2 {:aaa/bbbb (list 1 2 3)}]})
  )

(defn ok*
  "Like ring.util.http-response/ok, but applies some common postprocessing"
  [node m]
  (let [m (cxr/pull node m)
        m (remove-namespaces m)]
    (ok m)))

(defn get-handler [{:keys [path-params node] :as request}]
  (let [id (:id path-params)]
    (if-not (common/uuid-string? id)
      (bad-request "ID must be a valid java.util.UUID")
      (let [id (UUID/fromString id)
            result (cxe/entity node id)]
        (if (nil? result)
          (not-found)
          (ok* node result))))))
