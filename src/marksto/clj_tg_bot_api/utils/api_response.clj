(ns marksto.clj-tg-bot-api.utils.api-response
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

; ResponseParameters
;
; Contains information about why a request was unsuccessful.
;
; Field             	Type    	Description
; migrate_to_chat_id 	Integer 	Optional. The group has been migrated to
;                   	        	a supergroup with the specified identifier.
; retry_after       	Integer 	Optional. In case of exceeding flood control,
;                   	        	the number of seconds left to wait before
;                   	        	the request can be repeated.

(defn get-response-parameters
  [{:keys [parameters] :as _response}]
  parameters)

(defn migrated-to-supergroup?
  [response]
  (:migrate_to_chat_id (get-response-parameters response)))

(defn retry-request-after-sec
  [response]
  (:retry_after (get-response-parameters response)))
