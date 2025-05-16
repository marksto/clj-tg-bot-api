(ns marksto.clj-tg-bot-api.impl.client.core
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [martian.core :as m]
            [taoensso.truss :refer [have!]]

            [marksto.clj-tg-bot-api.impl.api.spec :as api-spec]
            [marksto.clj-tg-bot-api.impl.client.rate-limiter :as rl]
            [marksto.clj-tg-bot-api.impl.utils :as utils]
            [marksto.clj-tg-bot-api.impl.utils.response :as response]))

;;; Bot API client

(def global-server-url "https://api.telegram.org/bot")

(defn get-api-root-url-for-bot
  [server-url bot-auth-token]
  (str (or server-url global-server-url) bot-auth-token))

;; TODO: Hide behind a custom `:type` to prevent secrets (token) from leaking.
(defn ->client
  [{:keys [bot-id bot-auth-token server-url] :as _client-opts}]
  (-> (get-api-root-url-for-bot server-url bot-auth-token)
      (api-spec/build-martian)
      (assoc :bot-id bot-id)))


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

(defn- format-msg [base-msg method-name params]
  (format "%s (method='%s' args=%s)" base-msg method-name params))

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
         method-name (utils/fn-name method)]
     (log/log log-level (format-msg base-msg' method-name params)))))

(defn throw-for-failure
  ([method params failed-tg-resp]
   (throw-for-failure failure-msg method params failed-tg-resp))
  ([base-msg method params failed-tg-resp]
   (let [ex-msg (utils/apply-if-fn base-msg)
         ;; NB: Exclude an HTTP client-set `:error`, since it's of little use.
         response (dissoc failed-tg-resp :error)]
     (throw (ex-info ex-msg {:response response
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

(defn log-error
  ([method params ex]
   (log-error error-msg method params ex))
  ([base-msg method params ex]
   (let [base-msg' (utils/apply-if-fn base-msg)
         method-name (utils/fn-name method)]
     (log/log :error ex (format-msg base-msg' method-name params)))))

;; NB: For async requests, this strategy is of little use, since the provided
;;     callback will be processed on different threads and any exception will
;;     be redirected to the `UncaughtExceptionHandler` which will simply log.
(defn rethrow-error
  [method params ex]
  (throw (ex-info error-msg {:method method :params params} ex)))

(defn log-error-and-rethrow
  ([method params ex]
   (log-error method params ex)
   (when-not (utils/in-repl?) (utils/clear-stack-trace ex))
   (rethrow-error method params ex))
  ([base-msg method params ex]
   (log-error base-msg method params ex)
   (when-not (utils/in-repl?) (utils/clear-stack-trace ex))
   (rethrow-error method params ex)))


;;; Making Requests

(defn- call-tg-bot-api-method!
  [client method params]
  ;; TODO: Double-check with all popular clients and re-implement if necessary.
  ;; NB: Expects an HTTP client library to return a map with the `:error` key
  ;;     in case if request was unsuccessful (status codes other than 200-207,
  ;;     300-303, or 307) or in any of other exceptional situations (e.g. when
  ;;     the lib is unable to retrieve the response body).
  (try
    (if (nil? params)
      (m/response-for client method)
      (m/response-for client method params))
    (catch Throwable client-code-ex
      ;; NB: An `error` is processed by the `handle-response` function.
      {:error client-code-ex})))

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
    (call-ignorable-callback on-error method params (:error tg-resp))
    ;;
    :else
    (throw (IllegalStateException. "Malformed Telegram Bot API response"))))

;;

;; TODO: Add an auto-retry strategy for Telegram Bot API response 'parameters' with 'migrate_to_chat_id'.
;; TODO: Add an auto-retry strategy for Telegram Bot API response 'parameters' with 'retry_after'.

(defn make-request!
  [{:keys [bot-id in-test?] :as client} args]
  (let [[call-opts method params] (if (map? (first args)) args (cons nil args))]
    (have! keyword? method)
    (let [callbacks {:on-success (or (:on-success call-opts)
                                     get-result)
                     :on-failure (or (:on-failure call-opts)
                                     log-failure-reason-and-throw)
                     :on-error   (or (:on-error call-opts)
                                     log-error-and-rethrow)}
          chat-id (or (get params :chat-id) (get params :chat_id))
          {:keys [body]} (rl/with-rate-limiter bot-id in-test? chat-id
                           (call-tg-bot-api-method! client method params))]
      (log/debugf "Telegram Bot API returned: %s" body)
      (when (some? body)
        (handle-response method params body callbacks)))))

;;

(defn build-immediate-response
  ([client method]
   (build-immediate-response client method {}))
  ([client method params]
   (have! keyword? method)
   (let [request (-> client
                     (m/request-for method params)
                     (select-keys [:body :multipart :headers])
                     (assoc :status 200))
         method' (csk/->camelCaseString method)]
     ;; NB: Special handling of multipart requests so to not pollute the params
     ;;     schema of each and every method in the API spec with this `method`.
     (if (contains? request :multipart)
       (update request :multipart conj {:name "method" :content method'})
       (update request :body assoc :method method')))))

;;

(comment
  (def client (->client {:bot-id         1
                         :bot-auth-token (System/getenv "BOT_AUTH_TOKEN")}))
  (dissoc client :handlers)

  (make-request! client '(:get-me))
  (make-request! client '(:get-me {}))

  (build-immediate-response client :get-me)
  (build-immediate-response client :get-me {})
  (build-immediate-response client :send-audio {:chat-id 1 :audio "<audio>"})

  :end/comment)
