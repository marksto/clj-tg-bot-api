(ns marksto.clj-tg-bot-api.impl.utils.bot
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
