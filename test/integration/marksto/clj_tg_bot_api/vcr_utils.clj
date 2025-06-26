(ns marksto.clj-tg-bot-api.vcr-utils
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [fipp.clojure :as fipp]
            [matcher-combinators.test]
            [marksto.clj-tg-bot-api.impl.utils :as utils]
            [martian.vcr :as vcr])
  (:import [clojure.lang Reflector]))

(def token->bot-id #(subs % 0 (str/index-of % ":")))

(def real-bot-token (System/getenv "BOT_AUTH_TOKEN"))
(def real-bot-id (token->bot-id real-bot-token))

(def fake-bot-token "1234567890:TEST_pxWA8lDi7uLc3oadqNivHCALHBQ7sM")
(def fake-bot-id (token->bot-id fake-bot-token))

(defn re-str-for [field]
  (str "\\\\\\\"" field "\\\\\\\":\\\\\\\"[^\\\\]+\\\\\\\""))

(defn replacement-for [field value]
  (str "\\\\\\\"" field "\\\\\\\":\\\\\\\"" value "\\\\\\\""))

(def field+fake-value-seq
  [["first_name" "Testy"]
   ["username" "test_username"]])

(def match+replacement-seq
  (into [[real-bot-token fake-bot-token]
         [real-bot-id fake-bot-id]]
        (reduce
          (fn [acc [field fake-value]]
            (conj acc [(re-pattern (re-str-for field))
                       (replacement-for field fake-value)]))
          []
          field+fake-value-seq)))

(defn persist:post-process [resp-str]
  (reduce (fn [acc-str [match replacement]]
            (str/replace acc-str match replacement))
          resp-str
          match+replacement-seq))

(defn stringify [obj pprint?]
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

(defn persist:pre-process [response pprint?]
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

(defmethod vcr/persist-response! :prepared-file
  [{{:keys [pprint?]} :store :as opts} {:keys [response] :as ctx}]
  (let [response (persist:pre-process response pprint?)
        resp-str (stringify response pprint?)
        content (persist:post-process resp-str)
        file (#'vcr/response-file opts ctx)]
    (io/make-parents file)
    (spit file content)))

;;

(defn invoke-ctor [classy & args]
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

(defn load:post-process [response]
  (walk/prewalk (fn [form]
                  (if (and (map? form) (:ex/class-name form))
                    (map->exception form)
                    form))
                response))

(defmethod vcr/load-response :prepared-file
  [opts ctx]
  (let [response ((get-method vcr/load-response :file) opts ctx)]
    (load:post-process response)))
