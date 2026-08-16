# Google Play closed-testing release automation

SpeakKeys releases an Android App Bundle to its Google Play **Closed testing >
Alpha** track after a pull request that changes the Android release build is
merged into `main`. The workflow is `.github/workflows/release-play.yml`.

The workflow will remain safely disabled until the configuration below exists.
Do not put any of these values in a commit, pull request, workflow file, issue,
or Actions log.

> [!IMPORTANT]
> This automation change also updates `app/build.gradle.kts`, so the pull request
> that introduces it may itself qualify as an Android release. Configure the
> protected closed-testing environment before merging, or expect that first run
> to fail safely at its missing-configuration check and rerun it after setup.

## Security model

The workflow separates building from publishing:

1. The build job checks out the trusted `main` revision associated with the
   merged event, runs unit tests, and creates an unsigned AAB. It receives only
   the Firebase Android configuration needed by the release build.
2. The publish job does not check out or execute repository code. It signs the
   downloaded AAB with the Play **upload key**, obtains a short-lived Google
   credential through Workload Identity Federation, and uploads the bundle to
   SpeakKeys' closed-testing Alpha track (`alpha` in the Developer API).

The app-signing key managed by Google Play must never be stored in GitHub. Only
use the separate, revocable upload key registered in Play App Signing.

All external actions are pinned to full commit SHAs. The workflow uploads only
the AAB, ProGuard mapping, and native debug symbols between its own jobs, keeps
that artifact for one week, and never uploads the keystore or a Google
credential.

The unsigned AAB artifact can be downloaded by signed-in readers of this public
repository. It contains the Firebase Android API key and client identifiers
that are necessarily shipped in every installed app; those values are public
identifiers, not authorization secrets. Restrict the API key to the SpeakKeys
package and Play app-signing certificate in Google Cloud, enforce Firebase
security rules, and enable App Check for supported services. The private upload
key, passwords, and Google publisher credential never enter that artifact.

## 1. Create the protected GitHub environment

In **Repository settings > Environments**, create an environment named
`google-play-closed-testing` and restrict its deployment branches to `main`.

Also protect `main` and require Code Owner approval. The checked-in
`.github/CODEOWNERS` assigns the release workflow, release build configuration,
and migration counter to `@nishantkumar1292`; the file has no enforcement until
the branch rule enables required Code Owner reviews.

A required reviewer provides the strongest protection for the signing key, but
it also changes releases from fully automatic to approval-based. Leave the
reviewer rule disabled only if fully automatic closed-testing releases are desired.

Set these environment variables; they are identifiers, not secrets:

| Variable | Value |
| --- | --- |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full provider resource name, such as `projects/123456789/locations/global/workloadIdentityPools/github/providers/speakkeys` |
| `GOOGLE_PLAY_SERVICE_ACCOUNT` | Service account email used for Play publishing |

They can be configured without exposing credential material:

```bash
gh variable set GCP_WORKLOAD_IDENTITY_PROVIDER --env google-play-closed-testing
gh variable set GOOGLE_PLAY_SERVICE_ACCOUNT --env google-play-closed-testing
```

The first two commands prompt for their values. They are resource identifiers
and are safe to store as environment variables.

Set these environment secrets:

| Secret | Purpose |
| --- | --- |
| `SPEAKKEYS_UPLOAD_KEYSTORE_BASE64` | Base64-encoded Play upload-key keystore |
| `SPEAKKEYS_STORE_PASSWORD` | Upload keystore password |
| `SPEAKKEYS_KEY_ALIAS` | Upload key alias |
| `SPEAKKEYS_KEY_PASSWORD` | Upload key password |

The real Firebase Android configuration is needed by the build job before the
protected publish environment is entered. Store it as this repository secret:

| Secret | Purpose |
| --- | --- |
| `SPEAKKEYS_GOOGLE_SERVICES_JSON_BASE64` | Base64-encoded `google-services.json` registered for `com.speakkeys.keyboard` |

Values can be sent from local files directly to GitHub CLI without printing
them. On macOS:

```bash
base64 < app/google-services.json |
  gh secret set SPEAKKEYS_GOOGLE_SERVICES_JSON_BASE64

base64 < /absolute/path/to/speakkeys-upload.jks |
  gh secret set SPEAKKEYS_UPLOAD_KEYSTORE_BASE64 --env google-play-closed-testing

gh secret set SPEAKKEYS_STORE_PASSWORD --env google-play-closed-testing
gh secret set SPEAKKEYS_KEY_ALIAS --env google-play-closed-testing
gh secret set SPEAKKEYS_KEY_PASSWORD --env google-play-closed-testing
```

The last three commands prompt for their values without placing them on the
command line. GitHub encrypts Actions secrets, public repository readers cannot
view them, and this workflow does not start a credentialed job for an unmerged
pull request.

## 2. Configure Google Workload Identity Federation

Enable the Android Publisher API in a Google Cloud project, create a dedicated
service account, and connect GitHub's OIDC issuer to it through a Workload
Identity Pool and Provider. Grant the external identity only
`roles/iam.workloadIdentityUser` on that service account.

Enable the APIs used by Play publishing and the federated token exchange:

```bash
gcloud services enable \
  androidpublisher.googleapis.com \
  cloudresourcemanager.googleapis.com \
  iam.googleapis.com \
  iamcredentials.googleapis.com \
  sts.googleapis.com
```

See Google's [Android Publisher API setup guide](https://developers.google.com/android-publisher/getting_started)
and the Google authentication action's [Workload Identity Federation guide](https://github.com/google-github-actions/auth#workload-identity-federation)
for the provider and service-account setup.

Restrict the provider to this repository and workflow. Use this attribute
mapping:

```text
google.subject=assertion.sub,
attribute.repository_id=assertion.repository_id,
attribute.repository_owner_id=assertion.repository_owner_id,
attribute.ref=assertion.ref,
attribute.environment=assertion.environment,
attribute.event_name=assertion.event_name,
attribute.workflow_ref=assertion.workflow_ref,
attribute.runner_environment=assertion.runner_environment
```

Then use this attribute condition (line breaks are for readability):

```text
attribute.repository_id == '1206260724' &&
attribute.repository_owner_id == '26112797' &&
attribute.ref == 'refs/heads/main' &&
attribute.environment == 'google-play-closed-testing' &&
(attribute.event_name == 'pull_request_target' || attribute.event_name == 'workflow_dispatch') &&
attribute.workflow_ref == 'nishantkumar1292/SpeakKeys-Next/.github/workflows/release-play.yml@refs/heads/main' &&
attribute.runner_environment == 'github-hosted'
```

Repository and owner IDs are immutable and prevent a renamed or similarly
named repository from satisfying the policy. The environment and workflow
claims keep the credential scoped to this closed-testing publish job.

Grant `roles/iam.workloadIdentityUser` to the repository-specific principal set,
not to the whole pool:

```text
principalSet://iam.googleapis.com/projects/<PROJECT_NUMBER>/locations/global/workloadIdentityPools/<POOL_ID>/attribute.repository_id/1206260724
```

In Play Console, invite that service account only to
`com.speakkeys.keyboard` and grant **Release apps to testing tracks**. Do not
grant production-release, admin, finance, order, review, or access to other apps.

## 3. Verify closed testing

1. Confirm Play App Signing shows the upload certificate matching the keystore.
2. Confirm the highest version code ever uploaded in Play Console is no greater
   than `102`. If it is higher, raise `version_code_base` in the workflow above
   that value before the first run.
3. Confirm SpeakKeys' **Closed testing > Alpha** track exists and has the intended
   tester list or Google Group configured.
4. Manually run the workflow once.
5. Confirm tests pass, the bundle is signed, and an opted-in tester can install
   the closed-testing release.

Only Android release source and build changes trigger a release. Documentation,
tests, Apple-only shared code, and other repository maintenance do not.

The workflow intentionally pins uploads to `alpha`, the API identifier used by
SpeakKeys' **Closed testing > Alpha** track. Verify that track exists before the
first run. A custom closed track must use its exact Play Console track name.
Moving SpeakKeys to production requires a reviewed workflow change and
additional Play Console permission; it cannot happen by changing an Actions
variable.

The workflow generates `versionCode` as `102 + github.run_number` and passes it
to Gradle with a validated property. Runs are serialized, and a rerun keeps the
same code. After an ambiguous upload failure, check the target track in Play
Console first. Rerun the failed workflow if its code was not accepted; if Play
already accepted it but no usable release exists, start a new manual run to
allocate a fresh code. To roll back code, merge a new revert PR—never rerun an
old successful release. Local builds keep the committed `102` / `v0.1.2`
defaults. The automated `versionName` adds `+<versionCode>` to the current
committed version name for traceability.

App settings migrations deliberately use their own counter in `AppUpgrade.kt`.
Do not replace it with `BuildConfig.VERSION_CODE`; automated Play releases can
advance independently of settings-schema changes.

Keep the workflow file in place so its `github.run_number` sequence continues.
If the workflow is ever deleted and recreated, raise `version_code_base` above
the highest version code already used in Play before enabling it again.

The upload deliberately excludes the inherited `fastlane/metadata` directory;
those files are not used as SpeakKeys Play listing content.
