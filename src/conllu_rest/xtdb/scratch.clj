(ns conllu-rest.xtdb.scratch
  (:require [xtdb.api :as xt]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.xtdb.serialization :as cxs]
            [conllu-rest.xtdb.creation :as cxc]
            [conllu-rest.xtdb.queries :as cxq]
            [conllu-rest.xtdb.queries.diff :as cxqd]
            [conllu-rest.server.tokens :as tok]
            [editscript.core :as e]))


(def data "
# meta::id = AMALGUM_bio_cartagena
# meta::title = Juan de Cartagena
# meta::shortTitle = cartagena
# meta::type = bio
# meta::dateCollected = 2019-11-05
# meta::dateCreated = 2012-11-03
# meta::dateModified = 2019-10-01
# meta::sourceURL = https://en.wikipedia.org/wiki/Juan_de_Cartagena
# meta::speakerList = none
# meta::speakerCount = 0
# newdoc id = AMALGUM_bio_cartagena
# sent_id = AMALGUM_bio_cartagena-1
# s_type = frag
# text = Juan de Cartagena
# newpar = head (1 s)
1-2	Juan	Juan	_	_	_	_	_	_	_
1	Juan	Juan	PROPN	NNP	Number=Sing	0	root	0:root	Discourse=preparation:1->6|Entity=(person-1
2	de	de	PROPN	NNP	Number=Sing	1	flat	1:flat	_
2.1	foo	foo	_	_	_	_	_	_	_
2.2	bar	bar	_	_	_	_	_	_	_
3	Cartagena	Cartagena	PROPN	NNP	Number=Sing	1	flat	1:flat	Entity=person-1)
")

(def data2 "
# meta::id = AMALGUM_bio_cartagena
# meta::title = Juan de Cartagena
# meta::shortTitle = cartagena
# meta::type = bio
# meta::dateCollected = 2019-11-05
# meta::dateCreated = 2012-11-03
# meta::dateModified = 2019-10-01
# meta::sourceURL = https://en.wikipedia.org/wiki/Juan_de_Cartagena
# meta::speakerList = none
# meta::speakerCount = 0
# newdoc id = AMALGUM_bio_cartagena
# sent_id = AMALGUM_bio_cartagena-1
# s_type = frag
# text = Juan de Cartagena
# newpar = head (1 s)
1-2	Juan	Juan	_	_	_	_	_	_	_
1	Juan	Juan	PROPN	NNP	Number=Sing	0	root	0:root	Discourse=preparation:1->6|Entity=(person-1
2	de	de	PROPN	NNP	Number=Sing	1	flat	1:flat	_
2.1	foo	FOo	_	_	_	_	_	_	_
2.2	bar	bar	_	_	_	_	_	_	_
3	Cartagena	Cartagena	PROPN	NNP	Number=Sing	1	flat	1:flat	Entity=person-1)
")

(comment
  (def node (xtdb.api/start-node {}))
  (def xs (conllu-rest.conllu-parser/parse-conllu-string data))

  (require '[conllu-rest.server.xtdb :refer [xtdb-node]])

  (cxc/create-document node xs)

  (def doc-id (ffirst (xt/q (xt/db node) {:find '[?d] :where [['?d :document/id]]})))

  (spit "/tmp/bar" (conllu-rest.xtdb.serialization/serialize-document node doc-id))
  (spit "/tmp/bar" (conllu-rest.xtdb.serialization/serialize-document xtdb-node #uuid "b066f4d4-cce5-4f04-93f9-702ae5912bc5"))

  (cxq/pull xtdb-node {:document/id #uuid "b066f4d4-cce5-4f04-93f9-702ae5912bc5"
                       :xt/id       #uuid "b066f4d4-cce5-4f04-93f9-702ae5912bc5"})

  (cxq/pull xtdb-node {:sentence/id #uuid "89ba245d-9abb-484d-a310-d81dc55ae57c"
                       :xt/id       #uuid "89ba245d-9abb-484d-a310-d81dc55ae57c"})

  (xt/pull (xt/db xtdb-node)
           [:document/id
            :document/name
            {:document/sentences [:sentence/id]}]
           #uuid "b066f4d4-cce5-4f04-93f9-702ae5912bc5")

  )

(comment

  (def node (xtdb.api/start-node {}))
  (def xs (conllu-rest.conllu-parser/parse-conllu-string data))
  (cxc/create-document node xs)
  (def doc-id (:document/id (first (cxe/find-entities node [[:document/id '_]]))))
  doc-id

  (cxqd/get-diff node doc-id data data2)

  )


(comment
  ;; upload into real db
  (doseq [genre ["bio" "fiction" "news" "academic" "interview" "voyage" "whow"]]
    (let [path (str "amalgum/amalgum/" genre "/dep")
          filenames (seq (.list (clojure.java.io/file path)))
          filepaths (sort (map #(str path "/" %) filenames))]

      (cxc/ingest-conllu-files xtdb-node filepaths)
      ))



  (let [files (map clojure.string/trim (clojure.string/split-lines "./amalgum/amalgum/fiction/dep/AMALGUM_fiction_twain.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_queensland.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_arccos.conllu
  ./amalgum/amalgum/fiction/dep/AMALGUM_fiction_cracksman.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_yerby.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_atomic.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_archives.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_computing.conllu
  ./amalgum/amalgum/fiction/dep/AMALGUM_fiction_shaggy.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_wayne.conllu
  ./amalgum/amalgum/bio/dep/AMALGUM_bio_balandin.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_african.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_joe.conllu
  ./amalgum/amalgum/reddit/AMALGUM_reddit_splosion.conllu
  ./amalgum/amalgum/voyage/dep/AMALGUM_voyage_dothan.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_lords.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_trading.conllu
  ./amalgum/amalgum/fiction/dep/AMALGUM_fiction_cupid.conllu
  ./amalgum/amalgum/fiction/dep/AMALGUM_fiction_trades.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_dracaena.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_viewer.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_skills.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_hate.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_merlino.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_stone.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_mercenary.conllu
  ./amalgum/amalgum/voyage/dep/AMALGUM_voyage_chiapas.conllu
  ./amalgum/amalgum/fiction/dep/AMALGUM_fiction_baartock.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_marston.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_gamification.conllu
  ./amalgum/amalgum/bio/dep/AMALGUM_bio_tyrconnell.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_simulation.conllu
  ./amalgum/amalgum/voyage/dep/AMALGUM_voyage_sarajevo.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_suppliers.conllu
  ./amalgum/amalgum/fiction/dep/AMALGUM_fiction_goriot.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_colour.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_fetishes.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_reuters.conllu
  ./amalgum/amalgum/bio/dep/AMALGUM_bio_kara.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_funds.conllu
  ./amalgum/amalgum/fiction/dep/AMALGUM_fiction_pellucidar.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_vodka.conllu
  ./amalgum/amalgum/voyage/dep/AMALGUM_voyage_pontiac.conllu
  ./amalgum/amalgum/reddit/AMALGUM_reddit_javelin.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_canadian.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_mileage.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_turnover.conllu
  ./amalgum/amalgum/reddit/AMALGUM_reddit_weird.conllu
  ./amalgum/amalgum/bio/dep/AMALGUM_bio_twynnoy.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_mutations.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_circularity.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_waveform.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_incumbent.conllu
  ./amalgum/amalgum/reddit/AMALGUM_reddit_kids.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_dolores.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_vessel.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_whitecaps.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_rickard.conllu
  ./amalgum/amalgum/voyage/dep/AMALGUM_voyage_grosseto.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_bicentennial.conllu
  ./amalgum/amalgum/bio/dep/AMALGUM_bio_civil.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_elgersma.conllu
  ./amalgum/amalgum/bio/dep/AMALGUM_bio_huipa.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_competences.conllu
  ./amalgum/amalgum/voyage/dep/AMALGUM_voyage_marquesas.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_scientology.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_skive.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_iraq.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_candles.conllu
  ./amalgum/amalgum/bio/dep/AMALGUM_bio_capital.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_eosinophils.conllu
  ./amalgum/amalgum/bio/dep/AMALGUM_bio_cornet.conllu
  ./amalgum/amalgum/voyage/dep/AMALGUM_voyage_noon.conllu
  ./amalgum/amalgum/bio/dep/AMALGUM_bio_fraunce.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_tattoo.conllu
  ./amalgum/amalgum/interview/dep/AMALGUM_interview_kate.conllu
  ./amalgum/amalgum/reddit/AMALGUM_reddit_guardian.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_cancer.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_scottish.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_debris.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_arrivederci.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_toilet.conllu
  ./amalgum/amalgum/fiction/dep/AMALGUM_fiction_bartell.conllu
  ./amalgum/amalgum/reddit/AMALGUM_reddit_beatty.conllu
  ./amalgum/amalgum/reddit/AMALGUM_reddit_returning.conllu
  ./amalgum/amalgum/reddit/AMALGUM_reddit_whos.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_computer.conllu
  ./amalgum/amalgum/reddit/AMALGUM_reddit_diarrhea.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_spielberg.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_fourth.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_veterinary.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_cooperatives.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_ajax.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_waiter.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_curling.conllu
  ./amalgum/amalgum/whow/dep/AMALGUM_whow_technique.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_relay.conllu
  ./amalgum/amalgum/news/dep/AMALGUM_news_stingray.conllu
  ./amalgum/amalgum/academic/dep/AMALGUM_academic_enterococcus.conllu
  ./amalgum/amalgum/fiction/dep/AMALGUM_fiction_madeline.conllu"))]

    (cxc/ingest-conllu-files xtdb-node files))

  )

(comment
  ;; tokens
  (require '[conllu-rest.server.tokens :as tok :refer [xtdb-token-node create-token]])
  (create-token xtdb-token-node {:name "Luke" :email "lukegessler@gmail.com" :quality :gold})

  (tok/list-tokens tok/xtdb-token-node)

  )

