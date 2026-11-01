(ns marksto.clj-tg-bot-api.impl.utils-test
  (:require
   [clojure.core.cache :as cache]
   [clojure.test :refer [deftest is testing]]
   [marksto.clj-tg-bot-api.impl.utils :as sut]))

(def test-tti-ms 100)

(deftest tti-cache-factory-test
  (testing "an entry stays cached while it is used"
    (let [tti-cache (-> (sut/tti-cache-factory {} :tti test-tti-ms)
                        (cache/miss :a 1))
          _         (Thread/sleep 60)
          tti-cache (cache/hit tti-cache :a)
          _         (Thread/sleep 60)]
      (is (= {:a 1 :b 2}
             (into {} (cache/miss tti-cache :b 2)))
          "Put 120 ms ago, but used 60 ms ago")))
  (testing "an entry gets evicted once it has not been used for a while"
    (let [tti-cache (-> (sut/tti-cache-factory {:a 1} :tti test-tti-ms)
                        (cache/miss :b 2))
          _         (Thread/sleep 60)
          tti-cache (cache/hit tti-cache :b)
          _         (Thread/sleep 60)]
      (is (= {:b 2 :c 3}
             (into {} (cache/miss tti-cache :c 3)))
          "Unused for 120 ms")))
  (testing "a missing entry does not get cached by a hit"
    (let [tti-cache (cache/hit (sut/tti-cache-factory {} :tti test-tti-ms) :a)]
      (is (not (cache/has? tti-cache :a)))
      (is (nil? (cache/lookup tti-cache :a))))))
