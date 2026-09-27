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
   [clojure.string :as str]
   [instaparse.core :as insta]))

(def lang-char-class
  "An alias of a supported language is made of letters, digits, `-`, `#`, `+`
   and `.`. Being over-permissive here is harmless anyway, since the language
   is dropped from the text either way.
   Source: https://github.com/TelegramMessenger/libprisma#supported-languages"
  "[A-Za-z0-9+#._-]")

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

;; NB: The entities cannot nest here, and only `_`, `*`, `` ` ``, `[` are
;;     escapable — and only outside an entity. That leaves every unescaped
;;     marker droppable on sight, with no pairing to keep track of, since
;;     an unbalanced one makes the text invalid for the server anyway.
(def md:pre-pattern (str "```(?:" lang-char-class "*\\n)?(.*?)\\n?```"))
(def md:code-pattern "`([^`]*)`")
(def md:link-pattern "\\[([^\\]]*)\\]\\([^)]*\\)")
(def md:escape-pattern "\\\\([_*`\\[])")
(def md:marker-pattern "[_*]")

;; NB: The order of the alternatives is what makes a marker literal inside
;;     a `pre` or a `code` entity, and it is also the order of the capture
;;     groups that `->markdown-text` goes on to destructure.
(def markdown-token-re
  (re-pattern (str "(?s)" md:pre-pattern
                   "|" md:code-pattern
                   "|" md:link-pattern
                   "|" md:escape-pattern
                   "|" md:marker-pattern)))

(defn- ->markdown-text
  [[_ pre-body code-body link-text escaped-char]]
  (or pre-body code-body link-text escaped-char #_marker ""))

(defmethod strip-entities "Markdown" [_ text]
  (str/replace text markdown-token-re ->markdown-text))

;;; MarkdownV2 style

;; NB: Entities do nest here, and every marker is escapable, so a grammar
;;     earns its keep over a scan. It depends on:
;;     - the choice is ordered (`/`), not free (`|`), since a free choice
;;       makes every marker ambiguous w/ the plain text containing it;
;;     - deliberately no catch-all rule, so that an unpaired marker fails
;;       the parse instead of being counted as a plain text character.
(def md2:grammar
  (str "
document       = node*
<node>         = pre / code / custom-emoji / link / quote-mark / spoiler
                 / underline / bold / italic / strike / expand-mark
                 / escaped / chars

pre            = <'```'> lang? <nl?> pre-body <nl?> <'```'>
<lang>         = <#'" lang-char-class "+'>
<nl>           = <#'\\n'>
pre-body       = pre-char*
code           = <'`'> code-body <'`'>
code-body      = code-char*
<pre-char>     = escaped-tick / #'[^`\\\\\\n]' / #'\\n(?!```)'
<code-char>    = escaped-tick / #'[^`\\\\\\n]'
<escaped-tick> = <'\\\\'> #'[`\\\\]'

quote-mark     = <#'(?:\\*\\*)?>'>
expand-mark    = <'||'>
underline      = <'__'> node* <'__'>
bold           = <'*'> node* <'*'>
italic         = <'_'> node* <'_'>
strike         = <'~'> node* <'~'>
spoiler        = <'||'> node* <'||'>

custom-emoji   = <'!['> node* <']('> url <')'>
link           = <'['> node* <']('> url <')'>
<url>          = <#'(?:\\\\.|[^)])*'>

escaped        = <'\\\\'> #'[\\u0001-\\u007E]'
chars          = #'[^*_~|`\\[\\]()\\\\!>]+'
"))

(def *md2:parser (delay (insta/parser md2:grammar)))

(defn- ->markdown-v2-text [ast]
  (->> (tree-seq vector? rest ast)
       (filter string?)
       (apply str)))

(defmethod strip-entities "MarkdownV2" [_ text]
  (let [ast (@*md2:parser text)]
    (when-not (insta/failure? ast)
      (->markdown-v2-text ast))))
