#!/usr/bin/env bb

;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

#_{:splint/disable [naming/single-segment-namespace]}
(ns update-spec
  "A Babashka script that fetches the Telegram Bot API documentation page
   and updates the Bot API spec JSON file if there are any changes."
  {:author "Mark Sto (@marksto)"}
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [hickory.core :as h]
   [hickory.render :as r]
   [hickory.select :as s]
   [inflections.core :as inf]))

;;; Utils

(def blank-string?
  #(and (string? %) (str/blank? %)))

(def uppercase-string?
  #(and (string? %) (Character/isUpperCase ^char (get % 0))))

(defn removev
  [pred coll]
  (filterv (complement pred) coll))

;; Credits to 'https://cninja.blogspot.com/2011/02/clojure-partition-at.html'
(defn partition-at
  [f coll]
  (lazy-seq
    (when-let [s (seq coll)]
      (let [run (cons (first s) (take-while #(not (f %)) (rest s)))]
        (cons run (partition-at f (drop (count run) s)))))))

;;; Files

(def etag-file (fs/file "modules/core/resources/.tg-bot-api-etag"))

(def spec-file (fs/file "modules/core/resources/tg-bot-api-spec.json"))

;;; Fetching

(def tg-bot-api-url "https://core.telegram.org/bots/api")

(def fetch-timeout 5000)
(def fetch-attempts 3)

(defn fetch-with-retries! [headers n-attempt]
  (or (try
        (if (pos? n-attempt)
          (http/get tg-bot-api-url {:headers         headers
                                    :throw           false
                                    :connect-timeout fetch-timeout
                                    :timeout         fetch-timeout})
          {:status -1})
        (catch Exception ex
          (log/errorf ex "Failed Bot API page fetching attempt")
          nil))
      (recur headers (dec n-attempt))))

(defn fetch-tg-bot-api-page! []
  (let [headers (when (fs/exists? etag-file)
                  {"If-None-Match" (str/trim (slurp etag-file))})
        {:keys [status] :as resp} (fetch-with-retries! headers fetch-attempts)]
    (cond
      (<= 200 status 299) resp
      (= 304 status) nil
      :else
      (throw (ex-info "Failed to fetch the Bot API page" {:status status})))))

;;;; Rendering

(defn render-html:node [node]
  (r/hickory-to-html node))

(defn render-html:nodes [nodes]
  (str/join (mapv render-html:node nodes)))

;;;; Parsing > Utils

(defn remove-blank-strings [tree]
  (walk/prewalk
    (fn [form]
      (if (and (map? form) (seq (:content form)))
        (update form :content #(removev blank-string? %))
        form))
    tree))

(defn assert-node-tag [tag node]
  (when (not= tag (:tag node))
    (throw (ex-info "Wrong type of node received" {:tag  tag
                                                   :node node}))))

(defn text ^String [node]
  (cond
    (string? node) node
    (:tag node) (str/join (map text (:content node)))
    :else ""))

(defn has-text?
  ([node txt]
   (= txt (text node)))
  ([node tag txt]
   (and (= tag (:tag node))
        (= txt (text node)))))

(def id-anchor-selector
  (s/and (s/tag :a)
         (s/attr :href #(str/starts-with? % "#"))))

(defn get-anchor-id [node]
  (subs (-> node :attrs :href) 1))

(defn get-first-anchor-id [node]
  (some-> (s/select (s/child id-anchor-selector) node)
          (first)
          (get-anchor-id)))

;;;; Parsing > Temp IDs

(defn ->temp-id [anchor-id]
  (keyword "tmp" anchor-id))

(defn build-temp-id->id [api-elements]
  (reduce (fn [acc {:keys [id name kind] :as _api-element}]
            (assoc acc id (subs (str kind "/" (csk/->kebab-case name)) 1)))
          {}
          api-elements))

#_{:splint/disable [naming/conversion-functions]}
(defn fallback-to-content-temp-id [form]
  (if (and (map? form) (:temp-id form))
    (if (and (keyword? (:temp-id form))
             (= "tmp" (namespace (:temp-id form))))
      (->temp-id (str/lower-case (:content form)))
      (:temp-id form))
    form))

;;;; Parsing > Data Types

(def number-type? #{"Integer" "Float"})

(def basic-type? #{"Boolean" "True" "String" "Integer" "Float"})

(def array-type-prefix? #{"Array"})

;; NB: An API type is always a link, so it is only the basic types and arrays
;;     that get named in plain words, while the rest either join them (as "of",
;;     "or" or "and") or merely describe them (e.g. "a String for plain text").
(def type-noise? (complement (some-fn basic-type? array-type-prefix?)))

(defn prepare-type-node
  [type-node]
  (cond (map? type-node) {:temp-id (->temp-id (get-anchor-id type-node))
                          :content (text type-node)}
        (string? type-node) (remove type-noise? (re-seq #"\w+" type-node))
        :else type-node))

(defn parse-data-type
  [{:keys [content] :as _node}]
  (let [type-seq (flatten (map prepare-type-node content))
        [arrays type-seq] (split-with array-type-prefix? type-seq)
        type (case (count type-seq)
               0 (throw (ex-info "Found no data type named" {:content content}))
               1 (first type-seq)
               [:or (vec type-seq)])]
    (reduce (fn [type _] (vector :array type)) type arrays)))

;;;; Parsing > Content Sections

(def first-section-of-interest?
  #(has-text? % :h3 "Getting updates"))

(defn ->sections [content-node]
  (->> content-node
       (drop-while (complement first-section-of-interest?))
       (partition-at (comp #{:h3} :tag))))

(defn notes-subsection? [[node & _rest]]
  (assert-node-tag :h4 node)
  (str/includes? (text node) " "))

(defn ->subsections [section-node]
  (->> section-node
       (drop-while #(not= :h4 (:tag %)))
       (partition-at (comp #{:h4} :tag))
       #_(map #(->> % (drop 1) (take 1)))))

;;;; Parsing > API Element > Basic Info

(defn best-guess-kind [^String name]
  (if (uppercase-string? name) :type :method))

(def kind-specific-tags #{:table :ul})

(def description-tags (complement kind-specific-tags))

(defn get-api-element-basics
  [[node & rest]]
  (assert-node-tag :h4 node)
  (let [id (->temp-id (get-first-anchor-id node))
        name (text node)
        kind (best-guess-kind name)
        [tags rest'] (split-with #(description-tags (:tag %)) rest)
        description (render-html:nodes tags)]
    [{:id          id
      :name        name
      :kind        kind
      :description description}
     rest']))

;;;; Parsing > API Element > Constraints

;; NB: Both sides of a mutual relation declare it, hence the dedup. Only the
;;     adjacent duplicates ever collapse, which is what the sort is for. The
;;     group itself arrives already ordered, since only the relation knows
;;     whether that order carries a meaning.
(defn ->element-constraints [attrs]
  (update-vals (->> attrs (mapcat :relations) (group-by key))
               #(->> % (map val) (sort) (dedupe) (vec))))

(defn lift-attrs-relations
  [element attrs-key]
  (let [attrs (get element attrs-key)
        attrs' (mapv #(dissoc % :relations) attrs)
        element-constraints (->element-constraints attrs)]
    (cond-> (assoc element attrs-key attrs')
      (seq element-constraints) (assoc :constraints element-constraints))))

;;;; Parsing > API Element > API Type

(defn col-name->attr-key
  [col-name]
  (case col-name
    ("Field" "Parameter") :name
    "Type" :type
    "Required" :required
    "Description" :description))

(def type-dependant-field-re
  #".+(?:(must be)|(always)) [\"“]?([a-z0-9_]+)[\"”]?$")

;; NB: We do not parse other constraints for strings, such as:
;;     - "with at most 2 line feeds" (no need to check for it)
;;     - "must end in `_by_<bot_username>`" (impossible to check)
;;     - "must be a superset|subset of" (a one-off complicated case)
(def desc-text-re
  {:in-span              #"(?i)(?:[;,] |between |from )(-?\d+(?:\s*\*\s*\d+)*)(?:\s*-\s*| and | to )(-?\d+(?:\s*\*\s*\d+)*)"
   :in-values            #"(?i)must (?:be one of |always be |be )([^.;]+)"
   :size-of-list         #"(?i)(?:list|array) of (?:up to )?(?:(\d+)-)?(\d+)\s"
   :size-in-range        #"(?i)(?:up to|include) (?:(\d+)-)?(\d+) items"
   :size-max-limit       #"(?i)at most (\d+) [^.]*?can be specified"
   :total-length         #"(?i)total length of (?:up to )?(?:(\d+)-)?(\d+) characters"
   :length               #"(?i)(?:(\d+)-)?(\d+) characters"
   :byte-length          #"(?i)(?:(\d+)-)?(\d+) bytes"
   :enum-literals        #"(?i)one of (.+)"
   :allowed-chars-listed #"(?i)only (?:characters )?([^.]+?) and (\S+) are allowed"
   :allowed-chars-named  #"(?i)can contain only ([^.]+)"
   :disallowed-chars     #"(?i)(\w+) are not allowed"
   :begins-with          #"(?i)must begin with an? (\w+)"
   :no-consecutive       #"(?i)can't contain consecutive (\w+)"
   :aep-modifier         #"(?i)after entit(?:y|ies) parsing"
   :mutual-exclusion     #"(?i)can't be used together with ([a-z0-9_]+)"
   :mutual-requirement   #"(?i)required if ([a-z0-9_]+) (?:is not|isn't) specified"
   :cond-requirement     #"(?i)required if ([a-z0-9_]+) is specified"})

(defn ->char-class [char-classes]
  ;; NB: In a character class the `^` negates only when leading,
  ;;     while the `-` is a literal only when trailing. Sorting.
  (str "[" (str/join (sort-by #(case % "^" 0, "-" 2, 1) char-classes)) "]"))

(defn ->char-classes [items item->char-class]
  (let [unparsable (removev item->char-class items)]
    (if (seq unparsable)
      (log/warnf "Unparsable char class %s in %s"
                 (pr-str unparsable) (pr-str items))
      (mapv item->char-class items))))

(defn ->string-pattern
  [{:keys [char-classes begins-with guards]}]
  (when (or char-classes begins-with guards)
    (let [chars (if (seq char-classes)
                  (->char-class char-classes)
                  "[\\s\\S]")]
      (str begins-with
           (if guards (str "(?:" chars guards ")") chars)
           "*"))))

(def meta-character? #{"\\" "]" "^" "[" "&" "-"})

(defn escape-char [ch]
  (cond->> ch (and (meta-character? ch) (not= "-" ch)) (str "\\")))

;; NB: Ranges are kept narrow, since nonsense endpoints would compile
;;     into an invalid range, while a single char is more permissive.
(def char-range-or-char-re #"(?:A-Z|a-z|0-9|.)")

(defn listed-char->char-class [item]
  (when (re-matches char-range-or-char-re item)
    (escape-char item)))

(defn parse-listed-chars [{:keys [head tail]}]
  (->char-classes (conj (str/split head #",\s*") tail)
                  listed-char->char-class))

(def prose->char-class
  {"lowercase english letters" "a-z"
   "uppercase english letters" "A-Z"
   "english letters"           "A-Za-z"
   "letters"                   "A-Za-z"
   "digits"                    "0-9"
   "underscores"               "_"
   ;; NB: This char class is Java-specific, but we don't care much.
   "emoji"                     "\\p{IsEmoji}&&[^0-9#*]"})

(inf/add-uncountable! "emoji")

(defn noun->char-class [noun]
  (prose->char-class (inf/plural (str/lower-case noun))))

(defn parse-named-chars [{:keys [prose]}]
  (->char-classes (mapv str/lower-case (str/split prose #",\s*|\s+and\s+"))
                  prose->char-class))

(defn parse-begins-with [{:keys [noun]}]
  (some-> (->char-classes [noun] noun->char-class)
          (->char-class)))

(defn parse-no-consecutive [{:keys [noun]}]
  (let [char-class (noun->char-class noun)]
    (if (= 1 (count char-class))
      (str "(?<!" char-class char-class ")")
      (log/warnf "Unparsable no-consecutive noun %s" (pr-str noun)))))

(defn parse-disallowed-chars [{:keys [noun]}]
  (some->> (->char-classes [noun] noun->char-class)
           (cons "^")))

(defn parse-range-constraint
  ([{:keys [from to]}]
   (parse-range-constraint from to))
  ([from to]
   (merge (when from
            {:from (parse-long from)})
          {:to (parse-long to)})))

(defn check-match [groups group-ks]
  (when (not= (count groups) (count group-ks))
    (throw (ex-info "Found groups do not match keys" {:groups   (vec groups)
                                                      :group-ks group-ks})))
  groups)

(defn re-find* [re s group-ks]
  (some-> (re-find re s)
          (next)
          (check-match group-ks)
          (->> (zipmap group-ks)
               (into {} (remove (comp nil? val)))
               (not-empty))))

(defn re-search [desc-text re-key group-ks]
  (re-find* (get desc-text-re re-key) desc-text group-ks))

(def lead-number-re
  #"(?i)^\s*(?:(?:and|or)\s+)?(0x[0-9A-Fa-f]+|-?\d+(?:\s*\*\s*\d+)*)\b")

;; NB: Only the number a term leads with counts, which is what tells the value
;;     in "1000 for 3 months" from the condition it is stated under.
(defn parse-numeric [term]
  (when-some [[_ number-str] (re-find lead-number-re term)]
    (if (str/starts-with? (str/lower-case number-str) "0x")
      (Long/parseLong (subs number-str 2) 16)
      (->> (re-seq #"-?\d+" number-str)
           (map parse-long)
           (reduce *)))))

(def parens-re #"\([^)]*\)")

;; NB: A parenthetical only ever restates the value next to it — "(30 days)",
;;     "(0x6FB9F0)" — so it goes before the clause is split into terms.
(defn parse-numerics [clause]
  (-> (str/replace clause parens-re "")
      (str/split #",|\bor\b")
      (->> (keep parse-numeric))))

(defn span-covers? [span value]
  (let [{:keys [from to]} span]
    (and (or (nil? from) (<= from value))
         (<= value to))))

(defn ->term-bound [term]
  (if (map? term) (or (:from term) (:to term)) term))

;; NB: We do not parse other constraints for numbers, such as:
;;     - "no more than 2628000 seconds in the future" (current time offset)
;;     - "Should be positive" (advisory, and contradicted by a default of 0)
(defn parse-number-constraints [desc-text]
  ;; NB: A description states the values an attr admits as terms — a span or
  ;;     an exact one — and the terms do compose. Joined by an "or" they are
  ;;     a union, which is precisely what the constraint checks.
  (let [spans (->> (re-seq (get desc-text-re :in-span) desc-text)
                   (map (fn [[_ from to]]
                          {:from (parse-numeric from)
                           :to   (parse-numeric to)}))
                   (distinct))]
    ;; NB: Several spans at once are joined by an "and", each conditioned on
    ;;     something invisible from here — a currency, a base emoji — since
    ;;     two unconditional spans for one value make no sense. That logical
    ;;     contradiction is what this veto rests on.
    (when (<= (count spans) 1)
      (let [span (first spans)
            values (->> (re-seq (get desc-text-re :in-values) desc-text)
                        (mapcat (comp parse-numerics second))
                        (remove #(and span (span-covers? span %)))
                        (distinct))
            terms (->> (cond-> (vec values) span (conj span))
                       (sort-by ->term-bound)
                       (vec))]
        (when (seq terms)
          {:in terms})))))

(def quoted-literal-re #"“([^”]*)”")

;; NB: The literals are taken from the cue to the very end, rather than to the
;;     end of a sentence, since a member may well carry a period of its own —
;;     "“static” for a .WEBP or .PNG image ...". Over-reaching is the safe way
;;     round here: an extra member only ever admits a value, never rejects it,
;;     whereas a truncated list would reject the members left out.
(defn parse-string-literals [desc-text]
  (when-some [[_ clause] (re-find (get desc-text-re :enum-literals) desc-text)]
    (let [literals (map second (re-seq quoted-literal-re clause))]
      ;; NB: An emoji member, tho, reaches us blank — the docs state it as an
      ;;     `<img>`, whose text content is nothing — so a blank one means we
      ;;     have failed to read the values and had better state none at all.
      (when (and (seq literals)
                 (not-any? str/blank? literals))
        (vec (distinct literals))))))

(defn parse-string-constraints [desc-text]
  (let [length (or (some-> (re-search desc-text :length [:from :to])
                           (parse-range-constraint))
                   (some-> (re-search desc-text :byte-length [:from :to])
                           (parse-range-constraint)
                           (assoc :unit "bytes")))
        char-classes (or (some-> (re-search desc-text :allowed-chars-listed [:head :tail])
                                 (parse-listed-chars))
                         (some-> (re-search desc-text :allowed-chars-named [:prose])
                                 (parse-named-chars))
                         (some-> (re-search desc-text :disallowed-chars [:noun])
                                 (parse-disallowed-chars)))
        begins-with (some-> (re-search desc-text :begins-with [:noun])
                            (parse-begins-with))
        guards (some-> (re-search desc-text :no-consecutive [:noun])
                       (parse-no-consecutive))
        pattern (->string-pattern {:char-classes char-classes
                                   :begins-with  begins-with
                                   :guards       guards})
        literals (parse-string-literals desc-text)
        aep-mod? (re-find (get desc-text-re :aep-modifier) desc-text)]
    (cond-> nil
      length (assoc :length length)
      pattern (assoc :pattern pattern)
      literals (assoc :enum literals)
      aep-mod? (assoc :after_entities_parsing true))))

(defn array-type? [type]
  (and (vector? type) (= :array (first type))))

(defn parse-array-constraints [type desc-text]
  (let [element-type (second type)
        size (or (some-> (re-search desc-text :size-of-list [:from :to])
                         (parse-range-constraint))
                 (some-> (re-search desc-text :size-in-range [:from :to])
                         (parse-range-constraint))
                 (some-> (re-search desc-text :size-max-limit [:to])
                         (parse-range-constraint)))
        total-length (when (= "String" element-type)
                       (some-> (re-search desc-text :total-length [:from :to])
                               (parse-range-constraint)))]
    (cond-> nil
      size (assoc :size size)
      total-length (assoc :total_length total-length))))

(defn parse-mutual-exclusion [attr-name desc-text]
  (when-some [{:keys [other-attr-name]}
              (re-search desc-text :mutual-exclusion [:other-attr-name])]
    {:at_most_one (vec (sort [attr-name (keyword other-attr-name)]))}))

(defn parse-mutual-requirement [attr-name desc-text]
  (when-some [{:keys [other-attr-name]}
              (re-search desc-text :mutual-requirement [:other-attr-name])]
    {:at_least_one (vec (sort [attr-name (keyword other-attr-name)]))}))

(defn parse-cond-requirement [attr-name desc-text]
  (when-some [{:keys [other-attr-name]}
              (re-search desc-text :cond-requirement [:other-attr-name])]
    {:required_with [attr-name (keyword other-attr-name)]}))

(defn parse-attr-relations [attr-name desc-text]
  (merge (parse-mutual-exclusion attr-name desc-text)
         (parse-mutual-requirement attr-name desc-text)
         (parse-cond-requirement attr-name desc-text)))

(defn prepare-api-type-field
  [{:keys [description] :as field}]
  (let [optional? (-> (s/select s/first-child description)
                      (first)
                      (text)
                      (str/starts-with? "Optional"))
        field-name (-> field :name :content first keyword)
        field-type (parse-data-type (:type field))
        desc-text (text description)
        tdf-value (last (re-find type-dependant-field-re desc-text))
        json-ser? (str/includes? desc-text "JSON-serialized")
        num-const (when (number-type? field-type)
                    (parse-number-constraints desc-text))
        str-const (when (= "String" field-type)
                    (parse-string-constraints desc-text))
        arr-const (when (array-type? field-type)
                    (parse-array-constraints field-type desc-text))
        attr-rels (parse-attr-relations field-name desc-text)]
    (cond-> (-> field
                (assoc :name field-name)
                (assoc :type field-type)
                (assoc :required (not optional?))
                (update :description (comp render-html:nodes :content)))
            tdf-value (assoc :value tdf-value)
            json-ser? (assoc :json_serialized json-ser?)
            num-const (assoc-in [:constraints :number] num-const)
            str-const (assoc-in [:constraints :string] str-const)
            arr-const (assoc-in [:constraints :array] arr-const)
            attr-rels (assoc :relations attr-rels))))

(defn get-api-type-field
  [col-names single-row-nodes]
  (prepare-api-type-field
    (reduce (fn [field-acc [idx col-name]]
              (let [attr (col-name->attr-key col-name)
                    val-node (nth single-row-nodes idx)]
                (assoc field-acc attr val-node)))
            {}
            col-names)))

(defn get-api-type
  [col-names row-nodes]
  (-> (fn [type-acc single-row-nodes]
        (let [field (get-api-type-field col-names single-row-nodes)]
          (update type-acc :fields conj field)))
      (reduce {:kind   :type
               :fields []}
              row-nodes)
      (lift-attrs-relations :fields)))

;;;; Parsing > API Element > API Method

(defn prepare-api-method-param
  [{:keys [description] :as param}]
  (let [param-name (-> param :name :content first keyword)
        param-type (parse-data-type (:type param))
        desc-text (text description)
        json-ser? (str/includes? desc-text "JSON-serialized")
        num-const (when (number-type? param-type)
                    (parse-number-constraints desc-text))
        str-const (when (= "String" param-type)
                    (parse-string-constraints desc-text))
        arr-const (when (array-type? param-type)
                    (parse-array-constraints param-type desc-text))
        attr-rels (parse-attr-relations param-name desc-text)]
    (cond-> (-> param
                (assoc :name param-name)
                (assoc :type param-type)
                (update :required #(has-text? % "Yes"))
                (update :description (comp render-html:nodes :content)))
            json-ser? (assoc :json_serialized json-ser?)
            num-const (assoc-in [:constraints :number] num-const)
            str-const (assoc-in [:constraints :string] str-const)
            arr-const (assoc-in [:constraints :array] arr-const)
            attr-rels (assoc :relations attr-rels))))

(defn get-api-method-param
  [col-names single-row-nodes]
  (prepare-api-method-param
    (reduce (fn [param-acc [idx col-name]]
              (let [attr (col-name->attr-key col-name)
                    val-node (nth single-row-nodes idx)]
                (assoc param-acc attr val-node)))
            {}
            col-names)))

(defn get-api-method
  [col-names row-nodes]
  (-> (fn [method-acc single-row-nodes]
        (let [param (get-api-method-param col-names single-row-nodes)]
          (update method-acc :params conj param)))
      (reduce {:kind   :method
               :params []
               #_#_:returns nil} ; no need for the return type in Clojure
              row-nodes)
      (lift-attrs-relations :params)))

;;;; Parsing > API Element > Table

(defn parse-api-element-table
  [table-node]
  (assert-node-tag :table table-node)
  (let [col-names (->> table-node
                       (s/select (s/child (s/tag :thead)
                                          (s/tag :tr)
                                          (s/tag :th)))
                       (map text)
                       (zipmap (range)))
        row-nodes (->> table-node
                       (s/select (s/child (s/tag :tbody)
                                          (s/tag :tr)))
                       (map #(s/select (s/child (s/tag :td)) %)))]
    (case (get col-names 0)
      "Field" (get-api-type col-names row-nodes)
      "Parameter" (get-api-method col-names row-nodes))))

;;;; Parsing > API Element > List

;; NB: A union may have members that are no API types, which its description
;;     states in prose alone, e.g. "it can be either a String for plain text,
;;     an Array of RichText, or any of the following types".
(def union-prose-members-re
  #"(?i)can be either (.+?),? or any of the following types")

(defn parse-union-prose-members [description]
  (if-some [[_ clause] (re-find union-prose-members-re description)]
    (mapv #(parse-data-type {:content (map h/as-hickory (h/parse-fragment %))})
          (str/split clause #",\s*"))
    ;; NB: A type named in plain words can only be a member stated in prose, so
    ;;     if the regex above did not match, it is worded in some new way.
    (when-some [type-words (seq (remove type-noise? (re-seq #"\w+" description)))]
      (throw (ex-info "Found union members stated in unknown words"
                      {:type-words  (vec type-words)
                       :description description})))))

(defn parse-api-element-list
  [list-node description]
  ;; NB: A list makes a union of its API types and those stated in prose alone.
  (let [list-members (->> list-node
                          (s/select (s/child (s/tag :li) id-anchor-selector))
                          (mapv (comp ->temp-id get-anchor-id)))
        prose-members (parse-union-prose-members description)]
    {:kind  :type
     :union (or (not-empty (into (vec prose-members) list-members))
                (throw (ex-info "Found no union members" {:description description})))}))

;;;; Parsing > API Element

(defn ->api-element [subsection-nodes]
  (try
    (let [[api-element-basics nodes] (get-api-element-basics subsection-nodes)
          [kind-specific-node & rest] nodes]
      (conj api-element-basics
            (some-> (:tag kind-specific-node)
                    (case :table (parse-api-element-table kind-specific-node)
                          :ul (parse-api-element-list kind-specific-node
                                                      (:description api-element-basics))))
            (when (seq rest)
              {:notes (render-html:nodes rest)})))
    (catch Exception ex
      (log/errorf ex
                  "API element parsing failed. Subsection nodes:\n%s"
                  (with-out-str (pp/pprint subsection-nodes)))
      (System/exit 1))))

;;;; Parsing

(defn parse-version [tree]
  (-> (s/select (s/follow (s/tag :h3) (s/tag :h4) (s/tag :p)) tree)
      (first)
      (text)))

(defn ->dev-page-content [tree]
  (-> (s/select (s/id :dev_page_content) tree)
      (first)
      (remove-blank-strings)
      :content))

(defn normalize-ids [api-elements]
  (let [temp-id->id (build-temp-id->id api-elements)]
    (->> api-elements
         (walk/postwalk-replace temp-id->id)
         (walk/postwalk fallback-to-content-temp-id)
         (walk/postwalk-replace temp-id->id))))

(defn shape-into-map [curr-version api-elements]
  (merge
    {:version curr-version}
    (group-by #(case (:kind %) :type :types :method :methods) api-elements)))

(defn parse-tg-bot-api-page [html-str]
  (let [page-hy-tree (-> html-str (h/parse) (h/as-hickory))
        curr-version (parse-version page-hy-tree)
        page-content (->dev-page-content page-hy-tree)]
    (->> (->sections page-content)
         (map ->subsections)
         (apply concat)
         (map #(when-not (notes-subsection? %) (->api-element %)))
         (remove nil?)
         (normalize-ids)
         (shape-into-map curr-version))))

;;; Entrypoint

(defn ->version-number [version]
  (str/replace (str version) #"^Bot API\s+" ""))

(defn emit-gh-output! [k v]
  (some-> (System/getenv "GITHUB_OUTPUT")
          (spit (format "%s=%s\n" (name k) v) :append true)))

(defn update-spec!
  "Fetches the official docs page and rewrites the spec file if it has changed.

   Returns `nil` when there is nothing to update, and otherwise a map of what
   the update amounts to — the new `:version`, and a `:version-changed?` flag
   or a mere reshuffling of the same Bot API version."
  []
  (or (when-some [{:keys [body headers]} (fetch-tg-bot-api-page!)]
        (let [{:keys [version] :as parsed-page} (parse-tg-bot-api-page body)
              new-json (json/generate-string parsed-page {:pretty true})
              old-json (when (fs/exists? spec-file) (slurp spec-file))]
          (when-not (= old-json new-json)
            (spit spec-file new-json)
            (when-some [etag (get headers "ETag")] (spit etag-file etag))
            (log/info "Telegram Bot API >> Changed, spec updated")
            (let [{prev-version :version} (json/parse-string old-json true)]
              {:version          version
               :version-changed? (not= prev-version version)}))))
      (log/info "Telegram Bot API >> No changes")))

(defn -main [& _args]
  (when-some [{:keys [version version-changed?]} (update-spec!)]
    (emit-gh-output! :updated true)
    (emit-gh-output! :version (->version-number version))
    (emit-gh-output! :version-changed version-changed?))
  #_(System/exit 0))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment
  (do (def resp (fetch-tg-bot-api-page!))
      (def html-str (:body resp))
      (def page-hy-tree (-> html-str (h/parse) (h/as-hickory)))
      (def page-content (->dev-page-content page-hy-tree)))

  (def parsed-page (parse-tg-bot-api-page html-str))

  ;; subsections structure
  (->> (->sections page-content)
       (map ->subsections)
       (apply concat)
       (map #(when-not (notes-subsection? %) (map :tag %)))
       (remove nil?)
       (map #(partition-by kind-specific-tags %))
       (distinct)
       (sort-by count))

  ;; all parsed types
  (let [*types (atom #{})]
    (walk/postwalk (fn [form]
                     (let [type (:type form)]
                       (when (and type (not (map? type)))
                         (swap! *types conj type)))
                     form)
                   parsed-page)
    @*types)

  :end/comment)
