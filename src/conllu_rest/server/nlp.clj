(ns conllu-rest.server.nlp
  (:require [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io]))

(defn- valid-url? [s]
  (try
    (do (io/as-url s)
        true)
    (catch Exception _ false)))

(s/def ::url valid-url?)
(s/def ::type #{:sentence :xpos :upos :head-deprel})
(s/def ::config (s/keys :req-un [::url ::type]))

(defprotocol SentenceLevelProbDistProvider
  "A SentenceLevelProbDistProvider is backed by an NLP model that can take an input sentence and
  provide a probability distribution for each instance of the kind of annotation that it handles
  in the sentence."
  (predict-prob-dists [this sentence]))

(defrecord HttpProbDistProvider [config]
  SentenceLevelProbDistProvider
  (predict-prob-dists [this sentence]
    (let [{:keys [url]} config]
      [sentence url])))


;; some inspo: https://stackoverflow.com/questions/14673108/asynchronous-job-queue-for-web-service-in-clojure
;; Gameplan here.
;;
;; - On startup, read configs, which declare services:
;;   {:url "http://localhost:8080", :type :head}
;; - Create an agent for each config that can ping the service for results and make all the appropriate
;;   changes in the DB
;; - Make a listener function for xtdb that will:
;;   1. Inspect all items in the transaction and determine the sentence ID that is related to it
;;   2. Pool those sentence IDs into a set
;;   3. Queue a re-process with the NLP agent

(defn xtdb-listen [event]
  (println event)


  (comment
    {:xtdb.api/event-type :xtdb.api/indexed-tx,
     :committed? true,
     :xtdb.api/tx-time #inst "2022-01-18T04:55:17.894-00:00",
     :xtdb.api/tx-id 224,
     :xtdb.api/tx-ops ([:xtdb.api/put {:sentence/id #uuid "c516fb3e-68e5-46c5-96e4-585e624ec96f", :sentence/conllu-metadata [#uuid "fddb34c1-8d8c-45c8-8de8-8faaa954ca00" #uuid "dfbf5aac-ac03-45ee-9888-de277449de7e" #uuid "25fe0b71-c2da-4171-88ec-321e0ca7939d"], :sentence/tokens [#uuid "55773239-3b76-40b0-84cd-b0bbf356e7e1" #uuid "684edc6c-1e55-493c-a430-bbaafce29327"], :xt/id #uuid "c516fb3e-68e5-46c5-96e4-585e624ec96f"}] [:xtdb.api/put {:sentence/id #uuid "febc74db-fdf8-4bac-bad4-a0e646ee2d74", :sentence/tokens [#uuid "0ad0f06e-fb78-4f8c-8e77-04da2fc0a10d"], :xt/id #uuid "febc74db-fdf8-4bac-bad4-a0e646ee2d74"}] [:xtdb.api/put {:document/id #uuid "0cd07cb9-2887-405b-8160-d80ba54a93ed", :document/name AMALGUM_whow_stone, :document/sentences [#uuid "83c25682-c658-4d8f-a8ab-2d34398e0515" #uuid "c7d9c03b-b798-40a0-b415-51c515a5b77a" #uuid "a3c90161-523c-4c9c-a874-19cf2258afd0" #uuid "7cb6922b-b241-470c-82a5-05370bb58083" #uuid "2437416a-f340-4197-af4d-c1f6f0e18508" #uuid "1f851756-c249-4a0e-a60f-97c4db24cdc9" #uuid "c516fb3e-68e5-46c5-96e4-585e624ec96f" #uuid "febc74db-fdf8-4bac-bad4-a0e646ee2d74" #uuid "c2fc1976-5148-43fe-9efa-0ea2aec767f5" #uuid "e8144b94-1bf9-408e-a38c-411f5bac7a26" #uuid "0a1a1430-099f-4003-961e-6989cdc9d8f0" #uuid "3376a727-1b3e-4649-89ec-40782c145e38" #uuid "7db5dfb5-b979-4118-a838-a667e5a5488a" #uuid "718346ae-c67c-4296-9dfe-50c689d7525e" #uuid "fa9725f4-4c93-408c-b35b-71a904b6498b" #uuid "4812e867-5193-4059-97a4-675556cb0049" #uuid "15b8becb-8db2-497c-b652-453172b33430" #uuid "369dddd3-c82f-4645-949e-339d44846ebb" #uuid "29e3bbb4-0db6-4338-b987-8cafc238268b" #uuid "80ec658b-156c-4a4b-8717-f3aad6080335" #uuid "5a91e792-f2af-4570-903f-4dc4cce54ee4" #uuid "ac66ea5a-106d-4394-b00d-f6f42cde90e7" #uuid "34a21132-61b0-4ae4-a00d-0f613bec6d03" #uuid "2a3aa3e1-4b34-4fa1-9083-9cfbd2e3f8e3" #uuid "949e5b69-d2f9-492d-bdca-3351a15ea238" #uuid "9a16f2a2-5b0c-4fc3-8d83-06c3f873ea32" #uuid "9227a242-96b3-4fa7-9e45-3f7be648d040" #uuid "fc862a6e-a7d8-40f9-8f15-2340a7565af5" #uuid "f295e4bf-0e59-433f-b620-ad10f98f5a33" #uuid "1affa5e5-89bf-422f-be76-86f62d84ce35" #uuid "92db4a52-7bc7-4a35-a37b-4f98598ddc39" #uuid "6c17b02d-a85e-48b7-aeef-efc2b33c728d" #uuid "74ad67fa-ff01-4002-8f84-e21d6835cadc" #uuid "9ff9cc91-4ab8-488e-8f19-d38e3a9413ae" #uuid "8d4526e6-7fa4-4730-8475-977b5bf7646d" #uuid "d7659217-bdd5-41cf-ac4e-1ac9384d221b" #uuid "37d987a3-bd9f-4726-9991-9bea3d58c313" #uuid "cc34ec27-ec9d-4c79-9e02-089017d909c4" #uuid "648a984b-60bb-4c43-91a0-95c49c2682a6" #uuid "2582125b-5f73-456d-9b4c-12e467c787a2" #uuid "3e2a58e1-b79e-4bbb-8ee1-b874ee3fd5ad" #uuid "4786bcd1-18b7-418e-9cc7-8d0bcfb8ebb1" #uuid "4653b6cf-1ff3-4aef-95e6-db897ec8ef81" #uuid "8d08e269-98be-4345-938f-88af3a865c67" #uuid "40bd1db3-cd20-4c7c-a8cc-c294f2b2ef2c" #uuid "f7a57b43-da1a-410e-9650-f5ee4786ff98" #uuid "2c909df1-8e58-4897-a2c0-f8d3643f4798" #uuid "ecc3af8d-821e-40ad-91d7-8717637cc3e8" #uuid "318afee9-78a6-4c51-ae02-68f499187ec7" #uuid "79f16918-4033-43a6-a5fb-e26ec7ea0cf8" #uuid "e5f31736-c01e-4ea2-abef-c06e0b8a84b6" #uuid "ac538b67-202b-40a3-8717-6d42860c9c32" #uuid "10505c3c-c905-4b5a-bdeb-2373af7af4c5" #uuid "c38b7fe0-22e5-45e8-991c-6fbd02e3f8f0" #uuid "4c7ecc8b-0dd0-4165-a43d-7a576f1659df" #uuid "acc404d5-34b0-46a5-bee4-e7c6b9edd1f2" #uuid "c401a620-6b3b-44d4-9143-f95eb684f1da" #uuid "87e3a91e-9569-4347-9417-abf05b6fe123" #uuid "26199b2f-3fcf-47ac-90f6-b603b9dc0096" #uuid "179919c5-7f38-4118-b659-a31cc7e213e3" #uuid "11762500-21c0-48e1-a93b-e8ca76b2360b" #uuid "a0dc1f00-3124-4128-be2f-66786fd88e01" #uuid "5eb2f74d-052b-4084-8755-0c90676f35c8" #uuid "8cf86de2-5bed-4ab3-82a4-92751d6014aa" #uuid "c3b362f6-f236-4de6-b0d9-dfb8fbd91b93" #uuid "713648de-05ef-4e82-94ad-d3d3f7a5352b" #uuid "ed10ce12-1b06-403e-ba79-00435829aadb" #uuid "0a0db29a-616f-4ed6-bcf8-dbaaeb312692"], :xt/id #uuid "0cd07cb9-2887-405b-8160-d80ba54a93ed"}] [:xtdb.api/put {:head/id #uuid "991a7c3c-feed-4789-85cb-39e5efef16be", :head/value nil, :xt/id #uuid "991a7c3c-feed-4789-85cb-39e5efef16be"}] [:xtdb.api/put {:deprel/id #uuid "11c4c081-4f00-425e-b349-45686bb34246", :deprel/value nil, :xt/id #uuid "11c4c081-4f00-425e-b349-45686bb34246"}] [:xtdb.api/delete #xtdb/id 1ed7a87239211ac7045233f06ef780963273304b] [:xtdb.api/put {:token/lemma #uuid "06b5aa92-c6ee-44ec-a290-89d2a4d9ba20", :token/form #uuid "ef75ed44-33d1-4798-b033-fb31a1bc4f46", :token/deps [], :token/token-type :token, :token/feats [#uuid "5a87e898-b994-437a-b85f-1aef5e4ed814" #uuid "51ce5258-5212-476b-a676-b515aebbe5c4"], :token/misc [#uuid "dcefc6ed-fa75-49e8-8f1f-94c7850e34e7"], :token/deprel #uuid "11c4c081-4f00-425e-b349-45686bb34246", :token/xpos #uuid "6e8142de-8b8d-4fbb-b475-518f11258a36", :xt/id #uuid "684edc6c-1e55-493c-a430-bbaafce29327", :token/head #uuid "991a7c3c-feed-4789-85cb-39e5efef16be", :token/id #uuid "684edc6c-1e55-493c-a430-bbaafce29327", :token/upos #uuid "d0556774-c044-4509-9f5a-3cc2d89ed994"}] [:xtdb.api/put {:head/id #uuid "a24bcdc6-f03e-4893-9e32-904b3b76e854", :head/value nil, :xt/id #uuid "a24bcdc6-f03e-4893-9e32-904b3b76e854"}] [:xtdb.api/put {:deprel/id #uuid "82d80b33-9112-4ff4-87b6-0479b701470e", :deprel/value nil, :xt/id #uuid "82d80b33-9112-4ff4-87b6-0479b701470e"}] [:xtdb.api/delete #xtdb/id f87fd51c7c5ec89b501778fe2c7e14f17ac97567] [:xtdb.api/put {:token/lemma #uuid "d188a581-c8d8-4c23-9f82-afd5e5058c4e", :token/form #uuid "2bf4bf47-004f-497a-b6c3-12e59e5989ab", :token/deps [], :token/token-type :token, :token/feats [#uuid "2ba74cb2-ae4d-4567-9516-6ff0e71d03ca"], :token/misc [#uuid "7dc1ecb9-d3d5-403f-a274-1e8a4e30c4b5"], :token/deprel #uuid "82d80b33-9112-4ff4-87b6-0479b701470e", :token/xpos #uuid "a218dbc2-621f-4a8a-a9f7-1aa5b66bcfe0", :xt/id #uuid "0ad0f06e-fb78-4f8c-8e77-04da2fc0a10d", :token/head #uuid "a24bcdc6-f03e-4893-9e32-904b3b76e854", :token/id #uuid "0ad0f06e-fb78-4f8c-8e77-04da2fc0a10d", :token/upos #uuid "f56ac2bc-f095-415d-9e67-9d89448a44e2"}])}


    )

  )

(comment
  (do
    (require '[conllu-rest.server.xtdb :refer [xtdb-node]])
    (require '[conllu-rest.xtdb.easy :as cxe]))

  (-> (->HttpProbDistProvider {:url "http://localhost:5555"})
      (predict-prob-dists "qwe"))

  (->HttpProbDistProvider {:url "http://localhost"})

  (s/conform ::config {:type :upos :url "http://localhost:80"})


  (def sent-id)

  (let [{:keys [status body] :as resp} (client/get "http://localhost:5555")]
    (keys resp)
    )

  (spit "/tmp/tmp" (client/get "http://localhost:80"))

  )