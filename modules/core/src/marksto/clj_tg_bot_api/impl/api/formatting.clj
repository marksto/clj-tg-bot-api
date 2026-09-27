;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.impl.api.formatting
  "See https://core.telegram.org/bots/api#formatting-options for details."
  {:author "Mark Sto (@marksto)"}
  (:require
   [clojure.string :as str]))

(defmulti strip-entities
  "Removes Telegram Bot API message formatting markup from the given `text`
   according to the syntax rules of the specified `parse-mode`.

   Gives `nil` for a `parse-mode` that is not supported (yet), meaning that
   the plain text is unknown — never the raw `text`, since its length is an
   upper bound of the plain text length, and checking a limit against it
   would reject a text that is in fact within that limit.

   NB: Every message length that the Bot API states 'after entities parsing'
       is measured against what the server is left with once it has stripped
       the markup, so checking such a length takes doing the very same thing
       here."
  {:arglists '([parse-mode text])}
  (fn [parse-mode _text] parse-mode))

(defmethod strip-entities :default [_ _] nil)

;;; HTML style

;; NB: A fixed set of tags is supported, and every `<`, `>` and `&` that
;;     is not a part of a tag or an entity must be encoded — everywhere,
;;     `code` and `pre` blocks included. That leaves a tag an unambiguous
;;     `<...>` run, and the text is simply whatever the tags contain.
(def html-tag-re #"<[^>]*>")

(def html-entity-re #"&(?:#(\d+)|#[xX]([0-9a-fA-F]+)|([a-zA-Z]+));")

;; NB: All the numeric entities are supported, but only these named ones.
(def html-entity->char
  {"lt"   "<"
   "gt"   ">"
   "amp"  "&"
   "quot" "\""})

(defn- ->code-point-char
  [digits radix]
  (when-some [code-point (try (Long/parseLong digits radix)
                              (catch NumberFormatException _))]
    (when (<= 0 code-point Character/MAX_CODE_POINT)
      (String. (Character/toChars (int code-point))))))

;; NB: An entity that does not decode is left as is. Such a text is invalid
;;     anyway, so the server rejects it no matter what length we measure.
(defn- ->entity-char
  [[entity decimal hex named]]
  (or (some-> decimal (->code-point-char 10))
      (some-> hex (->code-point-char 16))
      (html-entity->char named)
      entity))

(defmethod strip-entities "HTML" [_ text]
  ;; NB: The tags go first, so that an encoded `&lt;b&gt;` stays a plain text.
  (-> text
      (str/replace html-tag-re "")
      (str/replace html-entity-re ->entity-char)))

;;; Markdown style

;; TODO: Strip the legacy `Markdown` markup as well.

;;; MarkdownV2 style

;; TODO: Strip the `MarkdownV2` markup as well.
