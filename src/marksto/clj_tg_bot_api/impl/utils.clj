(ns marksto.clj-tg-bot-api.impl.utils
  "A set of necessary general-purpose utilities"
  (:require [clojure.repl :refer [demunge]]
            [clojure.string :as str])
  (:import (clojure.lang IDeref)
           (java.util.regex Matcher Pattern)))

;; collections

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

(defn interleave*
  ([]
   '())
  ([c1]
   (lazy-seq c1))
  ([c1 c2]
   (lazy-seq
     (let [s1 (seq c1)
           s2 (seq c2)]
       (cond
         (and s1 s2)
         (cons (first s1)
               (cons (first s2)
                     (interleave* (rest s1) (rest s2))))
         s1 s1
         s2 s2))))
  ([c1 c2 & colls]
   (lazy-seq
     (let [colls' (remove empty? (conj colls c2 c1))]
       (concat (map first colls')
               (apply interleave* (map rest colls')))))))

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

(defn join*
  (^String [coll]
   (when (seq coll) (str/join coll)))
  (^String [separator coll]
   (when (seq coll) (str/join separator coll))))

;; regexps

(defn re-match-get-groups
  [^Pattern re ^CharSequence s groups]
  (let [matcher (re-matcher re s)
        get-group (fn [^String name]
                    (try
                      (Matcher/.group matcher name)
                      (catch Exception _
                        nil)))]
    (when (Matcher/.matches matcher)
      (map-keys #(get-group (name %)) groups))))

(defn re-find-all
  [^Pattern re ^CharSequence s]
  (let [matcher (re-matcher re s)]
    (doall (take-while some? (repeatedly #(re-find matcher))))))

;; functions

(defn apply-if-fn
  [fn-or-val]
  (if (fn? fn-or-val)
    (fn-or-val)
    fn-or-val))

(defn fn-name
  [fn]
  (when-some [full-name (as-> (str fn) $
                              (demunge $)
                              (or (re-find #"(.+)--\d+@" $)
                                  (re-find #"(.+)@" $))
                              (last $))]
    (str/replace-first full-name #".+/" "")))

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

;; runtime

(defn in-repl? []
  (or (force-ref (resolve '*repl*))
      (some (fn [^StackTraceElement ste]
              (and (= "clojure.main$repl" (StackTraceElement/.getClassName ste))
                   (= "doInvoke" (StackTraceElement/.getMethodName ste))))
            (Thread/.getStackTrace (Thread/currentThread)))))
