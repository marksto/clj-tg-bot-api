(ns marksto.clj-tg-bot-api.core
  "Provides a convenient wrapper around the 'telegrambot-lib' client library fns
   adding handy callback fns (operations) that handle Telegram Bot API responses
   and any errors (exceptions)."
  (:require [clj-http.conn-mgr :as conn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]

            [telegrambot-lib.core :as tg-bot-api]
            [teleoperator.tg-bot-api-utils.interface :as tg-bot-api-utils]

            [swa.platform.utils.interface.ex :as u-ex]
            [swa.platform.utils.interface.fns :as u-fns]
            [swa.platform.utils.interface.lang :as u-lang]
            [swa.platform.utils.interface.resilience :as u-res]
            [swa.platform.utils.interface.runtime :as u-runtime]))

;; TODO: Do a complete rewrite of the `telegrambot-lib` under the SWA Platform.
;;       - ideally, should (semi)auto-update upon the Telegram Bot API updates
;;       - hence, it makes sense to have a web-scraping web app backing it up,
;;         figuring out changes in the Bot API, and applying these changes to
;;         to existing "static" library code
;;       - therefore, it also makes sense to completely get rid of any "moving
;;         parts" of the original library (building a request body in a manual
;;         way, making HTTP requests, etc.) which are notoriously laborious to
;;         maintain
;;       - drop all `async?` processing logic as a part of the previous point
;;         and push rate limiting inside the lib
;;       - uniform the Bot API method contract — drop unnecessary fancy arity,
;;         add configurable auto-transformations of cases (mainly for dashes),
;;         use Malli schemas for validation/coercion/etc., postpone making the
;;         actual request (e.g. with `martian` serving as an adaptor layer)
;;       - serve all "moving parts" (making HTTP requests, polling updates) as
;;         separate sub-libraries that can be plugged-in by an end user

;; shared context

;; TODO: Make the 'clj-http' connection managers used by 'telegrambot-lib' configurable.
(u-lang/set-var-root! conn/*connection-manager* (conn/make-reusable-conn-manager {}))
(u-lang/set-var-root! conn/*async-connection-manager* (conn/make-reusable-async-conn-manager {}))


;; operations on response 'result'

(defn get-result
  ([tg-resp]
   (get-result tg-resp nil))
  ([tg-resp default-val]
   (or (tg-bot-api-utils/get-response-result tg-resp) default-val)))

(defn assert-result
  [expected tg-resp]
  (let [tg-resp-result (get-result tg-resp)]
    (when-not (= expected tg-resp-result)
      (throw
        (ex-info "The Telegram Bot API method returned an unexpected result"
                 {:expected expected
                  :actual   tg-resp-result})))
    tg-resp-result))


;; operations on response 'error'

(defn get-error
  [method-fn method-args failed-tg-resp]
  (let [resp-error (tg-bot-api-utils/get-response-error failed-tg-resp)]
    (assoc resp-error :method-fn method-fn :method-args method-args)))

(def failure-msg "Unsuccessful Telegram Bot API request")

(defn- format-msg [base-msg method-name method-args]
  (format "%s (method='%s' args=%s)" base-msg method-name method-args))

(defn log-failure-reason
  ([method-fn method-args failed-tg-resp]
   (log-failure-reason :error failure-msg
                       method-fn method-args failed-tg-resp))
  ([base-msg method-fn method-args failed-tg-resp]
   (log-failure-reason :error base-msg
                       method-fn method-args failed-tg-resp))
  ([log-level base-msg method-fn method-args failed-tg-resp]
   (let [resp-error (tg-bot-api-utils/get-response-error failed-tg-resp)
         error-text (some->> (seq resp-error)
                             (map (fn [[k v]] (str (name k) "=\"" v "\"")))
                             (not-empty)
                             (str/join ", "))
         base-msg' (cond-> (u-fns/apply-if-fn base-msg)
                           (seq error-text) (str ": " error-text))
         method-name (u-fns/fn-name method-fn true)]
     (log/log log-level (format-msg base-msg' method-name method-args)))))

(defn throw-for-failure
  ([method-fn method-args failed-tg-resp]
   (throw-for-failure failure-msg method-fn method-args failed-tg-resp))
  ([base-msg method-fn method-args failed-tg-resp]
   (let [ex-msg (u-fns/apply-if-fn base-msg)
         response (dissoc failed-tg-resp :error)]
     (throw (ex-info ex-msg {:response    response
                             :method-fn   method-fn
                             :method-args method-args})))))

;; NB: For async requests, this strategy is of little use, since the provided
;;     callback will be processed on different threads and any exception will
;;     be redirected to the `UncaughtExceptionHandler` which will simply log.
(defn log-failure-reason-and-throw
  ([method-fn method-args failed-tg-resp]
   (log-failure-reason method-fn method-args failed-tg-resp)
   (throw-for-failure method-fn method-args failed-tg-resp))
  ([base-msg method-fn method-args failed-tg-resp]
   (log-failure-reason base-msg method-fn method-args failed-tg-resp)
   (throw-for-failure base-msg method-fn method-args failed-tg-resp))
  ([log-level base-msg method-fn method-args failed-tg-resp]
   (log-failure-reason log-level base-msg method-fn method-args failed-tg-resp)
   (throw-for-failure base-msg method-fn method-args failed-tg-resp)))


;; operations on arbitrary errors (e.g. network)

(def error-msg "Error while making a Telegram Bot API request")

(defn log-error
  ([method-fn method-args ex]
   (log-error error-msg method-fn method-args ex))
  ([base-msg method-fn method-args ex]
   (let [base-msg' (u-fns/apply-if-fn base-msg)
         method-name (u-fns/fn-name method-fn true)]
     (log/log :error ex (format-msg base-msg' method-name method-args)))))

;; NB: For async requests, this strategy is of little use, since the provided
;;     callback will be processed on different threads and any exception will
;;     be redirected to the `UncaughtExceptionHandler` which will simply log.
(defn rethrow-error
  [method-fn method-args ex]
  (throw (ex-info error-msg {:method-fn   method-fn
                             :method-args method-args} ex)))

(defn log-error-and-rethrow
  ([method-fn method-args ex]
   (log-error method-fn method-args ex)
   (when-not (u-runtime/in-repl?) (u-ex/clear-stack-trace ex))
   (rethrow-error method-fn method-args ex))
  ([base-msg method-fn method-args ex]
   (log-error base-msg method-fn method-args ex)
   (when-not (u-runtime/in-repl?) (u-ex/clear-stack-trace ex))
   (rethrow-error method-fn method-args ex)))


;; making the Telegram Bot API calls

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

(defn ->total-rate-limiter []
  (u-res/rate-limiter {:rate (u-res/->rate 30 1)}))

(defn ->chat-rate-limiter [chat-id]
  (if-let [_is-group? (neg? chat-id)]
    (u-res/composite-rate-limiter {:rates [(u-res/->rate 1 1)
                                           (u-res/->rate 20 60)]})
    (u-res/rate-limiter {:rate (u-res/->rate 1 1)})))

(defn- get-rate-limiter!
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

;; TODO: Get rid of the `tg-bot-api:chat-id-fns` hack and the `best-guess-chat-id` fn.
(def ^:private tg-bot-api:chat-id-fns
  #{tg-bot-api/send-message
    tg-bot-api/edit-message-text
    tg-bot-api/edit-message-caption
    tg-bot-api/edit-message-media
    tg-bot-api/edit-message-reply-markup
    tg-bot-api/delete-message
    tg-bot-api/delete-messages
    tg-bot-api/forward-message
    tg-bot-api/forward-messages
    tg-bot-api/copy-message
    tg-bot-api/copy-messages
    tg-bot-api/send-photo
    tg-bot-api/send-audio
    tg-bot-api/send-document
    tg-bot-api/send-video
    tg-bot-api/send-animation
    tg-bot-api/send-voice
    tg-bot-api/send-video-note
    tg-bot-api/send-media-group
    tg-bot-api/send-location
    tg-bot-api/edit-message-live-location
    tg-bot-api/stop-message-live-location
    tg-bot-api/send-venue
    tg-bot-api/send-contact
    tg-bot-api/send-poll
    tg-bot-api/stop-poll
    tg-bot-api/send-dice
    tg-bot-api/send-chat-action
    tg-bot-api/set-message-reaction
    tg-bot-api/ban-chat-member
    tg-bot-api/unban-chat-member
    tg-bot-api/restrict-chat-member
    tg-bot-api/promote-chat-member
    tg-bot-api/set-chat-administrator-custom-title
    tg-bot-api/ban-chat-sender-chat
    tg-bot-api/unban-chat-sender-chat
    tg-bot-api/set-chat-permissions
    tg-bot-api/export-chat-invite-link
    tg-bot-api/create-chat-invite-link
    tg-bot-api/edit-chat-invite-link
    tg-bot-api/revoke-chat-invite-link
    tg-bot-api/approve-chat-join-request
    tg-bot-api/decline-chat-join-request
    tg-bot-api/set-chat-photo
    tg-bot-api/delete-chat-photo
    tg-bot-api/set-chat-title
    tg-bot-api/set-chat-description
    tg-bot-api/pin-chat-message
    tg-bot-api/unpin-chat-message
    tg-bot-api/unpin-all-chat-messages
    tg-bot-api/leave-chat
    tg-bot-api/get-chat
    tg-bot-api/get-chat-administrators
    tg-bot-api/get-chat-member-count
    tg-bot-api/get-chat-member
    tg-bot-api/set-chat-sticker-set
    tg-bot-api/delete-chat-sticker-set
    tg-bot-api/create-forum-topic
    tg-bot-api/edit-forum-topic
    tg-bot-api/close-forum-topic
    tg-bot-api/reopen-forum-topic
    tg-bot-api/delete-forum-topic
    tg-bot-api/unpin-all-forum-topic-messages
    tg-bot-api/edit-general-forum-topic
    tg-bot-api/close-general-forum-topic
    tg-bot-api/reopen-general-forum-topic
    tg-bot-api/hide-general-forum-topic
    tg-bot-api/unhide-general-forum-topic
    tg-bot-api/unpin-all-general-forum-topic-messages
    tg-bot-api/get-user-chat-boosts
    tg-bot-api/send-sticker
    tg-bot-api/send-invoice
    tg-bot-api/send-game
    tg-bot-api/set-game-score
    tg-bot-api/get-game-high-scores})

(defn- best-guess-chat-id
  [method-fn method-args]
  (let [[farg & rest] method-args]
    (if (and (map? farg) (empty? rest))
      (:chat_id farg)
      (when (contains? tg-bot-api:chat-id-fns method-fn)
        farg))))

(defn- call-bot-api-method!
  [tg-bot-api-client method-fn method-args]
  ;; NB: The `telegrambot-lib.http/request` returns a map with an `:error` key
  ;;     in case if request was unsuccessful (status codes other than 200-207,
  ;;     300-303, or 307) or in any of other exceptional situations (e.g. when
  ;;     the lib is unable to retrieve the response body).
  (try
    (if (seq method-args)
      (apply method-fn tg-bot-api-client method-args)
      (method-fn tg-bot-api-client))
    (catch Throwable client-code-ex
      (log/log :error client-code-ex "An error in the client code")
      ;; NB: An `error` is processed by the `handle-response-sync`.
      {:error client-code-ex})))

(defn- with-rate-limiter:call-bot-api-method!
  [{:keys [bot-id in-test?] :as tg-bot-api-client} method-fn method-args]
  (if in-test?
    (call-bot-api-method! tg-bot-api-client method-fn method-args)
    (u-res/with-rate-limiter {:ratelimiter (get-rate-limiter! bot-id)}
      (if-some [chat-id (best-guess-chat-id method-fn method-args)]
        (u-res/with-rate-limiter {:ratelimiter (get-rate-limiter! bot-id chat-id)}
          (call-bot-api-method! tg-bot-api-client method-fn method-args))
        (call-bot-api-method! tg-bot-api-client method-fn method-args)))))

;;

(defn- call-ignorable-callback
  [callback-fn method-fn method-args arg-val]
  (when (and (some? callback-fn) (not= :ignore callback-fn))
    (callback-fn method-fn method-args arg-val)))

(defn- handle-response
  [method-fn method-args api-resp
   {:keys [on-success on-failure on-error] :as _callbacks}]
  ;; NB: The order of checks here is crucial. First, we handle valid responses,
  ;;     including unsuccessful ones (also contain `:error` key). Only then we
  ;;     handle other `:error`-containing results, i.e. HTTP client exceptions.
  (cond
    (tg-bot-api-utils/valid-response? api-resp)
    (if (tg-bot-api-utils/successful-response? api-resp)
      (when (some? on-success) (on-success api-resp))
      (call-ignorable-callback on-failure method-fn method-args api-resp))
    ;;
    (contains? api-resp :error)
    (call-ignorable-callback on-error method-fn method-args (:error api-resp))
    ;;
    :else
    (throw (IllegalStateException. "Malformed Telegram Bot API response"))))

;;

;; TODO: Add an auto-retry strategy for Telegram Bot API response 'parameters' with 'migrate_to_chat_id'.
;; TODO: Add an auto-retry strategy for Telegram Bot API response 'parameters' with 'retry_after'.

(defn- make-request!
  [tg-bot-api-client call-opts method-fn method-args]
  (let [callbacks {:on-success (or (:on-success call-opts)
                                   get-result)
                   :on-failure (or (:on-failure call-opts)
                                   log-failure-reason-and-throw)
                   :on-error   (or (:on-error call-opts)
                                   log-error-and-rethrow)}
        api-resp (with-rate-limiter:call-bot-api-method!
                   tg-bot-api-client method-fn method-args)]
    (log/debugf "Telegram Bot API returned: %s" api-resp)
    (when (some? api-resp)
      (handle-response method-fn method-args api-resp callbacks))))

(defn call-bot-api!
  [tg-bot-api-client args]
  (let [[call-opts method-fn & method-args] (if (map? (first args))
                                              args
                                              (conj #_list args nil))]
    (when (nil? method-fn)
      (throw (ex-info "The `call-bot-api!` was called without `method-fn`"
                      {:args args})))
    (make-request! tg-bot-api-client call-opts method-fn method-args)))



;; IMMEDIATE RESPONSE

;; TODO: Call a 'telegrambot-lib' fn w/o actually making an HTTP request.
;;       Inject a `:method <method-name>` entry into the returned map.
(defn build-immediate-response
  "Builds an immediate response, i.e. a reply payload for an incoming update.

   From the Telegram Bot API docs: \"If you're using webhooks, you can perform
   a request to the Bot API while sending an answer to the webhook ... Specify
   the method to be invoked in the 'method' parameter of the request.\".

   Example:
   ```
   {:method              \"sendMessage\",
    :chat_id             chat-id,
    :reply_to_message_id msg-id,
    :text                \"An ordinary reply message text.\"}
   ```

   This technique helps to reduce the number of requests to the Bot API server
   and, as a consequence, helps us to stay under the limits on server requests
   and sometimes improves the responsiveness of the bot's UI on the client.

   See https://core.telegram.org/bots/api#making-requests-when-getting-updates"
  [method-name response-content]
  (merge {:method method-name} response-content))
