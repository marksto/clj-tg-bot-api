(ns marksto.clj-tg-bot-api.impl.utils
  "A set of necessary general-purpose utilities"
  (:require [clojure.set :as set]
            [loom.alg :as la]
            [loom.graph :refer [add-edges add-nodes digraph nodes]])
  (:import (clojure.lang IDeref)))

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

;; strings

(defn char-sequence?
  [obj]
  (instance? CharSequence obj))

(defn not-empty?
  [obj]
  (and (char-sequence? obj) (not (CharSequence/.isEmpty obj))))

(defn truncate
  ^String [^CharSequence s n]
  (assert (nat-int? n) "`n` must be a non-negative int")
  (when s
    (let [sl (CharSequence/.length s)]
      (if (< n sl)
        (subs (CharSequence/.toString s) (- sl n))
        s))))

;; functions

(defn apply-if-fn
  [fn-or-val]
  (if (fn? fn-or-val)
    (fn-or-val)
    fn-or-val))

;; references

(defn derefable?
  [obj]
  (instance? IDeref obj))

(defn force-ref
  [val]
  (if (derefable? val)
    (deref val)
    val))

;; exceptions

(defn clear-stack-trace
  [t]
  (Throwable/.setStackTrace t (into-array StackTraceElement [])))

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
       (filter #(< 1 (count %)))
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
