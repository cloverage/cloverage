(ns cloverage.test-instrument
  (:use [clojure.test]
        [cloverage instrument source]))

(def simple-forms
  "Simple forms that do not require macroexpansion and have no side effects."
  [1
   "A STRING"
   ''("a" "simple" "list")
   [1 2 'vector 3 4]
   {:simple :map :here 1}
   #{:sets :should :work}
   '(do :expression)])

(deftest wrap-preserves-value
  (doseq [simple-expr simple-forms]
    (is (= simple-expr (wrap no-instr 0 simple-expr)))
    (is (= (eval simple-expr) (eval (wrap nop 0 simple-expr))))))

(deftest correctly-resolves-macro-symbols
  ;; simply ensure that instrumentation succeeds without errors
  (is (instrument no-instr 'cloverage.sample.read-eval-sample)))

(deftest test-form-type
  (is (= :atomic (form-type 1)))
  (is (= :atomic (form-type "foo")))
  (is (= :atomic (form-type 'bar)))
  (is (= :coll (form-type [1 2 3 4])))
  (is (= :coll (form-type {1 2 3 4})))
  (is (= :coll (form-type #{1 2 3 4})))
  (is (= :list (form-type '(+ 1 2))))
  (is (= :do (form-type '(do 1 2 3)))))
