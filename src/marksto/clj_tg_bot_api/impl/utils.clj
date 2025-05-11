(ns marksto.clj-tg-bot-api.impl.utils
  (:require [clojure.repl :refer [demunge]]
            [clojure.string :as str])
  (:import (clojure.lang IDeref)))

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

(defn fn-name
  [fn]
  (when-some [full-name (as-> (str fn) $
                              (demunge $)
                              (or (re-find #"(.+)--\d+@" $)
                                  (re-find #"(.+)@" $))
                              (last $))]
    (str/replace-first full-name #".+/" "")))

;; exceptions

(defn clear-stack-trace
  [t]
  (Throwable/.setStackTrace t (into-array StackTraceElement [])))

;; derefable

(defn derefable?
  [obj]
  (instance? IDeref obj))

(defn force-ref
  [val]
  (if (derefable? val)
    (deref val)
    val))

;; runtime

(defn in-repl? []
  (or (force-ref (resolve '*repl*))
      (some (fn [^StackTraceElement ste]
              (and (= "clojure.main$repl" (StackTraceElement/.getClassName ste))
                   (= "doInvoke" (StackTraceElement/.getMethodName ste))))
            (Thread/.getStackTrace (Thread/currentThread)))))
