(ns cloverage.test-dependency
  (:require [cloverage.dependency :as cd])
  (:use     clojure.test)
  )


(def ns-fixtures {
  ;; sources snipped from incanter : https://github.com/liebke/incanter
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
   :expected '[incanter.core #{incanter.internal incanter.infix clojure.set}]} 

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
   :expected '[incanter.bayes #{incanter.core incanter.stats}]}
  })

(deftest test-dependency-extraction
  (doall (for [[ns-name
                {ns-form :ns-source expected :expected}] (seq ns-fixtures)]
    (let [result (cd/dependency-libs ns-form)]
      ;; wrap in seq to work around lack of LazySeq.toString
      (is (= expected result)
          (str "Parsing " ns-name
               " should give " expected
               " but got " result ))))))

(deftest test-dependency-sort
  (let [dep-lists [['first #{'fourth}]
                   ['second #{'fourth 'first}]
                   ['third #{}]
                   ['fourth #{'fifth}]
                   ['fifth #{}]]
        result    (cd/dependency-sort dep-lists)
        index-map (zipmap result (range))
        ]
    (is (not (nil? result)) "Dependency sort should not be nil.")
    (doseq [[name deps] dep-lists]
      (let [my-index (get index-map name)]
        (doseq [dep deps]
          (is (< (get index-map dep) my-index)
              (str "Prerequisite " dep " of " name " should be loaded before,"
                   " but isn't: " result)))))))
