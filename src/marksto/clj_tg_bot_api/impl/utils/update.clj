(ns marksto.clj-tg-bot-api.impl.utils.update
  "Telegram Bot API 'Update' type data accessors and utils"
  (:require [flatland.ordered.set :refer [ordered-set]]

            [marksto.clj-tg-bot-api.impl.api.spec :as api-spec]
            [marksto.clj-tg-bot-api.impl.utils.types :as types]))

(def all-update-types
  (->> (:update-types (api-spec/get-tg-bot-api-spec))
       (map :name)
       (apply ordered-set)))

(def message-update-types
  (->> (:update-types (api-spec/get-tg-bot-api-spec))
       (filter :message?)
       (map :name)
       (apply ordered-set)))

(def edited-update-types
  (->> (:update-types (api-spec/get-tg-bot-api-spec))
       (filter :edited?)
       (map :name)
       (apply ordered-set)))

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
    (or (-> update upd-type :chat)
        ;; NB: Mainly for `callback_query`.
        (-> update upd-type :message :chat))))

(defn get-update-message
  [update]
  (let [upd-type (get-update-type update)]
    (if (contains? message-update-types upd-type)
      (get update upd-type)
      ;; NB: Mainly for `callback_query`.
      (-> update upd-type :message))))

(defn get-update-commands
  [update]
  (when-some [message (get-update-message update)]
    (some->> (types/get-bot-commands message)
             (mapv #(types/->bot-command-name message %)))))

(defn get-update-chat-type
  [update]
  (or (:type (get-update-chat update))
      (-> update :inline_query :chat_type)))

(defn is-update-for-edited?
  [update]
  (contains? edited-update-types (get-update-type update)))
