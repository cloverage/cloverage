(ns cloverage.report.console-test
  (:require
   [clojure.test :refer :all]
   [cloverage.report.console :refer :all]))

(deftest check-ansi
  (is (nil? (ansi :green nil)))
  (is (= "\u001b[0mhello\u001b[0m" (ansi :purple "hello")))
  (is (= "\u001b[1mhello\u001b[0m" (ansi :bright "hello")))
  (is (= "\u001b[31mhello\u001b[0m" (ansi :red "hello")))
  (is (= "\u001b[32mhello\u001b[0m" (ansi :green "hello")))
  (is (= "\u001b[33mhello\u001b[0m" (ansi :yellow "hello"))))

(deftest check-strip-ansi
  (is (nil? (strip-ansi nil)))
  (is (= "" (strip-ansi (ansi :red ""))))
  (is (= "world" (strip-ansi (ansi :red "world")))))

(deftest check-printable-count
  (is (= 0 (printable-count nil)))
  (is (= 0 (printable-count "")))
  (is (= 0 (printable-count (ansi :red ""))))
  (is (= 11 (printable-count "hello world")))
  (is (= 11 (printable-count (ansi :green "hello world")))))

(deftest check-pad-right
  (is (= "     " (pad-right 5 nil)))
  (is (= "     " (pad-right 5 "")))
  (is (= "hello world" (pad-right 5 "hello world")))
  (is (= "hello world" (pad-right 11 "hello world")))
  (is (= " hello world" (pad-right 12 "hello world")))
  (is (= "         hello world" (pad-right 20 "hello world"))))

(deftest check-colorize
  (let [f (colorize "%.3f" 33 66)]
    (is (= (ansi :red "0.000") (f 0.0)))
    (is (= (ansi :red "10.001") (f 10.0005)))
    (is (= (ansi :red "33.000") (f 32.9999)))
    (is (= (ansi :yellow "33.000") (f 33.0000)))
    (is (= (ansi :yellow "66.000") (f 65.9999)))
    (is (= (ansi :green "66.000") (f 66.0000)))
    (is (= (ansi :green "100.000") (f 100.0000))))
  (is (thrown? IllegalArgumentException (colorize "%.2f" -1 30)))
  (is (thrown? IllegalArgumentException (colorize "%.2f" 65 30)))
  (is (thrown? IllegalArgumentException (colorize "%.2f" 40 130))))

(deftest check-print-table
  (let [data [{:field1 "Hello world"     :field2 94 :field3 true}
              {:field1 "Goodbye"         :field2 65 :field3 false}
              {:field1 (ansi :red "Red") :field2  1 :field3 false}]
        header [:field2 :field1 :field3]
        footer ["A", "B", "C"]]
    (is (= "
|---------+-------------+---------|
| :field2 |     :field1 | :field3 |
|---------+-------------+---------|
|      94 | Hello world |    true |
|      65 |     Goodbye |   false |
|       1 |         Red |   false |
|---------+-------------+---------|
|       A |           B |       C |
|---------+-------------+---------|
"
           (strip-ansi (with-out-str (print-table header data footer)))))))
