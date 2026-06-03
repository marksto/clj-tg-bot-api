;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.updates.impl.webhook
  {:author "Mark Sto (@marksto)"}
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [marksto.clj-tg-bot-api.updates.impl.common :as common]
   [marksto.clj-tg-bot-api.updates.impl.queries :as queries]
   [marksto.clj-tg-bot-api.updates.impl.utils :as utils])
  (:import
   (java.io File)
   (java.net URL)))

;; - configuration

(def ^:private default-wh-cfg
  {;; start-up
   :on-wh-setup-failure :exit

   ;; setting webhook
   :set-webhook-params  {:url nil}})

(defn complete-wh-cfg [{:keys [webhook] :as _params}]
  (utils/nested-merge default-wh-cfg webhook))

;; - params preparation

(def bot-token-re #"\d{6,11}:[0-9A-Za-z_-]{34,36}")

(defn mask-webhook-tokens
  [webhook-info-or-params]
  (-> webhook-info-or-params
      (update :url str/replace bot-token-re "hidden")
      (utils/update* :secret-token #(str (subs % 0 8) "..."))))

(defn get-webhook-certificate
  "Retrieves a PEM file with some certificate for the bot's webhook, using the
   \"setWebhook\" parameters, `:certificate` and `:url`, from the given config.

   By convention, the certificate file is loaded as a resource along this path:
   'cert/{bot_api_domain}.pem', where `{bot_api_domain}` is the domain part of
   the given webhook `:url`.

   Therefore, usually there is no need to provide the certificate file by hand,
   but if you will so, just pass it along the `:set-webhook-params` (as a `File`
   object, not as a text string), and it will override the above convention.

   Returns the certificate, if found or provided, as a `File`, `nil` otherwise.

   NOTES:
   We need to provide Telegram with this file via the `:certificate` parameter
   of the \"setWebhook\" Bot API method in any of the following cases:
   1. Setting a webhook with a self-signed certificate
      - https://core.telegram.org/bots/self-signed
      - https://core.telegram.org/bots/webhooks#a-self-signed-certificate
   2. Setting a verified webhook with an untrusted root
      - https://core.telegram.org/bots/webhooks#an-untrusted-root

   For a verified certificate with a trusted root CA, it’s enough to call the
   \"setWebhook\" method with just the `:url` param. But in this case, as well
   as in the case of an untrusted root, the bot developer has to remember to
   provide a complete chain of certificates — the bot's own public certificate
   followed by all intermediate certificates, if any.

   To figure things out in your case, check the official documentation:
   - https://packages.ubuntu.com/jammy/all/ca-certificates/filelist (trusted)
   - https://core.telegram.org/bots/webhooks#intermediate-certificates"
  ^File [{{:keys [certificate url]} :set-webhook-params :as _wh-cfg}]
  (if (some? certificate)
    (when (fs/exists? certificate) certificate)
    (let [domain (URL/.getHost (io/as-url url))
          res-name (format "cert/%s.pem" domain)]
      (if-some [cert-url (io/resource res-name)]
        (if (utils/inside-jar? cert-url)
          (utils/copy-resource! res-name)
          (when (fs/exists? cert-url) (fs/file cert-url)))
        (do (log/warnf "There's no certificate file at '%s'" res-name)
            nil)))))

;; - contract fns

(defn setup-webhook!
  [params client]
  (let [complete-wh-cfg (complete-wh-cfg params)]
    (try
      ;; NB: We avoid complying with (now obsolete) Telegram guidelines which
      ;;     prescribe to append the bot token value to the base webhook URL.
      ;;     The modern way of receiving the same outcome is to set a webhook
      ;;     param `secure_token` as part of the `:set-webhook-params`.
      (let [certificate (get-webhook-certificate complete-wh-cfg)
            ;; NB: Careful! Multipart request can only handle params of certain
            ;;     types, e.g. files, bytes, and strings. Plus, we need to drop
            ;;     a `nil` URL and an unnecessary certificate from the config.
            wh-params (cond-> (dissoc (:set-webhook-params complete-wh-cfg)
                                      :certificate)
                        certificate (assoc :certificate certificate))]
        (log/infof "Setting up Telegram webhook: %s"
                   (mask-webhook-tokens wh-params))
        (queries/set-webhook! client wh-params))
      (catch Throwable t
        (common/call-configured-fn! complete-wh-cfg :on-wh-setup-failure t)))))
