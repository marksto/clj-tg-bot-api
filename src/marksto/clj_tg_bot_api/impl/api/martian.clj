(ns marksto.clj-tg-bot-api.impl.api.martian
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [martian.core :as m]
            [martian.encoders :as me]
            [martian.interceptors :as mi]
            [schema-tools.coerce :as stc]

            [marksto.clj-tg-bot-api.impl.api.spec :as api-spec]
            [marksto.clj-tg-bot-api.impl.utils :as utils]))

;;; Martian Interceptors

(def params-mapper
  (json/object-mapper {:encode-key-fn csk/->snake_case_string}))

(defn- json-serialize-param
  [param]
  (json/write-value-as-string param params-mapper))

(defn- json-serialize-params
  [params paths]
  (reduce
    (fn [params path]
      (utils/update-in* params path json-serialize-param))
    params
    paths))

(def json-serialize-params-interceptor
  {:name  ::json-serialize-params
   :enter (fn [{:keys [handler] :as ctx}]
            (if-some [paths (:json-serialized-paths handler)]
              (update-in ctx [:request :body] #(json-serialize-params % paths))
              ctx))})

;;

(defn- get-url-path [handler]
  (subs (first (:path-parts handler)) 1))

(def inject-method-param-interceptor
  {:name  ::inject-method-param-interceptor
   :enter (fn [{:keys [tripod.context/queue handler] :as ctx}]
            (if (some #(= ::mi/request-only-handler (:name %)) queue)
              (assoc-in ctx [:request :body :method] (get-url-path handler))
              ctx))})

;;; Martian Builder

(defn api-method->handler
  [{:keys [id name description _params
           params-schema uploads-file? json-serialized-paths]}]
  ;; NB: This breaks the existing end bots test infrastructure since it ends up
  ;;     with string values of a query string params — disable HTTP GET for now.
  (let [use-http-get? #_(and (not uploads-file?) (str/starts-with? name "get")) false]
    (conj {:route-name (keyword (subs id (count api-spec/api-method-prefix)))
           :path-parts [(str "/" name)]
           :method     (if use-http-get? :get :post)
           :summary    description
           :consumes   (if uploads-file?
                         ["multipart/form-data"]
                         ["application/json"])
           :produces   ["application/json"]}
          (when params-schema
            (if use-http-get?
              {:query-schema params-schema}
              {:body-schema {:body params-schema}}))
          (when json-serialized-paths
            {:json-serialized-paths json-serialized-paths}))))

(defn build-handlers []
  (let [tg-bot-api-spec (api-spec/get-tg-bot-api-spec)]
    (mapv api-method->handler (:methods tg-bot-api-spec))))

(def offline-bootstrap-fn m/bootstrap)

(def martian-bootstrap-fn
  ;; NB: Sorted by descending popularity in the global Clojure community.
  (or (utils/requiring-resolve* 'martian.httpkit/bootstrap)
      (utils/requiring-resolve* 'martian.clj-http/bootstrap)
      (utils/requiring-resolve* 'martian.hato/bootstrap)
      (utils/requiring-resolve* 'martian.clj-http-lite/bootstrap)
      offline-bootstrap-fn))

(def offline-interceptors
  (delay (let [encoders (assoc (me/default-encoders)
                          "multipart/form-data" {:encode me/multipart-encode
                                                 :as     :multipart})]
           (conj m/default-interceptors
                 (mi/encode-request encoders)
                 mi/default-coerce-response))))

(def martian-default-interceptors
  ;; NB: Sorted by descending popularity in the global Clojure community.
  (or (utils/requiring-resolve* 'martian.httpkit/default-interceptors)
      (utils/requiring-resolve* 'martian.clj-http/default-interceptors)
      (utils/requiring-resolve* 'martian.hato/default-interceptors)
      (utils/requiring-resolve* 'martian.clj-http-lite/default-interceptors)
      offline-interceptors))

(defn get-perform-request-interceptor []
  (some #(when (= "perform-request" (name (:name %))) %)
        @martian-default-interceptors))

(defn build-martian
  [tg-bot-api-root-url interceptors]
  (when (= offline-bootstrap-fn martian-bootstrap-fn)
    (log/warn (str "You are in \"offline mode\", which means there is no "
                   "supported HTTP client available for sending requests. "
                   "Please, add any Martian library for JVM/Babashka HTTP "
                   "client to the classpath. For supported, check out the "
                   "https://github.com/oliyh/martian page.")))
  (when (= "#'martian.clj-http-lite/bootstrap" (str martian-bootstrap-fn))
    (log/warn (str "You have selected `clj-http-lite`, which has no support "
                   "for \"multipart/form-data\" requests used for uploading "
                   "files, therefore this Telegram Bot API feature will not "
                   "be available to your bot.")))
  (let [target-default-int ::mi/enqueue-route-specific-interceptors
        basic-interceptors (-> @martian-default-interceptors
                               (mi/inject inject-method-param-interceptor
                                          :before target-default-int)
                               (mi/inject json-serialize-params-interceptor
                                          :before target-default-int))
        final-interceptors (reduce (fn [ints [new rel-pos old-name]]
                                     (mi/inject ints new rel-pos old-name))
                                   basic-interceptors
                                   interceptors)]
    (martian-bootstrap-fn
      tg-bot-api-root-url
      (build-handlers)
      {:coercion-matcher stc/json-coercion-matcher
       :interceptors     final-interceptors})))
