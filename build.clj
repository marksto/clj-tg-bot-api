(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [clojure.tools.deps :as t]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.github.marksto/clj-tg-bot-api)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")

(defn- read-deps-edn []
  (edn/read-string (slurp (io/file "deps.edn"))))

(defn supported-http-client-aliases []
  (filter #(= "http" (namespace %)) (keys (:aliases (read-deps-edn)))))

(defn- get-test-dir
  [test-alias combined-aliases]
  (let [extra-paths (:extra-paths combined-aliases)]
    (or (when-some [test-dir (first extra-paths)]
          (when (str/starts-with? test-dir "test/")
            test-dir))
        (throw (ex-info "No test path in alias" {:alias test-alias
                                                 :paths extra-paths})))))

(defn- run-tests
  [test-alias & extra-aliases]
  (let [all-aliases (into [test-alias :test/runner] extra-aliases)
        basis (b/create-basis {:aliases all-aliases})
        combined (t/combine-aliases basis all-aliases)
        test-dir (get-test-dir test-alias combined)
        command (b/java-command
                  {:basis     basis
                   :java-opts (:jvm-opts combined)
                   :main      'clojure.main
                   :main-args ["-m" "cognitect.test-runner" "-d" test-dir]})
        {:keys [exit]} (b/process command)]
    (when-not (zero? exit)
      (throw (ex-info (format "Tests failed (with alias %s)" test-alias) {})))))

(defn test "Run all the tests." [opts]
  (println "\nRunning tests...")
  (run-tests :test/unit)
  (doseq [http-client-alias (supported-http-client-aliases)
          :let [http-client (name http-client-alias)]]
    (println (format "\nTesting with '%s' HTTP client..." http-client))
    (run-tests :test/integration http-client-alias))
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
