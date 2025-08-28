#!/usr/bin/env python3
#
# Copyright (C) 2025 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""A repo hook to check for modifications in 'external' directories."""

import re
import subprocess
import sys

def main():
  """Main function for the repo hook."""

  commit_message = subprocess.check_output(
      ['git', 'log', '-1', '--pretty=%B']).decode('utf-8')

  try:
    files_changed = subprocess.check_output(
        ['git', 'diff', '--name-only', 'HEAD~1']).decode('utf-8').splitlines()
  except subprocess.CalledProcessError:
    files_changed = subprocess.check_output(
        ['git', 'diff', '--name-only', '--cached']).decode('utf-8').splitlines()

  modifies_external = any('/external/' in f for f in files_changed)

  if not modifies_external:
    sys.exit(0)

  # Looks for "UpstreamCL:" (case-insensitive) followed by at least one
  # non-whitespace character.
  valid_pattern = re.compile(r'(?i)UpstreamCL:\s+\S+')

  if valid_pattern.search(commit_message):
    sys.exit(0) # A valid tag was found, check passes.

  sys.stderr.write("""
================================================================================
ERROR: Change to auto-synced 'external/' directory
================================================================================
Your changes in this 'external/' directory will be overwritten by the next sync
from Jetpack.

PREFERRED WORKFLOW:
Make your change in the Jetpack source tree INSTEAD and wait for the sync.
In this case, no manual platform CL is needed.

PARALLEL WORKFLOW (if you cannot wait for the sync):
If you must land the change in platform, you MUST change Jetpack at the same
time and tag this platform CL with the Jetpack change ID. Amend your commit
message:

  UpstreamCL: aosp/123456

You can override this check by using the EXEMPT tag:

  UpstreamCL: EXEMPT <reason>
================================================================================
""")
  sys.exit(1)

if __name__ == '__main__':
  main()
