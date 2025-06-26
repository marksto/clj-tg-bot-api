(ns marksto.clj-tg-bot-api.net-utils)

(def default-state {:enabled? false
                    :prop-map {}})

(def ^:private *state (atom default-state))

(def dead-end-host "127.0.0.1")
(def dead-end-port "9999")

(def props
  ["http.proxyHost"
   "http.proxyPort"
   "https.proxyHost"
   "https.proxyPort"])

(defn simulate-http-outage!
  "Sets system proxy to a dead‐end and remembers any previous values."
  []
  (let [current (into {} (map (fn [k] [k (System/getProperty k)]) props))]
    (reset! *state {:enabled? true
                    :prop-map current})
    (System/setProperty "http.proxyHost" dead-end-host)
    (System/setProperty "http.proxyPort" dead-end-port)
    (System/setProperty "https.proxyHost" dead-end-host)
    (System/setProperty "https.proxyPort" dead-end-port)))

(defn restore-http-settings!
  "Restores system proxy settings to their values before an \"outage\"."
  []
  (doseq [[k v] (:prop-map @*state)]
    (if (some? v)
      (System/setProperty k v)
      (System/clearProperty k)))
  (reset! *state {:enabled? false
                  :prop-map {}}))

(defmacro with-http-outage
  "Temporarily routes all HTTP within `body` through a dead‐end proxy,
   restoring previous settings afterward."
  [& body]
  `(do
     (simulate-http-outage!)
     (try
       ~@body
       (finally
         (restore-http-settings!)))))

(defn get-proxy-url []
  (when (:enabled? @*state)
    (str "http://" dead-end-host ":" dead-end-port)))
