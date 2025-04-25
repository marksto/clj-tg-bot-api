(ns teleoperator.tg-bot-api-client.interface
  (:require [teleoperator.tg-bot-api-client.core :as impl]))

(def max-responses-per-second impl/max-responses-per-second)

(defn call-bot-api!
  "Makes a request to the Telegram Bot API on behalf of the `tg-bot-api-client`.

   It makes a request in a synchronous blocking way, therefore it is up to the
   caller to take care of making the call async/non-blocking, for instance, by
   wrapping it into a virtual thread.

   A `method-fn` is a fn of the `tg-bot-api-client` and, possibly, other params
   needed to make an actual HTTP request to the Telegram Bot API method via the
   'telegrambot-lib' under the hood. Its invocation is wrapped in a `try-catch`
   block, so that any exceptions in the client code (i.e. those not coming from
   the HTTP client) are re-thrown as is.

   Supported `call-opts` are:
   - `:on-success`
     A unary callback fn of response that handles a Telegram Bot API response
     body for a successful request, i.e. one containing the `:ok true` entry;
     by default, returns the response `:result`
   - `:on-failure`
     A ternary callback fn of `method-fn`, `method-args` and failure response
     that handles a Telegram Bot API response body of an unsuccessful request,
     i.e. the one that contains `:ok false` and `:error <exception>` entries);
     by default, logs the response (without the `:error`, it's of little use),
     then throws an exception; supports `:ignore` value
   - `:on-error`
     A ternary callback fn of `method-fn`, `method-args` and `:error` response
     that handles an arbitrary `Exception` in all other exceptional situations
     that are not related to an unsuccessful request, i.e. if an error happens
     upon making a Telegram Bot API request or retrieving the response body;
     logs and rethrows an exception by default; supports `:ignore` value

   In case of a successful request, if not overridden, returns the `on-success`
   callback result.

   In other cases, when `on-failure`/`on-error` was called, if not overridden,
   the result of this fn is undefined (because of an exception being thrown)."
  {:arglists '([tg-bot-api-client method-fn & method-args]
               [tg-bot-api-client call-opts method-fn & method-args])}
  [tg-bot-api-client & args]
  (impl/call-bot-api! tg-bot-api-client args))

;;

(defn get-result
  "Returns a successful response, otherwise a given `default-val` or `nil`.
   To be used as an `:on-success` callback fn."
  ([tg-resp]
   (impl/get-result tg-resp))
  ([tg-resp default-val]
   (impl/get-result tg-resp default-val)))

(defn assert-result
  "Checks that a response is successful and equals to the `expected` value.
   To be used as an `:on-success` callback fn."
  [expected tg-resp]
  (impl/assert-result expected tg-resp))

;;

(defn get-error
  "For an unsuccessful Telegram Bot API request, returns the response error.
   To be used as an `:on-failure` callback fn."
  [method-fn method-args failed-tg-resp]
  (impl/get-error method-fn method-args failed-tg-resp))

(defn log-failure-reason
  "For an unsuccessful Telegram Bot API request, logs the response error.
   To be used as an `:on-failure` callback fn."
  ([method-fn method-args failed-tg-resp]
   (impl/log-failure-reason
     method-fn method-args failed-tg-resp))
  ([base-msg method-fn method-args failed-tg-resp]
   (impl/log-failure-reason
     base-msg method-fn method-args failed-tg-resp))
  ([log-level base-msg method-fn method-args failed-tg-resp]
   (impl/log-failure-reason
     log-level base-msg method-fn method-args failed-tg-resp)))

(defn log-failure-reason-and-throw
  "For an unsuccessful Telegram Bot API request, logs the response error and
   then throws an exception with the relevant data (`:response`, `:method-fn`
   and `:method-args`).
   To be used as an `:on-failure` callback fn."
  ([method-fn method-args failed-tg-resp]
   (impl/log-failure-reason-and-throw
     method-fn method-args failed-tg-resp))
  ([base-msg method-fn method-args failed-tg-resp]
   (impl/log-failure-reason-and-throw
     base-msg method-fn method-args failed-tg-resp))
  ([log-level base-msg method-fn method-args failed-tg-resp]
   (impl/log-failure-reason-and-throw
     log-level base-msg method-fn method-args failed-tg-resp)))

(defn throw-for-failure
  "For an unsuccessful Telegram Bot API request, throws an exception with the
   relevant data (`:response`, `:method-fn` and `:method-args`), w/o logging.
   To be used as an `:on-failure` callback fn."
  ([method-fn method-args failed-tg-resp]
   (impl/throw-for-failure method-fn method-args failed-tg-resp))
  ([base-msg method-fn method-args failed-tg-resp]
   (impl/throw-for-failure base-msg method-fn method-args failed-tg-resp)))

;;

(defn log-error
  "For in case there was an error while making a Telegram Bot API request,
   logs the corresponding exception `ex`.
   To be used as an `:on-error` callback fn."
  ([method-fn method-args ex]
   (impl/log-error method-fn method-args ex))
  ([base-msg method-fn method-args ex]
   (impl/log-error base-msg method-fn method-args ex)))

(defn log-error-and-rethrow
  "For in case there was an error while making a Telegram Bot API request,
   logs the corresponding exception `ex`, wraps it in a new exception that
   has the relevant data (`:method-fn` and `:method-args`) and `ex` as its
   cause, and then throws the latter one.
   To be used as an `:on-error` callback fn."
  ([method-fn method-args ex]
   (impl/log-error-and-rethrow method-fn method-args ex))
  ([base-msg method-fn method-args ex]
   (impl/log-error-and-rethrow base-msg method-fn method-args ex)))

(defn rethrow-error
  "For in case there was an error while making a Telegram Bot API request,
   wraps the corresponding exception `ex` in a new exception that has the
   relevant data (`:method-fn` and `:method-args`) and `ex` as its cause,
   and throws the latter one w/o logging.
   To be used as an `:on-error` callback fn."
  [method-fn method-args ex]
  (impl/rethrow-error method-fn method-args ex))
