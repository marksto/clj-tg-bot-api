(ns marksto.clj-tg-bot-api.impl.client.core
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [martian.core :as m]
            [martian.test :as mt]
            [taoensso.truss :refer [have!]]

            [marksto.clj-tg-bot-api.impl.api.martian :as api-martian]
            [marksto.clj-tg-bot-api.impl.client.rate-limiter :as rl]
            [marksto.clj-tg-bot-api.impl.utils :as utils]
            [marksto.clj-tg-bot-api.impl.utils.response :as response])
  (:import (java.io Writer)))

;;; Bot API client

;; NB: Prevents secrets (bot auth token) from leaking, e.g. into logs.
(defmethod print-method ::tg-bot-api-client [this ^Writer w]
  (.write w (str "#TelegramBotAPIClient"
                 (into {} (select-keys this [:bot-id])))))

(def global-server-url "https://api.telegram.org/bot")

(defn ->client
  [{:keys [bot-id bot-token server-url limit-rate? response-fn]
    :or   {server-url  global-server-url
           limit-rate? true}
    :as   _client-opts}]
  (have! some? bot-id bot-token server-url)
  (-> (api-martian/build-martian (str server-url bot-token))
      (cond-> response-fn (mt/respond-with response-fn))
      (assoc :bot-id bot-id :limit-rate? limit-rate?)
      (with-meta {:type ::tg-bot-api-client})))


;;; Operations on response 'result'

(defn get-result
  ([tg-resp]
   (get-result tg-resp nil))
  ([tg-resp default-val]
   (or (response/get-response-result tg-resp) default-val)))

(defn assert-result
  [expected tg-resp]
  (let [tg-resp-result (get-result tg-resp)]
    (when-not (= expected tg-resp-result)
      (throw
        (ex-info "The Telegram Bot API method returned an unexpected result"
                 {:expected expected
                  :actual   tg-resp-result})))
    tg-resp-result))


;;; Operations on response 'error'

(defn get-error
  [method params failed-tg-resp]
  (let [resp-error (response/get-response-error failed-tg-resp)]
    (assoc resp-error :method method :params params)))

(def failure-msg "Unsuccessful Telegram Bot API request")

(defn- format-msg [base-msg method params]
  (format "%s (method='%s' args=%s)" base-msg method params))

(defn log-failure-reason
  ([method params failed-tg-resp]
   (log-failure-reason :error failure-msg
                       method params failed-tg-resp))
  ([base-msg method params failed-tg-resp]
   (log-failure-reason :error base-msg
                       method params failed-tg-resp))
  ([log-level base-msg method params failed-tg-resp]
   (let [resp-error (response/get-response-error failed-tg-resp)
         error-text (some->> (seq resp-error)
                             (map (fn [[k v]] (str (name k) "=\"" v "\"")))
                             (not-empty)
                             (str/join ", "))
         base-msg' (cond-> (utils/apply-if-fn base-msg)
                           (seq error-text) (str ": " error-text))
         method' (csk/->camelCaseString method)]
     (log/log log-level (format-msg base-msg' method' params)))))

(defn throw-for-failure
  ([method params failed-tg-resp]
   (throw-for-failure failure-msg method params failed-tg-resp))
  ([base-msg method params failed-tg-resp]
   (let [ex-msg (utils/apply-if-fn base-msg)]
     (throw (ex-info ex-msg {:response failed-tg-resp
                             :method   method
                             :params   params})))))

;; NB: For async requests, this strategy is of little use, since the provided
;;     callback will be processed on different threads and any exception will
;;     be redirected to the `UncaughtExceptionHandler` which will simply log.
(defn log-failure-reason-and-throw
  ([method params failed-tg-resp]
   (log-failure-reason method params failed-tg-resp)
   (throw-for-failure method params failed-tg-resp))
  ([base-msg method params failed-tg-resp]
   (log-failure-reason base-msg method params failed-tg-resp)
   (throw-for-failure base-msg method params failed-tg-resp))
  ([log-level base-msg method params failed-tg-resp]
   (log-failure-reason log-level base-msg method params failed-tg-resp)
   (throw-for-failure base-msg method params failed-tg-resp)))


;;; Operations on arbitrary errors (e.g. network)

(def error-msg "Error while making a Telegram Bot API request")

(defn prepare-error
  [{ex :error :as _tg-resp}]
  ;; NB: Always unpack a noisy Martian container Interceptor Exception.
  (let [cause (ex-cause ex)
        cause-data (ex-data cause)]
    (or (when (= :schema-tools.coerce/error (:type cause-data))
          ;; NB: Clean up all noise from params schema coercion errors.
          (doto (ex-info (ex-message cause)
                         (select-keys cause-data [#_:schema :value :error]))
            (utils/clear-stack-trace)))
        cause)))

(defn log-error
  ([method params ex]
   (log-error error-msg method params ex))
  ([base-msg method params ex]
   (let [base-msg' (utils/apply-if-fn base-msg)
         method' (csk/->camelCaseString method)]
     (log/log :error ex (format-msg base-msg' method' params)))))

;; NB: For async requests, this strategy is of little use, since the provided
;;     callback will be processed on different threads and any exception will
;;     be redirected to the `UncaughtExceptionHandler` which will simply log.
(defn rethrow-error
  [method params ex]
  (throw
    (ex-info error-msg {:method method :params params} ex)))

(defn log-error-and-rethrow
  ([method params ex]
   (log-error method params ex)
   (rethrow-error method params ex))
  ([base-msg method params ex]
   (log-error base-msg method params ex)
   (rethrow-error method params ex)))


;;; Making Requests

(defn- call-tg-bot-api-method!
  [client method params]
  (try
    (if (nil? params)
      (m/response-for client method)
      (m/response-for client method params))
    (catch Throwable client-code-ex
      ;; NB: To make all Martian-supported HTTP clients compatible. An `error`
      ;;     is processed downstream by `prepare-response` & `handle-response`.
      {:error client-code-ex})))

;;

;; TODO: Add an auto-retry strategy for Telegram Bot API response 'parameters':
;;       - with 'migrate_to_chat_id'
;;       - with 'retry_after'

(def response-body-mapper
  (json/object-mapper {:decode-key-fn true}))

(defn- prepare-response
  [{:keys [body error] :as _call-result}]
  ;; NB: Expects an HTTP client library to return a map with the `:error` key
  ;;     in case if request was unsuccessful (status codes other than 200-207,
  ;;     300-303, or 307) or in any of other exceptional situations (e.g. when
  ;;     an HTTP connection cannot be established).
  (if-some [error-data (ex-data error)]
    (if-some [body (:body error-data)]
      ;; Failure - unsuccessful request (in terms of the Telegram Bot API)
      (if (seq body)
        (json/read-value body response-body-mapper)
        {:ok          false
         :error_code  (:status error-data)
         :description (:reason-phrase error-data)})
      ;; Error - in any other exceptional situation (incl. client-code-ex)
      {:error error})
    ;; Successful request
    body))

;;

(defn- call-ignorable-callback
  [callback-fn method params arg-val]
  (when (and (some? callback-fn) (not= :ignore callback-fn))
    (callback-fn method params arg-val)))

(defn- handle-response
  [method params tg-resp {:keys [on-success on-failure on-error] :as _callbacks}]
  ;; NB: The order of checks here is crucial. First, we handle valid responses,
  ;;     including unsuccessful ones (also contain `:error` key). Only then we
  ;;     handle other `:error`-containing tg-resp, e.g. HTTP, response parsing
  ;;     and client code exceptions.
  (cond
    (response/valid-response? tg-resp)
    (if (response/successful-response? tg-resp)
      (when (some? on-success) (on-success tg-resp))
      (call-ignorable-callback on-failure method params tg-resp))
    ;;
    (contains? tg-resp :error)
    (let [ex (prepare-error tg-resp)]
      (call-ignorable-callback on-error method params ex))
    ;;
    :else
    (throw (IllegalStateException. "Malformed Telegram Bot API response"))))

;;

(defn make-request!
  [{:keys [bot-id limit-rate?] :as client} args]
  (let [[call-opts method params] (if (map? (first args)) args (cons nil args))]
    (have! keyword? method)
    (let [callbacks {:on-success (or (:on-success call-opts)
                                     get-result)
                     :on-failure (or (:on-failure call-opts)
                                     log-failure-reason-and-throw)
                     :on-error   (or (:on-error call-opts)
                                     log-error-and-rethrow)}
          chat-id (or (get params :chat-id) (get params :chat_id))
          tg-resp (-> (rl/with-rate-limiter bot-id limit-rate? chat-id
                        (call-tg-bot-api-method! client method params))
                      (utils/force-ref)
                      (prepare-response))]
      (log/debugf "Telegram Bot API returned: %s" (pr-str tg-resp))
      (when (some? tg-resp)
        (handle-response method params tg-resp callbacks)))))

;;

(defn build-immediate-response
  ([client method]
   (build-immediate-response client method {}))
  ([client method params]
   (have! keyword? method)
   (let [method' (csk/->camelCaseString method)
         params' (assoc params :method method')]
     (-> client
         (m/request-for method params')
         (select-keys [:body :multipart :headers])
         (assoc :status 200)))))

;;

(comment
  (def client (->client {:bot-id    1
                         :bot-token (System/getenv "BOT_AUTH_TOKEN")}))
  (dissoc client :handlers)

  ;; CHECK WITH ALL SUPPORTED HTTP CLIENTS

  ;; Error Handling
  ;; 1. Successful request
  (make-request! client '(:get-me))
  ;; 2. Failure - Unsuccessful request (400 Bad Request)
  (make-request! client '(:get-chat {:chat-id 1}))
  ;; 3. Error - Client code exception (params coercion)
  (make-request! client '(:send-audio {:chat-id 1}))
  ;; 4. Error - Network connection (drop internet access)
  (make-request! client '(:get-me))

  ;; Multipart Requests
  ;; 1. successful coercion (1 -> string)
  (make-request! client '(:send-audio {:chat-id 1
                                       :audio   "<audio>"}))
  ;; 2. JSON-serialized
  (make-request! client '(:set-webhook {:url             "https://example.com"
                                        :allowed_updates ["message"
                                                          "edited_channel_post"
                                                          "callback_query"]}))

  ;; Immediate Response
  ;; 1. no params methods
  (build-immediate-response client :get-me)
  (build-immediate-response client :get-me {})
  ;; 2. multipart request
  (build-immediate-response client :send-audio {:chat-id 1
                                                :audio   "<audio>"})
  ;; 3. JSON-serialized
  (build-immediate-response
    client
    :create-invoice-link
    {:title                 "Tofu XF"
     :description           "Extra firm tofu"
     :payload               "prod-T0003"
     :currency              "XTR"
     :prices                [{:label "price" :amount 1000}]
     :suggested-tip-amounts []})

  :end/comment)
