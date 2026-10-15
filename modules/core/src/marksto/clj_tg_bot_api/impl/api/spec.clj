;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.impl.api.spec
  {:author "Mark Sto (@marksto)"}
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [flatland.ordered.map :refer [ordered-map]]
   [jsonista.core :as json]
   [marksto.clj-tg-bot-api.impl.api.formatting :as fmt]
   [marksto.clj-tg-bot-api.impl.utils :as utils]
   [martian.schema-tools :as mct]
   [schema-tools.coerce :as stc]
   [schema.core :as s])
  (:import
   (java.io File InputStream)
   (java.net URI URL)
   (java.nio.charset StandardCharsets)
   (java.nio.file Path)
   (schema.core Constrained NamedSchema Recursive)))

(defn get-json-serialized-paths
  [api-element]
  (let [*json-serialized-paths (atom [])]
    (mct/prewalk-with-path
      (fn [path form]
        (when (and (map? form) (:json_serialized form))
          (->> (keyword (:name form))
               (conj path)
               (swap! *json-serialized-paths conj)))
        form)
      []
      api-element)
    @*json-serialized-paths))

;;; Types

(def api-type-prefix "type/")

(def basic-type?
  #(and (string? %) (not (str/starts-with? % api-type-prefix))))

(def input-file?
  ;; NB: Omitting case for String that is usually handled by HTTP clients,
  ;;     because the String has special semantics in the Telegram Bot API:
  ;;     https://core.telegram.org/bots/api#sending-files
  #(or (instance? File %)
       (instance? URL %)
       (instance? URI %)
       (instance? Path %)
       (instance? InputStream %)
       (bytes? %)))

(s/defschema True
  "Boolean `true` (solely)."
  (s/pred true? 'true?))

(s/defschema Floating
  "Any floating point number."
  (s/pred float? 'float?))

(s/defschema InputFile
  "A file to be uploaded using 'multipart/form-data'."
  (s/pred input-file? 'input-file?))

(def input-file-api-type-id "type/input-file")

;;; Types > Schemas

;; - Basic types
;;   "Boolean", "True", "String", "Integer", "Float"
;;
;; - API types
;;   :some-api-type, :input-file (special case)
;;
;; - Container types > Union
;;   [:or ["Integer" "String"]]
;;   [:or [:input-file "String"]]
;;   [:or [:some-api-type ...]]
;;
;; - Container types > Array
;;   [:array "String"]
;;   [:array :some-api-type]
;;   [:array [:or [:some-api-type ...]]]
;;   [:array [:array :some-api-type]]

(defn basic-type->schema
  [^String type]
  (case type
    "Boolean" s/Bool
    "True" True
    "String" s/Str
    "Integer" s/Int
    "Float" Floating
    s/Any))

(defn known-type-schemas []
  (utils/map-keys
    {input-file-api-type-id InputFile}
    basic-type->schema
    ["Boolean" "True" "String" "Integer" "Float"]))

(defn unwrap-schema [schema]
  (if (or (instance? NamedSchema schema)
          (instance? Constrained schema))
    (recur (:schema schema))
    schema))

;; NB: Every schema record is a map as well, be it a predicate or a conditional,
;;     so it is a plain map alone that makes an API type schema. And so does a
;;     recursive ref, for it is API types alone that are ever referred so, and
;;     it has no precondition of its own that would tell it apart.
(defn api-type-schema? [schema]
  (let [schema (unwrap-schema schema)]
    (or (instance? Recursive schema)
        (and (map? schema) (not (record? schema))))))

(defn get-keys [schema]
  (let [schema (unwrap-schema schema)]
    (cond
      (instance? Recursive schema) (recur @(:derefable schema))
      (api-type-schema? schema) (keys schema))))

(defn get-required-keys [schema]
  (set (filter keyword? (get-keys schema))))

(defn get-allowed-keys [schema]
  (set (map s/explicit-schema-key (get-keys schema))))

(defn ->map-shape-pred
  "Gives a pred telling whether an object takes the shape of the `schema`,
   it being the first of the given `*shapes` that the object conforms to,
   i.e. has all the required keys and only the allowed keys of. If there's
   no such shape, it's the first one the object has the required keys of."
  [*shapes schema]
  (fn [obj]
    (and (map? obj)
         (let [obj-keys (utils/keyset obj)
               has-all-required-keys? (fn [{:keys [required]}]
                                        (set/subset? required obj-keys))
               has-only-allowed-keys? (fn [{:keys [allowed]}]
                                        (set/subset? obj-keys allowed))
               candidates (filter has-all-required-keys? @*shapes)
               {shape-schema :schema} (or (some #(when (has-only-allowed-keys? %) %)
                                                candidates)
                                          (first candidates))]
           (identical? schema shape-schema)))))

(defn map-schemas->schema
  "Returns a single schema that stands for all the given `map-schemas`."
  [map-schemas error-symbol]
  (if (next map-schemas)
    ;; NB: For map schemas we have no other good options than to take the map
    ;;     shape (the sets of required and allowed keys) and check for their
    ;;     conformance with it. We also have to sort these map schemas properly
    ;;     in order to avoid (coercion) issues in case the shape of one of them
    ;;     fully contains the shape of the other. And since a recursive ref may
    ;;     be not bound yet at this point, the shapes are only taken on the
    ;;     first check.
    (let [*shapes (delay (->> (for [schema map-schemas]
                                {:required (get-required-keys schema)
                                 :allowed (get-allowed-keys schema)
                                 :schema schema})
                              (sort-by (comp count :required) >)))
          pred+schemas (for [schema map-schemas]
                         [(->map-shape-pred *shapes schema) schema])]
      (apply s/conditional (cond-> (into [] cat pred+schemas)
                                   error-symbol (conj error-symbol))))
    (first map-schemas)))

(defn or-schema
  "Builds a schema for a union of the given `schemas`, which a value fits
   if it fits any of them. The map schemas are told apart by their shapes,
   the rest by their own preconditions. If all the `schemas` are map ones,
   the `error-symbol` names the error of a value that takes none of their
   shapes."
  ([schemas]
   (or-schema schemas nil))
  ([schemas error-symbol]
   ;; NB: Based on the `s/cond-pre` docstring, it is only suitable for schemas
   ;;     with mutually exclusive preconditions (e.g. `s/Int` and `s/Str`) and
   ;;     it won't work on map schemas, which is what all API types are. Thus,
   ;;     we need to make sure there's a max of 1 map schema for `s/cond-pre`.
   (let [{map-schemas   true
          other-schemas false} (group-by api-type-schema? schemas)
         exclusive-schemas (cond-> other-schemas
                                   (seq map-schemas)
                                   (conj (map-schemas->schema map-schemas error-symbol)))]
     (if (next exclusive-schemas)
       (apply s/cond-pre exclusive-schemas)
       (first exclusive-schemas)))))

(defn ->range-pred
  ([range]
   (->range-pred range identity))
  ([{:keys [from to]} measure]
   (fn [obj]
     (let [n (measure obj)]
       (and (if from (<= from n) true)
            (<= n to))))))

(defmulti ->constraint-pred
  "Builds a predicate for a single constraint, given the modifiers that alter
   the way it is checked and the schema it guards."
  {:arglists '([constraint params modifiers schema])}
  (fn [constraint _params _modifiers _schema] constraint))

(defn ->term-in-pred
  "Builds a pred for a single term an `in` constraint admits, which is either
   an inclusive span or an exact value."
  [term]
  (if (map? term) (->range-pred term) #(== term %)))

(defmethod ->constraint-pred :in
  [_ terms _modifiers schema]
  (when-not (contains? #{s/Int Floating} schema)
    (throw (ex-info "The 'in' constraint requires a number" {:schema schema})))
  ;; NB: The terms of an `in` are a union — any one of them admits the value —
  ;;     in contrast to the constraints of a category, which all have to hold.
  (apply some-fn (map ->term-in-pred terms)))

(defmethod ->constraint-pred :enum
  [_ literals _modifiers schema]
  (when-not (= s/Str schema)
    (throw (ex-info "The 'enum' constraint requires a string" {:schema schema})))
  (set literals))

(defmethod ->constraint-pred :size
  [_ params _modifiers schema]
  (when-not (vector? schema)
    (throw (ex-info "The 'size' constraint requires an array" {:schema schema})))
  (->range-pred params count))

(defmethod ->constraint-pred :total_length
  [_ params _modifiers schema]
  (when-not (= [s/Str] schema)
    (throw (ex-info "The 'total_length' constraint requires string items"
                    {:schema schema})))
  (->range-pred params #(transduce (map count) + %)))

(def length-unit->measure
  {"chars" count
   "bytes" #(alength (.getBytes ^String % StandardCharsets/UTF_8))})

(defmethod ->constraint-pred :length
  [_ {:keys [unit] :as params} _modifiers schema]
  (when-not (= s/Str schema)
    (throw (ex-info "The 'length' constraint requires a string"
                    {:schema schema})))
  (if-some [measure (length-unit->measure (or unit "chars"))]
    (->range-pred params measure)
    (throw (ex-info "Unsupported 'length' constraint unit" {:unit unit}))))

(defmethod ->constraint-pred :pattern
  [_ pattern _modifiers schema]
  (when-not (= s/Str schema)
    (throw (ex-info "The 'pattern' constraint requires a string"
                    {:schema schema})))
  (let [pattern-re (re-pattern pattern)]
    #(some? (re-matches pattern-re %))))

(def constraint-modifiers #{})

(defn ->constraints-pred
  [constraints schema]
  (let [modifiers (select-keys constraints constraint-modifiers)]
    (->> (utils/filter-keys constraints (complement constraint-modifiers))
         (map (fn [[constraint params]]
                (->constraint-pred constraint params modifiers schema)))
         (apply every-pred any?))))

(declare deferred-constraints?)

(defn ->constraints-error-symbol [category]
  (symbol (str (name category) "-constraints")))

(defn constrained-schema
  "Gives a constrained `schema` as `:schema`, plus as `:deferred` whatever
   constraints only the whole map can check, together with the very schema
   they are to be checked with."
  [schema constraints]
  (let [{deferred  true
         immediate false} (group-by (comp deferred-constraints? val) constraints)]
    (cond-> {:schema (reduce (fn [constrained-schema [category category-constraints]]
                               (s/constrained constrained-schema
                                              (->constraints-pred category-constraints schema)
                                              (->constraints-error-symbol category)))
                             schema
                             immediate)}
            (seq deferred)
            (assoc :deferred {:schema schema :constraints (into {} deferred)}))))

(def type-schemas-ns (create-ns 'marksto.clj-tg-bot-api.impl.api.schemas))

(defn type-schema-symbol [type-name]
  (symbol (str type-name "Schema")))

(defn type-schema-var
  ([type-name]
   (intern type-schemas-ns (type-schema-symbol type-name)))
  ([type-name schema]
   (intern type-schemas-ns (type-schema-symbol type-name) schema)))

(defn has-type-schema-var? [type-name]
  (boolean (ns-resolve type-schemas-ns (type-schema-symbol type-name))))

;; NB: An "attr" is whatever an API element is made of — a field of an API type
;;     or a param of an API method. The two are shaped alike, so all the schema
;;     building below is stated in these neutral terms.

(def ->attr-name (comp keyword :name))

(defn get-attr-value
  [obj attr-name]
  (or (get obj attr-name)
      (get obj (csk/->kebab-case attr-name))))

(defn ->type-value
  "Gives the value the type-dependant field of a `subtype` is fixed to, if any."
  [subtype]
  (some :value (:fields subtype)))

(defn ->type-pred
  [type-dependant-field type-value]
  (let [attr-name (->attr-name type-dependant-field)]
    (fn [obj]
      ;; NB: An object meets this pred before its fields are coerced, so a type
      ;;     given as a keyword (e.g. `:default`) is yet to become a string, and
      ;;     it is this very fn that makes it one.
      (= type-value (stc/keyword->string (get-attr-value obj attr-name))))))

;;; Types > Schemas > Cross-attribute constraints

(defn ->parse-mode-name
  [attr-name attr-names]
  (let [own-name (keyword (str (name attr-name) "_parse_mode"))]
    (if (contains? attr-names own-name) own-name :parse_mode)))

(defmulti ->value-resolver
  "Builds a fn that, given the whole map and the raw value, resolves the value
   the constraints must be checked against, or `::unresolved` when it can't be
   determined. Consumes the modifier it is built for."
  {:arglists '([modifier attr attr-names])}
  (fn [modifier _attr _attr-names] modifier))

(defmethod ->value-resolver :after_entities_parsing
  [_ attr attr-names]
  (let [parse-mode-name (->parse-mode-name (->attr-name attr) attr-names)]
    (fn [obj value]
      (if-some [parse-mode (get-attr-value obj parse-mode-name)]
        (or (fmt/strip-entities parse-mode value) ::unresolved)
        value))))

(def sibling-modifiers
  "A set of modifiers that resolve a value to check against the sibling attrs,
   so the constraints they alter can only be checked on the whole map."
  #{:after_entities_parsing})

(defn deferred-constraints? [constraints]
  (boolean (some sibling-modifiers (keys constraints))))

(defn ->deferred-constraints-pred
  [{:keys [attr schema constraints]} attr-names]
  (let [attr-name (->attr-name attr)
        resolvers (->> (vals constraints)
                       (mapcat keys)
                       (filter sibling-modifiers)
                       (distinct)
                       (mapv #(->value-resolver % attr attr-names)))
        ;; NB: A resolver consumes its own modifier, so whatever is left
        ;;     gets checked exactly as an unmodified constraint would be.
        pred (->> (vals constraints)
                  (map #(->constraints-pred (apply dissoc % sibling-modifiers) schema))
                  (apply every-pred any?))]
    (fn [obj]
      (let [value (reduce (fn [value value-resolver]
                            (if (= ::unresolved value)
                              value
                              (value-resolver obj value)))
                          (get-attr-value obj attr-name)
                          resolvers)]
        (or (nil? value) (= ::unresolved value) (pred value))))))

(defmulti ->relation-pred
  "Builds a predicate for a single relation between the attrs of an API element,
   given the groups of attr names it holds for."
  {:arglists '([relation attr-name-groups])}
  (fn [relation _attr-name-groups] relation))

(defn ->attrs-presence-pred
  [attr-name-groups present-count-ok?]
  (let [preds (for [attr-names (map #(map keyword %) attr-name-groups)]
                (fn [obj]
                  (-> (filter #(some? (get-attr-value obj %)) attr-names)
                      (count)
                      (present-count-ok?))))]
    (apply every-pred preds)))

(defmethod ->relation-pred :at_most_one
  [_ attr-name-groups]
  (->attrs-presence-pred attr-name-groups #(<= % 1)))

(defmethod ->relation-pred :at_least_one
  [_ attr-name-groups]
  (->attrs-presence-pred attr-name-groups #(>= % 1)))

(defmethod ->relation-pred :required_with
  [_ attr-name-groups]
  (let [preds (for [[required-attr-name other-attr-name]
                    (map #(map keyword %) attr-name-groups)]
                (fn [obj]
                  (or (nil? (get-attr-value obj other-attr-name))
                      (some? (get-attr-value obj required-attr-name)))))]
    (apply every-pred preds)))

(defn ->attrs-relations-pred
  [constraints]
  (when-some [preds (->> constraints
                         (mapv (fn [[relation attr-name-groups]]
                                 (->relation-pred relation attr-name-groups)))
                         (not-empty))]
    (apply every-pred preds)))

(defn ->attrs-constraints-pred
  [deferred attrs constraints]
  (let [attr-names (into #{} (map ->attr-name) attrs)]
    (when-some [preds (->> (conj (mapv #(->deferred-constraints-pred % attr-names)
                                       deferred)
                                 (->attrs-relations-pred constraints))
                           (remove nil?)
                           (not-empty))]
      (apply every-pred preds))))

(defn attrs-constrained-schema
  [map-schema deferred attrs constraints]
  (if-some [pred (->attrs-constraints-pred deferred attrs constraints)]
    (s/constrained map-schema pred 'attrs-constraints)
    map-schema))

(defn type->schema
  [*state type]
  (cond
    (string? type)
    (let [{type-name :name} (get-in @*state [:id->api-type type])]
      ;; NB: We can rest assured that the required schema is already available
      ;;     thanks to the topological order of parsing, except within a cycle.
      ;;     Its order is arbitrary, so a type may be parsed before the one it
      ;;     refers to, be it directly or from within a container type — there
      ;;     may be no schema to look up yet and then only a `s/recursive` ref
      ;;     can stand in for the latter.
      (if (and (some? type-name) (has-type-schema-var? type-name))
        (s/recursive (type-schema-var type-name))
        (get-in @*state [:id->schema type])))

    (vector? type)
    (let [[container-type inner-type] type]
      (case container-type
        "array" [(type->schema *state inner-type)]
        "or" (or-schema (map #(type->schema *state %) inner-type))))

    :else
    (throw (ex-info "Unsupported type" {:type type}))))

(defn constrained-type->schema
  [*state type constraints]
  (let [schema (type->schema *state type)]
    (if constraints
      (constrained-schema schema constraints)
      {:schema schema})))

;; NB: An attr both contributes its own schema and may defer some constraints
;;     to the whole type/params map, so the two are gathered in a single pass.
(defn ->attrs-schema
  [->attr-schema attrs]
  (reduce (fn [acc attr]
            (let [{:keys [key schema deferred]} (->attr-schema attr)]
              (cond-> (assoc-in acc [:schema key] schema)
                      deferred (update :deferred conj (assoc deferred :attr attr)))))
          {:schema   {}
           :deferred []}
          attrs))

(defn api-type:field->attr-schema
  [*state {:keys [required type constraints] :as field}]
  (-> *state
      (constrained-type->schema type constraints)
      (assoc :key (cond-> (->attr-name field)
                          (not required) (s/optional-key)))))

(defn api-type:concrete->schema
  [*state name fields constraints]
  (let [{:keys [schema deferred]}
        (->attrs-schema #(api-type:field->attr-schema *state %) fields)]
    (-> schema
        (attrs-constrained-schema deferred fields constraints)
        (s/named name))))

;; NB: Several subtypes may share a type value (e.g. a cached and a URL "photo"
;;     result), and then it is the attrs that one of them requires and none of
;;     the others has that tell them apart. A valid object of the others never
;;     holds any of those, so an object that does is checked against that very
;;     subtype, and gets its errors (say, a missing attr) reported against it.

(defn get-distinctive-attr-names
  "Gives the names of the attrs a `subtype` requires that none of the `others`
   (the subtypes sharing its type value) has among its attrs."
  [subtype others]
  (let [others-attr-names (into #{} (comp (mapcat :fields) (map :name)) others)]
    (into []
          (comp (filter :required)
                (map :name)
                (remove others-attr-names))
          (:fields subtype))))

(defn ->distinctive-attrs-error-symbol
  [attr-name-groups]
  (->> attr-name-groups
       (map #(str/join "+" %))
       (str/join "-or-")
       (format "%s?")
       (symbol)))

(defn api-type:same-type-subtypes->schema
  [*state api-subtype-ids]
  (if (next api-subtype-ids)
    (let [subtypes (map #(get-in @*state [:id->api-type %]) api-subtype-ids)
          attr-name-groups (map #(get-distinctive-attr-names % (remove #{%} subtypes))
                                subtypes)]
      (apply s/conditional
             (-> (vec (interleave
                        (map #(->relation-pred :at_least_one [%]) attr-name-groups)
                        (map #(type->schema *state %) api-subtype-ids)))
                 (conj (->distinctive-attrs-error-symbol attr-name-groups)))))
    (type->schema *state (first api-subtype-ids))))

(defn api-type:subtypes->schema
  [*state api-subtype-ids]
  (let [subtypes (map #(get-in @*state [:id->api-type %]) api-subtype-ids)
        type-dependant-field (some #(when (contains? % :value) %)
                                   (:fields (first subtypes)))]
    (if (some? type-dependant-field)
      (->> subtypes
           (reduce (fn [acc {:keys [id] :as subtype}]
                     (update acc (->type-value subtype) (fnil conj []) id))
                   (ordered-map))
           (mapcat (fn [[type-value api-subtype-ids]]
                     [(->type-pred type-dependant-field type-value)
                      (api-type:same-type-subtypes->schema *state api-subtype-ids)]))
           (apply s/conditional))
      (or-schema (map #(type->schema *state %) api-subtype-ids)
                 'subtype?))))

(defn api-type:supertype->schema
  [*state name api-subtype-ids alternatives]
  (let [subtypes-schema (api-type:subtypes->schema *state api-subtype-ids)]
    (s/named
      (if (seq alternatives)
        ;; NB: The alternatives are the members that are no API types, e.g.
        ;;     a String or an Array. None of them is a map, so it's their own
        ;;     preconditions that tell them apart from the subtypes, all of
        ;;     which are maps, and that is precisely what `s/cond-pre` needs.
        (apply s/cond-pre (conj (mapv #(type->schema *state %) alternatives)
                                subtypes-schema))
        subtypes-schema)
      name)))

(defn api-type->schema
  [*state {:keys [id name fields subtypes alternatives constraints] :as _api-type}]
  (let [schema (cond
                 (some? fields)
                 (api-type:concrete->schema *state name fields constraints)

                 (some? subtypes)
                 (api-type:supertype->schema *state name subtypes alternatives)

                 :else ; <=> "Currently holds no information"
                 (if (= input-file-api-type-id id) InputFile s/Any))]
    (when (has-type-schema-var? name)
      (type-schema-var name schema))
    schema))

(defn parse:api-type
  [*state {api-type-id :id fields :fields :as api-type}]
  (let [schema (api-type->schema *state api-type)
        ______ (swap! *state assoc-in [:id->schema api-type-id] schema)
        json-serialized-paths (get-json-serialized-paths fields)]
    (cond-> (assoc api-type :schema schema)

            (seq json-serialized-paths)
            (assoc :json-serialized-paths json-serialized-paths))))

;;; Methods

(def api-method-prefix "method/")

(defn api-method:param->attr-schema
  [*state {:keys [name type required constraints]}]
  (-> *state
      (constrained-type->schema type constraints)
      (assoc :key (cond-> (keyword name)
                          (not required) (s/optional-key)))))

(defn api-method-param-of-input-type?
  [{:keys [type]}]
  (or (= input-file-api-type-id type)
      (contains? (set (flatten type)) input-file-api-type-id)))

(defn api-method-params->params-schema
  [*state params constraints]
  (let [{:keys [schema deferred]}
        (->attrs-schema #(api-method:param->attr-schema *state %) params)]
    (attrs-constrained-schema schema deferred params constraints)))

(defn parse:api-method
  [*state {:keys [params constraints] :as api-method}]
  (let [json-serialized-paths (get-json-serialized-paths params)]
    (cond-> api-method

            (some? params)
            (assoc :params-schema
                   (api-method-params->params-schema *state params constraints))

            (some api-method-param-of-input-type? params)
            (assoc :uploads-file? true)

            (seq json-serialized-paths)
            (assoc :json-serialized-paths json-serialized-paths))))

;;; Types Parsing Order

(defn does-not-affect-order? [type]
  (or (basic-type? type) (= input-file-api-type-id type)))

(defn decontainerize
  "Gives the types a `type` holds inside its container types, if any, or else
   the very `type`. Unlike an array, which holds a single type, a union holds
   several ones, so it always returns a coll of types."
  [type]
  (if (vector? type)
    (let [[container-type inner-type] type]
      (case container-type
        "array" (decontainerize inner-type)
        "or" (mapcat decontainerize inner-type)))
    [type]))

(defn api-type->type-with-deps
  [{:keys [id fields subtypes alternatives]}]
  (cond-> {:id id}

          (some? fields)
          (assoc :deps (->> fields
                            (mapcat (comp decontainerize :type))
                            (remove does-not-affect-order?)))

          (some? subtypes)
          (assoc :deps (->> alternatives
                            (mapcat decontainerize)
                            (remove does-not-affect-order?)
                            (concat subtypes)))))

(defn build-types-deps-graph
  [types]
  (let [types-with-deps (map api-type->type-with-deps types)]
    (->> types-with-deps
         (map (juxt :id :deps))
         (into {})
         (utils/map->graph))))

(defn ordered-type-ids
  "Gives a seq of type IDs ordered topologically for further types processing."
  [types-deps-g]
  (reverse (utils/topsort-with-cycles types-deps-g)))

(defn prepare-recursive-types!
  "Creates (interns into current ns) type schema vars for the recursive types."
  [types-deps-g id->api-type]
  (let [cycled-types (utils/cycled-nodes types-deps-g)]
    (doseq [api-type-id cycled-types]
      (type-schema-var (-> api-type-id id->api-type :name)))))

;;; Parsing

(defn parse-raw-spec
  [{:keys [types methods]}]
  (let [id->api-type (utils/index-by :id types)
        *state (atom {:id->api-type id->api-type
                      :id->schema   (ordered-map (known-type-schemas))})
        types-deps-g (build-types-deps-graph types)
        ordered-types (map id->api-type (ordered-type-ids types-deps-g))]
    (prepare-recursive-types! types-deps-g id->api-type)

    (let [parsed-types (mapv #(parse:api-type *state %) ordered-types)
          parsed-methods (mapv #(parse:api-method *state %) methods)]
      (reset! *state nil)
      {:types   parsed-types
       :methods parsed-methods})))

(defn read-raw-spec!
  [file-name]
  (try
    (json/read-value
      (slurp (io/resource file-name))
      (json/object-mapper {:decode-key-fn true}))
    (catch Exception e
      (log/errorf e "Failed to read spec file '%s'" file-name))))

(defn parse-tg-bot-api-spec!
  [file-name]
  (when-some [raw-spec (read-raw-spec! file-name)]
    (try
      (parse-raw-spec raw-spec)
      (catch Exception e
        (log/errorf e "Failed to parse spec file '%s'" file-name)))))

(def *tg-bot-api-spec
  (delay (parse-tg-bot-api-spec! "tg-bot-api-spec.json")))

(defn get-tg-bot-api-spec []
  @*tg-bot-api-spec)

;;; Update Types

(defn collect-update-types
  [& {:keys [message? edited?] :as _opts}]
  (let [all-api-types (:types (get-tg-bot-api-spec))
        update-fields (->> all-api-types
                           (some #(when (= "Update" (:name %)) %))
                           :fields
                           (remove #(= "update_id" (:name %))))]
    (reduce
      (fn [acc {:keys [name type] :as field}]
        (let [incl-message? (or (not message?)
                                (= "type/message" type))
              incl-edited? (or (not edited?)
                               (str/starts-with? name "edited"))]
          (if (and incl-message? incl-edited?)
            (conj acc {:name (->attr-name field)})
            acc)))
      []
      update-fields)))

;;

(comment
  (def tg-bot-api-spec (parse-tg-bot-api-spec! "tg-bot-api-spec.json"))

  (def input-message-content
    (some #(when (= "InputMessageContent" (:name %)) %)
          (:types tg-bot-api-spec)))

  (def input-message-content-validator
    (s/validator (:schema input-message-content)))

  (input-message-content-validator {}) ; invalid
  ;; InputTextMessageContent
  (input-message-content-validator {:parse_mode "MarkdownV2"}) ; invalid
  (input-message-content-validator {:message_text "Hello there!"})
  ;; InputLocationMessageContent
  (input-message-content-validator {:latitude 68.960343}) ; invalid
  (input-message-content-validator {:latitude  68.960343
                                    :longitude 33.083448})
  ;; InputVenueMessageContent
  (input-message-content-validator {:latitude  68.966992
                                    :longitude 33.099318
                                    :title     "Cinema"}) ; invalid
  (input-message-content-validator {:latitude  68.966992
                                    :longitude 33.099318
                                    :title     "Cinema"
                                    :address   "Murmansk, Polyarnye Zori St., 51"})
  ;; InputContactMessageContent
  (input-message-content-validator {:first_name "Saint Martin"}) ; invalid
  (input-message-content-validator {:phone_number "+590691286858"
                                    :first_name   "Saint Martin"})
  ;; InputInvoiceMessageContent
  (input-message-content-validator {:title "Tofu XF"}) ; invalid
  (input-message-content-validator {:title       "Tofu XF"
                                    :description "Extra firm tofu"
                                    :payload     "prod-T0003"
                                    :currency    "XTR"}) ; invalid
  (input-message-content-validator {:title       "Tofu XF"
                                    :description "Extra firm tofu"
                                    :payload     "prod-T0003"
                                    :currency    "XTR"
                                    :prices      [{:label  "price"
                                                   :amount 1000}]})

  :end/comment)
