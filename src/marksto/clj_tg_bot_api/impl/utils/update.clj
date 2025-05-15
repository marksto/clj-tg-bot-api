(ns marksto.clj-tg-bot-api.impl.utils.update
  "Telegram Bot API 'Update' type data accessors and utils"
  (:require [flatland.ordered.set :refer [ordered-set]]

            [marksto.clj-tg-bot-api.impl.utils.types :as types]))

(def all-update-types
  (ordered-set
    :message
    :edited_message
    :channel_post
    :edited_channel_post
    :business_connection
    :business_message
    :edited_business_message
    :deleted_business_messages
    :message_reaction
    :message_reaction_count
    :inline_query
    :chosen_inline_result
    :callback_query
    :shipping_query
    :pre_checkout_query
    :purchased_paid_media
    :poll
    :poll_answer
    :my_chat_member
    :chat_member
    :chat_join_request
    :chat_boost
    :removed_chat_boost))

(def edited-update-types
  #{:edited_message
    :edited_channel_post
    :edited_business_message})

(let [all-type-names (map name all-update-types)
      default-type-names (map name (disj all-update-types
                                         :chat_member
                                         :message_reaction
                                         :message_reaction_count))]
  (defn build-allowed-update-types
    [mode {:keys [adding except] :as _opts}]
    (cond-> (case mode
              :all all-type-names
              :default default-type-names)
            (seq adding) (conj adding)
            (seq except) (disj except))))

(defn get-update-id
  [update]
  (:update_id update))

(defn get-update-type
  [update]
  ;; NB: At most one of the optional parameters can be present in any given
  ;;     update. The first one is a mandatory one, and it's the 'update_id'.
  (ffirst (dissoc update :update_id)))

(defn get-update-chat
  [update]
  (let [upd-type (get-update-type update)]
    (case upd-type
      (:message
        :edited_message
        :my_chat_member
        :chat_member
        :message_reaction
        :message_reaction_count
        :chat_join_request
        :chat_boost
        :removed_chat_boost) (-> update upd-type :chat)
      :callback_query (-> update :callback_query :message :chat)
      nil)))

(defn get-update-message
  [update]
  (let [upd-type (get-update-type update)]
    (case upd-type
      :message (:message update)
      :edited_message (:edited_message update)
      :callback_query (-> update :callback_query :message)
      nil)))

(defn get-update-commands
  [update]
  (when-some [message ((some-fn :message :edited_message) update)]
    (some->> (types/get-bot-commands message)
             (mapv #(types/->bot-command-name message %)))))

(defn get-update-chat-type
  [update]
  (or (:type (get-update-chat update))
      (-> update :inline_query :chat_type)))

(defn is-update-for-edited?
  [update]
  (contains? edited-update-types (get-update-type update)))
