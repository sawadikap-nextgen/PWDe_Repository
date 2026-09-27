# Firebase setup (accounts and cloud sync)

PWDe works fully as a guest. With Firebase configured, users can sign in with email and password, and
their calibration profiles, game profiles, working controls, app settings and added games sync
through Cloud Firestore.

## 1. Create the project

1. Create a project in the [Firebase console](https://console.firebase.google.com/).
2. Add an **Android app** with package name `com.pwde.app`.
3. **Authentication → Sign-in method:** enable **Email/Password**.
4. **Firestore Database:** create a database (production mode).

## 2. Configure the app

From the Android app's settings in the console (or its `google-services.json`), copy these into
`Android-App/local.properties`. That file is not committed.

```properties
pwde.firebase.apiKey=AIza...            # client.api_key.current_key
pwde.firebase.appId=1:1234567890:android:abc123   # client.client_info.mobilesdk_app_id
pwde.firebase.projectId=your-project-id  # project_info.project_id
```

All three are needed for sync, since Firestore needs the project id. Without them the app runs
guest-only, and with no project id it signs in but shows "Cloud sync isn't set up in this build".

## 3. Deploy the security rules

From this folder, with the [Firebase CLI](https://firebase.google.com/docs/cli):

```bash
firebase deploy --only firestore --project your-project-id
```

The rules in `firestore.rules` let each signed-in user read and write only `users/{their uid}/…`.

## Data layout

| Path | Contents |
| --- | --- |
| `users/{uid}` | email, display name, last sync time |
| `users/{uid}/calibrationProfiles/{id}` | a saved calibration (cursor/joystick tuning, gestures, voice options) |
| `users/{uid}/gameProfiles/{id}` | a game's button mappings; `calibrationRemoteId` links its calibration |
| `users/{uid}/state/controls` | the working controls; `activeCalibrationRemoteId` links the active calibration |
| `users/{uid}/state/settings` | app settings (appearance, input mode, read-aloud, setup progress) |
| `users/{uid}/state/customGames` | names of games the user added |

## How sync behaves

- **When:** on sign-in, a few seconds after any local change while signed in, and from **Sync now**
  on the Profile screen.
- **Conflicts:** each record keeps whichever copy changed last (`updatedAt`). Added games are merged
  as a union.
- **Deletes:** deleting a synced profile marks its document `deleted: true` (a tombstone), so other
  phones remove it too. They keep it if it was edited there after the delete.
- **Stays on the phone:** game screenshots/thumbnails, the PWDe on/off switch, the "I use another
  screen reader" setting, and in-progress GabAI sessions.
- **Switching accounts:** profiles on the phone are uploaded to the new account as new records.
  Signing out never deletes anything locally.
