(ns teleoperator.tg-bot-api-client.interface
  (:require [teleoperator.tg-bot-api-client.core :as impl]))

(def max-responses-per-second impl/max-responses-per-second)

(defn call-bot-api!
  "Makes a request to the Telegram Bot API on behalf of the `tg-bot-api-client`.

   By default, this function makes a request synchronously, i.e. it waits for a
   Telegram's response and only then returns, but this behaviour can be changed
   by providing `options`.

   A `method-fn` is a fn of the `tg-bot-api-client` and, possibly, other params
   needed to make an actual HTTP request to the Telegram Bot API method via the
   'telegrambot-lib' under the hood. Its invocation is wrapped in a `try-catch`
   block, so that any exceptions in the client code (i.e. those not coming from
   the HTTP client) are re-thrown as is.

   Supported `call-opts` are:
   - `:async?`
     If `true`, makes the whole process asynchronous — will return immediately
     with a one-off channel which will receive the result of some callback fn;
     by default, is `false` (synchronous request processing)
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
   callback result: either wrapped in a channel (for asynchronous requests) or
   as a plain value (synchronous).

   In other cases, when `on-failure`/`on-error` was called, if not overridden,
   the result of this fn is undefined (because of an exception being thrown)."
  {:arglists '([tg-bot-api-client method-fn & method-args]
               [tg-bot-api-client call-opts method-fn & method-args])}
  [tg-bot-api-client & args]
  (impl/call-bot-api! tg-bot-api-client args))

(def get-result impl/get-result)
(def assert-result impl/assert-result)

(def log-failure-reason impl/log-failure-reason)
(def log-failure-reason-and-throw impl/log-failure-reason-and-throw)
(def throw-for-failure impl/throw-for-failure)

(def log-error impl/log-error)
(def log-error-and-rethrow impl/log-error-and-rethrow)
(def rethrow-error impl/rethrow-error)
