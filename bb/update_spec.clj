#!/usr/bin/env bb

(require
  '[babashka.fs :as fs]
  '[babashka.http-client :as http]
  '[hickory.core :as h]
  '[hickory.render :as r]
  '[hickory.select :as s]
  '[cheshire.core :as json]
  '[clojure.pprint :as pp]
  '[clojure.string :as str]
  '[clojure.tools.logging :as log]
  '[clojure.walk :as walk]
  '[flatland.ordered.map :refer [ordered-map]])

;;; Utils

(def blank-string? #(and (string? %) (str/blank? %)))

(def uppercase-string? #(Character/isUpperCase ^char (get % 0)))

(defn removev
  [pred coll]
  (filterv (complement pred) coll))

(defn remove-blank-strings
  [tree]
  (walk/prewalk
    (fn [form]
      (if (and (map? form) (seq (:content form)))
        (update form :content #(removev blank-string? %))
        form))
    tree))

;; Credits to 'https://cninja.blogspot.com/2011/02/clojure-partition-at.html'
(defn partition-at
  [f coll]
  (lazy-seq
    (when-let [s (seq coll)]
      (let [run (cons (first s) (take-while #(not (f %)) (rest s)))]
        (cons run (partition-at f (drop (count run) s)))))))

(defn ordered-map-by
  [key-fn coll]
  (ordered-map (map (juxt key-fn identity) coll)))

;;; Files

(def etag-file (fs/file "resources/.tg-bot-api-etag"))

(def spec-file (fs/file "resources/tg-bot-api-spec.json"))

;;; Fetching

(def tg-bot-api-url "https://core.telegram.org/bots/api")

(defn fetch-tg-bot-api-page! []
  (let [headers (when (fs/exists? etag-file)
                  {"If-None-Match" (str/trim (slurp etag-file))})
        {:keys [status] :as resp} (http/get tg-bot-api-url {:headers headers
                                                            :throw   false})]
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

(defn get-anchor-id
  [node]
  (keyword (subs (-> node :attrs :href) 1)))

(defn get-first-anchor-id
  [node]
  (some-> (s/select (s/child id-anchor-selector) node)
          (first)
          (get-anchor-id)))

;;;; Parsing > Data Types

(def array-type-prefix? #{"Array"})
(def array-type-postfix "of")
(def union-type-separator ",")

(def type-noise? (some-fn empty? #{array-type-postfix union-type-separator}))

(defn prepare-type-node
  [type-node]
  (cond (map? type-node) (get-anchor-id type-node)
        (string? type-node) (remove type-noise?
                                    (-> type-node
                                        (str/trim)
                                        (str/split #"\s")))
        :else type-node))

(def union-type-separators #{"or" "and"})

(defn parse-data-type
  [{:keys [content] :as _node}]
  (let [type-seq (flatten (map prepare-type-node content))
        [arrays type-seq] (split-with array-type-prefix? type-seq)
        type (if (= 1 (count type-seq))
               (first type-seq)
               [:or (removev union-type-separators type-seq)])]
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
  (let [id (get-first-anchor-id node)
        name (text node)
        kind (best-guess-kind name)
        [tags rest'] (split-with #(description-tags (:tag %)) rest)
        description (render-html:nodes tags)]
    [{:id          id
      :name        name
      :kind        kind
      :description description}
     rest']))

;;;; Parsing > API Element > API Type

(defn col-name->attr-key
  [col-name]
  (case col-name
    ("Field" "Parameter") :name
    "Type" :type
    "Required" :required
    "Description" :description))

(defn prepare-api-type-field
  [{:keys [description] :as field}]
  (let [optional? (-> (s/select s/first-child description)
                      (first)
                      (has-text? "Optional"))]
    (-> field
        (update :name (comp keyword first :content))
        (update :type parse-data-type)
        (assoc :required (not optional?))
        (update :description (comp render-html:nodes :content)))))

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
  (reduce (fn [type-acc single-row-nodes]
            (let [field (get-api-type-field col-names single-row-nodes)]
              (update type-acc :fields conj [(:name field) field])))
          {:kind   :type
           :fields (ordered-map)}
          row-nodes))

;;;; Parsing > API Element > API Method

(defn prepare-api-method-param
  [param]
  (when (= "reply_markup" (:name param))
    (def param param))
  (-> param
      (update :name (comp keyword first :content))
      (update :type parse-data-type)
      (update :required #(has-text? % "Yes"))
      (update :description (comp render-html:nodes :content))))

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
  (reduce (fn [method-acc single-row-nodes]
            (let [param (get-api-method-param col-names single-row-nodes)]
              (update method-acc :params conj [(:name param) param])))
          {:kind   :method
           :params (ordered-map)
           #_#_:returns nil} ; no need for the return type in Clojure
          row-nodes))

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

(defn parse-api-element-list
  [list-node]
  (let [subtypes (->> list-node
                      (s/select (s/child (s/tag :li) id-anchor-selector))
                      (mapv get-anchor-id))]
    {:kind     :type
     :subtypes subtypes}))

;;;; Parsing > API Element

(defn ->api-element [subsection-nodes]
  (try
    (let [[api-element-basics nodes] (get-api-element-basics subsection-nodes)
          [kind-specific-node & rest] nodes]
      (conj api-element-basics
            (some-> (:tag kind-specific-node)
                    (case :table (parse-api-element-table kind-specific-node)
                          :ul (parse-api-element-list kind-specific-node)))
            (when (seq rest)
              {:notes (render-html:nodes rest)})))
    (catch Exception ex
      (log/errorf ex
                  "API element parsing failed. Subsection nodes:\n%s"
                  (with-out-str (pp/pprint subsection-nodes)))
      (System/exit 1))))

;;;; Parsing

(defn ->dev-page-content [tree]
  (-> (s/select (s/id :dev_page_content) tree)
      (first)
      (remove-blank-strings)
      :content))

(defn parse-tg-bot-api-page [html-str]
  (let [page-hy-tree (-> html-str (h/parse) (h/as-hickory))
        page-content (->dev-page-content page-hy-tree)]
    (->> (->sections page-content)
         (map ->subsections)
         (apply concat)
         (map #(when-not (notes-subsection? %) (->api-element %)))
         (remove nil?)
         (ordered-map-by :id))))

;;; Entrypoint

(defn -main [& _args]
  (or (when-some [{:keys [body headers]} (fetch-tg-bot-api-page!)]
        (let [parsed-page (parse-tg-bot-api-page body)
              new-json (json/generate-string parsed-page {:pretty true})
              old-json (when (fs/exists? spec-file) (slurp spec-file))]
          (when-not (= old-json new-json)
            (spit spec-file new-json)
            (when-some [etag (get headers "ETag")] (spit etag-file etag))
            (log/info "Telegram Bot API >> Changed, spec updated")
            :updated)))
      (log/info "Telegram Bot API >> No changes")))

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
