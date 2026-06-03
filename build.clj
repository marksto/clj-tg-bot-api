(ns build
  (:refer-clojure :exclude [test])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.tools.build.api :as b]
   [clojure.tools.deps :as t]
   [deps-deploy.deps-deploy :as dd]))

(def version (format "1.2.%s" (b/git-count-revs nil)))

(def modules
  [{:id 'core
    :dir "modules/core"
    :lib 'com.github.marksto/clj-tg-bot-api
    :description "The latest Telegram Bot API specification and client lib for Clojure-based apps."}
   {:id 'updates
    :dir "modules/updates"
    :lib 'com.github.marksto/clj-tg-bot-api.updates
    :description "Optional tooling for receiving Telegram Bot API updates with `clj-tg-bot-api`."}])

(def module-by-id
  (into {} (map (juxt :id identity)) modules))

(defn- selected-modules [{:keys [module]}]
  (if (nil? module)
    modules ; no module specified => all modules, in order
    (or (some-> (module-by-id module) (vector))
        (throw (ex-info "Unknown module" {:module module})))))

(def class-dir "target/classes")

(defn- read-deps-edn []
  (edn/read-string (slurp (io/file "deps.edn"))))

(defn supported-http-client-aliases []
  (filter #(= (name :http) (namespace %)) (keys (:aliases (read-deps-edn)))))

(defn- get-extra-paths
  [test-alias {:keys [extra-paths] :as _combined-aliases}]
  (or extra-paths
      (throw (ex-info "No test path in alias" {:alias test-alias
                                               :paths extra-paths}))))

(defn- run-tests
  [test-alias & extra-aliases]
  (let [all-aliases (into [test-alias :test/runner] extra-aliases)
        basis (b/create-basis {:aliases all-aliases})
        combined (t/combine-aliases basis all-aliases)
        dir-args (mapcat #(do ["-d" %]) (get-extra-paths test-alias combined))
        command (b/java-command
                  {:basis     basis
                   :java-opts (:jvm-opts combined)
                   :main      'clojure.main
                   :main-args (into ["-m" "cognitect.test-runner"] dir-args)})
        {:keys [exit]} (b/process command)]
    (when-not (zero? exit)
      (throw (ex-info (format "Tests failed (with alias %s)" test-alias) {})))))

(defn test "Run all the tests." [{:keys [test-type] :as opts}]
  (println "\nRunning tests...")
  (when (or (nil? test-type) (= :unit test-type))
    (run-tests :test/unit))
  (when (or (nil? test-type) (= :integration test-type))
    (doseq [http-client-alias (supported-http-client-aliases)
            :let [http-client (name http-client-alias)]]
      (println (format "\nTesting with '%s' HTTP client..." http-client))
      (run-tests :test/integration http-client-alias)))
  opts)

(defn- pom-template
  [version description]
  [[:description description]
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

(defn- basis []
  (let [module-overrides (reduce (fn [deps {:keys [lib]}]
                                   (assoc deps lib {:mvn/version version}))
                                 {}
                                 modules)]
    (b/create-basis
      {:aliases [:mvn/overrides]
       :extra   {:aliases {:mvn/overrides {:override-deps module-overrides}}}})))

(defn- jar-opts
  [opts {:keys [lib description] :as _module}]
  (assoc opts
    :lib lib :version version
    :jar-file (format "target/%s-%s.jar" lib version)
    :basis (basis)
    :class-dir class-dir
    :target "target"
    :src-dirs ["src"]
    :pom-data (pom-template version description)))

(defn- jar-module [opts module]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts module)]
    (println (format "\nBuilding '%s'..." (:lib module)))
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR...")
    (b/jar opts)))

(defn jar "Build JAR file(s)." [opts]
  (doseq [module (selected-modules opts)]
    (b/with-project-root (:dir module)
      (jar-module opts module)))
  opts)

(defn- install-module [opts module]
  (let [opts (jar-opts opts module)]
    (b/install opts)))

(defn install "Install JAR file(s) locally." [opts]
  (doseq [module (selected-modules opts)]
    (b/with-project-root (:dir module)
      (install-module opts module)))
  opts)

(defn- deploy-module [opts module]
  (let [{:keys [jar-file] :as opts} (jar-opts opts module)]
    (dd/deploy {:installer :remote
                :artifact  (b/resolve-path jar-file)
                :pom-file  (b/pom-path (select-keys opts [:lib :class-dir]))})))

(defn deploy "Deploy JAR file(s) to Clojars." [opts]
  (doseq [module (selected-modules opts)]
    (b/with-project-root (:dir module)
      (deploy-module opts module)))
  opts)
