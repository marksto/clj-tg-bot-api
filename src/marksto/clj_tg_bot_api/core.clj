(ns marksto.clj-tg-bot-api.core
  "Provides a convenient wrapper (a client library) around the Telegram Bot API
   adding handy callback fns (operations) handling responses (success, failure)
   and any errors (exception)."
  (:require [marksto.clj-tg-bot-api.impl.client.core :as client]))

;;; Bot API client

(defn ->client
  "Instantiates a Telegram Bot API client for a particular bot using the given
   `client-opts`.

   Supported `client-opts`:
   - `:bot-id`      — (mandatory) a bot identifier, usually a string or keyword,
                      that is used to distinguish between multiple clients, e.g.
                      for the purposes of rate limiting;
   - `:bot-token`   — (mandatory) a Telegram Bot API auth token for a given bot;
   - `:server-url`  — a Local Bot API Server URL; by default, uses a global one;
                      the provided `:bot-token` value gets appended to this URL;
   - `:limit-rate?` — when `true` (default), will use the built-in rate limiter;
                      otherwise, will bypass it, which is useful e.g. in tests;
   - `:response-fn` — a no-arg fn used to dynamically generate Telegram Bot API
                      server responses, effectively mocking real HTTP requests."
  {:arglists '([& {:keys [bot-id bot-token server-url limit-rate? response-fn]
                   :as   client-opts}])}
  [& {:as client-opts}]
  (client/->client client-opts))

;;; Making Requests

(defn make-request!
  "Makes a request to the Telegram Bot API `method` (keyword) on behalf of some
   bot using a given `client`, a `params` map, if any, as payload, and optional
   `call-opts` map.

   Supported `call-opts`:
   - `:on-success` — a unary callback fn of a response body for a successful
                     request, i.e. when the body contains the `:ok true` entry;
                     by default, returns the response `:result`;
   - `:on-failure` — a ternary callback fn of `method`, `params` and a response
                     body for an unsuccessful request, i.e. when the body goes
                     with `:ok false` and `:error` entries;
                     by default, logs the response and throws an exception that
                     has `:response`, `:method` and `:params` keys in its data;
                     supports `:ignore` value;
   - `:on-error`   — a ternary callback fn of `method`, `params` and arbitrary
                     `Exception` object for all exceptions that aren't related
                     to an unsuccessful request, i.e. if an error happens upon
                     making an HTTP request or retrieving the response body;
                     by default, logs and rethrows an exception;
                     supports `:ignore` value;

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

(defn build-immediate-response
  "Builds an immediate response — for making a request to the Telegram Bot API
   `method` (keyword) on behalf of a bot using a given `client` and a `params`
   map, if any, while responding to an incoming update received via the bot's
   webhook.

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
   (client/build-immediate-response client method))
  ([client method params]
   (client/build-immediate-response client method params)))

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
