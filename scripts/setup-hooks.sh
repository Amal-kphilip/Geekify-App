#!/bin/sh
# Configures Git to use versioned hooks in .githooks
git config core.hooksPath .githooks
echo "Configured git core.hooksPath to .githooks"
