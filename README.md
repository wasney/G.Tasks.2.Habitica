# HabitSync: Habitica ↔ Google Tasks Two-Way Sync (Android APK)

A self-contained, multi-user Android application built with **Kotlin**, **Jetpack Compose**, **Room**, **Retrofit**, and **Android WorkManager**. Designed to be shared with friends and family so each user can sign into their own **Habitica** and **Google Tasks** accounts directly on their phone—with zero shared servers and no PC required.

---

## Key Features

1. **Multi-User On-Device Authentication (`EncryptedSharedPreferences`)**
   - **Habitica Sign-In:** Users can log in with their **Habitica Username/Email & Password** (calls `POST https://habitica.com/api/v3/user/auth/local/login`) OR paste their personal **User ID & API Token** (from `Settings -> Site Data / API` if they use Google/Apple login on Habitica).
   - **Google Tasks Sign-In:** Native Android Google Account Picker (`GoogleSignIn` / OAuth 2.0 scope `https://www.googleapis.com/auth/tasks`) lets each user connect their own Google account and select which Google Tasks lists map to **Habitica To-Dos** and **Habitica Dailies**.

2. **Full Two-Way Sync (To-Dos, Dailies, Notes, Due Dates, and Subtasks/Checklists)**
   - **Habitica To-Dos ↔ Google Tasks:** Bi-directional creation, title & notes editing, due date syncing, and completion scoring.
   - **Habitica Dailies ↔ Google Tasks:** Maps repeating Habitica Dailies to a user-chosen Google Tasks list (e.g., `"Habitica Dailies"`). When a Daily is checked off in Google Tasks on your phone, it scores the Daily in Habitica (`POST /api/v3/tasks/{id}/score/up`). When Habitica's daily cron resets the Daily for the next day, the app automatically unchecks the corresponding Google Task and advances its due date!
   - **Full Subtasks & Checklists Sync:** Maps Habitica `checklist` items (`POST /api/v3/tasks/{id}/checklist`, `PUT /api/v3/tasks/{id}/checklist/{itemId}`, `POST /api/v3/tasks/{id}/checklist/{itemId}/score`) to native indented Google Subtasks (`parent` field in Google Tasks API) or Markdown `[x]`/`[ ]` lines in the task notes.

3. **User-Configurable Sync Settings Screen**
   - **Sync Frequency (`WorkManager`):** Every 15 minutes, 30 minutes, 1 hour, 2 hours, 6 hours, or Manual "Sync Now" only.
   - **Sync Direction:** Two-Way Bi-Directional (`TWO_WAY`), Google Tasks → Habitica Only (`GOOGLE_TO_HABITICA`), or Habitica → Google Tasks Only (`HABITICA_TO_GOOGLE`).
   - **Deletion Policy:** Mirror Deletion Both Ways (`DELETE_BOTH`), Safe Unlink & Keep Counterpart (`UNLINK_KEEP`), Mark Counterpart Completed (`MARK_COMPLETED`), or Re-create Deleted Task (`RECREATE`).
   - **Conflict Resolution:** Most Recently Updated Wins (`NEWEST_WINS`), Habitica Always Wins (`HABITICA_WINS`), or Google Tasks Always Wins (`GOOGLE_WINS`).
   - **Task Scope:** Both To-Dos & Dailies (`TODOS_AND_DAILIES`), To-Dos Only (`TODOS_ONLY`), or Dailies Only (`DAILIES_ONLY`).
   - **Subtask Mode:** Native Google Subtasks (`NATIVE_SUBTASKS`), Notes Markdown Checklist (`NOTES_MARKDOWN`), or Disabled (`DISABLED`).

---

## How to Get Your `.apk` Without Installing Android Studio (GitHub Actions)

This repository includes an automated GitHub Actions build pipeline (`.github/workflows/build-apk.yml`):

1. Create a free repository on [GitHub](https://github.com/new) (can be Public or Private).
2. Upload/push the contents of this folder to your repository.
3. Click the **Actions** tab in your GitHub repository.
4. Click the latest **Build HabitSync Android APK** workflow run and download **`HabitSync-debug-apk`** from the **Artifacts** section at the bottom of the summary page.
5. Unzip the artifact to get `app-debug.apk`, transfer it to your Android phone (or share it with friends/family via Google Drive/Discord/messages), and tap to install!

---

## One-Time Google Cloud Setup for Sharing with Friends & Family

Because Google requires OAuth verification for any Android app requesting the `https://www.googleapis.com/auth/tasks` scope:

1. Go to the [Google Cloud Console](https://console.cloud.google.com/) and create a free project (e.g., `HabitSync-Family`).
2. Navigate to **APIs & Services → Library**, search for **Google Tasks API**, and click **Enable**.
3. Go to **APIs & Services → OAuth consent screen**:
   - Select **External** user type (free) and fill in the app name (`HabitSync`) and your email.
   - Add the scope `https://www.googleapis.com/auth/tasks`.
   - Under **Test users**, click **+ Add Users** and enter your Gmail address plus the Gmail addresses of your friends and family (supports up to 100 users without needing Google app review).
4. Go to **APIs & Services → Credentials → + Create Credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.habitsync.gtasks`
   - SHA-1 certificate fingerprint: Paste the SHA-1 fingerprint printed in the GitHub Actions build log step `"Print Debug Keystore SHA-1 Fingerprint"` (or from `./gradlew signingReport` if building locally).
