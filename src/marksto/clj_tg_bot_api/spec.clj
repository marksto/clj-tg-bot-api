(ns marksto.clj-tg-bot-api.spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [martian.core :as m]
            [schema.core :as s]
            [schema-tools.coerce :as stc])
  (:import (java.io File InputStream)
           (java.net URI URL)
           (java.nio.file Path)))

;;; Utils

(defn index-by
  ([key-fn coll]
   (index-by {} key-fn coll))
  ([init-map key-fn coll]
   (reduce #(assoc %1 (key-fn %2) %2) init-map coll)))

;;; Bot API

;; TODO: Don't forget to provide an option to switch to a "local".
(def global-tg-bot-api-url "https://api.telegram.org/bot")

(defn get-tg-bot-api-url [bot-auth-token]
  (str global-tg-bot-api-url bot-auth-token))

(defn read-tg-bot-api-spec []
  (json/read-value
    (slurp (io/resource "tg-bot-api-spec.json"))
    (json/object-mapper {:decode-key-fn true})))

;;; Types

(def api-type-prefix "type/")

(def basic-type? #(not (str/starts-with? % api-type-prefix)))

(def input-file?
  ;; NB: Omitting a String case that is usually handled by HTTP clients,
  ;;     because the String has special semantics in the Bot API:
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
          (index-by (comp keyword :name) fields)
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

;;; Methods

(def api-method-prefix "method/")

(defn api-method-param->param-schema
  [{:keys [name type required]}]
  (let [param-key (cond-> (keyword name) (not required) (s/optional-key))
        param-val (type->schema type)]
    [param-key param-val]))

(defn api-method->handler
  [{:keys [id name params]}]
  (let [body-schema (when params
                      (into {} (map api-method-param->param-schema params)))]
    ;; TODO: Figure out how to set `:consumes ["multipart/form-data"]`
    ;;       or add an interceptor that transforms the request into a
    ;;       `{:multipart [{:name K :content V} ...]}` map, when this
    ;;       is absolutely necessary.
    (cond-> {:route-name (keyword (subs id (count api-method-prefix)))
             :path-parts [(str "/" name)]
             :method     (if (str/starts-with? name "get") :get :post)}
            (some? body-schema) (assoc :body-schema {:body body-schema}))))

;;; Martian

;; TODO: Change to an exposed Martian option after the PR #221 is merged.
(alter-var-root #'martian.schema/coercion-matchers
                (constantly stc/json-coercion-matcher))

(defn build-handlers [tg-bot-api-spec]
  (binding [*id->api-type* (index-by :id (:types tg-bot-api-spec))]
    (mapv api-method->handler (:methods tg-bot-api-spec))))

(defn build-martian
  [bot-auth-token]
  (let [tg-bot-api-spec (read-tg-bot-api-spec)]
    (m/bootstrap
      (get-tg-bot-api-url bot-auth-token)
      (build-handlers tg-bot-api-spec)
      {:produces ["application/json"]
       :consumes ["application/json"]})))
