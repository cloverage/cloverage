(ns cloverage.sample.cyclic-dependency
  ;; don't actually include the cyclic dependency in this namespace, because all namespaces have to be taken into
  ;; account to build the dep graph this would cause other tests to fail. We'll use with-redefs to give this namespace
  ;; circular deps in the relevant test -- see cloverage.coverage-test/test-cyclic-dependency
  #_(:require [cloverage.sample.cyclic-dependency :as self]))

(+ 40 2)
