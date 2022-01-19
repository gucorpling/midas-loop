(ns conllu-rest.routes.conllu-routes-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [muuntaja.core :as m]
            [mount.core :as mount]
            [xtdb.api :as xt]
            [muuntaja.core :as m]
            [clojure.java.io :as io]
            [conllu-rest.server.handler :refer :all]
            [conllu-rest.server.middleware]
            [conllu-rest.xtdb.creation :as cxc]
            [conllu-rest.conllu-parser :as parser]
            [conllu-rest.common-test :refer [sample-string token-by-form parse-json]]
            [conllu-rest.xtdb.easy :as cxe]))

(def ^:dynamic xtdb-node nil)
(def ^:dynamic handler nil)
(def ^:dynamic doc-id nil)
(def ^:dynamic sent1-id nil)
(def ^:dynamic sent2-id nil)
(def ^:dynamic juan-token-id nil)
(def ^:dynamic juande-token-id nil)
(def ^:dynamic foo-token-id nil)
(def ^:dynamic bar-token-id nil)

(use-fixtures
  :once
  (fn [f]
    (let [node (xt/start-node {})]
      (mount/start #'conllu-rest.server.config/env
                   #'conllu-rest.server.handler/app-routes)
      (cxc/create-document node (parser/parse-conllu-string sample-string))
      (let [sents (cxe/find-entities node [[:sentence/id '_]])]
        (binding [xtdb-node node
                  handler (app node (xt/start-node {}))
                  doc-id (str (:document/id (first (cxe/find-entities node [[:document/id '_]]))))
                  sent1-id (str (:sentence/id (first (filter #(= 6 (count (:sentence/tokens %))) sents))))
                  sent2-id (str (:sentence/id (first (filter #(= 13 (count (:sentence/tokens %))) sents))))
                  juan-token-id (str (token-by-form node "Juan"))
                  juande-token-id (str (token-by-form node "Juande"))
                  foo-token-id (str (token-by-form node "foo"))
                  bar-token-id (str (token-by-form node "bar"))]
          (f))))))

(deftest test-sentences
  (testing "Can't split on a subtoken of a supertoken"
    (let [response (handler (-> (request :post "/api/conllu/sentence/split")
                                (json-body {:token-id juan-token-id})))]
      (is (= 400 (:status response)))))

  (testing "Can split on a normal token"
    (let [response (handler (-> (request :post "/api/conllu/sentence/split")
                                (json-body {:token-id foo-token-id})))
          new-sid (:new-sentence-id (parse-json (:body response)))
          response2 (handler (-> (request :post "/api/conllu/sentence/merge-left")
                                 (json-body {:sentence-id new-sid})))]
      (is (= 200 (:status response)))
      (is (= 200 (:status response2)))))

  (testing "Cannot merge-left on the first sentence or merge-right on the last sentence"
    (is (= 400 (:status (handler (-> (request :post "/api/conllu/sentence/merge-left")
                                     (json-body {:sentence-id sent1-id}))))))
    (is (= 400 (:status (handler (-> (request :post "/api/conllu/sentence/merge-right")
                                     (json-body {:sentence-id sent2-id}))))))))

(defn tget [token-id]
  (let [response (handler (request :get (str "/api/conllu/token/id/" token-id)))
        data (parse-json (:body response))]
    data))

(defn set-atomic
  [name]
  (fn [tstate v]
    (handler (-> (request :put (str "/api/conllu/" name "/id/" (get-in tstate [(keyword name) :id])))
                 (json-body {:value v})))))

(defn create-assoc
  [name]
  (fn [tid k v]
    (handler (-> (request :post (str "/api/conllu/" name))
                 (json-body {:token-id tid
                             :key      k
                             :value    v})))))

(defn set-assoc
  [name]
  (fn [id v]
    (handler (-> (request :put (str "/api/conllu/" name "/id/" id))
                 (json-body {:value v})))))

(defn delete-assoc
  [name]
  (fn [id]
    (handler (request :delete (str "/api/conllu/" name "/id/" id)))))

(deftest test-atomics
  (let [set-form (set-atomic "form")
        set-lemma (set-atomic "lemma")
        set-upos (set-atomic "upos")
        set-xpos (set-atomic "xpos")]
    (testing "form"
      (let [tstate (tget juan-token-id)
            resp (set-form tstate "test")
            tstate2 (tget juan-token-id)
            resp2 (set-form tstate "Juan")
            tstate3 (tget juan-token-id)]
        (is (= 200 (:status resp)))
        (is (= 200 (:status resp2)))
        (is (= "Juan" (get-in tstate [:form :value])))
        (is (= "test" (get-in tstate2 [:form :value])))
        (is (= "Juan" (get-in tstate3 [:form :value])))
        (is (= 400 (:status (handler (-> (request :put (str "/api/conllu/form/id/" (get-in tstate [:lemma :id])))
                                         (json-body {:value "not actually a form"}))))))))

    (testing "lemma"
      (let [tstate (tget juan-token-id)
            resp (set-lemma tstate "test")
            tstate2 (tget juan-token-id)
            resp2 (set-lemma tstate "Juan")
            tstate3 (tget juan-token-id)]
        (is (= 200 (:status resp)))
        (is (= 200 (:status resp2)))
        (is (= "Juan" (get-in tstate [:lemma :value])))
        (is (= "test" (get-in tstate2 [:lemma :value])))
        (is (= "Juan" (get-in tstate3 [:lemma :value])))
        (is (= 400 (:status (handler (-> (request :put (str "/api/conllu/lemma/id/" (get-in tstate [:form :id])))
                                         (json-body {:value "not actually a lemma"}))))))))

    (testing "xpos"
      (let [tstate (tget juan-token-id)
            resp (set-xpos tstate "test")
            tstate2 (tget juan-token-id)
            resp2 (set-xpos tstate "NNP")
            tstate3 (tget juan-token-id)]
        (is (= 200 (:status resp)))
        (is (= 200 (:status resp2)))
        (is (= "NNP" (get-in tstate [:xpos :value])))
        (is (= "test" (get-in tstate2 [:xpos :value])))
        (is (= "NNP" (get-in tstate3 [:xpos :value])))
        (is (= 400 (:status (handler (-> (request :put (str "/api/conllu/xpos/id/" (get-in tstate [:form :id])))
                                         (json-body {:value "not actually a xpos"}))))))))

    (testing "upos"
      (let [tstate (tget juan-token-id)
            resp (set-upos tstate "test")
            tstate2 (tget juan-token-id)
            resp2 (set-upos tstate "PROPN")
            tstate3 (tget juan-token-id)]
        (is (= 200 (:status resp)))
        (is (= 200 (:status resp2)))
        (is (= "PROPN" (get-in tstate [:upos :value])))
        (is (= "test" (get-in tstate2 [:upos :value])))
        (is (= "PROPN" (get-in tstate3 [:upos :value])))
        (is (= 400 (:status (handler (-> (request :put (str "/api/conllu/upos/id/" (get-in tstate [:form :id])))
                                         (json-body {:value "not actually a upos"}))))))))))

(deftest test-assocs
  (let [create-feats (create-assoc "feats")
        set-feats (set-assoc "feats")
        delete-feats (delete-assoc "feats")
        create-misc (create-assoc "misc")
        set-misc (set-assoc "misc")
        delete-misc (delete-assoc "misc")
        assoc-get (fn [colname] (fn [t k]
                                  (->> ((keyword colname) t)
                                       (filter #(= (:key %) k))
                                       first)))
        feats-get (assoc-get "feats")
        misc-get (assoc-get "misc")]
    (testing "feats"
      (let [tstate (tget juan-token-id)
            resp (create-feats juan-token-id "foo" "bar")
            parsed (parse-json (:body resp))
            new-feats-id (:id parsed)
            tstate2 (tget juan-token-id)
            resp2 (set-feats new-feats-id "bar2")
            tstate3 (tget juan-token-id)
            resp3 (delete-feats new-feats-id)
            tstate4 (tget juan-token-id)
            existing-id (:id (first (:feats tstate)))
            existing-wrong-id (:id (first (:misc tstate)))
            resp4 (set-feats existing-id "sang")
            tstate5 (tget juan-token-id)
            resp5 (set-feats existing-wrong-id "qwe")]
        (is (= 200 (:status resp)))
        (is (= 200 (:status resp2)))
        (is (= 200 (:status resp3)))
        (is (= 200 (:status resp4)))
        (is (= 400 (:status resp5)))
        (is (= nil (feats-get tstate "foo")))
        (is (= "bar" (:value (feats-get tstate2 "foo"))))
        (is (= "bar2" (:value (feats-get tstate3 "foo"))))
        (is (= nil (feats-get tstate4 "foo")))
        (is (= "sang" (:value (feats-get tstate5 "Number"))))))

    (testing "misc"
      (let [tstate (tget juan-token-id)
            resp (create-misc juan-token-id "foo" "bar")
            parsed (parse-json (:body resp))
            new-misc-id (:id parsed)
            tstate2 (tget juan-token-id)
            resp2 (set-misc new-misc-id "bar2")
            tstate3 (tget juan-token-id)
            resp3 (delete-misc new-misc-id)
            tstate4 (tget juan-token-id)
            existing-id (:id (first (:misc tstate)))
            existing-wrong-id (:id (first (:feats tstate)))
            resp4 (set-misc existing-id "sang")
            tstate5 (tget juan-token-id)
            resp5 (set-misc existing-wrong-id "qwe")]
        (is (= 200 (:status resp)))
        (is (= 200 (:status resp2)))
        (is (= 200 (:status resp3)))
        (is (= 200 (:status resp4)))
        (is (= 400 (:status resp5)))
        (is (= nil (misc-get tstate "foo")))
        (is (= "bar" (:value (misc-get tstate2 "foo"))))
        (is (= "bar2" (:value (misc-get tstate3 "foo"))))
        (is (= nil (misc-get tstate4 "foo")))
        (is (= "sang" (:value (misc-get tstate5 "Discourse"))))))))

(deftest test-deps
  (testing "head, deprel, deps"
    (let [set-head (set-atomic "head")
          set-deprel (set-atomic "deprel")

          tstate (tget juan-token-id)

          resp (set-head tstate nil)
          dresp (set-deprel tstate nil)
          tstate2 (tget juan-token-id)

          resp2 (set-head tstate foo-token-id)
          dresp2 (set-deprel tstate "orphan")
          tstate3 (tget juan-token-id)

          resp3 (set-head tstate "root")
          dresp3 (set-deprel tstate "root")
          tstate4 (tget juan-token-id)

          resp4 (set-head tstate juande-token-id)
          tstate5 (tget juan-token-id)

          resp5 (set-head tstate sent1-id)
          tstate6 (tget juan-token-id)

          foo-state (tget foo-token-id)
          resp6 (set-head foo-state "root")
          foo-state' (tget foo-token-id)

          _ (println "DOIN IT RIGHT")
          bar-state (tget bar-token-id)
          resp7 (set-deprel bar-state "root")
          bar-state' (tget bar-token-id)

          hget (fn [ts] (get-in ts [:head :value]))
          dget (fn [ts] (get-in ts [:deprel :value]))
          dhget (fn [ts] (-> ts :deps first :key))
          ddget (fn [ts] (-> ts :deps first :value))]

      (is (= "root" (hget tstate)))
      (is (= "root" (dhget tstate)))
      (is (= "root" (ddget tstate)))

      (is (= 200 (:status resp)))
      (is (= 200 (:status dresp)))
      (is (= nil (hget tstate2)))
      (is (= nil (dget tstate2)))
      (is (= nil (dhget tstate2)))
      (is (= nil (ddget tstate2)))

      (is (= 200 (:status resp2)))
      (is (= 200 (:status dresp2)))
      (is (= foo-token-id (hget tstate3)))
      (is (= "orphan" (dget tstate3)))
      (is (= foo-token-id (dhget tstate3)))
      (is (= "orphan" (ddget tstate3)))

      (is (= 200 (:status resp3)))
      (is (= 200 (:status dresp3)))
      (is (= "root" (hget tstate4)))
      (is (= "root" (dget tstate4)))
      (is (= "root" (dhget tstate4)))
      (is (= "root" (ddget tstate4)))

      (is (= 400 (:status resp4)))
      (is (= 400 (:status resp5)))

      ;; setting head on deps-less token creates and sets a deps record
      (is (= 200 (:status resp6)))
      (is (= 0 (-> foo-state :deps count)))
      (is (= 1 (-> foo-state' :deps count)))
      (is (= "root" (dhget foo-state')))

      ;; setting deprel on deps-less token creates and sets a deps record
      (is (= 200 (:status resp7)))
      (println (:deps foo-state'))
      (println (:deps bar-state))
      (is (= 0 (-> bar-state :deps count)))
      (is (= 1 (-> bar-state' :deps count)))
      (is (= "root" (ddget bar-state'))))))