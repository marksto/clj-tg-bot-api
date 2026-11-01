;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.impl.utils
  "A set of necessary general-purpose utilities"
  {:author "Mark Sto (@marksto)"}
  (:require
   [clojure.core.cache :as cache :refer [defcache]]
   [clojure.set :as set]
   [loom.alg :as la]
   [loom.graph :refer [add-edges add-nodes digraph has-edge? nodes]])
  (:import
   (clojure.lang IDeref)
   (java.util.concurrent Future)))

(set! *warn-on-reflection* true)

;; collections

(def keyset (comp set keys))

(defn map-keys
  ([vf coll]
   (map-keys {} vf coll))
  ([init-map vf coll]
   (if (seq coll)
     (->> coll
          (reduce #(assoc! %1 %2 (vf %2))
                  (transient init-map))
          (persistent!))
     init-map)))

(defn index-by
  ([key-fn coll]
   (index-by {} key-fn coll))
  ([init-map kf coll]
   (if (seq coll)
     (->> coll
          (reduce #(assoc! %1 (kf %2) %2)
                  (transient init-map))
          (persistent!))
     init-map)))

;; NB: Credits to the 'https://stackoverflow.com/a/26059795'.
(let [sentinel ::absent]
  (defn contains-in?
    [m ks]
    (not (identical? sentinel (get-in m ks sentinel)))))

(defn update-in*
  ([m ks f]
   (update-in* m ks f nil))
  ([m ks f args]
   (if (contains-in? m ks)
     (if (nil? args)
       (update-in m ks f)
       (apply update-in m ks f args))
     m)))

(defn update-kvs
  [m fkv]
  (if (nil? m)
    {}
    (->> m
         (reduce-kv (fn [m k v]
                      (let [[k' v'] (fkv k v)]
                        (assoc! m k' v')))
                    (transient {}))
         (persistent!))))

(defn filter-keys
  [m pred]
  (some->> m
           (reduce-kv (fn [m k v]
                        (if (pred k) (assoc! m k v) m))
                      (transient {}))
           (persistent!)))

(defn remove-vals
  [m pred]
  (some->> m
           (reduce-kv (fn [m k v]
                        (if-not (pred v) (assoc! m k v) m))
                      (transient {}))
           (persistent!)))

(defn reverse-merge
  [& maps]
  (when (some identity maps)
    (reduce #(conj (or %2 {}) %1) maps)))

;; functions

(defn apply-if-fn
  ([fn-or-val]
   (if (fn? fn-or-val)
     (fn-or-val)
     fn-or-val))
  ([fn-or-val x]
   (if (fn? fn-or-val)
     (fn-or-val x)
     fn-or-val))
  ([fn-or-val x y]
   (if (fn? fn-or-val)
     (fn-or-val x y)
     fn-or-val))
  ([fn-or-val x y z]
   (if (fn? fn-or-val)
     (fn-or-val x y z)
     fn-or-val))
  ([fn-or-val x y z & args]
   (if (fn? fn-or-val)
     (apply fn-or-val x y z args)
     fn-or-val)))

;; references

(defn derefable?
  [obj]
  (or (instance? IDeref obj)
      (instance? Future obj)))

(defn force-ref
  [val]
  (if (derefable? val)
    (deref val)
    val))

;; exceptions

(defn clear-stack-trace
  [^Throwable t]
  (.setStackTrace t (into-array StackTraceElement [])))

;; dynaload

(defn requiring-resolve*
  [sym]
  (try
    (requiring-resolve sym)
    (catch Exception _ nil)))

;; graphs

(defn map->graph
  [m]
  (digraph m))

(defn cycled-nodes
  [g]
  (->> (la/scc g)
       (filter #(or (< 1 (count %))
                    (has-edge? g (first %) (first %))))
       (map set)
       (apply set/union)))

(defn topsort-with-cycles
  [g]
  (let [components (la/scc g)
        node->comp (into {} (for [component components, node component]
                              [node component]))
        ;; leaving only inter-component edges
        cross-edges (remove (fn [[node-a node-b]]
                              (= (node->comp node-a) (node->comp node-b)))
                            (la/distinct-edges g))
        ;; build subDAG on the original nodes
        g-no-edges (reduce add-nodes (digraph) (nodes g))
        acyclic-sg (apply add-edges g-no-edges cross-edges)]
    (la/topsort acyclic-sg)))

;; caches

;; NB: A time-to-idle (TTI) cache evicts the entries that have not been used,
;;     i.e. put via `miss` or looked up via `hit`, for a given time. The TTL
;;     cache of the `clojure.core.cache` evicts them that long after they've
;;     been put, even if they are still in use.
(defcache TTICache [cache used-at tti swept-at]
  cache/CacheProtocol
  (lookup [_ item]
    (get cache item))
  (lookup [_ item not-found]
    (get cache item not-found))
  (has? [_ item]
    (contains? cache item))
  (hit [this item]
    (if (contains? cache item)
      (TTICache. cache (assoc used-at item (System/currentTimeMillis)) tti swept-at)
      this))
  (miss [_ item result]
    ;; NB: Sweeps the unused entries away, at most once in the `tti` time.
    (let [now (System/currentTimeMillis)
          unused (when (<= tti (- now swept-at))
                   (for [[k used-ts] used-at
                         :when (<= tti (- now used-ts))]
                     k))]
      (TTICache. (assoc (apply dissoc cache unused) item result)
                 (assoc (apply dissoc used-at unused) item now)
                 tti
                 (if (some? unused) now swept-at))))
  (evict [_ item]
    (TTICache. (dissoc cache item) (dissoc used-at item) tti swept-at))
  (seed [_ base]
    (let [now (System/currentTimeMillis)]
      (TTICache. base (zipmap (keys base) (repeat now)) tti now)))
  Object
  (toString [_]
    (str cache \, \space used-at \, \space tti)))

(defn tti-cache-factory
  "Returns a TTI cache with the cache and the usage table initialized to `base`.
   The `:tti` argument is the time in milliseconds that entries are allowed to
   stay unused in the cache."
  [base & {tti :tti}]
  {:pre [(number? tti) (<= 0 tti) (map? base)]}
  (cache/seed (TTICache. {} {} tti 0) base))
