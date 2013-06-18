replicatorg-mavenized
=====================

Mavenized version of ReplicatorG project

## Prerequisites ##
- JDK 6 or newer
- Maven 3.0.4 or newer

## Build instructions ##
In order to compile code, execute the following command:
```
mvn clean compile
```

Packaging will not work because tests will **fail**.

## TODO ##
- Deal with multiple OS-dependent stuff (shared-objects, static-objects, pre-compiled executables, etc.).
- Reorganize assemblies for both running and testing each OS application.