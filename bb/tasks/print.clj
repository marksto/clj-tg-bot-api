(ns tasks.print
  (:require
   [babashka.tasks :as bt]))

(defn print-public-task [k]
  (let [{:keys [:private :name]} (bt/current-task)]
    (when-not private
      (println (case k :enter "☐" "✓") name))))
