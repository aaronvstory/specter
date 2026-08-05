"""Source-level safety guards for LspScope.java.

LspScope writes the ROOT-OWNED LSPosed scope DB from inside the app. Its two load-bearing safety
invariants — (1) it can only ever scope Specter's OWN module, never another module's (the fleet rule:
never touch GeerGit's mid 101), and (2) a target package can't inject SQL — live in a class that
imports android.content.Context + SQLiteDatabase, so it can't be compiled in the plain-JVM harness.
We pin the invariants at the SOURCE instead, exactly the way test_ipcheck's
test_zone_table_matches_the_android_side pins Dnsbl.java. A refactor that broke scope isolation or the
bound parameter would fail here.
"""
import re
from pathlib import Path

SRC = (Path(__file__).resolve().parents[1] / "xposed-module" / "app" / "src" / "main" / "java" /
       "com" / "specter" / "module" / "gen" / "LspScope.java").read_text("utf-8")


def test_scope_insert_targets_only_specters_own_module():
    # The mid is chosen by a sub-select on modules WHERE module_pkg_name = the SPECTER_PKG constant —
    # NEVER a hardcoded or caller-supplied mid — so a scope write can only land on Specter's own module
    # row, never another's. The constant is literally "com.specter".
    assert "SELECT mid, ?, 0 FROM modules WHERE module_pkg_name='" in SRC
    assert re.search(r"module_pkg_name='\"\s*\+\s*SPECTER_PKG", SRC), \
        "the mid must be selected by module_pkg_name=SPECTER_PKG, not a literal/other mid"
    assert 'SPECTER_PKG = "com.specter"' in SRC
    # There must be NO VALUES-form scope insert (which could carry a literal/other mid); only the SELECT
    # form that derives the mid from Specter's module row is allowed.
    assert not re.search(r"INSERT[^;]*INTO scope[^;]*VALUES", SRC), \
        "scope INSERT must derive Specter's mid via SELECT, never a VALUES row with a supplied mid"


def test_target_package_is_a_bound_parameter_not_interpolated():
    # app_pkg_name is the bound '?' in the INSERT ... SELECT, not string-concatenated into the SQL.
    assert "SELECT mid, ?, 0 FROM modules" in SRC
    # The only thing interpolated into the SQL is the SPECTER_PKG constant — never a variable package name
    # (which would be an injection vector).
    assert re.search(r"module_pkg_name='\"\s*\+\s*SPECTER_PKG\s*\+\s*\"'", SRC)


def test_scope_insert_is_idempotent():
    assert "INSERT OR IGNORE INTO scope" in SRC


def test_framework_keys_are_exactly_android_and_system():
    m = re.search(r"isFrameworkKey\(String p\)\s*\{\s*return ([^;]+);", SRC)
    assert m, "isFrameworkKey not found"
    body = m.group(1)
    assert '"android".equals(p)' in body and '"system".equals(p)' in body
    # nothing broader — no startsWith, no third key that could scope more of the framework than intended.
    assert "startsWith" not in body and body.count(".equals(p)") == 2


def test_non_framework_packages_must_pass_validPkg():
    # A non-framework pkg is validated with the same grammar RootWriter enforces at its su boundary; an
    # invalid name throws before the DB is touched.
    assert "RootWriter.validPkg(p)" in SRC
    assert "throw new ScopeException" in SRC


def test_scope_db_write_back_is_atomic():
    # Edit a temp copy, then mv it over the live root DB — never a partial in-place edit of the config.
    assert ".specter.tmp" in SRC and "mv -f " in SRC
