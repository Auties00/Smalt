# Coverage

Line and branch coverage is a map, not a gate on a percentage: a decoder branch no test reaches is a spec feature
no conformance vector or test exercises, which says which vector to fetch or which reference encoder option to
generate one with.

## Running it

The `coverage` Maven profile adds the [JaCoCo](https://www.jacoco.org/) agent (0.8.15, which reads Java 26 class
files) to every test JVM and writes each module's report under `target/site/jacoco`; the `it` module, which depends
on every artifact, adds them together under `it/target/site/jacoco-aggregate`:

```
mvn -Pcoverage verify
python tools/coverage/coverage.py it/target/site/jacoco-aggregate/jacoco.xml --markdown coverage.md
```

`coverage.py` groups the report by area (the package below `com.github.auties00.smalt`, its internal parts left
out, cut to two segments: `video/h264`, `container/mp4`, `image/jpeg`) and lists for each the line and branch
coverage and the methods with the most missed branches.

## In CI

- The nightly workflow's coverage job runs the suite of every module with the profile on the smoke data tier,
  publishes the summary on the run page and uploads the reports and the summary as the `coverage-report` artifact.
  It fails when an area's branch coverage falls more than two points below `baseline.tsv`.
- `package.yml` takes `coverage=true` to run the chosen modules with the profile and upload their reports.

## The baseline

`baseline.tsv` holds the branch coverage of each area the nightly job measured. It only grows: after a change that
adds tests, `coverage.py REPORT --update tools/coverage/baseline.tsv` raises the areas that improved and keeps the
others. An area that falls is a test that stopped running or a vector that stopped being fetched.
