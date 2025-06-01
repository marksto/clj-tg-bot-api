(ns marksto.clj-tg-bot-api.impl.utils.types
  "Aux checks and utilities for the Telegram Bot API types"
  (:require [clojure.string :as str]))

;;; Date/Time

(defn ms->tg-dt
  (^long []
   (ms->tg-dt (System/currentTimeMillis)))
  (^long [^long dt-ms]
   (quot dt-ms 1000)))

(defn tg-dt->ms
  ^long [tg-dt]
  (unchecked-multiply tg-dt 1000))

;;; User

(defn is-bot?
  [{is-bot :is_bot :as _user}]
  is-bot)

;;; Chat Type

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

;;; Message

(defn is-reply-to?
  [{original-msg :reply_to_message :as _message} msg-id]
  (and (some? msg-id)
       (some? original-msg)
       (= msg-id (:message_id original-msg))))

(defn message-text-includes?
  [{text :text :as _message} substr]
  (and (seq substr)
       (some? text)
       (str/includes? text substr)))

;;; Message Entity

(defn get-bot-commands
  [{:keys [entities] :as _message}]
  (when entities (filter #(= "bot_command" (:type %)) entities)))

(defn ->bot-command-name
  [message bot-command]
  (-> (:text message)
      (subs (inc (:offset bot-command)) ; drop '/'
            (+ (:offset bot-command) (:length bot-command)))
      (str/split #"@")
      (first)))

;;; Chat Member

(def active-chat-member-statuses
  #{"member" "administrator" "creator" "restricted"})

(def inactive-chat-member-statuses
  #{"left" "kicked"})

(defn has-joined?
  [{{old-status :status} :old_chat_member
    {new-status :status} :new_chat_member
    :as                  _chat-member-updated}]
  (and (contains? inactive-chat-member-statuses old-status)
       (contains? active-chat-member-statuses new-status)))

(defn has-left?
  [{{old-status :status} :old_chat_member
    {new-status :status} :new_chat_member
    :as                  _chat-member-updated}]
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

;;; Bot Commands

(def command-text-re #"[a-z0-9_]{1,32}")

(def global-command:start "start")
(def global-command:help "help")
(def global-command:settings "settings")

(def bot-command-scope-default
  {:type :default})

(def bot-command-scope-types:in-private-chat
  [:chat
   :all_private_chats
   :default])

(def bot-command-scope-types:in-group-chats
  [:chat_member
   :chat_administrators
   :chat
   :all_chat_administrators
   :all_group_chats
   :default])

(def bot-command-scope-types:all
  (set (concat bot-command-scope-types:in-private-chat
               bot-command-scope-types:in-group-chats)))
