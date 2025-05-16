(ns marksto.clj-tg-bot-api.impl.client
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [diehard.core :as dh]
            [diehard.rate-limiter :as dh.rl]

            [marksto.clj-tg-bot-api.impl.api.spec :as api-spec]
            [marksto.clj-tg-bot-api.impl.utils :as utils]
            [marksto.clj-tg-bot-api.impl.utils.response :as response]))

;; Bot API client instantiation

(def global-server-url "https://api.telegram.org/bot")

(defn get-api-root-url-for-bot
  [server-url bot-auth-token]
  (str (or server-url global-server-url) bot-auth-token))

(defn ->client
  [{:keys [bot-auth-token server-url] :as _client-opts}]
  (api-spec/build-martian (get-api-root-url-for-bot server-url bot-auth-token)))


;; operations on response 'result'

(defn get-result
  ([tg-resp]
   (get-result tg-resp nil))
  ([tg-resp default-val]
   (or (response/get-response-result tg-resp) default-val)))

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
  (let [resp-error (response/get-response-error failed-tg-resp)]
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
   (let [resp-error (response/get-response-error failed-tg-resp)
         error-text (some->> (seq resp-error)
                             (map (fn [[k v]] (str (name k) "=\"" v "\"")))
                             (not-empty)
                             (str/join ", "))
         base-msg' (cond-> (utils/apply-if-fn base-msg)
                           (seq error-text) (str ": " error-text))
         method-name (utils/fn-name method-fn)]
     (log/log log-level (format-msg base-msg' method-name method-args)))))

(defn throw-for-failure
  ([method-fn method-args failed-tg-resp]
   (throw-for-failure failure-msg method-fn method-args failed-tg-resp))
  ([base-msg method-fn method-args failed-tg-resp]
   (let [ex-msg (utils/apply-if-fn base-msg)
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
   (let [base-msg' (utils/apply-if-fn base-msg)
         method-name (utils/fn-name method-fn)]
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
   (when-not (utils/in-repl?) (utils/clear-stack-trace ex))
   (rethrow-error method-fn method-args ex))
  ([base-msg method-fn method-args ex]
   (log-error base-msg method-fn method-args ex)
   (when-not (utils/in-repl?) (utils/clear-stack-trace ex))
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

(defn ->rate [n sec]
  (/ (double n) sec))

(defn ->total-rate-limiter []
  (dh.rl/rate-limiter {:rate (->rate 30 1)}))

(defn ->chat-rate-limiter [chat-id]
  (if-let [_is-group? (neg? chat-id)]
    (dh.rl/rate-limiter {:rate (->rate 20 60)})
    (dh.rl/rate-limiter {:rate (->rate 1 1)})))

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
  #{})

(defn- best-guess-chat-id
  [method-fn method-args]
  (let [[farg & rest] method-args]
    (if (and (map? farg) (empty? rest))
      (:chat_id farg)
      (when (contains? tg-bot-api:chat-id-fns method-fn)
        farg))))

(defn- call-bot-api-method!
  [tg-bot-api-client method-fn method-args]
  ;; TODO: Double-check with all popular clients and re-implement if necessary.
  ;; NB: Expects an HTTP client library to return a map with the `:error` key
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
    (dh/with-rate-limiter {:ratelimiter (get-rate-limiter! bot-id)}
      (if-some [chat-id (best-guess-chat-id method-fn method-args)]
        (dh/with-rate-limiter {:ratelimiter (get-rate-limiter! bot-id chat-id)}
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
    (response/valid-response? api-resp)
    (if (response/successful-response? api-resp)
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

(defn make-request!
  [tg-bot-api-client args]
  (let [[call-opts method-fn & method-args] (if (map? (first args))
                                              args
                                              (conj #_list args nil))]
    (when (nil? method-fn)
      (throw (ex-info "The `call-bot-api!` was called without `method-fn`"
                      {:args args})))
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
        (handle-response method-fn method-args api-resp callbacks)))))

;;

;; TODO: Call a Bot API method fn w/o actually making an HTTP request.
;;       Inject a `:method <method-name>` entry into the returned map.
(defn build-immediate-response
  [tg-bot-api-client method-name method-args]
  (merge {:method method-name} method-args))
