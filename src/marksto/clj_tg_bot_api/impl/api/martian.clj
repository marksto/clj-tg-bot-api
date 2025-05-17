(ns marksto.clj-tg-bot-api.impl.api.martian
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [martian.core :as m]
            [martian.encoders :as me]
            [martian.encoding :as encoding]
            [martian.interceptors :as mi]
            [schema-tools.coerce :as stc]

            [marksto.clj-tg-bot-api.impl.api.spec :as api-spec]
            [marksto.clj-tg-bot-api.impl.utils :as utils]))

;;; Martian Patch

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

;;; Martian Builder

(defn api-method->handler
  [{:keys [id name description _params
           params-schema uploads-file?]}]
  (let [use-http-get? (and (not uploads-file?) (str/starts-with? name "get"))]
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
              {:body-schema {:body params-schema}})))))

(defn build-handlers []
  (mapv api-method->handler (api-spec/parse!)))

(def offline-bootstrap-fn m/bootstrap)

(def martian-bootstrap-fn
  ;; NB: Sorted by descending popularity in the global Clojure community.
  (or (utils/requiring-resolve* 'martian.httpkit/bootstrap)
      (utils/requiring-resolve* 'martian.clj-http/bootstrap)
      (utils/requiring-resolve* 'martian.hato/bootstrap)
      (utils/requiring-resolve* 'martian.babashka.http-client/bootstrap)
      (utils/requiring-resolve* 'martian.clj-http-lite/bootstrap)
      offline-bootstrap-fn))

(defn- offline-interceptors []
  (conj m/default-interceptors
        mi/default-encode-body
        mi/default-coerce-response))

(defn build-martian
  [tg-bot-api-root-url]
  (let [is-offline? (= offline-bootstrap-fn martian-bootstrap-fn)]
    (when is-offline?
      (log/warn (str "WARNING! You are in offline mode, meaning there is no "
                     "supported HTTP client available for sending requests. "
                     "Please, add any Martian library for JVM/Babashka HTTP "
                     "client to the classpath. For supported, check out the "
                     "https://github.com/oliyh/martian page.")))
    (when (= "#'martian.clj-http-lite/bootstrap" (str martian-bootstrap-fn))
      (log/warn (str "WARNING! You have picked up `clj-http-lite` which has "
                     "no support for 'multipart/form-data' requests used to "
                     "upload files, therefore this Telegram Bot API feature "
                     "won't be available for your bot.")))
    (martian-bootstrap-fn
      tg-bot-api-root-url
      (build-handlers)
      (cond-> {:coercion-matcher stc/json-coercion-matcher}
              is-offline? (assoc :interceptors (offline-interceptors))))))
