<div align="center">

# 🔔 NOTIFICATION HQ
**A Centralized Academic Information System**

*Transforming communication chaos into structured clarity for students and faculty.*

![Kotlin](https://img.shields.io/badge/kotlin_1.9-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android_SDK_34-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![SQLite](https://img.shields.io/badge/Room_DB-%2307405e.svg?style=for-the-badge&logo=sqlite&logoColor=white)
![Gemini AI](https://img.shields.io/badge/Gemini_2.5_Flash-8E75B2?style=for-the-badge&logo=googlebard&logoColor=white)
![Status](https://img.shields.io/badge/Status-Active_Prototype-success.svg?style=for-the-badge)

</div>

---

## 📖 Overview

Academic communication in engineering institutions is often afflicted by systemic fragmentation. Critical information—assignment deadlines, internal assessments, and placement drive notifications—is disseminated simultaneously across WhatsApp groups, Gmail, Google Classroom, and institutional LMS portals. 

**NOTIFICATION HQ** is an Android-based background application designed to function as a **Single Source of Truth (SSOT)**. Operating at the Android OS layer, it silently intercepts notification payloads from whitelisted source applications before they are dismissed, transforming a chaotic pull-based environment into a structured push-based system.

---

## ✨ Key Features

* 📥 **Multi-Channel Ingestion:** Intercepts system-level notifications from WhatsApp, Gmail, and Google Classroom via `NotificationListenerService`.
* 🛡️ **Omni-Extractor & Echo Killer (Denoiser):** Strips HTML from LMS emails and uses a 30-message sliding-window composite fingerprint cache (SHA-256) to eliminate duplicate background sync notifications.
* 🔀 **Dynamic Routing Engine:** Applies Fuzzy Token Matching (Jaccard similarity, threshold 0.6) for WhatsApp group routing and Regex-weighted scoring for Gmail sender routing.
* 🧠 **Hybrid Priority Tagging:** Submits notification bodies to the **Gemini 2.5 Flash LLM** to classify them as `URGENT`, `DUE`, or `INFO`, with a deterministic regex fallback if offline.
* 📝 **AI Auto-Summary:** Condenses long, complex faculty emails into three plain-language bullet points on demand.
* 🗂️ **Segregated Information Streams:** Dedicated dashboard tabs for *Academics, Placements, Hostel & Clubs,* and *NPTEL* to prevent context contamination.
* ✅ **Task Confidence Tracker (Active Dues):** A personal checklist of unsubmitted urgent tasks tied to a live Home Screen Widget counter.
* 📴 **100% Offline Capability:** All core aggregation, routing, and SQLite storage functions require zero internet connectivity, perfect for commuting students.

---

## 🏗️ System Architecture

The NOTIFICATION HQ backend is organized as a highly efficient, decoupled four-stage sequential pipeline running on Kotlin Coroutines (`Dispatchers.IO`):

1.  **NotificationCapture (The Gatekeeper):** Whitelists packages and extracts raw `StatusBarNotification` bundle data (e.g., `android.title`, `android.text`, `android.bigText`).
2.  **Notification Denoiser:** Normalizes text, decodes HTML entities, and enforces the *Echo Killer* deduplication cache.
3.  **NotificationRouter (The Engine):** Tokenizes strings (removing stop words) and maps incoming payloads to user-defined Dynamic Course Buckets.
4.  **NotificationPipeline (The Orchestrator):** Chains the modules, persists the classified entity into the Android Room database, and updates the MVVM UI via `LiveData`.

---

## 🛠️ Technology Stack

| Layer | Technology / Library |
| :--- | :--- |
| **Language** | Kotlin 1.9 (JVM target 17) |
| **UI Framework** | Android XML Views, ViewBinding, RecyclerView (DiffUtil) |
| **Architecture** | MVVM (Activity/Fragment → ViewModel → Repository → Room DAO) |
| **Background Services** | `NotificationListenerService`, Android WorkManager |
| **Local Database** | Android Room 2.6 (SQLite abstraction) |
| **Concurrency** | Kotlin Coroutines (`Dispatchers.IO`, `Dispatchers.Main`) |
| **AI / NLP Integration** | Google Gemini 2.5 Flash API via HTTPS REST (OkHttp3) |

---

## 🚀 Installation & Setup

### Prerequisites
* Android Studio Ladybug (or newer)
* Gradle 8.x
* Physical Android Device running Android 8.0 (API 26) to Android 14 (API 34). *(Emulators may not accurately simulate organic notification bursts).*
* A Gemini API Key (Google AI Studio)

### Steps
1. **Clone the repository:**
   ```bash
   git clone [https://github.com/your-username/NotificationHQ.git](https://github.com/your-username/NotificationHQ.git)
   ```

2. **Open the project** in Android Studio.

3. **Configure API Keys:**
   Create a `local.properties` file in the root directory and add your Gemini API key:
   ```properties
   GEMINI_API_KEY="your_api_key_here"
   ```

4. **Build and Run** the application on a physical Android device.

5. **Grant Permissions:** Upon first launch, navigate through the onboarding flow to grant "Notification Access" in the Android System Settings.

---

## 🔒 Privacy & Security Note

NOTIFICATION HQ respects user privacy by design. 
* **Local First:** All notification interception, deduplication, and database persistence occur strictly on the local device. No data is stored on a cloud backend.
* **Whitelisting:** The service only processes notifications from `com.whatsapp`, `com.google.android.gm` (Gmail), and `com.google.android.apps.classroom`. All other apps are silently ignored.
* **AI Data:** Only the text bodies of captured notifications are sent to the Gemini API for categorization. This can be disabled to rely entirely on the local Regex fallback engine.

---

## 🗺️ Roadmap (V2 Future Work)

- [ ] **Academic-Placement Sync Calendar:** Plotting extracted academic deadlines against placement events with clash-detection.
- [ ] **Automated Deadline Reminders:** Parsing `DUE` dates using regex to schedule local `AlarmManager` push alerts at T-24h and T-2h.
- [ ] **Intelligent Onboarding:** Auto-suggesting course buckets by scanning existing WhatsApp group names on the device.
- [ ] **On-Device LLM:** Migrating priority classification to the local *Gemini Nano* model via Android ML Kit for 100% offline AI tagging.

---

## 👥 Team Bravo

Developed as part of the Design Thinking and Innovation course at **Rajalakshmi Engineering College**.

* **Jashareen J**
* **Murali Krishna M**
* **Nithun Kumar RS**

---

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
