(ns conllu-rest.routes.conllu
  (:require [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.spec :as spec-coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.parameters :as parameters]
            [conllu-rest.middleware.formats :as formats]
            [ring.util.http-response :refer :all]
            [clojure.java.io :as io]
            [conllu-rest.common :refer [error-response]]
            [conllu-rest.conllu :refer [parse-conllu-string-with-python parse-conllu-string]]
            [conllu-rest.xtdb :refer [xtdb-node]]
            [conllu-rest.xtdb.creation :refer [create-document build-document]]
            [conllu-rest.xtdb.easy :as cxe]))

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
                                 (try (parse-conllu-string-with-python (slurp (:tempfile file)))
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



(comment

  (defn process-file [filepath]
    (let [parsed (->> filepath
                      slurp
                      parse-conllu-string)
          retval (build-document parsed)]
      (println "Processed" (-> parsed first :metadata (get "newdoc id")))
      retval))

  (doseq [genre ["bio"] #_["fiction" "bio" "news" "academic" "interview" "voyage" "whow"]]
    (let [path (str "amalgum/amalgum/" genre "/dep")
          filenames (seq (.list (clojure.java.io/file path)))
          filepaths (map #(str path "/" %) filenames)
          docnum (atom 0)]

      (println "Processing" genre)
      (doseq [fp-chunks (partition 1 filepaths)]
        (println (str "Beginning doc chunk " @docnum))
        (cxe/submit! xtdb-node (reduce into (map process-file fp-chunks)))
        (swap! docnum inc))

      ))

  )


