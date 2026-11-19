# Backend: GitHub token registration (proposed)

Status: **proposed** — the app already calls it and tolerates `404`/`405` until it ships.
Language-agnostic HTTP contract; the backend is being rewritten in Go, so examples are Go.

## Why

Students code in the app (or in their own editor) against repos in the **`bau-build`** GitHub organization.
An org-level agent checks every push. To attribute a push to a BAU BUILD user, and to act for that user
(for example, to comment or check status as them), the backend needs the student's GitHub identity and token.
**Hashing does not work here.** A hash only lets the server check a token it is shown again. It cannot call
GitHub with it. So the token is stored **encrypted at rest**, not hashed.

The app also keeps its own copy in Keychain (iOS/macOS) or Keystore (Android), and calls GitHub directly with it.

## Flow

1. The student creates a fine-grained PAT. Resource owner: `bau-build`. Repository access: only the project repos.
   Permissions: Contents RW, Pull requests RW, Metadata R. Suggested expiry: 90 days.
2. The app validates the token itself (`GET https://api.github.com/user`) and stores it in the device secret store.
3. The app calls **`PUT /v1/integrations/github/token`** in the background. A failure never blocks the user.
4. On disconnect, the app removes its copy and calls **`DELETE /v1/integrations/github`**. This endpoint already exists.
   It must also delete the stored token.

## `PUT /v1/integrations/github/token`

Auth: `Authorization: Bearer <BAU session token>` (same as every `/v1` call). HTTPS only.

Request (`application/json`; ignore unknown fields):

```json
{ "token": "github_pat_…", "login": "octocat" }
```

`login` is what the app saw. It is a hint only. **The server never trusts it** and uses the login GitHub returns.

Server steps:

1. **Validate with GitHub:** `GET https://api.github.com/user` with `Authorization: Bearer <token>`,
   `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`.
   - 401 → reject (`github_token_invalid`).
   - Read `login` and `id` from the response. Store the numeric `id` as the stable key, because logins can be renamed.
2. **Organization membership:** `GET https://api.github.com/orgs/bau-build/members/{login}`, using the org agent's
   own credentials (a GitHub App installation token). 204 means the user is a member.
   - Not a member → still store the token, but return `"org_member": false`. The app can then tell the student to
     accept the org invite. Optionally, the server sends the invite itself (`POST /orgs/bau-build/invitations`).
3. **Token permissions (best effort):** fine-grained PATs do not list their scopes. Probe them instead.
   `GET /repos/bau-build/{repo}` for a project the user is In → 200 means the token can read the repo.
   404 or 403 means the token is not approved yet, or the repo is not selected → `"repo_access": "pending"`.
   Do not reject: an org approval can arrive later.
4. **Uniqueness:** one GitHub `id` per BAU BUILD user. If another user already holds that GitHub id → `409 github_account_in_use`.
5. **Store:** `github_user_id`, `github_login`, `github_token_ciphertext`, `token_last4`, `validated_at`, and
   `expires_at` (from the `github-authentication-token-expiration` response header, if present). Replace any
   existing row. This endpoint is idempotent: the app may call it again on later launches.

Responses (all errors use the standard envelope `{"error":{"code","message"}}`):

| Status | `code` | Meaning |
|---|---|---|
| 200 | – | `{"connected":true,"login":"octocat","org_member":true,"repo_access":"ok"\|"pending","expires_at":"…"\|null}` |
| 400 | `invalid_request` | missing/blank `token` |
| 401 | `unauthorized` | no or expired BAU session |
| 409 | `github_account_in_use` | GitHub account linked to another BAU BUILD user |
| 422 | `github_token_invalid` | GitHub rejected the token (401 from `/user`) |
| 502 | `github_unavailable` | GitHub down or rate-limited; the app retries on the next launch |

The client relies **only on status codes and `code`**, never on `message` wording. It parses the response leniently,
and extra fields are fine. Today the app treats any 2xx as linked. A 404 or 405 is silent and retried on the next
launch. Anything else shows "Couldn't link GitHub to your BAU BUILD account. We'll retry." and is retried on the next launch.

`GET /v1/integrations/github` should report the same shape (`connected`, `login`, `org_member`, `repo_access`,
`expires_at`) and never the token.

## Storage requirements

- **Encrypt at rest** with an AEAD such as AES-256-GCM, a random nonce per value, and a key from a KMS or secret
  manager. The key never lives in the database or the repo. Use envelope encryption if possible. Bind the user id as
  associated data, so a ciphertext copied to another row will not decrypt.
- Never log the token. Never return it in any response. Log only `token_last4`.
- Decrypt only inside the agent or GitHub call path. Keep the plaintext in memory for as short a time as possible.
- On `DELETE /v1/integrations/github`, or when GitHub returns 401 for the token: delete the ciphertext and mark the user
  disconnected. Best effort: revoke the token with the
  [credential revocation API](https://docs.github.com/en/rest/credentials/revoke) (`POST /credentials/revoke`).
- Rate-limit the PUT (for example, 10 per user per hour).

```go
// Sketch: AES-256-GCM with the user id as associated data.
func sealToken(key []byte, userID, token string) ([]byte, error) {
    block, err := aes.NewCipher(key) // key from KMS/secret manager, 32 bytes
    if err != nil { return nil, err }
    gcm, err := cipher.NewGCM(block)
    if err != nil { return nil, err }
    nonce := make([]byte, gcm.NonceSize())
    if _, err := rand.Read(nonce); err != nil { return nil, err }
    return gcm.Seal(nonce, nonce, []byte(token), []byte(userID)), nil
}
```

## How the org agent uses it

- The **agent is a GitHub App** installed on the `bau-build` org. Its own installation token does the org-level work.
  Student PATs never do org-level work.
- Subscribe to `push` and `pull_request` webhooks. Verify `X-Hub-Signature-256` on every one.
- **Attribution:** map `sender.id` (and the commit author/committer ids) to the BAU BUILD user through `github_user_id`.
  A push from an unknown GitHub account → the check fails with "Link GitHub in the BAU BUILD app".
- **Branch rules:** students push only to `task/<taskId>-<slug>`. The app creates these branches. The agent checks
  that the pusher is In on the project and assigned to that task. If not, it fails the check, and can revert or
  delete the branch.
- **Branch protection / rulesets** on every project repo: `main` requires a PR, the agent's status check, and
  review from the lead or professor. No force pushes, no deletions. The agent is the only bypass actor.
- Run checks (tests, lint, AI review) and post a commit status or check run with the App's token. Mirror the results
  into `GET /v1/projects/{id}/changes`.
- The stored student PAT is optional for the agent. Use it only for actions that must appear as the student. If it
  gets a 401, treat the student as disconnected.
- **Org token policy:** under Organization settings → Personal access tokens, allow fine-grained tokens and
  **require approval**. Students see "pending approval" until an admin approves (the app explains this). Set the
  maximum lifetime to 90 days or less. When a token expires, GitHub returns 401, the app asks the student to connect
  again, and the PUT runs again.
