(ns marksto.clj-tg-bot-api.impl.api.spec-test
  (:require
   [clojure.java.io :as io]
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

(defn- type-of [type]
  (some #(when (= type (:name %)) %)
        (:types (sut/get-tg-bot-api-spec))))

(defn- type-schema-of [type]
  (:schema (type-of type)))

(defn- method-of [method]
  (some #(when (= method (:name %)) %)
        (:methods (sut/get-tg-bot-api-spec))))

(defn- params-schema-of [method]
  (:params-schema (method-of method)))

(defn- unions-in
  "Gives the members of every union that a `type` holds, if any."
  [type]
  (when (vector? type)
    (let [[container-type inner-type] type]
      (case container-type
        "array" (unions-in inner-type)
        "or" (cons inner-type (mapcat unions-in inner-type))))))

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

;;; Unions

(deftest same-type-members-are-distinguishable
  (let [{:keys [types methods]} (sut/get-tg-bot-api-spec)
        id->type (into {} (map (juxt :id identity)) types)]
    ;; NB: The union members that share a type value are told apart by the attrs
    ;;     each of them requires and none of the others has, so the member with
    ;;     no such attrs is unreachable, and its valid objects rejected. It holds
    ;;     for any union, be it an API type or that of a field or a param.
    (doseq [{:keys [name union fields params]} (concat types methods)
            [union-name union-type] (cons [name (when union ["or" union])]
                                          (for [attr (concat fields params)]
                                            [(str name "." (:name attr)) (:type attr)]))
            members (unions-in union-type)
            same-type-members (-> (group-by sut/->type-value (map id->type members))
                                  (dissoc nil)
                                  (vals))
            :when (next same-type-members)
            member same-type-members]
      (is (seq (sut/get-distinctive-attr-names
                 member (remove #{member} same-type-members)))
          (format "The '%s' member of '%s' must require a distinctive attr"
                  (:name member) union-name)))))

(deftest same-type-members-are-told-apart
  (testing "the two members differing in their required attrs alone"
    ;; NB: Both `InlineQueryResultCachedPhoto` and `InlineQueryResultPhoto`
    ;;     state "Type of the result, must be photo", so it is the former's
    ;;     `photo_file_id` and the latter's `photo_url` and `thumbnail_url`
    ;;     that tell the two apart.
    (let [inline-result (type-schema-of "InlineQueryResult")]
      (is (nil? (s/check inline-result
                         {:type          "photo"
                          :id            "1"
                          :photo_file_id "file-id"}))
          "A cached photo must pass")
      (is (nil? (s/check inline-result
                         {:type          "photo"
                          :id            "1"
                          :photo_url     "https://example.com/photo.jpg"
                          :thumbnail_url "https://example.com/thumb.jpg"}))
          "A URL photo must pass, though listed after the cached one")
      (is (rejected-by? inline-result
                        {:type      "photo"
                         :id        "1"
                         :photo_url "https://example.com/photo.jpg"}
                        'InlineQueryResultPhoto)
          "A URL photo without a thumbnail must fail as a URL photo")
      (is (rejected-by? inline-result
                        {:type          "photo"
                         :id            "1"
                         :photo_url     "https://example.com/photo.jpg"
                         :thumbnail_url "https://example.com/thumb.jpg"
                         :caption       (str/join (repeat 1025 \a))}
                        'attrs-constraints)
          "A URL photo must have its own constraints checked")
      (is (rejected-by? inline-result
                        {:type "photo"
                         :id   "1"}
                        'photo_file_id-or-photo_url+thumbnail_url?)
          "A photo of neither kind must fail, naming the attrs that tell them apart")))
  (testing "the two members sharing a required attr"
    ;; NB: `InlineQueryResultCachedVoice` requires a "Voice message title" and
    ;;     `InlineQueryResultVoice` requires a "Recording title", both being a
    ;;     `title`, so it is the `voice_url` alone that tells the latter apart.
    (let [inline-result (type-schema-of "InlineQueryResult")]
      (is (nil? (s/check inline-result
                         {:type          "voice"
                          :id            "1"
                          :voice_file_id "file-id"
                          :title         "t"}))
          "A cached voice must pass")
      (is (nil? (s/check inline-result
                         {:type      "voice"
                          :id        "1"
                          :voice_url "https://example.com/voice.ogg"
                          :title     "t"}))
          "A URL voice must pass, though listed after the cached one")
      (is (rejected-by? inline-result
                        {:type      "voice"
                         :id        "1"
                         :voice_url "https://example.com/voice.ogg"}
                        'InlineQueryResultVoice)
          "A URL voice without a title must fail as a URL voice"))))

(deftest union-members-stated-in-prose-are-accepted
  (testing "the members a union states in prose alone"
    ;; NB: `RichText` "can be either a String for plain text, an Array of
    ;;     RichText, or any of the following types", the latter being its
    ;;     listed API types, while the former two are no API types at all.
    (let [rich-text (type-schema-of "RichText")]
      (is (nil? (s/check rich-text "Hello, world!"))
          "A plain text must pass")
      (is (nil? (s/check rich-text ["Hello, " {:type "bold" :text "world"}]))
          "An array of a plain text and an API type must pass")
      (is (nil? (s/check rich-text [["Hello, "] ["world"]]))
          "An array of arrays must pass")
      (is (nil? (s/check rich-text {:type "bold"
                                    :text ["Hello, " {:type "italic" :text "world"}]}))
          "An API type holding an array must pass")
      (is (rejected-by? rich-text 42 'matches-some-precondition?)
          "A number must fail, being none of the members")
      (is (rejected-by? rich-text ["Hello, " 42] 'matches-some-precondition?)
          "An array holding a number must fail")
      (is (rejected-by? rich-text {:type "bogus" :text "Hello"} 'matches-some-precondition?)
          "An object of an unknown type must fail")
      (is (rejected-by? rich-text ["Hello, " {:type "bold"}] 'RichTextBold)
          "An API type within an array must fail as that very type")
      (is (rejected-by? rich-text {:type "date_time" :text "x"} 'RichTextDateTime)
          "An API type with just the attrs of another one must fail as that very type")))
  (testing "a param of a method holding such a union"
    ;; NB: These are the very params the `sendRichMessage` used to reject.
    (let [send-rich-message (params-schema-of "sendRichMessage")]
      (is (nil? (s/check send-rich-message
                         {:chat_id      1
                          :rich_message {:blocks [{:type "heading"
                                                   :size 1
                                                   :text "Hello"}
                                                  {:type "paragraph"
                                                   :text ["plain " {:type "bold" :text "bold"}]}]}}))
          "A rich message of plain and formatted texts must pass"))))

(deftest union-of-same-shape-api-types-accepts-both
  (testing "the members of a union listed in either order"
    ;; NB: A `Message` "represents a message" and an `InaccessibleMessage` one
    ;;     "that was deleted or is otherwise inaccessible to the bot", both of
    ;;     them requiring a `message_id`, a `date` and a `chat`. So it's the other
    ;;     keys of a message, and not the order of the two, that tell them apart.
    (let [chat {:id 1 :type "private"}]
      (doseq [members [["Message" "InaccessibleMessage"]
                       ["InaccessibleMessage" "Message"]]
              :let [union (sut/or-schema (map type-schema-of members) 'subtype?)]]
        (is (nil? (s/check union
                           {:message_id 1
                            :date       1700000000
                            :chat       chat
                            :text       "Hello"}))
            (format "A message must pass, with '%s' listed first" (first members)))
        (is (nil? (s/check union
                           {:message_id 1
                            :date       0
                            :chat       chat}))
            (format "An inaccessible message must pass, with '%s' listed first"
                    (first members)))))))

(deftest union-of-typed-api-types-is-told-apart-by-type
  (testing "the members of a union in a param of a method"
    ;; NB: The `sendMediaGroup` takes "an Array describing messages to be sent",
    ;;     each one being an audio, a document, a photo, a live photo or a video,
    ;;     and all but the live photo require just a `type` and a `media`. So each
    ;;     one is checked as the type it states, e.g. "Type of the media, must be
    ;;     audio", be its fields those of some other type or not.
    (let [send-media-group (params-schema-of "sendMediaGroup")]
      (doseq [media [{:type "audio" :media "file-id" :performer "Performer"}
                     {:type "document" :media "file-id" :disable_content_type_detection true}
                     {:type "photo" :media "file-id" :has_spoiler true}
                     {:type "video" :media "file-id" :supports_streaming true}]]
        (is (nil? (s/check send-media-group
                           {:chat_id 1
                            :media   [media media]}))
            (format "Media of the '%s' type with a field of its own must pass" (:type media))))
      (is (rejected-by? send-media-group
                        {:chat_id 1
                         :media   [{:type "photo" :media "file-id" :has_spoiler "yes"}
                                   {:type "photo" :media "file-id"}]}
                        'InputMediaPhoto)
          "A photo with an invalid field of its own must fail as a photo")
      (is (rejected-by? send-media-group
                        {:chat_id 1
                         :media   [{:type "video" :media "file-id" :has_spoiler "yes"}
                                   {:type "video" :media "file-id"}]}
                        'InputMediaVideo)
          "A video with an invalid field of its own must fail as a video")
      (is (rejected-by? send-media-group
                        {:chat_id 1
                         :media   [{:type "audio" :media "file-id" :has_spoiler true}
                                   {:type "audio" :media "file-id"}]}
                        'InputMediaAudio)
          "An audio with a field of a photo must fail as an audio")
      (is (rejected-by? send-media-group
                        {:chat_id 1
                         :media   [{:type "sticker" :media "file-id"}
                                   {:type "audio" :media "file-id"}]}
                        'some-matching-condition?)
          "Media of a type the union has not must fail")))
  (testing "the members of a union in a field of a type"
    ;; NB: An `InputRichMessageMedia` holds "the media to be sent", which is an
    ;;     animation, an audio, a document, a photo, a video or a voice note.
    (let [rich-message-media (type-schema-of "InputRichMessageMedia")]
      (is (nil? (s/check rich-message-media
                         {:id    "1"
                          :media {:type "voice_note" :media "file-id" :duration 1}}))
          "A voice note with a field of its own must pass")
      (is (rejected-by? rich-message-media
                        {:id    "1"
                         :media {:type "voice_note" :media "file-id" :width 1}}
                        'InputMediaVoiceNote)
          "A voice note with a field of a video must fail as a voice note"))))

(deftest union-of-api-types-and-others-accepts-both
  (testing "the members of a union that are no API types"
    ;; NB: No union in the spec has both several API types with no type values
    ;;     and a member of any other kind yet, so it's the `reply_markup` plus
    ;;     a String that make one up here.
    (let [reply-markup-or-text (sut/or-schema [(type-schema-of "InlineKeyboardMarkup")
                                               (type-schema-of "ReplyKeyboardMarkup")
                                               (type-schema-of "ReplyKeyboardRemove")
                                               (type-schema-of "ForceReply")
                                               s/Str])]
      (is (nil? (s/check reply-markup-or-text "Hello"))
          "A text must pass")
      (is (nil? (s/check reply-markup-or-text {:force_reply true}))
          "An API type must pass")
      (is (rejected-by? reply-markup-or-text 42 'matches-some-precondition?)
          "A number must fail, being none of the members")
      (is (rejected-by? reply-markup-or-text {:force_reply "yes"} 'ForceReply)
          "An API type must fail as that very type"))))

;;; Cyclic Types

(deftest cyclic-type-refs-are-resolved
  (testing "a ref from within a container type"
    ;; NB: `RichBlockCollage` holds the "Elements of the collage", an array of
    ;;     `RichBlock`, which unites the collage itself. So the two are parsed
    ;;     in no particular order, and the array may be built before the type
    ;;     it holds.
    (let [rich-block-collage (type-schema-of "RichBlockCollage")]
      (is (nil? (s/check rich-block-collage
                         {:type   "collage"
                          :blocks [{:type "divider"}]}))
          "A collage of a block must pass")
      (is (nil? (s/check rich-block-collage
                         {:type   "collage"
                          :blocks [{:type   "collage"
                                    :blocks [{:type "divider"}]}]}))
          "A collage of a collage must pass")
      (is (rejected-by? rich-block-collage
                        {:type   "collage"
                         :blocks [{:type "bogus"}]}
                        'some-matching-condition?)
          "A collage of an unknown block must fail as a block")))
  (testing "a ref among the union members told apart by their shapes"
    ;; NB: `MaybeInaccessibleMessage` "describes a message that can be
    ;;     inaccessible to the bot", either a `Message` or an `InaccessibleMessage`,
    ;;     both requiring a `message_id`, a `date` and a `chat`. And the former
    ;;     holds a `pinned_message` of the very union, so the union may be built
    ;;     before the shape of a message is known.
    (let [maybe-inaccessible-message (type-schema-of "MaybeInaccessibleMessage")
          chat {:id 1 :type "private"}]
      (is (nil? (s/check maybe-inaccessible-message
                         {:message_id 1
                          :date       1700000000
                          :chat       chat
                          :text       "Hello"}))
          "A message must pass")
      (is (nil? (s/check maybe-inaccessible-message
                         {:message_id     2
                          :date           1700000000
                          :chat           chat
                          :pinned_message {:message_id 1
                                           :date       1700000000
                                           :chat       chat
                                           :text       "Hello"}}))
          "A message holding a pinned message must pass")
      (is (nil? (s/check maybe-inaccessible-message
                         {:message_id 1
                          :date       0
                          :chat       chat}))
          "An inaccessible message must pass")
      (is (rejected-by? maybe-inaccessible-message
                        {:message_id 1
                         :date       1700000000
                         :chat       chat
                         :text       42}
                        'java.lang.String)
          "A message must fail as a message")
      (is (rejected-by? maybe-inaccessible-message
                        {:message_id 1}
                        'subtype?)
          "An object of neither shape must fail"))))

;;; Types Parsing Order

(deftest union-members-are-parsed-first
  (testing "the members of a union field"
    ;; NB: `InputRichMessageMedia` holds "The media to be sent", which is any of
    ;;     the six `InputMedia*` types, so the union of their schemas can only be
    ;;     built once all of them are.
    (let [{:keys [deps]} (sut/api-type->type-with-deps
                           (type-of "InputRichMessageMedia"))]
      (is (= #{"type/input-media-animation"
               "type/input-media-audio"
               "type/input-media-document"
               "type/input-media-photo"
               "type/input-media-video"
               "type/input-media-voice-note"}
             (set deps))
          "Every member of the union must be a dependency"))))

;;; File Uploads

(deftest attachable-fields-accept-input-file
  (testing "a nested field typed as a String"
    ;; NB: The `media` of an `InputMediaDocument` is the "File to send", which
    ;;     the docs type as a mere String, while its description tells of a new
    ;;     file to upload, passed as "attach://<file_attach_name>".
    (let [send-rich-message (params-schema-of "sendRichMessage")
          document {:type "document" :media (io/file "report.pdf")}]
      (is (nil? (s/check send-rich-message
                         {:chat_id      1
                          :rich_message {:blocks [{:type     "document"
                                                   :document document}]}}))
          "A file in a document block must pass")
      (is (nil? (s/check send-rich-message
                         {:chat_id      1
                          :rich_message {:markdown "![](tg://document?id=report)"
                                         :media    [{:id    "report"
                                                     :media document}]}}))
          "A file in the media of a text must pass"))))

(deftest nested-input-file-makes-method-upload-file
  (doseq [method ["sendRichMessage"
                  "editMessageText"
                  "editEphemeralMessageMedia"]]
    (is (:uploads-file? (method-of method))
        (format "The '%s' method must be able to upload a file" method)))
  (is (not (:uploads-file? (method-of "sendMessage")))
      "The 'sendMessage' method must not be able to upload a file"))

;;; Parameter Constraints

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

(deftest string-enum-constraint-is-enforced
  (testing "a param of a method, every member read past its own period"
    ;; NB: The method states "must be one of “static” for a .WEBP or .PNG image,
    ;;     “animated” for a .TGS animation, or “video” for a .WEBM video", where
    ;;     every member but the first stands after a period, so all 3 have to be
    ;;     read all the same.
    (let [set-thumbnail (params-schema-of "setStickerSetThumbnail")]
      (is (nil? (s/check set-thumbnail
                         {:name "pack_by_bot" :user_id 1 :format "static"}))
          "The member stated before any period must pass")
      (is (nil? (s/check set-thumbnail
                         {:name "pack_by_bot" :user_id 1 :format "animated"}))
          "The member stated after the first period must pass")
      (is (nil? (s/check set-thumbnail
                         {:name "pack_by_bot" :user_id 1 :format "video"}))
          "The member stated last must pass")
      (is (rejected-by? set-thumbnail
                        {:name "pack_by_bot" :user_id 1 :format "webp"}
                        'string-constraints)
          "A member that is not stated must fail")
      (is (rejected-by? set-thumbnail
                        {:name "pack_by_bot" :user_id 1 :format "STATIC"}
                        'string-constraints)
          "A stated member in another case must fail, the match being exact")))
  (testing "a field of a type"
    ;; NB: `MaskPosition` states "One of “forehead”, “eyes”, “mouth”, or
    ;;     “chin”" on its `point`, with no "must be" to introduce it.
    (let [mask-position (type-schema-of "MaskPosition")]
      (is (nil? (s/check mask-position
                         {:point "chin" :x_shift 0.0 :y_shift 0.0 :scale 1.0}))
          "The last of the stated members must pass")
      (is (rejected-by? mask-position
                        {:point "nose" :x_shift 0.0 :y_shift 0.0 :scale 1.0}
                        'string-constraints)
          "A member that is not stated must fail")))
  (testing "a length constraint standing beside the members"
    ;; NB: Both have to hold, the constraints of a category being a conjunction,
    ;;     which the members of an `enum` satisfy anyway.
    (let [sticker-format (type-schema-of "InputSticker")]
      (is (nil? (s/check sticker-format
                         {:sticker    "sticker-file-id"
                          :format     "video"
                          :emoji_list ["\uD83D\uDE00"]}))
          "A stated member must pass")
      (is (rejected-by? sticker-format
                        {:sticker    "sticker-file-id"
                         :format     "webm"
                         :emoji_list ["\uD83D\uDE00"]}
                        'string-constraints)
          "A member that is not stated must fail"))))

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
