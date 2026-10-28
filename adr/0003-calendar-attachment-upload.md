# Calendar attachment upload (experimental)

An opt-in internal API uploads calendar attachments to the authenticated
uploader's Tdrive. Sabre replaces inline BASE64 with the returned URI before
scheduling. The frontend and Thunderbird can then display ordinary URI
attachments.

## Configuration

In `configuration.properties`:

```properties
attachment.api.enabled=true
attachment.api.secret=<dedicated-service-secret>
attachment.tdrive.accounts.file=/root/conf/tdrive-accounts.json
```

The API is disabled by default. It is a `JMAPRoutes` route on the existing
`CalendarRestApiServer`, using the same port as the other REST endpoints; no
separate attachment server or port is started. Use HTTPS or a protected transport
between services.
The Bearer secret authenticates Sabre; it is separate from all Drive tokens.

The protected accounts file maps **OpenPaaS user IDs** to individual Drive
credentials, provisioned by an operator for the prototype:

```json
{
  "<user-id>": {
    "instanceUrl": "https://user.twake.example",
    "driveUrl": "https://user-drive.twake.example",
    "accessToken": "<user-scoped-access-token>"
  }
}
```

Grant the token file upload, metadata read and file sharing rights. Never supply
the Cozy administration credential. Unknown users are rejected. Configuration
loads at startup; token rotation currently requires a restart. URLs must be HTTPS
origins; HTTP loopback origins are accepted for local tests.

Configure Sabre with:

```properties
CALDAV_BINARY_ATTACHMENT_MODE=upload
CALDAV_ATTACHMENT_UPLOAD_ENDPOINT=http://side-service:8080/attachments
CALDAV_ATTACHMENT_UPLOAD_SECRET=<same-service-secret>
```

Use the configured Calendar REST API port in the endpoint above. Sabre keeps
its existing per-file JSON request/response contract. This endpoint does not
accept or rewrite a complete ICS document.

If `TRUSTED_ATTACH_URL_BASE` is configured, it must accept the public Drive URLs.

## Contract

`POST /attachments`, with `Authorization: Bearer <service-secret>`:

```json
{
  "userId": "<authenticated-user-id>",
  "calendarPath": "calendars/<user-id>/<calendar-id>",
  "eventUid": "<event-uid>",
  "filename": "report.pdf",
  "contentType": "application/pdf",
  "contentBase64": "<base64>"
}
```

The authenticated Sabre tenant supplies `userId`, rather than the ICS ORGANIZER.
The API returns `uri`, `filename`, `contentType`, `size` and `sha256`. A file is
limited to 5 MiB; Sabre limits an event to 10 MiB decoded content and 20 files.
Filenames must fit 160 UTF-8 bytes without path separators or control characters.
Reverse proxies must allow the larger BASE64 request body.

The service uploads raw bytes with `Content-MD5`, verifies returned size/hash,
and creates a permanent single-file sharing permission with `GET` only. The URI
is the Drive `/public?sharecode=...#/` page, matching the frontend picker.
Anyone possessing that link can read the file; it is not restricted to attendees.

Retries reuse a deterministic file name scoped to uploader, calendar, event UID,
filename, MIME type and content. Existing content is checked before reuse; an
existing permanent read-only share is reused when found. No automatic retry is
performed on file creation. Upload/share failure aborts the event save.

## Implementation

`AttachmentUploadRoute` authenticates Sabre and delegates to the provider-neutral
`AttachmentStorageClient` interface. Its `Upload` and `StoredAttachment` records
define the shared contract. `AttachmentUploadModule` registers the route in the
existing REST server and binds the client to `TdriveAttachmentStorageClient`.

`AttachmentUploadConfiguration` contains the API enable flag and service secret.
`TdriveAttachmentStorageConfiguration` holds the provider's account mapping and
is loaded only when the API is enabled. The route does not depend on Tdrive
accounts, HTTP responses or sharing codes; another client implementation can be
bound without changing the route. Tdrive uploads and creates/reuses read-only
shares, then constructs the public URI.

## Prototype limits

- Production needs a per-user credential provider with token refresh or a scoped
  token broker. Calendar's backend secret or a user's email cannot authorize a
  Drive upload; the inspected Drive OAuth endpoint has no `client_credentials`
  grant. Browser token exchange depends on a valid OIDC session and deployment
  configuration.
- Drive upload and DAV save are not one transaction. Partial failures can leave
  files; retry reuses them, but abandoned requests need a cleanup policy. Deleting
  an event does not delete the Drive file or revoke its sharing link.
- Concurrent duplicate requests can create duplicate sharing permissions. A
  distributed idempotency ledger and reconciliation remain production work.
- Only authenticated user PUTs are offloaded. Technical users and inbound iTIP
  binary attachments are rejected. URI-only events keep existing behavior.
- Files currently go to the user's Drive root with a deterministic prefix.
  Folder organization, shared-calendar ownership and attendee permission rules
  require product decisions before enabling this outside a test environment.

## Validation

Run the focused Java tests with system Maven:

```sh
mvn -pl calendar-rest-api -am package -Dtest=AttachmentUploadRouteTest,AttachmentUploadModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Focused API tests run the actual `CalendarRestApiServer` with an HTTP Drive
fixture and also exercise an alternate storage implementation, disabled mode
and Guice wiring. The separate October 22 Docker probe also
exercised real Sabre PUT/GET, Mongo persistence, a published Cozy Stack image,
two file uploads, share-code downloads, exact byte/SHA-256 comparison and retry.
That probe did not build the inspected Linagora Cozy fork or install the Drive
web UI; production OAuth, attendee delivery and UI rendering are not covered by
that result.
