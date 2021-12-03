(ns conllu-rest.routes.conllu
  (:require [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.spec :as spec-coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.multipart :as multipart]
            [ring.util.http-response :refer :all]
            [clojure.java.io :as io]
            [conllu-rest.common :refer [error-response]]
            [conllu-rest.util.conllu :refer [parse-conllu-string]]
            [conllu-rest.server.xtdb :refer [xtdb-node]]
            [conllu-rest.xtdb.creation :refer [create-document build-document]]
            [conllu-rest.xtdb.easy :as cxe]
            [xtdb.api :as xt]))

(def conllu-routes
  ["/conllu"
   {:swagger {:tags ["conllu"]}}

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

    ["/download"
     {:get {:summary "downloads a file"
            :swagger {:produces ["image/png"]}
            :handler (fn [_]
                       {:status  200
                        :headers {"Content-Type" "image/png"}
                        :body    (-> "public/img/warning_clojure.png"
                                     (io/resource)
                                     (io/input-stream))})}}]]])
