(ns marksto.clj-tg-bot-api.core-test
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.test :refer [deftest is testing]]
   [marksto.clj-tg-bot-api.core :as sut]
   [matcher-combinators.test])
  (:import
   (java.net URI)))

(def test-bot-id 1234567890)
(def test-bot-token (format "%s:TEST_pxWA8lDi7uLc3oadqNivHCALHBQ7sM" test-bot-id))

(def test-bot-str (format "#TelegramBotAPIClient{:bot-id %s}" test-bot-id))

(def test-file (io/file "test-file"))

(deftest ->client-test
  #_{:clj-kondo/ignore [:type-mismatch]}
  (testing "invalid parameters"
    (is (thrown-with-msg?
          Exception
          #"The `bot-token` must satisfy `string\?` predicate"
          (sut/->client)))
    (is (thrown-with-msg?
          Exception
          #"The `bot-token` must satisfy `string\?` predicate"
          (sut/->client {})))
    (is (thrown-with-msg?
          Exception
          #"Failed to parse an ID from the bot auth token"
          (sut/->client {:bot-token "malformed-bot-auth-token"})))
    (is (thrown-with-msg?
          Exception
          #"The `server-url` must satisfy `string\?` predicate"
          (sut/->client {:bot-token  test-bot-token
                         :server-url (.toURL (URI. "https://example.com"))}))))
  (testing "valid parameters"
    (testing "provided as kwargs"
      (let [client (sut/->client :bot-token test-bot-token)]
        (is (match? {:bot-id test-bot-id} client)
            "Bot ID should be visible")
        (is (= test-bot-str (pr-str client))
            "Secrets (bot auth token) must be protected from leakage")))
    (testing "provided as a map"
      (let [client (sut/->client {:bot-token test-bot-token})]
        (is (match? {:bot-id test-bot-id} client)
            "Bot ID should be visible")
        (is (some? (:limiter-opts client))
            "The rate limiting is enabled by default")
        (is (= test-bot-str (pr-str client))
            "Secrets (bot auth token) must be protected from leakage"))
      (let [client (sut/->client {:bot-token  test-bot-token
                                  :server-url "https://example.com/bot"})]
        (is (match? {:bot-id test-bot-id} client)
            "Bot ID should be visible")
        (is (some? (:limiter-opts client))
            "The rate limiting is enabled by default")
        (is (= test-bot-str (pr-str client))
            "Secrets (bot auth token) must be protected from leakage"))
      (let [client (sut/->client {:bot-token    test-bot-token
                                  :limiter-opts nil})]
        (is (match? {:bot-id test-bot-id} client)
            "Bot ID should be visible")
        (is (nil? (:limiter-opts client))
            "The rate limiting can be disabled via `:limiter-opts nil`")
        (is (= test-bot-str (pr-str client))
            "Secrets (bot auth token) must be protected from leakage")))))

(deftest explore-test
  (let [client (sut/->client :bot-token test-bot-token)]
    #_{:clj-kondo/ignore [:type-mismatch]}
    (testing "invalid parameters"
      (is (thrown-with-msg?
            Exception
            #"The `client` must satisfy `client\?` predicate"
            (sut/explore {:bot-token test-bot-token} :get-me)))
      (is (thrown-with-msg?
            Exception
            #"The `method` must satisfy `keyword\?` predicate"
            (sut/explore client "get-me"))))
    (testing "valid parameters"
      (let [available-methods (set (map first (sut/explore client)))]
        (is (every? available-methods [:get-me
                                       :get-chat
                                       :send-message
                                       :get-my-commands
                                       :create-invoice-link
                                       :send-audio
                                       :set-webhook
                                       :send-photo
                                       :send-rich-message
                                       :send-media-group
                                       :edit-ephemeral-message-media])
            "All methods used in this test namespace must be available")))))

(defn ctx->mock-responses
  [ctx]
  {:status 200
   :body   {:ok     true
            :result (:request ctx)}})

(deftest make-request!-test
  (let [client (sut/->client {:bot-token test-bot-token
                              :responses ctx->mock-responses})]
    #_{:clj-kondo/ignore [:type-mismatch]}
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
                                                        :text    "Hello, world!"})))
        (testing "holding a keyword type value"
          ;; NB: That's the very `bot-command-scope-default` of the utils ns.
          (is (= {:method  :post
                  :url     (str "https://api.telegram.org/bot" test-bot-token "/getMyCommands")
                  :headers {"Content-Type" "application/json"}
                  :body    "{\"scope\":\"{\\\"type\\\":\\\"default\\\"}\"}"}
                 (sut/make-request! client :get-my-commands {:scope {:type :default}}))))
        (testing "deeply-nested in recursive ones"
          (is (= {:method  :post
                  :url     (str "https://api.telegram.org/bot" test-bot-token "/sendRichMessage")
                  :headers {"Content-Type" "application/json"}
                  :body    (str "{\"chat_id\":1"
                                ",\"rich_message\":{\"blocks\":[{\"type\":\"paragraph\""
                                ",\"text\":[\"It's \",{\"type\":\"bold\""
                                ",\"text\":[\"very \",{\"type\":\"italic\""
                                ",\"text\":{\"type\":\"underline\""
                                ",\"text\":[{\"type\":\"date_time\",\"text\":\"now\""
                                ",\"unix_time\":1700000000,\"date_time_format\":\"r\"}]}}]}]}]}}")}
                 (sut/make-request! client :send-rich-message
                                    {:chat-id      1
                                     :rich-message {:blocks [{:type "paragraph"
                                                              :text ["It's "
                                                                     {:type "bold"
                                                                      :text ["very "
                                                                             {:type "italic"
                                                                              :text {:type "underline"
                                                                                     :text [{:type             "date_time"
                                                                                             :text             "now"
                                                                                             :unix-time        1700000000
                                                                                             :date-time-format "r"}]}}]}]}]}})))))
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
                            {:name "audio" :content test-file}]}
               (sut/make-request! client :send-audio {:chat-id 1
                                                      :audio   test-file})))
        (testing "with JSON-serialized params"
          (is (= {:method    :post
                  :url       (str "https://api.telegram.org/bot" test-bot-token "/setWebhook")
                  :headers   nil
                  :multipart [{:name "url" :content "https://example.com"}
                              {:name "certificate" :content test-file}
                              {:name "allowed_updates" :content (str "[\"message\""
                                                                     ",\"edited_channel_post\""
                                                                     ",\"callback_query\"]")}]}
                 (sut/make-request! client :set-webhook {:url             "https://example.com"
                                                         :certificate     test-file
                                                         :allowed_updates ["message"
                                                                           "edited_channel_post"
                                                                           "callback_query"]}))))
        (testing "with object params"
          ;; NB: The `reply_parameters` is not marked as JSON-serialized by the docs.
          (is (= {:method    :post
                  :url       (str "https://api.telegram.org/bot" test-bot-token "/sendPhoto")
                  :headers   nil
                  :multipart [{:name "chat_id" :content "1"}
                              {:name "photo" :content test-file}
                              {:name "reply_parameters" :content "{\"message_id\":2}"}]}
                 (sut/make-request! client :send-photo {:chat-id          1
                                                        :photo            test-file
                                                        :reply-parameters {:message-id 2}}))))
        (testing "with nested files"
          ;; NB: Each one goes as a separate part, with an "attach://" in its place.
          (testing "in a block of a rich message"
            (is (= {:method    :post
                    :url       (str "https://api.telegram.org/bot" test-bot-token "/sendRichMessage")
                    :headers   nil
                    :multipart [{:name "chat_id" :content "1"}
                                {:name "rich_message" :content (str "{\"blocks\":[{\"type\":\"document\""
                                                                    ",\"document\":{\"type\":\"document\""
                                                                    ",\"media\":\"attach://file0\"}}]}")}
                                {:name "file0" :content test-file}]}
                   (sut/make-request! client :send-rich-message
                                      {:chat-id      1
                                       :rich-message {:blocks [{:type     "document"
                                                                :document {:type  "document"
                                                                           :media test-file}}]}}))))
          (testing "in the media of a rich message text"
            ;; NB: That's the media that a `tg://document?id=` link refers to.
            (is (= {:method    :post
                    :url       (str "https://api.telegram.org/bot" test-bot-token "/sendRichMessage")
                    :headers   nil
                    :multipart [{:name "chat_id" :content "1"}
                                {:name "rich_message" :content (str "{\"markdown\":\"![](tg://document?id=report)\""
                                                                    ",\"media\":[{\"id\":\"report\""
                                                                    ",\"media\":{\"type\":\"document\""
                                                                    ",\"media\":\"attach://file0\"}}]}")}
                                {:name "file0" :content test-file}]}
                   (sut/make-request! client :send-rich-message
                                      {:chat-id      1
                                       :rich-message {:markdown "![](tg://document?id=report)"
                                                      :media    [{:id    "report"
                                                                  :media {:type  "document"
                                                                          :media test-file}}]}}))))
          (testing "in each item of an array"
            (is (= {:method    :post
                    :url       (str "https://api.telegram.org/bot" test-bot-token "/sendMediaGroup")
                    :headers   nil
                    :multipart [{:name "chat_id" :content "1"}
                                {:name "media" :content (str "[{\"type\":\"photo\",\"media\":\"attach://file0\"}"
                                                             ",{\"type\":\"photo\",\"media\":\"attach://file1\"}]")}
                                {:name "file0" :content test-file}
                                {:name "file1" :content test-file}]}
                   (sut/make-request! client :send-media-group
                                      {:chat-id 1
                                       :media   [{:type "photo" :media test-file}
                                                 {:type "photo" :media test-file}]}))))
          (testing "in an object param"
            (is (= {:method    :post
                    :url       (str "https://api.telegram.org/bot" test-bot-token "/editEphemeralMessageMedia")
                    :headers   nil
                    :multipart [{:name "chat_id" :content "1"}
                                {:name "receiver_user_id" :content "2"}
                                {:name "ephemeral_message_id" :content "3"}
                                {:name "media" :content "{\"type\":\"photo\",\"media\":\"attach://file0\"}"}
                                {:name "file0" :content test-file}]}
                   (sut/make-request! client :edit-ephemeral-message-media
                                      {:chat-id              1
                                       :receiver-user-id     2
                                       :ephemeral-message-id 3
                                       :media                {:type "photo" :media test-file}})))))
        (testing "with no file to upload"
          ;; NB: A method that can upload a file goes as JSON when it has none.
          (is (= {:method  :post
                  :url     (str "https://api.telegram.org/bot" test-bot-token "/sendAudio")
                  :headers {"Content-Type" "application/json"}
                  :body    "{\"chat_id\":1,\"audio\":\"<file_id>\"}"}
                 (sut/make-request! client :send-audio {:chat-id 1
                                                        :audio   "<file_id>"}))))))

    ;; NB: The one place where every API method is exercised, if only barely.
    (testing "params coercion for every method"
      (let [api-methods (map first (sut/explore client))
            failure-map (reduce (fn [res method]
                                  ;; NB: An empty params map either builds a request (no required)
                                  ;;     or fails the coercion. There is no other expected outcome
                                  ;;     if the API spec is healthy.
                                  (try
                                    (sut/make-request! client method {})
                                    res
                                    (catch Exception e
                                      (let [ex-msg (str (ex-message e))]
                                        (if (re-find #"Could not coerce value to schema" ex-msg)
                                          res
                                          (assoc res method ex-msg))))))
                                {}
                                api-methods)]
        (is (empty? failure-map)
            (format "Methods failing in unexpected way:\n%s"
                    (with-out-str (pp/pprint failure-map))))))))

(deftest build-response-test
  (let [client (sut/->client {:bot-token test-bot-token})]
    #_{:clj-kondo/ignore [:type-mismatch]}
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
                                                       :text    "Hello, world!"})))
      (testing "deeply-nested"
        (is (= {:status  200
                :headers {"Content-Type" "application/json"
                          "Accept"       "application/json"}
                :body    (str "{\"chat_id\":1"
                              ",\"reply_markup\":\"{\\\"inline_keyboard\\\":[[{\\\"text\\\":\\\"Button\\\",\\\"callback_data\\\":\\\"42\\\"}]]}\""
                              ",\"text\":\"Hello, world!\""
                              ",\"method\":\"sendMessage\"}")}
               (sut/build-response client :send-message
                                   {:chat-id      1
                                    :text         "Hello, world!"
                                    :reply-markup {:inline-keyboard [[{:text          "Button"
                                                                       :callback-data "42"}]]}})))))
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
                          {:name "audio" :content test-file}
                          {:name "method" :content "sendAudio"}]}
             (sut/build-response client :send-audio {:chat-id 1
                                                     :audio   test-file})))
      (testing "with JSON-serialized params"
        (is (= {:status    200
                :headers   {"Accept" "application/json"}
                :multipart [{:name "url" :content "https://example.com"}
                            {:name "certificate" :content test-file}
                            {:name "allowed_updates" :content (str "[\"message\""
                                                                   ",\"edited_channel_post\""
                                                                   ",\"callback_query\"]")}
                            {:name "method" :content "setWebhook"}]}
               (sut/build-response client :set-webhook {:url             "https://example.com"
                                                        :certificate     test-file
                                                        :allowed_updates ["message"
                                                                          "edited_channel_post"
                                                                          "callback_query"]}))))
      (testing "with object params"
        ;; NB: The `reply_parameters` is not marked as JSON-serialized by the docs.
        (is (= {:status    200
                :headers   {"Accept" "application/json"}
                :multipart [{:name "chat_id" :content "1"}
                            {:name "photo" :content test-file}
                            {:name "reply_parameters" :content "{\"message_id\":2}"}
                            {:name "method" :content "sendPhoto"}]}
               (sut/build-response client :send-photo {:chat-id          1
                                                       :photo            test-file
                                                       :reply-parameters {:message-id 2}}))))
      (testing "with nested files"
        ;; NB: Each one goes as a separate part, with an "attach://" in its place.
        (testing "in a block of a rich message"
          (is (= {:status    200
                  :headers   {"Accept" "application/json"}
                  :multipart [{:name "chat_id" :content "1"}
                              {:name "rich_message" :content (str "{\"blocks\":[{\"type\":\"document\""
                                                                  ",\"document\":{\"type\":\"document\""
                                                                  ",\"media\":\"attach://file0\"}}]}")}
                              {:name "file0" :content test-file}
                              {:name "method" :content "sendRichMessage"}]}
                 (sut/build-response client :send-rich-message
                                     {:chat-id      1
                                      :rich-message {:blocks [{:type     "document"
                                                               :document {:type  "document"
                                                                          :media test-file}}]}}))))
        (testing "in the media of a rich message text"
          ;; NB: That's the media that a `tg://document?id=` link refers to.
          (is (= {:status    200
                  :headers   {"Accept" "application/json"}
                  :multipart [{:name "chat_id" :content "1"}
                              {:name "rich_message" :content (str "{\"markdown\":\"![](tg://document?id=report)\""
                                                                  ",\"media\":[{\"id\":\"report\""
                                                                  ",\"media\":{\"type\":\"document\""
                                                                  ",\"media\":\"attach://file0\"}}]}")}
                              {:name "file0" :content test-file}
                              {:name "method" :content "sendRichMessage"}]}
                 (sut/build-response client :send-rich-message
                                     {:chat-id      1
                                      :rich-message {:markdown "![](tg://document?id=report)"
                                                     :media    [{:id    "report"
                                                                 :media {:type  "document"
                                                                         :media test-file}}]}}))))
        (testing "in each item of an array"
          (is (= {:status    200
                  :headers   {"Accept" "application/json"}
                  :multipart [{:name "chat_id" :content "1"}
                              {:name "media" :content (str "[{\"type\":\"photo\",\"media\":\"attach://file0\"}"
                                                           ",{\"type\":\"photo\",\"media\":\"attach://file1\"}]")}
                              {:name "file0" :content test-file}
                              {:name "file1" :content test-file}
                              {:name "method" :content "sendMediaGroup"}]}
                 (sut/build-response client :send-media-group
                                     {:chat-id 1
                                      :media   [{:type "photo" :media test-file}
                                                {:type "photo" :media test-file}]}))))
        (testing "in an object param"
          (is (= {:status    200
                  :headers   {"Accept" "application/json"}
                  :multipart [{:name "chat_id" :content "1"}
                              {:name "receiver_user_id" :content "2"}
                              {:name "ephemeral_message_id" :content "3"}
                              {:name "media" :content "{\"type\":\"photo\",\"media\":\"attach://file0\"}"}
                              {:name "file0" :content test-file}
                              {:name "method" :content "editEphemeralMessageMedia"}]}
                 (sut/build-response client :edit-ephemeral-message-media
                                     {:chat-id              1
                                      :receiver-user-id     2
                                      :ephemeral-message-id 3
                                      :media                {:type "photo" :media test-file}})))))
      (testing "with no file to upload"
        ;; NB: A method that can upload a file goes as JSON when it has none.
        (is (= {:status  200
                :headers {"Content-Type" "application/json"
                          "Accept"       "application/json"}
                :body    "{\"chat_id\":1,\"audio\":\"<file_id>\",\"method\":\"sendAudio\"}"}
               (sut/build-response client :send-audio {:chat-id 1
                                                       :audio   "<file_id>"}))))))
  (testing "presence of mock responses does not affect interceptors chain"
    (let [client (sut/->client {:bot-token test-bot-token
                                :responses {}})]
      (is (= {:status  200
              :headers {"Content-Type" "application/json"}
              :body    "{\"method\":\"getMe\"}"}
             (sut/build-response client :get-me))))))

(comment
  (clojure.test/run-tests)
  :end/comment)
