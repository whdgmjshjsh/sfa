# Repository Guidelines

## Project Structure & Module Organization
- `Tai-e/`: main analysis framework and student implementation; pointer analysis entrypoints live in `Tai-e/src/main/java/pku`, with framework sources under `Tai-e/src/main/java/pascal/taie`.
- `Tai-e/src/test/java`: upstream test suite; add regression tests here when extending behavior.
- `Tai-e/build/`: Gradle outputs; runnable jar currently at `Tai-e/build/tai-e-all-0.5.1-SNAPSHOT.jar`.
- `judge/`: local evaluator. Place submissions under `judge/user/submission_jar` or `submission_zip`; scripts wrap your artifacts in a constrained Docker run.
- Top-level PDFs/markdown: assignment brief, grading notes, and public samples (`公开测试样例.md`, `项目任务.md`).

## Build, Test, and Development Commands
- Build runnable fat jar (Java 17+):  
  `cd Tai-e && ./gradlew fatJar` → updates `build/tai-e-all-*.jar`.
- Run framework tests:  
  `cd Tai-e && ./gradlew test` (reports at `Tai-e/build/reports/tests/test/index.html`).
- Smoke-run your pointer analysis on a test case (matching judge behavior):  
  `cd Tai-e && java -jar build/tai-e-all-0.5.1-SNAPSHOT.jar -a pku-pta -cp testcase -m Main`.
- Local scoring with the provided judge (Docker required):  
  `cd judge && ./run_jar.sh` after placing your jar at `judge/user/submission_jar/submission.jar` (delete `result.json` to re-run).

  ```bash
  cd Tai-e
  GRADLE_USER_HOME=$(pwd)/.gradle-local ./gradlew fatJar
  cp build/tai-e-all-0.5.1-SNAPSHOT.jar ../judge/user/submission_jar/submission.jar
  cd ../judge
  bash ./run_jar.sh
  rm -rf ./user/submission_jar/result.json
  ```

## Coding Style & Naming Conventions
- Java style mirrors existing sources: 4-space indentation, braces on the same line, CamelCase types, lowerCamelCase fields/locals, and uppercase snake for constants.
- Keep code in package `pku` for student logic; reuse framework utilities in `pascal.taie` instead of duplicating helpers.
- Prefer immutable views or unmodifiable collections for shared results; log via Log4j (`org.apache.logging.log4j`) as seen in `PointerAnalysisTrivial`.
- Match existing null/Type checks before casting IR nodes; avoid introducing unchecked reflective calls or post-Java-8 language features (assignment spec).

## Testing Guidelines
- Add targeted unit tests in `Tai-e/src/test/java` that exercise new transfer rules or edge cases (casts, arrays, static fields, virtual dispatch).
- When updating points-to logic, validate on the public benchmarks by running the jar through `judge/run_jar.sh`; confirm `result.txt` matches expected soundness and finishes under 60s per case.
- Use deterministic data structures (e.g., `TreeSet` for outputs) so `result.txt` ordering remains stable for diffing.

## Commit & Pull Request Guidelines
- Current history is minimal; follow concise, imperative commit messages (e.g., `Implement field-sensitive stores`, `Add array load tests`).
- Include in PRs: problem statement, key design choices (heap abstraction, context/flow sensitivity), and notes on performance/memory expectations.
- Link related test updates and, when applicable, attach sample `result.txt` outputs or judge run summaries to document expected behavior.

## Security & Configuration Tips
- Ensure Java 17 is active (`java -version`) before building; Gradle wrapper handles dependencies offline after initial download.
- Judge containers run with `--network none`; avoid code paths that require external resources. Keep memory use under 6g and avoid spawning large process trees (`--pids-limit 256`).

## References
- The `Reference` folder contains source files (src_1/ and src_2/) that can be used as implementation examples.