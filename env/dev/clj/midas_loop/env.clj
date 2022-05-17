(ns midas-loop.env
  (:require [clojure.tools.logging :as log]
            [midas-loop.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[midas-loop started successfully using the development profile]=-"))
   :stop
   (fn []
     (log/info "\n-=[midas-loop has shut down successfully]=-"))
   :middleware wrap-dev})
