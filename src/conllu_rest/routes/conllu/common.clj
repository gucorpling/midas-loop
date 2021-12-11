(ns conllu-rest.routes.conllu.common
  (:require [clojure.walk :refer [postwalk]]
            [conllu-rest.xtdb.reads :as cxr]
            [ring.util.http-response :refer :all]))


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

