(ns conllu-rest.routes.conllu.sentence
  (:require [ring.util.http-response :refer :all]
            [conllu-rest.common :as common :refer [error-response nyi-response]]
            [conllu-rest.routes.conllu.common :as cc]
            [conllu-rest.xtdb.queries.sentence :as cxqs]
            [conllu-rest.xtdb.easy :as cxe]))

(defn split-sentence [{:keys [body-params node] :as request}]
  (let [token-id (:token-id body-params)]
    (if-let [token-id (common/parse-uuid token-id)]
      (let [{:keys [status msg new-sentence-id]} (cxqs/split-sentence node token-id)]
        (if (= status :ok)
          (ok {:new-sentence-id new-sentence-id})
          (bad-request msg)))
      (bad-request "Token ID must be a valid java.util.UUID"))))

(defn delete-sentence [{:keys [body-params node] :as request}]
  (let [sentence-id (:sentence-id body-params)]
    (if-let [sentence-id (common/parse-uuid sentence-id)]
      (let [{:keys [status msg]} (cxqs/delete node sentence-id)]
        (if (= status :ok)
          (ok)
          (bad-request msg)))
      (bad-request "Token ID must be a valid java.util.UUID"))))

(defn merge-sentence-right [{:keys [body-params node] :as request}]
  (let [sentence-id (:sentence-id body-params)]
    (if-let [sentence-id (common/parse-uuid sentence-id)]
      (let [{:keys [status msg]} (cxqs/merge-sentence-right node sentence-id)]
        (if (= status :ok)
          (ok)
          (bad-request msg)))
      (bad-request "Token ID must be a valid java.util.UUID"))))

(defn merge-sentence-left [{:keys [body-params node] :as request}]
  (let [sentence-id (:sentence-id body-params)]
    (if-let [sentence-id (common/parse-uuid sentence-id)]
      (let [{:keys [status msg]} (cxqs/merge-sentence-left node sentence-id)]
        (if (= status :ok)
          (ok)
          (bad-request msg)))
      (bad-request "Token ID must be a valid java.util.UUID"))))

(defn sentence-routes []
  ["/sentence"
   ["/id/:id"
    {:get {:summary    "Produce JSON representation of a sentence"
           :parameters {:path {:id uuid?}}
           :handler    cc/get-handler}}]
   ["/delete/:id"
    {:post {:summary    "Delete a sentence and all its contents"
            :parameters {:body {:id uuid?}}
            :handler    delete-sentence}}]
   ["/split"
    {:post {:summary     "Split a sentence at a given token ID"
            :description (str "Split a sentence at a given token ID, yielding a new sentence with"
                              " all tokens following the target token ID, inclusive. Will reject"
                              " with a 400 if the token is already at the beginning of a split, or"
                              " if the token, its sentence, or its document do not exist.")
            :parameters  {:body {:token-id uuid?}}
            :handler     split-sentence}}]
   ["/merge-right"
    {:post {:summary     "Merge a sentence with an existing sentence that follows it"
            :description (str "Merge a sentence identified to a sentence in the same document"
                              " to its right. \"right\" here means \"following\"--RTL scripts"
                              " do not produce a different behavior. Will reject with a 400"
                              " if the sentence or its document do not exist, or if the sentence"
                              " is already at the end of its document.")
            :parameters  {:body {:sentence-id uuid?}}
            :handler     merge-sentence-right}}]
   ["/merge-left"
    {:post {:summary    "Like merge-right, but to the left."
            :parameters {:body {:sentence-id uuid?}}
            :handler    merge-sentence-left}}]])
