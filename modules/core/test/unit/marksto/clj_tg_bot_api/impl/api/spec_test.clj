(ns marksto.clj-tg-bot-api.impl.api.spec-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [marksto.clj-tg-bot-api.impl.api.spec :as sut]
   [schema.core :as s]))

(defn- checker-error
  "Gives the exception a `schema` fails to build a checker with, if any."
  [schema]
  (try (s/checker schema) nil (catch Throwable t t)))

(defn- rejected-by?
  [schema params constraint]
  (str/includes? (pr-str (s/check schema params)) (name constraint)))

(defn- type-schema-of [type]
  (->> (:types (sut/get-tg-bot-api-spec))
       (some #(when (= type (:name %)) %))
       :schema))

(defn- params-schema-of [method]
  (->> (:methods (sut/get-tg-bot-api-spec))
       (some #(when (= method (:name %)) %))
       :params-schema))

;;; Spec-wide Invariants

(deftest tg-bot-api-spec-parses-well
  (let [{:keys [types methods] :as tg-bot-api-spec} (sut/get-tg-bot-api-spec)]
    (is (some? tg-bot-api-spec)
        "The spec must parse (check the logged error if it does not)")
    (is (seq types)
        "The parsed spec must have the API types")
    (is (seq methods)
        "The parsed spec must have the API methods")))

(deftest type-schema-vars-are-bound
  (sut/get-tg-bot-api-spec)
  (let [unbound-vars (->> (ns-interns sut/type-schemas-ns)
                          (remove (comp bound? val))
                          (mapv key))]
    (is (empty? unbound-vars)
        "Every interned type schema var must be bound to a schema")))

(deftest every-schema-checker-builds
  (let [{:keys [types methods]} (sut/get-tg-bot-api-spec)]
    ;; NB: Building a checker is what forces every `s/recursive` reference
    ;;     in a schema to resolve. Merely parsing the spec does not, which
    ;;     is how an unresolvable reference reaches the API method callers.
    (testing "API types"
      (doseq [{:keys [name schema]} types]
        (is (nil? (checker-error schema))
            (format "The '%s' type schema must build a checker" name))))
    (testing "API methods"
      (doseq [{:keys [name params-schema]} methods
              :when (some? params-schema)]
        (is (nil? (checker-error params-schema))
            (format "The '%s' method params schema must build a checker" name))))))

;;; Constraints

(deftest array-size-constraint-is-enforced
  (testing "a bounded range of items"
    (let [send-poll (params-schema-of "sendPoll")]
      (is (nil? (s/check send-poll
                         {:chat_id  1 :question "q"
                          :options  (repeat 12 {:text "a"})}))
          "Options right at the upper bound must pass")
      (is (rejected-by? send-poll
                        {:chat_id 1 :question "q"
                         :options (repeat 13 {:text "a"})}
                        'array-constraints)
          "Options over the upper bound must fail")
      (is (rejected-by? send-poll
                        {:chat_id 1 :question "q"
                         :options []}
                        'array-constraints)
          "Options under the lower bound must fail")))
  (testing "an upper bound alone"
    ;; NB: `setMyCommands` states "At most 100 commands can be specified",
    ;;     naming no lower bound, so an empty list must stay acceptable.
    (let [set-my-commands (params-schema-of "setMyCommands")]
      (is (nil? (s/check set-my-commands
                         {:commands []}))
          "No commands at all must pass")
      (is (rejected-by? set-my-commands
                        {:commands (repeat 101 {:command "cmd" :description "d"})}
                        'array-constraints)
          "Commands over the upper bound must fail")))
  (testing "a field of a type rather than a param of a method"
    ;; NB: `InputChecklist` states "List of 1-30 tasks in the checklist".
    (let [input-checklist (type-schema-of "InputChecklist")]
      (is (nil? (s/check input-checklist
                         {:title "t"
                          :tasks (for [n (range 1 31)] {:id n :text "task"})}))
          "Tasks right at the upper bound must pass")
      (is (rejected-by? input-checklist
                        {:title "t"
                         :tasks (for [n (range 1 32)] {:id n :text "task"})}
                        'array-constraints)
          "Tasks over the upper bound must fail"))))

(deftest array-total-length-constraint-is-enforced
  (let [set-sticker-kwds (params-schema-of "setStickerKeywords")]
    (is (nil? (s/check set-sticker-kwds
                       {:sticker  "sticker-file-id"
                        :keywords ["cat" "kitten" "кот" "котёнок"]}))
        "Keywords within the 64 characters in total must pass")
    (is (rejected-by? set-sticker-kwds
                      {:sticker  "sticker-file-id"
                       :keywords [(str/join (repeat 32 \a))
                                  (str/join (repeat 33 \b))]}
                      'array-constraints)
        "Keywords over the 64 characters in total must fail, each being shorter")))

(deftest number-in-constraint-is-enforced
  (testing "a span trailing the prose"
    ;; NB: `sendLocation` states "measured in meters; 0-1500" on its
    ;;     `horizontal_accuracy`, which is a Float rather than an Integer.
    (let [send-location (params-schema-of "sendLocation")]
      (is (nil? (s/check send-location
                         {:chat_id             1
                          :latitude            51.5
                          :longitude           -0.12
                          :horizontal_accuracy 1500.0}))
          "An accuracy right at the upper bound must pass")
      (is (rejected-by? send-location
                        {:chat_id             1
                         :latitude            51.5
                         :longitude           -0.12
                         :horizontal_accuracy 1500.5}
                        'number-constraints)
          "An accuracy over the upper bound must fail")))
  (testing "a span stated as a sentence of its own"
    ;; NB: `sendLocation` states "Must be between 1 and 360 if specified"
    ;;     on its `heading`.
    (let [send-location (params-schema-of "sendLocation")]
      (is (nil? (s/check send-location
                         {:chat_id   1
                          :latitude  51.5
                          :longitude -0.12
                          :heading   360}))
          "A heading right at the upper bound must pass")
      (is (rejected-by? send-location
                        {:chat_id   1
                         :latitude  51.5
                         :longitude -0.12
                         :heading   0}
                        'number-constraints)
          "A heading under the lower bound must fail")))
  (testing "a span stated as the accepted values"
    ;; NB: `getUpdates` states "Values between 1-100 are accepted" on `limit`.
    (let [get-updates (params-schema-of "getUpdates")]
      (is (nil? (s/check get-updates {:limit 100}))
          "A limit right at the upper bound must pass")
      (is (rejected-by? get-updates {:limit 101} 'number-constraints)
          "A limit over the upper bound must fail")))
  (testing "a field of a type rather than a param of a method"
    ;; NB: `Birthdate` states "Month of the user's birth; 1-12" on its `month`.
    (let [birthdate (type-schema-of "Birthdate")]
      (is (nil? (s/check birthdate {:day 29 :month 12}))
          "A month right at the upper bound must pass")
      (is (rejected-by? birthdate {:day 29 :month 13} 'number-constraints)
          "A month over the upper bound must fail")))
  (testing "a span together with the exact values stated beside it"
    ;; NB: `sendLocation` states "must be between 60 and 86400, or 0x7FFFFFFF
    ;;     for live locations that can be edited indefinitely" on its
    ;;     `live_period`, and then "Must be 0 for ephemeral messages", so all
    ;;     three of the terms admit a value on their own.
    (let [send-location (params-schema-of "sendLocation")]
      (is (nil? (s/check send-location
                         {:chat_id     1
                          :latitude    51.5
                          :longitude   -0.12
                          :live_period 60}))
          "A period right at the span's lower bound must pass")
      (is (nil? (s/check send-location
                         {:chat_id     1
                          :latitude    51.5
                          :longitude   -0.12
                          :live_period 86400}))
          "A period right at the span's upper bound must pass")
      (is (nil? (s/check send-location
                         {:chat_id     1
                          :latitude    51.5
                          :longitude   -0.12
                          :live_period 0x7FFFFFFF}))
          "The exact value standing for an indefinite period must pass")
      (is (nil? (s/check send-location
                         {:chat_id     1
                          :latitude    51.5
                          :longitude   -0.12
                          :live_period 0}))
          "The exact value standing for an ephemeral message must pass")
      (is (rejected-by? send-location
                        {:chat_id     1
                         :latitude    51.5
                         :longitude   -0.12
                         :live_period 59}
                        'number-constraints)
          "A period just under the span must fail, no exact value matching it")
      (is (rejected-by? send-location
                        {:chat_id     1
                         :latitude    51.5
                         :longitude   -0.12
                         :live_period 86401}
                        'number-constraints)
          "A period just over the span must fail, no exact value matching it")))
  (testing "the exact values alone"
    ;; NB: `giftPremiumSubscription` states "must be one of 3, 6, or 12" on
    ;;     its `month_count`, which bounds nothing yet admits just the three.
    (let [gift-premium (params-schema-of "giftPremiumSubscription")]
      (is (nil? (s/check gift-premium
                         {:user_id 1 :star_count 1000 :month_count 3}))
          "The least of the exact values must pass")
      (is (nil? (s/check gift-premium
                         {:user_id 1 :star_count 1000 :month_count 12}))
          "The greatest of the exact values must pass")
      (is (rejected-by? gift-premium
                        {:user_id 1 :star_count 1000 :month_count 4}
                        'number-constraints)
          "A value between two of the exact ones must fail")
      (is (rejected-by? gift-premium
                        {:user_id 1 :star_count 1000 :month_count 24}
                        'number-constraints)
          "A value beyond all the exact ones must fail")))
  (testing "a span whose bound the docs spell out as a product"
    ;; NB: `BusinessOpeningHoursInterval` states "; 0 - 7 * 24 * 60" on its
    ;;     `opening_minute` and "; 0 - 8 * 24 * 60" on its `closing_minute`,
    ;;     a week's worth of minutes left unmultiplied.
    (let [opening-hours (type-schema-of "BusinessOpeningHoursInterval")]
      (is (nil? (s/check opening-hours
                         {:opening_minute 10080
                          :closing_minute 11520}))
          "Minutes right at either upper bound must pass")
      (is (rejected-by? opening-hours
                        {:opening_minute 10081
                         :closing_minute 11520}
                        'number-constraints)
          "A minute over the evaluated product must fail")
      (is (rejected-by? opening-hours
                        {:opening_minute 10080
                         :closing_minute 11521}
                        'number-constraints)
          "The two bounds must differ, the one week from the other"))))

(deftest byte-length-constraint-is-enforced
  (let [inline-kbd-button (type-schema-of "InlineKeyboardButton")]
    (is (nil? (s/check inline-kbd-button
                       {:text          "Tap me"
                        :callback_data (str/join (repeat 64 \a))}))
        "Callback data within the 64 bytes must pass")
    (is (rejected-by? inline-kbd-button
                      {:text          "Tap me"
                       :callback_data (str/join (repeat 33 \ж))}
                      'string-constraints)
        "Callback data over the 64 bytes must fail, being only 33 characters")))

(deftest pattern-constraint-is-enforced
  (testing "an enumerated character set"
    (let [set-webhook (params-schema-of "setWebhook")]
      (is (nil? (s/check set-webhook
                         {:url          "https://example.com/hook"
                          :secret_token "s3cret_token-42"}))
          "A secret token of the allowed characters must pass")
      (is (rejected-by? set-webhook
                        {:url          "https://example.com/hook"
                         :secret_token "s3cret token"}
                        'string-constraints)
          "A secret token with a disallowed character must fail")))
  (testing "a character set banning emoji"
    (let [set-custom-title (params-schema-of "setChatAdministratorCustomTitle")]
      (is (nil? (s/check set-custom-title
                         {:chat_id 1 :user_id 2 :custom_title "Admin 1"}))
          "A custom title of mere digits and letters must pass")
      (is (nil? (s/check set-custom-title
                         {:chat_id 1 :user_id 2 :custom_title "#1 Boss 2*3"}))
          "A custom title with the keycap base chars must pass")
      (is (nil? (s/check set-custom-title
                         {:chat_id 1 :user_id 2 :custom_title "✓ ok"}))
          "A custom title with a non-emoji dingbat must pass")
      (is (rejected-by? set-custom-title
                        {:chat_id 1 :user_id 2 :custom_title "Admin 😃"}
                        'string-constraints)
          "A custom title with an emoji must fail")
      ;; NB: A keycap is out of reach for constraint. Its only `IsEmoji`
      ;;     code point is the ASCII digit, which the pattern exempts on
      ;;     purpose, the `U+FE0F` and the `U+20E3` that follow being an
      ;;     `IsEmoji_Component` each, but not an `IsEmoji`.
      #_(is (rejected-by? set-custom-title
                          {:chat_id 1 :user_id 2 :custom_title "1️⃣"}
                          'string-constraints)
            "A custom title with a keycap must fail")
      (is (rejected-by? set-custom-title
                        {:chat_id 1 :user_id 2 :custom_title "🇺🇸"}
                        'string-constraints)
          "A custom title with a flag must fail")
      (is (rejected-by? set-custom-title
                        {:chat_id 1 :user_id 2 :custom_title "Acme™"}
                        'string-constraints)
          "A custom title with a trade mark pictographic sign must fail")
      (is (rejected-by? set-custom-title
                        {:chat_id 1 :user_id 2 :custom_title "© marksto"}
                        'string-constraints)
          "A custom title with a copyright pictographic sign must fail")))
  (testing "a character set with a prefix and a repetition rule"
    (let [new-sticker-set (params-schema-of "createNewStickerSet")]
      (is (nil? (s/check new-sticker-set
                         {:name     "animals_by_mybot"
                          :user_id  1
                          :title    "Animals"
                          :stickers [{:sticker    "sticker-file-id"
                                      :format     "static"
                                      :emoji_list ["🐱"]}]}))
          "A sticker set name of the allowed shape must pass")
      (is (rejected-by? new-sticker-set
                        {:name     "1animals_by_mybot"
                         :user_id  1
                         :title    "Animals"
                         :stickers [{:sticker    "sticker-file-id"
                                     :format     "static"
                                     :emoji_list ["🐱"]}]}
                        'string-constraints)
          "A sticker set name not beginning with a letter must fail")
      (is (rejected-by? new-sticker-set
                        {:name     "animals__by_mybot"
                         :user_id  1
                         :title    "Animals"
                         :stickers [{:sticker    "sticker-file-id"
                                     :format     "static"
                                     :emoji_list ["🐱"]}]}
                        'string-constraints)
          "A sticker set name with consecutive underscores must fail"))))

(deftest after-entities-parsing-length-is-enforced
  (let [send-msg (params-schema-of "sendMessage")
        at-limit (str/join (repeat 4096 \a))
        over-limit (str/join (repeat 4097 \a))]
    (testing "a plain text is what the server gets, so it is measured as is"
      (is (nil? (s/check send-msg
                         {:chat_id 1
                          :text    at-limit}))
          "A text within the 4096 characters must pass")
      (is (rejected-by? send-msg
                        {:chat_id 1
                         :text    over-limit}
                        'attrs-constraints)
          "A text over the 4096 characters must fail"))
    (testing "an HTML-styled text is measured once the markup is stripped"
      (is (nil? (s/check send-msg
                         {:chat_id    1
                          :text       (str "<b>" at-limit "</b>")
                          :parse_mode "HTML"}))
          "A markup of 4102 characters holding 4096 of them must pass")
      (is (rejected-by? send-msg
                        {:chat_id    1
                         :text       (str "<b>" over-limit "</b>")
                         :parse_mode "HTML"}
                        'attrs-constraints)
          "A markup of 4103 characters holding 4097 of them must fail")
      (is (nil? (s/check send-msg
                         {:chat_id    1
                          :text       (str/join (repeat 4096 "&amp;"))
                          :parse_mode "HTML"}))
          "An entity per character must be measured as a single character"))
    (testing "a legacy Markdown text is measured once the markup is stripped"
      (is (nil? (s/check send-msg
                         {:chat_id    1
                          :text       (str "*" at-limit "*")
                          :parse_mode "Markdown"}))
          "A markup of 4098 characters holding 4096 of them must pass")
      (is (rejected-by? send-msg
                        {:chat_id    1
                         :text       (str "*" over-limit "*")
                         :parse_mode "Markdown"}
                        'attrs-constraints)
          "A markup of 4099 characters holding 4097 of them must fail")
      (is (nil? (s/check send-msg
                         {:chat_id    1
                          :text       (str "[" at-limit "](http://e.com/)")
                          :parse_mode "Markdown"}))
          "A link URL must not be measured, only its text"))
    (testing "a MarkdownV2 text is measured once the markup is stripped"
      (is (nil? (s/check send-msg
                         {:chat_id    1
                          :text       (str "*" at-limit "*")
                          :parse_mode "MarkdownV2"}))
          "A markup of 4098 characters holding 4096 of them must pass")
      (is (rejected-by? send-msg
                        {:chat_id    1
                         :text       (str "*" over-limit "*")
                         :parse_mode "MarkdownV2"}
                        'attrs-constraints)
          "A markup of 4099 characters holding 4097 of them must fail")
      (is (nil? (s/check send-msg
                         {:chat_id    1
                          :text       (str/join (repeat 4096 "\\."))
                          :parse_mode "MarkdownV2"}))
          "An escape per character must be measured as a single character")
      (is (nil? (s/check send-msg
                         {:chat_id    1
                          :text       (str "*" over-limit)
                          :parse_mode "MarkdownV2"}))
          "An unterminated markup must pass, since its length is unknown"))
    (testing "a parse mode we do not know is left to the server"
      (is (nil? (s/check send-msg
                         {:chat_id    1
                          :text       over-limit
                          :parse_mode "UnknownStyle"}))
          "A text over the limit must pass, since its length is unknown"))))

(deftest at-most-one-relation-is-enforced
  (testing "a single relation between the two params"
    ;; NB: `sendPoll` states "Can't be used together with close_date" on its
    ;;     `open_period`, and the very same the other way round.
    (let [send-poll (params-schema-of "sendPoll")]
      (is (nil? (s/check send-poll
                         {:chat_id 1 :question "q" :options [{:text "a"}]}))
          "Neither of the two mutually exclusive params must pass")
      (is (nil? (s/check send-poll
                         {:chat_id     1 :question "q" :options [{:text "a"}]
                          :open_period 60}))
          "The one of the two must pass")
      (is (nil? (s/check send-poll
                         {:chat_id    1 :question "q" :options [{:text "a"}]
                          :close_date 1700000000}))
          "The other of the two must pass")
      (is (rejected-by? send-poll
                        {:chat_id     1 :question "q" :options [{:text "a"}]
                         :open_period 60
                         :close_date  1700000000}
                        'attrs-constraints)
          "Both of the two at once must fail"))))

(deftest at-least-one-relation-is-enforced
  (testing "a single relation between the two params"
    ;; NB: A `sendGift` with both `user_id` and `chat_id` is accepted. The docs
    ;;     only say each is required when the other is absent — they never say
    ;;     the two are exclusive — so inferring `at_most_one` would be guessing,
    ;;     and permissive is the safe direction.
    (let [send-gift (params-schema-of "sendGift")]
      (is (nil? (s/check send-gift
                         {:gift_id "gift-id" :user_id 1}))
          "The one of the two must pass")
      (is (nil? (s/check send-gift
                         {:gift_id "gift-id" :chat_id 1}))
          "The other of the two must pass")
      (is (rejected-by? send-gift
                        {:gift_id "gift-id"}
                        'attrs-constraints)
          "Neither of the two must fail")))
  (testing "several relations at once"
    ;; NB: `editMessageText` states a relation between its `chat_id` and
    ;;     `inline_message_id`, another one between the latter and its
    ;;     `message_id`, and one more between its `text` and the
    ;;     `rich_message`. Each of the three is checked on its own, with
    ;;     the other two satisfied.
    (let [edit-text (params-schema-of "editMessageText")]
      (testing "identifying the message to edit"
        (is (nil? (s/check edit-text
                           {:text              "Hello, world!"
                            :inline_message_id "id"}))
            "An inline message ID alone must pass")
        (is (nil? (s/check edit-text
                           {:text    "Hello, world!"
                            :chat_id 1 :message_id 2}))
            "A chat ID together with a message ID must pass")
        (is (rejected-by? edit-text
                          {:text    "Hello, world!"
                           :chat_id 1}
                          'attrs-constraints)
            "A chat ID without a message ID must fail")
        (is (rejected-by? edit-text
                          {:text       "Hello, world!"
                           :message_id 2}
                          'attrs-constraints)
            "A message ID without a chat ID must fail")
        (is (rejected-by? edit-text
                          {:text "Hello, world!"}
                          'attrs-constraints)
            "No identification whatsoever must fail"))
      (testing "supplying the new message content"
        (is (nil? (s/check edit-text
                           {:text              "Hello, world!"
                            :inline_message_id "id"}))
            "A text alone must pass")
        (is (nil? (s/check edit-text
                           {:rich_message      {}
                            :inline_message_id "id"}))
            "A rich message alone must pass")
        (is (rejected-by? edit-text
                          {:inline_message_id "id"}
                          'attrs-constraints)
            "Neither a text nor a rich message must fail")))))

(deftest required-with-relation-is-enforced
  (testing "a single relation that only holds the one way round"
    ;; NB: `message_id` is "Required if `business_connection_id` is specified"
    ;;     for `unpinChatMessage`, and there's nothing the other way round.
    (let [unpin-message (params-schema-of "unpinChatMessage")]
      (is (nil? (s/check unpin-message
                         {:chat_id                1
                          :business_connection_id "id"
                          :message_id             2}))
          "Both of the two must pass")
      (is (rejected-by? unpin-message
                        {:chat_id                1
                         :business_connection_id "id"}
                        'attrs-constraints)
          "The other one without the required one must fail")
      (is (nil? (s/check unpin-message
                         {:chat_id    1
                          :message_id 2}))
          "The required one without the other one must pass")
      (is (nil? (s/check unpin-message
                         {:chat_id 1}))
          "Neither of the two must pass"))))

;;

(comment
  (clojure.test/run-tests)
  :end/comment)
