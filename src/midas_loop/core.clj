(ns midas-loop.core
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [mount.core :as mount]
            [cli-matic.core :refer [run-cmd*]]
            [cli-matic.utils-v2 :as U2]
            [cli-matic.utils :as U]
            [cli-matic.help-gen :as H]
            [cli-matic.platform :as P]
            [midas-loop.server.xtdb :refer [xtdb-node]]
            [midas-loop.server.nlp :refer [agent-map]]
            [midas-loop.xtdb.creation :refer [ingest-conllu-files]]
            [midas-loop.xtdb.serialization :refer [serialize-document]]
            [midas-loop.server.http]
            [midas-loop.server.nlp]
            [midas-loop.server.repl]
            [midas-loop.server.tokens :as tok]
            [midas-loop.xtdb.easy :as cxe])
  (:refer-clojure :exclude [import])
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

(defn import [args]
  (binding [midas-loop.server.xtdb/*listen?* false]
    (mount/start-with-args args))
  (let [filepaths (->> args
                       :filepaths
                       (reduce (fn [filepaths x]
                                 (if (.isDirectory (io/file x))
                                   (into filepaths (->> (file-seq (io/file x))
                                                        (filter #(.. %
                                                                     (toPath)
                                                                     (getFileName)
                                                                     (toString)
                                                                     (endsWith ".conllu")))))
                                   (conj filepaths x)))
                               [])
                       sort)]
    (ingest-conllu-files xtdb-node agent-map filepaths)
    (log/info (str "Successfully imported " (count filepaths) " documents:"))
    (println "\nBegin document manifest:\n")
    (doseq [name filepaths]
      (println (str "\t- " name)))
    (println "\nEnd document manifest.\n")))

(defn export [args]
  (mount/start-with-args args)
  (log/info "Attempting export to " (:outpath args))
  (doseq [{:document/keys [id name]} (cxe/find-entities xtdb-node [[:document/id '_]])]
    (let [doc-str (serialize-document xtdb-node id)
          filename (str (:outpath args) "/" name ".conllu")]
      (io/make-parents filename)
      (spit filename doc-str)
      (println "Wrote " filename)))
  (println "Done with export."))

(defn add-token [{:keys [name email] :as args}]
  (mount/start-with-args args)
  (log/info (str "Attemping to add a token for user:\n\n\tName: " name "\n\tEmail: " email "\n"))
  (let [{:keys [secret]} (tok/create-token tok/xtdb-token-node args)]
    (log/info (str "Successfully created token:\n\n\t" secret "\n\nKeep this token SECRET."))))

(defn list-tokens [args]
  (mount/start-with-args args)
  (log/info "Existing tokens:\n")
  (let [records (tok/list-tokens tok/xtdb-token-node)]
    (binding [clojure.pprint/*print-miser-width* 80
              clojure.pprint/*print-right-margin* 100
              clojure.pprint/*print-pprint-dispatch* clojure.pprint/code-dispatch]
      (doseq [record records]
        (println (with-out-str (clojure.pprint/pprint record))))))
  (println))

(defn revoke-token [{:keys [secret] :as args}]
  (mount/start-with-args args)
  (println)
  (log/info (str "Attempting to revoke token " secret))
  (if (some? (tok/read-token tok/xtdb-token-node (keyword secret)))
    (do
      (tok/delete-token tok/xtdb-token-node (keyword secret))
      (log/info "Deletion successful."))
    (log/warn (str "Token does note exist: " secret)))
  (println))

(def cli-config
  {:app         {:command     "midas-loop"
                 :description "https://github.com/gucorpling/midas-loop"
                 :version     "0.0.1-rc1"}
   :global-opts []
   :commands    [;; main method--run the HTTP server
                 {:command     "run"
                  :short       "r"
                  :description ["Start the web app and begin listening for requests."]
                  :opts        [{:option "port" :short "p" :as "port for HTTP server" :type :int}]
                  :runs        mount/start-with-args
                  :on-shutdown stop-app}

                 ;; read in conllu files
                 {:command     "import"
                  :short       "i"
                  :description ["Read and ingest CoNLL-U files."
                                ""
                                "NOTE: you should only run this command while your server is shut down."]
                  :opts        [{:option   "filepaths"
                                 :short    0
                                 :as       "paths to CoNLL-U files to ingest, or a directory with CoNLL-U files"
                                 :type     :string
                                 :multiple true}]
                  :runs        import
                  :on-shutdown stop-app}

                 ;; export
                 {:command     "export"
                  :short       "e"
                  :description ["Export all documents in the database as CoNLL-U files."
                                ""
                                "NOTE: you should only run this command while your server is shut down."]
                  :opts        [{:option "outpath"
                                 :short  0
                                 :as     "Directory where the CoNLL-U files should be written"
                                 :type   :string}]
                  :runs        export
                  :on-shutdown stop-app}

                 {:command     "token"
                  :short       "t"
                  :description ["Token-related helpers."
                                ""
                                "NOTE: you should only run this command while your server is shut down."]
                  :opts        []
                  :subcommands [{:command     "add"
                                 :short       "a"
                                 :description "Mint a new token for a user"
                                 :opts        [{:option "name"
                                                :short  0
                                                :as     "User's name (human-friendly)"
                                                :type   :string}
                                               {:option "email"
                                                :short  1
                                                :as     "User's email"
                                                :type   :string}
                                               {:option "quality"
                                                :short  2
                                                :as     (str "Quality of annotaions produced by the token user. "
                                                             "Use silver for NLP tools.")
                                                :type   #{:gold :silver}}]
                                 :runs        add-token
                                 :on-shutdown stop-app}
                                {:command     "list"
                                 :short       "l"
                                 :description "List all valid tokens"
                                 :opts        []
                                 :runs        list-tokens
                                 :on-shutdown stop-app}
                                {:command     "revoke"
                                 :short       "r"
                                 :description "Remove a valid token"
                                 :opts        [{:option "secret"
                                                :short  0
                                                :as     "The token to be revoked"
                                                :type   :string}]
                                 :runs        revoke-token
                                 :on-shutdown stop-app}]}]})


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
