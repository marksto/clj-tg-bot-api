(ns marksto.clj-tg-bot-api.impl.client.testing
  (:require [martian.test :as martian-test]))

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
