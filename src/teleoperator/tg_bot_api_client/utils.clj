(ns teleoperator.tg-bot-api-client.utils
  "Aux checks, utilities & method params builders for the Telegram Bot API"
  (:require [clojure.string :as str]

            [teleoperator.utils.md-v2 :as utils-md-v2]

            [swa.platform.utils.interface.str :as u-str]))

;; AUXILIARY CHECKS & UTILS

(defn get-datetime-in-tg-format
  (^long []
   (get-datetime-in-tg-format (System/currentTimeMillis)))
  (^long [^long datetime-ms]
   (quot datetime-ms 1000)))

(defn datetime-in-tg-format->ms ^long [tg-dt]
  (unchecked-multiply tg-dt 1000))

; User

(def mention-types #{:by-name :by-full-name :by-username})

(defn get-user-mention-text
  "Handy fn for mentioning users in a message text.
   NB: Must be used with the MarkdownV2 parse mode."
  ([user]
   ;; NB: This is the default option since the User always have a 'first_name' and an 'id'.
   (get-user-mention-text user :by-name))
  ([{user-id :id first-name :first_name ?last-name :last_name ?username :username :as user}
    mention-type]
   {:pre [(contains? mention-types mention-type)]}
   (utils-md-v2/escape
     (case mention-type
       :by-name (str "[" first-name "](tg://user?id=" user-id ")")
       :by-full-name (if (some? ?last-name)
                       (str "[" first-name " " ?last-name "](tg://user?id=" user-id ")")
                       (get-user-mention-text user))
       :by-username (if (some? ?username)
                      (str "@" ?username)
                      (get-user-mention-text user))))))

; Chat Type

(def raw-chat-types
  #{"private"
    "sender" ; same as "private" in inline queries
    "group"
    "supergroup"
    "channel"})

(defn is-private?
  [chat-type]
  ;; NB: The "sender" case covers inline queries.
  (contains? #{"private" "sender"} chat-type))

(defn is-group?
  [chat-type]
  (= "group" chat-type))

(defn is-supergroup?
  [chat-type]
  (= "supergroup" chat-type))

(defn is-channel?
  [chat-type]
  (= "channel" chat-type))

; Message

(defn is-reply-to?
  [{{original-msg-id :message_id :as original-msg} :reply_to_message
    :as                                            _message}
   msg-id]
  (and (some? msg-id)
       (some? original-msg)
       (= msg-id original-msg-id)))

(defn message-text-includes?
  [{text :text :as _message} substr]
  (and (seq substr)
       (some? text)
       (str/includes? text substr)))

; Chat Member

(defn is-bot?
  [{{is-bot :is_bot} :user :as _chat_member}]
  is-bot)

(def active-chat-member-statuses
  #{"member" "administrator" "creator" "restricted"})

(def inactive-chat-member-statuses
  #{"left" "kicked"})

(defn has-joined?
  [{{old-status :status :as _old-chat-member} :old_chat_member
    {new-status :status :as _new-chat-member} :new_chat_member
    :as                                       _chat-member-updated}]
  (and (contains? inactive-chat-member-statuses old-status)
       (contains? active-chat-member-statuses new-status)))

(defn has-left?
  [{{old-status :status :as _old-chat-member} :old_chat_member
    {new-status :status :as _new-chat-member} :new_chat_member
    :as                                       _chat-member-updated}]
  (and (contains? active-chat-member-statuses old-status)
       (contains? inactive-chat-member-statuses new-status)))

(def administrator-chat-member-statuses
  #{"administrator" "creator"})

(defn is-administrator?
  [{status :status :as _chat_member}]
  (contains? administrator-chat-member-statuses status))

(defn is-promoted-administrator?
  [{old-chat-member :old_chat_member
    new-chat-member :new_chat_member
    :as             _chat-member-updated}]
  (and (not (is-administrator? old-chat-member))
       (is-administrator? new-chat-member)))

(defn is-demoted-administrator?
  [{old-chat-member :old_chat_member
    new-chat-member :new_chat_member
    :as             _chat-member-updated}]
  (and (is-administrator? old-chat-member)
       (not (is-administrator? new-chat-member))))

; Bot Commands

(def command-text-re
  "Text of a command has to be 1-32 characters long and can only contain
   lowercase English letters, digits and underscores."
  #"[a-z0-9_]{1,32}")

(def global-command:start
  "The Global Telegram bot \"start\" command.
   See https://core.telegram.org/bots/features#global-commands"
  "start")

(def global-command:help
  "The Global Telegram bot \"help\" command.
   See https://core.telegram.org/bots/features#global-commands"
  "help")

(def global-command:settings
  "The Global Telegram bot \"settings\" command (if applicable).
   See https://core.telegram.org/bots/features#global-commands"
  "settings")

(def bot-command-scope-default
  "Represents the default scope of bot commands.
   Default commands are used if no commands with a narrower scope
   are specified for the user.
   See https://core.telegram.org/bots/api#botcommandscopedefault"
  {:type :default})

(def bot-command-scope-types:in-private-chat
  "Types of Bot Command Scopes in the (private) chat with the bot.
   See https://core.telegram.org/bots/features#command-scopes"
  [:chat
   :all_private_chats
   :default])

(def bot-command-scope-types:in-group-chats
  "Types of Bot Command Scopes in group and supergroup chats.
   See https://core.telegram.org/bots/features#command-scopes"
  [:chat_member
   :chat_administrators
   :chat
   :all_chat_administrators
   :all_group_chats
   :default])

(def bot-command-scope-types:all
  "All types of Bot Command Scopes.
   See https://core.telegram.org/bots/features#command-scopes"
  (set (concat bot-command-scope-types:in-private-chat
               bot-command-scope-types:in-group-chats)))


;; UPDATE DATA ACCESSORS

(def update-types
  "A set of supported update types."
  #{:message
    :callback_query
    :my_chat_member
    :chat_member
    :message_reaction
    :message_reaction_count
    :chat_join_request
    :chat_boost
    :removed_chat_boost})

(defn get-update-id
  "Returns the ID of a given `update`."
  [update]
  (:update_id update))

(defn get-update-type
  "Determines the `update` type, i.e. its optional parameter key, as a keyword.
   At most *one* of the optional parameters can be present in any given update."
  [update]
  ;; NB: At most one of the optional parameters can be present in any given
  ;;     update. The first one is a mandatory one, and it's the 'update_id'.
  (ffirst (dissoc update :update_id)))

(defn get-update-chat
  "For an `update` returns its 'chat', if available; otherwise returns `nil`."
  [update]
  (let [upd-type (get-update-type update)]
    (case upd-type
      (:message
        :my_chat_member
        :chat_member
        :message_reaction
        :message_reaction_count
        :chat_join_request
        :chat_boost
        :removed_chat_boost) (get-in update [upd-type :chat])
      :callback_query (get-in update [upd-type :message :chat])
      nil)))

(defn get-update-message
  "For an `update` returns either the message itself for a 'message' update or
   a related message for a 'callback query' update; otherwise returns `nil`.

   NB: May return either a full-fledged Message or an InaccessibleMessage that
       only contains 'chat', 'message_id', and 'date' (which is always `0`)."
  [update]
  (let [upd-type (get-update-type update)]
    (case upd-type
      :message (:message update)
      :callback_query (get-in update [upd-type :message])
      nil)))

(defn get-update-commands
  "Retrieves a list of bot commands, if any, from the `update`'s message text.
   If there are no message `:entities` with \"bot_command\" type, returns `nil`."
  [update]
  (letfn [(filter-cmds [entities]
            (filter #(= "bot_command" (:type %)) entities))
          (map-to-name [bot-cmds]
            (let [get-name (fn [txt cmd]
                             (-> txt
                                 (subs (inc (:offset cmd)) ; drop '/'
                                       (+ (:offset cmd) (:length cmd)))
                                 (str/split #"@")
                                 (first)))
                  txt (-> update :message :text)]
              (mapv #(get-name txt %) bot-cmds)))]
    (some-> update
            :message
            :entities
            filter-cmds
            map-to-name)))

(defn get-update-chat-type
  "For an `update` returns its 'chat_type', if available; otherwise `nil`.

   For anything that contains a Chat (see `get-update-chat`) returns its 'type',
   which can be either \"private\", \"group\", \"supergroup\" or \"channel\".

   For an inline query, a type of the chat from which the inline query was sent
   is optional and can be either \"sender\" for a private chat with the inline
   query sender, \"private\", \"group\", \"supergroup\", or \"channel\". It may
   be missing if the request was sent from a secret chat."
  [update]
  (or (:type (get-update-chat update))
      (get-in update [:inline_query :chat_type])))


;; BOT API RESPONSES

(defn valid-response?
  "Checks if `response` is a valid response from the Telegram Bot API.

   From the Telegram Bot API documentation:
   The response contains a JSON object, which always has a Boolean field 'ok'."
  [response]
  (and (map? response) (contains? response :ok)))

(defn successful-response?
  "Checks if a Telegram Bot API `response` is successful.

   From the Telegram Bot API documentation:
   If 'ok' equals true, the request was successful <...>."
  [response]
  (and (valid-response? response) (true? (:ok response))))

(defn get-response-result
  "For a `response` returns its 'result', if available; otherwise `nil`.

   From the Telegram Bot API documentation:
   If 'ok' equals true, the request was successful and the result of the query
   can be found in the 'result' field."
  [response]
  (when (successful-response? response)
    (:result response)))

(defn get-response-result-chat-id
  "For a `response-result` returns its chat ID, if available; otherwise `nil`."
  [response-result]
  (when response-result
    (or (get-in response-result [:chat :id])
        (get response-result :chat_id))))

(defn get-response-error-text
  "For a `response` returns a text representation of the error, if available;
   otherwise `nil`.

   From the Telegram Bot API documentation:
   The response contains a JSON object, which <...> may have an optional String
   field 'description' with a human-readable description of the result.
   In case of an unsuccessful request, 'ok' equals false and the error is
   explained in the 'description'.
   An Integer 'error_code' field is also returned, but its contents are subject
   to change in the future."
  [response]
  (when-not (successful-response? response)
    (->> (select-keys response [:description :error_code])
         (map (fn [[k v]] (str (name k) "=\"" v "\"")))
         (u-str/join* ", "))))

; ResponseParameters
;
; Contains information about why a request was unsuccessful.
;
; Field             	Type    	Description
; migrate_to_chat_id 	Integer 	Optional. The group has been migrated to
;                   	        	a supergroup with the specified identifier.
; retry_after       	Integer 	Optional. In case of exceeding flood control,
;                   	        	the number of seconds left to wait before
;                   	        	the request can be repeated.

;; TODO: Add an auto-retry strategy for Telegram Bot API response 'parameters' with 'retry_after'.
;; TODO: Add an auto-retry strategy for Telegram Bot API response 'parameters' with 'migrate_to_chat_id'.
(defn get-response-error-parameters
  "For a `response` returns its 'parameters', if available; otherwise `nil`.

   From the Telegram Bot API documentation:
   Some errors may also have an optional field 'parameters' of the type
   'ResponseParameters', which can help to automatically handle errors."
  [_response]
  nil)


;; IMMEDIATE RESPONSE

;; TODO: Add a mapping of `upd-type`s to `method-name`s:
;;       :message -> "sendMessage"
;;       :callback_query -> "answerCallbackQuery"
;;       ...
(defn build-immediate-response
  "From the Telegram Bot API docs: \"If you're using webhooks, you can perform
   a request to the Bot API while sending an answer to the webhook ... Specify
   the method to be invoked in the 'method' parameter of the request.\".

   An example of an immediate request that the Telegram Bot API can handle:
   ```
   {:method \"sendMessage\",
    :chat_id chat-id,
    :reply_to_message_id msg-id,
    :text \"An ordinary reply message text.\"}
   ```

   This technique helps to reduce the number of requests to the Bot API server
   and, as a consequence, reduces the response time of the client's UI."
  [method-name response-content]
  (merge {:method method-name} response-content))


;; METHOD PARAMS BUILDERS

;; TODO: Automatically convert all '_'s into '-'s in params map keys for all Telegram Bot API methods.
;;       For all functions representing the Telegram Bot API methods, add input params transformation.

;; TODO: Specify preconditions that check each passed argument for its type, optionality, and other constraints.
;;       According to the Telegram Bot API documentation (https://core.telegram.org/bots/api).

;; TODO: Re-impl method params builder fns atop of Malli Schema transformations.
;;       All of the following "METHODS"-related things can be reimplemented in
;;       the form of Malli Schemas and passed around as pure data (which avoids
;;       dealing w/ 2 forms of data representation and cumbersome fn calls).

; /sendMessage
;
; Use this method to send text messages. On success, the sent Message is returned.
; (Links tg://user?id=<user_id> can be used to mention a user by their ID without using a username.)
;
; Parameter                 	Type                  	Required	Description
; chat_id                   	Integer or String     	Yes     	Unique identifier for the target chat or username of
;                           	                      	        	the target channel (in the format @channelusername)
; message_thread_id           Integer                 Optional  Unique identifier for the target message thread (topic)
;                                                               of the forum; for forum supergroups only
; text                      	String                	Yes     	Text of the message to be sent,
;                           	                      	        	1-4096 characters after entities parsing
; parse_mode                	String                	Optional	Mode for parsing entities in the message text.
;                           	                      	        	See formatting options for more details.
; entities                  	Array of              	Optional	List of special entities that appear in message text,
;                           	MessageEntity         	        	which can be specified instead of parse_mode
; disable_web_page_preview  	Boolean               	Optional	Disables link previews for links in this message
; disable_notification      	Boolean               	Optional	Sends the message silently. Users will receive
;                           	                      	        	a notification with no sound.
; protect_content             Boolean                 Optional  Protects the contents of the sent message from
;                                                               forwarding and saving
; reply_to_message_id       	Integer               	Optional	If the message is a reply, ID of the original message
; allow_sending_without_reply	Boolean               	Optional	Pass True, if the message should be sent even if
;                           	                      	        	the specified replied-to message is not found
; reply_markup              	InlineKeyboardMarkup or	Optional	Additional interface options.
;                           	ReplyKeyboardMarkup  or	         	A JSON-serialized object for an inline keyboard,
;                           	ReplyKeyboardRemove  or	         	custom reply keyboard, instructions to remove
;                           	ForceReply             	         	reply keyboard or to force a reply from the user.
(defn build-message-options
  [{:keys [topic-id
           parse-mode
           no-link-preview
           send-silently
           protect-content
           original-msg-id
           reply-anyway
           reply-markup] :as _options}]
  (cond-> {}
          (some? topic-id) (assoc :message_thread_id topic-id)
          (some? parse-mode) (assoc :parse_mode parse-mode)
          (true? no-link-preview) (assoc :disable_web_page_preview true)
          (true? send-silently) (assoc :disable_notification true)
          (true? protect-content) (assoc :protect_content true)
          (some? original-msg-id) (assoc :reply_to_message_id original-msg-id)
          (true? reply-anyway) (assoc :allow_sending_without_reply true)
          (some? reply-markup) (assoc :reply_markup reply-markup)))

; ReplyKeyboardMarkup
;
; This object represents a custom keyboard with reply options (see Introduction to bots for details and examples).
;
; Field             	    Type              Description
; keyboard          	    Array of Array 	  Array of button rows, each represented by an Array of KeyboardButton objects
;                   	    of KeyboardButton
; is_persistent           Boolean           Optional. Requests clients to always show the keyboard when the regular
;                                           keyboard is hidden. Defaults to false, in which case the custom keyboard
;                                           can be hidden and opened with a keyboard icon.
; resize_keyboard   	    Boolean           Optional. Requests clients to resize the keyboard vertically for optimal fit
;                   	                      (e.g., make the keyboard smaller if there are just two rows of buttons).
;                   	                      Defaults to false, in which case the custom keyboard is always of the same
;                   	                      height as the app's standard keyboard.
; one_time_keyboard 	    Boolean           Optional. Requests clients to hide the keyboard as soon as it's been used.
;                   	                      The keyboard will still be available, but clients will automatically display
;                   	                      the usual letter-keyboard in the chat – the user can press a special button
;                   	                      in the input field to see the custom keyboard again. Defaults to false.
; input_field_placeholder String            Optional. The placeholder to be shown in the input field when the keyboard
;                                           is active; 1-64 characters
; selective         	    Boolean           Optional. Use this parameter to show the keyboard to specific users only.
;                   	                      Targets:
;                   	                      1) users that are @mentioned in the text of the Message object;
;                   	                      2) if the bot's message is a reply (has reply_to_message_id),
;                   	                         sender of the original message.
(def default-reply-keyboard
  {:keyboard        []
   :resize_keyboard true})

(defn build-reply-keyboard
  ([]
   default-reply-keyboard)
  ([button-rows]
   (update default-reply-keyboard :keyboard into button-rows))
  ([button-rows {:keys [persistent resize one-time placeholder selective] :as _options}]
   (cond-> (build-reply-keyboard button-rows)
           (true? persistent) (assoc :is_persistent true)
           (true? resize) (assoc :resize_keyboard true)
           (true? one-time) (assoc :one_time_keyboard true)
           (and (some? placeholder)
                (< 1 (count placeholder))) (assoc :input_field_placeholder (u-str/truncate placeholder 64))
           (true? selective) (assoc :selective true))))

; KeyboardButton
;
; This object represents one button of the reply keyboard.
; For simple text buttons String can be used instead of this object to specify text of the button.

; ReplyKeyboardRemove
;
; Upon receiving a message with this object, Telegram clients will remove the current custom keyboard and display
; the default letter-keyboard. By default, custom keyboards are displayed until a new keyboard is sent by a bot.
; An exception is made for one-time keyboards that are hidden immediately after the user presses a button
; (see ReplyKeyboardMarkup).
;
; Field           	Type    	Description
; remove_keyboard 	True    	Requests clients to remove the custom keyboard (user will not be able to summon
;                 	        	this keyboard; if you want to hide the keyboard from sight but keep it accessible,
;                 	        	use one_time_keyboard in ReplyKeyboardMarkup)
; selective       	Boolean 	Optional. Use this parameter to remove the keyboard for specific users only.
;                 	        	Targets:
;                 	        	1) users that are @mentioned in the text of the Message object;
;                 	        	2) if the bot's message is a reply (has reply_to_message_id),
;                 	        	   sender of the original message.
(def default-remove-keyboard
  {:remove_keyboard true})

(defn build-remove-keyboard
  ([]
   default-remove-keyboard)
  ([{:keys [selective] :as _options}]
   (cond-> default-remove-keyboard
           (true? selective) (assoc :selective true))))

; InlineKeyboardMarkup
;
; This object represents an inline keyboard that appears right next to the message it belongs to.
;
; Field           	Type                	Description
; inline_keyboard 	Array of Array of   	Array of button rows, each represented by an Array of
;                 	InlineKeyboardButton	InlineKeyboardButton objects
(def default-inline-keyboard
  {:inline_keyboard []})

(defn build-inline-keyboard
  ([]
   default-inline-keyboard)
  ([button-rows]
   (update default-inline-keyboard :inline_keyboard into button-rows)))

; InlineKeyboardButton
;
; This object represents one button of an inline keyboard. You must use exactly one of the optional fields.
;
; Field                           	Type        	Description
; text                            	String      	Label text on the button
; url                             	String      	Optional. HTTP or tg:// url to be opened when button is pressed
; login_url                       	LoginUrl    	Optional. An HTTP URL used to automatically authorize the user.
;                                 	            	Can be used as a replacement for the Telegram Login Widget.
; callback_data                   	String      	Optional. Data to be sent in a callback query to the bot
;                                 	            	when button is pressed, 1-64 bytes
; switch_inline_query             	String      	Optional. If set, pressing the button will prompt the user to select
;                                 	            	one of their chats, open that chat and insert the bot's username and
;                                 	            	the specified inline query in the input field. Can be empty, in which
;                                 	            	case just the bot's username will be inserted.
;                                 	            	NOTE: This offers an easy way for users to start using your bot
;                                 	            	in inline mode when they are currently in a private chat with it.
;                                 	            	Especially useful when combined with 'switch_pm…' actions –
;                                 	            	in this case the user will be automatically returned to the chat
;                                 	            	they switched from, skipping the chat selection screen.
; switch_inline_query_current_chat	String      	Optional. If set, pressing the button will insert the bot's username
;                                 	            	and the specified inline query in the current chat's input field.
;                                 	            	Can be empty, in which case only the bot's username will be inserted.
;                                 	            	This offers a quick way for the user to open your bot in inline mode
;                                 	            	in the same chat – good for selecting something from multiple options.
; callback_game                   	CallbackGame	Optional. Description of the game that will be launched when the user
;                                 	            	presses the button.
;                                 	            	NOTE: This type of button must be the 1st button in the first row.
; pay                             	Boolean     	Optional. Specify True, to send a Pay button.
;                                 	            	NOTE: This type of button must be the 1st button in the first row.
(def inline-kbd-btn-types
  #{:url :login_url :callback_data :switch_inline_query :switch_inline_query_current_chat :callback_game :pay})

(defn build-inline-kbd-btn
  [text type-specific-key type-specific-val]
  {:pre [(keyword? type-specific-key)
         (string? type-specific-val)]}
  (let [field (-> type-specific-key
                  name
                  (str/replace "-" "_")
                  keyword)]
    (assert (contains? inline-kbd-btn-types field))
    {:text text
     field type-specific-val}))

; ForceReply
;
; Upon receiving a message with this object, Telegram clients will display a reply interface to the user
; (act as if the user has selected the bot's message and tapped 'Reply'). This can be extremely useful
; if you want to create user-friendly step-by-step interfaces without having to sacrifice privacy mode.
;
; Field         	          Type    	Description
; force_reply   	          True    	Shows reply interface to the user, as if they manually selected
;               	        	          the bot's message and tapped 'Reply'
; input_field_placeholder 	String 	  Optional. The placeholder to be shown in the input field when
;                                     the reply is active; 1-64 characters
; selective     	          Boolean 	Optional. Use this parameter to force reply from specific users only.
;               	                  	Targets:
;               	                  	1) users that are @mentioned in the text of the Message object;
;               	                  	2) if the bot's message is a reply (has reply_to_message_id),
;               	                  	   sender of the original message.
(def default-force-reply
  {:force_reply true})

(defn build-force-reply
  ([]
   default-force-reply)
  ([{:keys [placeholder selective] :as _options}]
   (cond-> default-force-reply
           (and (some? placeholder)
                (< 1 (count placeholder))) (assoc :input_field_placeholder (u-str/truncate placeholder 64))
           (true? selective) (assoc :selective true))))

; Reply Markups
(def reply-markup-builders-by-type
  {:custom-keyboard build-reply-keyboard
   :remove-keyboard build-remove-keyboard
   :inline-keyboard build-inline-keyboard
   :force-reply     build-force-reply})

(defn build-reply-markup
  [type & args]
  (apply (get reply-markup-builders-by-type type) args))


(comment
  (build-message-options
    {:reply-markup (build-reply-markup :inline-keyboard
                                       [[(build-inline-kbd-btn "Callback" :callback-data "<some_data>")]])})
  (build-message-options
    {:reply-markup (build-reply-markup :custom-keyboard [["Add expense"]])})

  (build-message-options {:reply-markup (build-reply-markup :remove-keyboard {:selective true})})

  (build-message-options {:reply-markup (build-reply-markup :force-reply {:selective true})}))


; /sendChatAction
;
; Use this method when you need to tell the user that something is happening on the bot's side. The status is set
; for 5 seconds or less (when a message arrives from your bot, Telegram clients clear its typing status). Returns
; `True` on success.
;
; We only recommend using this method when a response from the bot will take a noticeable amount of time to arrive.
;
; Parameter 	Type            	Required	Description
; chat_id   	Integer or String	Yes     	Unique identifier for the target chat or username of the target channel
;           	                	        	(in the format @channelusername)
; action    	String          	Yes     	Type of action to broadcast. Choose one, depending on what the user is
;           	                	        	about to receive:
;           	                	        	- `typing` for text messages,
;           	                	        	- `upload_photo` for photos,
;           	                	        	- `record_video` or `upload_video` for videos,
;           	                	        	- `record_voice` or `upload_voice` for voice notes,
;           	                	        	- `upload_document` for general files,
;           	                	        	- `find_location` for location data,
;           	                	        	- `record_video_note` or `upload_video_note` for video notes.

;; TODO: Implement a specification for the "sendChatAction" method.
