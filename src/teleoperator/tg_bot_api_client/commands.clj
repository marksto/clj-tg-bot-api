(ns teleoperator.tg-bot-api-client.commands
  (:require [camel-snake-kebab.core :as csk]
            [jsonista.core :as json]
            [telegrambot-lib.core :as tg-bot-api]

            [teleoperator.handlers.commands :as h-commands]
            [teleoperator.tg-bot-api-client.requests :as tg-requests]
            [teleoperator.tg-bot-api-client.utils :as tg-utils]

            [swa.platform.utils.interface.coll :as u-coll]
            [swa.platform.utils.interface.log :as u-log]))

(defn- log-successful-request
  [language-code bot-command-scope _response]
  (u-log/info! "Set bot commands for %s and scope '%s'"
               (if language-code
                 (str "'" language-code "' language")
                 "other languages")
               bot-command-scope))

(def ^:private default-mapper (json/object-mapper))

(defn- build-req-content
  "Builds a request content map for the \"setMyCommands\" Bot API method.

   See: https://core.telegram.org/bots/api#setmycommands"
  ([bot-command-scope language-code]
   (build-req-content nil bot-command-scope language-code))
  ([bot-commands bot-command-scope language-code]
   (let [commands-json (some-> bot-commands
                               (json/write-value-as-string default-mapper))
         scope-json (some-> bot-command-scope
                            (update-keys csk/->snake_case_string)
                            (update-vals #(if (keyword? %) (name %) %))
                            (json/write-value-as-string default-mapper))]
     (cond-> {}
             commands-json (assoc :commands commands-json)
             scope-json (assoc :scope scope-json)
             language-code (assoc :language_code (name language-code))))))

(defn set-bot-commands!
  ([bot-commands-config bot-commands language-code]
   (set-bot-commands! bot-commands-config bot-commands nil language-code))
  ([{:keys [make-request!] :as _bot-commands-config}
    bot-commands bot-command-scope language-code]
   (let [bot-command-scope' (or bot-command-scope
                                tg-utils/bot-command-scope-default)]
     (when-some [req-content (build-req-content
                               bot-commands bot-command-scope' language-code)]
       (u-log/debug! "The 'setMyCommands' request content: %s" req-content)
       (make-request! #(tg-bot-api/set-my-commands % req-content)
                      {:on-success #(log-successful-request
                                      language-code bot-command-scope' %)
                       :on-failure tg-requests/log-failure-reason
                       :on-error   :ignore})))))

(defn delete-bot-commands!
  ([bot-commands-config]
   (delete-bot-commands! bot-commands-config nil nil))
  ([bot-commands-config bot-command-scope]
   (delete-bot-commands! bot-commands-config bot-command-scope nil))
  ([{:keys [make-request!] :as _bot-commands-config}
    bot-command-scope language-code]
   (when-some [req-content (build-req-content bot-command-scope language-code)]
     (u-log/debug! "The 'deleteMyCommands' request content: %s" req-content)
     (make-request! #(tg-bot-api/delete-my-commands % req-content)
                    {:on-failure tg-requests/log-failure-reason
                     :on-error   :ignore}))))

;;

(def chat-type->bot-command-scope-types
  {:chat-type/private tg-utils/bot-command-scope-types:in-private-chat
   :chat-type/group   tg-utils/bot-command-scope-types:in-group-chats})

(defn- take-bot-command-scope-types-from
  [bot-command-scope-types from-scope-type]
  (let [idx (u-coll/index-of from-scope-type bot-command-scope-types)]
    (if (= -1 idx)
      bot-command-scope-types
      (subvec bot-command-scope-types idx))))

(defn get-all-suitable-scope-types
  "Returns all suitable bot command scope types for the given `chat-type`
   and `bot-command-scope-type`, following the algorithm for determining
   the list of commands for a particular user viewing the bot menu.

   See: https://core.telegram.org/bots/api#determining-list-of-commands"
  [chat-type bot-command-scope-type]
  (take-bot-command-scope-types-from
    (get chat-type->bot-command-scope-types chat-type [:default])
    bot-command-scope-type))

(defn pick-valid-bot-commands
  "For the given `chat-type`, `command-handlers`, `bot-command-scope` and
   `language-code` picks only valid bot commands, i.e. those that conform
   to the `BotCommand` object spec.

   Uses a provided `default-language-code` (or English if it is `nil`) as
   a fallback for getting localized bot command description

   Returns valid bot commands as a vector of Bot API `BotCommand` objects.

   See: https://core.telegram.org/bots/api#botcommand

   NB: If there's nothing in the `description` of a bot command, it won't
       appear on the list of commands in the bot menu. It will still work,
       thus we call such a command \"invisible\"."
  [{:keys [chat-type command-handlers bot-command-scope language-code]}
   default-language-code]
  (let [suitable-scope-types (set (get-all-suitable-scope-types
                                    chat-type (:type bot-command-scope)))]
    (->> command-handlers
         (filter (fn [{{:keys [scope]} :handler/condition :as handler-desc}]
                   (and (h-commands/valid-command-text? handler-desc)
                        (contains? suitable-scope-types (:type scope)))))
         (map (fn [{{:keys [command]} :handler/condition :as handler-desc}]
                (when-some [description (h-commands/get-localized-description
                                          handler-desc
                                          language-code
                                          default-language-code)]
                  {:command     command
                   :description description})))
         (u-coll/removev nil?))))

;;

(defn- get-all-language-codes-for-scope
  "NB: From the Telegram Bot API documentation: 'If <language_code is> empty,
       commands will be applied to all users from the given scope, for whose
       language there are no dedicated commands'."
  [command-handlers]
  (-> (h-commands/collect-language-codes command-handlers)
      (vec)
      (conj nil)))

(defn- update-bot-commands-within-scope!
  [{:keys [default-language-code] :as bot-commands-config}
   {:keys [command-handlers bot-command-scope] :as update-params}]
  (delete-bot-commands! bot-commands-config bot-command-scope)
  (doseq [language-code (get-all-language-codes-for-scope command-handlers)
          :let [bot-commands (-> update-params
                                 (assoc :language-code language-code)
                                 (pick-valid-bot-commands default-language-code))]]
    (delete-bot-commands! bot-commands-config nil language-code)
    (delete-bot-commands! bot-commands-config bot-command-scope language-code)
    (when (seq bot-commands)
      (set-bot-commands! bot-commands-config
                         bot-commands bot-command-scope language-code))))

(defn update-bot-commands!
  "For a given `bot-commands-config`, updates the bot commands, so that they
   are always in sync with what the bot can actually handle.

   It starts by deleting all previously set bot commands and then resets all
   valid bot commands found in the `update-handler-tree`.

   NOTE:
   Can be disabled by explicitly configuring a bot w/ `:update-bot-commands?`
   being `false`. As a rule, this is necessary when one wants to specify the
   bot commands yourself or while testing the bot logic."
  [{:keys [update-bot-commands? update-handler-tree]
    :or   {update-bot-commands? true}
    :as   bot-commands-config}]
  (when update-bot-commands?
    (delete-bot-commands! bot-commands-config)
    (doseq [chat-type h-commands/supported-chat-types
            :let [command-handlers (h-commands/collect-command-handlers
                                     update-handler-tree chat-type)]
            bot-command-scope (h-commands/collect-scopes command-handlers)
            :let [update-params {:chat-type         chat-type
                                 :command-handlers  command-handlers
                                 :bot-command-scope bot-command-scope}]]
      (update-bot-commands-within-scope! bot-commands-config update-params))))

;;

(defn check-global-commands
  "Checks if an 'update-handler-tree' in a given `bot-commands-config` follows
   the Telegram Bot API conventions for Global Commands.

   Logs a warning message for each missing basic command, if any, then returns
   the `bot-commands-config`.

   See: https://core.telegram.org/bots/features#global-commands"
  [{:keys [update-handler-tree] :as bot-commands-config}]
  (let [command-handlers (h-commands/collect-command-handlers
                           update-handler-tree :chat-type/private)]
    (when-not (some h-commands/handles-start-command? command-handlers)
      (u-log/warn! "Bot lacks the \"start\" command in private chats!"))
    (when-not (some h-commands/handles-help-command? command-handlers)
      (u-log/warn! "Bot lacks the \"help\" command in private chats!")))
  ;; NB: For group chats there is, supposedly, no "start" command.
  (let [command-handlers (h-commands/collect-command-handlers
                           update-handler-tree :chat-type/group)]
    (when-not (some h-commands/handles-help-command? command-handlers)
      (u-log/warn! "Bot lacks the \"help\" command in group chats!")))
  bot-commands-config)

;;

(defn process-bot-commands!
  "Inspects a given `bot-commands-config` and processes command handlers in its
   'update-handler-tree'.

   Checks if it follows the Telegram Bot API conventions on \"Global Commands\".
   Updates the bot commands via the Bot API, if the bot is configured to do so."
  {:arglists '([{:keys [update-bot-commands?
                        update-handler-tree
                        default-language-code
                        make-request!]
                 :as   bot-commands-config}])}
  [bot-commands-config]
  (-> bot-commands-config
      (check-global-commands)
      (update-bot-commands!)))
