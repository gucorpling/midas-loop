(ns midas-loop.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[midas-loop started successfully]=-"))
   :stop
   (fn []
     (log/info "\n-=[midas-loop has shut down successfully]=-"))
   :middleware identity})
