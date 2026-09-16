# Instructions for coding agents

Read this file before changing this repository. These rules also apply to subdirectories unless a more specific instruction applies. Read `CONTRIBUTING.md` for the contributor workflow and only the relevant sections of `DEVELOPMENT_GUIDE.md` for implementation details.

## Product and build contract

- This is a local JDBC database workbench. Keep **Java 8**, **Spring Boot 2.7.18**, one Maven module, and a runnable `database-toolbox.jar` containing the UI. End users must not need Node, Docker, Maven, a separate web server, or an internet connection at startup.
- The frontend is native JavaScript/CSS in `src/main/resources/static/workbench/`. No frontend build system, framework migration, CDN, or external runtime without an explicit scope change.
- `legacy/v1/` is an archive, excluded from the build. Implement current features in `src/`; do not silently restore legacy endpoints.
- Users select an isolated JDBC driver profile. Do not put vendor drivers on the application's runtime classpath, replace selection with global `DriverManager`, or assume PostgreSQL compatibility proves GaussDB compatibility.
- Project license is GPL-2.0. Preserve license notices and bundled dependency source/license obligations; coordinate dependency or license changes with the owner.

## Find the relevant boundary

| Concern | Source |
| --- | --- |
| Driver loading / bundled installation | `workbench/driver/` |
| Saved profiles / secrets / JDBC connections | `workbench/connection/`, `common/` |
| Object tree / table structure / dialect SQL | `workbench/metadata/`, `workbench/dialect/` |
| Session, transaction, execution, cancellation | `workbench/execution/` |
| Cell editing | `workbench/execution/CellEdits.java`, companion `CellEditingTest.java`, UI `app.js` |
| Local access protection / storage lock | `workbench/LocalRuntime.java` |
| Launcher and disposable integration checks | `scripts/` |

Java package paths above are relative to `src/main/java/com/example/dbtoolbox/`; tests mirror that package under `src/test/java/`.

## Invariants to preserve

1. Each editor/table tab owns its JDBC session. Metadata uses separate short-lived connections. Never use a new connection to perform a tab's transaction-sensitive work.
2. Acquire the session gate for SQL, transaction changes and close operations. Every SQL submission, including cell edits, goes through prepare → fingerprint/context-bound confirmation → submit. Retrying a write must keep the same request ID and payload; never silently retry with a fresh ID.
3. Turning auto-commit back on first rolls back pending work. Preserve cancel/timeout/unknown-outcome states; a cancellation request is not proof that a database operation stopped.
4. Preserve all result sets, update counts (including zero), duplicate column labels, NULL versus empty string, and large integer/decimal precision. Keep collection limits and truncation visible.
5. SQL scripts and procedure bodies use the existing lexer; never split SQL simply on `;`.
6. Cell edits accept only server-owned table-preview snapshots from the same unchanged session. Use complete primary keys, quoted metadata identifiers, typed bind parameters, a locked original-value check and exactly-one-row validation. Preserve savepoint rollback and post-write value verification. Do not trust client-provided SQL, table names, keys or old values for this write path. No arbitrary-query, view, primary-key or generated-column writeback.
7. Keep loopback-only hosting, Host/Origin checks, mutation token protection, storage locking, encrypted credentials and output redaction. Treat imported drivers as executable code.
8. Do not inspect or publish real connection data, credentials or queries. Use a temporary storage root and disposable fixtures for tests. Do not commit `data/`, logs, local recordings, `.env`, build outputs or distribution binaries.

## Working and verification

- Check `git status` first. Preserve unrelated user edits. Use a feature branch (`codex/<feature>` for agent work); do not commit directly on `main`, force-push, merge or create releases without the relevant authorization.
- Make focused changes with meaningful tests for behavior and failure paths. Fix failures introduced by your changes. Report pre-existing failures separately.
- Use **JDK 8** for required validation: `mvn clean verify`, `node --check src/main/resources/static/workbench/app.js`, `sh -n scripts/database-toolbox.sh`. `-o` is optional only when dependencies are cached. Documentation-only changes need link/content checks, not a full rebuild.
- For JDBC changes, add integration-level tests using H2 and validate claimed vendor behavior on disposable real databases. Read `--help` before running smoke scripts; they create and delete fixtures. Do not point them at production.
- For UI changes, operate the rendered UI in a real browser and inspect it. API tests alone do not verify selection, dialogs, keyboard controls or transaction feedback.
- For packaging changes or a new JAR delivery, launch the built artifact with temporary storage. Keep feature artifacts separate from published release assets.
- Record commands, actual results, database/driver/runtime versions, and remaining gaps. Source inspection, automated tests and visual inspection are different evidence. Do not claim support for environments not tested.
- Update user docs for changed behavior and developer docs for changed contracts. Never mark an old acceptance checklist passed based solely on implementation.
- A PR must explain the problem, resulting behavior, validation and limits. Link the relevant test/feature record. See `CONTRIBUTING.md`.

No fixed agent count or delegation is required. Follow the caller's tool and authorization constraints. If blocked, name the failing command or missing requirement and finish independent authorized work.
