(ns marksto.clj-tg-bot-api.core
  "Provides a convenient wrapper (a client library) around the Telegram Bot API
   adding handy callback fns (operations) handling responses (success, failure)
   and any errors (exception)."
  (:require [marksto.clj-tg-bot-api.impl.client :as impl]))

(defn ->client
  "Instantiates a Telegram Bot API client for a particular bot using the given
   `client-opts`."
  {:arglists '([{:keys [bot-auth-token server-url] :as client-opts}])}
  [client-opts]
  (impl/->client client-opts))

;;

(defn make-request!
  "Makes a request to the Telegram Bot API on behalf of the `tg-bot-api-client`.

   It makes a request in a synchronous blocking way, therefore it is up to the
   caller to take care of making the call async/non-blocking, for instance, by
   wrapping it into a virtual thread.

   A `method-fn` is a fn of the `tg-bot-api-client` and, possibly, other params
   needed to make an actual HTTP request to the Telegram Bot API method.

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

   Any exception in the client code (i.e. those not coming from an HTTP client)
   is re-thrown as is.

   In case of a successful request, if not overridden, returns the `on-success`
   callback result.

   In other cases, when `on-failure`/`on-error` was called, if not overridden,
   the result of this fn is undefined (because of an exception being thrown)."
  {:arglists '([tg-bot-api-client method-fn & method-args]
               [tg-bot-api-client call-opts method-fn & method-args])}
  [tg-bot-api-client & args]
  (impl/make-request! tg-bot-api-client args))

(defn build-immediate-response
  "Builds an immediate response, a payload for making a request to the Bot API
   while responding to an incoming update received via the webhook.

   From the Telegram Bot API documentation:
   If you're using *webhooks*, you can perform a request to the Bot API while
   sending an answer to the webhook. <...> Specify the _method_ to be invoked
   in the method parameter of the request. It's not possible to know that such
   a request was successful or get its result.

   This technique helps to reduce the number of requests to the Bot API server
   and, as a consequence, helps us to stay under the limits on server requests
   and sometimes improves the responsiveness of the bot's UI on the client.

   See https://core.telegram.org/bots/api#making-requests-when-getting-updates"
  [tg-bot-api-client method-name & {:as method-args}]
  (impl/build-immediate-response tg-bot-api-client method-name method-args)

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
   To be used as an `:on-error` callback fn.

   NB: If called not from a REPL, clears the stack trace of the exception
       being rethrown."
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
