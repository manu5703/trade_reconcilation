# 0002: recon-core has no framework code and does no I/O

**Status:** accepted

## Context
Two runtimes will run the matching rules: the Spring Boot service (interactive runs, one
business date) and the Spark job (10M-row batch). If each had its own copy of the rules, they
would drift apart and produce different breaks for the same data.

## Decision
`recon-core` is plain Java 21 with **no runtime dependencies**: domain records, tolerances, ISIN
rules and (from stage 2) the matching passes. It doesn't read files or environment variables.
For example, `ToleranceConfig.fromProperties` takes an already-loaded `Properties` object, so
the CLI reads a file, Spring can bind its own config, and tests build one inline.

## Consequences
- The core is unit-testable in milliseconds, with no containers or Spring context.
- Spark serializes the core's objects to executors, so they must stay simple, immutable values.
- Tolerances live in one file (`config/tolerances.properties`), shared by the generator and the matcher.
