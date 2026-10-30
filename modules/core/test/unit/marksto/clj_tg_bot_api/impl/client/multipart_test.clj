(ns marksto.clj-tg-bot-api.impl.client.multipart-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [marksto.clj-tg-bot-api.impl.client.multipart :as sut])
  (:import
   (java.nio.file Files)))

(deftest body-test
  (testing "parts of a text, a file and bytes"
    (let [test-file (doto (fs/file (fs/create-temp-file {:prefix "test-file" :suffix ".txt"}))
                      (fs/delete-on-exit)
                      (spit "Test file"))
          file-name (fs/file-name test-file)
          file-type (or (Files/probeContentType (fs/path test-file)) ; OS-specific
                        "application/octet-stream")
          exp-value (str "--TestBorderline\r\n"
                         "Content-Disposition: form-data; name=\"chat_id\"\r\n"
                         "Content-Type: text/plain; charset=UTF-8\r\n"
                         "\r\n"
                         "1\r\n"
                         "--TestBorderline\r\n"
                         "Content-Disposition: form-data; name=\"document\"; filename=\"" file-name "\"\r\n"
                         "Content-Type: " file-type "\r\n"
                         "\r\n"
                         "Test file\r\n"
                         "--TestBorderline\r\n"
                         "Content-Disposition: form-data; name=\"thumbnail\"\r\n"
                         "Content-Type: application/octet-stream\r\n"
                         "\r\n"
                         "Test bytes\r\n"
                         "--TestBorderline--\r\n")]
      (is (= exp-value
             (slurp (sut/body [{:name "chat_id" :content "1"}
                               {:name "document" :content test-file}
                               {:name "thumbnail" :content (String/.getBytes "Test bytes")}]
                              "TestBorderline"))))
      (is (= exp-value
             (slurp (sut/body [{:name "chat_id" :content "1"}
                               {:name "document" :content (fs/path test-file)}
                               {:name "thumbnail" :content (String/.getBytes "Test bytes")}]
                              "TestBorderline")))
          "A path must be sent as a file"))))
