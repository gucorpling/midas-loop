(ns conllu-rest.xtdb.reads
  (:require [clojure.tools.logging :as log]
            [conllu-rest.xtdb.easy :as cxe]
            [xtdb.api :as xt]))



(defn- get-typed-id [m]
  (let [typed-id (ffirst (filter (fn [[k _]] (and (= (name k) "id")
                                                  (not= (namespace k) "xt")))
                                 m))]
    (when (nil? typed-id)
      (log/error "Unknown entity type from map:" m))
    typed-id))

(defn- get-pull-fragment [tid]
  (case tid
    :document/id [:document/id
                  :document/name
                  {:document/sentences (get-pull-fragment :sentence/id)}]
    :sentence/id [:sentence/id
                  {:sentence/conllu-metadata (get-pull-fragment :conllu-metadata/id)}
                  {:sentence/tokens (get-pull-fragment :token/id)}]
    :conllu-metadata/id [:conllu-metadata/id
                         :conllu-metadata/key
                         :conllu-metadata/value]
    ;; tokens
    :token/id [:token/id
               :token/token-type
               :token/subtokens
               {:token/form (get-pull-fragment :form/id)}
               {:token/lemma (get-pull-fragment :lemma/id)}
               {:token/upos (get-pull-fragment :upos/id)}
               {:token/xpos (get-pull-fragment :xpos/id)}
               {:token/feats (get-pull-fragment :feats/id)}
               {:token/head (get-pull-fragment :head/id)}
               {:token/deprel (get-pull-fragment :deprel/id)}
               {:token/deps (get-pull-fragment :deps/id)}
               {:token/misc (get-pull-fragment :misc/id)}]
    :form/id [:form/id :form/value]
    :lemma/id [:lemma/id :lemma/value]
    :upos/id [:upos/id :upos/value]
    :xpos/id [:xpos/id :xpos/value]
    :feats/id [:feats/id :feats/key :feats/value]
    :head/id [:head/id :head/value]
    :deprel/id [:deprel/id :deprel/value]
    :deps/id [:deps/id :deps/key :deps/value]
    :misc/id [:misc/id :misc/key :misc/value]))


(defn pull
  [node m]
  (xt/pull (xt/db node) (get-pull-fragment (get-typed-id m)) (:xt/id m)))


(comment
  (pull
    conllu-rest.server.xtdb/xtdb-node
    {:document/id #uuid"fe704604-90f9-4d42-bc68-9b8832c0ebf0" #_#_:token/id "62f9fe8e-7e25-4e94-bee8-b2c0eeea83f2"
     :xt/id       #uuid"fe704604-90f9-4d42-bc68-9b8832c0ebf0"}
    )


  (cxe/entity
    conllu-rest.server.xtdb/xtdb-node
    #uuid"fe704604-90f9-4d42-bc68-9b8832c0ebf0")

  (get-pull-fragment :token/id)

  )
