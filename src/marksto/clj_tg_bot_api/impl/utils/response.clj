(ns marksto.clj-tg-bot-api.impl.utils.response
  "Utilities for Telegram Bot API responses")

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
