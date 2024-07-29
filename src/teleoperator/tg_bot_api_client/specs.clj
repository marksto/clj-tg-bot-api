(ns teleoperator.tg-bot-api-client.specs
  (:require [clojure.spec.alpha :as s]
            [teleoperator.tg-bot-api-client.utils :as tg-utils]
            [swa.platform.utils.interface.lang :as u-lang]))

;; Data Types

(s/def ::date nat-int?)

;; TODO: Have to be "A two-letter ISO 639-1 language code".
(s/def ::language-code string?)

(s/def ::username string?)

;; TODO: Have to be "A two-letter ISO 3166-1 alpha-2 country code".
(s/def ::country-code string?)

;; TODO: Have to be "A three-letter ISO 4217 currency code".
(s/def ::currency-code string?)

;; Chat ID

(s/def ::chat-id int?)

(s/def ::user-chat-id pos-int?)

(s/def ::group-chat-id neg-int?)

;; User

(s/def ::user-id ::user-chat-id)

(s/def :teleoperator.tg-bot-api-client.specs.user/id ::user-id)
(s/def :teleoperator.tg-bot-api-client.specs.user/first_name string?)
(s/def :teleoperator.tg-bot-api-client.specs.user/last_name string?)
(s/def :teleoperator.tg-bot-api-client.specs.user/username ::username)
(s/def :teleoperator.tg-bot-api-client.specs.user/is_bot boolean?)
(s/def :teleoperator.tg-bot-api-client.specs.user/language_code ::language-code)

(s/def ::user
  (s/keys :req-un [:teleoperator.tg-bot-api-client.specs.user/id
                   :teleoperator.tg-bot-api-client.specs.user/first_name]
          :opt-un [:teleoperator.tg-bot-api-client.specs.user/last_name
                   :teleoperator.tg-bot-api-client.specs.user/username
                   :teleoperator.tg-bot-api-client.specs.user/is_bot
                   :teleoperator.tg-bot-api-client.specs.user/language_code]))

;; Chat

(s/def :teleoperator.tg-bot-api-client.specs.chat/id ::chat-id)
(s/def :teleoperator.tg-bot-api-client.specs.chat/type tg-utils/raw-chat-types)
(s/def :teleoperator.tg-bot-api-client.specs.chat/first_name string?)
(s/def :teleoperator.tg-bot-api-client.specs.chat/last_name string?)
(s/def :teleoperator.tg-bot-api-client.specs.chat/username ::username)
(s/def :teleoperator.tg-bot-api-client.specs.chat/title string?)
(s/def :teleoperator.tg-bot-api-client.specs.chat/all_members_are_administrators boolean?)

(s/def ::chat
  (s/and (s/keys :req-un [:teleoperator.tg-bot-api-client.specs.chat/id
                          :teleoperator.tg-bot-api-client.specs.chat/type])
         (s/or ::user ; a temporary (and wrong) solution to avoid specs duplication
               (s/keys :opt-un [:teleoperator.tg-bot-api-client.specs.chat/title
                                :teleoperator.tg-bot-api-client.specs.chat/all_members_are_administrators]))))

;; Message ID

(s/def ::message-id nat-int?)

;; Bot Command

(s/def :teleoperator.tg-bot-api-client.specs.bot-command/command string?)
(s/def :teleoperator.tg-bot-api-client.specs.bot-command/description string?)

(s/def ::bot-command
  (s/keys :req-un [:teleoperator.tg-bot-api-client.specs.bot-command/command
                   :teleoperator.tg-bot-api-client.specs.bot-command/description]))

;; Update

(s/def ::update_id pos-int?)

;; Update Types

(def qualified-update-types
  (map #(u-lang/->keyword :teleoperator.tg-bot-api-client.specs %)
       tg-utils/update-types))

;; TODO: Implement specs for other Telegram Bot API update types, if necessary.
;;       Try to use generic schemas for both this Clojure Spec and the Telegram
;;       Bot API client lib, e.g. Malli, spec-provider + `webhook-requests/edn`.
;;       Reuse whatever Specs that are already available in other OSS projects.

::message
::callback_query
::my_chat_member
::chat_member
::message_reaction
::message_reaction_count
::chat_join_request
::chat_boost
::removed_chat_boost

;; Update > Message

(s/def :teleoperator.tg-bot-api-client.specs.message/message_id ::message-id)
(s/def :teleoperator.tg-bot-api-client.specs.message/from map?)
(s/def :teleoperator.tg-bot-api-client.specs.message/chat ::chat)
(s/def :teleoperator.tg-bot-api-client.specs.message/date ::date)
(s/def :teleoperator.tg-bot-api-client.specs.message/group_chat_created boolean?)

(s/def ::message
  (s/keys :req-un [:teleoperator.tg-bot-api-client.specs.message/message_id
                   :teleoperator.tg-bot-api-client.specs.message/chat
                   :teleoperator.tg-bot-api-client.specs.message/date]
          :opt-un [:teleoperator.tg-bot-api-client.specs.message/from
                   :teleoperator.tg-bot-api-client.specs.message/group_chat_created]))

;;

(s/def ::update
  (s/keys :req-un [::update_id
                   `(or ~@qualified-update-types)]))

;;

;; Here, define Clojure Specs for those objects that are passed to the
;; Telegram Bot API as method parameters, e.g. 'MessageEntity', to use
;; these Specs in the preconditions of the corresponding functions.
;; See the "METHOD PARAMS BUILDERS" section of the `utils` namespace.

;; TODO: Implement specs for other Telegram Bot API types, if necessary — MessageEntity.
