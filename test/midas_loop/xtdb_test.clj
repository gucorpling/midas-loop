(ns midas-loop.xtdb-test
  "Integration tests covering "
  (:require [clojure.test :refer :all]
            [xtdb.api :as xt]
            [midas-loop.common-test :refer [sample-string token-by-form]]
            [midas-loop.server.xtdb :refer []]
            [midas-loop.conllu-parser :as parser]
            [midas-loop.xtdb.easy :as cxe]
            [midas-loop.xtdb.serialization :as cxs]
            [midas-loop.xtdb.creation :as cxc]
            [midas-loop.xtdb.queries :as cxq]
            [midas-loop.xtdb.queries.document :as cxqd]
            [midas-loop.xtdb.queries.sentence :as cxqs]
            [midas-loop.xtdb.queries.token :as cxqt]))

(def ^:dynamic node nil)

(use-fixtures
  :each
  (fn [f]
    (binding [node (xt/start-node {})]
      (cxe/install-tx-fns! node)
      (f))))

(deftest read-write
  (let [parsed-data (parser/parse-conllu-string sample-string)]
    (testing "CoNLL-U can be read"
      (cxc/create-document node parsed-data)
      (is (= 1 (count (cxe/find-entities node {:document/id '_}))))
      (is (= 19 (count (cxe/find-entities node {:token/id '_})))))

    (testing "Round-trip data looks the same in the trivial case (no changes)"
      (let [doc-id (:document/id (first (cxe/find-entities node {:document/id '_})))]
        (is (= (clojure.string/trim sample-string)
               (clojure.string/trim (cxs/serialize-document node doc-id)))))
      )))

(deftest sentence-splitting
  (let [parsed-data (parser/parse-conllu-string sample-string)
        _ (cxc/create-document node parsed-data)
        juan-token-id (token-by-form node "Juan")
        freq-token-id (token-by-form node "frequently")
        last-sent-id (ffirst (xt/q (xt/db node) '{:find  [?s]
                                                  :where [[?s :sentence/conllu-metadata ?cm]
                                                          [?cm :conllu-metadata/value "AMALGUM_bio_cartagena-3"]]}))
        first-sent-id (ffirst (xt/q (xt/db node) '{:find  [?s]
                                                   :where [[?s :sentence/conllu-metadata ?cm]
                                                           [?cm :conllu-metadata/value "AMALGUM_bio_cartagena-1"]]}))]
    (testing "Splitting a sentence fails if the token is at the beginning of a sentence"
      (= :error (:status (cxqs/split-sentence node juan-token-id))))

    (testing "Splitting a sentence is OK if the token is not at the beginning of a sentence"
      (= :ok (:status (cxqs/split-sentence node freq-token-id)))
      (= 3 (count (cxe/find-entities node {:sentence/id '_})))
      (= 6 (count (:sentence/tokens (xt/pull (xt/db node) [{:sentence/tokens [:token/id]}] first-sent-id))))
      (= 12 (count (:sentence/tokens (xt/pull (xt/db node) [{:sentence/tokens [:token/id]}] last-sent-id))))
      (= 1 (count (:sentence/tokens
                    (xt/pull (xt/db node) [{:sentence/tokens [:token/id]}]
                             (ffirst (xt/q (xt/db node)
                                           {:find  '[?s]
                                            :where ['[?s :sentence/id]
                                                    '(not [?s :sentence/id ~last-sent-id])
                                                    '(not [?s :sentence/id ~first-sent-id])]}))))))
      )

    (testing "Merging a sentence fails if it's the last sentence in a document"
      (= :error (:status (cxqs/merge-sentence-right node last-sent-id))))

    (testing "Merging a sentence OK if it's the last sentence in a document"
      (= :ok (:status (cxqs/merge-sentence-right node first-sent-id)))
      (= 2 (count (cxe/find-entities node {:sentence/id '_}))))))

(deftest atomic-values
  (let [parsed-data (parser/parse-conllu-string sample-string)
        _ (cxc/create-document node parsed-data)
        juan-token-id (token-by-form node "Juan")
        juan-token (cxe/entity node juan-token-id)]
    (testing "Form is writeable"
      (let [form-id (:token/form juan-token)]
        (cxqt/put node {:form/id form-id :form/value "juan"} :form/id)
        (= (:form/value (cxe/entity node form-id)) "juan")))

    (testing "Putting with the wrong ID fails"
      (is (= :error (:status (cxqt/put node {:form/id juan-token-id :form/value "foo"} :form/id)))))))

(deftest assoc-values
  (let [parsed-data (parser/parse-conllu-string sample-string)
        _ (cxc/create-document node parsed-data)
        juan-token-id (token-by-form node "Juan")
        juan-token (cxe/entity node juan-token-id)]
    (testing "Deleting assoc column works"
      (let [feats-ids (:token/feats juan-token)
            pre-count (count feats-ids)]
        (cxqt/delete-assoc node (first feats-ids) :feats/id)
        (is (= pre-count (inc (count (:token/feats (cxe/entity node juan-token-id))))))))))

(deftest dep-values
  (let [parsed-data (parser/parse-conllu-string sample-string)
        _ (cxc/create-document node parsed-data)
        juan-token-id (token-by-form node "Juan")
        freq-token-id (token-by-form node "frequently")
        juan-token (cxe/entity node juan-token-id)]
    (testing "Putting head works and also updates deps"
      (let [head-id (:token/head juan-token)
            ;; TODO(DEPS)
            #_#_deps-id (first (:token/deps juan-token))]
        (is (= :ok (:status (cxqt/put-head node {:head/id head-id :head/value freq-token-id}))))
        (is (= freq-token-id (:head/value (cxe/entity node head-id))))
        #_(is (= freq-token-id (:deps/key (cxe/entity node deps-id))))

        (is (= :ok (:status (cxqt/put-head node {:head/id head-id :head/value :root}))))
        (is (= :root (:head/value (cxe/entity node head-id))))
        #_(is (= :root (:deps/key (cxe/entity node deps-id))))

        (is (= :ok (:status (cxqt/put-head node {:head/id head-id :head/value nil}))))
        (is (= nil (:head/value (cxe/entity node head-id))))
        #_(is (= nil (:deps/key (cxe/entity node deps-id))))))

    (testing "Putting deprel works and also updates deps"
      (let [deprel-id (:token/deprel juan-token)
            #_#_deps-id (first (:token/deps juan-token))]
        (is (= :ok (:status (cxqt/put-deprel node {:deprel/id deprel-id :deprel/value "orphan"}))))
        (is (= "orphan" (:deprel/value (cxe/entity node deprel-id))))
        #_(is (= "orphan" (:deps/value (cxe/entity node deps-id))))

        (is (= :ok (:status (cxqt/put-deprel node {:deprel/id deprel-id :deprel/value nil}))))
        (is (= nil (:deprel/value (cxe/entity node deprel-id))))
        #_(is (= nil (:deps/value (cxe/entity node deps-id))))))))
