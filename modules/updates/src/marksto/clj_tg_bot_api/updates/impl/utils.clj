;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.updates.impl.utils
  "A set of necessary general-purpose utilities"
  {:author "Mark Sto (@marksto)"}
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [diehard.rate-limiter :as dh.rl])
  (:import
   (clojure.lang IDeref IFn)
   (java.io File)
   (java.lang Thread$Builder Thread$Builder$OfVirtual)
   (java.net URI URL)
   (java.util.concurrent.atomic AtomicBoolean)))

(set! *warn-on-reflection* true)

;; collections

(defn update*
  ([m k f]
   (if (contains? m k)
     (update m k f)
     m))
  ([m k f x]
   (if (contains? m k)
     (update m k f x)
     m))
  ([m k f x y]
   (if (contains? m k)
     (update m k f x y)
     m))
  ([m k f x y z]
   (if (contains? m k)
     (update m k f x y z)
     m))
  ([m k f x y z more]
   (if (contains? m k)
     (apply update m k f x y z more)
     m)))

(defn nested-merge
  [& maps]
  (if (map? (first maps))
    (apply merge-with nested-merge (first maps) (rest maps))
    (apply merge-with nested-merge (rest maps))))

;; resources

(defn url-scheme ^String [^URL n]
  (when n (URI/.getScheme (URL/.toURI n))))

(defn inside-jar? [^URL n]
  (= "jar" (url-scheme n)))

(defn- default-class-loader []
  (Thread/.getContextClassLoader (Thread/currentThread)))

(defn copy-resource!
  (^File [^String res-name]
   (let [temp-file (fs/file (fs/create-temp-file))]
     (copy-resource! res-name temp-file)))
  (^File [^String res-name file]
   (copy-resource! res-name (default-class-loader) file))
  (^File [^String res-name ^ClassLoader loader file]
   (when-some [url (io/resource res-name loader)]
     (with-open [is (io/input-stream url)]
       (io/copy is file))
     file)))

;; concurrency

(defn sleep-uninterruptibly
  [^long ms]
  (dh.rl/uninterruptible-sleep ms))

(defn- thread-builder:set-name
  [tb {:keys [name count-start] :as _opts}]
  (if (some? name)
    (if (some? count-start)
      (Thread$Builder/.name tb name count-start)
      (Thread$Builder/.name tb name))
    tb))

(defn- ->thread-builder:virtual
  ^Thread$Builder [opts]
  (let [vtb (Thread/ofVirtual)]
    (if-some [{:keys [inherit-itl? ueh-fn]} (not-empty opts)]
      (cond-> (thread-builder:set-name vtb opts)
        (boolean? inherit-itl?)
        (Thread$Builder$OfVirtual/.inheritInheritableThreadLocals ^boolean inherit-itl?)
        (some? ueh-fn)
        (Thread$Builder$OfVirtual/.uncaughtExceptionHandler ^Thread$UncaughtExceptionHandler ueh-fn))
      vtb)))

(defn ->vthread!
  ^Thread [^Runnable f & {:as opts}]
  (Thread$Builder$OfVirtual/.start (->thread-builder:virtual opts) f))

(defn flag
  ([]
   (flag false))
  ([init-val]
   (let [*a-bool (AtomicBoolean. (boolean init-val))]
     (reify
       IDeref
       (deref [_]
         (AtomicBoolean/.get *a-bool))
       IFn
       (invoke [_ new-val]
         (AtomicBoolean/.set *a-bool (boolean new-val)))
       (invoke [_ exp-val new-val]
         (AtomicBoolean/.compareAndSet *a-bool (boolean exp-val) (boolean new-val)))
       Object
       (toString [_]
         (str "flag<" *a-bool ">"))))))
