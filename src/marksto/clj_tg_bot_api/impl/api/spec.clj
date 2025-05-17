(ns marksto.clj-tg-bot-api.impl.api.spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

(def ^:dynamic *id->api-type* nil)

(declare type->schema)

(defn api-type->schema
  [^String type]
  (let [{:keys [fields subtypes name]} (get *id->api-type* type)]
    (cond
      (some? fields)
      (-> {}
          (utils/index-by (comp keyword :name) fields)
          (update-vals (comp type->schema :type)))

      (some? subtypes)
      (apply s/either (map type->schema subtypes))

      (= "InputFile" name) InputFile :else s/Any)))

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
            "or" (apply s/either (map type->schema inner-type)))))))

;; TODO: Parse additional String type constraints:
;;       - "1-64 characters", "1-4096 characters", "0-1024 characters"
;;       - "up to 64 characters", "(up to 256 characters)"
;;       - "0-16 characters, emoji are not allowed"
;;       - "0-2048 characters after entities parsing"
;;       - "0-200 characters with at most 2 line feeds after entities parsing"

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

;;; Parsing

(def *tg-bot-api-spec
  (delay (json/read-value
           (slurp (io/resource "tg-bot-api-spec.json"))
           (json/object-mapper {:decode-key-fn true}))))

(defn parse! []
  (let [{:keys [types methods]} @*tg-bot-api-spec]
    (binding [*id->api-type* (utils/index-by :id types)]
      methods)))
