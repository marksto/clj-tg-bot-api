(ns marksto.clj-tg-bot-api.impl.client.testing
  (:require [martian.test :as martian-test]
            [taoensso.truss :refer [have!]]))

;; TODO: Introduce this new feature upstream, to the `martian-test` codebase?

(defn dynamic-responses [response-fn]
  {:name  ::dynamic-responses
   :leave (fn [ctx]
            (assoc ctx :response (response-fn ctx)))})

(defn respond-with-dynamic
  "Adds an interceptor that simulates the server responding with the supplied
   responses, retrieved dynamically via a given unary `response-fn` of `ctx`.

   The response may be a function that, given the request, returns a response,
   or just a response value.

   Removes all interceptors that would perform real HTTP operations."
  [martian response-fn]
  (-> (#'martian-test/replace-http-interceptors martian)
      (update :interceptors conj (dynamic-responses response-fn))))

(defn respond-with
  [martian response-map-or-fn]
  (have! [:or map? fn?] response-map-or-fn)
  (cond-> martian

          (map? response-map-or-fn)
          (martian-test/respond-with-constant response-map-or-fn)

          (fn? response-map-or-fn)
          (respond-with-dynamic response-map-or-fn)))
