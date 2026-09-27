(ns marksto.clj-tg-bot-api.impl.api.formatting-test
  "Every example below is taken from the \"Formatting options\" docs section:
   https://core.telegram.org/bots/api#formatting-options"
  (:require
   [clojure.test :refer [deftest is testing]]
   [marksto.clj-tg-bot-api.impl.api.formatting :as sut]))

(defn- stripped-as-is=
  [parse-mode text expected]
  (is (= expected (sut/strip-entities parse-mode text))
      (format "%s: %s" parse-mode (pr-str text))))

(deftest strip-entities:html-test
  (let [is= (partial stripped-as-is= "HTML")]
    (testing "every supported tag"
      (is= "<b>bold</b>, <strong>bold</strong>" "bold, bold")
      (is= "<i>italic</i>, <em>italic</em>" "italic, italic")
      (is= "<u>underline</u>, <ins>underline</ins>" "underline, underline")
      (is= "<s>s</s>, <strike>s</strike>, <del>s</del>" "s, s, s")
      (is= "<span class=\"tg-spoiler\">spoiler</span>" "spoiler")
      (is= "<tg-spoiler>spoiler</tg-spoiler>" "spoiler")
      (is= "<a href=\"http://www.example.com/\">inline URL</a>" "inline URL")
      (is= "<a href=\"tg://user?id=123456789\">inline mention</a>" "inline mention")
      (is= "<tg-emoji emoji-id=\"5368324170671202286\">👍</tg-emoji>" "👍")
      (is= "<tg-time unix=\"1647531900\" format=\"wDT\">22:45 tomorrow</tg-time>"
           "22:45 tomorrow")
      (is= "<code>inline fixed-width code</code>" "inline fixed-width code")
      (is= "<pre>pre-formatted block</pre>" "pre-formatted block")
      (is= "<pre><code class=\"language-python\">py block</code></pre>" "py block")
      (is= "<blockquote>quoted</blockquote>" "quoted")
      (is= "<blockquote expandable>quoted</blockquote>" "quoted"))
    (testing "nesting, as in the docs example"
      (is= (str "<b>bold <i>italic bold <s>italic bold strikethrough "
                "<span class=\"tg-spoiler\">italic bold strikethrough spoiler</span></s> "
                "<u>underline italic bold</u></i> bold</b>")
           (str "bold italic bold italic bold strikethrough "
                "italic bold strikethrough spoiler "
                "underline italic bold bold")))
    (testing "the only supported named entities"
      (is= "a &lt; b &gt; c &amp; d &quot;e&quot;" "a < b > c & d \"e\""))
    (testing "all the numerical entities"
      (is= "&#128512; &#x1F600; &#65;" "😀 😀 A"))
    (testing "an entity that is not a tag stays a plain text"
      (is= "&lt;b&gt;not a tag&lt;/b&gt;" "<b>not a tag</b>"))
    (testing "an entity that does not decode is left as is"
      (is= "&nosuch; &#99999999999;" "&nosuch; &#99999999999;"))))

(deftest strip-entities:unknown-parse-mode-test
  (testing "an unsupported parse-mode strips to nothing known"
    (doseq [parse-mode [nil "markdown" "html" "MarkdownV3" "Whatever"]]
      (stripped-as-is= parse-mode "*bold*" nil))))

;;

(comment
  (clojure.test/run-tests)
  :end/comment)
