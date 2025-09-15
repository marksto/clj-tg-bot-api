(ns marksto.clj-tg-bot-api.impl.api.spec
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [flatland.ordered.map :refer [ordered-map]]
   [jsonista.core :as json]
   [marksto.clj-tg-bot-api.impl.utils :as utils]
   [martian.schema-tools :as mct]
   [schema.core :as s])
  (:import
   (java.io File InputStream)
   (java.net URI URL)
   (java.nio.file Path)
   (schema.core NamedSchema)))

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

;; NB: At the moment is as simple as this.
(def api-type-schema? map?)

(defn get-required-keys
  [map-schema]
  (-> (if (instance? NamedSchema map-schema)
        (:schema map-schema) ; unwrap named
        map-schema)
      (utils/filter-keys keyword?)
      (utils/keyset)))

(defn ->map-shape-pred
  [required-keys]
  (fn [obj]
    (and (map? obj)
         (set/subset? required-keys (utils/keyset obj)))))

(defn or-schema
  ([schemas]
   (or-schema schemas nil))
  ([schemas error-symbol]
   ;; NB: Based on the `s/cond-pre` docstring, it is only suitable for schemas
   ;;     with mutually exclusive preconditions (e.g. `s/Int` and `s/Str`) and
   ;;     it won't work on map schemas, which is what all API types are. Thus,
   ;;     we need to make sure there's a max of 1 map schema for `s/cond-pre`.
   (if (< 1 (count (filter api-type-schema? schemas)))
     ;; NB: For map schemas we have no other good options than to take the map
     ;;     shape (a set of required keys) and check for conformance with it.
     ;;     We also have to sort these map schemas properly in order to avoid
     ;;     (coercion) issues in case the shape of one of them fully contains
     ;;     the shape of the other.
     (let [pred+schemas (->> schemas
                             (map #(vector (get-required-keys %) %))
                             (sort-by (comp count first) >)
                             (map (fn [[req-keys schema]]
                                    [(->map-shape-pred req-keys) schema])))]
       (apply s/conditional (cond-> (vec (flatten pred+schemas))
                                    error-symbol (conj error-symbol))))
     (apply s/cond-pre schemas))))

;; TODO: Impl the `after_entities_parsing`-related logic. Pre-parse an `obj`?
;;       Is the juice worth the squeeze though? Probably not, or vary rarely.
(defn ->string-constraints-pred
  [{:keys [from to after_entities_parsing]}]
  (fn [obj]
    (or after_entities_parsing
        (let [str-length (count obj)]
          (and (if from (<= from str-length) true)
               (<= str-length to))))))

(defn constrained-schema
  [schema {:keys [string] :as _constraints}]
  (condp = schema
    s/Str (s/constrained schema (->string-constraints-pred string))))

(defn type-schema-symbol [type-name]
  (symbol (str type-name "Schema")))

(defn type-schema-var
  ([type-name]
   (intern *ns* (type-schema-symbol type-name)))
  ([type-name schema]
   (intern *ns* (type-schema-symbol type-name) schema)))

(defn has-type-schema-var? [type-name]
  (boolean (ns-resolve *ns* (type-schema-symbol type-name))))

(def ->field-name (comp keyword :name))

(defn ->type-pred
  [type-dependant-field subtype]
  (let [field-name (->field-name type-dependant-field)]
    (fn [obj]
      (= (some :value (:fields subtype))
         (or (get obj field-name)
             (get obj (csk/->kebab-case field-name)))))))

(defn type->schema
  [*state type]
  (cond
    (string? type)
    ;; NB: We can rest assured that the required schema is already available
    ;;     and avoid a recursion thanks to the topological order of parsing.
    (get-in @*state [:id->schema type])

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
      (if (vector? schema)
        (mapv #(constrained-schema % constraints) schema)
        (constrained-schema schema constraints))
      schema)))

(defn api-type:concrete->schema
  [*state name fields]
  (-> {}
      (utils/index-by ->field-name fields)
      (utils/update-kvs
        (fn [field-name {:keys [required type constraints]}]
          (let [{name :name} (get-in @*state [:id->api-type type])
                field-schema (if (has-type-schema-var? name)
                               (s/recursive (type-schema-var name))
                               (constrained-type->schema *state type constraints))]
            [(cond-> field-name (not required) (s/optional-key))
             field-schema])))
      (s/named name)))

(defn api-type:supertype->schema
  [*state name api-subtype-ids]
  (let [subtype-schemas (map #(type->schema *state %) api-subtype-ids)
        subtypes (map #(get-in @*state [:id->api-type %]) api-subtype-ids)
        type-dependant-field (some #(when (contains? % :value) %)
                                   (:fields (first subtypes)))]
    (s/named
      (if (some? type-dependant-field)
        (apply s/conditional
               (interleave (map #(->type-pred type-dependant-field %) subtypes)
                           subtype-schemas))
        (or-schema subtype-schemas 'subtype?))
      name)))

(defn api-type->schema
  [*state {:keys [id name fields subtypes] :as _api-type}]
  (cond
    (some? fields)
    (let [schema (api-type:concrete->schema *state name fields)]
      (when (has-type-schema-var? name)
        (type-schema-var name schema))
      schema)

    (some? subtypes)
    (api-type:supertype->schema *state name subtypes)

    :else ; <=> "Currently holds no information"
    (if (= input-file-api-type-id id) InputFile s/Any)))

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

(defn api-method-param->param-schema
  [*state {:keys [name type required constraints]}]
  (let [param-key (cond-> (keyword name) (not required) (s/optional-key))
        param-val (constrained-type->schema *state type constraints)]
    [param-key param-val]))

(defn api-method-param-of-input-type?
  [{:keys [type]}]
  (or (= input-file-api-type-id type)
      (contains? (set (flatten type)) input-file-api-type-id)))

(defn api-method-params->params-schema
  [*state params]
  (into {} (map #(api-method-param->param-schema *state %) params)))

(defn parse:api-method
  [*state {:keys [params] :as api-method}]
  (let [json-serialized-paths (get-json-serialized-paths params)]
    (cond-> api-method

            (some? params)
            (assoc :params-schema
                   (api-method-params->params-schema *state params))

            (some api-method-param-of-input-type? params)
            (assoc :uploads-file? true)

            (seq json-serialized-paths)
            (assoc :json-serialized-paths json-serialized-paths))))

;;; Types Parsing Order

(defn does-not-affect-order? [type]
  (or (basic-type? type) (= input-file-api-type-id type)))

(defn decontainerize [type]
  (if (vector? type)
    (recur (second type))
    type))

(defn api-type->type-with-deps
  [{:keys [id fields subtypes]}]
  (cond-> {:id id}

          (some? fields)
          (assoc :deps (->> fields
                            (map (comp decontainerize :type))
                            (remove does-not-affect-order?)))

          (some? subtypes)
          (assoc :deps subtypes)))

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
            (conj acc {:name (->field-name field)})
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
