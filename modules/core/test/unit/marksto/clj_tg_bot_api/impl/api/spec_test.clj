(ns marksto.clj-tg-bot-api.impl.api.spec-test
  (:require
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

;;

(comment
  (clojure.test/run-tests)
  :end/comment)
