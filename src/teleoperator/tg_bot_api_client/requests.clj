(ns teleoperator.tg-bot-api-client.requests
  "Provides a convenient wrapper around the 'telegrambot-lib' client library fns
   adding handy callback fns (operations) that handle Telegram Bot API responses
   and any errors (exceptions)."
  (:require [clojure.core.async :refer [go <!]]
            [clj-http.conn-mgr :as conn]

            [teleoperator.tg-bot-api-client.utils :as tg-utils]

            [swa.platform.utils.interface.fns :as u-fns]
            [swa.platform.utils.interface.log :as u-log]))

;; shared context

;; TODO: Make the 'clj-http' connection managers used by 'telegrambot-lib' configurable.
(alter-var-root #'conn/*connection-manager*
                (constantly (conn/make-reusable-conn-manager {})))
(alter-var-root #'conn/*async-connection-manager*
                (constantly (conn/make-reusable-async-conn-manager {})))


;; operations on response 'result'

(defn get-result
  ([tg-resp]
   (get-result tg-resp nil))
  ([tg-resp default-val]
   (or (tg-utils/get-response-result tg-resp) default-val)))

(defn assert-result
  [expected tg-resp]
  (let [tg-resp-result (get-result tg-resp)]
    (when-not (= expected tg-resp-result)
      (throw
        (ex-info "The Telegram Bot API method returned an unexpected result"
                 {:expected expected
                  :actual   tg-resp-result})))
    tg-resp-result))


;; operations on response 'description' & 'error'

(defn log-failure-reason
  ([failed-tg-resp]
   (log-failure-reason failed-tg-resp
                       :error
                       "Unsuccessful Telegram Bot API request"))
  ([failed-tg-resp base-msg]
   (log-failure-reason failed-tg-resp :error base-msg))
  ([failed-tg-resp log-level base-msg]
   (let [logged-msg (u-fns/apply-if-fn base-msg)
         error-text (tg-utils/get-response-error-text failed-tg-resp)]
     (if (seq error-text)
       (u-log/log! log-level "%s: %s" logged-msg error-text)
       (u-log/log! log-level logged-msg)))))

(defn- throw-for-failure
  ([failed-tg-resp]
   (throw-for-failure failed-tg-resp
                      "Unsuccessful Telegram Bot API request"))
  ([failed-tg-resp base-msg]
   (let [ex-msg (u-fns/apply-if-fn base-msg)
         response (dissoc failed-tg-resp :error)]
     (throw (ex-info ex-msg {:response response})))))

;; NB: For async requests, this strategy is of little use, since the provided
;;     callback will be processed on different threads and any exception will
;;     be redirected to the `UncaughtExceptionHandler` which will simply log.
(defn log-failure-reason-and-throw
  ([failed-tg-resp]
   (log-failure-reason failed-tg-resp)
   (throw-for-failure failed-tg-resp))
  ([failed-tg-resp base-msg]
   (log-failure-reason failed-tg-resp base-msg)
   (throw-for-failure failed-tg-resp base-msg))
  ([failed-tg-resp log-level base-msg]
   (log-failure-reason failed-tg-resp log-level base-msg)
   (throw-for-failure failed-tg-resp base-msg)))


;; operations on arbitrary errors (e.g. network)

(defn log-error
  ([ex]
   (log-error ex "Error while making a Telegram Bot API request"))
  ([ex base-msg]
   (let [logged-msg (u-fns/apply-if-fn base-msg)]
     (u-log/error! ex logged-msg))))

;; NB: For async requests, this strategy is of little use, since the provided
;;     callback will be processed on different threads and any exception will
;;     be redirected to the `UncaughtExceptionHandler` which will simply log.
(defn rethrow-error [ex]
  (throw ex))

(defn log-error-and-rethrow
  ([ex]
   (log-error ex)
   (rethrow-error ex))
  ([ex base-msg]
   (log-error ex base-msg)
   (rethrow-error ex)))


;; making the Telegram Bot API calls

(defn- call-bot-api-method!
  [tg-bot-api-client method-fn async?]
  (try
    ;; NB: The `telegrambot-lib.http/request` returns a map with
    ;;     an `:error` key in case when request was unsuccessful
    ;;     (status codes other than 200-207, 300-303, or 307) or
    ;;     in any of other exceptional situations (e.g. when the
    ;;     lib is unable to retrieve the response body).
    (method-fn (assoc tg-bot-api-client :async async?))
    (catch Throwable client-code-ex
      (u-log/error! client-code-ex "An error in the client code")
      ;; NB: An `error` is processed by the `handle-request-res`.
      {:error client-code-ex})))

;;

(defn- call-callback-fn
  [callback-fn arg-val]
  (when (and (some? callback-fn) (not= :ignore callback-fn))
    (callback-fn arg-val)))

(defn- handle-req-res-sync
  [req-res on-success on-failure on-error]
  (when (some? req-res)
    (u-log/debug! "Telegram Bot API returned: %s" req-res)
    ;; NB: The order of checks here is crucial. First, we handle valid responses,
    ;;     including unsuccessful ones (also contain `:error` key). Only then we
    ;;     handle other `:error`-containing results, i.e. HTTP client exceptions.
    (cond
      (tg-utils/valid-response? req-res)
      (if (tg-utils/successful-response? req-res)
        (call-callback-fn on-success req-res)
        (call-callback-fn on-failure req-res))
      ;;
      (contains? req-res :error)
      (call-callback-fn on-error (:error req-res))
      ;;
      :else
      (throw (IllegalStateException. "Malformed Telegram Bot API response")))))

(defn- handle-req-res-async
  [req-res on-success on-failure on-error]
  (go (handle-req-res-sync (<! req-res) on-success on-failure on-error)))

;;

;; TODO: Add a shared rate limiter (use some full-featured resilience library, e.g. 'diehard').
;;       This is to overcome the recent error — HTTP 429 — "Too many requests, retry after X".
; The Bots FAQ on the official Telegram website lists the following limits on server requests:
; - No more than 1 message per second in a single chat,
; - No more than 20 messages per minute in one group,
; - No more than 30 messages per second in total.
(def max-responses-per-second 1)

;; TODO: Rename the ns to `t.tg-bot-api.client` and fn to just `call-bot-api!`.
(defn make-request!
  "Makes a request to the Telegram Bot API on behalf of the `tg-bot-api-client`.

   By default, this function makes a request synchronously, i.e. it waits for a
   Telegram's response and only then returns, but this behaviour can be changed
   by providing `options`.

   The `method-fn` param is a unary fn that receives the `tg-bot-api-client` and
   sends a request to the Telegram Bot API via 'telegrambot-lib' under the hood.
   Its invocation is wrapped in a `try-catch` block, so that any client-specific
   exception (i.e. not coming from an HTTP client) is re-thrown as is.

   Supported options are:
   - `:async?`
     If `true`, makes the whole process asynchronous — will return immediately
     with a one-off channel which will receive the result of some callback fn;
     by default, is `false` (synchronous request processing)
   - `:on-success`
     A callback fn to handle a Telegram Bot API response body for a successful
     request (one that contains the `:ok true` entry); by default, returns the
     response `:result`
   - `:on-failure`
     A callback fn to handle a Telegram Bot API response body for unsuccessful
     request (one that contains `:ok false` and `:error <exception>` entries);
     by default, logs the response (without the `:error`, it's of little use),
     then throws an exception; supports `:ignore` value
   - `:on-error`
     A callback fn to handle an arbitrary `Exception` in any other exceptional
     situation that is not related to an unsuccessful request, e.g. when error
     happens upon making a Telegram Bot API request or retrieving the response
     body; logs and rethrows an exception by default; supports `:ignore` value

   In case of a successful request, if not overridden, returns the `on-success`
   callback result: either wrapped in a channel (for asynchronous requests) or
   as a plain value (synchronous).

   In other cases, when `on-failure`/`on-error` was called, if not overridden,
   the result of this fn is undefined (because of an exception being thrown)."
  ([tg-bot-api-client method-fn]
   (make-request! tg-bot-api-client method-fn nil))
  ([tg-bot-api-client method-fn
    {:keys [async? on-success on-failure on-error]
     :or   {async?     false
            on-success get-result
            on-failure log-failure-reason-and-throw
            on-error   log-error-and-rethrow}
     :as   _options}]
   (when-some [req-res (call-bot-api-method! tg-bot-api-client method-fn async?)]
     (if async?
       (handle-req-res-async req-res on-success on-failure on-error)
       (handle-req-res-sync req-res on-success on-failure on-error)))))
