(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [clojure.tools.deps :as t]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.github.marksto/clj-tg-bot-api)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")

(defn- run-tests [test-alias]
  (println (format "\nRunning %s tests..." (name test-alias)))
  (let [basis (b/create-basis {:aliases [test-alias :test/runner]})
        combined (t/combine-aliases basis [test-alias :test/runner])
        extra-paths (:extra-paths combined)
        test-dir (or (when-some [test-dir (first extra-paths)]
                       (when (str/starts-with? test-dir "test/")
                         test-dir))
                     (throw (ex-info "No test path in alias" {:alias test-alias
                                                              :paths extra-paths})))
        cmds (b/java-command
               {:basis     basis
                :java-opts (:jvm-opts combined)
                :main      'clojure.main
                :main-args ["-m" "cognitect.test-runner" test-dir]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit)
      (throw (ex-info (format "Tests failed (with alias %s)" test-alias) {})))))

(defn test "Run all the tests." [opts]
  (println "\nRunning tests...")
  (run-tests :test/unit)
  (run-tests :test/integration)
  opts)

(defn- pom-template [version]
  [[:description "The latest Telegram Bot API specification and client lib for Clojure-based apps."]
   [:url "https://github.com/marksto/clj-tg-bot-api"]
   [:licenses
    [:license
     [:name "Eclipse Public License"]
     [:url "http://www.eclipse.org/legal/epl-v10.html"]]]
   [:developers
    [:developer
     [:name "Mark Sto"]]]
   [:scm
    [:url "https://github.com/marksto/clj-tg-bot-api"]
    [:connection "scm:git:https://github.com/marksto/clj-tg-bot-api.git"]
    [:developerConnection "scm:git:ssh:git@github.com:marksto/clj-tg-bot-api.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (assoc opts
    :lib lib :version version
    :jar-file (format "target/%s-%s.jar" lib version)
    :basis (b/create-basis {})
    :class-dir class-dir
    :target "target"
    :src-dirs ["src"]
    :pom-data (pom-template version)))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (test opts)
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR...")
    (b/jar opts))
  opts)

(defn install "Install the JAR locally." [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file  (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
