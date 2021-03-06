(defproject midas-loop "0.0.1"

  :description "Midas Loop"
  :url "http://github.com/gucorpling/midas-loop"

  :dependencies [[ch.qos.logback/logback-classic "1.2.5"]
                 [clojure.java-time "0.3.3"]
                 [cprop "0.1.19"]
                 [expound "0.8.9"]
                 [funcool/struct "1.4.0"]
                 [json-html "0.4.7"]
                 [luminus-transit "0.1.2"]
                 [luminus-undertow "0.1.11"]
                 [luminus/ring-ttl-session "0.3.3"]
                 [markdown-clj "1.10.6"]
                 [metosin/muuntaja "0.6.8"]
                 [metosin/reitit "0.5.15"]
                 [metosin/ring-http-response "0.9.3"]
                 [mount "0.1.16"]
                 [nrepl "0.8.3"]
                 [org.clojure/clojure "1.11.0"]
                 [cli-matic "0.4.3"]
                 [org.clojure/tools.logging "1.1.0"]
                 [org.webjars.npm/bulma "0.9.2"]
                 [org.webjars.npm/material-icons "1.0.0"]
                 [org.webjars/webjars-locator "0.41"]
                 [org.webjars/webjars-locator-jboss-vfs "0.1.0"]
                 [ring-webjars "0.2.0"]
                 [ring/ring-core "1.9.4"]
                 [ring/ring-defaults "0.3.3"]
                 [buddy/buddy-auth "3.0.1"]
                 [selmer "1.12.44"]
                 [jumblerg/ring-cors "2.0.0"]
                 [clj-http "3.12.3"]
                 [juji/editscript "0.5.8"]

                 [com.xtdb/xtdb-core "1.21.0"]
                 [com.xtdb/xtdb-lmdb "1.21.0"]
                 [com.xtdb/xtdb-rocksdb "1.21.0"]
                 [com.xtdb/xtdb-http-server "1.21.0"]]

  :min-lein-version "2.0.0"

  :source-paths ["src"]
  :test-paths ["test"]
  :resource-paths ["resources"]
  :target-path "target/%s/"
  :main ^:skip-aot midas-loop.core

  :plugins []

  :profiles
  {:uberjar       {:omit-source    true
                   :aot            :all
                   :uberjar-name   "midas-loop.jar"
                   :source-paths   ["env/prod/clj"]
                   :resource-paths ["env/prod/resources"]}

   :dev           [:project/dev :profiles/dev]
   :test          [:project/dev :project/test :profiles/test]

   :project/dev   {:jvm-opts       ["-Dconf=dev-config.edn"]
                   :dependencies   [[org.clojure/tools.namespace "1.1.0"]
                                    [pjstadig/humane-test-output "0.11.0"]
                                    [prone "2021-04-23"]
                                    [ring/ring-devel "1.9.4"]
                                    [ring/ring-mock "0.4.0"]]
                   :plugins        [[com.jakemccrary/lein-test-refresh "0.24.1"]
                                    [jonase/eastwood "0.3.5"]
                                    [cider/cider-nrepl "0.26.0"]]

                   :source-paths   ["env/dev/clj"]
                   :resource-paths ["env/dev/resources"]
                   :repl-options   {:init-ns user
                                    :timeout 120000}
                   :injections     [(require 'pjstadig.humane-test-output)
                                    (pjstadig.humane-test-output/activate!)]}
   :project/test  {:resource-paths ["env/test/resources"]}
   :profiles/dev  {}
   :profiles/test {}})
