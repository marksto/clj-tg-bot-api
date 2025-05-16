(ns marksto.clj-tg-bot-api.impl.api.spec
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [jsonista.core :as json]
            [martian.core :as m]
            [martian.encoders :as me]
            [martian.encoding :as encoding]
            [martian.interceptors :as mi]
            [schema.core :as s]
            [schema-tools.coerce :as stc]

            [marksto.clj-tg-bot-api.impl.utils :as utils])
  (:import (java.io File InputStream)
           (java.net URI URL)
           (java.nio.file Path)))

(def read-tg-bot-api-spec
  (delay (json/read-value
           (slurp (io/resource "tg-bot-api-spec.json"))
           (json/object-mapper {:decode-key-fn true}))))

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

(defn api-method->handler
  [{:keys [id name description params]}]
  (let [params-schema (when params
                        (into {} (map api-method-param->param-schema params)))
        uploads-file? (some api-method-param-of-input-type? params)
        use-http-get? (and (not uploads-file?) (str/starts-with? name "get"))]
    (conj {:route-name (keyword (subs id (count api-method-prefix)))
           :path-parts [(str "/" name)]
           :method     (if use-http-get? :get :post)
           :summary    description
           :consumes   (if uploads-file?
                         ["multipart/form-data"]
                         ["application/json"])}
          (when params-schema
            (if use-http-get?
              {:query-schema params-schema}
              {:body-schema {:body params-schema}})))))

;;; Martian

;; NB: W/o this function produces a request map of a wrong shape.
;; {:body {:multipart [{:name "chat_id", :content 1} ...]}, ...}
;; TODO: Fix/improve this upstream, in the `martian` codebase?
(defn encode-request [encoders]
  {:name    ::encode-request
   :encodes (keys encoders)
   :enter   (fn [{:keys [request handler] :as ctx}]
              (let [has-body? (get-in ctx [:request :body])
                    content-type (and has-body?
                                      (not (get-in request [:headers "Content-Type"]))
                                      (encoding/choose-content-type encoders (:consumes handler)))
                    multipart? (= "multipart/form-data" content-type)
                    {:keys [encode]} (encoding/find-encoder encoders content-type)]
                (cond-> ctx
                        has-body? (update-in [:request :body] encode)
                        ;; NB: Luckily, all target HTTP clients — clj-http (but not lite), http-kit,
                        ;;     hato, and even babashka/http-client — all support the same syntax.
                        multipart? (update :request set/rename-keys {:body :multipart})
                        content-type (assoc-in [:request :headers "Content-Type"] content-type))))})

(defn multipart-encode [body]
  (mapv (fn [[k v]] {:name (name k) :content v}) body))

(def encoders (assoc (me/default-encoders)
                "multipart/form-data" {:encode multipart-encode
                                       :as     :multipart}))

(alter-var-root #'mi/default-encode-body
                (constantly (encode-request encoders)))

(defn build-handlers [tg-bot-api-spec]
  (binding [*id->api-type* (utils/index-by :id (:types tg-bot-api-spec))]
    (mapv api-method->handler (:methods tg-bot-api-spec))))

;; TODO: Make an actual HTTP client pluggable via dynaload and/or multi-method.
(defn build-martian
  [tg-bot-api-root-url]
  (m/bootstrap
    tg-bot-api-root-url
    (build-handlers @read-tg-bot-api-spec)
    {:produces         ["application/json"]
     :coercion-matcher stc/json-coercion-matcher
     :interceptors     (into m/default-interceptors
                             [mi/default-encode-body
                              mi/default-coerce-response])}))
