# clj-tg-bot-api

The latest Telegram Bot API specification and client lib for Clojure-based apps.

Features:

- (Semi)auto-updates upon the Telegram Bot API updates

  The Telegram Bot API get web-scraped and parsed, the spec file gets formed, some of the "static" code gets recompiled

- Has no "moving parts" a.k.a. Bring Your Own HTTP client

  Building a request body in a manual way, making HTTP requests, etc. is notoriously laborious and hard to maintain and, moreover, often unnecessary (tests, immediate responses, etc.), so we postpone making actual request

- Uniform Bot API method contract

  Drops unnecessary fancy arities (unlocks certain logic), adds keywords auto-transformations (dashes to underscores), uses schemas for validation/coercion

- Built-in rate limiting using Telegram defaults, yet re-configurable

- Built-in auto-retry strategies for failed Telegram Bot API requests

- Provides a long-polling for the ease of local development and debug

## Usage



## License

Copyright © 2025 Mark Sto

Licensed under [EPL 1.0](LICENSE) (same as Clojure).
