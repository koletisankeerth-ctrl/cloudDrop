# CloudDrop Backend

A file-sharing backend: register, log in, upload files, download them, and share them through time-limited links.
Built with Java 21, Spring Boot 3.3.5, Spring Data JPA and MySQL.

> ## ⚠️ Learning implementation, not production security
> This project deliberately does **not** use Spring Security. Authentication (BCrypt + JWT + a servlet filter) is
> hand-built so you can see how it works. Without a mature framework, authorization and security infrastructure
> must be implemented and reviewed carefully, and this code has **not** had a professional security review.
> Do not expose it to the internet as-is. See [Known gaps](#known-gaps-and-limitations).

## Contents
1. [How it works](#how-it-works)
2. [Prerequisites](#prerequisites)
3. [Configuration](#configuration)
4. [Run it](#run-it)
5. [API reference](#api-reference)
6. [Verify with Swagger UI](#verify-with-swagger-ui)
7. [Verify with Postman](#verify-with-postman)
8. [Running the tests](#running-the-tests)
9. [Security design](#security-design)
10. [How partial failures are handled](#how-partial-failures-are-handled)
11. [Known gaps and limitations](#known-gaps-and-limitations)
12. [Common mistakes](#common-mistakes)
13. [Moving to AWS S3 later](#moving-to-aws-s3-later)

## How it works

```
HTTP request
  -> JwtAuthFilter            default-deny: needs "Authorization: Bearer <jwt>" unless the URL is on the public list
       valid token -> AuthContext (ThreadLocal) holds the caller's identity; ALWAYS cleared in finally
  -> Controller               HTTP only: parse input, call a service, shape the response
  -> Service                  business rules and ownership checks
  -> Repository (MySQL)  and  FileStorageService (local disk)
  -> GlobalExceptionHandler   turns any exception into one consistent JSON error
```

| Package (`com.clouddrop`) | Responsibility |
|---|---|
| `controller` | `AuthController`, `FileController`, `ShareController`, `HealthController` |
| `service`, `service.impl` | `UserService`, `FileService`, `ShareService` and their implementations |
| `repository` | Spring Data JPA interfaces |
| `entity` | `User`, `StoredFile`, `ShareLink` (never returned to clients) |
| `dto` | Request and response records |
| `auth` | `JwtService`, `PasswordHasher`, `AuthContext`, `AuthenticatedUser` |
| `filter` | `JwtAuthFilter` (Jakarta servlet filter) |
| `storage` | `FileStorageService` interface and `LocalFileStorageService` |
| `exception` | Custom exceptions and `GlobalExceptionHandler` |
| `config` | `AppProperties`, `FilterConfig`, `OpenApiConfig` |
| `util` | `FilenameSanitizer`, `TokenHasher` |

## Prerequisites
- JDK 21
- Maven 3.9+
- MySQL 8.x, running locally, with a user that may create the `clouddrop` database
  (or create it yourself: `CREATE DATABASE clouddrop CHARACTER SET utf8mb4;`)

## Configuration

Settings come from **environment variables**. There are no defaults for credentials or secrets, so the app
**refuses to start** if `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` or `UPLOAD_DIR` is missing,
or if `JWT_SECRET` is shorter than 32 characters.

| Variable | Required | Example | Meaning |
|---|---|---|---|
| `DB_URL` | yes | `jdbc:mysql://localhost:3306/clouddrop?createDatabaseIfNotExist=true&serverTimezone=UTC` | JDBC URL |
| `DB_USERNAME` | yes | `clouddrop_app` | MySQL user |
| `DB_PASSWORD` | yes | *(your password)* | MySQL password |
| `JWT_SECRET` | yes | *(32+ random characters)* | Signs JWTs. Anyone who knows it can forge logins. |
| `JWT_EXPIRATION_HOURS` | no (24) | `24` | Token lifetime |
| `UPLOAD_DIR` | yes | `C:/clouddrop-data/uploads` | Where file bytes are stored. Keep it **outside** the project folder. |

Fixed in `application.yml`: port `8080`, max upload size `5GB`, `ddl-auto: validate`, `sql.init.mode: always`.

Generate a secret in PowerShell:
```powershell
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }) -as [byte[]])
```

`.env.example` lists the variables. **Spring Boot does not read `.env` files by itself.** Set real environment
variables (below) or put them in your IDE's run configuration. `.env` is git-ignored; never commit it.

## Run it

PowerShell, from the project folder:
```powershell
$env:DB_URL="jdbc:mysql://localhost:3306/clouddrop?createDatabaseIfNotExist=true&serverTimezone=UTC"
$env:DB_USERNAME="root"
$env:DB_PASSWORD="<your MySQL password>"
$env:JWT_SECRET="<32+ random characters>"
$env:UPLOAD_DIR="C:/clouddrop-data/uploads"
mvn spring-boot:run
```
Variables last only for that PowerShell window. Success looks like `Started CloudDropApplication` in the log.

On every startup `schema.sql` runs first (it uses `CREATE TABLE IF NOT EXISTS`, so it is safe to repeat), then Hibernate
**validates** that the entities match the tables. Hibernate never creates or alters tables. If you change an entity,
change `schema.sql` to match, and migrate existing databases yourself (`IF NOT EXISTS` will not alter an existing table).

**After changing code, restart the app.** A running instance does not pick up new endpoints. (A `404 No static resource`
for a URL that should exist usually means a stale process.)

## API reference

Base URL `http://localhost:8080`. Private endpoints need the header `Authorization: Bearer <token>`.

| Method and path | Auth | Purpose | Success |
|---|---|---|---|
| `GET /api/health` | public | Liveness check | 200 |
| `POST /api/auth/register` | public | Create an account | 201 |
| `POST /api/auth/login` | public | Exchange email and password for a JWT | 200 |
| `GET /api/auth/me` | JWT | Current user | 200 |
| `POST /api/files` | JWT | Upload (multipart, part name `file`) | 201 |
| `GET /api/files?page=0&size=20` | JWT | List your files, newest first (size max 100) | 200 |
| `GET /api/files/{id}` | JWT | File metadata | 200 |
| `GET /api/files/{id}/download[?inline=true]` | JWT | Stream the file | 200 |
| `DELETE /api/files/{id}` | JWT | Delete file and its share links | 204 |
| `POST /api/files/{id}/shares` | JWT | Create a share link (optional `{"expiresInMinutes": 1-43200}`, default 1440) | 201 |
| `GET /api/shares/{token}` | public | Shared file's metadata | 200 |
| `GET /api/shares/{token}/download[?inline=true]` | public | Stream the shared file | 200 |

`/api/shares/{token}/download` goes beyond the originally listed endpoints: a share link that cannot deliver the file is of little use.

**Request bodies**
```jsonc
// POST /api/auth/register
{"username": "Alice", "email": "alice@example.com", "password": "password123"}   // password 8-72 chars
// POST /api/auth/login
{"email": "alice@example.com", "password": "password123"}
// POST /api/files/{id}/shares  (body optional)
{"expiresInMinutes": 30}
```
Login returns `{"token": "...", "tokenType": "Bearer", "expiresAt": "...", "user": {...}}`.

**Errors** always have this shape:
```json
{"timestamp": "...", "status": 403, "error": "Forbidden", "message": "You do not have access to this file",
 "path": "/api/files/1", "fieldErrors": {"email": "must be a well-formed email address"}}
```
(`fieldErrors` appears only for validation failures.)

| Status | Meaning here |
|---|---|
| 400 | Validation failure, empty file, malformed JSON, bad parameter |
| 401 | Missing, invalid or expired token; wrong login credentials |
| 403 | Authenticated, but the file belongs to someone else |
| 404 | Unknown file, unknown share token, or file bytes missing on disk |
| 405 / 415 | Wrong HTTP method / wrong content type |
| 409 | Email already registered |
| 410 | Share link expired or revoked |
| 413 | File over the size limit |
| 500 | Unexpected; details are logged server-side only |

## Verify with Swagger UI

1. Open `http://localhost:8080/swagger-ui.html`.
2. `POST /api/auth/register`, then `POST /api/auth/login`. Copy `token` from the response.
3. Click **Authorize** (top right), paste the token **without** the word `Bearer`, and confirm.
4. Walk through the endpoints in the table above. For `POST /api/files`, use the file picker on the `file` field.

## Verify with Postman

- **Auth:** on each private request, open the **Authorization** tab, choose **Bearer Token** and paste the token.
  Tip: set the collection's Bearer Token to `{{token}}` and add this post-response script to the login request:
  `pm.collectionVariables.set("token", pm.response.json().token);`
- **Upload:** `POST /api/files`, Body, **form-data**, key `file` (change its type from Text to **File**), choose a file.
  Do not set a `Content-Type` header yourself.
- **View a PDF:** `GET /api/files/{id}/download?inline=true`, then use the **Preview** tab, or **Send and Download** to save it.
- **Share:** `POST /api/files/{id}/shares` (JSON body optional). Copy the returned `token` (it is shown only once).
  Then `GET /api/shares/{token}/download?inline=true` with **no Authorization**. This also works in a normal browser tab.

**Checklist**

| Check | Expected |
|---|---|
| `GET /api/health` | 200 `{"status":"UP"}` |
| Register the same email twice | 201, then 409 |
| Login with a wrong password | 401 |
| `GET /api/files` with no token | 401 |
| Upload, list, metadata, download | Bytes identical to the original |
| Second user requests the first user's file | 403 |
| Upload a 0-byte file | 400 |
| Share link before and after expiry | 200, then 410 |
| `GET /api/shares/garbage` | 404 |
| `DELETE /api/files/{id}` twice | 204, then 404 |

## Running the tests
```powershell
mvn test
```
- **Unit tests** (Mockito): services, JWT, filter, storage, helpers.
- **Web-layer tests** (`@WebMvcTest`): controllers and JSON error shapes.
- **Integration tests** (`com.clouddrop.integration`): the whole app against in-memory **H2 in MySQL mode**, with real
  filter, services, JPA, schema validation and local-disk storage. No MySQL or environment variables needed.

H2 is not MySQL. Always also run the app against real MySQL (the steps above) before trusting a change.

## Security design

| Area | What the code does |
|---|---|
| Passwords | BCrypt (`at.favre.lib`, cost 12), no Spring Security. Limited to 72 bytes (a BCrypt limit). |
| Login | Same 401 message for unknown email and wrong password; a dummy hash check keeps timing similar. |
| JWT | HS256. Checks signature, algorithm, issuer, expiry, and that `sub` (numeric) and `email` exist. Unsigned (`alg: none`) tokens are rejected. |
| Identity | Comes only from the validated JWT, through `AuthContext`. User ids, owner ids and headers from the client are ignored. |
| Filter | **Default-deny**: everything needs a token except a short exact whitelist. `AuthContext` is cleared in `finally` on every request. |
| Ownership | The service checks the owner before opening bytes or deleting. |
| Share links | 256-bit `SecureRandom` token, only its SHA-256 hash is stored, server-side expiry, revocation checked, cascade-deleted with the file. |
| Uploads | Storage key is a server-generated UUID; the client filename is metadata only. UUID-only key check plus a path-containment check. `CREATE_NEW` never overwrites. |
| Downloads | `attachment` by default; `inline` only for PDF, PNG, JPEG, GIF, WebP, plain text. `nosniff` and `no-store` headers. |
| Errors | One JSON shape. Unexpected errors return a generic 500; details go to the server log only. |
| Secrets | Environment variables only; startup fails if they are missing or weak. `.env` is git-ignored. |

## How partial failures are handled

Upload (`FileServiceImpl.upload`, deliberately **not** transactional so a huge upload doesn't hold a DB connection):
1. Bytes are streamed to disk. If this fails (too large, disconnect, disk error), storage deletes its partial file and nothing reaches the DB.
2. The metadata row is saved. If this fails, the service deletes the file it just wrote and rethrows.
3. **Remaining gap:** if the process is killed between steps 1 and 2, an orphan file stays on disk. It is unreachable (UUID name, no row), but wastes space. A periodic job comparing the folder to the `files` table would clean this up; it is not built.

Delete: the DB row is removed first (share links cascade), then the bytes. If the disk delete fails, the request still succeeds
and an `ERROR` log names the leftover key. This is safer than the reverse order, which could leave a row pointing at missing bytes.

If a row exists but its bytes are missing, download returns 404 and logs an error.

## Known gaps and limitations

- **No rate limiting or lockout:** login and public share endpoints can be hammered or brute-forced.
- **No token revocation, logout or refresh:** a stolen JWT works until it expires (24h by default). A deleted account's token is rejected by `/me` and by
  upload; other endpoints simply find no data for that user.
- **No share-link revoke endpoint:** `revoked_at` is enforced, but only settable in SQL today.
- **No quotas:** one user can fill the disk. Tomcat also spools uploads to the system temp folder, so large uploads need free space there too.
- **No HTTPS, CORS or security headers beyond the download ones:** terminate TLS in front of the app; add CORS before using a browser frontend on another origin.
- **403 vs 404:** another user's file returns 403, which reveals that the id exists.
- **Swagger UI is public:** disable it (`springdoc.swagger-ui.enabled=false`, `springdoc.api-docs.enabled=false`) outside development.
- **Client-supplied content type is untrusted:** we never trust it for inline display beyond the allow-list.
- **No malware scanning** of uploads, and no `Range` (resume/seek) support for downloads.
- **JDBC connection is unencrypted** in the sample URL; use TLS to the database in real deployments.
- **Single directory storage:** all files sit in one folder; fine for learning, not for millions of files.

## Common mistakes

| Symptom | Likely cause |
|---|---|
| `Could not resolve placeholder 'DB_URL'` (or similar) | Environment variable not set in this terminal window |
| `Communications link failure` / `Access denied` | MySQL not running, or wrong credentials |
| Startup error about `secret` | `JWT_SECRET` shorter than 32 characters |
| `Schema-validation: wrong column type` | `schema.sql` and an entity disagree; an old table also won't be altered by `IF NOT EXISTS` |
| `401` right after login | Token pasted with quotes or with a `Bearer ` prefix in Postman's Token box |
| `400` on upload | Form key isn't exactly `file`, or its type is still Text |
| `404 No static resource ...` | App wasn't restarted after a code change |
| Upload works in tests but fails locally | `UPLOAD_DIR` not writable, or inside OneDrive/the project folder |
| A browser address-bar request to `/api/files/..` gives 401 | Browsers can't send the header from the address bar; use Postman, `fetch`, or a share link |

## Moving to AWS S3 later

Business code only talks to the `FileStorageService` interface (`store`, `open`, `delete`). To add S3:
1. Write `S3FileStorageService implements FileStorageService`. Use a multipart or streaming upload so large files aren't buffered in memory.
2. Select the implementation with a property or Spring profile, and move `LocalFileStorageService` behind it.
3. Keep keys as server-generated UUIDs. Put credentials in the environment or an IAM role, never in code.
No service, controller or DB change should be needed.
