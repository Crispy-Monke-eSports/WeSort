#!/usr/bin/env python3
"""Summarize real JUnit reports, including aborted tests; fail if reports are missing."""
import glob
import sys
import xml.etree.ElementTree as ET

reports = sorted(glob.glob(sys.argv[1] + '/test-results/test/TEST-*.xml'))
if not reports:
    sys.exit('No JUnit XML reports found')
roots = [ET.parse(p).getroot() for p in reports]
print(' '.join(f'{key}={sum(int(r.get(key, 0)) for r in roots)}'
               for key in ('tests', 'failures', 'errors', 'skipped')))
for root in roots:
    for case in root.findall('testcase'):
        skip = case.find('skipped')
        if skip is not None:
            print(f'SKIPPED {root.get("name")}.{case.get("name")}: {skip.get("message", "no reason supplied")}')
if any(int(r.get('failures', 0)) or int(r.get('errors', 0)) for r in roots):
    sys.exit(1)
