[![Clojars Project](https://img.shields.io/clojars/v/com.github.marksto/clj-tg-bot-api.svg)](https://clojars.org/com.github.marksto/clj-tg-bot-api)
[![CI](https://github.com/marksto/clj-tg-bot-api/actions/workflows/ci.yml/badge.svg)](https://github.com/marksto/clj-tg-bot-api/actions)
[![License](https://img.shields.io/github/license/marksto/clj-tg-bot-api.svg)](LICENSE)

# Clojure Telegram Bot API

The latest [Telegram Bot API](https://core.telegram.org/bots/api) specification and client lib for Clojure-based applications.

![Clojure Telegram Bot API logo](docs/clj-tg-bot-api-logo.jpg)

> _“Technology gives us the facilities that lessen the barriers of time and distance – the telegraph and cable, the telephone, radio, and the rest.”_
><br/>— Emily Greene Balch

## Introduction

The `clj-tg-bot-api` is an idiomatic, data-driven Clojure client for the *latest* Telegram Bot API. It auto-synchronizes API method definitions and validation schemas straight from the official docs — so you never fall behind on new Telegram bot features.

It exposes a uniform interface with just [3 primary functions](#basics) and uses Martian under the hood to deliver a pluggable HTTP layer, parameter validation, rich testing support (without brittle global mocks), and production-ready essentials like rate limiting — so you can focus on your bot's core logic.

## Features

* **Comes with one-of-a-kind Telegram Bot API specification**
  - based off of the official Bot API [documentation page](https://core.telegram.org/bots/api)
  - with regular, automated updates — never miss a new Bot API version again!
* **Uniform and idiomatic Clojure interface**
  - no fancy n-ary functions for methods — [just a keyword and a map of params](#available-methods)
  - basic validation/coercion of method parameters to their schemas
  - auto-conversion of dashes to underscores in param keys
* **Does things at the right level of abstraction**
  - it's a simple API client library, not an opinionated framework
  - modular from the ground up — choose and use only what you need
  - `core` has zero "moving parts" — even [an HTTP client is pluggable](#supported-http-clients)!
  - `core` leaves the execution/concurrency model up to your discretion
* **Built on top of [Martian](https://github.com/oliyh/martian) for the best possible feature set**
  - highly configurable via interceptor chain with reasonable defaults
  - superior testing experience — without brittle global mocks
  - recording and playing back responses in a VCR style
* **Production-ready built-in essentials**
  - `updates` module — to uniformly set up and tear down [updates retrieval](#getting-updates)
  - [rate limiting](#rate-limiting) — with conservative defaults, yet re-configurable
  - support for multipart requests and JSON-serialized params
  - HTTP response maps — for [replying to incoming updates](https://core.telegram.org/bots/api#making-requests-when-getting-updates)
  - support for multiple bots running simultaneously
  - set of simple, frequently used utility functions

## Installation

Add the following dependencies to your `deps.edn` or `project.clj` file:

### Latest version

Add the required dependency to the `core` module. Other modules are optional.

| Module    | Project dependency                                                                                                                                          |
|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `core`    | [![Clojars](https://img.shields.io/clojars/v/com.github.marksto/clj-tg-bot-api.svg)](https://clojars.org/com.github.marksto/clj-tg-bot-api)                 |
| `updates` | [![Clojars](https://img.shields.io/clojars/v/com.github.marksto/clj-tg-bot-api.updates.svg)](https://clojars.org/com.github.marksto/clj-tg-bot-api.updates) |

### Supported HTTP clients

Add any supported HTTP client of your choice via a Martian module.

| Target HTTP client | Extra project dependency                                                                                                                              |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| `http-kit`         | [![Clojars](https://img.shields.io/clojars/v/com.github.oliyh/martian-httpkit.svg)](https://clojars.org/com.github.oliyh/martian-httpkit)             |
| `clj-http`         | [![Clojars](https://img.shields.io/clojars/v/com.github.oliyh/martian-clj-http.svg)](https://clojars.org/com.github.oliyh/martian-clj-http)           |
| `clj-http-lite`    | [![Clojars](https://img.shields.io/clojars/v/com.github.oliyh/martian-clj-http-lite.svg)](https://clojars.org/com.github.oliyh/martian-clj-http-lite) |
| `hato`             | [![Clojars](https://img.shields.io/clojars/v/com.github.oliyh/martian-hato.svg)](https://clojars.org/com.github.oliyh/martian-hato)                   |

⚠️ Since the library uses a number of JVM-specific dependencies, there is currently no support for running it with `bb` and `org.babashka/http-client`.

## Usage

### Basics

First, create a Telegram Bot API client instance for the bot. Use it to make API requests with the `make-request!` function. Alternatively, use the `build-response` function to construct an HTTP response map for [replying to an incoming update via the bot's webhook](https://core.telegram.org/bots/api#making-requests-when-getting-updates).

```clojure
(ns my.bot.core
  (:require [marksto.clj-tg-bot-api.core :as tg-bot-api]))

;; Create a client for the bot
(def client (tg-bot-api/->client {:bot-token "<TG_BOT_AUTH_TOKEN>"}))

;; Test the bot's authentication token
(tg-bot-api/make-request! client :get-me)
;=> {:id         27644437
;    :is_bot     true
;    :first_name "My fluffy bot"}

;; Make a request
(tg-bot-api/make-request! client :send-message {:chat-id 2946901
                                                :text    "Hello, world!"})
;=> {:message_id 1
;    :from       {:id 27644437 ...}
;    :chat       {:id 2946901 ...}
;    :date       1234567890
;    :text       "Hello, world!"}

;; or

;; Build an HTTP response map for replying to an incoming update
(tg-bot-api/build-response client :send-message {:chat-id 2946901
                                                 :text    "Hello, world!"})
;=> {:status  200
;    :headers {"Content-Type" "application/json", "Accept" "application/json"}
;    :body    "{\"chat_id\":2946901,\"text\":\"Hello, world!\",\"method\":\"sendMessage\"}"}
```

### Available Methods

Use the `marksto.clj-tg-bot-api.core/explore` helper function on a Telegram Bot API client instance to see the full list of available methods (keywords with summaries) and detailed information (parameters schema) about any of them:

```clojure
(tg-bot-api/explore client)
;=> [[:get-updates "..."]
;    [:set-webhook "..."]
;    [:delete-webhook "..."]
;    [:get-webhook-info "..."]
;    [:get-me "..."]
;    [:log-out "..."]
;    [:close "..."]
;    [:send-message "..."]
;    ...]

(tg-bot-api/explore client :get-updates)
;=> {:summary    "<p>Use this method to receive incoming updates using long polling ..."
;    :parameters {#schema.core.OptionalKey{:k :offset}          Int
;                 #schema.core.OptionalKey{:k :limit}           Int
;                 #schema.core.OptionalKey{:k :timeout}         Int
;                 #schema.core.OptionalKey{:k :allowed-updates} [java.lang.String]}}
```

### Advanced Client Options

The library also provides you with several advanced options for configuring the client and individual API method calls.

#### Local Bot API Server

If you are [using a Local Bot API Server](https://core.telegram.org/bots/api#using-a-local-bot-api-server), simply pass the `:server-url` string, which will be used instead of the global one.

```clojure
(def client (tg-bot-api/->client {:bot-token  "<TG_BOT_AUTH_TOKEN>"
                                  :server-url "http://localhost:1234/bot"}))
```

#### Rate Limiting

The built-in rate limiter is conservative. It aims to avoid exceeding the API limits as much as possible by following the [Telegram Bot API FAQ](https://core.telegram.org/bots/faq#my-bot-is-hitting-limits-how-do-i-avoid-this) and applying the following defaults for "sendMessage" and "editMessageText" (which share limits):
- No more than    1 message   per second in a single chat,
- No more than 20 messages per minute  in the same group chat,
- No more than 30 messages per second in total (for broadcasting).

While this helps to avoid HTTP 429 "Too many requests, retry after N" error in some cases, in others it can feel quite or not enough limiting (pun intended), especially if you are good to go with [paid broadcasts](https://core.telegram.org/bots/faq#how-can-i-message-all-of-my-bot-39s-subscribers-at-once). Therefore, it can be fine-tuned or completely reconfigured as follows:

```clojure
;; Example custom limiter opts that get deep merged into the default ones

;; Adding a limit on a certain method (in this case, for "setMyCommands")
(def custom-limiter-opts {:set-my-commands {:in-total {:rate 1}}})

;; Dropping "30 messages per second in total" limit (for paid broadcasts)
(def custom-limiter-opts {:send-message {:in-total nil}})

(def client (tg-bot-api/->client {:bot-token    "<TG_BOT_AUTH_TOKEN>"
                                  :limiter-opts custom-limiter-opts}))
```

See the `marksto.clj-tg-bot-api.impl.client.rate-limiter` namespace for default settings and the `diehard.rate-limiter` namespace for the full list of supported rate limiter options.

Alternatively, if you prefer to run at full speed and handle HTTP 429 errors yourself — basically, [retrying after](https://core.telegram.org/bots/api#responseparameters) the specified time — set `:limiter-opts` to `nil` to bypass the built-in rate limiter. This is usually preferable in tests.

#### Testing

The `:responses` option is used for generating Telegram Bot API server responses, effectively mocking real HTTP requests during tests. Its value is either a map (from method to predefined response or request->response generator function), or a unary function (that returns a response for the given `ctx` map, which most importantly contains a `:request`, but also comes with other details about the call).

```clojure
;; Providing a static map of responses
(def client (tg-bot-api/->client {:bot-token "<TG_BOT_AUTH_TOKEN>"
                                  :responses {:get-me {:status 200
                                                       :body   {:ok     true
                                                                :result {:id         12345678
                                                                         :is_bot     true
                                                                         :first_name "Testy"
                                                                         :username   "test_username"}}}}}))
(tg-bot-api/make-request! client :get-me)
;=> {:id 12345678, :is_bot true, :first_name "Testy", :username "test_username"}

;; Providing a map of response generators
(def client (tg-bot-api/->client {:bot-token "<TG_BOT_AUTH_TOKEN>"
                                  :responses {:get-me (fn [_request]
                                                        (case (rand-int 2)
                                                          0 {:status 200
                                                             :body   {:ok     true
                                                                      :result {:id         12345678
                                                                               :is_bot     true
                                                                               :first_name "Testy"
                                                                               :username   "test_username"}}}
                                                          ;; assuming `clj-http` is used which itself throws exceptions
                                                          1 (throw (ex-info "clj-http: status 404"
                                                                            {:status 404
                                                                             :body   "{\"ok\":false,\"error_code\":404,\"description\":\"Not Found\"}"}))))}}))
(tg-bot-api/make-request! client :get-me)
;=> {:id 12345678, :is_bot true, :first_name "Testy", :username "test_username"}
(tg-bot-api/make-request! client :get-me)
;=> ExceptionInfo: Interceptor Exception: clj-http: status 404 {..., :exception #error {
;   :cause "clj-http: status 404"
;   :data {:status 404, :body "{\"ok\":false,\"error_code\":404,\"description\":\"Not Found\"}"}
;   ...}
```

The `martian.test/respond-with` function from the `martian-test` module is used under the hood. You might find it useful to explore its source code.

Additionally, it usually makes sense to set the `:limiter-opts` to `nil` for tests to bypass the built-in rate limiter and speed things up.

#### Custom Behaviour

Any custom behavior can be added via [Tripod interceptors](https://github.com/frankiesardo/tripod#interceptors), which are used by Martian under the hood for everything from setting and validating parameters to performing an HTTP request.

The `:interceptors` option is used for injecting custom interceptors into the basic interceptor chain. Each element of this coll is a vector of the form `[interceptor rel-position basic-name]`, where:
- `interceptor`  — a new interceptor to add, or a `nil` to remove;
- `rel-position` — a keyword ∈ `#{:before :after :replace}`;
- `basic-name`   — the name of some basic interceptor.

Check out the `marksto.clj-tg-bot-api.core-i9n-test` namespace for an example of leveraging the `:interceptors` option to enable VCR-based testing using the `martian-vcr` module.

### Call Options

The `make-request!` function supports the following call options:
- `:on-success` — a unary callback function of a response body containing an `:ok true` entry which indicates that the request was successful; by default, returns `:result` of the response;
- `:on-failure` — a ternary callback function of `method`, `params`, and  response body containing `:ok false` and `:error` entries indicating that the request was unsuccessful; by default, logs the response and throws an exception; supports `:ignore` value;
- `:on-error` — a ternary callback function of `method`, `params`, and any exception; by default, logs and rethrows the specified exception; supports `:ignore` value;
- other entries — HTTP client-specific options for making requests, such as timeouts, redirect policy, proxy, etc., that go as is into the request map.

While you can always pass in a custom `:on-success`/`:on-failure`/`:on-error` implementation, the `marksto.clj-tg-bot-api.core` namespace comes with a set of common ones that can be used as any of these callback functions:

```clojure
(def client (tg-bot-api/->client {:bot-token "<TG_BOT_AUTH_TOKEN>"}))

;; Asserting the result of a successful request
(tg-bot-api/make-request! client
                          {:on-success #(tg-bot-api/assert-result {:is_bot false} %)}
                          :get-me)
;=> ExceptionInfo: The Telegram Bot API method returned an unexpected result {:expected {:is_bot false}, :actual {:is_bot true, ...}}

;; Returning an error map upon an unsuccessful request
(tg-bot-api/make-request! client
                          {:on-failure tg-bot-api/get-error}
                          :send-message
                          {:chat-id 1
                           :text    "Hello, world!"})
;=> {:error_code 400,
;    :description "Bad Request: chat not found",
;    :method :send-message,
;    :params {:chat-id 1, :text "Hello, world!"}}

;; Ignoring all unsuccessful requests and errors
(tg-bot-api/make-request! client
                          {:on-failure :ignore
                           :on-error   :ignore}
                          :send-message
                          {:chat-id 1
                           :text    "Hello, world!"})
;=> nil
```

Examples of passing HTTP client-specific options for making requests as `make-request!` call options:

```clojure
;; Printing request info to *out*, including request body with 'clj-http':
;; - https://github.com/dakrone/clj-http#debugging
(tg-bot-api/make-request! client
                          {:debug      true
                           :debug-body true}
                          :get-me)

;; Specifying a per-request proxy settings with 'clj-http' and 'httpkit':
;; - https://github.com/dakrone/clj-http#proxies
;; - https://cljdoc.org/d/http-kit/http-kit/2.9.0-beta3/api/org.httpkit.client#request
(tg-bot-api/make-request! client
                          {:proxy-host "127.0.0.1"
                           :proxy-port 8118}
                          :send-message
                          {:chat-id 1
                           :text    "Hello, world!"})

;; Reusable client (persistent connections, connection pooling) with 'hato':
;; - https://github.com/gnarroway/hato#building-a-client
;; - https://github.com/dakrone/clj-http#persistent-connections
;; - https://github.com/http-kit/http-kit/wiki/2-Client#persistent-connections
(require '[hato.client :as hc])
(def http-client (hc/build-http-client {:connect-timeout 10000
                                        :redirect-policy :always}))
(def shared-call-opts {:http-client http-client})
(tg-bot-api/make-request! client
                          shared-call-opts
                          :get-me)
(tg-bot-api/make-request! client
                          shared-call-opts
                          :send-message
                          {:chat-id 1
                           :text    "Hello, world!"})
```

### Getting Updates

As stated in [the Bot API documentation](https://core.telegram.org/bots/api#getting-updates), there are 2 mutually exclusive ways to get updates for the bot — via [a webhook URL](#webhook) xor via [long-polling process](#long-polling).

The `updates` module provides conventional means to:
- uniformly set up and tear down the way a Telegram bot gets updates
- dynamically switch between these ways at runtime

#### Webhook

You specify a public HTTPS endpoint through which the Telegram Bot API will provide pending updates to the bot.

This way of receiving updates is common for a remotely deployed bot, but arranging it in local dev env can be tricky, since a self-signed certificate, a static IP address with a proper router configuration, and/or a reverse proxy service (such as ngrok) is necessary for this setup to work.

Read the official [Marvin's Marvellous Guide to All Things Webhook](https://core.telegram.org/bots/webhooks) for a more detailed overview.

The `marksto.clj-tg-bot-api.updates.core` namespace comes with a `setup-webhook!` function, which plays well with any system/state management library. Read its docstring for details.

#### Long-polling

Bot performs long-polling of server updates itself, which means a continuous calling of the 'getUpdates' Telegram Bot API method and waiting to receive any incoming updates over this time, then making the same call again.

This way is totally fine for a local debugging/testing purposes, but it turns out to be slower and more error-prone than listening to a dedicated webhook, a recommended option for Production environment.

The `marksto.clj-tg-bot-api.updates.core` namespace comes with a `setup-long-polling!` and `teardown-long-polling!` functions, which play well with any system/state management library. Read their docstring for details.

#### Switching

The `updates` module also allows to dynamically switch between these ways at runtime. This is helpful in REPL-driven development of a bot and can be used to reroute the incoming updates from a remotely deployed instance to your local one for debugging purposes.

This will require you to provide parameters with options for both webhook and long-polling.

⚠️ When switching, mind the database connection or any other external stateful service connection, if your bot happens to use them.

### Utilities

The library also provides a set of utility functions — for Telegram Bot API types, updates and responses — available for use through the `marksto.clj-tg-bot-api.utils` namespace. Feel free to check them out!

## Rationale

Telegram Bot API doesn't publish an OpenAPI/Swagger definition, so every client library ends up hand-crafting endpoint lists, parameter checks, and HTTP plumbing — inevitably drifting behind upstream changes and hiding subtle bugs.

Manually updating method definitions and validation logic eats up the development budget and slows down adoption of new API features — especially since the Telegram Bot API has been experiencing rapid development lately.

As an enthusiast building bots daily, I've found that existing Clojure client libraries either lag behind, impose unnecessary boilerplate, or lack key features. I needed a lean, API spec-driven solution that would be easy to maintain and would natively support all the necessary use cases.

## License

Copyright © 2026 Mark Sto

Licensed under [EPL 1.0](LICENSE) (same as Clojure).
