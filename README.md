[![Clojars Project](https://img.shields.io/clojars/v/com.github.marksto/clj-tg-bot-api.svg)](https://clojars.org/com.github.marksto/clj-tg-bot-api)
[![CI](https://github.com/marksto/clj-tg-bot-api/actions/workflows/ci.yml/badge.svg)](https://github.com/marksto/clj-tg-bot-api/actions)
[![License](https://img.shields.io/github/license/marksto/clj-tg-bot-api.svg)](LICENSE)

# Clojure Telegram Bot API

The latest [Telegram Bot API](https://core.telegram.org/bots/api) specification and client lib for Clojure-based apps.

![Clojure Telegram Bot API logo](docs/clj-tg-bot-api-logo.jpg)

> _“Technology gives us the facilities that lessen the barriers of time and distance – the telegraph and cable, the telephone, radio, and the rest.”_
><br/>— Emily Greene Balch

## Introduction

The `clj-tg-bot-api` is an idiomatic, data-driven Clojure client for the *latest* Telegram Bot API. It auto-synchronizes API method definitions and validation schemas straight from the official docs — so you never fall behind on new Telegram bot features.

It exposes a uniform interface with just **3 primary functions** and uses Martian under the hood to deliver a pluggable HTTP layer, parameter validation, rich testing support (without brittle global mocks), and production-ready essentials like rate limiting — so you can focus on your bot's core logic.

## Features

* **Comes with one-of-a-kind Telegram Bot API specification**
  - based off of the official Bot API [documentation page](https://core.telegram.org/bots/api)
  - with regular, automated updates — never miss a new Bot API version again!
* **Uniform and idiomatic Clojure interface**
  - no fancy n-ary functions for methods — just a keyword and a map of params
  - basic validation/coercion of method parameters to their schemas
  - auto-conversion of dashes to underscores in param keys
  - support for multiple simultaneously running bots
* **Does things at the right level of abstraction**
  - it's a simple API client library, not an opinionated framework
  - has zero "moving parts" — even [an HTTP client is pluggable](#supported-http-clients)!
  - leaves the execution/concurrency model up to your discretion
* **Built on top of [Martian](https://github.com/oliyh/martian) for the best possible feature set**
  - highly configurable via interceptor chain with reasonable defaults
  - superior testing experience — without brittle global mocks
  - recording and playing back responses in a VCR style
* **Production-ready built-in essentials**
  - rate limiting — using Bot API defaults, yet re-configurable
  - multipart requests and JSON-serialized params support
  - set of simple, frequently used utility functions

## Installation

Add the following dependencies to your `deps.edn` or `project.clj` file:

1. the latest version of the library itself
2. any supported HTTP client of your choice via a Martian module (see below)

### Latest version

[![Clojars Project](http://clojars.org/com.github.marksto/clj-tg-bot-api/latest-version.svg)](http://clojars.org/com.github.marksto/clj-tg-bot-api)

### Supported HTTP clients

| Target HTTP client | Extra project dependency                                                                                                                                  |
|--------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `http-kit`         | [![Clojars](https://img.shields.io/clojars/v/com.github.oliyh/martian-httpkit.svg)](https://clojars.org/com.github.oliyh/martian-httpkit)           |
| `clj-http`         | [![Clojars](https://img.shields.io/clojars/v/com.github.oliyh/martian-clj-http.svg)](https://clojars.org/com.github.oliyh/martian-clj-http)         |
| `clj-http-lite`    | [![Clojars](https://img.shields.io/clojars/v/com.github.oliyh/martian-clj-http-lite.svg)](https://clojars.org/com.github.oliyh/martian-clj-http-lite) |
| `hato`             | [![Clojars](https://img.shields.io/clojars/v/com.github.oliyh/martian-hato.svg)](https://clojars.org/com.github.oliyh/martian-hato)                 |

Since the library uses a number of JVM-specific dependencies, there is currently no support for running it with `bb` and `org.babashka/http-client`.

## Usage

### Basics

```clojure
(ns my.bot.core
  (:require [marksto.clj-tg-bot-api.core :as tg-bot-api]))

;; Create a client for the bot
(def client (tg-bot-api/->client {:bot-id    1
                                  :bot-token "<TG_BOT_AUTH_TOKEN>"}))

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

### Advanced Client Options

The library also provides you with several advanced options for configuring the client and individual API method calls.

#### Local Bot API Server

If you are [using a Local Bot API Server](https://core.telegram.org/bots/api#using-a-local-bot-api-server), simply pass the `:server-url` string, which will be used instead of the global one.

```clojure
(def client (tg-bot-api/->client {:bot-id     1
                                  :bot-token  "<TG_BOT_AUTH_TOKEN>"
                                  :server-url "http://localhost:1234/bot"}))
```

#### Testing

The `:responses` option is used for generating Telegram Bot API server responses, effectively mocking real HTTP requests during tests. Its value is either a map (from method to predefined response or request->response generator function), or a unary function (that returns a response for the given `ctx` map, which most importantly contains a `:request`, but also comes with other details about the call).

```clojure
;; Providing a static map of responses
(def client (tg-bot-api/->client {:bot-id    1
                                  :bot-token "<TG_BOT_AUTH_TOKEN>"
                                  :responses {:get-me {:status 200
                                                       :body   {:ok     true
                                                                :result {:id         12345678
                                                                         :is_bot     true
                                                                         :first_name "Testy"
                                                                         :username   "test_username"}}}}}))
(tg-bot-api/make-request! client :get-me)
;=> {:id 12345678, :is_bot true, :first_name "Testy", :username "test_username"}

;; Providing a map of response generators
(def client (tg-bot-api/->client {:bot-id    1
                                  :bot-token "<TG_BOT_AUTH_TOKEN>"
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

Additionally, it usually makes sense to set the `:limit-rate?` to `false` for tests to bypass the built-in rate limiter and speed things up.

#### Custom Behaviour

Any custom behavior can be added via [Tripod interceptors](https://github.com/frankiesardo/tripod#interceptors), which are used by Martian under the hood for everything from setting and validating parameters to performing an HTTP request.

The `:interceptors` option is used for injecting custom interceptors into the basic interceptor chain. Each element of this coll is a vector of the form `[interceptor rel-pos basic-name]`, where:
- `interceptor` — a new object to add or `nil` to remove;
- `rel-pos`     — may be `:before`, `:after`, `:replace`;
- `basic-name`  — the name of some basic interceptor.

Check out the `marksto.clj-tg-bot-api.core-itest` namespace for an example of leveraging the `:interceptors` option to enable VCR-based testing using the `martian-vcr` module.

### Call Options

The `make-request!` function supports the following call options:
- `:on-success` — a unary callback function of a response body containing an `:ok true` entry which indicates that the request was successful; by default, returns `:result` of the response;
- `:on-failure` — a ternary callback function of `method`, `params`, and  response body containing `:ok false` and `:error` entries indicating that the request was unsuccessful; by default, logs the response and throws an exception; supports `:ignore` value;
- `:on-error` — a ternary callback function of `method`, `params`, and any exception; by default, logs and rethrows the specified exception; supports `:ignore` value.

While you can always pass in a custom implementation, the `marksto.clj-tg-bot-api.core` namespace comes with a set of common ones that can be used as any of these callback functions:

```clojure
(def client (tg-bot-api/->client {:bot-id    1
                                  :bot-token "<TG_BOT_AUTH_TOKEN>"}))

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

### Utilities

The library also provides a set of utility functions — for Telegram Bot API types, updates and responses — available for use through the `marksto.clj-tg-bot-api.utils` namespace. Feel free to check them out!

## Rationale

Telegram Bot API doesn't publish an OpenAPI/Swagger definition, so every client library ends up hand-crafting endpoint lists, parameter checks, and HTTP plumbing — inevitably drifting behind upstream changes and hiding subtle bugs.

Manually updating method definitions and validation logic eats up the development budget and slows down adoption of new API features — especially since the Telegram Bot API has been experiencing rapid development lately.

As an enthusiast building bots daily, I've found that existing Clojure client libraries either lag behind, impose unnecessary boilerplate, or lack key features. I needed a lean, API spec-driven solution that would be easy to maintain and would natively support all the necessary use cases.

## License

Copyright © 2025 Mark Sto

Licensed under [EPL 1.0](LICENSE) (same as Clojure).
