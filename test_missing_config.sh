#!/bin/bash
unset OPENCODE_URL
unset OPENCODE_PASSWORD
unset OPENCODE_DIRECTORY
./gradlew clean :core:testing:integrationTest --no-daemon --console=plain
