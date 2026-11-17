"""Print every skipped test (with its reason) and a pass/fail summary from surefire XML reports.

JUnit assumption failures without a message only say "assumption is not true"; for
those, the assumption statement itself is looked up in the test source (from the
stack frame that raised it) and printed as the reason.
"""

import glob
import os
import re
import xml.etree.ElementTree as ET

FRAME = re.compile(r"at (?:[\w.@-]+/)?([\w.$]+)\.([\w$<>]+)\(([\w$]+\.java):(\d+)\)")
_source_cache = {}


def find_source(module_dir, classname, filename):
    pkg = classname.rsplit(".", 1)[0].replace(".", "/")
    key = (module_dir, pkg, filename)
    if key not in _source_cache:
        hits = glob.glob(os.path.join(module_dir, "src", "test", "java", pkg, filename))
        _source_cache[key] = hits[0] if hits else None
    return _source_cache[key]


def assumption_statement(module_dir, text):
    """Return the test-source line that raised the assumption, e.g. assumeTrue(x != null)."""
    for cls, _method, filename, line in FRAME.findall(text or ""):
        if cls.startswith(("org.junit.", "org.opentest4j.", "java.", "jdk.", "sun.")):
            continue
        src = find_source(module_dir, cls.split("$")[0], filename)
        if not src:
            continue
        try:
            with open(src, encoding="utf-8") as fh:
                lines = fh.readlines()
            stmt = lines[int(line) - 1].strip()
        except (OSError, IndexError, ValueError):
            continue
        return f"{stmt}  [{filename}:{line}]"
    return None


totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
skipped = []
for path in sorted(glob.glob("**/target/surefire-reports/TEST-*.xml", recursive=True)):
    module_dir = path.split("/target/surefire-reports/")[0]
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print(f"WARN cannot parse {path}: {exc}")
        continue
    for key in totals:
        totals[key] += int(root.get(key, "0") or 0)
    for case in root.iter("testcase"):
        skip = case.find("skipped")
        if skip is None:
            continue
        message = (skip.get("message") or "").strip()
        text = skip.text or ""
        reason = message.splitlines()[0] if message else ""
        if not reason or "assumption is not true" in reason or reason.startswith("Assumption failed"):
            stmt = assumption_statement(module_dir, text or message)
            if stmt:
                reason = f"{reason or 'assumption failed'}; assumption not met: {stmt}"
        if not reason:
            reason = text.strip().splitlines()[0] if text.strip() else "skipped (no message reported)"
        skipped.append(f"{case.get('classname')}#{case.get('name')}: {reason}")

print("=== Skipped tests (test: reason) ===")
for line in skipped:
    print("SKIPPED", line)
passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
print(
    f"=== Surefire totals over all modules of this job: tests={totals['tests']} passed={passed} "
    f"failures={totals['failures']} errors={totals['errors']} skipped={totals['skipped']} ==="
)
