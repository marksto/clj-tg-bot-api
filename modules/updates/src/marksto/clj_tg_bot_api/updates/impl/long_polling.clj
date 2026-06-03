;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.updates.impl.long-polling
  {:author "Mark Sto (@marksto)"}
  (:require
   [clojure.tools.logging :as log]
   [marksto.clj-tg-bot-api.updates.impl.common :as common]
   [marksto.clj-tg-bot-api.updates.impl.queries :as queries]
   [marksto.clj-tg-bot-api.updates.impl.utils :as utils]
   [marksto.clj-tg-bot-api.updates.impl.webhook :as webhook])
  (:import
   (java.util LinkedList)
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

;; - configuration

(def ^:private default-timeout
  "A timeout in seconds for long polling.

   That way Telegram will keep the connection open and respond immediately when
   there is an update, while waiting for so many seconds before answering if no
   updates are present.

   If omitted, defaults to `0`, i.e. \"short polling\", that should be used for
   testing purposes only."
  10)

(def ^:private default-get-updates-params
  {:timeout default-timeout
   :offset  0
   :limit   100})

(def ^:private default-lp-cfg
  {;; start-up
   :on-lp-setup-failure   :exit

   ;; producing updates
   :get-updates-params    default-get-updates-params

   ;; consuming updates
   :update-handler        (fn [_update] false) #_no-op
   :updates-poll-timeout  100

   ;; webhook-related
   :drop-pending-updates? true
   :restore-prev-webhook? false

   ;; status checker
   :status-check-period   20000
   :restart-period        10000
   :restart-attempts      5
   :on-no-more-attempts   :exit})

(defn- complete-lp-cfg [{:keys [long-polling] :as _params}]
  (utils/nested-merge default-lp-cfg long-polling))

(defn- ->timeout-ms [get-updates-params]
  (* 1000 (get get-updates-params :timeout 0)))

;; - updates retrieval

(defn- put-updates
  [^LinkedBlockingQueue queue updates]
  (doseq [update updates]
    (LinkedBlockingQueue/.put queue update)))

(defn- poll-updates!!
  [^LinkedBlockingQueue queue timeout-ms]
  ;; NB: This blocking head poll with a timeout prevents a busy-wait loop that
  ;;     would result in excessive CPU load.
  (let [fst (LinkedBlockingQueue/.poll queue timeout-ms TimeUnit/MILLISECONDS)]
    (when (some? fst)
      (let [buf (LinkedList.)]
        (LinkedList/.add buf fst)
        (LinkedBlockingQueue/.drainTo queue buf)
        (seq buf)))))

(defn- get-new-offset
  "Returns a new offset which identifies the first update to be returned."
  [updates curr-offset]
  (if (seq updates)
    (-> updates last :update_id inc)
    curr-offset))

(defn create-producer!
  "Creates an incoming updates producer w/ the specified `local-state` using
   the provided `lp-cfg` to tune up the updates retrieval process.

   - If an end user stops the long-polling process, the `:stop-polling-flag`
     of the `local-state` will receive a `true` value.

   - In case of the 'getUpdates' Telegram Bot API method request failure, we
     need to signal about this failure using the `:poll-failure-flag` of the
     `local-state`, so to automatically restart the long-polling process.
     (There is a dedicated Status Checker subprocess running in parallel for
     this specific sake).

   Returns a queue with the received update objects."
  [{:keys [poll-failure-flag stop-polling-flag uuid] :as _local-state}
   {:keys [get-updates-params] :as _lp-cfg}
   client]
  (log/tracef "Creating a producer subprocess (uuid=%s)" uuid)
  (let [update-queue (LinkedBlockingQueue.)]
    (utils/->vthread!
      (fn []
        (loop [offset (:offset get-updates-params)
               failed? false]
          (let [params (assoc get-updates-params :offset offset)]
            (if-some [res (when-not (or @stop-polling-flag failed?)
                            (queries/get-updates! client params))]
              (if (= ::queries/failure res)
                ;; failure: signal for the long-polling restart
                (do (log/tracef "A request has failed (uuid=%s)" uuid)
                    (poll-failure-flag true)
                    (recur offset true))
                ;; got API response: enqueue updates and bump the offset
                (do (log/tracef "API response (uuid=%s): %s" uuid res)
                    (let [updates (:result res)]
                      (put-updates update-queue updates)
                      (recur (get-new-offset updates offset) false))))
              (log/tracef "The producer subprocess was stopped (uuid=%s)" uuid)))))
      :name "updates-producer")
    update-queue))

(defn create-consumer!
  "Creates an incoming updates consumer with the specified `update-queue` and
   `local-state` using the provided `lp-cfg` to tune up the updates handling.

   NB: Calls a unary `update-handler` fn on each queued update, sequentially,
       in a synchronous blocking way."
  [^LinkedBlockingQueue update-queue
   {:keys [stop-polling-flag uuid] :as _local-state}
   {:keys [update-handler updates-poll-timeout] :as _lp-cfg}]
  (log/tracef "Creating a consumer subprocess (uuid=%s)" uuid)
  (utils/->vthread!
    (fn []
      (loop [updates nil]
        (if (and @stop-polling-flag (nil? updates))
          (log/tracef "The consumer subprocess was stopped (uuid=%s)" uuid)
          (recur
            (if-some [update (first updates)]
              (if (try
                    ;; NB: May return `true`, which means we need to repeat
                    ;;     handling the same update. It is up to the client
                    ;;     code to eventually return `false`, otherwise the
                    ;;     loop will run indefinitely.
                    (update-handler update)
                    (catch Throwable t
                      ;; NB: Normally, we should not get here, but we would
                      ;;     like to insulate the outer `loop` from errors.
                      (log/errorf t "Unexpected error during update: %s" update)
                      false))
                updates
                (next updates))
              (poll-updates!! update-queue updates-poll-timeout))))))
    :name "updates-consumer"))

;; - (re)store webhook

(defn- retrieve-current-webhook-info
  "Gets the current webhook info and, if there's a preconfigured webhook,
   saves one to be able, if possible, to restore it later."
  [client]
  (let [wh-info (queries/get-webhook-info! client)]
    ;; NB: If the bot uses long-polling (getUpdates) to receive incoming
    ;;     updates, the 'url' field of the returned object will be empty.
    (when (seq (:url wh-info))
      (log/debugf "Retrieved the current webhook info: %s"
                  (webhook/mask-webhook-tokens wh-info))
      wh-info)))

(defn- try-restoring-the-prev-webhook
  "Attempts to restore the previous webhook for the given bot. Accounts for
   the `drop-pending-updates?` flag.

   To be done correctly, restoring the previous webhook requires extra data,
   specifically the webhook's 'certificate' and 'secure_token', that can only
   come out of band, i.e. specified by the bot developer in advance. So, if
   this is the case, these webhook params have to be provided in a usual way,
   as part of the `:set-webhook-params` under the `:webhook` key in `:params`
   map. Otherwise, just reset a webhook manually, e.g. via curl, later on.

   Returns `true` if succeeded, `nil` otherwise. Does not throw."
  [params client prev-webhook-info drop-pending-updates?]
  (when (some? prev-webhook-info)
    (try
      (let [;; NB: We override the config value to keep symmetry in delete and
            ;;     set webhook calls, since the reason behind dropping pending
            ;;     updates just before and after the current updates retrieval
            ;;     session mustn't change.
            complete-wh-cfg (-> (webhook/complete-wh-cfg params)
                                (assoc-in [:set-webhook-params :drop_pending_updates]
                                          drop-pending-updates?)
                                (assoc-in [:set-webhook-params :url]
                                          (:url prev-webhook-info)))
            certificate (when (:has_custom_certificate prev-webhook-info)
                          (or (webhook/get-webhook-certificate complete-wh-cfg)
                              (throw (IllegalArgumentException.
                                       "Missing webhook certificate (PEM file)"))))
            ;; NB: Careful! Multipart request can only handle params of certain
            ;;     types, e.g. files, bytes, and strings. Plus, we need to drop
            ;;     a `nil` URL and an unnecessary certificate from the config.
            wh-params (cond-> (-> prev-webhook-info
                                  (merge (:set-webhook-params complete-wh-cfg))
                                  (dissoc :certificate))
                        certificate (assoc :certificate certificate))]
        (log/infof "Restoring the webhook from the info: %s"
                   (webhook/mask-webhook-tokens wh-params))
        (queries/set-webhook! client wh-params))
      (catch Throwable t
        (log/warn t "Failed to restore the webhook! Please, do it manually.")))))

;; - setup -> teardown

(defn- start-long-polling!
  [lp-cfg client]
  (log/info "Starting Telegram polling...")
  (let [poll-failure-flag (utils/flag)
        stop-polling-flag (utils/flag)
        local-state {:uuid              (str (random-uuid))
                     :poll-failure-flag poll-failure-flag
                     :stop-polling-flag stop-polling-flag}]
    (common/set-bot-local-state! (:bot-id client) {:long-polling local-state})
    (let [update-queue (create-producer! local-state lp-cfg client)]
      (create-consumer! update-queue local-state lp-cfg)
      (log/info "Telegram polling started.")
      local-state)))

(defn- stop-long-polling!
  [{:keys [get-updates-params] :as _lp-cfg} client]
  (log/info "Stopping Telegram polling...")
  (let [old-local-state (common/drop-bot-local-state! (:bot-id client))
        {{:keys [stop-polling-flag]} :long-polling} old-local-state
        should-stop? (some? stop-polling-flag)]
    (if should-stop?
      (do (stop-polling-flag true)
          ;; NB: Waits for an ongoing 'getUpdates' request to catch up to avoid
          ;;     an HTTP 409 "Conflict: terminated by setWebhook request" error
          ;;     after the previous webhook is successfully restored.
          (utils/sleep-uninterruptibly (->timeout-ms get-updates-params))
          (log/info "Telegram polling was stopped."))
      (log/info "Telegram polling has already been stopped."))
    should-stop?))

(defn- restart-long-polling!
  "Stops and then immediately starts a long-polling for the bot.
   Never throws, but returns `nil` instead (see `not-polling?`)."
  [lp-cfg client]
  (try
    (stop-long-polling! lp-cfg client)
    (start-long-polling! lp-cfg client)
    (catch Exception _ nil)))

(defn- not-polling?
  [poll-failure-flag]
  (or (nil? poll-failure-flag) ; restart failed
      @poll-failure-flag)) ; polling has failed

(defn- run-long-polling-status-checker!
  "An async Status Checker task that aims to restart a \"dead\" long-polling
   process. Runs periodically, waiting for quite a long time (by default, 20
   seconds) between successive runs.

   Takes the given `:poll-failure-flag` and uses it to determine the current
   polling status. If it happened to be closed from the outside of this task
   or wasn't created at all, attempts to restart.

   Number of restarts is limited with the `:restart-attempts` config option.
   If all restart attempts are exhausted, calls a `:on-no-more-attempts` fn,
   which results in a `System/exit` by default.

   Takes the given `:stop-polling-flag` and uses it for a signal to stop the
   current long-polling process, which is normally done by an end user, then
   also completes itself."
  [init-local-state
   {:keys [status-check-period restart-attempts restart-period] :as lp-cfg}
   client]
  (log/trace "Running a status checker subprocess...")
  (utils/->vthread!
    (fn []
      (loop [wait-ms status-check-period
             countdown restart-attempts
             local-state init-local-state]
        (log/tracef "Waiting for %s sec..." (/ wait-ms 1000))
        (utils/sleep-uninterruptibly wait-ms)

        ;; NB: Here we may receive a new `local-state` after a restart,
        ;;     so the same Status Checker will now listen to and serve
        ;;     another set of flags and producer/consumer subprocesses.
        (if @(:stop-polling-flag local-state)
          (log/trace "The status checker subprocess was stopped")
          (if (not-polling? (:poll-failure-flag local-state))
            (do
              (log/trace "Checking the status... NOT POLLING!")
              (if (zero? countdown)
                (do
                  (log/fatalf "Long-polling restart attempts exhausted")
                  (common/call-configured-fn! lp-cfg :on-no-more-attempts))
                (do
                  (log/tracef "Trying to restart polling (#%d of %d)"
                              (- (inc restart-attempts) countdown)
                              restart-attempts)
                  (let [new-local-state (restart-long-polling! lp-cfg client)]
                    (recur restart-period (dec countdown) new-local-state)))))

            ;; long-polling goes on and there's no need to stop it
            (do (log/trace "Checking the status... polling [OK]")
                ;; NB: Reset the parameters to their initial values.
                (recur status-check-period restart-attempts local-state))))))
    :name "status-checker"))

;; - contract fns

(defn setup-long-polling!
  [params client]
  ;; NB: Don't let start another long-polling process if there is one in place.
  ;;     This safeguards the bot from receiving the following Bot API response:
  ;;     409 Conflict: terminated by other getUpdates request;
  ;;                   make sure that only one bot instance is running
  (when-not (common/has-long-polling-state? (:bot-id client))
    (let [{:keys [drop-pending-updates?] :as lp-cfg} (complete-lp-cfg params)
          *webhook-info (volatile! nil)]
      ;; NB: The long-polling won't work if a webhook is set up! So, first, get
      ;;     the current webhook info and, if there's any, save one to be able
      ;;     to restore it later. When no webhook is preconfigured, do nothing.
      ;;     In case of a setup failure, it returns the webhook info, if any.
      (try
        (vreset! *webhook-info (retrieve-current-webhook-info client))
        (when (some? @*webhook-info)
          ;; NB: This Bot API call will throw an exception on failure or error.
          (queries/delete-webhook! client {:drop-pending-updates drop-pending-updates?}))
        (let [local-state (start-long-polling! lp-cfg client)]
          (run-long-polling-status-checker! local-state lp-cfg client))
        (catch Throwable t
          (log/error t "Exception during the long-polling setup process")
          (common/call-configured-fn! lp-cfg :on-lp-setup-failure t)))
      @*webhook-info)))

(defn teardown-long-polling!
  [params client webhook-info]
  (let [lp-cfg (complete-lp-cfg params)
        long-polling-stopped? (stop-long-polling! lp-cfg client)]
    (when (and long-polling-stopped? (:restore-prev-webhook? lp-cfg))
      (try-restoring-the-prev-webhook
        params client webhook-info (:drop-pending-updates? lp-cfg)))))
