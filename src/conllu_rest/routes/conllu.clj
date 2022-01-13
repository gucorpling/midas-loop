(ns conllu-rest.routes.conllu
  (:require [clojure.java.io :as io]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.multipart :as multipart]
            [ring.util.http-response :refer :all]
            [xtdb.api :as xt]
            [clojure.tools.logging :as log]
            [conllu-rest.common :refer [error-response nyi-response]]
            [conllu-rest.server.config :refer [env]]
            [conllu-rest.server.xtdb :refer [xtdb-node]]
            [conllu-rest.server.tokens :refer [wrap-token-auth]]
            [conllu-rest.conllu-parser :refer [parse-conllu-string]]
            [conllu-rest.xtdb.creation :refer [create-document build-document]]
            [conllu-rest.xtdb.easy :as cxe]
            [conllu-rest.routes.conllu.document :refer [document-routes]]
            [conllu-rest.routes.conllu.sentence :refer [sentence-routes]]
            [conllu-rest.routes.conllu.conllu-metadata :refer [conllu-metadata-routes]]
            [conllu-rest.routes.conllu.token :refer [token-routes]]
            [conllu-rest.common :as common]
            [conllu-rest.xtdb.serialization :refer [serialize-document]]))


(defn conllu-routes []
  (let [prod? (:prod env)]
    (when-not prod?
      (log/warn ":prod is not true in config.edn, so authentication is DISABLED"))
    ["/conllu"
     {:swagger    {:tags ["conllu"]}
      :middleware (if prod? [wrap-token-auth] [])}

     (document-routes)
     (sentence-routes)
     (conllu-metadata-routes)
     (token-routes)

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
                                (bad-request "ID must be a valid java.util.UUID"))))}}]]]))
