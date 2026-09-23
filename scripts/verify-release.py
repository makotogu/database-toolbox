#!/usr/bin/env python3
"""Verify a standalone JAR in a new directory, including its default JDBC drivers.

Requires Python 3 and Java 8 for development verification only. The application
itself does not require Python. All test files remain in the printed temp folder.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--h2-driver", help="Optional additional H2 JAR to test user imports alongside defaults")
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", type=int, default=18090)
    args = parser.parse_args()
    directory = Path(tempfile.mkdtemp(prefix="数据库工作台 release "))
    artifact = directory / "database-toolbox.jar"
    shutil.copy2(args.jar, artifact)
    base = "http://127.0.0.1:%s" % args.port
    token = None
    checks = []

    def log(name, **details):
        item = dict(check=name, status="PASS", **details)
        checks.append(item)
        print(json.dumps(item, ensure_ascii=False), flush=True)

    def request(method, path, data=None, headers=None):
        actual = dict(headers or {})
        if token:
            actual["X-Toolbox-Token"] = token
        if isinstance(data, dict):
            data = json.dumps(data).encode()
            actual["Content-Type"] = "application/json"
        with urllib.request.urlopen(urllib.request.Request(base + path, data=data, method=method, headers=actual), timeout=5) as response:
            return response.read()

    def api(method, path, data=None, headers=None):
        result = json.loads(request(method, path, data, headers))
        assert result["success"], result.get("message")
        return result["data"]

    def denied(method, path, data=None, headers=None):
        try:
            request(method, path, data, headers)
        except urllib.error.HTTPError as error:
            assert error.code == 403, error.code
            return
        raise AssertionError("Request should have been denied")

    def command(port):
        return [args.java, "-Djava.net.preferIPv4Stack=true", "-jar", str(artifact),
                "--server.address=127.0.0.1", "--server.port=" + str(port)]

    def start(label):
        output = open(directory / (label + ".log"), "wb")
        process = subprocess.Popen(command(args.port), cwd=str(directory), stdout=output, stderr=subprocess.STDOUT)
        output.close()
        try:
            for attempt in range(200):
                assert process.poll() is None, "Startup failed; inspect " + str(directory)
                try:
                    bootstrap = api("GET", "/api/bootstrap")
                    assert Path(bootstrap["storageRoot"]).resolve() == (directory / "data").resolve()
                    return process, bootstrap["token"]
                except (OSError, urllib.error.URLError):
                    time.sleep(0.1)
            raise AssertionError("Startup timeout")
        except BaseException:
            stop(process)
            raise

    def stop(process):
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def execute(session, sql):
        payload = dict(sessionId=session["id"], sql=sql, mode="SCRIPT", requestId=uuid.uuid4().hex)
        plan = api("POST", "/api/executions/prepare", payload)
        payload["confirmationToken"] = plan["confirmationToken"]
        execution = api("POST", "/api/executions", payload)
        for attempt in range(200):
            if execution["state"] not in ("QUEUED", "RUNNING", "CANCEL_REQUESTED"):
                assert execution["state"] == "SUCCEEDED", execution
                return execution
            time.sleep(0.05)
            execution = api("GET", "/api/executions/" + execution["id"])
        raise AssertionError("Execution timeout")

    with zipfile.ZipFile(artifact) as jar:
        entries = jar.namelist()
        static_resources = ("index.html", "workbench/app.js", "workbench/connection-fields.js", "workbench/sql-highlight.js", "workbench/sql-drafts.js", "workbench/menus.js", "workbench/menus.css", "workbench/workbench.css", "workbench/favicon.svg")
        for resource in static_resources:
            assert "BOOT-INF/classes/static/" + resource in entries
        assert not any(name.startswith("BOOT-INF/lib/") and any(word in name.lower() for word in ("h2-", "mysql-connector", "postgresql-")) for name in entries)
        assert not any("com/example/dbtoolbox/" + name + "/" in entry for name in ("sync", "backup", "job", "datasource") for entry in entries)
        catalog = json.loads(jar.read("BOOT-INF/classes/bundled-drivers/catalog.json"))
        assert len(catalog) == 3 and all(item["bundled"] for item in catalog)
        import hashlib
        for profile in catalog:
            for filename, expected_hash in zip(profile["files"], profile["sha256"]):
                assert hashlib.sha256(jar.read("BOOT-INF/classes/bundled-drivers/" + filename)).hexdigest() == expected_hash
    log("packaging", bundledStaticResources=len(static_resources), bundledDriverProfiles=3, jdbcJarsOnApplicationClasspath=0)
    process = None
    try:
        process, token = start("first-start")
        assert len(list(directory.glob("*.jar"))) == 1
        log("empty_directory_start", directory=str(directory), version=api("GET", "/api/bootstrap")["version"])
        assert request("GET", "/")
        for resource in static_resources:
            assert request("GET", "/" + resource)
        log("static_resources")
        saved_token, token = token, None
        denied("POST", "/api/sessions", {})
        token = saved_token
        denied("POST", "/api/sessions", {}, {"Origin": "https://unrelated.invalid"})
        denied("GET", "/api/bootstrap", headers={"Host": "unrelated.invalid"})
        denied("GET", "/api/bootstrap", headers={"Sec-Fetch-Site": "cross-site"})
        log("local_request_guards", negativeCases=4)

        defaults = api("GET", "/api/drivers")
        assert len(defaults) == 3 and all(item["bundled"] for item in defaults)
        driver = next(item for item in defaults if item["driverClass"] == "org.h2.Driver")
        assert driver["driverClass"] == "org.h2.Driver"
        log("default_drivers_ready", drivers=[item["name"] + " " + item["version"] for item in defaults])
        connection = api("POST", "/api/connections", dict(name="Release persistence", driverId=driver["id"], jdbcUrl="jdbc:h2:file:./verification-db", username="sa", password="", saveSqlDrafts=True))
        session = api("POST", "/api/sessions", {"connectionId": connection["id"]})
        execute(session, "CREATE TABLE verification(n INTEGER); INSERT INTO verification VALUES (42); SELECT * FROM verification;")
        api("DELETE", "/api/sessions/" + session["id"])
        log("bundled_driver_query", withoutUpload=True)
        draft_sql = "INSERT INTO verification VALUES (99); -- saved but never executed"
        draft_path = "/api/connections/" + connection["id"] + "/sql-drafts"
        draft = api("PUT", draft_path, dict(revision=api("GET", draft_path)["revision"], requestId=str(uuid.uuid4()),
            tabs=[dict(id=str(uuid.uuid4()), name="Restart fixture.sql", sql=draft_sql)]))
        assert draft_sql.encode() not in (directory / "data/config/sql-drafts.enc").read_bytes()
        imported = None
        if args.h2_driver:
            boundary = "Release" + uuid.uuid4().hex
            body = ("--%s\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nRelease imported H2\r\n" % boundary).encode()
            body += ("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"h2.jar\"\r\nContent-Type: application/java-archive\r\n\r\n" % boundary).encode()
            body += Path(args.h2_driver).read_bytes() + ("\r\n--%s--\r\n" % boundary).encode()
            imported = api("POST", "/api/drivers/import", body, {"Content-Type": "multipart/form-data; boundary=" + boundary})
            assert not imported["bundled"]
            assert len(api("GET", "/api/drivers")) == 4
            log("user_driver_coexists")

        with open(directory / "second-instance.log", "wb") as output:
            second = subprocess.Popen(command(args.port + 1), cwd=str(directory), stdout=output, stderr=subprocess.STDOUT)
        try:
            assert second.wait(timeout=20) != 0
        finally:
            stop(second)
        assert "数据目录正由另一个工作台使用" in (directory / "second-instance.log").read_text()
        log("same_directory_lock")
        stop(process)
        token = None
        process, token = start("restart")
        assert any(item["id"] == driver["id"] for item in api("GET", "/api/drivers"))
        after = api("GET", "/api/drivers")
        assert len(after) == (4 if imported else 3)
        if imported:
            assert any(item["id"] == imported["id"] for item in after)
        assert any(item["id"] == connection["id"] for item in api("GET", "/api/connections"))
        session = api("POST", "/api/sessions", {"connectionId": connection["id"]})
        result = execute(session, "SELECT n FROM verification")
        assert any(item.get("rows") == [[42]] for unit in result["statements"] for item in unit["results"]), result
        api("DELETE", "/api/sessions/" + session["id"])
        log("restart_persistence", driverRetained=True, connectionRetained=True, queryValue=42)
        restored_draft = api("GET", draft_path)
        assert restored_draft == draft
        log("sql_draft_restart", textRetained=True, autoExecuted=False)
    finally:
        stop(process)
    (directory / "verification.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2))
    log("summary", checks=len(checks), failures=0)


if __name__ == "__main__":
    main()
