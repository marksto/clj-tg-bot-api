(ns user
  (:require [clojure.tools.namespace.repl :refer [set-refresh-dirs]]))

(println "Loaded root `user.clj` ns\n")

;; NB: No need to refresh the "development", "scripts" and "projects".
(set-refresh-dirs "src")

;; NB: We use `io.aviso/pretty` in dev to highlight important aspects
;;     of error stack traces using ANSI formatting.
;; https://github.com/AvisoNovate/pretty

;; NB: Optionally requiring snitch for a sane debugging experience.
;;     https://github.com/AbhinavOmprakash/snitch
(try (when-not (resolve 'clojure.core/defn*)
       (require '[snitch.core #_#_:refer [defn* defmethod* *fn *let]]))
     (catch Exception _))

;; NB: We use `hugoduncan/criterium` in dev to benchmark Clojure code.
;;     (require '[criterium.core :refer [quick-bench]])
;;     (quick-bench <project-fn-call>)
