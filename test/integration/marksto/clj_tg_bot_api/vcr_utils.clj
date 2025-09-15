(ns marksto.clj-tg-bot-api.vcr-utils
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [fipp.clojure :as fipp] ; comes with Martian VCR
   [marksto.clj-tg-bot-api.impl.utils :as utils]
   [martian.vcr :as vcr]
   [matcher-combinators.test])
  (:import
   (clojure.lang Reflector)))

;;; Persisting

#_{:clj-kondo/ignore [:missing-docstring]}
(defonce ^:dynamic *replace-args* [])

;; NB: The number of backslashes is for after the JSON is stringified:
;; JSON str == "{...,\"field\":\"value\",...}"
;; resp-str => "{...,\\\"field\\\":\\\"value\\\",...}"
;; regexp   => "{...,\\\\\\\"field\\\\\\\":\\\\\\\"value\\\\\\\",...}"

(defn- re-str-for [field]
  (str "\\\\\\\"" field "\\\\\\\":\\\\\\\"[^\\\\]+\\\\\\\""))

(defn- replacement-for [field value]
  (str "\\\\\\\"" field "\\\\\\\":\\\\\\\"" value "\\\\\\\""))

#_{:clj-kondo/ignore [:missing-docstring]}
(defn prepare-replace-args [replacements]
  (reduce
    (fn [acc [type match replacement]]
      (conj acc
            (case type
              :string-val [match replacement]
              :json-field [(re-pattern (re-str-for match))
                           (replacement-for match replacement)])))
    []
    replacements))

(defmacro with-replacements
  "Executes `body` with the provided `replacements` in the VCR-recorded files.

   Replacements are vectors of one of the following forms:
   - `[:string-val <match-str> <replacement-str>]`
     Used for literal global replacements of the recorded file text contents.
   - `[:json-field <field-match-str> <value-replacement-str>]`
     Used for surgical replacements of JSON fields' values, e.g. in response.

   Returns whatever the `body` returns."
  [replacements & body]
  `(binding [*replace-args* (into *replace-args*
                                  (prepare-replace-args ~replacements))]
     (do ~@body)))

(defn- persist:post-process [resp-str]
  (reduce (fn [acc-str [match replacement]]
            (str/replace acc-str match replacement))
          resp-str
          *replace-args*))

;;

(defn- stringify [obj pprint?]
  (if pprint?
    (with-out-str (fipp/pprint obj))
    (pr-str obj)))

(defn raw-object?
  "Qualifies all unreadable object entries that may also leak secrets,
   like e.g. a built-in `jdk.internal.net.http.HttpRequestImpl` does."
  [val pprint?]
  (str/starts-with? (stringify val pprint?) "#object"))

(defn exception->map
  "Recursively converts any `Throwable` into a plain map suitable for EDN."
  [^Throwable ex]
  ;; NB: There is no value in serializing the stack trace elements.
  (let [ex-data (ex-data ex)
        message (ex-message ex)
        cause (some-> (ex-cause ex) (exception->map))]
    (cond-> {:ex/class-name (Class/.getName (class ex))}
            ex-data (assoc :ex/data ex-data)
            message (assoc :ex/message message)
            cause (assoc :ex/cause cause))))

(defn- persist:pre-process [response pprint?]
  (->> response
       ;; NB: First, prepare the top-level or nested (`:error`) exceptions.
       (walk/postwalk (fn [form]
                        (if (instance? Throwable form)
                          (exception->map form)
                          form)))
       ;; NB: Only then safely get rid of all potential '#object' literals.
       (walk/postwalk (fn [form]
                        (if (map? form)
                          (utils/remove-vals form #(raw-object? % pprint?))
                          form)))))

;;

(defmethod vcr/persist-response! :prepared-file
  [{{:keys [pprint?]} :store :as opts} {:keys [response] :as ctx}]
  (let [response (persist:pre-process response pprint?)
        resp-str (stringify response pprint?)
        content (persist:post-process resp-str)
        file (#'vcr/response-file opts ctx)]
    (io/make-parents file)
    (spit file content)))

;;; Loading

#_{:splint/disable [lint/catch-throwable]}
(defn- invoke-ctor [classy & args]
  (try
    (Reflector/invokeConstructor classy (to-array args))
    (catch Throwable _ nil)))

(defn map->exception
  "Recursively converts any previously EDN'ized instance of `Throwable`."
  [{:ex/keys [class-name message data cause]}]
  (let [cause-ex (when cause (map->exception cause))]
    (if (= "clojure.lang.ExceptionInfo" class-name)
      (ex-info message data cause-ex)
      (let [classy (Class/forName class-name)
            ex (or (invoke-ctor classy message)
                   (invoke-ctor classy)
                   (throw (ex-info (str "Cannot instantiate " class-name)
                                   {:class-name class-name})))]
        (when cause-ex
          (Throwable/.initCause ex cause-ex))
        ex))))

(defn- load:post-process [response]
  (walk/prewalk (fn [form]
                  (if (and (map? form) (:ex/class-name form))
                    (map->exception form)
                    form))
                response))

(defmethod vcr/load-response :prepared-file
  [opts ctx]
  (let [response ((get-method vcr/load-response :file) opts ctx)]
    (load:post-process response)))
