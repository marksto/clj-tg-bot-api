(ns marksto.clj-tg-bot-api.impl.api.spec-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [marksto.clj-tg-bot-api.impl.api.spec :as sut]
   [schema.core :as s]))

(deftest tg-bot-api-spec-parses-well
  (let [{:keys [types methods] :as tg-bot-api-spec} (sut/get-tg-bot-api-spec)]
    (is (some? tg-bot-api-spec)
        "The spec must parse (check the logged error if it does not)")
    (is (seq types)
        "The parsed spec must have the API types")
    (is (seq methods)
        "The parsed spec must have the API methods")))

(deftest type-schema-vars-are-bound
  (sut/get-tg-bot-api-spec)
  (let [unbound-vars (->> (ns-interns sut/type-schemas-ns)
                          (remove (comp bound? val))
                          (mapv key))]
    (is (empty? unbound-vars)
        "Every interned type schema var must be bound to a schema")))

(deftest every-schema-checker-builds
  (let [{:keys [types methods]} (sut/get-tg-bot-api-spec)]
    ;; NB: Building a checker is what forces every `s/recursive` reference
    ;;     in a schema to resolve. Merely parsing the spec does not, which
    ;;     is how an unresolvable reference reaches the API method callers.
    (testing "API types"
      (doseq [{:keys [name schema]} types]
        (is (some? (s/checker schema))
            (format "The '%s' type schema must build a checker" name))))
    (testing "API methods"
      (doseq [{:keys [name params-schema]} methods
              :when (some? params-schema)]
        (is (some? (s/checker params-schema))
            (format "The '%s' method params schema must build a checker" name))))))

(deftest array-total-length-constraint-is-enforced
  (let [{:keys [methods]} (sut/get-tg-bot-api-spec)
        {:keys [params-schema]} (some #(when (= "setStickerKeywords" (:name %)) %)
                                      methods)]
    (is (nil? (s/check params-schema {:sticker  "sticker-file-id"
                                      :keywords ["cat" "kitten" "кот" "котёнок"]}))
        "Keywords within the 64 characters in total must pass")
    (is (some? (s/check params-schema {:sticker  "sticker-file-id"
                                       :keywords [(str/join (repeat 32 \a))
                                                  (str/join (repeat 33 \b))]}))
        "Keywords over the 64 characters in total must fail, each being shorter")))

;;

(comment
  (clojure.test/run-tests)
  :end/comment)
