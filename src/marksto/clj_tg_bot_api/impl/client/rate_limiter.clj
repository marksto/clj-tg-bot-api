(ns marksto.clj-tg-bot-api.impl.client.rate-limiter
  (:require [diehard.core :as dh]
            [diehard.rate-limiter :as dh.rl]))

;; NB: The Bots FAQ on the official Telegram website lists the following limits
;;     on server requests.
;;
;;     LEGACY VERSION
;;     "When sending messages inside a particular chat, avoid sending more than
;;      1 message per second. We may allow short bursts that go over this limit,
;;      but eventually you'll begin receiving 429 errors.
;;
;;      Also note that your bot will not be able to send more than 20 messages
;;      per minute to the same group.
;;
;;      If you're sending bulk notifications to multiple users, the API will not
;;      allow more than 30 messages per second or so. Consider spreading out
;;      notifications over large intervals of 8—12 hours for best results."
;;
;;     CURRENT VERSION
;;     "By default, bots are able to message their users *at no cost* – but have
;;      limitations on the number of messages they can broadcast in a single
;;      interval:
;;
;;      - In a single chat, avoid sending more than one message per second. We
;;        may allow short bursts that go over this limit, but eventually you'll
;;        begin receiving 429 errors.
;;
;;      - In a group, bots aren't able to send more than 20 messages per minute.
;;
;;      - For bulk notifications, bots are not able to broadcast more than about
;;        30 messages per second, unless they enable paid broadcasts to increase
;;        the limit."
;;
;;     See details in https://core.telegram.org/bots/faq#broadcasting-to-users,
;;     "My bot is hitting limits, how do I avoid this?".
;;
;;     IMPLEMENTATION
;;
;;     To overcome the issue with HTTP 429 — "Too many requests, retry after X"
;;     error, the Telegram Bot API client implements the following rate limits:
;;
;;     - No more than 1  message  per second in a single chat,
;;     - No more than 20 messages per minute in the same group chat,
;;     - No more than 30 messages per second in total (for broadcasting).

(defonce
  ^{:doc "An atom that holds a map of the following structure:
          {<bot-id> {:total    <total bot rate limiter>
                     <chat-id> <some chat rate limiter>}}"}
  *rate-limiters
  (atom {}))

(defn ->rate [n sec]
  (/ (double n) sec))

(defn ->total-rate-limiter []
  (dh.rl/rate-limiter {:rate (->rate 30 1)}))

(defn ->chat-rate-limiter [chat-id]
  (if-let [_is-group? (neg? chat-id)]
    (dh.rl/rate-limiter {:rate (->rate 20 60)})
    (dh.rl/rate-limiter {:rate (->rate 1 1)})))

(defn get-rate-limiter!
  ([bot-id]
   (-> *rate-limiters
       (swap!
         (fn [rate-limiters]
           (if (some? (get-in rate-limiters [bot-id :total]))
             rate-limiters
             (assoc-in rate-limiters [bot-id :total] (->total-rate-limiter)))))
       (get-in [bot-id :total])))
  ([bot-id chat-id]
   (-> *rate-limiters
       (swap!
         (fn [rate-limiters]
           (if (some? (get-in rate-limiters [bot-id chat-id]))
             rate-limiters
             (assoc-in rate-limiters [bot-id chat-id] (->chat-rate-limiter chat-id)))))
       (get-in [bot-id chat-id]))))

;;

(defmacro with-rate-limiter
  [bot-id in-test? chat-id form]
  `(if ~in-test?
     ~form
     (dh/with-rate-limiter (get-rate-limiter! ~bot-id)
       (if (some? ~chat-id)
         (dh/with-rate-limiter (get-rate-limiter! ~bot-id ~chat-id)
           ~form)
         ~form))))
