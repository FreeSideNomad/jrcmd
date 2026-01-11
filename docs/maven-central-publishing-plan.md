# Maven Central Publishing Plan

This document outlines the complete plan for publishing `commandbus-spring` to Maven Central and creating automated releases via GitHub Actions.

## Overview

| Item | Value |
|------|-------|
| **GroupId** | `com.ivamare` |
| **ArtifactId** | `commandbus` |
| **Initial Version** | `0.1.0` |
| **Namespace** | https://central.sonatype.com/publishing/namespaces (already registered) |
| **Repository** | https://github.com/FreeSideNomad/jrcmd |

## Release Workflow

```
Developer                    GitHub Actions                    Maven Central
    │                              │                                │
    ├─── git tag v0.1.0 ──────────►│                                │
    │    git push --tags           │                                │
    │                              ├─── Build & Test                │
    │                              ├─── Create Draft Release        │
    │                              │                                │
    │◄─── Review Draft Release ────┤                                │
    │                              │                                │
    ├─── Publish Release ─────────►│                                │
    │                              ├─── Build Artifacts             │
    │                              ├─── Sign with GPG               │
    │                              ├─── Deploy to Central ─────────►│
    │                              │                                │
    │◄─────────────────────────────┴────────── Available ◄──────────┤
```

---

## Step-by-Step Setup Instructions

### Step 1: Generate GPG Signing Key

Maven Central requires all artifacts to be signed with GPG. Generate a dedicated key for releases:

```bash
# Generate a new GPG key (use RSA 4096-bit)
gpg --full-generate-key
```

When prompted:
- **Kind of key**: `(1) RSA and RSA`
- **Key size**: `4096`
- **Expiration**: `0` (does not expire) or set an expiration
- **Real name**: `FreeSideNomad Release`
- **Email**: `freesidenomad@gmail.com`
- **Passphrase**: Create a strong passphrase (save it securely!)

```bash
# List your keys to find the key ID
gpg --list-secret-keys --keyid-format LONG

# Output will show something like:
# sec   rsa4096/ABCD1234EFGH5678 2024-01-01 [SC]
#       FULL40CHARACTERKEYIDHERE12345678901234567890
# uid                 [ultimate] FreeSideNomad Release <freesidenomad@gmail.com>

# Export the private key (you'll need this for GitHub secrets)
gpg --armor --export-secret-keys ABCD1234EFGH5678 > private-key.asc

# Publish your public key to a keyserver (required for Maven Central verification)
gpg --keyserver keyserver.ubuntu.com --send-keys ABCD1234EFGH5678
gpg --keyserver keys.openpgp.org --send-keys ABCD1234EFGH5678
```

**Important**: Keep `private-key.asc` secure. You'll paste its contents into GitHub Secrets.

### Step 2: Create Sonatype User Token

1. Go to https://central.sonatype.com/
2. Log in with your account
3. Navigate to **Account** → **Generate User Token**
4. Click **Generate User Token**
5. Save both the **username** and **password** shown (they look like random strings)

**Note**: These are NOT your Sonatype login credentials. They are generated API tokens specifically for publishing.

### Step 3: Configure GitHub Repository Secrets

Go to your GitHub repository → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

Add these secrets:

| Secret Name | Value |
|-------------|-------|
| `MAVEN_CENTRAL_USERNAME` | The username from Step 2 (e.g., `abc123XY`) |
| `MAVEN_CENTRAL_PASSWORD` | The password from Step 2 (e.g., `longrandomstring...`) |
| `GPG_PRIVATE_KEY` | Contents of `private-key.asc` from Step 1 |
| `GPG_PASSPHRASE` | The passphrase you created for the GPG key |

### Step 4: Update pom.xml

The pom.xml needs these additions for Maven Central compliance:

1. **Developer information** (required)
2. **Maven Central publishing profile** with GPG signing
3. **Central Publishing Maven Plugin** for deployment

These changes have been applied to the pom.xml in this repository.

### Step 5: Add Release Workflow

The GitHub Actions workflow at `.github/workflows/release.yml` handles:

1. **On tag push (v*)**: Builds, tests, and creates a draft GitHub Release
2. **On release publish**: Deploys signed artifacts to Maven Central

### Step 6: Create Your First Release

```bash
# 1. Ensure all changes are committed and pushed to main
git checkout main
git pull origin main

# 2. Create and push a version tag
git tag v0.1.0
git push origin v0.1.0

# 3. Wait for GitHub Actions to create the draft release
#    Go to: https://github.com/FreeSideNomad/jrcmd/releases

# 4. Review the draft release
#    - Edit release notes if needed
#    - Verify the artifacts look correct

# 5. Click "Publish release" to trigger Maven Central deployment

# 6. Monitor the deployment
#    - Check GitHub Actions for the deploy job status
#    - Artifacts appear on Maven Central within ~10-30 minutes after successful deployment
```

---

## File Changes Summary

### Modified Files

#### `pom.xml`
- Added `<developers>` section with organization info
- Added `release` profile with:
  - `maven-gpg-plugin` for artifact signing
  - `central-publishing-maven-plugin` for Maven Central deployment
  - Configuration to use environment variables for credentials

#### `.github/workflows/release.yml` (new file)
- Two-job workflow:
  - `build-release`: Triggered on tag push, creates draft release
  - `publish`: Triggered when release is published, deploys to Maven Central

### Required GitHub Secrets

| Secret | Purpose |
|--------|---------|
| `MAVEN_CENTRAL_USERNAME` | Sonatype user token username |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype user token password |
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private key |
| `GPG_PASSPHRASE` | GPG key passphrase |

---

## Versioning Strategy

This project uses **tag-based versioning**:

- The `pom.xml` stays at `1.0.0-SNAPSHOT` during development
- The release workflow extracts the version from the git tag (e.g., `v0.1.0` → `0.1.0`)
- The version is injected into the build during the release process

### Version Tag Format

Tags must follow the pattern: `v<MAJOR>.<MINOR>.<PATCH>`

Examples:
- `v0.1.0` - Initial release
- `v0.1.1` - Patch release
- `v0.2.0` - Minor release with new features
- `v1.0.0` - Major stable release

---

## Troubleshooting

### GPG Signing Fails

```
gpg: signing failed: No secret key
```

**Solution**: Ensure `GPG_PRIVATE_KEY` secret contains the full armored key including:
```
-----BEGIN PGP PRIVATE KEY BLOCK-----
...
-----END PGP PRIVATE KEY BLOCK-----
```

### Maven Central Deployment Fails

```
401 Unauthorized
```

**Solution**:
1. Verify `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` are the **user token** values, not your login credentials
2. Regenerate the user token at https://central.sonatype.com/

### Artifact Not Appearing on Maven Central

After successful deployment, artifacts may take 10-30 minutes to sync. Check:
- https://central.sonatype.com/artifact/com.ivamare/commandbus

### Key Not Found on Keyserver

```
gpg: keyserver receive failed: No data
```

**Solution**: Upload to multiple keyservers:
```bash
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
gpg --keyserver pgp.mit.edu --send-keys YOUR_KEY_ID
```

---

## Maintenance

### Updating Dependencies Before Release

```bash
# Check for dependency updates
mvn versions:display-dependency-updates
mvn versions:display-plugin-updates

# Update dependencies and commit
git add pom.xml
git commit -m "chore: update dependencies"
git push
```

### Creating a Patch Release

```bash
# For bug fixes after v0.1.0
git tag v0.1.1
git push origin v0.1.1
# Then publish the release on GitHub
```

### Deprecating a Version

If you need to deprecate a version on Maven Central, contact Sonatype support. You cannot delete published artifacts.

---

## Security Considerations

1. **Never commit secrets** - GPG keys and tokens stay in GitHub Secrets only
2. **Rotate tokens periodically** - Regenerate Sonatype user tokens annually
3. **GPG key backup** - Keep a secure backup of your GPG key and passphrase
4. **Review before publish** - The draft release step allows you to verify before public deployment
5. **Branch protection** - Consider requiring PR reviews before merging to main

---

## Checklist for First Release

- [ ] GPG key generated and public key published to keyservers
- [ ] Sonatype user token created
- [ ] GitHub secrets configured (4 secrets)
- [ ] pom.xml updated with developer info and release profile
- [ ] release.yml workflow added
- [ ] README.md updated with Maven dependency info
- [ ] Create tag `v0.1.0` and push
- [ ] Review and publish the draft release
- [ ] Verify artifact appears on Maven Central
