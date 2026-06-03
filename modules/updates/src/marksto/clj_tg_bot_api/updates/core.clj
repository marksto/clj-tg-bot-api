;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.updates.core
  "An extra module for the `clj-tg-bot-api` library that provides conventional
   means to uniformly set up and tear down the way a Telegram bot gets updates.

   As stated in the Bot API documentation, there are 2 mutually exclusive ways
   to get updates for the bot — via a webhook URL xor via long-polling process.
   See https://core.telegram.org/bots/api#getting-updates for more details.

   The module also allows to dynamically switch between these ways at runtime."
  {:author "Mark Sto (@marksto)"}
  (:require
   [marksto.clj-tg-bot-api.updates.impl.long-polling :as long-polling]
   [marksto.clj-tg-bot-api.updates.impl.webhook :as webhook]))

;; WEBHOOK

(defn setup-webhook!
  "Specifies a public HTTPS endpoint through which the Telegram Bot API
   will provide pending updates to the bot.

   This way of receiving updates is common for a remotely deployed bot,
   but arranging it in local dev env can be tricky, since a self-signed
   certificate, a static IP address with a proper router configuration,
   and/or a reverse proxy service (such as ngrok) is necessary for this
   setup to work.

   The `client` is a Telegram Bot API client instance created with the
   `marksto.clj-tg-bot-api.core/->client` fn.

   The `params` is a map of options that is deep-merged with defaults.
   It provides both `:webhook` and `:long-polling` options under their
   respective keys. Provide options for both to be able to dynamically
   switch between these ways at runtime.

   Available `:webhook` options with a specific default value or a set
   of possible values (with a default one coming first):
   ```
   {;; start-up
    :on-wh-setup-failure     #{:exit, (fn [ex] ...)}    (optional)

    ;; setting up webhook
    :set-webhook-params      <setWebhook method params> (required)}
   ```

   `:set-webhook-params`: https://core.telegram.org/bots/api#setwebhook
   The only required param is `:url`, e.g. \"https://somebot.io/api\".

   See `webhook/get-webhook-certificate` docstring for an explanation
   on how/when to set up the SSL/TLS certificate for the bot's domain.

   Returns a response from the 'setWebhook' API method. Does not throw.

   NB: See official \"Marvin's Marvellous Guide to All Things Webhook\":
       https://core.telegram.org/bots/webhooks"
  {:arglists '([{:keys [webhook long-polling] :as params} client])}
  [params client]
  (webhook/setup-webhook! params client))

;; LONG-POLLING

(defn setup-long-polling!
  "Starts the updates long-polling process after some preparatory work.

   The `client` is a Telegram Bot API client instance created with the
   `marksto.clj-tg-bot-api.core/->client` fn.

   The `params` is a map of options that is deep-merged with defaults.
   It provides both `:long-polling` and `:webhook` options under their
   respective keys. Provide options for both to be able to dynamically
   switch between these ways at runtime.

   Available `:long-polling` options with a specific default value or
   a set of possible values (with a default one coming first):
   ```
   {;; start-up
    :on-lp-setup-failure    #{:exit, (fn [ex] ...)}    (optional)

    ;; producing updates
    :get-updates-params     <getUpdates method params> (optional)

    ;; consuming updates
    :update-handler         (fn [update] <repeat?>)    (required)
    :updates-poll-timeout   100                        (optional)

    ;; webhook-related
    :drop-pending-updates?  #{true, false}             (optional)
    :restore-prev-webhook?  #{false, true}             (optional)

    ;; status checker
    :status-check-period    20000                      (optional)
    :restart-period         10000                      (optional)
    :restart-attempts       5                          (optional)
    :on-no-more-attempts    #{:exit, (fn [] ...)}      (optional)}
   ```

   `:get-updates-params`: https://core.telegram.org/bots/api#getupdates
   The defaults are `{:timeout 10, :offset 0, :limit 100}`.

   The `:update-handler` is a unary fn of update that processes it and
   yields a truthy result — if it is `true`, then a call for the given
   update will be repeated. Normally, it should not throw, but when it
   does the update simply gets logged and skipped.

   Since the current webhook, if set, has to be deleted in order to be
   able to receive updates using 'getUpdates' method, there are a few
   params as well that control this process, namely:
   - `:drop-pending-updates?`
     Passed as a parameter to the 'deleteWebhook' and 'setWebhook' API
     methods, first upon webhook deletion and then upon its restoration
     attempt, if asked for (true by default);
   - `:restore-prev-webhook?`
     Determines whether or not the bot will try to restore a previously
     deleted webhook (false by default).

   To be done correctly, restoring the previous webhook requires extra
   data, specifically the webhook's 'certificate' and 'secure_token',
   that can only come out of band, i.e. specified by the bot developer
   in advance. So, if this is the case, these webhook params have to be
   provided in a usual way, as part of the `:set-webhook-params` under
   the `:webhook` key in `:params` map. Otherwise, just reset a webhook
   manually, e.g. via curl, later on.

   There is also a \"status checker\" process that will automatically
   restart the updates retrieval whenever it fails. This process can be
   fine-tuned to your liking, though the defaults listed above should
   work out-of-the-box in most cases.

   Returns info on a previously set up webhook, if any. Does not throw.

   NB: We perform long-polling of server updates ourselves, which means
       a continuous calling of the 'getUpdates' Telegram Bot API method
       and waiting to receive any incoming updates over this time, then
       making the same call again; this way is totally fine for a local
       debugging/testing purposes, but it turns out to be slower & more
       error-prone than listening to a dedicated webhook, a recommended
       option for Production environment."
  {:arglists '([{:keys [long-polling webhook] :as params} client])}
  [params client]
  (long-polling/setup-long-polling! params client))

(defn teardown-long-polling!
  "Stops the updates long-polling process at the initiative of the user
   and also tries to restore a previously set up webhook, if any.

   The `client` is a Telegram Bot API client instance created with the
   `marksto.clj-tg-bot-api.core/->client` fn.

   The `params` is a map of options that is deep-merged with defaults.
   See the `setup-long-polling!` docstring for available options.

   The `webhook-info`, if any, is usually returned by a preceding call
   to the `setup-long-polling!` fn.

   Does nothing when the long-polling process has already been stopped.

   Returns `true` if the webhook was successfully set, `nil` otherwise."
  {:arglists '([{:keys [long-polling webhook] :as params} client]
               [{:keys [long-polling webhook] :as params} client webhook-info])}
  ([params client]
   (long-polling/teardown-long-polling! params client nil))
  ([params client webhook-info]
   (long-polling/teardown-long-polling! params client webhook-info)))
