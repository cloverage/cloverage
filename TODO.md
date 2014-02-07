In no particular order, things that should be done:

  - Make cloverage a higher order lein task, thus letting it support any test framework. This requires:
    - Persistent coverage store
    - Wrapm macro must be printable, or plugin must hook into reader to wrap every top level form in the (currently unprintable) macro. Or both.
    - A small runtime library to inject to project classpath (with insturmentation / storing logic, pref. no dependencies)
    - A richer lein plugin that handles the option parsing, sets up instrumentation hook (or prints out instrumented sources and changes sourcepath), delegates to a lein task then gathers results
    - (opt.) Logic to compose coverage from multiple runs

  - Instrument deftype, reify.
  - Better html output (form-level output rather than colouring lines)
  - TESTS! Oh my god, tests.
  - Automated snapshot builds [#18]
  - ignore destructuring partial coverage? (how?) [#15]
  - figure out partial for coverage [#23]
  - release script [#6]
