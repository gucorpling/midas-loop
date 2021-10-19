(ns conllu-rest.xtdb.easy
  "A set of convenience functions for transactions with only one part and other common xtdb operations.
  Functions that end in * return a vector that can be used to build transactions incrementally."
  (:require [xtdb.api :as xt]
            [clojure.pprint :as pprint]
            [clojure.walk :as walk]
            [clojure.tools.reader :as reader])
  (:refer-clojure :exclude [set update merge])
  (:import (java.util UUID)))

;; reads ---------------------------------------------------------------------------------
(defn entity [node id]
  "Get an entire entity by :xt/id"
  (xt/entity (xt/db node) id))

(defn entities [node id-vecs]
  "get entities given a seq of 1-tuples of :xt/id"
  (map #(entity node (first %)) id-vecs))

(defn q [node query]
  (xt/q (xt/db node) query))

(defn- find-entity-by-attrs
  "Find an entity by attributes. Options:
    - :id-only? [false] - set to true to return only :xt/id instead of the entire entity
    - :all-results [false] - set to true to return more than just the first entity"
  ([node attrs] (find-entity-by-attrs node attrs {:id-only? false :all-results false}))
  ([node attrs {:keys [id-only? all-results] :as opts}]
   (let [result (xt/q (xt/db node)
                      {:find  ['e]
                       :where (vec (for [[k v] attrs]
                                     ['e k v]))})
         result (if all-results result (ffirst result))
         result (if id-only?
                  result
                  (if (set? result)
                    (entities node result)
                    (entity node result)))]
     result)))

(defn find-entity-id [node attrs] (find-entity-by-attrs node attrs {:id-only? true :all-results false}))
(defn find-entity-ids [node attrs] (find-entity-by-attrs node attrs {:id-only? true :all-results true}))
(defn find-entity [node attrs] (find-entity-by-attrs node attrs {:id-only? false :all-results false}))
(defn find-entities [node attrs] (find-entity-by-attrs node attrs {:id-only? false :all-results true}))

;; match --------------------------------------------------------------------------------
(defn match*
  ([eid doc]
   [:xtdb.api/match eid doc])
  ([eid doc valid-time]
   [:xtdb.api/match eid doc valid-time]))

;; deftx --------------------------------------------------------------------------------
(defn- filtered-refers
  "ns-refers, but without clojure.core vars"
  [ns]
  (into {} (filter (fn [[_ v]] (not= "clojure.core" (namespace (symbol v))))
                   (ns-refers ns))))

(defn- fully-qualify-symbols
  "Add fully qualified symbols where needed so that we can store the body in an XTDB transaction function. "
  [body]
  (let [ns-vars (clojure.core/merge (ns-interns *ns*) (filtered-refers *ns*))]
    (walk/postwalk
      (fn [x]
        (cond
          (and (symbol? x) (some? (namespace x)))
          (symbol (ns-resolve *ns* x))

          (and (symbol? x) (contains? ns-vars x))
          (symbol (get ns-vars x))

          :else
          x))
      body)))

;; macro for transactions to avoid race conditions: https://clojurians-log.clojureverse.org/crux/2020-03-24
(defmacro deftx
  "Defines a function used for mutations that uses a Crux transaction function under the hood.
  Body must return a valid Crux transaction vector (or return false, error, etc.)
  `install-tx-fns` must be called on the node before the deftx function can work.

  NOTE: XTDB tx fns require all symbols to be fully qualified. This macro will attempt to resolve
  them for you, with the following restriction: none of your symbols should shadow either symbols
  that are interned in your current namespace, or ones that are :refer'd into your current namespace.

  (If you do shadow them, e.g. in a `let` expression, this macro will fully qualify them and likely
  produce an invalid expression which the compiler will complain about.)"
  [name bindings & body]
  (let [kwd-name (keyword (str *ns*) (str name))
        symbol-name (symbol (str name))
        fq-bindings (fully-qualify-symbols bindings)
        fq-body (fully-qualify-symbols body)]
    `(do
       (def
         ~(vary-meta
            symbol-name
            assoc
            :xtdb-tx-fn
            `(fn [node#]
               (xt/submit-tx node# [[:xtdb.api/put {:xt/id ~kwd-name
                                                    :xt/fn (quote (fn ~fq-bindings
                                                                    ~@fq-body))}]])))
         (fn ~symbol-name [node# & ~'args]
           (let [tx-map# (xt/submit-tx node# [(into [:xtdb.api/fn ~kwd-name] ~'args)])]
             (xt/await-tx node# tx-map#)
             (xt/tx-committed? node# tx-map#))))
       ;; Also define a version that just returns the transaction
       (def
         ~(symbol (str symbol-name "**"))
         (fn ~symbol-name [node# & ~'args]
           [(into [:xtdb.api/fn ~kwd-name] ~'args)])))))

(defn install-deftx-fns
  "Given a node and a seq of namespace symbols, scan all public vars
  and use any :xtdb-tx-fn in their metadata to install the tx-fn on
  the node"
  ([node]
   ;; If no namespaces are supplied, take all "glam.xtdb" nses that aren't tests
   (install-deftx-fns
     node
     (->> (all-ns)
          (filter #(clojure.string/starts-with? (str %) "glam.xtdb"))
          (filter #(not (clojure.string/ends-with? (str %) "-test"))))))
  ([node namespaces]
   (doseq [ns-symbol namespaces]
     (when-let [ns (the-ns ns-symbol)]
       (doseq [[vname v] (ns-publics ns)]
         (when-let [tx-install-fn (some-> v meta :xtdb-tx-fn)]
           ;; evict any already-existing entities with the tx-fn's id
           ;; TODO: is there any cost to doing this over and over? If so, consider
           ;; enabling this only in dev and using a put-if-nil strategy for prod
           (xt/await-tx node (xt/submit-tx node [[:xtdb.api/evict (keyword (str ns) (str vname))]]))
           (tx-install-fn node)))))))

;; tx functions for mutations ------------------------------------------------------------
(def ^:private merge-tx-fn-id ::merge)
(def ^:private merge-tx-fn '(fn [ctx eid m]
                              (let [db (xtdb.api/db ctx)
                                    entity (xtdb.api/entity db eid)]
                                [[:xtdb.api/put (merge entity m)]])))

(def ^:private update-tx-fn-id ::update)
(def ^:private update-tx-fn '(fn [ctx eid k f-symbol & args]
                               (let [db (xtdb.api/db ctx)
                                     entity (xtdb.api/entity db eid)
                                     ;; namespace qualify f with clojure.core if there is none
                                     qualified-f-symbol (if (nil? (namespace f-symbol))
                                                          (symbol "clojure.core" (str f-symbol))
                                                          f-symbol)
                                     f-lambda (-> qualified-f-symbol find-var var-get)]
                                 [[:xtdb.api/put (apply update (into [entity k f-lambda] args))]])))

(defn install-tx-fns! [node]
  "Call this on your xtdb node whenever you start it"
  (let [tx-fn (fn [eid fn] {:xt/id eid :xt/fn fn})
        put* (fn [doc] [:xtdb.api/put doc])]
    (when-not (entity node merge-tx-fn-id)
      (xt/submit-tx node [(match* merge-tx-fn-id nil)
                          (put* (tx-fn merge-tx-fn-id merge-tx-fn))]))
    (when-not (entity node update-tx-fn-id)
      (xt/submit-tx node [(match* update-tx-fn-id nil)
                          (put* (tx-fn update-tx-fn-id update-tx-fn))])))
  ;; install tx-fns defined using `deftx` as well
  (install-deftx-fns node))

;; mutations --------------------------------------------------------------------------------
(defn submit-tx-sync [node tx]
  (let [tx-map (xt/submit-tx node tx)]
    (xt/await-tx node tx-map)
    (xt/tx-committed? node tx-map)))
(def submit! submit-tx-sync)

;; vanilla xtdb mutations
(defn put* [doc]
  [:xtdb.api/put doc])
(defn put [node doc]
  (submit-tx-sync node [(put* doc)]))

(defn delete* [eid]
  [:xtdb.api/delete eid])
(defn delete [node eid]
  (submit-tx-sync node [(delete* eid)]))

(defn evict* [eid]
  [:xtdb.api/evict eid])
(defn evict [node eid]
  (submit-tx-sync node [(evict* eid)]))

;; additional mutations api enabled by our tx-fns
(defn merge*
  "Merges m with some entity identified by eid in a transaction function"
  [eid m]
  [:xtdb.api/fn merge-tx-fn-id eid m])
(defn merge
  "Merges m with some entity identified by eid in a transaction function"
  [node eid m]
  (submit-tx-sync node [(merge* eid m)]))

(defmacro update*
  "Updates some entity identified by eid in a transaction function. f can be any function"
  [eid k f & args]
  `(let [f-symbol# (-> ~f var symbol)]
     [:xtdb.api/fn ~update-tx-fn-id ~eid ~k f-symbol# ~@args]))
(defmacro update
  "Updates some entity identified by eid in a transaction function. f can be any function"
  [node eid k f & args]
  `(submit-tx-sync ~node [(update* ~eid ~k ~f ~@args)]))

(comment
  (def node user/node)
  (macroexpand
    '(deftx foo [node rec]
            (let [{foo :foo} {:foo :bar}]
              [[:xtdb.api/put rec]])))

  (install-deftx-fns node)

  (foo node {:xt/id ::tmp})
  (xt/q (xt/db node) '{:find [?e] :where [[?e :xt/id ?eid]] :in [?eid]} :txtl1)

  (fully-qualify-symbols
    (list 'def 'bar []))

  )

;; --------------------------------------------------------------------------------
;; utils
;; --------------------------------------------------------------------------------
(defn identize
  "Turn a single id or seq of ids into a (Pathom-style) ident or vec of idents, respectively.
  Note that this will look like {:foo/id 1} instead of [:foo/id 1] -- the latter is a Fulcroism,
  the former is what Pathom needs."
  [id-or-id-seq id-attr]
  (if (coll? id-or-id-seq)
    (vec (for [id id-or-id-seq]
           {id-attr id}))
    {id-attr id-or-id-seq}))

;; conveniences
(defn new-record
  "Create a blank record with a UUID in :xt/id. If ns is provided,
  will also assoc this id with (keyword ns \"id\"), e.g. :person/id.
  If ns and eid are both provided, will use the eid for both keys instead
  of a random UUID. If eid is passed but is nil, will fall back to a UUID."
  ([] (new-record nil))
  ([ns]
   (let [eid (UUID/randomUUID)]
     (new-record ns eid)))
  ([ns eid]
   (cond
     (nil? eid) (new-record ns)
     (nil? ns) {:xt/id eid}
     :else {:xt/id            eid
            (keyword ns "id") eid})))

(defn create-record
  ([kw-ns attrs]
   (create-record kw-ns nil attrs))
  ([kw-ns id attrs]
   (clojure.core/merge (new-record kw-ns id) attrs)))

(defn remove-id
  "Remove an ID from a to-many join"
  [entity key target-id]
  (let [new-vec (vec (filter #(not= % target-id) (key entity)))]
    (assoc entity key new-vec)))

(defn conj-unique
  "Like conj, but only fires if x is not present"
  [coll x]
  (if (some (hash-set x) coll)
    coll
    (conj coll x)))

;; join conveniences
(defn add-to-multi-joins**
  "Joins from e1 to e2 at all keys specified in `join-keys`. This function is idemponent:
  if an e1->e2 join already exists at some join key on e1, nothing will change.
  This function also includes match clauses for both entities, guarding against race
  conditions."
  [node e1-id join-keys e2-id]
  (let [e1 (entity node e1-id)
        e2 (entity node e2-id)]
    [;;(gxe/match* e1-id e1)
     ;;(gxe/match* e2-id e2)
     (put* (reduce (fn [project join-key]
                     (-> project
                         (clojure.core/update join-key conj-unique e2-id)
                         ;; in case this is the first assoc, turn the list into a vector
                         (clojure.core/update join-key vec)))
                   e1
                   join-keys))]))

(defn add-join**
  "See `add-to-multi-joins**`"
  [node e1-id join-key e2-id]
  (add-to-multi-joins** node e1-id [join-key] e2-id))

(defn remove-from-multi-joins**
  "Remove joins from e1 to e2 at all keys specified in `join-keys`. This function is
  idemponent: if an e1->e2 join does not exist at some join key on e1, nothing will change.
  This function also includes match clauses for both entities, guarding against race
  conditions."
  [node e1-id join-keys e2-id]
  (let [e1 (entity node e1-id)
        e2 (entity node e2-id)]
    (into [;;(gxe/match* e1-id e1)
           ;;(gxe/match* e2-id e2)
           (put* (reduce (fn [project join-key]
                               (remove-id project join-key e2-id))
                             e1
                             join-keys))])))

(defn remove-join**
  "See `remove-from-multi-joins**`"
  [node e1-id join-key e2-id]
  (remove-from-multi-joins** node e1-id [join-key] e2-id))
