(ns marksto.clj-tg-bot-api.impl.client.rate-limiter
  (:require
   [diehard.core :as dh]
   [diehard.rate-limiter :as dh.rl]
   [marksto.clj-tg-bot-api.impl.utils :as utils]))

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
;;     For "sendMessage" and "editMessageText" (share limits):
;;     - No more than  1 message  per second in a single chat,
;;     - No more than 20 messages per minute in the same group chat,
;;     - No more than 30 messages per second in total (for broadcasting).

(defonce
  ^{:doc "An atom that holds a map of the following structure:
          {<bot-id> {<api-method> {:in-total <in-total-rl>
                                   <chat-id> <chat-rl>}}}
          where <in-total-rl> is a rate limiter for all requests of that type,
          while <chat-rl> is a rate limiter for a specific chat, if supported."}
  *rate-limiters
  (atom {}))

;;

(defn ->rate
  "Calculates a numerical rate value for making `n` calls in `sec` seconds."
  [n sec]
  (/ (double n) sec))

(defn group-chat? [chat-id] (neg? chat-id))

(def default-opts
  "Bot API method -> rate limiter opts | base Bot API method"
  {:send-message      {:in-total {:rate 30}
                       :per-chat (fn [chat-id]
                                   (if (group-chat? chat-id)
                                     {:rate (->rate 20 60)}
                                     {:rate 1}))}
   :edit-message-text :send-message})

(defn get-base-limited-method [method]
  (let [default-method-or-opts (get default-opts method)]
    (if (keyword? default-method-or-opts) ; base method?
      default-method-or-opts
      method)))

;;

(def default-sleep-fn dh.rl/uninterruptible-sleep)

(defn has-opts-for?
  [limiter-id method-opts]
  (contains? method-opts (if (= :in-total limiter-id) :in-total :per-chat)))

(defn get-opts-map
  [limiter-id method-opts]
  (if (= :in-total limiter-id)
    (:in-total method-opts)
    (utils/apply-if-fn (:per-chat method-opts) limiter-id)))

(defn ->rate-limiter-opts
  [limiter-id default-method-opts limiter-method-opts]
  (some-> (if (has-opts-for? limiter-id limiter-method-opts)
            (some-> (get-opts-map limiter-id limiter-method-opts)
                    (utils/reverse-merge
                      (get-opts-map limiter-id default-method-opts)))
            (get-opts-map limiter-id default-method-opts))
          (update :sleep-fn #(or % default-sleep-fn))))

(defn ->rate-limiter
  [limiter-id limiter-opts method]
  (some-> (->rate-limiter-opts
            limiter-id (get default-opts method) (get limiter-opts method))
          (dh.rl/rate-limiter)))

(defn get-rate-limiter!
  [limiter-id limiter-opts bot-id method]
  (when (some? limiter-id)
    (let [method' (get-base-limited-method method)
          rate-limiter-path [bot-id method' limiter-id]]
      (-> *rate-limiters
          (swap!
            (fn [rate-limiters]
              (if (contains? rate-limiters rate-limiter-path)
                rate-limiters
                (assoc-in rate-limiters
                          rate-limiter-path
                          (->rate-limiter limiter-id limiter-opts method')))))
          (get-in rate-limiter-path)))))

;;

(defmacro -with-rate-limiter
  [limiter-id limiter-opts bot-id method form]
  `(let [limiter# (get-rate-limiter! ~limiter-id ~limiter-opts ~bot-id ~method)]
     (if (some? limiter#)
       (dh/with-rate-limiter limiter# ~form)
       ~form)))

(defmacro with-rate-limiter
  "Wraps a call to the given `form`, which is also known to be made by еру bot
   with the given `bot-id`, to a particular Bot API `method`, w/ `:chat-id` in
   the parameters (if any), and the given `limiter-opts`, with a corresponding
   rate limiter.

   The `limiter-opts` is either `nil` (then the rate limiting is bypassed), or
   a map of the following structure:
   ```
   {<api-method> {:in-total <in-total-rl-opts>
                  <chat-id> <chat-rl-opts-or-fn>}
    <api-method> <base-api-method>}
   ```
   where `<in-total-rl-opts>` is an opts map of a rate limiter for all requests
   of that type/to that Bot API method, and `<chat-rl-opts-or-fn>` is either an
   opts map or a unary fn of 'chat-id' that returns an opts map of rate limiter
   for a specific chat, if supported (by default, only for `:send-message`).

   If the `<api-method> <base-api-method>` entry is present, the limits for the
   `<api-method>` are shared with the `<base-api-method>` (by default, only for
   `:edit-message-text` that shares limits with the `:send-message`).

   The `limiter-opts`, only if provided, get deep merged with the default ones.

   See the `diehard.rate-limiter` ns for the supported set of limiters options.

   See https://core.telegram.org/bots/faq"
  [limiter-opts bot-id method chat-id form]
  `(if (some? ~limiter-opts)
     (-with-rate-limiter
       :in-total ~limiter-opts ~bot-id ~method
       (-with-rate-limiter
         ~chat-id ~limiter-opts ~bot-id ~method
         ~form))
     ~form))

;;

^:rct/test
(comment
  ;; :send-message

  (reset! *rate-limiters {})
  (get-rate-limiter!
    :in-total
    {:send-message {:per-chat {:sleep-fn dh.rl/interruptible-sleep}}}
    1234567890
    :send-message)
  ; =>>
  ; {:rate     0.03
  ;  :sleep-fn #(= dh.rl/uninterruptible-sleep %)
  ;  ...}

  (reset! *rate-limiters {})
  (get-rate-limiter!
    :in-total
    {:send-message {:in-total {:rate 1}}}
    1234567890
    :send-message)
  ; =>>
  ; {:rate     0.001
  ;  :sleep-fn #(= dh.rl/uninterruptible-sleep %)
  ;  ...}

  (reset! *rate-limiters {})
  (get-rate-limiter!
    :in-total
    {:send-message {:in-total nil
                    :per-chat {:sleep-fn dh.rl/interruptible-sleep}}}
    1234567890
    :send-message)
  ; =>
  nil

  (reset! *rate-limiters {})
  (get-rate-limiter!
    -1
    {:send-message {:per-chat {:rate 1}}}
    1234567890
    :send-message)
  ; =>>
  ; {:rate     0.001
  ;  :sleep-fn #(= dh.rl/uninterruptible-sleep %)
  ;  ...}

  (reset! *rate-limiters {})
  (get-rate-limiter!
    -1
    {:send-message {:per-chat {:rate     1
                               :sleep-fn dh.rl/interruptible-sleep}}}
    1234567890
    :send-message)
  ; =>>
  ; {:rate     0.001
  ;  :sleep-fn #(= dh.rl/interruptible-sleep %)
  ;  ...}

  (reset! *rate-limiters {})
  (get-rate-limiter!
    -1
    {:send-message {:per-chat {:sleep-fn dh.rl/interruptible-sleep}}}
    1234567890
    :send-message)
  ; =>>
  ; {:rate     0.0003333333333333333
  ;  :sleep-fn #(= dh.rl/interruptible-sleep %)
  ;  ...}

  ;; :edit-message-text

  (reset! *rate-limiters {})
  (get-rate-limiter!
    :in-total
    {:send-message {:per-chat {:sleep-fn dh.rl/interruptible-sleep}}}
    1234567890
    :edit-message-text)
  ; =>>
  ; {:rate     0.03
  ;  :sleep-fn #(= dh.rl/uninterruptible-sleep %)
  ;  ...}

  (reset! *rate-limiters {})
  (get-rate-limiter!
    :in-total
    {:send-message {:in-total {:rate 1}}}
    1234567890
    :edit-message-text)
  ; =>>
  ; {:rate     0.001
  ;  :sleep-fn #(= dh.rl/uninterruptible-sleep %)
  ;  ...}

  (reset! *rate-limiters {})
  (get-rate-limiter!
    :in-total
    {:send-message {:in-total nil
                    :per-chat {:sleep-fn dh.rl/interruptible-sleep}}}
    1234567890
    :edit-message-text)
  ; =>
  nil

  (reset! *rate-limiters {})
  (get-rate-limiter!
    -1
    {:send-message {:per-chat {:rate 1}}}
    1234567890
    :edit-message-text)
  ; =>>
  ; {:rate     0.001
  ;  :sleep-fn #(= dh.rl/uninterruptible-sleep %)
  ;  ...}

  (reset! *rate-limiters {})
  (get-rate-limiter!
    -1
    {:send-message {:per-chat {:rate     1
                               :sleep-fn dh.rl/interruptible-sleep}}}
    1234567890
    :edit-message-text)
  ; =>>
  ; {:rate     0.001
  ;  :sleep-fn #(= dh.rl/interruptible-sleep %)
  ;  ...}

  (reset! *rate-limiters {})
  (get-rate-limiter!
    -1
    {:send-message {:per-chat {:sleep-fn dh.rl/interruptible-sleep}}}
    1234567890
    :edit-message-text)
  ; =>>
  ; {:rate     0.0003333333333333333
  ;  :sleep-fn #(= dh.rl/interruptible-sleep %)
  ;  ...}

  ;; other methods

  (reset! *rate-limiters {})
  (get-rate-limiter!
    :in-total
    {:send-message    {:in-total nil
                       :per-chat {:sleep-fn dh.rl/interruptible-sleep}}
     :set-my-commands {:in-total {:rate 1}}}
    1234567890
    :set-my-commands)
  ; =>>
  ; {:rate     0.001
  ;  :sleep-fn #(= dh.rl/uninterruptible-sleep %)
  ;  ...}

  (reset! *rate-limiters {})
  (get-rate-limiter!
    -1
    {:send-message    {:in-total nil
                       :per-chat {:sleep-fn dh.rl/interruptible-sleep}}
     :set-my-commands {:in-total {:rate 1}}}
    1234567890
    :set-my-commands)
  ; =>
  nil

  (reset! *rate-limiters {})
  (get-rate-limiter!
    :in-total
    {:send-message    {:in-total nil
                       :per-chat {:sleep-fn dh.rl/interruptible-sleep}}
     :set-my-commands {:in-total {:rate     1
                                  :sleep-fn dh.rl/interruptible-sleep}}}
    1234567890
    :set-my-commands)
  ; =>>
  ; {:rate     0.001
  ;  :sleep-fn #(= dh.rl/interruptible-sleep %)
  ;  ...}

  ;; interference

  (reset! *rate-limiters {})
  (get-rate-limiter!
    :in-total
    {:send-message    {:in-total nil}
     :set-my-commands {:in-total {:rate 1}}}
    1234567890
    :send-message)
  ; =>
  nil

  (reset! *rate-limiters {})
  (get-rate-limiter!
    -1
    {:send-message    {:per-chat {:rate 1}}
     :set-my-commands {:in-total {:rate 1}}}
    1234567890
    :send-message)
  ; =>>
  ; {:rate     0.001
  ;  :sleep-fn #(= dh.rl/uninterruptible-sleep %)
  ;  ...}

  (reset! *rate-limiters {})
  (get-rate-limiter!
    :in-total
    {:set-my-commands {:in-total {:rate 1}}}
    1234567890
    :send-message)
  ; =>>
  ; {:rate     0.03
  ;  :sleep-fn #(= dh.rl/uninterruptible-sleep %)
  ;  ...}

  :end/comment)
