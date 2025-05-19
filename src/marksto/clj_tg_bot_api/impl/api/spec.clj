(ns marksto.clj-tg-bot-api.impl.api.spec
  (:require [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [schema.core :as s]

            [marksto.clj-tg-bot-api.impl.utils :as utils])
  (:import (java.io File InputStream)
           (java.net URI URL)
           (java.nio.file Path)))

;;; Types

(def api-type-prefix "type/")

(def basic-type? #(not (str/starts-with? % api-type-prefix)))

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

;;; Types > Schemas

(defn basic-type->schema
  [^String type]
  (case type
    "Boolean" s/Bool
    "True" True
    "String" s/Str
    "Integer" s/Int
    "Float" Floating
    s/Any))

;; NB: At the moment is as simple as this.
(def api-type-schema? map?)

(defn or-schema
  [schemas]
  ;; NB: Based on the `s/cond-pre` docstring, it is only suitable for schemas
  ;;     with mutually exclusive preconditions (e.g. `s/Int` and `s/Str`) and
  ;;     it won't work on map schemas, which is what all API types are. Thus,
  ;;     we need to make sure there's a max of 1 map schema for `s/cond-pre`.
  (if (< 1 (count (filter api-type-schema? schemas)))
    (apply s/either schemas)
    (apply s/cond-pre schemas)))

(def ^:dynamic *id->api-type* nil)

(def ^:dynamic *id->api-method-param-type* nil)

(defn- get-api-type
  [^String api-type-id]
  (let [api-type (get *id->api-type* api-type-id)]
    (swap! *id->api-method-param-type* assoc api-type-id api-type)
    api-type))

(declare type->schema)

;; TODO: Re-impl `s/either` (`schema.core.Either`) schemas. It doesn't coerce!
;;       Maybe use `schema-tools.core/schema-value` for sub-schemas retrieval.

(def ->field-name
  (comp keyword :name))

(defn ->type-pred
  [type-dependant-field subtype]
  (let [field-name (->field-name type-dependant-field)]
    (fn [obj]
      (= (some :value (:fields subtype))
         (or (get obj field-name)
             (get obj (csk/->kebab-case field-name)))))))

(defn api-supertype->schema
  [name api-subtype-ids]
  (let [subtype-schemas (map type->schema api-subtype-ids)
        subtypes (map get-api-type api-subtype-ids)
        type-dependant-field (some #(when (contains? % :value) %)
                                   (:fields (first subtypes)))]
    (s/named
      (if (some? type-dependant-field)
        (apply s/conditional
               (interleave (map #(->type-pred type-dependant-field %) subtypes)
                           subtype-schemas))
        (or-schema subtype-schemas))
      name)))

(defn api-type->schema
  [^String api-type-id]
  (let [{:keys [fields subtypes name]} (get-api-type api-type-id)]
    (cond
      (some? fields)
      (-> {}
          (utils/index-by ->field-name fields)
          (update-vals (comp type->schema :type))
          (s/named name))

      (some? subtypes)
      (api-supertype->schema name subtypes)

      :else
      (if (= "InputFile" name) InputFile s/Any))))

(def memo:api-type->schema (memoize api-type->schema))

;; "Boolean", "True", "String", "Integer", "Float"
;;
;; :some-api-type
;; :input-file (special case)
;;
;; [:or ["Integer" "String"]]
;; [:or [:input-file "String"]]
;; [:or [:some-api-type ...]]
;;
;; [:array "String"]
;; [:array :some-api-type]
;; [:array [:or [:some-api-type ...]]]
;; [:array [:array :some-api-type]]

(defn type->schema [type]
  (if (string? type)
    (if (basic-type? type)
      (basic-type->schema type)
      (memo:api-type->schema type))
    (do (when-not (vector? type)
          (throw (ex-info "Unsupported type" {:type type})))
        (let [[container-type inner-type] type]
          (case container-type
            "array" [(type->schema inner-type)]
            "or" (or-schema (map type->schema inner-type)))))))

;; TODO: Parse additional String type constraints:
;;       - "1-64 characters", "1-4096 characters", "0-1024 characters"
;;       - "up to 64 characters", "(up to 256 characters)"
;;       - "0-16 characters, emoji are not allowed"
;;       - "0-2048 characters after entities parsing"
;;       - "0-200 characters with at most 2 line feeds after entities parsing"

(defn parse:api-type
  [{api-type-id :id :as api-type}]
  (assoc api-type :schema (memo:api-type->schema api-type-id)))

;;; Methods

(def api-method-prefix "method/")

(defn api-method-param->param-schema
  [{:keys [name type required]}]
  (let [param-key (cond-> (keyword name) (not required) (s/optional-key))
        param-val (type->schema type)]
    [param-key param-val]))

(defn api-method-param-of-input-type?
  [{:keys [type]}]
  (or (= "type/input-file" type)
      (contains? (set (flatten type)) "type/input-file")))

(defn api-method-params->params-schema
  [params]
  (into {} (map api-method-param->param-schema params)))

(defn parse:api-method
  [{:keys [params] :as api-method}]
  (cond-> api-method

          (some? params)
          (assoc :params-schema (api-method-params->params-schema params))

          (some api-method-param-of-input-type? params)
          (assoc :uploads-file? true)))

;;; Parsing

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
      (let [{:keys [types methods]} raw-spec]
        (binding [*id->api-type* (utils/index-by :id types)
                  *id->api-method-param-type* (atom {})]
          (let [parsed-methods (mapv parse:api-method methods)
                used-types (mapv parse:api-type
                                 (vals @*id->api-method-param-type*))]
            {:methods    parsed-methods
             :used-types used-types})))
      (catch Exception e
        (log/errorf e "Failed to parse spec file '%s'" file-name)))))

(def *tg-bot-api-spec
  (delay (parse-tg-bot-api-spec! "tg-bot-api-spec.json")))

(defn get-tg-bot-api-spec []
  @*tg-bot-api-spec)

;;

(comment
  (do (defn get-required-fields
        [type]
        (filter :required (:fields type)))

      (defn fields-eq
        [field-1 field-2]
        (= (select-keys field-1 [:name :type])
           (select-keys field-2 [:name :type])))

      (defn select-common-fields
        [[first-sub & rest-sub :as _subtypes]]
        (for [field (get-required-fields first-sub)
              :let [eq-field? #(fields-eq field %)]
              :when (every? #(some eq-field? %)
                            (map get-required-fields rest-sub))]
          field))

      (defn set-distinct-fields
        [subtype common-fields]
        (let [distinct-fields (for [field (get-required-fields subtype)
                                    :let [eq-field? #(fields-eq field %)]
                                    :when (not (some eq-field? common-fields))]
                                field)]
          (assoc subtype :distinct-fields distinct-fields)))

      (defn set-subtype-fields
        [subtypes common-fields]
        (mapv #(set-distinct-fields % common-fields) subtypes))

      (defn select-type-dependant-field
        [subtypes]
        (let [first-fields (map (comp first :fields) subtypes)]
          (when (and (every? :required first-fields)
                     (apply = (map (juxt :name :type) first-fields)))
            (mapv #(assoc %1 :type-field %2) subtypes first-fields)))))

  (let [used-types (:used-types (get-tg-bot-api-spec))
        supertypes (filter :subtypes used-types)
        id->api-type (utils/index-by :id used-types)]
    (->> supertypes
         (map #(update % :subtypes (partial mapv id->api-type)))
         #_(take 1)
         (map (fn [{:keys [subtypes] :as supertype}]
                (let [common-fields (select-common-fields subtypes)]
                  (-> supertype
                      (assoc :common-fields common-fields)
                      (update :subtypes select-type-dependant-field)
                      (update :subtypes #(set-subtype-fields % common-fields))))))
         (filter (fn [{:keys [common-fields] :as supertype}]
                   ((some-fn empty? #(< 1 (count %))) common-fields)))
         #_(first)))

  :end/comment)
