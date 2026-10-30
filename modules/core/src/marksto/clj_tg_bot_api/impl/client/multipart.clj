;; Copyright (c) Mark Sto, 2026. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.clj-tg-bot-api.impl.client.multipart
  "A bare minimum of the 'multipart/form-data' body encoding, for the webhook
   replies, which unlike requests have no HTTP client to encode them.

   NOTE: Kudos to the `hato.multipart` namespace, which this is adapted from:
         https://github.com/gnarroway/hato/blob/master/src/hato/multipart.clj
         Copyright (c) 2019 George Simon Narroway, under the MIT License."
  {:author "Mark Sto (@marksto)"}
  (:require
   [clojure.java.io :as io])
  (:import
   (java.io File InputStream SequenceInputStream)
   (java.nio.charset StandardCharsets)
   (java.nio.file Files Path)
   (java.util Collections UUID)))

(defn boundary
  "Gives a random boundary that is compliant with RFC 2046."
  []
  (str "CljTgBotApi" (subs (str (UUID/randomUUID)) 0 8)))

(def ^{:private true :tag String} line-break "\r\n")

(defn- content-disposition
  [{:keys [name content]}]
  (str "Content-Disposition: form-data; name=\"" name "\""
       (when (instance? File content)
         (str "; filename=\"" (File/.getName content) "\""))))

(defn- content-type
  [{:keys [content]}]
  (let [content-type (or (cond
                           (string? content) "text/plain; charset=UTF-8"
                           (instance? File content) (Files/probeContentType
                                                      (File/.toPath content)))
                         "application/octet-stream")]
    (str "Content-Type: " content-type)))

(defn- path->file
  [content]
  (cond-> content
    (instance? Path content) (Path/.toFile)))

(defn- ->input-stream
  ^InputStream [content]
  (-> (if (string? content)
        (String/.getBytes content StandardCharsets/UTF_8)
        content)
      (io/input-stream)))

(defn body
  "Gives an InputStream of a 'multipart/form-data' body, built of the `parts`,
   each a map of a `:name` and some `:content` (e.g. a String, a File, bytes),
   which are separated by the `boundary`."
  ^InputStream [parts boundary]
  (->> (concat (mapcat (fn [part]
                         [(str "--" boundary
                               line-break
                               (content-disposition part)
                               line-break
                               (content-type part)
                               line-break
                               line-break)
                          (:content part)
                          line-break])
                       (map #(update % :content path->file) parts))
               [(str "--" boundary "--" line-break)])
       (mapv ->input-stream)
       (Collections/enumeration)
       (SequenceInputStream.)))
