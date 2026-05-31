;; Copyright (c) Mark Sto, 2025. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.impl.utils.response
  "Utilities for Telegram Bot API responses"
  {:author "Mark Sto (@marksto)"})

(defn valid-response?
  [response]
  (and (map? response) (contains? response :ok)))

(defn successful-response?
  [response]
  (and (valid-response? response) (true? (:ok response))))

(defn get-response-result
  [response]
  (when (successful-response? response)
    (:result response)))

(defn get-response-result-chat-id
  [response-result]
  (when response-result
    (or (-> response-result :chat :id)
        (get response-result :chat_id))))

(defn get-response-error
  [response]
  (when-not (successful-response? response)
    (select-keys response [:error_code :description])))

(defn get-response-parameters
  [{:keys [parameters] :as _response}]
  parameters)

(defn get-chat-id-for-retry
  [response]
  (:migrate_to_chat_id (get-response-parameters response)))

(defn get-wait-ms-for-retry
  [response]
  (:retry_after (get-response-parameters response)))
