#!/usr/bin/env python3
"""Exercise a running Toolbox JAR against disposable MySQL/PostgreSQL databases.

Uses only Python's standard library. Supply database URLs/drivers explicitly;
passwords may be passed through TOOLBOX_MYSQL_PASSWORD / TOOLBOX_POSTGRES_PASSWORD.
Creates uniquely named toolbox_v2_smoke objects, then removes its objects,
connections and sessions. Imported drivers remain available for UI verification.
Never point this integration test at a production database.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


TERMINAL = {"SUCCEEDED", "FAILED", "CANCELED", "TIMED_OUT", "OUTCOME_UNKNOWN"}


class Smoke:
    def __init__(self, args):
        self.args = args
        self.base = args.base_url.rstrip("/")
        self.token = None
        self.logs = []
        self.secrets = [args.mysql_password, args.postgres_password]
        self.prefix = "toolbox_v2_smoke_" + uuid.uuid4().hex[:10]
        self.session_ids = []
        self.connection_ids = []

    def clean(self, value):
        text = str(value)
        for secret in self.secrets:
            if secret:
                text = text.replace(secret, "***")
        return re.sub(r"(?i)(password|passwd|pwd|token|secret)(\s*[=:]\s*)[^\s;&]+", r"\1\2***", text)

    def log(self, check, status="PASS", **details):
        item = {"check": check, "status": status}
        item.update(details)
        # Do not serialize request payloads, URLs, authentication fields or the CSRF token.
        def sanitize(value):
            if isinstance(value, str):
                return self.clean(value)
            if isinstance(value, dict):
                return {key: sanitize(child) for key, child in value.items()}
            if isinstance(value, list):
                return [sanitize(child) for child in value]
            return value
        sanitized = sanitize(item)
        self.logs.append(sanitized)
        print(json.dumps(sanitized, ensure_ascii=False), flush=True)

    def api(self, method, path, payload=None, body=None, content_type=None, query=None):
        if query:
            path += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        headers = {"Accept": "application/json"}
        if self.token:
            headers["X-Toolbox-Token"] = self.token
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base + path, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=35) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            try:
                detail = json.loads(error.read()).get("message", "HTTP error")
            except (ValueError, AttributeError):
                detail = "HTTP error"
            raise AssertionError(self.clean("%s %s: HTTP %s: %s" % (method, path.split("?")[0], error.code, detail))) from None
        if not result.get("success"):
            raise AssertionError(self.clean(result.get("message", "API returned failure")))
        return result.get("data")

    def upload_driver(self, vendor, filename):
        path = Path(filename)
        jar = path.read_bytes()
        boundary = "ToolboxSmoke" + uuid.uuid4().hex
        driver_class = "com.mysql.cj.jdbc.Driver" if vendor == "mysql" else "org.postgresql.Driver"
        pieces = []
        for name, value in (("name", "%s %s" % (self.prefix, vendor)), ("driverClass", driver_class)):
            pieces.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (boundary, name, value)).encode())
        safe_name = path.name.replace('"', "_").replace("\r", "_").replace("\n", "_")
        pieces.append(("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"%s\"\r\nContent-Type: application/java-archive\r\n\r\n" % (boundary, safe_name)).encode())
        pieces.extend((jar, b"\r\n", ("--%s--\r\n" % boundary).encode()))
        driver = self.api("POST", "/api/drivers/import", body=b"".join(pieces), content_type="multipart/form-data; boundary=" + boundary)
        assert driver.get("driverClass") == driver_class, "Imported driver class mismatch"
        self.log(vendor + ".driver_import", driverFile=path.name, sha256=hashlib.sha256(jar).hexdigest(), driverId=driver["id"])
        return driver

    def connect(self, vendor):
        if self.args.use_bundled_drivers:
            driver_class = "com.mysql.cj.jdbc.Driver" if vendor == "mysql" else "org.postgresql.Driver"
            driver = next(item for item in self.api("GET", "/api/drivers") if item.get("bundled") and item["driverClass"] == driver_class)
            self.log(vendor + ".bundled_driver", version=driver["version"], files=driver["files"], sha256=driver["sha256"], withoutUpload=True)
        else:
            driver = self.upload_driver(vendor, getattr(self.args, vendor + "_driver"))
        profile = {"name": self.prefix + " " + vendor, "driverId": driver["id"],
                   "jdbcUrl": getattr(self.args, vendor + "_url"),
                   "username": getattr(self.args, vendor + "_user"),
                   "password": getattr(self.args, vendor + "_password"),
                   "dialectHint": "MYSQL" if vendor == "mysql" else "POSTGRESQL"}
        saved = self.api("POST", "/api/connections", profile)
        self.connection_ids.append(saved["id"])
        assert not saved.get("password"), "Connection response exposed a password"
        version = self.api("POST", "/api/connections/test", dict(profile, id=saved["id"]))
        assert version.get("success"), "Database connection test failed: " + self.clean(version.get("message"))
        session = self.new_session(saved["id"])
        self.log(vendor + ".connection", product=version.get("productName"), databaseVersion=version.get("productVersion"), driverVersion=version.get("driverVersion"))
        return saved, session

    def new_session(self, connection_id):
        session = self.api("POST", "/api/sessions", {"connectionId": connection_id})
        self.session_ids.append(session["id"])
        return session

    def submit(self, session, sql=None, mode="SQL", **options):
        request = {"sessionId": session["id"], "mode": mode, "requestId": uuid.uuid4().hex}
        if sql is not None:
            request["sql"] = sql
        request.update(options)
        plan = self.api("POST", "/api/executions/prepare", request)
        assert plan.get("units"), "Preparation produced no execution units"
        request["confirmationToken"] = plan["confirmationToken"]
        record = self.api("POST", "/api/executions", request)
        return record, plan, request

    def poll(self, record, timeout=35):
        deadline = time.monotonic() + timeout
        while record.get("state") not in TERMINAL:
            if time.monotonic() > deadline:
                raise AssertionError("Execution did not reach a terminal state")
            time.sleep(0.08)
            record = self.api("GET", "/api/executions/" + record["id"])
        # A terminal state is written immediately before finally releases the session.
        time.sleep(0.03)
        return record

    def execute(self, session, sql=None, mode="SQL", expected="SUCCEEDED", **options):
        record, plan, request = self.submit(session, sql, mode, **options)
        record = self.poll(record)
        if record.get("state") != expected:
            errors = [unit.get("error") for unit in record.get("statements", []) if unit.get("error")]
            raise AssertionError(self.clean("Expected %s, got %s: %s" % (expected, record.get("state"), errors or record.get("message"))))
        return record, plan, request

    @staticmethod
    def results(record, kind="RESULT_SET"):
        return [r for u in record["statements"] for r in u.get("results", []) if r["kind"] == kind]

    def scalar(self, session, sql):
        record, _, _ = self.execute(session, sql)
        return self.results(record)[0]["rows"][0][0]

    def metadata_preview(self, vendor, profile, session, table, schema=None):
        query = {"catalog": session.get("catalog"), "schema": schema}
        tables = self.api("GET", "/api/connections/%s/objects" % profile["id"], query=dict(query, kind="tables"))
        assert any(item["name"] == table for item in tables), "Created table not found in object browser"
        structure = self.api("GET", "/api/connections/%s/table-structure" % profile["id"], query=dict(query, table=table))
        assert [c["name"] for c in structure["columns"]] == ["id", "val"]
        assert structure["columns"][0]["primaryKey"] and structure["indexes"]
        request = dict(query, table=table, filters=[{"column": "id", "operator": ">=", "value": "2"}], orderBy="id", limit=1)
        first, plan, _ = self.execute(session, mode="TABLE_PREVIEW", offset=0, **request)
        second, _, _ = self.execute(session, mode="TABLE_PREVIEW", offset=1, **request)
        assert str(self.results(first)[0]["rows"][0][0]) == "2"
        assert str(self.results(second)[0]["rows"][0][0]) == "3"
        assert "?" in plan["units"][0]["sql"], "Preview did not bind parameters"
        self.log(vendor + ".metadata_preview", columns=2, pages=2, exactName=table)

    def mysql(self):
        profile, session = self.connect("mysql")
        table = self.prefix + "_%rows"
        proc = self.prefix + "_proc"
        qt, qp = "`" + table + "`", "`" + proc + "`"
        try:
            sql = ("CREATE TABLE %s (id INT PRIMARY KEY, val VARCHAR(80));\n" % qt
                   + "INSERT INTO %s VALUES (1,'first'),(2,'second'),(3,'third');\n" % qt
                   + "DELIMITER $$\nCREATE PROCEDURE %s(IN p_in INT, INOUT p_count INT, OUT p_out INT)\n" % qp
                   + "BEGIN\n SET p_count=p_count+p_in;\n SET p_out=p_in*2;\n SELECT p_in AS id,p_out AS id;\n SELECT 'body;still one statement' AS message;\nEND$$\nDELIMITER ;")
            created, plan, _ = self.execute(session, sql, "SCRIPT")
            assert len(plan["units"]) == 3 and len(created["statements"]) == 3
            assert all("DELIMITER" not in unit["sql"] for unit in plan["units"])
            self.log("mysql.delimiter_script", units=3)
            self.metadata_preview("mysql", profile, session, table)
            routines = self.api("GET", "/api/connections/%s/objects" % profile["id"], query={"kind": "routines", "catalog": session.get("catalog")})
            routine = next(r for r in routines if r["name"] == proc and r["type"] == "PROCEDURE")
            detail = self.api("GET", "/api/connections/%s/routine-detail" % profile["id"], query={"catalog": session.get("catalog"), "name": proc, "type": "PROCEDURE", "specificName": routine.get("specificName")})
            assert detail["definition"] and [p["mode"] for p in detail["parameters"]] == ["IN", "INOUT", "OUT"]
            parameters = detail["parameters"]
            for parameter in parameters:
                parameter["value"] = 3 if parameter["name"] == "p_in" else 10 if parameter["name"] == "p_count" else None
            called, _, request = self.execute(session, detail["callSql"], "CALL", parameters=parameters)
            result_sets = self.results(called)
            assert len(result_sets) == 2
            assert [c["label"] for c in result_sets[0]["columns"]] == ["id", "id"]
            assert [str(v) for v in result_sets[0]["rows"][0]] == ["3", "6"]
            outputs = {p["name"]: str(p["value"]) for r in self.results(called, "OUT_PARAMETERS") for p in r["parameters"]}
            assert outputs == {"p_count": "13", "p_out": "6"}
            duplicate = self.api("POST", "/api/executions", request)
            assert duplicate["id"] == called["id"], "Repeated request ID was executed again"
            self.log("mysql.call_inout_out_multi_results", resultSets=2, duplicateLabels=True, idempotentRequest=True)
            failed, _, _ = self.execute(session, "INSERT INTO %s VALUES(91,'before'); SELECT * FROM `%s_missing`; INSERT INTO %s VALUES(92,'after');" % (qt, self.prefix, qt), "SCRIPT", expected="FAILED")
            assert [unit["state"] for unit in failed["statements"]] == ["SUCCEEDED", "FAILED", "SKIPPED"]
            assert str(self.scalar(session, "SELECT COUNT(*) FROM %s WHERE id=91" % qt)) == "1"
            assert str(self.scalar(session, "SELECT COUNT(*) FROM %s WHERE id=92" % qt)) == "0"
            self.log("mysql.script_stop_on_error", statementStates=[u["state"] for u in failed["statements"]])
            explained, _, _ = self.execute(session, "SELECT * FROM " + qt, "EXPLAIN")
            assert self.results(explained, "PLAN")[0]["rows"]
            self.log("mysql.explain")
        finally:
            self.cleanup_sql(session, "DROP PROCEDURE IF EXISTS %s; DROP TABLE IF EXISTS %s;" % (qp, qt), "mysql.objects_cleanup")

    def postgres(self):
        profile, session = self.connect("postgres")
        namespace = self.prefix + "_schema"
        table = self.prefix + "_%rows"
        func = self.prefix + "_double"
        temporary = self.prefix + "_temp"
        qt = '"%s"."%s"' % (namespace, table)
        qf = '"%s"."%s"' % (namespace, func)
        try:
            self.execute(session, 'CREATE SCHEMA "%s"; CREATE TABLE %s (id INT PRIMARY KEY,val VARCHAR(80)); INSERT INTO %s VALUES(1,\'first\'),(2,\'second\'),(3,\'third\');' % (namespace, qt, qt), "SCRIPT")
            self.metadata_preview("postgres", profile, session, table, namespace)
            script = ("CREATE TEMP TABLE %s(n INT);\n" % temporary
                      + "DO $body$\nDECLARE v INT := 7;\nBEGIN\n INSERT INTO %s VALUES(v);\n RAISE NOTICE 'inside;block';\nEND;\n$body$;\n" % temporary
                      + "SELECT n,'not;a;delimiter' AS note FROM %s;" % temporary)
            block, plan, _ = self.execute(session, script, "SCRIPT")
            assert len(plan["units"]) == 3 and str(self.results(block)[0]["rows"][0][0]) == "7"
            assert any("inside;block" in w for unit in block["statements"] for w in unit.get("warnings", []))
            self.log("postgres.dollar_block_session", units=3, value=7, warningPreserved=True)
            self.execute(session, "CREATE FUNCTION %s(p INT) RETURNS INT LANGUAGE SQL AS $fn$ SELECT p*2 $fn$;" % qf, "BLOCK")
            routines = self.api("GET", "/api/connections/%s/objects" % profile["id"], query={"kind": "routines", "catalog": session.get("catalog"), "schema": namespace})
            routine = next(r for r in routines if r["name"] == func and r["type"] == "FUNCTION")
            detail = self.api("GET", "/api/connections/%s/routine-detail" % profile["id"], query={"catalog": session.get("catalog"), "schema": namespace, "name": func, "type": "FUNCTION", "specificName": routine.get("specificName")})
            assert detail["definition"] and [p["position"] for p in detail["parameters"]] == [1, 2]
            parameters = detail["parameters"]
            for parameter in parameters:
                if parameter["mode"] == "IN":
                    parameter["value"] = 9
            called, _, _ = self.execute(session, detail["callSql"], "CALL", parameters=parameters)
            outputs = [p for r in self.results(called, "OUT_PARAMETERS") for p in r["parameters"]]
            assert len(outputs) == 1 and str(outputs[0]["value"]) == "18"
            self.log("postgres.callable_function", returned=18)
            explained, _, _ = self.execute(session, "SELECT * FROM " + qt, "EXPLAIN")
            raw = self.results(explained, "PLAN")[0]["rows"][0][0]
            assert json.loads(raw)[0]["Plan"]["Node Type"]
            self.log("postgres.explain_json")
            observer = self.new_session(profile["id"])
            self.api("POST", "/api/sessions/%s/transaction" % session["id"], {"action": "AUTO_COMMIT", "autoCommit": False})
            self.execute(session, "INSERT INTO %s VALUES(90,'uncommitted')" % qt)
            assert str(self.scalar(observer, "SELECT COUNT(*) FROM %s WHERE id=90" % qt)) == "0"
            self.api("POST", "/api/sessions/%s/transaction" % session["id"], {"action": "ROLLBACK"})
            assert str(self.scalar(session, "SELECT COUNT(*) FROM %s WHERE id=90" % qt)) == "0"
            self.api("POST", "/api/sessions/%s/transaction" % session["id"], {"action": "AUTO_COMMIT", "autoCommit": True})
            self.log("postgres.transaction_rollback", checkedFromSecondSession=True)
            sleeping, _, _ = self.submit(session, "SELECT pg_sleep(20)", timeoutSeconds=30)
            deadline = time.monotonic() + 5
            while sleeping["state"] == "QUEUED" and time.monotonic() < deadline:
                time.sleep(0.05)
                sleeping = self.api("GET", "/api/executions/" + sleeping["id"])
            time.sleep(0.15)
            started = time.monotonic()
            requested = self.api("POST", "/api/executions/%s/cancel" % sleeping["id"], {})
            assert requested["state"] in {"CANCEL_REQUESTED", "CANCELED"}
            canceled = self.poll(requested, timeout=12)
            assert canceled["state"] == "CANCELED", "Driver did not acknowledge cancellation: " + canceled["state"]
            assert str(self.scalar(session, "SELECT 1")) == "1"
            self.log("postgres.cancel", finalState=canceled["state"], elapsedMs=round((time.monotonic() - started) * 1000), sessionReusable=True)
        finally:
            try:
                self.api("POST", "/api/sessions/%s/transaction" % session["id"], {"action": "AUTO_COMMIT", "autoCommit": True})
            except Exception as error:
                self.log("postgres.cleanup_transaction", "FAIL", message=self.clean(error))
            self.cleanup_sql(session, 'DROP SCHEMA IF EXISTS "%s" CASCADE;' % namespace, "postgres.objects_cleanup")

    def cleanup_sql(self, session, sql, label):
        try:
            self.execute(session, sql, "SCRIPT")
            self.log(label)
        except Exception as error:
            self.log(label, "FAIL", message=self.clean(error))

    def cleanup_resources(self):
        cleaned = {"sessions": 0, "connections": 0}
        for resource, identifiers in (("sessions", self.session_ids), ("connections", self.connection_ids)):
            for identifier in reversed(identifiers):
                try:
                    self.api("DELETE", "/api/%s/%s" % (resource, identifier))
                    cleaned[resource] += 1
                except Exception as error:
                    self.log(resource + ".cleanup", "FAIL", message=self.clean(error))
        self.log("resources_cleanup", **cleaned)

    def run(self):
        bootstrap = self.api("GET", "/api/bootstrap")
        self.token = bootstrap["token"]
        self.secrets.append(self.token)
        self.log("bootstrap", version=bootstrap.get("version"), runPrefix=self.prefix)
        try:
            for vendor in ("mysql", "postgres"):
                if getattr(self.args, vendor + "_url"):
                    try:
                        getattr(self, vendor)()
                    except Exception as error:
                        self.log(vendor + ".workflow", "FAIL", message=self.clean(error), errorType=type(error).__name__)
        finally:
            self.cleanup_resources()
        failures = sum(item["status"] == "FAIL" for item in self.logs)
        self.log("summary", "PASS" if failures == 0 else "FAIL", checks=len(self.logs), failures=failures)
        return 0 if failures == 0 else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://127.0.0.1:18089")
    parser.add_argument("--use-bundled-drivers", action="store_true", help="Select the bundled MySQL/PostgreSQL profiles without uploading a driver")
    for vendor in ("mysql", "postgres"):
        parser.add_argument("--" + vendor + "-url")
        parser.add_argument("--" + vendor + "-driver")
        parser.add_argument("--" + vendor + "-user", default="root" if vendor == "mysql" else "postgres")
        parser.add_argument("--" + vendor + "-password", default=os.getenv("TOOLBOX_" + vendor.upper() + "_PASSWORD", ""))
    args = parser.parse_args()
    if not args.mysql_url and not args.postgres_url:
        parser.error("Supply at least one disposable database URL and its external JDBC driver")
    for vendor in ("mysql", "postgres"):
        if not args.use_bundled_drivers and getattr(args, vendor + "_url") and not getattr(args, vendor + "_driver"):
            parser.error("--%s-driver is required when --%s-url is supplied" % (vendor, vendor))
    return Smoke(args).run()


if __name__ == "__main__":
    sys.exit(main())
