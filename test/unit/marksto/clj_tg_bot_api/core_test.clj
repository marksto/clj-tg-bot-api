(ns marksto.clj-tg-bot-api.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test]
            [marksto.clj-tg-bot-api.core :as sut])
  (:import (java.net URI)))

(def test-bot-token "1234567890:TEST_pxWA8lDi7uLc3oadqNivHCALHBQ7sM")

(deftest ->client-test
  (testing "invalid parameters"
    (is (thrown-with-msg?
          Exception
          #"The `bot-id` must satisfy `some\?` predicate"
          (sut/->client)))
    (is (thrown-with-msg?
          Exception
          #"The `bot-id` must satisfy `some\?` predicate"
          (sut/->client {:bot-token test-bot-token})))
    (is (thrown-with-msg?
          Exception
          #"The `bot-token` must satisfy `string\?` predicate"
          (sut/->client :bot-id 1)))
    (is (thrown-with-msg?
          Exception
          #"The `bot-token` must satisfy `string\?` predicate"
          (sut/->client {:bot-id 1})))
    (is (thrown-with-msg?
          Exception
          #"The `server-url` must satisfy `string\?` predicate"
          (sut/->client {:bot-id     1
                         :bot-token  test-bot-token
                         :server-url (.toURL (URI. "https://example.com"))}))))
  (testing "valid parameters"
    (testing "provided as kwargs"
      (let [client (sut/->client :bot-id 1 :bot-token test-bot-token)]
        (is (match? {:bot-id 1} client)
            "Bot ID should be visible")
        (is (= "#TelegramBotAPIClient{:bot-id 1}" (pr-str client))
            "Secrets (bot auth token) must be protected from leakage")))
    (testing "provided as a map"
      (let [client (sut/->client {:bot-id    1
                                  :bot-token test-bot-token})]
        (is (match? {:bot-id 1} client)
            "Bot ID should be visible")
        (is (true? (:limit-rate? client))
            "The rate limiting is enabled by default")
        (is (= "#TelegramBotAPIClient{:bot-id 1}" (pr-str client))
            "Secrets (bot auth token) must be protected from leakage"))
      (let [client (sut/->client {:bot-id     1
                                  :bot-token  test-bot-token
                                  :server-url "https://example.com/bot"})]
        (is (match? {:bot-id 1} client)
            "Bot ID should be visible")
        (is (true? (:limit-rate? client))
            "The rate limiting is enabled by default")
        (is (= "#TelegramBotAPIClient{:bot-id 1}" (pr-str client))
            "Secrets (bot auth token) must be protected from leakage"))
      (let [client (sut/->client {:bot-id      1
                                  :bot-token   test-bot-token
                                  :limit-rate? false})]
        (is (match? {:bot-id 1} client)
            "Bot ID should be visible")
        (is (false? (:limit-rate? client))
            "The rate limiting can be disabled via `:rate-limit?`")
        (is (= "#TelegramBotAPIClient{:bot-id 1}" (pr-str client))
            "Secrets (bot auth token) must be protected from leakage")))))

(defn ctx->mock-responses
  [ctx]
  {:status 200
   :body   {:ok     true
            :result (:request ctx)}})

(deftest make-request!-test
  (let [client (sut/->client {:bot-id    1
                              :bot-token test-bot-token
                              :responses ctx->mock-responses})]
    (testing "invalid parameters"
      (is (thrown-with-msg?
            Exception
            #"The `client` must satisfy `client\?` predicate"
            (sut/make-request! {:bot-token test-bot-token} :get-me)))
      (is (thrown-with-msg?
            Exception
            #"The `method` must satisfy `keyword\?` predicate"
            (sut/make-request! client "get-me")))
      (is (thrown-with-msg?
            Exception
            #"The `method` must satisfy `keyword\?` predicate"
            (sut/make-request! client nil "get-me")))
      (is (thrown-with-msg?
            Exception
            #"The `method` must satisfy `keyword\?` predicate"
            (sut/make-request! client {} "get-me")))
      (is (thrown-with-msg?
            Exception
            #"The `params` must satisfy `map-or-nil\?` predicate"
            (sut/make-request! client :get-me "params")))
      (is (thrown-with-msg?
            Exception
            #"The `params` must satisfy `map-or-nil\?` predicate"
            (sut/make-request! client nil :get-me "params")))
      (is (thrown-with-msg?
            Exception
            #"The `params` must satisfy `map-or-nil\?` predicate"
            (sut/make-request! client {} :get-me "params"))))

    ;; NB: All these requests are made in "offline mode", without actually being sent.
    (testing "echo responses for"
      (testing "methods w/o params"
        (is (= {:method :post
                :url    (str "https://api.telegram.org/bot" test-bot-token "/getMe")}
               (sut/make-request! client :get-me)))
        (is (= {:method :post
                :url    (str "https://api.telegram.org/bot" test-bot-token "/getMe")}
               (sut/make-request! client :get-me {}))))
      (testing "methods with params"
        (is (= {:method  :post
                :url     (str "https://api.telegram.org/bot" test-bot-token "/getChat")
                :headers {"Content-Type" "application/json"}
                :body    "{\"chat_id\":1}"}
               (sut/make-request! client :get-chat {:chat-id 1})))
        (is (= {:method  :post
                :url     (str "https://api.telegram.org/bot" test-bot-token "/sendMessage")
                :headers {"Content-Type" "application/json"}
                :body    "{\"chat_id\":1,\"text\":\"Hello, world!\"}"}
               (sut/make-request! client :send-message {:chat-id 1
                                                        :text    "Hello, world!"}))))
      (testing "JSON-serialized params"
        (is (= {:method  :post
                :url     (str "https://api.telegram.org/bot" test-bot-token "/createInvoiceLink")
                :headers {"Content-Type" "application/json"}
                :body    (str "{\"description\":\"Extra firm tofu\""
                              ",\"suggested_tip_amounts\":\"[]\""
                              ",\"payload\":\"prod-T0003\""
                              ",\"title\":\"Tofu XF\""
                              ",\"currency\":\"XTR\""
                              ",\"prices\":\"[{\\\"label\\\":\\\"price\\\",\\\"amount\\\":1000}]\"}")}
               (sut/make-request!
                 client
                 :create-invoice-link
                 {:title                 "Tofu XF"
                  :description           "Extra firm tofu"
                  :payload               "prod-T0003"
                  :currency              "XTR"
                  :prices                [{:label "price" :amount 1000}]
                  :suggested-tip-amounts []}))))
      (testing "multipart requests"
        (is (= {:method    :post
                :url       (str "https://api.telegram.org/bot" test-bot-token "/sendAudio")
                :headers   nil
                :multipart [{:name "chat_id" :content "1"}
                            {:name "audio" :content "<audio>"}]}
               (sut/make-request! client :send-audio {:chat-id 1
                                                      :audio   "<audio>"})))
        (testing "with JSON-serialized params"
          (is (= {:method    :post
                  :url       (str "https://api.telegram.org/bot" test-bot-token "/setWebhook")
                  :headers   nil
                  :multipart [{:name "url" :content "https://example.com"}
                              {:name "allowed_updates" :content (str "[\"message\""
                                                                     ",\"edited_channel_post\""
                                                                     ",\"callback_query\"]")}]}
                 (sut/make-request! client :set-webhook {:url             "https://example.com"
                                                         :allowed_updates ["message"
                                                                           "edited_channel_post"
                                                                           "callback_query"]}))))))))

(deftest build-response-test
  (let [client (sut/->client {:bot-id    1
                              :bot-token test-bot-token})]
    (testing "invalid parameters"
      (is (thrown-with-msg?
            Exception
            #"The `client` must satisfy `client\?` predicate"
            (sut/build-response {:bot-token test-bot-token} :get-me)))
      (is (thrown-with-msg?
            Exception
            #"The `method` must satisfy `keyword\?` predicate"
            (sut/build-response client "get-me")))
      (is (thrown-with-msg?
            Exception
            #"The `params` must satisfy `map-or-nil\?` predicate"
            (sut/build-response client :get-me "params"))))
    (testing "methods w/o params"
      (is (= {:status  200
              :headers {"Content-Type" "application/json"
                        "Accept"       "application/json"}
              :body    "{\"method\":\"getMe\"}"}
             (sut/build-response client :get-me)))
      (is (= {:status  200
              :headers {"Content-Type" "application/json"
                        "Accept"       "application/json"}
              :body    "{\"method\":\"getMe\"}"}
             (sut/build-response client :get-me {}))))
    (testing "methods with params"
      (is (= {:status  200
              :headers {"Content-Type" "application/json"
                        "Accept"       "application/json"}
              :body    "{\"chat_id\":1,\"method\":\"getChat\"}"}
             (sut/build-response client :get-chat {:chat-id 1})))
      (is (= {:status  200
              :headers {"Content-Type" "application/json"
                        "Accept"       "application/json"}
              :body    "{\"chat_id\":1,\"text\":\"Hello, world!\",\"method\":\"sendMessage\"}"}
             (sut/build-response client :send-message {:chat-id 1
                                                       :text    "Hello, world!"}))))
    (testing "JSON-serialized params"
      (is (= {:status  200
              :headers {"Content-Type" "application/json"
                        "Accept"       "application/json"}
              :body    (str "{\"description\":\"Extra firm tofu\""
                            ",\"suggested_tip_amounts\":\"[]\""
                            ",\"payload\":\"prod-T0003\""
                            ",\"title\":\"Tofu XF\""
                            ",\"currency\":\"XTR\""
                            ",\"prices\":\"[{\\\"label\\\":\\\"price\\\",\\\"amount\\\":1000}]\""
                            ",\"method\":\"createInvoiceLink\"}")}
             (sut/build-response
               client
               :create-invoice-link
               {:title                 "Tofu XF"
                :description           "Extra firm tofu"
                :payload               "prod-T0003"
                :currency              "XTR"
                :prices                [{:label "price" :amount 1000}]
                :suggested-tip-amounts []}))))
    (testing "multipart requests"
      (is (= {:status    200
              :headers   {"Accept" "application/json"}
              :multipart [{:name "chat_id" :content "1"}
                          {:name "audio" :content "<audio>"}
                          {:name "method" :content "sendAudio"}]}
             (sut/build-response client :send-audio {:chat-id 1
                                                     :audio   "<audio>"})))
      (testing "with JSON-serialized params"
        (is (= {:status    200
                :headers   {"Accept" "application/json"}
                :multipart [{:name "url" :content "https://example.com"}
                            {:name "allowed_updates" :content (str "[\"message\""
                                                                   ",\"edited_channel_post\""
                                                                   ",\"callback_query\"]")}
                            {:name "method" :content "setWebhook"}]}
               (sut/build-response client :set-webhook {:url             "https://example.com"
                                                        :allowed_updates ["message"
                                                                          "edited_channel_post"
                                                                          "callback_query"]})))))))

(comment
  (clojure.test/run-tests)
  :end/comment)
