;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.updates.impl.common
  {:author "Mark Sto (@marksto)"}
  (:require
   [marksto.clj-tg-bot-api.impl.utils :as core-utils]))

;; SHARED STATE

;; NB: An internal implementation detail. It stays unexposed to the outer world.
;;     So far, it is only necessary to start/stop Telegram long-polling process.
(defonce ^:private *local-state (atom {}))

(defn set-bot-local-state!
  [bot-id local-state]
  (swap! *local-state assoc bot-id local-state))

(defn drop-bot-local-state!
  [bot-id]
  (get (first (swap-vals! *local-state dissoc bot-id)) bot-id))

(defn has-long-polling-state?
  [bot-id]
  (core-utils/contains-in? @*local-state [bot-id :long-polling]))

;; AUX FNS

(defn call-configured-fn!
  [config fn-key & args]
  (let [fn-config-option (get config fn-key)]
    (if (= :exit fn-config-option)
      (System/exit -1)
      (apply core-utils/apply-if-fn fn-config-option args))))
