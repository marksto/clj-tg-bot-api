(ns marksto.clj-tg-bot-api.updates.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as json]
   [marksto.clj-tg-bot-api.core :as tg-bot-api]
   [marksto.clj-tg-bot-api.updates.core :as sut]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test]))

;; NB: Each client serves a bot of its own, so that the long polling
;;     of one test never gets in the way of another one.
(def *last-bot-id (atom 1234567890))

(defn- ok-response
  [result]
  {:status 200
   :body   {:ok     true
            :result result}})

(defn- error-response
  [error-code description & {:as parameters}]
  {:status error-code
   :body   (cond-> {:ok          false
                    :error_code  error-code
                    :description description}
             (some? parameters) (assoc :parameters parameters))})

(def default-responses
  {:get-webhook-info (ok-response {:url                    ""
                                   :has_custom_certificate false
                                   :pending_update_count   0})
   :delete-webhook   (ok-response true)
   :set-webhook      (ok-response true)
   :get-updates      (ok-response [])})

(def parse-json
  #(json/read-value % json/keyword-keys-object-mapper))

(defn- ->test-client
  "Gives a client of a new bot, whose requests to a method get the `responses`
   to it, one by one, and then a successful one, after a short pause. Records
   each request in the `*requests` as a map of `:method`, `:params` (as sent)
   and `:at-ms`, a monotonic time."
  [responses]
  (let [*requests (atom [])
        save-req! (fn [method {:keys [body] :as _request}]
                    (swap! *requests conj
                           {:method method
                            :params (some-> body (parse-json))
                            :at-ms  (quot (System/nanoTime) 1000000)}))
        *responses (atom responses)
        get-resp! (fn [method]
                    (-> (swap-vals! *responses update method next)
                        (first)
                        (get method)
                        (first)))]
    {:*requests *requests
     :client    (tg-bot-api/->client
                  {:bot-token (format "%s:TEST_pxWA8lDi7uLc3oadqNivHCALHBQ7sM"
                                      (swap! *last-bot-id inc))
                   :responses (into {}
                                    (map (fn [[method default-response]]
                                           [method (fn [request]
                                                     (save-req! method request)
                                                     (or (get-resp! method)
                                                         (do (Thread/sleep 10)
                                                             default-response)))]))
                                    default-responses)})}))

;; LONG-POLLING

(defn- requests-to
  [*requests method]
  (filterv #(= method (:method %)) @*requests))

(defn- ->update-handler
  "Gives an update handler that answers with the `handle-fn`, and a promise
   of the first `n` updates it was called with. If no `handle-fn` was given
   it defaults to `(constantly false)`."
  ([n]
   (->update-handler n (constantly false)))
  ([n handle-fn]
   (let [*updates (atom [])
         handled (promise)]
     {:handler (fn [update]
                 (when (= n (count (swap! *updates conj update)))
                   (deliver handled @*updates))
                 (handle-fn update))
      :handled handled})))

(defn- with-long-polling
  "Calls `f` while a long polling with the `params` is set up for the `client`."
  [params client f]
  (sut/setup-long-polling! params client)
  (try
    (f)
    (finally
      (sut/teardown-long-polling! params client))))

(defn- long-polling-params
  "Gives the params of a short polling, with frequent status checks
   and no restarts, unless the `lp-cfg` says otherwise."
  [lp-cfg]
  ;; NB: The `:exit` defaults would end the test run itself.
  {:long-polling (merge {:get-updates-params  {:timeout 0}
                         :status-check-period 100
                         :restart-period      100
                         :restart-attempts    0
                         :on-lp-setup-failure (fn [_ex])
                         :on-no-more-attempts (fn [])}
                        lp-cfg)})

(declare test-webhook-info)

(deftest setup-long-polling!-test
  (testing "updates are handled one by one, in order, and get confirmed"
    (let [{:keys [*requests client]}
          (->test-client {:get-updates [(ok-response [{:update_id 1} {:update_id 2}])
                                        (ok-response [{:update_id 3}])]})
          {:keys [handler handled]} (->update-handler 3)]
      (with-long-polling
        (long-polling-params {:update-handler handler})
        client
        (fn []
          (is (= [{:update_id 1} {:update_id 2} {:update_id 3}]
                 (deref handled 5000 ::timeout)))
          (is (match? (m/prefix [{:params {:offset 0}}
                                 {:params {:offset 3}}])
                      (requests-to *requests :get-updates)))))))
  (testing "an update is handled again while the handler answers `true`"
    (let [{:keys [client]}
          (->test-client {:get-updates [(ok-response [{:update_id 1} {:update_id 2}])]})
          *calls (atom 0)
          {:keys [handler handled]}
          (->update-handler 4 #(and (= 1 (:update_id %))
                                    (< (swap! *calls inc) 3)))]
      (with-long-polling
        (long-polling-params {:update-handler handler})
        client
        (fn []
          (is (= [{:update_id 1} {:update_id 1} {:update_id 1} {:update_id 2}]
                 (deref handled 5000 ::timeout)))))))
  (testing "an update is skipped when the handler throws"
    (let [{:keys [client]}
          (->test-client {:get-updates [(ok-response [{:update_id 1} {:update_id 2}])]})
          {:keys [handler handled]}
          (->update-handler 2 #(when (= 1 (:update_id %))
                                 (throw (ex-info "Boom!" {}))))]
      (with-long-polling
        (long-polling-params {:update-handler handler})
        client
        (fn []
          (is (= [{:update_id 1} {:update_id 2}]
                 (deref handled 5000 ::timeout)))))))
  (testing "any other failure gets the long polling restarted"
    (let [{:keys [client]}
          (->test-client {:get-updates [(error-response 401 "Unauthorized")
                                        (ok-response [{:update_id 1}])]})
          {:keys [handler handled]} (->update-handler 1)]
      (with-long-polling
        (long-polling-params {:update-handler   handler
                              :restart-attempts 1})
        client
        (fn []
          (is (= [{:update_id 1}]
                 (deref handled 5000 ::timeout)))))))
  (testing "the `:on-no-more-attempts` is called once the restarts run out"
    (let [{:keys [*requests client]}
          (->test-client {:get-updates (repeat (error-response 401 "Unauthorized"))})
          no-more-attempts (promise)]
      (with-long-polling
        (long-polling-params {:restart-attempts    2
                              :on-no-more-attempts #(deliver no-more-attempts true)})
        client
        (fn []
          (is (true? (deref no-more-attempts 5000 ::timeout)))
          (is (= 3 (count (requests-to *requests :get-updates)))
              "The first attempt and 2 restarts")))))
  (testing "a webhook gets deleted beforehand and restored afterwards"
    (let [{:keys [*requests client]}
          (->test-client {:get-webhook-info [(ok-response test-webhook-info)]})
          params (long-polling-params {:restore-prev-webhook? true})]
      (is (= test-webhook-info (sut/setup-long-polling! params client)))
      (is (true? (sut/teardown-long-polling! params client test-webhook-info)))
      (is (nil? (sut/teardown-long-polling! params client test-webhook-info))
          "Nothing must be done once the long polling has been stopped")
      (is (match? [{:method :get-webhook-info}
                   {:method :delete-webhook
                    :params {:drop_pending_updates true}}
                   {:method :set-webhook
                    :params {:url                  (:url test-webhook-info)
                             :drop_pending_updates true}}]
                  (remove #(= :get-updates (:method %)) @*requests)))))
  (testing "a setup failure is handed to the `:on-lp-setup-failure`"
    (let [{:keys [client]}
          (->test-client {:get-webhook-info [(ok-response test-webhook-info)]
                          :delete-webhook   [(error-response 401 "Unauthorized")]})
          setup-failure (promise)
          params (long-polling-params {:on-lp-setup-failure #(deliver setup-failure %)})]
      (is (= test-webhook-info (sut/setup-long-polling! params client)))
      (is (instance? Exception (deref setup-failure 0 nil))))))

;; WEBHOOK

(def test-webhook-info
  {:url                    "https://example.com/webhook"
   :has_custom_certificate false
   :pending_update_count   0})

(def test-secret-token "s3cr3t-t0k3n")

(defn- webhook-params [wh-cfg]
  {:webhook (merge {:set-webhook-params {:url          (:url test-webhook-info)
                                         :secret-token test-secret-token}}
                   wh-cfg)})

(deftest setup-webhook!-test
  (testing "a webhook gets set up"
    (let [{:keys [*requests client]} (->test-client {})
          params (webhook-params {})]
      (is (true? (sut/setup-webhook! params client)))
      (is (match? [{:method :set-webhook
                    :params {:url          (:url test-webhook-info)
                             :secret_token test-secret-token}}]
                  @*requests))))
  (testing "a setup failure is handed to the `:on-wh-setup-failure`"
    (let [{:keys [client]}
          (->test-client {:set-webhook [(error-response 400 "Bad Request: bad webhook")]})
          setup-failure (promise)
          params (webhook-params {:on-wh-setup-failure #(deliver setup-failure %)})]
      (sut/setup-webhook! params client)
      (is (instance? Exception (deref setup-failure 0 nil))))))
