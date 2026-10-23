// Pure domain logic: no Spring, no I/O, no runtime dependencies. The service and the Spark job
// both depend on this module so they apply exactly the same matching rules.
plugins {
    id("recon.java-conventions")
    `java-library`
}
