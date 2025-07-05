(ns marksto.clj-tg-bot-api.core-itest
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [marksto.clj-tg-bot-api.core :as sut]
            [marksto.clj-tg-bot-api.impl.api.martian :as api-martian]
            [marksto.clj-tg-bot-api.net-utils :as net-utils]
            [marksto.clj-tg-bot-api.vcr-utils :as vcr-utils]
            [matcher-combinators.test]
            [martian.vcr :as vcr]))

(def token->bot-id #(subs % 0 (str/index-of % ":")))

(def real-bot-token (System/getenv "BOT_AUTH_TOKEN"))
(def real-bot-id (token->bot-id real-bot-token))

(def fake-bot-token "1234567890:TEST_pxWA8lDi7uLc3oadqNivHCALHBQ7sM")
(def fake-bot-id (token->bot-id fake-bot-token))

(def replacements
  [[:string-val real-bot-token fake-bot-token]
   [:string-val real-bot-id fake-bot-id]])

(use-fixtures :once (fn [f] (vcr-utils/with-replacements replacements (f))))

;;

(def perform-request-interceptor
  (api-martian/get-perform-request-interceptor))

(def perform-request-interceptor-name
  (:name perform-request-interceptor))

(def used-http-client
  (str/replace (namespace perform-request-interceptor-name) "martian." ""))

(def vcr-root-dir
  (str "test-resources/integration/" used-http-client))

(def vcr-opts
  {:store {:kind     :prepared-file
           :root-dir vcr-root-dir
           :pprint?  true}})

;; NB: HTTP client-specific. Addresses request inconsistencies between clients.
(def http-client-specific-ops
  {:name  ::http-client-specific-ops
   :enter (fn [ctx]
            (case used-http-client
              "httpkit"
              ;; manually set dead-end proxy details, since it is based on NIO
              (update ctx :request conj {:proxy-url (net-utils/get-proxy-url)})
              ("clj-http" "clj-http-lite")
              ;; just record request, so it is visible like with other clients
              (update ctx :request conj {:save-request? true})
              ctx))})

;; NB: Mimics clients throwing exceptions on error (`clj-http[-lite]`, `hato`).
(def throw-exception-response
  {:name  ::throw-exception-response
   :leave (fn [{:keys [response] :as ctx}]
            (if (instance? Throwable response)
              (throw response)
              ctx))})

;; NB: Catches all HTTP error exceptions, so that they get recorded by the VCR.
(def catching-perform-request
  {:name  ::catching-perform-request
   :leave (fn [ctx]
            (try
              ;; NB: There's no need to do anything specific for `http-kit`,
              ;;     its errors get recorded anyway, under the `:error` key.
              ((:leave perform-request-interceptor) ctx)
              (catch Exception ex
                (assoc ctx :response ex))))})

(defn build-test-interceptors []
  ;; NB: We need to rebuild the interceptor chain to reset the counters.
  [[(vcr/record vcr-opts) :before perform-request-interceptor-name]
   [(vcr/playback vcr-opts) :before perform-request-interceptor-name]
   [throw-exception-response :before ::vcr/record]
   [http-client-specific-ops :before perform-request-interceptor-name]
   [catching-perform-request :replace perform-request-interceptor-name]])

;;

(deftest make-request!-test
  (let [client (sut/->client {:bot-id       1
                              :bot-token    real-bot-token
                              :interceptors (build-test-interceptors)})]
    (testing "Success:"
      ;; NB: All successful API requests first need to be recorded!
      (testing "The bot's authentication token"
        (vcr-utils/with-replacements
          [[:json-field "first_name" "Testy"]
           [:json-field "username" "test_username"]]
          (is (match?
                {:id         1234567890
                 :is_bot     true
                 :first_name "Testy"
                 :username   "test_username"}
                (sut/make-request! client :get-me))))))

    (testing "Failure:"
      ;; NB: All unsuccessful API requests first need to be recorded!
      (testing "Bad Request"
        (is (thrown-match?
              {:response {:description "Bad Request: chat not found"
                          :error_code  400
                          :ok          false}
               :method   :get-chat
               :params   {:chat-id 1}}
              (sut/make-request! client :get-chat {:chat-id 1})))))

    (testing "Error:"
      (testing "Client code exception (params coercion)"
        ;; NB: For this one neither `::perform-request` nor VCR will be reached.
        (is (thrown-with-msg?
              ClassCastException
              #"class java.lang.String cannot be cast to class java.lang.Number"
              (sut/make-request! client :send-audio {:chat-id "Oops!"}))))

      (testing "Network outage (server connection error)"
        (is (thrown-with-msg?
              Exception
              #"Error while making a Telegram Bot API request"
              (net-utils/with-http-outage
                (sut/make-request! client :send-message {:chat-id 1
                                                         :text    "🛜"}))))))))

;;

(comment
  ;; RECORDING
  (clojure.test/run-tests)

  ;; PLAY BACK
  (clojure.test/run-tests)

  ;; RESETTING
  (do (require '[babashka.fs :as fs])
      (fs/delete-tree vcr-root-dir))

  :end/comment)
