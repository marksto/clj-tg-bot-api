(ns tasks.fs
  (:require
   [babashka.fs :as fs]))

(defn clj-exclusions []
  (->> [(fs/glob "." "target/**.clj")]
       (flatten)
       (map str)
       (set)))

(defn clj-files
  ([]
   (clj-files nil))
  ([{:keys [root file] :as _arg-map}]
   (let [root-dir (or root ".")
         pattern (cond (nil? file) "**.clj"
                       (= "clj" (fs/extension file)) file)]
     (some->> pattern
              (fs/glob root-dir)
              (map str)
              (remove (clj-exclusions))))))

(defn edn-exclusions []
  (->> [(fs/glob "." "target/**.edn")
        (fs/glob "." "test-resources/integration/**.edn")]
       (flatten)
       (map str)
       (set)))

(defn edn-files
  ([]
   (edn-files nil))
  ([{:keys [root file] :as _arg-map}]
   (let [root-dir (or root ".")
         pattern (cond (nil? file) "**.edn"
                       (= "edn" (fs/extension file)) file)]
     (some->> pattern
              (fs/glob root-dir)
              (map str)
              (remove (edn-exclusions))))))

(defn clj+edn-files
  ([]
   (clj+edn-files nil))
  ([arg-map]
   (concat (clj-files arg-map) (edn-files arg-map))))
