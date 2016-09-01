(ns cloverage.instrument-test
  (:require [clojure.test :as t]
            [cloverage.instrument :as inst]
            [riddley.walk :as rw]))

(def simple-forms
  "Simple forms that do not require macroexpansion and have no side effects."
  [1
   "A STRING"
   ''("a" "simple" "list")
   [1 2 'vector 3 4]
   {:simple :map :here 1}
   #{:sets :should :work}
   '(do :expression)])

(t/deftest wrap-preserves-value
  (doseq [simple-expr simple-forms]
    (t/is (= simple-expr (rw/macroexpand-all (inst/wrap #'inst/no-instr 0 simple-expr))))
    (t/is (= (eval simple-expr) (eval (inst/wrap #'inst/nop 0 simple-expr))))))

(t/deftest correctly-resolves-macro-symbols
  ;; simply ensure that instrumentation succeeds without errors
  (t/is (inst/instrument #'inst/no-instr 'cloverage.sample.read-eval-sample)))

(defn- form-type-
  "Provide a default empty env to form-type, purely for easier testing."
  ([f] (form-type- f nil))
  ([f e] (inst/form-type f e)))

(defprotocol Protocol
  (method [this]))

(defrecord Record [foo]
  Protocol
  (method [_] foo))

(t/deftest test-form-type
  (t/is (= :atomic (form-type- 1)))
  (t/is (= :atomic (form-type- "foo")))
  (t/is (= :atomic (form-type- 'bar)))
  (t/is (= :coll (form-type- [1 2 3 4])))
  (t/is (= :coll (form-type- {1 2 3 4})))
  (t/is (= :coll (form-type- #{1 2 3 4})))
  (t/is (= :coll (form-type- (Record. 1))))
  (t/is (= :list (form-type- '(+ 1 2))))
  (t/is (= :do (form-type- '(do 1 2 3))))
  (t/is (= :list (form-type- '(loop 1 2 3)
                             {'loop 'hoop} ;fake a local binding
                             ))))

(t/deftest do-wrap-for-record-returns-record
  (t/is (= 1 (method (eval (inst/wrap #'inst/nop 0 (Record. 1)))))))

(t/deftest do-wrap-for-record-func-key-returns-func
  (t/is (= 1 ((method (eval (inst/wrap #'inst/nop 0 (Record. (fn [] 1)))))))))

(t/deftest preserves-fn-conditions
  (let [pre-fn (eval (inst/wrap #'inst/nop 0
                                '(fn [n] {:pre [(> n 0) (even? n)]} n)))]
    (t/is (thrown? AssertionError (pre-fn -1)))
    (t/is (thrown? AssertionError (pre-fn 1)))
    (t/is (= 2 (pre-fn 2))))
  (let [post-fn (eval (inst/wrap #'inst/nop 0
                                 '(fn [n] {:post [(> % 3) (even? %)]} n)))]
    (t/is (thrown? AssertionError (post-fn 1)))
    (t/is (thrown? AssertionError (post-fn 5)))
    (t/is (= 4 (post-fn 4))))
  ;; XXX: side effect, but need to test defn since we special case it
  (let [both-defn (eval (inst/wrap #'inst/nop 0
                                   '(defn both-defn [n]
                                      {:pre [(> n -1)] :post [(> n 0)]}
                                      n)))]
    (t/is (thrown? AssertionError (both-defn 0)))
    (t/is (thrown? AssertionError (both-defn -1)))
    (t/is (= 1 (both-defn 1)))))
