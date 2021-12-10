(ns conllu-rest.core
  (:require [conllu-rest.server.config :refer [env]]
            [conllu-rest.server.xtdb :refer [xtdb-node]]
            [conllu-rest.xtdb.creation :refer [ingest-conllu-files]]
            [conllu-rest.server.http]
            [conllu-rest.server.repl]
            [clojure.tools.logging :as log]
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

(defn stop-app []
  (doseq [component (:stopped (mount/stop))]
    (log/info component "stopped"))
  (shutdown-agents))

(defn ingest [args]
  (mount/start-with-args args)
  (log/info (:filepaths args))
  (ingest-conllu-files xtdb-node (:filepaths args))
  (log/info (str "Successfully ingested " (count (:filepaths args)) " documents:"))
  (println "\nBegin document manifest:\n")
  (doseq [name (:filepaths args)]
    (println (str "\t- " name)))
  (println "\nEnd document manifest.\n"))

(def cli-config
  {:app         {:command     "conllu-rest"
                 :description "https://github.com/lgessler/conllu-rest"
                 :version     "0.0.1"}
   :global-opts []
   :commands    [
                 ;; main method--run the HTTP server
                 {:command     "run"
                  :short       "r"
                  :description ["Start the web app and begin listening for requests."]
                  :opts        [{:option "port" :short "p" :as "port for HTTP server" :type :int}]
                  :runs        mount/start-with-args
                  :on-shutdown stop-app}

                 ;; read in conllu files
                 {:command     "ingest"
                  :short       "i"
                  :description ["Read and ingest CoNLL-U files."
                                ""
                                "NOTE: you should only run this command while your server is shut down."]
                  :opts        [{:option   "filepaths"
                                 :short    0
                                 :as       "paths to CoNLL-U files to ingest"
                                 :type     :string
                                 :multiple true}]
                  :runs        ingest
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
    (log/info result)
    (if (and (#{"run" "r"} (first args)) (= retval 0))
      (log/info "Started server successfully")
      (P/exit-script retval))))

(defn start-app [args]
  (run-cmd args cli-config))

(defn -main [& args]
  (start-app args))
