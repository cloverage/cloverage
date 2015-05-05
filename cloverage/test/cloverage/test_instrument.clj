(ns cloverage.test-instrument
  (:import java.lang.AssertionError)
  (:use [clojure.test]
        [cloverage instrument source])
  (:require [riddley.walk :refer [macroexpand-all]]))

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
    (is (= simple-expr (macroexpand-all (wrap #'no-instr 0 simple-expr))))
    (is (= (eval simple-expr) (eval (wrap #'nop 0 simple-expr))))))

(deftest correctly-resolves-macro-symbols
  ;; simply ensure that instrumentation succeeds without errors
  (is (instrument #'no-instr 'cloverage.sample.read-eval-sample)))

(defn- form-type-
  "Provide a default empty env to form-type, purely for easier testing."
  ([f] (form-type- f nil))
  ([f e] (form-type f e)))

(defprotocol Protocol
  (method [this]))

(defrecord Record [foo]
  Protocol
  (method [_] foo))

(deftest test-form-type
  (is (= :atomic (form-type- 1)))
  (is (= :atomic (form-type- "foo")))
  (is (= :atomic (form-type- 'bar)))
  (is (= :coll (form-type- [1 2 3 4])))
  (is (= :coll (form-type- {1 2 3 4})))
  (is (= :coll (form-type- #{1 2 3 4})))
  (is (= :coll (form-type- (Record. 1))))
  (is (= :list (form-type- '(+ 1 2))))
  (is (= :do (form-type- '(do 1 2 3))))
  (is (= :list (form-type- '(loop 1 2 3)
                          {'loop 'hoop} ;fake a local binding
                          ))))

(deftest do-wrap-for-record-returns-record
  (is (= 1 (method (eval (wrap #'nop 0 (Record. 1)))))))

(deftest do-wrap-for-record-func-key-returns-func
  (is (= 1 ((method (eval (wrap #'nop 0 (Record. (fn [] 1)))))))))

(deftest preserves-fn-conditions
  (let [pre-fn (eval (wrap #'nop 0
                           '(fn [n] {:pre [(> n 0) (even? n)]} n)))]
    (is (thrown? AssertionError (pre-fn -1)))
    (is (thrown? AssertionError (pre-fn 1)))
    (is (= 2 (pre-fn 2))))
  (let [post-fn (eval (wrap #'nop 0
                            '(fn [n] {:post [(> % 3) (even? %)]} n)))]
    (is (thrown? AssertionError (post-fn 1)))
    (is (thrown? AssertionError (post-fn 5)))
    (is (= 4 (post-fn 4))))
  ;; XXX: side effect, but need to test defn since we special case it
  (let [both-defn (eval (wrap #'nop 0
                        '(defn both-defn [n]
                           {:pre [(> n -1)] :post [(> n 0)]}
                           n)))]
    (is (thrown? AssertionError (both-defn 0)))
    (is (thrown? AssertionError (both-defn -1)))
    (is (= 1 (both-defn 1)))))
