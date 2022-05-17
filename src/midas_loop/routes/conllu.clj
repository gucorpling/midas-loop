(ns midas-loop.routes.conllu
  (:require [clojure.java.io :as io]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.multipart :as multipart]
            [ring.util.http-response :refer :all]
            [xtdb.api :as xt]
            [clojure.tools.logging :as log]
            [midas-loop.common :refer [error-response nyi-response]]
            [midas-loop.server.config :refer [env]]
            [midas-loop.server.xtdb :refer [xtdb-node]]
            [midas-loop.server.tokens :refer [wrap-token-auth]]
            [midas-loop.conllu-parser :refer [parse-conllu-string]]
            [midas-loop.xtdb.creation :refer [create-document build-document]]
            [midas-loop.xtdb.easy :as cxe]
            [midas-loop.routes.conllu.document :refer [document-routes]]
            [midas-loop.routes.conllu.sentence :refer [sentence-routes]]
            [midas-loop.routes.conllu.conllu-metadata :refer [conllu-metadata-routes]]
            [midas-loop.routes.conllu.token :refer [token-routes atomic-routes associative-routes dep-routes]]
            [midas-loop.common :as common]
            [midas-loop.xtdb.serialization :refer [serialize-document]]))


(defn conllu-routes []
  (when (:dev env)
    (log/warn (str "\n\nAUTHENTICATION IS DISABLED, and ANYONE CAN CHANGE YOUR DATA -- :dev is true in config.edn\n"
                   "If you are running in production, STOP the server and edit your config to have `:dev false`\n"))
    (Thread/sleep 2000))
  ["/conllu"
   {:swagger    {:tags ["conllu"]}
    :middleware (if (:dev env) [] [wrap-token-auth])}

   (document-routes)
   (sentence-routes)
   (conllu-metadata-routes)
   (token-routes)
   (atomic-routes "form")
   (atomic-routes "lemma")
   (atomic-routes "upos")
   (atomic-routes "xpos")
   (associative-routes "feats")
   (dep-routes)
   (associative-routes "misc")

   ["/files"
    ["/upload"
     {:post {:summary    "upload a file"
             :parameters {:multipart {:file multipart/temp-file-part}}
             :responses  {200 {:body {:name string?, :size int?}}}
             :handler    (fn [{{{:keys [file]} :multipart} :parameters}]
                           (let [sentences
                                 (try (parse-conllu-string (slurp (:tempfile file)))
                                      (catch Exception e {:status 500 :title "Error parsing CoNLL-U file"}))]

                             (if (= (:status sentences) 500)
                               sentences
                               (let [result (create-document xtdb-node sentences)]
                                 {:status 200
                                  :body   {:name (:filename file)
                                           :size (:size file)}}))))}}]

    ["/download/:id"
     {:get {:summary    "Downloads a file as .conllu"
            :swagger    {:produces ["text/x-conllu"]}
            :parameters {:path {:id uuid?}}
            :handler    (fn [{:keys [path-params node]}]
                          (let [id (:id path-params)]
                            (if-let [uuid (common/parse-uuid id)]
                              (let [result (cxe/entity node uuid)]
                                (if (nil? result)
                                  (not-found)
                                  {:status  200
                                   :headers {"Content-Type"        "application/x-conllu"
                                             "Content-Disposition" (str "attachment; filename=" id ".conllu")}
                                   :body    (serialize-document node uuid)}))
                              (bad-request "ID must be a valid java.util.UUID"))))}}]]])
