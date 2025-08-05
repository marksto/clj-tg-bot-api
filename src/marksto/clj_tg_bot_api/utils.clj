(ns marksto.clj-tg-bot-api.utils
  "Some utilities for Telegram Bot API types, updates and responses"
  (:require [marksto.clj-tg-bot-api.impl.utils.types :as types]
            [marksto.clj-tg-bot-api.impl.utils.update :as update]
            [marksto.clj-tg-bot-api.impl.utils.response :as response]))

;;;; TYPES

;;; Date/Time

(defn ms->tg-dt
  "Gets the current date-time in or converts a given `dt-ms` (date-time millis)
   to the Telegram Date/Time format."
  (^long []
   (types/ms->tg-dt))
  (^long [^long dt-ms]
   (types/ms->tg-dt dt-ms)))

(defn tg-dt->ms
  "Coerces a given `tg-dt` date-time (in Telegram Date/Time format) to millis."
  ^long [tg-dt]
  (types/tg-dt->ms tg-dt))

;;; User

(defn is-bot?
  "Checks if a given `user` is a bot."
  [user]
  (types/is-bot? user))

;;; Chat Type

(def raw-chat-types
  types/raw-chat-types)

(defn is-private?
  [chat-type]
  (types/is-private? chat-type))

(defn is-group?
  [chat-type]
  (types/is-group? chat-type))

(defn is-supergroup?
  [chat-type]
  (types/is-supergroup? chat-type))

(defn is-channel?
  [chat-type]
  (types/is-channel? chat-type))

;;; Message

(defn is-reply-to?
  {:arglists '([{original-msg :reply_to_message :as message} msg-id])}
  [message msg-id]
  (types/is-reply-to? message msg-id))

(defn message-text-includes?
  [message substr]
  (types/message-text-includes? message substr))

;;; Message Entity

(defn get-bot-commands
  [message]
  (types/get-bot-commands message))

(defn ->bot-command-name
  [message bot-command]
  (types/->bot-command-name message bot-command))

;;; Chat Member

(def active-chat-member-statuses
  types/active-chat-member-statuses)

(def inactive-chat-member-statuses
  types/inactive-chat-member-statuses)

(defn has-joined?
  [chat-member-updated]
  (types/has-joined? chat-member-updated))

(defn has-left?
  [chat-member-updated]
  (types/has-left? chat-member-updated))

(defn is-administrator?
  [chat-member]
  (types/is-administrator? chat-member))

(defn is-promoted-administrator?
  [chat-member-updated]
  (types/is-promoted-administrator? chat-member-updated))

(defn is-demoted-administrator?
  [chat-member-updated]
  (types/is-demoted-administrator? chat-member-updated))

;;; Bot Commands

(def command-text-re
  "Text of a command has to be 1-32 characters long and can only contain
   lowercase English letters, digits and underscores."
  types/command-text-re)

(def global-command:start
  "The Global Telegram bot \"start\" command.
   See https://core.telegram.org/bots/features#global-commands"
  types/global-command:start)

(def global-command:help
  "The Global Telegram bot \"help\" command.
   See https://core.telegram.org/bots/features#global-commands"
  types/global-command:help)

(def global-command:settings
  "The Global Telegram bot \"settings\" command (if applicable).
   See https://core.telegram.org/bots/features#global-commands"
  types/global-command:settings)

(def bot-command-scope-default
  "Represents the default scope of bot commands.
   Default commands are used if no commands with a narrower scope
   are specified for the user.
   See https://core.telegram.org/bots/api#botcommandscopedefault"
  types/bot-command-scope-default)

(def bot-command-scope-types:in-private-chat
  "Types of Bot Command Scopes in the (private) chat with the bot.
   See https://core.telegram.org/bots/features#command-scopes"
  types/bot-command-scope-types:in-private-chat)

(def bot-command-scope-types:in-group-chats
  "Types of Bot Command Scopes in group and supergroup chats.
   See https://core.telegram.org/bots/features#command-scopes"
  types/bot-command-scope-types:in-group-chats)

(def bot-command-scope-types:all
  "All types of Bot Command Scopes.
   See https://core.telegram.org/bots/features#command-scopes"
  types/bot-command-scope-types:all)

;;;; UPDATES

;;; Update Types

(def all-update-types
  "A set of all update types in the latest supported Telegram Bot API version."
  update/all-update-types)

(def message-update-types
  "A set of update types holding a Message, e.g. messages, channel posts, etc."
  update/message-update-types)

(def edited-update-types
  "A set of update types that represent smth. that has been edited."
  update/edited-update-types)

(defn build-allowed-update-types
  "Builds a list of update types for passing as the `allowed_updates` param's
   value when calling 'getUpdates' & 'setWebhook' Telegram Bot API methods.

   When called with `:all` as a first arg, returns a list of all update types
   in the latest supported Telegram Bot API version.

   When called with `:default` as a first arg, returns a list of update types
   allowed by default. Please, note that there is no need in using this fn in
   case the Telegram Bot API defaults already work for your bot's use case.

   The point of using this fn is to include necessary and exclude unnecessary
   allowed update types by passing along `:adding []` or `:except []` kwargs,
   respectively.

   From the Telegram Bot API documentation:
   Specify an empty list to receive all update types except \"chat_member\",
   \"message_reaction\", and \"message_reaction_count\" (default)."
  {:arglists '([:all     & {:keys [adding except] :as opts}]
               [:default & {:keys [adding except] :as opts}])}
  [mode & {:as opts}]
  (update/build-allowed-update-types mode opts))

;;; Update Data Accessors

(defn get-update-id
  "Returns the ID of a given `update`."
  [update]
  (update/get-update-id update))

(defn get-update-type
  "Determines the `update` type, i.e. its optional parameter key, as a keyword.
   At most *one* of the optional parameters can be present in any given update."
  [update]
  (update/get-update-type update))

(defn get-update-chat
  "For an `update` returns its 'chat', if available; otherwise returns `nil`."
  [update]
  (update/get-update-chat update))

(defn get-update-message
  "For an `update` returns either a message for a 'message' or 'edited message'
   update or a linked message for a 'callback query' update; otherwise returns
   `nil`.

   NB: May return either a full-fledged Message or an InaccessibleMessage that
       only contains 'chat', 'message_id', and 'date' (which is always `0`)."
  [update]
  (update/get-update-message update))

(defn get-update-commands
  "Retrieves a list of bot commands, if any, from the `update`'s message text.
   If there are no message `:entities` with \"bot_command\" type, returns `nil`."
  [update]
  (update/get-update-commands update))

(defn get-update-chat-type
  "For an `update` returns its 'chat_type', if available; otherwise `nil`.

   For anything that contains a Chat (see `get-update-chat`) returns its 'type',
   which can be either \"private\", \"group\", \"supergroup\" or \"channel\".

   For an inline query, a type of the chat from which the inline query was sent
   is optional and can be either \"sender\" for a private chat with the inline
   query sender, \"private\", \"group\", \"supergroup\", or \"channel\". It may
   be missing if the request was sent from a secret chat."
  [update]
  (update/get-update-chat-type update))

(defn is-update-for-edited?
  "Checks if an `update` is for smth., e.g. a message, that has been edited."
  [update]
  (update/is-update-for-edited? update))

;;;; BOT API RESPONSES

(defn valid-response?
  "Checks if `response` is a valid response from the Telegram Bot API.

   From the Telegram Bot API documentation:
   The response contains a JSON object, which always has a Boolean field 'ok'."
  [response]
  (response/valid-response? response))

(defn successful-response?
  "Checks if a Telegram Bot API `response` is successful.

   From the Telegram Bot API documentation:
   If 'ok' equals true, the request was successful <...>."
  [response]
  (response/successful-response? response))

(defn get-response-result
  "For a `response` returns its 'result', if available; otherwise `nil`.

   From the Telegram Bot API documentation:
   If 'ok' equals true, the request was successful and the result of the query
   can be found in the 'result' field."
  [response]
  (response/get-response-result response))

(defn get-response-result-chat-id
  "For a `response-result` returns its chat ID, if available; otherwise `nil`."
  [response-result]
  (response/get-response-result-chat-id response-result))

(defn get-response-error
  "For a `response` returns its error map with 'error_code' and 'description',
   if available; otherwise `nil`.

   From the Telegram Bot API documentation:
   The response contains a JSON object, which <...> may have an optional String
   field 'description' with a human-readable description of the result.
   In case of an unsuccessful request, 'ok' equals false and the error is
   explained in the 'description'.
   An Integer 'error_code' field is also returned, but its contents are subject
   to change in the future."
  [response]
  (response/get-response-error response))

(defn get-response-parameters
  "For a `response` returns its 'parameters', if available; otherwise `nil`.

   From the Telegram Bot API documentation:
   Some errors may also have an optional field 'parameters' of the type
   'ResponseParameters', which can help to automatically handle errors."
  [response]
  (response/get-response-parameters response))

(defn get-chat-id-for-retry
  "Returns a Chat ID of a supergroup to which the group has been migrated for
   a subsequent retry request."
  [response]
  (response/get-chat-id-for-retry response))

(defn get-wait-ms-for-retry
  "Returns a number of millis left to wait before the request can be repeated
   in case of exceeding the Telegram Bot API flood control."
  [response]
  (response/get-wait-ms-for-retry response))

;;;; PARSE MODES

(def parse-mode:md
  "A handy constant for Markdown-style message text entities parsing mode.
   Pass as a `parse_mode` option to the `build-message-options` fn.

   See syntax details: https://core.telegram.org/bots/api#markdownv2-style"
  "MarkdownV2")

(def parse-mode:html
  "A handy constant for HTML-style message text entities parsing mode.
   Pass as a `parse_mode` option to the `build-message-options` fn.

   See supported tags: https://core.telegram.org/bots/api#html-style"
  "HTML")
