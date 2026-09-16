#!/usr/bin/env python3
"""Validate cell editing against disposable databases through a running Toolbox JAR.

Uses bundled drivers and Python's standard library. Creates uniquely named tables,
connections and sessions and cleans them up. Never use a production database.
With no vendor URLs supplied, runs against a fresh in-memory H2 database.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import sys
import uuid

spec = importlib.util.spec_from_file_location("toolbox_smoke", Path(__file__).with_name("smoke-v2.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CellSmoke(module.Smoke):
    def check_vendor(self, vendor):
        if vendor == "h2":
            driver = next(d for d in self.api("GET", "/api/drivers") if d.get("bundled") and d["driverClass"] == "org.h2.Driver")
            profile = self.api("POST", "/api/connections", {"name": self.prefix + " h2", "driverId": driver["id"], "jdbcUrl": "jdbc:h2:mem:" + self.prefix, "username": "sa", "password": ""})
            self.connection_ids.append(profile["id"])
            session = self.new_session(profile["id"])
            self.log("h2.connection", driverVersion=driver["version"])
        else:
            profile, session = self.connect(vendor)
        other = self.new_session(profile["id"])
        quote = "`" if vendor == "mysql" else '"'
        name = self.prefix + "_cells"
        table = quote + name + quote
        schema = "public" if vendor == "postgres" else "PUBLIC" if vendor == "h2" else None
        context = {"catalog": session.get("catalog"), "schema": schema, "table": name}

        def preview():
            record, _, _ = self.execute(session, mode="TABLE_PREVIEW", **context)
            result = self.results(record)[0]
            assert result["columns"][2]["editable"], result.get("readOnlyReason") or result["columns"][2].get("readOnlyReason")
            return record

        def edit(source, column, value, null=False, expected="SUCCEEDED"):
            return self.execute(session, mode="CELL_UPDATE", expected=expected,
                                cellChange={"executionId": source["id"], "result": 0, "row": 0, "column": column, "value": value, "nullValue": null})

        def external(column):
            return self.scalar(other, "SELECT %s FROM %s WHERE id=1234567890123456789 AND part='a'" % (column, table))

        try:
            self.execute(session, "CREATE TABLE %s(id BIGINT, part VARCHAR(10), val VARCHAR(80), amount DECIMAL(30,19), PRIMARY KEY(id,part))" % table)
            self.execute(session, "INSERT INTO %s VALUES(1234567890123456789,'a','old',0),(1234567890123456789,'b','second',0)" % table)
            source = preview()
            saved, plan, request = edit(source, 2, "quote ' ; <script> 数据")
            assert plan["confirmationRequired"] and self.results(saved, "UPDATE_COUNT")[0]["updateCount"] == 1
            assert self.api("POST", "/api/executions", request)["id"] == saved["id"]
            assert external("val") == "quote ' ; <script> 数据"
            assert self.scalar(other, "SELECT val FROM %s WHERE part='b'" % table) == "second"
            self.log(vendor + ".composite_key_bound_write_and_idempotency")
            if vendor == "mysql":
                namespace = self.prefix + "_scope"
                qualified = "`" + namespace + "`." + table
                try:
                    self.execute(session, "CREATE DATABASE `" + namespace + "`")
                    self.execute(session, "CREATE TABLE %s LIKE %s; INSERT INTO %s VALUES(1234567890123456789,'a','other database',0)" % (qualified, table, qualified), "SCRIPT")
                    scoped, _, _ = self.execute(session, mode="TABLE_PREVIEW", catalog="", schema=namespace, table=name)
                    edit(scoped, 2, "correct database")
                    assert self.scalar(other, "SELECT val FROM " + qualified) == "correct database"
                    assert external("val") == "quote ' ; <script> 数据"
                    self.log("mysql.schema_alias_targets_previewed_database")
                    self.execute(session, "CREATE TABLE `%s`.readonly_cells(id INT PRIMARY KEY, val VARCHAR(80)) ENGINE=MyISAM" % namespace)
                    readonly, _, _ = self.execute(session, mode="TABLE_PREVIEW", catalog=namespace, table="readonly_cells")
                    assert "InnoDB" in self.results(readonly)[0]["readOnlyReason"]
                    self.log("mysql.nontransactional_table_readonly")
                finally:
                    self.cleanup_sql(session, "DROP DATABASE IF EXISTS `" + namespace + "`", "mysql.scope_cleanup")
            edit(preview(), 3, "1.1234567890123456789")
            assert external("amount") == "1.1234567890123456789"
            rounded, _, _ = edit(preview(), 3, "1.12345678901234567891", expected="FAILED")
            assert external("amount") == "1.1234567890123456789"
            self.log(vendor + ".precision_and_rounding_rollback")
            edit(preview(), 2, None, True)
            assert external("val") is None
            edit(preview(), 2, "")
            assert external("val") == ""
            self.log(vendor + ".null_and_empty")
            source = preview()
            self.execute(other, "UPDATE %s SET val='concurrent' WHERE part='a'" % table)
            conflict, _, _ = edit(source, 2, "mine", expected="FAILED")
            assert "冲突" in conflict["message"] and external("val") == "concurrent"
            self.log(vendor + ".concurrent_conflict")
            self.api("POST", "/api/sessions/%s/transaction" % session["id"], {"action": "AUTO_COMMIT", "autoCommit": False})
            self.execute(session, "UPDATE %s SET val='prior' WHERE part='b'" % table)
            edit(preview(), 2, "x" * 100, expected="FAILED")
            assert self.scalar(session, "SELECT val FROM %s WHERE part='b'" % table) == "prior"
            edit(preview(), 2, "pending")
            assert external("val") == "concurrent"
            self.api("POST", "/api/sessions/%s/transaction" % session["id"], {"action": "ROLLBACK"})
            assert external("val") == "concurrent"
            assert self.scalar(other, "SELECT val FROM %s WHERE part='b'" % table) == "second"
            self.log(vendor + ".savepoint_failure_preserves_prior_work_and_rollback")
            edit(preview(), 2, "committed")
            self.api("POST", "/api/sessions/%s/transaction" % session["id"], {"action": "COMMIT"})
            assert external("val") == "committed"
            self.log(vendor + ".manual_commit")
        finally:
            self.api("POST", "/api/sessions/%s/transaction" % session["id"], {"action": "AUTO_COMMIT", "autoCommit": True})
            self.cleanup_sql(session, "DROP TABLE IF EXISTS " + table, vendor + ".objects_cleanup")

    def run(self):
        self.token = self.api("GET", "/api/bootstrap")["token"]
        self.secrets.append(self.token)
        vendors = [v for v in ("mysql", "postgres") if getattr(self.args, v + "_url")]
        try:
            for vendor in vendors or ["h2"]:
                try:
                    self.check_vendor(vendor)
                except Exception as error:
                    self.log(vendor + ".workflow", "FAIL", message=self.clean(error))
        finally:
            self.cleanup_resources()
        failures = sum(item["status"] == "FAIL" for item in self.logs)
        self.log("summary", "FAIL" if failures else "PASS", checks=len(self.logs), failures=failures)
        return bool(failures)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://127.0.0.1:18090")
    for vendor in ("mysql", "postgres"):
        parser.add_argument("--" + vendor + "-url")
        parser.add_argument("--" + vendor + "-user", default="root" if vendor == "mysql" else "postgres")
        parser.add_argument("--" + vendor + "-password", default=os.getenv("TOOLBOX_" + vendor.upper() + "_PASSWORD", ""))
    args = parser.parse_args()
    args.use_bundled_drivers = True
    return CellSmoke(args).run()


if __name__ == "__main__":
    sys.exit(main())
