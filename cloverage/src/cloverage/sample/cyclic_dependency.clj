(ns cloverage.sample.cyclic-dependency)

;; Using `:require` in the `ns` declaration to create a circular
;; dependency unfortunately causes Eastwood to throw an exception.
;; Adding this ns to the `:excluded-namespaces` doesn't squash the
;; exception.  Presumably this is because Eastwood wants to evaluate
;; the `ns` form to determine the namespace.

;; Whereas a separate `require` satisfies Eastwood, but causes failures
;; in the test suite.  For now, let's make Eastwood happy.

(require '[cloverage.sample.cyclic-dependency :as self])

(+ 40 2)
