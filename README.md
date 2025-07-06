# clj-tg-bot-api

The latest Telegram Bot API specification and client lib for Clojure-based apps.

Features:

- (Semi)auto-updates upon the Telegram Bot API updates

  The Telegram Bot API get web-scraped and parsed, the spec file gets formed, some of the "static" code gets recompiled

- Does things on a right level of abstraction

  Uses `martian` under the hood and leverages its full potential to enable the best feature set possible (pluggable HTTP clients, generative testing, recording and playing back responses in a VCR style, etc.)

- Has no "moving parts" a.k.a. Bring Your Own HTTP client

  Building a request body in a manual way, making HTTP requests, etc. is notoriously laborious and hard to maintain and, moreover, often unnecessary (during testing, when making requests in response to updates, etc.), so we postpone making actual request

- Uniform Bot API method contract

  Drops unnecessary fancy arities (unlocks certain logic), adds method params keys auto-conversion (dashes to underscores), uses schemas for validation/coercion (checks each method param for its type and optionality)

- Built-in rate limiting using Telegram defaults, yet re-configurable

- Provides a long-polling for the ease of local development and debug

- Supports multiple simultaneously running bots (via associating different instances with unique IDs)

## Usage



## License

Copyright © 2025 Mark Sto

Licensed under [EPL 1.0](LICENSE) (same as Clojure).
