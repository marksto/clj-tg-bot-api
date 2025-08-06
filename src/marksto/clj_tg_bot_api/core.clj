(ns marksto.clj-tg-bot-api.core
  "Provides a convenient wrapper (a client library) around the Telegram Bot API
   adding handy callback fns (operations) handling responses (success, failure)
   and any errors (exception)."
  (:require [marksto.clj-tg-bot-api.impl.client.core :as client]))

;;; Bot API client

(defn ->client
  "Creates a Telegram Bot API client instance using the provided `client-opts`.

   The `:bot-token` is a mandatory Bot API authentication token (string) of the
   Telegram bot that will be served by this client.

   When providing a fake `:bot-token` value for tests, make sure it follows the
   official format and starts with a numeric ID, as it is used to differentiate
   between multiple clients.

   Advanced options:
   - `:server-url`   — a Local Bot API Server URL (string); uses a global one by
                       default; the provided `:bot-token` value gets appended to
                       this URL;
   - `:limit-rate?`  — if `true` (default), will use the built-in rate limiter;
                       otherwise, will bypass it; used primarily during tests;
   - `:responses`    — a map from method to predefined response/generator fn or
                       a unary fn that, given the `ctx`, returns a response; it
                       is used for generating Telegram Bot API server responses,
                       effectively mocking real HTTP requests during tests;
   - `:interceptors` — custom interceptors to inject into the basic interceptor
                       chain; each element of this coll is a vector of the form
                       `[interceptor rel-pos basic-name]`, where:
                       - `interceptor` — a new object to add or `nil` to remove;
                       - `rel-pos`     — may be `:before`, `:after`, `:replace`;
                       - `basic-name`  — the name of some basic interceptor.

  Returns a client instance for making requests on behalf of the Telegram bot."
  {:arglists '([& {:keys [bot-token server-url limit-rate? responses interceptors]
                   :as   client-opts}])}
  [& {:as client-opts}]
  (client/->client client-opts))

;;; Making Requests

(defn make-request!
  "Makes a request to the Telegram Bot API `method` (keyword) on behalf of some
   bot, using the given `client`, an optional `params` map as the payload, and
   an optional `call-opts` map.

   Supported `call-opts`:
   - `:on-success` — a unary fn of a response body containing an `:ok true`
                     entry which indicates that the request was successful;
                     by default, returns `:result` of the response;
   - `:on-failure` — a ternary fn of `method`, `params`, and  response body
                     containing `:ok false` and `:error` entries indicating
                     that the request was unsuccessful;
                     by default, logs the response and throws an exception;
                     supports `:ignore` value;
   - `:on-error`   — a ternary fn of `method`, `params`, and any exception;
                     by default, logs and rethrows the specified exception;
                     supports `:ignore` value.

   In case of a successful request, if not overridden, returns the `on-success`
   callback result.

   In other cases, when `on-failure`/`on-error` gets called, incl. exceptions
   in the client code (e.g. params schema coercion errors), if not overridden
   by custom callbacks, the result of this fn is undefined due to an exception
   being thrown.

   It makes a request in a synchronous blocking way, therefore it is up to the
   caller to take care of making the call async/non-blocking, for instance, by
   wrapping it into a virtual thread (a recommended approach on JVM 21+).

   See https://core.telegram.org/bots/api#making-requests"
  {:arglists '([client call-opts? method params?])}
  [client & args]
  (client/make-request! client args))

(defn build-response
  "Constructs an HTTP response map for replying to an incoming update received
   via the bot's webhook, triggering a call to the specified Telegram Bot API
   `method` (keyword) call, using the given `client` and optional `params` map.

   From the Telegram Bot API documentation:
   If you're using *webhooks*, you can perform a request to the Bot API while
   sending an answer to the webhook. <...> Specify the _method_ to be invoked
   in the method parameter of the request. It's not possible to know that such
   a request was successful or get its result.

   This technique helps to reduce the number of requests to the Bot API server
   and, as a consequence, helps us to stay under the limits on server requests
   and sometimes improves the responsiveness of the bot's UI on the client.

   See https://core.telegram.org/bots/api#making-requests-when-getting-updates"
  {:arglists '([client method params?])}
  ([client method]
   (client/build-response client method))
  ([client method params]
   (client/build-response client method params)))

;;; On-Success Callbacks

(defn get-result
  "Returns a successful response, otherwise a given `default-val` or `nil`.
   To be used as an `:on-success` callback fn."
  ([tg-resp]
   (client/get-result tg-resp))
  ([tg-resp default-val]
   (client/get-result tg-resp default-val)))

(defn assert-result
  "Checks that a response is successful and equals to the `expected` value.
   To be used as an `:on-success` callback fn."
  [expected tg-resp]
  (client/assert-result expected tg-resp))

;;; On-Failure Callbacks

(defn get-error
  "For an unsuccessful Telegram Bot API request, returns the response error.
   To be used as an `:on-failure` callback fn."
  [method params failed-tg-resp]
  (client/get-error method params failed-tg-resp))

(defn log-failure-reason
  "For an unsuccessful Telegram Bot API request, logs the response error.
   To be used as an `:on-failure` callback fn."
  ([method params failed-tg-resp]
   (client/log-failure-reason
     method params failed-tg-resp))
  ([base-msg method params failed-tg-resp]
   (client/log-failure-reason
     base-msg method params failed-tg-resp))
  ([log-level base-msg method params failed-tg-resp]
   (client/log-failure-reason
     log-level base-msg method params failed-tg-resp)))

(defn log-failure-reason-and-throw
  "For an unsuccessful Telegram Bot API request, logs the response error and
   throws an exception w/ relevant data (`:response`, `:method`, `:params`).
   To be used as an `:on-failure` callback fn."
  ([method params failed-tg-resp]
   (client/log-failure-reason-and-throw
     method params failed-tg-resp))
  ([base-msg method params failed-tg-resp]
   (client/log-failure-reason-and-throw
     base-msg method params failed-tg-resp))
  ([log-level base-msg method params failed-tg-resp]
   (client/log-failure-reason-and-throw
     log-level base-msg method params failed-tg-resp)))

(defn throw-for-failure
  "For an unsuccessful Telegram Bot API request, simply throws an exception
   with relevant data (`:response`, `:method`, `:params`), without logging.
   To be used as an `:on-failure` callback fn."
  ([method params failed-tg-resp]
   (client/throw-for-failure method params failed-tg-resp))
  ([base-msg method params failed-tg-resp]
   (client/throw-for-failure base-msg method params failed-tg-resp)))

;;; On-Error Callbacks

(defn log-error
  "For in case there was an error while making a Telegram Bot API request,
   logs the corresponding exception `ex`.
   To be used as an `:on-error` callback fn."
  ([method params ex]
   (client/log-error method params ex))
  ([base-msg method params ex]
   (client/log-error base-msg method params ex)))

(defn log-error-and-rethrow
  "For in case there was an error while making a Telegram Bot API request,
   logs the corresponding exception `ex`, wraps it in a new exception with
   relevant data (`:method` and `:params`) and `ex` as its cause, and then
   throws the latter one.
   To be used as an `:on-error` callback fn."
  ([method params ex]
   (client/log-error-and-rethrow method params ex))
  ([base-msg method params ex]
   (client/log-error-and-rethrow base-msg method params ex)))

(defn rethrow-error
  "For in case there was an error while making a Telegram Bot API request,
   wraps the corresponding exception `ex` in a new exception with relevant
   data (`:method` and `:params`) and `ex` as its cause, and throws it w/o
   logging.
   To be used as an `:on-error` callback fn."
  [method params ex]
  (client/rethrow-error method params ex))
