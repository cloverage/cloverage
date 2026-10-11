(ns cloverage.instrument-test-clj12
  (:require [clojure.test :as t]
            [cloverage.instrument :as inst]
            [riddley.walk :as rw]))

(def ^:private bb? (System/getProperty "babashka.version"))

(defmacro if-bb [then else]
  (if bb? then else))

(t/deftest instrument-clj-1-12-features
  (t/testing "Instrumentation of new Clojure 1.12 features"
    (t/testing "Qualified methods - Class/method, Class/.method, and Class/new"
      (t/is (= '(do
                  (do
                    (do ((do Long/new) (do 1)))
                    (do System/out)
                    (do (let* [f (do Long/.byteValue)]
                          (do ((do f) (do 1)))))
                    (do (let* [f (do Long/valueOf)]
                          (do ((do f) (do 1)))))))
               (rw/macroexpand-all (inst/instrument-form #'inst/nop
                                                         nil
                                                         '(do
                                                            (Long/new 1)
                                                            System/out
                                                            (let [f Long/.byteValue]
                                                              (f 1))
                                                            (let [f Long/valueOf]
                                                              (f 1))))))))
    (t/testing "Functional interfaces"
      (t/is (= '(do
                  (let* [p (do even?)]
                    (do (. p test (do 42)))))
               (rw/macroexpand-all (inst/instrument-form #'inst/nop
                                                         nil
                                                         '(let [^java.util.function.Predicate p even?]
                                                            (.test p 42)))))))
    (t/testing "Array class syntax"
      (if-bb
        ;; bb: int-array is a regular function, not inlined; uses copyOf (binarySearch not currently in bb's reflection config)
        (t/is (= '(do
                    (do
                      (do (new ProcessBuilder (do ((do into-array) (do String) (do [(do "a")])))))
                      (do ((do java.util.Arrays/copyOf)
                           (do ((do int-array) (do [(do 1) (do 2) (do 3)])))
                           (do 2)))))
                 (rw/macroexpand-all (inst/instrument-form #'inst/nop
                                                           nil
                                                           '(do
                                                              (ProcessBuilder. ^String/1 (into-array String ["a"]))
                                                              (java.util.Arrays/copyOf ^int/1 (int-array [1 2 3])
                                                                                       2))))))
        ;; JVM: int-array and int are inlined to interop calls
        (t/is (= '(do
                    (do
                      (do (new ProcessBuilder (do ((do into-array) (do String) (do [(do "a")])))))
                      (do ((do java.util.Arrays/binarySearch)
                           (do (. clojure.lang.Numbers clojure.core/int_array (do [(do 1) (do 2) (do 3)])))
                           (do (. clojure.lang.RT (intCast (do 2))))))))
                 (rw/macroexpand-all (inst/instrument-form #'inst/nop
                                                           nil
                                                           '(do
                                                              (ProcessBuilder. ^String/1 (into-array String ["a"]))
                                                              (java.util.Arrays/binarySearch ^int/1 (int-array [1 2 3])
                                                                                             (int 2)))))))))))
