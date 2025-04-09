(ns teleoperator.markdown.interface
  "A minor part of the Markdown functionality that is absolutely necessary."
  (:require [teleoperator.markdown.md-v2 :as md-v2]))

(defn escape
  [text-str]
  (md-v2/escape text-str))

(defn format-bold
  ([text-str]
   (md-v2/format-bold text-str true))
  ([text-str escape?]
   (md-v2/format-bold text-str escape?)))

(defn format-italic
  ([text-str]
   (md-v2/format-italic text-str true))
  ([text-str escape?]
   (md-v2/format-italic text-str escape?)))

(defn format-underline
  ([text-str]
   (md-v2/format-underline text-str true))
  ([text-str escape?]
   (md-v2/format-underline text-str escape?)))

(defn format-strikethrough
  ([text-str]
   (md-v2/format-strikethrough text-str true))
  ([text-str escape?]
   (md-v2/format-strikethrough text-str escape?)))
