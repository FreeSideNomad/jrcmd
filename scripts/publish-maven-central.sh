#!/bin/bash
#
# Publish to Maven Central via GitHub Actions
#
# This script validates and publishes a draft GitHub release,
# which triggers the GitHub Actions workflow to deploy to Maven Central.
#
# Usage:
#   ./scripts/publish-maven-central.sh <tag>
#   ./scripts/publish-maven-central.sh v0.2.0
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

echo_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

echo_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check for tag argument
if [ -z "$1" ]; then
    echo_error "Usage: $0 <tag>"
    echo "  Example: $0 v0.2.0"
    exit 1
fi

TAG="$1"

# Validate tag format
if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo_error "Invalid tag format: $TAG"
    echo "  Expected format: v<major>.<minor>.<patch> (e.g., v0.2.0)"
    exit 1
fi

# Extract version from tag (remove 'v' prefix)
TAG_VERSION="${TAG#v}"

echo_info "Validating release for tag: $TAG"

# Check gh CLI is installed
if ! command -v gh &> /dev/null; then
    echo_error "GitHub CLI (gh) is not installed."
    echo "  Install: https://cli.github.com/"
    exit 1
fi

# Check gh is authenticated
if ! gh auth status &> /dev/null; then
    echo_error "GitHub CLI is not authenticated."
    echo "  Run: gh auth login"
    exit 1
fi

# Check tag exists locally
if ! git rev-parse "$TAG" &> /dev/null; then
    echo_error "Tag $TAG does not exist locally."
    echo "  Create it with: git tag -a $TAG -m 'Release $TAG'"
    exit 1
fi

echo_info "Tag $TAG exists"

# Check tag exists on remote
if ! git ls-remote --tags origin | grep -q "refs/tags/$TAG"; then
    echo_error "Tag $TAG does not exist on remote."
    echo "  Push it with: git push origin $TAG"
    exit 1
fi

echo_info "Tag $TAG exists on remote"

# Get version from pom.xml
POM_VERSION=$(grep -m1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')

echo_info "pom.xml version: $POM_VERSION"
echo_info "Tag version: $TAG_VERSION"

# Validate pom.xml version matches tag
if [ "$POM_VERSION" != "$TAG_VERSION" ]; then
    echo_error "Version mismatch!"
    echo "  pom.xml version: $POM_VERSION"
    echo "  Tag version: $TAG_VERSION"
    echo ""
    echo "  Update pom.xml to match the tag version."
    exit 1
fi

echo_info "Version validated: $TAG_VERSION"

# Check for SNAPSHOT
if [[ "$POM_VERSION" == *"-SNAPSHOT"* ]]; then
    echo_error "Cannot publish SNAPSHOT version to Maven Central"
    echo "  Current version: $POM_VERSION"
    exit 1
fi

# Check release exists and get its status
RELEASE_STATUS=$(gh release view "$TAG" --json isDraft --jq '.isDraft' 2>/dev/null || echo "NOT_FOUND")

if [ "$RELEASE_STATUS" = "NOT_FOUND" ]; then
    echo_error "No GitHub release found for tag $TAG"
    echo "  The release workflow may still be running."
    echo "  Check: gh run list --workflow=release.yml"
    exit 1
fi

if [ "$RELEASE_STATUS" = "false" ]; then
    echo_warn "Release $TAG is already published!"
    echo ""
    gh release view "$TAG"
    exit 0
fi

echo_info "Draft release found for $TAG"

# Show release info
echo ""
echo "======================================"
echo "  Publishing to Maven Central"
echo "======================================"
echo "  Tag: $TAG"
echo "  Version: $TAG_VERSION"
echo "  Artifact: com.ivamare:commandbus"
echo "======================================"
echo ""

# List release assets
echo_info "Release assets:"
gh release view "$TAG" --json assets --jq '.assets[].name' | while read -r asset; do
    echo "  - $asset"
done
echo ""

read -p "Publish release to trigger Maven Central deployment? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 0
fi

# Publish the release
echo_info "Publishing release $TAG..."
gh release edit "$TAG" --draft=false

echo ""
echo_info "Release $TAG published!"
echo_info "GitHub Actions will now deploy to Maven Central."
echo ""
echo_info "Monitor deployment: gh run list --workflow=release.yml"
echo_info "View release: https://github.com/FreeSideNomad/jrcmd/releases/tag/$TAG"
echo_info "Maven Central (after deployment): https://central.sonatype.com/artifact/com.ivamare/commandbus/$TAG_VERSION"
