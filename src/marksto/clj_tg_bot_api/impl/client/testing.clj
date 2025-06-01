(ns marksto.clj-tg-bot-api.impl.client.testing
  (:require [martian.test :as martian-test]))

;; TODO: Improve/introduce this upstream, in the `martian-test` codebase?
(defn constant-responses [response-fn]
  {:name  ::constant-responses
   :leave (fn [{request :request {:keys [route-name]} :handler :as ctx}]
            (let [responder (get (response-fn) route-name)
                  response (if (fn? responder) (responder request) responder)]
              (assoc ctx :response response)))})

(defn respond-with
  "Adds an interceptor that simulates the server responding with the supplied
   responses, retrieved dynamically via the provided no-arg `response-fn`.

   The response may be a function that, given the request, returns a response,
   or just a response value.

   Removes all interceptors that would perform real HTTP operations."
  [martian response-fn]
  (-> (#'martian-test/replace-http-interceptors martian)
      (update :interceptors conj (constant-responses response-fn))))
