(ns marksto.clj-tg-bot-api.impl.api.spec-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [marksto.clj-tg-bot-api.impl.api.spec :as sut]
   [schema.core :as s]))

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
        (is (some? (s/checker schema))
            (format "The '%s' type schema must build a checker" name))))
    (testing "API methods"
      (doseq [{:keys [name params-schema]} methods
              :when (some? params-schema)]
        (is (some? (s/checker params-schema))
            (format "The '%s' method params schema must build a checker" name))))))

(deftest array-total-length-constraint-is-enforced
  (let [{:keys [methods]} (sut/get-tg-bot-api-spec)
        {:keys [params-schema]} (some #(when (= "setStickerKeywords" (:name %)) %)
                                      methods)]
    (is (nil? (s/check params-schema {:sticker  "sticker-file-id"
                                      :keywords ["cat" "kitten" "кот" "котёнок"]}))
        "Keywords within the 64 characters in total must pass")
    (is (some? (s/check params-schema {:sticker  "sticker-file-id"
                                       :keywords [(str/join (repeat 32 \a))
                                                  (str/join (repeat 33 \b))]}))
        "Keywords over the 64 characters in total must fail, each being shorter")))

(deftest byte-length-constraint-is-enforced
  (let [{:keys [types]} (sut/get-tg-bot-api-spec)
        {:keys [schema]} (some #(when (= "InlineKeyboardButton" (:name %)) %)
                               types)]
    (is (nil? (s/check schema {:text          "Tap me"
                               :callback_data (str/join (repeat 64 \a))}))
        "Callback data within the 64 bytes must pass")
    (is (some? (s/check schema {:text          "Tap me"
                                :callback_data (str/join (repeat 33 \ж))}))
        "Callback data over the 64 bytes must fail, being only 33 characters")))

(deftest pattern-constraint-is-enforced
  (let [{:keys [methods]} (sut/get-tg-bot-api-spec)
        params-schema-of #(->> methods
                               (some (fn [m] (when (= % (:name m)) m)))
                               :params-schema)
        set-webhook (params-schema-of "setWebhook")
        set-custom-title (params-schema-of "setChatAdministratorCustomTitle")
        new-sticker-set (params-schema-of "createNewStickerSet")]
    (testing "an enumerated character set"
      (let [->params #(hash-map :url "https://example.com/hook" :secret_token %)]
        (is (nil? (s/check set-webhook (->params "s3cret_token-42")))
            "A secret token of the allowed characters must pass")
        (is (some? (s/check set-webhook (->params "s3cret token")))
            "A secret token with a disallowed character must fail")))
    (testing "a character set banning emoji"
      (let [->params #(hash-map :chat_id 1 :user_id 2 :custom_title %)]
        (is (nil? (s/check set-custom-title (->params "Admin 1")))
            "A custom title of mere digits and letters must pass")
        (is (nil? (s/check set-custom-title (->params "#1 Boss 2*3")))
            "A custom title with the keycap base chars must pass")
        (is (nil? (s/check set-custom-title (->params "✓ ok")))
            "A custom title with a non-emoji dingbat must pass")
        (is (some? (s/check set-custom-title (->params "Admin 😃")))
            "A custom title with an emoji must fail")
        #_(is (some? (s/check set-custom-title (->params "1️⃣")))
              "A custom title with a keycap must fail")
        (is (some? (s/check set-custom-title (->params "🇺🇸")))
            "A custom title with a flag must fail")
        (is (some? (s/check set-custom-title (->params "Acme™")))
            "A custom title with a pictographic sign must fail")
        (is (some? (s/check set-custom-title (->params "© marksto")))
            "A custom title with a pictographic sign must fail")))
    (testing "a character set with a prefix and a repetition rule"
      (let [->params #(hash-map :user_id 1 :name % :title "Animals"
                                :stickers [{:sticker    "sticker-file-id"
                                            :format     "static"
                                            :emoji_list ["🐱"]}])]
        (is (nil? (s/check new-sticker-set (->params "animals_by_mybot")))
            "A sticker set name of the allowed shape must pass")
        (is (some? (s/check new-sticker-set (->params "1animals_by_mybot")))
            "A sticker set name not beginning with a letter must fail")
        (is (some? (s/check new-sticker-set (->params "animals__by_mybot")))
            "A sticker set name with consecutive underscores must fail")))))

;;

(comment
  (clojure.test/run-tests)
  :end/comment)
