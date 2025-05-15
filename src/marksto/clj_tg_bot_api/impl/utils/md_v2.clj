(ns marksto.clj-tg-bot-api.impl.utils.md-v2
  "A minor part of the MarkdownV2 functionality that is absolutely necessary."
  (:require [clojure.string :as str]

            [marksto.clj-tg-bot-api.impl.utils :as utils]))

;; escaping

(def ^:private inline-url-re
  #"\[(?<text>[^]]+)\]\((?<href>[^\s]+)\)")

(defn- escape-text
  "Escapes plain text (no pre, code or inline strings)."
  [text-str]
  (str/replace text-str #"[_*\[\]()~`>#+\-=|{}.!]" #(str "\\" %)))

;; TODO: Implement escaping for the inline code strings and pre-formatted code blocks in 'escape-code'.
#_{:clj-kondo/ignore [:unused-private-var]}
(defn- escape-code
  "Escapes the inline code strings and pre-formatted code blocks."
  [code-str]
  (str/replace code-str #"[`\\]" #(str "\\" %)))

(defn- escape-inline-url
  "Escapes the \"[inline URL](http://www.example.com/)\" strings."
  [inline-url-str]
  (let [{:keys [text href]} (utils/re-match-get-groups
                              inline-url-re
                              inline-url-str
                              [:text :href])
        escaped-url (str/replace href #"[)\\]" #(str "\\" %))]
    (str "[" (escape-text text) "]" "(" escaped-url ")")))

(defn escape
  [text-str]
  (if (str/blank? text-str)
    text-str
    (let [text-fragments (str/split text-str inline-url-re)
          inline-urls (map first (utils/re-find-all inline-url-re text-str))]
      (utils/join*
        (utils/interleave* (map escape-text text-fragments)
                           (map escape-inline-url inline-urls))))))

;; formatting

(defn format-bold
  ([text-str]
   (format-bold text-str true))
  ([text-str escape?]
   (str "*" (if escape? (escape text-str) text-str) "*")))

(defn format-italic
  ([text-str]
   (format-italic text-str true))
  ([text-str escape?]
   (str "_" (if escape? (escape text-str) text-str) "_")))

(defn format-underline
  ([text-str]
   (format-underline text-str true))
  ([text-str escape?]
   (str "__" (if escape? (escape text-str) text-str) "__")))

(defn format-strikethrough
  ([text-str]
   (format-strikethrough text-str true))
  ([text-str escape?]
   (str "~" (if escape? (escape text-str) text-str) "~")))
