(ns conllu-rest.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[conllu-rest started successfully]=-"))
   :stop
   (fn []
     (log/info "\n-=[conllu-rest has shut down successfully]=-"))
   :middleware identity})
