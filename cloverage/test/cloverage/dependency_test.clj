(ns cloverage.dependency-test
  (:require [clojure.test :as t]
            [cloverage.dependency :as cd]
            [cloverage.source :as source]))

(def ns-fixtures {;; sources snipped from incanter : https://github.com/liebke/incanter
                  :incanter-core
                  {:ns-source
                   '(ns ^{:doc "This is the core numerics library for Incanter.
              It provides functions for vector- and matrix-based
              mathematical operations and the core data manipulation
              functions for Incanter.

              This library is built on Parallel Colt
              (http://sites.google.com/site/piotrwendykier/software/parallelcolt)
              an extension of the Colt numerics library
              (http://acs.lbl.gov/~hoschek/colt/).
              "
                          :author "David Edgar Liebke"}

                     incanter.core

                      (:use [incanter internal]
                            [incanter.infix :only (infix-to-prefix defop)]
                            [clojure.set :only (difference)])
                      (:import (incanter Matrix)
                               (cern.colt.matrix.tdouble DoubleMatrix2D
                                                         DoubleFactory2D
                                                         DoubleFactory1D)
                               (cern.colt.matrix.tdouble.algo DenseDoubleAlgebra
                                                              DoubleFormatter)
                               (cern.colt.matrix.tdouble.algo.decomposition DenseDoubleCholeskyDecomposition
                                                                            DenseDoubleSingularValueDecomposition
                                                                            DenseDoubleEigenvalueDecomposition
                                                                            DenseDoubleLUDecomposition
                                                                            DenseDoubleQRDecomposition)
                               (cern.jet.math.tdouble DoubleFunctions DoubleArithmetic)
                               (cern.colt.function.tdouble DoubleDoubleFunction DoubleFunction)
                               (cern.colt.list.tdouble DoubleArrayList)
                               (cern.jet.stat.tdouble DoubleDescriptive Gamma)
                               (javax.swing JTable JScrollPane JFrame)
                               (java.util Vector)))
                   :expected '#{incanter.internal incanter.infix clojure.set}}

                  :incanter-bayes
                  {:ns-source
                   '(ns ^{:doc "This is library provides functions for performing
                basic Bayesian modeling and inference.
                "
                          :author "David Edgar Liebke"}
                     incanter.bayes
                      (:use [incanter.core :only (matrix mmult mult div minus trans ncol nrow
                                                         plus to-list decomp-cholesky solve half-vectorize
                                                         vectorize symmetric-matrix identity-matrix kronecker
                                                         bind-columns)]
                            [incanter.stats :only (sample-normal sample-gamma sample-dirichlet
                                                                 sample-inv-wishart sample-mvn mean)]))
                   :expected '#{incanter.core incanter.stats}}
                  :parkour-dseq
                  {:ns-source
                   '(ns parkour.io.dseq
                      (:require [clojure.core.protocols :as ccp]
                                [clojure.core.reducers :as r]
                                [parkour (conf :as conf) (cstep :as cstep) (wrapper :as w)]
                                [parkour.mapreduce (source :as src)]
                                [parkour.io.dseq (mapred :as mr1) (mapreduce :as mr2)]
                                [parkour.util :refer [ignore-errors]])
                      (:import [java.io Closeable Writer]
                               [clojure.lang IObj]))
                   :expected '#{clojure.core.protocols clojure.core.reducers parkour.conf
                                parkour.cstep parkour.wrapper parkour.mapreduce.source
                                parkour.io.dseq.mapred parkour.io.dseq.mapreduce
                                parkour.util}}})

(t/deftest test-dependency-extraction
  (doseq [[ns-name {ns-form :ns-source, expected :expected}] ns-fixtures]
    (t/testing ns-name
      (with-redefs [source/ns-form (constantly ns-form)]
        (t/is (= expected
                 (#'cd/dependencies (symbol (namespace ns-name)
                                            (name ns-name)))))))))

(t/deftest test-dependency-sort
  (t/is (= '[clojure.walk
             clojure.template
             clojure.string
             clojure.stacktrace
             clojure.test]
           (cd/in-dependency-order '[clojure.stacktrace
                                     clojure.string
                                     clojure.template
                                     clojure.test
                                     clojure.walk]))))

(t/deftest cyclic-dependency-test
  (t/testing "Should throw an Exception if cyclic dependencies exist between namespaces"
    (with-redefs [source/ns-form (fn [ns-symbol]
                                   (condp = ns-symbol
                                     'a '(ns a (:require b c))
                                     'b '(ns b (:require c))
                                     'c '(ns c (:require a))))]
      (t/is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Circular dependency between c and a"
             (cd/in-dependency-order '[a b c]))))))

(t/deftest isolated-namespace-test
  (t/testing "Isolated namespaces should be included in the result"
    (with-redefs [source/ns-form (fn [ns-symbol]
                                   (condp = ns-symbol
                                     'a '(ns a)
                                     'b '(ns b)
                                     'c '(ns c (:require b))))]
      (t/is (= '[b c a] (cd/in-dependency-order '[a b c]))))))
