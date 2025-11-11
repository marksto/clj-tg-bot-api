;; Copyright (c) Mark Sto, 2025. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.impl.utils.bot
  {:author "Mark Sto (@marksto)"}
  (:require
   [clojure.string :as str]))

(defn parse-bot-id
  [bot-token]
  (try
    (parse-long (subs bot-token 0 (str/index-of bot-token \:)))
    (catch Exception ex
      (throw (ex-info "Failed to parse an ID from the bot auth token" {} ex)))))

(defn mask-bot-token
  [bot-token mask-str]
  (str (parse-bot-id bot-token) \: mask-str))
