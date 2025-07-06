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

[//]: # (TODO: Cover advanced usage and utilities.)

## Rationale

Telegram Bot API doesn't publish an OpenAPI/Swagger definition, so every client library ends up hand-crafting endpoint lists, parameter checks, and HTTP plumbing — inevitably drifting behind upstream changes and hiding subtle bugs.

Manually updating method definitions and validation logic eats up the development budget and slows down adoption of new API features — especially since the Telegram Bot API has been experiencing rapid development lately.

As an enthusiast building bots daily, I've found that existing Clojure client libraries either lag behind, impose unnecessary boilerplate, or lack key features. I needed a lean, API spec-driven solution that would be easy to maintain and would natively support all the necessary use cases.

## License

Copyright © 2025 Mark Sto

Licensed under [EPL 1.0](LICENSE) (same as Clojure).
