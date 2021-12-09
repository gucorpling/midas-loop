(ns conllu-rest.server.tokens
  (:require [conllu-rest.server.xtdb :refer [start-standalone-xtdb-node]]
            [conllu-rest.server.config :refer [env]]
            [conllu-rest.xtdb.easy :as cxe]
            [ring.util.http-response :as resp]
            [mount.core :refer [defstate]]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.backends :as backends]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [clojure.tools.logging :as log])
  (:import (java.security SecureRandom)
           (java.util Base64)))

(defn start-token-node []
  (start-standalone-xtdb-node {:db-dir (-> env ::config :token-db-dir)}))

(defstate xtdb-token-node
  :start (let [node (start-token-node)]
           node)
  :stop (.close xtdb-token-node))


(def ^:private secure-random (SecureRandom.))
(def ^:private b64-encoder (.withoutPadding (Base64/getUrlEncoder)))
(defn- generate-token
  ([] (generate-token 32))
  ([bytes]
   (let [buffer (byte-array bytes)]
     (.nextBytes secure-random buffer)
     (.encodeToString b64-encoder buffer))))

(defn create-token [node {:keys [name email]}]
  (let [secret-string (str "secret=" (generate-token 48))
        record {:xt/id      (keyword secret-string)
                :secret     secret-string
                :user-name  name
                :user-email email}]
    (when (cxe/put node record)
      record)))

(defn delete-token [node id]
  (cxe/delete node id))

(defn read-token [node id]
  (cxe/entity node id))

(defstate buddy-backend
  :start (let [auth-fn (fn [request token]
                         (read-token xtdb-token-node (keyword token)))]
           (backends/token {:authfn auth-fn})))

(defn wrap-token-auth [handler]
  (let [auth-check (fn [handler]
                     (fn [request]
                       (if (authenticated? request)
                         (handler request)
                         (resp/unauthorized {:error "Unauthorized. Provide a valid token."}))))]
    (-> handler
        auth-check
        (wrap-authentication buddy-backend))))

(comment
  ;; Wrap the ring handler.
  (def app (-> my-handler
               (wrap-authentication backend)))

  )