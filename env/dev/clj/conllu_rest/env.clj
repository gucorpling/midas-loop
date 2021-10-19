(ns conllu-rest.env
  (:require [clojure.tools.logging :as log]
            [conllu-rest.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[conllu-rest started successfully using the development profile]=-"))
   :stop
   (fn []
     (log/info "\n-=[conllu-rest has shut down successfully]=-"))
   :middleware wrap-dev})
