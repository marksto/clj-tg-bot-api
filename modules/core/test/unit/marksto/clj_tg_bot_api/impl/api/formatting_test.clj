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

(deftest strip-entities:markdown-test
  (let [is= (partial stripped-as-is= "Markdown")]
    (testing "every supported syntax"
      (is= "*bold text*" "bold text")
      (is= "_italic text_" "italic text")
      (is= "[inline URL](http://www.example.com/)" "inline URL")
      (is= "[inline mention](tg://user?id=123456789)" "inline mention")
      (is= "`inline fixed-width code`" "inline fixed-width code")
      (is= "```\npre-formatted block\n```" "pre-formatted block")
      (is= "```python\npy block\n```" "py block"))
    (testing "escaping, as in the docs examples"
      (is= "_snake_\\__case_" "snake_case")
      (is= "*2*\\**2=4*" "2*2=4")
      (is= "\\`not code\\`" "`not code`")
      (is= "\\[not a link](x)" "[not a link](x)"))
    (testing "only `_`, `*`, `` ` `` and `[` are escapable, so the rest stays as is"
      (is= "\\]\\~\\." "\\]\\~\\."))
    (testing "no other entity kind exists here, so its markup stays a plain text"
      (is= "~strikethrough~ ||spoiler|| >quote" "~strikethrough~ ||spoiler|| >quote"))
    (testing "a marker inside an entity stays a plain text"
      (is= "`*not bold*`" "*not bold*")
      (is= "```\n*not bold*\n```" "*not bold*"))))

(deftest strip-entities:markdown-v2-test
  (let [is= (partial stripped-as-is= "MarkdownV2")]
    (testing "every supported syntax"
      (is= "*bold \\*text*" "bold *text")
      (is= "_italic \\*text_" "italic *text")
      (is= "__underline__" "underline")
      (is= "~strikethrough~" "strikethrough")
      (is= "||spoiler||" "spoiler")
      (is= "[inline URL](http://www.example.com/)" "inline URL")
      (is= "[inline mention](tg://user?id=123456789)" "inline mention")
      (is= "![👍](tg://emoji?id=5368324170671202286)" "👍")
      (is= "`inline fixed-width code`" "inline fixed-width code")
      (is= "```\npre-formatted block\n```" "pre-formatted block")
      (is= "```python\npy block\n```" "py block"))
    (testing "every date-time entity format"
      (doseq [dt-fmt ["&format=wDT" "&format=t" "&format=r" ""]]
        (is= (str "![22:45 tomorrow](tg://time?unix=1647531900" dt-fmt ")")
             "22:45 tomorrow")))
    (testing "nesting, as in the docs example"
      (is= (str "*bold _italic bold ~italic bold strikethrough "
                "||italic bold strikethrough spoiler||~ "
                "__underline italic bold___ bold*")
           (str "bold italic bold italic bold strikethrough "
                "italic bold strikethrough spoiler "
                "underline italic bold bold")))
    (testing "block quotations"
      (is= ">Quote started\n>Quote continued\n>The last line"
           "Quote started\nQuote continued\nThe last line")
      (is= "**>Expandable started\n>The last line||"
           "Expandable started\nThe last line"))
    (testing "the `__` ambiguity, as resolved in the docs"
      (is= "___italic underline_**__" "italic underline")
      (is= "**" ""))
    (testing "any character with code 1 to 126 is escapable anywhere"
      (is= "\\_\\*\\[\\]\\(\\)\\~\\`\\>\\#\\+\\-\\=\\|\\{\\}\\.\\!"
           "_*[]()~`>#+-=|{}.!")
      (is= "a \\\\ b" "a \\ b"))
    (testing "inside `pre` and `code` a backtick and a backslash are escaped"
      (is= "`a \\` b`" "a ` b")
      (is= "```\na \\\\ b\n```" "a \\ b"))
    (testing "inside the URL part a closing paren and a backslash are escaped"
      (is= "[x](http://e.com/a\\)b)" "x"))
    (testing "an unpaired marker fails the parse, leaving the plain text unknown"
      (doseq [text ["*bold" "_italic" "~strike" "`code" "[x" "a ] b" "(x)" "!x"]]
        (is= text nil)))
    ;; NB: Allowing an empty entity is what lets the docs' `**` separator parse,
    ;;     and it also makes a doubled marker a whole entity of its own. Such a
    ;;     text is invalid for the server either way, so measuring it is as good
    ;;     as leaving its length unknown.
    (testing "a doubled unpaired marker parses as an empty entity instead"
      (is= "__underline" "underline")
      (is= "||spoiler" "spoiler"))))

(deftest strip-entities:unknown-parse-mode-test
  (testing "an unsupported parse-mode strips to nothing known"
    (doseq [parse-mode [nil "markdown" "html" "MarkdownV3" "Whatever"]]
      (stripped-as-is= parse-mode "*bold*" nil))))

;;

(comment
  (clojure.test/run-tests)
  :end/comment)
