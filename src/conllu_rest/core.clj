(ns conllu-rest.core
  (:require [conllu-rest.server.handler :as handler]
            [conllu-rest.server.config :refer [env]]
            [conllu-rest.util.nrepl :as nrepl]
            [luminus.http-server :as http]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [mount.core :as mount]
            [cli-matic.core :refer [run-cmd*]]
            [cli-matic.utils-v2 :as U2]
            [cli-matic.utils :as U]
            [cli-matic.help-gen :as H]
            [cli-matic.platform :as P])
  (:gen-class))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (log/error {:what      :uncaught-exception
                  :exception ex
                  :where     (str "Uncaught exception on" (.getName thread))}))))

(mount/defstate ^{:on-reload :noop} http-server
  :start
  (http/start
    (-> env
        (update :io-threads #(or % (* 2 (.availableProcessors (Runtime/getRuntime)))))
        (assoc :handler (handler/app))
        (update :port #(or (-> env :options :port) %))
        (select-keys [:handler :host :port])))
  :stop
  (http/stop http-server))

(mount/defstate ^{:on-reload :noop} repl-server
  :start
  (when (env :nrepl-port)
    (nrepl/start {:bind (env :nrepl-bind)
                  :port (env :nrepl-port)}))
  :stop
  (when repl-server
    (nrepl/stop repl-server)))

(defn stop-app []
  (doseq [component (:stopped (mount/stop))]
    (log/info component "stopped"))
  (shutdown-agents))

(def cli-config
  {:app         {:command     "conllu-rest"
                 :description "https://github.com/lgessler/conllu-rest"
                 :version     "0.0.1"}
   :global-opts []
   :commands    [{:command     "run" :short "r"
                  :description ["Start the web app and begin listening for requests."]
                  :opts        [{:option "port" :short "p" :as "port for HTTP server" :type :int}]
                  :runs        mount/start-with-args
                  :on-shutdown stop-app}]})


(defn run-cmd
  "like cli-matic's run-cmd, but doesn't exit at the end if the command is 'run'"
  [args supplied-config]
  (let [config (U2/cfg-v2 supplied-config)
        {:keys [help stderr subcmd retval] :as result} (run-cmd* config args)]

    ; prints the error message, if present
    (when (seq stderr)
      (U/printErr ["** ERROR: **" stderr "" ""]))

    ; prints help
    (cond
      (= :HELP-GLOBAL help)
      (let [helpFn (H/getGlobalHelperFn config subcmd)]
        (U/printErr (helpFn config subcmd)))

      (= :HELP-SUBCMD help)
      (let [helpFn (H/getSubcommandHelperFn config subcmd)]
        (U/printErr (helpFn config subcmd))))

    ;; For some reason, the run subcommand exits immediately when combined with cli-matic. Use this as a workaround.
    (if (#{"run" "r"} (first args))
      (log/info "Started server successfully")
      (P/exit-script retval))))

(defn start-app [args]
  (run-cmd args cli-config))

(defn -main [& args]
  (start-app args))
