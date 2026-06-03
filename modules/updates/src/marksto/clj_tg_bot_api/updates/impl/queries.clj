;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.updates.impl.queries
  {:author "Mark Sto (@marksto)"}
  (:require
   [marksto.clj-tg-bot-api.core :as tg-bot-api]))

(defn- log-and-indicate-failure
  [method params tg-resp-or-ex]
  (if (instance? Throwable tg-resp-or-ex)
    (tg-bot-api/log-error method params tg-resp-or-ex)
    (tg-bot-api/log-failure-reason method params tg-resp-or-ex))
  ::failure)

(defn get-updates!
  "Receives incoming updates from the Telegram Bot API via the 'getUpdates'
   method called in a synchronous blocking way with a sufficient `:timeout`
   param (which is fine, since we're running inside a virtual thread).

   In case of success, returns the HTTP response map; otherwise, in case of
   any failure or error, returns a `::failure` keyword."
  [client params]
  ;; NB: There's no need to add a request timeout on the HTTP client level,
  ;;     since we are doing a classical long-polling w/ a `:timeout` param.
  (tg-bot-api/make-request! client
                            {:on-success identity
                             :on-failure log-and-indicate-failure
                             :on-error   log-and-indicate-failure}
                            :get-updates
                            params))

;;

(defn get-webhook-info!
  [client]
  (tg-bot-api/make-request! client
                            {:on-error :ignore}
                            :get-webhook-info))

(defn set-webhook!
  [client params]
  (tg-bot-api/make-request! client
                            {:on-success #(tg-bot-api/assert-result true %)}
                            :set-webhook
                            params))

(defn delete-webhook!
  [client params]
  (tg-bot-api/make-request! client
                            {:on-success #(tg-bot-api/assert-result true %)}
                            :delete-webhook
                            params))
